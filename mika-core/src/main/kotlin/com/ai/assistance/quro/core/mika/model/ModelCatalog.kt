package com.ai.assistance.quro.core.mika.model

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 服务商列出的一个模型。能力字段为 null 表示接口没给,由调用方沿用自己的初值,这里不猜。
 *
 * 有的服务商一个 Key 下的模型走不同的接口协议,列表里给每个模型写明协议和地址(`api` 表)。
 * [endpoint] 是其中 ZorvAI 会说的那一种,调用这个模型时按它选适配器和地址;为 null 时按服务商的接口类型。
 * [unsupportedApis] 是写明了、但 ZorvAI 一种也不会说的协议名:这样的模型调用必然失败,不给选(见 [usable])。
 * [chat] 是接口说没说它是对话模型(`type` 是 chat / embedding / rerank…,或输出模态里有没有文字);没说为 null。
 */
data class ListedModel(
    val id: String,
    val displayName: String = "",
    val toolCalls: Boolean? = null,
    val vision: Boolean? = null,
    val contextTokens: Int? = null,
    val reasoning: Boolean? = null,
    val endpoint: ModelEndpoint? = null,
    val unsupportedApis: List<String> = emptyList(),
    val chat: Boolean? = null,
    /** 服务商明确给出的思考档位;null 表示未知,空列表表示没有可选档位。保留服务商的顺序。 */
    val reasoningEfforts: List<String>? = null,
) {
    val usable: Boolean get() = endpoint != null || unsupportedApis.isEmpty()
}

/** 模型列表里 `api` 表认得的接口协议;[wire] 是表里的键名。 */
enum class ModelProtocol(val wire: String) {
    /** OpenAI Chat Completions,由 [OpenAiCompatModel] 调用。 */
    ChatCompletions("chat-completions"),
    /** Anthropic Messages,由 [AnthropicModel] 调用。 */
    Messages("messages"),
    /** Gemini generateContent,由 [GeminiModel] 调用。 */
    GenerateContent("generate-content"),
    /** OpenAI Responses,由 [ResponsesModel] 调用。排在最后:同一个模型也写了前几种的,照旧用前几种。 */
    Responses("responses");

    companion object {
        fun of(wire: String?): ModelProtocol? = entries.firstOrNull { it.wire == wire }
    }
}

/**
 * 一个模型单独指定的接口:协议、列表里给的地址([baseUrl],是这个协议的根,如 `…/anthropic/v1`),
 * 以及兼容接口把思考内容放在哪个字段([reasoningField],如 `reasoning_content`;没说为 null)。
 */
data class ModelEndpoint(val protocol: ModelProtocol, val baseUrl: String, val reasoningField: String? = null) {
    /**
     * 交给适配器的 Base URL。适配器自己接版本路径:[AnthropicModel] 接 `/v1/messages`、[GeminiModel] 接
     * `/v1beta/models/…`,所以列表地址末尾已带的这一段去掉,免得重复;Chat Completions 只接 `/chat/completions`、
     * Responses 只接 `/responses`,原样用。
     */
    val adapterBaseUrl: String
        get() {
            val url = baseUrl.trim().trimEnd('/')
            return when (protocol) {
                ModelProtocol.ChatCompletions, ModelProtocol.Responses -> url
                ModelProtocol.Messages -> url.removeSuffix("/v1")
                ModelProtocol.GenerateContent -> url.removeSuffix("/v1beta")
            }
        }
}

/** 列模型为什么失败;[text] 是给人看的原因。 */
enum class ListFailure(val text: String) {
    BadUrl("接口地址不对,要以 http:// 或 https:// 开头"),
    Auth("API Key 无效或没有权限"),
    Quota("账户额度已用完"),
    RateLimited("请求太频繁,请稍后再试"),
    Network("连不上这个地址,请检查网络和接口地址"),
    NotSupported("这个服务不提供模型列表"),
    Server("服务暂时出错,请稍后再试"),
    BadResponse("返回的内容看不懂,请检查接口地址和接口类型"),
}

