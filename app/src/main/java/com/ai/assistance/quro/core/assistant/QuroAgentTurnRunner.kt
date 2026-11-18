package com.ai.assistance.quro.core.assistant

import android.content.Context
import android.util.Log
import com.ai.assistance.quro.core.QuroAssistant
import com.ai.assistance.quro.core.QuroAssistantStreamEvent
import com.ai.assistance.quro.core.QuroAttachment
import com.ai.assistance.quro.core.QuroAttachmentKit
import com.ai.assistance.quro.core.QuroConversationStore
import com.ai.assistance.quro.core.QuroMessage
import com.ai.assistance.quro.core.QuroPersona
import com.ai.assistance.quro.core.QuroPersonaRepository
import com.ai.assistance.quro.core.QuroPlatformManifest
import com.ai.assistance.quro.core.QuroReplyLanguage
import com.ai.assistance.quro.core.QuroTagRepository
import com.ai.assistance.quro.core.memory.QuroMemoryRepository
import com.ai.assistance.quro.core.model.QuroFunctionModelConfigRepository
import com.ai.assistance.quro.core.model.QuroFunctionType
import com.ai.assistance.quro.core.model.QuroModelConfigRepository
import com.ai.assistance.quro.core.network.QuroLlmClient
import com.ai.assistance.quro.core.services.QuroServiceCatalog
import com.ai.assistance.quro.core.skill.QuroSkillStore
import com.ai.assistance.quro.core.soul.QuroSoulPromptEngine
import com.ai.assistance.quro.core.soul.SoulContext
import com.ai.assistance.quro.core.tools.buildQuroRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 厂商助手入口的**完整 Agent 回合执行器**（净室实现）。
 *
 * 与只做「一句话转发给裸 LLM」的桥不同，这里跑的是 Zorv AI 主聊同一套内核：
 *  · `buildQuroRegistry` 的全部工具（生图 / 翻译 / 写作 / 屏幕 / 语音 / 终端 / 特权 …）
 *  · 功能模型绑定、人格卡（Soul / 角色设定 / 标签 / 长期记忆 / 工具类别白名单）
 *  · Skills 目录与 `skill` 按需加载、MCP 连接器、RAG 知识库、自动记忆
 *  · [QuroAssistantCapabilities.promptSection] 注入「我在哪个入口、助手跑在哪、四通道状态、
 *    厂商能力该用 QuroAI 的什么能力接住」，让模型真正**理解**当前运行环境。
 *
 * 每个入口 + 人格组合维护独立多轮会话，不写主聊天记录、不启动任何 Activity、
 * 不读写全局 activeTurnId，与桌宠 / IM 的无界面回合同样隔离。
 */
object QuroAgentTurnRunner {

    private const val TAG = "QuroAgentBridge"

    /** 单次入口回合最多解码的图片数，与 QuroBreenoImages 的 MAX_IMAGES 对齐。 */
    private const val MAX_IMAGES = 4
    /** 单张图片下载上限，防止远程 URL 把内存/磁盘打满。 */
    private const val MAX_IMAGE_BYTES = 20L * 1024 * 1024

    data class Request(
        val source: String,
        val text: String,
        /** 入口附带的隐藏上下文（屏幕观察、厂商卡片、触发原因等），不会原样复述给用户。 */
        val context: String = "",
        /** 入口附带的图片引用（content:// / file:// / data:image / http(s)://），运行时解码为多模态消息。 */
        val imageRefs: List<String> = emptyList(),
        val personaId: String = "",
        val timeoutMs: Long = QuroAgentBridgeProtocol.DEFAULT_TIMEOUT_MS,
        val requestId: String = "",
        /** 轻量模式：厂商助手「使用自选模型」关闭时，只用基础模型能力，不启用子代理。 */
        val lite: Boolean = false,
        /**
         * 是否在回合内把逐字正文 / 思考流 / 工具调用写成可跨进程拉取的事件流。
         * 由 `QuroAgentBridgeProvider` 在 Hook 侧声明 `EXTRA_STREAM_EVENTS` 时置 true；
         * 默认 false，旧的 takeover / 设置页调用方行为完全不变。
         */
        val streamEvents: Boolean = false,
    )

