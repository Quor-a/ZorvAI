package com.ai.assistance.quro.core.mika.uistate

import com.ai.assistance.quro.core.mika.capability.CapabilityKey
import com.ai.assistance.quro.core.mika.prompt.Feedback
import com.ai.assistance.quro.core.mika.tool.FAILURE_MESSAGES
import com.ai.assistance.quro.core.mika.tool.HINT_NOT_SETTLED
import com.ai.assistance.quro.core.mika.tool.HINT_TAP_UNCHANGED
import com.ai.assistance.quro.core.mika.tool.ToolContext
import com.ai.assistance.quro.core.mika.tool.ToolDispatcher
import com.ai.assistance.quro.core.mika.tool.ToolExecution
import com.ai.assistance.quro.core.mika.tool.fail
import com.ai.assistance.quro.core.mika.tool.okObj
import com.ai.assistance.quro.core.mika.tool.switchHint
import com.ai.assistance.quro.core.mika.vision.LocateRegistry
import com.ai.assistance.quro.core.mika.vision.Locator
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/*
 * 工具上下文里的界面状态:开局由 [Interfaces.attach] 放进去,有界面的工具(手机、网页、通用工具里点名屏幕的)从这里取。
 * 工具引擎(core/tool)不认识这些,只经 [com.ai.assistance.quro.core.mika.tool.SurfaceLayer] 用到界面一侧。
 */

/** 界面状态在工具上下文里的键。 */
internal val INTERFACES = CapabilityKey<Interfaces>("interfaces")

/** 本任务的界面状态(开局放进来的那一份)。 */
internal val ToolContext.interfaces: Interfaces get() = this[INTERFACES] ?: error("interfaces not attached")

/** 分发器那个任务的界面状态(执行入口、回放按读屏判定时用)。 */
internal val ToolDispatcher.interfaces: Interfaces get() = ctx.interfaces

/** 每块屏的读屏状态,按能力的 id。 */
internal val ToolContext.sessions: Map<String, ScreenSession> get() = interfaces.sessions

/** 最近操作的是哪块屏。 */
internal val ToolContext.focus: SurfaceFocus get() = interfaces.focus

/** 此刻在操作的那块屏的读屏状态(分发器在每次调用前按工具所属的屏记下,见 [focus])。 */
internal val ToolContext.session: ScreenSession get() = sessions.getValue(focus.current.id)
internal val ToolContext.screen: ScreenPort get() = session.screen

/** 此刻在操作的那块屏。 */
internal val ToolContext.surface: Surface get() = focus.current
internal val ToolContext.lastSnapshot: ScreenSnapshot? get() = session.lastSnapshot

/** 此刻那块屏上 find_visually 找到的点(@vN);那块屏上任何写操作后清空。 */
internal val ToolContext.located: LocateRegistry get() = session.located
internal val ToolContext.locatedInfo: MutableMap<String, Located> get() = session.locatedInfo

/** 上次写操作之后看过 describe:这期间的坐标点击按看图得来的点对待。 */
internal var ToolContext.described: Boolean
    get() = session.described
    set(v) { session.described = v }
internal val ToolContext.locator: Locator? get() = session.locator

internal fun ToolContext.clearLocated() = session.clearLocated()

