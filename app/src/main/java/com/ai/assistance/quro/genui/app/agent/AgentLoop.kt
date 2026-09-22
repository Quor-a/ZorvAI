package com.ai.assistance.quro.genui.app.agent

import android.content.Context
import com.ai.assistance.quro.genui.app.agent.tools.ZorvToolAdapter
import com.ai.assistance.quro.genui.app.llm.LLMClient
import com.ai.assistance.quro.genui.app.llm.Prompts
import com.ai.assistance.quro.genui.app.store.GenStore
import com.ai.assistance.quro.genui.app.store.ModelProvider
import com.ai.assistance.quro.genui.app.store.Protocol
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Agent 主循环 —— ZorvAI 式"会想、会查、会记、会动手"的智能体，输出仍是 A2UI 界面。
 *
 * 两阶段设计（体验与可靠性平衡）：
 *  1) 决策轮（非流式，带 tools，仅 OpenAI 兼容协议）：模型决定查资料/读记忆/用设备能力，
 *     工具结果回填上下文，模型自行决定查几轮（不再被固定数字腰斩，靠死循环检测兜底）；若模型直接交出 HTML 则跳过渲染轮；
 *  2) 渲染轮（流式，不带 tools）：全部上下文（含工具结果）交给模型逐字写出 HTML → 画布。
 *
 * 上下文：灵魂卡（AI 自动孵化的人格）+ 记忆库索引 + 最近界面历史 + 本条指令。
 */