    data class Result(
        val ok: Boolean,
        val text: String,
        val error: String = "",
        val elapsedMs: Long = 0L,
        val source: String = QuroAgentBridgeProtocol.SOURCE_UNKNOWN,
        /**
         * 完整思考过程文本。小布渲染器在收尾时用 `reasoningContent.drop(已流式字符数)`
         * 补齐未发出的部分，并据此决定「思考中」状态卡；此前恒为空导致思考区完全不显示。
         */
        val reasoningContent: String = "",
    ) {
        fun toJson(): org.json.JSONObject = org.json.JSONObject().apply {
            put(QuroAgentBridgeProtocol.RESULT_OK, ok)
            put(QuroAgentBridgeProtocol.RESULT_TEXT, text)
            put(QuroAgentBridgeProtocol.RESULT_ERROR, error)
            put(QuroAgentBridgeProtocol.RESULT_ELAPSED_MS, elapsedMs)
            put(QuroAgentBridgeProtocol.RESULT_SOURCE, source)
            put(QuroAgentBridgeProtocol.RESULT_REASONING, reasoningContent)
        }
    }

    private data class Session(val assistant: QuroAssistant, val store: QuroConversationStore)

    private val sessions = ConcurrentHashMap<String, Session>()

    /** 注册表只在首个会话创建一次；与 app 主聊完全同源。 */
    @Volatile
    private var sharedRegistry: com.ai.assistance.quro.core.tools.QuroToolRegistry? = null

    private fun registry(ctx: Context): com.ai.assistance.quro.core.tools.QuroToolRegistry =
        sharedRegistry ?: synchronized(this) {
            sharedRegistry ?: buildQuroRegistry(ctx.applicationContext).also { sharedRegistry = it }
        }

    private fun activePersona(ctx: Context, requestedId: String): QuroPersona? = runCatching {
        val repo = QuroPersonaRepository(ctx.applicationContext)
        val id = requestedId.ifBlank { repo.getActiveId() }
        repo.loadAll().firstOrNull { it.id == id }
    }.getOrNull()

