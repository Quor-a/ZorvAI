package com.ai.assistance.quro.core.bot.adapters

import android.util.Log
import org.json.JSONObject
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import android.util.Base64
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import android.content.SharedPreferences

/**
 * iLink API 异常。
 */
class ILinkApiException(
    message: String,
    val statusCode: Int? = null,
    cause: Throwable? = null
) : IOException(message, cause)

/**
 * iLink Bot API 的 HTTP 客户端（对齐 weixin_clawbot ILinkClient）。
 *
 * 职责：1. 构建请求头 2. 序列化请求体 3. 发送 HTTP 请求 4. 解析响应
 */
class ILinkClient(
    private var baseUrl: String = DEFAULT_BASE_URL,
    private val token: String = "",
    private val client: OkHttpClient? = null
) {
    companion object {
        private const val TAG = "ILinkClient"
        private const val DEFAULT_BASE_URL = "https://ilinkai.weixin.qq.com"
        private const val CHANNEL_VERSION = "1.0.2"

        // 已知错误码
        private val KNOWN_ERRORS = mapOf(
            -2 to "rate limited, try again later",
            -14 to "session expired, re-login via openclaw"
        )
    }

    private val httpClient = client ?: OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS) // 长轮询需要更长超时
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    private val JSON_MEDIA_TYPE = "application/json".toMediaType()

    // ==================== QR 登录 ====================

    /**
     * 获取登录二维码（对齐 weixin_clawbot fetchLoginQrCode）。
     * GET /ilink/bot/get_bot_qrcode?bot_type=3
     */
    fun fetchLoginQrCode(): QrCodeResponse {
        val url = "$baseUrl/ilink/bot/get_bot_qrcode?bot_type=3"
        val request = Request.Builder()
            .url(url)
            .get()
            .apply {
                // 二维码请求不需要认证头，只需要基本头
                addHeader("iLink-App-ClientVersion", "1")
            }
            .build()

        val response = httpClient.newCall(request).execute()
        assertHttpOk(response, "get_bot_qrcode")
        val json = JSONObject(response.body?.string() ?: "{}")
        return QrCodeResponse.fromJson(json)
    }

    /**
     * 轮询二维码状态（对齐 weixin_clawbot pollQrStatus）。
     * GET /ilink/bot/get_qrcode_status?qrcode={token}
     */
    fun pollQrStatus(qrCode: String): QrStatusResponse {
        val encoded = java.net.URLEncoder.encode(qrCode, "UTF-8")
        val url = "$baseUrl/ilink/bot/get_qrcode_status?qrcode=$encoded"
        val request = Request.Builder()
            .url(url)
            .get()
            .addHeader("iLink-App-ClientVersion", "1")
            .build()

        val response = httpClient.newCall(request).execute()
        assertHttpOk(response, "get_qrcode_status")
        val json = JSONObject(response.body?.string() ?: "{}")
        return QrStatusResponse.fromJson(json)
    }

    // ==================== 消息收发 ====================

    /**
     * 长轮询获取消息（对齐 weixin_clawbot getUpdates）。
     * POST /ilink/bot/getupdates
     */
    fun getUpdates(
        getUpdatesBuf: String,
        timeoutSeconds: Int = 35
    ): GetUpdatesResponse {
        val body = JSONObject().apply {
            put("get_updates_buf", getUpdatesBuf)
            put("base_info", JSONObject().apply {
                put("channel_version", CHANNEL_VERSION)
            })
        }.toString()

        val requestBody = body.toRequestBody(JSON_MEDIA_TYPE)
        val request = Request.Builder()
            .url("$baseUrl/ilink/bot/getupdates")
            .post(requestBody)
            .apply {
                buildHeaders(body).forEach { (k, v) -> addHeader(k, v) }
            }
            .build()

        // 长轮询需要更长的超时时间
        val longPollClient = httpClient.newBuilder()
            .readTimeout(timeoutSeconds + 10L, TimeUnit.SECONDS)
            .build()

        val response = longPollClient.newCall(request).execute()
        assertHttpOk(response, "getupdates")
        val json = JSONObject(response.body?.string() ?: "{}")
        return GetUpdatesResponse.fromJson(json)
    }

    /**
     * 发送文本消息（对齐 weixin_clawbot sendText）。
     * POST /ilink/bot/sendmessage
     */
    fun sendText(
        toUserId: String,
        text: String,
        botId: String = "",
        contextToken: String? = null
    ): SendResult {
        val clientId = generateClientId()
        val body = JSONObject().apply {
            put("msg", JSONObject().apply {
                put("from_user_id", botId)
                put("to_user_id", toUserId)
                put("client_id", clientId)
                put("message_type", 2) // BOT
                put("message_state", 2) // FINISH
                if (!contextToken.isNullOrBlank()) {
                    put("context_token", contextToken)
                }
                put("item_list", org.json.JSONArray().apply {
                    put(JSONObject().apply {
                        put("type", 1) // text
                        put("text_item", JSONObject().apply {
                            put("text", text)
                        })
                    })
                })
            })
            put("base_info", JSONObject().apply {
                put("channel_version", CHANNEL_VERSION)
            })
        }.toString()

        val requestBody = body.toRequestBody(JSON_MEDIA_TYPE)
        val request = Request.Builder()
            .url("$baseUrl/ilink/bot/sendmessage")
            .post(requestBody)
            .apply {
                buildHeaders(body).forEach { (k, v) -> addHeader(k, v) }
            }
            .build()

        val response = httpClient.newCall(request).execute()
        assertHttpOk(response, "sendmessage")
        val json = JSONObject(response.body?.string() ?: "{}")
        val ret = json.optInt("ret", -1)

        return if (ret != 0) {
            val hint = KNOWN_ERRORS[ret] ?: ""
            SendResult(
                ok = false,
                to = toUserId,
                clientId = clientId,
                error = "ret=$ret${if (hint.isNotEmpty()) " ($hint)" else ""}"
            )
        } else {
            SendResult(ok = true, to = toUserId, clientId = clientId)
        }
    }

    // ==================== 内部方法 ====================

    /**
     * 构建鉴权头（对齐 weixin_clawbot _buildHeaders）。
     */
    private fun buildHeaders(body: String): Map<String, String> {
        val headers = mutableMapOf(
            "Content-Type" to "application/json",
            "AuthorizationType" to "ilink_bot_token",
            "Content-Length" to body.toByteArray().size.toString(),
            "X-WECHAT-UIN" to randomWechatUin()
        )
        if (token.isNotEmpty()) {
            headers["Authorization"] = "Bearer $token"
        }
        return headers
    }

    /**
     * 生成随机 WeChat UIN：随机 uint32 → 十进制字符串 → base64（对齐 weixin_clawbot）。
     */
    private fun randomWechatUin(): String {
        val bytes = ByteArray(4)
        SecureRandom().nextBytes(bytes)
        val n = ((bytes[0].toLong() and 0xFF) shl 24) or
                ((bytes[1].toLong() and 0xFF) shl 16) or
                ((bytes[2].toLong() and 0xFF) shl 8) or
                (bytes[3].toLong() and 0xFF)
        return Base64.encodeToString(n.toString().toByteArray(), Base64.NO_WRAP)
    }

    /**
     * 生成 client_id（对齐 weixin_clawbot）。
     * 格式：android-{timestamp}
     */
    private fun generateClientId(): String = "android-${System.currentTimeMillis()}"

    /**
     * 断言 HTTP 响应状态码为 2xx，否则抛出 ILinkApiException。
     */
    private fun assertHttpOk(response: Response, endpoint: String) {
        if (!response.isSuccessful) {
            val body = response.body?.string()?.take(200) ?: "(empty)"
            throw ILinkApiException(
                "$endpoint HTTP ${response.code}: $body",
                statusCode = response.code
            )
        }
    }

    /**
     * 释放资源。
     */
    fun dispose() {
        httpClient.dispatcher.executorService.shutdown()
        httpClient.connectionPool.evictAll()
    }
}

