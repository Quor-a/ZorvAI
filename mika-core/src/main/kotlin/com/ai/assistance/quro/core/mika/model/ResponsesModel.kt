package com.ai.assistance.quro.core.mika.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 请求时带的 Bearer 令牌。[fresh] 为真表示上一个被拒了(HTTP 401),要先换一个新的;换不到抛 [ModelError]。
 * 没有令牌(本地服务)为 null。[refreshable] 为假的(固定的 API Key)被拒后不再重试。
 */
interface BearerToken {
    val refreshable: Boolean get() = false
    suspend fun get(fresh: Boolean): String?

    companion object {
        /** 固定的 API Key。 */
        fun fixed(key: String?): BearerToken = object : BearerToken {
            override suspend fun get(fresh: Boolean): String? = key
        }
    }
}

/**
 * OpenAI Responses 接口(`POST {base}/responses`),一律流式、不在服务端存对话(`store:false`):每次把要用的上下文都放在
 * `input` 数组里发,系统提示词放 `instructions`,对话里的系统消息改成 developer 消息(system 消息条目有的服务端不收)。
 * 工具是 function 工具(`strict:false`,ZorvAI 的参数 schema 不按严格模式写);结果按 `function_call_output` 回给模型。
 * 默认只发这几项和 `include`;用户选了思考档位才发 `reasoning.effort`。温度、输出上限不发(套餐接口不收)。
 *
 * 推理模型的思考:请求加密后的思考内容(`include: reasoning.encrypted_content`),原样存在这一回合第一个工具调用的
 * [Part.ToolCall.opaque] 里,回放时放回工具调用前面,模型接着上一步的思路做;只给文字回答的回合不需要接着想,不存。
 * 服务端说不收 `include` 时去掉再发一次。
 *
 * 只有收到 `response.completed`(或 `response.incomplete`)才算成功;流在这之前断开当作网络故障(会重试)。
 * [chatGptPlan] 为真时请求用的是用户的 ChatGPT 套餐额度:HTTP 429 是额度到了上限、403 是账号不能这样用,都不重试。
 * [accountLogin] 是用别家账号登录(如 xAI)时那一家的名字:HTTP 403 是订阅档位不能用这个接口([ModelError.AccountPlanNotAllowed])。
 */
