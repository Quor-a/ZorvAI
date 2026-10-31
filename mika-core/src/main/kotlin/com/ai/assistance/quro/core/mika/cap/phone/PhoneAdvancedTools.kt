package com.ai.assistance.quro.core.mika.cap.phone

import com.ai.assistance.quro.core.mika.model.Part
import com.ai.assistance.quro.core.mika.observation.Untrusted
import com.ai.assistance.quro.core.mika.prompt.Feedback
import com.ai.assistance.quro.core.mika.prompt.Prompts
import com.ai.assistance.quro.core.mika.tool.ArgError
import com.ai.assistance.quro.core.mika.tool.Args
import com.ai.assistance.quro.core.mika.tool.Risk
import com.ai.assistance.quro.core.mika.tool.ToolContext
import com.ai.assistance.quro.core.mika.tool.ToolDef
import com.ai.assistance.quro.core.mika.tool.ToolExecution
import com.ai.assistance.quro.core.mika.tool.Verb
import com.ai.assistance.quro.core.mika.tool.int
import com.ai.assistance.quro.core.mika.tool.str
import com.ai.assistance.quro.core.mika.uistate.UiTreeNode
import com.ai.assistance.quro.core.mika.uistate.toPx
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import com.ai.assistance.quro.core.mika.uistate.action
import com.ai.assistance.quro.core.mika.uistate.lastOrSnapshot
import com.ai.assistance.quro.core.mika.uistate.screen
import com.ai.assistance.quro.core.mika.uistate.session

/** 按界面实际内容推进，直到找到目标或内容停止变化。 */
internal object PhoneFind : PhoneTool {
    override val verb = Verb.PhoneFind
    override val risk = Risk.Write
    override val composite = true
    override val label = "滚动查找"
    override val def = ToolDef("phone_find", Prompts.tool("phone_find"), mapOf(
        "text" to str("Text or accessibility description to find."),
        "resourceId" to str("Full or partial Android resource ID."),
        "role" to str("Full or partial role or class."),
        "direction" to str("Where the target is: up or down.", listOf("up", "down")),
        "maxScrolls" to int("Maximum scrolls before returning, 1-200 (default 30).", 1, 200),
    ), listOf("direction"))

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution = ctx.session.textOnly {
        val query = PhoneAutomationEngine.Query(a.str("text", max = 200), a.str("resourceId", max = 200), a.str("role", max = 100))
        if (query.text == null && query.resourceId == null && query.role == null) throw ArgError("text", "give a target")
        val direction = a.str("direction")!!
        if (direction !in setOf("up", "down")) throw ArgError("direction", "must be up or down")
        val sub = ctx.sub ?: return@textOnly fail("actions_unavailable")
        val limit = a.int("maxScrolls", 1, 200) ?: 30
        var app: String? = null
        var previous: Int? = null
        var unchanged = 0
        var scrolled = 0
        var reason = "scroll_limit"
        var result: JsonObject? = null
        for (i in 0..limit) {
            val read = ctx.session.observe(withImage = false, recordFrame = false)
            if (read.content["ok"] != JsonPrimitive(true)) { reason = "read_failed"; break }
            val screen = ctx.session.lastSnapshot
            if (screen == null) { reason = "read_failed"; break }
            val currentApp = screen.packageName ?: screen.unreadableApp
            if (screen.protected || !screen.hasWindow || currentApp == null) { reason = "screen_needs_review"; break }
            if (app == null) app = currentApp else if (app != currentApp) { reason = "app_switched"; break }
            result = PhoneAutomationEngine.find(screen, query, 100)
            if ((result["total"] as? JsonPrimitive)?.intOrNull ?: 0 > 0) { reason = "found"; break }
            val fingerprint = screen.windows.hashCode().takeIf { screen.windows.isNotEmpty() } ?: screen.elements.hashCode()
            unchanged = if (fingerprint == previous) unchanged + 1 else 0
            if (unchanged >= 2) { reason = "content_unchanged"; break }
            previous = fingerprint
            if (i == limit) break
            val action = sub.run(Part.ToolCall("${ctx.callId}.scroll${i + 1}", "phone_scroll", buildJsonObject {
                put("direction", direction); put("amount", 3)
            }.toString()))
            if (action.content["ok"] != JsonPrimitive(true)) {
                reason = (action.content["error"] as? JsonPrimitive)?.contentOrNull ?: "scroll_failed"
                break
            }
            scrolled++
        }
        ToolExecution(buildJsonObject {
            put("ok", reason == "found")
            put("found", reason == "found")
            put("stoppedBecause", reason)
            put("scrolls", scrolled)
            result?.forEach { (key, value) -> put(key, value) }
        })
    }
}

