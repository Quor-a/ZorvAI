package com.ai.assistance.quro.genui.aiapp.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ai.assistance.quro.genui.aiapp.brain.ZorvBrain
import com.ai.assistance.quro.genui.aiapp.data.GenUISessionStore
import com.ai.assistance.quro.genui.aiapp.renderx.ChannelPage
import com.ai.assistance.quro.genui.aiapp.core.GenUIChatMessage
import com.ai.assistance.quro.genui.aiapp.core.GenUILlmResult
import com.ai.assistance.quro.genui.aiapp.core.GenUIToolCall
import com.ai.assistance.quro.genui.aiapp.core.GenUIToolSpec
import com.ai.assistance.quro.core.model.QuroModelConfig
import com.ai.assistance.quro.genui.aiapp.net.GenUILlmClient
import com.ai.assistance.quro.genui.aiapp.agent.AgentThinkingProcessor
import com.ai.assistance.quro.genui.aiapp.tools.GenUIToolRegistry
import com.ai.assistance.quro.genui.aiapp.tools.RegisterComponentTool
import com.ai.assistance.quro.genui.sdk.dsl.StreamingParser
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.update
import kotlin.text.Regex

/**
 * 生成式 UI ViewModel
 *
 * 不是聊天 ViewModel — 不管理消息列表。
 * 管理"当前界面"状态：用户说做什么 → AI 生成全屏 UI → 替换当前界面
 *
 * 支持工具调用循环：
 * - AI 可以调用 function calling 工具（如 get_device_info、register_component）
 * - 工具执行结果会返回给 AI，让 AI 继续生成
 * - 循环直到 AI 返回最终的文本回复（包含 GenUI JSON）
 */
class ChatViewModel(app: Application) : AndroidViewModel(app) {

    private val llmClient = GenUILlmClient()
    private val thinkingProcessor = AgentThinkingProcessor()

    /**
     * 宿主对接层：模型配置 / 身份灵魂 / 完整工具集全部取自 ZorvAI 宿主，
     * aiapp 不再自带模型配置与人格注入（详见 [ZorvBrain]）。
     */
    private val brain = ZorvBrain(app.applicationContext)

    private val _state = MutableStateFlow(ChatState())
    val state: StateFlow<ChatState> = _state.asStateFlow()

    private var streamJob: Job? = null
    private val maxHistory = 20

    /**
     * 工具调用最大轮次，取自宿主模型配置。
     * 0 = 不限制（沿用宿主语义，用 100 作为上限防止死循环）
     */
    private fun getMaxToolCallRounds(): Int {
        val configured = brain.modelConfig().maxToolRounds
        return if (configured <= 0) 100 else configured
    }

    init {
        // 注册 GenUI 专属本地工具（与宿主工具集并行，执行时按名分派）
        registerDefaultTools()
        reloadConfig()
        restoreSession()
    }

    /** 系统提示词：宿主平台基座 + 宿主人格/灵魂层 + GenUI 表达规则层（见 [ZorvBrain.systemPrompt]）。 */
    private fun buildSystemContent(): String = brain.systemPrompt()

    /** 用宿主当前人格/模型重建 system 消息 */
    private fun rebuildSystemMessage() {
        _state.update { st ->
            st.copy(
                conversationHistory = listOf(
                    GenUIChatMessage(role = "system", content = buildSystemContent())
                ) + st.conversationHistory.filter { it.role != "system" }
            )
        }
    }

    /** 冷启动恢复：历史对话 + 上次界面 + 历史作品 + 宿主身份/模型标签 */
    private fun restoreSession() {
        val ctx = getApplication<Application>().applicationContext
        runCatching {
            val savedHistory = GenUISessionStore.loadHistory(ctx)
            val history = listOf(
                GenUIChatMessage(role = "system", content = buildSystemContent())
            ) + savedHistory.map { (role, content) ->
                GenUIChatMessage(role = role, content = content)
            }
            var lastGenUI = GenUISessionStore.loadLastGenUI(ctx)
            // 上次保存的是通道页（markdown/a2ui/html 围栏）→ 恢复为通道而非 GenUI
            var restoredChannel: ChannelPage? = null
            if (lastGenUI != null && lastGenUI.startsWith("```")) {
                detectChannel(lastGenUI)?.let { (page, raw) ->
                    restoredChannel = page
                    lastGenUI = null
                }
            }
            val works = GenUISessionStore.loadWorks(ctx).map {
                com.ai.assistance.quro.genui.aiapp.data.GenUISessionStore.WorkItem(it.title, it.request, it.json, it.time)
            }
            _state.update {
                it.copy(
                    hostPersonaName = brain.activePersona().name,
                    modelLabel = brain.modelConfig().model,
                    channel = restoredChannel,
                    conversationHistory = if (savedHistory.isEmpty()) it.conversationHistory else history,
                    currentGenUI = lastGenUI,
                    currentRequest = GenUISessionStore.loadLastRequest(ctx),
                    hasUI = lastGenUI != null,
                    works = works
                )
            }
        }
    }

    /** 会话落盘（在状态定型后调用） */
    private fun persistSession() {
        val ctx = getApplication<Application>().applicationContext
        runCatching {
            val st = _state.value
            GenUISessionStore.saveHistory(
                ctx,
                st.conversationHistory.filter { it.role != "system" }.map { it.role to it.content }
            )
            GenUISessionStore.saveLastPage(ctx, st.currentGenUI, st.currentRequest)
            GenUISessionStore.saveWorks(ctx, st.works)
        }
    }

    /** 历史回看：恢复某次生成的界面（GenUI 或通道页） */
    fun restoreWork(work: com.ai.assistance.quro.genui.aiapp.data.GenUISessionStore.WorkItem) {
        // 通道页（存的是原始围栏）→ 重新路由打开
        if (work.json.startsWith("```")) {
            detectChannel(work.json)?.let { (page, _) ->
                _state.update { it.copy(channel = page, hasUI = true, pageStack = emptyList(), isStreaming = false, error = null) }
                return
            }
        }
        _state.update {
            it.copy(
                pageStack = emptyList(),
                channel = null,
                currentGenUI = work.json,
                currentRequest = work.request,
                hasUI = true,
                isStreaming = false,
                error = null
            )
        }
        GenUISessionStore.saveLastPage(getApplication<Application>().applicationContext, work.json, work.request)
    }

    /**
     * 注册默认工具集
     */
    /**
     * 新架构通道路由（不动 GenUI SDK，多通道并行）：
     * ```a2ui``` → A2UI 风格扁平邻接表（JSON 或 YAML）
     * ```markdown``` / ```md``` → Markwon 原生渲染
     * ```html``` → 内联 WebView
     * 命中即全屏打开，跳过 GenUI 提取管线。
     */
    private fun FlatDocParser(body: String, isYaml: Boolean) = com.ai.assistance.quro.genui.aiapp.renderx.FlatDoc.parse(body, isYaml)

    /** 用户点名通道：a2ui/markdown/html（发送时从用户消息提取） */
    private var forcedChannel: String? = null

    private fun channelFromRequest(request: String): String? = when {
        request.contains("a2ui", ignoreCase = true) -> "a2ui"
        request.contains("markdown", ignoreCase = true) -> "markdown"
        Regex("用\\s*html|html\\s*(写|版|页)|写\\s*html", RegexOption.IGNORE_CASE).containsMatchIn(request) -> "html"
        else -> null
    }

    /** 返回 (通道页, 原始围栏文本)——原始文本用于历史回放 */
    private fun detectChannel(text: String, userRequest: String = ""): Pair<ChannelPage, String>? {
        val forced = forcedChannel ?: channelFromRequest(userRequest)
        val fences = Regex("```(a2ui|markdown|md|html)\\s*\\n?([\\s\\S]*?)```", RegexOption.IGNORE_CASE)
            .findAll(text).toList()
        if (fences.isEmpty()) return null
        // 用户点名通道 → 只考虑该通道的围栏（其他围栏一律忽略，防模型黏住旧通道）
        val considered = if (forced != null) {
            fences.filter { m ->
                val k = m.groupValues[1].lowercase()
                k == forced || (forced == "markdown" && k == "md")
            }
        } else fences
        if (considered.isEmpty()) return null
        // 完整性优先：长的在前
        for (m in considered.sortedByDescending { it.groupValues[2].length }) {
            val kind = m.groupValues[1].lowercase()
            val raw = "```" + m.groupValues[1] + "\n" + m.groupValues[2].trim() + "\n```"
            val body = m.groupValues[2].trim()
            when (kind) {
                "a2ui" -> {
                    val isYaml = !body.startsWith("{") && !body.startsWith("[")
                    FlatDocParser(body, isYaml)?.let { doc ->
                        return ChannelPage.FlatPage(channelTitle(text, 1) ?: "界面", doc) to raw
                    }
                }
                "markdown", "md" -> if (body.length > 20) {
                    return ChannelPage.MarkdownPage(channelTitle(body, 2) ?: "文档", body) to raw
                }
                "html" -> if (body.length > 40) {
                    return ChannelPage.HtmlPage(channelTitle(text, 0) ?: "网页", body) to raw
                }
            }
        }
        return null
    }

    /** 0=html<title> 1=json"title" 2=markdown#标题 */
    private fun channelTitle(text: String, kind: Int): String? = when (kind) {
        1 -> Regex("\"title\"\\s*:\\s*\"([^\"]+)\"").find(text)?.groupValues?.get(1)
        2 -> Regex("^#\\s+(.+)$", RegexOption.MULTILINE).find(text)?.groupValues?.get(1)
        else -> Regex("<title>([\\s\\S]*?)</title>", RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1)
    }?.take(24)

