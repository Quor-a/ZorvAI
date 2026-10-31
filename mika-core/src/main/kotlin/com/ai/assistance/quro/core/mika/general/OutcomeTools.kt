package com.ai.assistance.quro.core.mika.general

import com.ai.assistance.quro.core.mika.prompt.Prompts
import com.ai.assistance.quro.core.mika.tool.AgentTool
import com.ai.assistance.quro.core.mika.tool.ArgError
import com.ai.assistance.quro.core.mika.tool.Args
import com.ai.assistance.quro.core.mika.tool.FAILURE_SUBTYPES
import com.ai.assistance.quro.core.mika.tool.Outcome
import com.ai.assistance.quro.core.mika.tool.Risk
import com.ai.assistance.quro.core.mika.tool.TAKEOVER_REASONS
import com.ai.assistance.quro.core.mika.tool.TAKEOVER_REASON_ALIASES
import com.ai.assistance.quro.core.mika.tool.ToolContext
import com.ai.assistance.quro.core.mika.tool.ToolDef
import com.ai.assistance.quro.core.mika.tool.ToolExecution
import com.ai.assistance.quro.core.mika.tool.Verb
import com.ai.assistance.quro.core.mika.tool.bool
import com.ai.assistance.quro.core.mika.tool.capField
import com.ai.assistance.quro.core.mika.tool.fail
import com.ai.assistance.quro.core.mika.tool.okObj
import com.ai.assistance.quro.core.mika.tool.str
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** 做不成、问用户或交给用户的工具:结果里带给循环的 [Outcome]。 */
internal object EndNotDone : AgentTool {
    override val verb = Verb.EndNotDone
    override val label = "说明没办成的原因"
    override val risk = Risk.Read
    override val def = ToolDef("end_not_done",
        Prompts.tool("end_not_done"),
        mapOf("message" to str("The concrete blocker, what was and was not completed, and the screen's current state."),
            "subtype" to str("Failure kind (default interaction_failed).", FAILURE_SUBTYPES),
            "recoverable" to bool("Whether a retry could still succeed (default false).")),
        listOf("message"))

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val m = a.str("message", max = 8000)!!
        val st = a.str("subtype") ?: "interaction_failed"
        if (st !in FAILURE_SUBTYPES) throw ArgError("subtype", "must be one of ${FAILURE_SUBTYPES.joinToString("|")}")
        val rec = a.bool("recoverable", false)
        return ToolExecution(buildJsonObject {
            put("ok", false); put("reported", true); put("subtype", st); put("recoverable", rec); put("counts_as_failure", false)
        }, outcome = Outcome.Failure(m, st, rec))
    }
}

internal object AskUser : AgentTool {
    override val verb = Verb.AskUser
    override val label = "问你一个问题"
    override val risk = Risk.Read
    override val def = ToolDef("ask_user",
        Prompts.tool("ask_user"),
        mapOf("prompt" to str("The question to show the user."),
            "options" to buildJsonObject {
                put("type", "array"); put("description", "Optional 2-8 answer choices."); put("minItems", 2); put("maxItems", 8)
                put("items", buildJsonObject { put("type", "string") })
            }),
        listOf("prompt"))

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val q = a.str("prompt", max = 1000)!!.trim()
        val opts = a.strList("options", 2, 8)?.map(String::trim)?.distinct()
        if (opts != null && opts.size < 2) throw ArgError("options", "must have at least 2 different choices")
        return ToolExecution(okObj { put("asked", true) }, outcome = Outcome.Input(q, opts))
    }
}

/** 交给用户亲手操作屏幕;要过锁屏关卡(用户要在屏上操作)。 */
internal object HandToUser : SurfaceAware {
    override val verb = Verb.HandToUser
    override val label = "请你接手"
    override val risk = Risk.Write
    override val withScreens = true
    override val def = ToolDef("hand_to_user",
        Prompts.tool("hand_to_user"),
        mapOf("reason" to str("Why the user is needed.", TAKEOVER_REASONS),
            "message" to str("Tell the user what to do and where the task stopped. For payment: the item, spec, quantity, the amount shown on screen and the address."),
            "item" to str("For payment: the item, spec and quantity, as shown on screen."),
            "amount" to str("For payment: the amount the user will pay, with currency, as shown on screen."),
            "address" to str("For payment: the delivery address shown on screen, if any.")),
        listOf("reason", "message"))

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        checkSurface(a, ctx)
        val raw = a.str("reason")!!
        val r = TAKEOVER_REASON_ALIASES[raw] ?: raw
        if (r !in TAKEOVER_REASONS) throw ArgError("reason", "must be one of ${TAKEOVER_REASONS.joinToString("|")}")
        val m = a.str("message", max = 1000)!!
        // 付款:模型没写的项用之前被拒的付款申报里的
        val declared = ctx.refusedPay.takeIf { r == "payment" }
        // 只给紧接着的那次付款接管用,之后无关的付款不串用
        if (declared != null) ctx.refusedPay = null
        val item = a.str("item", max = 10000)?.let(::capField) ?: declared?.content
        val amount = a.str("amount", max = 10000)?.let(::capField) ?: declared?.amount
        val address = a.str("address", max = 10000)?.let(::capField)
        return ToolExecution(okObj { put("handedOver", true) }, outcome = Outcome.Takeover(r, m, item, amount, address))
    }
}
