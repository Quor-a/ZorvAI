package com.ai.assistance.quro.core.mika.agent

import com.ai.assistance.quro.core.mika.action.ConfirmChoice
import com.ai.assistance.quro.core.mika.action.Refusals
import com.ai.assistance.quro.core.mika.gate.ConfirmationGate
import com.ai.assistance.quro.core.mika.gate.DeclareResult
import com.ai.assistance.quro.core.mika.general.RequestConfirmation
import com.ai.assistance.quro.core.mika.memory.UserUtterances
import com.ai.assistance.quro.core.mika.prompt.Feedback
import com.ai.assistance.quro.core.mika.record.Redactor
import com.ai.assistance.quro.core.mika.record.RunEvent
import com.ai.assistance.quro.core.mika.tool.HandBack
import com.ai.assistance.quro.core.mika.tool.HandBackOutcome
import com.ai.assistance.quro.core.mika.tool.Outcome
import com.ai.assistance.quro.core.mika.tool.ToolDispatcher
import com.ai.assistance.quro.core.mika.tool.ToolExecution
import com.ai.assistance.quro.core.mika.run.Attendance
import com.ai.assistance.quro.core.mika.run.Delivery
import com.ai.assistance.quro.core.mika.run.DeliveryState
import com.ai.assistance.quro.core.mika.run.Interaction
import com.ai.assistance.quro.core.mika.run.LocalInteraction
import com.ai.assistance.quro.core.mika.run.RunOrigin
import com.ai.assistance.quro.core.mika.run.RunProvenance
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID

/**
 * 等用户:回答追问、交回接管、回答确认。等待期间对外显示等待的状态,等完换回 [running](由调用方给);
 * 用户的决定(或超时、跳过)做成这次调用的工具结果。界面经 [answerInput] 一类方法把用户的回答交进来。
 */