/** 端口的动作结果转成工具结果:没变化、被切到别的 App、页面没静下来都写进提示;失败按错误码给说明。 */
internal fun ToolContext.action(r: ActionResult): ToolExecution = when (r) {
    is ActionResult.Ok -> {
        // 被切到别的 App(不是目标 App 自己调起的付款页之类):付款申报作废
        if (r.appSwitched != null && !r.switchedByTarget) refusedPay = null
        val hints = listOfNotNull(
            HINT_TAP_UNCHANGED.takeIf { r.changed == false && !reportsEffect && r.opened == null },
            // 开到用户主屏上又挪回来的,由下面那句说清,不再说被切走
            r.appSwitched?.takeIf { r.opened == null }?.let { switchHint(it, r.switchedByTarget) },
            HINT_NOT_SETTLED.takeIf { r.settled == false },
            r.coveredBy?.let { Feedback.format("covered_by", "pkg" to it) },
            r.opened?.let { openedHint(it) },
            // 系统在用户屏上问的,留给用户选:不说怎么选
            r.chooser?.takeIf { r.opened?.picker != true }?.let { Feedback.format("launch.chooser", "pkg" to it.packageName) },
            // 要的就是另一份时,开出另一份不用再提醒;开出的不是要的那份,照实说
            (r.opened?.inProfile ?: r.inProfile)?.takeIf { it == OpenedElsewhere.OTHER_PROFILE && r.requestedUser == null }?.let { Feedback["profile.other"] },
            r.requestedUser?.takeIf { r.profileUser != null && r.profileUser != it }?.let { wanted ->
                if (r.inProfile == OpenedElsewhere.MAIN_PROFILE) Feedback["profile.main_not_requested"]
                else Feedback.format("profile.copy_not_requested", "user" to "${r.profileUser}", "wanted" to "$wanted")
            },
            r.hint,
        )
        ToolExecution(okObj {
            r.changed?.let { put("changed", it) }
            r.appSwitched?.let { put("app_switched", it) }
            if (r.appSwitched != null && r.switchedByTarget) put("opened_by_target", true)
            if (r.relocated) put("relocated", true)
            r.relocatedTo?.takeIf { r.relocated }?.let { put("relocatedTo", it) }
            if (r.settled == false) put("settled", false)
            r.coveredBy?.let { put("coveredBy", it) }
            r.chooser?.let { put("chooser", it.toJson()) }
            r.opened?.let { put("opened", it.toJson()) }
            r.inProfile?.let { put("inProfile", it) }
            r.profileUser?.let { put("profileUser", it) }
            r.requestedUser?.let { wanted -> r.profileUser?.let { put("requestedCopy", it == wanted) } }
            if (hints.isNotEmpty()) put("hint", hints.joinToString(" "))
        }, changed = r.changed)
    }
    is ActionResult.Failed ->
        if (r.reason == "ref_expired") fail("ref_expired", Feedback["ref_expired"])
        else {
            val code = r.reason.takeIf { Regex("^[a-z][a-z0-9_]*$").matches(it) } ?: "action_failed"
            val message = FAILURE_MESSAGES[code] ?: Feedback.format("action_failed", "reason" to r.reason)
            fail(code, r.hint?.let { "$message $it" } ?: message)
        }
}

/**
 * 动作让 App 开到了用户主屏上时给模型的那句:挪回来了就说在 ZorvAI 的屏上接着做、别再点一次;系统在用户屏上问打开哪一个的,
 * 说留给用户选、别再点也别替用户选,下一个动作会等用户选完;用户关了那个问题、用户亲手处理完了的,照实说;
 * 要用户亲手处理的弹窗(登录验证一类)留在用户屏上,说别挪回来、请用户在那里点;留在用户屏上没动的(用户正用着),
 * 说别再来、请用户处理。
 */
internal fun openedHint(o: OpenedElsewhere): String {
    val app = o.app ?: o.packageName
    return when {
        o.closed -> Feedback["spill.picker_closed"]
        o.byUser -> Feedback.format("spill.user_done", "app" to app)
        o.picker && o.onUserScreen -> Feedback.format("spill.user_picking", "app" to app)
        o.needsUser && o.onUserScreen -> Feedback.format("spill.user_popup", "app" to app)
        o.onUserScreen -> Feedback.format("spill.in_use", "app" to app)
        else -> Feedback.format("spill.pulled_back", "app" to app)
    }
}

/**
 * 稳定编号开着时先把模型编号翻译成端口编号再交给 [act]:编号已退役返回 ref_expired,不执行;
 * 旧一屏的编号仍在最新一屏时照常执行并标 relocated。运行记录里另记端口编号。
 */
internal suspend fun ToolContext.withPortRef(ref: Pair<String, String>?, act: suspend (Pair<String, String>?) -> ToolExecution): ToolExecution {
    val st = session.stabilizer
    if (st == null || ref == null) return act(ref)
    val r = st.resolve(ref.first, ref.second) as? Resolved.Current ?: return action(ActionResult.Failed("ref_expired"))
    val e = act(r.portRef to r.portSnapshotId)
    val content = if (r.relocated && e.content["ok"] == JsonPrimitive(true)) JsonObject(e.content + ("relocated" to JsonPrimitive(true))) else e.content
    return ToolExecution(content, changed = e.changed, outcome = e.outcome, frame = e.frame, images = e.images,
        record = JsonObject(e.record + buildJsonObject { put("portRef", r.portRef); put("portSnapshotId", r.portSnapshotId) }))
}

/** 坐标换算用的一屏:最近一次读屏,还没读过就读一次。 */
internal suspend fun ToolContext.lastOrSnapshot(): ScreenSnapshot = session.lastSnapshot ?: session.snapshot()

/** 确认申报的 app 按当前那块屏的写法校验(手机上是包名,网页上是站点主机名)。 */
internal fun ToolContext.confirmTarget(app: String): String = surface.declaredTarget(app)
