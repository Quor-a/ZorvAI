package com.ai.assistance.quro.core.mika.cap.web

import com.ai.assistance.quro.core.mika.model.Part
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import com.ai.assistance.quro.core.mika.tool.ToolIndex
import com.ai.assistance.quro.core.mika.tool.Verb
import com.ai.assistance.quro.core.mika.tool.SubActions

const val SCRIPT_CODE_MAX_CHARS = 8_000
const val SCRIPT_DEFAULT_TIMEOUT_MS = 30_000
const val SCRIPT_MAX_TIMEOUT_MS = 60_000
/** 一次脚本里最多几个动作(点、输入、打开网址)。 */
const val SCRIPT_MAX_ACTIONS = 20
/** 一次脚本里最多几次 quro.navigate;整页跳走本来就会结束脚本,这条只管同页内跳转后继续的情况。 */
const val SCRIPT_MAX_NAVIGATIONS = 3
/** 一次脚本里最多几次读(snapshot、read):每次都进运行记录。 */
const val SCRIPT_MAX_READS = 40
private const val MAX_LOGS = 50
private const val MAX_LOG_CHARS = 500
private const val READ_DEFAULT_CHARS = 4_000
private val PAGE_READS = setOf("query", "queryAll", "text")
private val QUERY_FIELDS = setOf("text", "attrs", "value", "box", "count")
private const val SELECTOR_MAX_CHARS = 500
const val QUERY_MAX_ITEMS = 200
private const val QUERY_DEFAULT_ITEMS = 50
private const val FIELD_MAX_CHARS = 4_000
private const val FIELD_DEFAULT_CHARS = 500
private const val TEXT_MAX_CHARS = 30_000
private const val TEXT_DEFAULT_CHARS = 4_000
private const val WAIT_DEFAULT_MS = 5_000

/** 这些错误一出现整个脚本就停:剩下的动作不再做,交回模型。 */
private val FATAL = setOf("confirmation_required", "guard_stopped", "handed_over", "tab_crashed", "no_tab_open")

/**
 * 一次 web_script 的 `quro.*` 调用:校验参数、计上限、翻译成工具调用交给 [sub](子步编号 `<callId>.<n>`),
 * 把结果整理成脚本拿到的值;记下每一步的结局,最后整理成工具结果。
 */
internal class ScriptCalls(private val callId: String, private val sub: SubActions?, private val tools: ToolIndex) : ScriptBridge {
    private val steps = mutableListOf<JsonObject>()
    private val logs = mutableListOf<String>()
    private var droppedLogs = 0
    private var n = 0
    private var actions = 0
    private var reads = 0
    private var navigations = 0
    private var snapshotId: String? = null
    private var abort: ScriptAbort? = null
    private var abortExtra: JsonObject = JsonObject(emptyMap())

    /** 做这个动作的工具。 */
    private fun tool(verb: Verb): String = tools.nameOf(verb, WEB)!!

    override fun log(text: String) {
        if (logs.size < MAX_LOGS) logs += text.take(MAX_LOG_CHARS) else droppedLogs++
    }

