package com.ai.assistance.quro.core.bot.adapters

import android.content.Context
import com.ai.assistance.quro.core.bot.QuroBotPlatform
import com.ai.assistance.quro.core.bot.QuroOutboundMessage
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.security.SecureRandom
import android.util.Base64

/**
 * 微信 iLink 个人号 Bot 适配器（直连官方域名，零公网端点）。
 *
 * 协议来源：反编译 Bothub APK（app-web-release-1.0.112）+ 开源项目交叉验证。
 *
 * 核心端点（全部在 https://ilinkai.weixin.qq.com 下）：
 *   GET  /ilink/bot/get_bot_qrcode?bot_type=3        → 获取登录二维码
 *   GET  /ilink/bot/get_qrcode_status?qrcode={token} → 轮询扫码状态
 *   POST /ilink/bot/getconfig                        → 获取配置（typingTicket 等）
 *   POST /ilink/bot/getupdates                       → 长轮询收消息（hold 35s）
 *   POST /ilink/bot/getuploadurl                     → 获取文件上传 URL
 *   POST /ilink/bot/sendmessage                      → 发消息（必须带 context_token）
 *   POST /ilink/bot/sendtyping                       → 发送输入状态
 *
 * 鉴权头（每次请求必带）：
 *   AuthorizationType: ilink_bot_token
 *   Authorization:      Bearer {bot_token}
 *   X-WECHAT-UIN:      {base64(random_uint32)}       防重放
 */
class QuroWechatIlinkBotAdapter(context: Context) : QuroDirectBotAdapter(context) {
    override val platform = QuroBotPlatform.WECHAT

    companion object {
        private const val BASE_URL = "https://ilinkai.weixin.qq.com"
        private const val BOT_TYPE = "3"
        private const val CHANNEL_VERSION = "1.0.2"
    }

    private val botToken get() = pref("wechat_token")

    /** 长轮询游标（服务端返回，下次请求原样带回）。 */
    private var getUpdatesBuf = ""

    /** 按 userId 缓存 inbound 的 context_token，sendmessage 必须回带。 */
    private val contextTokens = ConcurrentHashMap<String, String>()

    /** typingTicket（从 getconfig 获取，sendtyping 需要）。 */
    private var typingTicket = ""

    // ---- 扫码登录状态 ----
    private var loginPollJob: kotlinx.coroutines.Job? = null
    enum class LoginState { IDLE, WAITING_SCAN, CONFIRMED, DENIED, EXPIRED }
    @Volatile var loginState = LoginState.IDLE
        private set
    @Volatile var qrCodeData: String? = null
        private set
    @Volatile var qrError: String? = null
        private set

    override fun isConfigured(): Boolean = botToken.isNotBlank()

    // ==================== 鉴权头 ====================

    private fun authHeaders(token: String): Map<String, String> = mapOf(
        "Content-Type" to "application/json",
        "AuthorizationType" to "ilink_bot_token",
        "Authorization" to "Bearer $token",
        "X-WECHAT-UIN" to randomWechatUin(),
    )

    private fun baseHeaders(): Map<String, String> = mapOf(
        "Content-Type" to "application/json",
        "iLink-App-ClientVersion" to "1",
    )

    // ==================== 扫码登录 ====================