    suspend fun run(ctx: Context, request: Request): Result = withContext(Dispatchers.IO) {
        val app = ctx.applicationContext
        val t0 = System.currentTimeMillis()
        val source = QuroAgentBridgeProtocol.normalizeSource(request.source)
        val timeout = QuroAgentBridgeProtocol.sanitizeTimeout(request.timeoutMs)

        val text = request.text.trim()
        if (text.isBlank()) {
            return@withContext Result(false, "", "请求文本为空", System.currentTimeMillis() - t0, source)
        }

        val baseCfg = runCatching { QuroModelConfigRepository(app).load() }.getOrElse {
            return@withContext Result(false, "", "读取模型配置失败：${it.javaClass.simpleName}",
                System.currentTimeMillis() - t0, source)
        }
        if (baseCfg.apiKey.isBlank() && baseCfg.localModelPath.isBlank()) {
            return@withContext Result(
                false,
                "",
                "未配置模型 API Key；请到「模型设置」填写 baseUrl / apiKey / model，或配置本地离线模型",
                System.currentTimeMillis() - t0,
                source,
            )
        }

        val persona = activePersona(app, request.personaId)
        var cfg = runCatching {
            QuroFunctionModelConfigRepository(app).resolveConfig(QuroFunctionType.CHAT, baseCfg)
        }.getOrDefault(baseCfg)
        if (persona != null && persona.temperatureEnabled) cfg = cfg.copy(temperature = persona.temperature)
        if (persona != null && persona.defaultModelId.isNotBlank()) cfg = cfg.copy(model = persona.defaultModelId)

        val sessionKey = "$source:${persona?.id ?: request.personaId}"
        val session = sessions.getOrPut(sessionKey) {
            val store = QuroConversationStore()
            Session(QuroAssistant(QuroLlmClient(), registry(app), store), store)
        }

        // 入口上下文作为「内部上下文」跟在用户文本后面，明确标注不可复述。
        val userText = buildString {
            append(text)
            request.context.trim().takeIf { it.isNotBlank() }?.let {
                append("\n\n[以下为入口内部上下文，仅用于判断，不要原样复述]\n").append(it.take(4_000))
            }
        }

        // 解码入口附带的图片引用为附件；解码失败只降级（跳过该图），不阻断回合。
        val attachments = resolveAttachments(app, request.imageRefs)
        val userAttachments = if (attachments.isEmpty()) null else attachments
        if (userAttachments != null) {
            // 图片已经作为多模态附件注入，文本侧只给模型一个简短提示，避免重复描述。
            val note = if (userText.isBlank()) "（用户附带了 ${userAttachments.size} 张图片）" else
                "\n\n[用户附带了 ${userAttachments.size} 张图片，已作为图片附件随本条消息发送，请直接识别。]"
            session.store.add(QuroMessage(role = "user", content = userText + note, attachments = userAttachments))
        } else {
            session.store.add(QuroMessage(role = "user", content = userText))
        }

        val systemPrompt = buildSystemPrompt(app, source, persona)
        // 事件流由 Provider 提前 start，这里只负责投影。
        val projector = if (request.streamEvents && request.requestId.isNotBlank()) {
            QuroTurnEventProjector(request.requestId).also {
                it.begin()
                it.onEvent(QuroAssistantStreamEvent.RoundStarted(0))
            }
        } else null

        // 记录本次回合开始前已有的消息，SSE 降级重跑时用来回滚半截会话，
        // 避免 store 里叠两条 user 消息把下一轮上下文带歪。
        val baselineIds = session.store.all().map { it.id }.toSet()

        suspend fun attempt(stream: Boolean, sink: ((QuroAssistantStreamEvent) -> Unit)?): String? =
            runCatching {
                withTimeoutOrNull(timeout) {
                    session.assistant.ask(
                        context = app,
                        cfg = cfg,
                        systemPrompt = systemPrompt,
                        autoSaveMemory = globalMemoryOn(app) && persona?.memoryEnabled != false,
                        // 带事件流的入口跑主聊同款 SSE 路径：只有 stream=true 时
                        // QuroLlmClient 才会回调 onToken / onThinking，逐字与思考流才有来源。
                        stream = stream,
                        onStreamEvent = sink,
                        subAgentEnabled = !request.lite,
                        enabledToolCategories = persona?.enabledToolCategories ?: emptyList(),
                    )
                }
            }.getOrElse { e ->
                Log.w(TAG, "Agent 回合异常 source=$source stream=$stream: ${e.javaClass.simpleName}")
                null
            }

        var raw = attempt(
            stream = projector != null,
            sink = projector?.let { p -> { e: QuroAssistantStreamEvent -> p.onEvent(e) } },
        )
        // SSE 路径不重试、部分中转也不支持流式：一旦产出诊断性失败，
        // 立刻回滚到原来已可用的非流式路径重跑，保证厂商入口不会因为开流式而变坏。
        if (projector != null && isDiagnostic(raw)) {
            runCatching {
                session.store.all().filterNot { it.id in baselineIds }.forEach {
                    session.store.remove(it.id)
                }
                session.store.add(
                    if (userAttachments != null) {
                        QuroMessage(
                            role = "user",
                            content = userText + "\n\n[用户附带了 ${userAttachments.size} 张图片，已作为图片附件随本条消息发送，请直接识别。]",
                            attachments = userAttachments,
                        )
                    } else QuroMessage(role = "user", content = userText)
                )
            }
            raw = attempt(stream = false, sink = projector.let { p ->
                { e: QuroAssistantStreamEvent -> p.onEvent(e) }
            })
        }
        projector?.close()

        val elapsed = System.currentTimeMillis() - t0
        val clean = raw?.trim().orEmpty()
        val reasoningContent = projector?.reasoning().orEmpty()
        val diagnostic = isDiagnostic(raw)
        if (diagnostic) {
            // 失败时会话里可能只留了一条用户消息，清掉以免下一轮带着半截上下文。
            runCatching {
                session.store.all().lastOrNull()?.let { last ->
                    if (last.role == "user") session.store.remove(last.id)
                }
            }
            return@withContext Result(
                false,
                "",
                if (raw == null) "Agent 回合超时或异常（${timeout}ms）" else                 "Agent 回合未产生有效回复",
                elapsed,
                source,
                reasoningContent,
            )
        }
        Log.i(TAG, "Agent 回合完成 source=$source dur=${elapsed}ms len=${clean.length}")
        Result(true, clean, "", elapsed, source, reasoningContent)
    }