    fun closeChannel() {
        _state.update { it.copy(channel = null) }
    }

    /** 画布返回聊天主页（保留历史记录，仅收起当前界面） */
    fun closeCanvas() {
        _state.update { it.copy(currentGenUI = null, hasUI = false) }
    }

    /** 二级/多级界面：open_screen 动作压栈 */
    fun pushPage(spec: com.ai.assistance.quro.genui.sdk.dsl.UISpec) {
        val json = runCatching { com.ai.assistance.quro.genui.sdk.GenUI.encode(spec) }.getOrNull() ?: return
        _state.update { it.copy(pageStack = it.pageStack + json) }
    }

    /** 返回上一级界面 */
    fun popPage() {
        _state.update { st ->
            if (st.pageStack.isEmpty()) st else st.copy(pageStack = st.pageStack.dropLast(1))
        }
    }

    /**
     * 注册 GenUI 专属本地工具。
     *
     * 注意：设备信息、联网、文件、终端等通用能力一律走宿主注册表（[ZorvBrain.executeTool]），
     * 这里只保留 GenUI SDK 自己特有的能力——register_component（运行时注册自定义组件类型）。
     * 上游旧版自带的 get_device_info 已删除：宿主同名工具功能更全，避免重名与重复下发。
     */
    private fun registerDefaultTools() {
        if (!GenUIToolRegistry.isRegistered("register_component")) {
            GenUIToolRegistry.register(RegisterComponentTool())
        }
    }

    /** 读取宿主模型配置 + 人格卡，刷新只读展示与 system 消息。 */
    fun reloadConfig() {
        val cfg = brain.modelConfig()
        val isEmpty = _state.value.conversationHistory.isEmpty()

        _state.update {
            if (isEmpty) {
                it.copy(
                    hostPersonaName = brain.activePersona().name,
                    modelLabel = cfg.model,
                    conversationHistory = listOf(
                        GenUIChatMessage(role = "system", content = buildSystemContent())
                    )
                )
            } else {
                it.copy(hostPersonaName = brain.activePersona().name, modelLabel = cfg.model)
            }
        }
    }

    /** 宿主侧改了人格卡/模型后，重建 system 消息让本轮立即生效。 */
    fun refreshHostIdentity() {
        _state.update { it.copy(hostPersonaName = brain.activePersona().name, modelLabel = brain.modelConfig().model) }
        rebuildSystemMessage()
    }

    /**
     * 发送请求 — 生成新 UI
     *
     * 实现完整的工具调用循环：
     * 1. 调用 LLM（携带 tools）
     * 2. 如果返回工具调用，则执行工具并将结果加入历史
     * 3. 再次调用 LLM（携带工具结果）
     * 4. 循环直到 AI 返回文本回复或达到最大轮次
     */
    fun send(text: String) {
        if (text.isBlank() || _state.value.isStreaming) return

        val config = brain.modelConfig()
        val personaName = brain.activePersona().name
        val personaChanged = personaName != _state.value.hostPersonaName
        _state.update { it.copy(modelLabel = config.model, hostPersonaName = personaName) }
        // 宿主侧可能刚换了人格卡 → 本轮先把 system 消息对齐到当前身份，保证两个入口是同一个人
        if (personaChanged) rebuildSystemMessage()

        // 校验：模型配置全部取自 ZorvAI 主设置
        when {
            brain.isLocalProvider(config) -> {
                _state.update {
                    it.copy(error = "当前选的是本地离线模型（${config.provider}），GenUI 助手需要云端模型。请到 ZorvAI 主设置切换模型后重试。")
                }
                return
            }
            config.apiKey.isBlank() -> {
                _state.update { it.copy(error = "ZorvAI 尚未配置 API Key，请到主设置 → 模型配置 填写后重试") }
                return
            }
            config.baseUrl.isBlank() -> {
                _state.update { it.copy(error = "ZorvAI 尚未配置 Base URL，请到主设置 → 模型配置 填写后重试") }
                return
            }
            config.model.isBlank() -> {
                _state.update { it.copy(error = "ZorvAI 尚未选择模型，请到主设置 → 模型配置 选择后重试") }
                return
            }
        }

        // 用户点名通道（a2ui/markdown/html）→ 本轮锁定
        forcedChannel = channelFromRequest(text)

        val newHistory = _state.value.conversationHistory + GenUIChatMessage(
            role = "user",
            content = text
        )

        _state.update {
            it.copy(
                currentRequest = text,
                isStreaming = true,
                streamingText = "",
                streamingReasoning = "",
                error = null,
                hasUI = false,
                toolCallRecords = emptyList(),
                conversationHistory = newHistory,
                pageStack = emptyList()
            )
        }

        streamJob = viewModelScope.launch {
            // 初始化思考链
            thinkingProcessor.startChain(text)
            thinkingProcessor.onChainUpdate = { chain ->
                _state.update { it.copy(thoughtChain = chain) }
            }
            _state.update { it.copy(thoughtChain = thinkingProcessor.getCurrentChain()) }

            // 执行工具调用循环
            runToolCallingLoop(config)

            // 完成思考链
            thinkingProcessor.completeChain()
            _state.update { it.copy(thoughtChain = thinkingProcessor.getCurrentChain()) }
        }
    }

    /**
     * 画布渲染规则（画图轮专用系统提示词，注入在对话历史之后）。
     * 目的：从源头约束 AI 输出结构，替代事后清洗。
     */
    private fun renderRulesMessage(forced: String?): GenUIChatMessage = GenUIChatMessage(
        role = "system",
        content = (if (forced != null)
            "# 本轮通道已由用户锁定（最高优先级）\n" +
            "用户明确指定本轮必须使用三反引号" + forced + "围栏输出。\n" +
            "绝对禁止输出任何其他围栏（markdown/a2ui/html/genui 都不行）；\n" +
            "不要输出 intent/plan/generate 结构；围栏外不得有任何文字。\n"
        else
            "# 输出通道选择（第一优先级）\n" +
            "- GenUI SDK 通道是主力默认：生成式界面/交互应用一律走 GenUI 流程\n" +
            "- 用户点名要某通道（用 markdown/a2ui/html 写）→ 必须按用户指定的通道输出\n" +
            "- 用户未点名时，按内容类型自选：纯文章/攻略/新闻/长文 → markdown 围栏；" +
            "独立网页/复杂样式/可玩小游戏 → html 围栏；简单结构化展示/省 token → a2ui 围栏\n" +
            "选择非 GenUI 通道时：直接输出对应围栏，围栏外不得输出任何文字，不要输出 intent/plan/generate。\n"
        ) + "\n"
        + "# GenUI 流程输出格式（生成式界面时适用）\n"
        + "1. content 通道结构固定：<intent>简短思考</intent> → <plan>规划</plan> → <generate>```genui\n{完整 JSON}\n```</generate>"
    )

    /**
     * 工具调用循环
     *
     * 多轮调用 LLM，处理工具调用，直到返回文本结果或达到最大轮次。
     */
    private suspend fun runToolCallingLoop(config: QuroModelConfig) {
        // 工具集 = 宿主完整工具集（ZorvAI ~全部内置工具）+ aiapp 本地 GenUI 专属工具（register_component）
        val tools = brain.toolSpecs(config).map {
            GenUIToolSpec(name = it.name, description = it.description, parametersJson = it.parametersJson)
        } + GenUIToolRegistry.getAllSpecs()
        val maxRounds = getMaxToolCallRounds()
        var round = 0
        // 断流续写：genui 输出被截断时自动请求续写，而不是直接落入兜底卡片
        var continuationCount = 0
        var pendingText = ""

        while (round < maxRounds) {
            round++
            val currentState = _state.value

            val result = llmClient.chat(
                baseUrl = config.baseUrl,
                apiKey = config.apiKey,
                model = config.model,
                messages = currentState.conversationHistory.takeLast(maxHistory) + renderRulesMessage(forcedChannel),
                temperature = config.temperature,
                maxTokens = config.maxTokens,
                tools = tools.ifEmpty { null },
                stream = true,
                onToken = { token ->
                    // 交给思考处理器解析
                    thinkingProcessor.processChunk(token)

                    _state.update {
                        val newText = it.streamingText + token
                        // 尝试流式提取 GenUI JSON（流式模式，禁用文本包装）
                        val newGenUI = extractGenUIDsl(newText, isStreaming = true)
                        // 只进不退：新的提取失败但之前有有效 UI 时，保留旧的
                        val finalGenUI = newGenUI ?: it.currentGenUI
                        it.copy(
                            streamingText = newText,
                            currentGenUI = finalGenUI,
                            hasUI = finalGenUI != null
                        )
                    }
                },
                onThinking = { reasoning ->
                    _state.update {
                        it.copy(streamingReasoning = reasoning)
                    }
                }
            )

            when (result) {
                is GenUILlmResult.Text -> {
                    // 断流续写：genui 代码块未闭合说明输出被中途截断
                    val content = result.content.ifBlank { _state.value.streamingText }
                    if (content.isNotBlank() && hasUnclosedGenuiFence(content) &&
                        continuationCount < MAX_GENUI_CONTINUATIONS
                    ) {
                        continuationCount++
                        pendingText = if (pendingText.isBlank()) content
                                      else pendingText + "\n" + content
                        _state.update {
                            it.copy(
                                conversationHistory = it.conversationHistory +
                                    GenUIChatMessage(role = "assistant", content = content) +
                                    GenUIChatMessage(role = "user", content = GENUI_CONTINUE_PROMPT),
                                streamingText = "",
                                streamingReasoning = ""
                            )
                        }
                        continue
                    }

                    // 最终文本结果 — 处理并结束循环（拼接续写片段后再提取）
                    val combined = if (pendingText.isBlank()) result.content
                                   else pendingText + "\n" + result.content
                    handleTextResult(GenUILlmResult.Text(combined, result.reasoning))
                    return
                }

                is GenUILlmResult.ToolCalls -> {
                    // 工具调用 — 执行工具并继续循环
                    val shouldContinue = handleToolCallsResult(result)
                    if (!shouldContinue) {
                        return
                    }
                    // 重置流式状态，准备下一轮
                    _state.update {
                        it.copy(
                            streamingText = "",
                            streamingReasoning = ""
                        )
                    }
                }

                is GenUILlmResult.Error -> {
                    // 错误 — 结束循环
                    handleErrorResult(result)
                    return
                }
            }
        }

        // 达到最大轮次仍未返回文本
        _state.update {
            it.copy(
                isStreaming = false,
                streamingText = "",
                streamingReasoning = "",
                error = "工具调用超过最大轮次（${getMaxToolCallRounds()}轮），已停止"
            )
        }
    }