    fun startQrLogin(): Boolean {
        if (loginState == LoginState.WAITING_SCAN) return true
        loginPollJob?.cancel()
        loginState = LoginState.IDLE
        qrCodeData = null
        qrError = null

        val qrUrl = "$BASE_URL/ilink/bot/get_bot_qrcode?bot_type=$BOT_TYPE"
        val (qrCode, qrBody, _) = httpGetWithStatus(qrUrl, baseHeaders())
        if (qrCode !in 200..299) {
            val errType = when {
                qrCode == 0 -> "网络不可达"
                qrCode == 404 -> "接口不存在(404)"
                qrCode in 400..499 -> "客户端错误($qrCode)"
                qrCode in 500..599 -> "服务端错误($qrCode)"
                else -> "HTTP $qrCode"
            }
            val detail = qrBody.take(300).ifBlank { "(无详细信息)" }
            val msg = "请求二维码失败[$errType]：$BASE_URL → $detail\n" +
                "可能原因：①手机网络(运营商/校园网/公司内网)拦截或限速该域名 ②DNS 解析不到 ③当前 WiFi 需切移动数据或开 VPN\n" +
                "绕过法：在能访问该域名的电脑上执行  curl \"$BASE_URL/ilink/bot/get_bot_qrcode?bot_type=3\"  拿二维码扫码取 token，" +
                "再在 App「手动填 token」处粘贴即可（无需扫码）"
            qrError = msg
            Log_e("扫码登录 Step1: $msg")
            return false
        }

        val qrJson = runCatching { JSONObject(qrBody) }.getOrNull() ?: run {
            val msg = "二维码响应解析失败（非JSON）：${qrBody.take(200)}"
            qrError = msg
            Log_e("扫码登录 Step1: $msg")
            return false
        }

        // APK 字段：qrcode, qrcode_img_base64, qrcode_img_content, qr_code_url
        val qrcodeToken = qrJson.optString("qrcode").ifBlank {
            qrJson.optString("qrcode_token").ifBlank { qrJson.optString("token").orEmpty() }
        }
        val qrImage = qrJson.optString("qrcode_img_content").ifBlank {
            qrJson.optString("qrcode_img_base64").ifBlank {
                qrJson.optString("qr_code_url").ifBlank {
                    qrJson.optString("qr_url").ifBlank { qrJson.optString("url").orEmpty() }
                }
            }
        }

        if (qrcodeToken.isBlank()) {
            val msg = "响应中未找到二维码 token，原始响应: ${qrJson.toString().take(500)}"
            qrError = msg
            Log_e("扫码登录 Step1: $msg")
            return false
        }

        qrCodeData = qrImage.ifBlank { "QR_TOKEN:$qrcodeToken" }
        qrError = null
        loginState = LoginState.WAITING_SCAN
        Log_i("扫码登录 Step1: 二维码已获取 (token=${qrcodeToken.take(16)}...)，等待微信扫码...")

        loginPollJob = scope.launch {
            var statusBase = BASE_URL
            val maxPolls = 100
            for (polled in 0 until maxPolls) {
                if (loginState != LoginState.WAITING_SCAN || stopped.get()) break
                delay(3000)

                try {
                    val statusUrl = "$statusBase/ilink/bot/get_qrcode_status?qrcode=$qrcodeToken"
                    val (stCode, stBody, _) = httpGetWithStatus(statusUrl, baseHeaders())
                    if (stCode !in 200..299) {
                        if (polled % 10 == 0) Log_w("扫码轮询 HTTP $stCode: ${stBody.take(150)}")
                        continue
                    }
                    val statusJson = runCatching { JSONObject(stBody) }.getOrNull() ?: continue
                    val status = statusJson.optString("status", "").lowercase()

                    when {
                        status == "wait" -> { /* 继续等 */ }
                        status == "scaned" -> Log_i("扫码登录: 已扫描，等待手机确认...")
                        status == "scaned_but_redirect" -> {
                            val redirectHost = statusJson.optString("redirect_host").ifBlank {
                                statusJson.optString("redirect").ifBlank { statusJson.optString("host") }
                            }
                            if (redirectHost.isNotBlank()) {
                                statusBase = "https://$redirectHost"
                                Log_i("扫码轮询切换到 redirect_host: $statusBase")
                            }
                        }
                        status == "confirmed" -> {
                            val token = statusJson.optString("bot_token").ifBlank {
                                statusJson.optString("token").orEmpty()
                            }
                            if (token.isNotBlank()) {
                                prefs.edit().putString("wechat_token", token).apply()
                                loginState = LoginState.CONFIRMED
                                Log_i("扫码登录成功！token 已保存 (${token.take(20)}...)")
                                scope.launch { runCatching { start() } }
                            } else {
                                loginState = LoginState.DENIED
                                Log_w("扫码确认但未返回 token: ${statusJson.toString().take(300)}")
                            }
                            return@launch
                        }
                        status == "expired" -> {
                            loginState = LoginState.EXPIRED
                            Log_w("扫码登录: 二维码已过期")
                            return@launch
                        }
                        status == "canceled" || status == "deny" -> {
                            loginState = LoginState.DENIED
                            Log_w("扫码登录: 被用户取消")
                            return@launch
                        }
                        else -> {
                            if (polled % 10 == 0) Log_w("扫码轮询中... status=$status ($polled/$maxPolls)")
                        }
                    }
                } catch (_: Exception) {
                    // 网络抖动，继续
                }
            }
            if (loginState == LoginState.WAITING_SCAN) {
                loginState = LoginState.EXPIRED
                Log_w("扫码登录: 超时（5分钟）")
            }
        }
        return true
    }

