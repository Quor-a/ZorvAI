package com.ai.assistance.quro.core.bot.adapters

import android.util.Log
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import android.util.Base64
import java.util.concurrent.TimeUnit

/**
 * iLink HTTP 客户端（移植自 Andclaw ClawBotApiClient）。
 * 纯 OkHttp + org.json，零外部依赖。
 */
class ClawBotApiClient(
    private val httpClient: OkHttpClient = OkHttpClient(),
    private val channelVersion: String = "1.0.2"
) {
    companion object {
        private const val TAG = "ClawBotApiClient"
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }

    fun buildHeaders(botToken: String?): okhttp3.Headers {
        val b = okhttp3.Headers.Builder()
        b.add("Content-Type", "application/json")
        b.add("AuthorizationType", "ilink_bot_token")
        b.add("X-WECHAT-UIN", randomWechatUin())
        b.add("iLink-App-ClientVersion", "1")
        if (!botToken.isNullOrBlank()) {
            b.add("Authorization", "Bearer ${botToken.trim()}")
        }
        return b.build()
    }

    /**
     * GET ilink/bot/get_bot_qrcode
     */
    fun getBotQrcode(baseUrl: String, botType: String, botToken: String? = null): JSONObject {
        val url = normalizeBaseUrl(baseUrl).newBuilder()
            .addPathSegments("ilink/bot/get_bot_qrcode")
            .addQueryParameter("bot_type", botType)
            .build()
        val req = Request.Builder()
            .url(url)
            .headers(buildHeaders(botToken))
            .get()
            .build()
        return httpClient.newCall(req).execute().use { resp ->
            val body = resp.body?.string() ?: ""
            if (!resp.isSuccessful) {
                throw ClawBotHttpException(resp.code, body)
            }
            Log.d(TAG, "getBotQrcode 响应: ${body.take(200)}")
            JSONObject(body)
        }
    }

    /**
     * GET ilink/bot/get_qrcode_status
     */
    fun getQrcodeStatus(baseUrl: String, qrcode: String, botToken: String? = null): JSONObject {
        val encoded = URLEncoder.encode(qrcode, StandardCharsets.UTF_8.name())
        val url = normalizeBaseUrl(baseUrl).newBuilder()
            .addPathSegments("ilink/bot/get_qrcode_status")
            .encodedQuery("qrcode=$encoded")
            .build()
        val req = Request.Builder()
            .url(url)
            .headers(buildHeaders(botToken))
            .get()
            .build()
        return httpClient.newCall(req).execute().use { resp ->
            val body = resp.body?.string() ?: ""
            if (!resp.isSuccessful) {
                throw ClawBotHttpException(resp.code, body)
            }
            Log.d(TAG, "getQrcodeStatus 响应: ${body.take(200)}")
            JSONObject(body)
        }
    }

    /**
     * POST ilink/bot/getupdates（长轮询）。
     */
    fun postGetUpdates(
        baseUrl: String,
        getUpdatesBuf: String,
        botToken: String?,
        timeoutMs: Long = 38_000L
    ): String {
        val effectiveTimeout = timeoutMs.coerceAtLeast(5_000L)
        val longPollClient = httpClient.newBuilder()
            .callTimeout(effectiveTimeout, TimeUnit.MILLISECONDS)
            .readTimeout(effectiveTimeout, TimeUnit.MILLISECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .connectTimeout(15, TimeUnit.SECONDS)
            .build()
        val url = normalizeBaseUrl(baseUrl).newBuilder()
            .addPathSegments("ilink/bot/getupdates")
            .build()
        val payload = """{"get_updates_buf":${jsonString(getUpdatesBuf)},"base_info":{"channel_version":${jsonString(channelVersion)}}}"""
        val req = Request.Builder()
            .url(url)
            .headers(buildHeaders(botToken))
            .post(payload.toRequestBody(JSON_MEDIA))
            .build()
        return try {
            longPollClient.newCall(req).execute().use { resp ->
                val body = resp.body?.string() ?: ""
                if (!resp.isSuccessful) {
                    throw ClawBotHttpException(resp.code, body)
                }
                body
            }
        } catch (e: IOException) {
            if (e is SocketTimeoutException || e is InterruptedIOException) {
                emptyGetUpdatesJson(getUpdatesBuf)
            } else {
                throw e
            }
        }
    }

    /**
     * POST ilink/bot/sendmessage（发送文本消息）。
     */
    fun postSendMessage(
        baseUrl: String,
        botToken: String?,
        toUserId: String,
        text: String,
        contextToken: String
    ) {
        val clientId = "quro-" + java.util.UUID.randomUUID().toString().replace("-", "")
        val payload = buildString {
            append("""{"msg":{""")
            append(""""from_user_id":"","to_user_id":${jsonString(toUserId)},""")
            append(""""client_id":${jsonString(clientId)},"message_type":2,"message_state":2,""")
            append(""""context_token":${jsonString(contextToken)},""")
            append(""""item_list":[{"type":1,"text_item":{"text":${jsonString(text)}}}]""")
            append("""},"base_info":{"channel_version":${jsonString(channelVersion)}}}""")
        }
        postShort(baseUrl, "ilink/bot/sendmessage", payload, botToken)
    }

    /**
     * POST ilink/bot/getconfig
     */
    fun postGetConfig(baseUrl: String, botToken: String?, ilinkUserId: String, contextToken: String?): String {
        val ctxPart = if (contextToken.isNullOrBlank()) "" else ""","context_token":${jsonString(contextToken)}"""
        val payload = """{"ilink_user_id":${jsonString(ilinkUserId)}$ctxPart,"base_info":{"channel_version":${jsonString(channelVersion)}}}"""
        return postShortWithBody(baseUrl, "ilink/bot/getconfig", payload, botToken)
    }

    /**
     * POST ilink/bot/sendtyping
     */
    fun postSendTyping(
        baseUrl: String,
        botToken: String?,
        ilinkUserId: String,
        typingTicket: String,
        typingStatus: Int = 1
    ) {
        val payload = """{"ilink_user_id":${jsonString(ilinkUserId)},"typing_ticket":${jsonString(typingTicket)},"status":$typingStatus,"base_info":{"channel_version":${jsonString(channelVersion)}}}"""
        postShort(baseUrl, "ilink/bot/sendtyping", payload, botToken)
    }

    private fun postShort(baseUrl: String, pathSegments: String, jsonBody: String, botToken: String?) {
        postShortWithBody(baseUrl, pathSegments, jsonBody, botToken)
    }

    private fun postShortWithBody(
        baseUrl: String,
        pathSegments: String,
        jsonBody: String,
        botToken: String?
    ): String {
        val url = normalizeBaseUrl(baseUrl).newBuilder()
            .addPathSegments(pathSegments)
            .build()
        val shortClient = httpClient.newBuilder()
            .callTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .connectTimeout(15, TimeUnit.SECONDS)
            .build()
        val req = Request.Builder()
            .url(url)
            .headers(buildHeaders(botToken))
            .post(jsonBody.toRequestBody(JSON_MEDIA))
            .build()
        return shortClient.newCall(req).execute().use { resp ->
            val body = resp.body?.string() ?: ""
            if (!resp.isSuccessful) {
                throw ClawBotHttpException(resp.code, body)
            }
            body
        }
    }

    private fun normalizeBaseUrl(baseUrl: String): okhttp3.HttpUrl {
        val trimmed = baseUrl.trim().trimEnd('/')
        return try {
            trimmed.toHttpUrl()
        } catch (_: IllegalArgumentException) {
            throw IllegalArgumentException("invalid baseUrl: $baseUrl")
        }
    }

    private fun jsonString(s: String): String =
        buildString {
            append('"')
            for (c in s) {
                when (c) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> append(c)
                }
            }
            append('"')
        }

    private fun emptyGetUpdatesJson(getUpdatesBuf: String): String =
        """{"ret":0,"get_updates_buf":${jsonString(getUpdatesBuf)},"msgs":[]}"""
}

class ClawBotHttpException(val code: Int, val body: String) : RuntimeException("HTTP $code: $body")

private fun randomWechatUin(): String {
    val buf = ByteArray(4)
    SecureRandom().nextBytes(buf)
    val u = ((buf[0].toInt() and 0xff) shl 24) or
        ((buf[1].toInt() and 0xff) shl 16) or
        ((buf[2].toInt() and 0xff) shl 8) or
        (buf[3].toInt() and 0xff)
    val uLong = u.toLong() and 0xffffffffL
    return Base64.encodeToString(uLong.toString().toByteArray(StandardCharsets.UTF_8), Base64.NO_WRAP)
}