internal class UserExchange(
    private val tools: ToolDispatcher,
    private val confirmations: ConfirmationGate?,
    /** 等用户回答确认的时限,到时按不同意;为 null 时一直等。 */
    private val confirmTimeoutMs: Long?,
    private val inputTimeoutMs: Long,
    /** 用户本次说的话(memory_save 的引文校验用);不收集时为 null。 */
    private val utterances: () -> UserUtterances?,
    private val setState: (TaskState) -> Unit,
    private val emit: (RunEvent) -> Unit,
    private val now: () -> Long,
) {
    private var threadId = "local"
    private var provenance = RunProvenance()
    private var interaction: Interaction = LocalInteraction
    @Volatile var activeAsk: Delivery.Ask? = null
        private set
    @Volatile private var takeoverReply: CompletableDeferred<HandBack>? = null
    @Volatile private var inputReply: CompletableDeferred<String>? = null
    @Volatile private var confirmationReply: CompletableDeferred<ConfirmChoice>? = null
    /** 回答换成给模型的样子(带着用户屏幕时换成说明,见 [UserScreenTurns.asText]);默认原样。 */
    var answerText: suspend (String) -> String = { it }

    fun begin(thread: String, run: RunProvenance, channel: Interaction = LocalInteraction) {
        threadId = thread
        provenance = run
        interaction = channel
        activeAsk = null
    }

    private fun ask(kind: Delivery.Ask.Kind, text: String): Delivery.Ask =
        Delivery.Ask(UUID.randomUUID().toString(), threadId, UUID.randomUUID().toString(), kind, text)

    private suspend fun announce(ask: Delivery.Ask): DeliveryState {
        activeAsk = ask
        return interaction.publish(ask)
    }

    private fun resolve(ask: Delivery.Ask) {
        if (activeAsk?.askId == ask.askId) activeAsk = null
        interaction.resolved(ask.askId)
    }

    private fun matches(id: String?): Boolean = id == null || activeAsk?.askId == id

    fun resumeAfterTakeover(result: HandBack, askId: String? = null): Boolean =
        if (matches(askId)) takeoverReply?.complete(result) ?: false else false

    /** 没有在等的问题(已超时、已答过)时返回 false。 */
    fun answerInput(text: String, askId: String? = null): Boolean =
        if (matches(askId)) inputReply?.complete(text) ?: false else false

    fun answerConfirmation(choice: ConfirmChoice, askId: String? = null): Boolean =
        if (matches(askId)) confirmationReply?.complete(choice) ?: false else false

    /** 任务结束:不再等任何回答。 */
    fun clear() {
        takeoverReply = null
        inputReply = null
        confirmationReply = null
        activeAsk = null
    }

    /** 等用户回答追问,最多 [inputTimeoutMs];超时返回 null。用户的回答可能是验证码一类,不记。 */
    suspend fun awaitAnswer(prompt: String, options: List<String>?): String? {
        if (provenance.attendance == Attendance.Unattended) return null
        val pending = CompletableDeferred<String>().also { inputReply = it }
        val ask = ask(Delivery.Ask.Kind.Input, prompt)
        activeAsk = ask
        setState(TaskState.WaitingInput(prompt, options))
        emit(RunEvent.State(now(), "waiting_input", detail(prompt)))
        return try {
            if (announce(ask) == DeliveryState.Failed) null
            else withTimeoutOrNull(inputTimeoutMs) { pending.await() }
        } finally {
            inputReply = null
            resolve(ask)
        }.also { if (it == null) emit(RunEvent.State(now(), "no_answer", detail(prompt))) else utterances()?.add(UserWords.said(com.ai.assistance.quro.core.mika.userscreen.UserScreenMessage.parse(it).words, tools)) }?.let { answerText(it) }
    }

    suspend fun input(o: Outcome.Input, running: TaskState): ToolExecution {
        val answer = awaitAnswer(o.prompt, o.options)
        // 用户在任务里给的事实也是核查的依据
        answer?.let { tools.noteUserAnswer(o.prompt, it) }
        setState(running)
        return ToolExecution(buildJsonObject {
            if (answer != null) { put("ok", true); put("answer", answer) }
            else { put("ok", false); put("error", "no_answer"); put("message", INPUT_TIMEOUT_MESSAGE); put("recoverable", true); put("counts_as_failure", false) }
        })
    }

    /** 等用户交回;取消任务(aborted)时返回 null。没交回就不说成交回了:跳过、超时不算失败,但如实告诉模型。 */
    suspend fun takeover(o: Outcome.Takeover, running: TaskState): ToolExecution? {
        if (provenance.attendance == Attendance.Unattended || provenance.origin == RunOrigin.Child) return null
        val pending = CompletableDeferred<HandBack>().also { takeoverReply = it }
        val ask = ask(Delivery.Ask.Kind.Takeover, o.message)
        activeAsk = ask
        setState(TaskState.WaitingTakeover(o.reason, o.message, o.item, o.amount, o.address))
        emit(RunEvent.State(now(), "waiting_takeover", detail(o.message)))
        val since = now()
        val back = try {
            if (announce(ask) == DeliveryState.Failed) return null
            if (provenance.attendance == Attendance.Reachable) withTimeoutOrNull(inputTimeoutMs) { pending.await() }
            else pending.await()
        } finally { takeoverReply = null; resolve(ask) } ?: return null
        back.note?.let { utterances()?.add(it) }
        tools.noteTakeover(o.reason, back)
        val outcome = if (back.userFinished && back.outcome == HandBackOutcome.complete) "user_finished" else back.outcome.name
        emit(RunEvent.Takeover(now(), o.reason, outcome, back.durationMs ?: (now() - since), back.inputCount))
        if (back.outcome == HandBackOutcome.aborted) return null
        setState(running)
        return ToolExecution(buildJsonObject {
            put("outcome", back.outcome.name)
            if (back.outcome == HandBackOutcome.complete) {
                put("ok", true)
                put("durationMs", back.durationMs ?: (now() - since))
                back.inputCount?.let { put("inputCount", it) }
                back.stoppedAt?.let { put("stoppedAt", it) }
                put("note", HANDBACK_NOTE)
                if (back.userFinished) { put("userFinished", true); put("instruction", USER_FINISHED_PROMPT) }
                back.note?.takeIf { it.isNotBlank() }?.let { put("userNote", it) }
            } else {
                put("ok", false)
                put("reason", if (back.outcome == HandBackOutcome.skipped) "takeover_skipped" else "takeover_timeout")
                put("counts_as_failure", false)
                put("note", NO_HANDBACK_NOTE)
            }
        })
    }

    /** 申报和前台对得上就等用户回答(到时限按不同意,可取消);工具结果就是用户的决定。 */
    suspend fun confirm(o: Outcome.Confirm, step: Int, running: TaskState): ToolExecution {
        val g = confirmations ?: return ToolExecution(buildJsonObject {
            put("ok", false); put("error", "unknown_tool"); put("tool", RequestConfirmation.name)
            put("message", Feedback["confirmation_unavailable"]); put("recoverable", true)
        })
        val r = o.request
        fun event(decision: String?) = RunEvent.Confirmation(now(), r.action.name, detail(r.target), detail(r.content), r.amount?.let(::detail), decision)
        fun error(code: String, message: String, recoverable: Boolean) = ToolExecution(buildJsonObject {
            put("ok", false); put("error", code); put("message", message)
            put("recoverable", recoverable); put("counts_as_failure", false)
        })
        when (g.declare(r, o.foregroundApp)) {
            DeclareResult.Mismatch -> {
                emit(event("mismatch"))
                return ToolExecution(buildJsonObject {
                    put("ok", false); put("error", "confirmation_mismatch")
                    put("message", Feedback.format("confirm_app_mismatch", "app" to r.app, "foreground" to (o.foregroundApp ?: "unknown")))
                    put("recoverable", true)
                })
            }
            DeclareResult.Declined -> {
                emit(event("declined_before"))
                return error("confirmation_declined", CONFIRMATION_DECLINED, recoverable = false)
            }
            DeclareResult.Unsupported -> return error("payment_requires_takeover", Refusals.PAYMENT_TAKEOVER, recoverable = true)
            DeclareResult.Pending -> Unit
        }
        if (provenance.attendance == Attendance.Unattended || provenance.origin == RunOrigin.Child) {
            g.decide(ConfirmChoice.Deny, step)
            emit(event("declined_unattended"))
            return error("confirmation_declined", CONFIRMATION_DECLINED, recoverable = false)
        }
        // 付款只在手机上同意:渠道、定时、MCP 发起的任务不问
        if (provenance.origin != RunOrigin.Ui) {
            g.decide(ConfirmChoice.Deny, step)
            emit(event("declined_remote"))
            return error("confirmation_declined", CONFIRMATION_DECLINED, recoverable = false)
        }
        val pending = CompletableDeferred<ConfirmChoice>().also { confirmationReply = it }
        val ask = ask(Delivery.Ask.Kind.Confirm, "${r.action.name}: ${r.target} ${r.content}")
        activeAsk = ask
        setState(TaskState.WaitingConfirmation(r))
        emit(event(null))
        // 没人回答(不在手机旁)就按不同意,绝不自动同意
        val timeout = if (provenance.attendance == Attendance.Reachable) confirmTimeoutMs ?: 15 * 60_000L else confirmTimeoutMs
        val answer = try {
            if (announce(ask) == DeliveryState.Failed) null
            else if (timeout == null) pending.await() else withTimeoutOrNull(timeout) { pending.await() }
        } finally { confirmationReply = null; resolve(ask) }
        val choice = answer ?: ConfirmChoice.Deny
        g.decide(choice, step)
        setState(running)
        emit(event(when {
            answer == null -> "timed_out"
            choice.approved -> "approved"
            else -> "declined"
        }))
        return if (choice.approved) ToolExecution(buildJsonObject { put("ok", true); put("approved", true); put("message", CONFIRMATION_APPROVED) })
        else error("confirmation_declined", CONFIRMATION_DECLINED, recoverable = false)
    }

    private fun detail(s: String) = RunEvent.clip(Redactor.text(s))

    companion object {
        val INPUT_TIMEOUT_MESSAGE: String get() = Feedback["no_answer_message"]
        val HANDBACK_NOTE: String get() = Feedback["handback_note"]
        val NO_HANDBACK_NOTE: String get() = Feedback["no_handback_note"]
        val USER_FINISHED_PROMPT: String get() = Feedback["user_finished_prompt"]
        val CONFIRMATION_DECLINED: String get() = Feedback["confirmation_declined"]
        val CONFIRMATION_APPROVED: String get() = Feedback["confirmation_approved"]
    }
}