class AgentLoop(
    context: Context,
    private val store: GenStore,
    private val onStatus: (String) -> Unit,        // 状态行（一行摘要，保留兼容）
    private val onHtmlDelta: (String) -> Unit,     // 渲染轮流式增量
    private val onAskPermission: suspend (tool: String, brief: String, level: Int) -> Boolean = { _, _, _ -> true },
    /** 结构化事件流：思考/决策/工具全过程。UI 用它画时间线。 */
    private val onEvent: (AgentEvent) -> Unit = {}
) {
    private val appContext = context.applicationContext
    // 工具调用整体对接到 ZorvAI 的完整工具系统（buildQuroRegistry + QuroToolEngine），
    // 不再用 GenUI 私有的 BuiltinTools 小工具集。
    val tools = ZorvToolAdapter(appContext)
    private val soulStore = SoulStore(appContext)
    private val llm = LLMClient()
    /** 快速模型通道（专项模型分派里 fastProviderId 的真实落点） */
    private val fast = com.ai.assistance.quro.genui.app.llm.FastClient(store)

    @Volatile var cancelled = false
        private set
    fun cancel() { cancelled = true; llm.cancel() }

    /** 统计本次生成的工具调用次数（供 Finished 事件） */
    private var toolCallCount = 0
    private val startedAt = System.currentTimeMillis()

    /** 小程序包体总字节（filesDir/miniapps/<appId>/ 递归求和），供时间线显示真实体量。 */
    private fun miniAppBytes(appId: String): Long {
        val dir = File(com.yuanbao.miniapp.core.MiniAppEngine.userAppsRoot(appContext), appId)
        if (!dir.isDirectory) return 0L
        var n = 0L
        dir.walkTopDown().forEach { f -> if (f.isFile) n += f.length() }
        return n
    }

    /**
     * 本次生成期间新建/更新过的小程序 id（取最新的一个）。
     * 用于 create_miniapp 成功但 id 没能回传时的兜底交付——包已经在磁盘上了，
     * 不该因为一个字段缺失就让对话框里什么都没有。
     */
    /**
     * 是否为本轮的「GenUI 原生 UI」工具调用（新名 `genui_native_ui`；旧名 `create_miniapp` 作别名一起认，
     * 保证历史提示词/缓存里的旧调用不会失效）。
     */
    private fun isNativeUiTool(name: String): Boolean =
        name == "genui_native_ui" || name == "create_miniapp"

    private fun newestMiniAppSince(since: Long): String? = runCatching {
        val root = com.yuanbao.miniapp.core.MiniAppEngine.userAppsRoot(appContext)
        root.listFiles { f -> f.isDirectory && File(f, "app.json").isFile }
            ?.filter { it.lastModified() >= since - 5_000L }
            ?.maxByOrNull { it.lastModified() }
            ?.name
    }.getOrNull()

    suspend fun run(
        provider: ModelProvider,
        userPrompt: String,
        seedHtml: String? = null,
        onDone: (html: String, title: String) -> Unit,
        onDirectHtml: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        cancelled = false
        toolCallCount = 0
        onEvent(AgentEvent.Started(userPrompt, provider.name, provider.model))

        val soul = soulStore.load() ?: soulStore.fallback
        val system = Prompts.systemWith(soul, tools.memory.indexForPrompt(), recentHistory())
        // 决策轮专用提示：此阶段禁止写 HTML，只决定工具调用；否则模型会在决策轮
        // 就开始输出整页 HTML（非流式、耗时且被丢弃，导致画布空白）
        val decisionSystem = system + "\n\n# 当前阶段：工具决策（重要）\n" +
            "现在是【决策阶段】，**禁止输出任何界面代码**（HTML / WXML / 组件树 / 绘制指令都不要写），" +
            "也不要写解释性文字。只做两件事之一：\n" +
            "a) 需要实时信息 / 记忆 / 设备能力 → 调用相应工具（可连续多个）；\n" +
            "b) 全都不需要 → 只回复四个字符：NO_TOOLS。\n" +
            "本阶段只按系统提示「三、界面选择路由」判断：要不要工具、要哪些工具、要不要走小程序工具；" +
            "各形态具体怎么写，留到成稿阶段按「二、渲染落在哪里」的手册落笔。\n" +
            "★ 路由命中形态 3（GenUI 原生 UI）→ 本阶段直接调 genui_native_ui；命中形态 4（要原生桥）→ 本阶段调 miniapp(action=\"create\") 再 miniapp(action=\"run\")。\n" +
            "   **形态 3 与形态 4 二选一**：选了哪条就只走那条，两个都做会在对话流里并排出现两块画布（重复界面）。\n" +
            "   **两个工具到底哪个是哪个**（最常搞混的一处）：\n" +
            "     · genui_native_ui = **WXML/WXSS/JS 原生界面**，自研引擎直接画，没有 HTML、没有 native.* 桥；\n" +
            "     · miniapp = **HTML 小程序工作室**，WebView 跑真实网页 + window.native 原生桥，交付要 create 再 run 两步。\n" +
            "     用户说「有状态、要交互、好看」→ 形态 3；用户说「要存数据/跑SQL/加密/通知/分享/定位/拉起App」→ 形态 4。\n" +
            "     完整对照表见系统提示「二、渲染落在哪里」开头；**函数级手册与错误清单见「八、小程序手册」**——\n" +
            "     落笔前照手册写，工具被打回时报错逐条对着错误清单改（不要盲试第二次）。\n" +
            "     四条最致命（引擎事实）：① 只有 scroll-view 能滚动，超屏内容必须被 <scroll-view scroll-y style=\"height:100%\"> 包住；\n" +
            "     ② switch/checkbox/radio/slider 是**纯文本**不是控件（自绘开关）；③ input 只有单行，bindinput 仅在键盘「完成」时触发一次。\n" +
            "     ④ 视口宽高由宿主给（ZorvAI 对话框内只有约屏高 62%），且**横向没有滚动**：两列用 flex:1，别写死 50%（右侧会被裁）。\n" +
            "   这两个工具成功后交付即已完成（产物自己内嵌进对话流），**不要再跟一份 HTML 盖上去**。\n" +
            "★ 用户要『介绍你自己 / 展示你能做什么』→ 用 genui_native_ui 渲染自我介绍界面，严禁文字直答。"

        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", decisionSystem))
            .put(JSONObject().put("role", "user").put("content", userPrompt))

        // 路由直通：交互类应用关键词命中 → 注入确定指令，不走犹豫的形态选择。
        // 小程序渲染在对话流里（用户主交互面），是 GenUI 的第一等交付形态——HTML 只是展示型页面的备选。
        var miniAppDelivered: String? = null
        /** 本轮第一次调 create_miniapp 的时间戳（用于"包落盘了但没拿到 id"的最后兜底交付）。 */
        var createdMiniAppAt: Long? = null
        /** 需要原生桥接的小程序走 ZorvAI 小程序工作室（miniapp run）或任意返回完整 HTML 文档的
         *  可视化工具产物：捕获后内嵌对话流渲染，跳过 HTML 渲染轮（与 miniAppDelivered 互斥）。 */
        var studioHtmlDelivered: String? = null
        /** 与 [studioHtmlDelivered] 配套的卡片标题（交付事件延后到工具循环结束才发，标题要先留住）。 */
        var studioHtmlTitle: String? = null
        /** 模型用 miniapp(action="create"/"write") 落地过的工作室工程名（用于"只 create 没 run"的兜底补跑）。 */
        var studioProject: String? = null
        val wantsMini = wantsMiniApp(userPrompt)
        val wantsHtml = wantsHtmlPage(userPrompt)
        val wantsNative = wantsNativeCapability(userPrompt)
        if (wantsNative && !wantsHtml) {
            // 路由表里"要原生能力"排在"有状态+频繁交互"之前 → 原生需求优先落形态 4（工作室 + native.* 桥）。
            messages.put(JSONObject().put("role", "system").put("content",
                "【路由直通：形态 4 小程序工作室】这条需求要调原生能力（storage/ui/device/network/router/kotlin/aci/crypto/db/location），" +
                "按路由表落在小程序工作室，两步都要做完：\n" +
                "1) miniapp(action=\"create\", name=\"英文短名\", files=[{path:\"app.json\",content:\"…\"}," +
                "{path:\"pages/index/index.html\",content:\"<!DOCTYPE html>…\"}]) 落地工程；\n" +
                "2) miniapp(action=\"run\", name=\"英文短名\", entry=\"pages/index/index.html\") 取回自包含 HTML。\n" +
                "run 的产物会自动内嵌进对话流（已注入 window.native），这就是交付——不要再自己另写一份 HTML 盖上去。\n" +
                "（若用户原话明确要求用 HTML/网页实现，则忽略本条，回复 NO_TOOLS 走形态 1。）\n"))
            onEvent(AgentEvent.Thinking("路由直通：形态 4 小程序工作室"))
        } else if (wantsMini && !wantsHtml) {
            messages.put(JSONObject().put("role", "system").put("content",
                "【路由直通：形态 3 GenUI 原生 UI】这条需求按路由表落在原生界面（直接内嵌对话流，用户可直接试玩）。\n" +
                "本阶段就调 genui_native_ui：app_id + title + files，files 必须给全 " +
                "app.json / app.js / app.wxss / pages/index/index.{wxml,wxss,js}，真实逻辑 + 真实状态，尺寸全用 rpx。\n" +
                "工具成功后交付即完成（产物自己内嵌进对话流），**不要再回复任何 HTML 或文字**。\n" +
                "不要调用 open_miniapp——只有用户明确说『全屏打开』时才调。\n" +
                "（若用户原话明确要求用 HTML/网页实现，则忽略本条，回复 NO_TOOLS 走形态 1。）\n"))
            onEvent(AgentEvent.Thinking("路由直通：形态 3 小程序"))
        }

        // 快速模型预检（专项模型分派的真实用途之一）：这条指令要不要先联网？
        // 失败/未配置时静默跳过，绝不阻塞主流程。
        if (!isPrefetchSkipped(userPrompt)) {
            onEvent(AgentEvent.Thinking("读取意图…"))
            runCatching { fast.preflight(userPrompt) }.getOrNull()?.let { hint ->
                onStatus("意图预检 · 建议联网")
                onEvent(AgentEvent.Thinking("判断出这条指令可能要查实时信息"))
                messages.put(JSONObject().put("role", "system").put("content", "# 预检提示\n$hint"))
            }
        }

        val loopStart = System.currentTimeMillis()
        try {
            // ---------- 决策轮（带工具，OpenAI 兼容协议才支持 function calling） ----------
            if (provider.protocol == Protocol.OPENAI) {
                val decls = tools.declarations()
                // 关键立场：工具调用【不再被固定轮数腰斩】。模型想查多少轮就查多少轮，
                // 自己会靠 NO_TOOLS / 直接成稿收尾。maxToolRounds 现在只是【软提醒阈值】——
                // 超过后轻推一次、绝不强制中断；0 = 完全不限制。
                // 唯一的硬停止只有两道"意外兜底"安全闸，二者都只拦失控、不拦正常调研：
                //   ① 死循环检测：累计重复调用"完全相同的工具+参数"（拿不到新信息、在空转）；
                //   ② 很宽松的总时长兜底：防模型无限调不同工具把一次生成挂死。
                val roundNudge = provider.maxToolRounds   // 0 = 不限制
                var rounds = 0
                val seenSigs = LinkedHashSet<String>()
                var redundant = 0
                while (!cancelled && studioHtmlDelivered == null) {
                    rounds++
                    onEvent(AgentEvent.Thinking(
                        if (rounds == 1) "分析指令，判断需不需要查资料或调用设备能力…"
                        else "根据上一步结果，继续判断还缺什么…"
                    ))
                    // 软提醒（非强制，只触发一次）：超阈值后请模型自行评估是否该收尾
                    if (roundNudge > 0 && rounds == roundNudge + 1) {
                        messages.put(JSONObject().put("role", "user").put("content",
                            "你已连续调用约 $roundNudge 轮工具。若信息已足够，请停止调用工具、直接输出完整 HTML 文档；若确实还缺关键数据，可继续。"))
                    }
                    val assistant = llm.chatOnce(provider, messages, decls)
                    val calls = assistant.optJSONArray("tool_calls")
                    val content = assistant.optString("content").orEmpty()

                    if (calls == null || calls.length() == 0) {
                        if (content.isNotBlank() && content.contains("<!DOCTYPE", ignoreCase = true)) {
                            // 决策轮直接交出 HTML（罕见）：流式写入画布后完成，不丢内容
                            onStatus("直接出稿 · ${kb(content.length)}")
                            onEvent(AgentEvent.Decided(0, "不需要工具，直接成稿"))
                            onHtmlDelta(content)
                            onDone(content, extractTitle(content))
                            return
                        }
                        // 决策轮里模型可能说一些"数据已确认，开始落稿"之类的话——
                        // 如果它不是 HTML，不能作为 assistant 消息继续；否则模型进入渲染轮后
                        // 会顺着胡说，最终只写出一行字就停。把它转成一条 user 备注即可。
                        if (content.isNotBlank()) {
                            messages.put(JSONObject()
                                .put("role", "user")
                                .put("content", "（模型刚才的说明，不必重复）\n$content\n\n现在请直接输出完整 HTML 文档，不要任何解释。"))
                        }
                        onEvent(AgentEvent.Decided(0, "信息已足够，开始写界面"))
                        break   // 进入渲染轮
                    }

                    // 死循环检测：把本轮所有工具调用的"名称|参数"拼成一个签名。
                    // 若与之前某轮完全相同 → 没拿到新信息、在空转；累计到阈值就温柔收尾。
                    val sig = buildString {
                        for (i in 0 until calls.length()) {
                            val fn = calls.getJSONObject(i).optJSONObject("function") ?: continue
                            append(fn.optString("name")).append('|')
                                .append(fn.optString("arguments").trim()).append(';')
                        }
                    }
                    if (seenSigs.contains(sig)) {
                        redundant++
                        if (redundant >= REDUNDANT_LIMIT) {
                            onEvent(AgentEvent.Thinking("检测到工具调用陷入重复，改用已有信息成稿"))
                            messages.put(JSONObject().put("role", "user").put("content",
                                "你已重复调用完全相同的工具且未获得新信息，请停止调用工具，直接基于已有信息输出完整 HTML 文档。必须以 <!DOCTYPE html> 开头、</html> 结尾，不要只写一句话。"))
                            break
                        }
                    } else {
                        seenSigs.add(sig)
                    }

                    onEvent(AgentEvent.Decided(calls.length(), "需要 ${calls.length()} 项能力"))

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
                        val callId = call.optString("id", "call_${rounds}_$i")
                        val args = runCatching { JSONObject(fn.optString("arguments").ifBlank { "{}" }) }
                            .getOrDefault(JSONObject())
                        val gate = tools.gateFor(name)
                        val level = ToolGate.level(gate)
                        val briefText = brief(args)

                        onStatus("调用工具：$name $briefText")
                        // 若该工具策略是"每次询问"，先广播等待授权事件
                        if (tools.gate.modeOf(gate) == ToolGate.AuthMode.ALWAYS_ASK) {
                            onEvent(AgentEvent.ToolAwaitingAuth(callId, name, briefText, level))
                        }
                        // 广播"开始执行"：UI 靠它把时间线条目置为 running，并在完成时回填耗时。
                        // （此前只有 ToolFinished，条目永远停在"运行中"，也看不到参数与结果）
                        onEvent(AgentEvent.ToolStarted(callId, name, briefText, level, rounds))
                        toolCallCount++

                        val t0 = System.currentTimeMillis()
                        val result = executeTool(name, args)
                        val cost = System.currentTimeMillis() - t0

                        val denied = result.has("denied")
                        val hasError = result.has("error")
                        val summary = when {
                            denied -> result.optString("denied")
                            hasError -> result.optString("error")
                            else -> summarizeToolResult(name, result)
                        }
                        onEvent(
                            if (denied) AgentEvent.ToolDenied(callId, name, summary)
                            else AgentEvent.ToolFinished(callId, name, cost, !hasError, summary)
                        )
                        // GenUI 小程序创建成功 → 记下 app_id（**先不广播**：交付事件统一在本轮工具循环
                        // 结束后只发一次，见下方"一轮只交付一件"。此前在工具循环里就地 onEvent，
                        // 于是模型只要又调了工作室（或再 create 一次），对话流里就并排出现两块画布）
                        if (isNativeUiTool(name) && !result.has("error")) {
                            if (createdMiniAppAt == null) createdMiniAppAt = System.currentTimeMillis()
                            // app_id 优先取返回体；万一模型/适配器只回了 created 也能认。
                            // 只认 "ok" 一个字段太脆：任一环节漏掉 ok，交给 AI 的东西就进不了对话框。
                            val aid = result.optString("app_id")
                                .ifBlank { result.optString("created") }
                            // 后一次 create 覆盖前一次：模型"先建后改"时，用户要看到的是最终那一个
                            if (aid.isNotBlank()) miniAppDelivered = aid
                        }
                        // ZorvAI 小程序工作室（miniapp run）或返回完整 HTML 文档的可视化工具产物：
                        // 捕获自包含 HTML（同样先不广播），内嵌对话流渲染，跳过 HTML 渲染轮。
                        if (studioHtmlDelivered == null) {
                            val rh = extractRenderableHtml(name, result)
                            if (rh != null) {
                                studioHtmlDelivered = rh
                                studioHtmlTitle = studioTitleOf(name)
                            }
                        }
                        // 记下工作室工程名：模型只 create 忘了 run 时，下面替它补一步。
                        if (name == "miniapp") {
                            val act = args.optString("action")
                            val nm = args.optString("name")
                            if (nm.isNotBlank() && (act == "create" || act == "write")) studioProject = nm
                        }
                        onStatus("工具完成：$name · ${kb(result.toString().length)}")
                        messages.put(JSONObject()
                            .put("role", "tool")
                            .put("tool_call_id", callId)
                            .put("content", result.toString()))
                    }

                    // 总时长兜底（很宽松，正常任务远到不了）：防模型无限调不同工具把生成挂死
                    if (System.currentTimeMillis() - loopStart > HARD_TIME_MS) {
                        onEvent(AgentEvent.Thinking("已达安全时长上限，用现有信息成稿"))
                        messages.put(JSONObject().put("role", "user").put("content",
                            "本次生成已达安全时长上限。请停止调用工具，直接基于已有信息输出完整 HTML 文档。"))
                        break
                    }
                }
            }

            // ---------- 渲染轮（流式出 HTML） ----------
            if (cancelled) return
            // ★ 兜底交付：模型只 create 了工作室工程、忘了 run → 替它把 run 补上。
            // 少了这一步，工程躺在磁盘上，对话框里什么东西都不会出现（"AI 没有写进对话框"）。
            if (studioHtmlDelivered == null && miniAppDelivered == null && studioProject != null) {
                val nm = studioProject!!
                onEvent(AgentEvent.Thinking("补齐一步：运行小程序工作室工程 $nm"))
                val r = runCatching {
                    executeTool("miniapp", JSONObject().put("action", "run").put("name", nm))
                }.getOrNull()
                val html = r?.let { extractRenderableHtml("miniapp", it) }
                if (html != null) {
                    studioHtmlDelivered = html
                    studioHtmlTitle = "小程序工作室"
                }
            }
            // ★ 最后一道交付兜底：这一轮确实调过 create_miniapp、包也已落盘，却因为某些环节
            // （返回体里没带 app_id / 事件被吞）没拿到 id → 直接从 filesDir/miniapps 里挑本次
            // 新建的那个包交付。目标是：只要 AI 真的生成了小程序，对话框里就一定有它。
            if (miniAppDelivered == null && createdMiniAppAt != null) {
                val newest = newestMiniAppSince(createdMiniAppAt!!)
                if (newest != null) {
                    miniAppDelivered = newest
                    onStatus("小程序已落盘：$newest")
                }
            }
            // ★★ 一轮只交付一件（双画布的唯一开关）★★
            // 本轮的交付事件在这里、且只在这里发一次：原生小程序（形态 3）优先，没有才回落到
            // 工作室 HTML（形态 4）。此前"谁先被调用谁先广播"，模型同一轮既 create_miniapp 又
            // run 工作室时，对话流里就会并排出现两块画布（用户报的"双画布"）。
            if (miniAppDelivered != null) {
                onEvent(AgentEvent.MiniAppCreated(miniAppDelivered!!))
            } else if (studioHtmlDelivered != null) {
                onEvent(AgentEvent.StudioMiniApp(studioHtmlDelivered!!, studioHtmlTitle ?: "小程序工作室"))
            }
            // ★ 小程序画布交付：create_miniapp 已成功 → 不再走 HTML 渲染轮。
            // 可交互小程序已通过 MiniAppCreated 事件内嵌渲染到对话流；这里只标记完成，
            // 避免模型紧接着又吐一份垃圾 HTML 盖在交互小程序上。
            if (miniAppDelivered != null) {
                val id = miniAppDelivered
                val elapsed = System.currentTimeMillis() - loopStart
                // 用真实包体大小：原来这里写死 0，时间线会显示「0KB · N 次工具调用」，
                // 看起来像"什么都没生成"（用户据此判断小程序是空的，实际不是）。
                onEvent(AgentEvent.Finished("小程序 · $id", miniAppBytes(id), toolCallCount, elapsed))
                return
            }
            // ★ 小程序工作室 / 可视化交付：miniapp run 或可视化工具已返回自包含 HTML → 内嵌对话流渲染，
            // 不走 HTML 渲染轮（避免模型又吐一份盖在上头）。
            if (studioHtmlDelivered != null) {
                val elapsed = System.currentTimeMillis() - loopStart
                onEvent(AgentEvent.Finished("小程序工作室 · 预览", 0, toolCallCount, elapsed))
                return
            }
            onStatus("写界面 · 流式渲染中")
            onEvent(AgentEvent.Rendering(if (!seedHtml.isNullOrBlank()) "接着已中断的部分继续写…" else "开始绘制界面…"))


            // 渲染轮：把 tool 消息折成 user 备注（避免部分端点要求 tool/tool_calls 严格配对）
            val renderMsgs = JSONArray()
            for (i in 0 until messages.length()) {
                val m = messages.getJSONObject(i)
                val role = m.optString("role")
                if (role == "tool") {
                    renderMsgs.put(JSONObject().put("role", "user")
                        .put("content", "[工具结果] " + m.optString("content").take(8000)))
                } else {
                    val c = if (m.isNull("content")) "" else m.optString("content")
                    if (c.isBlank() && role != "system") continue
                    renderMsgs.put(JSONObject().put("role", role).put("content", c))
                }
            }
            // —— 续写模式：画布上已有上次留下的部分 HTML ——
            // 只给模型【尾部片段】而不是全文，避免把几万字符塞进上下文（既贵又慢）；
            // 尾部足够让它判断"写到哪儿了、接下来该写什么"。
            val seed: String? = seedHtml?.takeIf { it.isNotBlank() }
            val isContinuation = seed != null
            val tailLimit = provider.contextChars.coerceIn(500, 8000)
            val tail = seed?.takeLast(tailLimit).orEmpty()
            val closingTagHint = if (isContinuation)
                "你要做的是【续写】：上面是已写入画布的部分文档（只展示尾部）。" +
                "直接从它中断的地方接着往下写，绝对不要重复任何已有内容，" +
                "不要重新输出 <!DOCTYPE html> / <html> / <head> / 已有的 <style> 与 <script>。" +
                "补完剩余的 DOM 与脚本后，用 </body></html> 结束。"
            else
                "基于以上全部信息，现在输出最终界面。只输出以 <!DOCTYPE html> 开头的完整 HTML 文档，不要任何解释。"

            if (isContinuation) {
                renderMsgs.put(JSONObject().put("role", "user")
                    .put("content", "【已在画布上的文档尾部片段】\n```\n" + tail + "\n```\n\n" + closingTagHint))
            } else {
                renderMsgs.put(JSONObject().put("role", "user").put("content", closingTagHint))
            }

            val sb = StringBuilder()
            /** 续写时交付"种子 + 增量"，否则就是增量本身；统一在此剥掉 Markdown 围栏 */
            fun deliver(): String =
                if (seed != null) stripFences(seed + sb.toString()) else stripFences(sb.toString())
            // 绘制过程探针：从流式增量里实时解析"正在画什么"（真实观测，非假进度）
            val probe = PaintProbe()
            // 部分模型即使在提示词里被禁止，仍会顺手把 HTML 包在 ```html ... ``` 里。
            // 这个过滤器只在【开头】剥一次围栏：一旦确认不是围栏就原样放行后续所有内容，
            // 绝不把正常 HTML 当成围栏缓冲而丢弃（v0.11.9 黑屏回归的根因正是旧版有状态
            // stripper 把正文误判成围栏首部、持续返回空、最终吞掉整段输出）。结尾围栏在 deliver() 收口。
            val fenceFilter = LeadingFenceFilter()
            // 渲染轮 system = 基座 + 灵魂（说话层+视觉签名层）：视觉签名只在此轮注入，影响 UI 美学方向
            val renderSystem = system + Soul.injectStyle(soul) +
                "\n# 当前阶段：成稿（按路由落笔）\n" +
                "本阶段不再调工具。先按系统提示「三、界面选择路由」定形态，再照「二、渲染落在哪里」的手册写：\n" +
                "· 形态 1 → 一次写完整 HTML；要系统原生控件质感就把形态 2 的块（<!--stack:compose--> 等）嵌进**同一个文档**。\n" +
                "· 形态 5（路由判定为纯文本）→ 直接回文字答案，不要套下面的 HTML 契约与质量自检。\n" +
                "\n# 输出格式（遵守即通过）\n" +
                "直接输出写入 WebView 画布的原始 HTML 文档：以 <!DOCTYPE html> 开头、以 </html> 结尾。" +
                "不要使用 Markdown 代码块包裹（不要写 ```html 或 ```），也不要写任何解释性文字——只输出纯 HTML。\n" +
                "\n# 质量自检（输出前心里过一遍）\n" +
                "界面至少包含：清晰的层级标题、真实密度的内容、一处数据可视化（图表/进度/徽标任选）、" +
                "至少一个可交互反馈（按钮按压态/状态切换/过渡动画）、内联 SVG 图标至少两枚。\n" +
                (if (isContinuation) "# 当前是续写任务\n你正在补完一个已被中断的文档，只输出新增部分，不要重复已有内容。\n" else "")
            llm.chatStream(
                provider = provider,
                system = renderSystem,
                messages = renderMsgs,
                onChunk = { delta ->
                    val cleanDelta = fenceFilter.feed(delta)
                    sb.append(cleanDelta)
                    onHtmlDelta(cleanDelta)
                    // 绘制过程：解析出的每个里程碑都是一条真实事件，UI 直接呈现"正在画什么"
                    probe.feed(cleanDelta, deliver()).forEach(onEvent)
                    // 体量里程碑：作为兜底进度（结构无明显变化的长文档里仍有心跳）
                    val n = sb.length
                    if (n / 4096 > lastMilestone) {
                        lastMilestone = n / 4096
                        onEvent(AgentEvent.RenderProgress(n.toLong()))
                    }
                },
                onDone = { _ ->
                    val html = deliver()
                    if (html.isBlank()) {
                        // 模型跑完若干轮思考却没吐出任何 HTML：绝不静默黑屏，明确报错让用户重试
                        onError("模型未输出任何界面内容（接口返回为空，或提示词过严导致模型困惑）。请重试，或换一种说法 / 换个模型。")
                    } else {
                        onEvent(AgentEvent.Finished(
                            title = extractTitle(html),
                            bytes = html.length.toLong(),
                            toolCalls = toolCallCount,
                            elapsedMs = System.currentTimeMillis() - startedAt
                        ))
                        onDone(html, extractTitle(html))
                    }
                },
                onError = { msg ->
                    if (deliver().isBlank()) throw RuntimeException(msg)
                    val html = deliver()
                    onEvent(AgentEvent.Finished(
                        title = extractTitle(html),
                        bytes = html.length.toLong(),
                        toolCalls = toolCallCount,
                        elapsedMs = System.currentTimeMillis() - startedAt
                    ))
                    onDone(html, extractTitle(html))   // 有部分内容仍交付
                }
            )
        } catch (e: Exception) {
            if (!cancelled) {
                val msg = e.message ?: "Agent 执行失败"
                onEvent(AgentEvent.Failed(msg, 0))
                onError(msg)
            }
        }
    }

    // ---------- internals ----------

    /** 已播报的 4KB 里程碑数（渲染进度去抖） */
    private var lastMilestone = 0

    /** 续写/追问这类明确不需要联网的短指令，跳过预检省一次请求 */
    private fun isPrefetchSkipped(prompt: String): Boolean {
        if (prompt.length < 4) return true
        return prompt.contains("继续") || prompt.contains("接着写") || prompt.contains("补完")
    }

    /** 交互类应用关键词 → 小程序画布直通（与上游 GenUI 一致的关键词表） */
    private fun wantsMiniApp(prompt: String): Boolean {
        val kws = listOf(
            "记账", "账本", "待办", "清单", "todo", "TODO", "计算器", "番茄钟", "倒计时", "秒表",
            "计时", "打卡", "签到", "记事", "日记", "笔记", "备忘", "换算", "抽签", "骰子",
            "随机数", "小游戏", "密码生成", "bmi", "BMI", "小工具", "小程序", "习惯", "存钱", "预算")
        return kws.any { prompt.contains(it, ignoreCase = true) } ||
            listOf("你自己", "自我介绍", "介绍自己", "你是谁", "介绍下你", "介绍一下你",
                "你的能力", "你能做什么", "展示一下你").any { prompt.contains(it) }
    }

    /** 信息展示类关键词（或用户点名 html/web）→ HTML 画布。用户点名永远最高优先。 */
    private fun wantsHtmlPage(prompt: String): Boolean {
        val kws = listOf(
            "html", "HTML", "Html", "web 页", "web页", "web app", "WebApp",
            "新闻", "资讯", "文章", "报告", "仪表盘", "看板", "图表", "数据可视化",
            "落地页", "官网", "介绍页", "网页", "网页版", "图文", "海报", "简历", "专题")
        return kws.any { prompt.contains(it, ignoreCase = true) }
    }

    /**
     * 需要原生能力的意图 → 形态 4（小程序工作室 + native.* 桥）。
     * 路由表里这条排在"有状态+频繁交互"之前：同样是待办，要"存到本地/发通知"就该走工作室。
     */
    private fun wantsNativeCapability(prompt: String): Boolean {
        val kws = listOf(
            "震动", "振动", "发通知", "系统通知", "通知栏", "分享到", "分享给", "分享按钮",
            "剪贴板", "复制到", "定位", "当前位置", "获取位置", "地图标记",
            "数据库", "sql", "SQL", "sqlite", "SQLite", "加密", "md5", "sha256",
            "存到本地", "保存到本地", "本地存储", "持久化", "离线保存",
            "跳转页面", "多页面", "页面跳转", "子页面", "返回上一页",
            "打开其他应用", "打开应用", "启动应用")
        return kws.any { prompt.contains(it, ignoreCase = true) }
    }

    /** 工具结果摘要（给时间线看，不塞全文） */
    /**
     * 从工具结果里抽取"可直接内嵌对话流渲染的自包含 HTML"。
     * 触发条件（与上游 ZorvAI 小程序工作室 / 可视化工具对齐）：
     *  - 小程序工作室 miniapp 的 run 返回完整 <!DOCTYPE html> 文档（含 native.* 桥接）；
     *  - 其他工具若返回以 <!DOCTYPE 开头的完整 HTML 文档也一并内联渲染（覆盖创意工作室等可视化工具）。
     * 注意：ZorvToolAdapter.execute 对"非 JSON 文本"会按 looksFailed 包成 {error} 或 {result}，
     * 因此两个字段都要试；只有"明显是完整 HTML 文档"才认，避免把普通错误文本当 HTML 渲成白屏。
     */
    private fun extractRenderableHtml(name: String, result: JSONObject): String? {
        val candidate = result.optString("result").ifBlank { result.optString("error").ifBlank { null } }
            ?: return null
        val t = candidate.trimStart()
        val isDoc = t.startsWith("<!doctype", ignoreCase = true) ||
                (name == "miniapp" && t.startsWith("<html", ignoreCase = true))
        if (!isDoc) return null
        return candidate
    }

    /** 工作室 / 可视化内嵌卡的中文标题。 */
    private fun studioTitleOf(name: String): String =
        if (name == "miniapp") "小程序工作室" else "可视化预览"

    /** 把工具返回压缩成一行人类可读摘要，用于思考时间线。 */
    private fun summarizeToolResult(name: String, r: JSONObject): String = runCatching {
        when {
            r.has("error") -> "失败：${r.optString("error").take(60)}"
            name == "web_search" -> buildString {
                append(r.optJSONArray("results")?.length() ?: 0).append(" 条结果")
                val eng = r.optString("engines")
                if (eng.isNotBlank()) append(" · ").append(eng)
                val pf = r.optJSONArray("partial_failures")
                if (pf != null && pf.length() > 0) append("（部分引擎失败）")
            }
            name == "web_fetch" -> buildString {
                append("读到 ").append(r.optInt("chars")).append(" 字")
                if (r.optBoolean("truncated")) append("（已截断）")
                val ct = r.optString("content_type")
                if (ct.isNotBlank()) append(" · ").append(ct)
            }
            name == "file_list" -> "共 ${r.optInt("count")} 个文件"
            name == "contacts_search" -> "找到 ${r.optInt("count")} 位联系人"
            r.has("results") -> "${r.optJSONArray("results")?.length() ?: 0} 条结果"
            r.has("count") -> "共 ${r.optInt("count")} 条"
            r.has("content") -> "读到 ${r.optString("content").length} 字"
            r.has("text") -> r.optString("text").take(48)
            r.has("value") -> "已取回数据"
            r.has("ok") -> "完成"
            else -> r.toString().take(56)
        }
    }.getOrDefault("完成")

    private suspend fun executeTool(name: String, args: JSONObject): JSONObject {
        // 授权按"工具族"判定：memory_write 走 memory 的策略（权限屏设置的就是族名）。
        // 注意必须传族名给 gate，否则用户对 memory 设置的策略对 memory_write 不生效。
        val family = tools.gateFor(name)
        val verdict = try {
            tools.gate.authorize(family, brief(args)) { tool, briefArg, level ->
                onAskPermission(tool, briefArg, level)
            }
        } catch (e: Exception) {
            "授权流程异常：${e.message}"
        }
        if (verdict != null) {
            return JSONObject().put("denied", verdict)
        }
        // 超时保护：单个工具最长 40 秒，避免一个卡住的工具拖死整个生成
        val result = try {
            kotlinx.coroutines.withTimeout(TOOL_TIMEOUT_MS) { tools.execute(name, args) }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            JSONObject().put("error", "工具执行超时（${TOOL_TIMEOUT_MS / 1000}秒），已跳过。可换一种方式或稍后重试。")
        } catch (e: Exception) {
            JSONObject().put("error", humanizeToolError(name, e.message ?: "工具执行失败"))
        }
        return result
    }

    /**
     * 把工具抛出的原始异常转成模型能据以调整、用户能看懂的说明。
     * 注意：权限类文案已由 PermRegistry 生成（含"去哪个屏开哪个开关"），此处只补充
     * "模型该怎么应对"的指令，不再重复解释——重复会让同一句话出现两遍。
     */
    private fun humanizeToolError(name: String, raw: String): String = when {
        raw.contains("权限") || raw.contains("permission", true) || raw.contains("需要「") ->
            "$raw\n（模型注意：不要反复重试同一工具，改用不需要该权限的方案，或请用户先开启权限。）"
        raw.contains("网络不可达") || raw.contains("Unable to resolve host", true) ||
            raw.contains("连接超时") || raw.contains("SocketTimeout", true) ->
            "网络不可用或超时。\n（模型注意：可改用已有知识作答，并如实说明未能联网核实。）"
        name == "web_search" && raw.contains("所有搜索引擎") ->
            "$raw\n（模型注意：换更通用或更短的关键词再试一次；仍失败就用已知信息作答并说明。）"
        raw.contains("不存在") || raw.contains("未安装") -> raw
        else -> "$name 执行失败：$raw"
    }

    private fun recentHistory(): String {
        val pages = store.loadPages().takeLast(5)
        if (pages.isEmpty()) return "（这是本次会话第一次生成）"
        val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)
        return "最近生成过的界面（新→旧）：\n" + pages.reversed().joinToString("\n") { p ->
            "· ${fmt.format(Date(p.ts))} 「${p.title}」"
        }
    }

    private fun extractTitle(html: String): String =
        Regex("<title>(.*?)</title>", RegexOption.DOT_MATCHES_ALL)
            .find(html)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotBlank() }
            ?: ("界面 · " + SimpleDateFormat("HH:mm", Locale.CHINA).format(Date()))

    private fun brief(a: JSONObject): String = runCatching {
        a.keys().asSequence().take(2).joinToString(" ") { k ->
            "$k=" + (a.opt(k)?.toString() ?: "").take(24)
        }
    }.getOrDefault("")

    private fun kb(n: Int): String = String.format(Locale.US, "%.1fKB", n / 1024.0)

    /**
     * 去掉模型偶尔顺手包在 HTML 外的 Markdown 代码围栏（开头 ```html / 结尾 ```）。
     * 只动"最外层"的围栏：开头一个、结尾一个，文档内部的 ```（如 <script> 里的示例）不受影响。
     * 这是最终收口，配合 [LeadingFenceFilter] 的流式开头剥离，双保险。
     */
    private fun stripFences(raw: String): String {
        var s = raw.trimStart('\uFEFF')
        val lead = Regex("^\\s*```[a-zA-Z0-9_+#-]*\\s*\\n?", RegexOption.DOT_MATCHES_ALL)
        s = lead.replaceFirst(s, "")
        val trail = Regex("\\n?```\\s*$", RegexOption.DOT_MATCHES_ALL)
        s = trail.replaceFirst(s, "")
        return s
    }

    /**
     * 流式写入画布前剥离【开头的】Markdown 围栏。
     *
     * 只判定一次：遇到开头的 ```html / ``` 就剥掉并放行后续；一旦确认不是围栏
     *（开头不是反引号）或缓冲超过上限，立即原样放行——绝不会把正常 HTML 当成围栏缓冲丢弃
     *（这正是 v0.11.9 黑屏回归的根因）。结尾围栏由 [stripFences] 在 deliver() 统一收口。
     */
    private class LeadingFenceFilter {
        private var resolved = false
        private val buf = StringBuilder()

        fun feed(chunk: String): String {
            if (resolved) return chunk
            buf.append(chunk)
            val s = buf.toString()
            val fence = Regex("^\\s*```[a-zA-Z0-9_+#-]*\\s*\\n?", RegexOption.DOT_MATCHES_ALL)
            val m = fence.find(s)
            if (m != null) {
                resolved = true
                buf.clear()
                return s.substring(m.range.last + 1)
            }
            // 已能判定不是围栏：去掉前导空白后既非空、也不以反引号开头 → 直接放行缓冲内容
            val trimmed = s.trimStart()
            if (trimmed.isNotEmpty() && !trimmed.startsWith("`")) {
                resolved = true
                val out = buf.toString()
                buf.clear()
                return out
            }
            // 防无限缓冲：超过 256 字符还没形成围栏，直接放行（正常 HTML 远到不了这长度）
            if (s.length > 256) {
                resolved = true
                val out = buf.toString()
                buf.clear()
                return out
            }
            // 仍在判定中（可能是围栏的一部分，或纯空白前导）：暂不放行
            return ""
        }
    }

    companion object {
        /** 单个工具执行的超时上限：一个卡住的工具不该拖死整个生成。
         *  web_search 是多引擎串行探测（最多 3 引擎 × 2 次重试），给足余量避免误判超时。 */
        private const val TOOL_TIMEOUT_MS = 75_000L
        /** 死循环检测：累计出现多少次"完全相同的工具调用签名"才温柔收尾（不拦正常调研）。 */
        private const val REDUNDANT_LIMIT = 3
        /** 总时长兜底：单轮生成决策阶段最多跑这么久（很宽松，只防挂死，正常任务远到不了）。 */
        private const val HARD_TIME_MS = 20 * 60_000L
    }
}

/**
 * 一次待裁决的实时授权请求（L3/L4 工具调用时由 UI 弹卡）。
 * [continuation] 由授权卡的三颗按钮恢复：本次允许=true / 拒绝=false / 永久允许=gate 记忆后 true。
 */
class PermRequest(
    val tool: String,
    val brief: String,
    val level: Int,
    val continuation: kotlinx.coroutines.CancellableContinuation<Boolean>
) {
    fun complete(allow: Boolean) {
        try {
            continuation.resumeWith(kotlin.Result.success(allow))
        } catch (_: Exception) {
            // 重复 resume 会抛异常：弹窗可能被"允许"与"关闭"两条路径各调用一次，
            // 这里静默吞掉即可，属于预期内的幂等保护
        }
    }
}
