package com.ai.assistance.quro.core.agent.orchestration

/** 策划 / 规划 / 设计阶段的产物。 */
data class TaskPlan(
    /** 策划方案：目标拆解与策略。 */
    val strategy: String = "",
    /** 规划方案：步骤拆解与执行顺序。 */
    val plan: String = "",
    /** 设计方案：实现细节与约束。 */
    val design: String = "",
    /** 拆解出的可执行步骤（可空，空则由编排器用传入 steps 兜底）。 */
    val steps: List<TaskStep> = emptyList(),
    /** 原始规划文本（若有）。 */
    val raw: String = "",
)

/** 单个可执行步骤。 */
data class TaskStep(
    val id: String,
    val description: String,
    val toolName: String? = null,
    val arguments: String? = null,
)