class ResponsesModel(
    baseUrl: String,
    private val token: BearerToken,
    private val model: String,
    override val capabilities: Capabilities,
    private val http: OkHttpClient,
    private val chatGptPlan: Boolean = false,
    /** 每个请求都带的头(见 [ClientHeaders]);令牌的头由适配器自己加。这个接口不给回复上限(套餐那条路不收)。 */
    private val headers: Map<String, String> = emptyMap(),
    /** null 使用服务端默认;服务端拒绝所选档位时照常报错,不悄悄换档重试。 */
    private val reasoningEffort: String? = null,
    private val accountLogin: String? = null,
) : StreamingChatModel {
    override val name: String get() = model
    private val endpoint = baseUrl.trim().trimEnd('/') + "/responses"
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply =
        completeStreaming(system, messages, tools) {}

    override suspend fun completeStreaming(
        system: String, messages: List<Message>, tools: List<ToolSpec>, onText: (String) -> Unit,
    ): ModelReply {
        var includeReasoning = true
        var fresh = false
        while (true) {
            val stream = ResponsesStream(onText, ::errorOf)
            val bearer = token.get(fresh)
            val (status, retryAfter, text) = HttpSupport.stream(http, request(bearer, system, messages, tools, includeReasoning), stream::accept)
            if (status in 200..299) {
                if (text.isNotEmpty()) return stream.fromBody(text).also { reply ->
                    reply.parts.filterIsInstance<Part.Text>().joinToString("") { it.text }.takeIf(String::isNotEmpty)?.let(onText)
                }
                return stream.finish()
            }
            val err = errorBody(text)
            when {
                status == 401 && token.refreshable && !fresh -> fresh = true
                status == 400 && includeReasoning && (err?.param == "include" || text.contains("reasoning.encrypted_content")) ->
                    includeReasoning = false
                else -> throw httpError(status, retryAfter, text, err)
            }
        }
    }

    internal fun body(system: String, messages: List<Message>, tools: List<ToolSpec>, includeReasoning: Boolean = true): JsonObject =
        buildJsonObject {
            put("model", model)
            if (system.isNotEmpty()) put("instructions", system)
            put("input", JsonArray(messages.flatMap(::serialize)))
            if (tools.isNotEmpty()) putJsonArray("tools") { tools.forEach { add(toolJson(it)) } }
            put("store", false)
            put("stream", true)
            reasoningEffort?.let { effort -> putJsonObject("reasoning") { put("effort", effort) } }
            if (includeReasoning) putJsonArray("include") { add(ENCRYPTED_REASONING) }
        }

    private suspend fun request(bearer: String?, system: String, messages: List<Message>, tools: List<ToolSpec>, includeReasoning: Boolean): Request =
        Request.Builder().url(endpoint)
            .withHeaders(headers)
            .apply { if (!bearer.isNullOrEmpty()) header("Authorization", "Bearer $bearer") }
            .header("Accept", "text/event-stream")
            .postInference(InferenceProtocol.Responses, body(system, messages, tools, includeReasoning)) {
                if (chatGptPlan || endpoint.toHttpUrlOrNull()?.host == "api.openai.com") "openai" else "openai_compatible"
            }
            .build()

    private fun toolJson(t: ToolSpec): JsonObject = buildJsonObject {
        put("type", "function")
        put("name", t.name)
        put("description", t.description)
        put("parameters", try { json.parseToJsonElement(t.parametersJsonSchema) } catch (e: Exception) {
            throw IllegalArgumentException("工具 ${t.name} 的参数 schema 不是合法 JSON", e)
        })
        put("strict", false)
    }

    /** 一条内部消息展开成若干 `input` 条目:助手的话、思考、工具调用;工具结果各占一条;文字和图片合成一条消息。 */
    private fun serialize(m: Message): List<JsonObject> {
        if (m.role == Role.Assistant) return assistantItems(m)
        val out = mutableListOf<JsonObject>()
        m.parts.filterIsInstance<Part.ToolResult>().forEach { r ->
            out += buildJsonObject { put("type", "function_call_output"); put("call_id", r.callId); put("output", r.content) }
        }
        val rest = m.parts.filter { it is Part.Text || it is Part.Image }
        if (rest.isNotEmpty()) out += buildJsonObject {
            put("type", "message")
            put("role", if (m.role == Role.System) "developer" else "user")
            putJsonArray("content") {
                rest.forEach { p ->
                    addJsonObject {
                        if (p is Part.Image && capabilities.vision) {
                            put("type", "input_image"); put("image_url", "data:${p.mime};base64,${p.pngBase64}")
                        } else {
                            put("type", "input_text"); put("text", if (p is Part.Text) p.text else IMAGE_OMITTED)
                        }
                    }
                }
            }
        }
        return out
    }

    private fun assistantItems(m: Message): List<JsonObject> {
        val calls = m.parts.filterIsInstance<Part.ToolCall>()
        val reasoning = calls.mapNotNull { it.opaque }.flatMap { o ->
            runCatching { json.parseToJsonElement(o) as? JsonArray }.getOrNull().orEmpty().filterIsInstance<JsonObject>()
        }
        val text = m.parts.filterIsInstance<Part.Text>().joinToString("\n") { it.text }
        return buildList {
            addAll(reasoning)
            if (text.isNotEmpty()) add(buildJsonObject {
                put("type", "message"); put("role", "assistant")
                putJsonArray("content") { addJsonObject { put("type", "output_text"); put("text", text) } }
            })
            calls.forEach { c ->
                add(buildJsonObject { put("type", "function_call"); put("call_id", c.id); put("name", c.name); put("arguments", c.argsJson) })
            }
        }
    }

    internal data class ErrorBody(val code: String?, val message: String, val param: String?)

    private fun errorBody(text: String): ErrorBody? {
        val root = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return null
        val e = root["error"] as? JsonObject
        if (e != null) return ErrorBody(e.str("code") ?: e.str("type"), e.str("message").orEmpty(), e.str("param"))
        return root.str("detail")?.let { ErrorBody(null, it, null) }
    }

    /** 流里的 `response.failed` / `error`,以及 HTTP 出错时内容里的 `error.code`。认不出的代码为 null。 */
    private fun errorOf(code: String?, message: String, param: String?): ModelError? = when (code) {
        "subscription_sharing_usage_limit_exceeded" -> ModelError.ChatGptLimitReached()
        "subscription_sharing_user_not_eligible" -> ModelError.ChatGptNotEligible(message.take(200))
        // 服务端一时不可用:保留登录,过一会儿再试
        "subscription_sharing_usage_unavailable", "subscription_sharing_user_unavailable" -> ModelError.Server(503)
        "subscription_sharing_unsupported_capability" -> ModelError.BadResponse("服务端不支持请求里的 ${param ?: "某项内容"}")
        "subscription_sharing_route_not_supported" -> ModelError.BadResponse("服务端不支持这个接口")
        "subscription_sharing_invalid_user", "chatpass_v2_scope_not_authorized", "chatpass_v2_invalid_authorization_context" ->
            ModelError.Auth()
        "context_length_exceeded" -> ModelError.ContextTooLong()
        "insufficient_quota" -> ModelError.QuotaExceeded()
        "rate_limit_exceeded" -> if (chatGptPlan) ModelError.ChatGptLimitReached() else ModelError.RateLimited(null)
        "model_not_found" -> ModelError.ModelNotFound(model, ModelAccessErrors.clip(message))
        "server_error" -> ModelError.Server(500)
        else -> null
    }

    private fun httpError(status: Int, retryAfter: String?, body: String, err: ErrorBody?): ModelError =
        err?.let { errorOf(it.code, it.message, it.param) }
            // 账号登录已经核对过身份:没有认得出的错误代码的 403 是档位不够,不当成 Key 不对或某个模型没开通
            ?: (if (status == 403 && accountLogin != null) ModelError.AccountPlanNotAllowed(accountLogin, err?.message.orEmpty().take(200)) else null)
            ?: ModelAccessErrors.of(status, body, model) ?: when {
            status == 401 -> ModelError.Auth()
            status == 403 -> if (chatGptPlan) ModelError.ChatGptNotEligible(err?.message.orEmpty().take(200)) else ModelError.Auth()
            status == 429 -> if (chatGptPlan) ModelError.ChatGptLimitReached() else ModelError.RateLimited(HttpSupport.retryAfterMs(retryAfter))
            status == 402 -> ModelError.QuotaExceeded()
            status == 400 && (body.contains("context_length") || body.contains("maximum context")) -> ModelError.ContextTooLong()
            status == 404 -> ModelError.ModelNotFound(model, ModelAccessErrors.jsonDetail(body))
            status in 500..599 -> ModelError.Server(status)
            else -> ModelError.BadResponse("HTTP $status ${body.take(200)}")
        }

    private companion object {
        const val ENCRYPTED_REASONING = "reasoning.encrypted_content"
        const val IMAGE_OMITTED = "[screenshot omitted: the current model cannot see images]"
    }
}

