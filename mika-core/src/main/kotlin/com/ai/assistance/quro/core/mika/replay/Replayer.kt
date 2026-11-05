package com.ai.assistance.quro.core.mika.replay

import com.ai.assistance.quro.core.mika.action.ActionExecutor
import com.ai.assistance.quro.core.mika.action.ActionOutcome
import com.ai.assistance.quro.core.mika.action.Origin
import com.ai.assistance.quro.core.mika.model.Part
import com.ai.assistance.quro.core.mika.record.Redactor
import com.ai.assistance.quro.core.mika.record.RunEvent
import com.ai.assistance.quro.core.mika.record.RunSink
import com.ai.assistance.quro.core.mika.uistate.ScreenSnapshot
import com.ai.assistance.quro.core.mika.uistate.UiElement
import com.ai.assistance.quro.core.mika.uistate.interfaces
import com.ai.assistance.quro.core.mika.tool.ToolDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlin.math.hypot
import com.ai.assistance.quro.core.mika.tool.Verb

sealed interface ReplayOutcome {
    /** 走完(或核对通过)的步数。 */
    val done: Int

    data class Completed(override val done: Int) : ReplayOutcome
    /**
     * [executed]:第 done+1 步真的执行了(界面可能已经动过);执行前就被拒的(参数、链接、编号过期、App 被占等)为 false。
     * [error] 是那一步工具结果的错误码,交还时一并告诉模型。
     */
    data class Diverged(
        override val done: Int, val expected: String, val seen: String, val executed: Boolean = false, val error: String? = null,
    ) : ReplayOutcome
    /** 下一步是不可逆操作(或判不清),停在它之前交还模型走正常确认流程。 */
    data class StoppedForConfirmation(override val done: Int) : ReplayOutcome
}

/**
 * 不调模型地照路线一步步做。每步:锁屏关卡 → 读屏 → 轻量核对(前台包名、动作前锚点、选择器唯一命中)→
 * 经 [ActionExecutor] 执行(守卫一有提示就停;不可逆或判不清都停,回放从不认为用户已同意)→ 核对动作后的变化。
 * 任何一处对不上就停,交还模型。输入的字只来自路线里的固定文字和本次原话里的槽值。
 */
