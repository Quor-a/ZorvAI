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
import com.ai.assistance.quro.core.bot.adapters.TokenExpiredException

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
        saveSyncBuf = { buf -> wechatPrefs.edit().putString(KEY_SYNC_BUF, buf).apply() },
        onTokenExpired = {
            // 轮询时token过期，触发自动重新登录
            d(">>> 轮询时检测到token过期，触发自动重新登录")
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                autoRelogin()
            }
        }
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
        
        // 清除旧的会话上下文缓存，确保新登录不会使用旧数据
        currentUserContext.set(false)
        lastFromUserId = ""
        lastContextToken = ""
        contextTokens.clear()
        d("已清除旧的会话缓存，准备新登录")

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
                        contextToken = msg.contextToken,
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
        var token = wechatPrefs.getString(KEY_BOT_TOKEN, null)
        val baseUrl = wechatPrefs.getString(KEY_BASE_URL, DEFAULT_BASE_URL) ?: DEFAULT_BASE_URL
        // 发送者必须是机器人自己的 ilink_bot_id（登录时存为 KEY_ACCOUNT_ID）；空 sender 会被服务端丢弃
        val accountId = wechatPrefs.getString(KEY_ACCOUNT_ID, "")

        d("deliver 开始: to=$userId token=${token?.take(10) ?: "null"} baseUrl=$baseUrl accountId=$accountId contextToken=${msg.contextToken?.take(20) ?: "null"}")

        if (token.isNullOrEmpty()) {
            val errMsg = "微信发送失败：未登录（bot_token 为空，请重新扫码或手动填入 Token）"
            de(errMsg)
            lastError = errMsg
            // 抛异常让 processInbound 能捕获并回显到对话框
            throw IllegalStateException(errMsg)
        }

        // 使用传入的 context_token（协议要求精确匹配，否则回复会新建会话）
        var contextToken = msg.contextToken ?: ""

        // 如果 context_token 为空，等待用户发送新消息（最多30秒）
        if (contextToken.isEmpty()) {
            d("等待用户发送新消息以获取 context_token...")
            var waitTime = 0
            val maxWait = 30 // 30秒
            while (contextToken.isEmpty() && waitTime < maxWait) {
                kotlinx.coroutines.delay(1000)
                waitTime++
                contextToken = contextTokens[userId] ?: lastContextToken
                if (waitTime % 5 == 0) {
                    d("等待 context_token... (${waitTime}秒)")
                }
            }
            
            if (contextToken.isEmpty()) {
                val errMsg = "微信发送失败：缺少 context_token（该用户尚未发过消息，或会话已过期）"
                de(errMsg)
                lastError = errMsg
                throw IllegalStateException(errMsg)
            }
        }

        var retryCount = 0
        val maxRetries = 2 // 最多重试1次（总共2次尝试）
        
        while (retryCount < maxRetries) {
            try {
                apiClient.postSendMessage(
                    baseUrl, token, userId, msg.text, contextToken,
                    fromUserId = accountId ?: "",
                )
                d("消息已发送: to=$userId from=$accountId contextToken=${contextToken.take(20) ?: "null"}")
                lastError = null
                return
            } catch (e: TokenExpiredException) {
                // Token 过期异常，尝试自动重新登录
                retryCount++
                if (retryCount >= maxRetries) {
                    val errMsg = "微信发送失败：Token 已过期且重新登录失败，请手动重新扫码"
                    de("发送消息失败（token过期重试耗尽）", e)
                    lastError = errMsg
                    throw IllegalStateException(errMsg)
                }
                
                d(">>> Token 过期，尝试自动重新登录... (第${retryCount}次)")
                try {
                    // 执行自动重新登录（会清除旧 contextToken 缓存）
                    autoRelogin()
                    // 等待用户扫码（最多等待2分钟）
                    var waitTime = 0
                    val maxWait = 120 // 2分钟
                    while (loginState != QrLoginStatus.CONFIRMED && waitTime < maxWait) {
                        kotlinx.coroutines.delay(1000)
                        waitTime++
                        if (waitTime % 10 == 0) {
                            d("等待用户扫码... (${waitTime}秒)")
                        }
                    }
                    
                    if (loginState == QrLoginStatus.CONFIRMED) {
                        // 重新登录成功，更新 token
                        token = wechatPrefs.getString(KEY_BOT_TOKEN, null)
                        // 新 session 中旧 context_token 已失效，需要用户发新消息获取新的
                        contextToken = ""
                        d(">>> 重新登录成功，使用新 token 重试发送 (需要用户发新消息获取新的 context_token)")
                        
                        // 等待用户发送新消息以获取新的 context_token
                        var ctxWait = 0
                        val ctxMaxWait = 30
                        while (contextToken.isEmpty() && ctxWait < ctxMaxWait) {
                            kotlinx.coroutines.delay(1000)
                            ctxWait++
                            contextToken = contextTokens[userId] ?: lastContextToken
                            if (ctxWait % 5 == 0) d("等待新 context_token... (${ctxWait}秒)")
                        }
                        if (contextToken.isEmpty()) {
                            val errMsg = "微信发送失败：重新登录成功但无法获取新的 context_token，请让用户先发一条新消息"
                            lastError = errMsg
                            throw IllegalStateException(errMsg)
                        }
                        
                        continue
                    } else {
                        val errMsg = "微信发送失败：Token 已过期，等待重新扫码超时"
                        de("发送消息失败（等待扫码超时）", e)
                        lastError = errMsg
                        throw IllegalStateException(errMsg)
                    }
                } catch (e2: Exception) {
                    val errMsg = "微信发送失败：Token 过期后自动重新登录失败: ${e2.message}"
                    de("自动重新登录失败", e2)
                    lastError = errMsg
                    throw IllegalStateException(errMsg)
                }
            } catch (e: Exception) {
                val errMsg = "微信发送失败：${e.message ?: "未知错误"}"
                de("发送消息失败", e)
                lastError = errMsg
                // 抛异常让 processInbound 能捕获并回显到对话框
                throw e
            }
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

    /**
     * 自动重新登录：当 token 过期时，清除旧缓存并启动新的扫码登录流程。
     * 用户需要手动扫描新的二维码。
     */
    private suspend fun autoRelogin() {
        d(">>> 检测到 token 过期，自动启动重新登录流程...")
        
        // 1. 停止当前轮询
        stopPolling()
        
        // 2. 清除旧 token 和缓存
        wechatPrefs.edit().apply {
            remove(KEY_BOT_TOKEN)
            remove(KEY_ACCOUNT_ID)
            remove(KEY_USER_ID)
            putBoolean(KEY_LOGGED_IN, false)
            apply()
        }
        
        // 3. 清除所有会话上下文缓存
        // 协议要求 context_token 必须与当前 bot_token 匹配，旧 token 过期后 context_token 也失效
        // 不清除会导致 deliver() 使用已失效的旧 context_token，发送失败
        currentUserContext.set(false)
        lastFromUserId = ""
        lastContextToken = ""
        contextTokens.clear()
        d("已清除所有会话上下文缓存（旧 context_token 在新 session 中无效）")
        
        // 4. 重置登录状态，准备新的扫码流程
        loginState = QrLoginStatus.WAIT
        qrCodeData = null
        qrError = "Token 已过期，请重新扫码登录"
        
        // 5. 启动新的扫码登录（不直接调用startQrLogin，避免状态冲突）
        loginJob?.cancel()
        loginJob = CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                d(">>> 开始获取新的二维码...")
                val baseUrl = wechatPrefs.getString(KEY_BASE_URL, DEFAULT_BASE_URL) ?: DEFAULT_BASE_URL
                val botType = wechatPrefs.getString(KEY_BOT_TYPE, DEFAULT_BOT_TYPE) ?: DEFAULT_BOT_TYPE
                
                // 获取新的二维码
                val qrResponse = apiClient.getBotQrcode(baseUrl, botType)
                val qrcode = qrResponse.optString("qrcode", "")
                val qrcodeImgContent = qrResponse.optString("qrcode_img_content", "")
                
                d(">>> 新二维码获取成功: qrcode=${qrcode.take(50)}, imgContent=${qrcodeImgContent.take(50)}")
                
                if (qrcode.isEmpty()) {
                    loginState = QrLoginStatus.UNKNOWN
                    qrError = "获取新二维码失败：服务器返回空二维码"
                    return@launch
                }
                
                qrCodeData = qrcodeImgContent.takeIf { it.isNotEmpty() } ?: qrcode
                loginState = QrLoginStatus.SCANNED
                d("新二维码已就绪，等待扫码...")
                
                // 轮询扫码状态
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
                                
                                d(">>> 重新登录成功! token=${token.take(20)}..., accountId=$accountId")
                                
                                // 保存新登录信息
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
                                qrError = "新二维码已过期"
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
                    qrError = "扫码超时，请重新获取二维码"
                }
            } catch (e: Exception) {
                de("自动重新登录失败", e)
                loginState = QrLoginStatus.UNKNOWN
                qrError = "自动重新登录失败: ${e.message}"
            }
        }
        
        d(">>> 已启动自动重新登录，请扫描新的二维码")
    }

    // ==================== 状态查询 ====================

    fun isLoggedIn(): Boolean = wechatPrefs.getBoolean(KEY_LOGGED_IN, false)

    // 注意：QrLoginStatus 使用同包顶层定义（ILinkClient.kt），
    // 切勿在此嵌套同名枚举，否则 UI 的 when 因类型不一致而整块不渲染二维码。
}
