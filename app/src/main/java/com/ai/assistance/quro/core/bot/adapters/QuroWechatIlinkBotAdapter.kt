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
import java.util.Base64

/**
 * 微信 iLink 个人号 Bot 适配器（直连官方域名，零公网端点）。
 *
 * 协议来源（2026-07-24 从开源项目交叉验证）：
 *   - hao-ji-xing/openclaw-weixin  weixin-bot-api.md（完整协议逆向文档）
 *   - YaoApp/yao               integrations/weixin/bot.go（Go SDK）
 *   - 0xranx/golembot           src/weixin-login.ts（TS 扫码登录实现）
 *   - frankenchine/wechatbot    Java Spring Boot SDK
 *
 * 核心端点（全部在 https://ilinkai.weixin.qq.com 下）：
 *   GET  /ilink/bot/get_bot_qrcode?bot_type=3        → 获取登录二维码
 *   GET  /ilink/bot/get_qrcode_status?qrcode={token} → 轮询扫码状态
 *   POST /ilink/bot/getupdates                      → 长轮询收消息（hold 35s）
 *   POST /ilink/bot/sendmessage                     → 发消息（必须带 context_token）
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
        private const val LONG_POLL_TIMEOUT_MS = 35000
    }

    private val botToken get() = pref("wechat_token")

    /** 长轮询游标（服务端返回，下次请求原样带回）。 */
    private var getUpdatesBuf = ""

    /** 按 userId 缓存 inbound 的 context_token，sendmessage 必须回带。 */
    private val contextTokens = ConcurrentHashMap<String, String>()

    // ---- 扫码登录状态 ----
    private var loginPollJob: kotlinx.coroutines.Job? = null
    enum class LoginState { IDLE, WAITING_SCAN, CONFIRMED, DENIED, EXPIRED }
    @Volatile var loginState = LoginState.IDLE
        private set
    /** 二维码数据：优先 base64 图片内容（qrcode_img_content），其次 URL。 */
    @Volatile var qrCodeData: String? = null
        private set
    @Volatile var qrError: String? = null
        private set

    override fun isConfigured(): Boolean = botToken.isNotBlank()

    // ==================== 鉴权头 ====================

    /** 构建所有 iLink 请求共用的鉴权 header map。 */
    private fun authHeaders(token: String): Map<String, String> = mapOf(
        "Content-Type" to "application/json",
        "AuthorizationType" to "ilink_bot_token",
        "Authorization" to "Bearer $token",
        "X-WECHAT-UIN" to randomWechatUin(),
    )

    /** 不带 token 的基础头（获取二维码时用）。 */
    private fun baseHeaders(): Map<String, String> = mapOf(
        "Content-Type" to "application/json",
    )

    // ==================== 扫码登录 ====================

    /**
     * 发起扫码登录（参照 golembot weixin-login.ts + openclaw-weixin 协议文档）。
     *
     * 步骤：
     *   1. GET /ilink/bot/get_bot_qrcode?bot_type=3  → 拿到 qrcode token + 图片
     *   2. GET /ilink/bot/get_qrcode_status?qrcode={token} 轮询（每 3s，最多 5min）
     *   3. status=confirmed 时取 bot_token 存盘，自动启动长轮询
     *
     * @return true 表示已成功发起（QR 数据在 [qrCodeData]），false 表示网络/解析失败
     */
    fun startQrLogin(): Boolean {
        if (loginState == LoginState.WAITING_SCAN) return true
        loginPollJob?.cancel()
        loginState = LoginState.IDLE
        qrCodeData = null
        qrError = null

        // ---- Step 1: GET 获取二维码（用 httpGetWithStatus 保留原始错误详情）----
        val qrUrl = "$BASE_URL/ilink/bot/get_bot_qrcode?bot_type=$BOT_TYPE"
        val (qrCode, qrBody, _) = httpGetWithStatus(qrUrl)
        if (qrCode !in 200..299) {
            // 区分网络层失败(DNS/连接/超时) vs HTTP 错误(404/403/500)
            val errType = when {
                qrCode == 0 -> "网络不可达"  // 异常：DNS/连接/SSL
                qrCode == 404 -> "接口不存在(404)"
                qrCode in 400..499 -> "客户端错误($qrCode)"
                qrCode in 500..599 -> "服务端错误($qrCode)"
                else -> "HTTP $qrCode"
            }
            // 从 body 里提取可能的详细原因（如 DNS 失败名、超时信息）
            val detail = qrBody.take(300).ifBlank { "(无详细信息)" }
            val msg = "请求二维码失败[$errType]：$BASE_URL → $detail\n可能原因：①手机无法访问该域名(需公网/非代理) ②iLink 服务仅限企业微信 ③先手动填写 bot token 绕过扫码"
            qrError = msg
            Log_e("扫码登录 Step1: $msg")
            return false
        }
        // qrBody 此时是成功响应的 JSON 字符串
        val qrJson = runCatching { JSONObject(qrBody) }.getOrNull() ?: run {
            val msg = "二维码响应解析失败（非JSON）：${qrBody.take(200)}"
            qrError = msg
            Log_e("扫码登录 Step1: $msg")
            return false
        }

        // 响应字段（协议文档确认）：qrcode（轮询用 token）+ qrcode_img_content（base64 图片或 URL）
        val qrcodeToken = qrJson.optString("qrcode").ifBlank {
            // 部分实现可能用不同字段名
            qrJson.optString("qrcode_token").ifBlank { qrJson.optString("token").orEmpty() }
        }
        val qrImage = qrJson.optString("qrcode_img_content").ifBlank {
            qrJson.optString("qr_url").ifBlank { qrJson.optString("url").orEmpty() }
        }

        if (qrcodeToken.isBlank()) {
            val msg = "响应中未找到二维码 token，原始响应: ${qrJson.toString().take(500)}"
            qrError = msg
            Log_e("扫码登录 Step1: $msg")
            return false
        }

        // 优先展示图片（base64 或 URL），没有则展示 token 文字提示
        qrCodeData = qrImage.ifBlank { "QR_TOKEN:$qrcodeToken" }
        qrError = null
        loginState = LoginState.WAITING_SCAN
        Log_i("扫码登录 Step1: 二维码已获取 (token=${qrcodeToken.take(16)}...)，等待微信扫码...")

        // ---- Step 2: 轮询扫码状态 ----
        loginPollJob = scope.launch {
            val maxPolls = 100 // 5 分钟（每 3s 一次）
            for (polled in 0 until maxPolls) {
                if (loginState != LoginState.WAITING_SCAN || stopped.get()) break
                delay(3000)

                try {
                    val statusUrl = "$BASE_URL/ilink/bot/get_qrcode_status?qrcode=$qrcodeToken"
                    val (stCode, stBody, _) = httpGetWithStatus(statusUrl)
                    if (stCode !in 200..299) {
                        if (polled % 10 == 0) Log_w("扫码轮询 HTTP $stCode: ${stBody.take(150)}")
                        continue
                    }
                    val statusJson = runCatching { JSONObject(stBody) }.getOrNull() ?: continue
                    val status = statusJson.optString("status", "").lowercase()

                    when {
                        status == "wait" -> { /* 继续等 */ }
                        status == "scaned" -> Log_i("扫码登录: 已扫描，等待手机确认...")
                        status == "confirmed" -> {
                            val token = statusJson.optString("bot_token").ifBlank {
                                statusJson.optString("token").orEmpty()
                            }
                            if (token.isNotBlank()) {
                                prefs.edit().putString("wechat_token", token).apply()
                                loginState = LoginState.CONFIRMED
                                Log_i("扫码登录成功！token 已保存 (${token.take(20)}...)")
                                // 自动启动长轮询
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

    // ==================== 长轮询收消息 ====================

    /**
     * 长轮询循环（参照 YaoApp yao bot.go GetUpdates + openclaw-weixin 协议文档）。
     *
     * POST /ilink/bot/getupdates
     * Body: {"get_updates_buf": "<游标>", "base_info": {"channel_version": "1.0.2"}}
     * 响应: { "ret": 0, "msgs": [...], "get_updates_buf": "<新游标>" }
     */
    override suspend fun runConnection() {
        var retries = 0
        while (!stopped.get()) {
            if (botToken.isBlank()) {
                delay(2000)
                continue
            }
            try {
                val body = JSONObject().apply {
                    put("get_updates_buf", getUpdatesBuf)
                    put("base_info", JSONObject().apply {
                        put("channel_version", CHANNEL_VERSION)
                    })
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
                    // ret 非 0 通常表示鉴权失败，不要更新游标
                    delay(5000)
                    continue
                }

                // 更新游标（无论是否有消息都要更新）
                val newBuf = root.optString("get_updates_buf")
                if (newBuf.isNotEmpty()) getUpdatesBuf = newBuf

                // 解析消息列表
                val msgs = root.optJSONArray("msgs") ?: continue
                for (i in 0 until msgs.length()) {
                    val msg = msgs.optJSONObject(i) ?: continue

                    val fromUserId = msg.optString("from_user_id", "")
                    val toUserId = msg.optString("to_user_id", "")
                    val messageType = msg.optInt("message_type", 0)
                    val ctxToken = msg.optString("context_token", "")

                    // 只处理用户发来的文本消息（message_type=1 表示入站消息）
                    if (messageType != 1 && messageType == 0) {
                        // message_type 缺失时也尝试处理（部分版本可能不返回此字段）
                    }

                    // 提取文本（item_list[].type==1 为文本）
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

                    // 缓存 context_token（按 from_user_id 索引，deliver 时回带）
                    if (fromUserId.isNotBlank() && ctxToken.isNotBlank()) {
                        contextTokens[fromUserId] = ctxToken
                    }

                    // 触发 inbound 处理
                    val chatId = fromUserId.ifBlank { toUserId }
                    if (chatId.isNotBlank() && text.isNotBlank()) {
                        onInbound(chatId, fromUserId.ifBlank { chatId }, text)
                    }
                }
            } catch (e: Exception) {
                Log_e("长轮询异常: ${e.message}")
                backoff(retries++)
            }
            // 服务端 hold 35s，有消息会立即返回；无消息时 resp 也很快（空 msgs）
            if (!stopped.get()) delay(1000)
        }
    }

    // ==================== 发消息 ====================

    /**
     * 发送消息（参照 YaoApp yao bot.go SendMessage + 协议文档）。
     *
     * POST /ilink/bot/sendmessage
     * Body: {
     *   "msg": {
     *     "to_user_id": "...",
     *     "message_type": 2,       // BOT 发出
     *     "message_state": 2,      // FINISH
     *     "context_token": "...",  // 必填！从 inbound 消息缓存
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
        /*
         * context_token 是 iLink 协议最关键的字段——没有它消息无法关联到正确对话窗口，
         * 微信端根本收不到。如果缓存里没有（比如 Bot 重启后丢失、或 24h 过期），
         * 日志打 WARNING 但仍然尝试发送（服务端可能拒绝，这是预期行为）。
         */
        if (ctxToken.isBlank()) {
            Log_w("deliver 警告: user=${reply.userId} 没有 context_token，消息可能无法送达（需要用户先发一条消息触发）")
        }

        val body = JSONObject().apply {
            put("msg", JSONObject().apply {
                put("to_user_id", reply.userId)
                put("client_id", randomClientId())
                put("message_type", 2)     // MessageTypeBot（发出）
                put("message_state", 2)    // MessageStateFinish
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
            put("base_info", JSONObject().apply {
                put("channel_version", CHANNEL_VERSION)
            })
        }.toString()

        val json = httpPostJson(
            "$BASE_URL/ilink/bot/sendmessage",
            headers = authHeaders(botToken),
            json = body,
        )
        if (json == null) Log_e("deliver 失败 user=${reply.userId} (HTTP 错误或网络异常)")
        else Log_i("deliver 已发往微信 user=${reply.userId}")
    }

    // ==================== 工具方法 ====================

    /** 生成随机 X-WECHAT-UIN 头值（随机 uint32 → 十进制字符串 → base64）。 */
    private fun randomWechatUin(): String {
        val bytes = ByteArray(4)
        SecureRandom().nextBytes(bytes)
        val n = ((bytes[0].toInt() and 0xFF) shl 24) or
                ((bytes[1].toInt() and 0xFF) shl 16) or
                ((bytes[2].toInt() and 0xFF) shl 8) or
                (bytes[3].toInt() and 0xFF)
        return Base64.getEncoder().encodeToString(n.toString().toByteArray())
    }

    /** 生成随机 client_id（用于 sendmessage 的去重）。 */
    private fun randomClientId(): String {
        val bytes = ByteArray(8)
        SecureRandom().nextBytes(bytes)
        return buildString(bytes.size) { bytes.forEach { append("%02x".format(it.toInt() and 0xFF)) } }
    }

    private fun Log_i(s: String) = android.util.Log.i(TAG, "[WeChat] $s")
    private fun Log_w(s: String) = android.util.Log.w(TAG, "[WeChat] $s")
    private fun Log_e(s: String) = android.util.Log.e(TAG, "[WeChat] $s")
}
