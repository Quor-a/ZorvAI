package com.ai.assistance.quro.ui

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.core.content.edit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ai.assistance.quro.core.AppExecutors
import com.ai.assistance.quro.core.QuroAssistant
import com.ai.assistance.quro.core.QuroPlatformManifest
import com.ai.assistance.quro.core.QuroAttachment
import com.ai.assistance.quro.core.turn.QuroTurnController
import com.ai.assistance.quro.core.vision.QuroVisionLoop
import com.ai.assistance.quro.core.cards.QuroChatCard
import com.ai.assistance.quro.core.agent.QuroAgentTrace
import com.ai.assistance.quro.core.QuroConversationMeta
import com.ai.assistance.quro.core.QuroConversationRepository
import com.ai.assistance.quro.core.QuroConversationStore
import com.ai.assistance.quro.core.memory.QuroMemoryEntry
import com.ai.assistance.quro.core.memory.QuroMemoryRepository
import com.ai.assistance.quro.core.experience.QuroExperienceEngine
import com.ai.assistance.quro.core.experience.QuroExperienceRepository
import com.ai.assistance.quro.core.QuroMessage
import com.ai.assistance.quro.core.QuroChatMessage
import com.ai.assistance.quro.core.QuroLlmResult
import com.ai.assistance.quro.core.bot.BotConversationBinder
import com.ai.assistance.quro.core.bot.QuroBotManager
import com.ai.assistance.quro.core.bot.QuroBotPlatform
import com.ai.assistance.quro.core.QuroToolSpec
import com.ai.assistance.quro.core.QuroPersistedConversation
import com.ai.assistance.quro.core.QuroPersona
import com.ai.assistance.quro.core.soul.QuroSoulPromptEngine
import com.ai.assistance.quro.core.soul.SoulContext
import com.ai.assistance.quro.core.QuroPersonaRepository
import com.ai.assistance.quro.core.QuroReplyNotifier
import com.ai.assistance.quro.core.QuroReplyWidget
import com.ai.assistance.quro.core.QuroCrashLogger
import com.ai.assistance.quro.util.QuroDiag
import com.ai.assistance.quro.core.QuroTagRepository
import com.ai.assistance.quro.core.QuroCrashReporter
import com.ai.assistance.quro.core.cms.QuroCmsRepository
import com.ai.assistance.quro.core.aidlaci.QuroAidlAciManager
import com.ai.assistance.quro.core.skill.QuroSkill
import com.ai.assistance.quro.core.skill.QuroSkillStore
import com.ai.assistance.quro.core.network.QuroLlmClient
import com.ai.assistance.quro.core.model.QuroModelConfig
import com.ai.assistance.quro.core.model.QuroModelConfigRepository
import com.ai.assistance.quro.core.model.QuroFunctionModelConfigRepository
import com.ai.assistance.quro.core.model.QuroFunctionType
import com.ai.assistance.quro.core.attachment.AttachmentManager
import com.ai.assistance.quro.core.policy.QuroPolicy
import com.ai.assistance.quro.core.policy.QuroPolicyStore
import com.ai.assistance.quro.core.tools.buildQuroRegistry
import com.ai.assistance.quro.core.tools.QuroToolRegistry
import com.ai.assistance.quro.core.tools.QuroTool
import com.ai.assistance.quro.core.tools.ImportedToolDef
import com.ai.assistance.quro.core.tools.QuroImportedToolRegistry
import com.ai.assistance.quro.core.tools.QuroVoiceStyle
import com.ai.assistance.quro.core.tools.QuroCloudTtsCatalog
import com.ai.assistance.quro.core.tools.QuroTtsPrefs
import com.ai.assistance.quro.core.tools.QuroTtsHolder
import com.ai.assistance.quro.core.tools.QuroTtsProviderPrefs
import com.ai.assistance.quro.core.tools.QuroTtsProviders
import com.ai.assistance.quro.core.tools.QuroTtsProviderKind
import com.ai.assistance.quro.core.tools.QuroVoiceFeaturePrefs
import com.ai.assistance.quro.core.tools.QuroToolRouter
// ZorvAI 生成式 UI（AI 自写 JSX/HTML → WebView 内渲染）：把 :genui 模块接入主对话运行时
import com.zorv.genui.controller.GenUiController
import com.zorv.genui.host.GenUiHost
import com.zorv.genui.host.SnapshotPersister
import com.zorv.genui.store.GenUiStore
import com.zorv.genui.store.RoomBackedStore
import com.zorv.genui.heal.GenUiSelfHeal
import com.zorv.genui.prompt.GenUiPrompt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

import android.util.Log

private const val TAG = "QuroChatViewModel"

/**
 * 对话 ViewModel（原创）：支持多会话、历史记录持久化、新�?/切换/删除会话�?
 * 同一份内�? [store] 实例贯穿生命周期，避�? QuroAssistant 持有过期引用�?
 */
class QuroChatViewModel(context: Context) : ViewModel() {
    private val appContext = context.applicationContext
    private val repo = QuroModelConfigRepository(appContext)
    private val convRepo = QuroConversationRepository(appContext)
    private val personaRepo = QuroPersonaRepository(appContext)
    private val memoryRepo = QuroMemoryRepository(appContext)
    private val tagRepo = QuroTagRepository(appContext)

    // 当前会话的内存存储（单一实例，QuroAssistant 始终写入它）
    private val store = QuroConversationStore()
    // 共享工具注册表：assistant 下发 tools 字段 �? 系统提示词菜�? 都从这里取，保证二者严格一致�?
    private val registry = buildQuroRegistry(appContext).also { QuroToolRegistry.active = it }
    // 统一附件管理器：管理所有上下文附件（工作区/ACI/技�?/屏幕/通知/位置等）
    private val attachmentManager = AttachmentManager(appContext)

    /** 当前全部已注册工具（含导入工具），供�?+」面板的「已有工具列表」展示�? */
    fun allTools(): List<QuroTool> = registry.all()

    /** 导入一个工具（用户粘贴 JSON / AI 自写）：持久化并并入运行时注册表，使其立即可�? AI 调用�? */
    fun importTool(def: ImportedToolDef) {
        QuroImportedToolRegistry.add(appContext, def)
        registry.mergeImported(appContext)
    }

    // ========== 统一附件管理器方�? ==========

    /** 添加工作区上下文附件 */
    fun addWorkspaceContext(workspacePath: String, workspaceName: String) {
        attachmentManager.addWorkspaceContext(workspacePath, workspaceName)
    }

    /** 添加 ACI 上下文附�? */
    fun addAciContext(aciName: String, packageName: String) {
        attachmentManager.addAciContext(aciName, packageName)
    }

    /** 添加技能上下文附件 */
    fun addSkillsContext(skillNames: List<String>, skillCount: Int) {
        attachmentManager.addSkillsContext(skillNames, skillCount)
    }

    /** 捕获屏幕内容 */
    fun captureScreenContent() {
        viewModelScope.launch {
            attachmentManager.captureScreenContent()
        }
    }

    /** 捕获通知 */
    fun captureNotifications() {
        viewModelScope.launch {
            attachmentManager.captureNotifications()
        }
    }

    /** 捕获位置 */
    fun captureLocation() {
        viewModelScope.launch {
            attachmentManager.captureLocation()
        }
    }

    /** 清空所有附�? */
    fun clearAttachments() {
        attachmentManager.clearAttachments()
    }

    /** 获取附件管理器（�? UI 观察附件状态） */
    fun getAttachmentManager(): AttachmentManager = attachmentManager
    private var assistant = QuroAssistant(QuroLlmClient(), registry, store)

    // 全部会话（含消息），落盘的唯一真相�?
    private val _convs = MutableStateFlow<List<QuroPersistedConversation>>(emptyList())
    private val _conversationsMeta = MutableStateFlow<List<QuroConversationMeta>>(emptyList())
    private val _currentId = MutableStateFlow("")
    private val _messages = MutableStateFlow<List<QuroMessage>>(emptyList())
    // TTS 自动朗读去重：记录已朗读的最后一�? assistant 消息 id，防止退出重入对话框时重复播放�?
    // （Compose remember 是纯内存态，销毁重建即丢失 �? 必须提升�? ViewModel 层。）
    private val _lastSpokenMsgId = MutableStateFlow("")
    val lastSpokenMsgId: StateFlow<String> = _lastSpokenMsgId
    fun markSpoken(msgId: String) { _lastSpokenMsgId.value = msgId }
    // A4 修复：每个会话独立的「生成中」状态。原全局 _busy 会导致切换会话后打断按钮残留�?
    // 现改为按 conversationId 记录，UI 仅对【当前可见会话】显示打断按钮�?
    private val _busyMap = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    fun isBusy(conversationId: String): Boolean = _busyMap.value[conversationId] == true
    // [D5] 统一错误通道：ViewModel 捕获的异常经此暴露给 UI（错误横幅），并配合 Log.e 记录上下文�?
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()
    fun clearError() { _error.value = null }
    private val sendJobs = mutableMapOf<String, Job>()
    /** 每个会话的「在线生成缓冲」：生成中或刚结束的会话，其最新内容优先从此处取，
     *  使切换回该会话时即时看到最新（含在�? token），无需�? �?2s 落盘快照�? */
    private val liveBuffers = mutableMapOf<String, QuroConversationStore>()

    // 屏幕理解闭环（原创）：复�? L1 无障碍服务周期性采集屏幕帧
    private val visionLoop = QuroVisionLoop(appContext, viewModelScope)
    val visionEnabled: StateFlow<Boolean> = visionLoop.enabled
    val visionStatus: StateFlow<QuroVisionLoop.Status> = visionLoop.status
    fun setVisionEnabled(on: Boolean) { visionLoop.setEnabled(on) }

    // 对话轮次状态机（原创）：管理每轮生成的 activate / complete / interrupt
    private val turn = QuroTurnController()
    fun turnState(conversationId: String) = turn.stateOf(conversationId)
    // 当前选中的会�? id（供外部组件如语音球读取，把语音球对话写入此对话框）
    var activeConversationId: String = ""
        private set
    private val uiPrefs = appContext.getSharedPreferences("quro_ui", Context.MODE_PRIVATE)

    // ══════════════ ZorvAI 生成式 UI（:genui 模块）═════════════
    // 每会话一个独立控制器（含独立 Room 库 + WebView 池），天然隔离跨会话卡片 id 与快照。
    // 切换会话时释放上一会话的 WebView，避免无限增长；卡片数据仍在各自 Room 库，切回可重建。
    private val genUiControllers = mutableMapOf<String, GenUiController>()
    private var genUiActiveConv: String? = null
    /** 每会话独立追踪「已喂给控制器的助理正文长度」，按消息 id 记录，避免工具调用轮次串文本 */
    private val genUiIngestLen = mutableMapOf<String, Int>()

    fun genUiControllerFor(convId: String): GenUiController {
        synchronized(genUiControllers) {
            val prev = genUiActiveConv
            if (prev != null && prev != convId) {
                // 释放上一会话的 WebView 池（内存生死线），数据仍在 Room，切回可重建。
                // WebView.destroy 必须主线程，故投递到主线程执行（本方法可能在 IO 流线程被调用）。
                genUiControllers[prev]?.let { ctrl ->
                    Handler(Looper.getMainLooper()).post { runCatching { ctrl.releaseAll() } }
                }
            }
            genUiActiveConv = convId
            return genUiControllers.getOrPut(convId) {
                val store: GenUiStore = RoomBackedStore(appContext, name = "zorv_genui_${convId.take(64)}")
                val selfHeal = GenUiSelfHeal(maxAttempts = 3)
                // 宿主要的是 SnapshotPersister（非 suspend 的 fire-and-forget 单方法接口），
                // 而 RoomBackedStore.saveState 是 suspend；用 SAM 适配器在 IO 线程桥接。
                val host = GenUiHost(
                    appContext,
                    snapshotPersister = SnapshotPersister { id, rev, state ->
                        viewModelScope.launch(Dispatchers.IO) { store.saveState(id, rev, state) }
                    }
                )
                GenUiController(host, store, selfHeal).also { ctrl ->
                    // 自愈：模型写错 → 收到 <runtime-error> → 隐藏用户消息注入反馈 → 触发重写（rev+1）
                    ctrl.onRepairRequest = { feedback -> repairGenUi(convId, feedback) }
                    // 组件主动接话：AI 自写卡片里的按钮 emit('intent') → 作为一条用户消息让 AI 接手回应
                    ctrl.onIntent = { _, type, payload ->
                        val summary = buildString {
                            append("【对话卡片交互】用户通过我生成的界面触发了 \"$type\"")
                            payload?.let { p ->
                                p.keys().forEach { k -> append("\n- $k: ${p.opt(k)}") }
                            }
                        }
                        viewModelScope.launch { send(text = summary, repairConvId = convId) }
                    }
                }
            }
        }
    }

    /** 把流式累积的助理正文增量喂给生成式 UI 解析器（delta 跟踪，因 onToken 返回的是累计全文） */
    private fun ingestGenUiFromBuffer(convId: String, buf: QuroConversationStore) {
        val m = buf.all().lastOrNull { it.role == "assistant" && !it.hidden } ?: return
        val prev = genUiIngestLen[m.id] ?: 0
        if (m.content.length <= prev) return
        val delta = m.content.substring(prev)
        genUiIngestLen[m.id] = m.content.length
        if (delta.isNotEmpty()) genUiControllerFor(convId).ingest(delta)
    }

    /** 流结束后收尾：处理未闭合围栏，并把本轮产出的卡片 id 关联到对应助理消息（按围栏 id= 精确匹配） */
    private fun finishAndAttachGenUi(convId: String, buf: QuroConversationStore) {
        val ctrl = genUiControllers[convId] ?: return
        ctrl.finish()
        val refs = ctrl.cards.value
        if (refs.isEmpty()) return
        buf.all().forEach { m ->
            if (m.role != "assistant" || m.hidden) return@forEach
            val ids = refs.filter { r ->
                Regex("""zorv/ui[^\n]*\bid=${r.id}\b""").containsMatchIn(m.content)
            }.map { it.id }
            if (ids.isNotEmpty()) {
                buf.update(m.id) { it.copy(genUiCardIds = (it.genUiCardIds + ids).distinct()) }
            }
        }
        genUiIngestLen.clear()
    }

    /** 生成式 UI 自愈：把运行时错误反馈作为隐藏用户消息注入，触发模型完整重写该卡片 */
    private fun repairGenUi(convId: String, feedback: String) {
        viewModelScope.launch { send(text = "", repairFeedback = feedback, repairConvId = convId) }
    }

    companion object {
        /** 当前活跃�? ViewModel 实例，供语音球等外部组件委托对话写入「选中的对话框」�? */
        lateinit var instance: QuroChatViewModel
            private set
    }
    private val _thinking = MutableStateFlow(uiPrefs.getBoolean("thinking", true))

