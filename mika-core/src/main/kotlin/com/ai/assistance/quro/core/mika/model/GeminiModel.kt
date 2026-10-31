package com.ai.assistance.quro.core.mika.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Gemini generateContent 原生接口。
 * - 角色只有 user / model;相邻同角色合并。历史里的 System 消息并入 systemInstruction。
 * - Gemini 不给调用编号:合成 "gemini-<序号>-<名称>"(响应带 id 则用之);
 *   回传 functionResponse 时按 callId 到历史里的 ToolCall 找函数名。
 * - thought 部分解析为 Thinking,不回传、请求也不开 thinkingConfig。
 */
/** Google 给的占位签名:调用不是 Gemini 自己产生的(带不出原签名)时用它跳过签名校验。 */
internal const val SKIP_SIGNATURE = "skip_thought_signature_validator"

class GeminiModel(
    baseUrl: String = "https://generativelanguage.googleapis.com",
    private val apiKey: String,
    private val model: String,
    override val capabilities: Capabilities,
    private val http: OkHttpClient,
    /** 每个请求都带的头(见 [ClientHeaders]);Key 的头由适配器自己加。 */
    private val headers: Map<String, String> = emptyMap(),
    /** 回复最多多少 token(`generationConfig.maxOutputTokens`);null 不限,按服务端默认。 */
    private val maxOutputTokens: Int? = null,
) : StreamingChatModel {
    override val name: String get() = model
    private val endpoint = baseUrl.trimEnd('/') + "/v1beta/models/$model:generateContent"
    private val streamEndpoint = baseUrl.trimEnd('/') + "/v1beta/models/$model:streamGenerateContent?alt=sse"
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply {
        val (status, retryAfter, text) = HttpSupport.execute(http, request(system, messages, tools, stream = false))
        if (status !in 200..299) throw mapError(status, retryAfter, text)
        return parse(text)
    }

    override suspend fun completeStreaming(
        system: String, messages: List<Message>, tools: List<ToolSpec>, onText: (String) -> Unit,
    ): ModelReply {
        val stream = GeminiStream(onText, ::parse, ::parseUsage)
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
            .filter { it.isNotBlank() }.joinToString("\n\n")
        val payload = buildJsonObject {
            if (systemText.isNotBlank()) putJsonObject("systemInstruction") {
                putJsonArray("parts") { add(buildJsonObject { put("text", systemText) }) }
            }
            if (tools.isNotEmpty()) put("tools", buildJsonArray {
                add(buildJsonObject {
                    put("functionDeclarations", buildJsonArray { tools.forEach { add(toolJson(it)) } })
                })
            })
            put("contents", buildContents(messages))
            maxOutputTokens?.let { n -> putJsonObject("generationConfig") { put("maxOutputTokens", n) } }
        }
        return Request.Builder().url(if (stream) streamEndpoint else endpoint)
            .withHeaders(headers)
            .header("x-goog-api-key", apiKey)
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()
    }

    private fun mapError(status: Int, retryAfter: String?, body: String): ModelError {
        val err = try { json.parseToJsonElement(body).jsonObject["error"] as? JsonObject } catch (e: Exception) { null }
        val st = (err?.get("status") as? JsonPrimitive)?.contentOrNull.orEmpty()
        val msg = (err?.get("message") as? JsonPrimitive)?.contentOrNull ?: body
        val lower = msg.lowercase()
        ModelAccessErrors.of(status, body, model)?.let { return it }
        return when {
            status == 401 || status == 403 -> ModelError.Auth()
            // 这个模型额度为零的 429 上面已认成用不了;其余 429 按可重试限流处理(其限流与额度文案混用,无法可靠区分)
            status == 429 || st == "RESOURCE_EXHAUSTED" -> ModelError.RateLimited(HttpSupport.retryAfterMs(retryAfter))
            status == 404 -> ModelError.ModelNotFound(model)
            status == 413 || (status == 400 && lower.contains("token") &&
                (lower.contains("exceed") || lower.contains("too long") || lower.contains("maximum"))) -> ModelError.ContextTooLong()
            status in 500..599 -> ModelError.Server(status)
            else -> ModelError.BadResponse("HTTP $status ${body.take(200)}")
        }
    }

    // Gemini 不接受 additionalProperties / $schema / $id 等 OpenAPI 子集之外的键
    private val rejectedKeys = setOf("additionalProperties", "\$schema", "\$id", "\$defs")

    // 只在 schema 位置剔除;properties 是 名称->schema 的映射,名称本身不动
    private fun sanitize(el: JsonElement): JsonElement = when (el) {
        is JsonObject -> JsonObject(el.filterKeys { it !in rejectedKeys }.mapValues { (k, v) ->
            if (k == "properties" && v is JsonObject) JsonObject(v.mapValues { sanitize(it.value) }) else sanitize(v)
        })
        is JsonArray -> JsonArray(el.map { sanitize(it) })
        else -> el
    }

    private fun toolJson(t: ToolSpec): JsonObject = buildJsonObject {
        put("name", t.name)
        put("description", t.description)
        val schema = try { json.parseToJsonElement(t.parametersJsonSchema) } catch (e: Exception) {
            throw IllegalArgumentException("工具 ${t.name} 的参数 schema 不是合法 JSON", e)
        }
        // 无参数工具:Gemini 拒绝没有 properties 的 object schema,省略 parameters
        val clean = sanitize(schema)
        val hasProps = (clean as? JsonObject)?.get("properties")?.let { it is JsonObject && it.isNotEmpty() } == true
        if (hasProps) put("parameters", clean)
    }

    private fun buildContents(messages: List<Message>): JsonArray {
        val nameById = HashMap<String, String>()
        messages.forEach { m -> m.parts.filterIsInstance<Part.ToolCall>().forEach { nameById[it.id] = it.name } }
        val merged = mutableListOf<Pair<String, MutableList<JsonObject>>>()
        for (m in messages) {
            if (m.role == Role.System) continue
            val role = if (m.role == Role.Assistant) "model" else "user"
            val parts = if (role == "model") modelParts(m) else userParts(m, nameById)
            if (parts.isEmpty()) continue
            if (merged.lastOrNull()?.first == role) merged.last().second += parts else merged += role to parts.toMutableList()
        }
        return buildJsonArray {
            merged.forEach { (role, parts) ->
                add(buildJsonObject { put("role", role); put("parts", JsonArray(parts)) })
            }
        }
    }

    private fun userParts(m: Message, nameById: Map<String, String>): List<JsonObject> {
        // functionResponse 紧跟在 functionCall 之后,排在其它部分之前
        val results = m.parts.filterIsInstance<Part.ToolResult>().map { r ->
            buildJsonObject {
                putJsonObject("functionResponse") {
                    put("name", nameById[r.callId] ?: r.callId)
                    putJsonObject("response") {
                        put("content", r.content)
                        if (r.isError) put("is_error", true)
                    }
                }
            }
        }
        val rest = m.parts.mapNotNull { p ->
            when {
                p is Part.Text -> if (p.text.isEmpty()) null else buildJsonObject { put("text", p.text) }
                p is Part.Image && capabilities.vision -> buildJsonObject {
                    putJsonObject("inlineData") { put("mimeType", p.mime); put("data", p.pngBase64) }
                }
                p is Part.Image -> buildJsonObject { put("text", "[screenshot omitted: the current model cannot see images]") }
                else -> null
            }
        }
        return results + rest
    }

    private fun modelParts(m: Message): List<JsonObject> {
        // 一轮里只有第一个调用带签名(并行调用的其余几个本来就没有);第一个没有签名说明调用不是本适配器产生的
        // (换了模型、或回放补的调用),按 Google 的说明发跳过校验的占位签名
        val firstCall = m.parts.firstOrNull { it is Part.ToolCall }
        return m.parts.mapNotNull { p -> modelPart(p, first = p === firstCall) }
    }

    private fun modelPart(p: Part, first: Boolean): JsonObject? =
        when (p) {
            is Part.Text -> if (p.text.isEmpty()) null else buildJsonObject { put("text", p.text) }
            is Part.ToolCall -> buildJsonObject {
                (p.opaque ?: SKIP_SIGNATURE.takeIf { first })?.let { put("thoughtSignature", it) }
                putJsonObject("functionCall") {
                    put("name", p.name)
                    put("args", try { json.parseToJsonElement(p.argsJson).jsonObject } catch (e: Exception) { JsonObject(emptyMap()) })
                }
            }
            else -> null
        }

    private fun parse(text: String): ModelReply {
        try {
            val root = json.parseToJsonElement(text).jsonObject
            val blockReason = ((root["promptFeedback"] as? JsonObject)?.get("blockReason") as? JsonPrimitive)?.contentOrNull
            if (blockReason != null) throw ModelError.ContentRefused()
            val cand = root["candidates"]?.jsonArray?.firstOrNull()?.jsonObject
                ?: throw ModelError.BadResponse("缺少 candidates")
            val parts = mutableListOf<Part>()
            var calls = 0
            (cand["content"] as? JsonObject)?.get("parts")?.jsonArray?.forEach { el ->
                val p = el.jsonObject
                val fc = p["functionCall"] as? JsonObject
                val t = (p["text"] as? JsonPrimitive)?.contentOrNull
                when {
                    fc != null -> {
                        val name = (fc["name"] as? JsonPrimitive)?.contentOrNull
                            ?: throw ModelError.BadResponse("functionCall 缺少 name")
                        val id = (fc["id"] as? JsonPrimitive)?.contentOrNull ?: "gemini-$calls-$name"
                        val sig = (p["thoughtSignature"] as? JsonPrimitive)?.contentOrNull
                        parts += Part.ToolCall(id, name, (fc["args"] ?: JsonObject(emptyMap())).toString(), sig)
                        calls++
                    }
                    t.isNullOrEmpty() -> {}
                    (p["thought"] as? JsonPrimitive)?.booleanOrNull == true -> parts += Part.Thinking(t)
                    else -> parts += Part.Text(t)
                }
            }
            val usable = parts.any { it !is Part.Thinking }
            val stop = when (val fr = (cand["finishReason"] as? JsonPrimitive)?.contentOrNull) {
                null, "STOP" -> if (calls > 0) StopReason.ToolUse else StopReason.EndTurn
                "MAX_TOKENS" -> StopReason.MaxTokens
                "SAFETY", "RECITATION", "PROHIBITED_CONTENT", "BLOCKLIST", "SPII" ->
                    if (usable) StopReason.Other else throw ModelError.ContentRefused()
                else -> if (calls > 0 && fr.isNotEmpty()) StopReason.ToolUse else StopReason.Other
            }
            return ModelReply(parts, stop, parseUsage(root["usageMetadata"]))
        } catch (e: ModelError) {
            throw e
        } catch (e: Exception) {
            throw ModelError.BadResponse(e.message.orEmpty().take(200), e)
        }
    }

    private fun parseUsage(el: JsonElement?): Usage? {
        val u = el as? JsonObject ?: return null
        fun n(k: String) = (u[k] as? JsonPrimitive)?.intOrNull
        val inT = n("promptTokenCount") ?: return null
        // promptTokenCount 已含缓存命中部分,与统一口径一致
        return Usage(inT, (n("candidatesTokenCount") ?: 0) + (n("thoughtsTokenCount") ?: 0), n("cachedContentTokenCount") ?: 0)
    }
}

