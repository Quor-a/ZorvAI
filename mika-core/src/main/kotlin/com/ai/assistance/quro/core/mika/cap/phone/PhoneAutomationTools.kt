package com.ai.assistance.quro.core.mika.cap.phone

import com.ai.assistance.quro.core.mika.model.Part
import com.ai.assistance.quro.core.mika.observation.Untrusted
import com.ai.assistance.quro.core.mika.prompt.Prompts
import com.ai.assistance.quro.core.mika.tool.Args
import com.ai.assistance.quro.core.mika.tool.Risk
import com.ai.assistance.quro.core.mika.tool.ToolContext
import com.ai.assistance.quro.core.mika.tool.ToolDef
import com.ai.assistance.quro.core.mika.tool.ToolExecution
import com.ai.assistance.quro.core.mika.tool.Verb
import com.ai.assistance.quro.core.mika.tool.int
import com.ai.assistance.quro.core.mika.tool.str
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import com.ai.assistance.quro.core.mika.uistate.session

/** 不截屏地按文字、资源 ID 或控件类型查询当前无障碍树,结果保留父子线索。 */
internal object PhoneQuery : PhoneTool {
    override val verb = Verb.Query
    override val risk = Risk.Read
    override val label = "查找界面控件"
    override val def = ToolDef("phone_query", Prompts.tool("phone_query"), mapOf(
        "text" to str("Case-insensitive text or accessibility description to find."),
        "resourceId" to str("Full or partial Android resource ID to find."),
        "role" to str("Full or partial accessibility class or role to find."),
        "limit" to int("Maximum results, default 30.", 1, 100),
    ))

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val read = ctx.session.observe(withImage = false, recordFrame = false)
        val snapshot = ctx.session.lastSnapshot!!
        val query = PhoneAutomationEngine.Query(a.str("text", max = 200), a.str("resourceId", max = 200), a.str("role", max = 100))
        val found = PhoneAutomationEngine.find(snapshot, query, a.int("limit", 1, 100) ?: 30)
        return read.with(content = JsonObject(mapOf("ok" to JsonPrimitive(true)) + found))
    }
}

/** 一次采集多屏文字。每次滚动仍通过子动作入口,因此取消、状态记录和动作结果与单步一致。 */
internal object PhoneCollect : PhoneTool {
    override val verb = Verb.Collect
    override val risk = Risk.Write
    override val composite = true
    override val label = "采集多屏内容"
    override val def = ToolDef("phone_collect", Prompts.tool("phone_collect"), mapOf(
        "direction" to str("Direction of newer content: up or down.", listOf("up", "down")),
        "pages" to int("Maximum screens to inspect, 1-30 (default 10).", 1, 30),
        "maxItems" to int("Maximum lines to return, 1-1000 (default 300).", 1, 1000),
        "fromY" to int("Top of the content area, normalized 0-999 (default 50).", 0, 999),
        "toY" to int("Bottom of the content area, normalized 0-999 (default 950).", 0, 999),
    ), listOf("direction"))

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution = ctx.session.textOnly { collect(a, ctx) }

