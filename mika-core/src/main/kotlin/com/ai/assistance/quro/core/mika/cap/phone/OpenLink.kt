package com.ai.assistance.quro.core.mika.cap.phone

import com.ai.assistance.quro.core.mika.gate.IrreversibleDetector
import com.ai.assistance.quro.core.mika.prompt.Feedback
import com.ai.assistance.quro.core.mika.prompt.Prompts
import com.ai.assistance.quro.core.mika.tool.Args
import com.ai.assistance.quro.core.mika.tool.Risk
import com.ai.assistance.quro.core.mika.tool.ToolContext
import com.ai.assistance.quro.core.mika.tool.ToolDef
import com.ai.assistance.quro.core.mika.tool.ToolExecution
import com.ai.assistance.quro.core.mika.tool.Verb
import com.ai.assistance.quro.core.mika.tool.argStr
import com.ai.assistance.quro.core.mika.tool.fail
import com.ai.assistance.quro.core.mika.tool.str
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import com.ai.assistance.quro.core.mika.tool.LinkPolicy
import com.ai.assistance.quro.core.mika.uistate.action
import com.ai.assistance.quro.core.mika.uistate.session

/**
 * 打开链接或设置页;只在提速开关 deepLinks 打开时提供。不可逆判定里它只是打开页面;
 * 落到付款页时让模型交给用户,页面上的操作照常过关卡。
 */
internal object OpenLink : PhoneTool {
    override val verb = Verb.OpenLink
    override val batchable = true
    override val routable = true
    override val risk = Risk.Write
    override val showsScreenAfter = true
    override val logged = true
    override val def = ToolDef("phone_open_link",
        Prompts.tool("phone_open_link", "settings_actions" to LinkPolicy.SETTINGS_ACTIONS.joinToString(), "app_details" to LinkPolicy.APP_DETAILS),
        mapOf(
            "uri" to str("The link to open, e.g. a link from the reference notes, or package:<name> for app details."),
            "action" to str("An Android settings action from the list above."),
            "package" to str("Exact package name of the app that should open the link; required for http(s) links."),
        ))

    override fun available(ctx: ToolContext) = ctx.phone.deepLinks

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val uri = a.str("uri", max = 2000)?.trim()
        val action = a.str("action")?.trim()
        val pkg = a.str("package")?.trim()
        LinkPolicy.check(action, uri, pkg)?.let { return fail("invalid_link", it) }
        if (action == null && (uri to pkg) !in ctx.allowedLinks) {
            return fail("invalid_link", Feedback["link_not_listed"])
        }
        // 换了页面,之前被拒的付款申报就不是这次的了
        ctx.refusedPay = null
        val r = ctx.action(ctx.phone.screen.openLink(action, uri, pkg))
        if (r.content["ok"] != JsonPrimitive(true)) return r
        ctx.appOpened = true
        val s = ctx.session.peek()
        val pay = s?.let { IrreversibleDetector.paymentPage(it) }
        val hints = listOfNotNull((r.content["hint"] as? JsonPrimitive)?.content)
        return ToolExecution(JsonObject(r.content.filterKeys { it != "hint" } + buildMap {
            put("currentApp", s?.packageName?.let(::JsonPrimitive) ?: JsonNull)
            if (pay != null) put("paymentPage", JsonPrimitive(true))
            if (hints.isNotEmpty()) put("hint", JsonPrimitive(hints.joinToString(" ")))
        }), changed = r.changed)
    }

    override fun logTarget(o: JsonObject, r: ToolExecution, ctx: ToolContext) = o.argStr("action") ?: o.argStr("uri")
}
