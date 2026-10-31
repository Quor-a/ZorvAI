package com.ai.assistance.quro.core.mika.uistate.tools

import com.ai.assistance.quro.core.mika.uistate.ScreenFamily
import com.ai.assistance.quro.core.mika.risk.GateTarget
import com.ai.assistance.quro.core.mika.prompt.Feedback
import com.ai.assistance.quro.core.mika.uistate.ActionResult
import com.ai.assistance.quro.core.mika.uistate.Verification
import com.ai.assistance.quro.core.mika.tool.ArgError
import com.ai.assistance.quro.core.mika.tool.Args
import com.ai.assistance.quro.core.mika.uistate.DESCRIBED_ELEMENT
import com.ai.assistance.quro.core.mika.uistate.DESCRIBED_ROLE
import com.ai.assistance.quro.core.mika.tool.COORD
import com.ai.assistance.quro.core.mika.tool.LOCATED_REF
import com.ai.assistance.quro.core.mika.tool.REF
import com.ai.assistance.quro.core.mika.tool.Risk
import com.ai.assistance.quro.core.mika.tool.SNAP
import com.ai.assistance.quro.core.mika.tool.ToolContext
import com.ai.assistance.quro.core.mika.tool.ToolDef
import com.ai.assistance.quro.core.mika.tool.ToolExecution
import com.ai.assistance.quro.core.mika.tool.Verb
import com.ai.assistance.quro.core.mika.tool.argStr
import com.ai.assistance.quro.core.mika.tool.bool
import com.ai.assistance.quro.core.mika.tool.fail
import com.ai.assistance.quro.core.mika.tool.int
import com.ai.assistance.quro.core.mika.tool.okObj
import com.ai.assistance.quro.core.mika.tool.str
import com.ai.assistance.quro.core.mika.tool.switchHint
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import com.ai.assistance.quro.core.mika.uistate.action
import com.ai.assistance.quro.core.mika.uistate.lastOrSnapshot
import com.ai.assistance.quro.core.mika.uistate.locatedInfo
import com.ai.assistance.quro.core.mika.uistate.toPx
import com.ai.assistance.quro.core.mika.uistate.lastSnapshot
import com.ai.assistance.quro.core.mika.uistate.screen
import com.ai.assistance.quro.core.mika.uistate.withPortRef

private const val KEYCODE_ENTER = 66

/** 输入、清空另收的参数:一个点的坐标(ref 也可以是看图找到的 @vN),树读不到输入框时直接往那一点打字。 */
private val POINT_ARGS = mapOf(
    "x" to int("Instead of a ref: the input box's point (e.g. a visibleText center), tapped before typing. $COORD", 0, 999),
    "y" to int(COORD, 0, 999),
)

/** 往哪儿打字:树里的元素(编号),或一个点(像素;[located] 是看图找到的 @vN,不是为 null)。 */
private sealed interface FieldAim {
    data class Ref(val ref: Pair<String, String>?) : FieldAim
    data class Point(val x: Int, val y: Int, val located: String?) : FieldAim
}

/**
 * 按参数定往哪儿打字:没给点时是编号(或焦点所在的框);给了坐标或 @vN 时是那一点,@vN 要和找到时的画面对得上。
 * 不能用的 @vN 返回给模型的失败结果。
 */
private suspend fun aim(a: Args, ctx: ToolContext): Pair<FieldAim?, ToolExecution?> {
    val ref = a.ref(located = true)
    val x = a.coord("x")
    val y = a.coord("y")
    if (ref != null && (x != null || y != null)) throw ArgError("x", "give either ref+snapshotId or x+y, not both")
    if ((x == null) != (y == null)) throw ArgError(if (x == null) "x" else "y", "x and y go together")
    if (ref != null && LOCATED_REF.matches(ref.first)) {
        val p = locatedPixel(ctx, ref.first, ref.second) ?: return null to fail("ref_expired", Feedback["located_stale"])
        return FieldAim.Point(p.first, p.second, ref.first) to null
    }
    if (x != null && y != null) {
        val s = ctx.lastOrSnapshot()
        return FieldAim.Point(toPx(x, s.width), toPx(y, s.height), null) to null
    }
    return FieldAim.Ref(ref) to null
}

/** 往看图找到的点上打字、清空时结果里写上找的是什么;没佐证的点标出来。 */
private fun located(ctx: ToolContext, aim: FieldAim?, r: ToolExecution): ToolExecution {
    val ref = (aim as? FieldAim.Point)?.located ?: return r
    val info = ctx.locatedInfo[ref] ?: return r
    val extra = listOfNotNull(info.target?.let { "located" to JsonPrimitive(it) }, ("unverifiedPoint" to JsonPrimitive(true)).takeIf { info.unverified })
    return if (extra.isEmpty()) r else r.with(content = JsonObject(r.content + extra))
}

