package com.ai.assistance.quro.core.mika.oauth

import com.ai.assistance.quro.core.mika.model.HttpSupport
import com.ai.assistance.quro.core.mika.model.ModelError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** 一次 OpenRouter 授权的随机值:每次新生成。[callbackUrl] 是这次本机监听的回调地址。 */
data class OpenRouterAttempt(val state: String, val verifier: String, val callbackUrl: String) {
    val challenge: String get() = Pkce.challenge(verifier)
}

/**
 * 用 OpenRouter 账号登录:OpenRouter 官方给第三方应用的 OAuth PKCE。在浏览器里打开授权页,用户同意后带着 `code`
 * 回到本机回调,再用 `code` 和校验串换一把属于这个用户的 API Key。换到的就是普通的 OpenRouter Key,
 * 之后和填 Key 的 OpenRouter 服务商一样用(列模型、调用都走 [API_BASE])。
 * Key 不进日志;异常文字里只有类别和错误代码。
 */
class OpenRouterAuth(
    private val http: OkHttpClient,
    private val authUrl: String = AUTH_URL,
    private val keysUrl: String = KEYS_URL,
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun newAttempt(callbackUrl: String) = OpenRouterAttempt(Pkce.state(), Pkce.verifier(), callbackUrl)

    /** 授权页地址:回调地址、S256 校验、Key 的名字(预填,用户可改)、state。 */
    fun authorizeUrl(attempt: OpenRouterAttempt): String = authUrl.toHttpUrl().newBuilder()
        .addQueryParameter("callback_url", attempt.callbackUrl)
        .addQueryParameter("code_challenge", attempt.challenge)
        .addQueryParameter("code_challenge_method", "S256")
        .addQueryParameter("key_label", KEY_LABEL)
        .addQueryParameter("state", attempt.state)
        .build().toString()

    /**
     * 本机回调收不收这一次:state 对得上的收;没带 state、没带 code、带了 `error` 的(用户在授权页拒绝时 OpenRouter 不带 state)也收,
     * 当作没同意。别的(带 code 却没有或对不上 state,或什么都没带)不收:不是这次发起的,回 400 接着等。
     */
    fun accepts(params: Map<String, String>, attempt: OpenRouterAttempt): Boolean {
        val state = params["state"]
        if (state != null) return state == attempt.state
        return params["code"].isNullOrEmpty() && !params["error"].isNullOrEmpty()
    }

    /** 从回调里取出 `code`;没有就是用户没同意(或授权页报了错)。 */
    fun readCallback(params: Map<String, String>, attempt: OpenRouterAttempt): String {
        val state = params["state"]
        if (state != null && state != attempt.state) throw AccountSignInException(AccountSignInFailure.Rejected, "state")
        val code = params["code"]?.takeIf { it.isNotEmpty() }
            ?: throw AccountSignInException(AccountSignInFailure.Denied, params["error"]?.take(40) ?: "no_code")
        if (state == null) throw AccountSignInException(AccountSignInFailure.Rejected, "state")
        return code
    }

    /**
     * 用 `code` 和校验串换 Key(`POST /api/v1/auth/keys`)。400 是校验方式对不上,403 是 code 或校验串不对、或 code 过期(10 分钟)。
     * 出错时只带回状态码,不带响应正文。
     */
    suspend fun exchange(code: String, attempt: OpenRouterAttempt): String {
        val body = buildJsonObject {
            put("code", code)
            put("code_verifier", attempt.verifier)
            put("code_challenge_method", "S256")
        }.toString().toRequestBody(JSON_TYPE)
        val raw = try {
            HttpSupport.execute(http, Request.Builder().url(keysUrl).header("Accept", "application/json").post(body).build())
        } catch (e: CancellationException) {
            throw e
        } catch (e: ModelError) {
            throw AccountSignInException(AccountSignInFailure.Network, "http", e)
        }
        when {
            raw.status == 403 -> throw AccountSignInException(AccountSignInFailure.Expired, "http_403")
            raw.status == 429 || raw.status in 500..599 -> throw AccountSignInException(AccountSignInFailure.Network, "http_${raw.status}")
            raw.status != 200 -> throw AccountSignInException(AccountSignInFailure.Rejected, "http_${raw.status}")
        }
        val root = runCatching { json.parseToJsonElement(raw.body) as? JsonObject }.getOrNull()
        return (root?.get("key") as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: throw AccountSignInException(AccountSignInFailure.Rejected, "no_key")
    }

    companion object {
        const val AUTH_URL = "https://openrouter.ai/auth"
        const val KEYS_URL = "https://openrouter.ai/api/v1/auth/keys"
        /** 换到的 Key 调用的接口根,和 OpenRouter 预设一样。 */
        const val API_BASE = "https://openrouter.ai/api/v1"
        /** 用户在 OpenRouter 上管理(删除)Key 的页面。 */
        const val KEYS_PAGE = "https://openrouter.ai/settings/keys"
        /** 授权页上预填的 Key 名字,用户在 Keys 页上靠它认出是哪一把。 */
        const val KEY_LABEL = "ZorvAI"
        /** 回调地址用 localhost(OpenRouter 写明本机回调任意端口都可以);监听只绑 127.0.0.1。 */
        const val CALLBACK_HOST = "localhost"
        private val JSON_TYPE = "application/json".toMediaType()
    }
}

/**
 * 走一遍 OpenRouter 登录:开本机回调 → [openBrowser] 打开授权页 → 等回调 → 换 Key。
 * 换 Key 这一步(至多 [finishTimeoutMs])做完才把结果网页回给浏览器:[page] 收到 null 是成功,否则是失败的原因。
 * 失败抛 [AccountSignInException];取消照常往外抛。返回换到的 API Key。
 */
suspend fun signInWithOpenRouter(
    auth: OpenRouterAuth,
    openBrowser: (String) -> Unit,
    page: (AccountSignInException?) -> String,
    timeoutMs: Long = 5 * 60_000L,
    finishTimeoutMs: Long = 20_000L,
): String {
    var outcome: Result<String>? = null
    LoopbackCallback.open().use { cb ->
        val attempt = auth.newAttempt(cb.redirectUri(OpenRouterAuth.CALLBACK_HOST))
        openBrowser(auth.authorizeUrl(attempt))
        cb.await(timeoutMs, { auth.accepts(it, attempt) }) { params ->
            val r = try {
                Result.success(withTimeout(finishTimeoutMs) { auth.exchange(auth.readCallback(params, attempt), attempt) })
            } catch (e: TimeoutCancellationException) {
                Result.failure(AccountSignInException(AccountSignInFailure.Network, "timeout", e))
            } catch (e: CancellationException) {
                throw e
            } catch (e: AccountSignInException) {
                Result.failure(e)
            } catch (e: Exception) {
                Result.failure(AccountSignInException(AccountSignInFailure.Network, cause = e))
            }
            outcome = r
            page(r.exceptionOrNull() as AccountSignInException?)
        } ?: throw AccountSignInException(AccountSignInFailure.TimedOut)
    }
    return outcome!!.getOrThrow()
}
