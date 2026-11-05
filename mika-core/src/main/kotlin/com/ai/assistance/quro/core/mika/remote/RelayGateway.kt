package com.ai.assistance.quro.core.mika.remote

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** 远程网关仅接受 HTTPS 源地址。HTTP 回环只供 JVM 集成测试显式打开,产品没有降级开关。 */
class RelayGateway(val url: HttpUrl, private val http: OkHttpClient) {
    class Failure(val unauthorized: Boolean = false) : IOException("Remote gateway request failed")

    suspend fun pair(code: String): RelayPairing {
        require(code.length in 8..64)
        val request = Request.Builder().url(url.newBuilder().encodedPath("/pair").build())
            .post(Json.encodeToString(PairRequest(code)).toRequestBody("application/json".toMediaType())).build()
        val result = exchange(request)
        val paired = runCatching { Json.decodeFromString<PairResponse>(result) }.getOrElse { throw Failure() }
        if (!Regex("[A-Za-z0-9_-]{43}").matches(paired.deviceToken) || paired.mcpUrl != url.newBuilder().encodedPath("/mcp").build().toString()) throw Failure()
        return RelayPairing(url.toString(), paired.deviceToken)
    }

    suspend fun unpair(deviceToken: String) {
        exchange(Request.Builder().url(url.newBuilder().encodedPath("/device").build())
            .header("Authorization", "Bearer $deviceToken").delete().build(), acceptRevoked = true)
    }

    private suspend fun exchange(request: Request, acceptRevoked: Boolean = false): String = suspendCancellableCoroutine { cont ->
        val call = http.newCall(request)
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { if (cont.isActive) cont.resumeWithException(Failure()) }
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    try {
                        if (acceptRevoked && it.code == 401) { if (cont.isActive) cont.resume(""); return }
                        if (!it.isSuccessful || (it.body?.contentLength() ?: 0) > 16_384) throw Failure(it.code in listOf(401, 403))
                        val source = it.body?.source()
                        if (source?.request(16_385) == true) throw Failure()
                        val body = source?.readUtf8().orEmpty()
                        if (body.length > 16_384) throw Failure()
                        if (cont.isActive) cont.resume(body)
                    } catch (e: Exception) { if (cont.isActive) cont.resumeWithException(if (e is Failure) e else Failure()) }
                }
            }
        })
    }

    companion object {
        fun parse(raw: String, allowLoopbackHttp: Boolean = false): HttpUrl {
            val u = raw.trim().toHttpUrl()
            require(u.username.isEmpty() && u.password.isEmpty() && u.query == null && u.fragment == null && u.encodedPath == "/") { "Use an HTTPS gateway origin" }
            require(u.isHttps || allowLoopbackHttp && u.host in setOf("127.0.0.1", "::1") && u.scheme == "http") { "Use HTTPS" }
            return u
        }

        fun http(): OkHttpClient = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
            .callTimeout(java.time.Duration.ofSeconds(20)).build()
    }
}

/** 不用 data class,避免生成带凭据的 toString。整个配置只经手机的加密存储保留。 */
class RelayPairing(val gateway: String, val deviceToken: String)

@Serializable private data class PairRequest(val code: String)
@Serializable private data class PairResponse(val deviceToken: String, val mcpUrl: String)
