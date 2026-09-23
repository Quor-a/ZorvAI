package com.ai.assistance.quro.core.agent.orchestration

/** 交付闸门的判定结果：任务产物是否真正可交付给用户。 */
sealed interface Deliverability {
    /** 一句话摘要（用于轨迹/诊断）。 */
    val summary: String

    /** 可交付：产物通过判定，可以交回用户。 */
    data class Deliverable(override val summary: String) : Deliverability

    /** 不可交付：不能直接交回用户，需要继续编排（重新规划/执行/修正）。 */
    data class NotDeliverable(
        override val summary: String,
        /** 不可交付的具体原因（会压回 Agent 作为修正指令）。 */
        val reason: String,
        /** 修正建议（可选）。 */
        val suggestion: String? = null,
    ) : Deliverability
}
