package com.ai.assistance.quro.core.bot.adapters

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * 微信 ClawBot 门面类（对齐 weixin_clawbot WeixinClawbot）。
 *
 * 职责：1. 账户管理 2. 二维码登录 3. 消息收发 4. 资源管理
 */
class WeixinClawbot(private val context: Context) {
    companion object {
        private const val TAG = "WeixinClawbot"
    }

    val accountStore = AccountStore.getInstance(context)
    private val _messages = MutableSharedFlow<WeixinMessage>(extraBufferCapacity = 64)
    val messages: SharedFlow<WeixinMessage> = _messages.asSharedFlow()

    // 活跃的轮询器
    private val pollers = ConcurrentHashMap<String, MessagePoller>()

    // 当前作用域
    private var scope: CoroutineScope? = null

    /**
     * 启动二维码登录（对齐 weixin_clawbot startQrLogin）。
     * 返回二维码数据和状态更新的 Flow。
     */
    fun startQrLogin(): QrLoginFlow {
        return QrLoginFlow(context, this)
    }

    /**
     * 连接到指定账户（对齐 weixin_clawbot connect）。
     * 启动长轮询，返回消息流。
     */
    fun connect(account: ClawBotAccount): SharedFlow<WeixinMessage> {
        if (pollers.containsKey(account.id)) {
            Log.d(TAG, "轮询器已存在，复用: ${account.id}")
            return pollers[account.id]!!.messages
        }

        val client = ILinkClient(
            baseUrl = account.baseUrl,
            token = account.token
        )
        val poller = MessagePoller(client, account)
        pollers[account.id] = poller

        // 启动轮询
        val pollScope = scope ?: CoroutineScope(Dispatchers.IO + SupervisorJob())
        poller.start(pollScope)

        // 内部监听：持久化上下文令牌
        pollScope.launch {
            poller.messages.collect { msg ->
                if (!msg.contextToken.isNullOrBlank()) {
                    accountStore.updateContextToken(
                        accountId = account.id,
                        userId = msg.fromUserId,
                        contextToken = msg.contextToken
                    )
                }
                _messages.emit(msg)
            }
        }

        return poller.messages
    }

    /**
     * 连接已保存的账户（对齐 weixin_clawbot loadAccount + connect）。
     */
    fun connectSaved(): SharedFlow<WeixinMessage>? {
        val account = accountStore.loadFirst() ?: return null
        return connect(account)
    }

    /**
     * 发送文本消息（对齐 weixin_clawbot sendText）。
     */
    suspend fun sendText(
        text: String,
        toUserId: String? = null,
        accountId: String? = null
    ): SendResult {
        val poller = if (accountId != null) {
            pollers[accountId]
        } else {
            pollers.values.firstOrNull()
        }

        if (poller == null) {
            return SendResult(
                ok = false,
                to = "",
                clientId = "",
                error = "No active connection – call connect() first"
            )
        }

        val to = toUserId ?: poller.defaultPeer
        if (to.isNullOrBlank()) {
            return SendResult(
                ok = false,
                to = "",
                clientId = "",
                error = "No recipient – provide toUserId or wait for an inbound message"
            )
        }

        return withContext(Dispatchers.IO) {
            poller.replyText(toUserId = to, text = text)
        }
    }

    /**
     * 断开指定账户（对齐 weixin_clawbot disconnect）。
     */
    fun disconnect(accountId: String) {
        pollers.remove(accountId)?.stop()
    }

    /**
     * 登出指定账户（对齐 weixin_clawbot logout）。
     */
    fun logout(accountId: String? = null) {
        if (accountId != null) {
            pollers.remove(accountId)?.stop()
            accountStore.remove(accountId)
        } else {
            pollers.values.forEach { it.stop() }
            pollers.clear()
            accountStore.clear()
        }
    }

    /**
     * 设置协程作用域。
     */
    fun setScope(scope: CoroutineScope) {
        this.scope = scope
    }

    /**
     * 释放资源（对齐 weixin_clawbot dispose）。
     */
    fun dispose() {
        pollers.values.forEach { it.stop() }
        pollers.clear()
        accountStore.dispose()
    }
}

/**
 * 二维码登录流程（对齐 weixin_clawbot QrLoginFlow）。
 */
