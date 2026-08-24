package com.ai.assistance.quro.core.bot.adapters

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import java.util.concurrent.ConcurrentHashMap

/**
 * 消息轮询器（对齐 weixin_clawbot MessagePoller）。
 *
 * 职责：1. 长轮询循环 2. 消息解析 3. 错误处理和重连 4. 状态管理
 */
class MessagePoller(
    private val client: ILinkClient,
    private val account: ClawBotAccount,
    private val errorRetryDelay: Long = 5000L // 5秒
) {
    companion object {
        private const val TAG = "MessagePoller"
    }

    private val _messages = MutableSharedFlow<WeixinMessage>(extraBufferCapacity = 64)
    val messages: SharedFlow<WeixinMessage> = _messages.asSharedFlow()

    private var pollJob: Job? = null
    private var _running = false

    // 游标状态
    private var buf: String = ""

    // 上下文令牌缓存（内存）
    private val contextTokens = ConcurrentHashMap<String, String>()

    // 默认对端（第一个收到消息的用户）
    var defaultPeer: String? = null
        private set

    /**
     * 启动长轮询循环。
     */
    fun start(scope: CoroutineScope) {
        if (_running) return
        _running = true
        pollJob = scope.launch(Dispatchers.IO) {
            pollLoop()
        }
    }

    /**
     * 停止长轮询循环。
     */
    fun stop() {
        _running = false
        pollJob?.cancel()
        pollJob = null
    }

    /**
     * 获取指定用户的上下文令牌。
     */
    fun contextTokenFor(userId: String): String? = contextTokens[userId]

    /**
     * 回复指定用户（对齐 weixin_clawbot replyText）。
     */
    fun replyText(toUserId: String, text: String, contextToken: String? = null): SendResult {
        val token = contextToken ?: contextTokens[toUserId]
        return client.sendText(
            toUserId = toUserId,
            text = text,
            botId = account.botId,
            contextToken = token
        )
    }

    /**
     * 发送消息到默认对端（对齐 weixin_clawbot sendToDefault）。
     */
    fun sendToDefault(text: String): SendResult {
        val peer = defaultPeer ?: account.defaultTo
        if (peer.isNullOrBlank()) {
            return SendResult(
                ok = false,
                to = "",
                clientId = "",
                error = "No default peer – wait for an inbound message first"
            )
        }
        return replyText(toUserId = peer, text = text)
    }

    // ==================== 内部方法 ====================

    private suspend fun pollLoop() {
        Log.d(TAG, "开始长轮询循环, botId=${account.botId}")

        var pollTimeout = 35000 // 初始 35 秒

        while (_running) {
            try {
                val resp = client.getUpdates(
                    getUpdatesBuf = buf,
                    timeoutSeconds = pollTimeout / 1000
                )

                if (!_running) break

                // 使用服务端推荐的超时时间
                if (resp.longPollingTimeoutMs > 0) {
                    pollTimeout = resp.longPollingTimeoutMs
                }

                if (!resp.isOk) {
                    Log.w(TAG, "getupdates error ret=${resp.ret} errcode=${resp.errCode} ${resp.errMsg}")

                    // 错误处理
                    when (resp.errCode) {
                        -14 -> {
                            Log.e(TAG, "session 过期(errcode=-14)，停止轮询")
                            stop()
                            return
                        }
                        -2 -> {
                            Log.w(TAG, "触发频率限制(errcode=-2)，等待 10 秒后重试")
                            delay(10000)
                        }
                        else -> {
                            delay(errorRetryDelay)
                        }
                    }
                    continue
                }

                // 更新游标
                if (resp.getUpdatesBuf.isNotBlank() && resp.getUpdatesBuf != buf) {
                    buf = resp.getUpdatesBuf
                }

                // 处理消息
                for (msg in resp.messages) {
                    handleInbound(msg)
                }

            } catch (e: CancellationException) {
                // 协程取消，正常退出
                break
            } catch (e: Exception) {
                Log.e(TAG, "轮询异常: ${e.javaClass.simpleName}: ${e.message}")
                delay(errorRetryDelay)
            }
        }

        Log.d(TAG, "长轮询循环结束")
    }

    private suspend fun handleInbound(msg: WeixinMessage) {
        if (msg.fromUserId.isBlank()) return

        // 缓存上下文令牌
        if (!msg.contextToken.isNullOrBlank()) {
            contextTokens[msg.fromUserId] = msg.contextToken
            Log.d(TAG, "缓存 context_token: user=${msg.fromUserId}, token=${msg.contextToken.take(20)}...")
        }

        // 设置默认对端
        if (defaultPeer == null) {
            defaultPeer = msg.fromUserId
            Log.d(TAG, "设置默认对端: $defaultPeer")
        }

        // 发射消息到 Flow
        _messages.emit(msg)
    }
}

/**
 * Bot 账户数据类（对齐 weixin_clawbot ClawBotAccount）。
 */
data class ClawBotAccount(
    val id: String,
    val token: String,
    val baseUrl: String,
    val botId: String,
    val defaultTo: String? = null,
    val contextToken: String? = null
) {
    companion object {
        fun fromPrefs(prefs: android.content.SharedPreferences): ClawBotAccount? {
            val token = prefs.getString("wechat_token", "") ?: ""
            if (token.isBlank()) return null

            return ClawBotAccount(
                id = prefs.getString("wechat_bot_id", "") ?: "",
                token = token,
                baseUrl = prefs.getString("wechat_base_url", "https://ilinkai.weixin.qq.com") ?: "https://ilinkai.weixin.qq.com",
                botId = prefs.getString("wechat_bot_id", "") ?: "",
                defaultTo = prefs.getString("wechat_default_to", ""),
                contextToken = prefs.getString("wechat_context_token", "")
            )
        }

        fun save(account: ClawBotAccount, prefs: android.content.SharedPreferences) {
            prefs.edit().apply {
                putString("wechat_token", account.token)
                putString("wechat_base_url", account.baseUrl)
                putString("wechat_bot_id", account.botId)
                putString("wechat_default_to", account.defaultTo)
                putString("wechat_context_token", account.contextToken)
                apply()
            }
        }

        fun clear(prefs: android.content.SharedPreferences) {
            prefs.edit().apply {
                remove("wechat_token")
                remove("wechat_base_url")
                remove("wechat_bot_id")
                remove("wechat_default_to")
                remove("wechat_context_token")
                apply()
            }
        }
    }
}