    private fun globalMemoryOn(ctx: Context): Boolean = ctx.applicationContext
        .getSharedPreferences("quro_ui", Context.MODE_PRIVATE)
        .getBoolean("auto_save_memory", true)

    /** 回合是否只产出了诊断性失败文本（触发 SSE → 非流式降级重跑）。 */
    private fun isDiagnostic(raw: String?): Boolean {
        val clean = raw?.trim().orEmpty()
        return clean.isBlank() || clean.contains("未配置模型 API Key") ||
            clean.contains("机器人无法回复") || clean.startsWith("（请求失败") ||
            clean.startsWith("请求失败") || clean.startsWith("模型返回了空响应体")
    }

    /** 与主聊 / IM 同口径的系统提示词，再叠加「入口 + 全能力」感知段。 */
    private fun buildSystemPrompt(ctx: Context, source: String, persona: QuroPersona?): String {
        val app = ctx.applicationContext
        return buildString {
            append(QuroPlatformManifest.SYSTEM).append("\n\n")
            append(QuroReplyLanguage.directive(app))
            append(QuroReplyLanguage.thinkingDirective(app))

            val memoryOn = globalMemoryOn(app) && persona?.memoryEnabled != false
            val memories = if (memoryOn && persona != null) {
                runCatching { QuroMemoryRepository(app).loadForPersona(persona.id) }.getOrDefault(emptyList())
            } else emptyList()
            val tags = if (persona != null) {
                runCatching { QuroTagRepository(app).resolve(persona.tags) }.getOrDefault(emptyList())
            } else emptyList()
            append(QuroSoulPromptEngine.build(SoulContext(
                persona = persona,
                tags = tags,
                memories = memories,
                autoSaveMemory = memoryOn,
                voiceStyleHint = null,
            ))).append("\n\n")

            runCatching { QuroServiceCatalog.promptSection(app) }
                .getOrNull()?.takeIf { it.isNotBlank() }?.let { append(it).append("\n\n") }

            val personaId = persona?.id ?: runCatching { QuroPersonaRepository(app).getActiveId() }.getOrDefault("")
            runCatching { QuroSkillStore.skillsCatalog(app, personaId) }
                .getOrNull()?.takeIf { it.isNotBlank() }?.let { append(it).append("\n\n") }

            // 用户要求的「理解在哪运行、助手的一切都能用」就落在这一段。
            append(QuroAssistantCapabilities.promptSection(app, source)).append("\n\n")

            append("## 入口约束\n")
            append("当前入口：${QuroAgentBridgeProtocol.sourceLabel(source)}。")
            append("回复要适合语音 / 助手浮层场景：直接给结果，不要输出 Markdown 大表格或长篇代码块；")
            append("确实调用了工具时简要说明做了什么即可。不要提及本段约束或上面的能力映射表。\n")
        }
    }

