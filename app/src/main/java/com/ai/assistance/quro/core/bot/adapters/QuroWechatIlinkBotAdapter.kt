package com.ai.assistance.quro.core.bot.adapters

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.ai.assistance.quro.core.bot.QuroBotPlatform
import com.ai.assistance.quro.core.bot.QuroOutboundMessage
import com.ai.assistance.quro.util.QuroDiag
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 微信 iLink Bot 适配器（移植自 Andclaw ClawBotApiClient + ClawBotPoller）。
 *
 * 纯 OkHttp + org.json，零第三方 SDK 依赖。
 * 协议层完全对齐 Andclaw 的 ClawBotApiClient + ClawBotPoller。
 */
class QuroWechatIlinkBotAdapter(context: Context) : QuroDirectBotAdapter(context) {
    override val platform = QuroBotPlatform.WECHAT

    companion object {
        private const val TAG = "QuroWechatIlinkBot"
        private const val PREFS_NAME = "quro_wechat_ilink"
        private const val KEY_BOT_TOKEN = "bot_token"
        private const val KEY_BASE_URL = "base_url"
        private const val KEY_ACCOUNT_ID = "account_id"
        private const val KEY_USER_ID = "user_id"
        private const val KEY_SYNC_BUF = "sync_buf"
        private const val KEY_LOGGED_IN = "logged_in"
        private const val KEY_BOT_TYPE = "bot_type"
        private const val DEFAULT_BASE_URL = "https://ilinkai.weixin.qq.com"
        private const val DEFAULT_BOT_TYPE = "3"
    }

    /** 统一诊断出口：Logcat + 手机公共 Download/QuroAI_logs（用户无需 adb 即可取）。 */
    private fun d(s: String) { Log.d(TAG, s); QuroDiag.log("Wechat", s) }
    private fun de(msg: String, t: Throwable? = null) { Log.e(TAG, msg, t); QuroDiag.log("Wechat", "$msg ${t?.message ?: ""}") }

