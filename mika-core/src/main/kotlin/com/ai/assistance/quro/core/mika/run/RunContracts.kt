package com.ai.assistance.quro.core.mika.run

import com.ai.assistance.quro.core.mika.action.ConfirmChoice
import com.ai.assistance.quro.core.mika.agent.Turn

@JvmInline value class RunId(val value: String) {
    init { require(value.isNotBlank()) }
    override fun toString(): String = value
}

/** 一次运行的不可变开局信息。后续步骤会补能力、资源预算与审批快照。 */
data class RunSpec(
    val id: RunId,
    val goal: String,
    val threadId: String,
    val priorTurns: List<Turn> = emptyList(),
    val provenance: RunProvenance = RunProvenance(),
    /** null 表示默认能力；子 Run 可给严格子集。 */
    val capabilities: Set<String>? = null,
    /** 条件检查 Run 只允许读取；网页导航作为取得读取对象的准备动作例外。 */
    val readOnly: Boolean = false,
    val parent: RunId? = null,
    val stepBudget: Int? = null,
) {
    init { require(goal.isNotBlank() && threadId.isNotBlank()) }
}

/** 从本次对话送给运行的输入。取消走控制通道,不会等在插话队列里。 */
sealed interface RunInput {
    /**
     * 插话是补充:撤掉还在等的模型请求,接在已有历史后面重问(做过的一概保留);正在执行的工具照常做完、结果照记,
     * 撤掉也不丢东西的等待提前结束(见 [waitOrSteer])。
     */
    data class Steer(val messageId: String, val text: String) : RunInput
    /** 和 [Steer] 走同一条路(旧入口的名字)。 */
    data class Redirect(val messageId: String, val text: String) : RunInput
    data class Answer(val text: String, val askId: String? = null) : RunInput
    data class Confirm(val choice: ConfirmChoice, val askId: String? = null) : RunInput
    data class HandBack(val result: com.ai.assistance.quro.core.mika.tool.HandBack, val askId: String? = null) : RunInput
    data object Cancel : RunInput
}

enum class InputReceipt { Accepted, NoPendingInteraction, NextTask, Closed }

sealed interface RunPhase {
    data object Queued : RunPhase
    data object Preparing : RunPhase
    data object Running : RunPhase
    data class WaitingUser(val askId: String? = null) : RunPhase
    data class Parked(val askId: String) : RunPhase
    data class Paused(val reason: String) : RunPhase
    data object Finishing : RunPhase
    data object Aftercare : RunPhase
    data object Ended : RunPhase
    data object Interrupted : RunPhase
}
