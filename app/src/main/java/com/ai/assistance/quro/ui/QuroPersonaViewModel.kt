package com.ai.assistance.quro.ui

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ai.assistance.quro.core.QuroChatMessage
import com.ai.assistance.quro.core.network.QuroLlmClient
import com.ai.assistance.quro.core.QuroLlmResult
import com.ai.assistance.quro.core.QuroPersona
import com.ai.assistance.quro.core.QuroVoiceProfile
import com.ai.assistance.quro.core.QuroPersonaRepository
import com.ai.assistance.quro.core.model.QuroModelConfig
import com.ai.assistance.quro.core.model.QuroModelConfigRepository
import com.ai.assistance.quro.core.model.QuroFunctionModelConfigRepository
import com.ai.assistance.quro.core.model.QuroFunctionType
import com.ai.assistance.quro.core.QuroTag
import com.ai.assistance.quro.core.QuroTagRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import android.util.Log
import com.ai.assistance.quro.util.QuroDiag
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 人格卡 ViewModel（原创）：管理人格卡列表、激活状态、增删改，
 * 并提供「AI 人格孵化」——依据名称/描述/标签调用 LLM 生成角色设定等字段。
 *
 * 心跳孵化（v: 自动孵化）：app 级协程遍历全部人格卡，每张卡独立判断是否超过最小间隔，
 * 独立蒸馏、独立写入 incubation 与 lastIncubatedAt，互不共享。
 */
class QuroPersonaViewModel(context: Context) : ViewModel() {
    private val appContext = context.applicationContext
    private val repo = QuroPersonaRepository(appContext)
    private val modelRepo = QuroModelConfigRepository(appContext)
    private val tagRepo = QuroTagRepository(appContext)
    private val client = QuroLlmClient()

    // 心跳依赖在 Application 启动时初始化（app 级、不随 ViewModel 销毁）
    init {
        initHeartbeat(appContext)
    }

    private val _personas = MutableStateFlow(repo.loadAll())
    val personas: StateFlow<List<QuroPersona>> = _personas.asStateFlow()

    private val _activeId = MutableStateFlow(repo.getActiveId())
    private val _activePersona = MutableStateFlow<QuroPersona?>(null)
    val activePersona: StateFlow<QuroPersona?> = _activePersona.asStateFlow()

    /** 手动「AI 孵化」按钮的即时状态（对话框回填用）。 */
    private val _incubating = MutableStateFlow(false)
    val incubating: StateFlow<Boolean> = _incubating.asStateFlow()

    private val _incubateResult = MutableStateFlow<IncubateResult?>(null)
    val incubateResult: StateFlow<IncubateResult?> = _incubateResult.asStateFlow()

    // ── 心跳孵化对外状态（按 persona id） ──
    /** 全局开关状态，UI 绑定；与 QuroChatViewModel 共用 quro_ui 偏好。 */
    val personaHeartbeatEnabled: StateFlow<Boolean>
        get() = heartbeatEnabled
    fun setPersonaHeartbeatEnabled(on: Boolean) = setHeartbeatEnabled(on)

    /** 每卡独立孵化中状态（按 persona id），供 UI 实时显示「孵化中」。 */
    val incubatingStates: StateFlow<Map<String, Boolean>>
        get() = incubatingStatesFlow

    init {
        syncActive()
    }

    fun refresh() {
        _personas.value = repo.loadAll()
        syncActive()
    }

    fun setActive(id: String) {
        repo.setActiveId(id)
        _activeId.value = id
        syncActive()
    }

    fun upsert(p: QuroPersona) {
        repo.upsert(p)
        refresh()
    }

    fun delete(id: String) {
        repo.delete(id)
        if (_activeId.value == id) _activeId.value = ""
        refresh()
    }

    /** 空白人格卡模板（用于新建）。 */
    fun blank(): QuroPersona = QuroPersona(avatarEmoji = "🤖", avatarType = "emoji")

    /** 手动「AI 孵化」按钮（保留）：依据名称/描述/标签生成设定并回填编辑对话框，不自动持久化。 */
    fun incubate(name: String, description: String, tags: List<QuroTag>) {
        // ⚠️ 必须用 Dispatchers.IO：distill → QuroLlmClient.chat 内部 client.newCall(req).execute()
        // 是 OkHttp 同步阻塞网络 I/O，若在 viewModelScope 默认的 Main 调度器上跑会直接 ANR。
        // （v371 已修 maybeAutoIncubate 同款问题；手动「孵化」按钮这条路此前漏改，本次补齐。）
        viewModelScope.launch(Dispatchers.IO) {
            _incubating.value = true
            _incubateResult.value = null
            val r = try {
                withTimeout(INCUBATE_TIMEOUT_MS) { distill(name, description, tags) }
            } catch (e: TimeoutCancellationException) {
                IncubateResult.Error("孵化超时(${INCUBATE_TIMEOUT_MS}ms)")
            }
            _incubating.value = false
            _incubateResult.value = r
        }
    }

