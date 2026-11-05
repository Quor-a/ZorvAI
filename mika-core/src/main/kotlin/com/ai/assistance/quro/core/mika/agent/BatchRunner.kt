package com.ai.assistance.quro.core.mika.agent

import com.ai.assistance.quro.core.mika.observation.Observation
import com.ai.assistance.quro.core.mika.run.SKIPPED_FOR_NEW_MESSAGE
import com.ai.assistance.quro.core.mika.guard.OperationGuards
import com.ai.assistance.quro.core.mika.guard.GuardVerdict
import com.ai.assistance.quro.core.mika.action.ActionExecutor
import com.ai.assistance.quro.core.mika.action.ActionOutcome
import com.ai.assistance.quro.core.mika.action.Origin
import com.ai.assistance.quro.core.mika.model.Part
import com.ai.assistance.quro.core.mika.model.ToolSpec
import com.ai.assistance.quro.core.mika.prompt.Feedback
import com.ai.assistance.quro.core.mika.prompt.Prompts
import com.ai.assistance.quro.core.mika.record.Redactor
import com.ai.assistance.quro.core.mika.record.RunEvent
import com.ai.assistance.quro.core.mika.tool.ToolDispatcher
import com.ai.assistance.quro.core.mika.tool.ToolExecution
import com.ai.assistance.quro.core.mika.tool.Until
import com.ai.assistance.quro.core.mika.tool.UntilOutcome
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put

const val DO_STEPS_TOOL = "do_steps"
private const val MIN_STEPS = 1
/** 只限制单次调用的资源消耗,不把连续操作的合理长度写成任务规则。 */
private const val MAX_STEPS = 20
private const val EXPECT_MAX = 200
/** 第二步起,判定和执行前等界面稳定的上限(与动作自带屏幕相同)。 */
private const val SETTLE_MS = 1500L
/** 一步的等待条件(都可不给);expect 是旧写法,意思从「做完看一眼」变成「等到出现」。 */
private const val EXPECT = "expect"
private const val EXPECT_GONE = "expect_gone"
private const val EXPECT_APP = "expect_app"
private const val WAIT_STABLE = "wait_stable"
private const val TIMEOUT_S = "timeout_s"
private val STEP_FIELDS = setOf("tool", "args", EXPECT, EXPECT_GONE, EXPECT_APP, WAIT_STABLE, TIMEOUT_S)

/** 应用访问被挡:批里要停下,并把执行入口的说明原样带给模型。 */
private fun appAccessDenied(code: String) = com.ai.assistance.quro.core.mika.access.AppAccessDenied.isAppAccessCode(code)

/** 一批里的一步;[until] 是做完之后、接着做之前要等到的样子,没给为 null。 */
data class BatchStep(val tool: String, val argsJson: String, val until: Until? = null)

/**
 * 一次执行多个连续动作,逐步核对,出意外就停。每一步(第二步起先等界面稳定)经 [ActionExecutor] 执行,
 * 和单步同一套顺序;批里不使用、也不假设任何同意,不可逆的一步命中就停在它之前,不执行。
 * 核对不通过:工具失败、被切到别的 App、输入回读不一致。一步带了等待条件([Until])时做完先等它成立
 * ([ToolDispatcher.waitUntil]:读屏比字面,不问模型),到时限、看出弹框或换了 App 就停;等了多久写进这一步的结果。
 * 每一步对守卫和运行记录都是一个动作(运行记录里 source = "batch")。
 */
