package com.ai.assistance.quro.core.mika.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 有的模型会把本该走 `tool_calls` 通道的工具调用标记直接吐进正文:
 * DeepSeek V3.2 / V4 的 DSML(`<｜DSML｜invoke name="…">…`),以及 Qwen 一类的 `<tool_call>{…}</tool_call>`。
 * 原样交给界面上就是一堆 `<｜｜DSML｜｜ calls>` 乱码,而且会被当成正文记进历史。
 *
 * 这里做两件事,顺序是先还原、再剥离:
 * 1. 能还原成工具调用的就还原(参数按 `string` 标志转成字符串或 JSON 值),交给工具引擎照常执行;
 * 2. 还原不出来的 DSML 只把标记剥掉,留下标记之外的正文;还原不出来的 `<tool_call>` 块原样留着,
 *    由 AgentLoop 的漏调用检查(LeakedToolCall)退回一次,不当成回答交出。
 *
 * 只在正文里真的出现标记时才动,正常回答一个字符都不改(见 [repair] 的 changed 判断)。
 * 回答里成对写出、里面是合法调用 JSON 的 `<tool_call>` 块会被当成调用,这是有意的取舍:
 * 把乱码显示给用户的代价更大;调了不存在的工具时,工具引擎照常回报错误。
 */
object LeakedToolMarkup {
    data class Invocation(val name: String, val argsJson: String)

    /** [text] 是剥掉标记后剩下的正文;[calls] 是从标记里还原出来的工具调用(可能为空)。 */
    data class Scan(val text: String, val calls: List<Invocation>)

    /** 竖线有两种写法:全角 `｜` 和半角 `|`,泄漏时还会写成一到两根。 */
    private const val B = "[｜|]"

    private fun bars(prefix: String) = prefix + B + "{1,2}\\s*DSML\\s*" + B + "{1,2}"

    private val INVOKE = Regex(
        bars("<") + "\\s*invoke\\s+name\\s*=\\s*\"([^\"]*)\"\\s*>(.*?)</" + B + "{1,2}\\s*DSML\\s*" + B + "{1,2}\\s*invoke\\s*>",
        RegexOption.DOT_MATCHES_ALL,
    )
    private val PARAM = Regex(
        bars("<") + "\\s*parameter\\s+name\\s*=\\s*\"([^\"]*)\"(?:\\s+string\\s*=\\s*\"(true|false)\")?\\s*>(.*?)</" +
            B + "{1,2}\\s*DSML\\s*" + B + "{1,2}\\s*parameter\\s*>",
        RegexOption.DOT_MATCHES_ALL,
    )

    /** 剩下的成对/落单标记(外层 `calls`、`function_calls` 之类)一律去掉。 */
    private val TAG = Regex("</?" + B + "{1,2}\\s*DSML\\s*" + B + "{1,2}[^>]*>")

    /** Qwen 一类:`<tool_call>{"name":…,"arguments":…}</tool_call>`。 */
    private val TOOL_CALL_BLOCK = Regex(
        "<tool_call>\\s*(.*?)\\s*</tool_call>",
        RegexOption.DOT_MATCHES_ALL,
    )
    private val PIPE_TOOL_CALL_BLOCK = Regex(
        "<" + B + "{1,2}tool_call" + B + "{1,2}\\s*>(.*?)</" + B + "{1,2}tool_call" + B + "{1,2}\\s*>",
        RegexOption.DOT_MATCHES_ALL,
    )

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * 流式里常见:标记刚开了个头(`<｜`、`<｜｜DSML`、`<｜｜DSML｜｜ invoke name="pho`)。
     * 只认竖线开头、接着是 DSML 前几个字母的半个标记,不匹配正常文字(`a<b 这段讲 DSML` 不算)。
     */
    private val PARTIAL_TAIL = Regex("^/?" + B + "{1,2}\\s*(?:D(?:S(?:M(?:L.*)?)?)?)?$", RegexOption.DOT_MATCHES_ALL)

    /** 流式预览用:每来一帧就把已经收到的那部分标记剥掉,别让用户看着乱码闪过去。 */
    fun stripMarkup(text: String): String = scan(text).text

