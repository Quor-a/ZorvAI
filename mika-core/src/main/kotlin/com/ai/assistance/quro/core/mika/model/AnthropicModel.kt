package com.ai.assistance.quro.core.mika.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap

/**
 * Anthropic Messages API。
 * - 历史里的 System 消息并入 system 文本(Anthropic 没有 system 角色消息)。
 * - 选了思考强度时开自适应思考(`thinking.type = adaptive`)并把档位放进 `output_config.effort`;选 `none` 时关掉。
 *   列表写明了模型接受哪几种思考方式([thinkingTypes])的,不接受的那种不发(什么都不发,按服务端默认)。
 *   思考块的 `signature` 连同收到它的地址存在 [Part.Thinking.opaque] 里([SignedThinking]),
 *   带工具调用的回合原样回传给同一个地址(不回传会被服务端拒绝),块的顺序照收到的不变。
 *   签名还和产生它时的系统提示词、工具、前面的消息绑定;这些变了(展开一组工具、压缩历史等)以后,Claude 5.1 起的模型可能
 *   拒绝回传的思考块([STALE_THINKING]),这时按文档的恢复办法去掉思考块再发一次(ZorvAI 在这些模型上开思考只会是自适应思考,文档说
 *   自适应思考不要求助手回合以思考块开头;这里把这次带的都去掉,见 [dropStaleThinking]),之后的请求也不再带它们
 *   ([staleThinking])。
 * - 相邻同角色消息合并(Anthropic 要求 user/assistant 交替)。
 */
