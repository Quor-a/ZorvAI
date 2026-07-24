package com.ai.assistance.quro.core.bot.adapters

import android.content.Context
import com.ai.assistance.quro.core.bot.QuroBotPlatform
import com.ai.assistance.quro.core.bot.QuroOutboundMessage
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * QQ 机器人 V2 适配器（直连官方网关，零公网端点）。
 *
 * 接入形态（元宝核实）：
 *  - 换 token：POST https://bots.qq.com/app/getAppAccessToken（appId + clientSecret）→ access_token
 *  - 拿 WS 网关：GET https://api.sgroup.qq.com/gateway/bot（Authorization: QQBot {token}）→ wss 地址
 *  - 收消息：WebSocket 长连，op=0 DISPATCH 的 C2C_MESSAGE_CREATE 事件
 *  - 回消息：POST https://api.sgroup.qq.com/v2/users/{openid}/messages（被动回复，5 分钟内）
 *  - 心跳：HELLO 给 heartbeat_interval，客户端周期发 op=1 HEARTBEAT
 *  - Intent：1<<25（C2C + 群@，沙箱期仅私聊可用）
 *
 * 仅用 OkHttp（含 WebSocket）+ org.json，不引入官方 SDK。
 */
class QuroQqBotAdapter(context: Context) : QuroDirectBotAdapter(context) {
    override val platform = QuroBotPlatform.QQ

    private val appId get() = pref("qq_appid")
    private val appSecret get() = pref("qq_secret")

    private var accessToken: String = ""
    private var ws: WebSocket? = null
    private val alive = AtomicBoolean(false)
    /** WS 真实连接状态（onOpen→true, onClosed/onFailure→false），供 UI 读取。 */
    val wsConnected = AtomicBoolean(false)
    private val lastSeq = AtomicLong(0)
    private var heartbeatJob: kotlinx.coroutines.Job? = null

    override fun isConfigured(): Boolean = appId.isNotBlank() && appSecret.isNotBlank()

    /** 覆盖基类：不立即标 connected=true，等 WS onOpen 后再标真实状态。 */
    override suspend fun start() {
        if (!isConfigured()) {
            Log_w("QQ 未配置，跳过 start")
            connected = false
            return
        }
        if (connJob?.isActive == true) return
        stopped.set(false)
        wsConnected.set(false)
        connJob = scope.launch {
            try {
                runConnection()
            } catch (e: Exception) {
                Log_e("连接循环异常退出: ${e.message}")
            } finally {
                connected = false
                wsConnected.set(false)
            }
        }
        Log_i("QQ 适配器已启动（等待 WS 连接...）")
    }

    override suspend fun runConnection() {
        var retries = 0
        while (!stopped.get()) {
            alive.set(true)
            try {
                val tokenJson = httpPostJson(
                    "https://bots.qq.com/app/getAppAccessToken",
                    json = JSONObject().apply {
                        put("appId", appId)
                        put("clientSecret", appSecret)
                    }.toString(),
                ) ?: run { alive.set(false); backoff(retries++); continue }
                accessToken = tokenJson.optString("access_token").also {
                    if (it.isBlank()) { alive.set(false); backoff(retries++); continue }
                }

                val gw = httpGetString(
                    "https://api.sgroup.qq.com/gateway/bot",
                    headers = mapOf("Authorization" to "QQBot $accessToken"),
                ) ?: run { alive.set(false); backoff(retries++); continue }
                val wsUrl = JSONObject(gw).optString("url").also {
                    if (it.isBlank()) { alive.set(false); backoff(retries++); continue }
                }

                retries = 0
                val listener = QqWsListener()
                val req = Request.Builder().url(wsUrl).build()
                ws = client.newWebSocket(req, listener)
                // 阻塞直到连接断开（listener 在关闭时置 alive=false）
                while (alive.get() && !stopped.get()) delay(1000)
            } catch (e: Exception) {
                Log_e("runConnection 异常: ${e.message}")
            } finally {
                heartbeatJob?.cancel()
                ws?.cancel()
                ws = null
                alive.set(false)
            }
            if (!stopped.get()) backoff(retries++)
        }
    }

    override fun onDisconnect() {
        alive.set(false)
        heartbeatJob?.cancel()
        ws?.cancel()
        ws = null
    }

