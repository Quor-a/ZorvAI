package com.ai.assistance.quro.core.mika.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * OpenAI Chat Completions 兼容端点(DeepSeek/Qwen/Kimi/GLM/OpenRouter/本地服务等)。差异只放在 [capabilities]。
 *
 * 思考内容各家放的地方不同:`reasoning_content`、`reasoning`,或者 `content` 是分块数组、思考在 `thinking` 块里。
 * 收到的思考记下从哪个地址、哪个位置来的([ReasoningVia],存在 [Part.Thinking.opaque]),带工具的请求原样放回原处:
 * 有的服务商要求工具调用前后的思考接着回传,不回传就拒绝。只回传从这个地址收到的,别家给的思考不发
 * (不认这个字段的服务商不会收到它)。[reasoningField] 是模型列表里写明的思考字段(`reasoning_content` 或
 * `reasoning`):写明了的,来历不明的思考(旧版本存下的历史)也按它回传。
 */
class OpenAiCompatModel(
    baseUrl: String,
    private val apiKey: String?,
    private val model: String,
    override val capabilities: Capabilities,
    private val http: OkHttpClient,
    reasoningField: String? = null,
    /** 每个请求都带的头(客户端标识、服务商要的会话号等,见 [ClientHeaders]);Key 的头由适配器自己加。 */
    private val headers: Map<String, String> = emptyMap(),
) : StreamingChatModel {
    override val name: String get() = model
    private val endpoint = baseUrl.trimEnd('/') + "/chat/completions"
    private val declaredVia = ReasoningVia.ofField(reasoningField)
    private val json = Json { ignoreUnknownKeys = true }

    private companion object {
        val MARKABLE_ROLES = setOf("system", "user", "tool")
        val EPHEMERAL = buildJsonObject { put("type", "ephemeral") }
        /** SSE 的最后一件:收到它才算说完。 */
        const val STREAM_DONE = "[DONE]"
    }

    override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply {
        val (status, retryAfter, text) = HttpSupport.execute(http, request(system, messages, tools, stream = false))
        if (status !in 200..299) throw mapError(status, retryAfter, text)
        // 有的兼容端点不守 OpenAI 的默认值:没让流式也照样回 SSE(本地、局域网上的自建服务常见)。按流读完再拼,别把 `data:` 当成坏 JSON。
        if (!looksLikeEvents(text)) return parse(text)
        val stream = OpenAiStream({}, ::parseUsage, ::thinking)
        eventsOf(text).forEach(stream::accept)
        return stream.finish()
    }

    override suspend fun completeStreaming(
        system: String, messages: List<Message>, tools: List<ToolSpec>, onText: (String) -> Unit,
    ): ModelReply {
        var stream = OpenAiStream(onText, ::parseUsage, ::thinking)
        var response = HttpSupport.stream(http, request(system, messages, tools, stream = true), stream::accept)
        if (response.status == 400 && response.body.contains("stream_options")) {
            // 一些兼容端点能流式生成，却没有用量选项。
            stream = OpenAiStream(onText, ::parseUsage, ::thinking)
            response = HttpSupport.stream(http, request(system, messages, tools, stream = true, includeUsage = false), stream::accept)
        }
        val (status, retryAfter, text) = response
        if (status !in 200..299) {
            // 不支持流式传输的兼容端点仍可按原接口完成任务。
            if (status == 400 && text.contains("stream", ignoreCase = true)) return complete(system, messages, tools).also {
                it.parts.filterIsInstance<Part.Text>().joinToString("") { part -> part.text }.takeIf(String::isNotEmpty)?.let(onText)
            }
            throw mapError(status, retryAfter, text)
        }
        if (text.isNotEmpty()) return parse(text).also { reply ->
            reply.parts.filterIsInstance<Part.Text>().joinToString("") { it.text }.takeIf(String::isNotEmpty)?.let(onText)
        }
        return stream.finish()
    }

    private fun request(system: String, messages: List<Message>, tools: List<ToolSpec>, stream: Boolean, includeUsage: Boolean = true): Request {
        val payload = buildJsonObject {
            put("model", model)
            // 思考只在带工具的请求里回传(工具调用一步接一步时要接着想);不带工具的旁路调用不需要
            val replayReasoning = tools.isNotEmpty()
            val wire = listOf(buildJsonObject { put("role", "system"); put("content", system) }) +
                messages.flatMap { serialize(it, replayReasoning) }
            put("messages", JsonArray(if (capabilities.promptCaching == PromptCaching.Explicit) withCacheMarks(wire) else wire))
            if (tools.isNotEmpty()) putJsonArray("tools") { tools.forEach { add(toolJson(it)) } }
            if (stream) {
                put("stream", true)
                if (includeUsage) putJsonObject("stream_options") { put("include_usage", true) }
            } else {
                // 写明不要流式:省掉这一项时有的兼容端点默认按 SSE 回
                put("stream", false)
            }
        }
        return Request.Builder().url(endpoint)
            .withHeaders(headers)
            .header("Accept", if (stream) "text/event-stream" else "application/json")
            .apply { if (!apiKey.isNullOrEmpty()) header("Authorization", "Bearer $apiKey") }
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()
    }

    /**
     * 显式缓存:标在系统提示词上(每次都一样),再标在最后一条能标的消息上(缓存到这次为止的整段对话,
     * 下一次请求的开头与它相同时就从缓存取)。服务端从标记往回找相同的开头,所以较早的消息不能被改写。
     * 内容是字符串的换成一段文字块再标;助手消息(可能只有工具调用)不标。
     */
    private fun withCacheMarks(wire: List<JsonObject>): List<JsonObject> {
        val last = wire.indexOfLast { (it["role"] as? JsonPrimitive)?.contentOrNull in MARKABLE_ROLES && it["content"] !is JsonNull }
        return wire.mapIndexed { i, m -> if (i == 0 || i == last) marked(m) else m }
    }

    private fun marked(m: JsonObject): JsonObject {
        val blocks = when (val c = m["content"]) {
            is JsonPrimitive -> listOf(buildJsonObject { put("type", "text"); put("text", c.content) })
            is JsonArray -> c.map { it.jsonObject }
            else -> return m
        }
        if (blocks.isEmpty()) return m
        val withMark = blocks.dropLast(1) + JsonObject(blocks.last() + ("cache_control" to EPHEMERAL))
        return JsonObject(m + ("content" to JsonArray(withMark)))
    }

    private fun mapError(status: Int, retryAfter: String?, body: String): ModelError = ModelAccessErrors.of(status, body, model) ?: when {
        status == 401 -> ModelError.Auth()
        status == 402 || body.contains("insufficient_quota") -> ModelError.QuotaExceeded()
        status == 429 -> ModelError.RateLimited(HttpSupport.retryAfterMs(retryAfter))
        status == 400 && (body.contains("context_length") || body.contains("maximum context")) -> ModelError.ContextTooLong()
        status == 404 -> ModelError.ModelNotFound(model)
        status in 500..599 -> ModelError.Server(status)
        status == 403 -> ModelError.Auth()
        else -> ModelError.BadResponse("HTTP $status ${body.take(200)}")
    }

    private fun toolJson(t: ToolSpec): JsonObject = buildJsonObject {
        put("type", "function")
        putJsonObject("function") {
            put("name", t.name)
            put("description", t.description)
            put("parameters", try { json.parseToJsonElement(t.parametersJsonSchema) } catch (e: Exception) {
                throw IllegalArgumentException("工具 ${t.name} 的参数 schema 不是合法 JSON", e)
            })
        }
    }

    /** 一条内部消息可能展开成多条线上消息(tool 结果各占一条,图片只能放 user 消息)。 */
    private fun serialize(m: Message, replayReasoning: Boolean): List<JsonObject> {
        if (m.role == Role.Assistant) return listOf(assistantJson(m, replayReasoning))
        val out = mutableListOf<JsonObject>()
        m.parts.filterIsInstance<Part.ToolResult>().forEach { r ->
            out += buildJsonObject {
                put("role", "tool"); put("tool_call_id", r.callId); put("content", r.content)
            }
        }
        val rest = m.parts.filter { it is Part.Text || it is Part.Image }
        if (rest.isNotEmpty()) {
            val role = if (m.role == Role.System) "system" else "user"
            val hasImage = rest.any { it is Part.Image } && capabilities.vision
            out += buildJsonObject {
                put("role", role)
                if (!hasImage) {
                    put("content", rest.joinToString("\n") { textOf(it) })
                } else {
                    putJsonArray("content") {
                        rest.forEach { p ->
                            add(buildJsonObject {
                                if (p is Part.Image) {
                                    put("type", "image_url")
                                    putJsonObject("image_url") { put("url", "data:${p.mime};base64,${p.pngBase64}") }
                                } else {
                                    put("type", "text"); put("text", textOf(p))
                                }
                            })
                        }
                    }
                }
            }
        }
        return out
    }

    private fun textOf(p: Part): String = when (p) {
        is Part.Text -> p.text
        is Part.Image -> "[screenshot omitted: the current model cannot see images]"
        else -> ""
    }

    /** 收到的一段思考:记下这个地址和收到它的位置,回放时认得。 */
    private fun thinking(text: String, via: ReasoningVia) = Part.Thinking(text, via.tag(endpoint))

    /** 这条助手消息里能回传给这个地址的思考和放回哪里:从这里收到的按收到的位置;来历不明的按列表写明的字段;都没有为 null。 */
    private fun replayable(m: Message): Pair<String, ReasoningVia>? {
        val parts = m.parts.filterIsInstance<Part.Thinking>()
        val own = parts.mapNotNull { p -> ReasoningVia.of(p.opaque, endpoint)?.let { p.text to it } }
        if (own.isNotEmpty()) return own.joinToString("") { it.first } to own.first().second
        val declared = declaredVia ?: return null
        val untagged = parts.filter { it.opaque == null }.joinToString("") { it.text }
        return if (untagged.isEmpty()) null else untagged to declared
    }

    private fun assistantJson(m: Message, replayReasoning: Boolean): JsonObject {
        val text = m.parts.filterIsInstance<Part.Text>().joinToString("\n") { it.text }
        val calls = m.parts.filterIsInstance<Part.ToolCall>()
        val replay = if (replayReasoning) replayable(m) else null
        return buildJsonObject {
            put("role", "assistant")
            when {
                // 分块的那家要整条原样放回:思考块在前,文字块在后
                replay?.second == ReasoningVia.ThinkingChunks -> putJsonArray("content") {
                    addJsonObject {
                        put("type", "thinking")
                        putJsonArray("thinking") { addJsonObject { put("type", "text"); put("text", replay.first) } }
                    }
                    if (text.isNotEmpty()) addJsonObject { put("type", "text"); put("text", text) }
                }
                text.isEmpty() && calls.isNotEmpty() -> put("content", JsonNull)
                else -> put("content", text)
            }
            replay?.second?.field?.let { put(it, replay.first) }
            if (calls.isNotEmpty()) putJsonArray("tool_calls") {
                calls.forEach { c ->
                    add(buildJsonObject {
                        put("id", c.id); put("type", "function")
                        putJsonObject("function") { put("name", c.name); put("arguments", c.argsJson) }
                    })
                }
            }
        }
    }

    /** 整份回复是 SSE(`data: …` 一件件)还是一份 JSON。 */
    private fun looksLikeEvents(body: String): Boolean {
        val head = body.trimStart()
        return head.startsWith("data:") || head.startsWith("event:")
    }

    /** 把整份 SSE 回复拆成一件件事件;内容已经收完,缺结束标记时补一个,当作正常说完。 */
    private fun eventsOf(body: String): List<String> {
        val out = mutableListOf<String>()
        val data = mutableListOf<String>()
        fun dispatch() {
            if (data.isNotEmpty()) {
                out += data.joinToString("\n")
                data.clear()
            }
        }
        body.lineSequence().forEach { line ->
            when {
                line.isEmpty() -> dispatch()
                line.startsWith("data:") -> data += line.removePrefix("data:").removePrefix(" ")
            }
        }
        dispatch()
        if (out.lastOrNull() != STREAM_DONE) out += STREAM_DONE
        return out
    }

    private fun parse(text: String): ModelReply {
        try {
            val root = json.parseToJsonElement(text).jsonObject
            val choice = root["choices"]?.jsonArray?.firstOrNull()?.jsonObject
                ?: throw ModelError.BadResponse("缺少 choices")
            val msg = choice["message"]?.jsonObject ?: throw ModelError.BadResponse("缺少 message")
            val parts = mutableListOf<Part>()
            ReasoningVia.fields.firstNotNullOfOrNull { via ->
                (msg[via.field!!] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }?.let { it to via }
            }?.let { (t, via) -> parts += thinking(t, via) }
            when (val content = msg["content"]) {
                is JsonArray -> {
                    val chunks = ContentChunks.of(content)
                    if (chunks.thinking.isNotEmpty() && parts.isEmpty()) parts += thinking(chunks.thinking, ReasoningVia.ThinkingChunks)
                    if (chunks.text.isNotEmpty()) parts += Part.Text(chunks.text)
                }
                is JsonPrimitive -> content.contentOrNull?.takeIf { it.isNotEmpty() }?.let { parts += Part.Text(it) }
                else -> {}
            }
            (msg["tool_calls"] as? JsonArray)?.forEach { el ->
                val c = el.jsonObject
                val fn = c["function"]!!.jsonObject
                parts += Part.ToolCall(
                    c["id"]!!.jsonPrimitive.content,
                    fn["name"]!!.jsonPrimitive.content,
                    // 有的适配器对没有参数的工具给空串:当成 `{}`,否则调用方按 JSON 解析会失败
                    fn["arguments"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: "{}",
                )
            }
            val stop = when ((choice["finish_reason"] as? JsonPrimitive)?.contentOrNull) {
                "stop" -> StopReason.EndTurn
                "tool_calls", "function_call" -> StopReason.ToolUse
                "length" -> StopReason.MaxTokens
                else -> if (parts.any { it is Part.ToolCall }) StopReason.ToolUse else StopReason.Other
            }
            // 有的模型会把工具调用标记直接吐进正文:先还原成调用,还原不了就剥掉,别把乱码交给界面(见 LeakedToolMarkup)
            return LeakedToolMarkup.repair(ModelReply(parts, stop, parseUsage(root["usage"])))
        } catch (e: ModelError) {
            throw e
        } catch (e: Exception) {
            throw ModelError.BadResponse(e.message.orEmpty().take(200), e)
        }
    }

    private fun parseUsage(el: JsonElement?): Usage? {
        val u = el as? JsonObject ?: return null
        fun int(o: JsonObject?, k: String) = (o?.get(k) as? JsonPrimitive)?.intOrNull
        // 兼容接口有两种命中字段名;按字段是否存在取值
        val hit = int(u, "prompt_cache_hit_tokens")
        val miss = int(u, "prompt_cache_miss_tokens")
        val inT = int(u, "prompt_tokens") ?: if (hit != null && miss != null) hit + miss else return null
        val outT = int(u, "completion_tokens") ?: 0
        val cached = maxOf(int(u["prompt_tokens_details"] as? JsonObject, "cached_tokens") ?: 0, hit ?: 0).coerceAtMost(inT)
        return Usage(inT, outT, cached)
    }
}

/** Chat Completions 的 delta 可以把同一个工具的名称与参数拆成多帧。 */
private class OpenAiStream(
    private val onText: (String) -> Unit,
    private val usageOf: (JsonElement?) -> Usage?,
    private val thinkingOf: (String, ReasoningVia) -> Part.Thinking,
) {
    private data class Call(val id: StringBuilder = StringBuilder(), val name: StringBuilder = StringBuilder(),
        val args: StringBuilder = StringBuilder())

    private val json = Json { ignoreUnknownKeys = true }
    private val visible = StringBuilder()
    private val thinking = StringBuilder()
    private var thinkingVia: ReasoningVia? = null
    private val calls = sortedMapOf<Int, Call>()
    private var usage: Usage? = null
    private var reason: String? = null
    private var done = false
    private var chunks = 0

    fun accept(data: String) {
        if (data == "[DONE]") { done = true; return }
        val root = try { json.parseToJsonElement(data).jsonObject }
        catch (e: Exception) { throw ModelError.BadResponse("流式响应不是合法 JSON", e) }
        if (root["error"] != null) throw ModelError.BadResponse(root["error"].toString().take(200))
        chunks++
        usageOf(root["usage"])?.let { usage = it }
        val choice = (root["choices"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return
        (choice["finish_reason"] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotEmpty)?.let { reason = it }
        val delta = choice["delta"] as? JsonObject ?: return
        ReasoningVia.fields.forEach { via ->
            (delta[via.field!!] as? JsonPrimitive)?.contentOrNull?.let { appendThinking(it, via) }
        }
        when (val content = delta["content"]) {
            is JsonArray -> {
                val chunks = ContentChunks.of(content)
                if (chunks.thinking.isNotEmpty()) appendThinking(chunks.thinking, ReasoningVia.ThinkingChunks)
                appendVisible(chunks.text)
            }
            is JsonPrimitive -> content.contentOrNull?.let(::appendVisible)
            else -> {}
        }
        (delta["tool_calls"] as? JsonArray)?.forEach { item ->
            val obj = item as? JsonObject ?: return@forEach
            val index = (obj["index"] as? JsonPrimitive)?.intOrNull ?: return@forEach
            val call = calls.getOrPut(index, ::Call)
            (obj["id"] as? JsonPrimitive)?.contentOrNull?.let { call.id.append(it) }
            val fn = obj["function"] as? JsonObject
            (fn?.get("name") as? JsonPrimitive)?.contentOrNull?.let { call.name.append(it) }
            (fn?.get("arguments") as? JsonPrimitive)?.contentOrNull?.let { call.args.append(it) }
        }
    }

    /** 一次回复里思考只按最先收到的位置记(同一家不会换位置;两个字段都给时只记一份)。 */
    private fun appendThinking(t: String, via: ReasoningVia) {
        if (t.isEmpty()) return
        if (thinkingVia == null) thinkingVia = via
        if (via == thinkingVia) thinking.append(t)
    }

    private fun appendVisible(t: String) {
        if (t.isEmpty()) return
        visible.append(t)
        // 预览也先剥一遍:模型把工具调用标记吐进正文时,别让用户看着乱码流过去
        onText(LeakedToolMarkup.stripMarkup(visible.toString()))
    }

    fun finish(): ModelReply {
        // 收到 finish_reason 就算回复完整:有的兼容端点不发 [DONE]。两样都没有是连接中途被断开
        if (!done && reason == null) throw ModelError.StreamCut()
        if (chunks == 0) throw ModelError.BadResponse("流式响应没有内容")
        val parts = buildList<Part> {
            thinkingVia?.takeIf { thinking.isNotEmpty() }?.let { add(thinkingOf(thinking.toString(), it)) }
            if (visible.isNotEmpty()) add(Part.Text(visible.toString()))
            calls.values.forEach { c ->
                if (c.name.isEmpty() || c.id.isEmpty()) throw ModelError.BadResponse("工具调用流缺少名称或编号")
                add(Part.ToolCall(c.id.toString(), c.name.toString(), c.args.toString().ifEmpty { "{}" }))
            }
        }
        val stop = when (reason) {
            "stop" -> StopReason.EndTurn
            "tool_calls", "function_call" -> StopReason.ToolUse
            "length" -> StopReason.MaxTokens
            else -> if (calls.isNotEmpty()) StopReason.ToolUse else StopReason.Other
        }
        return LeakedToolMarkup.repair(ModelReply(parts, stop, usage))
    }
}

/**
 * 兼容接口把思考放在哪:[field] 是助手消息上的字段名;[ThinkingChunks] 是 `content` 为分块数组、思考在 `thinking` 块里。
 * 记在 [Part.Thinking.opaque] 里的是 `{"endpoint":…,"via":…}`。
 */
internal enum class ReasoningVia(val wire: String, val field: String?) {
    ReasoningContent("reasoning_content", "reasoning_content"),
    Reasoning("reasoning", "reasoning"),
    ThinkingChunks("thinking_chunks", null);

    fun tag(endpoint: String): String = buildJsonObject { put("endpoint", endpoint); put("via", wire) }.toString()

    companion object {
        /** 放在消息字段上的几种,按这个次序认(两个都给时取 `reasoning_content`)。 */
        val fields = listOf(ReasoningContent, Reasoning)

        fun ofField(name: String?): ReasoningVia? = fields.firstOrNull { it.field == name }

        /** [opaque] 是从 [endpoint] 收到的思考时给出它的位置;别处来的、认不出的为 null。 */
        fun of(opaque: String?, endpoint: String): ReasoningVia? {
            val o = opaque?.let { runCatching { Json.parseToJsonElement(it) as? JsonObject }.getOrNull() } ?: return null
            if ((o["endpoint"] as? JsonPrimitive)?.contentOrNull != endpoint) return null
            val via = (o["via"] as? JsonPrimitive)?.contentOrNull
            return entries.firstOrNull { it.wire == via }
        }
    }
}

/** `content` 是分块数组时:`text` 块拼成回答,`thinking` 块(里面又是 `text` 块)拼成思考。别的块不认。 */
private class ContentChunks(val text: String, val thinking: String) {
    companion object {
        fun of(content: JsonArray): ContentChunks {
            val text = StringBuilder()
            val thinking = StringBuilder()
            content.forEach { el ->
                val c = el as? JsonObject ?: return@forEach
                when ((c["type"] as? JsonPrimitive)?.contentOrNull) {
                    "text" -> (c["text"] as? JsonPrimitive)?.contentOrNull?.let(text::append)
                    "thinking" -> when (val t = c["thinking"]) {
                        is JsonArray -> t.forEach { inner ->
                            ((inner as? JsonObject)?.get("text") as? JsonPrimitive)?.contentOrNull?.let(thinking::append)
                        }
                        is JsonPrimitive -> t.contentOrNull?.let(thinking::append)
                        else -> {}
                    }
                }
            }
            return ContentChunks(text.toString(), thinking.toString())
        }
    }
}
