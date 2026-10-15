package com.ai.assistance.quro.core.agent.orchestration

/**
 * 默认策划器：**Agent 的一环，不是可选功能**。
 *
 * ## 为什么需要一个「默认」实现
 * [TaskPlanner] 此前只能由调用方注入，没注入就整段跳过——于是「策划/规划/设计」
 * 实际上从未在真实对话里跑过。它是纯 `suspend` 接口，真实实现通常由 LLM 承担，
 * 但**每次对话都额外调一次 LLM 换计划**代价太高（延迟 + token + 半数情况计划没用），
 * 所以默认走这个**纯本地、零 token、零延迟**的实现。
 *
 * ## 它做什么（2026 升级：任务拆解策略 + 子目标拆解 + 完成判据 + 重规划反思）
 * 1) 判定任务类型 → 选拆解策略（DIRECT / LINEAR / BRANCHING / GRAPH，
 *    对应材料中的链式 / 树状 / 图状任务分解）；
 * 2) 把用户指令拆成三个必答问题（目标 / 依赖 / 验证）作隐藏 system 提示首轮注入；
 * 3) 非单步任务再补一份「子目标拆解」提纲，引导模型分步推进、避免一口答偏；
 * 4) 显式给出「完成判据」，对抗「AI 干了但没干成」；
 * 5) 重规划（context 非空）时带入「反思」框架：上一轮为什么没达标、该避开的坑。
 *
 * ## 刻意不做的事
 * 不生成 [TaskStep] 步骤列表——真正的步骤拆解要靠模型看工具目录后自己决定，
 * 这里是纯本地启发式，编出来的工具步骤只会误导。步骤留给 [TaskPlan.steps] 为空。
 * 不调用 LLM、不做语义解析、不猜工具名。
 */
object DefaultTaskPlanner : TaskPlanner {

    // —— 任务类型信号词（零 token 规则判定，与 IntentRouter 同一哲学）——
    private val SEQ = listOf("先", "再", "然后", "接着", "之后", "步骤", "一步步", "依次", "最后", "按顺序")
    private val BRANCH = listOf("比较", "对比", "哪个", "选", "vs", "方案", "判断", "评估", "优缺点", "区别", "还是", "推荐")
    private val GRAPH = listOf(
        "调研", "研究", "分析", "总结", "收集", "汇总", "报告", "怎么看", "为什么",
        "梳理", "综述", "整理", "查一下", "了解", "背景", "现状",
    )

    override suspend fun plan(brief: String, context: String): TaskPlan {
        val b = brief.trim()
        if (b.isEmpty()) return TaskPlan()
        val lower = b.lowercase()

        // 1) 选策略：命中顺序信号→链式；命中决策信号→树状；命中调研信号→图状；否则直接执行。
        val kind = when {
            SEQ.any { lower.contains(it) } -> StrategyKind.LINEAR
            BRANCH.any { lower.contains(it) } -> StrategyKind.BRANCHING
            GRAPH.any { lower.contains(it) } -> StrategyKind.GRAPH
            else -> StrategyKind.DIRECT
        }
        val strategyLabel = when (kind) {
            StrategyKind.DIRECT -> "直接执行（单步可达）"
            StrategyKind.LINEAR -> "链式拆解（顺序多步）"
            StrategyKind.BRANCHING -> "树状拆解（分叉评估后收敛）"
            StrategyKind.GRAPH -> "图状拆解（多源汇聚后综合）"
        }

        // 2) 三必答问题（沿用原逻辑，作为 plan 主体）。
        val planBody = buildString {
            appendLine("执行前先确认三件事：")
            appendLine("1) 要做什么：把上面的目标复述成一句可判定完成/未完成的话。")
            appendLine("2) 依赖什么：这一步需要哪些工具或信息？工具不在手上就先说明，别假装调过。")
            appendLine("3) 怎么验证：做完用什么证据说明它真的成了（命令输出、文件内容、检索结果）。")
        }

        // 3) 子目标拆解提纲（仅非单步；纯引导，非强制步骤）。
        val decomposition = when (kind) {
            StrategyKind.DIRECT -> emptyList()
            StrategyKind.LINEAR -> linearOutline(b)
            StrategyKind.BRANCHING -> branchingOutline()
            StrategyKind.GRAPH -> graphOutline()
        }

        // 4) 完成判据（对抗「干了但没干成」）。
        val verification = when (kind) {
            StrategyKind.DIRECT -> "回答直接命中用户诉求，且不含未兑现的承诺或编造的工具调用。"
            StrategyKind.LINEAR -> "每一步的前置依赖都已满足，最终产物达到最后一步的预期，而非中途声明完成。"
            StrategyKind.BRANCHING -> "已对所有候选分叉评估并给出明确取舍结论，而非罗列选项让用户自己选。"
            StrategyKind.GRAPH -> "已综合多源信息给出有依据的结论，并标注关键来源/不确定点，而非复述单条检索结果。"
        }

        // 5) 重规划反思：context 非空＝上一轮被交付闸门打回，加入反思框架（ReAct 自我修正）。
        val reflection = if (context.isNotBlank()) {
            "\n【反思】上一轮未达标：请先想清楚根因（工具缺失 / 信息不足 / 偏离诉求），" +
                "再调整策略，不要换汤不换药地重复同样的答法。"
        } else ""

        return TaskPlan(
            strategy = "目标：$b；采用策略：$strategyLabel",
            plan = planBody.toString() + reflection,
            // design 刻意留空：本地实现没有工具目录与代码上下文，编不出可靠的设计方案。
            design = "",
            steps = emptyList(),
            raw = "",
            strategyKind = kind,
            decomposition = decomposition,
            verification = verification,
        )
    }

    /** 链式：把「先X再Y」之类的显式顺序拆成有序子目标；否则给通用顺序提纲。 */
    private fun linearOutline(b: String): List<String> {
        val parts = b.split(Regex("[,，。；;]|先|再|然后|接着|之后|最后|依次|一步步|按顺序"))
            .map { it.trim() }.filter { it.length in 2..40 }
        return if (parts.size >= 2) {
            parts.mapIndexed { i, p -> "第${i + 1}步：$p" }
        } else {
            listOf("明确起点与终点", "按顺序推进，每完成一步确认中间产物", "收尾并自检是否真的完成")
        }
    }

    /** 树状：分叉评估后收敛。 */
    private fun branchingOutline(): List<String> = listOf(
        "列出所有候选方案/选项",
        "给出每项的权衡维度（成本/风险/适用场景）",
        "逐项评估并排除明显不合适的",
        "给出明确推荐与理由，或说明在当前信息下无法判断的点",
    )

    /** 图状：多源汇聚后综合。 */
    private fun graphOutline(): List<String> = listOf(
        "明确要回答的核心问题（一句话）",
        "检索/收集相关信息（多源，优先权威）",
        "交叉验证来源，剔除矛盾或低质信息",
        "综合给出有依据的结论，标注关键来源与不确定点",
    )
}