// ==================== 数据模型 ====================

/**
 * 二维码响应（对齐 weixin_clawbot QrCodeResponse）。
 */
data class QrCodeResponse(
    val qrCode: String,
    val qrCodeImgContent: String
) {
    companion object {
        fun fromJson(json: JSONObject): QrCodeResponse {
            return QrCodeResponse(
                qrCode = json.optString("qrcode", ""),
                qrCodeImgContent = json.optString("qrcode_img_content", "")
            )
        }
    }
}

/**
 * 二维码登录状态枚举（对齐 weixin_clawbot QrLoginStatus）。
 *
 * ⚠️ 唯一权威定义：微信 iLink 适配器与设置页 UI 都引用此顶层枚举，
 * 切勿在 QuroWechatIlinkBotAdapter 内再嵌套同名枚举，否则 UI 的 when 永远
 * 匹配不到适配器实际状态（类型不一致 → 扫码区整块不渲染）。
 */
enum class QrLoginStatus {
    WAIT,
    SCANNED,
    CONFIRMED,
    DENIED,
    EXPIRED,
    UNKNOWN
}

/**
 * 二维码状态响应（对齐 weixin_clawbot QrStatusResponse）。
 */
data class QrStatusResponse(
    val status: QrLoginStatus,
    val botToken: String? = null,
    val ilinkBotId: String? = null,
    val ilinkUserId: String? = null,
    val baseUrl: String? = null
) {
    companion object {
        fun fromJson(json: JSONObject): QrStatusResponse {
            val rawStatus = json.optString("status", "")
            val status = when (rawStatus.lowercase()) {
                "wait" -> QrLoginStatus.WAIT
                "scaned" -> QrLoginStatus.SCANNED
                "confirmed" -> QrLoginStatus.CONFIRMED
                "expired" -> QrLoginStatus.EXPIRED
                else -> QrLoginStatus.UNKNOWN
            }
            return QrStatusResponse(
                status = status,
                botToken = json.optString("bot_token", ""),
                ilinkBotId = json.optString("ilink_bot_id", ""),
                ilinkUserId = json.optString("ilink_user_id", ""),
                baseUrl = json.optString("baseurl", "")
            )
        }
    }
}

