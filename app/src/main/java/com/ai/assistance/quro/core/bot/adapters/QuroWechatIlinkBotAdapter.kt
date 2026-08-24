package com.ai.assistance.quro.core.bot.adapters

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.ai.assistance.quro.core.bot.QuroBotPlatform
import com.ai.assistance.quro.core.bot.QuroOutboundMessage
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 微信 iLink Bot 适配器（移植自 Andclaw 1.2.0 ClawBot 实现）。
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

    override fun isConfigured(): Boolean {
        return wechatPrefs.getBoolean(KEY_LOGGED_IN, false)
    }

    // ==================== 扫码登录（Andclaw ClawBotAuthClient 风格） ====================

    fun startQrLogin() {
        Log.d(TAG, "startQrLogin 被调用, loginState=$loginState")

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
                Log.d(TAG, ">>> 开始获取二维码...")
                val baseUrl = wechatPrefs.getString(KEY_BASE_URL, DEFAULT_BASE_URL) ?: DEFAULT_BASE_URL
                val botType = wechatPrefs.getString(KEY_BOT_TYPE, DEFAULT_BOT_TYPE) ?: DEFAULT_BOT_TYPE

                // 1. 获取二维码
                val qrResponse = apiClient.getBotQrcode(baseUrl, botType)
                val qrcode = qrResponse.optString("qrcode", "")
                val qrcodeImgContent = qrResponse.optString("qrcode_img_content", "")

                Log.d(TAG, ">>> 二维码获取成功: qrcode=${qrcode.take(50)}, imgContent=${qrcodeImgContent.take(50)}")

                if (qrcode.isBlank()) {
                    loginState = QrLoginStatus.UNKNOWN
                    qrError = "服务器返回空二维码"
                    return@launch
                }

                qrCodeData = qrcodeImgContent.takeIf { it.isNotBlank() } ?: qrcode
                loginState = QrLoginStatus.SCANNED
                Log.d(TAG, "二维码已就绪，等待扫码...")

                // 2. 轮询扫码状态（Andclaw 风格：外层循环 + 单次查询）
                var pollCount = 0
                val maxPolls = 120 // 最多轮询 120 次（约 2 分钟）
                while (isActive && pollCount < maxPolls) {
                    kotlinx.coroutines.delay(1000) // 每秒查一次
                    pollCount++

                    try {
                        val statusResponse = apiClient.getQrcodeStatus(baseUrl, qrcode)
                        val status = statusResponse.optString("status", "").lowercase()
                        Log.d(TAG, "扫码状态: $status (第 $pollCount 次)")

                        when (status) {
                            "scaned" -> {
                                loginState = QrLoginStatus.SCANNED
                            }
                            "confirmed" -> {
                                val botToken = statusResponse.optString("bot_token", "")
                                val resolvedBaseUrl = statusResponse.optString("baseurl", "").takeIf { it.isNotBlank() } ?: baseUrl
                                val ilinkBotId = statusResponse.optString("ilink_bot_id", "")
                                val ilinkUserId = statusResponse.optString("ilink_user_id", "")

                                if (botToken.isBlank() || ilinkBotId.isBlank()) {
                                    loginState = QrLoginStatus.UNKNOWN
                                    qrError = "登录确认但缺少关键字段"
                                    return@launch
                                }

                                // 保存登录态
                                wechatPrefs.edit()
                                    .putBoolean(KEY_LOGGED_IN, true)
                                    .putString(KEY_BOT_TOKEN, botToken)
                                    .putString(KEY_BASE_URL, resolvedBaseUrl)
                                    .putString(KEY_ACCOUNT_ID, ilinkBotId)
                                    .putString(KEY_USER_ID, ilinkUserId)
                                    .putString(KEY_BOT_TYPE, botType)
                                    .apply()

                                loginState = QrLoginStatus.CONFIRMED
                                Log.i(TAG, "登录确认: botId=$ilinkBotId")

                                // 启动消息轮询
                                startPolling()
                                return@launch
                            }
                            "expired" -> {
                                loginState = QrLoginStatus.EXPIRED
                                qrError = "二维码已过期"
                                Log.w(TAG, "二维码已过期")
                                return@launch
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "轮询扫码状态异常: ${e.message}")
                        // 继续轮询，不中断
                    }
                }

                // 超时
                if (loginState != QrLoginStatus.CONFIRMED) {
                    loginState = QrLoginStatus.EXPIRED
                    qrError = "登录超时（2分钟）"
                }

            } catch (e: CancellationException) {
                Log.d(TAG, "登录被取消")
            } catch (e: Exception) {
                loginState = QrLoginStatus.UNKNOWN
                qrError = "错误: ${e.javaClass.simpleName}: ${e.message}"
                Log.e(TAG, "登录异常", e)
            }
        }
    }

    fun cancelQrLogin() {
        loginJob?.cancel()
        loginJob = null
        loginState = QrLoginStatus.WAIT
        qrCodeData = null
        qrError = null
    }

    // ==================== 消息轮询（Andclaw ClawBotPoller 风格） ====================

    private fun startPolling() {
        if (connected) return
        connected = true

        pollJob = CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            poller.runLoop(
                onInbound = { msg ->
                    Log.d(TAG, "收到消息: from=${msg.fromUserId}, text=${msg.text.take(100)}")
                    // 保存最新的用户上下文（用于回复）
                    lastFromUserId = msg.fromUserId
                    lastContextToken = msg.contextToken
                    currentUserContext.set(true)
                    onInbound(msg.fromUserId, msg.fromUserId, msg.text)
                },
                onConnected = {
                    Log.d(TAG, "轮询已连接")
                },
                onDisconnected = {
                    Log.d(TAG, "轮询已断开")
                },
                shouldStop = { !connected }
            )
        }
        Log.d(TAG, "消息轮询已启动")
    }

    private fun stopPolling() {
        if (!connected) return
        connected = false
        pollJob?.cancel()
        pollJob = null
        Log.d(TAG, "消息轮询已停止")
    }

    // ==================== 连接生命周期 ====================

    override suspend fun runConnection() {
        Log.i(TAG, "runConnection 开始")
        connected = true

        if (isConfigured()) {
            startPolling()
        }

        while (!stopped.get() && scope.isActive) {
            delay(1000)
        }

        stopPolling()
        connected = false
        Log.i(TAG, "runConnection 结束")
    }

    // ==================== 发送消息（Andclaw postSendMessage 风格） ====================

    override suspend fun deliver(reply: QuroOutboundMessage) {
        val token = wechatPrefs.getString(KEY_BOT_TOKEN, null)
        val baseUrl = wechatPrefs.getString(KEY_BASE_URL, DEFAULT_BASE_URL) ?: DEFAULT_BASE_URL

        if (token.isNullOrBlank()) {
            Log.w(TAG, "未登录，无法发送消息")
            return
        }

        // 消息过长时截断
        val text = if (reply.text.length > 4000) {
            reply.text.take(4000) + "\n...(内容过长已截断)"
        } else reply.text

        // 使用最近一次收到消息的上下文
        val toUserId = reply.userId
        val contextToken = lastContextToken

        if (contextToken.isBlank()) {
            Log.w(TAG, "无可用的 context_token，消息未发送")
            return
        }

        try {
            withContext(Dispatchers.IO) {
                apiClient.postSendMessage(baseUrl, token, toUserId, text, contextToken)
            }
            Log.d(TAG, "发送成功: to=$toUserId")
        } catch (e: Exception) {
            Log.e(TAG, "发送失败: ${e.message}", e)
        }
    }

    // ==================== 资源管理 ====================

    override suspend fun stop() {
        super.stop()
        stopPolling()
        loginJob?.cancel()
    }

    fun logout() {
        loginJob?.cancel()
        pollJob?.cancel()
        wechatPrefs.edit().clear().apply()
        loginState = QrLoginStatus.WAIT
        qrCodeData = null
        qrError = null
        currentUserContext.set(false)
        lastFromUserId = ""
        lastContextToken = ""
        connected = false
        Log.d(TAG, "已登出")
    }
}