class QrLoginFlow(
    private val context: Context,
    private val clawbot: WeixinClawbot
) {
    companion object {
        private const val TAG = "QrLoginFlow"
    }

    private val client = ILinkClient()

    /**
     * 开始登录流程，返回二维码数据和状态更新。
     */
    fun startLogin(scope: CoroutineScope): QrLoginSession {
        val session = QrLoginSession(context, client, clawbot)
        session.start(scope)
        return session
    }
}

/**
 * 二维码登录会话。
 */
class QrLoginSession(
    private val context: Context,
    private val client: ILinkClient,
    private val clawbot: WeixinClawbot
) {
    companion object {
        private const val TAG = "QrLoginSession"
    }

    private val _events = MutableSharedFlow<QrLoginEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<QrLoginEvent> = _events.asSharedFlow()

    private var pollJob: Job? = null

    /**
     * 开始登录流程。
     */
    fun start(parentScope: CoroutineScope) {
        // 使用独立的SupervisorJob，避免被父scope取消
        val job = SupervisorJob()
        val loginScope = CoroutineScope(job + Dispatchers.IO)
        pollJob = Job(job) // pollJob取消时也取消独立scope

       loginScope.launch {
            try {
                Log.d(TAG, "开始获取二维码...")
                // 1. 获取二维码
                val qrResponse = client.fetchLoginQrCode()
                Log.d(TAG, "二维码响应: qrCode=${qrResponse.qrCode}, imgContent=${qrResponse.qrCodeImgContent.take(50)}")
                if (qrResponse.qrCode.isBlank()) {
                    Log.e(TAG, "二维码获取失败: qrCode为空")
                    _events.emit(QrLoginEvent.Error("二维码获取失败：服务器未返回二维码"))
                    return@launch
                }

                _events.emit(QrLoginEvent.QrReady(qrResponse.qrCodeImgContent))

                // 2. 轮询状态
                val maxPolls = 100
                for (i in 0 until maxPolls) {
                    delay(3000) // 每3秒轮询一次

                    val statusResponse = client.pollQrStatus(qrResponse.qrCode)
                    Log.d(TAG, "二维码状态: ${statusResponse.status}")

                    when (statusResponse.status) {
                        QrLoginStatus.WAIT -> { /* 继续等 */ }
                        QrLoginStatus.SCANNED -> {
                            _events.emit(QrLoginEvent.Scanned)
                        }
                        QrLoginStatus.CONFIRMED -> {
                            val token = statusResponse.botToken ?: ""
                            if (token.isBlank()) {
                                _events.emit(QrLoginEvent.Error("登录成功但未获取到token"))
                                return@launch
                            }

                            // 保存账户
                            val account = ClawBotAccount(
                                id = statusResponse.ilinkBotId ?: "",
                                token = token,
                                baseUrl = statusResponse.baseUrl ?: "https://ilinkai.weixin.qq.com",
                                botId = statusResponse.ilinkBotId ?: "",
                                defaultTo = statusResponse.ilinkUserId
                            )
                            clawbot.accountStore.save(account)

                            _events.emit(QrLoginEvent.Confirmed(account))

                            // 启动连接
                            clawbot.connect(account)
                            return@launch
                        }
                        QrLoginStatus.EXPIRED -> {
                            _events.emit(QrLoginEvent.Expired)
                            return@launch
                        }
                        QrLoginStatus.UNKNOWN -> {
                            Log.w(TAG, "未知状态: ${statusResponse.status}")
                        }
                    }
                }

                // 超时
                _events.emit(QrLoginEvent.Expired)

            } catch (e: Exception) {
                Log.e(TAG, "登录异常: ${e.javaClass.simpleName}: ${e.message}", e)
                _events.emit(QrLoginEvent.Error("登录失败: ${e.message ?: "未知错误"}"))
            }
        }
    }

    /**
     * 取消登录流程。
     */
    fun cancel() {
        pollJob?.cancel()
    }
}

/**
 * 二维码登录事件（对齐 weixin_clawbot QrLoginEvent）。
 */
sealed class QrLoginEvent {
    data class QrReady(val qrContent: String) : QrLoginEvent()
    object Scanned : QrLoginEvent()
    data class Confirmed(val account: ClawBotAccount) : QrLoginEvent()
    object Expired : QrLoginEvent()
    data class Error(val message: String) : QrLoginEvent()
}