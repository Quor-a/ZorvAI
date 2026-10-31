package com.ai.assistance.quro.core.mika.agent

import com.ai.assistance.quro.core.mika.userscreen.UserScreenMessage

/**
 * 一次运行能不能当成「办成了」来学:个人技能和 App 知识的起草、经验路线的记录与成功计数、
 * 试用的技能和 App 知识转正式,都只问这一处。
 *
 * 看交出回答后判的结局([OutcomeJudge]),不看最终核查:交出了回答([TaskState.Succeeded])、结局是 [RunOutcome.Done]、
 * 没有退回一次后仍没对上的值;核查跑过的([TaskState.Succeeded.reviewed])还要核实通过——跑过却没通过照样挡住,没跑不挡。
 */
object LearnGate {
    fun done(end: TaskState, outcome: RunOutcome): Boolean =
        end is TaskState.Succeeded && outcome == RunOutcome.Done && end.unconfirmed.isEmpty() && !end.doubtful

    /**
     * 这次运行记不记成经验路线:开着路线、办成了([done])、有运行编号(路线从落盘的事件里取)。
     * 目标带着用户屏幕的不记:意思在屏幕上,按字记的路线下次对不上。
     */
    fun shouldSaveRoute(routesOn: Boolean, end: TaskState, outcome: RunOutcome, runId: String?, goal: String): Boolean =
        routesOn && done(end, outcome) && runId != null && !UserScreenMessage.has(goal)
}