    /**
     * 针对单张人格卡的孵化（心跳 + 手动每卡按钮共用）：蒸馏后写入该 persona 的
     * incubation 与 lastIncubatedAt 并持久化——每卡独立更新，不共享。
     */
    fun incubate(persona: QuroPersona) {
        incubatePersona(persona)
    }

    /** 在 Application 中调用：启动全局心跳循环（用 AtomicBoolean 守卫避免重复启动）。 */
    fun startHeartbeat() = QuroPersonaViewModel.startHeartbeat()

    /** 取消全局心跳循环。 */
    fun stopHeartbeat() = QuroPersonaViewModel.stopHeartbeat()

    fun clearIncubateResult() {
        _incubateResult.value = null
    }

    private fun syncActive() {
        val id = _activeId.value
        _activePersona.value = _personas.value.firstOrNull { it.id == id }
    }

    companion object {
        /** app 级协程作用域：进程级，不被配置变更/ViewModel 销毁影响。 */
        private val heartbeatScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val started = AtomicBoolean(false)
        private const val MIN_GAP_MS = 60 * 60 * 1000L        // 每卡至少间隔 1 小时

        private lateinit var hbContext: Context
        private lateinit var hbRepo: QuroPersonaRepository
        private lateinit var hbModelRepo: QuroModelConfigRepository
        private lateinit var hbTagRepo: QuroTagRepository
        private val hbClient = QuroLlmClient()

        private val hbPrefs: SharedPreferences
            get() = hbContext.getSharedPreferences("quro_ui", Context.MODE_PRIVATE)

        private val _heartbeatEnabled = MutableStateFlow(true)
        val heartbeatEnabled: StateFlow<Boolean> = _heartbeatEnabled.asStateFlow()

        private val _incubatingStates = MutableStateFlow<Map<String, Boolean>>(emptyMap())
        val incubatingStatesFlow: StateFlow<Map<String, Boolean>> = _incubatingStates.asStateFlow()

        private var heartbeatJob: Job? = null  // 保留字段兼容（不再使用定时器）

        /** 在偏好就绪后初始化心跳依赖（幂等，可在 ViewModel 与 Application 各调一次）。 */
        fun initHeartbeat(context: Context) {
            hbContext = context.applicationContext
            hbRepo = QuroPersonaRepository(hbContext)
            hbModelRepo = QuroModelConfigRepository(hbContext)
            hbTagRepo = QuroTagRepository(hbContext)
            _heartbeatEnabled.value = hbPrefs.getBoolean("persona_heartbeat", true)
        }

        fun setHeartbeatEnabled(on: Boolean) {
            _heartbeatEnabled.value = on
            hbPrefs.edit { putBoolean("persona_heartbeat", on) }
        }

        /**
         * 心跳孵化启动（仅标记启用，不再启动定时轮询）。
         * 孵化由对话结束事件驱动（[pulse]），而非持续计时器。
         */
        fun startHeartbeat() {
            started.set(true)
        }

        /** 停用心跳孵化。 */
        fun stopHeartbeat() {
            started.set(false)
        }

        /**
         * 对话结束时触发一次心跳孵化扫描（事件驱动，替代旧 15 分钟轮询）。
         * 遍历全部人格卡，对超过最小间隔的执行单次孵化。
         * 由 QuroChatViewModel 在每轮对话结束后调用（maybeAutoIncubate → pulse）。
         */
        fun pulse() {
            if (!started.get()) return
            if (!_heartbeatEnabled.value) return
            hbRepo.loadAll().forEach { p ->
                if (System.currentTimeMillis() - p.lastIncubatedAt > MIN_GAP_MS) {
                    try {
                        incubatePersona(p)
                    } catch (_: Exception) {
                        // 单卡失败不影响其他卡
                    }
                }
            }
        }

        /** #820：孵化调用超时阈值，避免 LLM 卡死导致 UI 久转无响应。 */
        private const val INCUBATE_TIMEOUT_MS = 60_000L

        /** #820：孵化超时/失败时复用 AnrMonitor 同款 Download 双写路径，落诊断报告便于手机端自查。 */
        private fun writeIncubationDiag(p: QuroPersona, msg: String) {
            runCatching {
                val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                val fileName = "incubate_diag_${p.id}_$ts.txt"
                val content = buildString {
                    append("===== 人格孵化诊断 =====\n")
                    append("时间: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}\n")
                    append("人格 id: ${p.id}\n")
                    append("人格名: ${p.name}\n")
                    append("描述: ${p.description}\n")
                    append("问题: $msg\n")
                    append("（由孵化超时/失败触发，与 ANR 监控共用 Download/QuroAI_logs 双写路径，无需 adb 即可在文件管理器取阅）\n")
                }
                QuroDiag.writeFile(fileName, content)?.let { Log.w("PersonaIncubate", "孵化诊断已双写Download: $it") }
            }.onFailure { Log.w("PersonaIncubate", "孵化诊断双写失败: ${it.message}") }
        }

        /** 单卡孵化：在心跳作用域内执行，蒸馏后独立持久化 incubation + lastIncubatedAt。 */
        private fun incubatePersona(p: QuroPersona) {
            if (_incubatingStates.value[p.id] == true) return
            heartbeatScope.launch {
                _incubatingStates.value = _incubatingStates.value + (p.id to true)
                try {
                    val r = withTimeout(INCUBATE_TIMEOUT_MS) {
                        distill(p.name, p.description, hbTagRepo.resolve(p.tags))
                    }
                    if (r is IncubateResult.Success) {
                        val now = System.currentTimeMillis()
                        // 重新读取最新记录，避免覆盖并发编辑；每卡独立写入
                        val base = hbRepo.loadAll().firstOrNull { it.id == p.id } ?: p
                        val memo = buildIncubationMemo(base.name, r)
                        hbRepo.upsert(
                            base.copy(
                                incubation = if (base.incubation.isBlank()) memo else "${base.incubation}\n$memo",
                                lastIncubatedAt = now,
                            ),
                        )
                    } else if (r is IncubateResult.Error) {
                        writeIncubationDiag(p, "孵化返回错误: ${(r as IncubateResult.Error).message}")
                    }
                } catch (e: TimeoutCancellationException) {
                    writeIncubationDiag(p, "孵化超时(${INCUBATE_TIMEOUT_MS}ms)，疑似 LLM 调用卡死，已中止该卡孵化")
                } catch (_: Exception) {
                    // 单卡失败静默
                } finally {
                    _incubatingStates.value = _incubatingStates.value + (p.id to false)
                }
            }
        }

        /** 通用蒸馏：调用 LLM 生成人格设定，返回结构化结果（不持久化）。
         *  含自动重试：首次失败等 3 秒重试一次（覆盖瞬时网络抖动）。 */
        private suspend fun distill(name: String, description: String, tags: List<QuroTag>): IncubateResult {
            val cfg: QuroModelConfig = hbModelRepo.load()
            if (cfg.apiKey.isBlank()) {
                return IncubateResult.Error("请先在「模型」设置中填写 API Key")
            }
            val prompt = buildIncubatePrompt(name, description, tags)
            // 功能模型配置接入引擎：人格蒸馏使用 PERSONA_INCUBATE 绑定的模型（覆盖在本仓库配置之上）
            val effModel = QuroFunctionModelConfigRepository(hbContext).resolveConfig(QuroFunctionType.PERSONA_INCUBATE, cfg).model
            val maxRetries = 1
            var lastError: IncubateResult? = null
            for (attempt in 0..maxRetries) {
                if (attempt > 0) delay(3000L)  // 重试前等 3 秒
                val res = try {
                    hbClient.chat(
                        cfg.baseUrl,
                        cfg.apiKey,
                        effModel,
                        listOf(QuroChatMessage("user", prompt)),
                        cfg.temperature,
                        cfg.maxTokens,
                        emptyList(),
                    )
                } catch (e: Exception) {
                    val msg = e.message ?: e.javaClass.simpleName
                    lastError = IncubateResult.Error(
                        when {
                            msg.contains("timeout", ignoreCase = true) -> "连接超时（服务器响应太慢），请检查网络或换一个模型"
                            msg.contains("UnknownHost", ignoreCase = true) || msg.contains("dns", ignoreCase = true) -> "DNS 解析失败，请检查网络连接或 API 地址是否正确"
                            msg.contains("Connection refused", ignoreCase = true) || msg.contains("reset", ignoreCase = true) -> "连接被拒绝，请确认 API 地址 (${cfg.baseUrl.take(30)}) 可访问"
                            msg.contains("network", ignoreCase = true) || msg.contains("socket", ignoreCase = true) || msg.contains("io", ignoreCase = true) -> "网络不可达，请检查 WiFi/数据网络后重试"
                            else -> "请求失败：$msg"
                        }
                    )
                    continue  // → 重试或跳出
                }
                return when (res) {
                    is QuroLlmResult.Text -> parseIncubate(res.content)
                    is QuroLlmResult.Error -> IncubateResult.Error(res.message)
                    is QuroLlmResult.ToolCalls -> IncubateResult.Error("孵化过程不支持工具调用")
                }
            }
            return lastError!!
        }

        private fun buildIncubatePrompt(name: String, description: String, tags: List<QuroTag>): String {
            val tagStr = if (tags.isEmpty()) "（无）" else tags.joinToString(", ") { "${it.name}(${it.hint})" }
            return """你是一个 AI 人格孵化引擎。根据下面的名称、描述、标签，孵化出一份完整的人格卡设定。
请只输出一个 JSON 对象，不要包含任何额外文字、不要使用 markdown 代码块。字段如下：
{
  "roleSetting": "角色设定：2-4 句，定义身份、性格、说话风格",
  "opening": "开场白：一句话，作为首次对话时 AI 的问候语",
  "chatSetting": "聊天设定：1-3 句，约束回复长度、语气、禁忌",
  "voiceSetting": "语音设定：一句话，描述推荐音色与语速，例如'温柔女声，中等语速'",
  "voiceProfile": { "providerId": "edge", "voiceId": "zh-CN-XiaoxiaoNeural", "emotion": "", "speed": 1.0 }
}
voiceProfile 为该人格推荐一个具体可播放的语音组合（结构化，供 TTS 直接调用）：
- providerId 必须从以下选一：edge / openai / minimax / siliconflow / tts302 / cozecn / gizwits / acgn / aliyun / mimo / volcengine / iflytek / tencent
- voiceId 为该服务商下的一个有效音色 ID（参考：edge→"zh-CN-XiaoxiaoNeural"，openai→"alloy"，minimax→"female-shaonv"，siliconflow/aliyun→"中文女"，volcengine→"zh_female_qingxin"，iflytek→"xiaoyan"，tencent→"101001"，mimo 留空字符串）
- emotion 为情绪/风格标签，无则空字符串
- speed 为 0.75–1.25 的浮点数（1.0 正常）
若无法确定合适音色，则 providerId 用 "edge"、voiceId 用 "zh-CN-XiaoxiaoNeural"、emotion 空、speed 1.0。
名称：$name
描述：$description
标签：$tagStr"""
        }

        private fun parseIncubate(content: String): IncubateResult {
            val cleaned = content
                .replace(Regex("```[a-zA-Z]*\\n?"), "")
                .replace("```", "")
                .trim()
            return try {
                val o = JSONObject(cleaned)
                val vp = o.optJSONObject("voiceProfile")
                val voiceProfile = if (vp != null) {
                    val legacyEmotion = vp.optString("emotion", "")
                    val emotionEnabled = if (vp.has("emotionEnabled")) vp.optBoolean("emotionEnabled", false) else legacyEmotion.isNotBlank()
                    val emotionTags = if (vp.has("emotionTags")) {
                        val arr = vp.optJSONArray("emotionTags")
                        (0 until (arr?.length() ?: 0)).mapNotNull { arr?.optString(it) }.filter { it.isNotBlank() }
                    } else if (legacyEmotion.isNotBlank()) listOf(legacyEmotion) else emptyList()
                    QuroVoiceProfile(
                        providerId = vp.optString("providerId", "edge").ifBlank { "edge" },
                        voiceId = vp.optString("voiceId", ""),
                        emotionEnabled = emotionEnabled,
                        emotionTags = emotionTags,
                        speed = vp.optDouble("speed", 1.0).toFloat().coerceIn(0.5f, 2.0f),
                    )
                } else null
                IncubateResult.Success(
                    roleSetting = o.optString("roleSetting", ""),
                    opening = o.optString("opening", ""),
                    chatSetting = o.optString("chatSetting", ""),
                    voiceSetting = o.optString("voiceSetting", ""),
                    voiceProfile = voiceProfile,
                )
            } catch (e: Exception) {
                IncubateResult.Error("解析孵化结果失败：${e.message}\n原始内容：${content.take(200)}")
            }
        }

        /** 将一次心跳孵化结果整理为「备忘」写入 incubation 字段。 */
        private fun buildIncubationMemo(name: String, r: IncubateResult.Success): String {
            val ts = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date())
            return "【心跳孵化 $ts · $name】\n角色设定：${r.roleSetting}\n开场白：${r.opening}\n聊天设定：${r.chatSetting}\n语音设定：${r.voiceSetting}"
        }
    }
}

/** AI 孵化结果。 */
sealed interface IncubateResult {
    data class Success(
        val roleSetting: String,
        val opening: String,
        val chatSetting: String,
        val voiceSetting: String,
        /** 结构化语音组合（B2 孵化升级）：具体服务商/音色/情绪/语速，直接喂 B1 接线。 */
        val voiceProfile: QuroVoiceProfile? = null,
    ) : IncubateResult

    data class Error(val message: String) : IncubateResult
}