/**
 * 长轮询响应（对齐 weixin_clawbot GetUpdatesResponse）。
 */
data class GetUpdatesResponse(
    val ret: Int,
    val errCode: Int,
    val errMsg: String,
    val messages: List<WeixinMessage>,
    val getUpdatesBuf: String,
    val longPollingTimeoutMs: Int
) {
    val isOk: Boolean get() = ret == 0 && errCode == 0

    companion object {
        fun fromJson(json: JSONObject): GetUpdatesResponse {
            val rawMsgs = json.optJSONArray("msgs") ?: org.json.JSONArray()
            val messages = mutableListOf<WeixinMessage>()
            for (i in 0 until rawMsgs.length()) {
                val msgJson = rawMsgs.optJSONObject(i) ?: continue
                messages.add(WeixinMessage.fromJson(msgJson))
            }
            return GetUpdatesResponse(
                ret = json.optInt("ret", 0),
                errCode = json.optInt("errcode", 0),
                errMsg = json.optString("errmsg", ""),
                messages = messages,
                getUpdatesBuf = json.optString("get_updates_buf", ""),
                longPollingTimeoutMs = json.optInt("longpolling_timeout_ms", 0)
            )
        }
    }
}

/**
 * 消息项类型枚举（对齐 weixin_clawbot MessageItemType）。
 */
enum class MessageItemType(val value: Int) {
    TEXT(1),
    IMAGE(2),
    VOICE(3),
    FILE(4),
    VIDEO(5),
    UNKNOWN(0);

    companion object {
        fun fromInt(v: Int): MessageItemType {
            return values().firstOrNull { it.value == v } ?: UNKNOWN
        }
    }
}

/**
 * 消息项（对齐 weixin_clawbot MessageItem）。
 */
data class MessageItem(
    val type: MessageItemType,
    val text: String? = null,
    val voiceText: String? = null,
    val raw: Map<String, Any?>? = null
) {
    companion object {
        fun fromJson(json: JSONObject): MessageItem {
            val type = MessageItemType.fromInt(json.optInt("type", 0))
            return MessageItem(
                type = type,
                text = json.optJSONObject("text_item")?.optString("text"),
                voiceText = json.optJSONObject("voice_item")?.optString("text"),
                raw = json.keys().asSequence().associateWith { json.opt(it) }
            )
        }
    }
}

/**
 * 微信消息（对齐 weixin_clawbot WeixinMessage）。
 */
data class WeixinMessage(
    val seq: Int,
    val messageId: Int,
    val fromUserId: String,
    val toUserId: String,
    val clientId: String,
    val createTimeMs: Long,
    val messageType: Int,
    val messageState: Int,
    val contextToken: String?,
    val items: List<MessageItem>
) {
    val isFromUser: Boolean get() = messageType == 1

    /**
     * 获取第一个文本内容（对齐 weixin_clawbot textContent getter）。
     */
    val textContent: String?
        get() {
            for (item in items) {
                when (item.type) {
                    MessageItemType.TEXT -> item.text?.let { return it }
                    MessageItemType.VOICE -> item.voiceText?.let { return it }
                    else -> continue
                }
            }
            return null
        }

    companion object {
        fun fromJson(json: JSONObject): WeixinMessage {
            val rawItems = json.optJSONArray("item_list") ?: org.json.JSONArray()
            val items = mutableListOf<MessageItem>()
            for (i in 0 until rawItems.length()) {
                val itemJson = rawItems.optJSONObject(i) ?: continue
                items.add(MessageItem.fromJson(itemJson))
            }
            return WeixinMessage(
                seq = json.optInt("seq", 0),
                messageId = json.optInt("message_id", 0),
                fromUserId = json.optString("from_user_id", ""),
                toUserId = json.optString("to_user_id", ""),
                clientId = json.optString("client_id", ""),
                createTimeMs = json.optLong("create_time_ms", 0),
                messageType = json.optInt("message_type", 0),
                messageState = json.optInt("message_state", 0),
                contextToken = json.optString("context_token", "").ifBlank { null },
                items = items
            )
        }
    }
}

/**
 * 发送结果（对齐 weixin_clawbot SendResult）。
 */
data class SendResult(
    val ok: Boolean,
    val to: String,
    val clientId: String,
    val error: String? = null
) {
    override fun toString(): String {
        return if (ok) "SendResult(ok, to=$to)" else "SendResult(error=$error)"
    }
}