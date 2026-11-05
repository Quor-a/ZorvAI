package com.ai.assistance.quro.core.mika.action

import com.ai.assistance.quro.core.mika.tool.Risk
import com.ai.assistance.quro.core.mika.gate.ConfirmationGate
import com.ai.assistance.quro.core.mika.gate.Irreversible
import com.ai.assistance.quro.core.mika.guard.DIAGNOSTIC_VERBS
import com.ai.assistance.quro.core.mika.guard.GuardVerdict
import com.ai.assistance.quro.core.mika.guard.OperationGuards
import com.ai.assistance.quro.core.mika.guard.PAGE_READ_VERBS
import com.ai.assistance.quro.core.mika.guard.GuardScope
import com.ai.assistance.quro.core.mika.guard.elementsHash
import com.ai.assistance.quro.core.mika.guard.resultFingerprint
import com.ai.assistance.quro.core.mika.model.Part
import com.ai.assistance.quro.core.mika.record.Redactor
import com.ai.assistance.quro.core.mika.record.RunEvent
import com.ai.assistance.quro.core.mika.replay.Anchors
import com.ai.assistance.quro.core.mika.replay.effectiveWrite
import com.ai.assistance.quro.core.mika.uistate.Described
import com.ai.assistance.quro.core.mika.uistate.ScreenSnapshot
import com.ai.assistance.quro.core.mika.uistate.interfaces
import com.ai.assistance.quro.core.mika.tool.ActionPass
import com.ai.assistance.quro.core.mika.tool.ToolDispatcher
import com.ai.assistance.quro.core.mika.tool.ToolExecution
import com.ai.assistance.quro.core.mika.tool.Verb
import com.ai.assistance.quro.core.mika.tool.parseArgs
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import com.ai.assistance.quro.core.mika.tool.SubActions
import com.ai.assistance.quro.core.mika.guard.OffScreenGuard
import com.ai.assistance.quro.core.mika.run.RunProvenance
import com.ai.assistance.quro.core.mika.run.SKIPPED_FOR_NEW_MESSAGE
import com.ai.assistance.quro.core.mika.run.waitOrSteer
import com.ai.assistance.quro.core.mika.tool.failCode
import com.ai.assistance.quro.core.mika.capability.Paced

/**
 * 所有改变外部世界的动作唯一的执行入口:[ToolDispatcher.execute] 要一张 [ActionPass],只有这里有。
 * 每个来源都按同一顺序:操作守卫 → 锁屏/被拿走的关卡(写操作)→ 不可逆判定与同意核对(按过关卡之后此刻的屏幕)→
 * 执行 → 守卫核对(失败熔断、画面变化、重读)→ 运行记录。来源之间有意的不同只写在 [OriginPolicy] 里。
 */
