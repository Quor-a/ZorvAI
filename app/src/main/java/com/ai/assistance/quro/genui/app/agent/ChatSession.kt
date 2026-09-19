package com.ai.assistance.quro.genui.app.agent

import com.ai.assistance.quro.genui.app.agent.tools.BuiltinTools
import com.ai.assistance.quro.genui.app.llm.LLMClient
import com.ai.assistance.quro.genui.app.store.GenStore
import com.ai.assistance.quro.genui.app.store.ModelProvider
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * 标准 Agent 对话会话：多轮聊天、流式气泡、可调用工具（搜索/文件/记忆/设备能力…），
 * 但不生成界面——答案以对话消息呈现。
 *
 * 设计：与 [AgentLoop] 共用同一套 [BuiltinTools] + [LLMClient]，工具执行逻辑在此复刻，
 * 不重复实现。区别仅在"产出形态"——[AgentLoop] 把结果喂给 WebView 画界面，
 * [ChatSession] 把结果以消息气泡流式呈现。
 */
/** 用户附件：图片走 vision（base64 content 数组），文本直接读，二进制落盘供 file_read */
data class Attach(
    val name: String,
    val mime: String,
    val path: String,
    val size: Long,
    val isImage: Boolean,
)

data class ToolTrace(
    val name: String,
    val brief: String,
    val ms: Long = -1,              // -1 = 进行中
    val isError: Boolean = false,
    val denied: Boolean = false,
)

data class ChatMsg(
    val id: String,
    val role: String,   // "user" | "assistant" | "tool" | "error"
    val text: String = "",
    val done: Boolean = false,
    val ts: Long = System.currentTimeMillis(),
    val tool: ToolTrace? = null,    // 工具调用结构化元数据（不持久化，运行期渲染用）
    val reasoning: String = "",     // 思考全文（折叠条展开用，运行期渲染）
    val reasoningMs: Long = 0,      // 思考耗时
    val attachments: List<Attach> = emptyList(),  // 用户附件（运行期渲染，不持久化）
)

