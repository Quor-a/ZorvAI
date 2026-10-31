package com.ai.assistance.quro.core.mika.cap.web

import com.ai.assistance.quro.core.mika.uistate.CdpKeys
import com.ai.assistance.quro.core.mika.uistate.KeyModifier
import com.ai.assistance.quro.core.mika.risk.GateTarget
import com.ai.assistance.quro.core.mika.prompt.Prompts
import com.ai.assistance.quro.core.mika.uistate.ActionResult
import com.ai.assistance.quro.core.mika.uistate.Resolved
import com.ai.assistance.quro.core.mika.tool.ArgError
import com.ai.assistance.quro.core.mika.tool.Args
import com.ai.assistance.quro.core.mika.tool.PAGE_REF_RE
import com.ai.assistance.quro.core.mika.tool.Risk
import com.ai.assistance.quro.core.mika.tool.ToolContext
import com.ai.assistance.quro.core.mika.tool.ToolDef
import com.ai.assistance.quro.core.mika.tool.ToolExecution
import com.ai.assistance.quro.core.mika.tool.Verb
import com.ai.assistance.quro.core.mika.tool.argStr
import com.ai.assistance.quro.core.mika.tool.failCode
import com.ai.assistance.quro.core.mika.tool.int
import com.ai.assistance.quro.core.mika.tool.str
import com.ai.assistance.quro.core.mika.uistate.toPx
import kotlinx.serialization.json.JsonObject
import com.ai.assistance.quro.core.mika.uistate.action
import com.ai.assistance.quro.core.mika.uistate.lastOrSnapshot
import com.ai.assistance.quro.core.mika.uistate.lastSnapshot
import com.ai.assistance.quro.core.mika.uistate.session
import com.ai.assistance.quro.core.mika.uistate.withPortRef
import com.ai.assistance.quro.core.mika.uistate.DwellStop
import com.ai.assistance.quro.core.mika.uistate.dwellStopped

/** 复制、剪切、粘贴。 */
private val CLIPBOARD_KEYS = setOf("KeyC", "KeyX", "KeyV")

/**
 * web_interact:按 action 校验参数(多给的参数报错,不默默忽略),坐标按最近一屏换成 view 像素,
 * 稳定编号开着时两头的编号都先翻译成端口编号。组合键、拖动、下拉选择可能触发提交,要按目标判不可逆。
 */