class AnthropicModel(
    baseUrl: String = "https://api.anthropic.com",
    private val apiKey: String,
    private val model: String,
    override val capabilities: Capabilities,
    private val http: OkHttpClient,
    /** 回复上限(`max_tokens`,必填);null 按默认:开了思考的 [THINKING_MAX_TOKENS],否则 [DEFAULT_MAX_TOKENS]。 */
    maxTokens: Int? = null,
    /** 每个请求都带的头(见 [ClientHeaders]);Key 的头由适配器自己加。 */
    private val headers: Map<String, String> = emptyMap(),
    /** 用户选的思考强度;null 不发,`none` 关掉思考,其余作为 `output_config.effort` 发出。 */
    private val reasoningEffort: String? = null,
    /**
     * 列表写明这个模型接受的思考方式(见 [ListedModel.thinkingTypes]);不含 `adaptive` 的不发档位,不含 `disabled` 的不发 `none`。
     * null 是列表没说(档位来自别的列表写法),照档位发。
     */
    private val thinkingTypes: List<String>? = null,
) : StreamingChatModel {
    override val name: String get() = model
    private val endpoint = baseUrl.trimEnd('/') + "/v1/messages"
    /** 实际发出的 `thinking.type`:`adaptive`、`disabled`,或 null(不发)。 */
    private val thinkingType: String? = when (reasoningEffort) {
        null -> null
        "none" -> "disabled"
        else -> "adaptive"
    }?.takeIf { thinkingTypes == null || it in thinkingTypes }
    private val maxTokens = maxTokens ?: if (thinkingType == "adaptive") THINKING_MAX_TOKENS else DEFAULT_MAX_TOKENS
    private val json = Json { ignoreUnknownKeys = true }
    private val ephemeral = buildJsonObject { put("type", "ephemeral") }
    /**
     * 被拒的那次请求带的思考块([Part.Thinking.opaque]);历史里还留着,之后的请求都不带。
     * 每个任务建一个模型,子任务和它共用,所以可能有几个协程同时用。
     */
    private val staleThinking: MutableSet<String> = ConcurrentHashMap.newKeySet()

    override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply {
        val (status, retryAfter, text) = dropStaleThinking(messages) {
            HttpSupport.execute(http, request(system, messages, tools, stream = false))
        }
        if (status !in 200..299) throw mapError(status, retryAfter, text)
        return parse(text)
    }

    override suspend fun completeStreaming(
        system: String, messages: List<Message>, tools: List<ToolSpec>, onText: (String) -> Unit,
    ): ModelReply {
        val stream = AnthropicStream(endpoint, onText, ::parseUsage)
        // 被拒的那次没有事件(不是 2xx 时 HttpSupport.stream 不调 accept),重发接着用这个 stream
        val (status, retryAfter, text) = dropStaleThinking(messages) {
            HttpSupport.stream(http, request(system, messages, tools, stream = true), stream::accept)
        }
        if (status !in 200..299) throw mapError(status, retryAfter, text)
        if (text.isNotEmpty()) return parse(text).also { reply ->
            reply.parts.filterIsInstance<Part.Text>().joinToString("") { it.text }.takeIf(String::isNotEmpty)?.let(onText)
        }
        return stream.finish()
    }

    /**
     * 发一次;服务端说回传的思考块前缀对不上([STALE_THINKING])时,把这次带的思考块都记进 [staleThinking] 再发一次(只一次)。
     * 文档给的做法是去掉报错点名的那块和它之后的;这里全去掉,不用按 `messages.N` 找位置,代价是更早的有效思考也不带了。
     * 这次没带思考块的不重发,照常报错。
     */
    private suspend fun dropStaleThinking(messages: List<Message>, send: suspend () -> RawResponse): RawResponse {
        val sent = messages.filter { it.role == Role.Assistant }.flatMap { it.parts }.filterIsInstance<Part.Thinking>()
            .filter(::replays).mapNotNull { it.opaque }
        val first = send()
        if (first.status != 400 || STALE_THINKING !in first.body || !staleThinking.addAll(sent)) return first
        return send()
    }

    /** 这一块回不回传:关掉思考时不带;别的地址收到的不带(签名只有发它的服务认);被拒的那次请求带过的不带([staleThinking])。 */
    private fun replays(p: Part.Thinking): Boolean {
        val opaque = p.opaque ?: return false
        return thinkingType != "disabled" && opaque !in staleThinking && SignedThinking.block(p, endpoint) != null
    }

    private suspend fun request(system: String, messages: List<Message>, tools: List<ToolSpec>, stream: Boolean): Request {
        val systemText = (listOf(system) + messages.filter { it.role == Role.System }
            .map { m -> m.parts.filterIsInstance<Part.Text>().joinToString("\n") { it.text } })
            .filter { it.isNotEmpty() }.joinToString("\n\n")
        val payload = buildJsonObject {
            put("model", model)
            put("max_tokens", maxTokens)
            if (stream) put("stream", true)
            // Anthropic 拒绝空文本块,system 为空时整个字段省略
            if (systemText.isNotBlank()) put("system", buildJsonArray {
                add(buildJsonObject {
                    put("type", "text"); put("text", systemText); put("cache_control", ephemeral)
                })
            })
            if (tools.isNotEmpty()) put("tools", buildJsonArray {
                tools.forEachIndexed { i, t -> add(toolJson(t, last = i == tools.lastIndex)) }
            })
            put("messages", buildMessages(messages))
            when (thinkingType) {
                "disabled" -> putJsonObject("thinking") { put("type", "disabled") }
                "adaptive" -> {
                    putJsonObject("thinking") {
                        put("type", "adaptive")
                        put("display", "summarized")
                    }
                    putJsonObject("output_config") { put("effort", reasoningEffort) }
                }
            }
        }
        return Request.Builder().url(endpoint)
            .withHeaders(headers)
            .header("x-api-key", apiKey)
            .header("anthropic-version", "2023-06-01")
            .postInference(InferenceProtocol.Anthropic, payload)
            .build()
    }

    private fun mapError(status: Int, retryAfter: String?, body: String): ModelError = ModelAccessErrors.of(status, body, model) ?: when {
        status == 401 || status == 403 -> ModelError.Auth()
        status == 402 || body.contains("credit balance is too low") -> ModelError.QuotaExceeded()
        status == 429 -> ModelError.RateLimited(HttpSupport.retryAfterMs(retryAfter))
        status == 413 || body.contains("prompt is too long") -> ModelError.ContextTooLong()
        status == 404 -> ModelError.ModelNotFound(model, ModelAccessErrors.jsonDetail(body))
        status == 529 || body.contains("overloaded_error") -> ModelError.Server(529)
        status in 500..599 -> ModelError.Server(status)
        else -> ModelError.BadResponse("HTTP $status ${body.take(200)}")
    }

    private fun toolJson(t: ToolSpec, last: Boolean): JsonObject = buildJsonObject {
        put("name", t.name)
        put("description", t.description)
        put("input_schema", try { json.parseToJsonElement(t.parametersJsonSchema) } catch (e: Exception) {
            throw IllegalArgumentException("工具 ${t.name} 的参数 schema 不是合法 JSON", e)
        })
        if (last) put("cache_control", ephemeral)
    }

    private fun buildMessages(messages: List<Message>): JsonArray {
        // (role, blocks) 先按相邻同角色合并,再输出
        val merged = mutableListOf<Pair<String, MutableList<JsonObject>>>()
        for (m in messages) {
            if (m.role == Role.System) continue
            val role = if (m.role == Role.Assistant) "assistant" else "user"
            val blocks = if (role == "assistant") assistantBlocks(m) else userBlocks(m)
            if (blocks.isEmpty()) continue
            if (merged.lastOrNull()?.first == role) merged.last().second += blocks else merged += role to blocks.toMutableList()
        }
        // 对话缓存到最后一条 user 消息为止,下一步的开头与它相同就从缓存取;助手消息可能以思考块结尾,不标
        val lastUser = merged.indexOfLast { it.first == "user" }
        return buildJsonArray {
            merged.forEachIndexed { i, (role, blocks) ->
                val content = if (i == lastUser) blocks.dropLast(1) + JsonObject(blocks.last() + ("cache_control" to ephemeral)) else blocks
                add(buildJsonObject { put("role", role); put("content", JsonArray(content)) })
            }
        }
    }

    // tool_result 必须排在同一 user 消息的其它块之前
    private fun userBlocks(m: Message): List<JsonObject> {
        val results = m.parts.filterIsInstance<Part.ToolResult>().map { r ->
            buildJsonObject {
                put("type", "tool_result"); put("tool_use_id", r.callId)
                put("content", r.content); put("is_error", r.isError)
            }
        }
        val rest = m.parts.mapNotNull { p ->
            when {
                p is Part.Text -> buildJsonObject { put("type", "text"); put("text", p.text) }
                p is Part.Image && capabilities.vision -> buildJsonObject {
                    put("type", "image")
                    putJsonObject("source") {
                        put("type", "base64"); put("media_type", p.mime); put("data", p.pngBase64)
                    }
                }
                p is Part.Image -> buildJsonObject {
                    put("type", "text"); put("text", "[screenshot omitted: the current model cannot see images]")
                }
                else -> null
            }
        }
        return results + rest
    }

    // 开思考时,带工具调用的回合必须把上一条助手消息的思考块连同签名原样带回,块的顺序照收到的:
    // 新模型在工具调用前写的进度说明是单独的思考块,排在文字后面;挪到前面,服务端会当作前面的内容改过了。
    // 明确关掉思考时不带,免得和当前请求的思考配置冲突;别的地址收到的、被拒的那次请求带过的也不带([replays])。
    private fun assistantBlocks(m: Message): List<JsonObject> = m.parts.mapNotNull { p ->
        when (p) {
            is Part.Thinking -> if (replays(p)) SignedThinking.block(p, endpoint) else null
            is Part.Text -> if (p.text.isEmpty()) null else buildJsonObject { put("type", "text"); put("text", p.text) }
            is Part.ToolCall -> buildJsonObject {
                put("type", "tool_use"); put("id", p.id); put("name", p.name)
                put("input", try { json.parseToJsonElement(p.argsJson).jsonObject } catch (e: Exception) { JsonObject(emptyMap()) })
            }
            else -> null
        }
    }

    private fun parse(text: String): ModelReply {
        try {
            val root = json.parseToJsonElement(text).jsonObject
            val content = root["content"]?.jsonArray ?: throw ModelError.BadResponse("缺少 content")
            val parts = mutableListOf<Part>()
            content.forEach { el ->
                val b = el.jsonObject
                when (b["type"]?.jsonPrimitive?.contentOrNull) {
                    "text" -> b["text"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() }?.let { parts += Part.Text(it) }
                    "thinking" -> {
                        val text = (b["thinking"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                        val signature = (b["signature"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }
                        if (text.isNotEmpty() || signature != null) parts += Part.Thinking(text, signature?.let { SignedThinking.signed(endpoint, it) })
                    }
                    "redacted_thinking" -> (b["data"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }
                        ?.let { parts += Part.Thinking("", SignedThinking.redacted(endpoint, it)) }
                    "tool_use" -> parts += Part.ToolCall(
                        b["id"]!!.jsonPrimitive.content,
                        b["name"]!!.jsonPrimitive.content,
                        (b["input"] ?: JsonObject(emptyMap())).toString(),
                    )
                }
            }
            val stop = when ((root["stop_reason"] as? JsonPrimitive)?.contentOrNull) {
                "end_turn", "stop_sequence" -> StopReason.EndTurn
                "tool_use" -> StopReason.ToolUse
                "max_tokens" -> StopReason.MaxTokens
                else -> StopReason.Other
            }
            return ModelReply(parts, stop, parseUsage(root["usage"]))
        } catch (e: ModelError) {
            throw e
        } catch (e: Exception) {
            throw ModelError.BadResponse(e.message.orEmpty().take(200), e)
        }
    }

    private fun parseUsage(el: JsonElement?): Usage? {
        val u = el as? JsonObject ?: return null
        fun n(k: String) = (u[k] as? JsonPrimitive)?.intOrNull
        val inT = n("input_tokens") ?: return null
        val cached = n("cache_read_input_tokens") ?: 0
        // Anthropic 的 input_tokens 不含缓存部分;统一成总输入口径。output_tokens 含思考但不单独报,reasoningTokens 记 0
        return Usage(inT + cached + (n("cache_creation_input_tokens") ?: 0), n("output_tokens") ?: 0, cached)
    }
}

private class AnthropicStream(
    private val endpoint: String,
    private val onText: (String) -> Unit,
    private val usageOf: (JsonElement?) -> Usage?,
) {
    private data class Block(
        val type: String, val id: String = "", val name: String = "",
        val text: StringBuilder = StringBuilder(), val input: StringBuilder = StringBuilder(), val initialInput: String = "{}",
        val signature: StringBuilder = StringBuilder(), val data: String = "",
    )

    private val json = Json { ignoreUnknownKeys = true }
    private val blocks = sortedMapOf<Int, Block>()
    private val visible = StringBuilder()
    private var usage: Usage? = null
    private var reason: String? = null
    private var stopped = false

    fun accept(data: String) {
        val root = try { json.parseToJsonElement(data).jsonObject }
        catch (e: Exception) { throw ModelError.BadResponse("流式响应不是合法 JSON", e) }
        when ((root["type"] as? JsonPrimitive)?.contentOrNull) {
            "message_start" -> usage = usageOf((root["message"] as? JsonObject)?.get("usage"))
            "content_block_start" -> {
                val index = (root["index"] as? JsonPrimitive)?.intOrNull ?: return
                val b = root["content_block"] as? JsonObject ?: return
                val type = (b["type"] as? JsonPrimitive)?.contentOrNull ?: return
                blocks[index] = Block(type,
                    id = (b["id"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                    name = (b["name"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                    initialInput = b["input"]?.toString() ?: "{}",
                    data = (b["data"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                ).also { block ->
                    (b["signature"] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotEmpty)?.let(block.signature::append)
                    (b["text"] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotEmpty)?.let {
                        block.text.append(it); visible.append(it); onText(visible.toString())
                    }
                }
            }
            "content_block_delta" -> {
                val index = (root["index"] as? JsonPrimitive)?.intOrNull ?: return
                val block = blocks[index] ?: return
                val delta = root["delta"] as? JsonObject ?: return
                when ((delta["type"] as? JsonPrimitive)?.contentOrNull) {
                    "text_delta" -> (delta["text"] as? JsonPrimitive)?.contentOrNull?.let {
                        block.text.append(it); visible.append(it); onText(visible.toString())
                    }
                    "thinking_delta" -> (delta["thinking"] as? JsonPrimitive)?.contentOrNull?.let { block.text.append(it) }
                    "signature_delta" -> (delta["signature"] as? JsonPrimitive)?.contentOrNull?.let { block.signature.append(it) }
                    "input_json_delta" -> (delta["partial_json"] as? JsonPrimitive)?.contentOrNull?.let { block.input.append(it) }
                }
            }
            "message_delta" -> {
                reason = ((root["delta"] as? JsonObject)?.get("stop_reason") as? JsonPrimitive)?.contentOrNull ?: reason
                val output = ((root["usage"] as? JsonObject)?.get("output_tokens") as? JsonPrimitive)?.intOrNull
                if (output != null) usage = usage?.copy(outputTokens = output)
            }
            "message_stop" -> stopped = true
            "error" -> throw ModelError.BadResponse(root["error"].toString().take(200))
        }
    }

    fun finish(): ModelReply {
        // message_delta 带来 stop_reason 时内容块都已收齐,只差 message_stop 也算完整;两样都没有是连接中途被断开
        if (!stopped && reason == null) throw ModelError.StreamCut()
        val parts = blocks.values.mapNotNull { block ->
            when (block.type) {
                "text" -> block.text.toString().takeIf(String::isNotEmpty)?.let(Part::Text)
                "thinking" -> {
                    val text = block.text.toString()
                    val signature = block.signature.toString().takeIf(String::isNotEmpty)
                    if (text.isNotEmpty() || signature != null) Part.Thinking(text, signature?.let { SignedThinking.signed(endpoint, it) }) else null
                }
                "redacted_thinking" -> block.data.takeIf(String::isNotEmpty)?.let { Part.Thinking("", SignedThinking.redacted(endpoint, it)) }
                "tool_use" -> {
                    if (block.id.isEmpty() || block.name.isEmpty()) throw ModelError.BadResponse("工具调用流缺少名称或编号")
                    Part.ToolCall(block.id, block.name, block.input.toString().ifEmpty { block.initialInput })
                }
                else -> null
            }
        }
        val stop = when (reason) {
            "end_turn", "stop_sequence" -> StopReason.EndTurn
            "tool_use" -> StopReason.ToolUse
            "max_tokens" -> StopReason.MaxTokens
            else -> StopReason.Other
        }
        return ModelReply(parts, stop, usage)
    }
}

/** 默认回复上限;开了思考时思考也占这个额度,4K 往往在出答案前就用完,给 [THINKING_MAX_TOKENS]。 */
private const val DEFAULT_MAX_TOKENS = 4096
private const val THINKING_MAX_TOKENS = 16_000

/**
 * 回传的思考块前缀对不上时报错里的一句(Anthropic 文档 Preserved thinking 一节的原文)。
 * 只有前半句「Invalid `signature` in `thinking` block」、没有这一句的是签名本身坏了,不重发,照常报错。
 */
private const val STALE_THINKING = "bound to a different conversation"

/**
 * 记在 [Part.Thinking.opaque] 里的 Anthropic 思考块:带签名的 thinking 是 `{"endpoint":…,"signature":…}`,
 * redacted_thinking 是 `{"endpoint":…,"redacted":…}`。签名只有发它的服务认,回放时只认同一个地址收到的;
 * 别的地址(另一个 Anthropic 兼容服务)、别家适配器存的都不回传。
 */
internal object SignedThinking {
    fun signed(endpoint: String, signature: String): String =
        buildJsonObject { put("endpoint", endpoint); put("signature", signature) }.toString()

    fun redacted(endpoint: String, data: String): String =
        buildJsonObject { put("endpoint", endpoint); put("redacted", data) }.toString()

    /** [p] 是从 [endpoint] 收到的思考块时,回放用的那一块;别处来的、认不出的为 null。 */
    fun block(p: Part.Thinking, endpoint: String): JsonObject? {
        val o = p.opaque?.let { runCatching { Json.parseToJsonElement(it) as? JsonObject }.getOrNull() } ?: return null
        if ((o["endpoint"] as? JsonPrimitive)?.contentOrNull != endpoint) return null
        (o["redacted"] as? JsonPrimitive)?.contentOrNull?.let { data ->
            return buildJsonObject { put("type", "redacted_thinking"); put("data", data) }
        }
        val signature = (o["signature"] as? JsonPrimitive)?.contentOrNull ?: return null
        return buildJsonObject { put("type", "thinking"); put("thinking", p.text); put("signature", signature) }
    }
}