    override suspend fun call(op: String, args: JsonObject): JsonObject {
        abort?.let { throw it }
        if (op in PAGE_READS) return pageRead(op, args)
        val (tool, toolArgs) = when (op) {
            "waitText" -> {
                val text = str(args, "text") ?: return bad("quro.wait needs {text}")
                val ms = int(args, "timeoutMs", 0, PageScriptProtocol.MAX_WAIT_MS) ?: return bad("timeoutMs must be 0-${PageScriptProtocol.MAX_WAIT_MS}")
                tool(Verb.WaitFor) to buildJsonObject {
                    put("condition", "text_present"); put("value", text)
                    put("seconds", ((ms.takeIf { it > 0 } ?: WAIT_DEFAULT_MS) / 1000.0).coerceAtLeast(0.5))
                }
            }
            "scroll" -> {
                val dir = str(args, "direction")?.takeIf { it in setOf("up", "down", "left", "right") } ?: return bad("quro.scroll takes 'up', 'down', 'left' or 'right'")
                val amount = int(args, "amount", 1, 20) ?: return bad("amount must be 1-20")
                tool(Verb.Scroll) to buildJsonObject { put("direction", dir); if (amount > 0) put("amount", amount) }
            }
            "snapshot" -> tool(Verb.ListElements) to JsonObject(emptyMap())
            "read" -> {
                val max = int(args, "maxChars", 1, READ_PAGE_MAX_CHARS) ?: return bad("maxChars must be an integer 1-$READ_PAGE_MAX_CHARS")
                val offset = int(args, "offset", 0, Int.MAX_VALUE) ?: return bad("offset must be a non-negative integer")
                tool(Verb.ReadPage) to buildJsonObject { put("maxChars", max.takeIf { it > 0 } ?: READ_DEFAULT_CHARS); put("offset", offset) }
            }
            "tap", "type" -> {
                val ref = str(args, "ref") ?: return bad("quro.$op needs an element ref from quro.snapshot(), e.g. \"@e3\"")
                val sid = snapshotId ?: return fail("no_snapshot", "call quro.snapshot() first; tap and type use refs from the latest snapshot in this script")
                if (op == "tap") tool(Verb.Tap) to buildJsonObject { put("ref", ref); put("snapshotId", sid) }
                else {
                    val text = str(args, "text") ?: return bad("quro.type needs the text to type")
                    tool(Verb.TypeText) to buildJsonObject {
                        put("ref", ref); put("snapshotId", sid); put("text", text)
                        if (args["enter"] == JsonPrimitive(true)) put("pressEnter", true)
                    }
                }
            }
            "navigate" -> {
                val url = str(args, "url") ?: return bad("quro.navigate needs a full http(s) url")
                tool(Verb.Navigate) to buildJsonObject { put("url", url) }
            }
            else -> return fail("unknown_call", "quro.$op does not exist")
        }
        val write = op == "tap" || op == "type" || op == "navigate" || op == "scroll"
        if (write && actions >= SCRIPT_MAX_ACTIONS) stop("limit_exceeded", "the script reached its limit of $SCRIPT_MAX_ACTIONS actions")
        if (op == "navigate" && navigations >= SCRIPT_MAX_NAVIGATIONS) stop("limit_exceeded", "the script reached its limit of $SCRIPT_MAX_NAVIGATIONS navigations")
        if (!write && reads >= SCRIPT_MAX_READS) stop("limit_exceeded", "the script reached its limit of $SCRIPT_MAX_READS reads")
        val runner = sub ?: stop("actions_unavailable", "page actions cannot be run from a script here")
        if (write) actions++ else reads++
        if (op == "navigate") navigations++
        val exec = runner.run(Part.ToolCall("$callId.${++n}", tool, toolArgs.toString()))
        val c = exec.content
        val ok = c["ok"] == JsonPrimitive(true)
        val error = (c["error"] as? JsonPrimitive)?.contentOrNull
        steps += buildJsonObject {
            put("n", n); put("op", op)
            (toolArgs["ref"] as? JsonPrimitive)?.let { put("ref", it) }
            (toolArgs["url"] as? JsonPrimitive)?.let { put("url", it) }
            put("ok", ok)
            exec.changed?.let { put("changed", it) }
            if (!ok) put("error", error ?: "failed")
            if (c["executed"] == JsonPrimitive(false)) put("executed", false)
        }
        if (!ok) {
            val message = (c["message"] as? JsonPrimitive)?.contentOrNull ?: error ?: "failed"
            if (error in FATAL) {
                abortExtra = buildJsonObject { c["action"]?.let { put("action", it) }; c["matched"]?.let { put("matched", it) } }
                stop(error!!, "quro.$op (action ${steps.size}) stopped the script: $message")
            }
            return fail(error ?: "failed", message)
        }
        // 写操作之后页面变了,旧编号作废;要再点就再 snapshot
        if (write) snapshotId = null
        return when (op) {
            "snapshot" -> {
                snapshotId = (c["snapshotId"] as? JsonPrimitive)?.contentOrNull
                value {
                    put("snapshotId", c["snapshotId"] ?: JsonNull)
                    c["url"]?.let { put("url", it) }; c["title"]?.let { put("title", it) }
                    put("elements", JsonArray((c["elements"] as? JsonArray).orEmpty().mapNotNull { e ->
                        val o = e as? JsonObject ?: return@mapNotNull null
                        buildJsonObject {
                            put("ref", o["ref"] ?: JsonNull); put("role", o["role"] ?: JsonNull); put("name", o["name"] ?: JsonNull)
                            put("center", o["center"] ?: JsonNull)
                        }
                    }))
                    put("truncated", c["truncated"] ?: JsonPrimitive(false))
                }
            }
            "read" -> value { for (k in listOf("url", "title", "text", "links", "truncated", "nextOffset")) c[k]?.let { put(k, it) } }
            "navigate" -> value { for (k in listOf("url", "title", "loaded", "httpStatus")) c[k]?.let { put(k, it) } }
            "waitText" -> buildJsonObject { put("ok", true); put("value", c["met"] ?: JsonPrimitive(false)) }
            else -> value {
                put("changed", exec.changed?.let(::JsonPrimitive) ?: JsonNull)
                c["verification"]?.let { put("verification", it) }
            }
        }
    }