    private companion object {
        /** genui 断流续写的最大次数 */
        const val MAX_GENUI_CONTINUATIONS = 2

        /** 续写提示词 */
        const val GENUI_CONTINUE_PROMPT =
            "你的上一条输出在 genui 代码块中途被截断了。现在请【跳过所有思考过程】，" +
            "禁止输出 <intent>、<plan>、<think> 等任何标签和解释文字，" +
            "第一行就直接输出 ```genui 代码块，内容是一个【完整且精简】的 JSON：" +
            "从 {\"id\" 开始到收尾括号完整闭合，控制在 600 字以内，" +
            "删掉可有可无的装饰性组件，但必须保留核心功能内容和完整闭合的结构。"
    }

    /** 判断文本中的 genui 代码块是否未闭合（输出被截断） */
    private fun hasUnclosedGenuiFence(text: String): Boolean {
        val start = text.lastIndexOf("```genui")
        if (start < 0) return false
        if (text.indexOf("```", start + 8) >= 0) return false
        // 围栏虽未闭合，但若文本中已存在完整可解析的 JSON（模型重新输出了完整版），无需再续写
        val largest = extractLargestJson(text)
        if (largest != null && StreamingParser.tryParsePartial(largest) != null) return false
        return true
    }

    /**
     * 处理文本结果（最终回复）
     *
     * 核心原则：只进不退（Monotonic Rendering）
     * - 如果流式过程中已经成功渲染过 UI，最终结果绝不回退到"生成失败"
     * - 标准提取失败时，依次尝试多种 fallback 策略
     */
    private fun handleTextResult(result: GenUILlmResult.Text) {
        val fullText = result.content.ifBlank {
            // 如果 AI 返回空文本，尝试用流式文本
            _state.value.streamingText
        }
        val currentState = _state.value

        // ── 新架构通道优先（Markdown / A2UI 扁平表 / HTML）──
        detectChannel(fullText, currentState.currentRequest)?.let { (page, raw) ->
            _state.update {
                it.copy(
                    channel = page,
                    isStreaming = false,
                    streamingText = "",
                    error = null,
                    works = (listOf(com.ai.assistance.quro.genui.aiapp.data.GenUISessionStore.WorkItem(
                        title = "[通道] " + page.title.ifBlank { "内容页" },
                        request = currentState.currentRequest.ifBlank { page.title },
                        json = raw, // 存原始围栏，回放时重新路由
                        time = System.currentTimeMillis()
                    )) + it.works).distinctBy { w -> w.json }.take(20)
                )
            }
            persistSession()
            return
        }

        // 策略 1：标准提取（strict：流式已结束，截断 JSON 绝不能进画布）
        var genuiJson = extractGenUIDsl(fullText, strict = true)

        // 策略 2：标准提取失败，但流式过程中有成功的 UI → 保留流式 UI
        // （只进不退：绝不从已渲染成功回退到失败）
        if (genuiJson == null && currentState.currentGenUI != null) {
            val lastStreaming = currentState.currentGenUI
            // strict：残缺的流式残留同样不能作为最终结果
            val staleOk = validForCanvas(lastStreaming, true)
            if (staleOk) {
                genuiJson = lastStreaming
            }
        }

        // 策略 3：从完整文本中直接找最大 JSON，用流式补全解析
        if (genuiJson == null) {
            val cleaned = stripThinkingTags(fullText)
            val largest = extractLargestJson(cleaned)
            if (largest != null && StreamingParser.tryParsePartial(largest) != null) {
                genuiJson = largest
            }
        }

        // 策略 4：直接拿流式文本的最后状态尝试
        if (genuiJson == null && currentState.streamingText.isNotEmpty()) {
            val streamingJson = extractGenUIDsl(currentState.streamingText, strict = true)
            if (streamingJson != null) {
                genuiJson = streamingJson
            }
        }

        // ── 画布诊断：记录提取链路的判定过程 ──
        val debugInfo = buildString {
            append("【画布诊断】")
            append("fullText=").append(fullText.length).append("字符")
            append(" | 含genui围栏=").append(fullText.contains("```genui"))
            append(" | 含root键=").append(containsRootKey(fullText))
            append(" | root值已开始=").append(hasRootValueStarted(fullText))
            val cleanedLen = runCatching { stripThinkingTags(fullText).length }.getOrDefault(-1)
            append(" | 清洗后=").append(cleanedLen).append("字符")
            val resolved = genuiJson
            if (resolved != null) {
                val rootType = runCatching {
                    org.json.JSONObject(resolved).optJSONObject("root")?.optString("type")
                }.getOrNull()
                append(" || 画布收到: ").append(resolved.length).append("字符, root.type=").append(rootType ?: "无")
            } else {
                append(" || 画布收到: 兜底卡片(提取全部失败)")
                // 深挖：取宽松候选拿到 strict 解析的具体异常
                val cand = runCatching { extractFromGenuiFences(fullText, strict = false) }.getOrNull()
                val err = if (cand != null) {
                    runCatching { com.ai.assistance.quro.genui.sdk.dsl.DslParser.parse(prepareForCanvas(cand)) }
                        .exceptionOrNull()?.message?.take(160) ?: "宽松候选可解析但strict失败"
                } else "无完整候选"
                append(" | 失败原因: ").append(err)
            }
        }

        // 策略 5：所有提取都失败 — 生成默认 UI（绝不显示"生成失败"）
        if (genuiJson == null) {
            // 最后尝试：用 fullText 生成一个简单的文本卡片
            // 展示前剥离思考标签与代码块围栏，避免原始标签泄露到画布
            val cleanedDisplay = stripThinkingTags(fullText)
                .replace("```genui", "")
                .replace("```json", "")
                .replace("```", "")
                .trim()
            val displayText = cleanedDisplay.ifBlank { "（AI 未返回有效 UI，请重试）" }
            val escaped = displayText.take(500)
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "")
                .replace("\t", "\\t")
            genuiJson = """{"id":"fallback","title":"AI 回复","root":{"type":"card","style":{"padding":{"all":16},"cornerRadius":12,"elevation":2},"children":[{"type":"text","properties":{"text":"$escaped"},"style":{"textSize":14}}]}}"""
        }