class BatchRunner(
    private val tools: ToolDispatcher,
    private val guards: OperationGuards,
    private val executor: ActionExecutor,
    /** 每个子步的守卫结论(执行前、执行后各一次)交回循环,计入卡住提醒与重复输入;返回要附给模型的建议。 */
    private val noteVerdicts: (step: BatchStep, exec: ToolExecution?, verdicts: List<GuardVerdict>) -> String? = { _, _, _ -> null },
    /** 用户此刻有没有新插话在等(只看内存里的收件箱);有就在两个子步之间停下,交给循环去送。 */
    private val steerWaiting: () -> Boolean = { false },
) {
    private class Stop(
        val index: Int, val reason: String, val entry: JsonObject, val executed: Boolean, val extra: JsonObject = JsonObject(emptyMap()),
        /** 停下之后没做的那些子步也各给一条结果(新插话时),子步和结果一一对上。 */
        val rest: List<JsonElement> = emptyList(),
    )

    /** 所有子步里出现过的守卫结论;循环据此判断这一步有没有守卫提醒。 */
    val verdicts = mutableListOf<GuardVerdict>()
    private var advice: String? = null

    /** 各步按条件等界面花的时间之和;运行记录把它算作等待,不算操作。 */
    var waitedMs = 0L
        private set

    suspend fun run(steps: List<BatchStep>, callId: String, step: Int = 0): ToolExecution {
        val entries = mutableListOf<JsonElement>()
        var last: ToolExecution? = null
        // 各子步读到的观察(已记进账本);整批的结果带着它们,裁剪时按它们的形态缩短
        val seen = mutableListOf<Observation>()
        var stop: Stop? = null
        fun skipFrom(i: Int): Stop {
            val skipped = steps.drop(i).map { entry(it.tool) { put("ok", false); put("error", NEW_MESSAGE); put("executed", false) } }
            return Stop(i, NEW_MESSAGE, skipped.first(), executed = false, rest = skipped.drop(1))
        }
        for ((i, s) in steps.withIndex()) {
            if (i > 0 && steerWaiting()) {
                stop = skipFrom(i)
                break
            }
            // 上一步的界面还在变时判定会读到过渡画面,expect 也会误判
            if (i > 0) tools.settle(SETTLE_MS)
            val out = executor.run(Part.ToolCall("$callId.${i + 1}", s.tool, s.argsJson), Origin.Batch(step))
            val before = out.before
            note(s, null, before)
            fun notRun(reason: String, extra: JsonObject = JsonObject(emptyMap()), message: String? = null) = Stop(i, reason, entry(s.tool) {
                put("ok", false); put("error", reason); put("executed", false)
                message?.let { put("message", it) }
                if (before.isNotEmpty()) put("guard", JsonArray(before.map { it.toJson() }))
            }, executed = false, extra = extra)
            val exec = when (out) {
                is ActionOutcome.Ran -> out.exec
                is ActionOutcome.Refused -> {
                    val hit = out.hit
                    // 应用权限拒绝:把执行入口给的下一步说明一起交给模型,别让它只看到错误码
                    val message = (out.content["message"] as? JsonPrimitive)?.contentOrNull?.takeIf { appAccessDenied(out.code) }
                    stop = if (hit == null) notRun(out.code, message = message)
                    else notRun(out.code, buildJsonObject { put("action", hit.action.name); put("matched", hit.matched) }, message)
                    break
                }
                is ActionOutcome.Cancelled -> { stop = notRun("cancelled"); break }
            }
            // 这一步之前的停留、间隔或这次等待被用户插话截断:这一步什么也没做,和后面的一起跳过,交给循环去送那句话
            if (exec.content["error"] == JsonPrimitive(NEW_MESSAGE)) {
                waitedMs += exec.waitMs
                stop = skipFrom(i)
                break
            }
            seen += exec.observations
            // 子步里为节奏补的停留同样算这一批的等待
            waitedMs += exec.waitMs
            val afterVerdicts = out.after
            note(s, exec, afterVerdicts)
            val all = before + afterVerdicts
            last = exec
            val c = exec.content
            val screen = c["screen"] as? JsonObject
            val failed = c["ok"] != JsonPrimitive(true)
            val hard = when {
                failed -> (c["error"] as? JsonPrimitive)?.contentOrNull ?: "failed"
                c["verification"] == JsonPrimitive("mismatch") -> "action_not_applied"
                c["app_switched"] != null -> "app_switched"
                // 开到了用户的主屏上(挪回来了也是换了地方):后面的步骤是按原来那一屏排的
                c["opened"] != null -> "opened_elsewhere"
                // 网站、App 说到上限了:后面的步骤不再做
                c["limitReached"] != null -> "limit_reached"
                else -> null
            }
            val review = i < steps.lastIndex && screen != null &&
                (screen["imageReason"] != null || screen["protected"] == JsonPrimitive(true) ||
                    screen["readable"] == JsonPrimitive(false) || screen["note"] != null)
            // 动作自带的屏幕可能还是加载中的样子:等到要出现的字出现了,就不必再看图
            val until = s.until?.takeIf { hard == null && (!review || it.appears != null) }
            val waited = until?.let { tools.waitUntil(it, executor.recorder::now, steerWaiting) }
            waited?.let { waitedMs += it.waitedMs }
            val reason = hard ?: when {
                waited != null && !waited.met -> when (waited.stopped) {
                    UntilOutcome.TIMEOUT -> "expectation_unmet"
                    UntilOutcome.NEW_MESSAGE -> null
                    else -> waited.stopped
                }
                review && waited?.met != true -> "screen_needs_review"
                else -> null
            }
            val e = entry(s.tool) {
                put("ok", reason == null)
                exec.changed?.let { put("changed", it) }
                effectKind(c)?.let { put("effect", it) }
                (c["verification"] as? JsonPrimitive)?.let { put("verification", it) }
                reason?.let { put("error", it) }
                if (waited != null && !waited.met) s.until?.let { untilFields(it).forEach { (k, v) -> put(k, v) } }
                waited?.let { w ->
                    put("wait", buildJsonObject { put("met", w.met); w.stopped?.let { put("stopped", it) }; put("waited_ms", w.waitedMs) })
                }
                (c["app_switched"] as? JsonPrimitive)?.let { put("app_switched", it) }
                (c["opened"] as? JsonObject)?.let { put("opened", it); (c["hint"] as? JsonPrimitive)?.let { h -> put("hint", h) } }
                (c["chooser"] as? JsonObject)?.let { put("chooser", it) }
                (c["limitReached"] as? JsonPrimitive)?.let { put("limitReached", it); (c["hint"] as? JsonPrimitive)?.let { h -> put("hint", h) } }
                if (all.isNotEmpty()) put("guard", JsonArray(all.map { it.toJson() }))
            }
            if (reason != null) { stop = Stop(i, reason, JsonObject(e + ("executed" to JsonPrimitive(true))), executed = true); break }
            // 等的时候用户插了话:后面的子步在下一轮开头照常跳过;这已是最后一步时就停在它这里
            if (waited?.stopped == UntilOutcome.NEW_MESSAGE && i == steps.lastIndex) {
                stop = Stop(i, NEW_MESSAGE, JsonObject(e + ("executed" to JsonPrimitive(true))), executed = true); break
            }
            entries += e
            if (guards.stop != null && i < steps.size - 1) {
                stop = Stop(i + 1, "guard_stopped", entry(steps[i + 1].tool) { put("ok", false); put("error", "guard_stopped"); put("executed", false) }, executed = false)
                break
            }
        }
        return finish(steps, entries, stop, last, callId, step, seen)
    }

    private fun finish(
        steps: List<BatchStep>, done: List<JsonElement>, stop: Stop?, last: ToolExecution?, callId: String, step: Int, seen: List<Observation>,
    ): ToolExecution {
        val completed = stop?.index ?: steps.size
        executor.recorder.event(RunEvent.Batch(executor.recorder.now(), completed, steps.size, stop != null, stop?.reason))
        val screen = last?.content?.get("screen")
        val content = buildJsonObject {
            put("ok", stop == null)
            when (stop?.reason) {
                null -> Unit
                "confirmation_required" -> {
                    put("error", "confirmation_required")
                    stop.extra.forEach { (k, v) -> put(k, v) }
                    val what = stop.extra["matched"]?.let { (it as JsonPrimitive).content }
                    put("message", "Step ${stop.index + 1} of ${steps.size} spends money ($what). " +
                        "It was not run, and the steps after it were not run either. Payment steps never go in do_steps: " +
                        if (executor.confirms) "follow the step's message (request_confirmation, or hand_to_user for a payment), then do that step on its own as a single tool call."
                        else "do it on its own as a single tool call, not as part of a batch.")
                    put("counts_as_failure", false)
                }
                NEW_MESSAGE -> {
                    put("error", "batch_stopped")
                    put("reason", NEW_MESSAGE)
                    put("message", (if (stop.executed) "Step ${stop.index + 1} of ${steps.size} was run, but waiting for its condition was cut short"
                        else "Steps ${stop.index + 1}-${steps.size} of ${steps.size} were skipped") + " to handle a new message from the user. " +
                        "Read the message first, then decide what to do next.")
                    put("counts_as_failure", false)
                }
                else -> {
                    put("error", "batch_stopped")
                    put("reason", stop.reason)
                    val n = "Step ${stop.index + 1} of ${steps.size}"
                    if (appAccessDenied(stop.reason)) {
                        // 权限拒绝不是普通的操作失败:不要建议「换个批继续」,把下一步该怎么做说清楚
                        val detail = (stop.entry["message"] as? JsonPrimitive)?.contentOrNull
                            ?: "Step ${stop.index + 1} of ${steps.size} was not run (${stop.reason})."
                        put("message", "$detail The remaining steps in this batch were not run.")
                        put("retryable", false)
                    } else {
                        put("message", (if (stop.executed) "$n was run but did not pass its check (${stop.reason})" else "$n was not run (${stop.reason})") +
                            "; the steps after it were not run. Check the screen, then continue one step at a time or with a new batch.")
                    }
                }
            }
            advice?.let { put("advice", it) }
            put("completed", completed); put("total", steps.size)
            put("steps", JsonArray(done + listOfNotNull(stop?.entry) + stop?.rest.orEmpty()))
            screen?.let { put("screen", it) }
            if (stop != null) put("recoverable", true)
        }
        // 子步的画面已经逐个存过
        // 最后一个子步发给模型的画面(能看图的主模型才有)留给循环,接在整批结果后面
        return ToolExecution(content, changed = last?.changed, frame = if (!executor.recorder.savesScreens) last?.frame ?: last?.image else null,
            screenElements = last?.screenElements, images = last?.images.orEmpty(), imageIds = last?.imageIds.orEmpty(), place = last?.place,
            observations = seen)
    }

    private fun note(s: BatchStep, exec: ToolExecution?, v: List<GuardVerdict>) {
        verdicts += v
        noteVerdicts(s, exec, v)?.let { advice = it }
    }

    private fun entry(tool: String, b: JsonObjectBuilder.() -> Unit) = buildJsonObject { put("tool", tool); b() }

    companion object {
        /** 子步因用户新插话没做时的停止原因。 */
        const val NEW_MESSAGE = SKIPPED_FOR_NEW_MESSAGE

        /**
         * 校验 do_steps 的参数;[available] 是此刻提供给模型的工具名,[batchable] 是能放进一批的工具(按工具声明,
         * 只有屏幕动作和等待;观察、追问、接管、确认都不行)。
         * 返回步骤,或点名第几步的错误说明。
         */
        fun parse(argsJson: String, available: Set<String>, batchable: List<String>): Pair<List<BatchStep>?, String?> {
            val o = try { Json.parseToJsonElement(argsJson.ifBlank { "{}" }) as? JsonObject } catch (e: SerializationException) { null }
                ?: return null to "arguments: must be a JSON object"
            o.keys.firstOrNull { it != "steps" && it != "reason" }?.let { return null to "$it: unknown argument for do_steps" }
            val arr = o["steps"] as? JsonArray ?: return null to "steps: required, an array of $MIN_STEPS-$MAX_STEPS steps"
            if (arr.size !in MIN_STEPS..MAX_STEPS) return null to "steps: must have $MIN_STEPS-$MAX_STEPS items"
            val out = arr.mapIndexed { i, e ->
                val n = i + 1
                val s = e as? JsonObject ?: return null to "steps[$n]: must be an object with tool, args and optional expect"
                s.keys.firstOrNull { it !in STEP_FIELDS }?.let { return null to "steps[$n].$it: unknown field" }
                val tool = (s["tool"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null to "steps[$n].tool: required"
                if (tool !in batchable || tool !in available) {
                    return null to "steps[$n].tool: $tool cannot be batched; only ${batchable.filter { it in available }.joinToString()}"
                }
                val args = when (val a = s["args"]) {
                    null -> JsonObject(emptyMap())
                    is JsonObject -> a
                    else -> return null to "steps[$n].args: must be an object"
                }
                val texts = listOf(EXPECT, EXPECT_GONE, EXPECT_APP).associateWith { k ->
                    when (val x = s[k]) {
                        null -> null
                        is JsonPrimitive -> x.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
                            ?: return null to "steps[$n].$k: must be a non-blank string"
                        else -> return null to "steps[$n].$k: must be a string"
                    }?.also { if (it.length > EXPECT_MAX) return null to "steps[$n].$k: must be at most $EXPECT_MAX characters" }
                }
                val stable = when (val x = s[WAIT_STABLE]) {
                    null -> false
                    is JsonPrimitive -> x.takeIf { !it.isString }?.booleanOrNull ?: return null to "steps[$n].$WAIT_STABLE: must be true or false"
                    else -> return null to "steps[$n].$WAIT_STABLE: must be true or false"
                }
                val max = Until.MAX_MS / 1000
                val seconds = when (val x = s[TIMEOUT_S]) {
                    null -> null
                    is JsonPrimitive -> x.takeIf { !it.isString }?.doubleOrNull?.takeIf { it in 1.0..max.toDouble() }
                        ?: return null to "steps[$n].$TIMEOUT_S: must be a number of seconds, 1-$max"
                    else -> return null to "steps[$n].$TIMEOUT_S: must be a number of seconds, 1-$max"
                }
                val waits = texts.values.any { it != null } || stable
                if (seconds != null && !waits) return null to "steps[$n].$TIMEOUT_S: only goes with $EXPECT, $EXPECT_GONE, $EXPECT_APP or $WAIT_STABLE"
                val until = if (!waits) null else Until(texts[EXPECT], texts[EXPECT_GONE], texts[EXPECT_APP], stable,
                    seconds?.let { (it * 1000).toLong() } ?: Until.DEFAULT_MS)
                BatchStep(tool, args.toString(), until)
            }
            return out to null
        }

        /** 等待条件按模型的写法交回(没等到时附在那一步的结果里)。 */
        private fun untilFields(u: Until): JsonObject = buildJsonObject {
            u.appears?.let { put(EXPECT, it) }
            u.gone?.let { put(EXPECT_GONE, it) }
            u.place?.let { put(EXPECT_APP, it) }
            if (u.stable) put(WAIT_STABLE, true)
            put(TIMEOUT_S, u.timeoutMs / 1000.0)
        }

        /**
         * 一批里的步骤作用在两块以上的屏上时给模型的说明([surfaceOf] 是工具作用的屏,不动屏的等待为 null);
         * 都在同一块屏上为 null。
         */
        fun mixedSurface(steps: List<BatchStep>, surfaceOf: (String) -> String?): String? {
            val surfaces = steps.mapNotNull { surfaceOf(it.tool) }.distinct()
            if (surfaces.size < 2) return null
            return Feedback.format("batch.mixed_surface", "surfaces" to surfaces.joinToString(", "))
        }

        /** 运行记录用:批里输入的文字一律打码(端口还没说是不是密码框)。 */
        fun redactArgs(argsJson: String): String {
            val o = try { Json.parseToJsonElement(argsJson) as? JsonObject } catch (e: SerializationException) { null } ?: return Redactor.text(argsJson)
            val steps = o["steps"] as? JsonArray ?: return Redactor.text(argsJson)
            val masked = JsonArray(steps.map { s ->
                val so = s as? JsonObject ?: return@map s
                val a = so["args"] as? JsonObject
                if (a == null || "text" !in a) s else JsonObject(so + ("args" to JsonObject(a + ("text" to JsonPrimitive("***")))))
            })
            return Redactor.text(JsonObject(o + ("steps" to masked)).toString())
        }

        /** do_steps 的定义;[batchable] 是能放进一批的工具名。 */
        fun spec(batchable: List<String>) = ToolSpec(
            DO_STEPS_TOOL,
            Prompts.tool("do_steps"),
            buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put("steps", buildJsonObject {
                        put("type", "array"); put("minItems", MIN_STEPS); put("maxItems", MAX_STEPS)
                        put("items", buildJsonObject {
                            put("type", "object")
                            put("properties", buildJsonObject {
                                put("tool", buildJsonObject { put("type", "string"); put("enum", JsonArray(batchable.map(::JsonPrimitive))) })
                                put("args", buildJsonObject { put("type", "object"); put("description", "The arguments that tool takes on its own.") })
                                put(EXPECT, buildJsonObject {
                                    put("type", "string"); put("maxLength", EXPECT_MAX)
                                    put("description", "Optional: after this step, wait until an element's name contains this text. Text already on screen before the step also matches, so pick text the step brings up.")
                                })
                                put(EXPECT_GONE, buildJsonObject {
                                    put("type", "string"); put("maxLength", EXPECT_MAX)
                                    put("description", "Optional: wait until no element's name contains this text (a loading or progress label).")
                                })
                                put(EXPECT_APP, buildJsonObject {
                                    put("type", "string"); put("maxLength", EXPECT_MAX)
                                    put("description", "Optional: wait until the app in front (package name) or the page address contains this.")
                                })
                                put(WAIT_STABLE, buildJsonObject { put("type", "boolean"); put("description", "Optional: wait until the screen stops changing.") })
                                put(TIMEOUT_S, buildJsonObject {
                                    put("type", "number"); put("minimum", 1); put("maximum", Until.MAX_MS / 1000)
                                    put("description", "Optional: longest wait for this step's conditions, in seconds (default ${Until.DEFAULT_MS / 1000}).")
                                })
                            })
                            put("required", JsonArray(listOf(JsonPrimitive("tool"), JsonPrimitive("args"))))
                            put("additionalProperties", false)
                        })
                    })
                    put("reason", buildJsonObject { put("type", "string"); put("description", com.ai.assistance.quro.core.mika.tool.REASON_NOTE) })
                })
                put("required", JsonArray(listOf(JsonPrimitive("steps"))))
                put("additionalProperties", false)
            }.toString(),
        )
    }
}

/** 结果里 effect 的种类;动作没报效果为 null。 */
/** 动作报回的实际效果的种类(能力写在结果的 effect 里);没报为 null。 */
private fun effectKind(c: JsonObject): String? = ((c["effect"] as? JsonObject)?.get("kind") as? JsonPrimitive)?.contentOrNull
