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
            "现在是【决策阶段】，禁止输出 HTML 文档。只做两件事之一：\n" +
            "a) 需要实时信息/记忆/设备能力 → 调用相应工具（可连续多个）；\n" +
            "b) 无任何工具需求 → 只回复四个字符：NO_TOOLS。\n" +
            "不要写界面、不要写代码、不要解释。"

        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", decisionSystem))
            .put(JSONObject().put("role", "user").put("content", userPrompt))

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
                val loopStart = System.currentTimeMillis()
                while (!cancelled) {
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
                "\n# 输出格式（务必遵守）\n" +
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

    /** 工具结果摘要（给时间线看，不塞全文） */
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
