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

    /**
     * 鉴权头（APK 反编译确认：每次请求必带全部 5 个头）。
     * APK 的 OkHttpClient interceptor 统一注入：
     *   Content-Type, AuthorizationType, Authorization, X-WECHAT-UIN, iLink-App-ClientVersion
     * 缺少任何一个都可能导致服务端静默丢弃请求或返回 ret!=0。
     */
    private fun authHeaders(token: String): Map<String, String> = mapOf(
        "AuthorizationType" to "ilink_bot_token",
        "Authorization" to "Bearer $token",
        "X-WECHAT-UIN" to randomWechatUin(),
        "iLink-App-ClientVersion" to "1",
        "User-Agent" to "Bothub-Android/iLink",
    )

    private fun baseHeaders(): Map<String, String> = mapOf(
        "iLink-App-ClientVersion" to "1",
        "User-Agent" to "Bothub-Android/iLink",
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
            qrError = "请求二维码失败[$errType]：$BASE_URL → $detail"
            return false
        }

        val qrJson = runCatching { JSONObject(qrBody) }.getOrNull() ?: run {
            qrError = "二维码响应解析失败（非JSON）：${qrBody.take(200)}"
            return false
        }

        // 协议确认：qrcode=轮询token, qrcode_img_content=可渲染的URL
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
            qrError = "响应中未找到二维码 token，原始响应: ${qrJson.toString().take(500)}"
            return false
        }

        qrCodeData = qrImage.ifBlank { "QR_TOKEN:$qrcodeToken" }
        qrError = null
        loginState = LoginState.WAITING_SCAN

        loginPollJob = scope.launch {
            var statusBase = BASE_URL
            val maxPolls = 100
            for (polled in 0 until maxPolls) {
                if (loginState != LoginState.WAITING_SCAN || stopped.get()) break
                delay(3000)

                try {
                    val statusUrl = "$statusBase/ilink/bot/get_qrcode_status?qrcode=$qrcodeToken"
                    val (_, stBody, _) = httpGetWithStatus(statusUrl, baseHeaders())
                    val statusJson = runCatching { JSONObject(stBody) }.getOrNull() ?: continue
                    val status = statusJson.optString("status", "").lowercase()

                    when {
                        status == "wait" -> { /* 继续等 */ }
                        status == "scaned" -> { /* 已扫描，等待确认 */ }
                        status == "scaned_but_redirect" -> {
                            val redirectHost = statusJson.optString("redirect_host").ifBlank {
                                statusJson.optString("redirect").ifBlank { statusJson.optString("host") }
                            }
                            if (redirectHost.isNotBlank()) {
                                statusBase = "https://$redirectHost"
                            }
                        }
                        status == "confirmed" -> {
                            // 协议确认：confirmed 返回 bot_token, ilink_bot_id, ilink_user_id
                            val token = statusJson.optString("bot_token").ifBlank {
                                statusJson.optString("token").orEmpty()
                            }
                            if (token.isNotBlank()) {
                                prefs.edit().putString("wechat_token", token).apply()
                                loginState = LoginState.CONFIRMED
                                scope.launch { runCatching { start() } }
                            } else {
                                loginState = LoginState.DENIED
                            }
                            return@launch
                        }
                        status == "expired" -> {
                            loginState = LoginState.EXPIRED
                            return@launch
                        }
                        status == "canceled" || status == "deny" -> {
                            loginState = LoginState.DENIED
                            return@launch
                        }
                    }
                } catch (_: Exception) {
                    // 网络抖动，继续
                }
            }
            if (loginState == LoginState.WAITING_SCAN) {
                loginState = LoginState.EXPIRED
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
     * APK 确认：getconfig 请求体需要 ilink_user_id + base_info。
     */
    private fun fetchConfig() {
        if (botToken.isBlank()) return
        val body = JSONObject().apply {
            put("ilink_user_id", "")
            put("context_token", "")
            put("base_info", baseInfoJson())
        }.toString()

        val (statusCode, respBody, json) = httpPostWithStatus(
            "$BASE_URL/ilink/bot/getconfig",
            headers = authHeaders(botToken),
            json = body,
        )
        if (json == null) {
            Log_w("getconfig 失败 HTTP=$statusCode resp=${respBody.take(300)}")
            return
        }
        val ret = json.optInt("ret", -1)
        if (ret != 0) {
            Log_w("getconfig 返回 ret=$ret resp=${json.toString().take(300)}")
            return
        }
        typingTicket = json.optString("typing_ticket", "")
        Log_i("getconfig 成功: typing_ticket=${typingTicket.take(16)}...")
    }

    // ==================== sendtyping（APK 新增端点） ====================

    /**
     * 发送输入状态（APK 实现：WeixinSendTypingRequest/Response）。
     * 在处理消息时调用，让用户看到"正在输入..."。
     * APK 确认：需要 ilink_user_id + typing_ticket + base_info。
     */
    private fun sendTyping(userId: String) {
        if (botToken.isBlank() || typingTicket.isBlank()) return
        val body = JSONObject().apply {
            put("ilink_user_id", userId)
            put("typing_ticket", typingTicket)
            put("status", 1)  // 1=typing, 2=cancel (协议要求)
            put("base_info", baseInfoJson())
        }.toString()

        // sendtyping 是尽力而为，失败不影响主流程
        runCatching {
            httpPostJson(
                "$BASE_URL/ilink/bot/sendtyping",
                headers = authHeaders(botToken),
                json = body,
            )
        }
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
        Log_i("runConnection 开始 botToken=${botToken.take(10)}...")
        connected = true

        // 登录成功后先获取配置（typingTicket 等）
        fetchConfig()

        var retries = 0
        while (!stopped.get()) {
            if (botToken.isBlank()) {
                Log_w("botToken 为空，等待...")
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
                )
                if (root == null) {
                    backoff(retries++)
                    continue
                }

                retries = 0
                val ret = root.optInt("ret", -1)
                if (ret != 0) {
                    val errcode = root.optInt("errcode", 0)
                    Log_w("getupdates ret=$ret errcode=$errcode 响应: ${root.toString().take(300)}")
                    // errcode=-14 表示 session 过期，bot_token 失效
                    if (errcode == -14 || ret == -14) {
                        Log_e("session 过期(errcode=-14)，清除 token，停止轮询。请重新扫码登录。")
                        prefs.edit().remove("wechat_token").apply()
                        loginState = LoginState.IDLE
                        break
                    }
                    delay(5000)
                    continue
                }

                // 游标更新
                val newBuf = root.optString("get_updates_buf", "")
                if (newBuf.isNotEmpty()) {
                    getUpdatesBuf = newBuf
                }

                // 协议确认：根级别 msgs 数组
                var msgs = root.optJSONArray("msgs")
                // 兼容：部分版本可能用 message_list
                if (msgs == null) msgs = root.optJSONArray("message_list")

                if (msgs == null || msgs.length() == 0) {
                    if (!stopped.get()) delay(1000)
                    continue
                }

                for (i in 0 until msgs.length()) {
                    val msg = msgs.optJSONObject(i) ?: continue

                    val fromUserId = msg.optString("from_user_id", "")
                    val toUserId = msg.optString("to_user_id", "")
                    val ctxToken = msg.optString("context_token", "")

                    // ===== 提取文本（协议确认：item_list[].type==1 为文本，嵌套 text_item.text） =====
                    var text = ""

                    // 方式1: 标准 item_list 结构（协议主路径）
                    val items = msg.optJSONArray("item_list")
                    if (items != null && items.length() > 0) {
                        for (j in 0 until items.length()) {
                            val item = items.optJSONObject(j) ?: continue
                            val itemType = item.optInt("type", 0)
                            when (itemType) {
                                1 -> { // 文本消息
                                    val ti = item.optJSONObject("text_item")
                                    if (ti != null) text = ti.optString("text", "")
                                }
                                3 -> { // 语音消息：voice_item.text（语音转文字）
                                    val vi = item.optJSONObject("voice_item")
                                    if (vi != null) text = vi.optString("text", "")
                                }
                                2 -> { /* 图片消息，暂跳过 */ }
                            }
                        }
                    }

                    // 方式2: msg 层直接有 content/text 字段（兼容）
                    if (text.isBlank()) {
                        text = msg.optString("content", "").ifBlank {
                            msg.optString("text", "").ifBlank {
                                msg.optString("message_content", "")
                            }
                        }
                    }

                    // 方式3: msg 内嵌套 msg 对象（某些版本的 API）
                    if (text.isBlank()) {
                        val innerMsg = msg.optJSONObject("msg")
                        if (innerMsg != null) {
                            val innerItems = innerMsg.optJSONArray("item_list")
                            if (innerItems != null) {
                                for (j in 0 until innerItems.length()) {
                                    val item = innerItems.optJSONObject(j) ?: continue
                                    if (item.optInt("type", 0) == 1) {
                                        val ti = item.optJSONObject("text_item")
                                        if (ti != null) text = ti.optString("text", "")
                                    }
                                }
                            }
                            if (text.isBlank()) text = innerMsg.optString("text", "")
                        }
                    }

                    // 缓存 context_token（每个用户最新的 token）
                    if (fromUserId.isNotBlank() && ctxToken.isNotBlank()) {
                        contextTokens[fromUserId] = ctxToken
                    }

                    val chatId = fromUserId.ifBlank { toUserId }
                    if (chatId.isNotBlank() && text.isNotBlank()) {
                        sendTyping(fromUserId)
                        onInbound(chatId, fromUserId.ifBlank { chatId }, text)
                    }
                }
            } catch (e: Exception) {
                Log_e("长轮询异常: ${e.javaClass.simpleName}: ${e.message}")
                backoff(retries++)
            }
            if (!stopped.get()) delay(1000)
        }
        connected = false
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
        if (botToken.isBlank()) return
        val ctxToken = contextTokens[reply.userId].orEmpty()

        // 消息过长时截断（微信单条消息有长度限制）
        val text = if (reply.text.length > 4000) {
            reply.text.take(4000) + "\n...(内容过长已截断)"
        } else reply.text

        val body = JSONObject().apply {
            put("msg", JSONObject().apply {
                put("to_user_id", reply.userId)
                put("from_user_id", "")
                put("client_id", randomClientId())
                put("message_type", 2)     // 2=bot
                put("message_state", 2)    // 2=finish
                if (ctxToken.isNotBlank()) put("context_token", ctxToken)
                val itemArray = JSONArray()
                itemArray.put(JSONObject().apply {
                    put("type", 1)  // 1=text
                    put("text_item", JSONObject().apply {
                        put("text", text)
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
        if (json != null) {
            val ret = json.optInt("ret", -1)
            if (ret != 0) {
                Log_e("deliver 失败 user=${reply.userId} ret=$ret")
            }
        }
    }

    // ==================== 工具方法 ====================

    /** 构建 base_info JSON（APK 的 WeixinBaseInfo）。 */
    private fun baseInfoJson(): JSONObject = JSONObject().apply {
        put("channel_version", CHANNEL_VERSION)
    }

    private fun randomWechatUin(): String {
        val bytes = ByteArray(4)
        SecureRandom().nextBytes(bytes)
        // 协议要求 uint32 → 十进制字符串 → base64；用 Long 避免符号问题
        val n = ((bytes[0].toLong() and 0xFF) shl 24) or
                ((bytes[1].toLong() and 0xFF) shl 16) or
                ((bytes[2].toLong() and 0xFF) shl 8) or
                (bytes[3].toLong() and 0xFF)
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
