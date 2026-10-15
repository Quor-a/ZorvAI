package com.ai.assistance.quro.core.agent.orchestration

import com.ai.assistance.quro.core.websearch.LlmCompleter
import org.json.JSONArray
import org.json.JSONObject

/**
 * 自由编排策划器（Free / Ad-hoc Orchestration Planner）
 *
 * 与 [DefaultTaskPlanner]（零 token 本地启发式）互补，这里是**让 AI 自己现场编出它需要的步骤**——
 * 不是固化在代码里的模板，而是针对当前任务动态推理：要做什么、拆成哪些子目标、
 * 哪些子目标需要联网查证（网络查询技术）、按什么顺序执行、做到什么程度算完成。
 *
 * 这是材料里「任务拆解 + 自我迭代（反思）」的落地：本策划器**只产计划**，真正的执行
 * 由 [LongHorizonOrchestrator] 交给执行体（reactPass / web_search 步骤）完成；
 * 若交付闸门判不可交付，编排器会带着「为什么没达标」重新 plan() —— 于是下一轮 AI
 * 会**重新编排**（自由编排的闭环），直到可交付或超预算升级。
 *
 * 安全兜底：LLM 不可用 / 解析失败 / 产出空步骤 → 退化为 [DefaultTaskPlanner]，
 * 绝不因为「计划层」出错而阻断主流程或让任务空转。
 */
class FreePlanComposer(
    private val completer: LlmCompleter,
    private val fallback: TaskPlanner = DefaultTaskPlanner,
) : TaskPlanner {

    /** 自由编排里可被执行体识别的步骤类型（与 QuroAssistant 的执行体约定一致）。 */
    enum class Kind(val toolName: String) {
        /** 联网查证：arguments 为查询词，执行体调 WebSearchOrchestrator.searchSmart。 */
        WEB_SEARCH("web_search"),
        /** 综合作答：执行体跑一轮完整 ReAct（含工具调用），产出最终答复。 */
        SYNTHESIZE("synthesize"),
    }

    override suspend fun plan(brief: String, context: String): TaskPlan {
        val b = brief.trim()
        if (b.isEmpty()) return fallback.plan(brief, context)

        // 让 AI 用逻辑思维先想清楚「怎么排、排什么」，现场生成它需要的步骤。
        val prompt = buildPrompt(b, context)
        val raw = runCatching { completer.complete(SYSTEM, prompt, 700) }.getOrNull()
        if (raw.isNullOrBlank()) return fallback.plan(brief, context)

        return parse(raw, b, context) ?: fallback.plan(brief, context)
    }

    private fun buildPrompt(brief: String, context: String): String = buildString {
        appendLine("任务：$brief")
        if (context.isNotBlank()) {
            appendLine()
            appendLine("重规划上下文（上一轮未达标，请据此调整策略，不要换汤不换药）：")
            appendLine(context)
        }
        appendLine()
        appendLine("请按以下 JSON 输出你的编排方案（只输出 JSON，不要解释、不要 markdown 代码块）：")
        appendLine(
            """{
  "strategy": "DIRECT|LINEAR|BRANCHING|GRAPH",
  "decomposition": ["子目标1", "子目标2"],
  "verification": "做到什么程度算真正完成",
  "steps": [
    {"kind": "web_search", "query": "需要联网查证的具体问题"},
    {"kind": "synthesize", "note": "综合前述结果作答"}
  ]
}""",
        )
        appendLine()
        appendLine("规则：")
        appendLine("1) 需要事实/时效信息（新闻、价格、现状、他人言论、官方内容）时必须安排 web_search 步骤，查询词要具体；")
        appendLine("2) 靠已有知识就能答的不要安排 web_search，直接 synthesize；")
        appendLine("3) 最后一步必须是 synthesize；")
        appendLine("4) steps 可为空数组（纯直接作答时），但多数任务至少包含一个 synthesize。")
    }

    private suspend fun parse(raw: String, brief: String, context: String): TaskPlan? {
        val jsonText = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val obj = runCatching { JSONObject(jsonText) }.getOrNull() ?: return null

        val strategyStr = obj.optString("strategy", "DIRECT").uppercase()
        val kind = try { StrategyKind.valueOf(strategyStr) } catch (_: Throwable) { StrategyKind.DIRECT }

        val decomposition = runCatching {
            val arr = obj.optJSONArray("decomposition") ?: JSONArray()
            (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotBlank() } }
        }.getOrElse { emptyList() }

        val verification = obj.optString("verification", "").takeIf { it.isNotBlank() }
            ?: when (kind) {
                StrategyKind.DIRECT -> "回答直接命中用户诉求，且不含未兑现的承诺或编造的工具调用。"
                StrategyKind.LINEAR -> "每一步的前置依赖都已满足，最终产物达到最后一步的预期。"
                StrategyKind.BRANCHING -> "已对所有候选分叉评估并给出明确取舍结论。"
                StrategyKind.GRAPH -> "已综合多源信息给出有依据的结论，并标注关键来源/不确定点。"
            }

        // 现场编出执行步骤：web_search = 网络查询技术；synthesize = 综合作答。
        val steps = runCatching {
            val arr = obj.optJSONArray("steps") ?: JSONArray()
            val out = mutableListOf<TaskStep>()
            for (i in 0 until arr.length()) {
                val s = arr.optJSONObject(i) ?: continue
                when (s.optString("kind", "").lowercase()) {
                    "web_search" -> {
                        val q = s.optString("query", "").takeIf { it.isNotBlank() } ?: continue
                        out.add(TaskStep("web_$i", "联网查证：$q", Kind.WEB_SEARCH.toolName, q))
                    }
                    "synthesize" -> {
                        out.add(TaskStep("syn_$i", "综合作答", Kind.SYNTHESIZE.toolName, null))
                    }
                }
            }
            out
        }.getOrElse { emptyList() }

        // 安全闸门：没有任何可执行步骤时退化为 fallback（否则主流程会空转）。
        if (steps.isEmpty()) return fallback.plan(brief, context)

        val strategyLabel = when (kind) {
            StrategyKind.DIRECT -> "直接执行（单步可达）"
            StrategyKind.LINEAR -> "链式拆解（顺序多步）"
            StrategyKind.BRANCHING -> "树状拆解（分叉评估后收敛）"
            StrategyKind.GRAPH -> "图状拆解（多源汇聚后综合）"
        }

        return TaskPlan(
            strategy = "目标：$brief；采用策略：$strategyLabel（自由编排·AI 现场生成步骤）",
            plan = "由 AI 自由编排生成：先理清目标与子目标，再决定是否需要联网查证，最后综合作答。",
            design = "",
            steps = steps,
            raw = raw,
            strategyKind = kind,
            decomposition = decomposition,
            verification = verification,
        )
    }

    companion object {
        private val SYSTEM = """
            你是一个严谨的 Agent 编排策划器。给定用户任务，你要像人一样"先想清楚再动手"：
            1) 用逻辑思维把任务拆成可判定的子目标；
            2) 判断哪些子目标需要联网查证（网络查询技术），并写出具体查询词；
            3) 安排执行顺序，最后一步永远是把结果综合成最终答复。
            你不是在写答案，而是在规划"怎么排、排什么"——只输出结构化编排方案。
        """.trimIndent()
    }
}
