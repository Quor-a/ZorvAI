package com.ai.assistance.quro.core.mika.action

import com.ai.assistance.quro.core.mika.prompt.Feedback
import com.ai.assistance.quro.core.mika.guard.GuardVerdict
import com.ai.assistance.quro.core.mika.risk.IrrevAction
import com.ai.assistance.quro.core.mika.gate.Irreversible
import com.ai.assistance.quro.core.mika.uistate.ScreenSnapshot
import com.ai.assistance.quro.core.mika.tool.ToolExecution
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** 一次动作走完执行入口的结局。 */
sealed interface ActionOutcome {
    /** 执行前的守卫结论。 */
    val before: List<GuardVerdict>

    /**
     * 没有执行。[code]:guard_stopped / guard_warning / confirmation_required / confirmation_mismatch / approval_unavailable / target_unresolved / own_app。
     * [content] 是交给这次调用方的结果(脚本子步、模型单步照它回;批和回放按 [code] 自己说明)。
     */
    data class Refused(
        val code: String, val content: JsonObject, override val before: List<GuardVerdict>, val hit: Irreversible? = null,
    ) : ActionOutcome

    /**
     * 执行了。[exec] 是最终结果(模型单步可能已换成等用户之后的结果或核查退回);[after] 是执行后的守卫结论
     * (页面脚本的子步结论排在前面);[content] 是交给模型、也记进运行记录的内容。
     * [shotId]、[picture]:[Frames.Caller] 时留给调用方存的画面;[seen] 是记路线锚点时动作后读的一屏(没读为 null)。
     */
    data class Ran(
        val exec: ToolExecution, override val before: List<GuardVerdict>, val after: List<GuardVerdict>, val content: JsonObject,
        val durationMs: Long, val shotId: Int? = null, val picture: ByteArray? = null, val seen: ScreenSnapshot? = null,
    ) : ActionOutcome {
        val verdicts: List<GuardVerdict> get() = before + after
    }

    /** 等用户时任务被取消了。 */
    data class Cancelled(override val before: List<GuardVerdict>) : ActionOutcome
}

/** 这次调用对调用方的结果:执行了就是执行结果,没执行就是拒绝说明。 */
fun ActionOutcome.execution(): ToolExecution = when (this) {
    is ActionOutcome.Ran -> exec
    is ActionOutcome.Refused -> ToolExecution(content)
    is ActionOutcome.Cancelled -> ToolExecution(buildJsonObject { put("ok", false); put("error", "cancelled"); put("executed", false) })
}

/** 被拦下时交给调用方的说明,按来源的说法写。 */
internal object Refusals {
    const val SINGLE_USE = "One approval lets through only the next matching action, and that action uses it up whether it succeeds or not."
    const val OWN_APP = "own_app"
    const val PAYMENT_TAKEOVER = "The user completes payment with their own hands; do not use request_confirmation: after opening the payment page, call hand_to_user(reason=payment), " +
        "with item as the product (variant, quantity), amount as the amount due shown on screen, and address as the delivery address."

    fun content(code: String, style: RefusalStyle, before: List<GuardVerdict>, hit: Irreversible?, reason: String?): JsonObject = when (style) {
        // 模型单步被守卫叫停时任务就此结束,用不到说明
        RefusalStyle.Model -> if (hit == null) notRun(code, if (code == OWN_APP) Feedback["own_app"] else code) else {
            val blocked = if (reason != null) mismatch(hit, reason) else required(hit)
            if (before.isEmpty()) blocked else JsonObject(blocked + ("guard" to JsonArray(before.map { it.toJson() })))
        }
        RefusalStyle.Script -> {
            val message = when (code) {
                "guard_stopped" -> "ZorvAI's operation guard stopped the task."
                "guard_warning" -> "ZorvAI's operation guard flagged this action; it was not run."
                "confirmation_required" -> "this step spends money (${hit?.matched})"
                "confirmation_mismatch" -> "this payment step (${hit?.matched}) does not match what the user approved ($reason)"
                "approval_unavailable" -> "this step spends money and this run cannot get the user's approval; the action was not run"
                "target_unresolved" -> "that ref is not on the latest snapshot; call quro.snapshot() again"
                OWN_APP -> Feedback["own_app"]
                else -> error("unknown refusal $code")
            }
            notRun(code, message) { hit?.let { put("action", it.action.name); put("matched", it.matched) } }
        }
        RefusalStyle.Code -> notRun(code, code)
    }

    private fun required(hit: Irreversible) = buildJsonObject {
        put("ok", false); put("error", "confirmation_required")
        put("action", hit.action.name); put("matched", hit.matched)
        put("message", if (hit.action == IrrevAction.pay) "This step is a payment, so it was not run. $PAYMENT_TAKEOVER"
            else "This step spends money (${hit.action.name}), so it was not run. First call request_confirmation saying where, what is bought and how much it costs, and do it after the user approves. $SINGLE_USE")
        put("recoverable", true); put("counts_as_failure", false)
    }

    private fun mismatch(hit: Irreversible, reason: String) = buildJsonObject {
        put("ok", false); put("error", "confirmation_mismatch")
        put("action", hit.action.name); put("matched", hit.matched); put("reason", reason)
        put("message", "This step does not match the action the user approved ($reason). That approval is void, and this step was not run. " +
            "If it still needs doing, call request_confirmation again describing this step truthfully, and do it right after the user approves.")
        put("recoverable", true); put("counts_as_failure", false)
    }

    private fun notRun(error: String, message: String, extra: JsonObjectBuilder.() -> Unit = {}) = buildJsonObject {
        put("ok", false); put("error", error); put("message", message); put("executed", false)
        extra()
    }
}