    /**
     * quro.query / queryAll / text:校验、计数,交回 `{ok, read:{mode, selector, fields, limit, maxChars}}`,由端口在读取世界里读。
     * 按 value 属性选元素不行(能一位一位猜出隐藏字段的值),转义、注释、命名空间的写法也认([SelectorPolicy])。
     */
    private fun pageRead(op: String, args: JsonObject): JsonObject {
        val selector = str(args, "selector")?.takeIf { it.length <= SELECTOR_MAX_CHARS } ?: return bad("quro.$op needs a CSS selector (at most $SELECTOR_MAX_CHARS characters)")
        if (SelectorPolicy.selectsByValue(selector)) return fail("invalid_selector", "selecting by the value attribute is not allowed; read values with fields: ['value']")
        val fields = when (val f = args["fields"]) {
            null, is JsonNull -> listOf("text")
            is JsonPrimitive -> listOfNotNull(f.takeIf { it.isString }?.content)
            is JsonArray -> f.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
            else -> return bad("fields must be a list of 'text', 'attrs', 'value', 'box' or 'count'")
        }
        if (fields.isEmpty() || fields.any { it !in QUERY_FIELDS }) return bad("fields must be a list of 'text', 'attrs', 'value', 'box' or 'count'")
        val limit = int(args, "limit", 1, QUERY_MAX_ITEMS) ?: return bad("limit must be 1-$QUERY_MAX_ITEMS")
        val textMax = if (op == "text") TEXT_MAX_CHARS else FIELD_MAX_CHARS
        val maxChars = int(args, "maxChars", 1, textMax) ?: return bad("maxChars must be 1-$textMax")
        if (reads >= SCRIPT_MAX_READS) stop("limit_exceeded", "the script reached its limit of $SCRIPT_MAX_READS reads")
        reads++
        steps += buildJsonObject { put("n", ++n); put("op", op); put("selector", selector.take(100)); put("ok", true) }
        val mode = when {
            op == "text" -> "text"
            fields == listOf("count") -> "count"
            op == "query" -> "one"
            else -> "all"
        }
        return buildJsonObject {
            put("ok", true)
            put("read", buildJsonObject {
                put("mode", mode); put("selector", selector)
                put("fields", JsonArray(fields.map(::JsonPrimitive)))
                put("limit", limit.takeIf { it > 0 } ?: QUERY_DEFAULT_ITEMS)
                put("maxChars", maxChars.takeIf { it > 0 } ?: if (op == "text") TEXT_DEFAULT_CHARS else FIELD_DEFAULT_CHARS)
            })
        }
    }

    /** 整理成 web_script 的工具结果。 */
    fun content(run: ScriptRun): JsonObject {
        val stopped = abort
        return buildJsonObject {
            when {
                stopped != null -> {
                    put("ok", false); put("error", stopped.error); put("message", stopped.message + hintFor(stopped.error))
                    abortExtra.forEach { (k, v) -> put(k, v) }
                    if (stopped.error == "confirmation_required") put("counts_as_failure", false)
                    put("recoverable", true)
                }
                run is ScriptRun.Done -> {
                    put("ok", true); put("endedBy", "return")
                    val v = run.valueJson
                    put("result", v?.take(EVAL_RESULT_MAX_CHARS) ?: "null")
                    put("truncated", (v?.length ?: 0) > EVAL_RESULT_MAX_CHARS)
                }
                run is ScriptRun.Navigated -> {
                    put("ok", true); put("endedBy", "navigation")
                    put("message", "The page navigated to a new document, which ends the script (its state is gone). Observe the new page, then continue or run another script.")
                }
                run is ScriptRun.Failed -> {
                    put("ok", false); put("error", run.error); put("message", run.message + hintFor(run.error))
                    run.line?.let { put("line", it) }
                    put("recoverable", true)
                }
            }
            put("actions", JsonArray(steps))
            if (logs.isNotEmpty()) put("logs", JsonArray(logs.map(::JsonPrimitive)))
            if (droppedLogs > 0) put("logsDropped", droppedLogs)
        }
    }

    private fun hintFor(error: String): String = when (error) {
        "confirmation_required" -> ". That action was not run, and the script stopped. Irreversible actions never run from a script: call request_confirmation, then do that step as a normal tool call."
        "limit_exceeded" -> ". Split the work: return what you have and continue with another script."
        "timeout" -> ". Do less per script, or raise timeoutMs (at most $SCRIPT_MAX_TIMEOUT_MS)."
        "devtools_unavailable" -> ". Do the steps with web_observe, web_tap and web_type_text instead."
        "script_error" -> ". Fix the script and run it again. The script has no DOM (document, window and the like are undefined): read with quro.query / quro.queryAll / quro.text, act with quro.tap / quro.type / quro.scroll / quro.navigate."
        "blocked_network", "blocked_navigation" -> ". The script stopped: only quro actions may change the page. Observe before going on."
        else -> ""
    }

    private fun stop(error: String, message: String): Nothing {
        throw ScriptAbort(error, message).also { abort = it }
    }

    private fun value(b: JsonObjectBuilder.() -> Unit) = buildJsonObject { put("ok", true); put("value", buildJsonObject(b)) }
    private fun fail(error: String, message: String) = buildJsonObject { put("ok", false); put("error", error); put("message", message) }
    private fun bad(message: String) = fail("invalid_arguments", message)

    private fun str(o: JsonObject, k: String): String? = (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }

    /** 没给为 0;给了但不合法为 null。 */
    private fun int(o: JsonObject, k: String, min: Int, max: Int): Int? {
        val v = o[k] ?: return 0
        if (v is JsonNull) return 0
        val i = (v as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull ?: return null
        return i.takeIf { it in min..max }
    }

    private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()
}
