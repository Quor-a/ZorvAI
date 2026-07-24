package com.ai.assistance.quro.ui

import android.content.Context
import androidx.core.content.edit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ai.assistance.quro.core.QuroAssistant
import com.ai.assistance.quro.core.QuroPlatformManifest
import com.ai.assistance.quro.core.QuroAttachment
import com.ai.assistance.quro.core.cards.QuroChatCard
import com.ai.assistance.quro.core.QuroConversationMeta
import com.ai.assistance.quro.core.QuroConversationRepository
import com.ai.assistance.quro.core.QuroConversationStore
import com.ai.assistance.quro.core.memory.QuroMemoryEntry
import com.ai.assistance.quro.core.memory.QuroMemoryRepository
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
import com.ai.assistance.quro.core.QuroTagRepository
import com.ai.assistance.quro.core.QuroCrashReporter
import com.ai.assistance.quro.core.cms.QuroCmsRepository
import com.ai.assistance.quro.core.skill.QuroSkillStore
import com.ai.assistance.quro.core.network.QuroLlmClient
import com.ai.assistance.quro.core.model.QuroModelConfig
import com.ai.assistance.quro.core.model.QuroModelConfigRepository
import com.ai.assistance.quro.core.policy.QuroPolicy
import com.ai.assistance.quro.core.policy.QuroPolicyStore
import com.ai.assistance.quro.core.tools.buildQuroRegistry
import com.ai.assistance.quro.core.tools.QuroTool
import com.ai.assistance.quro.core.tools.ImportedToolDef
import com.ai.assistance.quro.core.tools.QuroImportedToolRegistry
import com.ai.assistance.quro.core.tools.QuroVoiceStyle
import com.ai.assistance.quro.core.tools.QuroCloudTtsCatalog
import com.ai.assistance.quro.core.tools.QuroTtsPrefs
import com.ai.assistance.quro.core.tools.QuroTtsProviderPrefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/**
 * 对话 ViewModel（原创）：支持多会话、历史记录持久化、新建/切换/删除会话。
 * 同一份内存 [store] 实例贯穿生命周期，避免 QuroAssistant 持有过期引用。
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
    // 共享工具注册表：assistant 下发 tools 字段 与 系统提示词菜单 都从这里取，保证二者严格一致。
    private val registry = buildQuroRegistry(appContext)

    /** 当前全部已注册工具（含导入工具），供「+」面板的「已有工具列表」展示。 */
    fun allTools(): List<QuroTool> = registry.all()

    /** 导入一个工具（用户粘贴 JSON / AI 自写）：持久化并并入运行时注册表，使其立即可被 AI 调用。 */
    fun importTool(def: ImportedToolDef) {
        QuroImportedToolRegistry.add(appContext, def)
        registry.mergeImported(appContext)
    }
    private var assistant = QuroAssistant(QuroLlmClient(), registry, store)

    // 全部会话（含消息），落盘的唯一真相源
    private val _convs = MutableStateFlow<List<QuroPersistedConversation>>(emptyList())
    private val _conversationsMeta = MutableStateFlow<List<QuroConversationMeta>>(emptyList())
    private val _currentId = MutableStateFlow("")
    private val _messages = MutableStateFlow<List<QuroMessage>>(emptyList())
    // A4 修复：每个会话独立的「生成中」状态。原全局 _busy 会导致切换会话后打断按钮残留，
    // 现改为按 conversationId 记录，UI 仅对【当前可见会话】显示打断按钮。
    private val _busyMap = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    fun isBusy(conversationId: String): Boolean = _busyMap.value[conversationId] == true
    private var sendJob: Job? = null
    // 当前选中的会话 id（供外部组件如语音球读取，把语音球对话写入此对话框）
    var activeConversationId: String = ""
        private set
    private val uiPrefs = appContext.getSharedPreferences("quro_ui", Context.MODE_PRIVATE)

    companion object {
        /** 当前活跃的 ViewModel 实例，供语音球等外部组件委托对话写入「选中的对话框」。 */
        lateinit var instance: QuroChatViewModel
            private set
    }
    private val _thinking = MutableStateFlow(uiPrefs.getBoolean("thinking", true))

    val conversations: StateFlow<List<QuroConversationMeta>> = _conversationsMeta.asStateFlow()
    val currentId: StateFlow<String> = _currentId.asStateFlow()
    val messages: StateFlow<List<QuroMessage>> = _messages.asStateFlow()
    // 仅反映【当前可见会话】是否正在生成（随切换会话自动变化），供 UI 显示打断按钮。
    val busy: StateFlow<Boolean> = combine(_busyMap, _currentId) { map, id -> map[id] == true }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val thinking: StateFlow<Boolean> = _thinking.asStateFlow()

    fun setThinking(on: Boolean) {
        _thinking.value = on
        uiPrefs.edit { putBoolean("thinking", on) }
    }

    // AI 自动保存记忆开关（设置页「AI 自动保存记忆」控制；默认开启，保持原有行为）
    private val _autoSaveMemory = MutableStateFlow(uiPrefs.getBoolean("auto_save_memory", true))
    val autoSaveMemory: StateFlow<Boolean> = _autoSaveMemory.asStateFlow()

    fun setAutoSaveMemory(on: Boolean) {
        _autoSaveMemory.value = on
        uiPrefs.edit { putBoolean("auto_save_memory", on) }
    }

    // 外观与对话设置：深色模式（全局主题，需上提到 QuroApp 根部主题处生效）
    private val _darkMode = MutableStateFlow(uiPrefs.getBoolean("dark_mode", false))
    val darkModePref: StateFlow<Boolean> = _darkMode.asStateFlow()
    fun isDarkMode(): Boolean = _darkMode.value
    fun setDarkMode(on: Boolean) { _darkMode.value = on; uiPrefs.edit { putBoolean("dark_mode", on) } }

    // 外观与对话设置：回复完成提示音
    private val _soundOn = MutableStateFlow(uiPrefs.getBoolean("sound_on", true))
    val soundOnPref: StateFlow<Boolean> = _soundOn.asStateFlow()
    fun isSoundOn(): Boolean = _soundOn.value
    fun setSoundOn(on: Boolean) { _soundOn.value = on; uiPrefs.edit { putBoolean("sound_on", on) } }

    // 外观与对话设置：字号档位（0=小 1=标准 2=大）
    private val _fontTier = MutableStateFlow(uiPrefs.getInt("font_tier", 1))
    val fontTierPref: StateFlow<Int> = _fontTier.asStateFlow()
    fun getFontTier(): Int = _fontTier.value
    fun setFontTier(tier: Int) { _fontTier.value = tier; uiPrefs.edit { putInt("font_tier", tier) } }

    // 外观与对话设置：回车发送
    private val _enterSend = MutableStateFlow(uiPrefs.getBoolean("enter_send", true))
    val enterSendPref: StateFlow<Boolean> = _enterSend.asStateFlow()
    fun isEnterSend(): Boolean = _enterSend.value
    fun setEnterSend(on: Boolean) { _enterSend.value = on; uiPrefs.edit { putBoolean("enter_send", on) } }

    // AI 回复通知总开关（离开软件时的系统通知 / 桌面卡片均受它控制）
    private val _aiReplyNotify = MutableStateFlow(uiPrefs.getBoolean("ai_reply_notify", true))
    val aiReplyNotifyPref: StateFlow<Boolean> = _aiReplyNotify.asStateFlow()
    fun setAiReplyNotify(on: Boolean) { _aiReplyNotify.value = on; uiPrefs.edit { putBoolean("ai_reply_notify", on) } }

    // ---- 用户资料（头像 / 名字 / 签名，显示在对话框并注入 system prompt） ----
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
     * 统一触发回复通知：离开软件时弹系统通知（由 QuroReplyNotifier 按前台状态判断）+ 刷新桌面卡片。
     * 受总开关控制。前台（用户在软件内）时系统通知不会弹、桌面卡片照常刷新。
     */
    private fun fireReplyNotification(sender: String, text: String) {
        if (!_aiReplyNotify.value) return
        QuroReplyNotifier.notifyReply(appContext, sender, text)
        QuroReplyWidget.updateLatest(appContext, sender, text)
    }

    init {
        val loaded = convRepo.loadAll().toMutableList()
        if (loaded.isEmpty()) {
            val id = UUID.randomUUID().toString()
            val now = System.currentTimeMillis()
            val welcome = QuroMessage(
                role = "assistant",
                content = defaultWelcome(),
            )
            loaded.add(QuroPersistedConversation(id, "新对话", now, now, listOf(welcome)))
            convRepo.saveAll(loaded)
        }
        _convs.value = loaded
        val latest = loaded.maxByOrNull { it.updatedAt }!!
        _currentId.value = latest.id
        activeConversationId = latest.id
        instance = this
        store.clear()
        latest.messages.forEach { store.add(it) }
        _messages.value = store.all()
        emitMeta()

        // 注册机器人会话绑定器：把平台用户消息按设置写入 App 持久化会话
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

    /** 查找某平台用户对应的机器人自动会话（按标题匹配）。 */
    private fun findBotConversation(platform: QuroBotPlatform, userId: String): QuroPersistedConversation? {
        val prefix = "[${platform.label}] "
        return _convs.value.firstOrNull { it.title == "$prefix$userId" }
    }

    /** 为某平台用户新建一个自动会话，返回其 ID。 */
    private fun createBotConversation(platform: QuroBotPlatform, userId: String): String {
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val welcome = QuroMessage(
            role = "assistant",
            content = "这是来自 ${platform.label} 用户 $userId 的机器人对话。",
        )
        val conv = QuroPersistedConversation(id, "[${platform.label}] $userId", now, now, listOf(welcome))
        _convs.value = _convs.value + conv
        convRepo.saveAll(_convs.value)
        emitMeta()
        return id
    }

    /** 把消息追加到指定会话并持久化；若该会话正好是当前可见会话，也同步刷新 UI。 */
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
        // 如果追加的是当前可见会话，同步刷新 _messages
        if (_currentId.value == conversationId) {
            messages.forEach { store.add(it) }
            _messages.value = store.all()
        }
    }

    fun newConversation() {
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val welcome = QuroMessage(role = "assistant", content = defaultWelcome())
        val conv = QuroPersistedConversation(id, "新对话", now, now, listOf(welcome))
        _convs.value = _convs.value + conv
        _currentId.value = id
        activeConversationId = id
        store.clear()
        store.add(welcome)
        _messages.value = store.all()
        convRepo.saveAll(_convs.value)
        emitMeta()
    }

    fun selectConversation(id: String) {
        val conv = _convs.value.firstOrNull { it.id == id } ?: return
        _currentId.value = id
        activeConversationId = id
        store.clear()
        conv.messages.forEach { store.add(it) }
        _messages.value = store.all()
        // A4：打断状态已改为按会话隔离（_busyMap），busy 仅反映【当前可见会话】是否生成中。
        // 切换会话后无需复位全局状态——切走时打断按钮随当前会话自动隐藏，切回仍在生成的会话会重新显示。
    }

    fun deleteConversation(id: String) {
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

    fun clear() {
        store.clear()
        val welcome = QuroMessage(role = "assistant", content = "对话已清空。")
        store.add(welcome)
        commitCurrent()
    }

    // ---- 发送 ----

    fun send(
        text: String,
        attachments: List<QuroAttachment> = emptyList(),
        cfg: QuroModelConfig = repo.load(),
    ) {
        val t = text.trim()
        if ((t.isEmpty() && attachments.isEmpty()) || isBusy(_currentId.value)) return
        // 接住对话协程里逃逸的异常，转成可见报错而非界面卡死/进程崩溃。
        sendJob = viewModelScope.launch(QuroCrashReporter.handler) {
            _busyMap.value = _busyMap.value + (_currentId.value to true)
            try {
                val firstUser = store.all().none { it.role == "user" }
                store.add(
                    QuroMessage(
                        role = "user",
                        content = t,
                        attachments = if (attachments.isNotEmpty()) attachments else null,
                        // A2 修复：把发送者昵称/头像随消息一并带出，供气泡渲染显示（与 system prompt 昵称同源）。
                        senderName = userProfile.value.name.takeIf { it.isNotBlank() },
                        avatarUrl = userProfile.value.avatarUri.takeIf { it.isNotBlank() },
                    ),
                )
                // 触发词自动激活：匹配到的非常驻（alwaysOn=false）技能按隐藏消息预注入，供 AI 本轮作答
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
                commitCurrent(updateTitle = firstUser)
                if (cfg.apiKey.isBlank()) {
                    store.add(
                        QuroMessage(
                            role = "assistant",
                            content = "⚠️ 尚未配置模型 API Key，请点右上角模型芯片 →「在模型设置中管理」填写 baseUrl / apiKey / model。",
                        ),
                    )
                } else {
                    // ask 内部已对每个环节兜底，这里再兜一层：任何意外都转成一条可见的错误消息，
                    // 且 finally 保证 _busy 一定复位，避免「卡死在思考中」导致此后永远无法回复。
                    runCatching {
                        assistant.ask(appContext, cfg, buildSystemPrompt(cfg), autoSaveMemory = autoSaveMemory.value) {
                            // 工具调用/结果产生时实时刷新并落盘，退出也能保留中间过程
                            commitCurrent()
                        }
                    }.onFailure { e ->
                        if (e is CancellationException) {
                            // 用户主动打断生成：不报红错误，附一行明确反馈并保留已生成的部分内容
                            store.add(QuroMessage(role = "assistant", content = "⏹ 已停止生成。"))
                            commitCurrent()
                            return@onFailure
                        }
                        store.add(
                            QuroMessage(
                                role = "assistant",
                                content = "⚠️ 回复生成失败：${(e.message ?: "未知错误").take(200)}",
                            ),
                        )
                    }
                }
                commitCurrent()
                // 对话一轮完成 → 触发人格自动孵化（按轮次累计，静默、不阻塞主对话）
                maybeAutoIncubate()
                // 回复完成 → 统一触发通知（离开软件弹系统通知 + 刷新桌面卡片，受总开关控制）
                runCatching {
                    store.all().lastOrNull { it.role == "assistant" && !it.hidden && it.content.isNotBlank() }
                        ?.let { fireReplyNotification("Quro AI", it.content) }
                }
                } catch (e: Exception) {
                    store.add(
                        QuroMessage(
                            role = "assistant",
                            content = "⚠️ 发生错误：${(e.message ?: "未知错误").take(200)}",
                        ),
                    )
                    commitCurrent()
                } finally {
                _busyMap.value = _busyMap.value - _currentId.value
            }
        }
    }

    /** 打断当前正在进行的生成：取消发送协程并立即复位 busy（已生成的部分内容已落盘，不报错）。
     *  修复：必须在此处立即设 _busy=false，不能仅依赖协程 finally（finally 异步执行，
     *  用户连续点停止时 sendJob 已 null/已 cancel 导致后续点击无效、状态卡在"生成中"）。 */
    fun stop() {
        sendJob?.cancel()
        sendJob = null
        _busyMap.value = _busyMap.value - activeConversationId   // 立即复位【当前会话】：打断按钮马上切回发送
    }

    /**
     * 语音球轮次：把用户语音识别文本写入「绑定的对话框」并问询，返回最终回复文本。
     *
     * - [sessionId] 为空或等同于当前可见会话 → 写入当前正在看的对话框（原行为，store 即当前对话）。
     * - [sessionId] 指向其它会话 → 临时接管内存 store 把对话写进该会话并落盘，
     *   不打扰用户当前可见的对话框（[commitCurrent] 不会误写当前会话）。
     *
     * 这是「语音球绑定到指定对话框」的核心。
     */
    suspend fun voiceBallTurn(text: String, cfg: QuroModelConfig = repo.load(), sessionId: String = ""): String {
        val targetId = sessionId.ifBlank { _currentId.value }

        // 目标即当前可见会话：走原路径
        if (targetId == _currentId.value) {
            activeConversationId = targetId
            store.add(QuroMessage(role = "user", content = text, senderName = userProfile.value.name.takeIf { it.isNotBlank() }, avatarUrl = userProfile.value.avatarUri.takeIf { it.isNotBlank() }))
            // 触发词自动激活（当前可见会话路径）：与 send() 同源逻辑
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
            fireReplyNotification("Quro AI", reply)
            return reply
        }

        // 目标为其它（非当前可见）会话：临时接管 store，写入并落盘，不切换可见对话框
        val conv = _convs.value.firstOrNull { it.id == targetId }
            ?: return voiceBallTurn(text, cfg, "")   // 兜底：会话不存在 → 写当前
        val saved = store.all().toList()
        return try {
            store.clear()
            conv.messages.forEach { store.add(it) }
            store.add(QuroMessage(role = "user", content = text, senderName = userProfile.value.name.takeIf { it.isNotBlank() }, avatarUrl = userProfile.value.avatarUri.takeIf { it.isNotBlank() }))
            // 触发词自动激活（绑定其它会话路径）：与 send() 同源逻辑
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
            val reply = runVoiceAsk(cfg) { /* 绑定会话不中途落盘当前视图，结束后整段写回目标会话 */ }
            val finalMsgs = store.all().toList()
            _convs.value = _convs.value.map { c ->
                if (c.id == targetId) c.copy(
                    messages = finalMsgs,
                    updatedAt = System.currentTimeMillis(),
                    title = if (c.title == "新对话") deriveTitle(finalMsgs) else c.title,
                ) else c
            }
            convRepo.saveAll(_convs.value)
            emitMeta()
            fireReplyNotification("Quro AI", reply)
            reply
        } finally {
            store.clear()
            saved.forEach { store.add(it) }
            // 当前可见视图的 _messages 不在此处刷新，避免绑定写入时界面闪烁
        }
    }

    /** 用同一套助手与系统提示词问询（store 须已含用户消息）。onTick 用于生成中持久化。 */
    private suspend fun runVoiceAsk(cfg: QuroModelConfig, onTick: () -> Unit): String {
        return if (cfg.apiKey.isBlank()) {
            "⚠️ 尚未配置模型 API Key，请点右上角模型芯片 →「在模型设置中管理」填写 baseUrl / apiKey / model。"
        } else {
            runCatching {
                assistant.ask(appContext, cfg, buildSystemPrompt(cfg), autoSaveMemory = autoSaveMemory.value, onUpdate = onTick)
            }.getOrElse { e ->
                if (e is CancellationException) "⏹ 已停止生成。" else "⚠️ 语音球出错了：${e.message ?: "未知错误"}"
            }
        }
    }

    fun currentConfig(): QuroModelConfig = repo.load()

    // ---- 内部 ----

    /** 把当前内存 store 的变动写回 _convs、刷新消息流并落盘。 */
    private fun commitCurrent(updateTitle: Boolean = false) {
        val id = _currentId.value
        val msgs = store.all()
        val existing = _convs.value.firstOrNull { it.id == id }
        val title = if (updateTitle && existing != null) deriveTitle(msgs) else existing?.title ?: "新对话"
        _convs.value = _convs.value.map { c ->
            if (c.id == id) c.copy(messages = msgs, updatedAt = System.currentTimeMillis(), title = title) else c
        }
        _messages.value = msgs
        convRepo.saveAll(_convs.value)
        emitMeta()
    }

    /**
     * 把 AI 经 ui_widget / ui_card 下发的富组件挂到「当前助手消息」气泡里（实现可视化组件融进聊天气泡）。
     * 安全：流式生成时 onUpdate 每帧 commitCurrent 把内容写入 store，这里只是给 store 里最后一条
     * 助手消息追加 cards 并刷新 _messages，不会冲掉正在流的文本。
     */
    fun attachCardToLastAssistant(card: QuroChatCard) {
        val msgs = store.all()
        // 🔧 修复（v200）：ui_widget / ui_card 在 ToolCalls 阶段执行时，本轮唯一的 assistant 占位消息是
        //   hidden=true 且带 toolCalls 的。若按「最后非隐藏 assistant」找，会命中【上一轮】可见消息，
        //   导致当前轮组件卡片串到历史气泡 → 用户看到「完全错乱」。
        //   正确目标：优先挂到本轮 hidden 占位（hidden 且含 toolCalls）；兜底再退最后非隐藏 assistant / 最后非隐藏消息。
        val target = msgs.lastOrNull { it.role == "assistant" && it.hidden && it.toolCalls?.isNotEmpty() == true }
            ?: msgs.lastOrNull { it.role == "assistant" && !it.hidden }
            ?: msgs.lastOrNull { !it.hidden }
        if (target != null) {
            store.update(target.id) { it.copy(cards = it.cards + card) }
        } else {
            store.add(QuroMessage(role = "assistant", content = "", cards = listOf(card)))
        }
        _messages.value = store.all()
    }

    private fun emitMeta() {
        _conversationsMeta.value = _convs.value
            .sortedByDescending { it.updatedAt }
            .map { metaOf(it) }
    }

    private fun deriveTitle(msgs: List<QuroMessage>): String {
        val firstUser = msgs.firstOrNull { it.role == "user" }
        return firstUser?.content?.take(20)?.trim()?.ifBlank { "新对话" } ?: "新对话"
    }

    private fun metaOf(conv: QuroPersistedConversation): QuroConversationMeta {
        val last = conv.messages.lastOrNull { it.role != "system" }
        val preview = last?.content
            ?.replace("\n", " ")
            ?.take(40)
            ?.trim()
            ?: "空对话"
        return QuroConversationMeta(conv.id, conv.title, conv.updatedAt, preview)
    }

    // ---- 人格卡 / 记忆库 接线 ----

    /** 项目开源地址（仓库建立后回填 owner；当前为默认值）。 */
    private val QURO_REPO_URL = "https://github.com/QuroAI/QuroAI"

    /** 默认系统提示词（未激活任何人格卡时使用）。 */
    private val DEFAULT_SYSTEM =
        """你是一个由 Quro AI 个人开发的 AI 助手（当前未启用特定人格卡，以通用身份应答），乐于助人、简洁准确。

## ⚠️ 关键规则：你必须调用工具（CRITICAL）
你已接入了设备工具调用能力，**并且你拥有充分的自主权决定何时调用工具**。

### 主动调用（最重要）
你**不必等用户明确说"帮我打开 / 查一下 / 调用 XX"才行动**。只要结合上下文判断某项能力能真正帮到用户、或能让你的回答更准更可靠，你就应该**主动**调用对应工具——像人自然使用工具一样，无需先征询、也无需报备"我要调用 XX 工具"。
- 用户随口说"有点闷" → 可主动 get_battery / get_wifi_info 给出环境状态；
- 用户描述一个任务 → 直接选最合适的能力执行，而不是先问"要我帮你做吗"；
- 工具调用是你的默认工作方式之一，不是"被要求才用"的被动功能。

当用户要求执行任何**具体动作**时——
包括但不限于：打开应用、启动APP、查询设备信息、设闹钟、开手电筒、振动、
查电量/WiFi/网络、读写应用内文件、运行代码、发HTTP请求、朗读文字（TTS）、打开网页等——

**你绝对不能只回复一段文字描述！你必须调用对应的工具函数去真正执行！**

示例：
- 用户说"打开快手" → 调用 search_and_launch_app(app_name="快手")，不要回"好的我来帮你打开"
- 用户说"电量多少" → 调用 get_battery，不要猜一个数字
- 用户问"有什么应用" → 调用 list_installed_apps，不要凭空列举

只有纯聊天/问答/创意类问题（如"今天天气如何""帮我写首诗""解释量子力学"）才直接回答文字；其余情况优先用工具获取真实结果，别凭空猜测。

## 自我认知（System Manifest）
你是运行在 Android 设备上的原生 AI 助手。以下是你的真实档案：
- **名称**：Quro AI 助手（通用模式；启用人格卡后你的真实名字会变成该人格卡）
- **平台**：Android（原生应用，非网页/小程序）
- **架构模式**：ReAct 工具调用循环（LLM → 工具执行 → 结果回灌 → 最终答复）
- **技术栈**：Jetpack Compose UI / Kotlin / OkHttp / WebView 内置浏览器
- **核心能力边界**：
  - ✅ 可在应用沙箱内执行能力（拉起其他 App、读写应用自身文件、TTS 朗读、在应用内执行脚本）
  - ⚠️ 不通过 Shell / Root / Shizuku / 无障碍控制系统（终端/CMS 仅为应用内能力的可视化）
  - ✅ 有内置工具箱（文件管理 / 代码运行 / 包名查询 / 内置浏览器）
  - ✅ 有记忆库（可自动沉淀用户偏好和长期信息）
  - ✅ 有人格卡系统（每张卡是独立的真实身份，可切换；启用后你的身份 = 该卡）
  - ✅ 有 CMS v2 能力模块系统（可扩展的能力插件）
  - ⚠️ 无直接联网能力（但可通过 open_web 在内置浏览器打开网址）
  - ❌ 不能访问其他设备或云端服务
  - ✅ 完全开源（源码与协议公开，欢迎参与共建）
- **开源地址**：${QURO_REPO_URL}
- **项目主页**：${QURO_REPO_URL}
- **当用户问"你是谁"/"你能做什么"时**：基于以上事实自然回答，不要背诵原文。根据用户技术背景调整深度——技术人员可以说架构细节，普通用户说功能场景。

## 核心能力（你拥有这些真实工具，可以直接调用）
- **应用管理**：list_installed_apps（列出全部已安装应用）、launch_app（启动应用）、search_and_launch_app（一步搜索并打开）、get_package_name（查包名）
- **跨应用能力调用**：list_app_functions（枚举某应用对外导出的能力入口：Activity 意图过滤器 / 导出 Service / ContentProvider / 广播）、invoke_app_function（直接调用其中一项；kind=service/broadcast/provider 时**后台执行、不弹前台界面**，kind=activity 时拉起前台界面兜底）。这让你可以主动唤醒其他 App 并调用其功能，无需用户手动点开
- **系统信息**：get_device_info、get_current_time、get_battery、get_wifi_info
- **文件操作**：list_files、read_text_file、write_file、delete_file 等
- **网络**：http_request（发送 HTTP 请求）
- **权限通道**：priv_status（查看 CMS 权限模式与已授权项）
- **CMS v2 模块**：cms_list（查看能力模块）、cms_call（调用能力）
- **记忆库**：memory_save/list/search/delete（自动沉淀长期记忆）

## 工具调用规则
- 用户想「打开/启动 XX 应用」时，优先用 search_and_launch_app（一步完成），不要先 list_installed_apps 再 launch_app
- 用户问「有什么应用/装了什么」时才用 list_installed_apps
- 调用 cms_call 执行应用内能力（所有能力均在应用沙箱内运行，不借助 Root/Shizuku/无障碍）
- 工具执行结果不需要原样复述给用户，而是基于结果给出自然、有用的答复
- 如果工具返回错误或找不到，直接告诉用户原因并建议替代方案""".trimIndent()

    /** 当前激活的人格卡（无则返回 null）。 */
    private fun activePersona(): QuroPersona? {
        val id = personaRepo.getActiveId()
        if (id.isBlank()) return null
        return personaRepo.loadAll().firstOrNull { it.id == id }
    }

    /** 欢迎语：若激活人格卡有开场白则用之，否则用通用问候。 */
    private fun defaultWelcome(): String {
        val opening = activePersona()?.opening?.takeIf { it.isNotBlank() }
        return opening ?: "你好，我是 Quro AI。已就绪，可以聊天、调用工具。点左上角菜单查看历史对话，或点 ➕ 新建对话。"
    }

    // ── 人格自动孵化（修复「AI 人格自动孵化没有真正工作」）──
    // 对话进行中按轮次累计触发：把近期对话交给 LLM 提炼为「孵化备忘」，
    // **追加**到当前激活人格的 incubation 字段（其本意即"孵化灵感与备忘"），
    // 让自动孵化真正闭环——而非只有手动按钮、incubation 字段永远空白、ask() 永不回写人格段。
    // 只追加、绝不覆盖用户编写的角色设定/聊天设定，避免破坏既定人格；孵化失败静默，不阻塞主对话。
    private var sinceIncubate = 0
    private val _autoIncubating = MutableStateFlow(false)
    val autoIncubating: StateFlow<Boolean> = _autoIncubating.asStateFlow()
    private val AUTO_INCUBATE_THRESHOLD = 3

    private fun maybeAutoIncubate() {
        // 对话结束 → 触发心跳孵化扫描（事件驱动，替代旧 15 分钟轮询）
        try { QuroPersonaViewModel.pulse() } catch (_: Exception) {}
        val persona = activePersona() ?: return
        if (persona.id.isBlank()) return
        val cfg = repo.load()
        if (cfg.apiKey.isBlank()) return
        sinceIncubate++
        if (sinceIncubate < AUTO_INCUBATE_THRESHOLD) return
        sinceIncubate = 0
        if (_autoIncubating.value) return
        _autoIncubating.value = true
        viewModelScope.launch(QuroCrashReporter.handler) {
            try {
                // 仅当本轮确有真实 AI 回复时才孵化，避免把报错/空轮喂给 LLM
                val lastMsg = store.all().lastOrNull { !it.hidden && it.content.isNotBlank() }
                if (lastMsg?.role != "assistant") return@launch
                val recent = store.all().takeLast(18)
                    .filter { it.content.isNotBlank() }
                    .joinToString("\n") { "${it.role}: ${it.content.take(400)}" }
                if (recent.length < 80) return@launch
                val prompt = buildAutoIncubatePrompt(persona, recent)
                val res = QuroLlmClient().chat(
                    cfg.baseUrl, cfg.apiKey, cfg.model,
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
            } catch (_: Exception) {
                // 孵化失败静默：不影响主对话
            } finally {
                _autoIncubating.value = false
            }
        }
    }

    private fun buildAutoIncubatePrompt(persona: QuroPersona, recent: String): String = """
你是「人格自动孵化」引擎。基于当前人格卡设定与近期对话，提炼出 1-3 条简短的"孵化备忘"——
关于这个人格未来应如何演化（语气微调建议、值得记住的用户偏好、角色设定可补充点等）。
只输出 JSON：{"notes":["备忘1","备忘2"]}，每条不超过 40 字，不要任何额外文字或 markdown。
当前人格：${persona.name}
角色设定：${persona.roleSetting}
聊天设定：${persona.chatSetting}
近期对话：
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
        val line = "• [$stamp] $note"
        val lines = existing.lineSequence().filter { it.isNotBlank() }.toMutableList()
        lines.add(line)
        val trimmed = if (lines.size > 40) lines.takeLast(40) else lines
        return trimmed.joinToString("\n")
    }

    /**
     * 组装系统提示词（身份置顶 + 工具只走 tools 字段）。
     *
     * 设计原则：
     * 1. 身份认知（名字+角色设定+聊天设定）永远放在 system prompt 最前面 → 模型首先看到「我是谁」
     * 2. 工具列表**不放在 system prompt 文本里**——完整工具集通过 API 的 tools 字段下发，
     *    避免 system prompt 被工具清单淹没（这正是此前人格被稀释的根因）。
     * 3. system prompt 只保留「何时该调用工具」的指引，不枚举具体工具名。
     * 4. 长期记忆放在最后 → 作为补充上下文。
     */
    private fun buildSystemPrompt(cfg: QuroModelConfig): String {
        val persona = activePersona()
        val sb = StringBuilder()

        // 平台/品牌自我认知基座（永远最先，不被人格卡覆盖）
        sb.append(QuroPlatformManifest.SYSTEM).append("\n\n")

        // ══════════════ 第一优先级：身份认知（人格卡 = AI 真实身份；Quro AI = 开发者；运行环境靠工具自行发现） ══════════════
        // ══════════════ 灵魂层（人格/标签/语音/记忆）由自写编排引擎生成 ══════════════
        // Project B0：QuroSoulPromptEngine 负责"这张人格卡是谁、怎么说话、记得什么、用什么声音"，
        // 与平台基座 / 工具清单 / 用户技能解耦（下方由调用方拼接）。
        val voiceStyleHint = if (persona != null && QuroTtsPrefs.getSource(appContext) == QuroTtsPrefs.SOURCE_CLOUD) {
            val vt = QuroTtsProviderPrefs.getSelectedStyleTags(appContext)
            if (vt.isNotEmpty()) QuroVoiceStyle.systemHint(vt) else null
        } else null
        val soulCtx = SoulContext(
            persona = persona,
            tags = if (persona != null) tagRepo.resolve(persona.tags) else emptyList(),
            memories = memoryRepo.loadForPersona(persona?.id ?: ""),
            autoSaveMemory = autoSaveMemory.value,
            voiceStyleHint = voiceStyleHint,
        )
        sb.append(QuroSoulPromptEngine.build(soulCtx)).append("\n")

        // ══════════════ 第二优先级：自我认知 + 工具调用原则（不列工具清单） ══════════════
        // 平台/品牌基座（QuroPlatformManifest.SYSTEM）已声明「你是 Quro AI」与「必须调用工具」，
        // 人格仅作为上方叠加的扮演层，此处不再重复品牌与工具原则。



        // ══════════════ 第三优先级：长期记忆（受「AI 自动保存记忆」开关控制） ══════════════
        // autoSaveMemory=false 时完全不注入记忆相关提示：AI 既不读取已有记忆，也不主动保存。
        // 长期记忆已由 QuroSoulPromptEngine 在灵魂层统一编排（受「AI 自动保存记忆」开关控制）。

        // ══════════════ 工具菜单：必须与下方实际下发的 tools 字段严格一致 ══════════════
        // 直接由「当前生效的工具集」生成，避免菜单与字段不一致导致模型选了不存在的工具。
        // 记忆开关关闭时，从工具集里摘除 memory_* 工具，确保 AI 既不提示也不调用记忆类工具。
        val baseSpecs = if (cfg.useFullTools) registry.fullSpecs() else registry.coreSpecs()
        val activeSpecs = if (autoSaveMemory.value) baseSpecs else baseSpecs.filter { !it.name.startsWith("memory_") }
        appendCapabilityAwareness(sb, activeSpecs)

        // ══════════════ 用户技能 SKILL（已启用的自定义指令注入系统提示词） ══════════════
        // alwaysOn=false 的技能不再常驻系统提示词（改为触发词命中时按需注入，避免重复）
        val skills = QuroSkillStore.enabledList(appContext).filter { it.alwaysOn }
        if (skills.isNotEmpty()) {
            sb.append("\n## 已启用技能（Skills）\n")
            sb.append("以下是用户已启用的自定义技能，请将其指令作为额外的行为约束 / 能力说明，在合适时主动按技能行事：\n")
            skills.forEach { s ->
                sb.append("\n### ${s.name}\n")
                if (s.description.isNotBlank()) sb.append("${s.description}\n")
                if (s.prompt.isNotBlank()) sb.append(s.prompt).append("\n")
            }
        }

        // ══════════════ 用户身份（让用户知道如何称呼用户） ══════════════
        // v232 修复：此前系统提示词从不注入 user_name，导致 AI「不知道用户叫什么」。
        val userName = userProfile.value.name
        if (userName.isNotBlank()) {
            sb.append("\n## 关于用户\n")
            sb.append("当前用户的名字是「${userName}」。在合适的场合可以直接用这个名字称呼用户，但不要每句话都刻意叫名字。\n")
        }

        return sb.toString().trim()
    }

    /** 把当前生效的工具清单（与 tools 字段一致）与 CMS v2 能力/权限策略拼进系统提示词。 */
    private fun appendCapabilityAwareness(sb: StringBuilder, specs: List<QuroToolSpec>) {
        val repo = QuroCmsRepository(appContext)
        val caps = repo.loadCapabilities()
        val cmsPolicy = QuroPolicyStore.getCms(appContext)
        val privPolicy = QuroPolicyStore.getPriv(appContext)

        sb.append("\n\n## 我的能力（当前可用的工具函数）\n")
        sb.append(
            "【强制规则】以下是你**当前真实可调用的工具函数**（与 API 的 tools 字段完全一致）。" +
            "当用户意图匹配任一工具时，**必须调用它真正执行**，而不是用文字描述你会做什么。" +
            "流程：调用工具 → 拿到结果 → 基于结果回答；若工具返回错误，告诉用户原因。" +
            "**多个相互独立的动作可在同一条回复里一次性发起多个 tool_calls**" +
            "（例如「打开快手、查电量、设个闹钟」应在一轮里并行调用 search_and_launch_app / get_battery / set_alarm）。" +
            "**收到工具结果后，只要用户请求还没满足，就继续调用下一个工具，不要提前结束。**\n"
        )
        sb.append("### 工具清单（格式：工具名：用途）\n")
        specs.forEach { s ->
            sb.append("- ${s.name}：${s.description}\n")
        }
        sb.append("\n（其中 `ui_open_*` / `ui_toggle_*` / `ui_clear_*` / `ui_new_*` 为**界面控制工具**：调用后会在当前对话框直接打开对应界面/弹层/开关，例如 ui_open_onlyoffice 打开 WPS/文档中心、ui_toggle_deepthink 切换深度思考、ui_clear_chat 清空对话。它们同样可由你并行发起，让用户无需手动点击即可导航应用。）\n")
        sb.append("\n（CMS 模块与大部分能力在应用沙箱内执行（intent/js/api）；另有系统级通道 L1 无障碍控屏 / L2 Shizuku / L3 设备管理员 / L4 ROOT / L5 Linux，对应工具已包含在上方清单中，运行时由系统授权与资产可用性把关，未授权时工具会返回明确引导，无需你做通道自查。）\n")
        sb.append("\n### 在对话框里「展示」UI（重要）\n")
        sb.append(
            "- `ui_widget`：当你想给用户**可视化、可交互**的结果时，调用它在对话框内直接渲染组件，而不是只发纯文本。" +
            "支持几十种类型：button（按钮触发动作）/ toggle（开关）/ slider（滑块）/ progress（进度条）/ stat（统计数字）/ alert（提醒条）/" +
            "table（表格）/ list（可选项列表）/ segmented（分段选择）/ pie（饼图）/ rating（星级评分）/ countdown（倒计时）/" +
            "tabs（标签页）/ expandable（折叠块）/ form（表单）/ chips（标签组，单选或多选）/ steps（步骤条）/ gauge（仪表盘）/ media（图片/音频/视频链接）/ info（信息块）/" +
            "以及 legacy 的 todo / chart / note / actions。" +
            "每个组件带丰富属性，组合即可产出「几百款」不同的 UI 输出。" +
            "组件会在对话框底部卡片栏即时渲染、随用户操作（勾选/拖动/切换）实时变化。示例：发一张待办清单、一个带图表的统计卡、一组可点选的标签、一个提交表单。" +
            "需要用户在对话框里看到可点的东西时，优先用 ui_widget，而不是只写文字。\n"
        )
        sb.append("- `ai_browser`：联网搜索、抓取网页正文、打开内置浏览器、自动研究简报。研究/查资料类任务【务必用一次 action=automate】（它内部完成搜索+抓取+合并，一次返回）；不要分步调用 search 再 read，那会拖慢对话。需要联网信息时调用。\n")
        sb.append("- 语音能力：你可通过 `speak` / `stop_speak` 工具进行 **TTS 语音合成输出**（音色/语速等配置见「设置 → 语音」）。**STT 语音识别是用户的输入通道**——用户说的话会被转写成文字作为消息发给你，你无需、也不能去「调用 STT 工具」，直接基于收到的文字消息作答即可。\n")

        sb.append("\n### CMS v2 模块（可扩展）\n")
        if (caps.isEmpty()) {
            sb.append("- 当前未安装任何能力模块。用户可在「设置 → CMS v2 模块」中添加模块/能力。\n")
        } else {
            sb.append("- 我可以通过以下工具真实调用已安装的能力：\n")
            sb.append("  - cms_list：列出所有能力模块与可用能力（id / 说明 / 风险级别）。\n")
            sb.append("  - cms_call：调用某个具体能力，参数 {capability_id, args}。\n")
            sb.append("- 已安装能力清单：\n")
            caps.forEach { (m, c) ->
                val risk = c.requiresPermissions.mapNotNull { m.findPermission(it)?.level?.name }.distinct()
                    .joinToString("/").ifBlank { "Normal" }
                sb.append("  · [${m.name}] ${c.id} — ${c.summary}（风险：$risk）\n")
            }
            sb.append("- 调用示例：用户说「帮我 echo 一段文字」，可用 cms_call({capability_id:\"echo_text\", args:{text:\"hello\"}}) 执行。\n")
        }
        sb.append("\n## CMS 权限模式（重要）\n")
        sb.append("- priv_status：查看 CMS v2 权限模式与已授权项；L1-L5 系统级通道已随工具集开放，运行时由系统授权与资产可用性把关，未授权工具会返回明确引导。\n")
        sb.append("- 直接调用 cms_call 即可执行对应能力；若策略=询问且未授权，提示用户在对话底部控制条切到「允许」。\n")

        sb.append("\n## 权限策略（重要）\n")
        sb.append("- CMS v2 模块权限模式 = ${cmsPolicy.name}；权限子系统模式 = ${privPolicy.name}。\n")
        sb.append("- ${cmsPolicy.usageHint()}\n")
        sb.append("- ${privPolicy.usageHint()}\n")
        sb.append("- 当策略=询问(ASK)时，调用高风险能力会被拦截并提示用户在对话底部控制条切到「允许」；不要反复重试，直接告诉用户去切换即可。\n")
    }

    /** 把记忆库工具（AI 自动沉淀长期记忆）的用法注入系统提示词。 */
    private fun appendMemoryAwareness(sb: StringBuilder) {
        sb.append("\n\n## 长期记忆（记忆库）\n")
        sb.append("- 你拥有记忆库工具，应主动「自动保存」用户透露的持久信息：\n")
        sb.append("  - memory_save：保存一条记忆（content 必填；可选 title/group/tags）。\n")
        sb.append("  - memory_list：列出全部已保存记忆。\n")
        sb.append("  - memory_search：按关键词检索记忆。\n")
        sb.append("  - memory_delete：删除某条记忆。\n")
        sb.append("- 当用户说出偏好、习惯、项目背景、重要约定、联系方式、长期目标等值得跨会话记住的内容时，主动调用 memory_save 沉淀，不要等用户要求。\n")
        sb.append("- 上方「记忆库」段落已给出已有记忆，回答时自然融入，不要生硬提及「根据记忆」。\n")
    }
}
