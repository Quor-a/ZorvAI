package com.ai.assistance.quro.core.mika.uistate

import com.ai.assistance.quro.core.mika.prompt.Feedback
import com.ai.assistance.quro.core.mika.replay.Anchors
import com.ai.assistance.quro.core.mika.replay.Selector
import com.ai.assistance.quro.core.mika.tool.AgentTool
import com.ai.assistance.quro.core.mika.tool.LOCATED_REF
import com.ai.assistance.quro.core.mika.tool.SurfaceLayer
import com.ai.assistance.quro.core.mika.tool.ToolContext
import com.ai.assistance.quro.core.mika.tool.ToolDispatcher
import com.ai.assistance.quro.core.mika.tool.ToolConfig
import com.ai.assistance.quro.core.mika.tool.ToolExecution
import com.ai.assistance.quro.core.mika.tool.ToolIndex
import com.ai.assistance.quro.core.mika.tool.Until
import com.ai.assistance.quro.core.mika.tool.UntilOutcome
import com.ai.assistance.quro.core.mika.tool.Verb
import com.ai.assistance.quro.core.mika.tool.argStr
import com.ai.assistance.quro.core.mika.tool.fail
import com.ai.assistance.quro.core.mika.tool.failCode
import com.ai.assistance.quro.core.mika.tool.writesScreen
import com.ai.assistance.quro.core.mika.visual.Shots
import com.ai.assistance.quro.core.mika.vision.LocateResult
import com.ai.assistance.quro.core.mika.vision.Locator
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 一次动作落在哪里,由界面一侧给出,执行入口的不可逆判定与审批只读它:
 * [args] 是判定用的参数(点看图找到的 @vN 时换成它登记的坐标与所见),[aimed] 是模型那一眼(按 snapshotId 找到的那次读屏,
 * 没给编号时是坐标换算会用的那一屏),[bySight] 表示点的是看图得来的位置,[seen] 是视觉模型复看确认过的所见文字,
 * [readAgeMs] 是那次读屏距今多久(不知道为 null)。[now] 此刻重新看一眼(只在要判不可逆时读;不登记编号)。
 */
class ActionTarget internal constructor(
    val args: JsonObject,
    val aimed: ScreenSnapshot?,
    val bySight: Boolean,
    val seen: String?,
    val readAgeMs: Long?,
    private val look: suspend () -> ScreenSnapshot?,
) {
    suspend fun now(): ScreenSnapshot? = look()
}

/** 按描述指目标的参数:元素显示的字、名字或输入框提示语;可再加角色。 */
const val DESCRIBED_ELEMENT = "element"
const val DESCRIBED_ROLE = "role"
/** 和描述同时给就不知道该按哪个。 */
private val DESCRIBED_CONFLICTS = listOf("ref", "snapshotId", "x", "y")
/** 页面弹框挂着时的错误码(见 cap/web 的 DIALOG_OPEN)。 */
private const val DIALOG_OPEN_CODE = "dialog_open"
/** 不止一个对得上时最多列几个。 */
private const val DESCRIBED_LISTED = 6
/** 看图找过、没找到或没确认的原因(其余是画面拿不到、不能看)。 */
private val SIGHT_MISSED = setOf("not_found", "name_mismatch", "not_confirmed", "unverified_grounding", "vision_unavailable")

/** 按描述找目标的结果:[Resolved] 是换成编号后的参数和找到的元素,[Failed] 是给模型的失败结果。 */
sealed interface Described {
    /**
     * [note]:按开头或包含对上时附进动作结果的说明(描述了什么、实际对上了谁);完全相同为 null。
     * [element] 为 null:树里没有,看图找到的点(参数换成 @vN)。
     */
    class Resolved(val args: JsonObject, val element: UiElement?, val note: JsonObject? = null) : Described
    class Failed(val result: ToolExecution) : Described
}

/**
 * 一个任务里有界面的能力(手机、网页)共用的界面状态:每块屏一份读屏状态、在操作哪块屏、给执行入口看的动作目标、路线要的目标与锚点。
 * 分发器在每次调用前后经它做界面上的收尾(动作前看一眼、比出实际效果、附上动作之后的界面、清掉看图得来的点);
 * 分发器和核心的循环不认识它,只经 [SurfaceLayer] 用到它。开局由 [attach] 放进工具上下文,有界面的工具从上下文里取。
 */