/** 输入(带回车时可能是发送,要按目标判不可逆)。 */
internal class TypeText(f: ScreenFamily) : ScreenTool(f, "type_text") {
    override val verb = Verb.TypeText
    override val batchable = true
    override val routable = f.routable
    override val label = "输入文字"
    override val risk = Risk.External
    override val gateTarget = GateTarget.Enter
    override val showsScreenAfter = true
    override val reportsEffect = true
    override val logged = true
    override val def by lazy { ToolDef(name,
        describe(),
        mapOf(
            "text" to buildJsonObject { put("type", "string"); put("description", "Text to enter, 1-10000 characters."); put("minLength", 1); put("maxLength", 10000) },
            "pressEnter" to bool("Press Enter after typing (default false)."),
            "ref" to str(REF), "snapshotId" to str(SNAP),
            DESCRIBED_ELEMENT to str("Instead of ref+snapshotId: the input field's label, accessible name or placeholder, as shown on the screen. It must match exactly one input field on the screen right now."),
            DESCRIBED_ROLE to str(DESCRIBE_ROLE),
        ) + POINT_ARGS, listOf("text")) }

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        if (a.str(DESCRIBED_ELEMENT) != null) throw ArgError(DESCRIBED_ELEMENT, "could not be resolved to an input field here; use ref+snapshotId")
        val text = a.str("text", max = 10000)!!
        val enter = a.bool("pressEnter", false)
        val (target, failed) = aim(a, ctx)
        failed?.let { return it }
        return when (target) {
            is FieldAim.Point -> located(ctx, target, typeInto(ctx, text, enter) { ctx.screen.typeAt(target.x, target.y, text) })
            else -> ctx.withPortRef((target as FieldAim.Ref).ref) { ref -> typeInto(ctx, text, enter) { ctx.screen.typeText(ref?.first, ref?.second, text) } }
        }
    }

    private suspend fun typeInto(ctx: ToolContext, text: String, enter: Boolean, type: suspend () -> ActionResult): ToolExecution {
        val screen = ctx.screen
        val typed = type()
        if (typed !is ActionResult.Ok) return ctx.action(typed)
        val v = typed.verification
        if (v == Verification.MISMATCH) return ToolExecution(buildJsonObject {
            put("ok", false); put("error", "action_not_applied"); put("verification", v.wire)
            put("message", Feedback["type.not_applied"])
            put("recoverable", true)
        })
        val switched = typed.appSwitched
        // 读不回来就不按回车:可能把没打进去的内容提交出去;前台被切走时回车会打到别的 App 上
        val pressEnter = enter && v != Verification.UNKNOWN && switched == null
        if (pressEnter) {
            val k = screen.pressKey(KEYCODE_ENTER)
            if (k !is ActionResult.Ok) return ctx.action(k)
        }
        return ToolExecution(okObj {
            put("typed", text.length); put("pressedEnter", pressEnter)
            put("secret", typed.secret)
            if (typed.relocated) put("relocated", true)
            typed.relocatedTo?.takeIf { typed.relocated }?.let { put("relocatedTo", it) }
            v?.let { put("verification", it.wire) }
            if (enter && !pressEnter) put("enterSkipped", true)
            switched?.let { put("app_switched", it) }
            val hints = listOfNotNull(
                typed.hint,
                (if (enter) Feedback.format("type.unverified_enter", "unverified" to Feedback["type.unverified"]) else Feedback["type.unverified"])
                    .takeIf { v == Verification.UNKNOWN },
                switched?.let { switchHint(it, typed.switchedByTarget).let { h -> if (enter) Feedback.format("type.switched_enter", "switch" to h) else h } },
            )
            if (hints.isNotEmpty()) put("hint", hints.joinToString(" "))
        }, changed = typed.changed)
    }

    override fun logTarget(o: JsonObject, r: ToolExecution, ctx: ToolContext) = refTarget(o, r, ctx.lastSnapshot) ?: pointTarget(o)

    // 只有端口明确说不是密码框才写出输入的字
    override fun logTyped(o: JsonObject, r: ToolExecution) =
        o.argStr("text")?.takeIf { r.content["secret"] == JsonPrimitive(false) }?.let { "「${it.take(40)}」" }
}

internal class ClearText(f: ScreenFamily) : ScreenTool(f, "clear_text") {
    override val verb = Verb.ClearText
    override val batchable = true
    override val routable = f.routable
    override val label = "清空输入框"
    override val risk = Risk.Write
    override val showsScreenAfter = true
    override val reportsEffect = true
    override val logged = true
    override val def by lazy { ToolDef(name, describe(),
        mapOf("ref" to str(REF), "snapshotId" to str(SNAP)) + POINT_ARGS) }

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val (target, failed) = aim(a, ctx)
        failed?.let { return it }
        return when (target) {
            is FieldAim.Point -> located(ctx, target, ctx.action(ctx.screen.clearAt(target.x, target.y)))
            else -> ctx.withPortRef((target as FieldAim.Ref).ref) { r -> ctx.action(ctx.screen.clearText(r?.first, r?.second)) }
        }
    }

    override fun logTarget(o: JsonObject, r: ToolExecution, ctx: ToolContext) = refTarget(o, r, ctx.lastSnapshot) ?: pointTarget(o)
}

/** 往坐标点上打字、清空时动作清单里写的对象。 */
private fun pointTarget(o: JsonObject): String? = o["x"]?.let { x -> "${x},${o["y"]}" }