class ChatSession(
    private val store: GenStore,
    private val onUserMsg: (String) -> Unit,
    private val onAssistantStart: (id: String) -> Unit,
    private val onAssistantDelta: (id: String, delta: String) -> Unit,
    private val onAssistantDone: (id: String) -> Unit,
    private val onToolStart: (id: String, name: String, brief: String) -> Unit,
    private val onToolResult: (id: String, result: String, ms: Long, ok: Boolean) -> Unit,
    private val onThinking: (String) -> Unit,
    private val onError: (String) -> Unit,
    private val onReasoningComplete: (id: String, text: String, ms: Long) -> Unit = { _, _, _ -> },
    private val onAskPermission: suspend (tool: String, brief: String, level: Int) -> Boolean,
    /** 对话里生成了完整 HTML 界面 → 交给画布渲染（GenUI 渲染支持） */
    private val onUiDetected: (html: String) -> Unit = {},
    /** create_miniapp 成功 → 对话内嵌小程序卡片 + 画布入栈 */
    private val onMiniApp: (appId: String) -> Unit = {},
    /** AI 回复中检出 A2UI JSONL → 官方 A2UI-Android 引擎全屏渲染（生成式 UI 的安卓原生形态） */
    private val onA2ui: (jsonl: String, surfaceId: String) -> Unit = { _, _ -> }
) {
    @Volatile var cancelled = false
        private set
    fun cancel() { cancelled = true; llm.cancel() }

    private val tools = BuiltinTools(store.context())
    private val llm = LLMClient()
    /** 对话上下文（OpenAI messages 数组），跨多轮保留 */
    private val messages = JSONArray()

    private fun newId(): String = UUID.randomUUID().toString().take(8)

    /**
     * A2UI JSONL 检出：AI 回复里出现 createSurface/updateComponents/updateDataModel 行时，
     * 把这些行提取为完整 JSONL，交给官方 A2UI-Android 引擎渲染成安卓原生界面。
     * 这是"生成式 UI 可以是安卓"的直通车——AI 不写 HTML 也能产出真原生交互界面。
     */
    private fun maybeEmitA2ui(content: String) {
        runCatching {
            val lines = content.lines().map { it.trim() }
                .filter { it.startsWith("{") && (it.contains("createSurface") ||
                    it.contains("updateComponents") || it.contains("updateDataModel")) }
            if (lines.size < 2) return
            val jsonl = lines.joinToString("\n")
            val sid = Regex("\"surfaceId\"\\s*:\\s*\"([^\"]+)\"").find(jsonl)?.groupValues?.get(1) ?: "main"
            onA2ui(jsonl, sid)
        }
    }

    /** 最近一条用户消息（UI 意图判断用） */
    private fun lastUserText(): String {
        for (i in messages.length() - 1 downTo 0) {
            val m = messages.optJSONObject(i) ?: continue
            if (m.optString("role") == "user") return m.optString("content")
        }
        return ""
    }

    /** UI 意图关键词：与 AgentLoop.isUiIntent 同一份清单 */
    private fun isUiIntent(prompt: String): Boolean {
        val nouns = listOf(
            "页面", "网页", "界面", "应用", "小程序", "工具", "仪表盘", "看板", "面板",
            "卡片", "图表", "表单", "计算器", "游戏", "Dashboard", "dashboard",
        )
        val selfShow = listOf(
            "你自己", "自我介绍", "介绍自己", "你是谁", "介绍下你", "介绍一下你",
            "你的能力", "你能做什么", "展示一下你",
        )
        return nouns.any { prompt.contains(it, ignoreCase = true) } ||
            selfShow.any { prompt.contains(it) }
    }

    /** 从回答里提取完整 HTML 文档（```html 围栏或裸 <!DOCTYPE>…</html>） */
    private fun extractHtml(text: String): String? {
        val fence = Regex("(?is)```html\\s*\\n(.*?)```").find(text)
        if (fence != null) return fence.groupValues[1].trim()
        val doc = Regex("(?is)(<!DOCTYPE html>.*</html>)").find(text)
        return doc?.groupValues?.get(1)
    }

    /** 回答含完整界面时：原文进消息（对话框内嵌完整渲染引擎），同时通知画布入栈 */
    private fun deliverMaybeUi(aid: String, text: String) {
        onAssistantDelta(aid, text)
        extractHtml(text)?.let { html -> onUiDetected(html) }
    }

    /** 长对话防爆上下文：决策轮只带最近 30 条（system 恒在首条） */
    private fun capHistory(): JSONArray {
        if (messages.length() <= 30) return messages
        val out = JSONArray()
        out.put(messages.getJSONObject(0)) // system
        for (i in messages.length() - 29 until messages.length()) out.put(messages.getJSONObject(i))
        return out
    }

    /** 对话模式系统提示：灵魂名 + 记忆索引 + 真实时间 + 对话守则 */
    private fun chatSystem(): String {
        val ctx = store.context()
        val soulName = runCatching { SoulStore(ctx).load()?.name }.getOrNull() ?: "助手"
        val mem = runCatching { AgentMemory(ctx).indexForPrompt() }.getOrDefault("")
        val now = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm EEEE", java.util.Locale.CHINA)
            .format(java.util.Date())
        return buildString {
            append("你是 ").append(soulName).append("，GenUI 的常驻对话助手。\n")
            append("# 你在哪个模式、该干什么\n")
            append("GenUI 是一个把 AI 回复变成真实可用界面的 Android 应用，有两个界面：\n")
            append("- 【Agent 对话框】（当前）：用户在这里和你聊天、提问、要数据。你直接回答、" +
                "出数据卡（```card 围栏）、用户要界面时在回复末尾给 ```html 完整文档（端上渲染到画布）。\n")
            append("- 【GenUI 画布】（另一个模式）：用户在那里给生成指令，产出整页界面——不归本会话管。\n")
            append("当前你的职责只有对话：回答、取数、出卡。\n")
            append("# 内置工具能力（直接调用，不必先探测）\n")
            append("- run_python：**内置 CPython 3.14 完整运行时**（标准库就绪）。数学计算、单位换算、" +
                "日期推算、统计、文本/JSON 处理——必须用 run_python 算出真值，禁止心算和估算。\n")
            append("- web_search / news_search：实时网页与新闻；system_status：设备与引擎状态；" +
                "memory_write/memory_read：长期记忆。其余工具见工具列表。\n")
            append("- 画布选择（GenUI 不止一种画布，先选对再动手）：\n" +
                "  · 小程序画布（create_miniapp + open_miniapp）：微信小程序语法（WXML/WXSS/JS，自研引擎原生渲染），" +
                "适合**有状态、频繁交互的轻应用**——待办/计算器/计时器/记事/换算/小游戏。" +
                "尺寸一律 rpx（750rpx=屏宽），禁 px。\n" +
                "  · HTML 画布：适合**信息展示为主**——新闻页/报告/仪表盘/图表/图文排版。\n" +
                "  口诀：会点很多下的用小程序，拿来看的用 HTML。用户说\"做个小工具/小应用\"优先小程序画布。\n" +
                "  · 原生渲染通道（HTML 内混搭真原生控件）：XML 原生布局（<!--stack:xml--> + <div id=\"gen-xml\"> 占位 + " +
                "<script type=\"text/xml-layout\">Android 控件 XML）、Compose 组件（<!--stack:compose--> + <div id=\"gen-compose\"> + " +
                "<script type=\"text/x-compose\">JSON 组件树）、GenCanvas（<!--stack:canvas--> + <div id=\"gen-canvas\"> + " +
                "<script type=\"text/x-canvas\">绘制指令 JSON）。\n")
            append("- 生成式 UI（安卓原生直出）：当卡片/表单/图表/列表等可视化明显更有帮助时，可直接在回复中输出 A2UI v0.10 JSONL\n" +
                "（每行一个 JSON：第一条 createSurface，然后 updateComponents，最后 updateDataModel；" +
                "root(Card)->content(Column)->children；组件 component 字段；数据绑定 {\"path\":\"/...\"}）——" +
                "端上会用谷歌官方 A2UI 引擎渲染成真原生界面，你的回答气泡旁会展开它。\n")
            append("# 现在时间\n").append(now).append("\n")
            if (mem.isNotBlank()) append("\n# 记忆库索引（用户相关的长期记忆，细节用 memory_read 查）\n").append(mem).append("\n")
            append("\n# 对话守则\n")
            append("- 直接回答问题；不要输出 HTML 文档，用户明确要代码片段时才给代码。\n")
            append("- 需要实时信息、设备能力或读写记忆时调用工具；调用前用一句话说明目的。\n")
            append("- 回答用 Markdown：要点用列表，命令/术语用行内代码。\n")
            append("- 工具失败就如实说明，绝不编造数据。\n")
            append("- 用户要界面/页面/可视化/小工具时：禁止只给文字描述——先按需调用工具取真实数据，" +
                "然后回复末尾用 ```html 围栏给出完整 HTML 文档（脚本引用 /assets/runtimes/ 本地运行时，" +
                "数据用工具返回的真值填充），端上会把它渲染到画布并通知用户。\n")
            append("- 结构化数据用卡片围栏输出（气泡下方渲染真实数据卡）：类型 stat(指标" +
                "title/value/delta)、progress(title/percent)、list(title/items[{text}])、" +
                "bar(title/data[{label,value}])、gauge(title/percent)、line(title/data[{value}])、" +
                "kv(title/items[{k,v}])。\n")
            append("# 诚实铁律（违反即事故）\n")
            append("- 工具失败 = 动作没有发生。严禁失败后声称「已保存/已发送/已获取到」；\n")
            append("- 严禁编造天气数值/新闻/股价等任何实时数据——真实数据只能来自本轮工具结果；\n")
            append("- 同一工具连续失败 2 次就停止尝试，如实告知用户失败原因（错误里有指引），不要绝望式乱试；\n")
            append("- 没有真实数据就直说拿不到——诚实的一句「拿不到」胜过编造的一万字。\n")
            append("- ★ 自写可视化卡：先 MoBridge.ui.component('名字', 模板组件树) 注册" +
                "（模板 {{prop}} 占位），然后回复里 ```card {\"use\":\"名字\",\"属性\":\"值\"}``` " +
                "即渲染你自己的设计；改版式重新注册同名即重渲染，弹窗/卡片全端生效。")
        }
    }

    /**
     * 从持久化对话记录重建模型上下文（单一事实源 = GenStore 的 chatLog）。
     * 修复：ChatSession.messages 是内存态——重启/Activity 重建后 UI 有历史、
     * 模型零记忆（"每次对话都是新的对话"）。每轮 chat() 前重灌，自愈。
     * 保持 messages[0]=system；截最近 20 条、单条 1200 字符；跳过空文本。
     */
    /** 新建对话：清空模型上下文（system 之外全部丢弃），UI 侧同步清 chatLog */
    fun reset() {
        // JSONArray 无 clear()：保留 [0]=system，逐个移除其余
        for (i in messages.length() - 1 downTo 1) messages.remove(i)
    }

    fun restoreHistory(items: List<Pair<String, String>>) {
        val real = items.filter { it.second.isNotBlank() }
            .map { (r, t) -> r to if (t.length > 1200) t.take(1200) + "…(截断)" else t }
            .takeLast(20)
        if (real.isEmpty()) return
        val arr = JSONArray()
        arr.put(JSONObject().put("role", "system").put("content", chatSystem()))
        real.forEach { (role, text) ->
            arr.put(JSONObject().put(
                "role", if (role == "assistant") "assistant" else "user"
            ).put("content", text))
        }
        while (messages.length() > 0) messages.remove(0)
        for (i in 0 until arr.length()) messages.put(arr.getJSONObject(i))
    }

    /** 把用户本轮输入加入上下文并通知 UI */
    fun addUser(text: String, attachments: List<Attach> = emptyList()) {
        if (attachments.isNotEmpty()) {
            // 多模态：OpenAI 兼容 content 数组（text + image_url base64）——chatOnce 原样透传
            val contentArr = org.json.JSONArray()
            var finalText = text
            for (a in attachments) {
                val f = java.io.File(a.path)
                if (a.isImage && f.exists()) {
                    runCatching {
                        val b64 = android.util.Base64.encodeToString(f.readBytes(), android.util.Base64.NO_WRAP)
                        contentArr.put(JSONObject().put("type", "image_url")
                            .put("image_url", JSONObject().put("url", "data:${a.mime};base64,$b64")))
                    }
                } else if (a.mime.startsWith("text/") || a.name.matches(
                        Regex(".*\\.(md|txt|json|csv|log|kt|py|js|ts|html|css|xml|yaml|yml|ini)", RegexOption.IGNORE_CASE))) {
                    val body = runCatching { f.readText() }.getOrDefault("").take(4000)
                    finalText += "\n\n（用户附上文件「${a.name}」内容：\n$body）"
                } else {
                    finalText += "\n\n（用户上传了附件「${a.name}」，${f.length() / 1024}KB，" +
                        "已保存于 ${f.absolutePath}，可用 file_read 工具读取）"
                }
            }
            contentArr.put(0, JSONObject().put("type", "text").put("text", finalText))
            messages.put(JSONObject().put("role", "user").put("content", contentArr))
            return
        }
        messages.put(JSONObject().put("role", "user").put("content", text))
        onUserMsg(text)
    }

    /**
     * 跑一轮对话：循环决策（带 function calling）→ 调用工具 → 直到模型不再调工具，
     * 再用流式接口把最终回答呈现成气泡。工具循环本身用非流式 chatOnce（带工具必须 OpenAI 协议），
     * 最终回答用 chatStream 流式输出。
     */
    suspend fun run(provider: ModelProvider) {
        try {
            // cancel() 之后 session 会被复用（ensureSession 是单例），不复位就永远起不来
            cancelled = false
            // 注入系统提示（只在首轮插一次）：灵魂 + 记忆 + 时间 + 对话守则
            if (messages.length() == 0 || messages.optJSONObject(0)?.optString("role") != "system") {
                val sys = JSONArray().put(JSONObject().put("role", "system").put("content", chatSystem()))
                for (i in 0 until sys.length()) messages.put(sys.getJSONObject(i))
                // 把 system 挪到最前：新 put 的在尾部
                val arr = JSONArray()
                arr.put(messages.getJSONObject(messages.length() - 1))
                for (i in 0 until messages.length() - 1) arr.put(messages.getJSONObject(i))
                while (messages.length() > 0) messages.remove(0)
                for (i in 0 until arr.length()) messages.put(arr.getJSONObject(i))
            }
            // 本轮对话的失败工具清单（诚实性硬兜底：回答前逐条点名注入）
            val failedTools = mutableListOf<String>()
            while (!cancelled) {
                onThinking("思考中…")
                // ★ 对话轮也必须带上运行时工具（run_python/run_js）——此前只在此处拼
                // declarations()，AI 收到的工具清单里根本没有 run_python（截图实锤）。
                val decls = tools.declarations()
                val rtArr = tools.runtimeDeclarations()
                for (i in 0 until rtArr.length()) decls.put(rtArr.getJSONObject(i))
                val assistant = llm.chatOnce(provider, capHistory(), decls)
                // 决策轮的推理过程也给用户看见（推理模型 reasoning_content / 思考标签）
                runCatching {
                    val th = assistant.optString("reasoning_content").ifBlank {
                        assistant.optString("reasoning")
                    }
                    if (th.isNotBlank()) onThinking("💭 " + th.takeLast(140).replace("\n", " "))
                }
                val calls = assistant.optJSONArray("tool_calls")
                val content = assistant.optString("content").orEmpty()

                if (calls == null || calls.length() == 0) {
                    // 快车道：决策轮若已给出有内容的完整回答（而非"我再想想"式的短句），
                    // 直接呈现，省掉一次重流式请求，且不丢模型的原话
                    if (content.length >= 40 && !content.startsWith("NO_TOOLS")) {
                        val aid = newId()
                        onAssistantStart(aid)
                        deliverMaybeUi(aid, content)
                        maybeEmitA2ui(content)
                        onAssistantDone(aid)
                        messages.put(JSONObject().put("role", "assistant").put("content", content))
                        // 用户要界面但模型只给了文字描述 → 自动跟进一次，逼出 ```html 真文档
                        if (isUiIntent(lastUserText()) && extractHtml(content) == null) {
                            messages.put(JSONObject().put("role", "user").put("content",
                                "（自动跟进）你刚才只给了文字描述，没有给界面。现在直接输出完整 HTML 文档：" +
                                    "需要数据就先调用工具取真实数据，然后以 ```html 围栏给出整页代码，" +
                                    "数据必须来自工具结果，禁止编造假数据。"))
                            val aid2 = newId()
                            onAssistantStart(aid2)
                            streamAnswer(provider, aid2, failedTools)
                            return
                        }
                        return
                    }
                    // 否则走流式重答
                    val aid = newId()
                    onAssistantStart(aid)
                    streamAnswer(provider, aid, failedTools)
                    return
                }

                // 有工具调用：写回 assistant 消息（含 tool_calls），逐个执行
                messages.put(JSONObject()
                    .put("role", "assistant")
                    .put("content", if (content.isBlank()) JSONObject.NULL else content)
                    .put("tool_calls", calls))
                for (i in 0 until calls.length()) {
                    if (cancelled) return
                    val call = calls.getJSONObject(i)
                    val fn = call.optJSONObject("function") ?: continue
                    val name = fn.optString("name")
                    val callId = call.optString("id", "call_${i}")
                    val args = runCatching { JSONObject(fn.optString("arguments").ifBlank { "{}" }) }
                        .getOrDefault(JSONObject())
                    val brief = args.keys().asSequence().take(2)
                        .joinToString(" ") { k -> "$k=${args.opt(k)?.toString()?.take(24)}" }

                    val tid = newId()
                    val t0 = System.currentTimeMillis()
                    onToolStart(tid, name, brief)
                    onThinking("调用工具：$name $brief")

                    val verdict = tools.gate.authorize(tools.gateFor(name), brief) { t, b, l ->
                        onAskPermission(t, b, l)
                    }
                    if (verdict != null) {
                        failedTools.add("$name（用户未授权）")
                        onToolResult(tid, "已拒绝：$verdict", -1L, true)
                        messages.put(JSONObject().put("role", "tool")
                            .put("tool_call_id", callId)
                            .put("content", JSONObject().put("denied", verdict).toString()))
                        continue
                    }
                    val res = runCatching { tools.execute(name, args) }
                        .getOrDefault(JSONObject().put("error", "工具执行失败"))
                    val cost = System.currentTimeMillis() - t0
                    // 判定精细化：返回里有有效数据（results/citations/items/text/output/context）就不算失败——
                    // 部分"error+数据共存"的工具（兜底链部分成功等）此前被一刀切标成失败
                    val hasData = (res.optJSONArray("results")?.length() ?: 0) > 0 ||
                        (res.optJSONArray("citations")?.length() ?: 0) > 0 ||
                        (res.optJSONArray("items")?.length() ?: 0) > 0 ||
                        res.optString("text").isNotBlank() ||
                        res.optString("output").isNotBlank() ||
                        res.optString("content").isNotBlank() ||
                        res.optString("context").isNotBlank() ||
                        res.optBoolean("ok", false)
                    val ok = if (res.has("error")) hasData else !res.has("denied")
                    if (!ok) failedTools.add("$name：${res.optString("error", res.optString("denied")).take(100)}")
                    // 给人看摘要（ToolSummarize），给模型看全量 JSON——此前气泡里怼 400 字
                    // 原始 JSON，用户根本读不了
                    onToolResult(tid,
                        (if (ok) "✅ " else "❌ ") + "$name · " + ToolSummarize.fmtMs(cost) +
                            "\n" + ToolSummarize.summarize(name, res), cost, !ok)
                    // 小程序创建成功 → 对话流内嵌渲染 + 画布入栈（不再弹独立页）
                    if (ok && name == "create_miniapp") {
                        val appId = args.optString("app_id", "")
                        if (appId.isNotBlank()) runCatching { onMiniApp(appId) }
                    }
                    // 执行日志：工具流水（对话模式同样可回看）
                    runCatching {
                        AgentLog.append(store.context(), "对话 · $name", listOf(
                            (if (ok) "✅ " else "❌ ") + com.ai.assistance.quro.genui.app.agent.ToolSummarize.summarize(name, res).take(200)
                                + " · " + com.ai.assistance.quro.genui.app.agent.ToolSummarize.fmtMs(cost)))
                    }.getOrDefault(Unit)
                    messages.put(JSONObject().put("role", "tool")
                        .put("tool_call_id", callId)
                        .put("content", res.toString()))
                }
            }
        } catch (e: Exception) {
            if (!cancelled) onError(e.message ?: "对话失败")
        }
    }

    /** 用流式接口生成最终回答（复用已经累积的完整上下文） */
    private suspend fun streamAnswer(provider: ModelProvider, aid: String, failedTools: List<String> = emptyList()) {
        var msgs = JSONArray()
        val all = capHistory()
        for (i in 0 until all.length()) {
            val m = all.getJSONObject(i)
            if (m.optString("role") == "system") continue
            msgs.put(m)
        }
        // —— 诚实性硬兜底（对齐 AgentLoop 渲染轮）：失败工具逐条点名 ——
        // 实测（2026-09-17 早）：7 个工具全失败后模型仍编出"11:45 实况"天气。
        // 提示词约束有漏网，回答前把失败清单拍在脸上，模型无法假装没看见。
        // 只注入本轮 msgs（发给模型），不写入持久化上下文。
        if (failedTools.isNotEmpty()) {
            msgs.put(JSONObject().put("role", "user").put("content",
                "【系统校验 · 必读】本轮以下工具调用失败，共 " + failedTools.size + " 项：\n" +
                failedTools.mapIndexed { i, e -> "${i + 1}. $e" }.joinToString("\n") +
                "\n你的回答硬性要求：①严禁虚构以上失败工具本应产生的任何数据" +
                "（天气数值/新闻/文件/通知等）；②必须明确告知用户哪些功能失败及原因" +
                "（如权限未授权、网络不可达）；③严禁使用「已保存/已发送/已获取到」等成功话术。" +
                "没有真实数据就直说拿不到，这比编一个数字诚实一万倍。"))
        }
        // 注：ZorvAI 基线的 LLMClient.chatStream 不暴露 onReasoning/onLengthCutoff 回调，
        // 故思考流与长度截断提示暂不在此处捕获（保持与基线一致，不修改共用 LLMClient）。
        val answerBuf = StringBuilder()
        // —— 流式节流（打字机聚合）：delta 每 token 一次回调 → 气泡全文 copy + 重组，
        //    万字回复时每秒数十次大字符串拷贝，主线程 GC 压力直接把 UI 拖卡
        //    （实测：回复越到后面越卡，要切页面/退出才恢复）。聚合为 100ms 一冲。
        var pendBuf = StringBuilder()
        var lastFlush = 0L
        fun flushDelta(force: Boolean) {
            if (pendBuf.isEmpty()) return
            val now = System.currentTimeMillis()
            if (force || now - lastFlush >= 100 || pendBuf.length >= 256) {
                onAssistantDelta(aid, pendBuf.toString())
                pendBuf = StringBuilder()
                lastFlush = now
            }
        }
        llm.chatStream(
            provider = provider,
            system = chatSystem(),
            messages = msgs,
            onChunk = { d ->
                answerBuf.append(d)
                pendBuf.append(d)
                flushDelta(false)
            },
            onDone = { _ ->
                flushDelta(true)
                maybeEmitA2ui(answerBuf.toString())
                onAssistantDone(aid)
                // 关键：把本轮回答写回上下文——此前模型对上一轮自己说过的话毫无记忆
                if (answerBuf.isNotBlank()) {
                    messages.put(JSONObject().put("role", "assistant")
                        .put("content", answerBuf.toString()))
                    // 对话里生成的界面同样送画布（GenUI 渲染支持）
                    runCatching { deliverMaybeUi(aid, answerBuf.toString()) }
                }
            },
            onError = { msg ->
                flushDelta(true)   // 出错也不丢已收到的内容
                onError(msg)
            }
        )
    }
}
