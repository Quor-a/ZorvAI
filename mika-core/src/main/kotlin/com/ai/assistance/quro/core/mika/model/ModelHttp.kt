package com.ai.assistance.quro.core.mika.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 对话适配器以外也要照同一套说法报错的地方(比如语音转文字)用:发一个请求,把 HTTP 状态和报错内容转成 [ModelError]。
 * 映射本身从各适配器原样搬来,适配器改成调这里,避免两份各改各的。
 */
object ModelHttp {
    class Reply(val status: Int, val retryAfter: String?, val body: String) {
        val ok: Boolean get() = status in 200..299
    }

    /** 发出请求并读完回复;网络失败抛 [ModelError.Network],取消协程会取消请求。 */
    suspend fun send(http: OkHttpClient, request: Request): Reply =
        HttpSupport.execute(http, request).let { Reply(it.status, it.retryAfter, it.body) }

    /** OpenAI 兼容接口(对话、转写同一套)的错误映射。[model] 是请求的模型名。 */
    fun openAiError(status: Int, retryAfter: String?, body: String, model: String): ModelError =
        ModelAccessErrors.of(status, body, model) ?: when {
            status == 401 -> ModelError.Auth()
            status == 402 || body.contains("insufficient_quota") -> ModelError.QuotaExceeded()
            status == 429 -> ModelError.RateLimited(HttpSupport.retryAfterMs(retryAfter))
            status == 400 && (body.contains("context_length") || body.contains("maximum context")) -> ModelError.ContextTooLong()
            status == 404 -> ModelError.ModelNotFound(model, ModelAccessErrors.jsonDetail(body))
            status in 500..599 -> ModelError.Server(status)
            status == 403 -> ModelError.Auth()
            else -> ModelError.BadResponse("HTTP $status ${body.take(200)}")
        }

    private val json = Json { ignoreUnknownKeys = true }

    /** Gemini 原生接口的错误映射。 */
    fun geminiError(status: Int, retryAfter: String?, body: String, model: String): ModelError {
        val err = try { json.parseToJsonElement(body).jsonObject["error"] as? JsonObject } catch (e: Exception) { null }
        val st = (err?.get("status") as? JsonPrimitive)?.contentOrNull.orEmpty()
        val msg = (err?.get("message") as? JsonPrimitive)?.contentOrNull ?: body
        val lower = msg.lowercase()
        ModelAccessErrors.of(status, body, model)?.let { return it }
        return when {
            status == 401 || status == 403 -> ModelError.Auth()
            // 这个模型额度为零的 429 上面已认成用不了;其余 429 按可重试限流处理(其限流与额度文案混用,无法可靠区分)
            status == 429 || st == "RESOURCE_EXHAUSTED" -> ModelError.RateLimited(HttpSupport.retryAfterMs(retryAfter))
            status == 404 -> ModelError.ModelNotFound(model, ModelAccessErrors.jsonDetail(body))
            status == 413 || (status == 400 && lower.contains("token") &&
                (lower.contains("exceed") || lower.contains("too long") || lower.contains("maximum"))) -> ModelError.ContextTooLong()
            status in 500..599 -> ModelError.Server(status)
            else -> ModelError.BadResponse("HTTP $status ${body.take(200)}")
        }
    }
}