internal object Interact : WebTool {
    override val verb = Verb.Interact
    override val risk = Risk.External
    override val gateTarget = GateTarget.Interact
    override val showsScreenAfter = true
    override val reportsEffect = true
    override val logged = true
    override val def = ToolDef("web_interact",
        Prompts.tool("web_interact"),
        mapOf(
            "action" to str("What to do.", listOf("hover", "drag", "select", "key", "dialog")),
            "ref" to str("Element from the latest web_observe/web_list_elements: hover target, drag start, or the drop-down for select."),
            "snapshotId" to str("snapshotId of that web_observe/web_list_elements result (also used for toRef)."),
            "x" to int("Instead of ref for hover/drag: point 0-999 across the screen.", 0, 999),
            "y" to int("Instead of ref for hover/drag: point 0-999 down the screen.", 0, 999),
            "toRef" to str("drag: element to release over."),
            "toX" to int("drag: release point 0-999 across, instead of toRef.", 0, 999),
            "toY" to int("drag: release point 0-999 down, instead of toRef.", 0, 999),
            "steps" to int("drag: mouse moves between press and release, 1-$DRAG_STEPS_MAX (default $DRAG_STEPS_DEFAULT).", 1, DRAG_STEPS_MAX),
            "option" to str("select: the option's shown text or its value."),
            "answer" to str("dialog: accept (OK) or dismiss (Cancel) the page's open dialog.", listOf("accept", "dismiss")),
            "text" to str("dialog: text to enter into a prompt dialog before accepting (default: its prefilled text)."),
            "key" to str("key: one key (Enter, Tab, Escape, Backspace, Delete, Space, ArrowUp/Down/Left/Right, Home, End, PageUp, PageDown, a letter or digit), optionally after Control+, Shift+, Alt+ or Meta+."),
        ), listOf("action"))

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val action = a.str("action")!!
        val allowed = when (action) {
            "hover" -> setOf("ref", "snapshotId", "x", "y")
            "drag" -> setOf("ref", "snapshotId", "x", "y", "toRef", "toX", "toY", "steps")
            "select" -> setOf("ref", "snapshotId", "option")
            "key" -> setOf("key")
            "dialog" -> setOf("answer", "text")
            else -> throw ArgError("action", "must be hover, drag, select, key or dialog")
        }
        a.o.keys.firstOrNull { it != "action" && it != "reason" && it !in allowed }?.let { throw ArgError(it, "not used by action $action") }
        val w = ctx.web!!
        return when (action) {
            "dialog" -> {
                val answer = a.str("answer") ?: throw ArgError("answer", "required for action dialog: accept or dismiss")
                if (answer != "accept" && answer != "dismiss") throw ArgError("answer", "must be accept or dismiss")
                ctx.action(w.answerDialog(answer == "accept", a.str("text", max = 2000)))
            }
            "key" -> {
                val spec = a.str("key", max = 60) ?: throw ArgError("key", "required for action key")
                val combo = CdpKeys.parseCombo(spec) ?: throw ArgError("key", "not a supported key or combination: '$spec'. Use names like Enter, Escape, Tab, ArrowDown, Control+A, Shift+Tab")
                // 剪贴板快捷键会把用户剪贴板里的东西交给网页(或改掉剪贴板):不做
                if (combo.modifiers.any { it == KeyModifier.Control || it == KeyModifier.Meta } && combo.key.code in CLIPBOARD_KEYS) {
                    throw ArgError("key", "clipboard shortcuts (copy, cut, paste) are not allowed; use web_type_text to enter text")
                }
                // 回车一类的键可能提交、换页:和点击一样先补足这一页的停留
                try { ctx.dwell?.before(port = w) } catch (e: DwellStop) { return dwellStopped(e) }
                ctx.action(w.pressCombo(combo))
            }
            "select" -> {
                val ref = a.ref() ?: throw ArgError("ref", "required for action select")
                val option = a.str("option", max = 200) ?: throw ArgError("option", "required for action select")
                ctx.withPortRef(ref) { r -> ctx.action(w.selectOption(r!!.first, r.second, option)) }
            }
            "hover" -> {
                val at = point(a, ctx, "ref", "x", "y") ?: throw ArgError("ref", "give ref+snapshotId or x+y")
                when (val p = portPoint(at, ctx)) { null -> ctx.action(ActionResult.Failed("ref_expired")); else -> ctx.action(w.hover(p)) }
            }
            else -> {
                val from = point(a, ctx, "ref", "x", "y") ?: throw ArgError("ref", "give ref+snapshotId or x+y for where the drag starts")
                val to = point(a, ctx, "toRef", "toX", "toY") ?: throw ArgError("toRef", "give toRef (with snapshotId) or toX+toY for where the drag ends")
                val steps = a.int("steps", 1, DRAG_STEPS_MAX) ?: DRAG_STEPS_DEFAULT
                val pf = portPoint(from, ctx); val pt = portPoint(to, ctx)
                if (pf == null || pt == null) ctx.action(ActionResult.Failed("ref_expired")) else ctx.action(w.drag(pf, pt, steps))
            }
        }
    }

    /** 一个落点:编号([refKey],snapshotId 共用)或 0–999 坐标;两样都给、坐标只给一半时报错,都没给为 null。 */
    private suspend fun point(a: Args, ctx: ToolContext, refKey: String, xKey: String, yKey: String): WebPoint? {
        val ref = a.str(refKey)
        val x = a.coord(xKey); val y = a.coord(yKey)
        if (ref != null) {
            if (x != null || y != null) throw ArgError(xKey, "give either $refKey or $xKey+$yKey, not both")
            if (!PAGE_REF_RE.matches(ref)) throw ArgError(refKey, "must look like @e3")
            val sid = a.str("snapshotId") ?: throw ArgError("snapshotId", "required when $refKey is given")
            return WebPoint.Ref(ref, sid)
        }
        if ((x == null) != (y == null)) throw ArgError(if (x == null) xKey else yKey, "give both $xKey and $yKey")
        if (x == null) return null
        val s = ctx.lastOrSnapshot()
        return WebPoint.Xy(toPx(x, s.width), toPx(y!!, s.height))
    }

    /** 稳定编号开着时把模型编号翻译成端口编号;已退役为 null(ref_expired)。 */
    private fun portPoint(p: WebPoint, ctx: ToolContext): WebPoint? {
        val st = ctx.session.stabilizer
        if (p !is WebPoint.Ref || st == null) return p
        val r = st.resolve(p.ref, p.snapshotId) as? Resolved.Current ?: return null
        return WebPoint.Ref(r.portRef, r.portSnapshotId)
    }

    override fun logTarget(o: JsonObject, r: ToolExecution, ctx: ToolContext) =
        listOfNotNull(o.argStr("action"), o.argStr("answer"), o.argStr("key") ?: o.argStr("option")?.let { "「${it.take(40)}」" },
            o.argStr("ref")?.let { ref -> ctx.lastSnapshot?.elements?.firstOrNull { it.ref == ref }?.name?.takeIf(String::isNotBlank) ?: ref },
            o.argStr("toRef")?.let { "→ $it" }).joinToString(" ")
}
