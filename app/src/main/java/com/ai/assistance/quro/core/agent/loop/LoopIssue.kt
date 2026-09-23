package com.ai.assistance.quro.core.agent.loop

/** 校验（闭环"校验"阶段）发现的一条问题。 */
data class LoopIssue(
    val code: String,
    val message: String,
    val severity: Severity,
) {
    enum class Severity { ERROR, WARN }
}
