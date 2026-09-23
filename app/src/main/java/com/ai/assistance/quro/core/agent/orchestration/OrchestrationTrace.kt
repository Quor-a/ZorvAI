package com.ai.assistance.quro.core.agent.orchestration

import com.ai.assistance.quro.core.agent.QuroAgentTrace

/**
 * 长程编排轨迹：在闭环轨迹基础上，显式标记
 * 策划 / 规划 / 设计 / 执行 / 校验 / 修正 / 交付判定 七个阶段。
 *
 * 对话框内「执行追踪」面板可据此看到：Agent 先做了策划方案、规划方案、设计方案，
 * 再进入执行-校验，最后过交付闸门——不可交付则继续编排，而不是立刻把半成品交回用户。
 * 所有发射走 tryEmit，零风险。
 */
object OrchestrationTrace {
    fun phase(tag: String, phase: TaskPhase, detail: String = "") =
        QuroAgentTrace.thought(tag, "【${phase.label}】", detail)

    fun strategize(tag: String, summary: String, detail: String = "") = phase(tag, TaskPhase.STRATEGIZE, "$summary $detail")
    fun plan(tag: String, summary: String, detail: String = "") = phase(tag, TaskPhase.PLAN, "$summary $detail")
    fun design(tag: String, summary: String, detail: String = "") = phase(tag, TaskPhase.DESIGN, "$summary $detail")
    fun execute(tag: String, summary: String, detail: String = "") =
        QuroAgentTrace.action(tag, "【执行】 $summary", detail)
    fun verify(tag: String, summary: String, detail: String = "") =
        QuroAgentTrace.result(tag, "【校验】 $summary", detail)
    fun correct(tag: String, summary: String, detail: String = "") =
        QuroAgentTrace.action(tag, "【修正】 $summary", detail)
    fun deliver(tag: String, summary: String, detail: String = "") =
        QuroAgentTrace.status(tag, "【交付判定】 $summary", detail)
}