        // 画布实际渲染前完整预处理（归一化+动作纠正），保证 safeParse 不降级为错误卡
        if (genuiJson != null) {
            genuiJson = prepareForCanvas(genuiJson)
        }
        // 历史作品入集（标题优先取解析出的 spec.title）
        if (genuiJson != null) {
            val specTitle = runCatching {
                org.json.JSONObject(genuiJson).optString("title")
            }.getOrNull()?.takeIf { it.isNotBlank() }
            val newWork = com.ai.assistance.quro.genui.aiapp.data.GenUISessionStore.WorkItem(
                title = specTitle ?: currentState.currentRequest.ifBlank { "未命名作品" },
                request = currentState.currentRequest,
                json = genuiJson,
                time = System.currentTimeMillis()
            )
            _state.update { it.copy(works = (listOf(newWork) + it.works).distinctBy { w -> w.json }.take(20)) }
        }
        _state.update {
            it.copy(
                currentGenUI = genuiJson,
                hasUI = true,
                isStreaming = false,
                streamingText = "",
                streamingReasoning = "",
                error = null,
                debugInfo = debugInfo,
                conversationHistory = it.conversationHistory + GenUIChatMessage(
                    role = "assistant",
                    content = fullText,
                    reasoning = result.reasoning
                )
            )
        }
        persistSession()
    }

    /**
     * 处理工具调用结果
     *
     * 1. 将 assistant 消息（含 tool_calls）加入历史
     * 2. 逐个执行工具
     * 3. 将每个工具的结果作为 role=tool 的消息加入历史
     *
     * @return 是否继续循环（true=继续，false=停止）
     */
    private suspend fun handleToolCallsResult(result: GenUILlmResult.ToolCalls): Boolean {
        val content = result.content ?: ""

        // 1. 将 assistant 消息加入历史（包含 tool_calls）
        _state.update {
            it.copy(
                conversationHistory = it.conversationHistory + GenUIChatMessage(
                    role = "assistant",
                    content = content,
                    toolCalls = result.calls,
                    reasoning = result.reasoning
                )
            )
        }

        // 2. 逐个执行工具并添加结果消息
        for (toolCall in result.calls) {
            val toolResult = executeSingleTool(toolCall)

            // 判断是否成功（尝试从 JSON 结果中解析 success 字段）
            val isSuccess = try {
                val json = org.json.JSONObject(toolResult)
                if (json.has("success")) json.getBoolean("success") else true
            } catch (_: Exception) {
                true // 非 JSON 结果默认视为成功
            }

            // 记录工具调用到思考面板
            _state.update {
                it.copy(
                    toolCallRecords = it.toolCallRecords + ToolCallRecord(
                        name = toolCall.name,
                        arguments = toolCall.arguments,
                        result = toolResult,
                        isSuccess = isSuccess
                    ),
                    conversationHistory = it.conversationHistory + GenUIChatMessage(
                        role = "tool",
                        content = toolResult,
                        toolCallId = toolCall.id,
                        toolName = toolCall.name
                    )
                )
            }
        }

        return true // 继续循环，让 AI 根据工具结果继续生成
    }

    /**
     * 执行单个工具调用
     *
     * 分派规则：aiapp 本地注册的 GenUI 专属工具（register_component）就地执行，
     * 其余一律交给宿主 [ZorvBrain.executeTool]（宿主注册表 + 权限前置申请 + 超时保护）。
     *
     * @param toolCall 工具调用
     * @return 工具执行结果（JSON 字符串）
     */
    private suspend fun executeSingleTool(toolCall: GenUIToolCall): String {
        return try {
            if (GenUIToolRegistry.isRegistered(toolCall.name)) {
                GenUIToolRegistry.executeTool(toolCall.name, toolCall.arguments)
            } else {
                brain.executeTool(toolCall.name, toolCall.arguments)
            }
        } catch (e: Exception) {
            // 工具执行失败，返回错误信息
            org.json.JSONObject().apply {
                put("success", false)
                put("error", "工具执行失败: ${e.message}")
                put("tool", toolCall.name)
            }.toString()
        }
    }

    /**
     * 处理错误结果
     */
    private fun handleErrorResult(result: GenUILlmResult.Error) {
        _state.update {
            it.copy(
                isStreaming = false,
                streamingText = "",
                streamingReasoning = "",
                error = result.message
            )
        }
    }

    fun stop() {
        streamJob?.cancel()
        streamJob = null
        _state.update {
            it.copy(
                isStreaming = false,
                streamingText = ""
            )
        }
    }

    fun clear() {
        streamJob?.cancel()
        streamJob = null
        _state.update {
            it.copy(
                currentGenUI = null,
                currentRequest = "",
                hasUI = false,
                isStreaming = false,
                streamingText = "",
                streamingReasoning = "",
                error = null,
                thoughtChain = null,
                toolCallRecords = emptyList(),
                pageStack = emptyList(),
                debugInfo = null,
                conversationHistory = listOf(
                    GenUIChatMessage(
                        role = "system",
                        content = buildSystemContent()
                    )
                )
            )
        }
        runCatching {
            // 清除只重置当前画布，历史库（对话/界面记录）必须保留
            val ctx = getApplication<Application>().applicationContext
            GenUISessionStore.saveLastPage(ctx, "", "")
        }
    }

    // ==================== GenUI DSL 提取 ====================

    /**
     * 检测文本中是否包含思考/分析/内部内容特征
     *
     * 用于流式铁闸：只要命中任一特征，就视为思考内容，不进入画布。
     * 这是比标签剥离更前置、更激进的防护。
     */
    private fun containsThinkingContent(text: String): Boolean {
        val lower = text.lowercase()

        // 特征 1：XML 风格思考标签（开标签即可，不管是否闭合）
        val thinkingTagPatterns = listOf(
            "<thinking", "<reasoning", "<thought", "<intent", "<intint", "<intention",
            "<retrieve", "<retrieval", "<search",
            "<plan", "<planning",
            "<tool_call", "<toolcall", "<tool", "<tool_result", "<toolResult",
            "<decision", "<decide",
            "<self_correct", "<self-correct", "<selfcorrect", "<selfcorrection",
            "<journal", "<log",
            "<generate", "<generation", "<gen",
            "<analysis", "<analyze", "<analyse",
            "<reflection", "<reflect",
            "<observation", "<observe",
            "<action", "<step",
            "<context", "<memory",
            "<clarify", "<question",
            "<draft", "<outline",
            "<verify", "<validation", "<validate", "<check",
            "<debug", "<trace", "<inspect",
            "<component_check", "<type_check", "<schema_check",
            "<thinking>", "<reasoning>", "<thought>", "<intent>", "<intint>",
            "<self_correct>", "<analysis>", "<plan>", "<generate>"
        )
        if (thinkingTagPatterns.any { lower.contains(it) }) return true

        // 特征 1.5：闭合标签也检测（AI 可能把开标签放在 reasoning 部分）
        // 用 "<" + "/tag>" 拼接，避免编辑工具误解析
        val slash = "/"
        val closingTagPatterns = listOf(
            "<${slash}thinking>", "<${slash}reasoning>", "<${slash}thought>",
            "<${slash}intent>", "<${slash}intint>", "<${slash}intention>",
            "<${slash}retrieve>", "<${slash}retrieval>", "<${slash}search>",
            "<${slash}plan>", "<${slash}planning>",
            "<${slash}tool_call>", "<${slash}toolcall>", "<${slash}tool>",
            "<${slash}tool_result>", "<${slash}toolResult>",
            "<${slash}decision>", "<${slash}decide>",
            "<${slash}self_correct>", "<${slash}self_correction>",
            "<${slash}selfcorrect>", "<${slash}self-correct>",
            "<${slash}journal>", "<${slash}log>",
            "<${slash}generate>", "<${slash}generation>", "<${slash}gen>",
            "<${slash}analysis>", "<${slash}analyze>", "<${slash}analyse>",
            "<${slash}reflection>", "<${slash}reflect>",
            "<${slash}observation>", "<${slash}observe>",
            "<${slash}action>", "<${slash}step>",
            "<${slash}context>", "<${slash}memory>",
            "<${slash}clarify>", "<${slash}question>",
            "<${slash}draft>", "<${slash}outline>",
            "<${slash}verify>", "<${slash}validation>", "<${slash}validate>",
            "<${slash}check>",
            "<${slash}debug>", "<${slash}trace>", "<${slash}inspect>",
            "<${slash}component_check>", "<${slash}type_check>", "<${slash}schema_check>"
        )
        if (closingTagPatterns.any { lower.contains(it) }) return true

        // 特征 2：典型的思考/分析性短语（中文）
        // 只保留明确只出现在思考过程中的短语，不包含可能在正常引导文本中出现的词
        val thinkingPhrases = listOf(
            "逐一检查", "检查所有", "逐个检查", "依次检查", "组件合规",
            "组件检查", "类型检查", "格式检查",
            "让我想想", "我来分析",
            "思考过程", "分析过程",
            "核心需求不是", "视觉上要有", "语气要",
            "self correct", "self-correct", "self_correct"
        )
        if (thinkingPhrases.any { lower.contains(it.lowercase()) }) return true

        // 特征 3：勾选清单模式（大量 ✓ ✅ 等符号）
        val checkmarkCount = listOf("✓", "✅", "☑", "✔", "[x]", "[✓]").sumOf { pattern ->
            Regex.fromLiteral(pattern).findAll(text).count()
        }
        if (checkmarkCount >= 3) return true

        // 特征 4：大量编号列表（≥5条）+ 短文本，像检查清单
        val numberedLinePattern = Regex("^\\s*\\d+\\.\\s+.+", RegexOption.MULTILINE)
        val numberedLines = numberedLinePattern.findAll(text).count()
        if (numberedLines >= 5) return true

        // 特征 5：Markdown 粗体标题 + "检查"、"验证" 等关键词
        val boldCheckRegex = Regex("\\*\\*(组件|类型|格式|合规|检查|验证|校验)")
        if (boldCheckRegex.containsMatchIn(text)) return true

        // 特征 6："10 个领域"、"300+ 组件" 等 self_correct 标志性文案
        if (lower.contains("个领域") && lower.contains("组件类型")) return true
        if (lower.contains("全部") && lower.contains("种组件均在")) return true

        return false
    }

    /**
     * 检测文本中是否存在未闭合的思考标签（流式场景专用）
     *
     * @return 未闭合标签的起始位置，如果没有未闭合标签返回 null
     */
    private fun findUnclosedThinkingTag(text: String): Int? {
        val thinkingTags = listOf(
            "thinking", "reasoning", "thought", "intent", "intention", "intint",
            "retrieve", "retrieval", "search",
            "plan", "planning",
            "tool_call", "toolcall", "tool", "tool_result", "toolResult",
            "decision", "decide",
            "self_correct", "self_correction", "selfcorrect", "self-correct",
            "journal", "log",
            "analysis", "analyze", "analyse",
            "reflection", "reflect",
            "observation", "observe",
            "action", "step",
            "context", "memory",
            "clarify", "question",
            "draft", "outline",
            "verify", "validation", "validate", "check",
            "debug", "trace", "inspect",
            "component_check", "type_check", "schema_check"
        )

        var earliestOpen: Int? = null

        for (tag in thinkingTags) {
            // 找所有开标签 <tag> 或 <tag ...>
            val openRegex = Regex("<$tag(?:\\s[^>]*)?>", RegexOption.IGNORE_CASE)
            val closeRegex = Regex("</$tag\\s*>", RegexOption.IGNORE_CASE)

            val openMatches = openRegex.findAll(text).map { it.range.first }.toList()
            val closeMatches = closeRegex.findAll(text).map { it.range.first }.toList()

            // 如果开标签数量 > 闭标签数量，说明有未闭合的
            if (openMatches.size > closeMatches.size) {
                // 找到最后一个未闭合的开标签位置
                val lastOpen = openMatches.last()
                // 确认这个开标签之后没有对应的闭标签
                val hasCloseAfter = closeMatches.any { it > lastOpen }
                if (!hasCloseAfter) {
                    if (earliestOpen == null || lastOpen < earliestOpen) {
                        earliestOpen = lastOpen
                    }
                }
            }
        }

        return earliestOpen
    }

    /**
     * 从 AI 返回的文本中提取 GenUI DSL JSON
     *
     * 支持流式场景：即使 JSON 不完整，也能尽可能提取已生成的部分。
     * 使用 StreamingParser 验证提取的 JSON 是否可被部分解析。
     *
     * 核心原则：画布纯净 — 思考/工具/内部内容绝不进入画布
     *
     * 优先级：
     * 1. ```genui 完整代码块（最高优先级，直接使用）
     * 2. ```json 完整代码块
     * 3. 最大的完整 JSON 对象
     * 4. 流式不完整 genui/json 代码块
     * 5. 流式不完整纯文本 JSON
     * 6. 纯文本包装（仅非流式 + 确认是自然语言回复时）
     */
    /**
     * JSON 是否可作为最终结果放进画布
     * strict=true：必须通过完整严格解析（流式已结束，截断 JSON 绝不能进画布）
     * strict=false：允许流式部分解析（画布会以流式降级 UI 呈现）
     */
    /** SDK 支持的全部动作判别名（多态反序列化白名单） */
    private val knownActionNames = setOf(
        "navigate", "update_state", "toggle_state", "show_dialog", "dismiss_dialog",
        "haptic", "emit", "call_api", "open_url", "copy_to_clipboard", "custom", "log",
        "toast", "snackbar", "vibrate", "register_component", "set_state", "scroll_to",
        "share", "go_back", "refresh", "load_more", "open_app", "play_media", "stop_media",
        "open_screen", "open_html", "execute", "send_message"
    )

    /** 宽松 Json：用于预处理与完整解析 */
    private val lenientJson = kotlinx.serialization.json.Json {
        ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true
    }

    /**
     * 动作判别字段纠正：SDK 的 GenUIAction 多态判别字段是 "action"，
     * 但模型常按 {"type":"open_url",...} 输出（提示词早期版本也教错了）。
     * 递归把 actions 数组内元素的 "type" 重命名为 "action"，否则整页 JSON 解析失败。
     */
    private fun sanitizeActions(element: kotlinx.serialization.json.JsonElement): kotlinx.serialization.json.JsonElement =
        when (element) {
            is kotlinx.serialization.json.JsonObject -> {
                val fixed = element.mapValues { (_, v) -> sanitizeActions(v) }.toMutableMap()
                val actionsEl = fixed["actions"]
                if (actionsEl is kotlinx.serialization.json.JsonArray) {
                    fixed["actions"] = kotlinx.serialization.json.JsonArray(actionsEl.mapNotNull { item ->
                        if (item is kotlinx.serialization.json.JsonObject) {
                            val m = item.toMutableMap()
                            if (m.containsKey("type") && !m.containsKey("action")) {
                                val typeVal = m.remove("type")
                                if (typeVal != null) m["action"] = typeVal
                            }
                            // 未知动作判别名会让多态反序列化炸掉整页 → 直接丢弃该动作
                            val act = (m["action"] as? kotlinx.serialization.json.JsonPrimitive)?.content
                            if (act != null && act !in knownActionNames) null
                            else kotlinx.serialization.json.JsonObject(m)
                        } else null
                    })
                }
                kotlinx.serialization.json.JsonObject(fixed)
            }
            is kotlinx.serialization.json.JsonArray ->
                kotlinx.serialization.json.JsonArray(element.map { sanitizeActions(it) })
            else -> element
        }

    private fun sanitizeActionsJson(json: String): String = runCatching {
        val el = lenientJson.parseToJsonElement(json)
        lenientJson.encodeToString(kotlinx.serialization.json.JsonElement.serializer(), sanitizeActions(el))
    }.getOrDefault(json)

    /**
     * GenUI JSON 归一化器：模型简写/别名 → SDK 标准形。
     * ignoreUnknownKeys 只忽略未知键；类型写错（如 padding:16、animation:"fade"）仍会抛异常，
     * 这里在解析前统一纠正。
     */
    private fun normalizeGenUIJson(jsonStr: String): String = runCatching {
        val el = lenientJson.parseToJsonElement(jsonStr)
        val fixed = normalizeEl(el)
        lenientJson.encodeToString(kotlinx.serialization.json.JsonElement.serializer(), fixed)
    }.getOrDefault(jsonStr)

    private fun normalizeEl(el: kotlinx.serialization.json.JsonElement): kotlinx.serialization.json.JsonElement {
        return when (el) {
            is kotlinx.serialization.json.JsonObject -> {
                // choice_grid（对话式游戏/选项架构）→ 展开为逐项 send_message 按钮网格
                if (el["type"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content } == "choice_grid") {
                    val options = (el["options"] as? kotlinx.serialization.json.JsonArray)
                        ?.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content } ?: emptyList()
                    val prefix = (el["prefix"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: ""
                    val cols = 2
                    val mkBtn: (String) -> kotlinx.serialization.json.JsonObject = { opt ->
                        kotlinx.serialization.json.JsonObject(mapOf(
                            "type" to kotlinx.serialization.json.JsonPrimitive("button"),
                            "properties" to kotlinx.serialization.json.JsonObject(mapOf("text" to kotlinx.serialization.json.JsonPrimitive(opt))),
                            "style" to kotlinx.serialization.json.JsonObject(mapOf("textSize" to kotlinx.serialization.json.JsonPrimitive(13))),
                            "events" to kotlinx.serialization.json.JsonObject(mapOf(
                                "onClick" to kotlinx.serialization.json.JsonObject(mapOf(
                                    "actions" to kotlinx.serialization.json.JsonArray(listOf(
                                        kotlinx.serialization.json.JsonObject(mapOf(
                                            "action" to kotlinx.serialization.json.JsonPrimitive("send_message"),
                                            "text" to kotlinx.serialization.json.JsonPrimitive(prefix + opt)
                                        ))
                                    ))
                                ))
                            ))
                        ))
                    }
                    val rows = options.chunked(cols).map { rowOpts ->
                        kotlinx.serialization.json.JsonObject(mapOf(
                            "type" to kotlinx.serialization.json.JsonPrimitive("row"),
                            "style" to kotlinx.serialization.json.JsonObject(mapOf()),
                            "children" to kotlinx.serialization.json.JsonArray(rowOpts.map(mkBtn))
                        ))
                    }
                    return kotlinx.serialization.json.JsonObject(mapOf(
                        "type" to kotlinx.serialization.json.JsonPrimitive("column"),
                        "style" to kotlinx.serialization.json.JsonObject(mapOf()),
                        "children" to kotlinx.serialization.json.JsonArray(rows)
                    ))
                }
                val m = el.toMutableMap()
                (m["style"] as? kotlinx.serialization.json.JsonObject)?.let { st ->
                    val s = st.toMutableMap()
                    listOf("fontSize").forEach { k -> s[k]?.let { v -> if (!s.containsKey("textSize")) s["textSize"] = v; s.remove(k) } }
                    listOf("radius", "borderRadius").forEach { k -> s[k]?.let { v -> if (!s.containsKey("cornerRadius")) s["cornerRadius"] = v; s.remove(k) } }
                    listOf("bg", "background").forEach { k -> s[k]?.let { v -> if (!s.containsKey("backgroundColor")) s["backgroundColor"] = v; s.remove(k) } }
                    listOf("textSize", "cornerRadius", "elevation", "borderWidth", "opacity", "letterSpacing", "lineHeight").forEach { k ->
                        (s[k] as? kotlinx.serialization.json.JsonPrimitive)?.let { v ->
                            if (v.isString) v.content.toFloatOrNull()?.let { f -> s[k] = kotlinx.serialization.json.JsonPrimitive(f) }
                        }
                    }
                    listOf("clip").forEach { k ->
                        (s[k] as? kotlinx.serialization.json.JsonPrimitive)?.let { v ->
                            if (v.isString) v.content.toBooleanStrictOrNull()?.let { b -> s[k] = kotlinx.serialization.json.JsonPrimitive(b) }
                        }
                    }
                    listOf("padding", "margin").forEach { k ->
                        s[k]?.let { v ->
                            if (v !is kotlinx.serialization.json.JsonObject) {
                                val n = (v as? kotlinx.serialization.json.JsonPrimitive)?.content?.toFloatOrNull() ?: 0f
                                s[k] = kotlinx.serialization.json.JsonObject(mapOf(
                                    "start" to kotlinx.serialization.json.JsonPrimitive(n),
                                    "top" to kotlinx.serialization.json.JsonPrimitive(n),
                                    "end" to kotlinx.serialization.json.JsonPrimitive(n),
                                    "bottom" to kotlinx.serialization.json.JsonPrimitive(n)
                                ))
                            }
                        }
                    }
                    listOf("backgroundColor", "textColor", "borderColor", "color").forEach { ck ->
                        (s[ck] as? kotlinx.serialization.json.JsonPrimitive)?.let { cv ->
                            val hex = cv.content.removePrefix("#")
                            when {
                                // 9 位非法（模型手滑）→ 截取前 8 位（#AARRGGBB）
                                hex.length == 9 -> s[ck] = kotlinx.serialization.json.JsonPrimitive("#" + hex.substring(0, 8))
                                // 文字颜色透明度过低（<38%）→ 提升为不透明，保证可读
                                hex.length == 8 && ck == "textColor" &&
                                    (hex.substring(0, 2).toIntOrNull(16) ?: 0xFF) < 0x60 ->
                                    s[ck] = kotlinx.serialization.json.JsonPrimitive("#FF" + hex.substring(2))
                            }
                            // 6 位与 8 位（#AARRGGBB）均为合法格式，原样保留
                        }
                    }
                    m["style"] = kotlinx.serialization.json.JsonObject(s)
                }
                (m["animation"] as? kotlinx.serialization.json.JsonPrimitive)?.let { a ->
                    m["animation"] = kotlinx.serialization.json.JsonObject(mapOf("type" to kotlinx.serialization.json.JsonPrimitive(a.content)))
                }
                (m["children"] as? kotlinx.serialization.json.JsonArray)?.let { ch ->
                    m["children"] = kotlinx.serialization.json.JsonArray(ch.filterIsInstance<kotlinx.serialization.json.JsonObject>().map { normalizeEl(it) })
                }
                (m["events"] as? kotlinx.serialization.json.JsonObject)?.let { ev ->
                    val e2 = ev.toMutableMap()
                    // 事件 key 别名统一为 onClick（模型常写 onTap/click/on_press）
                    e2.keys.toList().forEach { k ->
                        val alias = mapOf("onTap" to "onClick", "tap" to "onClick", "click" to "onClick", "on_press" to "onClick", "on_click" to "onClick", "press" to "onClick")
                        alias[k]?.let { target ->
                            if (!e2.containsKey(target)) { e2[target] = e2[k]!! }
                            if (k != target) e2.remove(k)
                        }
                    }
                    e2.keys.toList().forEach { k ->
                        val v = e2[k]
                        if (v is kotlinx.serialization.json.JsonObject) {
                            val acts = v["actions"]
                            if (acts is kotlinx.serialization.json.JsonArray) {
                                e2[k] = kotlinx.serialization.json.JsonObject(
                                    v.toMutableMap().apply {
                                        this["actions"] = kotlinx.serialization.json.JsonArray(acts.mapNotNull { act ->
                                            if (act is kotlinx.serialization.json.JsonObject) {
                                                val a2 = act.toMutableMap()
                                                a2["type"]?.let { tp -> if (!a2.containsKey("action")) a2["action"] = tp }
                                                a2.remove("type")
                                                (a2["spec"] as? kotlinx.serialization.json.JsonPrimitive)?.let { sp ->
                                                    runCatching { lenientJson.parseToJsonElement(sp.content) }.getOrNull()?.let { a2["spec"] = it }
                                                }
                                                (normalizeEl(kotlinx.serialization.json.JsonObject(a2)) as kotlinx.serialization.json.JsonObject)
                                            } else null
                                        })
                                    }
                                )
                            }
                        }
                    }
                    m["events"] = kotlinx.serialization.json.JsonObject(e2)
                }
                m.keys.toList().forEach { k ->
                    if (k != "children" && k != "events" && k != "style") m[k] = normalizeEl(m[k]!!)
                }
                kotlinx.serialization.json.JsonObject(m)
            }
            is kotlinx.serialization.json.JsonArray ->
                kotlinx.serialization.json.JsonArray(el.map { normalizeEl(it) })
            else -> el
        }
    }

    /** 解析前完整预处理：归一化 + 动作判别纠正 */
    private fun prepareForCanvas(jsonIn: String): String {
        // 剥离意外残留的围栏标记（某些提取路径返回了带 ```markdown 的串）
        var s0 = jsonIn.trim()
        if (s0.startsWith("```")) {
            s0 = s0.removePrefix("```").substringAfter('\n', "").removeSuffix("```").trim()
        }
        // 数组内裸逗号/连续逗号/前导逗号清理（模型常输出 children: [ , , {..} ]）
        val j1 = Regex(",(\\s*,)+").replace(s0, ",")
        // cornerRadius 四角数组 [16,16,0,0] → 首数字（数组会让整页 JSON 解析失败）
        val j1b = Regex("\"cornerRadius\"\\s*:\\s*\\[\\s*(\\d+(?:\\.\\d+)?)").replace(j1) { mr -> "\"cornerRadius\": " + mr.groupValues[1] }
        val j2 = Regex("\\[\\s*,").replace(j1b, "[")
        return sanitizeActionsJson(normalizeGenUIJson(j2))
    }

    private fun validForCanvas(json: String, strict: Boolean): Boolean {
        if (strict) {
            // 先纠正动作判别字段，再用 SDK 宽松配置（ignoreUnknownKeys）做完整解析
            val spec = runCatching { com.ai.assistance.quro.genui.sdk.dsl.DslParser.parse(prepareForCanvas(json)) }.getOrNull() ?: return false
            return spec.root.type.isNotBlank()
        }
        return StreamingParser.tryParsePartial(json) != null
    }

    /** ```genui / ```gen-ui 代码块提取（多轮择优：完整(最长优先)→修复→宽松补全） */
    private fun extractFromGenuiFences(source: String, strict: Boolean): String? {
        val genuiRegex = Regex("```(?:genui|gen-ui)\\s*\\n?([\\s\\S]*?)```", RegexOption.IGNORE_CASE)
        val candidates = genuiRegex.findAll(source).map { it.groupValues[1].trim() }
            .filter { it.startsWith("{") }.sortedByDescending { it.length }.toList()
        if (candidates.isEmpty()) return null
        // 第一轮：完整候选，最长优先（避免被截断残段抢先）
        candidates.filter { it.endsWith("}") }.forEach { json ->
            if (validForCanvas(json, strict)) return json
        }
        // 第二轮：空 root 修复轮
        candidates.filter { it.endsWith("}") }.forEach { json ->
            val repaired = repairEmptyRootValue(json) ?: return@forEach
            if (validForCanvas(repaired, strict)) return repaired
        }
        // 第三轮：不完整候选 — 仅流式模式允许部分解析
        if (!strict) {
            candidates.filter { !it.endsWith("}") }.forEach { json ->
                if (StreamingParser.tryParsePartial(json) != null) return json
            }
        }
        return null
    }

    /** ```json 代码块提取（多轮择优） */
    private fun extractFromJsonFences(source: String, strict: Boolean): String? {
        val jsonRegex = Regex("```json\\s*\\n?([\\s\\S]*?)```", RegexOption.IGNORE_CASE)
        val candidates = jsonRegex.findAll(source).map { it.groupValues[1].trim() }
            .filter { it.startsWith("{") && it.endsWith("}") && containsRootKey(it) }
            .sortedByDescending { it.length }.toList()
        candidates.forEach { json -> if (validForCanvas(json, strict)) return json }
        candidates.forEach { json ->
            val repaired = repairEmptyRootValue(json) ?: return@forEach
            if (validForCanvas(repaired, strict)) return repaired
        }
        return null
    }

    /** 最大的完整 JSON 对象提取（含校验与空 root 修复） */
    private fun extractLargestGenUI(source: String, strict: Boolean): String? {
        val largestJson = extractLargestJson(source) ?: return null
        if (!containsRootKey(largestJson)) return null
        if (validForCanvas(largestJson, strict)) return largestJson
        val repaired = repairEmptyRootValue(largestJson)
        if (repaired != null && validForCanvas(repaired, strict)) return repaired
        return null
    }

    private fun extractGenUIDsl(text: String, isStreaming: Boolean = false, strict: Boolean = false): String? {
        // ═══════════════════════════════════════════════════════
        // 流式铁闸：流式模式下，检测到任何思考/内部内容特征，直接返回 null
        // 画布上要么是真正的 GenUI JSON，要么是加载动画，绝不能有思考文本
        // ═══════════════════════════════════════════════════════
        if (isStreaming && containsThinkingContent(text)) {
            // 再确认一下：文本中是否真的有 GenUI 代码块？
            // 如果有 ```genui 或 ```json 且包含 root 键，那是真 UI，放行
            val hasGenuiBlock = text.contains("```genui", ignoreCase = true) ||
                    text.contains("```json", ignoreCase = true)
            val hasRootKey = containsRootKey(text)
            if (!(hasGenuiBlock && hasRootKey)) {
                // 没有真正的 UI 代码块，且有思考内容 → 直接返回 null
                return null
            }
        }

        // ═══ 优先在【原始文本】上提取 — 围栏内是正式输出，不受标签剥离影响 ═══
        // 诊断发现：模型未闭合思考标签时，stripThinkingTags 会从标签处截断，
        // 把完整的 genui JSON 一起丢掉（fullText=10976字符 → 清洗后仅73字符）。
        // 围栏/JSON 提取带 tryParsePartial 校验，不会把脏内容放进画布。
        extractFromGenuiFences(text, strict)?.let { return it }
        extractFromJsonFences(text, strict)?.let { return it }
        extractLargestGenUI(text, strict)?.let { return it }

        // 0. 再剥离所有思考标签内容，作为兜底提取源
        val cleanedText = stripThinkingTags(text)

        // 1. ```genui 完整代码块 — 最高优先级
        //    当存在完整的 genui 代码块时，直接使用其中的 JSON，
        //    忽略代码块之外的所有文本（包括引导语、工具结果描述等）
        //    大小写不敏感，支持 ```genui / ```GenUI / ```GENUI 等
        extractFromGenuiFences(cleanedText, strict)?.let { return it }

        // 2. ```json 完整代码块
        extractFromJsonFences(cleanedText, strict)?.let { return it }

        // 3. 最大的完整 JSON 对象
        // 必须通过 StreamingParser.tryParsePartial 验证，确保是有效的 GenUI DSL
        extractLargestGenUI(cleanedText, strict)?.let { return it }

        // 4. 流式输出 — 从 ```genui 代码块中提取不完整的 JSON
        if (cleanedText.contains("```genui")) {
            val start = cleanedText.indexOf("```genui")
            val jsonStart = cleanedText.indexOf('{', start)
            if (jsonStart >= 0) {
                // 只取代码块开始后的部分，忽略前面的引导文本
                val partial = cleanedText.substring(jsonStart).trim()
                // 必须有 root 键且 root 值已开始（防止截断的 JSON 进入渲染器）
                if (containsRootKey(partial) && hasRootValueStarted(partial)) {
                    if (StreamingParser.tryParsePartial(partial) != null) {
                        return partial
                    }
                }
            }
        }

        // 5. 流式输出 — 从 ```json 代码块中提取不完整的 JSON
        if (cleanedText.contains("```json")) {
            val start = cleanedText.indexOf("```json")
            val jsonStart = cleanedText.indexOf('{', start)
            if (jsonStart >= 0) {
                val partial = cleanedText.substring(jsonStart).trim()
                if (containsRootKey(partial) && hasRootValueStarted(partial)) {
                    if (StreamingParser.tryParsePartial(partial) != null) {
                        return partial
                    }
                }
            }
        }

        // 6. 流式输出 — 直接从文本中找到第一个 { 开始的不完整 JSON
        //    但只在文本中没有代码块标记时才尝试，避免把代码块外的描述包进去
        val hasCodeBlock = cleanedText.contains("```")
        if (!hasCodeBlock) {
            val firstBrace = cleanedText.indexOf('{')
            if (firstBrace >= 0) {
                val partial = cleanedText.substring(firstBrace).trim()
                if (containsRootKey(partial) && hasRootValueStarted(partial)) {
                    if (StreamingParser.tryParsePartial(partial) != null) {
                        return partial
                    }
                }
            }
        }

        // 7. 纯文本包装（降级：将 AI 的纯文本回复包装成 GenUI）
        //    核心原则：AI 任何的回复都是 UI
        //    只要在剥离思考标签后有文本，就包装成卡片显示
        if (!isStreaming && cleanedText.isNotBlank() && !cleanedText.startsWith("{")) {
            val wrapped = autoWrapTextToGenUI(cleanedText)
            if (wrapped != null) return wrapped
        }

        return null
    }

    /**
     * 剥离所有思考标签、工具调用结果和引导文本
     *
     * 移除 AI 思考过程中产生的内部内容，包括：
     * 1. XML 风格思考标签：<thinking>、<retrieve>、<decision>、<generate> 等
     * 2. 未闭合的思考标签（流式场景）：从标签开始到文本末尾全部移除
     * 3. Markdown 风格思考段落：## 思考、**思考**： 等开头的段落
     * 4. 工具调用结果描述："信息已获取："、"工具调用结果："、"Tool Result:" 等
     * 5. 设备信息等工具返回的纯文本描述
     * 6. genui 代码块之前的引导文本（当存在 genui 代码块时）
     * 7. 内联的 JSON 工具结果对象（非 GenUI 格式的 JSON）
     * 8. 代码块之后的解释性文本
     *
     * 支持多行、非贪婪匹配。
     */
    fun stripThinkingTagsPublic(text: String): String = stripThinkingTags(text)

    private fun stripThinkingTags(text: String): String {
        var result = text

        // ── 0. 紧急拦截：检测是否处于思考标签内部（流式场景） ──
        //    如果文本中包含未闭合的思考标签，直接截断到标签开始位置之前。
        //    这是流式场景下最重要的防护——防止思考过程内容泄露进画布。
        val unclosedTag = findUnclosedThinkingTag(result)
        if (unclosedTag != null) {
            result = result.substring(0, unclosedTag).trim()
            // 如果截断后为空，直接返回（没有有效内容）
            if (result.isBlank()) return ""
        }

        // ── 0.5 内容保留型标签：只剥标签壳，绝不删除内部内容 ──
        //    <generate> 包裹的是正式 UI 输出（```genui JSON）。
        //    若按普通思考标签处理，整个 JSON 会被连根删掉，导致画布无法渲染。
        val contentPreservingTags = listOf("generate", "generation", "gen")
        for (tag in contentPreservingTags) {
            result = Regex("</?$tag(?:\\s[^>]*)?>", RegexOption.IGNORE_CASE).replace(result, "")
        }

        // ── 1. XML 风格思考标签 ──────────────────────────────
        val thinkingTags = listOf(
            "thinking", "reasoning", "thought", "intent", "intention", "intint",
            "retrieve", "retrieval", "search",
            "plan", "planning",
            "tool_call", "toolcall", "tool", "tool_result", "toolResult",
            "decision", "decide",
            "self_correct", "self_correction", "selfcorrect", "self-correct",
            "journal", "log",
            "analysis", "analyze", "analyse",
            "reflection", "reflect",
            "observation", "observe",
            "action", "step",
            "context", "memory",
            "clarify", "question",
            "draft", "outline",
            "verify", "validation", "validate", "check",
            "debug", "trace", "inspect",
            "component_check", "type_check", "schema_check"
        )

        for (tag in thinkingTags) {
            // 匹配 <tag>...</tag>，支持多行、非贪婪
            val regex = Regex("<$tag>[\\s\\S]*?</$tag>", RegexOption.IGNORE_CASE)
            result = regex.replace(result, "")
            // 也匹配自闭合标签 <tag/>
            val selfClosingRegex = Regex("<$tag\\s*/>", RegexOption.IGNORE_CASE)
            result = selfClosingRegex.replace(result, "")
            // 匹配带属性的标签 <tag ...>...</tag>
            val attrRegex = Regex("<$tag[^>]*>[\\s\\S]*?</$tag>", RegexOption.IGNORE_CASE)
            result = attrRegex.replace(result, "")
        }

        // ── 1.5 移除孤立闭合标签及其前方的全部内容 ──────────
        //    AI 可能把 <tag> 开标签放在 reasoning 部分，
        //    content 部分只有：标签内容 + </tag>
        //    此时 </tag> 前面的所有文本都是标签内容，必须全部移除
        val slash = "/"
        for (tag in thinkingTags) {
            val closeTagPattern = "<${slash}$tag\\s*>"
            val closeRegex = Regex(closeTagPattern, RegexOption.IGNORE_CASE)
            val openRegex = Regex("<$tag(?:\\s[^>]*)?>", RegexOption.IGNORE_CASE)

            val openCount = openRegex.findAll(result).count()
            val closeMatches = closeRegex.findAll(result).toList()

            // 闭合标签数量 > 开标签数量 → 有孤立的闭合标签
            if (closeMatches.size > openCount) {
                // 找到第一个孤立的闭合标签位置
                val firstClose = closeMatches.first().range.first
                // 移除从文本开头到闭合标签末尾的所有内容
                val closeEnd = closeMatches.first().range.last + 1
                result = result.substring(closeEnd).trim()
                break  // 只需处理一次，后面重新进入循环
            }
        }

        // ── 2. Markdown 风格思考段落 ──────────────────────────
        // 匹配 "## 思考"、"### 思考过程" 等标题开头的段落
        val mdThinkingHeaders = listOf(
            "思考", "思考过程", "分析", "分析过程",
            "计划", "规划", "步骤", "行动",
            "检索", "搜索", "查询",
            "决策", "判断", "反思",
            "工具调用", "工具使用", "tool", "thinking", "reasoning"
        )
        for (header in mdThinkingHeaders) {
            // 匹配 ## 标题 到下一个 ## 标题或文末之间的内容
            val headerRegex = Regex(
                "(?i)^#{1,4}\\s*$header[：:.*#\\s]*\\n[\\s\\S]*?(?=\\n#{1,4}\\s|\\n```|$)",
                RegexOption.MULTILINE
            )
            result = headerRegex.replace(result, "")
        }

        // 匹配 "**思考**："、"**分析**：" 等粗体开头的行及其后内容
        val boldThinkingRegex = Regex(
            "(?i)^\\s*\\*\\*(思考|分析|计划|决策|检索|工具调用|生成|反思|判断)\\*\\*[：:].*$",
            RegexOption.MULTILINE
        )
        result = boldThinkingRegex.replace(result, "")

        // ── 3. 工具调用结果描述文本 ──────────────────────────
        // 移除 "信息已获取："、"工具调用结果："、"Tool result:" 等开头的行及其后续描述
        val toolResultPrefixes = listOf(
            "信息已获取", "获取到信息", "已获取信息",
            "工具调用结果", "工具结果", "工具返回", "工具执行结果",
            "tool result", "tool_result", "tool output", "tool response",
            "设备信息", "device info",
            "执行结果", "运行结果", "调用结果",
            "返回结果", "响应结果",
            "查询结果", "搜索结果",
            "组件注册", "注册结果",
            "result:", "results:"
        )
        for (prefix in toolResultPrefixes) {
            // 匹配前缀开头的整行（带冒号或空格）
            val prefixRegex = Regex(
                "(?i)^\\s*$prefix[：:].*$",
                RegexOption.MULTILINE
            )
            result = prefixRegex.replace(result, "")
        }

        // 移除 "根据设备信息"、"根据工具结果" 等引导句
        val introPhrases = listOf(
            "根据.*信息", "根据.*结果", "基于.*信息", "基于.*结果",
            "结合.*信息", "结合.*结果", "按照.*信息", "按照.*结果"
        )
        for (phrase in introPhrases) {
            val phraseRegex = Regex("(?i)^\\s*$phrase.*[，,]?.*[。.！!]?$", RegexOption.MULTILINE)
            result = phraseRegex.replace(result, "")
        }

        // ── 3.5 剥离内联工具结果 JSON ──────────────────────────
        // 移除包含典型工具结果键（如 success/error/device 等）的 JSON 对象
        // 这些不是 GenUI DSL，不应该进入画布
        result = stripToolResultJsonBlocks(result)

        // ── 4. 移除代码块之前的引导文本（当存在 genui 代码块时） ──
        // 如果文本中有 ```genui 代码块，代码块之前的所有文本都视为引导语
        val genuiBlockIndex = result.indexOf("```genui")
        if (genuiBlockIndex > 0) {
            // 保留代码块及其之后的内容
            result = result.substring(genuiBlockIndex)
        }

        // 同样处理 ```json 代码块（当没有 genui 块但有 json 块时）
        val jsonBlockIndex = result.indexOf("```json")
        if (jsonBlockIndex > 0 && genuiBlockIndex < 0) {
            // 检查 json 块是否包含 GenUI DSL 特征（root 键）
            val afterJson = result.substring(jsonBlockIndex)
            val firstBrace = afterJson.indexOf('{')
            if (firstBrace >= 0) {
                val potentialJson = afterJson.substring(firstBrace).take(200)
                if (potentialJson.contains("\"root\"")) {
                    // 看起来像 GenUI，保留
                    result = afterJson
                }
            }
        }

        // ── 5. 清理多余空白行 ────────────────────────────────
        // 移除连续的空行（保留最多一个空行）
        result = result.replace(Regex("\\n{3,}"), "\n\n")

        return result.trim()
    }

    /**
     * 剥离内联的工具结果 JSON 对象
     *
     * 识别并移除看起来像工具调用结果的 JSON 对象（非 GenUI DSL）。
     * 判断依据：包含 success/error/device 等工具结果特征键，且不包含 GenUI 的 root 键。
     */
    private fun stripToolResultJsonBlocks(text: String): String {
        var result = text
        val toolResultKeys = listOf(
            "\"success\"", "\"error\"", "\"device\"", "\"width\"", "\"height\"",
            "\"model\"", "\"version\"", "\"sdk\"", "\"density\"", "\"dpi\"",
            "\"registered\"", "\"component\""
        )

        // 找到所有 JSON 对象块
        var firstBrace = result.indexOf('{')
        while (firstBrace >= 0) {
            var depth = 0
            var inString = false
            var escaped = false
            var endIndex = -1

            for (i in firstBrace until result.length) {
                val c = result[i]
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = !inString
                    !inString && c == '{' -> depth++
                    !inString && c == '}' -> {
                        depth--
                        if (depth == 0) {
                            endIndex = i + 1
                            break
                        }
                    }
                }
            }

            if (endIndex > firstBrace) {
                val jsonBlock = result.substring(firstBrace, endIndex)
                // 检查是否是工具结果 JSON（有工具特征键，没有 root 键）
                val hasToolResultKey = toolResultKeys.any { jsonBlock.contains(it) }
                val hasRootKey = jsonBlock.contains("\"root\"")
                val hasSchemaKey = jsonBlock.contains("\"schemaVersion\"")

                if (hasToolResultKey && !hasRootKey && !hasSchemaKey) {
                    // 这是工具结果 JSON，移除
                    result = result.removeRange(firstBrace, endIndex)
                    // 移除后重新从当前位置开始查找
                    firstBrace = result.indexOf('{', firstBrace.coerceAtMost(result.length))
                    continue
                }
            }
            // 移动到下一个可能的 JSON 开始位置
            firstBrace = result.indexOf('{', firstBrace + 1)
        }

        return result
    }

    /**
     * 将纯文本回复包装成 GenUI 文本卡片
     *
     * 仅保留有意义的纯文本回复。如果剥离思考标签、代码块、工具调用结果后
     * 文本为空，则返回 null（不降级显示）。
     *
     * 严格过滤：任何看起来像内部思考/检查/调试/工具输出的内容都不包装。
     */
    private fun autoWrapTextToGenUI(text: String): String? {
        var plainText = text

        // 先剥离思考标签
        plainText = stripThinkingTags(plainText)

        // 如果剥离后没有有意义的文本，返回 null
        if (plainText.isBlank()) return null

        // 移除代码块（代码块不是可显示的文本内容）
        val codeBlockRegex = Regex("```[\\s\\S]*?```")
        plainText = codeBlockRegex.replace(plainText, "").trim()

        // 移除工具调用结果标记
        val toolResultRegex = Regex("(?i)tool\\s*result\\s*:?[\\s\\S]*?(?=\\n\\n|$)")
        plainText = toolResultRegex.replace(plainText, "").trim()

        // 如果剥离后没有有意义的文本，返回 null
        if (plainText.isBlank()) return null

        // 限制长度，避免过长
        if (plainText.length > 500) {
            plainText = plainText.take(500) + "..."
        }

        val escaped = plainText
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "")
            .replace("\t", "\\t")

        return """{"id":"auto_wrap","title":"AI 回复","root":{"type":"card","style":{"padding":{"all":16},"cornerRadius":12,"elevation":2},"children":[{"type":"text","properties":{"text":"$escaped"},"style":{"textSize":14}}]}}"""
    }

    private fun extractLargestJson(text: String): String? {
        val firstBrace = text.indexOf('{')
        if (firstBrace < 0) return null

        var depth = 0
        var inString = false
        var escaped = false

        for (i in firstBrace until text.length) {
            val c = text[i]
            when {
                escaped -> escaped = false
                c == '\\' -> escaped = true
                c == '"' -> inString = !inString
                !inString && c == '{' -> depth++
                !inString && c == '}' -> {
                    depth--
                    if (depth == 0) {
                        return text.substring(firstBrace, i + 1).trim()
                    }
                }
            }
        }
        return null
    }

    /**
     * 快速检查 JSON 字符串中是否包含 "root" 键（而非字符串值中的 root）
     *
     * 匹配 "root"\s*:  即 root 键后面跟着冒号（可能有空白）。
     * 用于快速过滤掉明显不完整的 JSON（只有 id/title，没有 root）。
     */
    private fun containsRootKey(json: String): Boolean {
        return Regex(""""root"\s*:""").containsMatchIn(json)
    }

    /**
     * 检查 JSON 中 root 键后面是否已开始有值（至少有 { ）
     *
     * 防止返回 "root": 后面截断的 JSON 给渲染器导致崩溃。
     */
    private fun hasRootValueStarted(json: String): Boolean {
        val match = Regex(""""root"\s*:""").find(json) ?: return false
        val afterColon = match.range.last + 1
        for (i in afterColon until json.length) {
            val c = json[i]
            if (!c.isWhitespace()) return c == '{'
        }
        return false
    }

    /**
     * 修复 "root": 后无值（直接跟 } 或 EOF）的非法 JSON
     *
     * 场景：流式输出在 "root": 处被截断，续写时模型只补了闭括号，
     * 产生 {"id":...,"title":...,"root":} 这类结构无法通过解析。
     * 用一个占位文本组件填充 root，保住 id/title 等已生成的内容。
     */
    private fun repairEmptyRootValue(json: String): String? {
        if (!Regex(""""root"\s*:\s*(?=\})""").containsMatchIn(json)) return null
        val title = Regex(""""title"\s*:\s*"([^"]*)""").find(json)?.groupValues?.get(1) ?: "AI 界面"
        val heading = title.replace("\\", "\\\\").replace("\"", "\\\"")
        val placeholderRoot =
            "{\"type\":\"card\",\"style\":{\"padding\":{\"all\":20},\"cornerRadius\":16,\"elevation\":2},\"children\":[" +
            "{\"type\":\"text\",\"properties\":{\"text\":\"" + heading + "\"},\"style\":{\"textSize\":22,\"fontWeight\":\"bold\"}}," +
            "{\"type\":\"text\",\"properties\":{\"text\":\"界面内容生成不完整，请点击重试\"},\"style\":{\"textSize\":14}}]}"
        return json.replaceFirst(
            Regex(""""root"\s*:\s*(?=\})"""),
            "\"root\":" + placeholderRoot
        )
    }
}