sealed interface ModelListResult {
    data class Ok(val models: List<ListedModel>) : ModelListResult
    data class Failed(val reason: ListFailure, val detail: String = "") : ModelListResult {
        val message: String get() = if (detail.isEmpty()) reason.text else "${reason.text}($detail)"
    }
}

/**
 * 用各家公开的「列出模型」接口取可用模型:
 * - OpenAI 兼容:`GET {base}/models`(Bearer)。只给 id 的照实返回;带 `context_length`、
 *   `architecture.input_modalities`、`supported_parameters` 等字段的(部分聚合服务会给)才填能力。
 *   也认另一种写法:`capabilities.tools.supported`、`capabilities.reasoning.supported`、`limit.context`、
 *   `modalities.input`(有 `image/…` 即能看图),以及按协议给地址的 `api` 表(见 [ListedModel.endpoint])。
 *   用 ChatGPT 账号列出的是 `models` 表:名字在 `slug`、显示名在 `display_name`,只留 `visibility` 为 `list` 的、保持服务端的次序;
 *   给了 `input_modalities`、`context_window`、`supported_reasoning_levels` 的照填。
 * - Anthropic:`GET {base}/v1/models`,按 `after_id` 翻页;`max_input_tokens` 是上下文,
 *   `capabilities.image_input` / `capabilities.thinking` 是看图和推理。不给工具调用信息。
 * - Gemini:`GET {base}/v1beta/models`,按 `pageToken` 翻页;只留能 `generateContent` 的,
 *   `inputTokenLimit` 是上下文,`thinking` 是推理。不给工具调用和看图信息。
 */
class ModelCatalog(private val http: OkHttpClient) {
    // 各方法的 headers:每个请求都带的头(见 [ClientHeaders]);Key 的头由这里自己加
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun openAiCompat(baseUrl: String, apiKey: String?, headers: Map<String, String> = emptyMap()): ModelListResult = run {
        val url = endpoint(baseUrl, "/models") ?: return@run badUrl()
        val req = Request.Builder().url(url).get().withHeaders(headers)
            .apply { if (!apiKey.isNullOrEmpty()) header("Authorization", "Bearer $apiKey") }.build()
        val root = fetch(req).getOrElse { return@run it.asFailure() }
        val data = (root["data"] ?: root["models"]) as? JsonArray ?: return@run ModelListResult.Failed(ListFailure.BadResponse)
        ModelListResult.Ok(data.mapNotNull { (it as? JsonObject)?.let(::openAiModel) }.distinctBy { it.id })
    }

    suspend fun anthropic(baseUrl: String, apiKey: String, headers: Map<String, String> = emptyMap()): ModelListResult = run {
        val base = endpoint(baseUrl, "/v1/models") ?: return@run badUrl()
        val all = mutableListOf<ListedModel>()
        var after: String? = null
        repeat(MAX_PAGES) {
            val url = base.newBuilder().addQueryParameter("limit", "1000")
                .apply { if (after != null) addQueryParameter("after_id", after) }.build()
            val req = Request.Builder().url(url).get().withHeaders(headers)
                .header("x-api-key", apiKey).header("anthropic-version", "2023-06-01").build()
            val root = fetch(req).getOrElse { return@run it.asFailure() }
            val data = root["data"] as? JsonArray ?: return@run ModelListResult.Failed(ListFailure.BadResponse)
            all += data.mapNotNull { (it as? JsonObject)?.let(::anthropicModel) }
            after = root.str("last_id")
            if (root.bool("has_more") != true || after == null) return@run ModelListResult.Ok(all.distinctBy { it.id })
        }
        ModelListResult.Ok(all.distinctBy { it.id })
    }