    fun cancelQrLogin() {
        loginPollJob?.cancel()
        loginPollJob = null
        loginState = LoginState.IDLE
        qrCodeData = null
        qrError = null
    }

    // ==================== getconfig（APK 新增端点） ====================

    /**
     * 获取配置（APK 实现：WeixinGetConfigRequest/Response）。
     * 登录成功后调用，获取 typingTicket 等配置。
     */
    private fun fetchConfig() {
        if (botToken.isBlank()) return
        val body = JSONObject().apply {
            put("ilink_user_id", "")
            put("base_info", baseInfoJson())
        }.toString()

        val json = httpPostJson(
            "$BASE_URL/ilink/bot/getconfig",
            headers = authHeaders(botToken),
            json = body,
        ) ?: return

        typingTicket = json.optString("typing_ticket", "")
        Log_i("getconfig: typing_ticket=${typingTicket.take(10)}...")
    }

    // ==================== sendtyping（APK 新增端点） ====================

    /**
     * 发送输入状态（APK 实现：WeixinSendTypingRequest/Response）。
     * 在处理消息时调用，让用户看到"正在输入..."。
     */
    private fun sendTyping(userId: String) {
        if (botToken.isBlank() || typingTicket.isBlank()) return
        val body = JSONObject().apply {
            put("ilink_user_id", userId)
            put("typing_ticket", typingTicket)
            put("base_info", baseInfoJson())
        }.toString()

        httpPostJson(
            "$BASE_URL/ilink/bot/sendtyping",
            headers = authHeaders(botToken),
            json = body,
        )
    }

    // ==================== getuploadurl（APK 新增端点） ====================

    /**
     * 获取文件上传 URL（APK 实现：WeixinGetUploadUrlRequest/Response）。
     * 发送图片/文件前需要先获取上传 URL。
     */
    private fun getUploadUrl(fileKey: String): String? {
        if (botToken.isBlank()) return null
        val body = JSONObject().apply {
            put("filekey", fileKey)
            put("base_info", baseInfoJson())
        }.toString()

        val json = httpPostJson(
            "$BASE_URL/ilink/bot/getuploadurl",
            headers = authHeaders(botToken),
            json = body,
        ) ?: return null

        return json.optString("upload_param", "")
    }

    // ==================== 长轮询收消息 ====================

    /**
     * 长轮询循环（APK 实现：WeixinGetUpdatesRequest/Response）。
     *
     * POST /ilink/bot/getupdates
     * Body: {"get_updates_buf": "<游标>", "base_info": {"channel_version": "1.0.2"}}
     * 响应: { "ret": 0, "msgs": [...], "get_updates_buf": "<新游标>" }
     */
    override suspend fun runConnection() {
        // 登录成功后先获取配置
        fetchConfig()

        var retries = 0
        while (!stopped.get()) {
            if (botToken.isBlank()) {
                delay(2000)
                continue
            }
            try {
                val body = JSONObject().apply {
                    put("get_updates_buf", getUpdatesBuf)
                    put("base_info", baseInfoJson())
                }.toString()

                val root = httpPostJson(
                    "$BASE_URL/ilink/bot/getupdates",
                    headers = authHeaders(botToken),
                    json = body,
                ) ?: run {
                    backoff(retries++)
                    continue
                }

                retries = 0
                val ret = root.optInt("ret", -1)
                if (ret != 0) {
                    Log_w("getupdates 返回 ret=$ret，可能 token 过期")
                    delay(5000)
                    continue
                }

                val newBuf = root.optString("get_updates_buf")
                if (newBuf.isNotEmpty()) getUpdatesBuf = newBuf

                val msgs = root.optJSONArray("msgs") ?: continue
                for (i in 0 until msgs.length()) {
                    val msg = msgs.optJSONObject(i) ?: continue

                    val fromUserId = msg.optString("from_user_id", "")
                    val toUserId = msg.optString("to_user_id", "")
                    val ctxToken = msg.optString("context_token", "")

                    // 提取文本（item_list[].type==1 为文本，text_item.text）
                    var text = ""
                    val items = msg.optJSONArray("item_list")
                    if (items != null) {
                        for (j in 0 until items.length()) {
                            val item = items.optJSONObject(j) ?: continue
                            if (item.optInt("type", 0) == 1) {
                                val ti = item.optJSONObject("text_item")
                                if (ti != null) text = ti.optString("text", "")
                            }
                        }
                    }

                    if (fromUserId.isNotBlank() && ctxToken.isNotBlank()) {
                        contextTokens[fromUserId] = ctxToken
                    }

                    val chatId = fromUserId.ifBlank { toUserId }
                    if (chatId.isNotBlank() && text.isNotBlank()) {
                        // 发送输入状态
                        sendTyping(fromUserId)
                        onInbound(chatId, fromUserId.ifBlank { chatId }, text)
                    }
                }
            } catch (e: Exception) {
                Log_e("长轮询异常: ${e.message}")
                backoff(retries++)
            }
            if (!stopped.get()) delay(1000)
        }
    }