    private val wechatPrefs: SharedPreferences =
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ClawBot API 客户端
    private val apiClient = ClawBotApiClient(
        httpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(70, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    )

    // ClawBot 轮询器
    private val poller = ClawBotPoller(
        api = apiClient,
        getAuthToken = { wechatPrefs.getString(KEY_BOT_TOKEN, null) },
        getBaseUrl = { wechatPrefs.getString(KEY_BASE_URL, DEFAULT_BASE_URL) ?: DEFAULT_BASE_URL },
        loadSyncBuf = { wechatPrefs.getString(KEY_SYNC_BUF, "") ?: "" },
        saveSyncBuf = { buf -> wechatPrefs.edit().putString(KEY_SYNC_BUF, buf).apply() }
    )

    // 独立登录 scope
    private var loginJob: Job? = null
    private var pollJob: Job? = null

    /** 真实连接态：仅轮询启动后才算已连接，避免 UI 误显「已连接」。 */
    override val isConnected: Boolean get() = pollJob?.isActive == true

    // 扫码登录状态
    @Volatile var loginState = QrLoginStatus.WAIT
        private set
    @Volatile var qrCodeData: String? = null
        private set
    @Volatile var qrError: String? = null
        private set

    // 当前用户上下文（用于发送消息）
    private val currentUserContext = AtomicBoolean(false)
    private var lastFromUserId: String = ""
    private var lastContextToken: String = ""

    // 按 userId 缓存 inbound 的 context_token，sendmessage 必须回带。
    private val contextTokens = ConcurrentHashMap<String, String>()

    override fun isConfigured(): Boolean {
        return wechatPrefs.getBoolean(KEY_LOGGED_IN, false)
    }

    override suspend fun runConnection() {
        // 微信 Bot 使用独立的轮询机制，不走基类的 runConnection
        // 长轮询由 startPolling() 启动
        while (!stopped.get()) {
            if (isConfigured() && pollJob?.isActive != true) {
                startPolling()
            }
            delay(5000) // 每5秒检查一次
        }
    }

    // ==================== 扫码登录（Andclaw ClawBotAuthClient 风格） ====================

    fun startQrLogin() {
        d("startQrLogin 被调用, loginState=$loginState")

        if (loginState == QrLoginStatus.SCANNED || loginState == QrLoginStatus.CONFIRMED) {
            return
        }

        loginJob?.cancel()
        loginJob = null
        loginState = QrLoginStatus.WAIT
        qrCodeData = null
        qrError = null

        loginJob = CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                d(">>> 开始获取二维码...")
                val baseUrl = wechatPrefs.getString(KEY_BASE_URL, DEFAULT_BASE_URL) ?: DEFAULT_BASE_URL
                val botType = wechatPrefs.getString(KEY_BOT_TYPE, DEFAULT_BOT_TYPE) ?: DEFAULT_BOT_TYPE

                // 1. 获取二维码
                val qrResponse = apiClient.getBotQrcode(baseUrl, botType)
                val qrcode = qrResponse.optString("qrcode", "")
                val qrcodeImgContent = qrResponse.optString("qrcode_img_content", "")

                d(">>> 二维码获取成功: qrcode=${qrcode.take(50)}, imgContent=${qrcodeImgContent.take(50)}")

                if (qrcode.isEmpty()) {
                    loginState = QrLoginStatus.UNKNOWN
                    qrError = "服务器返回空二维码"
                    return@launch
                }

                qrCodeData = qrcodeImgContent.takeIf { it.isNotEmpty() } ?: qrcode
                loginState = QrLoginStatus.SCANNED
                d("二维码已就绪，等待扫码...")

                // 2. 轮询扫码状态（Andclaw 风格：外层循环 + 单次查询）
                var pollCount = 0
                val maxPolls = 120 // 最多轮询 120 次（约 2 分钟）
                while (isActive && pollCount < maxPolls) {
                    kotlinx.coroutines.delay(1000) // 每秒查一次
                    pollCount++

                    try {
                        val statusResponse = apiClient.getQrcodeStatus(baseUrl, qrcode)
                        val status = statusResponse.optString("status", "").lowercase()
                        d("扫码状态: $status (第 $pollCount 次)")

                        when (status) {
                            "scanned" -> {
                                loginState = QrLoginStatus.SCANNED
                                d("已扫码，等待确认...")
                            }
                            "confirmed" -> {
                                loginState = QrLoginStatus.CONFIRMED
                                val token = statusResponse.optString("bot_token", "")
                                val accountId = statusResponse.optString("ilink_bot_id", "")
                                val userId = statusResponse.optString("ilink_user_id", "")
                                val serverBaseUrl = statusResponse.optString("baseurl", "")

                                d(">>> 登录成功! token=${token.take(20)}..., accountId=$accountId")

                                // 保存登录信息
                                wechatPrefs.edit().apply {
                                    putString(KEY_BOT_TOKEN, token)
                                    putString(KEY_ACCOUNT_ID, accountId)
                                    putString(KEY_USER_ID, userId)
                                    putString(KEY_BASE_URL, serverBaseUrl.ifEmpty { baseUrl })
                                    putBoolean(KEY_LOGGED_IN, true)
                                    apply()
                                }

                                // 启动长轮询
                                startPolling()
                                return@launch
                            }
                            "denied" -> {
                                loginState = QrLoginStatus.DENIED
                                qrError = "用户拒绝登录"
                                return@launch
                            }
                            "expired" -> {
                                loginState = QrLoginStatus.EXPIRED
                                qrError = "二维码已过期"
                                return@launch
                            }
                        }
                    } catch (e: Exception) {
                        de("轮询扫码状态失败: ${e.message}")
                    }
                }

                // 超时
                if (loginState != QrLoginStatus.CONFIRMED) {
                    loginState = QrLoginStatus.EXPIRED
                    qrError = "扫码超时"
                }
            } catch (e: Exception) {
                de("扫码登录失败", e)
                loginState = QrLoginStatus.UNKNOWN
                qrError = e.message ?: "未知错误"
            }
        }
    }

    // ==================== 长轮询 ====================

    fun startPolling() {
        if (pollJob?.isActive == true) return

        pollJob = CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            poller.runLoop(
                onInbound = { msg ->
                    d("收到消息: from=${msg.fromUserId}, text=${msg.text.take(50)}")

                    // 缓存 context_token
                    contextTokens[msg.fromUserId] = msg.contextToken

                    // 保存当前用户上下文
                    lastFromUserId = msg.fromUserId
                    lastContextToken = msg.contextToken
                    currentUserContext.set(true)

                    // 转发给 AI 处理
                    val inbound = com.ai.assistance.quro.core.bot.QuroInboundMessage(
                        platform = QuroBotPlatform.WECHAT,
                        userId = msg.fromUserId,
                        userName = "",
                        text = msg.text,
                    )
                    com.ai.assistance.quro.core.bot.QuroBotManager.instance(appContext).handleInbound(inbound)
                },
                onConnected = {
                    d("微信 Bot 已连接")
                },
                onDisconnected = {
                    d("微信 Bot 已断开")
                },
                shouldStop = { pollJob?.isActive != true }
            )
        }
    }

    fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    // ==================== 发送消息 ====================

    override suspend fun deliver(msg: QuroOutboundMessage) {
        val userId = msg.userId
        val token = wechatPrefs.getString(KEY_BOT_TOKEN, null)
        val baseUrl = wechatPrefs.getString(KEY_BASE_URL, DEFAULT_BASE_URL) ?: DEFAULT_BASE_URL
        // 发送者必须是机器人自己的 ilink_bot_id（登录时存为 KEY_ACCOUNT_ID）；空 sender 会被服务端丢弃
        val accountId = wechatPrefs.getString(KEY_ACCOUNT_ID, "")

        if (token.isNullOrEmpty()) {
            de("发送失败: 未登录")
            lastError = "微信发送失败：未登录"
            return
        }

        // 获取 context_token（按用户缓存，回带才能定位会话）
        val contextToken = contextTokens[userId] ?: lastContextToken

        if (contextToken.isEmpty()) {
            de("发送失败: 缺少 context_token")
            lastError = "微信发送失败：缺少 context_token（请先收到一条消息）"
            return
        }

        try {
            apiClient.postSendMessage(
                baseUrl, token, userId, msg.text, contextToken,
                fromUserId = accountId ?: "",
            )
            d("消息已发送: to=$userId from=$accountId")
            lastError = null
        } catch (e: Exception) {
            de("发送消息失败", e)
            lastError = "微信发送失败：${e.message ?: "未知错误"}"
        }
    }

    // ==================== 登出 ====================

    fun logout() {
        stopPolling()
        wechatPrefs.edit().clear().apply()
        loginState = QrLoginStatus.WAIT
        qrCodeData = null
        qrError = null
        currentUserContext.set(false)
        lastFromUserId = ""
        lastContextToken = ""
        contextTokens.clear()
    }

    fun cancelQrLogin() {
        loginJob?.cancel()
        loginJob = null
        loginState = QrLoginStatus.WAIT
        qrCodeData = null
        qrError = null
    }

    // ==================== 状态查询 ====================

    fun isLoggedIn(): Boolean = wechatPrefs.getBoolean(KEY_LOGGED_IN, false)

    // 注意：QrLoginStatus 使用同包顶层定义（ILinkClient.kt），
    // 切勿在此嵌套同名枚举，否则 UI 的 when 因类型不一致而整块不渲染二维码。
}