    suspend fun gemini(baseUrl: String, apiKey: String, headers: Map<String, String> = emptyMap()): ModelListResult = run {
        val base = endpoint(baseUrl, "/v1beta/models") ?: return@run badUrl()
        val all = mutableListOf<ListedModel>()
        var token: String? = null
        repeat(MAX_PAGES) {
            val url = base.newBuilder().addQueryParameter("pageSize", "1000")
                .apply { if (token != null) addQueryParameter("pageToken", token) }.build()
            val req = Request.Builder().url(url).get().withHeaders(headers).header("x-goog-api-key", apiKey).build()
            val root = fetch(req).getOrElse { return@run it.asFailure() }
            val data = root["models"] as? JsonArray ?: JsonArray(emptyList())
            all += data.mapNotNull { (it as? JsonObject)?.let(::geminiModel) }
            token = root.str("nextPageToken")?.takeIf { it.isNotEmpty() }
            if (token == null) return@run ModelListResult.Ok(all.distinctBy { it.id })
        }
        ModelListResult.Ok(all.distinctBy { it.id })
    }

    private fun openAiModel(o: JsonObject): ListedModel? {
        // 用 ChatGPT 账号列模型时(`models` 表)名字在 `slug`、显示名在 `display_name`,只留 `visibility` 为 list 的
        o.str("visibility")?.let { if (it != "list") return null }
        val id = (o.str("id") ?: o.str("slug"))?.takeIf { it.isNotBlank() } ?: return null
        val arch = o["architecture"] as? JsonObject
        val inputs = (arch?.get("input_modalities") as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        val modality = arch?.str("modality")?.substringBefore("->")
        val outputs = ((arch?.get("output_modalities") ?: o.obj("modalities")?.get("output")) as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            ?: arch?.str("modality")?.takeIf { "->" in it }?.substringAfter("->")?.split('+')
        val params = (o["supported_parameters"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        val caps = o["capabilities"] as? JsonObject
        val modalIn = ((o.obj("modalities")?.get("input") ?: o["input_modalities"]) as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        val reasoningLevels = (o["supported_reasoning_levels"] ?: caps?.obj("reasoning")?.get("effort")) as? JsonArray
        val efforts = reasoningLevels?.mapNotNull { level ->
            (((level as? JsonObject)?.get("effort") ?: level) as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
                ?.trim()?.takeIf { it.isNotEmpty() }
        }?.distinct()
        val api = o.obj("api")
        // 一个模型可能写了几种协议:按 Chat Completions、Messages、generateContent、Responses 的次序取第一种会说的
        val endpoint = api?.let { a ->
            ModelProtocol.entries.firstNotNullOfOrNull { p ->
                val e = a.obj(p.wire) ?: return@firstNotNullOfOrNull null
                val url = e.str("base_url")?.takeIf { it.isNotBlank() } ?: return@firstNotNullOfOrNull null
                ModelEndpoint(p, url, e.obj("compatibility")?.str("reasoning_field"))
            }
        }
        return ListedModel(
            id = id,
            displayName = (o.str("name") ?: o.str("display_name")).orEmpty(),
            toolCalls = params?.let { "tools" in it } ?: caps?.obj("tools")?.bool("supported")
                ?: caps?.bool("function_calling") ?: caps?.bool("tool_calls"),
            vision = inputs?.let { "image" in it } ?: modalIn?.let { m -> m.any { it == "image" || it.startsWith("image/") } }
                ?: modality?.let { "image" in it } ?: o.bool("supports_image_in") ?: caps?.bool("vision"),
            contextTokens = o.int("context_length") ?: o.int("context_window") ?: o.int("max_context_length")
                ?: o.obj("limit")?.int("context"),
            reasoning = params?.let { "reasoning" in it || "include_reasoning" in it } ?: caps?.obj("reasoning")?.bool("supported")
                ?: o.bool("supports_reasoning") ?: reasoningLevels?.let { it.isNotEmpty() },
            endpoint = endpoint,
            reasoningEfforts = efforts,
            unsupportedApis = if (endpoint == null) api?.keys?.toList().orEmpty() else emptyList(),
            chat = when (o.str("type")?.lowercase()) {
                in CHAT_TYPES -> true
                in NON_CHAT_TYPES -> false
                // 只出图、出声音的不是对话模型;出文字的不另记
                else -> outputs?.takeIf { "text" !in it }?.let { false }
            },
        )
    }

    private fun anthropicModel(o: JsonObject): ListedModel? {
        val id = o.str("id")?.takeIf { it.isNotBlank() } ?: return null
        val caps = o["capabilities"] as? JsonObject
        return ListedModel(
            id = id,
            displayName = o.str("display_name").orEmpty(),
            vision = (caps?.get("image_input") as? JsonObject)?.bool("supported"),
            contextTokens = o.int("max_input_tokens"),
            reasoning = (caps?.get("thinking") as? JsonObject)?.bool("supported"),
        )
    }

    private fun geminiModel(o: JsonObject): ListedModel? {
        val id = o.str("name")?.removePrefix("models/")?.takeIf { it.isNotBlank() } ?: return null
        val methods = (o["supportedGenerationMethods"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        if (methods != null && "generateContent" !in methods) return null
        return ListedModel(
            id = id,
            displayName = o.str("displayName").orEmpty(),
            contextTokens = o.int("inputTokenLimit"),
            reasoning = o.bool("thinking"),
        )
    }

    private class Failure(val result: ModelListResult.Failed) : Exception(result.message)

    private fun Throwable.asFailure(): ModelListResult.Failed =
        (this as? Failure)?.result ?: ModelListResult.Failed(ListFailure.Network)

    private suspend fun fetch(req: Request): Result<JsonObject> {
        val raw = try {
            HttpSupport.execute(http, req)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return Result.failure(Failure(ModelListResult.Failed(ListFailure.Network)))
        }
        if (raw.status !in 200..299) return Result.failure(Failure(failureFor(raw.status, raw.body)))
        val root = try { json.parseToJsonElement(raw.body).jsonObject } catch (e: Exception) { null }
            ?: return Result.failure(Failure(ModelListResult.Failed(ListFailure.BadResponse)))
        return Result.success(root)
    }

    private fun failureFor(status: Int, body: String): ModelListResult.Failed = when {
        status == 401 || status == 403 -> ModelListResult.Failed(ListFailure.Auth)
        // Gemini 对无效 Key 回 400 INVALID_ARGUMENT,原因写在内容里
        status == 400 && (body.contains("API_KEY_INVALID") || body.contains("API key not valid")) -> ModelListResult.Failed(ListFailure.Auth)
        status == 402 || body.contains("insufficient_quota") -> ModelListResult.Failed(ListFailure.Quota)
        status == 429 -> ModelListResult.Failed(ListFailure.RateLimited)
        status == 404 || status == 405 || status == 501 -> ModelListResult.Failed(ListFailure.NotSupported)
        status in 500..599 -> ModelListResult.Failed(ListFailure.Server, "HTTP $status")
        else -> ModelListResult.Failed(ListFailure.BadResponse, "HTTP $status")
    }

    private fun badUrl() = ModelListResult.Failed(ListFailure.BadUrl)

    private fun endpoint(baseUrl: String, path: String) = (baseUrl.trim().trimEnd('/') + path).toHttpUrlOrNull()

    private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
    private fun JsonObject.obj(k: String): JsonObject? = this[k] as? JsonObject
    private fun JsonObject.bool(k: String): Boolean? = (this[k] as? JsonPrimitive)?.booleanOrNull
    private fun JsonObject.int(k: String): Int? = (this[k] as? JsonPrimitive)?.intOrNull?.takeIf { it > 0 }

    private companion object {
        /** 翻页上限:防止服务端一直给下一页。 */
        const val MAX_PAGES = 10
        /** 列表里 `type` 一项的写法:是对话模型的,和不是的(向量、重排、画图、语音、审核)。 */
        val CHAT_TYPES = setOf("chat", "llm", "language", "text", "vlm", "code")
        val NON_CHAT_TYPES = setOf(
            "embedding", "embeddings", "rerank", "reranker", "image", "images", "video", "audio", "tts", "stt", "asr",
            "speech", "transcription", "moderation",
        )
    }
}
