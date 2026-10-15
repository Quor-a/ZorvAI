package com.ai.assistance.quro.core.agent.orchestration

/**
 * 任务拆解策略（材料：规划方法——链式 / 树状 / 图状任务分解）。
 * - DIRECT    ：单意图问答 / 创作，一步可达，无需拆解。
 * - LINEAR    ：顺序多步（先…再…、步骤、一步步），按序推进。
 * - BRANCHING ：决策 / 比较 / 选型（比较、哪个、方案 A/B），需分叉评估后收敛。
 * - GRAPH     ：调研 / 分析 / 综述（调研、分析、总结、收集、报告），多源汇聚后综合。
 */
enum class StrategyKind(val label: String) {
    DIRECT("直接执行"),
    LINEAR("链式拆解"),
    BRANCHING("树状拆解"),
    GRAPH("图状拆解"),
}

/** 策划 / 规划 / 设计阶段的产物。 */
data class TaskPlan(
    /** 策划方案：目标拆解与策略。 */
    val strategy: String = "",
    /** 规划方案：步骤拆解与执行顺序（含三必答问题与重规划反思）。 */
    val plan: String = "",
    /** 设计方案：实现细节与约束。 */
    val design: String = "",
    /** 拆解出的可执行步骤（可空，空则由编排器用传入 steps 兜底）。 */
    val steps: List<TaskStep> = emptyList(),
    /** 原始规划文本（若有）。 */
    val raw: String = "",
    /** 任务拆解策略（见 [StrategyKind]）。 */
    val strategyKind: StrategyKind = StrategyKind.DIRECT,
    /** 子目标拆解提纲（本地启发式，引导模型用的「要达成什么」，不是具体工具步骤）。 */
    val decomposition: List<String> = emptyList(),
    /** 完成判据：做到什么程度算真的成了（对抗「AI 干了但没干成」）。 */
    val verification: String = "",
)

/** 单个可执行步骤。 */
data class TaskStep(
    val id: String,
    val description: String,
    val toolName: String? = null,
    val arguments: String? = null,
)