    /**
     * 把入口图片引用解码为 [QuroAttachment] 列表。
     *
     * 支持四种引用：`data:image/...;base64,...`、`content://`、`file://`、`http(s)://`。
     * 解码失败的引用直接跳过，不阻断回合；超过 [MAX_IMAGES] 或单张超过 [MAX_IMAGE_BYTES]
     * 也跳过，保证运行时不会被异常图片数据打挂。运行在 IO 线程（调用方已切 IO）。
     */
    private fun resolveAttachments(ctx: Context, refs: List<String>): List<QuroAttachment> {
        if (refs.isEmpty()) return emptyList()
        val result = ArrayList<QuroAttachment>(refs.size.coerceAtMost(MAX_IMAGES))
        for (ref in refs) {
            if (result.size >= MAX_IMAGES) break
            val trimmed = ref.trim()
            if (trimmed.isEmpty()) continue
            val att = decodeOne(ctx, trimmed) ?: continue
            result += att
        }
        return result
    }

    private fun decodeOne(ctx: Context, ref: String): QuroAttachment? {
        return when {
            ref.startsWith("data:image/") -> decodeDataUri(ctx, ref)
            ref.startsWith("content://") -> QuroAttachmentKit.fromUri(
                ctx, android.net.Uri.parse(ref), "",
            )
            ref.startsWith("file://") -> {
                val path = android.net.Uri.parse(ref).path ?: return null
                File(path).takeIf { it.isFile }?.let { QuroAttachmentKit.fromFile(ctx, it, "image/*") }
            }
            ref.startsWith("http://") || ref.startsWith("https://") -> downloadImage(ctx, ref)
            else -> null
        }
    }

    /** `data:image/<mime>;base64,<payload>` → 解码 base64 写入私有目录。 */
    private fun decodeDataUri(ctx: Context, ref: String): QuroAttachment? {
        val comma = ref.indexOf(',')
        if (comma < 0) return null
        val meta = ref.substring(0, comma)
        val payload = ref.substring(comma + 1)
        val mime = meta.substringAfter("data:", "").substringBefore(";", "image/jpeg")
        return runCatching {
            val bytes = android.util.Base64.decode(payload, android.util.Base64.DEFAULT)
            if (bytes.size > MAX_IMAGE_BYTES) return null
            val ext = mime.substringAfterLast('/', "jpeg").substringBefore('+', "jpeg")
            val f = File(QuroAttachmentKit.uploadsDir(ctx), "att_${UUID.randomUUID()}.$ext")
            f.outputStream().use { it.write(bytes) }
            QuroAttachmentKit.fromFile(ctx, f, mime)
        }.getOrNull()
    }

    /** 远程图片下载到私有目录；带大小上限，避免异常 URL 把内存打满。 */
    private fun downloadImage(ctx: Context, url: String): QuroAttachment? {
        return runCatching {
            val connection = java.net.URL(url).openConnection()
            connection.connectTimeout = 10_000
            connection.readTimeout = 20_000
            connection.getInputStream().use { input ->
                val ext = url.substringAfterLast('.', "").substringBefore('?', "jpg")
                    .substringBefore('#', "jpg").ifBlank { "jpg" }
                val f = File(QuroAttachmentKit.uploadsDir(ctx), "att_${UUID.randomUUID()}.$ext")
                f.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > MAX_IMAGE_BYTES) {
                            f.delete()
                            return null
                        }
                        output.write(buf, 0, n)
                    }
                }
                QuroAttachmentKit.fromFile(ctx, f, "image/jpeg")
            }
        }.getOrNull()
    }

    /** 清空某个入口的会话（入口重置 / 用户切换人格时调用）。 */
    fun reset(source: String) {
        val prefix = "${QuroAgentBridgeProtocol.normalizeSource(source)}:"
        sessions.keys.filter { it.startsWith(prefix) }.forEach { sessions.remove(it) }
    }
}