    override suspend fun deliver(reply: QuroOutboundMessage) {
        // token 为空时尝试刷新一次（可能 WS 重连后 token 丢失/过期）
        if (accessToken.isBlank()) {
            Log_w("deliver 时 accessToken 为空，尝试刷新...")
            val tokenJson = httpPostJson(
                "https://bots.qq.com/app/getAppAccessToken",
                json = JSONObject().apply {
                    put("appId", appId)
                    put("clientSecret", appSecret)
                }.toString(),
            )
            accessToken = tokenJson?.optString("access_token").orEmpty()
            if (accessToken.isBlank()) {
                Log_e("deliver 失败：token 刷新也为空，无法发送回复给 user=${reply.userId}")
                return
            }
        }
        // 被动回复：POST /v2/users/{openid}/messages，msg_type=text；
        // QQBot 要求 content 为 JSON 字符串（{"text":"..."}），裸文本会被拒
        val body = JSONObject().apply {
            put("msg_type", "text")
            put("content", JSONObject().put("text", reply.text).toString())
        }.toString()
        val json = httpPostJson(
            "https://api.sgroup.qq.com/v2/users/${reply.userId}/messages",
            headers = mapOf("Authorization" to "QQBot $accessToken"),
            json = body,
        )
        if (json == null) Log_e("deliver 失败 user=${reply.userId}（HTTP 错误或网络异常）")
        else Log_i("deliver 已发往 QQ 用户 ${reply.userId}")
    }

    private fun startHeartbeat(intervalMs: Long) {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (alive.get() && isActive) {
                delay(intervalMs)
                try { ws?.send(JSONObject().put("op", 1).put("d", if (lastSeq.get() > 0) lastSeq.get() else JSONObject.NULL).toString()) }
                catch (e: Exception) { Log_e("heartbeat 失败: ${e.message}") }
            }
        }
    }

    private inner class QqWsListener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            Log_i("WS 已连接（真实握手成功）")
            wsConnected.set(true)
            connected = true
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            runCatching {
                val msg = JSONObject(text)
                when (val op = msg.optInt("op", -1)) {
                    10 -> { // HELLO
                        val interval = msg.optJSONObject("d")?.optLong("heartbeat_interval", 30000) ?: 30000
                        // IDENTIFY
                        val identify = JSONObject().apply {
                            put("op", 2)
                            put("d", JSONObject().apply {
                                put("token", "QQBot $accessToken")
                                put("intents", 1 shl 25)
                            })
                        }.toString()
                        webSocket.send(identify)
                        startHeartbeat(interval)
                        Log_i("IDENTIFY 已发，心跳间隔 ${interval}ms")
                    }
                    0 -> { // DISPATCH
                        val seq = msg.optLong("s", 0)
                        if (seq > 0) lastSeq.set(seq)
                        val t = msg.optString("t")
                        val d = msg.optJSONObject("d") ?: return@runCatching
                        if (t == "C2C_MESSAGE_CREATE") {
                            val openid = d.optJSONObject("author")?.optString("user_openid").orEmpty()
                            var content = d.optString("content", "").trim()
                            // C2C 内容可能带前导 "/" 指令 token，去掉
                            content = content.removePrefix("/").trim()
                            if (openid.isNotBlank() && content.isNotBlank()) {
                                onInbound(openid, openid, content)
                            }
                        }
                    }
                    11 -> { /* HEARTBEAT ACK */ }
                    7, 12 -> { // INVALID SESSION / RECONNECT
                        Log_w("收到重连指令 op=$op，关闭重连")
                        alive.set(false)
                        webSocket.cancel()
                    }
                }
            }.onFailure { e -> Log_e("onMessage 解析失败: ${e.message}") }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            Log_w("WS closing $code $reason")
            wsConnected.set(false)
            webSocket.cancel()
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Log_w("WS closed $code $reason")
            wsConnected.set(false)
            connected = false
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log_e("WS failure: ${t.message}")
            wsConnected.set(false)
            connected = false
        }
    }

    private fun Log_i(s: String) = android.util.Log.i(TAG, "[QQ] $s")
    private fun Log_w(s: String) = android.util.Log.w(TAG, "[QQ] $s")
    private fun Log_e(s: String) = android.util.Log.e(TAG, "[QQ] $s")
}