    private suspend fun collect(a: Args, ctx: ToolContext): ToolExecution {
        val direction = a.str("direction")!!
        if (direction !in setOf("up", "down")) return failure("invalid_direction")
        val pages = a.int("pages", 1, 30) ?: 10
        val maxItems = a.int("maxItems", 1, 1000) ?: 300
        val fromY = a.int("fromY", 0, 999) ?: 50
        val toY = a.int("toY", 0, 999) ?: 950
        if (fromY >= toY) return failure("invalid_region")
        val sub = ctx.sub ?: return failure("actions_unavailable")
        var collected = emptyList<String>()
        var app: String? = null
        var inspected = 0
        var scrolled = 0
        var noNewPages = 0
        var stopped = "page_limit"
        var finalScreen: JsonObject? = null
        for (page in 0 until pages) {
            val read = sub.run(Part.ToolCall("${ctx.callId}.read${page + 1}", "phone_list_elements", "{}"))
            val content = read.content
            if (content["ok"] != JsonPrimitive(true)) {
                stopped = (content["error"] as? JsonPrimitive)?.contentOrNull ?: "read_failed"
                break
            }
            val ocrReadable = (content["visibleText"] as? JsonArray)?.isNotEmpty() == true
            if (content["protected"] == JsonPrimitive(true) ||
                !ocrReadable && (content["readable"] == JsonPrimitive(false) || content["imageReason"] != null)) {
                stopped = "screen_needs_review"
                break
            }
            val currentApp = (content["currentApp"] as? JsonPrimitive)?.contentOrNull
            if (page == 0) {
                if (currentApp == null) { stopped = "no_app_open"; break }
                app = currentApp
            }
            else if (app != null && currentApp != app) { stopped = "app_switched"; break }
            val lines = pageLines(content, fromY, toY)
            val before = collected.size
            collected = if (direction == "down") PhoneAutomationEngine.appendPage(collected, lines)
            else PhoneAutomationEngine.prependPage(collected, lines)
            inspected++
            finalScreen = content
            noNewPages = if (collected.size == before) noNewPages + 1 else 0
            if (collected.size >= maxItems) { stopped = "item_limit"; break }
            if (noNewPages >= 2) { stopped = "no_new_content"; break }
            if (page == pages - 1) break
            val scroll = sub.run(Part.ToolCall("${ctx.callId}.scroll${page + 1}", "phone_scroll", buildJsonObject {
                put("direction", direction); put("amount", 3)
            }.toString()))
            if (scroll.content["ok"] != JsonPrimitive(true)) {
                stopped = (scroll.content["error"] as? JsonPrimitive)?.contentOrNull ?: "scroll_failed"
                break
            }
            scrolled++
        }
        val output = collected.take(maxItems)
        val joined = output.joinToString("\n")
        return ToolExecution(buildJsonObject {
            put("ok", inspected > 0)
            if (inspected == 0) put("error", stopped)
            put("currentApp", app)
            put("pagesInspected", inspected)
            put("scrolls", scrolled)
            put("items", output.size)
            put("stoppedBecause", stopped)
            put("truncated", collected.size > maxItems || joined.length > 49_000)
            put("text", Untrusted.wrapWithin(joined, 50_000))
            finalScreen?.get("snapshotId")?.let { put("snapshotId", it) }
        })
    }

    private fun pageLines(content: JsonObject, fromY: Int, toY: Int): List<String> {
        data class Line(val text: String, val top: Int, val bottom: Int, val left: Int)
        val out = mutableListOf<Line>()
        fun add(value: String?, bounds: JsonArray?) {
            val text = value?.trim()?.replace(Regex("\\s+"), " ").orEmpty()
            if (text.isEmpty()) return
            val top = (bounds?.getOrNull(1) as? JsonPrimitive)?.intOrNull ?: 0
            val bottom = (bounds?.getOrNull(3) as? JsonPrimitive)?.intOrNull ?: 999
            val left = (bounds?.getOrNull(0) as? JsonPrimitive)?.intOrNull ?: 0
            if (bottom >= fromY && top <= toY) out += Line(text, top, bottom, left)
        }
        fun visit(node: JsonObject) {
            val bounds = node["bounds"] as? JsonArray
            if (node["secret"] != JsonPrimitive(true)) {
                for (key in listOf("text", "description")) {
                    add((node[key] as? JsonPrimitive)?.contentOrNull, bounds)
                }
            }
            (node["children"] as? JsonArray)?.forEach { (it as? JsonObject)?.let(::visit) }
        }
        val windows = content["tree"] as? JsonArray
        if (windows != null) windows.forEach { window ->
            ((window as? JsonObject)?.get("nodes") as? JsonArray)?.forEach { (it as? JsonObject)?.let(::visit) }
        }
        if (out.isEmpty()) (content["elements"] as? JsonArray)?.forEach { element ->
            val obj = element as? JsonObject ?: return@forEach
            if (obj["secret"] != JsonPrimitive(true)) add((obj["name"] as? JsonPrimitive)?.contentOrNull, obj["bounds"] as? JsonArray)
        }
        // OCR 只包含树里缺失的文字；与树文本按屏幕位置合并，群聊才不会把补出的消息都放到末尾。
        (content["visibleText"] as? JsonArray)?.forEach { entry ->
            val obj = entry as? JsonObject ?: return@forEach
            add((obj["text"] as? JsonPrimitive)?.contentOrNull, obj["bounds"] as? JsonArray)
        }
        val sorted = out.sortedWith(compareBy(Line::top, Line::left))
        return sorted.filterIndexed { index, line ->
            val previous = sorted.getOrNull(index - 1)
            previous == null || previous.text != line.text || minOf(previous.bottom, line.bottom) <= maxOf(previous.top, line.top)
        }.map(Line::text)
    }

    private fun failure(error: String) = ToolExecution(buildJsonObject { put("ok", false); put("error", error) })
}
