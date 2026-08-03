package com.ai.assistance.quro.ui

import android.content.Context
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
import com.ai.assistance.quro.core.aci.QuroAciManager
import com.ai.assistance.quro.core.skill.QuroSkill
import com.ai.assistance.quro.core.skill.QuroSkillStore
import com.ai.assistance.quro.core.network.QuroLlmClient
import com.ai.assistance.quro.core.model.QuroModelConfig
import com.ai.assistance.quro.core.model.QuroModelConfigRepository
import com.ai.assistance.quro.core.model.QuroFunctionModelConfigRepository
import com.ai.assistance.quro.core.model.QuroFunctionType
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
import com.ai.assistance.quro.core.tools.QuroTtsProviderPrefs
import com.ai.assistance.quro.core.tools.QuroTtsProviders
import com.ai.assistance.quro.core.tools.QuroTtsProviderKind
import com.ai.assistance.quro.core.tools.QuroVoiceFeaturePrefs
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
    private val registry = buildQuroRegistry(appContext).also { QuroToolRegistry.active = it }

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
    // TTS 自动朗读去重：记录已朗读的最后一条 assistant 消息 id，防止退出重入对话框时重复播放。
    // （Compose remember 是纯内存态，销毁重建即丢失 → 必须提升到 ViewModel 层。）
    private val _lastSpokenMsgId = MutableStateFlow("")
    val lastSpokenMsgId: StateFlow<String> = _lastSpokenMsgId
    fun markSpoken(msgId: String) { _lastSpokenMsgId.value = msgId }
    // A4 修复：每个会话独立的「生成中」状态。原全局 _busy 会导致切换会话后打断按钮残留，
    // 现改为按 conversationId 记录，UI 仅对【当前可见会话】显示打断按钮。
    private val _busyMap = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    fun isBusy(conversationId: String): Boolean = _busyMap.value[conversationId] == true
    private val sendJobs = mutableMapOf<String, Job>()
    /** 每个会话的「在线生成缓冲」：生成中或刚结束的会话，其最新内容优先从此处取，
     *  使切换回该会话时即时看到最新（含在途 token），无需等 ≤2s 落盘快照。 */
    private val liveBuffers = mutableMapOf<String, QuroConversationStore>()

    // 屏幕理解闭环（原创）：复用 L1 无障碍服务周期性采集屏幕帧
    private val visionLoop = QuroVisionLoop(appContext, viewModelScope)
    val visionEnabled: StateFlow<Boolean> = visionLoop.enabled
    val visionStatus: StateFlow<QuroVisionLoop.Status> = visionLoop.status
    fun setVisionEnabled(on: Boolean) { visionLoop.setEnabled(on) }

    // 对话轮次状态机（原创）：管理每轮生成的 activate / complete / interrupt
    private val turn = QuroTurnController()
    fun turnState(conversationId: String) = turn.stateOf(conversationId)
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

    /** #862：正在后台生成中的会话 id 集合，供历史抽屉显示「生成中」徽标（切走其它会话续跑可见）。 */
    val generatingIds: StateFlow<Set<String>> =
        _busyMap.map { map -> map.filterValues { it }.keys.toSet() }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())
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
        // ★ 全面排查修复（v316）：init 内禁止主线程 IO。convRepo.loadAll() 读取全量对话历史
        //   + saveAll() 写盘均为重 IO，对话量大时在主线程同步执行会直接 ANR（启动/进聊天即卡死）。
        //   这里只同步设置引用与空初始态，重 IO 全部挪到 IO 线程异步完成。
        instance = this
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
                loaded.add(QuroPersistedConversation(id, "新对话", now, now, listOf(welcome)))
                convRepo.saveAll(loaded)
            }
            _convs.value = loaded
            // #882 数据自检：修复 v407-v409 串台 bug 写入磁盘的脏数据。
            // 历史版本（commitCurrent 用共享 store 作 buf）在切会话时会把别会话的消息混进当前会话的
            // messages 列表并持久化 → selectConversation 每次从 conv.messages 加载的都是脏数据，
            // 运行时隔离再正确也无法清除已落盘的污染。
            // 清洗策略：每个会话内按 id 去重（保留首次出现），并记录清洗日志供诊断。
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

        // 注册机器人会话绑定器：把平台用户消息按设置写入 App 持久化会话（纯内存操作，留主线程）
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
        // 多会话切换修复：新建会话前先打断【当前可见会话】正在进行的生成，避免孤儿协程污染；
        // 仅取消当前会话（按 id），不波及后台其它会话——后台续跑的会话不受影响。
        sendJobs[_currentId.value]?.cancel(); sendJobs.remove(_currentId.value)
        liveBuffers.remove(_currentId.value)
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
        val from = _currentId.value
        val conv = _convs.value.firstOrNull { it.id == id } ?: return
        QuroDiag.log("SELECT", "from=$from to=$id busyFrom=${isBusy(from)} busyTo=${isBusy(id)}")
        // 切走【不再打断】其它会话的生成：每条在途生成使用独立缓冲 buf（见 send()），
        // 经 commitCurrent(convId, buf) 按会话 id 落盘、仅当目标会话可见时才刷新显示，
        // 因此切走时旧协程继续写自己的 buf，不会污染新会话；切回时从持久化 reload 即可看到进度。
        _currentId.value = id
        activeConversationId = id
        store.clear()
        // 优先取在线缓冲（生成中/刚结束）→ 即时看到最新；否则取持久化消息。
        val live = liveBuffers[id]
        if (live != null) live.all().forEach { store.add(it) }
        else conv.messages.forEach { store.add(it) }
        _messages.value = store.all()
        // busy 仅反映【当前可见会话】是否生成中；切回仍在后台生成的会话时，
        // _busyMap 中该会话仍为 true，打断按钮会自动重新显示。
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
        val welcome = QuroMessage(role = "assistant", content = "对话已清空。")
        store.add(welcome)
        commitCurrent()
    }

    // ---- 发送 ----

    fun send(
        text: String,
        attachments: List<QuroAttachment> = emptyList(),
        cfg: QuroModelConfig = repo.load(),
        /** 用户在本轮对话框「选择技能」里显式选中的技能；非空时其指令仅作用于本轮消息。 */
        skill: QuroSkill? = null,
    ) {
        val t = text.trim()
        if (t.isEmpty() && attachments.isEmpty()) return
        // 多会话切换修复：锁定本轮归属会话 convId，整个协程以内一律以 convId 记账，
        // 不再读实时 _currentId，避免切换会话后轮次/忙态/落盘串台。
        val convId = _currentId.value
        activeConversationId = convId
        QuroDiag.log("SEND", "convId=$convId busyBefore=${isBusy(convId)} text=${t.take(80).replace("\n", " ")}")
        // 轮次打断（barge-in）：仅打断【同一会话】正在进行的前一轮，绝不波及后台其它会话。
        // ★ 存话根因修复：仅看 isBusy 标志会在标志错位时漏掉"仍在生成"的会话，
        //   导致同一会话被起多个 job 并发写 liveBuffers[convId] 与 _convs[convId] → 回复互相覆盖/错位（写个html吧挂到设备仪表盘即此）。
        //   改为同时判断 turn 状态与在途 job，确保同一会话任意时刻只有 1 个活跃生成。
        if (isBusy(convId) || turn.stateOf(convId) != QuroTurnController.State.IDLE || sendJobs.containsKey(convId)) {
            // #878 修复：打断旧轮前必须把旧 buf 已生成的全部内容同步回共享 store 并强制落盘。
            // 否则新 buf 在下面快照共享 store 时拿不到旧轮的 AI 回复 → 用户看到"内容消失"。
            // 旧 job 取消后其 finally/cancellation-handler 虽也会 commitCurrent(旧buf)，
            // 但新 job 的后续 commitCurrent(新buf) 会用缺旧内容的新 buf 覆盖 _messages → 旧内容仍丢失。
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
        // 屏幕理解（看懂屏幕）：开启时把当前屏幕的无障碍节点树快照注入系统提示，
        // 使 AI 每轮都能"看"到当前屏幕在做什么（无需像素截图权限）。
        val screenCtx = if (visionEnabled.value) visionLoop.consumeLatestSnapshot()?.let { "\n\n[当前屏幕 UI 结构]\n$it" } else null

        // ── 立即显示用户消息（不等待协程调度）──
        // 用户消息必须先于 launch add 到共享 store 并 commitCurrent，
        // 这样界面立刻反映出"已发送"状态，无需等 AI 响应。
        // 用户消息先构造成引用，便于既加入共享 store（即时显示）又追加进种子（生成副本）。
        val userMsg = QuroMessage(
            role = "user",
            content = t,
            attachments = if (attachments.isNotEmpty()) attachments else null,
            senderName = userProfile.value.name.takeIf { it.isNotBlank() },
            avatarUrl = userProfile.value.avatarUri.takeIf { it.isNotBlank() },
        )
        // ★ 存话根因修复：种子快照必须取自【本会话权威态】，绝不用可能被切会话交换的共享单例 store。
        //   优先本会话在线缓冲（最新、可能尚未落盘）→ 否则持久化 _convs[convId].messages（落盘权威态）→ 兜底空。
        //   再把本条用户消息追加其后（共享 store 仅用于即时显示，不作为种子来源，避免串台/缺失）。
        val liveSeed = liveBuffers[convId]?.all()
        val persistedSeed = _convs.value.firstOrNull { it.id == convId }?.messages
        val convBase = (liveSeed ?: persistedSeed ?: emptyList()).toList()
        // ★ 串台防御（v434+ 修复）：轮次信息通过【隐藏 system 消息】传给 LLM，
        //   不再注入 userMsg.content（旧方案会导致 seededUserMsg 进入 buf→liveBuffer→commitCurrent 刷屏，
        //   使用户 UI 看到内部 [第N轮] 标记泄露）。
        val roundNumber = convBase.count { it.role == "user" } + 1
        val firstUser = convBase.none { it.role == "user" }
        val initialMessages = convBase + userMsg
        store.add(userMsg)  // 仅用于即时显示（commitCurrent 默认用 store 刷屏/首存）
        QuroCrashLogger.logEvent(appContext, "USERMSG", "senderName=[${(userProfile.value.name ?: "").take(20)}] avatarUrl=[${(userProfile.value.avatarUri ?: "").take(60)}]")
        QuroDiag.log("SEED", "convId=$convId seedMsgs=${initialMessages.size} hasAssistant=${initialMessages.any { it.role == "assistant" }} busyBefore=${isBusy(convId)} liveSeed=${liveSeed != null}")
        commitCurrent(convId, updateTitle = firstUser)  // 外部立即显示（首条用户消息同步衍化对话标题）

        // #864 修复：busy 标志必须在 launch 之前置位，使 ChatScreen 的「正在思考…」人格占位气泡
        // 在用户点击发送的那一帧就出现（带人格头像），否则会因 launch 调度晚一帧才显示头像。
        _busyMap.value = _busyMap.value + (convId to true)

        // 接住对话协程里逃逸的异常，转成可见报错而非界面卡死/进程崩溃。
        val job = viewModelScope.launch(QuroCrashReporter.handler) {
            // 独立缓冲：用 send() 同步抓取的【本会话快照】initialMessages 作为本轮工作副本 buf，
            // 不再读单例共享 store（共享 store 随时可能被切会话 swap），使后台生成与显示 store 彻底解耦——
            // 切走其它会话不再污染、本会话可在后台续跑；commitCurrent(convId, buf) 按 id 落盘。
            val buf = QuroConversationStore().apply { initialMessages.forEach { add(it) } }
            val store = buf
            liveBuffers[convId] = buf
            val genAssistant = QuroAssistant(QuroLlmClient(), registry, buf)
            try {
                // 注意：用户消息已在 launch 外添加到共享 store 并 commitCurrent（立即显示），
                // buf 快照已包含该消息，此处无需重复添加。
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
                // 用户显式选中的技能（对话框「选择技能」）：仅作用于本轮，指令作为隐藏消息预注入
                if (skill != null && skill.prompt.isNotBlank()) {
                    store.add(
                        QuroMessage(
                            role = "user",
                            content = "[用户已选择技能「${skill.name}」，请严格按以下指令处理其本条消息]\n### ${skill.name}\n${skill.prompt}",
                            hidden = true,
                        ),
                    )
                }
                // 落盘技能隐藏消息到 buf（用户消息与标题更新已在 launch 外的 commitCurrent 处理）
                commitCurrent(convId, buf)
                // ★ 串台防御（v434+）：轮次标记改为【隐藏 user 消息】注入 buf，
                //   不再污染 userMsg.content（旧方案导致 [第N轮] 泄露到 UI）。
                //   hidden=true 确保 UI 渲染层不显示、落盘后可追溯调试。
                store.add(
                    QuroMessage(
                        role = "user",
                        content = "[第${roundNumber}轮] 请严格针对本轮（最新一条）用户消息作答，忽略更早轮次的用户消息。",
                        hidden = true,
                    ),
                )
                if (cfg.apiKey.isBlank()) {
                    store.add(
                        QuroMessage(
                            role = "assistant",
                            content = "⚠️ 尚未配置模型 API Key，请点右上角模型芯片 →「在模型设置中管理」填写 baseUrl / apiKey / model。",
                        ),
                    )
                } else {
                    // 功能模型配置：主对话 (CHAT) 恒用主模型（resolveConfig 默认跟随主模型，等效于 cfg），
                    // 此处经统一入口消费，便于后续子能力接入独立模型时复用同一机制。
                    val effectiveCfg = QuroFunctionModelConfigRepository(appContext).resolveConfig(QuroFunctionType.CHAT, cfg)
                    // ask 内部已对每个环节兜底，这里再兜一层：任何意外都转成一条可见的错误消息，
                    // 且 finally 保证 _busy 一定复位，避免「卡死在思考中」导致此后永远无法回复。
                    runCatching {
                        // ★ ANR 修复：buildSystemPrompt 内部同步做 memory/experience/skills 存储读取 + 大字符串拼接，
                        // 原为 ask 的实参在 viewModelScope(主线程) 上求值 → 主线程重 I/O/计算 → 触发系统 ANR 对话框。
                        // 改为在 IO 线程先把提示词算好，再交给 ask（ask 自身仍切 IO 执行 ReAct 循环）。
                        val sysPrompt = withContext(Dispatchers.IO) { buildSystemPrompt(effectiveCfg) + (screenCtx ?: "") }
                        genAssistant.ask(appContext, effectiveCfg, sysPrompt, autoSaveMemory = autoSaveMemory.value, stream = true) {
                        // 工具调用/结果产生、以及流式 token 到达时实时刷新并落盘（退出生效），
                        // 退出也能保留中间过程；commitCurrent 内部已对落盘做 ≤1s 节流。
                            commitCurrent(convId, buf)
                        }
                    }.onFailure { e ->
                        if (e is CancellationException) {
                            // 用户主动打断生成：不报红错误，附一行明确反馈并保留已生成的部分内容
                            QuroDiag.log("SEND_CANCEL", "convId=$convId (job cancelled → 已停止生成)")
                            store.add(QuroMessage(role = "assistant", content = "⏹ 已停止生成。"))
                            commitCurrent(convId, buf)
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
                commitCurrent(convId, buf, forceSave = true)
                // 对话一轮完成 → 触发人格自动孵化（按轮次累计，静默、不阻塞主对话）
                maybeAutoIncubate()
                // 回复完成 → 统一触发通知（离开软件弹系统通知 + 刷新桌面卡片，受总开关控制）
                runCatching {
                    store.all().lastOrNull { it.role == "assistant" && !it.hidden && it.content.isNotBlank() }
                        ?.let { fireReplyNotification("Zorv AI", it.content) }
                }
                } catch (e: Exception) {
                    store.add(
                        QuroMessage(
                            role = "assistant",
                            content = "⚠️ 发生错误：${(e.message ?: "未知错误").take(200)}",
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
                // 仅当在线缓冲仍指向本轮 buf（即无新轮 supersede）时才做收尾：
                // ① 把本轮最终内容同步回共享 store，保证下一轮 send() 的种子快照含完整历史
                //    （含上轮 AI 回复）。否则下一轮 initialMessages 缺失上轮回复 →
                //    上轮内容消失 / 多轮上下文断裂 / 单对话框内回复串位。
                // ② 再移除 liveBuffer，避免误删续跑/新轮的缓冲。
                // ⚠️ 必须用 this@QuroChatViewModel.store 访问类字段：launch 内 line 511 把 `store` 重名遮蔽成本轮 buf。
                if (liveBuffers[convId] === buf) {
                    // ★ 多会话串台根因修复（对齐元宝「每会话状态独立、不共享」原则）：
                    //   全局单例 store 仅承载【当前可见会话】的工作副本。后台（不可见）会话生成完成时，
                    //   若也把自身内容无条件覆盖进全局 store，会污染下一个可见会话的 send() 首显
                    //   （store.add(userMsg) + commitCurrent 默认用 store 刷 _messages）→ 屏幕串出旧会话内容
                    //   （如「心情日记已创建完成」）。故仅当本会话当前可见才同步全局 store；
                    //   其回复早已通过 commitCurrent 的落盘分支正确写入 _convs[convId].messages，
                    //   切回时由 selectConversation 重新装载，数据不丢。
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

    /** 打断当前正在进行的生成：取消对应会话的发送协程并立即复位 busy（已生成的部分内容已落盘，不报错）。
     *  按当前可见会话 id 精准取消对应 job（sendJobs 映射），不波及后台其它会话的生成。 */
    fun stop() {
        val id = activeConversationId
        // #878 修复：取消前把当前 buf 已生成内容同步回共享 store并强制落盘，
        // 否则取消后 buf 仅在 job 内部，共享 store 缺失 → 切走再切回或新消息 barge-in 时内容丢失。
        val stoppingBuf = liveBuffers[id]
        // ★ 串台防御：仅当被停止的会话当前可见时才同步全局 store（全局 store 只承载可见会话副本）。
        //   停止后台不可见会话时若仍覆盖全局 store，会污染随后可见会话的 send() 首显。
        if (stoppingBuf != null && id == _currentId.value) {
            store.clear()
            stoppingBuf.all().forEach { store.add(it) }
            commitCurrent(id, forceSave = true)
        }
        QuroDiag.log("STOP", "id=$id (manual stop button)")
        sendJobs[id]?.cancel()
        sendJobs.remove(id)
        turn.interrupt(id)
        _busyMap.value = _busyMap.value - id   // 立即复位【当前会话】：打断按钮马上切回发送
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
            fireReplyNotification("Zorv AI", reply)
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
            fireReplyNotification("Zorv AI", reply)
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
                // 功能模型配置接入引擎：语音球问答使用 CHAT 绑定模型
                val effCfg = QuroFunctionModelConfigRepository(appContext).resolveConfig(QuroFunctionType.CHAT, cfg)
                // ★ ANR 修复：与对话框主路径一致，buildSystemPrompt 放到 IO 线程求值，避免语音球问答在主线程做存储读取。
                val sysPrompt = withContext(Dispatchers.IO) { buildSystemPrompt(effCfg) }
                // #1110：语音球问答原默认 stream=false → 整段回、不逐层；与主对话（stream=true）行为不一致，
                // 表现为「部分返回不是一层一层返回、自己回到对话框」。云模型改为流式，与文本框主路径一致。
                assistant.ask(appContext, effCfg, sysPrompt, autoSaveMemory = autoSaveMemory.value, stream = true, onUpdate = onTick)
            }.getOrElse { e ->
                if (e is CancellationException) "⏹ 已停止生成。" else "⚠️ 语音球出错了：${e.message ?: "未知错误"}"
            }
        }
    }

    fun currentConfig(): QuroModelConfig = repo.load()

    // ---- 内部 ----

    /** 把当前内存 store 的变动写回 _convs、刷新消息流并落盘。
     * @param forceSave 跳过落盘节流，强制立即写盘（用于对话收尾，确保最终内容不丢）。
     * 落盘节流：流式生成时 onUpdate 每 ~200ms 触发一次 commitCurrent，若每次都 saveAll 全量对话，
     * 会把整段对话 JSON 高频重写磁盘。这里把真实写盘限频到 ≥2s 一次，状态刷新（_messages/_convs）
     * 仍每次即时更新，保证 UI 实时跟手、磁盘不爆。 */
    // ★ 存话修复：落盘节流改为【按会话】独立计时，避免会话 A 的写盘把会话 B 的写盘节流掉，
    //   导致 B 在 2s 内的最新内容未被落盘而丢失（多会话并存时尤其明显）。
    private val lastSaveMsByConv = mutableMapOf<String, Long>()
    // 多会话切换修复：convId 指定本轮归属会话。落盘【始终】写回 _convs[convId]；
    // 仅当 convId 即当前可见会话时才刷新可见消息流 _messages，避免后台生成串台到前台。
    private fun commitCurrent(convId: String = _currentId.value, buf: QuroConversationStore = store, updateTitle: Boolean = false, forceSave: Boolean = false) {
        val id = convId
        val msgs = buf.all()
        // #883 修复（内容直接消失根因）：仅当 buf 是【允许刷新显示】的来源时才更新可见消息流 _messages：
        //   ① buf === liveBuffers[id]：本轮正在干活的活动缓冲（流式增量 / 终态都走它）；
        //   ② buf === store：共享工作副本（clear / 首条用户消息立即显示 / 语音球等非生成路径）；
        //   ③ liveBuffers[id] == null：该会话已无在线缓冲（本轮是最终完成者，落最终内容）。
        // 反例——被取消/打断的【过时旧 buf】（liveBuffers[id] 已指向更新的 buf，且 buf !== store）：
        //   它的 catch 仍会 commitCurrent(convId, oldBuf)，若放行会同步把 _messages 覆盖回旧内容
        //   （缺最新用户消息 + 新一轮流式），表现为「内容直接消失 / 回退到旧内容」。
        //   此守卫与 #877 的 IO 落盘守卫（activeBuf != null && activeBuf !== buf → return）同源，
        //   一个堵落盘、一个堵显示，彻底断掉过时缓冲对当前会话的污染。
        val canUpdateDisplay = (buf === store) || (liveBuffers[id] === buf) || (liveBuffers[id] == null)
        // ★ 唯一允许刷新可见消息流的闸门（#883 修复点）。
        //   注意：本行之后曾紧跟一条无守卫的 `if (id == _currentId.value) _messages.value = msgs`，
        //   它会在每次 commitCurrent 时绕过 canUpdateDisplay，把被取消/过时的旧 buf 内容强制覆盖 _messages
        //   → 表现为「内容直接消失 / HTML 写出又消失 / 切会话内容丢失」。v414 已删除那条重复行。
        if (id == _currentId.value) {
            QuroDiag.log("DISPLAY", "convId=$id allow=$canUpdateDisplay msgs=${msgs.size} bufIsLive=${liveBuffers[id] === buf} bufIsStore=${buf === store}")
            if (canUpdateDisplay) _messages.value = msgs
        }
        // ◇ #763 相关（非根因修复，仅 IO 侧节流优化）：经核查 QuroAssistant.ask 整体运行于
        //   withContext(Dispatchers.IO)，onUpdate(emit) 在该 IO 作用域内触发且已被节流到 ~500ms/次，
        //   故 commitCurrent 本就在 IO 线程，并非主线程 ANR 的直接成因。此处仅将开销更大的
        //   `_convs` 全量更新 + `emitMeta()`（历史列表/元数据映射）+ `saveAll` 落盘从「每次 emit(2Hz)」
        //   进一步合并节流到 ≤2s 一次，减少无谓的列表拷贝与磁盘写；主线程 ANR 真凶仍需 StrictMode + 真机
        //   主线程埋点（androidx.tracing）复现取证后定位，切勿将此节流误读为 ANR 修复。
        val now = System.currentTimeMillis()
        val last = lastSaveMsByConv[id] ?: 0L
        val due = forceSave || now - last >= 2000L
        if (due) {
            lastSaveMsByConv[id] = now
            viewModelScope.launch(AppExecutors.io) {
                // #877 串台修复：落盘前再次校验本 buf 是否仍是该会话的活动 liveBuffer。
                // send() 打断旧轮时会用新 buf 覆盖 liveBuffers[convId]，旧 job 此前已排进 IO 线程的
                // 落盘任务若晚于新轮执行，会拿旧 buf 的内容把新会话覆盖掉 → 回复串台/丢失。
                // 仅当「liveBuffers[id] === buf」或「该会话已无 liveBuffer（本 job 是最終完成者）」才写盘。
                val activeBuf = liveBuffers[id]
                if (activeBuf != null && activeBuf !== buf) return@launch
                val existing = _convs.value.firstOrNull { it.id == id }
                val title = if (updateTitle && existing != null) deriveTitle(msgs) else existing?.title ?: "新对话"
                _convs.value = _convs.value.toMutableList().also { list ->
                    val idx = list.indexOfFirst { it.id == id }
                    if (idx >= 0) list[idx] = list[idx].copy(messages = msgs, updatedAt = System.currentTimeMillis(), title = title)
                }
                QuroDiag.log("SAVE", "convId=$id msgs=${msgs.size} activeBufSame=${liveBuffers[id] === buf} force=$forceSave")
                emitMeta()
                runCatching { convRepo.saveAll(_convs.value) }
            }
        }
    }

    /**
     * 把 AI 经 ui_widget / ui_card 下发的富组件挂到「当前助手消息」气泡里（实现可视化组件融进聊天气泡）。
     * 安全：流式生成时 onUpdate 每帧 commitCurrent 把内容写入 store，这里只是给 store 里最后一条
     * 助手消息追加 cards 并刷新 _messages，不会冲掉正在流的文本。
     */
    fun attachCardToLastAssistant(card: QuroChatCard) {
        // 🔧 v290 修复：代码执行状态卡（type=toolcall，AI 经 ui_widget / ui_card 下发的
        // 「运行中 / 完成 / 失败」进度卡）不再作为独立卡片浮在对话框里——
        // 那样会「位置错（错挂到上一轮可见气泡）/ 提前出现（任务还没执行就显示）/ 与工具块重复」。
        // 改为熔化进「执行轨迹」总线，由 A 系统 ToolCallBlock 内嵌的「执行轨迹」统一呈现，
        // 真正落实「执行追踪融进工具调用卡，不再是独立浮层」。
        if (card is QuroChatCard.ToolCallCard) {
            val label = when (card.status.lowercase()) {
                "running" -> "执行中"
                "done" -> "执行完成"
                "error" -> "执行失败"
                else -> "等待执行"
            }
            val detail = card.message.takeIf { it.isNotBlank() }
                ?: "${label}${if (card.tool.isNotBlank()) " · ${card.tool}" else ""}"
            QuroAgentTrace.status(card.tool.ifBlank { "工具" }, detail)
            return
        }
        // #879 切会话防污染：区分「可见会话」与「后台生成会话」。
        // - 可见会话：直接改共享 store 并刷新 _messages（原行为）。
        // - 后台生成会话：改该会话自己的 liveBuffer，绝不碰共享 store / _messages，
        //   否则卡片会漏进当前可见会话（切会话串台的次要来源）。
        // 归属会话 = activeConversationId（send 协程启动时已锁定为本轮 convId）。
        val ownerId = activeConversationId
        val visible = (_currentId.value == ownerId)
        // ★ 修复（v416）：可见会话在【生成中】时，实时内容在 liveBuffer（buf）里，共享 store 是过时的
        //   （仅含上轮终态 + 本轮用户消息，不含正在流的助手回复/工具块）。若此处用 store 改卡并
        //   `_messages.value = store.all()`，会把屏幕回退到过时内容 → 表现为「内容消失 / 工具重负载时完全错乱」。
        //   因此：优先用 liveBuffer 作为卡片载体与显示源；无 liveBuffer（非生成中）才退回 store。
        val live = liveBuffers[ownerId]
        val storeForCard: QuroConversationStore = live ?: store
        val msgs = storeForCard.all()
        QuroDiag.log("CARD", "ownerId=$ownerId visible=$visible fromLive=${live != null}")
        // 🔧 修复（v200）：ui_widget / ui_card 在 ToolCalls 阶段执行时，本轮唯一的 assistant 占位消息是
        //   hidden=true 且带 toolCalls 的。若按「最后非隐藏 assistant」找，会命中【上一轮】可见消息，
        //   导致当前轮组件卡片串到历史气泡 → 用户看到「完全错乱」。
        //   正确目标：优先挂到本轮 hidden 占位（hidden 且含 toolCalls）；兜底再退最后非隐藏 assistant / 最后非隐藏消息。
        val target = msgs.lastOrNull { it.role == "assistant" && it.hidden && it.toolCalls?.isNotEmpty() == true }
            ?: msgs.lastOrNull { it.role == "assistant" && !it.hidden }
            ?: msgs.lastOrNull { !it.hidden }
        if (target != null) {
            storeForCard.update(target.id) { it.copy(cards = it.cards + card) }
        } else {
            storeForCard.add(QuroMessage(role = "assistant", content = "", cards = listOf(card)))
        }
        if (visible) {
            // 显示源必须与卡片载体一致：生成中用 liveBuffer（含正在流的实时内容），否则用 store。
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

    /** 项目开源地址（GitHub 仓库）。 */
    private val QURO_REPO_URL = "https://github.com/Quor-a/ZorvAI"

    /** 默认系统提示词（未激活任何人格卡时使用）。 */
    private val DEFAULT_SYSTEM =
        """你是一个由 Zorv AI 个人开发的 AI 助手（当前未启用特定人格卡，以通用身份应答），乐于助人、简洁准确。

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
- **名称**：Zorv AI 助手（通用模式；启用人格卡后你的真实名字会变成该人格卡）
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
        return opening ?: "你好，我是 Zorv AI。已就绪，可以聊天、调用工具。点左上角菜单查看历史对话，或点 ➕ 新建对话。"
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
                // 功能模型配置接入引擎：人格孵化使用 PERSONA_INCUBATE 绑定的模型（跟随主模型时等效 cfg.model）
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

        // ══════════════ #1113：本地离线小模型走「极简提示词」 ══════════════
        // 完整版 system prompt 静态部分约 16,000 字符 ≈ 11,500 token（基座 3.6k + 工具清单 5.3k
        // + 工具用法提示 6.2k + 经验库 0.5k，尚不含人格卡 / 记忆 / CMS 清单 / 对话历史）。
        // 手机端 GGUF 会话 n_ctx 上限 8192，原生层可用 prompt 仅 n_ctx - reserve ≈ 6,144 token：
        //   → prompt 从**头部**被截断，品牌/身份/人格整段丢失；
        //   → 剩余 6k token 还要在手机 CPU 上逐 chunk prefill，几十秒~数分钟不出首 token。
        // 用户观感即「一直进行中、一个字都不回」。本地模型也不走 function calling（tools 字段
        // 只下发给云端），塞 47 个工具清单纯属烧上下文，故本地一律裁掉。
        val isLocal = cfg.provider == "MNN" || cfg.provider == "LLAMA_CPP"

        // 平台/品牌自我认知基座（永远最先，不被人格卡覆盖）
        sb.append(if (isLocal) QuroPlatformManifest.SYSTEM_COMPACT else QuroPlatformManifest.SYSTEM)
            .append("\n\n")

        // ══════════════ 第一优先级：身份认知（人格卡 = AI 真实身份；Zorv AI = 开发者；运行环境靠工具自行发现） ══════════════
        // ══════════════ 灵魂层（人格/标签/语音/记忆）由自写编排引擎生成 ══════════════
        // Project B0：QuroSoulPromptEngine 负责"这张人格卡是谁、怎么说话、记得什么、用什么声音"，
        // 与平台基座 / 工具清单 / 用户技能解耦（下方由调用方拼接）。
        // 情绪/风格标签提示：统一交给 QuroVoiceStyle.hintForContext 构建（对话框与语音球共用同一份逻辑，
        // 尊重用户在「语音设置 · LLM 情绪标签」页显式选择的服务商；未显式选择时回落到播放源 / 全局服务商）。
        // 修复 v339/v343/v344 反复翻车：之前对话框与语音球各自写一套、且忽略了 getEmotionProviderId 显式选择、
        // 语音球还写死只认 SOURCE_CLOUD（选了 mimo/model 源就完全不注入情绪）。现统一函数彻底消除发散。
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
        // 平台/品牌基座（QuroPlatformManifest.SYSTEM）已声明「你是 Zorv AI」与「必须调用工具」，
        // 人格仅作为上方叠加的扮演层，此处不再重复品牌与工具原则。



        // ══════════════ 第三优先级：长期记忆（受「AI 自动保存记忆」开关控制） ══════════════
        // autoSaveMemory=false 时完全不注入记忆相关提示：AI 既不读取已有记忆，也不主动保存。
        // 长期记忆已由 QuroSoulPromptEngine 在灵魂层统一编排（受「AI 自动保存记忆」开关控制）。

        // ══════════════ 工具菜单：必须与下方实际下发的 tools 字段严格一致 ══════════════
        // 直接由「当前生效的工具集」生成，避免菜单与字段不一致导致模型选了不存在的工具。
        // 记忆开关关闭时，从工具集里摘除 memory_* 工具，确保 AI 既不提示也不调用记忆类工具。
        // 本地模型不下发 tools 字段、也无法真正执行工具 → 跳过整份能力清单（省 ~11,000 字符）。
        if (!isLocal) {
            val baseSpecs = if (cfg.useFullTools) registry.fullSpecs() else registry.coreSpecs()
            val activeSpecs = if (autoSaveMemory.value) baseSpecs else baseSpecs.filter {
                !it.name.startsWith("memory_") && !it.name.startsWith("experience_")
            }
            appendCapabilityAwareness(sb, activeSpecs)
        }

        // ══════════════ 用户技能 SKILL（已启用的自定义指令注入系统提示词） ══════════════
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

        // ══════════════ 用户身份（让用户知道如何称呼用户） ══════════════
        // v232 修复：此前系统提示词从不注入 user_name，导致 AI「不知道用户叫什么」。
        val userName = userProfile.value.name
        if (userName.isNotBlank()) {
            sb.append("\n## 关于用户\n")
            sb.append("当前用户的名字是「${userName}」。在合适的场合可以直接用这个名字称呼用户，但不要每句话都刻意叫名字。\n")
        }

        // ══════════════ AI 经验闭环（受「AI 自动保存记忆」开关控制） ══════════════
        // 关闭记忆开关时，experience_* 工具已从 activeSpecs 摘除且此处不注入，AI 既不读取也不沉淀经验。
        if (!isLocal && autoSaveMemory.value) appendExperienceAwareness(sb)

        // ══════════════ 人格自动孵化笔记本（让孵化真正闭环：AI 能读到基于近期对话提炼的演进备忘） ══════════════
        // 本地使用者孵化自己的 AI，无外部注入风险；incubation 是"笔记本"而非覆盖角色设定。
        val incubation = persona?.incubation?.takeIf { it.isNotBlank() }
        if (incubation != null) {
            // 本地小模型上下文紧张：孵化笔记只保留最近 5 条（云端仍保留 20 条）。
            val noteLines = incubation.lineSequence().filter { it.isNotBlank() }.toList()
                .takeLast(if (isLocal) 5 else 20)
            if (noteLines.any()) {
                sb.append("\n\n## 人格孵化笔记本（基于近期对话自动提炼的演进备忘，仅供参考，不覆盖上面的角色设定）\n")
                sb.append("以下是你与这位用户近期相处中沉淀的观察笔记，可自然融入你的语气与关注点，但不要机械复述：\n")
                noteLines.forEach { sb.append(it).append("\n") }
            }
        }

        // ══════════════ 串台防御（v429+） ══════════════
        // 强制约束：AI 必须且只能针对【最新一条用户消息】作答，绝不能：
        // - 继续/重复之前某轮任务的回复（如"xxx已创建完成"）
        // - 引用历史上下文中与当前用户消息无关的内容作为主要回复
        // - 把旧轮次中生成的 HTML/代码/长文本当成当前回复
        // 每条用户消息前有 [第N轮] 标记，最新一条 = 最大 N 值 → 只回应那条。
        if (isLocal) {
            // 本地：一行纪律即可，不铺陈（每多 100 字 ≈ 70 token，手机端 prefill 都是钱）。
            sb.append("\n\n## 回复纪律\n只针对**最新一条**用户消息作答，历史仅作背景，不要复述旧轮次的内容。\n")
        } else {
            sb.append("""
            
            ## ⚠️ 回复纪律（强制约束）
            - 你必须**仅针对最新一条带 [第N轮] 标记的用户消息**作答
            - 如果用户消息是「全面测试」「测试一下」等简短测试指令，就按字面意思执行测试并报告结果，**绝不能**回复之前任何任务（如创建应用、生成网页、部署模块等）的完成通知
            - 历史上下文仅作为参考背景，你的回复主体必须是**对当前这条消息的直接回应**
            - 违反此纪律会导致用户体验严重受损（串台），请务必遵守
            """.trimIndent())
        }

        val out = sb.toString().trim()
        // #1113 诊断：把 system prompt 实际规模写进日志，避免再靠猜。
        // 本地路径应稳定在 ~1,000 字符以内；若日志里看到上万，说明有别的入口绕过了 isLocal 分支。
        QuroDiag.log(
            "SysPrompt",
            "built | local=$isLocal | provider=${cfg.provider} | chars=${out.length} | ~tokens=${out.length / 3 * 2}"
        )
        return out
    }

    /**
     * 把「AI 经验笔记 & 自我进化」的行为指引 + 本轮相关经验注入系统提示词（OODA 闭环的 Feedback）。
     * 不打扰用户：纯后台沉淀，下次相关对话自动复用并修正。
     */
    private fun appendExperienceAwareness(sb: StringBuilder) {
        val engine = QuroExperienceEngine(QuroExperienceRepository(appContext))
        sb.append("\n\n## AI 经验笔记 & 自我进化（内部，不打扰用户）\n")
        sb.append("- 你拥有一个本地「经验库」，用于跨会话沉淀与复用可复用的结论（报错 / 解决方案 / 工具模式 / 版本差异），不打扰用户。\n")
        sb.append("  - experience_log：当一轮对话里你遇到、解决或可复用一个问题时，主动沉淀（type=error/solution/pattern/compatibility）。\n")
        sb.append("  - experience_query：动手前先查相关经验，复用已有结论、避免重复踩坑。\n")
        sb.append("  - experience_correct：某条经验被证明过时 / 错误时，记录自我纠错（was / reason / fix）。\n")
        sb.append("  - experience_version_check：遇到版本相关问题时自检兼容性，或列出已知兼容标记。\n")

        // Feedback 闭环：基于本轮用户消息注入 top-N 相关经验，让 AI 自动复用
        val lastUser = store.all().lastOrNull { it.role == "user" && !it.hidden }?.content ?: ""
        if (lastUser.isNotBlank()) {
            val top = engine.queryRelevant(lastUser, topN = 5, bumpReuse = true)
            if (top.isNotEmpty()) {
                sb.append("\n### 与本轮相关的已知经验（自动复用，自然融入回答，不要生硬提及「根据经验」）\n")
                top.forEach { e ->
                    sb.append("- [${e.type.key}] ")
                    if (e.title.isNotBlank()) sb.append("${e.title}：")
                    sb.append(e.content)
                    if (e.tags.isNotEmpty()) sb.append("（标签：${e.tags.joinToString(", ")}）")
                    sb.append("\n")
                }
            }
        }
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
        sb.append(com.ai.assistance.quro.core.tools.QuroToolUsageHints.buildToolUseDirective())
        sb.append("\n### 工具清单（格式：工具名：用途 [· 常见说法/多用途]）\n")
        specs.forEach { s ->
            sb.append("- ${s.name}：${s.description}\n")
            com.ai.assistance.quro.core.tools.QuroToolUsageHints.TOOL_USAGE_HINTS[s.name]?.let { hint ->
                sb.append("    · 常见说法/多用途：$hint\n")
            }
        }
        // ═══ AI 键盘通道专项指引（v436 新增）：让 LLM 知道何时走 IME 键盘通道而非无障碍 input_text ═══
        sb.append("\n### AI 键盘通道（ai_type_text / ai_press_enter / ai_press_send）\n")
        sb.append(
            "这三个工具走本应用注册的「系统键盘 IME 单例」QuroAiKeyboardService，用于向「其他 App 的聚焦输入框」像真人打字一样注入文字、回车或发送。" +
            "触发时机：当用户要你在某个 App（如微信、备忘录、WPS 搜索框、浏览器地址栏）的输入框里填字、换行、或触发发送键时，优先用它们，而不是无障碍 input_text。" +
            "前提与限制：①目标 App 的输入框必须「已聚焦」（当前有光标）；②本 AI 键盘必须已设为该输入框的「活动输入法」（首次使用会引导用户在输入法设置里启用并切换）。" +
            "若 isInputActive() 为 false（无聚焦输入框），工具会返回明确引导而非静默失败。" +
            "它与无障碍 input_text 是「两条独立通道」：需要「模拟真人逐字输入、触发 IME 的发送/回车动作」时走键盘通道；需要「直接覆盖或设置控件文本、不依赖输入法」时走无障碍通道。\n"
        )
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
        sb.append(
            "- **代码块与 HTML 可视化渲染（重要）**：对话框内置代码块渲染能力，你**应当主动使用围栏格式输出代码**，让结果以精美卡片呈现，而不是甩一大坨纯文本。" +
            "规则：\n" +
            "  ① 用三反引号围栏包裹代码，并标注语言，例如 ```kotlin … ```、```python … ```、```json … ```、```html … ```。\n" +
            "  ② 当语言是 `html` / `htm` / `markup`（或内容明显是 HTML 标签）时，对话框会自动为该代码块提供「**代码 | 预览**」双标签页：代码页可横向滚动查看源码，预览页会用 WebView 直接渲染出页面效果（含移动端 viewport 自适应缩放）。" +
            "也就是说，**你写出 ```html 围栏，用户就能直接在对话框里点「预览」看到网页长什么样**，无需复制出去打开。\n" +
            "  ③ 其它语言的代码块会以带横向滚动的等宽源码框呈现，长行不会撑破对话框。\n" +
            "  ④ 需要给用户「能跑起来的网页 / 组件 / 页面」时，**优先用 ```html 围栏输出**，并可在 HTML 里内联 `<style>` 与脚本；不要只发纯文本网址或裸 HTML 片段（那会被当成普通文字，失去预览能力）。\n" +
            "  ⑤ 若你只想展示少量行内代码，用单个反引号 `code` 即可；整段代码或网页务必用三反引号围栏。**这能力是系统自带的，每次回复都可用，无需用户提醒。**\n"
        )
        sb.append(
            "- **⑥ 预览型网页禁止用 write_file 写文件**：当你想给用户「能直接在对话框里预览效果的网页」时，**必须**用 ```html 围栏把完整源码写在回复正文里（见 ④，对话框自动提供「代码 | 预览」双标签），**严禁调用 write_file 把网页存成文件再让用户自己打开**——那样用户看不到预览，我们也无法渲染。write_file 只允许用于用户明确要求「把代码/工程保存到文件」的场景（如生成可下载的项目）。若你已用 write_file 写了网页，请同时把完整源码用 ```html 围栏再贴一份在回复里。\n"
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

        // ══════════════ CMS 引擎（系统资源包）· 一级运行引擎（区别于模块） ══════════════
        sb.append("\n### CMS 引擎（系统资源包 · 一级运行引擎）\n")
        sb.append("- **CMS 引擎**是 CMS 的一级运行引擎（区别于上方「能力模块」）：它不是某个业务模块，而是整套终端运行引擎，提供 NODE / PYTHON / SSH / JAVA / RUST / GO 等**共享运行时**，是依赖这些运行时的能力模块能运行的基础底座。\n")
        sb.append("- 引擎态与模块态相互独立：模块态用 `cms_status` 查，**引擎态用 `cms_engine_status` 查**（不要混用、不要猜）。\n")
        sb.append("- 用户可在「设置 → CMS v2 模块」页的「🔧 CMS引擎」卡进行：部署官方 CMS 引擎、导入/导出 CMS 引擎包（.cmsengine，可分享/本地留存）。引擎部署依赖终端 Linux 环境（proot/Alpine），未就绪时 cms_engine_status 会给出引导。\n")
        sb.append("- 当你要判断「某个需要 Python/Node 的模块能不能跑」「引擎是否就绪」「引擎拉起了哪些共享服务」时，调用 **cms_engine_status** 回查，而不是凭空回答。\n")

        // ══════════════ ACI（Agent Capability Interface）：AI 作为控制方调用第三方 App ══════════════
        sb.append("\n### 通过 ACI 控制的第三方 App 能力\n")
        sb.append("- ACI 性质（重要）：ACI 是【本地、无 Root、App 间 AIDL】调用框架。第三方 App 声明 exported Service + 权限 ai.aci.permission.CALL（protectionLevel=normal，安装即自动授予，不弹窗、不需提权）。\n")
        sb.append("- ACI 不使用、也不需要：Shizuku / dumpsys / OPLUS 权限 / ROOT / 无障碍 / 设备管理员。遇到任何 ACI 问题时【禁止】用这些系统工具去\"诊断\"或\"修复\"——那会偏离 ACI 的设计，且对解决问题毫无帮助。\n")
        sb.append("- aci_list：列出当前已发现的所有 ACI 第三方 App 及其暴露的能力（id / 说明 / 参数 / 是否需用户确认）。\n")
        sb.append("- aci_call：调用某个第三方 App 的 ACI 能力，参数 {target_package, capability, args}；会跨进程发往目标 App 并同步返回结果。\n")
        sb.append("- 应用启动时会自动发现设备上已安装的 ACI App；若 aci_list 为空，仅说明目标 App 未安装或未声明 ACI Service → 直接告知用户去安装该 App，【不要】跑 dumpsys/Shizuku 去查。\n")
        sb.append("- 排障边界（重要）：若 aci_call 返回 503（服务未绑定），这是绑定生命周期问题，框架会自动重绑 → 直接重试一次 aci_call 即可，【不要】去授权任何系统权限。其他错误码请原样转告用户，不要臆测为\"权限不足\"。\n")
        sb.append("- 官方参考受控端「ZorvAI 浏览器」(包名 com.ai.assistance.quro.browser) 已暴露能力：browser_open(打开网址) / browser_read(读当前页 URL+标题+完整 HTML) / browser_crawl(爬结构化正文+出站链接) / browser_search(检索) / browser_script(执行任意 JS) / browser_list(标签页) / browser_info(版本) / browser_capture(抓包) / browser_find(页内查找) / browser_nav(前进后退刷新) / browser_screenshot(截图存 Pictures/QuroAI_screenshots/) / console_ui(控制台 SDUI 快照) / console_action(控制台动作)。browser_read/browser_crawl 已修复，在 SPA 大页(如 news.sina.cn)也能稳定返回内容。\n")
        sb.append("- 已发现的第三方能力清单：\n")
        try {
            sb.append(QuroAciManager.getInstance().getCapabilityPrompt())
        } catch (e: Throwable) {
            sb.append("（ACI 尚未就绪：${e.message}）\n")
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