class Replayer(
    private val tools: ToolDispatcher,
    private val executor: ActionExecutor,
    private val gate: suspend () -> Unit,
    private val sink: RunSink,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private var seq = 0

    suspend fun run(choice: RouteChoice): ReplayOutcome {
        val steps = choice.route.steps
        val total = steps.size
        fun event(i: Int, matched: Boolean, detail: String) =
            emit(RunEvent.Replay(clock(), choice.route.id, i + 1, total, matched, Redactor.text(detail)))

        for ((i, step) in steps.withIndex()) {
            fun diverged(why: String, seen: String, executed: Boolean = false, error: String? = null): ReplayOutcome {
                event(i, false, why)
                return ReplayOutcome.Diverged(i, describe(step, choice.slots) + " ($why)", seen, executed, error)
            }
            val verb = tools.index[step.tool]?.takeIf { it.routable }?.verb ?: return diverged("not a replayable action", "")
            // 锁屏、被拿走时先等;等完再读屏,核对和执行都按等完之后的一屏(执行入口不再等第二次)
            gate()
            val snap = read() ?: return diverged("the screen could not be read", "")
            if (snap.protected) return diverged("the page is protected", PROTECTED_SEEN)
            val seen = summary(snap)
            step.before.packageName?.let { if (snap.packageName != it) return diverged("the foreground app is not $it", seen) }
            val names = snap.elements.map { it.name.trim() }.toSet()
            val need = minOf(2, step.before.texts.size)
            if (step.before.texts.count { it in names } < need) return diverged("the page before the action does not match", seen)

            val args = build(step, choice.slots) ?: return diverged("the slots in the arguments cannot be filled", seen)
            val target = step.target?.let { sel ->
                find(snap, sel, choice.slots) ?: return diverged("the target element is not unique or not found", seen)
            }
            if (verb == Verb.Tap && target == null) return diverged("the tap has no target to check", seen)
            // 链接只开参考资料里此刻还在的:资料改过,路线里记着的链接就不再放行
            if (verb == Verb.OpenLink) {
                fun arg(k: String) = (args[k] as? JsonPrimitive)?.contentOrNull
                if (!tools.linkAllowed(arg("action"), arg("uri"), arg("package"))) return diverged("the link is no longer in the reference material", seen)
            }
            val call = target?.let { JsonObject(args + mapOf("ref" to JsonPrimitive(it.ref), "snapshotId" to JsonPrimitive(snap.snapshotId))) } ?: args

            val id = "replay-${++seq}"
            val out = when (val o = executor.run(Part.ToolCall(id, step.tool, call.toString()), Origin.Replay)) {
                is ActionOutcome.Ran -> o
                is ActionOutcome.Refused -> when (o.code) {
                    "guard_stopped", "guard_warning" -> return diverged("the action guard raised a warning", seen)
                    "confirmation_required", "confirmation_mismatch" -> {
                        event(i, false, "irreversible action, handed back to the model for confirmation")
                        return ReplayOutcome.StoppedForConfirmation(i)
                    }
                    "target_unresolved" -> return diverged("cannot tell which element the tap would hit", seen)
                    "own_app" -> return diverged("it acts on ZorvAI itself", seen)
                    else -> error("unexpected refusal ${o.code}")
                }
                is ActionOutcome.Cancelled -> return diverged("cancelled", seen)
            }
            val exec = out.exec
            // 动作后只读一屏:路线锚点和「有没有出现预期文字」都用它
            val fresh = out.seen ?: tools.interfaces.peek()
            val effective = effectiveWrite(verb, exec.content)
            val now = fresh?.let { if (it.protected) PROTECTED_SEEN else summary(it) } ?: seen
            if (exec.outcome != null || exec.content["ok"] != JsonPrimitive(true)) {
                val error = (exec.content["error"] as? JsonPrimitive)?.contentOrNull
                return diverged("the action failed" + error?.let { ": $it" }.orEmpty(), now, executed = exec.outcome != null || error !in NOT_EXECUTED, error = error)
            }
            val shown = fresh?.elements?.map { it.name.trim() }?.toSet().orEmpty()
            val landed = exec.content["changed"] == JsonPrimitive(true) || exec.content["verification"] == JsonPrimitive("match") ||
                (effective && verb in OPENS) ||
                step.after.texts.any { it in shown }
            if (!landed) return diverged("no expected change after the action", now, executed = true)
            event(i, true, describe(step, choice.slots))
        }
        return ReplayOutcome.Completed(total)
    }

    private suspend fun read(): ScreenSnapshot? = try {
        // 读路线此刻所在的那块屏(最近一步动作的那块)
        val dump = tools.index.nameOf(Verb.ListElements, tools.surface)!!
        val r = tools.executeRead(Part.ToolCall("replay-read-${++seq}", dump, "{}"), 0)
        (r.content["snapshotId"] as? JsonPrimitive)?.contentOrNull?.let { tools.interfaces.snapshotFor(tools.declared(dump), JsonObject(mapOf("snapshotId" to JsonPrimitive(it)))) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    /** 去掉编号;每个字符串参数里的 `{n}` 都换成槽值。槽号超出范围、或非字符串参数里带着槽,为 null。 */
    private fun build(step: RouteStep, slots: List<String>): JsonObject? {
        val o = try { Json.parseToJsonElement(step.argsJson) as? JsonObject } catch (e: Exception) { null } ?: return null
        val out = mutableMapOf<String, JsonElement>()
        for ((k, v) in o) {
            if (k == "ref" || k == "snapshotId" || k == "target") continue
            out[k] = when {
                v is JsonPrimitive && v.isString -> JsonPrimitive(fill(v.content, slots) ?: return null)
                SLOT.containsMatchIn(v.toString()) -> return null
                else -> v
            }
        }
        return JsonObject(out)
    }

    /**
     * 在当前屏里按选择器找唯一的目标。名字为空不比名字;`{n}` 先换成槽值。
     * 记录时唯一、现在却不止一个:页面已不是那时的样子(如列表里多了同名条目),不认。
     * 记录时就不唯一:取同序号的那个,且只有它离记录时的位置不超过屏宽 15% 才认。
     */
    private fun find(s: ScreenSnapshot, sel: Selector, slots: List<String>): UiElement? {
        val name = fill(sel.name, slots) ?: return null
        val same = s.elements.filter { it.role == sel.role && it.resourceId == sel.resourceId && (name.isEmpty() || it.name.trim() == name) }
        if (same.size == 1) return same.single()
        if (same.isEmpty() || sel.uniqueAtRecord || sel.bounds.size != 4) return null
        fun near(e: UiElement) = e.bounds.size == 4 && hypot(cx(e.bounds) - cx(sel.bounds), cy(e.bounds) - cy(sel.bounds)) <= NEAR
        val pick = same.getOrNull(sel.ordinal)?.takeIf(::near) ?: return null
        return pick.takeIf { same.count(::near) == 1 }
    }

    private fun cx(b: List<Int>) = (b[0] + b[2]) / 2.0
    private fun cy(b: List<Int>) = (b[1] + b[3]) / 2.0

    private fun summary(s: ScreenSnapshot): String =
        "${s.packageName ?: "unknown app"}: " + s.elements.map { it.name.trim() }.filter { it.isNotEmpty() }.distinct().take(8).joinToString(", ")

    private fun emit(e: RunEvent) {
        try { sink.event(e) } catch (x: CancellationException) { throw x } catch (x: Exception) { /* 记录失败不影响回放 */ }
    }

    companion object {
        /** 这些错误在动作执行之前就拒了,界面没被动过。 */
        private val NOT_EXECUTED = setOf(
            "invalid_arguments", "unknown_tool", "invalid_link", "link_unresolved", "ref_expired", "app_in_use_by_other_task",
            "app_wait_would_deadlock", "app_wait_timed_out",
            "app_in_use_by_user", "app_not_found", "no_app_open", "confirmation_required", "batch_stopped",
        )

        /** 屏宽 15%(0–999 坐标)。 */
        private const val NEAR = 150.0
        private const val PROTECTED_SEEN = "a protected page"
        private val SLOT = Regex("\\{(\\d+)\\}")

        /** 把 `{n}` 换成第 n 个槽值;槽号不存在为 null。 */
        internal fun fill(s: String, slots: List<String>): String? {
            var bad = false
            val out = SLOT.replace(s) { m -> slots.getOrNull(m.groupValues[1].toInt() - 1) ?: run { bad = true; "" } }
            return out.takeUnless { bad }
        }

        /** 给模型看的一步说明。 */
        fun describe(step: RouteStep, slots: List<String>): String {
            val o = try { Json.parseToJsonElement(step.argsJson) as? JsonObject } catch (e: Exception) { null }
            val what = listOfNotNull(
                step.target?.name?.let { fill(it, slots) }?.takeIf { it.isNotEmpty() }?.let { "「$it」" },
                (o?.get("text") as? JsonPrimitive)?.contentOrNull?.let { fill(it, slots) }?.let { "type 「$it」" },
                (o?.get("package") as? JsonPrimitive)?.contentOrNull,
                (o?.get("direction") as? JsonPrimitive)?.contentOrNull,
                (o?.get("key") as? JsonPrimitive)?.contentOrNull,
            ).joinToString(" ")
            return if (what.isEmpty()) step.tool else "${step.tool} $what"
        }
    }
}