    // ==================== 发消息 ====================

    /**
     * 发送消息（APK 实现：WeixinSendMessageRequest/Response）。
     *
     * POST /ilink/bot/sendmessage
     * Body: {
     *   "msg": {
     *     "to_user_id": "...",
     *     "from_user_id": "",
     *     "client_id": "...",
     *     "message_type": 2,
     *     "message_state": 2,
     *     "context_token": "...",
     *     "item_list": [{"type": 1, "text_item": {"text": "..."}}]
     *   },
     *   "base_info": {"channel_version": "1.0.2"}
     * }
     */
    override suspend fun deliver(reply: QuroOutboundMessage) {
        if (botToken.isBlank()) {
            Log_e("deliver 失败: botToken 为空")
            return
        }
        val ctxToken = contextTokens[reply.userId].orEmpty()
        if (ctxToken.isBlank()) {
            Log_w("deliver 警告: user=${reply.userId} 没有 context_token，消息可能无法送达")
        }

        val body = JSONObject().apply {
            put("msg", JSONObject().apply {
                put("to_user_id", reply.userId)
                put("from_user_id", "")
                put("client_id", randomClientId())
                put("message_type", 2)     // Bot 发出
                put("message_state", 2)    // FINISH
                if (ctxToken.isNotBlank()) put("context_token", ctxToken)
                val itemArray = JSONArray()
                itemArray.put(JSONObject().apply {
                    put("type", 1)  // ItemTypeText
                    put("text_item", JSONObject().apply {
                        put("text", reply.text)
                    })
                })
                put("item_list", itemArray)
            })
            put("base_info", baseInfoJson())
        }.toString()

        val json = httpPostJson(
            "$BASE_URL/ilink/bot/sendmessage",
            headers = authHeaders(botToken),
            json = body,
        )
        if (json == null) Log_e("deliver 失败 user=${reply.userId}")
        else Log_i("deliver 已发往微信 user=${reply.userId}")
    }

    // ==================== 工具方法 ====================

    /** 构建 base_info JSON（APK 的 WeixinBaseInfo）。 */
    private fun baseInfoJson(): JSONObject = JSONObject().apply {
        put("channel_version", CHANNEL_VERSION)
    }

    private fun randomWechatUin(): String {
        val bytes = ByteArray(4)
        SecureRandom().nextBytes(bytes)
        val n = ((bytes[0].toInt() and 0xFF) shl 24) or
                ((bytes[1].toInt() and 0xFF) shl 16) or
                ((bytes[2].toInt() and 0xFF) shl 8) or
                (bytes[3].toInt() and 0xFF)
        return Base64.encodeToString(n.toString().toByteArray(), Base64.NO_WRAP)
    }

    private fun randomClientId(): String {
        val bytes = ByteArray(8)
        SecureRandom().nextBytes(bytes)
        return buildString(bytes.size) { bytes.forEach { append("%02x".format(it.toInt() and 0xFF)) } }
    }

    private fun Log_i(s: String) = android.util.Log.i(TAG, "[WeChat] $s")
    private fun Log_w(s: String) = android.util.Log.w(TAG, "[WeChat] $s")
    private fun Log_e(s: String) = android.util.Log.e(TAG, "[WeChat] $s")
}
