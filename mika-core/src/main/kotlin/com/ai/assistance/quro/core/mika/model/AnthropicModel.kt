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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Anthropic Messages API。
 * - 历史里的 System 消息并入 system 文本(Anthropic 没有 system 角色消息)。
 * - Thinking 不回传、请求也不开 extended thinking:Part.Thinking 不带 signature,而带 tool_use 的回合回放时必须带签名。
 * - 相邻同角色消息合并(Anthropic 要求 user/assistant 交替)。
 */
class AnthropicModel(
    baseUrl: String = "https://api.anthropic.com",
    private val apiKey: String,
    private val model: String,
    override val capabilities: Capabilities,
    private val http: OkHttpClient,
    private val maxTokens: Int = 4096,
    /** 每个请求都带的头(见 [ClientHeaders]);Key 的头由适配器自己加。 */
    private val headers: Map<String, String> = emptyMap(),
) : StreamingChatModel {
    override val name: String get() = model
    private val endpoint = baseUrl.trimEnd('/') + "/v1/messages"
    private val json = Json { ignoreUnknownKeys = true }
    private val ephemeral = buildJsonObject { put("type", "ephemeral") }

    override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply {
        val (status, retryAfter, text) = HttpSupport.execute(http, request(system, messages, tools, stream = false))
        if (status !in 200..299) throw mapError(status, retryAfter, text)
        return parse(text)
    }

    override suspend fun completeStreaming(
        system: String, messages: List<Message>, tools: List<ToolSpec>, onText: (String) -> Unit,
    ): ModelReply {
        val stream = AnthropicStream(onText, ::parseUsage)
        val (status, retryAfter, text) = HttpSupport.stream(http, request(system, messages, tools, stream = true), stream::accept)
        if (status !in 200..299) throw mapError(status, retryAfter, text)
        if (text.isNotEmpty()) return parse(text).also { reply ->
            reply.parts.filterIsInstance<Part.Text>().joinToString("") { it.text }.takeIf(String::isNotEmpty)?.let(onText)
        }
        return stream.finish()
    }

    private fun request(system: String, messages: List<Message>, tools: List<ToolSpec>, stream: Boolean): Request {
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
        }
        return Request.Builder().url(endpoint)
            .withHeaders(headers)
            .header("x-api-key", apiKey)
            .header("anthropic-version", "2023-06-01")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()
    }

    private fun mapError(status: Int, retryAfter: String?, body: String): ModelError = ModelAccessErrors.of(status, body, model) ?: when {
        status == 401 || status == 403 -> ModelError.Auth()
        status == 402 || body.contains("credit balance is too low") -> ModelError.QuotaExceeded()
        status == 429 -> ModelError.RateLimited(HttpSupport.retryAfterMs(retryAfter))
        status == 413 || body.contains("prompt is too long") -> ModelError.ContextTooLong()
        status == 404 -> ModelError.ModelNotFound(model)
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

    private fun assistantBlocks(m: Message): List<JsonObject> = m.parts.mapNotNull { p ->
        when (p) {
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
                    "thinking" -> b["thinking"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() }?.let { parts += Part.Thinking(it) }
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
        // Anthropic 的 input_tokens 不含缓存部分;统一成总输入口径
        return Usage(inT + cached + (n("cache_creation_input_tokens") ?: 0), n("output_tokens") ?: 0, cached)
    }
}

private class AnthropicStream(
    private val onText: (String) -> Unit,
    private val usageOf: (JsonElement?) -> Usage?,
) {
    private data class Block(val type: String, val id: String = "", val name: String = "",
        val text: StringBuilder = StringBuilder(), val input: StringBuilder = StringBuilder(), val initialInput: String = "{}")

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
                ).also { block ->
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
                "thinking" -> block.text.toString().takeIf(String::isNotEmpty)?.let(Part::Thinking)
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