private class GeminiStream(
    private val onText: (String) -> Unit,
    private val parseChunk: (String) -> ModelReply,
    private val usageOf: (JsonElement?) -> Usage?,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val visible = StringBuilder()
    private val thinking = StringBuilder()
    private val calls = mutableListOf<Part.ToolCall>()
    private var usage: Usage? = null
    private var finishReason: String? = null

    fun accept(data: String) {
        val root = try { json.parseToJsonElement(data).jsonObject }
        catch (e: Exception) { throw ModelError.BadResponse("流式响应不是合法 JSON", e) }
        usageOf(root["usageMetadata"])?.let { usage = it }
        val candidate = (root["candidates"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return
        (candidate["finishReason"] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotEmpty)?.let { finishReason = it }
        val reply = parseChunk(data)
        var chunkCalls = 0
        reply.parts.forEach { part ->
            when (part) {
                is Part.Text -> { visible.append(part.text); onText(visible.toString()) }
                is Part.Thinking -> thinking.append(part.text)
                is Part.ToolCall -> {
                    calls += if (part.id == "gemini-$chunkCalls-${part.name}") part.copy(id = "gemini-${calls.size}-${part.name}") else part
                    chunkCalls++
                }
                else -> Unit
            }
        }
    }

    fun finish(): ModelReply {
        // Gemini 的流没有单独的结束标记,带 finishReason 的那一帧就是收尾;没等到它是连接中途被断开
        if (finishReason == null) throw ModelError.StreamCut()
        val parts = buildList<Part> {
            if (thinking.isNotEmpty()) add(Part.Thinking(thinking.toString()))
            if (visible.isNotEmpty()) add(Part.Text(visible.toString()))
            addAll(calls)
        }
        val stop = when (finishReason) {
            "STOP" -> if (calls.isNotEmpty()) StopReason.ToolUse else StopReason.EndTurn
            "MAX_TOKENS" -> StopReason.MaxTokens
            else -> StopReason.Other
        }
        return ModelReply(parts, stop, usage)
    }
}
