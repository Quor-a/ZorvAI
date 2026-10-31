package com.ai.assistance.quro.core.mika.uistate.tools

import com.ai.assistance.quro.core.mika.uistate.ScreenFamily
import com.ai.assistance.quro.core.mika.uistate.Located
import com.ai.assistance.quro.core.mika.prompt.Feedback
import com.ai.assistance.quro.core.mika.tool.Args
import com.ai.assistance.quro.core.mika.tool.HINT_PROTECTED
import com.ai.assistance.quro.core.mika.tool.Risk
import com.ai.assistance.quro.core.mika.tool.ToolContext
import com.ai.assistance.quro.core.mika.tool.ToolDef
import com.ai.assistance.quro.core.mika.tool.ToolExecution
import com.ai.assistance.quro.core.mika.tool.Verb
import com.ai.assistance.quro.core.mika.tool.fail
import com.ai.assistance.quro.core.mika.tool.failCode
import com.ai.assistance.quro.core.mika.tool.ok
import com.ai.assistance.quro.core.mika.tool.str
import com.ai.assistance.quro.core.mika.vision.LocateResult
import com.ai.assistance.quro.core.mika.vision.ScreenUnavailable
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import com.ai.assistance.quro.core.mika.uistate.described
import com.ai.assistance.quro.core.mika.uistate.locatedInfo
import com.ai.assistance.quro.core.mika.uistate.locator

/** 看图类工具:要设置里有看图模型(视觉角色)才提供,和主模型能不能看图无关。 */
internal class Describe(f: ScreenFamily) : ScreenTool(f, "describe") {
    override val verb = Verb.Describe
    override val risk = Risk.Read
    // 只问看图模型、不动界面:用户插话时撤掉,先读那句话
    override val yieldsToSteer = true
    override val def by lazy { ToolDef(name,
        describe(),
        mapOf(
            "task" to str("What you want to do on this screen right now, 1-1000 characters."),
            "reason" to str("Why the element list is not enough, 1-300 characters."),
        ), listOf("task", "reason")) }

    override fun usable(ctx: ToolContext) = ctx.config.vision != null

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val task = a.str("task", max = 1000)!!
        a.str("reason", max = 300)
        val text = try {
            ctx.locator!!.describe(task)
        } catch (e: ScreenUnavailable) {
            return when (e.reason) {
                "protected_content" -> ToolExecution(buildJsonObject {
                    put("ok", false); put("error", "protected_content"); put("protected", true)
                    put("message", HINT_PROTECTED); put("recoverable", true)
                })
                "no_app_open" -> failCode("no_app_open")
                else -> fail(e.reason, e.message ?: Feedback["screen_unavailable"])
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return fail("vision_unavailable", Feedback.format("vision_unavailable", "error" to (e.message ?: e::class.simpleName)))
        }
        ctx.described = true
        return ok { put("description", text) }
    }
}

internal class FindVisually(f: ScreenFamily) : ScreenTool(f, "find_visually") {
    override val verb = Verb.FindVisually
    override val risk = Risk.Read
    override val yieldsToSteer = true
    override val def by lazy { ToolDef(name,
        describe(),
        mapOf(
            "target" to str("What the element is, e.g. the search icon at the top right. 1-120 characters."),
            "text" to str("The element's exact on-screen label, if it has one. 1-60 characters."),
        ), listOf("target")) }

    override fun usable(ctx: ToolContext) = ctx.config.vision != null

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val target = a.str("target", max = 120)!!
        val text = a.str("text", max = 60)
        return when (val r = ctx.locator!!.locate(target, text?.trim())) {
            is LocateResult.NotFound -> ok { put("found", false); put("reason", r.reason); put("message", r.message) }
            is LocateResult.Found -> {
                ctx.locatedInfo[r.ref] = Located(target, text?.trim(), r.seen, r.unverified)
                ok {
                    put("found", true); put("ref", r.ref); put("snapshotId", r.snapshotId)
                    put("x", r.x); put("y", r.y)
                    r.box?.let { b -> put("box", JsonArray(b.map(::JsonPrimitive))) }
                    r.seen?.let { put("seen", it) }
                    put("source", r.source)
                    if (r.unverified) put("unverified", true)
                    r.note?.let { put("note", it) }
                }
            }
        }
    }
}