/** 按无障碍事件醒来，避免固定间隔反复读屏。 */
internal object PhoneWait : PhoneTool {
    override val verb = Verb.WaitFor
    override val risk = Risk.Read
    override val label = "等待界面条件"
    override val yieldsToSteer = true
    override val def = ToolDef("phone_wait", Prompts.tool("phone_wait"), mapOf(
        "condition" to str("appear, disappear, activity, or stable.", listOf("appear", "disappear", "activity", "stable")),
        "value" to str("Text for appear/disappear; activity class name for activity."),
        "timeoutMs" to int("Maximum wait in milliseconds, 0-30000 (default 10000).", 0, 30000),
    ), listOf("condition"))

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val condition = a.str("condition")!!
        val value = a.str("value", max = 200)
        if (condition != "stable" && value == null) throw ArgError("value", "required")
        val timeout = (a.int("timeoutMs", 0, 30000) ?: 10000).toLong()
        val met = ctx.screen.waitFor(condition, value, timeout)
        return ToolExecution(buildJsonObject { put("ok", met); put("met", met); put("condition", condition); if (!met) put("error", "timeout") })
    }
}

internal object PhoneEventTool : PhoneTool {
    override val verb = Verb.PhoneEvent
    override val risk = Risk.Read
    override val label = "等待界面事件"
    override val yieldsToSteer = true
    override val def = ToolDef("phone_event", Prompts.tool("phone_event"), mapOf(
        "kind" to str("window, content, notification, or any.", listOf("window", "content", "notification", "any")),
        "mode" to str("once returns the next event; watch collects events during the timeout.", listOf("once", "watch")),
        "packageName" to str("Optional package name filter."),
        "timeoutMs" to int("Maximum wait in milliseconds, 1-30000 (default 10000).", 1, 30000),
        "maxEvents" to int("Maximum events in watch mode, 1-100 (default 30).", 1, 100),
    ), listOf("kind"))

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val kind = a.str("kind")!!
        val pkg = a.str("packageName", max = 200)
        val timeout = (a.int("timeoutMs", 1, 30000) ?: 10000).toLong()
        if (a.str("mode") == "watch") {
            val events = ctx.screen.watchEvents(kind, pkg, timeout, a.int("maxEvents", 1, 100) ?: 30)
            return ToolExecution(buildJsonObject {
                put("ok", events.isNotEmpty()); put("count", events.size)
                put("events", JsonArray(events.map { event -> buildJsonObject {
                    put("type", event.type); put("packageName", event.packageName)
                    put("text", Untrusted.wrapWithin(event.text.orEmpty(), 2_000))
                } }))
                if (events.isEmpty()) put("error", "timeout")
            })
        }
        val event = ctx.screen.nextEvent(kind, pkg, timeout)
        return ToolExecution(buildJsonObject {
            put("ok", event != null)
            if (event == null) put("error", "timeout") else {
                put("type", event.type); put("packageName", event.packageName)
                put("text", Untrusted.wrapWithin(event.text.orEmpty(), 2_000))
            }
        })
    }
}