    val conversations: StateFlow<List<QuroConversationMeta>> = _conversationsMeta.asStateFlow()
    val currentId: StateFlow<String> = _currentId.asStateFlow()
    val messages: StateFlow<List<QuroMessage>> = _messages.asStateFlow()
    // 仅反映【当前可见会话】是否正在生成（随切换会话自动变化），供 UI 显示打断按钮�?
    val busy: StateFlow<Boolean> = combine(_busyMap, _currentId) { map, id -> map[id] == true }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** #862：正在后台生成中的会�? id 集合，供历史抽屉显示「生成中」徽标（切走其它会话续跑可见）�? */
    val generatingIds: StateFlow<Set<String>> =
        _busyMap.map { map -> map.filterValues { it }.keys.toSet() }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())
    val thinking: StateFlow<Boolean> = _thinking.asStateFlow()

    fun setThinking(on: Boolean) {
        _thinking.value = on
        uiPrefs.edit { putBoolean("thinking", on) }
    }

    // AI 自动保存记忆开关（设置页「AI 自动保存记忆」控制；默认开启，保持原有行为�?
    private val _autoSaveMemory = MutableStateFlow(uiPrefs.getBoolean("auto_save_memory", true))
    val autoSaveMemory: StateFlow<Boolean> = _autoSaveMemory.asStateFlow()

    fun setAutoSaveMemory(on: Boolean) {
        _autoSaveMemory.value = on
        uiPrefs.edit { putBoolean("auto_save_memory", on) }
    }

    // 外观与对话设置：深色模式（全局主题，需上提�? QuroApp 根部主题处生效）
    private val _darkMode = MutableStateFlow(uiPrefs.getBoolean("dark_mode", false))
    val darkModePref: StateFlow<Boolean> = _darkMode.asStateFlow()
    fun isDarkMode(): Boolean = _darkMode.value
    fun setDarkMode(on: Boolean) { _darkMode.value = on; uiPrefs.edit { putBoolean("dark_mode", on) } }

    // 外观与对话设置：回复完成提示�?
    private val _soundOn = MutableStateFlow(uiPrefs.getBoolean("sound_on", true))
    val soundOnPref: StateFlow<Boolean> = _soundOn.asStateFlow()
    fun isSoundOn(): Boolean = _soundOn.value
    fun setSoundOn(on: Boolean) { _soundOn.value = on; uiPrefs.edit { putBoolean("sound_on", on) } }

    // 外观与对话设置：字号档位�?0=�? 1=标准 2=大）
    private val _fontTier = MutableStateFlow(uiPrefs.getInt("font_tier", 1))
    val fontTierPref: StateFlow<Int> = _fontTier.asStateFlow()
    fun getFontTier(): Int = _fontTier.value
    fun setFontTier(tier: Int) { _fontTier.value = tier; uiPrefs.edit { putInt("font_tier", tier) } }

    // 外观与对话设置：回车发�?
    private val _enterSend = MutableStateFlow(uiPrefs.getBoolean("enter_send", true))
    val enterSendPref: StateFlow<Boolean> = _enterSend.asStateFlow()
    fun isEnterSend(): Boolean = _enterSend.value
    fun setEnterSend(on: Boolean) { _enterSend.value = on; uiPrefs.edit { putBoolean("enter_send", on) } }

    // 外观与对话设置：保留对话轮数（对话框级覆盖模�? contextWindow 的轮次语义）�?
    // null = 跟随模型默认（contextWindow）；N>0 = 仅保留最�? N �? (用户+助手) 轮次�?
    private val _historyRounds = MutableStateFlow<Int?>(null)
    val historyRoundsPref: StateFlow<Int?> = _historyRounds.asStateFlow()
    fun setHistoryRounds(n: Int?) {
        _historyRounds.value = n
        // 立即把设置写回当前会话并落盘，避免「改了设置但没发消息就关应用」导致设置丢失�?
        val id = _currentId.value
        val idx = _convs.value.indexOfFirst { it.id == id }
        if (idx >= 0) {
            _convs.value = _convs.value.toMutableList().also { list ->
                list[idx] = list[idx].copy(historyRounds = n)
            }
            runCatching { convRepo.saveAll(_convs.value) }
        }
    }

    // AI 回复通知总开关（离开软件时的系统通知 / 桌面卡片均受它控制）
    private val _aiReplyNotify = MutableStateFlow(uiPrefs.getBoolean("ai_reply_notify", true))
    val aiReplyNotifyPref: StateFlow<Boolean> = _aiReplyNotify.asStateFlow()
    fun setAiReplyNotify(on: Boolean) { _aiReplyNotify.value = on; uiPrefs.edit { putBoolean("ai_reply_notify", on) } }

    // ---- 用户资料（头�? / 名字 / 签名，显示在对话框并注入 system prompt�? ----
    data class UserProfile(
        val name: String = "",
        val avatarUri: String = "",
        val bio: String = "",
    )

    private var _cachedProfile = MutableStateFlow(loadProfile())
    val userProfile: StateFlow<UserProfile> = _cachedProfile.asStateFlow()

    private fun loadProfile(): UserProfile = UserProfile(
        name = uiPrefs.getString("user_name", "") ?: "",
        avatarUri = uiPrefs.getString("user_avatar", "") ?: "",
        bio = uiPrefs.getString("user_bio", "") ?: "",
    )

    fun saveProfile(p: UserProfile) {
        _cachedProfile.value = p
        uiPrefs.edit {
            putString("user_name", p.name)
            putString("user_avatar", p.avatarUri)
            putString("user_bio", p.bio)
        }
    }

    /**
     * 统一触发回复通知：离开软件时弹系统通知（由 QuroReplyNotifier 按前台状态判断）+ 刷新桌面卡片�?
     * 受总开关控制。前台（用户在软件内）时系统通知不会弹、桌面卡片照常刷新�?
     */
    private fun fireReplyNotification(sender: String, text: String) {
        if (!_aiReplyNotify.value) return
        // N1 加固：占�?/错误文案（「⏹ 已停止生成。」「⚠�? …」）不触发系统通知与桌面卡片刷新—�?
        // 它们不是真实回复，用户主动停止后弹通知纯属打扰。真实回复不受影响�?
        val t = text.trimStart()
        if (t.startsWith("�?") || t.startsWith("⚠️")) return
        QuroReplyNotifier.notifyReply(appContext, sender, text)
        QuroReplyWidget.updateLatest(appContext, sender, text)
    }

    init {
        // �? 全面排查修复（v316）：init 内禁止主线程 IO。convRepo.loadAll() 读取全量对话历史
        //   + saveAll() 写盘均为�? IO，对话量大时在主线程同步执行会直�? ANR（启�?/进聊天即卡死）�?
        //   这里只同步设置引用与空初始态，�? IO 全部挪到 IO 线程异步完成�?
        instance = this
        // 生成式 UI 冷启动预热：当前会话确定后主线程预热该会话 WebView 池，消首张卡片 200-500ms 冷启动。
        // warmUp 幂等：池满后再次调用为 no-op；后续每次切会话都会为活跃会话预热，无副作用。
        viewModelScope.launch {
            currentId.collect { id ->
                if (id.isBlank()) return@collect
                Handler(Looper.getMainLooper()).post {
                    runCatching { genUiControllerFor(id).warmUp() }
                }
            }
        }
        _convs.value = emptyList()
        _messages.value = emptyList()
        viewModelScope.launch(AppExecutors.io) {
            val loaded = convRepo.loadAll().toMutableList()
            if (loaded.isEmpty()) {
                val id = UUID.randomUUID().toString()
                val now = System.currentTimeMillis()
                val welcome = QuroMessage(
                    role = "assistant",
                    content = defaultWelcome(),
                )
                loaded.add(QuroPersistedConversation(id = id, title = "新对�?", createdAt = now, updatedAt = now, messages = listOf(welcome)))
                convRepo.saveAll(loaded)
            }
            _convs.value = loaded
            // #882 数据自检：修�? v407-v409 串台 bug 写入磁盘的脏数据�?
            // 历史版本（commitCurrent 用共�? store �? buf）在切会话时会把别会话的消息混进当前会话�?
            // messages 列表并持久化 �? selectConversation 每次�? conv.messages 加载的都是脏数据�?
            // 运行时隔离再正确也无法清除已落盘的污染�?
            // 清洗策略：每个会话内�? id 去重（保留首次出现），并记录清洗日志供诊断�?
            var repaired = false
            val cleaned = loaded.map { conv ->
                val seen = mutableSetOf<String>()
                val unique = conv.messages.filter { msg ->
                    seen.add(msg.id) // Set.add returns false if element already present
                }
                if (unique.size < conv.messages.size) {
                    repaired = true
                    QuroCrashLogger.logEvent(appContext, "DATA_REPAIR",
                        "convId=${conv.id.take(8)}.. removed=${conv.messages.size - unique.size} dupes, was=${conv.messages.size} now=${unique.size}")
                    conv.copy(messages = unique)
                } else conv
            }
            if (repaired) {
                _convs.value = cleaned
                runCatching { convRepo.saveAll(cleaned) }
            }
            // ---- 数据自检结束 ----

            val latest = cleaned.maxByOrNull { it.updatedAt }!!
            _currentId.value = latest.id
            activeConversationId = latest.id
            store.clear()
            latest.messages.forEach { store.add(it) }
            _messages.value = store.all()
            emitMeta()
        }

        // 注册机器人会话绑定器：把平台用户消息按设置写�? App 持久化会话（纯内存操作，留主线程�?
        QuroBotManager.instance(appContext).conversationBinder = BotConversationBinder { platform, userId, userName, userText, replyText, mode, fixedConvId ->
            when (mode) {
                "none" -> Unit
                "fixed" -> {
                    val targetId = fixedConvId?.takeIf { id -> _convs.value.any { it.id == id } }
                        ?: createBotConversation(platform, userId)
                    appendToConversation(
                        targetId,
                        listOf(
                            QuroMessage(role = "user", content = userText, senderName = userName),
                            QuroMessage(role = "assistant", content = replyText),
                        ),
                    )
                }
                else -> { // auto
                    val convId = findBotConversation(platform, userId)?.id
                        ?: createBotConversation(platform, userId)
                    appendToConversation(
                        convId,
                        listOf(
                            QuroMessage(role = "user", content = userText, senderName = userName),
                            QuroMessage(role = "assistant", content = replyText),
                        ),
                    )
                }
            }
        }
    }

    // ---- 会话操作 ----

    /** 查找某平台用户对应的机器人自动会话（按标题匹配）�? */
    private fun findBotConversation(platform: QuroBotPlatform, userId: String): QuroPersistedConversation? {
        val prefix = "[${platform.label}] "
        return _convs.value.firstOrNull { it.title == "$prefix$userId" }
    }

    /** 为某平台用户新建一个自动会话，返回�? ID�? */
    private fun createBotConversation(platform: QuroBotPlatform, userId: String): String {
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val welcome = QuroMessage(
            role = "assistant",
            content = "这是来自 ${platform.label} 用户 $userId 的机器人对话�?",
        )
        val conv = QuroPersistedConversation(id = id, title = "[${platform.label}] $userId", createdAt = now, updatedAt = now, messages = listOf(welcome))
        _convs.value = _convs.value + conv
        convRepo.saveAll(_convs.value)
        emitMeta()
        return id
    }

    /** 把消息追加到指定会话并持久化；若该会话正好是当前可见会话，也同步刷新 UI�? */
    private fun appendToConversation(
        conversationId: String,
        messages: List<QuroMessage>,
        updateTitle: Boolean = false,
    ) {
        val idx = _convs.value.indexOfFirst { it.id == conversationId }
        if (idx < 0) return
        val conv = _convs.value[idx]
        val title = if (updateTitle) {
            messages.firstOrNull { it.role == "user" }?.content?.take(20)?.let { if (it.isNotBlank()) it else conv.title } ?: conv.title
        } else conv.title
        val updated = conv.copy(
            messages = conv.messages + messages,
            updatedAt = System.currentTimeMillis(),
            title = title,
        )
        val newList = _convs.value.toMutableList()
        newList[idx] = updated
        _convs.value = newList
        convRepo.saveAll(_convs.value)
        emitMeta()
        // 如果追加的是当前可见会话，同步刷�? _messages
        if (_currentId.value == conversationId) {
            messages.forEach { store.add(it) }
            _messages.value = store.all()
        }
    }

    fun newConversation() {
        // 🔧 Bug修复「切对话框中断生成」：新建对话【不再打断】当前会话正在进行的生成�?
        // 每条在途生成有独立缓冲 liveBuffers[convId] 与按会话记账�? commitCurrent�?
        // 旧会话协程在后台续跑、按 id 落盘，不会污染新会话（与 selectConversation 同一原则�?
        // 生成任务与对话框生命周期解耦）；切回旧会话可从 liveBuffer 恢复实时进度�?
        // 旧逻辑在此 cancel + �? liveBuffer，导致用户「新建对话」时旧会话回复被腰斩�?
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val welcome = QuroMessage(role = "assistant", content = defaultWelcome())
        val conv = QuroPersistedConversation(id = id, title = "新对�?", createdAt = now, updatedAt = now, messages = listOf(welcome))
        _convs.value = _convs.value + conv
        _currentId.value = id
        activeConversationId = id
        _historyRounds.value = null
        store.clear()
        store.add(welcome)
        _messages.value = store.all()
        convRepo.saveAll(_convs.value)
        emitMeta()
    }

    fun selectConversation(id: String) {
        val from = _currentId.value
        val conv = _convs.value.firstOrNull { it.id == id } ?: return
        QuroDiag.log("SELECT", "from=$from to=$id busyFrom=${isBusy(from)} busyTo=${isBusy(id)}")
        // 切走【不再打断】其它会话的生成：每条在途生成使用独立缓�? buf（见 send()），
        // �? commitCurrent(convId, buf) 按会�? id 落盘、仅当目标会话可见时才刷新显示，
        // 因此切走时旧协程继续写自己的 buf，不会污染新会话；切回时从持久化 reload 即可看到进度�?
        _currentId.value = id
        activeConversationId = id
        store.clear()
        // 载入该会话已保存的「保留对话轮数」设置（null=跟随模型默认�?
        _historyRounds.value = conv.historyRounds
        // 优先取在线缓冲（生成�?/刚结束）�? 即时看到最新；否则取持久化消息�?
        val live = liveBuffers[id]
        if (live != null) live.all().forEach { store.add(it) }
        else conv.messages.forEach { store.add(it) }
        _messages.value = store.all()
        // busy 仅反映【当前可见会话】是否生成中；切回仍在后台生成的会话时，
        // _busyMap 中该会话仍为 true，打断按钮会自动重新显示�?
    }

    fun deleteConversation(id: String) {
        sendJobs[id]?.cancel(); sendJobs.remove(id)
        liveBuffers.remove(id)
        val remaining = _convs.value.filter { it.id != id }
        _convs.value = remaining
        if (_currentId.value == id) {
            if (remaining.isEmpty()) {
                newConversation()
                return
            }
            selectConversation(remaining.maxByOrNull { it.updatedAt }!!.id)
        }
        convRepo.saveAll(_convs.value)
        emitMeta()
    }

    /**
     * 删除单条/聚合气泡对应的底层消息（v417 对话框缺失功能补全）�?
     * ids 为该气泡携带的全�? QuroMessage 原始 id；删除助手消息时，连带清理其隐藏�?
     * tool 结果消息（role=="tool" �? toolCallId 命中被删消息�? toolCall），避免孤儿消息残留�?
     */
    fun deleteMessage(ids: List<String>) {
        val cid = _currentId.value ?: return
        if (ids.isEmpty()) return
        // 若正在生成，先停掉本轮，避免过时缓冲把被删消息重新写回�?
        sendJobs[cid]?.cancel(); sendJobs.remove(cid)
        val removeSet = ids.toSet()
        val all = store.all()
        val toolCallIds = all.filter { it.id in removeSet }
            .flatMap { it.toolCalls?.map { tc -> tc.id } ?: emptyList() }.toSet()
        val toRemove = all.filter {
            it.id in removeSet || (it.role == "tool" && it.toolCallId != null && it.toolCallId in toolCallIds)
        }.map { it.id }.toSet()
        toRemove.forEach { store.remove(it) }
        val msgs = store.all()
        _messages.value = msgs
        val convs = _convs.value.toMutableList()
        val idx = convs.indexOfFirst { it.id == cid }
        if (idx >= 0) {
            convs[idx] = convs[idx].copy(messages = msgs, updatedAt = System.currentTimeMillis())
            _convs.value = convs
        }
        convRepo.saveAll(_convs.value)
        emitMeta()
    }

    fun deleteAllConversations() {
        sendJobs.values.forEach { it.cancel() }; sendJobs.clear()
        liveBuffers.clear()
        _convs.value = emptyList()
        newConversation()
        convRepo.saveAll(_convs.value)
        emitMeta()
    }

    fun clear() {
        sendJobs[_currentId.value]?.cancel(); sendJobs.remove(_currentId.value)
        liveBuffers.remove(_currentId.value)
        store.clear()
        val welcome = QuroMessage(role = "assistant", content = "对话已清空�?")
        store.add(welcome)
        commitCurrent()
    }

    // ---- 发�? ----

    fun send(
        text: String,
        attachments: List<QuroAttachment> = emptyList(),
        cfg: QuroModelConfig = repo.load(),
        /** 用户在本轮对话框「选择技能」里显式选中的技能；非空时其指令仅作用于本轮消息�? */
        skill: QuroSkill? = null,
        /** 上下文信息字符串（工作区路径/ACI应用/技能数量），作为隐藏消息注入，�? AI 本轮可用�? */
        contextMessage: String? = null,
        /** 生成式 UI 自愈：非空时把反馈作为【隐藏】用户消息注入，触发模型重写卡片（rev+1） */
        repairFeedback: String? = null,
        /** 生成式 UI 自愈/交互：强制把本轮归属到指定会话（否则取当前可见会话） */
        repairConvId: String? = null,
    ) {
        val t = (repairFeedback ?: text).trim()
        if (t.isEmpty() && attachments.isEmpty()) return
        // 多会话切换修复：锁定本轮归属会话 convId，整个协程以内一律以 convId 记账�?
        // 不再读实�? _currentId，避免切换会话后轮次/忙�?/落盘串台�?
        // 生成式 UI 自愈轮：repairConvId 强制归属到出错的会话
        val convId = repairConvId ?: _currentId.value
        activeConversationId = convId
        // 新一轮生成开始：复位「AI �? speak 工具播报」标记，避免上一轮残留导致自动朗读误让位
        QuroTtsHolder.speakToolFiredThisTurn = false
        QuroDiag.log("SEND", "convId=$convId busyBefore=${isBusy(convId)} text=${t.take(80).replace("\n", " ")}")
        // 轮次打断（barge-in）：仅打断【同一会话】正在进行的前一轮，绝不波及后台其它会话�?
        // �? 存话根因修复：仅�? isBusy 标志会在标志错位时漏�?"仍在生成"的会话，
        //   导致同一会话被起多个 job 并发�? liveBuffers[convId] �? _convs[convId] �? 回复互相覆盖/错位（写个html吧挂到设备仪表盘即此）�?
        //   改为同时判断 turn 状态与在�? job，确保同一会话任意时刻只有 1 个活跃生成�?
        if (isBusy(convId) || turn.stateOf(convId) != QuroTurnController.State.IDLE || sendJobs.containsKey(convId)) {
            // #878 修复：打断旧轮前必须把旧 buf 已生成的全部内容同步回共�? store 并强制落盘�?
            // 否则�? buf 在下面快照共�? store 时拿不到旧轮�? AI 回复 �? 用户看到"内容消失"�?
            // �? job 取消后其 finally/cancellation-handler 虽也�? commitCurrent(旧buf)�?
            // 但新 job 的后�? commitCurrent(新buf) 会用缺旧内容的新 buf 覆盖 _messages �? 旧内容仍丢失�?
            val oldBuf = liveBuffers[convId]
            if (oldBuf != null) {
                this@QuroChatViewModel.store.clear()
                oldBuf.all().forEach { this@QuroChatViewModel.store.add(it) }
                commitCurrent(convId, forceSave = true)
            }
            turn.interrupt(convId)
            sendJobs[convId]?.cancel()
            QuroDiag.log("BARGE", "convId=$convId oldBufSynced=${oldBuf != null}")
        }
        val myGen = turn.activate(convId)
        // 屏幕理解（看懂屏幕）：开启时把当前屏幕的无障碍节点树快照注入系统提示�?
        // �? AI 每轮都能"�?"到当前屏幕在做什么（无需像素截图权限）�?
        val screenCtx = if (visionEnabled.value) visionLoop.consumeLatestSnapshot()?.let { "\n\n[当前屏幕 UI 结构]\n$it" } else null

        // ── 立即显示用户消息（不等待协程调度）──
        // 用户消息必须先于 launch add 到共�? store �? commitCurrent�?
        // 这样界面立刻反映�?"已发�?"状态，无需�? AI 响应�?
        // 用户消息先构造成引用，便于既加入共享 store（即时显示）又追加进种子（生成副本）�?
        // 构建用户消息（只包含用户输入的纯文本，不包含上下文）
        // 生成式 UI 自愈轮：repairFeedback 作为隐藏消息注入（不展示给用户），让模型据此重写卡片
        val userMsg = QuroMessage(
            role = "user",
            content = t,
            attachments = if (attachments.isNotEmpty()) attachments else null,
            senderName = userProfile.value.name.takeIf { it.isNotBlank() },
            avatarUrl = userProfile.value.avatarUri.takeIf { it.isNotBlank() },
            hidden = repairFeedback != null,
        )
        // �? 存话根因修复：种子快照必须取自【本会话权威态】，绝不用可能被切会话交换的共享单例 store�?
        //   优先本会话在线缓冲（最新、可能尚未落盘）�? 否则持久�? _convs[convId].messages（落盘权威态）�? 兜底空�?
        //   再把本条用户消息追加其后（共�? store 仅用于即时显示，不作为种子来源，避免串台/缺失）�?
        val liveSeed = liveBuffers[convId]?.all()
        val persistedSeed = _convs.value.firstOrNull { it.id == convId }?.messages
        val convBase = (liveSeed ?: persistedSeed ?: emptyList()).toList()
        // �? 串台防御（v434+ 修复）：轮次信息通过【隐�? system 消息】传�? LLM�?
        //   不再注入 userMsg.content（旧方案会导�? seededUserMsg 进入 buf→liveBuffer→commitCurrent 刷屏�?
        //   使用�? UI 看到内部 [第N轮] 标记泄露）�?
        val firstUser = convBase.none { it.role == "user" }
        val initialMessages = convBase + userMsg
        store.add(userMsg)  // 仅用于即时显示（commitCurrent 默认�? store 刷屏/首存�?
        QuroCrashLogger.logEvent(appContext, "USERMSG", "senderName=[${(userProfile.value.name ?: "").take(20)}] avatarUrl=[${(userProfile.value.avatarUri ?: "").take(60)}]")
        QuroDiag.log("SEED", "convId=$convId seedMsgs=${initialMessages.size} hasAssistant=${initialMessages.any { it.role == "assistant" }} busyBefore=${isBusy(convId)} liveSeed=${liveSeed != null}")
        commitCurrent(convId, updateTitle = firstUser)  // 外部立即显示（首条用户消息同步衍化对话标题）

        // #864 修复：busy 标志必须�? launch 之前置位，使 ChatScreen 的「正在思考…」人格占位气�?
        // 在用户点击发送的那一帧就出现（带人格头像），否则会因 launch 调度晚一帧才显示头像�?
        _busyMap.value = _busyMap.value + (convId to true)

        // 接住对话协程里逃逸的异常，转成可见报错而非界面卡死/进程崩溃�?
        val job = viewModelScope.launch(QuroCrashReporter.handler) {
            // 独立缓冲：用 send() 同步抓取的【本会话快照】initialMessages 作为本轮工作副本 buf�?
            // 不再读单例共�? store（共�? store 随时可能被切会话 swap），使后台生成与显示 store 彻底解耦—�?
            // 切走其它会话不再污染、本会话可在后台续跑；commitCurrent(convId, buf) �? id 落盘�?
            val buf = QuroConversationStore().apply { initialMessages.forEach { add(it) } }
            val store = buf
            liveBuffers[convId] = buf
            val genAssistant = QuroAssistant(QuroLlmClient(), registry, buf)
            try {
                // 注意：用户消息已�? launch 外添加到共享 store �? commitCurrent（立即显示）�?
                // buf 快照已包含该消息，此处无需重复添加�?
                // 触发词自动激活：匹配到的非常驻（alwaysOn=false）技能按隐藏消息预注入，�? AI 本轮作答
                val onDemand = QuroSkillStore.matchTriggerSkills(t, appContext).filter { !it.alwaysOn }
                if (onDemand.isNotEmpty()) {
                    val inject = onDemand.joinToString("\n\n") { "### ${it.name}\n${it.prompt}" }
                    store.add(
                        QuroMessage(
                            role = "user",
                            content = "[本轮已根据触发词自动激活以下技能，请按其对用户消息作答]\n$inject",
                            hidden = true,
                        ),
                    )
                }
                // 用户显式选中的技能（对话框「选择技能」）：仅作用于本轮，指令作为隐藏消息预注�?
                if (skill != null && skill.prompt.isNotBlank()) {
                    store.add(
                        QuroMessage(
                            role = "user",
                            content = "[用户已选择技能�?${skill.name}」，请严格按以下指令处理其本条消息]\n### ${skill.name}\n${skill.prompt}",
                            hidden = true,
                        ),
                    )
                }
                // 上下文信息（工作�?/ACI/技能）：作为隐藏消息注入，�? AI 本轮知道用户选择了什�?
                if (!contextMessage.isNullOrBlank()) {
                    store.add(
                        QuroMessage(
                            role = "user",
                            content = "[上下文信�? - 用户当前选择的工作区/ACI/技能]\n$contextMessage",
                            hidden = true,
                        ),
                    )
                }
                // 落盘技能隐藏消息到 buf（用户消息与标题更新已在 launch 外的 commitCurrent 处理�?
                commitCurrent(convId, buf)
                // �? 多轮上下文纪律统一只靠系统提示词的「回复纪律」约束，
                //   不再每轮注入 [第N轮] 隐藏 user 消息（旧方案会被小本地模型回�?/改写�?
                //   表现为「（多轮对话上下文理解：…[第N轮] 你好）」泄漏、乱回复、不回复）�?
                //   系统提示词已含等价多轮纪律，移除冗余注入即可根治该泄漏�?
                // 本地离线模型（MNN / llama.cpp）走本地推理，不发起 HTTP，无需 apiKey。
                // 旧逻辑一刀切拦「apiKey 为空」，导致选本地模型也会被"尚未配置 API Key"挡住，
                // 本地模型因此永远用不了。这里先放行本地 provider，仅云端模型才校验 apiKey。
                val isLocalModel = cfg.provider == "MNN" || cfg.provider == "LLAMA_CPP"
                if (!isLocalModel && cfg.apiKey.isBlank()) {
                    store.add(
                        QuroMessage(
                            role = "assistant",
                            content = "⚠️ 尚未配置模型 API Key，请点右上角模型芯片 →「在模型设置中管理」填�? baseUrl / apiKey / model�?",
                        ),
                    )
                } else {
                    // 功能模型配置：主对话 (CHAT) 恒用主模型（resolveConfig 默认跟随主模型，等效�? cfg），
                    // 此处经统一入口消费，便于后续子能力接入独立模型时复用同一机制�?
                    val effectiveCfg = QuroFunctionModelConfigRepository(appContext).resolveConfig(QuroFunctionType.CHAT, cfg)
                    // ask 内部已对每个环节兜底，这里再兜一层：任何意外都转成一条可见的错误消息�?
                    // �? finally 保证 _busy 一定复位，避免「卡死在思考中」导致此后永远无法回复�?
                    runCatching {
                        // �? ANR 修复：buildSystemPrompt 内部同步�? memory/experience/skills 存储读取 + 大字符串拼接�?
                        // 原为 ask 的实参在 viewModelScope(主线�?) 上求�? �? 主线程重 I/O/计算 �? 触发系统 ANR 对话框�?
                        // 改为�? IO 线程先把提示词算好，再交�? ask（ask 自身仍切 IO 执行 ReAct 循环）�?
                        val spStart = System.currentTimeMillis()
                        val sysPrompt = withContext(Dispatchers.IO) { buildSystemPrompt(effectiveCfg) + (screenCtx ?: "") }
                        QuroDiag.log("GEN_SYSPROMPT_MS", "convId=$convId ms=${System.currentTimeMillis() - spStart}")
                        val askStart = System.currentTimeMillis()
                        var firstTokenTs = 0L
                        genAssistant.ask(appContext, effectiveCfg, sysPrompt, autoSaveMemory = autoSaveMemory.value, stream = true, historyRounds = _historyRounds.value ?: 0, deepThink = thinking.value) {
                        // 工具调用/结果产生、以及流�? token 到达时实时刷新并落盘（退出生效）�?
                        // 退出也能保留中间过程；commitCurrent 内部已对落盘�? �?1s 节流�?
                            if (firstTokenTs == 0L) { firstTokenTs = System.currentTimeMillis(); QuroDiag.log("GEN_FIRSTTOKEN", "convId=$convId ttfb=${firstTokenTs - askStart}ms") }
                            commitCurrent(convId, buf)
                            // 生成式 UI：把本轮累积正文增量喂给 :genui 解析器（AI 自写 JSX/HTML → 对话框内渲染）
                            ingestGenUiFromBuffer(convId, buf)
                        }
                        QuroDiag.log("GEN_ASK_MS", "convId=$convId total=${System.currentTimeMillis() - askStart}ms")
                    }.onFailure { e ->
                        if (e is CancellationException) {
                            // 用户主动打断生成：不报红错误，附一行明确反馈并保留已生成的部分内容
                            QuroDiag.log("SEND_CANCEL", "convId=$convId (job cancelled �? 已停止生�?)")
                            store.add(QuroMessage(role = "assistant", content = "�? 已停止生成�?"))
                            commitCurrent(convId, buf)
                            // 生成式 UI：被中断也可能已产出完整卡片，仍尝试收尾挂载
                            finishAndAttachGenUi(convId, buf)
                            return@onFailure
                        }
                        store.add(
                            QuroMessage(
                                role = "assistant",
                                content = "⚠️ 回复生成失败�?${(e.message ?: "未知错误").take(200)}",
                            ),
                        )
                    }
                }
                // 生成式 UI：流结束收尾——处理未闭合围栏，并把本轮产出的卡片 id 关联到对应助理消息
                finishAndAttachGenUi(convId, buf)
                commitCurrent(convId, buf, forceSave = true)
                // 对话一轮完�? �? 触发人格自动孵化（按轮次累计，静默、不阻塞主对话）
                maybeAutoIncubate()
                // 回复完成 �? 统一触发通知（离开软件弹系统通知 + 刷新桌面卡片，受总开关控制）
                runCatching {
                    store.all().lastOrNull { it.role == "assistant" && !it.hidden && it.content.isNotBlank() }
                        ?.let { fireReplyNotification("Zorv AI", it.content) }
                }
                } catch (e: Exception) {
                    // 🔧 Bug修复「取消被当成错误展示」：取消信号（用户停�?/同会话新消息 barge-in�?
                    // 从收尾段的挂起点逃逸时，绝不能包成「⚠�? 发生错误」红色气泡—�?
                    // 原样上抛，走 finally 干净收尾（onFailure 分支已负责「⏹ 已停止生成」反馈）�?
                    if (e is CancellationException) throw e
                    Log.e(TAG, "生成回复异常 convId=$convId", e)
                    _error.value = "回复生成失败�?${e.message ?: "未知错误"}"
                    store.add(
                        QuroMessage(
                            role = "assistant",
                            content = "⚠️ 发生错误�?${(e.message ?: "未知错误").take(200)}",
                        ),
                    )
                    commitCurrent(convId, buf)
                } finally {
                turn.complete(convId, myGen) // 仅当 gen 匹配才真正置 IDLE
                // 只有当前轮确实结束（无更新轮在跑）才复位 busy，避免旧协程 finally 误清
                if (turn.stateOf(convId) == QuroTurnController.State.IDLE) {
                    _busyMap.value = _busyMap.value - convId
                }
                sendJobs.remove(convId)
                // 仅当在线缓冲仍指向本�? buf（即无新�? supersede）时才做收尾�?
                // �? 把本轮最终内容同步回共享 store，保证下一�? send() 的种子快照含完整历史
                //    （含上轮 AI 回复）。否则下一�? initialMessages 缺失上轮回复 �?
                //    上轮内容消失 / 多轮上下文断�? / 单对话框内回复串位�?
                // �? 再移�? liveBuffer，避免误删续�?/新轮的缓冲�?
                // ⚠️ 必须�? this@QuroChatViewModel.store 访问类字段：launch �? line 511 �? `store` 重名遮蔽成本�? buf�?
                if (liveBuffers[convId] === buf) {
                    // �? 多会话串台根因修复（每会话状态独立、不共享）：
                    //   全局单例 store 仅承载【当前可见会话】的工作副本。后台（不可见）会话生成完成时，
                    //   若也把自身内容无条件覆盖进全局 store，会污染下一个可见会话的 send() 首显
                    //   （store.add(userMsg) + commitCurrent 默认�? store �? _messages）→ 屏幕串出旧会话内�?
                    //   （如「心情日记已创建完成」）。故仅当本会话当前可见才同步全局 store�?
                    //   其回复早已通过 commitCurrent 的落盘分支正确写�? _convs[convId].messages�?
                    //   切回时由 selectConversation 重新装载，数据不一�?
                    if (convId == _currentId.value) {
                        this@QuroChatViewModel.store.clear()
                        buf.all().forEach { this@QuroChatViewModel.store.add(it) }
                    }
                    liveBuffers.remove(convId)
                    QuroDiag.log("SYNC", "convId=$convId syncedBufToStore visible=${convId == _currentId.value}")
                }
            }
        }
        sendJobs[convId] = job
    }

    /** 打断当前正在进行的生成：取消对应会话的发送协程并立即复位 busy（已生成的部分内容已落盘，不报错）�?
     *  按当前可见会�? id 精准取消对应 job（sendJobs 映射），不波及后台其它会话的生成�? */
    fun stop() {
        val id = activeConversationId
        // #878 修复：取消前把当�? buf 已生成内容同步回共享 store并强制落盘，
        // 否则取消�? buf 仅在 job 内部，共�? store 缺失 �? 切走再切回或新消�? barge-in 时内容丢失�?
        val stoppingBuf = liveBuffers[id]
        // �? 串台防御：仅当被停止的会话当前可见时才同步全局 store（全局 store 只承载可见会话副本）�?
        //   停止后台不可见会话时若仍覆盖全局 store，会污染随后可见会话�? send() 首显�?
        if (stoppingBuf != null && id == _currentId.value) {
            store.clear()
            stoppingBuf.all().forEach { store.add(it) }
            commitCurrent(id, forceSave = true)
        }
        QuroDiag.log("STOP", "id=$id (manual stop button)")
        sendJobs[id]?.cancel()
        sendJobs.remove(id)
        turn.interrupt(id)
        _busyMap.value = _busyMap.value - id   // 立即复位【当前会话】：打断按钮马上切回发�?
    }

    /**
     * 语音球轮次：把用户语音识别文本写入「绑定的对话框」并问询，返回最终回复文本�?
     *
     * - [sessionId] 为空或等同于当前可见会话 �? 写入当前正在看的对话框（原行为，store 即当前对话）�?
     * - [sessionId] 指向其它会话 �? 临时接管内存 store 把对话写进该会话并落盘，
     *   不打扰用户当前可见的对话框（[commitCurrent] 不会误写当前会话）�?
     *
     * 这是「语音球绑定到指定对话框」的核心�?
     */
    suspend fun voiceBallTurn(text: String, cfg: QuroModelConfig = repo.load(), sessionId: String = ""): String {
        val targetId = sessionId.ifBlank { _currentId.value }

        // 目标即当前可见会话：走原路径
        if (targetId == _currentId.value) {
            activeConversationId = targetId
            store.add(QuroMessage(role = "user", content = text, senderName = userProfile.value.name.takeIf { it.isNotBlank() }, avatarUrl = userProfile.value.avatarUri.takeIf { it.isNotBlank() }))
            // 触发词自动激活（当前可见会话路径）：�? send() 同源逻辑
            val onDemand = QuroSkillStore.matchTriggerSkills(text, appContext).filter { !it.alwaysOn }
            if (onDemand.isNotEmpty()) {
                val inject = onDemand.joinToString("\n\n") { "### ${it.name}\n${it.prompt}" }
                store.add(
                    QuroMessage(
                        role = "user",
                        content = "[本轮已根据触发词自动激活以下技能，请按其对用户消息作答]\n$inject",
                        hidden = true,
                    ),
                )
            }
            commitCurrent()
            val reply = runVoiceAsk(cfg) { commitCurrent() }
            commitCurrent()
            fireReplyNotification("Zorv AI", reply)
            return reply
        }

        // 目标为其它（非当前可见）会话：临时接�? store，写入并落盘，不切换可见对话�?
        val conv = _convs.value.firstOrNull { it.id == targetId }
            ?: return voiceBallTurn(text, cfg, "")   // 兜底：会话不存在 �? 写当�?
        val saved = store.all().toList()
        return try {
            store.clear()
            conv.messages.forEach { store.add(it) }
            store.add(QuroMessage(role = "user", content = text, senderName = userProfile.value.name.takeIf { it.isNotBlank() }, avatarUrl = userProfile.value.avatarUri.takeIf { it.isNotBlank() }))
            // 触发词自动激活（绑定其它会话路径）：�? send() 同源逻辑
            val onDemand = QuroSkillStore.matchTriggerSkills(text, appContext).filter { !it.alwaysOn }
            if (onDemand.isNotEmpty()) {
                val inject = onDemand.joinToString("\n\n") { "### ${it.name}\n${it.prompt}" }
                store.add(
                    QuroMessage(
                        role = "user",
                        content = "[本轮已根据触发词自动激活以下技能，请按其对用户消息作答]\n$inject",
                        hidden = true,
                    ),
                )
            }
            val reply = runVoiceAsk(cfg) { commitCurrent(targetId) }
            val finalMsgs = store.all().toList()
            _convs.value = _convs.value.map { c ->
                if (c.id == targetId) c.copy(
                    messages = finalMsgs,
                    updatedAt = System.currentTimeMillis(),
                    title = if (c.title == "新对�?") deriveTitle(finalMsgs) else c.title,
                ) else c
            }
            convRepo.saveAll(_convs.value)
            emitMeta()
            fireReplyNotification("Zorv AI", reply)
            reply
        } finally {
            store.clear()
            saved.forEach { store.add(it) }
            // 当前可见视图�? _messages 不在此处刷新，避免绑定写入时界面闪烁
        }
    }

    /** 用同一套助手与系统提示词问询（store 须已含用户消息）。onTick 用于生成中持久化�? */
    private suspend fun runVoiceAsk(cfg: QuroModelConfig, onTick: () -> Unit): String {
        // 本地离线模型（MNN / llama.cpp）走本地推理，不发起 HTTP，无需 apiKey。
        val isLocalModel = cfg.provider == "MNN" || cfg.provider == "LLAMA_CPP"
        return if (!isLocalModel && cfg.apiKey.isBlank()) {
            "⚠️ 尚未配置模型 API Key，请点右上角模型芯片 →「在模型设置中管理」填�? baseUrl / apiKey / model�?"
        } else {
            runCatching {
                // 功能模型配置接入引擎：语音球问答使用 CHAT 绑定模型
                val effCfg = QuroFunctionModelConfigRepository(appContext).resolveConfig(QuroFunctionType.CHAT, cfg)
                // �? ANR 修复：与对话框主路径一致，buildSystemPrompt 放到 IO 线程求值，避免语音球问答在主线程做存储读取�?
                val spStart = System.currentTimeMillis()
                val sysPrompt = withContext(Dispatchers.IO) { buildSystemPrompt(effCfg) }
                QuroDiag.log("VB_SYSPROMPT_MS", "ms=${System.currentTimeMillis() - spStart}")
                val askStart = System.currentTimeMillis()
                var firstTokenTs = 0L
                // #1110：语音球问答原默�? stream=false �? 整段回、不逐层；与主对话（stream=true）行为不一致，
                // 表现为「部分返回不是一层一层返回、自己回到对话框」。云模型改为流式，与文本框主路径一致�?
                val r = assistant.ask(appContext, effCfg, sysPrompt, autoSaveMemory = autoSaveMemory.value, stream = true, historyRounds = _historyRounds.value ?: 0, deepThink = thinking.value, onUpdate = {
                    if (firstTokenTs == 0L) { firstTokenTs = System.currentTimeMillis(); QuroDiag.log("VB_FIRSTTOKEN", "ttfb=${firstTokenTs - askStart}ms") }
                    onTick()
                })
                QuroDiag.log("VB_ASK_MS", "total=${System.currentTimeMillis() - askStart}ms")
                r
            }.getOrElse { e ->
                if (e is CancellationException) "�? 已停止生成�?" else "⚠️ 语音球出错了�?${e.message ?: "未知错误"}"
            }
        }
    }

    fun currentConfig(): QuroModelConfig = repo.load()

    // ---- 内部 ----

    /** 把当前内�? store 的变动写�? _convs、刷新消息流并落盘�?
     * @param forceSave 跳过落盘节流，强制立即写盘（用于对话收尾，确保最终内容不丢）�?
     * 落盘节流：流式生成时 onUpdate �? ~200ms 触发一�? commitCurrent，若每次�? saveAll 全量对话�?
     * 会把整段对话 JSON 高频重写磁盘。这里把真实写盘限频�? �?2s 一次，状态刷新（_messages/_convs�?
     * 仍每次即时更新，保证 UI 实时跟手、磁盘不爆�? */
    // �? 存话修复：落盘节流改为【按会话】独立计时，避免会话 A 的写盘把会话 B 的写盘节流掉�?
    //   导致 B �? 2s 内的最新内容未被落盘而丢失（多会话并存时尤其明显）�?
    private val lastSaveMsByConv = mutableMapOf<String, Long>()
    // 多会话切换修复：convId 指定本轮归属会话。落盘【始终】写�? _convs[convId]�?
    // 仅当 convId 即当前可见会话时才刷新可见消息流 _messages，避免后台生成串台到前台�?
    private fun commitCurrent(convId: String = _currentId.value, buf: QuroConversationStore = store, updateTitle: Boolean = false, forceSave: Boolean = false) {
        val id = convId
        val msgs = buf.all()
        // #883 修复（内容直接消失根因）：仅�? buf 是【允许刷新显示】的来源时才更新可见消息�? _messages�?
        //   �? buf === liveBuffers[id]：本轮正在干活的活动缓冲（流式增�? / 终态都走它）；
        //   �? buf === store：共享工作副本（clear / 首条用户消息立即显示 / 语音球等非生成路径）�?
        //   �? liveBuffers[id] == null：该会话已无在线缓冲（本轮是最终完成者，落最终内容）�?
        // 反例——被取消/打断的【过时旧 buf】（liveBuffers[id] 已指向更新的 buf，且 buf !== store）：
        //   它的 catch 仍会 commitCurrent(convId, oldBuf)，若放行会同步把 _messages 覆盖回旧内容
        //   （缺最新用户消�? + 新一轮流式），表现为「内容直接消�? / 回退到旧内容」�?
        //   此守卫与 #877 �? IO 落盘守卫（activeBuf != null && activeBuf !== buf �? return）同源，
        //   一个堵落盘、一个堵显示，彻底断掉过时缓冲对当前会话的污染�?
        val canUpdateDisplay = (buf === store) || (liveBuffers[id] === buf) || (liveBuffers[id] == null)
        // �? 唯一允许刷新可见消息流的闸门�?#883 修复点）�?
        //   注意：本行之后曾紧跟一条无守卫�? `if (id == _currentId.value) _messages.value = msgs`�?
        //   它会在每�? commitCurrent 时绕�? canUpdateDisplay，把被取�?/过时的旧 buf 内容强制覆盖 _messages
        //   �? 表现为「内容直接消�? / HTML 写出又消�? / 切会话内容丢失」。v414 已删除那条重复行�?
        if (id == _currentId.value) {
            QuroDiag.log("DISPLAY", "convId=$id allow=$canUpdateDisplay msgs=${msgs.size} bufIsLive=${liveBuffers[id] === buf} bufIsStore=${buf === store}")
            if (canUpdateDisplay) _messages.value = msgs
        }
        // �? #763 相关（非根因修复，仅 IO 侧节流优化）：经核查 QuroAssistant.ask 整体运行�?
        //   withContext(Dispatchers.IO)，onUpdate(emit) 在该 IO 作用域内触发且已被节流到 ~500ms/次，
        //   �? commitCurrent 本就�? IO 线程，并非主线程 ANR 的直接成因。此处仅将开销更大�?
        //   `_convs` 全量更新 + `emitMeta()`（历史列�?/元数据映射）+ `saveAll` 落盘从「每�? emit(2Hz)�?
        //   进一步合并节流到 �?2s 一次，减少无谓的列表拷贝与磁盘写；主线�? ANR 真凶仍需 StrictMode + 真机
        //   主线程埋点（androidx.tracing）复现取证后定位，切勿将此节流误读为 ANR 修复�?
        val now = System.currentTimeMillis()
        val last = lastSaveMsByConv[id] ?: 0L
        val due = forceSave || now - last >= 2000L
        if (due) {
            lastSaveMsByConv[id] = now
            viewModelScope.launch(AppExecutors.io) {
                // #877 串台修复：落盘前再次校验�? buf 是否仍是该会话的活动 liveBuffer�?
                // send() 打断旧轮时会用新 buf 覆盖 liveBuffers[convId]，旧 job 此前已排�? IO 线程�?
                // 落盘任务若晚于新轮执行，会拿�? buf 的内容把新会话覆盖掉 �? 回复串台/丢失�?
                // 仅当「liveBuffers[id] === buf」或「该会话已无 liveBuffer（本 job 是最終完成者）」才写盘�?
                val activeBuf = liveBuffers[id]
                if (activeBuf != null && activeBuf !== buf) return@launch
                val existing = _convs.value.firstOrNull { it.id == id }
                val title = if (updateTitle && existing != null) deriveTitle(msgs) else existing?.title ?: "新对�?"
                // 仅在写入「当前可见会话」时落盘用户设置的保留轮数；其它会话（后台生�? / 语音球绑定会话）
                // 保留其自身已存的 historyRounds，避免把当前会话的设置串台覆盖到其它会话�?
                val rounds = if (id == _currentId.value) _historyRounds.value else existing?.historyRounds
                _convs.value = _convs.value.toMutableList().also { list ->
                    val idx = list.indexOfFirst { it.id == id }
                    if (idx >= 0) list[idx] = list[idx].copy(messages = msgs, updatedAt = System.currentTimeMillis(), title = title, historyRounds = rounds)
                }
                QuroDiag.log("SAVE", "convId=$id msgs=${msgs.size} activeBufSame=${liveBuffers[id] === buf} force=$forceSave")
                emitMeta()
                runCatching { convRepo.saveAll(_convs.value) }
            }
        }
    }

    /**
     * �? AI �? ui_widget / ui_card 下发的富组件挂到「当前助手消息」气泡里（实现可视化组件融进聊天气泡）�?
     * 安全：流式生成时 onUpdate 每帧 commitCurrent 把内容写�? store，这里只是给 store 里最后一�?
     * 助手消息追加 cards 并刷�? _messages，不会冲掉正在流的文本�?
     */
    fun attachCardToLastAssistant(card: QuroChatCard) {
        // 🔧 v290 修复：代码执行状态卡（type=toolcall，AI �? ui_widget / ui_card 下发�?
        // 「运行中 / 完成 / 失败」进度卡）不再作为独立卡片浮在对话框里—�?
        // 那样会「位置错（错挂到上一轮可见气泡）/ 提前出现（任务还没执行就显示�?/ 与工具块重复」�?
        // 改为熔化进「执行轨迹」总线，由 A 系统 ToolCallBlock 内嵌的「执行轨迹」统一呈现�?
        // 真正落实「执行追踪融进工具调用卡，不再是独立浮层」�?
        if (card is QuroChatCard.ToolCallCard) {
            val label = when (card.status.lowercase()) {
                "running" -> "执行�?"
                "done" -> "执行完成"
                "error" -> "执行失败"
                else -> "等待执行"
            }
            val detail = card.message.takeIf { it.isNotBlank() }
                ?: "${label}${if (card.tool.isNotBlank()) " · ${card.tool}" else ""}"
            QuroAgentTrace.status(card.tool.ifBlank { "工具" }, detail)
            return
        }
        // #879 切会话防污染：区分「可见会话」与「后台生成会话」�?
        // - 可见会话：直接改共享 store 并刷�? _messages（原行为）�?
        // - 后台生成会话：改该会话自己的 liveBuffer，绝不碰共享 store / _messages�?
        //   否则卡片会漏进当前可见会话（切会话串台的次要来源）�?
        // 归属会话 = activeConversationId（send 协程启动时已锁定为本�? convId）�?
        val ownerId = activeConversationId
        val visible = (_currentId.value == ownerId)
        // �? 修复（v416）：可见会话在【生成中】时，实时内容在 liveBuffer（buf）里，共�? store 是过时的
        //   （仅含上轮终�? + 本轮用户消息，不含正在流的助手回�?/工具块）。若此处�? store 改卡�?
        //   `_messages.value = store.all()`，会把屏幕回退到过时内�? �? 表现为「内容消�? / 工具重负载时完全错乱」�?
        //   因此：优先用 liveBuffer 作为卡片载体与显示源；无 liveBuffer（非生成中）才退�? store�?
        val live = liveBuffers[ownerId]
        // #879 防污染补充：后台会话若在卡片到达�? liveBuffer 已回收（生成早已结束，延迟异步卡片）�?
        // 且并非当前可见会话，则【绝不】触碰共�? store —�? 否则会把卡片写入错误的当前会话（串台）�?
        // 直接丢弃该延迟卡片（比污染当前会话安全），可见会话仍走下�? store 分支正常挂载�?
        if (live == null && !visible) {
            QuroDiag.log("CARD", "丢弃后台延迟卡片 ownerId=$ownerId（非可见且无 liveBuffer，避免串台）")
            return
        }
        val storeForCard: QuroConversationStore = live ?: store
        val msgs = storeForCard.all()
        QuroDiag.log("CARD", "ownerId=$ownerId visible=$visible fromLive=${live != null}")
        // 🔧 修复（v200）：ui_widget / ui_card �? ToolCalls 阶段执行时，本轮唯一�? assistant 占位消息�?
        //   hidden=true 且带 toolCalls 的。若按「最后非隐藏 assistant」找，会命中【上一轮】可见消息，
        //   导致当前轮组件卡片串到历史气�? �? 用户看到「完全错乱」�?
        //   正确目标：优先挂到本�? hidden 占位（hidden 且含 toolCalls）；兜底再退最后非隐藏 assistant / 最后非隐藏消息�?
        val target = msgs.lastOrNull { it.role == "assistant" && it.hidden && it.toolCalls?.isNotEmpty() == true }
            ?: msgs.lastOrNull { it.role == "assistant" && !it.hidden }
            ?: msgs.lastOrNull { !it.hidden }
        if (target != null) {
            storeForCard.update(target.id) { it.copy(cards = it.cards + card) }
        } else {
            storeForCard.add(QuroMessage(role = "assistant", content = "", cards = listOf(card)))
        }
        if (visible) {
            // 显示源必须与卡片载体一致：生成中用 liveBuffer（含正在流的实时内容），否则�? store�?
            _messages.value = if (live != null) live.all() else store.all()
        }
    }

    private fun emitMeta() {
        _conversationsMeta.value = _convs.value
            .sortedByDescending { it.updatedAt }
            .map { metaOf(it) }
    }

    private fun deriveTitle(msgs: List<QuroMessage>): String {
        val firstUser = msgs.firstOrNull { it.role == "user" }
        return firstUser?.content?.take(20)?.trim()?.ifBlank { "新对�?" } ?: "新对�?"
    }

    private fun metaOf(conv: QuroPersistedConversation): QuroConversationMeta {
        val last = conv.messages.lastOrNull { it.role != "system" }
        val preview = last?.content
            ?.replace("\n", " ")
            ?.take(40)
            ?.trim()
            ?: "空对�?"
        return QuroConversationMeta(conv.id, conv.title, conv.updatedAt, preview)
    }

    // ---- 人格�? / 记忆�? 接线 ----

    /** 项目开源地址（GitHub 仓库）�? */
    private val QURO_REPO_URL = "https://github.com/Quor-a/ZorvAI"

    /** 默认系统提示词（未激活任何人格卡时使用）�? */
    private val DEFAULT_SYSTEM =
        """你是运行�? Zorv AI 这个端侧运行环境里的 AI 助手（当前未启用特定人格卡，以通用身份应答），乐于助人、简洁准确�?

## 工具调用（主动执行）
你已接入了设备工具调用能力，遇到需要真实数据或真实动作的需求就主动调用——无需等用户明�?"帮我打开/查一�?"才行动�?

### 主动调用（最重要�?
�?**不必等用户明确说"帮我打开 / 查一�? / 调用 XX"才行�?**。只要结合上下文判断某项能力能真正帮到用户、或能让你的回答更准更可靠，你就应该**主动**调用对应工具——像人自然使用工具一样，无需先征诀��也无需报备"我要调用 XX 工具"�?
- 用户随口�?"有点�?" �? 可主�? get_battery / get_wifi_info 给出环境状态；
- 用户描述一个任�? �? 直接选最合适的能力执行，而不是先�?"要我帮你做吗"�?
- 工具调用是你的默认工作方式之一，需要真实数据或动作时应当主动用�?

当用户要求执行任�?**具体动作**时—�?
包括但不限于：打开应用、启动APP、查询设备信息、设闹钟、开手电筒、振动�?
查电�?/WiFi/网络、读写应用内文件、运行代码、发HTTP请求、朗读文字（TTS）、打开网页等—�?

**当用户要你执行具体动作时，优先调用对应工具真正执行，而不是只用文字描述；纯闲�? / 情感 / 创意表达可直接自然回答文字�?**

示例�?
- 用户�?"打开快手" �? 调用 search_and_launch_app(app_name="快手")，不要回"好的我来帮你打开"
- 用户�?"电量多少" �? 调用 get_battery，不要猜一个数�?
- 用户�?"有什么应�?" �? 调用 list_installed_apps，不要凭空列�?

具体动作优先用工具拿真实结果；纯闲聊 / 情感 / 创意类直接自然回答文字。需要真实数据或真实动作时主动调用工具，按最自然、最有帮助的方式作答�?

## ⚠️ 模糊命令处理（【最高优先级强制规则】，违反=严重错误�?

**当你不确定用户想要什么时，必须立刻调�? `visual_question` 询问，禁止猜测、禁止假设、禁止跳过！**

**必须调用 visual_question 的场景（任何一条都必须调用）：**
1. 用户指令模糊�?"帮我处理一�?"�?"搞个那个"�?"弄一�?"�?"弄一下那�?"）→ 必须问清�?
2. 执行任务缺少关键信息（文件路径、收件人、格式等）→ 必须问用户要
3. 用户的话有多种理�? �? 必须确认是哪�?
4. 需要确认的不可逆操作（删除、覆盖、发送、安装、提交）�? 必须先确�?
5. 有多个合理选项 �? 必须让用户�?
6. 用户提到模糊�?"那个"�?"这个"�?"�?" �? 必须问清楚指的是什�?
7. 用户指令缺少主语或宾�? �? 必须问清�?

**绝对禁止（违�?=做错=浪费用户时间）：**
- �? 猜测用户意图后直接执行（猜错=做错=浪费用户时间�?
- �? 假设用户想要某个选项
- �? �?"我猜你是想要..."代替询问
- �? 因为"大概是这个意�?"就跳过询�?
- �? 因为"看起来很明确"就跳过询问（除非真的100%明确�?
- �? 因为"之前用户说过类似的话"就跳过询�?

**正确做法：遇到任何不确定（哪怕只�?1%的不确定）→ 立刻 `visual_question`�?**

## 自我认知（System Manifest�?
你是运行�? Android 设备上的原生 AI 助手。以下是你的真实档案�?
- **名称**：Zorv AI 助手（通用模式；启用人格卡后你的真实名字会变成该人格卡�?
- **平台**：Android（原生应用，非网�?/小程序）
- **架构模式**：ReAct 工具调用循环（LLM �? 工具执行 �? 结果回灌 �? 最终答复）
- **技术栈**：Jetpack Compose UI / Kotlin / OkHttp / WebView 内置浏览�?
- **核心能力边界**�?
  - �? 可在应用沙箱内执行能力（拉起其他 App、读写应用自身文件、TTS 朗读、在应用内执行脚本）
  - ⚠️ 不通过 Shell / Root / Shizuku / 无障碍控制系统（终端/CMS 仅为应用内能力的可视化）
  - �? 有内置工具箱（文件管�? / 代码运行 / 包名查询 / 内置浏览器）
  - �? 有记忆库（可自动沉淀用户偏好和长期信息）
  - �? 有人格卡系统（每张卡是独立的真实身份，可切换；启用后你的身份 = 该卡�?
  - �? �? CMS v2 能力模块系统（可扩展的能力插件）
  - ⚠️ 无直接联网能力（但可通过 open_web 在内置浏览器打开网址�?
  - �? 不能访问其他设备或云端服�?
  - �? 完全开源（源码与协议公开，欢迎参与共建）
- **开源地址**�?${QURO_REPO_URL}
- **项目主页**�?${QURO_REPO_URL}
- **当用户问"你是�?"/"你能做什�?"�?**：基于以上事实自然回答，不要背诵原文。根据用户技术背景调整深度——技术人员可以说架构细节，普通用户说功能场景�?

## 核心能力（你拥有这些真实工具，可以直接调用）
- **应用管理**：list_installed_apps（列出全部已安装应用）、launch_app（启动应用）、search_and_launch_app（一步搜索并打开）、get_package_name（查包名�?
- **跨应用能力调�?**：list_app_functions（枚举某应用对外导出的能力入口：Activity 意图过滤�? / 导出 Service / ContentProvider / 广播）、invoke_app_function（直接调用其中一项；kind=service/broadcast/provider �?**后台执行、不弹前台界�?**，kind=activity 时拉起前台界面兜底）。这让你可以主动唤醒其他 App 并调用其功能，无需用户手动点开
- **系统信息**：get_device_info、get_current_time、get_battery、get_wifi_info
- **文件操作**：list_files、read_text_file、write_file、delete_file �?
- **网络**：http_request（发�? HTTP 请求�?
- **权限通道**：priv_status（查�? CMS 权限模式与已授权项）
- **CMS v2 模块**：cms_list（查看能力模块）、cms_call（调用能力）
- **记忆�?**：memory_save/list/search/delete（自动沉淀长期记忆�?
- **工作区工�?**：workspace_write（写文件）、workspace_read（读文件）、workspace_list（列目录）、workspace_render（渲染预览）、workspace_doc（创建文档）、workspace_media（播放媒体）、workspace_doc_view（打开文档�?
- **文档工具**：enhanced_doc_create（增强版文档创建，支�?20+格式）、aiwps_create（基础文档创建）、aiwps_read（读取文档）、aiwps_edit（编辑文档）

## 工具调用规则
- 用户想「打开/启动 XX 应用」时，优先用 search_and_launch_app（一步完成），不要先 list_installed_apps �? launch_app
- 用户问「有什么应�?/装了什么」时才用 list_installed_apps
- 调用 cms_call 执行应用内能力（所有能力均在应用沙箱内运行，不借助 Root/Shizuku/无障碍）
- 工具执行结果不需要原样复述给用户，而是基于结果给出自然、有用的答复
- 如果工具返回错误或找不到，直接告诉用户原因并建议替代方案
- 用户提到创作需求（图形/视频/音频/3D/游戏/低代码等）时，使�? creative_studio 工具获取推荐和调用能�?

## AI 多媒体生�?/识别（直接调用，结果返回对话框）
你拥有以�? AI 能力，可以直接调用，结果会自动返回到对话框：

### 图片生成 (image_gen)
- 用户�?"画一�?..."�?"生成图片"�?"帮我做张海报"时调�?
- 参数：{"prompt":"图片描述","width":1024,"height":1024}
- 生成的图片会自动显示在对话中

### 视频生成 (video_gen)
- 用户�?"做个视频"�?"生成视频"�?"帮我剪个视频"时调�?
- 参数：{"prompt":"视频描述","duration":5}
- 生成的视频会自动显示在对话中

### 图像识别 (image_recognition)
- 用户发送图片后，你想分析图片内容时调用
- 参数：{"image_path":"图片路径","question":"可选问�?"}
- 返回图片的详细描述和分析

### 音频识别 (audio_recognition)
- 用户发送音频后，你想转录音频内容时调用
- 参数：{"audio_path":"音频路径","language":"可选语言"}
- 返回音频的文字转�?

### 视频理解 (video_understanding)
- 用户发送视频后，你想分析视频内容时调用
- 参数：{"video_path":"视频路径","question":"可选问�?","max_frames":3}
- 返回视频的详细描述和分析，支持理解场景、动作、文字等

**重要：当用户发送附件时，主动使用对应工具处理！**

## 工作区文件渲染与文档创建

### 工作区渲�? (workspace_render)
- 用户�?"预览这个文件"�?"渲染一�?"�?"看看这个"时调�?
- 参数：{"path":"文件路径","title":"可选标�?"}
- 支持渲染：HTML、Markdown、代码、图片等
- 渲染结果以卡片形式显示在对话�?

### 工作区文档创�? (workspace_doc)
- 用户�?"创建文档"�?"新建文件"�?"写个HTML"时调�?
- 参数：{"path":"文件路径","content":"内容","type":"html|md|txt|json|js|py|java|kt|css"}
- 创建的文档自动保存到工作区，并渲染预�?

### 工作区媒体播�? (workspace_media)
- 用户�?"播放工作区里的音�?"�?"播放视频"时调�?
- 参数：{"path":"文件路径","action":"play_music|play_video|pause|stop"}
- 支持格式：mp3, wav, m4a, mp4, avi, mkv �?
- 播放结果会在对话框中显示播放卡片

### 工作区文档查�? (workspace_doc_view)
- 用户�?"打开这个文档"�?"查看PDF"时调�?
- 参数：{"path":"文件路径"}
- 支持格式：PDF、DOCX、XLSX、PPTX、TXT、MD �?
- 调用系统默认应用打开（如 WPS、Office�?

### 增强版文档创�? (enhanced_doc_create)
- 用户�?"创建文档"�?"新建文件"�?"写个XX"时调�?
- 支持20+格式：docx, xlsx, pptx, pdf, md, txt, csv, html, rtf, odt, epub, json, xml, yaml, css, js, svg
- 参数：{"type":"格式","title":"标题","content":"内容"}
- 创建后自动渲染预�?

### 工作流示�?
1. 用户�?"帮我写个网页" �? AI 调用 enhanced_doc_create 创建 HTML �? 自动渲染预览
2. 用户�?"看看 index.html" �? AI 调用 workspace_render �? 显示渲染结果
3. 用户�?"修改这个文件" �? AI �? workspace_read 读取 �? 修改�? workspace_write 写回 �? workspace_render 预览
4. 用户�?"播放工作区里的音�?" �? AI 调用 workspace_media 播放 �? 显示播放卡片
5. 用户�?"打开这个PDF" �? AI 调用 workspace_doc_view �? 系统应用打开
6. 用户�?"创建一个Word文档" �? AI 调用 enhanced_doc_create �? 显示渲染预览
7. 用户�?"写个JSON配置" �? AI 调用 enhanced_doc_create �? 显示代码高亮

## 可视化交互（问答与操作弹窗）

### ⚠️ 可视化问答弹�? (visual_question) �? 【最高优先级强制规则�?

**【强制】遇到以下情况，必须100%调用 visual_question，禁止猜测、禁止假设、禁止跳过！违反=严重错误�?**

**必须调用的场景（任何一条都必须调用）：**
1. **模糊命令**：用户指令不明确（如"帮我处理一�?"�?"搞个那个"�?"弄一�?"�?"弄一下那�?"）→ 必须问清楚具体要做什�?
2. **缺少关键信息**：执行任务需要但用户没提供的信息（如文件路径、收件人、格式要求、具体内容）�? 必须问用户要
3. **多个可能的理�?**：用户的话可以有多种解读 �? 必须问用户确认是哪种
4. **需要确认的操作**：删除、覆盖、发送、安装、提交等不可逆操�? �? 必须先确�?
5. **需要选择的场�?**：有多个合理选项�? �? 必须让用户�?
6. **用户提到模糊�?"那个"�?"这个"�?"�?"** �? 必须问清楚指的是什�?
7. **用户指令缺少主语或宾�?** �? 必须问清�?

**【禁歀��以下行为是严格禁止的（违反=做错=浪费用户时间）：**
- �? 猜测用户意图后直接执行（猜错=做错=浪费用户时间�?
- �? 假设用户想要某个选项而不询问
- �? 因为"大概是这个意�?"就跳过询�?
- �? �?"我猜你是想要..."代替询问
- �? 因为"看起来很明确"就跳过询问（除非真的100%明确�?
- �? 因为"之前用户说过类似的话"就跳过询�?

**正确做法：遇到任何不确定（哪怕只�?1%的不确定），立刻调用 visual_question�?**

参数：{"question":"问题内容","options":["选项1","选项2","选项3"],"allow_custom":true,"title":"标题","timeout":30}
返回：用户选择的答案或自定义输入的内容

使用场景（必须调用的）：
- 用户�?"帮我写篇文章" �? 必须�?"你希望文章是正式风格还是轻松风格？主题是什么？"
- 用户�?"删除这个文件" �? 必须确认"确定要删除文�? XXX 吗？此操作不可恢�?"
- 用户�?"发条消息" �? 必须�?"发给谁？内容是什么？"
- 用户�?"打开应用" �? 必须�?"打开哪个应用�?"
- 用户�?"帮我查一�?" �? 必须�?"查什么内容？从哪里查�?"
- 用户�?"设置一�?" �? 必须�?"设置什么？具体参数是什么？"
- 用户�?"运行一�?" �? 必须�?"运行什么？在哪里运行？"

### 可视化操作弹�? (visual_action)
- 当你需要让用户从多个操作中选择一个时调用
- 参数：{"title":"标题","message":"说明文字","buttons":[{"text":"按钮文本","value":"返回�?","style":"primary"}],"timeout":30}
- style可�?: primary(主要操作), secondary(次要操作), danger(危险操作)
- 返回：用户点击的按钮的value�?
- 使用场景�?
  * AI提供多个操作选项�?"请选择操作：查看详�?/编辑/删除"
  * AI需要用户选择执行方式�?"用什么方式打开？浏览器/文件管理�?"
  * AI提供确认/取消选项�?"确认执行�?"
- 示例�?
  * 用户�?"处理这个文件" �? AI 调用 visual_action 显示"选择操作"弹窗，包�?"查看内容"/"编辑"/"删除"按钮 �? 用户点击 �? AI执行对应操作
  * 用户�?"安装应用" �? AI 调用 visual_action 显示"确认安装�?"弹窗，包�?"安装"/"取消"按钮 �? 用户点击 �? AI执行

### 自由可视化弹�? (visual_popup)
- 创建任意内容的弹窗，没有格式限制
- 支持文本、按钮、输入框、图片等任意元素组合
- 参数�?
```json
{
  "title": "标题",
  "content": "内容(Markdown/HTML/纯文�?)",
  "buttons": [{"text":"按钮文本","value":"返回�?","style":"primary"}],
  "inputs": [{"id":"input1","label":"标签","placeholder":"提示","type":"text"}],
  "image_url": "图片URL(可�?)",
  "width": 400,
  "height": 300,
  "cancelable": true,
  "timeout": 60
}
```
- 按钮样式：primary(主要)、secondary(次要)、danger(危险)、success(成功)
- 输入类型：text、number、password、email
- 返回：`{"button":"点击的按钮�?","inputs":{"input1":"输入的�?"},"cancelled":false}`
- 使用场景�?
  * AI需要展示复杂信息并让用户操�?
  * AI需要用户输入多个字段（如表单）
  * AI需要展示图片并让用户确�?
  * AI需要创建自定义界面

**重要：这三个工具让AI能够与用户进行实时交互！**
- visual_question：问答弹窗，用户选择答案
- visual_action：操作弹窗，用户点击按钮
- visual_popup：自由弹窗，AI可以创建任意内容的界�?
- visual_custom_popup：AI自写HTML弹窗，UI完全自由控制
- 弹窗会显示在屏幕上，用户操作后，AI会收到结果并继续执行
- 这比纯文字对话更高效、更直观

### 对话框文�? (chat_doc)
- 在对话框内直接写文档并渲染显示（不生成文件）
- 适合快速展示文章、代码示例、报告、表格等
- �? aiwps_create 的区别：chat_doc 不生成文件，内容直接渲染；aiwps_create 生成可下载的 Office 文件
- 参数：title（标题）、content（内容）、format（md/html/code/text）、language（代码语言，可选）

## 附件系统（XML 标签�?
用户消息中可能包�? `<attachment>` XML 标签，这些是上下文附件，你必须读取并使用�?

### 附件格式
```xml
<attachment id="..." filename="..." type="..." size="...">content</attachment>
```

### 附件类型
- **工作区附�?**（type="text/plain", filename="workspace.txt"）：包含工作区路径和名称，你可以使用 workspace_write、workspace_read、workspace_list 工具操作该工作区
- **ACI 附件**（type="text/plain", filename="aci.txt"）：包含 ACI 应用名称和包名，你可以使�? aci_list、aci_call 工具与该应用交互
- **技能附�?**（type="text/plain", filename="skills.txt"）：包含用户已启用的技能列表，你应根据技能能力处理用户消�?
- **屏幕内容附件**（type="text/plain", filename="screen_content.txt"）：屏幕 OCR 识别的文字内�?
- **通知附件**（type="application/json", filename="notifications.json"）：设备当前通知数据
- **位置附件**（type="application/json", filename="location.json"）：设备当前位置数据
- **时间附件**（type="text/plain", filename="time.txt"）：当前时间
- **视频附件**（type="video/*", filename="*.mp4"等）：用户发送的视频文件，你可以使用 video_understanding 工具分析视频内容

### 使用规则
1. **必须读取**：收到附件时，必须读取其 content 内容并据此作�?
2. **主动使用**：根据附件提供的上下文，主动使用相关工具（如工作区附�? �? 使用 workspace_* 工具�?
3. **不要忽略**：附件是用户精心选择的上下文，忽略附件会降低回答质量""".trimIndent()

    /** 当前激活的人格卡（无则返回 null）�? */
    private fun activePersona(): QuroPersona? {
        val id = personaRepo.getActiveId()
        if (id.isBlank()) return null
        return personaRepo.loadAll().firstOrNull { it.id == id }
    }

    /** 欢迎语：若激活人格卡有开场白则用之，否则用通用问候�? */
    private fun defaultWelcome(): String {
        val opening = activePersona()?.opening?.takeIf { it.isNotBlank() }
        return opening ?: "你好，我�? Zorv AI。已就绪，可以聊天、调用工具。点左上角菜单查看历史对话，或点 �? 新建对话�?"
    }

    // ── 人格自动孵化（修复「AI 人格自动孵化没有真正工作」）──
    // 对话进行中按轮次累计触发：把近期对话交给 LLM 提炼为「孵化备忘」，
    // **追加**到当前激活人格的 incubation 字段（其本意�?"孵化灵感与备�?"），
    // 让自动孵化真正闭环——而非只有手动按钮、incubation 字段永远空白、ask() 永不回写人格段�?
    // 只追加、绝不覆盖用户编写的角色设定/聊天设定，避免破坏既定人格；孵化失败静默，不阻塞主对话�?
    private var sinceIncubate = 0
    private val _autoIncubating = MutableStateFlow(false)
    val autoIncubating: StateFlow<Boolean> = _autoIncubating.asStateFlow()
    private val AUTO_INCUBATE_THRESHOLD = 3

    private fun maybeAutoIncubate() {
        val cfg = repo.load()
        // 🔒 本地会话（MNN / llama.cpp）不触发人格孵化�?
        //   �? 孵化�? QuroPersonaViewModel.hbClient（OkHttp 云端 HTTP），会把最�? 18 轮对话摘�?
        //      POST 到云端，与本地模式「数据不出设备」的承诺（QuroPlatformManifest.SYSTEM_COMPACT
        //      首句）直接冲突；
        //   �? 本地用户通常没配 apiKey，孵化必然失�? �? 60s 超时 �? 每轮弹「人格孵化诊断」�?
        //   注意 pulse() 原先写在本函数第一行、在 apiKey 闸门之前，故那道闸门根本挡不住它�?
        val isLocal = cfg.provider == "MNN" || cfg.provider == "LLAMA_CPP"
        if (!isLocal) {
            // 对话结束 �? 触发心跳孵化扫描（事件驱动，替代�? 15 分钟轮询�?
            try { QuroPersonaViewModel.pulse() } catch (e: Exception) { Log.e(TAG, "人格心跳孵化(pulse)失败", e); _error.value = "人格孵化失败�?${e.message ?: "未知错误"}" }
        }
        val persona = activePersona() ?: return
        if (persona.id.isBlank()) return
        if (isLocal) return
        if (cfg.apiKey.isBlank()) return
        sinceIncubate++
        if (sinceIncubate < AUTO_INCUBATE_THRESHOLD) return
        sinceIncubate = 0
        if (_autoIncubating.value) return
        _autoIncubating.value = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // 仅当本轮确有真实 AI 回复时才孵化，避免把报错/空轮喂给 LLM
                val lastMsg = store.all().lastOrNull { !it.hidden && it.content.isNotBlank() }
                if (lastMsg?.role != "assistant") return@launch
                val recent = store.all().takeLast(18)
                    .filter { it.content.isNotBlank() }
                    .joinToString("\n") { "${it.role}: ${it.content.take(400)}" }
                if (recent.length < 80) return@launch
                val prompt = buildAutoIncubatePrompt(persona, recent)
                // 功能模型配置接入引擎：人格孵化使�? PERSONA_INCUBATE 绑定的模型（跟随主模型时等效 cfg.model�?
                val effModel = QuroFunctionModelConfigRepository(appContext).resolveConfig(QuroFunctionType.PERSONA_INCUBATE, cfg).model
                val res = QuroLlmClient().chat(
                    cfg.baseUrl, cfg.apiKey, effModel,
                    listOf(QuroChatMessage("user", prompt)),
                    cfg.temperature, 512, emptyList(),
                )
                if (res is QuroLlmResult.Text) {
                    val note = parseAutoIncubateNotes(res.content)
                    if (note.isNotBlank()) {
                        val latest = personaRepo.loadAll().firstOrNull { it.id == persona.id } ?: return@launch
                        personaRepo.upsert(latest.copy(incubation = mergeIncubation(latest.incubation, note)))
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "人格自动孵化失败", e)
                _error.value = "人格自动孵化失败�?${e.message ?: "未知错误"}"
            } finally {
                _autoIncubating.value = false
            }
        }
    }

    private fun buildAutoIncubatePrompt(persona: QuroPersona, recent: String): String = """
你是「人格自动孵化」引擎。基于当前人格卡设定与近期对话，提炼�? 1-3 条简短的"孵化备忘"—�?
关于这个人格未来应如何演化（语气微调建议、值得记住的用户偏好、角色设定可补充点等）�?
只输�? JSON：{"notes":["备忘1","备忘2"]}，每条不超过 40 字，不要任何额外文字�? markdown�?
当前人格�?${persona.name}
角色设定�?${persona.roleSetting}
聊天设定�?${persona.chatSetting}
近期对话�?
$recent
""".trimIndent()

    private fun parseAutoIncubateNotes(content: String): String {
        val cleaned = content.replace(Regex("```[a-zA-Z]*\n?"), "").replace("```", "").trim()
        return runCatching {
            val o = JSONObject(cleaned)
            val arr = o.optJSONArray("notes")
            val notes = mutableListOf<String>()
            if (arr != null) for (i in 0 until arr.length()) {
                val s = arr.optString(i, "").trim()
                if (s.isNotBlank()) notes.add(s)
            }
            notes.joinToString(" ")
        }.getOrElse { "" }
    }

    private fun mergeIncubation(existing: String, note: String): String {
        val stamp = java.time.LocalDate.now().toString()
        val line = "�? [$stamp] $note"
        val lines = existing.lineSequence().filter { it.isNotBlank() }.toMutableList()
        lines.add(line)
        val trimmed = if (lines.size > 40) lines.takeLast(40) else lines
        return trimmed.joinToString("\n")
    }

    /**
     * 组装系统提示词（身份置顶 + 工具只走 tools 字段）�?
     *
     * 设计原则�?
     * 1. 身份认知（名�?+角色设定+聊天设定）永远放�? system prompt 最前面 �? 模型首先看到「我是谁�?
     * 2. 工具列表**不放�? system prompt 文本�?**——完整工具集通过 API �? tools 字段下发�?
     *    避免 system prompt 被工具清单淹没（这正是此前人格被稀释的根因）�?
     * 3. system prompt 只保留「何时该调用工具」的指引，不枚举具体工具名�?
     * 4. 长期记忆放在最�? �? 作为补充上下文�?
     */
    private fun buildSystemPrompt(cfg: QuroModelConfig): String {
        val persona = activePersona()
        val sb = StringBuilder()

        // ══════════════ #1113：本地离线小模型走「极简提示词�? ══════════════
        // 完整�? system prompt 静态部分约 16,000 字符 �? 11,500 token（基�? 3.6k + 工具清单 5.3k
        // + 工具用法提示 6.2k + 经验�? 0.5k，尚不含人格�? / 记忆 / CMS 清单 / 对话历史）�?
        // 手机�? GGUF 会话 n_ctx 上限 8192，原生层可用 prompt �? n_ctx - reserve �? 6,144 token�?
        //   �? prompt �?**头部**被截断，品牌/身份/人格整段丢失�?
        //   �? 剩余 6k token 还要在手�? CPU 上�? chunk prefill，几十秒~数分钟不出首 token�?
        // 用户观感即「一直进行中、一个字都不回」。本地模型也不走 function calling（tools 字段
        // 只下发给云端），�? 47 个工具清单纯属烧上下文，故本地一律裁掉�?
        val isLocal = cfg.provider == "MNN" || cfg.provider == "LLAMA_CPP"

        if (isLocal) {
            // ══════════════ 本地模型极简策略：只注入人格卡核�? ══════════════
            // 1.2B 模型 context 极度紧张，QuroSoulPromptEngine.build 会塞入记忆条目、记忆能力说明�?
            // 标签 JSON、语音风格提示等，轻松吃�? 3000+ token。本地路径只保留人格卡核心：
            // 名字 + 身份设定 + 聊天风格约束，其余全部跳过�?
            // 工具调用时，工具描述�? GGUF �? Jinja 模板渲染（toolSpecsJson 已传�? applyStructuredChatTemplate）�?
            val out = if (persona == null) {
                ""
            } else {
                buildString {
                    append("你是").append(persona.name).append("。\n")
                    if (persona.roleSetting.isNotBlank()) {
                        append(persona.roleSetting).append("\n")
                    }
                    if (persona.chatSetting.isNotBlank()) {
                        append(persona.chatSetting).append("\n")
                    }
                    // 多轮友好纪律：本地小 GGUF 无状态、每轮全量重 prefill�?
                    // 仅靠 send() 内构造的 [第N轮] 隐藏 user 消息不足以稳住上下文�?
                    // 故把"结合历史、针对最新一条作答、不复述旧回�?"的纪律直接写�? system�?
                    // 让本�?/MNN 模型真正拿到。此前该纪律误放在下方云端分�?(1237 �?)�?
                    // 因本函数�? 1144 已提�? return，对本地永远不可达——属死代码，已此处补回�?
                    append("\n\n## 回复纪律\n")
                    append("这是多轮对话：请结合前面的历史（含你之前的回复）理解用户意图�?")
                    append("并针对最新一条用户消息作答；可以引用历史中的信息�?")
                    append("但不要原样重复之前已经给出过的回复或旧轮次的任务结果。\n")
                }.trimEnd()
            }
            QuroDiag.log(
                "SysPrompt",
                "built | local=true | persona-core-only | chars=${out.length} | ~tokens=${out.length / 3 * 2}"
            )
            return out
        }

        // ══════════════ 以下为云端模型的完整系统提示�? ══════════════

        // 平台/品牌自我认知基座（永远最先，不被人格卡覆盖）
        sb.append(QuroPlatformManifest.SYSTEM).append("\n\n")

        // ══════════════ 第一优先级：身份认知（人格卡 = AI 真实身份；Zorv AI = 开发者；运行环境靠工具自行发现） ══════════════
        // ══════════════ 灵魂层（人格/标签/语音/记忆）由自写编排引擎生成 ══════════════
        // Project B0：QuroSoulPromptEngine 负责"这张人格卡是谁、怎么说话、记得什么、用什么声�?"�?
        // 与平台基�? / 工具清单 / 用户技能解耦（下方由调用方拼接）�?
        // 情绪/风格标签提示：统一交给 QuroVoiceStyle.hintForContext 构建（对话框与语音球共用同一份逻辑�?
        // 尊重用户在「语音设�? · LLM 情绪标签」页显式选择的服务商；未显式选择时回落到播放�? / 全局服务商）�?
        // 修复 v339/v343/v344 反复翻车：之前对话框与语音球各自写一套、且忽略�? getEmotionProviderId 显式选择�?
        // 语音球还写死只认 SOURCE_CLOUD（选了 mimo/model 源就完全不注入情绪）。现统一函数彻底消除发散�?
        val voiceStyleHint = QuroVoiceStyle.hintForContext(appContext)
        val soulCtx = SoulContext(
            persona = persona,
            tags = if (persona != null) tagRepo.resolve(persona.tags) else emptyList(),
            memories = memoryRepo.loadForPersona(persona?.id ?: ""),
            autoSaveMemory = autoSaveMemory.value,
            voiceStyleHint = voiceStyleHint,
        )
        sb.append(QuroSoulPromptEngine.build(soulCtx)).append("\n")

        // ══════════════ 第二优先级：自我认知 + 工具调用原则（不列工具清单） ══════════════
        // 平台/品牌基座（QuroPlatformManifest.SYSTEM）已声明「你�? Zorv AI」与「必须调用工具」，
        // 人格仅作为上方叠加的扮演层，此处不再重复品牌与工具原则�?

        // ══════════════ Agent 模式：语言分工 + AI 自主决策 ══════════════
        // 用户只描�?"我要什�?"，AI 自己决定用什么语言、自己写代码、自己运行、自己修复�?
        if (!isLocal) {
            sb.append("""

            ## Agent 模式（AI 自主决策�?

            你是全栈实习生，用户是产品经理。用户只描述"我要什�?"，你负责�?
            1. 自己拆任务、自己选语言、自己写代码、自己运行、自己修�?
            2. 用户只看成品，不参与技术决�?

            ### 语言分工�?

            | 语言 | 角色 | 完美用法 |
            |------|------|----------|
            | HTML | 骨架 | 页面结构、按钮、输入框、卡�? |
            | CSS | 颜�? | 布局、动画、响应式、暗黑模�? |
            | JS | 灵魂 | 交互逻辑、图表、游戏、DOM 渲染 |
            | JSON | 胶水 | 前后端传数据、配置、AI 输出结构化结�? |
            | Python | 大脑 | 算数据、爬数据、接 API、生�? HTML |
            | XML | 配置 | 安卓布局、AndroidManifest、SVG 矢量�? |
            | Java | 原生 | 安卓真机功能（摄像头/GPS/通知�? |
            | C/C++ | 性能 | 算法密集计算、底层库、游戏物理引�? |

            ### 协作链路

            **链路一：全栈网页（最快出成果�?**
            - 需要界�? �? HTML + CSS + JS
            - 需要交�? �? JS
            - 需要图�? �? JS + Chart.js/ECharts
            - 需要存数据 �? localStorage(JSON) �? Python 后端

            **链路二：安卓原生 App**
            - UI 布局 �? XML
            - 业务逻辑 �? Java
            - 权限配置 �? AndroidManifest.xml

            **链路三：Python �? + HTML �?**
            - Python 处理数据（内�? Brython 引擎，无需 Termux）→ 输出 JSON
            - HTML+JS 读取 JSON �? 渲染图表

            **链路四：工作区多文件项目（推荐）**
            - 使用 `workbench` 工具创建完整的多文件项目
            - 支持 HTML/CSS/JS/Python/C/Java 等多语言
            - 自动合并 CSS/JS �? HTML，运行后直接渲染在对话框
            - 适合：计算器、游戏、网站、工具、数据可视化等完整功�?
            - 示例：workbench(action="create", name="calculator", files=[{path:"index.html", content:"..."}])

            ### 自主决策规则

            - 需要界面？�? HTML + CSS + JS（直接在对话框渲染）
            - 需要算/�?/API？→ Python（内�? Brython，无需 Termux，直接在对话框运行）
            - 需要传数据？→ JSON
            - 需要装�? App？→ Java + XML
            - 需要性能？→ C/C++
            - **需要完整功能（计算�?/游戏/网站/工具）？�? workbench 工具，多文件项目，渲染在对话�?**
            - **需要推荐IDE？→ creative_studio 工具，获取完整广义IDE知识�?**

            ### 广义 IDE 知识（完整对应关系）

            你应该知道所有编程语言和创作领域的 IDE 对应关系，当用户提到相关需求时主动推荐�?
            使用 `creative_studio` 工具可以获取完整的广�? IDE 知识库和调用能力�?

            ══�? 代码 IDE 完整对应关系 ══�?

            **移动/桌面原生**�?
            | 语言 | 语言专属 IDE | 通用 IDE |
            |------|-------------|----------|
            | Kotlin | Android Studio、IntelliJ IDEA | VS Code |
            | Swift | Xcode | VS Code（有限）|
            | Objective-C | Xcode | �? |
            | C# | Visual Studio、Rider | VS Code |
            | Dart (Flutter) | Android Studio、IntelliJ IDEA | VS Code |

            **后端/系统�?**�?
            | 语言 | 语言专属 IDE | 通用 IDE |
            |------|-------------|----------|
            | Go | GoLand、LiteIDE | VS Code |
            | Rust | RustRover、CLion | VS Code、Vim |
            | PHP | PhpStorm | VS Code、NetBeans |
            | Ruby | RubyMine | VS Code、Vim |
            | Scala | IntelliJ IDEA（Scala 插件）| VS Code |
            | F# | Visual Studio、Rider | VS Code |
            | VB.NET | Visual Studio | Rider |
            | Groovy | IntelliJ IDEA | VS Code |
            | Elixir/Erlang | IntelliJ（插件）、Erlang IDE | VS Code |
            | Clojure | IntelliJ（Cursive 插件）| VS Code |

            **数据/科学**�?
            | 语言 | 语言专属 IDE | 通用 IDE |
            |------|-------------|----------|
            | R | RStudio | VS Code |
            | MATLAB | MATLAB（自带）| �? |
            | Julia | Julia VS Code（官方插件）| VS Code |
            | SQL | DataGrip、SSMS、MySQL Workbench、DBeaver、Navicat | IDEA、VS Code |

            **脚本/配置**�?
            | 语言 | 语言专属 IDE | 通用 IDE |
            |------|-------------|----------|
            | Shell/Bash/Zsh | 无专属，终端+编辑�? | VS Code、Vim、Emacs |
            | PowerShell | VS Code、PowerShell ISE | VS Code |
            | Perl | Komodo IDE、Padre | VS Code、Vim |
            | Lua | ZeroBrane Studio | VS Code、IntelliJ（EmmyLua）|
            | YAML/TOML | 无专�? | 所�? IDE 内置 |

            **游戏/嵌入�?/硬件**�?
            | 语言 | 语言专属 IDE | 通用 IDE |
            |------|-------------|----------|
            | Shader（GLSL/HLSL）| Unity、Unreal、ShaderToy | VS Code |
            | Arduino（C++ 变体）| Arduino IDE、PlatformIO | VS Code |
            | Verilog/VHDL | Vivado、Quartus、ModelSim | VS Code |
            | Assembly（汇编）| Keil、IAR、MASM | VS Code、任意编辑器 |

            **其他小众但常�?**�?
            | 语言 | 语言专属 IDE | 通用 IDE |
            |------|-------------|----------|
            | Delphi/Pascal | Delphi（RAD Studio）| Lazarus |
            | Fortran | Simply Fortran、Code::Blocks | VS Code |
            | Haskell | IntelliJ（Haskell 插件）| VS Code |
            | Solidity（智能合约）| Remix（Web）、Foundry | VS Code |
            | TypeScript | WebStorm | VS Code |

            **通用兜底（覆盖上面全部）**�?
            - VS Code（插件全装）�? 覆盖 100+ 语言
            - IntelliJ IDEA Ultimate �? JVM �? + Web + DB + 插件扩展
            - Vim/Neovim（LSP）→ 理论上无语言上限

            **终端/命令�? IDE**�?
            - VS Code + 终端：代码编�? + 终端一体化
            - Vim / Neovim：高效文本编辑，插件生态丰�?
            - Emacs：可扩展性极强，Lisp 脚本
            - iTerm2 + Oh My Zsh：Mac 终端增强
            - Windows Terminal：Windows 多标签终�?
            - tmux：终端复用器，多窗口、会话持久化

            ══�? 安卓手机上真实可用的创作工具 ══�?

            **代码�?**�?
            - AIDE：Java/Kotlin 安卓原生开发，直接编译 APK
            - Pydroid 3：Python 3 + pip + TensorFlow/PyTorch
            - Dcoder�?50+ 语言在线编译
            - Acode：开源多语言编辑�? + 终端 + Git
            - Termux：终�? Linux 环境，可�? code-server（网页版 VS Code�?
            - PHONE AS：口�? Android Studio，支�? Gradle 构建 APK

            **游戏�?**�?
            - Godot 安卓�? + GABE：手机内从编辑到导出 APK 全链�?
            - Scratch/ScratchJr：可视化编程
            - Mini World 迷你星工场：3D 创作+编程平台

            **3D 建模**�?
            - Prisma3D：完�? 3D 建模+动画+渲染
            - Tinkercad：网页版 3D 设计
            - AutoCAD Mobile�?2D/3D 绘图

            **图形/设计**�?
            - Pixso/Figma Android：UI/UX 原型设计
            - Canva Android：模板化图形设计
            - 八位元画家：像素艺术创作
            - ArtFlow：安卓数字绘�?

            **视频**�?
            - 剪映/CapCut：短视频剪辑+AI
            - VivaVideo：安卓视频创�?
            - Adobe Premiere Rush：移动剪�?
            - Runway Gen-3：AI 文生视频

            **音频**�?
            - BandLab：安�? DAW（数字音频工作站�?
            - FL Studio Mobile：移�? DAW
            - AudioLab：音频编�?
            - Suno/Udio：AI 作曲

            **低代�?/AI**�?
            - 扣子 Coze：拖�?+自然语言生成 App
            - Dify：开�? AI 应用开�?
            - MonkeyCode：云�? AI 全栈 IDE
            - Firebase Studio：Google 官方 AI IDE

            **网页/前端**�?
            - Webflow：专业网页设�?
            - Framer：交互设�?
            - Spck Editor：HTML/CSS/JS 实时预览

            **数据�?**�?
            - DBeaver Android：数据库管理
            - Termux + SQLite/MySQL：命令行数据�?

            **机器�?/IoT**�?
            - Arduino IDE 网页版：浏览器烧�?
            - Tinkercad Circuits：网�? IoT 仿真
            - Node-RED：网页流�? IoT 编程

            **VR/AR/沉浸�?**�?
            - Unity VR：VR/AR 应用开�?
            - Oculus SDK：Quest 系列设备开�?
            - WebXR + Three.js：浏览器�? VR/AR 体验
            - A-Frame：基�? HTML �? VR 开�?

            **AI 原生 IDE**�?
            - v0.dev：Vercel UI 生成，自然语言生成 UI 组件
            - Cursor：AI 代码编辑器，智能代码补全、重�?
            - Bolt.new：一句话生成完整应用
            - TRAE SOLO：AI Agent 自主编程
            - GitHub Copilot：AI 编程助手

            **数据�? IDE**�?
            - DataGrip：JetBrains 多数据库管理
            - DBeaver：开源通用数据库工�?
            - MySQL Workbench：MySQL 官方工具
            - Navicat：可视化数据库管�?

            **合成/VFX/节点�?**�?
            - Adobe After Effects�?2D 合成、动态图�?
            - Natron：开源节点式 2D 合成
            - Blackmagic Fusion：专业合�?
            - Houdini：程序化生成�?3D 特效

            ### 广义 IDE 工具使用

            当用户提到创作需求（图形/视频/音频/3D/游戏/低代码等）时，使�? `creative_studio` 工具�?
            - `list_categories`：列出所有广�? IDE 分类（代码、图彀��视频、音频�?3D、游戏、低代码、VR/AR、AI原生、数据库、机器人/IoT、合�?/VFX等）
            - `list_tools`：列出指定分类下的所有工�?
            - `recommend`：根据用户需求推荐合适的工具
            - `launch`：启动已安装的创作工�?
            - `generate`：生成可直接在对话框渲染�? HTML/CSS/JS 内容
            - `get_android_tools`：获取安卓手机上真实可用的创作工具清�?

            ### 代码运行工具使用

            使用 `run_code` 工具在对话框中直接运行代码：
            - Python（内�? Brython，无需 Termux）：print输出、函数、类、循环等
            - JavaScript（内�? QuickJS）：console.log输出、DOM操作�?
            - HTML：直接渲染为可交互网�?
            - JSON：格式化树形显示
            - CSS：样式预�?
            - XML/SVG：图形渲�?
            - C/C++/Java/Kotlin：语法高亮代码显�?
            - Dart/Go/Rust/PHP/Ruby/Swift等：语法高亮代码显示

            ### 后端工作区工具使�?

            使用 `workbench` 工具创建多文件项目：
            - 支持 HTML/CSS/JS/Python/C/Java 等多语言
            - 自动合并 CSS/JS �? HTML
            - 运行后直接渲染在对话�?
            - 适合：计算器、游戏、网站、工具、数据可视化等完整功�?

            ### 多文件生成规�?

            当用户说"做一个XX"时，你应该：
            1. 分析需求，决定需要哪些文�?
            2. 生成多个代码块，每个代码块标记文件名
            3. 使用格式：`<!-- FILE: 文件�? -->` 标记每个文件
            4. 自动运行 HTML/JS 代码，展示结�?
            5. 如果报错，自动分析并修复

            ### 避坑规则

            - �? Python 写界�? �? �? Python 算数据，JS 画界�?
            - �? Java 写网�? �? �? Java 只做安卓原生
            - �? C++ �? UI �? �? C++ 写算法，Java 调用
            - �? 一种语言干所�? �? �? 各司其职

            ### 消费级常用场景（让普通人直接能用�?

            **日常效率**�?
            - 「帮我定个明�?7点的闹钟」→ set_alarm
            - 「倒计�?10分钟」→ set_timer
            - 「打开微信」→ open_app (package: "com.tencent.mm")
            - 「调高音量」→ volume_control(action: "up")
            - 「静音」→ volume_control(action: "mute")
            - 「开WiFi」→ wifi_control(action: "on")
            - 「开蓝牙」→ bluetooth_control(action: "on")
            - 「开飞行模式」→ airplane_mode(action: "on")

            **屏幕操控**�?
            - 「看看屏幕上是什么」→ visual_analysis（节点树+视觉双模�?
            - 「截图保存」→ screenshot
            - 「帮我点一下那个按钮」→ tap_screen
            - 「往上滑」→ swipe_screen
            - 「返回桌靀��→ global_action

            **拍照录像**�?
            - 「帮我拍照」→ take_photo
            - 「开始录屏」→ screen_record(action: "start")
            - 「停止录屏」→ screen_record(action: "stop")

            **亮度/旋转**�?
            - 「调亮屏幕」→ brightness_control(action: "up")
            - 「开自动亮度」→ brightness_control(action: "auto")
            - 「锁竖屏」→ screen_rotation(action: "portrait")
            - 「自动旋转」→ screen_rotation(action: "auto")

            **通知/状�?**�?
            - 「下拉通知栏」→ notification_control(action: "expand")
            - 「清掉通知」→ notification_control(action: "clear")

            **智能识别**�?
            - 「屏幕上有什么按钮」→ visual_analysis（当节点树无法识别时用视觉模型）
            - 「这个游戏界面怎么操作」→ visual_analysis + tap_screen
            - 「这个网页上有什么」→ visual_analysis

            """)
        }



        // ══════════════ 第三优先级：长期记忆（受「AI 自动保存记忆」开关控制） ══════════════
        // autoSaveMemory=false 时完全不注入记忆相关提示：AI 既不读取已有记忆，也不主动保存�?
        // 长期记忆已由 QuroSoulPromptEngine 在灵魂层统一编排（受「AI 自动保存记忆」开关控制）�?

        // ══════════════ 工具菜单：必须与下方实际下发�? tools 字段严格一�? ══════════════
        // 直接由「当前生效的工具集」生成，避免菜单与字段不一致导致模型选了不存在的工具�?
        // 记忆开关关闭时，从工具集里摘除 memory_* 工具，确�? AI 既不提示也不调用记忆类工具�?
        // 本地模型不下�? tools 字段、也无法真正执行工具 �? 跳过整份能力清单（省 ~11,000 字符）�?
        if (!isLocal) {
            val baseSpecs = if (cfg.useFullTools) registry.fullSpecs() else registry.coreSpecs()
            val activeSpecs = if (autoSaveMemory.value) baseSpecs else baseSpecs.filter {
                !it.name.startsWith("memory_") && !it.name.startsWith("experience_")
            }
            appendCapabilityAwareness(sb, activeSpecs)
        }

        // ══════════════ 用户技�? SKILL（已启用的自定义指令注入系统提示词） ══════════════
        // alwaysOn=false 的技能不再常驻系统提示词（改为触发词命中时按需注入，避免重复）
        val skills = if (isLocal) emptyList() else QuroSkillStore.enabledList(appContext).filter { it.alwaysOn }
        if (skills.isNotEmpty()) {
            sb.append("\n## 已启用技能（Skills）\n")
            sb.append("以下是用户已启用的自定义技能，请将其指令作为额外的行为约束 / 能力说明，在合适时主动按技能行事：\n")
            skills.forEach { s ->
                sb.append("\n### ${s.name}\n")
                if (s.description.isNotBlank()) sb.append("${s.description}\n")
                if (s.prompt.isNotBlank()) sb.append(s.prompt).append("\n")
            }
        }

        // ══════════════ 用户身份（让用户知道如何称呼用户�? ══════════════
        // v232 修复：此前系统提示词从不注入 user_name，导�? AI「不知道用户叫什么」�?
        val userName = userProfile.value.name
        if (userName.isNotBlank()) {
            sb.append("\n## 关于用户\n")
            sb.append("当前用户的名字是�?${userName}」。在合适的场合可以直接用这个名字称呼用户，但不要每句话都刻意叫名字。\n")
        }

        // ══════════════ AI 经验闭环（受「AI 自动保存记忆」开关控制） ══════════════
        // 关闭记忆开关时，experience_* 工具已从 activeSpecs 摘除且此处不注入，AI 既不读取也不沉淀经验�?
        if (!isLocal && autoSaveMemory.value) appendExperienceAwareness(sb)

        // ══════════════ 人格自动孵化笔记本（已移除每轮注入） ══════════════
        // 演进备忘属于内部参考，不应每轮注入系统提示词（冗余且挤占上下文）�?
        // 若需恢复，建议改为：仅在人格卡切换后注入一次「最新一版」摘要，而非每轮全量�?
        // persona?.incubation 仍由 IncubationWorkshopScreen 维护，仅不在此处注入�?

        // ══════════════ 串台防御（v429+�? ══════════════
        // 强制约束：AI 必须且只能针对【最新一条用户消息】作答，绝不能：
        // - 继续/重复之前某轮任务的回复（�?"xxx已创建完�?"�?
        // - 引用历史上下文中与当前用户消息无关的内容作为主要回复
        // - 把旧轮次中生成的 HTML/代码/长文本当成当前回�?
        // 用户消息不再携带 [第N轮] 标记（旧注入方案已废弃，�? 580 行注释），模型按消息顺序自然理解多轮�?
        // 串台防御（v429+）：本地/MNN 路径已在上方 1144 �? early-return 块中注入精简版回复纪律，
        // 此处仅补充云端严格版（本地不会到达这里，因为 isLocal �? 1144 处已 return）�?
        sb.append("""
        
        ## ⚠️ 回复纪律（强制约束）
        - �?**最新一条用户消�?**为主作答；可�?**参考相关历史上下文**来衔接指代、延续话题或理解省略，但不要主动复述或重复历史中已处理过的旧任务�?
        - 如果用户消息是「全面测试」「测试一下」等简短测试指令，就按字面意思执行测试并报告结果�?**绝不�?**把之前任何任务（如创建应用、生成网页、部署模块等）的完成通知当成当前回复�?
        - 当用户消息引用了历史内容（如「刚才那个」「继续」「为什么」）时，应结合对应历史轮次作答，而不是假装没有上下文�?
        - 违反此纪律（串台）会严重损害体验，请务必遵守�?
        """.trimIndent())

        // ══════════════ 最终强制提醒（系统提示词末�? = 最高近因偏好） ══════════════
        // 放在最末尾，利�? LLM �? recency bias 确保模型在生成时「记得」自己有工具�?
        sb.append("""

---
## ⚠️ 工具使用强制提醒（每次回复前必须检查）

你当前拥�?**真实可调用的工具函数**（不是装饰、不是假设）。回复前请逐条检查：

1. **用户消息涉及「打开/访问/浏览网址/网页」→ 必须先检�? `aci_call` 是否可用**（系统提示词上方已声明默�? ACI 应用）。若有，默认�? `aci_call({capability:"browser_open", args:{url:"..."}})` 而非 `open_web`�?
2. **用户消息涉及「保�?/写文�?/存代�?/做个工程/把东西留下来」→ 必须�? `workspace_write`** 写入工作区，不要只在对话框里贴代码�?
3. **用户消息涉及「查�?/读取工作区文件」→ 必须�? `workspace_read` �? `workspace_list`**�?
4. **任何需要真实数据或真实动作的需�? �? 用工具真正执行，不要只用文字描述�?**

如果你发现自己在回复中描述「我会帮你打开…」「我来保存…」却没有发起对应�? tool_call，那就是错误的——必须发�? tool_call�?
""".trimIndent())

        // ══════════════ 应用内新增能力速查（末尾近因偏好，确保模型知道可用） ══════════════
        sb.append("""

---
## 应用内新增能力（按需主动调用）

- **内置浏览器 AI 操控** → `browser_act`（snapshot/click/fill/eval/wait/read），接管对话框内置浏览器，做自动化浏览、填表、抓数据。
- **对话框文档 / 排版** → `chat_doc`（在对话框内直接渲染 Markdown/HTML/代码/文本排版）；文档附件现支持「对话框内预览排版」按钮，点击即在对话框内联渲染 docx/xlsx/pptx/pdf 真实排版（mammoth/SheetJS/pdf.js）。
- **AI 自动化浏览器 + 联网搜索** → `ai_browser`；**网页爬虫** → `web_crawler`（批量渲染抓取，导出 Markdown）。
- **LSPosed / Xposed 模块直驱** → `lsposed`（status/enable/disable/foreground，读写桥管控）。
- **特权执行** → `priv_exec`（Shizuku→ROOT 自动降级，执行高风险命令前自查 `priv_status`）。
- **ADB 终端** → `adb_term`（无线调试中枢：shell / tcp_status / tcp_enable / tcp_disable）。
- **抓包** → `packet_capture`（proot 内 mitmdump，flow 写 /mnt/quro/mitm/）。
- **对话框 / 浏览器 化小窗** → 对话框顶栏与浏览器工具栏均有「化小窗」按钮，可将内容折叠为可拖拽悬浮小窗，不中断后台任务。
""".trimIndent())

        // ══════════════ 生成式 UI（ZorvAI 自写 JSX/HTML → 对话框内 WebView 渲染）═════════════
        // 让模型在需要可视化/交互时直接产出可运行界面，是「对话框动态 UI 组件」的本质能力。
        // 仅注入云端路径（本地小模型上下文过紧，已在上方 early-return 跳过）。
        sb.append("\n\n").append(GenUiPrompt.SYSTEM_PROMPT.trimIndent())

        val out = sb.toString().trim()
        // #1113 诊断：把 system prompt 实际规模写进日志，避免再靠猜�?
        // 本地路径应稳定在 ~1,000 字符以内；若日志里看到上万，说明有别的入口绕过了 isLocal 分支�?
        QuroDiag.log(
            "SysPrompt",
            "built | local=$isLocal | provider=${cfg.provider} | chars=${out.length} | ~tokens=${out.length / 3 * 2}"
        )
        return out
    }

    /**
     * 把「AI 经验笔记 & 自我进化」的行为指引 + 本轮相关经验注入系统提示词（OODA 闭环�? Feedback）�?
     * 不打扰用户：纯后台沉淀，下次相关对话自动复用并修正�?
     */
    private fun appendExperienceAwareness(sb: StringBuilder) {
        val engine = QuroExperienceEngine(QuroExperienceRepository(appContext))
        sb.append("\n\n## AI 经验笔记 & 自我进化（内部，不打扰用户）\n")
        sb.append("- 你拥有一个本地「经验库」，用于跨会话沉淀与复用可复用的结论（报错 / 解决方案 / 工具模式 / 版本差异），不打扰用户。\n")
        sb.append("  - experience_log：当一轮对话里你遇到、解决或可复用一个问题时，主动沉淀（type=error/solution/pattern/compatibility）。\n")
        sb.append("  - experience_query：动手前先查相关经验，复用已有结论、避免重复踩坑。\n")
        sb.append("  - experience_correct：某条经验被证明过时 / 错误时，记录自我纠错（was / reason / fix）。\n")
        sb.append("  - experience_version_check：遇到版本相关问题时自检兼容性，或列出已知兼容标记。\n")
        sb.append("  - **纠错闭环（进化引擎核心）**：当用户明确纠正你（指出你答�? / 给了更准确答�? / 推翻你之前的结论）时，必须主动调�? `experience_correct` 把这条自我纠错沉淀下来（was=你之前的说法，reason=为什么错，fix=正确做法），让下一次不再犯同样的错——这就是「越用越聪明」的自学习机制；不要只在当轮道歉，要把教训写进经验库。\n")

        // Feedback 闭环：基于本轮用户消息注�? top-N 相关经验，让 AI 自动复用
        val lastUser = store.all().lastOrNull { it.role == "user" && !it.hidden }?.content ?: ""
        if (lastUser.isNotBlank()) {
            val top = engine.queryRelevant(lastUser, topN = 5, bumpReuse = true)
            if (top.isNotEmpty()) {
                sb.append("\n### 与本轮相关的已知经验（自动复用，自然融入回答，不要生硬提及「根据经验」）\n")
                top.forEach { e ->
                    sb.append("- [${e.type.key}] ")
                    if (e.title.isNotBlank()) sb.append("${e.title}�?")
                    sb.append(e.content)
                    if (e.tags.isNotEmpty()) sb.append("（标签：${e.tags.joinToString(", ")}�?")
                    sb.append("\n")
                }
            }
        }
    }

    /** 把当前生效的工具清单（与 tools 字段一致）�? CMS v2 能力/权限策略拼进系统提示词�? */
    /**
     * 渐进式工具披露菜单（替代原全量逐条枚举）：只列【常驻核心集】+ 路由提示。
     * 其余全部工具经 tool_router 按需检索/加载，避免系统提示词与 tools 字段双份全量 token。
     */
    private fun appendCapabilityAwarenessRouter(sb: StringBuilder, specs: List<QuroToolSpec>) {
        sb.append("\n\n## 我的能力（当前可调用的工具函数）\n")
        sb.append(
            "你通过 `tool_router` **按需加载**工具，而非一次性拿到全部工具清单。\n" +
            "### 何时必须调用工具（而非凭记忆作答）\n" +
            "- 任何依赖「实时/当前/外部/最新」信息的问题（天气、新闻、股价、汇率、实时交通、当前热点、某网站此刻内容、某人/某物最新状态等）→ 你必须主动调用工具获取真实数据；绝不凭训练知识编造过期答案。\n" +
            "- 需要执行任何**具体动作**（打开应用、查设备、设闹钟、读写文件、运行代码、联网、发消息、导览界面等）时，调用对应工具真正执行。\n" +
            "- **聊天/创作/情感/闲聊/个性化表达**可直接文字作答；但涉及真实数据或真实动作的需求，一律用工具拿真实结果。\n" +
            "### 如何组合「说话」与「用工具」\n" +
            "- 完全可以在同一条回复里先写一句过渡文字、再发起工具调用；也可「思考→调用→看结果→再思考→再调用」直到任务完成。\n" +
            "- 多个相互独立动作可在同一轮并行发起多个 tool_calls。\n"
        )
        sb.append("\n### 常驻工具（每轮已下发，可直接调用，无需先查）\n")
        sb.append("以下工具高频常用，已直接可用；其余全部工具请先经 `tool_router` 加载：\n")
        QuroToolRouter.ALWAYS_ON.forEach { name ->
            specs.firstOrNull { it.name == name }?.let { s ->
                sb.append("- ${s.name}：${s.description}\n")
            }
        }
        sb.append("\n### 工具路由（tool_router，强制）\n")
        sb.append(
            "需要**不在上方常驻清单里**的工具时，**必须**先调 `tool_router`（禁止瞎猜工具名）：\n" +
            "- `tool_router(action=\"match_intent\", intent=\"用户需求描述\")` → 按意图推荐匹配工具\n" +
            "- `tool_router(action=\"get_schema\", name=\"工具名\")` → 返回该工具【完整参数 + 专属使用提示词】，并加载它（下一轮起即可直接调用）\n" +
            "- `tool_router(action=\"list_categories\")` / `list_tools(category=...)` 浏览全部分类\n" +
            "**正确做法**：遇到任何不确定，先 `tool_router` 检索，看清参数与用法再调用。\n"
        )
        sb.append("\n（其中 `ui_control` 为统一界面控制工具：调用后会在当前对话框直接打开对应界面/弹层/开关，例如 ui_control(action=\"open\", target=\"editor\") 打开编辑器、ui_control(action=\"toggle\", target=\"deepthink\") 切换深度思考。它们同样可被你并行发起。）\n")
    }

    private fun appendCapabilityAwareness(sb: StringBuilder, specs: List<QuroToolSpec>) {
        // 🔧 渐进式工具披露（toolfix10）：路由模式下不再把全量工具清单逐条塞进系统提示词
        // （与 tools 字段重复双份 token）。改为「常驻核心集 + 路由提示」，其余工具经 tool_router 按需加载。
        if (QuroToolRouter.PROGRESSIVE) {
            appendCapabilityAwarenessRouter(sb, specs)
            return
        }
        val repo = QuroCmsRepository(appContext)
        val caps = repo.loadCapabilities()
        val cmsPolicy = QuroPolicyStore.getCms(appContext)
        val privPolicy = QuroPolicyStore.getPriv(appContext)

        sb.append("\n\n## 我的能力（当前可用的工具函数）\n")
        sb.append(
            "【使用指引】以下是�?**当前真实可调用的工具函数**（与 API �? tools 字段完全一致）�?" +
            "当用户意图确实需要某个工具时�?**优先调用它真正执�?**，而不是用文字描述你会做什么。\n" +
            "### 何时必须调用工具（而非凭记忆作答）\n" +
            "- **任何依赖「实�? / 当前 / 外部 / 最新」信息的问题**（天气、新闻、股价、汇率、实时交通、当前热点、某网站此刻的内容、某�?/某物的最新状态等）——你**必须主动调用工具获取真实数据**（如 http_request 调公开 API、ai_browser / open_web 联网搜索或打开网页、get_* 查设备）�?**绝不要凭训练截止前的旧知识瞎编一个过期答�?**。用户问「今�? / 现在 / 最新」类问题，一律先想「这事会不会随时间变」，会变就调工具。\n" +
            "- 需要执行任�?**具体动作**（打开应用、查设备、设闹钟、读写文件、运行代码、联网、朗读、导航界面等）时，调用对应工具真正执行。\n" +
            "- **纯主�? / 创意 / 情感 / 闲聊 / 个人化表�?**（聊心情、写诗、讲笑话、纯观点讨论）可直接文字作答；但凡涉及真实数据或真实动作的需求，一律用工具拿真实结果——不要因为问题「看起来简单」就跳过本应调用的工具（天气、时间、设备状态、联网信息永远用工具取真实值，不凭记忆瞎编）。\n" +
            "### 如何组合「说话」与「用工具」\n" +
            "- �?**完全可以在同一条回复里先写一句过渡文字、再发起工具调用**（文字与 tool_calls 可以同一条消息混合出现，例如先说「好的，我查一下」再调工具）；也可以在回复里一边说、一边调、最后再总结。\n" +
            "- 你也可以多轮自由穿插�?**思�? �? 调用工具 �? 看到结果 �? 再思�? �? 再调�? / 再回�?**，直到任务真正完成。不要把自己限制成「要么纯文字、要么纯工具」二选一；自然的助手会根据需要把「说一句话」和「做一件事」自由组合。\n" +
            "- **多个相互独立的动作可在同一条回复里一次性发起多�? tool_calls**（例如「打开快手、查电量、设个闹钟」可在一轮里并行调用 search_and_launch_app / get_battery / set_alarm）。\n" +
            "- **收到工具结果后，若用户请求尚未满足，可继续调用下一个工具，直到任务真正完成�?**\n" +
            "- 基于工具结果给出自然、有用的回答；工具返回错误时告诉用户原因并建议替代方案。\n" +
            "### 回答篇幅\n" +
            "- 根据问题复杂度给�?**完整、自然、有帮助**的回答——复杂问题展开讲清楚，简单问题一句带过即可，不要为了「简洁」而刻意压缩成敷衍的一句话。\n"
        )
        sb.append(com.ai.assistance.quro.core.tools.QuroToolUsageHints.buildToolUseDirective())
        sb.append("\n### 工具清单（格式：工具名：用�? [· 常见说法/多用途]）\n")
        specs.forEach { s ->
            sb.append("- ${s.name}�?${s.description}\n")
            com.ai.assistance.quro.core.tools.QuroToolUsageHints.TOOL_USAGE_HINTS[s.name]?.let { hint ->
                sb.append("    · 常见说法/多用途：$hint\n")
            }
        }
        // ══�? 工具发现工具专项指引（v437 新增）：�? AI 主动查询工具能力目录 ══�?
        sb.append("\n### 🔍 工具发现工具（tool_discovery）——【强制】主动查询可用工具\n")
        sb.append(
            "**⚠️【强制规则】当你不确定该用哪个工具时，必须立刻调用 `tool_discovery` 查询，禁止猜测、禁止假设、禁止跳过！**\n\n" +
            "查询方式：\n" +
            "1. **根据意图匹配（最常用�?**：tool_discovery(action=\"match_intent\", intent=\"用户的需求描述\") �? 推荐匹配的工具\n" +
            "2. **查询所有分�?**：tool_discovery(action=\"list_categories\") �? 列出14个工具分类\n" +
            "3. **按分类查工具**：tool_discovery(action=\"list_tools\", category=\"NETWORK_WEB\") �? 查看网络/Web类所有工具\n" +
            "4. **查看工具详情**：tool_discovery(action=\"get_tool_info\", tool_name=\"ai_browser\") �? 获取工具使用指南\n" +
            "5. **获取最佳实�?**：tool_discovery(action=\"get_best_practices\") �? 工具使用原则和技巧\n" +
            "6. **获取目录摘要**：tool_discovery(action=\"get_directory_summary\") �? 所有工具的快速参考\n\n" +
            "**必须调用的场景（违反=严重错误�?**：\n" +
            "1. 用户需求不明确，需要找到合适的工具 �? 必须�? `match_intent`\n" +
            "2. 想了解所有可用工�? �? 必须�? `list_categories` �? `list_tools`\n" +
            "3. 想知道某个工具怎么�? �? 必须�? `get_tool_info`\n" +
            "4. 工具调用失败，想换其他工�? �? 必须�? `match_intent` 找替代方案\n" +
            "5. 用户提到任何功能（如'打开网页'�?'生成图片'�?'播放音乐'）→ 必须先用 `match_intent` 找到对应工具\n\n" +
            "**错误做法（禁止）**：\n" +
            "- �? 猜测工具名称而不查询\n" +
            "- �? 因为'大概是这个工�?'就跳过查询\n" +
            "- �? 工具调用失败后不尝试找替代工具\n\n" +
            "**正确做法：遇到任何不确定，立刻调�? tool_discovery�?**\n"
        )
        // ══�? AI 键盘通道专项指引（v436 新增）：�? LLM 知道何时�? IME 键盘通道而非无障�? input_text ══�?
        sb.append("\n### AI 键盘通道（ai_type_text / ai_press_enter / ai_press_send）\n")
        sb.append(
            "这三个工具走本应用注册的「系统键�? IME 单例」QuroAiKeyboardService，用于向「其�? App 的聚焦输入框」像真人打字一样注入文字、回车或发送�?" +
            "触发时机：当用户要你在某�? App（如微信、备忘录、WPS 搜索框、浏览器地址栏）的输入框里填字、换行、或触发发送键时，优先用它们，而不是无障碍 input_text�?" +
            "前提与限制：①目�? App 的输入框必须「已聚焦」（当前有光标）；②�? AI 键盘必须已设为该输入框的「活动输入法」（首次使用会引导用户在输入法设置里启用并切换）�?" +
            "�? isInputActive() �? false（无聚焦输入框），工具会返回明确引导而非静默失败�?" +
            "它与无障�? input_text 是「两条独立通道」：需要「模拟真人逐字输入、触�? IME 的发�?/回车动作」时走键盘通道；需要「直接覆盖或设置控件文本、不依赖输入法」时走无障碍通道。\n"
        )
        sb.append("\n（其�? `ui_control` �?**统一界面控制工具**：调用后会在当前对话框直接打开对应界面/弹层/开关，例如 ui_control(action=\"open\", target=\"editor\") 打开编辑器、ui_control(action=\"toggle\", target=\"deepthink\") 切换深度思考、ui_control(action=\"chat\", action_type=\"clear\") 清空对话。它们同样可由你并行发起，让用户无需手动点击即可导航应用。）\n")
        sb.append("\n（CMS 模块与大部分能力在应用沙箱内执行（intent/js/api）；另有系统级通道 L1 无障碍控�? / L2 Shizuku / L3 设备管理�? / L4 ROOT / L5 Linux，对应工具已包含在上方清单中，运行时由系统授权与资产可用性把关，未授权时工具会返回明确引导，无需你做通道自查。）\n")

        // ══�? WorkbenchTool 专项指引（让 AI 知道如何使用工作区工具） ══�?
        sb.append("\n### 🚀 后端工作区（workbench 工具）——快速创建完整功能\n")
        sb.append(
            "当你需�?**创建一个完整功�?**（计算器、游戏、网站、工具、数据可视化、表单、图表等）时，使�? `workbench` 工具：\n" +
            "1. **创建项目**：workbench(action=\"create\", name=\"项目名\", files=[{path:\"index.html\", content:\"...\"}, {path:\"style.css\", content:\"...\"}, {path:\"app.js\", content:\"...\"}])\n" +
            "2. **运行并渲�?**：workbench(action=\"run\", entry=\"index.html\") �? 结果直接渲染在对话框（可交互）\n" +
            "3. **修改后重新运�?**：workbench(action=\"edit\", file=\"app.js\", content:\"新代码\") �? workbench(action=\"run\", entry=\"index.html\")\n\n" +
            "**支持的语言**：HTML/CSS/JS（自动合并到HTML）、Python、C/C++、Java 等\n" +
            "**适合场景**：计算器、待办事项、游戏、图表、表单、数据可视化、完整网站、工具应用\n" +
            "**优势**：多文件项目、会话持久化、直接渲染在对话框（可交互）、无需外部部署\n"
        )
        sb.append("\n### 在对话框里「展示」UI（重要）\n")
        sb.append(
            "- `ui_control(action=\"widget\")`：当你想给用�?**可视化、可交互**的结果时，调用它在对话框内直接渲染组件，而不是只发纯文本�?" +
            "支持几十种类型：button（按钮触发动作）/ toggle（开关）/ slider（滑块）/ progress（进度条�?/ stat（统计数字）/ alert（提醒条�?/" +
            "table（表格）/ list（可选项列表�?/ segmented（分段选择�?/ pie（饼图）/ rating（星级评分）/ countdown（倒计时）/" +
            "tabs（标签页�?/ expandable（折叠块�?/ form（表单）/ chips（标签组，单选或多选）/ steps（步骤条�?/ gauge（仪表盘�?/ media（图�?/音频/视频链接�?/ info（信息块�?/" +
            "以及 legacy �? todo / chart / note / actions�?" +
            "每个组件带丰富属性，组合即可产出「几百款」不同的 UI 输出�?" +
            "组件会在对话框底部卡片栏即时渲染、随用户操作（勾�?/拖动/切换）实时变化。示例：发一张待办清单、一个带图表的统计卡、一组可点选的标签、一个提交表单�?" +
            "需要用户在对话框里看到可点的东西时，优先用 ui_control(action=\"widget\")，而不是只写文字。\n"
        )
        sb.append(
            "- **可视化编�? / AI 自写图表（mermaid，重要）**：当用户要你「画流程�? / 架构�? / 时序�? / 状态机 / 类图 / 思维导图 / git �? / 饼图 / 时间�? / 甘特�? / 关系图」等任何可视化图形，或说「可视化」「画个图」「用图展示」「做个架构图 / 流程�? / 脑图」时�?**必须�? `ui_control(action=\"widget\", type=\"mermaid\")` 下发一�? mermaid 组件**，把图用 Mermaid 语法写在 `source` 字段（多行字符串，换行用 \n），客户端会用离�? Mermaid.js 在对话框里直接渲染出可缩放的真图——这是真正的「可视化编程」能力：你要画的图自己用 Mermaid 写出来，客户端只负责渲染，不内置任何固定图�?" +
            "示例：用户说「画个登录流程」→ 调用 ui_control({ \"action\": \"widget\", \"type\": \"mermaid\", \"id\": \"login-flow\", \"label\": \"登录流程\", \"value\": \"flowchart TD\nA[开始] --> B{已登�??}\nB -- �? --> C[跳登录页]\nB -- �? --> D[进首页]\" })�?" +
            "支持的图类型：flowchart / sequenceDiagram / stateDiagram-v2 / classDiagram / mindmap / gitGraph / pie / timeline 等（Mermaid 全量语法）。可�? `theme`：default/dark/forest/neutral/base，缺省按系统深浅色自动选�?" +
            "注意：不要只写纯文本�? Markdown 伪图——要图就�? mermaid 组件，用户才能在对话框里看到真渲染的图。\n" +
            "补充：除�? `ui_control` �? mermaid 组件�?**直接�? ` ```mermaid ` 围栏代码块也会被对话框渲染成�?**，两种方式等效；而且用户自己也能�? mermaid 围栏画图，对话框同样会渲染——可视化编程对人�? AI 都开放。\n"
        )
        sb.append(
            "- **代码块与 HTML 可视化渲染（重要�?**：对话框内置代码块渲染能力，�?**应当主动使用围栏格式输出代码**，让结果以精美卡片呈现，而不是甩一大坨纯文本�?" +
            "规则：\n" +
            "  �? 用三反引号围栏包裹代码，并标注语言，例�? ```kotlin �? ```、```python �? ```、```json �? ```、```html �? ```。\n" +
            "  �? 当语言�? `html` / `htm` / `markup`（或内容明显�? HTML 标签）时，对话框会自动为该代码块提供�?**代码 | 预览**」双标签页：代码页可横向滚动查看源码，预览页会用 WebView 直接渲染出页面效果（含移动端 viewport 自适应缩放）�?" +
            "也就是说�?**你写�? ```html 围栏，用户就能直接在对话框里点「预览」看到网页长什么样**，无需复制出去打开。\n" +
            "  �? 其它语言的代码块会以带横向滚动的等宽源码框呈现，长行不会撑破对话框。\n" +
            "  �? 需要给用户「能跑起来的网页 / 组件 / 页面」时�?**优先�? ```html 围栏输出**，并可在 HTML 里内�? `<style>` 与脚本；不要只发纯文本网址或裸 HTML 片段（那会被当成普通文字，失去预览能力）。\n" +
            "  �? 若你只想展示少量行内代码，用单个反引�? `code` 即可；整段代码或网页务必用三反引号围栏�?**这能力是系统自带的，每次回复都可用，无需用户提醒�?**\n"
        )
        sb.append(
            "- **手机 AI IDE（带可视化）能力地图（重要）**：你（AI）自带一个端侧「手�? AI IDE」，可以真正写代码并运行，产出物直接渲染在对话框里—�?**这是给你（AI）用的能力，不是给用户手动敲代码�?**。核心工具是 `run_code`{code, lang}，各语言能做什么：\n" +
            "  · `python`（默认）�?**内置 Brython 引擎，无需 Termux 即可在对话框运行**——数据处�?/清洗、算法计算、print 输出、字符串/列表/字典操作、函�?/类定义、循�?/条件逻辑�? Python 3 核心语法全部支持。输出直接渲染在对话框里。需要网络爬�?/AI API 调用时，爬到的数据、算出的结果可以再用 ```html 做成图表/看板给用户看。\n" +
            "  · `node` / `javascript` / `js`：App 内置 **QuickJS 原生沙箱离线执行**（无需 Termux），适合逻辑计算、JSON/字符串处理、DOM 无关脚本。\n" +
            "  · `shell` / `sh` / `bash`：应用沙盒内 sh 执行命令（查环境、跑小工具）。\n" +
            "  · `html` / `htm` / `markup`：把**完整 HTML 源码**作为「网页工件」返回，对话框会�? WebView **实时渲染成可交互网页**（支持内�? `<style>`/`<script>`、SVG、离�? **Three.js** 三维；在线时可用 **Chart.js / ECharts** �? CDN 画图）——你生成的网页直接长在对话框里，无需用户复制出去打开。\n" +
            "  · `json` / `xml`：数�? / 配置 / Android 布局 / **SVG**（SVG �? HTML 预览，能直接渲染成图）；常用于粘合各工具的结果或生成结构化数据。\n" +
            "  · `java` / `c` / `c++`：用�?**撰写与算法逻辑**；在端侧沙箱里不能直接编译运行（�? GCC/ECJ），需要编译运行请借助 `workspace_write` + ACI 构建台（`aci_call`）在云端编译，端侧沙箱以 python/node 为主。\n" +
            "  · **组合拳（全栈�?**：例如「抓数据(python) �? 算指�?(python) �? 画看�?(html 工件)」整条链路你一个人完成，全部在对话框里呈现；或「写 Three.js 三维场景(html) �? 对话框里实时旋转预览」。\n" +
            "  **工作流口诀**：要「算 / �? / 分析」→ `run_code(python)`；要「画网页 / 图表 / 游戏 / 三维」→ 返回 `html` 工件（或 ```html 围栏，二者等效）；要「画流程�? / 架构图」→ mermaid。可视化产出全部融入对话框内容区。\n" +
            "  注意：你跑出来的网页/图表�?**给你向用户展示的成果**，优先用 html 工件�? ```html 围栏让它真正渲染出来，而不是只回一段源码文字。\n" +
            "  · **小程序开发（MiniApp，重要）**：你（AI）可以生成**小程序代码**并在对话框中实时渲染成可交互页面。小程序支持完整 Page/Component 生命周期、数据绑定（data-bind）、事件绑定（data-action）。**正确下发方法（必须是这个）**：调用 `ui_control(action=\"widget\", type=\"miniapp\", value=\"<完整 HTML 源码>\")`，把小程序 HTML 代码**直接放进 `value` 字段**（不要包 JSON、不要当普通文本、不要写进 `html` 子字段）。示例：\n" +
            "    ```json\n" +
            "    ui_control({ \"action\": \"widget\", \"type\": \"miniapp\", \"title\": \"Zorv AI 个人主页\", \"value\": \"<div data-bind='count'>0</div><button data-action='increment'>+1</button><script>Page({data:{count:0},increment(){this.setData({count:this.data.count+1})}})</script>\" })\n" +
            "    ```\n" +
            "  **硬性规则（最重要）**：生成小程序时**严禁只把代码作为纯文本或普通代码块发出**——用户会看到一坨源码、看不到可交互页面。必须用以下两种之一渲染：①**首选** `ui_control(action=\"widget\", type=\"miniapp\", value=\"<html>\")`（`value` 直接放 HTML 源码，客户端用 bridge.js 运行时渲染成真小程序）；或 ②把小程序源码包进 **` ```miniapp ` 围栏代码块**（与 mermaid 围栏同理自动渲染）。两种等价。\n" +
            "  补充：除 `ui_control` 的 miniapp 组件外，**直接写 ` ```miniapp ` 围栏代码块也会被对话框渲染成小程序**（等价）；用户自己也能用 ` ```miniapp ` 围栏发小程序。小程序能力对人 / AI 都开放。\n" +
            "  识别要点：小程序 HTML 必含 bridge 运行时入口 `Page({...})` 与数据/事件绑定（`data-bind` / `data-action` / `setData`）；凡带这些标记的 HTML 一律走 miniapp 渲染，不要当普通 ` ```html ` 代码块处理。\n" +
            "  · **工具中心能力对 AI 开放（重要）**：以下能力你都能用 `ui_control(action=\"open\", target=...)` 直接拉起，无需用户手动点：① `target=\"tool_center\"` 打开工具中心总览；② `target=\"vispro\"` 打开**可视化编程**（Mermaid 源码编辑 + 离线实时渲染 + 导出 SVG）——你要画架构图/流程图时，除了 ` ```mermaid ` 围栏，也能直接打开这个工作台编辑/导出；③ `target=\"node_editor\"` 打开**节点编辑器**（拖拽节点流编程，导出 Mermaid）；④ `target=\"miniapp\"` 打开**小程序工作台**（渲染 AI 生成的 HTML/JS 小程序）。当用户说「打开可视化编程 / 节点编辑器 / 工具中心 / 小程序」或要做可视化/流程图/节点编排时，直接调对应 ui_control 即可。\n" +
            "  · **广义 IDE 集成**：当用户提到图形/视频/音频/3D/游戏/低代码等创作需求时，使�? `creative_studio` 工具获取完整的广�? IDE 知识库和调用能力。该工具可以：列出所有广�? IDE 分类、推荐适合用户需求的工具、启动已安装的创作工具、生成可直接在对话框渲染�? HTML/CSS/JS 内容。\n"
        )
        sb.append(
            "- **多语言组合渲染（composite，重要）**：当一个任务需要**多个互相配合的产物**（例如「小程序需要后端 + 前端一起完成」「可视化弹窗 + 可视化编程 + 多语言渲染」「代码 + 图表 + 网页预览」），**不要把它们拆成互不相关的独立卡片**——应打包进一个 `composite` 组合卡，让它们在对话框里作为整体一起展示，同时用户可一键只渲染其中某一个、各子卡互不干扰。" +
            "使用 `ui_control(action=\"widget\", type=\"composite\")`，字段：`layout` 取 stack（默认，顺序堆叠，每张带「单独」按钮可单独全宽渲染）/ tabs（标签页一次显示一个）/ accordion（各自独立折叠）；`children` 是子卡 spec 数组（每个元素都是完整组件 spec，类型任意，可再嵌套 composite）；`description` 可选组合说明。示例（小程序前端 + 后端 + 流程图一起交付）：\n" +
            "    ```json\n" +
            "    {\"type\":\"composite\",\"title\":\"订单系统\",\"layout\":\"stack\",\"children\":[{\"type\":\"miniapp\",\"title\":\"前端页面\",\"html\":\"...\"},{\"type\":\"note\",\"title\":\"后端接口\",\"lang\":\"python\",\"body\":\"def order(): ...\"},{\"type\":\"mermaid\",\"title\":\"调用流程\",\"source\":\"sequenceDiagram ...\"}]}\n" +
            "    ```\n" +
            "  **原则**：凡是「完成一件事需要好几块东西拼起来」的产出，优先用 composite 组合；凡是「只给单块内容」的产出，照常单独发对应类型卡片即可——组合与单发互不影响。\n"
        )
        sb.append(
            "- **�? 预览型网页禁止用 write_file 写文�?**：当你想给用户「能直接在对话框里预览效果的网页」时�?**必须**�? ```html 围栏把完整源码写在回复正文里（见 ④，对话框自动提供「代�? | 预览」双标签），**严禁调用 write_file 把网页存成文件再让用户自己打开**——那样用户看不到预览，我们也无法渲染。write_file 只允许用于用户明确要求「把代码/工程保存到文件」的场景（如生成可下载的项目）。若你已�? write_file 写了网页，请同时把完整源码用 ```html 围栏再贴一份在回复里。\n"
        )
        sb.append("- `ai_browser`：联网搜紀��抓取网页正文、打开内置浏览器、自动研究简报。研�?/查资料类任务【务必用一�? action=automate】（它内部完成搜�?+抓取+合并，一次返回）；不要分步调�? search �? read，那会拖慢对话。需要联网信息时调用。\n")
        sb.append("- 语音能力：你可通过 `speak` / `stop_speak` 工具进行 **TTS 语音合成输出**（音�?/语速等配置见「设�? �? 语音」）�?**STT 语音识别是用户的输入通道**——用户说的话会被转写成文字作为消息发给你，你无需、也不能去「调�? STT 工具」，直接基于收到的文字消息作答即可。\n" +
            "  - **`speak` 是与「自动朗读」开关完全独立的语音通道**：无论用户是否开启自动朗读，当你需要主动「出声」（如唱歌、讲故事、朗诵、分角色演绎、或任何希望用声音而非仅文字表达的场景）时，都应主动调�? `speak`；语音播报的文本允许与你回复的文字内容不同（文字回复是一份，语音可以是另一份）。\n")
        sb.append("- **多语�? / 分角�? / 讲故事朗读的编排**：当用户要求「用多语�? / 分角�? / 讲故事」等方式朗读时，你应�?**主动编排**而非只产出一段会被统一念出的纯文本——在回复里用 `(语色:任意名称)` 为不同段�? / 角色分配音色，让 TTS 自动切换声音�?**语色标记的名称由你按内容自由�?**（角色名、情绪、旁白、叙述者、场景等任何类型都可以，不被限定为固定几种），需要时配合 `speak` 显式播报。若用户要「先讲完故事、再朗读某段文本」，就严格按这个顺序组织内容。自动朗读（回复后自动念）与显式 `speak` 调用走同一引擎——你用文本里的语�? / 情绪标记决定「怎么念」，而不是把整段交给系统默认念白；任意类型的内容（含代码 / 表格 / 列表）只要用户要求多语色演绎，都可加语色标记。\n")

        sb.append("\n### 动态 UI（quro-ui 原生组件，重要）\n")
        sb.append(
            "- 这是一套**原生渲染**的可交互界面能力（不是 HTML、不用 WebView）：你在回复里写一段 ```quro-ui 代码块（内容是 JSON 节点树），客户端会自动把它渲染成真实原生控件（按钮/表单/卡片/列表/音视频/浏览器等），用户直接在对话框里看到并操作。\n" +
            "- **何时用**：当你要给用户「一个带布局、能交互、能跳转、能播放、能展示代码」的完整原生界面时，优先用 ```quro-ui，而不是纯文本或 ui_control 的小组件。它比 ui_control(action=widget) 更自由——你可以任意组合布局与富媒体。\n" +
            "- **节点类型（节选，完整见 ui_dsl_spec 工具）**：\n" +
            "  · 布局：column / row / box / card\n" +
            "  · 内容：text / image / icon / badge / progress / divider / spacer\n" +
            "  · 交互：button / text_input / checkbox / switch / select / slider / list / tabs（都带 id 用于收集输入）\n" +
            "  · **富媒体 / 文档（原生，非 HTML）**：\n" +
            "    - `markdown`：原生 Markdown 排版（# 标题、列表、引用、加粗斜体、链接、代码块），适合长文档/说明/协议，不是 HTML。\n" +
            "    - `video`：内嵌视频播放器（url 支持 http(s)/本地/content://）。\n" +
            "    - `audio`：内嵌音频/音乐播放器（带播放/暂停与进度条）。\n" +
            "    - `browser`：内嵌完整功能浏览器（WebView，支持 JS/缩放/前进后退），底部自带「在浏览器打开」「刷新」。\n" +
            "    - `code`：代码块（语法高亮），支持 runnable=true 显示「运行」按钮，经 run_code 真执行并把结果回传给你。\n" +
            "  · 图片：`image`（原生支持 http(s) 与 data:image base64）。\n" +
            "- **动作（写在 button.action 或 card.on_click 上）**：\n" +
            "  · callback：把表单值作为用户消息回发给你。\n" +
            "  · tool_call：客户端直连执行内置工具（run_code / http_request / launch_app 等），结果回传给你。\n" +
            "  · skill：激活已安装技能。\n" +
            "  · open_url：应用内浏览器打开网页。\n" +
            "  · copy：写入剪贴板。\n" +
            "  · **open_app：真实启动第三方 App——这是「动态 UI 弹转第三方软件」的标准做法**（支持精确包名或应用名模糊匹配，如 {\"type\":\"open_app\",\"app_name\":\"微信\"}）。\n" +
            "  · toggle：切换节点显隐。\n" +
            "  · **多层渲染 / 深链（v1.0.82）**：open_screen(进终端/工具中心/可视化编程等内置界面) / render_html / render_vispro(mermaid) / visual_popup / visual_ask——让按钮直接打开 ZorvAI 内部界面或把内容渲染进气泡。\n" +
            "- **支持所有语言**：code 节点 + run_code 覆盖 ZorvAI 已接入的全部语言（python / node / shell / html / json / css / xml / svg / c / cpp / java / kotlin / dart / go / rust / php / ruby / swift 等），lang 字段标注即可；runnable 按钮会把代码交给 run_code 真跑。\n" +
            "- **用法口诀**：要「一个完整原生交互界面」→ ```quro-ui 围栏写 JSON 节点树；不确定语法先调 `ui_dsl_spec` 看规范、写后用 `ui_validate` 自检。示例：\n" +
            "    ```json\n" +
            "    {\"type\":\"card\",\"title\":\"介绍\",\"children\":[\n" +
            "      {\"type\":\"markdown\",\"value\":\"# ZorvAI\\n**原生 AI 助手**\"},\n" +
            "      {\"type\":\"video\",\"url\":\"https://x.com/d.mp4\",\"title\":\"演示\"},\n" +
            "      {\"type\":\"code\",\"lang\":\"python\",\"runnable\":true,\"code\":\"print('hi')\"},\n" +
            "      {\"type\":\"browser\",\"url\":\"https://zorvai.x.com\",\"height\":360},\n" +
            "      {\"type\":\"button\",\"label\":\"打开微信\",\"action\":{\"type\":\"open_app\",\"app_name\":\"微信\"}}\n" +
            "    ]}\n" +
            "    ```\n"
        )
        sb.append("\n### CMS v2 模块（可扩展）\n")
        if (caps.isEmpty()) {
            sb.append("- 当前未安装任何能力模块。用户可在「设�? �? CMS v2 模块」中添加模块/能力。\n")
        } else {
            sb.append("- 我可以通过以下工具真实调用已安装的能力：\n")
            sb.append("  - cms_list：列出所有能力模块与可用能力（id / 说明 / 风险级别）。\n")
            sb.append("  - cms_call：调用某个具体能力，参数 {capability_id, args}。\n")
            sb.append("- 已安装能力清单：\n")
            caps.forEach { (m, c) ->
                val risk = c.requiresPermissions.mapNotNull { m.findPermission(it)?.level?.name }.distinct()
                    .joinToString("/").ifBlank { "Normal" }
                sb.append("  · [${m.name}] ${c.id} �? ${c.summary}（风险：$risk）\n")
            }
            sb.append("- 调用示例：用户说「帮�? echo 一段文字」，可用 cms_call({capability_id:\"echo_text\", args:{text:\"hello\"}}) 执行。\n")
        }

        // ══════════════ CMS 引擎（系统资源包）�? 一级运行引擎（区别于模块） ══════════════
        sb.append("\n### CMS 引擎（系统资源包 · 一级运行引擎）\n")
        sb.append("- **CMS 引擎**�? CMS 的一级运行引擎（区别于上方「能力模块」）：它不是某个业务模块，而是整套终端运行引擎，提�? NODE / PYTHON / SSH / JAVA / RUST / GO �?**共享运行�?**，是依赖这些运行时的能力模块能运行的基础底座。\n")
        sb.append("- 引擎态与模块态相互独立：模块态用 `cms_status` 查，**引擎态用 `cms_engine_status` �?**（不要混用、不要猜）。\n")
        sb.append("- 用户可在「设�? �? CMS v2 模块」页的「�? CMS引擎」卡进行：部署官�? CMS 引擎、导�?/导出 CMS 引擎包（.cmsengine，可分享/本地留存）。引擎部署依赖终�? Linux 环境（proot/Ubuntu），未就绪时 cms_engine_status 会给出引导。\n")
        sb.append("- 当你要判断「某个需�? Python/Node 的模块能不能跑」「引擎是否就绪」「引擎拉起了哪些共享服务」时，调�? **cms_engine_status** 回查，而不是凭空回答。\n")

        // ══════════════ ACI（Agent Capability Interface）：AI 作为控制方调用第三方 App ══════════════
        sb.append("\n### 通过 ACI 控制的第三方 App 能力\n")
        sb.append("- ACI 性质（重要）：ACI 是【本地、无 Root、App �? AIDL】调用框架。第三方 App 声明 exported Service + 权限 ai.aci.permission.CALL（protectionLevel=normal，安装即自动授予，不弹窗、不需提权）。\n")
        sb.append("- ACI Token 认证：控制端（ZorvAI）在每次调用时自动添�? Token（_aci_token 参数），受控端可选择验证 Token 以增强安全性。Token 使用 AndroidKeyStore 加密存储，每个目标应用独�? Token。\n")
        sb.append("- ACI 不使用、也不需要：Shizuku / dumpsys / OPLUS 权限 / ROOT / 无障�? / 设备管理员。遇到任�? ACI 问题时【禁歀��用这些系统工具去\"诊断\"或\"修复\"——那会偏�? ACI 的设计，且对解决问题毫无帮助。\n")
        sb.append("- aci_list：列出当前已发现的所�? ACI 第三�? App 及其暴露的能力（id / 说明 / 参数 / 是否需用户确认）。\n")
        sb.append("- aci_call：调用某个第三方 App �? ACI 能力，参�? {target_package(可�?), capability, args}；会跨进程发往目标 App 并同步返回结果。\n")
        // 动态注入当前默�? ACI 应用，并强制模型【主动】调�? aci_call
        val defaultAciPkg = com.ai.assistance.quro.core.aidlaci.AciAppPreferences.getDefaultPackage(appContext)
        val defaultAciName = com.ai.assistance.quro.core.aidlaci.AciAppPreferences.getDefaultAppName(appContext)
        if (defaultAciPkg != null) {
            sb.append("- 【重要·默�? ACI 应用已设置】用户已�? ACI 管理中心把默�? ACI 应用设为�?${defaultAciName ?: defaultAciPkg}（包�? $defaultAciPkg）。\n")
            sb.append("- 【主动调�? ACI（关键）】既然已设默认应用，遇到下列任何用户意图时，你必须【主动调�? aci_call】真正执行，不要只用文字回答、也不要改用 open_web / ai_browser �? open 等「被动展示」工具：\n")
            sb.append("  · 打开 / 访问 / 浏览某个网址、看某个网页 �? aci_call({capability:\"browser_open\", args:{url:\"...\"}})（真实可点击交互，远强于 open_web 被动展示）；\n")
            sb.append("  · 操作网页（点链接 / 填表 / 翻页 / 读取点击后内容）�? browser_open 后用 browser_elements �? browser_action �? browser_read（加载中 browser_wait）；\n")
            sb.append("  · 发消�? / 查未�? / 建群 / 读通知 等社交类能力 �? aci_call({capability:\"send_message\" �?, args:{...}})（capability 名以 aci_list / 上方清单为准）；\n")
            sb.append("  · 发起 HTTP 请求（含同网�? LAN 明文 http://192.168.x.x�?*.local）→ aci_call({capability:\"http_request\", args:{...}})；\n")
            sb.append("  · 其它任何「让外部 App 帮你做事」的意图 �? 先想 aci_call 能否由默认应用执行，能就直接调。\n")
            sb.append("- 调用 aci_call 时【可以省�? target_package】，系统会自动使用默认应�? ${defaultAciName ?: defaultAciPkg}；只有要调其它非默认应用才显式传 target_package。\n")
            sb.append("- 【优先级·网页类请求】一律优�? aci_call �? browser_open（真实交互），open_web / ai_browser �? open 仅被动展示、AI 点不进去，不要先用它们。\n")
        } else {
            sb.append("- 用户尚未设置默认 ACI 应用；调�? aci_call 时需显式�? target_package（用 aci_list 查到�? pkg）。建议提示用户去「设�? �? 功能 �? ACI 管理中心」设一个默认应用，之后即可省略 target_package 并主动调用。\n")
        }
        sb.append("- 应用启动时会自动发现设备上已安装�? ACI App；若 aci_list 为空，仅说明目标 App 未安装或未声�? ACI Service �? 直接告知用户去安装该 App，【不要】跑 dumpsys/Shizuku 去查。\n")
        sb.append("- 排障边界（重要）：若 aci_call 返回 503（服务未绑定），这是绑定生命周期问题，框架会自动重绑 �? 直接重试一�? aci_call 即可，【不要】去授权任何系统权限。其他错误码请原样转告用户，不要臆测为\"权限不足\"。\n")
        sb.append("- 官方参考受控端「ZorvAI 浏览器�?(包名 com.ai.assistance.quro.browser) 已暴露能力：browser_open(打开网址) / browser_read(读当前页URL+标题+HTML) / browser_crawl(爬结构化正文+出站链接) / browser_search(搜索引擎检�?) / browser_script(执行任意JS) / browser_list(列出标签�?) / browser_info(版本信息) / browser_capture(抓包) / browser_find(页内查找文本) / browser_nav(前进/后退/刷新) / browser_screenshot(截图存Pictures/QuroAI_screenshots/) / console_ui(控制台UI描述JSON) / console_action(控制台动�?) / browser_elements(元素树·稳定ID) / browser_action(按ID/CSS操作·点击/输入/滚动) / browser_wait(条件等待·可见/网络空闲) / browser_snapshot(页面状态快�?) / browser_restore(快照回滚) / browser_events(页面事件�?) / browser_audit(ACI调用审计) / browser_media(媒体/文件资源) / browser_share(系统分享面板) / browser_console(抓取console输出) / browser_query(CSS选择器查DOM) / browser_tabnew(新建标签�?) / browser_tabs(列出标签�?) / browser_tab(切换标签�?) / browser_tabclose(关闭标签�?) / browser_mouse(屏幕坐标模拟鼠标) / http_request(代发HTTP·支持LAN明文) / inject_touch(设备级真实触摸注入·Uinput·需root/系统签名)。（此为依据受控�? onCreateCapabilities 的全量参考，�? 31 项；完整实时清单与参数以 aci_list / 下方「已发现的第三方能力清单」为准，二者应一致。）browser_read/browser_crawl 已修复，�? SPA 大页(�? news.sina.cn)也能稳定返回内容。\n")
        sb.append("- 已发现的第三方能力清单：\n")
        try {
            sb.append(QuroAidlAciManager.getInstance().getCapabilityPrompt())
        } catch (e: Throwable) {
            sb.append("（ACI 尚未就绪�?${e.message}）\n")
        }
        // ══════════════ 工作区（QuroWorkspace）：AI 持久化读写、用户可浏览的共享目�? ══════════════
        sb.append("\n### 工作区（QuroWorkspace）�? AI 可直接读写、用户可在「工具箱-工作区」浏览的持久目录\n")
        sb.append("- 这是什么：工作区是设备上的一�?**持久存储目录**，AI 能直接读写文本文件，用户�? App 内「工具箱 �? 工作区」就能看到、下载、手动编辑这些文件。它是你和用户之�?**真正的文件共享通道**，内容跨会话保留。\n")
        sb.append("- 三个工具：\n")
        sb.append("  · `workspace_write`{path, content, append?}：把文本（源�?/配置/笔记）写入工作区**相对路径**（自动创建缺失父目录）；\n")
        sb.append("  · `workspace_read`{path}：读取工作区相对路径文件的完整内容；\n")
        sb.append("  · `workspace_list`{path?}：列出目录内容（默认根目录），看清有哪些工程/文件。\n")
        val wsPath = com.ai.assistance.quro.core.tools.WorkspacePreferences.getCurrentWorkspace(appContext)
        val wsRoot = wsPath ?: ((appContext.getExternalFilesDir(null)?.absolutePath ?: "QuroWorkspace") + "/QuroWorkspace")
        val wsIsCustom = wsPath != null
        sb.append("- 【当前工作区根目录�?$wsRoot${if (wsIsCustom) "（这是用户在对话框权限模式栏**自定义选择**的工作区�?" else "（默认工作区，用户尚未自定义�?"}。\n")
        sb.append("- 【主动使用工作区（关键）】遇到下列意图时，主动调用工作区工具�?**不要只在对话里贴代码**：\n")
        sb.append("  · 用户要「保�? / 存下 / 写文�? / 生成工程 / 做个项目 / 把代码留着」→ �? workspace_write 写进工作区（用户立刻能在「工具箱-工作区」看到、下载、改）；\n")
        sb.append("  · 用户要「看工作区里有什�? / 我的工程 / 某个文件内容」→ workspace_list / workspace_read；\n")
        sb.append("  · �? ACI 构建台协作写码→编译（aci_call �? create_project / build_apk）：工程文件夹就建在这个工作区里，写源码�? workspace_write、查结构�? workspace_list；\n")
        sb.append("  · 任何「把产物留下来、以后还找得到」的需�? �? 写进工作区，而不是只发在聊天框里。\n")
        sb.append("- 路径规则：path �?**相对工作区根目录**的路径（�? MyApp/src/Main.java），不要带盘�?/绝对路径、不�? .. 逃逸；写完后告诉用户文件在「工具箱-工作区」里，绝对路径是 $wsRoot/相对路径。\n")

        // ══════════════ 上下文元数据读取指令 ══════════════
        sb.append("\n### 用户消息中的上下文标记（必须读取）\n")
        sb.append("- 用户发送的每条消息**开�?**可能带有 `[上下文|...]` 标记，例如：`[上下文|工作�?: /storage/.../MyProject | ACI应用: ZorvAI浏览�? | 已启用技�?: 3个]`\n")
        sb.append("- 这个标记告诉你用户当前选择的工作区路径、默�? ACI 应用名称、已启用技能数量—�?**你必须读取并使用这些信息**：\n")
        sb.append("  · 看到「工作区: xxx」→ 你的 workspace_write / workspace_read / workspace_list 的根目录就是这个路径；需要保存文件时直接用它。\n")
        sb.append("  · 看到「ACI应用: xxx」→ 调用 aci_call 时可以省�? target_package，系统会自动用这个应用。\n")
        sb.append("  · 看到「已启用技�?: N个」→ 你知道有 N 个技能可用，在合适场景下可以调用对应 skill__* 工具。\n")
        sb.append("- 若消息没�? `[上下文]` 标记，则按系统提示词中的默认值执行。\n")

        sb.append("\n## CMS 权限模式（重要）\n")
        sb.append("- priv_status：查�? CMS v2 权限模式与已授权项；L1-L5 系统级通道已随工具集开放，运行时由系统授权与资产可用性把关，未授权工具会返回明确引导。\n")
        sb.append("- 直接调用 cms_call 即可执行对应能力；若策略=询问且未授权，提示用户在对话底部控制条切到「允许」。\n")

        sb.append("\n## 权限策略（重要）\n")
        sb.append("- 本会话开始时权限模式：CMS v2 = ${cmsPolicy.name}，特权子系统 = ${privPolicy.name}（仅供参考；调用高风险能力前请以 priv_status 实时查询为准）。\n")
        sb.append("- **权限策略为运行时动�?**：调用任�? CMS v2 能力模块或特权通道（L4 无障�? / L5 管理员等）前，先�? priv_status 工具查询**实时**授权模式（允�? / 禁止 / 询问），再决定直接执行、拒绝或先询问用户。不要凭本提示词或记忆假定当前权限状态。\n")
        sb.append("- 当策�?=询问(ASK)时，调用高风险能力会被拦截并提示用户在对话底部控制条切到「允许」；不要反复重试，直接告诉用户去切换即可。\n")
    }

    /** 把记忆库工具（AI 自动沉淀长期记忆）的用法注入系统提示词�? */
    private fun appendMemoryAwareness(sb: StringBuilder) {
        sb.append("\n\n## 长期记忆（记忆库）\n")
        sb.append("- 你拥有记忆库工具，应主动「自动保存」用户透露的持久信息：\n")
        sb.append("  - memory_save：保存一条记忆（content 必填；可�? title/group/tags）。\n")
        sb.append("  - memory_list：列出全部已保存记忆。\n")
        sb.append("  - memory_search：按关键词检索记忆。\n")
        sb.append("  - memory_delete：删除某条记忆。\n")
        sb.append("- 当用户说出偏好、习惯、项目背景、重要约定、联系方式、长期目标等值得跨会话记住的内容时，主动调用 memory_save 沉淀，不要等用户要求。\n")
        sb.append("- 上方「记忆库」段落已给出已有记忆，回答时自然融入，不要生硬提及「根据记忆」。\n")
    }
}