internal class Interfaces(
    surfaces: List<Surface>,
    stableRefs: Boolean,
    private val config: ToolConfig,
    val shots: Shots,
    /** 最近一次读到的界面文字(几块屏共用);动作之后读到的和它一样就不再记一遍。 */
    lastRead: () -> String?,
) : SurfaceLayer {
    companion object {
        /**
         * 本任务的界面状态:[surfaces] 是接上了端口的屏,[stableRefs] 开着时跨读屏用稳定编号(动作自带屏幕、元素优先时总是开着)。
         * 放进 [ctx],有界面的工具从那里取。
         */
        fun attach(ctx: ToolContext, surfaces: List<Surface>, stableRefs: Boolean): Interfaces =
            Interfaces(surfaces, stableRefs || ctx.config.inlineScreen || ctx.config.adaptiveVision, ctx.config, ctx.shots) {
                ctx.evidence.recentReads.lastOrNull()
            }.also { ctx[INTERFACES] = it }
    }

    val focus = SurfaceFocus(surfaces)
    val sessions: Map<String, ScreenSession> = focus.surfaces.associate { s ->
        s.id to ScreenSession(s, stableRefs, config.visionEnabled, config.recordFrames, lastRead, shots, config.vision, config.adaptiveVision)
    }
    private val gateView = GateView()

    suspend fun checkAppAccess(tool: AgentTool?, after: Boolean = false) {
        if (tool == null || tool.offScreen || tool.capability !in sessions || tool.verb == Verb.ListApps) return
        sessionFor(tool).checkAppAccess(current = after || tool.verb !in APP_TARGET_VERBS)
    }

    /** 此刻在操作的那块屏的读屏状态。 */
    val session: ScreenSession get() = sessions.getValue(focus.current.id)

    override val current: String get() = focus.current.id

    override fun enter(id: String?) = focus.enter(id)

    override fun leave(succeeded: Boolean) = focus.leave(succeeded)

    /** 开着时写操作执行前取目标元素与锚点(回放开关打开时由循环设置)。 */
    override var captureTargets: Boolean = false

    /** 工具 [tool] 那块屏的读屏状态;不是屏幕工具时是最近操作的那块。 */
    private fun sessionFor(tool: AgentTool?): ScreenSession = tool?.capability?.let(sessions::get) ?: session

    /** 这个工具作用在哪块屏上;不是屏幕工具为 null。 */
    override fun surfaceOf(tool: AgentTool?): String? = tool?.capability?.takeIf { it in sessions }

    /**
     * 用另一块屏的编号操作这块屏的工具:结果是 wrong_surface,说明编号属于哪块屏、该用哪个工具。
     * 认不出编号是哪块屏的(过期太久、拼错)时为 null,照常交给工具(按过期处理)。
     */
    override fun misaimed(tool: AgentTool, args: JsonObject, tools: ToolIndex): ToolExecution? {
        val own = tool.capability?.let(sessions::get) ?: return null
        val sid = args.argStr("snapshotId") ?: return null
        if (own.owns(sid)) return null
        val owner = sessions.values.firstOrNull { it.owns(sid) }
            ?: sessions.values.firstOrNull { s -> sid.startsWith(s.surface.family.snapshotPrefix) && sid.drop(s.surface.family.snapshotPrefix.length).firstOrNull()?.isDigit() == true }
            ?: return null
        if (owner === own) return null
        val ownerId = owner.surface.id
        return fail("wrong_surface", Feedback.format("wrong_surface",
            "id" to sid, "owner" to ownerId, "tool" to tool.name, "surface" to own.surface.id,
            "instead" to (tools.counterpart(tool.name, ownerId) ?: tools.nameOf(Verb.Observe, ownerId) ?: ownerId),
            "observe" to (tools.nameOf(Verb.Observe, own.surface.id) ?: tool.name)))
    }

    /** 一次调用之前在界面上做的:路线要的目标、改变界面的动作前先看一眼。由 [finish] 收尾。 */
    private class Prep(
        val session: ScreenSession,
        val probe: ScreenSession.EffectProbe?,
        val aimed: Pair<UiElement?, List<Int>?>?,
    ) {
        /** 这次调用由界面一侧报实际效果(effect)。 */
        val reportsEffect: Boolean get() = probe != null
    }

    override suspend fun around(tool: AgentTool, args: JsonObject, inlineScreen: Boolean, body: suspend (reportsEffect: Boolean) -> ToolExecution): ToolExecution {
        if (tool.offScreen || tool.verb == Verb.ListApps) return body(false)
        // 按目标启动可离开受限应用;此时不采集旧页面,只在启动后附上允许的页面。
        if (tool.verb in APP_TARGET_VERBS) {
            val raw = body(false)
            return finish(tool, args, Prep(session, null, null), raw, inlineScreen)
        }
        val prep = prepare(tool, args)
        return finish(tool, args, prep, body(prep.reportsEffect), inlineScreen)
    }

    private suspend fun prepare(tool: AgentTool, o: JsonObject): Prep {
        val session = this.session
        gateView.capture = if (captureTargets && tool.routable) gateView.captureTarget(session, tool.verb, o) else null
        // 改变界面的动作:动作前先看一眼(元素表 + 画面),动作后比出实际效果
        val probe = if (tool.reportsEffect) session.probe(o.argStr("snapshotId")) else null
        val aimed = probe?.let { effectTarget(session, tool.verb, o, it.before) }
        return Prep(session, probe, aimed)
    }

    /** 调用不是界面上的(或没走到 [prepare]):路线目标不留。 */
    override fun skip() { gateView.capture = null }

    /**
     * 一次调用之后:成功的动作按 [inlineScreen] 附界面差异;画面按观察策略附上,并报回实际效果;
     * 动过这块屏之后(失败的也算,参数不对没执行的不算),这块屏上之前看图得来的点就不再可信。
     */
    private suspend fun finish(tool: AgentTool, o: JsonObject, prep: Prep, raw: ToolExecution, inlineScreen: Boolean): ToolExecution {
        val session = prep.session
        session.probeDone()
        val readsAtAction = session.reads
        var result = raw
        val ok = result.content["ok"] == JsonPrimitive(true)
        if (inlineScreen && tool.showsScreenAfter && ok) result = session.withScreen(result)
        // 旧模式的视觉主模型直接看动作后画面；分级模式只在界面差异或下次 observe 需要时补图。
        if (tool.showsScreenAfter && ok && !config.adaptiveVision && result.images.isEmpty() && "screen" !in result.content) result = session.withFrame(result)
        val probe = prep.probe
        val aimed = prep.aimed
        if (ok && probe != null && aimed != null) {
            val typed = if (tool.verb == Verb.TypeText) o.argStr("text") else null
            result = session.withEffect(result, probe, tool.verb, readsAtAction, aimed.first, aimed.second, typed)
        }
        if (tool.writesScreen && result.content["error"] != JsonPrimitive("invalid_arguments")) session.clearLocated()
        return result
    }

    /**
     * 判效果用的目标:按编号的动作是模型那一屏里的那个元素,输入、清空没给编号时是动作前有焦点的输入框;
     * 画面区域是目标的范围,坐标动作(含看图找到的点)是落点周围一小块。都没有为 (null, null)。
     */
    private fun effectTarget(session: ScreenSession, verb: Verb, o: JsonObject, before: ScreenSnapshot?): Pair<UiElement?, List<Int>?> {
        val ref = o.argStr("ref")?.takeIf { !LOCATED_REF.matches(it) }
        val el = when {
            ref != null -> gateView.snapshotFor(session, o)?.elements?.firstOrNull { it.ref == ref }
            verb == Verb.TypeText || verb == Verb.ClearText -> before?.elements?.firstOrNull { it.focused && it.editable }
            else -> null
        }
        fun num(a: JsonObject, k: String) = (a[k] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toDoubleOrNull()?.toInt()
        val point = gateView.gateArgs(session, verb, o).let { a -> num(a, "x")?.let { x -> num(a, "y")?.let { y -> x to y } } }
        val region = el?.bounds?.takeIf { it.size == 4 } ?: point?.let { (x, y) ->
            val d = EffectTuning.POINT_REGION
            listOf((x - d).coerceAtLeast(0), (y - d).coerceAtLeast(0), (x + d).coerceAtMost(999), (y + d).coerceAtMost(999))
        }
        return el to region
    }

    /**
     * 按描述指目标的点击、打字([DESCRIBED_ELEMENT] 参数,没有编号和坐标):此刻重读一屏(登记编号),找唯一对上的元素,
     * 把参数换成它的 ref + snapshotId,之后的判定、审批、执行、核实都和按编号的一样。
     * 打字时树缺了一块或不能用、里面没有对得上的输入框,就看图找(见 [bySight])。
     * 不是这类调用为 null;找不到、不止一个、参数冲突时是给模型的失败结果,不执行。
     */
    suspend fun described(tool: AgentTool?, args: JsonObject): Described? {
        val verb = tool?.verb
        if (verb != Verb.Tap && verb != Verb.TypeText) return null
        val session = tool.capability?.let(sessions::get) ?: return null
        val text = args.argStr(DESCRIBED_ELEMENT)
        if (text == null) {
            if (DESCRIBED_ROLE in args) return Described.Failed(fail("invalid_arguments", "$DESCRIBED_ROLE: only used together with $DESCRIBED_ELEMENT"))
            return null
        }
        DESCRIBED_CONFLICTS.firstOrNull { it in args }?.let {
            return Described.Failed(fail("invalid_arguments", "$DESCRIBED_ELEMENT: give either $DESCRIBED_ELEMENT or $it, not both"))
        }
        val role = args.argStr(DESCRIBED_ROLE)?.trim()?.ifEmpty { null }
        // 内部读的一屏:模型没看到,不算它读过(不清掉「手里那屏已过时」);这一屏留给接着的动作当「动作前」
        val snap = screenAttempt { session.snapshot(internal = true) }
            ?: return Described.Failed(fail("screen_unavailable", Feedback["screen_unavailable"]))
        // 页面弹框挂着:页面停着,什么都点不到
        if (snap.web?.dialog != null) {
            session.clearPrimed()
            return Described.Failed(failCode(DIALOG_OPEN_CODE))
        }
        val observe = session.surface.family.tool("observe")
        val shown = text.trim().take(80)
        val m = DescribedTarget.match(snap.elements, ElementQuery(text, role, field = verb == Verb.TypeText))
        if (m !is DescribedMatch.Found) session.clearPrimed()
        return when (m) {
            is DescribedMatch.Found -> Described.Resolved(
                JsonObject(args.filterKeys { it != DESCRIBED_ELEMENT && it != DESCRIBED_ROLE } +
                    mapOf("ref" to JsonPrimitive(m.element.ref), "snapshotId" to JsonPrimitive(snap.snapshotId))),
                m.element,
                // 不是完全相同的名字:如实说出按描述对上的是哪个
                if (m.exact) null else JsonObject(mapOf("resolvedFrom" to JsonPrimitive(shown), "matched" to JsonPrimitive(m.element.name.take(80)),
                    "matchedRef" to JsonPrimitive(m.element.ref), "snapshotId" to JsonPrimitive(snap.snapshotId))),
            )
            is DescribedMatch.Ambiguous -> Described.Failed(fail("element_ambiguous", Feedback.format("described.ambiguous",
                "text" to shown, "count" to m.candidates.size, "snapshot" to snap.snapshotId,
                "candidates" to m.candidates.take(DESCRIBED_LISTED).joinToString("; ") { e ->
                    val near = DescribedTarget.neighbour(e, snap.elements)?.let { " near 「${it.take(30)}」" }.orEmpty()
                    val off = if (DescribedTarget.usable(e, verb == Verb.TypeText)) "" else " (not actionable)"
                    "${e.ref} ${e.role} 「${e.name.take(40)}」 at ${e.bounds}$near$off"
                })))
            DescribedMatch.None -> {
                // 打字而这一屏的树缺了一块或不能用:树里对不上不等于屏上没有,改看图找这个框
                val locator = session.locator
                if (verb == Verb.TypeText && locator != null && session.treeBlind(snap)) bySight(session, locator, args, shown)
                else Described.Failed(fail("element_not_found", Feedback.format("described.none",
                    "text" to shown, "role" to (role?.let { " with role $it" } ?: ""), "observe" to observe)))
            }
        }
    }

    /**
     * 看图找按描述指的输入框(和 find_visually 同一套核对:红框复看,没佐证的标出来),参数换成找到的 @vN:
     * 之后和模型自己传 @vN 一样,先核对画面没变、那一下点击过不可逆判定,粘贴后按屏上文字核对点没点中。
     */
    private suspend fun bySight(session: ScreenSession, locator: Locator, args: JsonObject, shown: String): Described =
        when (val r = locator.locate(shown, null)) {
            // 看了没找到(或看图这一下出错):换个说法或给点;画面拿不到、不能看(受保护、还没开 App)照 find_visually 的说明
            is LocateResult.NotFound -> Described.Failed(fail("element_not_found", Feedback.format("described.not_seen",
                "text" to shown, "reason" to r.reason,
                "next" to if (r.reason in SIGHT_MISSED) Feedback["described.not_seen.retry"] else r.message)))
            is LocateResult.Found -> {
                session.locatedInfo[r.ref] = Located(shown, null, r.seen, r.unverified)
                Described.Resolved(
                    JsonObject(args.filterKeys { it != DESCRIBED_ELEMENT && it != DESCRIBED_ROLE } +
                        mapOf("ref" to JsonPrimitive(r.ref), "snapshotId" to JsonPrimitive(r.snapshotId))),
                    null,
                    JsonObject(listOfNotNull("resolvedFrom" to JsonPrimitive(shown), "foundBySight" to JsonPrimitive(true),
                        r.seen?.let { "seen" to JsonPrimitive(it) }).toMap()),
                )
            }
        }

    /** 执行入口看的这一步的目标(见 [ActionTarget]);只读,不执行任何动作。 */
    fun actionTarget(tool: AgentTool?, args: JsonObject): ActionTarget {
        val session = sessionFor(tool)
        val verb = tool?.verb
        val checked = gateView.gateArgs(session, verb, args)
        return ActionTarget(checked, gateView.snapshotFor(session, checked), gateView.visionSourced(session, verb, args),
            gateView.confirmedSeen(session, verb, args), snapshotAgeMs(args.argStr("snapshotId")), ::peek)
    }

    /** 那次读屏距今多少毫秒;不在任何一块屏最近几次里为 null。 */
    private fun snapshotAgeMs(snapshotId: String?): Long? = sessions.values.firstNotNullOfOrNull { s ->
        s.recentSnapshots.firstOrNull { it.snapshotId == snapshotId }?.let { snap -> s.readAt[snap.snapshotId]?.let { System.currentTimeMillis() - it } }
    }

    /** 那次读屏(按工具 [tool] 那块屏、参数里的 snapshotId 找);不在最近几次里为 null。 */
    fun snapshotFor(tool: AgentTool?, args: JsonObject): ScreenSnapshot? = gateView.snapshotFor(sessionFor(tool), args)

    /** 运行记录用:模型编号此刻对应的端口编号;稳定编号关着、没有 @eN 或编号已过期时为空。 */
    override fun portRecord(tool: AgentTool?, args: JsonObject): JsonObject = gateView.portRecord(sessionFor(tool), args)

    /** 上一次调用是写操作时,它的目标元素(没有或认不出为 null)与动作前锚点;否则为 null。 */
    fun lastActionTarget(): Pair<Selector?, Anchors>? = gateView.capture?.let { it.target to it.before }

    /** 上一次写操作前的那一屏受系统防截屏保护。 */
    fun lastActionProtected(): Boolean = gateView.capture?.snapshot?.protected == true

    /** 上一次写操作之后此刻读一屏(不登记编号),取动作后新出现的文字;不是写操作或读不到为 null。 */
    suspend fun actionAfter(): Anchors? {
        if (gateView.capture == null) return null
        return peek()?.let(::anchorsAfter)
    }

    /** 同上,用调用方已经读好的动作后一屏。 */
    fun anchorsAfter(after: ScreenSnapshot): Anchors? = gateView.anchorsAfter(after)

    /** 此刻重新读最近操作的那块屏,不登记编号、不改模型手里的读屏;读不到为 null。 */
    suspend fun peek(): ScreenSnapshot? = session.peek()

    /** 等最近操作的那块屏稳定,最多 [timeoutMs];读不到状态也照常返回。 */
    override suspend fun settle(timeoutMs: Long) {
        screenAttempt { session.screen.waitStable(timeoutMs) }
    }

    /** 最近操作的那块屏此刻的一帧(原图)和它的画面编号,登记进回看;受保护页面、付款页不截,截不到为 null。 */
    override suspend fun visibleText(): List<String> = peek()?.elements?.map { it.name }.orEmpty()

    override suspend fun waitUntil(until: Until, clock: () -> Long, interrupted: () -> Boolean): UntilOutcome =
        UntilWait(session.screen, ::peek, clock, interrupted).run(until)

    override suspend fun imageNow(): Pair<ByteArray, Int>? = session.currentFrame()

    /** 主模型能看图时动作之后发的一屏:画面和元素表是同一时刻的;屏上还没有 App 时为 null。 */
    override suspend fun viewNow(): ToolDispatcher.Glance? = session.currentView()

    /** 模型此刻还看得到哪些读屏(裁剪、压缩之后);差异的 base 不在其中时发全量。 */
    override fun setVisible(ids: Set<String>) { sessions.values.forEach { it.visible = ids } }

    /** 运行状态里写的这块屏此刻的版本(网页的手机版、电脑版);没有或读不到为 null。 */
    override suspend fun mode(): String? = focus.surfaces.firstNotNullOfOrNull { it.mode }?.invoke()
}

private val APP_TARGET_VERBS = setOf(Verb.LaunchApp, Verb.OpenLink, Verb.ShareText, Verb.SetAlarm, Verb.SetTimer, Verb.ShowAlarms, Verb.AddCalendarEvent)