/** 结构化读取当前可见窗口，保留列表行和表单字段的父子关系。 */
internal object PhoneExtract : PhoneTool {
    override val verb = Verb.PhoneExtract
    override val risk = Risk.Read
    override val label = "提取界面数据"
    override val def = ToolDef("phone_extract", Prompts.tool("phone_extract"), mapOf(
        "mode" to str("text, list, or form.", listOf("text", "list", "form")),
        "maxItems" to int("Maximum records, 1-200 (default 100).", 1, 200),
    ), listOf("mode"))

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution = ctx.session.textOnly {
        val mode = a.str("mode")!!
        val max = a.int("maxItems", 1, 200) ?: 100
        ctx.session.observe(withImage = false, recordFrame = false)
        val screen = ctx.session.lastSnapshot ?: return@textOnly fail("read_failed")
        val records = mutableListOf<JsonObject>()
        fun text(node: UiTreeNode): List<String> = buildList {
            if (!node.secret) {
                node.text?.takeIf(String::isNotBlank)?.let(::add)
                node.description?.takeIf { it.isNotBlank() && it != node.text }?.let(::add)
            }
            node.children.forEach { addAll(text(it)) }
        }
        fun walk(node: UiTreeNode, parents: List<String>) {
            if (records.size >= max) return
            when (mode) {
                "text" -> if (!node.secret) listOfNotNull(node.text, node.description).distinct().filter(String::isNotBlank).forEach { value ->
                    if (records.size < max) records += buildJsonObject { put("text", value.take(2_000)); node.ref?.let { put("ref", it) }; put("parents", JsonArray(parents.map(::JsonPrimitive))) }
                }
                "form" -> if (node.editable) records += buildJsonObject {
                    put("role", node.role); put("secret", node.secret); node.ref?.let { put("ref", it) }
                    node.resourceId?.let { put("resourceId", it) }
                    node.hintText?.let { put("hint", it) }
                    if (!node.secret) node.text?.let { put("value", it.take(2_000)) }
                    put("parents", JsonArray(parents.map(::JsonPrimitive)))
                }
                "list" -> if (node.scrollable || node.role.contains("RecyclerView", true) || node.role.contains("ListView", true)) {
                    node.children.forEach { row ->
                        if (records.size < max) {
                            val values = text(row).distinct().filter(String::isNotBlank)
                            if (values.isNotEmpty()) records += buildJsonObject {
                                put("texts", JsonArray(values.map { JsonPrimitive(it.take(2_000)) }))
                                row.ref?.let { put("ref", it) }
                                put("bounds", JsonArray(row.bounds.map(::JsonPrimitive)))
                            }
                        }
                    }
                    return
                }
            }
            val next = (parents + listOfNotNull(node.text, node.description).firstOrNull { it.isNotBlank() }.orEmpty()).filter(String::isNotBlank).takeLast(6)
            node.children.forEach { walk(it, next) }
        }
        screen.windows.forEach { window -> window.nodes.forEach { walk(it, listOfNotNull(window.title)) } }
        ToolExecution(buildJsonObject {
            put("ok", true); put("mode", mode); put("snapshotId", screen.snapshotId)
            put("currentApp", screen.packageName ?: screen.unreadableApp)
            put("items", JsonArray(records)); put("count", records.size); put("truncated", records.size == max)
        })
    }
}

/** 查顶层弹窗；指定按钮文字时才点击，避免猜测弹窗用途。 */
internal object PhoneDialog : PhoneTool {
    override val verb = Verb.PhoneDialog
    override val risk = Risk.Write
    override val composite = true
    override val label = "检查弹窗"
    override val def = ToolDef("phone_dialog", Prompts.tool("phone_dialog"), mapOf(
        "buttonText" to str("Optional exact visible button label to dismiss the dialog."),
    ))

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution = ctx.session.textOnly {
        ctx.session.observe(withImage = false, recordFrame = false)
        val screen = ctx.session.lastSnapshot ?: return@textOnly fail("read_failed")
        val windows = screen.windows.sortedByDescending { it.layer }
        val dialog = windows.firstOrNull { w ->
            w.nodes.any { containsDialog(it) } ||
                (w.type == "system" && w.active && w.nodes.isNotEmpty()) ||
                (w.type == "application" && windows.count { it.type == "application" } > 1 && w == windows.first())
        }
        val labels = mutableListOf<String>()
        val buttons = mutableListOf<Pair<String, String>>()
        fun visit(node: UiTreeNode) {
            if (!node.secret) {
                node.text?.takeIf(String::isNotBlank)?.let { labels += it }
                if (node.clickable && node.ref != null) listOfNotNull(node.text, node.description).firstOrNull { it.isNotBlank() }?.let { buttons += it to node.ref }
            }
            node.children.forEach(::visit)
        }
        dialog?.nodes?.forEach(::visit)
        val wanted = a.str("buttonText", max = 100)
        var clicked: JsonObject? = null
        var error: String? = null
        if (wanted != null) {
            if (dialog == null) error = "dialog_not_found"
            else {
                val matches = buttons.filter { it.first.equals(wanted, true) }
                if (matches.size != 1) error = if (matches.isEmpty()) "button_not_found" else "ambiguous_button"
                else {
                    val sub = ctx.sub
                    if (sub == null) error = "actions_unavailable"
                    else clicked = sub.run(Part.ToolCall("${ctx.callId}.dismiss", "phone_tap", buildJsonObject {
                        put("ref", matches.single().second); put("snapshotId", screen.snapshotId)
                    }.toString())).content
                }
            }
        }
        ToolExecution(buildJsonObject {
            put("ok", error == null && (clicked == null || clicked["ok"] == JsonPrimitive(true)))
            put("found", dialog != null); put("snapshotId", screen.snapshotId)
            dialog?.let { put("title", it.title); put("packageName", it.packageName) }
            put("text", Untrusted.wrapWithin(labels.distinct().joinToString("\n"), 8_000))
            put("buttons", JsonArray(buttons.distinctBy { it.first }.map { (label, ref) -> buildJsonObject { put("text", label); put("ref", ref) } }))
            error?.let { put("error", it) }
            clicked?.let { put("action", it) }
        })
    }

