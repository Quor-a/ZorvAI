package com.ai.assistance.quro.core.mika.cap.phone

import com.ai.assistance.quro.core.mika.model.Part
import com.ai.assistance.quro.core.mika.prompt.Prompts
import com.ai.assistance.quro.core.mika.run.SKIPPED_FOR_NEW_MESSAGE
import com.ai.assistance.quro.core.mika.run.waitOrSteer
import com.ai.assistance.quro.core.mika.tool.failCode
import com.ai.assistance.quro.core.mika.tool.ArgError
import com.ai.assistance.quro.core.mika.tool.Args
import com.ai.assistance.quro.core.mika.tool.Risk
import com.ai.assistance.quro.core.mika.tool.SubActions
import com.ai.assistance.quro.core.mika.tool.ToolContext
import com.ai.assistance.quro.core.mika.tool.ToolDef
import com.ai.assistance.quro.core.mika.tool.ToolExecution
import com.ai.assistance.quro.core.mika.tool.Verb
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import com.ai.assistance.quro.core.mika.uistate.screen
import com.ai.assistance.quro.core.mika.uistate.session

/** 根据每一步读到的当前无障碍树重新定位目标,然后把动作交回统一执行入口。 */
internal object PhoneRun : PhoneTool {
    override val verb = Verb.PhoneRun
    override val risk = Risk.Write
    override val composite = true
    override val label = "连续操作手机"
    override val def = ToolDef("phone_run", Prompts.tool("phone_run"), mapOf(
        "commands" to buildJsonObject {
            put("type", "array"); put("minItems", 1); put("maxItems", 30)
            put("description", "Ordered commands. Each target is found again on the current screen before acting.")
            put("items", buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put("op", buildJsonObject { put("type", "string"); put("enum", JsonArray(listOf("launch", "tap", "type", "clear", "scroll", "swipe", "key", "back", "wait", "assert", "observe").map(::JsonPrimitive))) })
                    for (key in listOf("targetText", "resourceId", "role", "text", "packageName", "direction", "whenText", "key"))
                        put(key, buildJsonObject { put("type", "string") })
                    for (key in listOf("x", "y", "amount", "timeoutMs", "startX", "startY", "endX", "endY", "durationMs"))
                        put(key, buildJsonObject { put("type", "integer") })
                    put("present", buildJsonObject { put("type", "boolean") })
                    put("pressEnter", buildJsonObject { put("type", "boolean") })
                    put("cold", buildJsonObject { put("type", "boolean") })
                })
                put("required", JsonArray(listOf(JsonPrimitive("op"))))
                put("additionalProperties", false)
            })
        },
    ), listOf("commands"))

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution = ctx.session.textOnly { runCommands(a, ctx) }

    private suspend fun runCommands(a: Args, ctx: ToolContext): ToolExecution {
        val commands = a.o["commands"] as? JsonArray ?: throw ArgError("commands", "must be an array")
        if (commands.size !in 1..30) throw ArgError("commands", "must have 1-30 items")
        val sub = ctx.sub ?: return failed("actions_unavailable")
        val steps = mutableListOf<JsonObject>()
        var stop: String? = null
        var finalScreen: JsonObject? = null
        for ((index, raw) in commands.withIndex()) {
            val command = raw as? JsonObject ?: throw ArgError("commands[$index]", "must be an object")
            val op = command.string("op") ?: throw ArgError("commands[$index].op", "required")
            if (op !in setOf("launch", "tap", "type", "clear", "scroll", "swipe", "key", "back", "wait", "assert", "observe"))
                throw ArgError("commands[$index].op", "unknown operation")
            val whenText = command.string("whenText")
            if (whenText != null && !hasText(ctx, whenText)) {
                stop = "condition_unmet"
                steps += row(index, op, false, stop, executed = false)
                break
            }
            val result = when (op) {
                "launch" -> {
                    val pkg = command.string("packageName") ?: throw ArgError("commands[$index].packageName", "required")
                    run(sub, ctx, index, "phone_launch_app", buildJsonObject { put("package", pkg); if (command.boolean("cold") == true) put("cold", true) })
                }
                "tap", "type", "clear" -> {
                    val target = select(sub, ctx, index, command, op != "tap")
                    if (target.error != null) {
                        stop = target.error
                        steps += row(index, op, false, stop, executed = false)
                        break
                    }
                    val args = if (op == "tap") buildJsonObject {
                        target.ref?.let { put("ref", it); put("snapshotId", target.snapshotId!!) }
                        if (target.ref == null) {
                            put("x", command.integer("x")!!); put("y", command.integer("y")!!)
                            command.string("targetText")?.let { put("target", it) }
                        }
                    } else buildJsonObject {
                        if (op == "type") {
                            put("text", command.string("text") ?: throw ArgError("commands[$index].text", "required"))
                            if (command.boolean("pressEnter") == true) put("pressEnter", true)
                        }
                        target.ref?.let { put("ref", it); put("snapshotId", target.snapshotId!!) }
                    }
                    run(sub, ctx, index, when (op) { "tap" -> "phone_tap"; "type" -> "phone_type_text"; else -> "phone_clear_text" }, args)
                }
                "scroll" -> {
                    val dir = command.string("direction") ?: throw ArgError("commands[$index].direction", "required")
                    if (dir !in setOf("up", "down", "left", "right")) throw ArgError("commands[$index].direction", "invalid")
                    val amount = command.integer("amount") ?: 3
                    if (amount !in 1..20) throw ArgError("commands[$index].amount", "must be 1-20")
                    run(sub, ctx, index, "phone_scroll", buildJsonObject { put("direction", dir); put("amount", amount) })
                }
                "back" -> run(sub, ctx, index, "phone_back", JsonObject(emptyMap()))
                "key" -> {
                    val key = command.string("key") ?: throw ArgError("commands[$index].key", "required")
                    run(sub, ctx, index, "phone_press_key", buildJsonObject { put("key", key) })
                }
                "swipe" -> {
                    val coordinates = listOf("startX", "startY", "endX", "endY")
                    if (coordinates.any { command.integer(it) !in 0..999 }) throw ArgError("commands[$index]", "swipe coordinates must be 0-999")
                    run(sub, ctx, index, "phone_swipe", buildJsonObject {
                        coordinates.forEach { put(it, command.integer(it)!!) }
                        command.integer("durationMs")?.let { put("durationMs", it) }
                    })
                }
                "wait", "assert" -> {
                    val text = command.string("targetText") ?: throw ArgError("commands[$index].targetText", "required")
                    val present = command.boolean("present") ?: true
                    val timeout = if (op == "assert") 0 else command.integer("timeoutMs") ?: 5_000
                    if (timeout !in 0..10_000) throw ArgError("commands[$index].timeoutMs", "must be 0-10000")
                    // 等的时候用户插了话:不再等,这一条和后面的都不做,先读那句话
                    val found = awaitText(ctx, text, present, timeout)
                    if (found == null) failCode(SKIPPED_FOR_NEW_MESSAGE)
                    else ToolExecution(buildJsonObject { put("ok", found); if (!found) put("error", "condition_unmet") })
                }
                else -> run(sub, ctx, index, "phone_list_elements", JsonObject(emptyMap()))
            }
            val content = result.content
            val ok = content["ok"] == JsonPrimitive(true)
            val error = (content["error"] as? JsonPrimitive)?.contentOrNull
            steps += row(index, op, ok, error, content["executed"] != JsonPrimitive(false))
            (content["screen"] as? JsonObject)?.let { finalScreen = it }
            if (op == "observe") finalScreen = content
            if (!ok) { stop = error ?: "failed"; break }
            if (content["app_switched"] != null) { stop = "app_switched"; break }
            if (content["opened"] != null) { stop = "opened_elsewhere"; break }
            if ((content["screen"] as? JsonObject)?.get("imageReason") != null) { stop = "screen_needs_review"; break }
        }
        val last = run(sub, ctx, commands.size, "phone_list_elements", JsonObject(emptyMap()))
        if (last.content["ok"] == JsonPrimitive(true)) finalScreen = last.content
        else if (stop == null) stop = "final_read_failed"
        return ToolExecution(buildJsonObject {
            put("ok", stop == null)
            stop?.let { put("error", "phone_run_stopped"); put("reason", it); put("recoverable", true) }
            put("completed", steps.count { it["ok"] == JsonPrimitive(true) })
            put("total", commands.size)
            put("steps", JsonArray(steps))
            finalScreen?.let { put("screen", it) }
        })
    }

    private data class Target(val ref: String? = null, val snapshotId: String? = null, val error: String? = null)

    private suspend fun select(sub: SubActions, ctx: ToolContext, index: Int, command: JsonObject, editable: Boolean): Target {
        val text = command.string("targetText")
        val id = command.string("resourceId")
        val role = command.string("role")
        if (text == null && id == null && role == null) {
            if (editable) return Target()
            val x = command.integer("x"); val y = command.integer("y")
            return if (x in 0..999 && y in 0..999) Target() else Target(error = "target_required")
        }
        val query = run(sub, ctx, index, "phone_query", buildJsonObject {
            text?.let { put("text", it) }; id?.let { put("resourceId", it) }; role?.let { put("role", it) }; put("limit", 100)
        })
        if (query.content["ok"] != JsonPrimitive(true)) return Target(error = "query_failed")
        if (query.content["truncated"] == JsonPrimitive(true)) return Target(error = "ambiguous_target")
        val sid = (query.content["snapshotId"] as? JsonPrimitive)?.contentOrNull ?: return Target(error = "no_snapshot")
        val refKey = if (editable) "editRef" else "actionRef"
        val all = (query.content["matches"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
            .filter { (it[refKey] ?: it["ref"]) is JsonPrimitive && it["enabled"] != JsonPrimitive(false) }
        val exact = text?.let { t -> all.filter { (it["name"] as? JsonPrimitive)?.contentOrNull?.equals(t, true) == true } }.orEmpty()
        val candidates = (exact.takeIf { it.isNotEmpty() } ?: all).let { items ->
            val preferred = items.filter { it[if (editable) "editable" else "clickable"] == JsonPrimitive(true) }
            preferred.takeIf { it.isNotEmpty() } ?: items
        }
        if (candidates.size != 1) return Target(error = if (candidates.isEmpty()) "target_not_found" else "ambiguous_target")
        return Target(((candidates.single()[refKey] ?: candidates.single()["ref"]) as JsonPrimitive).content, sid)
    }

    private suspend fun run(sub: SubActions, ctx: ToolContext, index: Int, name: String, args: JsonObject): ToolExecution =
        sub.run(Part.ToolCall("${ctx.callId}.${index + 1}.$name", name, args.toString()))

    /** 等到文字出现(或消失);用户插话撤掉了这次等待时为 null。 */
    private suspend fun awaitText(ctx: ToolContext, text: String, present: Boolean, timeoutMs: Int): Boolean? {
        if (hasText(ctx, text) == present) return true
        if (timeoutMs == 0) return false
        return waitOrSteer { ctx.screen.waitFor(if (present) "appear" else "disappear", text, timeoutMs.toLong()) }
    }

    private suspend fun hasText(ctx: ToolContext, text: String): Boolean {
        val snapshot = ctx.session.peek() ?: return false
        return PhoneAutomationEngine.find(snapshot, PhoneAutomationEngine.Query(text = text), 1)["total"] != JsonPrimitive(0)
    }

    private fun row(index: Int, op: String, ok: Boolean, error: String?, executed: Boolean) = buildJsonObject {
        put("n", index + 1); put("op", op); put("ok", ok)
        if (!executed) put("executed", false)
        error?.let { put("error", it) }
    }

    private fun failed(error: String) = ToolExecution(buildJsonObject { put("ok", false); put("error", error) })

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
    private fun JsonObject.integer(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull
    private fun JsonObject.boolean(key: String): Boolean? = when (this[key]) { JsonPrimitive(true) -> true; JsonPrimitive(false) -> false; else -> null }
}
