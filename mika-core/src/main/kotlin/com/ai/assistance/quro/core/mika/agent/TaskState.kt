package com.ai.assistance.quro.core.mika.agent

import com.ai.assistance.quro.core.mika.gate.ConfirmationRequest
import com.ai.assistance.quro.core.mika.verify.AnswerSource

/** 不经模型、一下做好几步时的进度,任务卡片和通知据此写明在干什么。 */
sealed interface RunProgress {
    /** 照着记住的路线做,正在做第 [step]/[total] 步。 */
    data class Replay(val step: Int, val total: Int) : RunProgress
    /** 一次执行模型给的 [steps] 步(do_steps)。 */
    data class Batch(val steps: Int) : RunProgress
}

sealed interface TaskState {
    /** 还没开始运行。 */
    data object Idle : TaskState
    /**
     * [note] 是模型最近一句话,没有话时是最近调用的工具名。
     * [tool] 是这一步要调用的工具;为空表示这一步还在等模型回复(此时 [note] 仍是上一步的)。
     * [progress] 非空时这一步不经模型连做几步(回放路线、批量执行)。
     * [reason] 是模型给这次调用写的用意(调用参数里的 reason);没写时为 null。
     * [narration] 是模型随这次调用写的话(进度说明,不是回答);界面上「在做什么」先用它,没有再用 [reason]。
     * [preview] 是当前模型请求已流出的可见文字，尚未确认是最终答复还是工具进度。
     */
    data class Running(
        val step: Int, val note: String, val tool: String? = null, val progress: RunProgress? = null, val reason: String? = null,
        val narration: String? = null,
        val preview: String? = null,
    ) : TaskState
    /** 一个能力的前置条件此刻不满足,停在这里等(见 [GateReason]);条件满足或用户点了继续后恢复 Running。 */
    data class Held(val reason: GateReason) : TaskState
    /** 付款时 [item]/[amount]/[address] 在接管卡片上单独列出;用 [AgentLoop.resumeAfterTakeover] 交回结局。 */
    data class WaitingTakeover(
        val reason: String, val message: String,
        val item: String? = null, val amount: String? = null, val address: String? = null,
    ) : TaskState
    data class WaitingInput(val prompt: String, val options: List<String>? = null) : TaskState
    /**
     * 等用户同意一次要花钱的操作(只能同意这一次);用 [AgentLoop.answerConfirmation] 回答,等到回答、取消或超时(超时按不同意)。
     */
    data class WaitingConfirmation(val request: ConfirmationRequest) : TaskState
    /**
     * 模型不带工具调用的回复就是 [answer](原样给用户看)。核对有三种结果:
     * [reviewed] 为 false 是什么也没核对(聊天、回答里没有可核对的值、也没请核查模型),这时 [verified] 也是 false,界面不打标;
     * 核对过而且都对上时 [verified] 为 true;核对过却没能核实时 [verified] 为 false,[note] 是原因。
     * [unconfirmed] 是退回一次后仍没对上的那几处(回答照样交出,这几处标成未确认)。
     * [sources] 是代码从证据账本里找到的、支撑回答的那几次读取(模型不写依据)。
     * 学东西(经验路线、个人技能、App 知识)看判过的结局,不看这里的核对,见 [LearnGate]。
     */
    data class Succeeded(
        val answer: String, val verified: Boolean = true, val note: String? = null,
        val sources: List<AnswerSource> = emptyList(), val unconfirmed: List<String> = emptyList(),
        val reviewed: Boolean = true,
    ) : TaskState {
        /** 核对过却没能核实(界面标出、评测记成 unverified)。 */
        val doubtful: Boolean get() = reviewed && !verified
    }
    /** [kind] 由产生失败的代码自己给出,不从 [reason] 文字里猜。 */
    data class Failed(val reason: String, val kind: FailKind) : TaskState
    data object Cancelled : TaskState
}

/**
 * 为什么停下来等:由给出这个前置条件的能力定(核心不认识具体是什么)。[code] 是运行记录里 state 事件的名字,
 * [why] 是给用户看的说明(可为空),[resumable] 为 true 时用户可以点「继续」,否则条件满足后自动继续。
 * [needsYou] 为 true 时要用户在手机上动一下手才能接着做(界面上算「要你处理」,不只是等条件)。
 */
data class GateReason(val code: String, val why: String = "", val resumable: Boolean = false, val needsYou: Boolean = false)

/** 任务失败的来源。 */
enum class FailKind {
    /** Agent 调用 end_not_done,如实说做不成。 */
    GaveUp,
    /** 模型调用出错、返回异常,或停止操作却没有给出结果。 */
    Model,
    /** 操作保护触发,安全停止。 */
    Guard,
    StepLimit,
    /** 运行环境不满足或出错(手机操作助手、无障碍、没配模型、虚拟屏断开等)。 */
    Environment,
}