    private fun containsDialog(node: UiTreeNode): Boolean = node.role.contains("Dialog", true) || node.children.any(::containsDialog)
}

/** 单指路径或双指缩放，所有坐标均为 0-999。 */
internal object PhoneGesture : PhoneTool {
    override val verb = Verb.PhoneGesture
    override val risk = Risk.Write
    override val label = "多点手势"
    override val showsScreenAfter = true
    override val reportsEffect = true
    override val logged = true
    override val def = ToolDef("phone_gesture", Prompts.tool("phone_gesture"), mapOf(
        "paths" to buildJsonObject {
            put("type", "array"); put("minItems", 1); put("maxItems", 2)
            put("description", "One or two simultaneous finger paths. Each path is 2-32 [x,y] points in normalized 0-999 coordinates.")
            put("items", buildJsonObject { put("type", "array"); put("minItems", 2); put("maxItems", 32); put("items", buildJsonObject { put("type", "array"); put("minItems", 2); put("maxItems", 2); put("items", buildJsonObject { put("type", "integer"); put("minimum", 0); put("maximum", 999) }) }) })
        },
        "durationMs" to int("Gesture duration, 50-5000 ms (default 500).", 50, 5000),
    ), listOf("paths"))

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val paths = a.o["paths"] as? JsonArray ?: throw ArgError("paths", "required")
        if (paths.size !in 1..2) throw ArgError("paths", "must contain 1 or 2 paths")
        val screen = ctx.lastOrSnapshot()
        val pixels = paths.mapIndexed { p, raw ->
            val points = raw as? JsonArray ?: throw ArgError("paths[$p]", "must be an array")
            if (points.size !in 2..32) throw ArgError("paths[$p]", "must contain 2-32 points")
            points.mapIndexed { i, item ->
                val pair = item as? JsonArray ?: throw ArgError("paths[$p][$i]", "must be [x,y]")
                val x = (pair.getOrNull(0) as? JsonPrimitive)?.intOrNull
                val y = (pair.getOrNull(1) as? JsonPrimitive)?.intOrNull
                if (pair.size != 2 || x !in 0..999 || y !in 0..999) throw ArgError("paths[$p][$i]", "coordinates must be 0-999")
                toPx(x!!, screen.width) to toPx(y!!, screen.height)
            }
        }
        return ctx.action(ctx.screen.gesture(pixels, (a.int("durationMs", 50, 5000) ?: 500).toLong()))
    }
}

/** Android shell 身份命令；与沙箱进程的命令入口分开。 */
internal object PhoneCommand : PhoneTool {
    /** 连着失败这么多次就提醒别再换着写法试。 */
    private const val REPEATED_FAILURES = 2
    override val verb = Verb.PhoneCommand
    override val risk = Risk.Write
    override val offScreen = true
    override val label = "系统命令"
    override val def = ToolDef("phone_system_command", Prompts.tool("phone_system_command"), mapOf(
        "command" to str("Android shell command. MIKA_DISPLAY_ID is the current task's virtual display ID."),
    ), listOf("command"))

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val command = a.str("command", max = 4_000)!!
        val phone = ctx.phone
        val purpose = CommandSteer.purpose(command)
        // 已提醒过还在用 shell 点、打字、按键、启动,或用 shell 分享文字:不执行,只说该用哪个工具
        if (CommandSteer.refuses(purpose, phone.commandSteered)) return ToolExecution(buildJsonObject {
            put("ok", false); put("error", "use_phone_tool")
            put("hint", Feedback.format("system_command.refused", "use" to Feedback[purpose!!.feedbackKey]))
        })
        val result = ctx.screen.systemCommand(command) ?: return fail("no_app_open")
        phone.commandFailures = if (result.exitCode == 0) 0 else phone.commandFailures + 1
        if (purpose != null) phone.commandSteered = true
        // 模仿专门工具的命令、连着失败的命令:第一次提醒换回专门工具,照样执行
        val hints = listOfNotNull(
            purpose?.let { Feedback[it.feedbackKey] },
            Feedback["system_command.failed_again"].takeIf { phone.commandFailures >= REPEATED_FAILURES },
        )
        return ToolExecution(buildJsonObject {
            put("ok", result.exitCode == 0); put("exitCode", result.exitCode)
            put("output", Untrusted.wrapWithin(result.output, 65_536))
            if (hints.isNotEmpty()) put("hint", hints.joinToString(" "))
        })
    }
}

private fun fail(error: String) = ToolExecution(buildJsonObject { put("ok", false); put("error", error) })