private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

/**
 * 读 Responses 的事件流:`response.output_text.delta` 是逐段文字,`response.output_item.done` 是做完的一项
 * (消息、工具调用、思考),`response.completed` / `response.incomplete` 收尾,`response.failed` / `error` 是失败。
 */
private class ResponsesStream(
    private val onText: (String) -> Unit,
    private val errorOf: (String?, String, String?) -> ModelError?,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val visible = StringBuilder()
    private val items = mutableListOf<JsonObject>()
    private var final: JsonObject? = null
    private var incomplete = false

    fun accept(data: String) {
        if (data == "[DONE]") return
        val root = try { json.parseToJsonElement(data) as? JsonObject } catch (e: Exception) { null }
            ?: throw ModelError.BadResponse("流式响应不是合法 JSON")
        when (root.str("type")) {
            "response.output_text.delta" -> root.str("delta")?.takeIf { it.isNotEmpty() }?.let {
                visible.append(it)
                onText(visible.toString())
            }
            "response.output_item.done" -> (root["item"] as? JsonObject)?.let { items += it }
            "response.completed" -> final = root["response"] as? JsonObject ?: JsonObject(emptyMap())
            "response.incomplete" -> {
                final = root["response"] as? JsonObject ?: JsonObject(emptyMap())
                incomplete = true
            }
            "response.failed" -> throw failure((root["response"] as? JsonObject)?.get("error") as? JsonObject)
            "error" -> throw failure(root["error"] as? JsonObject ?: root)
        }
    }

    private fun failure(e: JsonObject?): ModelError {
        val code = e?.str("code")
        val message = e?.str("message").orEmpty()
        return errorOf(code, message, e?.str("param")) ?: ModelError.BadResponse(listOfNotNull(code, message.take(200)).joinToString(" "))
    }

    /** 服务端没按流式回、直接给了整个 response 对象。 */
    fun fromBody(text: String): ModelReply {
        val root = try { json.parseToJsonElement(text) as? JsonObject } catch (e: Exception) { null }
            ?: throw ModelError.BadResponse("返回内容不是合法 JSON")
        (root["error"] as? JsonObject)?.let { throw failure(it) }
        final = root
        incomplete = root.str("status") == "incomplete"
        return finish()
    }

    fun finish(): ModelReply {
        // 没等到 response.completed / response.incomplete 是连接中途被断开
        val response = final ?: throw ModelError.StreamCut()
        val output = items.ifEmpty { (response["output"] as? JsonArray)?.filterIsInstance<JsonObject>().orEmpty() }
        val reasoning = mutableListOf<JsonObject>()
        val thinking = StringBuilder()
        val text = StringBuilder()
        var refusal = false
        val calls = mutableListOf<Part.ToolCall>()
        output.forEach { item ->
            when (item.str("type")) {
                "reasoning" -> {
                    (item["summary"] as? JsonArray)?.forEach { s -> (s as? JsonObject)?.str("text")?.let(thinking::append) }
                    // 回放用:去掉只在服务端有意义的编号和状态,留下加密的思考
                    if (item["encrypted_content"] != null) reasoning += JsonObject(item - "id" - "status")
                }
                "message" -> (item["content"] as? JsonArray)?.forEach { c ->
                    val o = c as? JsonObject ?: return@forEach
                    when (o.str("type")) {
                        "output_text" -> o.str("text")?.let(text::append)
                        "refusal" -> refusal = true
                    }
                }
                "function_call" -> {
                    val id = item.str("call_id") ?: throw ModelError.BadResponse("工具调用缺少编号")
                    val name = item.str("name") ?: throw ModelError.BadResponse("工具调用缺少名称")
                    calls += Part.ToolCall(id, name, item.str("arguments")?.ifEmpty { null } ?: "{}")
                }
            }
        }
        if (text.isEmpty() && visible.isNotEmpty()) text.append(visible)
        val reason = (response["incomplete_details"] as? JsonObject)?.str("reason")
        if (refusal && text.isEmpty() && calls.isEmpty()) throw ModelError.ContentRefused()
        if (incomplete && reason == "content_filter" && text.isEmpty() && calls.isEmpty()) throw ModelError.ContentRefused()
        val withReasoning = if (reasoning.isNotEmpty() && calls.isNotEmpty()) {
            listOf(calls.first().copy(opaque = JsonArray(reasoning).toString())) + calls.drop(1)
        } else calls
        val parts = buildList {
            if (thinking.isNotEmpty()) add(Part.Thinking(thinking.toString()))
            if (text.isNotEmpty()) add(Part.Text(text.toString()))
            addAll(withReasoning)
        }
        val stop = when {
            calls.isNotEmpty() -> StopReason.ToolUse
            incomplete && reason == "max_output_tokens" -> StopReason.MaxTokens
            incomplete -> StopReason.Other
            else -> StopReason.EndTurn
        }
        return ModelReply(parts, stop, usageOf(response["usage"]))
    }

    private fun usageOf(el: JsonElement?): Usage? {
        val u = el as? JsonObject ?: return null
        fun int(o: JsonObject?, k: String) = (o?.get(k) as? JsonPrimitive)?.intOrNull
        val input = int(u, "input_tokens") ?: return null
        val cached = int(u["input_tokens_details"] as? JsonObject, "cached_tokens") ?: 0
        val output = int(u, "output_tokens") ?: 0
        val reasoning = (int(u["output_tokens_details"] as? JsonObject, "reasoning_tokens") ?: 0).coerceIn(0, output)
        return Usage(input, output, cached.coerceAtMost(input), reasoning)
    }
}