class ActionExecutor(
    private val tools: ToolDispatcher,
    private val guards: OperationGuards,
    confirmations: ConfirmationGate?,
    private val gate: suspend () -> Unit,
    val recorder: ActionRecorder,
    /** 不动屏幕的工具的守卫;没有为 null。 */
    private val offScreen: OffScreenGuard? = null,
    approvals: ApprovalSettings = ApprovalSettings.ASK,
    provenance: RunProvenance = RunProvenance(),
    selfPackage: String? = null,
    autonomous: Boolean = false,
    /** 同一网站、App 里连着做对外动作(发送、提交)时两次之间的间隔(见 [OutwardPacing]);默认不等。 */
    private val pacing: OutwardPacing = OutwardPacing.Off,
) {
    private val decision = ConfirmDecision(tools, confirmations, approvals, recorder = recorder, provenance = provenance, selfPackage = selfPackage, autonomous = autonomous)
    private val pass = ActionPass()

    /** 配了确认关卡(付款能经用户同意后做)。 */
    val confirms: Boolean = confirmations != null

    /** 不动屏幕的工具的守卫叫停的结论;没叫停为 null。 */
    val offScreenStop: GuardVerdict? get() = offScreen?.stop

    suspend fun run(call: Part.ToolCall, origin: Origin, followUp: FollowUp = FollowUp.NONE): ActionOutcome =
        run(call, origin, followUp, reads = null)

    /** [reads]:页面脚本一次调用里的读单独计数的范围(来源要求隔离时);那里的动作仍按任务计。 */
    private suspend fun run(call: Part.ToolCall, origin: Origin, followUp: FollowUp, reads: GuardScope?): ActionOutcome {
        val args = parseArgs(call.argsJson)
        // 不动屏幕的工具(沙箱一类):不进屏幕守卫,由各自的守卫另管
        val offScreen = tools.offScreen(call.name)
        val verb = tools.verb(call.name)
        // 用了另一块屏的编号的:不判不做、不记成在操作哪块屏,直接交给分发器说明该用哪个工具
        if (tools.misaimed(call.name, args) != null) {
            return ran(Step(call, args, origin, emptyList(), writing = false, offScreen, verb, null, followUp, reads))
        }
        // 之后的关卡、判定都按这次调用的那块屏(到这次调用结束)
        tools.aim(call.name, args)
        try {
            return aimed(call, origin, followUp, reads, args, offScreen, verb)
        } catch (e: com.ai.assistance.quro.core.mika.access.AppAccessDenied) {
            val content = buildJsonObject {
                put("ok", false); put("error", e.code); put("message", e.message); put("retryable", false)
                e.packageName?.let { put("package", it) }
            }
            // 单步:作为这次工具调用的结果交给模型,让它换路线或向用户说明;批和脚本按 Refused 自己处理
            if (origin.policy.recordsRefusal) followUp.refused(content)
            recorder.result(origin.step, call.name, verb, content, content, null, null, 0, origin.policy.source, call.id)
            return ActionOutcome.Refused(e.code, content, emptyList())
        } finally {
            // 成功与否由分发器记;这里只结束这一层
            tools.release(false)
        }
    }

    private suspend fun aimed(
        given: Part.ToolCall, origin: Origin, followUp: FollowUp, reads: GuardScope?, givenArgs: JsonObject, offScreen: Boolean, verb: Verb?,
    ): ActionOutcome {
        // 按描述指的目标先换成此刻那一屏里的编号:之后的判定、审批、执行和按编号的完全一样;找不到或不唯一就不做
        var call = given
        var args = givenArgs
        var note: JsonObject? = null
        tools.interfaces.checkAppAccess(tools.declared(call.name))
        when (val d = tools.interfaces.described(tools.declared(call.name), args)) {
            is Described.Failed -> return ran(Step(call, args, origin, emptyList(), writing = false, offScreen, verb, null, followUp, reads, preset = d.result))
            is Described.Resolved -> { args = d.args; call = call.copy(argsJson = d.args.toString()); note = d.note }
            null -> Unit
        }
        val policy = origin.policy
        val readScope = reads?.takeIf { verb in PAGE_READ_VERBS }
        val before = when {
            offScreen -> emptyList()
            readScope != null -> guards.beforeAction(call.name, args, verb in DIAGNOSTIC_VERBS, readScope)
            else -> guards.beforeAction(call.name, args, verb in DIAGNOSTIC_VERBS)
        }
        if (guards.stop != null) return refuse("guard_stopped", origin, before)
        if (policy.stopOnGuardWarning && before.isNotEmpty()) return refuse("guard_warning", origin, before)
        val writing = tools.writesScreen(call.name)
        if (writing && !policy.gatedByCaller) gate()
        // 没有可用的同意就不执行,也不计入失败熔断
        val d = decision.judge(call.name, args, origin)
        when (d) {
            is Decision.Required -> return blocked(call, args, origin, before, d.hit, null, followUp)
            is Decision.Mismatch -> return blocked(call, args, origin, before, d.hit, d.reason, followUp)
            Decision.Denied -> return refuse("approval_unavailable", origin, before)
            Decision.Unresolved -> return refuse("target_unresolved", origin, before)
            Decision.OwnApp -> return ownApp(call, args, origin, before, followUp)
            else -> Unit
        }
        // 对外动作:和同一网站、App 里的上一次隔开(等的时候任务还能取消,动作还没开始)
        val site = decision.outwardSite
        val paced = site?.let { pacing.waitBefore(it, recorder.now()) }?.takeIf { it > 0 } ?: 0L
        // 等间隔时用户插了话:这次不做,先让模型读那句话。间隔从上一次做完算起,下次再做时余下的照样补上
        if (paced > 0 && waitOrSteer { delay(paced) } == null) {
            return ran(Step(call, args, origin, before, writing = false, offScreen, verb, null, followUp, reads, preset = failCode(SKIPPED_FOR_NEW_MESSAGE)))
        }
        // 改动屏幕的动作:按这块屏的能力的节奏先停一会儿(App 里见 AppDwell),单步、一批里的每一步都在这里停;
        // 组合动作(脚本)本身不停,里面的每一步经这里各自停。到了屏数上限、停留中用户插了话就不做
        val screenPaced = writing && !offScreen && !tools.composite(call.name)
        val dwelt = if (screenPaced) tools.beforeWrite(call.name, outward = site != null) else Paced.NONE
        dwelt.stop?.let { stop ->
            return ran(Step(call, args, origin, before, writing = false, offScreen, verb, null, followUp, reads, preset = stop, pacedMs = paced + dwelt.waitMs))
        }
        if (writing) {
            guards.noteAction()
            decision.noteWrite(call.name)
        }
        val step = Step(call, args, origin, before, writing, offScreen, verb, (d as? Decision.Traced)?.detail, followUp, reads, note = note,
            pacedMs = paced + dwelt.waitMs, idleScrolls = dwelt.idleScrolls, outward = site != null)
        // 动作一旦开始就做完并记下:脚本超时、任务取消都不能把它掐在半中间、漏掉记录
        // 对外动作告诉这块屏的能力(网页的页数上限只管浏览,见 PageDwell)
        site?.let(tools::outwardStart)
        var done = false
        try {
            val outcome = if (policy.actionCapMs == null) ran(step) else withContext(NonCancellable) { ran(step) }
            if (screenPaced && outcome is ActionOutcome.Ran) tools.afterWrite(call.name, outcome.exec.content)
            if (site != null && outcome is ActionOutcome.Ran && outcome.exec.content["ok"] != JsonPrimitive(false)) {
                done = true
                pacing.noteDone(site, recorder.now())
            }
            return outcome
        } finally {
            site?.let { tools.outwardEnd(it, done) }
        }
    }

    private class Step(
        val call: Part.ToolCall, val args: JsonObject, val origin: Origin, val before: List<GuardVerdict>, val writing: Boolean,
        val offScreen: Boolean, val verb: Verb?, val traced: String?, val followUp: FollowUp, val reads: GuardScope?,
        /** 没交给工具就有的结果(按描述找不到目标)。 */
        val preset: ToolExecution? = null,
        /** 附进结果的说明(按描述对上的是谁)。 */
        val note: JsonObject? = null,
        /** 执行前为对外动作的间隔、这块屏的节奏等了多久;算进这次调用的等待。 */
        val pacedMs: Long = 0,
        /** 执行前的停留里轻轻滚了几下;只进运行记录。 */
        val idleScrolls: Int = 0,
        /** 不可逆判定认出是对外动作(发送、提交);记进这次的活动。 */
        val outward: Boolean = false,
    )

    private suspend fun ran(s: Step): ActionOutcome {
        val call = s.call
        val policy = s.origin.policy
        val n = s.origin.step
        // 页面脚本里的每个动作经这里回来,同样走完整个入口;脚本里的读只在这一次调用里计数
        val subVerdicts = mutableListOf<GuardVerdict>()
        val sub = if (tools.composite(call.name)) {
            val script = Origin.Script(n)
            val window = if (script.policy.isolatedReads) GuardScope(canStop = false) else null
            SubActions { c ->
                val o = run(c, script, FollowUp.NONE, window)
                subVerdicts += o.before
                if (o is ActionOutcome.Ran) subVerdicts += o.after
                o.execution()
            }
        } else null
        val started = recorder.now()
        // 改状态的动作:记下执行前账本的位置,之后(含这次结果自带的那一屏)有没有重新读过由回答前的核对来看
        val changes = tools.declaredRisk(call.name) != Risk.Read && (s.offScreen || ClassifiedRisk.changesState(s.verb))
        val mark = tools.ledgerMark()
        val raw = (s.preset ?: offScreen?.exhausted(call.name)?.let { ToolExecution(it) } ?: execute(s, sub))
            .let { r -> s.note?.let { r.with(content = JsonObject(r.content + it)) } ?: r }
            .let { r -> if (s.pacedMs > 0) r.with(waitMs = r.waitMs + s.pacedMs) else r }
            .let { r -> if (s.idleScrolls > 0) r.with(record = JsonObject(r.record + ("idleScrolls" to JsonPrimitive(s.idleScrolls)))) else r }
        // 报了失败、也没说界面变了的动作什么也没改成:回答前不用为它再读一遍
        val failed = raw.content["ok"] == JsonPrimitive(false) && raw.changed != true
        if (changes && s.preset == null && !failed) recorder.noteChange(mark)
        val durationMs = recorder.now() - started
        val resolved = s.followUp.resolve(raw) ?: return ActionOutcome.Cancelled(s.before)
        tools.interfaces.checkAppAccess(tools.declared(call.name), after = true)

        val after = subVerdicts.toMutableList()
        after += if (s.offScreen) offScreen?.result(call.name, s.args, resolved.content).orEmpty() else guards.result(call.name, resolved.content)
        // 动作报回的实际效果先记(没有效果、换了地方),接着的画面就不再按指纹重复记
        ((resolved.content["effect"] as? JsonObject)?.get("kind") as? JsonPrimitive)?.takeIf { !s.offScreen }?.let { after += guards.effect(it.content) }
        val elements = resolved.screenElements ?: resolved.content["elements"] as? JsonArray
        val beforeFrameProgress = guards.progressVersion
        elementsHash(elements)?.let { after += guards.frame("$n:${call.id}", it) }
        if (guards.progressVersion != beforeFrameProgress) after.removeAll { it.code == "no_progress" }
        if (!s.offScreen) {
            if (s.verb in PAGE_READ_VERBS) {
                // 读到画面上次变化以来没读到过的内容(新的一屏、整页换了起点、脚本给出新结果)不算重读
                val fp = elementsHash(elements) ?: resultFingerprint(resolved.content)
                val beforeReadProgress = guards.progressVersion
                after += if (s.reads != null) guards.read(call.name, guards.firstSeen(fp, s.reads), s.reads, fp)
                else guards.read(call.name, guards.firstSeen(fp), fingerprint = fp)
                if (guards.progressVersion != beforeReadProgress) after.removeAll { it.code == "no_progress" }
            } else if (s.writing && resolved.changed != false && resolved.content["ok"] == JsonPrimitive(true)) {
                guards.pageChanged()
            }
        }
        val (exec, content) = s.followUp.present(resolved, s.before + after)

        // 画面:模型单步交给调用方按它的顺序存;子步界面变了才当场存;回放不存
        val picture = if (policy.frames == Frames.Caller && s.verb != Verb.ViewPastScreens && !s.offScreen) exec.image ?: exec.frame else null
        val shotId = when (policy.frames) {
            Frames.Caller -> picture?.let { recorder.nextShot() }
            Frames.WhenChanged -> (exec.image ?: exec.frame)?.takeIf { exec.changed != false }?.let(recorder::saveFrame)
            Frames.None -> null
        }
        recorder.result(n, call.name, s.verb, content, exec.record, exec.changed, shotId, durationMs, policy.source, call.id, exec.waitMs)
        ActivityRecord.of(tools, call.name, s.args, exec, n, policy.source, call.id, recorder.now(), s.outward)?.let(recorder::event)
        if (policy.frames == Frames.WhenChanged && shotId != null) recorder.event(RunEvent.Media(recorder.now(), shotId, n, RunEvent.placeLabel(exec.place)))
        val seen = if (policy.routeTargets) routeTarget(call, s.verb, s.origin, exec) else null
        s.traced?.let { recorder.event(RunEvent.State(recorder.now(), "gate_unresolved", "${call.name} $it")) }
        return ActionOutcome.Ran(exec, s.before, after, content, durationMs, shotId.takeIf { picture != null }, picture, seen)
    }

    private suspend fun execute(s: Step, sub: SubActions?): ToolExecution {
        val call = if (s.offScreen && offScreen != null) offScreen.capTimeout(s.call, s.args) else s.call
        val cap = s.origin.policy.actionCapMs ?: return tools.execute(call, pass, s.origin.step, sub)
        // 动作自己有上限
        return withTimeoutOrNull(cap) { tools.execute(call, pass, s.origin.step, sub) } ?: ToolExecution(buildJsonObject {
            put("ok", false); put("error", "action_timeout"); put("message", "the action did not finish within ${cap / 1000} s; read the screen before going on")
        })
    }

    /**
     * 开着回放时记路线要的目标元素和前后锚点(动作后多读一屏,返回它)。
     * 取不到目标、动作却生效了:路线不能缺步,标 incomplete,整条路线不记。
     */
    private suspend fun routeTarget(call: Part.ToolCall, verb: Verb?, origin: Origin, exec: ToolExecution): ScreenSnapshot? {
        if (!tools.captureTargets || tools.index[call.name]?.routable != true) return null
        // 只有端口明确说「不是密码框」才记原文
        val secret = (exec.content["secret"] as? JsonPrimitive)?.booleanOrNull != false
        val args = Redactor.args(verb, call.argsJson, secret)
        val effective = effectiveWrite(verb, exec.content)
        val source = origin.policy.source
        val none = Anchors(null, emptyList())
        val ui = tools.interfaces
        val captured = ui.lastActionTarget()
        if (captured == null) {
            if (effective) recorder.event(RunEvent.ActionTarget(recorder.now(), origin.step, call.name, args, null, none, none, true,
                callId = call.id, source = source, incomplete = true))
            return null
        }
        val seen = if (effective) ui.peek() else null
        val after = seen?.let(ui::anchorsAfter) ?: none
        recorder.event(RunEvent.ActionTarget(recorder.now(), origin.step, call.name, args, captured.first, captured.second, after, effective,
            callId = call.id, protected = ui.lastActionProtected(), source = source))
        return seen
    }

    /** 作用在 ZorvAI 自己身上:不做;模型单步照常拿到说明、记进运行记录。 */
    private fun ownApp(call: Part.ToolCall, args: JsonObject, origin: Origin, before: List<GuardVerdict>, followUp: FollowUp): ActionOutcome {
        val content = Refusals.content(Refusals.OWN_APP, origin.policy.refusals, before, null, null)
        if (origin.policy.recordsRefusal) {
            followUp.refused(content)
            recorder.result(origin.step, call.name, tools.verb(call.name), content, tools.portRecord(call.name, args), null, null, 0, origin.policy.source, call.id)
        }
        return ActionOutcome.Refused(Refusals.OWN_APP, content, before)
    }

    private fun refuse(code: String, origin: Origin, before: List<GuardVerdict>) =
        ActionOutcome.Refused(code, Refusals.content(code, origin.policy.refusals, before, null, null), before)

    private fun blocked(
        call: Part.ToolCall, args: JsonObject, origin: Origin, before: List<GuardVerdict>, hit: Irreversible, reason: String?, followUp: FollowUp,
    ): ActionOutcome {
        recorder.confirmation(hit, if (reason != null) "mismatch" else "required")
        val code = if (reason != null) "confirmation_mismatch" else "confirmation_required"
        val content = Refusals.content(code, origin.policy.refusals, before, hit, reason)
        if (origin.policy.recordsRefusal) {
            followUp.refused(content)
            recorder.result(origin.step, call.name, tools.verb(call.name), content, tools.portRecord(call.name, args), null, null, 0, origin.policy.source, call.id)
        }
        return ActionOutcome.Refused(code, content, before, hit)
    }
}