    /** 扫一段正文:剥标记、还原调用。没有标记时原样返回,调用为空。 */
    fun scan(text: String): Scan {
        if (text.isEmpty()) return Scan(text, emptyList())
        var rest = text
        val calls = mutableListOf<Invocation>()

        // 1. DSML:先按 invoke 整块抽出来,参数按 parameter 抽
        if (rest.contains("DSML", ignoreCase = true)) {
            for (m in INVOKE.findAll(rest)) {
                val name = m.groupValues[1].trim()
                if (name.isEmpty()) continue
                calls += Invocation(name, paramsJson(m.groupValues[2]))
            }
            if (calls.isNotEmpty()) rest = rest.replace(INVOKE, " ")
            rest = TAG.replace(rest, " ")
        }

        // 2. <tool_call>{…}</tool_call>(里面是 JSON,可能是一个对象或一组)
        rest = extractJsonBlocks(rest, TOOL_CALL_BLOCK, calls)
        rest = extractJsonBlocks(rest, PIPE_TOOL_CALL_BLOCK, calls)

        // 流式预览:末尾还没收全的半个标记(<｜｜DSML｜、<｜…)也别显示出来
        val lt = rest.lastIndexOf('<')
        if (lt >= 0) {
            val tail = rest.substring(lt + 1)
            if (!tail.contains('>') && PARTIAL_TAIL.matches(tail)) {
                rest = rest.substring(0, lt)
            }
        }
        if (calls.isEmpty() && rest == text) return Scan(text, emptyList())
        // 标记剥掉后留下的空行收拾干净;正文内部的换行不动
        rest = rest.lines().joinToString("\n") { it.trimEnd() }.replace(Regex("\n{3,}"), "\n\n").trim()
        return Scan(rest, calls)
    }

    /** 把一个回复里的正文清洗掉;还原出调用时补上 [Part.ToolCall],结束时标成 ToolUse。 */
    fun repair(reply: ModelReply): ModelReply {
        if (reply.parts.none { it is Part.Text || it is Part.Thinking }) return reply
        val out = mutableListOf<Part>()
        val calls = mutableListOf<Part.ToolCall>()
        var changed = false
        for (p in reply.parts) {
            if (p is Part.Thinking) {
                // 思考内容里漏出来的标记只剥掉、不当成调用:调用只能从正文里认
                val cleaned = stripMarkup(p.text)
                if (cleaned != p.text) changed = true
                if (cleaned.isNotEmpty()) out += Part.Thinking(cleaned)
                continue
            }
            if (p !is Part.Text) {
                out += p
                continue
            }
            val scan = scan(p.text)
            if (scan.text != p.text || scan.calls.isNotEmpty()) changed = true
            if (scan.text.isNotEmpty()) out += Part.Text(scan.text)
            scan.calls.forEach { c -> calls += Part.ToolCall(callId(c, calls.size), c.name, c.argsJson) }
        }
        if (!changed) return reply
        out += calls
        return ModelReply(out, if (calls.isEmpty()) reply.stopReason else StopReason.ToolUse, reply.usage)
    }

    /** 参数块 → 工具参数 JSON。`string="false"` 的按 JSON 值收,其余按字符串。 */
    private fun paramsJson(block: String): String {
        if (PARAM.find(block) == null) return "{}"
        return buildJsonObject {
            for (m in PARAM.findAll(block)) {
                val name = m.groupValues[1].trim()
                if (name.isEmpty()) continue
                val raw = m.groupValues[3]
                val isString = m.groupValues[2] != "false"
                put(name, if (isString) JsonPrimitive(raw) else jsonValue(raw))
            }
        }.toString()
    }

    private fun jsonValue(raw: String) = try {
        json.parseToJsonElement(raw)
    } catch (e: Exception) {
        JsonPrimitive(raw)
    }

    /** 名字+参数做稳定的调用编号(同一条消息里再按序号区分),不引随机数,便于单测。 */
    private fun callId(c: Invocation, index: Int): String =
        "leaked_" + Integer.toHexString((c.name + "\u0000" + c.argsJson).hashCode()) + "_" + index

    private fun extractJsonBlocks(text: String, block: Regex, calls: MutableList<Invocation>): String {
        // 只剥能还原出调用的块;还原不了的原样留着,交给 AgentLoop 的漏调用检查(LeakedToolCall)退回重来,
        // 免得剥掉之后只剩前面一句「我查一下」,被当成最终回答交出去
        return block.replace(text) { m ->
            val found = invocationsOf(m.groupValues[1])
            if (found.isEmpty()) m.value else " ".also { calls += found }
        }
    }

    private fun invocationsOf(body: String): List<Invocation> {
        val element = try {
            json.parseToJsonElement(body)
        } catch (e: Exception) {
            return emptyList()
        }
        val items = when (element) {
            is JsonArray -> element.toList()
            is JsonObject -> listOf(element)
            else -> return emptyList()
        }
        return items.mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            val name = (obj["name"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
            if (name.isEmpty()) return@mapNotNull null
            val args = obj["arguments"] ?: obj["parameters"]
            Invocation(name, when (args) {
                null -> "{}"
                is JsonPrimitive -> (args as JsonPrimitive).contentOrNull ?: "{}"
                else -> args.toString()
            })
        }
    }
}
