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

/**
 * 飞书（Lark）自建应用机器人适配器（直连官方 WS 网关，零公网端点）。
 *
 * 接入形态（元宝核实）：
 *  - 换 token：POST https://open.feishu.cn/open-apis/auth/v3/tenant_access_token/internal（app_id + app_secret）→ tenant_access_token
 *  - 收消息：WebSocket 长连 wss://open.feishu.cn/open-apis/ws/v1?access_token=...（官方 SDK 内部即此地址；
 *    后台「事件订阅」选「使用长连接接收事件」后无需填回调 URL）
 *  - 心跳：服务端发 {"type":"ping","sn":N} → 客户端回 {"type":"pong","sn":N}
 *  - 事件：{"type":"message","event":{...im.message.receive_v1...}}
 *  - 回消息：POST https://open.feishu.cn/open-apis/im/v1/messages?receive_id_type=chat_id（Bearer token）
 */
class QuroFeishuBotAdapter(context: Context) : QuroDirectBotAdapter(context) {
    override val platform = QuroBotPlatform.FEISHU

    private val appId get() = pref("feishu_appid")
    private val appSecret get() = pref("feishu_secret")

    private var tenantToken: String = ""
    private var ws: WebSocket? = null
    private val alive = AtomicBoolean(false)
    /** WS 真实连接状态（onOpen→true, onClosed/onFailure→false），供 UI 读取。 */
    val wsConnected = AtomicBoolean(false)

    override fun isConfigured(): Boolean = appId.isNotBlank() && appSecret.isNotBlank()

    /** 覆盖基类：不立即标 connected=true，等 WS onOpen 后再标真实状态。 */
    override suspend fun start() {
        if (!isConfigured()) {
            Log_w("飞书 未配置，跳过 start")
            connected = false
            return
        }
        if (connJob?.isActive == true) return
        stopped.set(false)
        wsConnected.set(false)
        connJob = scope.launch {
            try { runConnection() } catch (e: Exception) { Log_e("连接循环异常退出: ${e.message}") }
            finally { connected = false; wsConnected.set(false) }
        }
        Log_i("飞书 适配器已启动（等待 WS 连接...）")
    }

    override suspend fun runConnection() {
        var retries = 0
        while (!stopped.get()) {
            alive.set(true)
            try {
                val tkn = httpPostJson(
                    "https://open.feishu.cn/open-apis/auth/v3/tenant_access_token/internal",
                    json = JSONObject().apply {
                        put("app_id", appId)
                        put("app_secret", appSecret)
                    }.toString(),
                ) ?: run {
                    lastError = "获取飞书 tenant_access_token 失败（app_id/secret 错误或网络不通）"
                    alive.set(false); backoff(retries++); continue
                }
                tenantToken = tkn.optString("tenant_access_token").also {
                    if (it.isBlank()) {
                        lastError = "获取飞书 tenant_access_token 返回为空（app_id/secret 无效）"
                        alive.set(false); backoff(retries++); continue
                    }
                }

                // 飞书 WS 端点必须带 trailing slash（v1/ 后的 /），否则部分网关版本返回 404
                // 参照：开源 Rust 实现 feishu_adapter.rs 用 wss://open.feishu.cn/open-apis/ws/v1/?tenant_access_token=...
                val wsUrl = "wss://open.feishu.cn/open-apis/ws/v1/?tenant_access_token=${tenantToken.urlEncode()}"
                retries = 0
                Log_i("WS 连接中: wss://open.feishu.cn/open-apis/ws/v1/?tenant_access_token=***(len=${tenantToken.length})")
                val req = Request.Builder().url(wsUrl).build()
                ws = client.newWebSocket(req, FeishuWsListener())
                while (alive.get() && !stopped.get()) delay(1000)
            } catch (e: Exception) {
                Log_e("runConnection 异常: ${e.message}")
            } finally {
                ws?.cancel()
                ws = null
                alive.set(false)
            }
            if (!stopped.get()) backoff(retries++)
        }
    }

    override fun onDisconnect() {
        alive.set(false)
        ws?.cancel()
        ws = null
    }

    override suspend fun deliver(reply: QuroOutboundMessage) {
        // token 为空时尝试刷新一次
        if (tenantToken.isBlank()) {
            Log_w("deliver 时 tenantToken 为空，尝试刷新...")
            val tkn = httpPostJson(
                "https://open.feishu.cn/open-apis/auth/v3/tenant_access_token/internal",
                json = JSONObject().apply { put("app_id", appId); put("app_secret", appSecret) }.toString(),
            )
            tenantToken = tkn?.optString("tenant_access_token").orEmpty()
            if (tenantToken.isBlank()) {
                lastError = "回复发送失败：飞书 token 刷新为空（app_id/secret 无效）"
                Log_e("deliver 失败：token 刷新也为空，无法发送回复给 chat=${reply.userId}")
                return
            }
        }
        // receive_id 用 chat_id（reply.userId 即聊天 id）；content 必须是 JSON 字符串
        val content = JSONObject().put("text", reply.text).toString()
        val body = JSONObject().apply {
            put("receive_id", reply.userId)
            put("msg_type", "text")
            put("content", content)
        }.toString()
        val json = httpPostJson(
            "https://open.feishu.cn/open-apis/im/v1/messages?receive_id_type=chat_id",
            headers = mapOf("Authorization" to "Bearer $tenantToken"),
            json = body,
        )
        if (json == null) {
            lastError = "回复发送失败 chat=${reply.userId}（HTTP 错误或网络异常）"
            Log_e("deliver 失败 chat=${reply.userId}（HTTP 错误或网络异常）")
        } else {
            lastError = null
            Log_i("deliver 已发往飞书会话 ${reply.userId}")
        }
    }

    private inner class FeishuWsListener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            Log_i("WS 已连接（真实握手成功）")
            lastError = null
            wsConnected.set(true)
            connected = true
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            runCatching {
                val msg = JSONObject(text)
                when (msg.optString("type")) {
                    "hello" -> Log_i("hello 收到，等待事件")
                    "ping" -> { // 必须回 pong（带相同 sn）保活
                        val sn = msg.optLong("sn", 0)
                        webSocket.send(JSONObject().put("type", "pong").put("sn", sn).toString())
                    }
                    "pong" -> { /* 客户端心跳应答，忽略 */ }
                    "message" -> {
                        val event = msg.optJSONObject("event") ?: return@runCatching
                        val message = event.optJSONObject("message") ?: return@runCatching
                        // 过滤机器人自身发出的消息，避免回复死循环
                        // （im.message.receive_v1 会把机器人自己的回复也回投为事件）
                        val sender = event.optJSONObject("sender")
                        if (sender?.optString("sender_type", "") == "app") {
                            Log_i("忽略机器人自身消息（sender_type=app），避免回环")
                            return@runCatching
                        }
                        val contentStr = message.optString("content", "")
                        val textBody = if (contentStr.isNotBlank()) {
                            runCatching { JSONObject(contentStr).optString("text", "") }.getOrDefault("")
                        } else ""
                        val chatId = message.optString("chat_id", "")
                        if (chatId.isNotBlank() && textBody.isNotBlank()) {
                            onInbound(chatId, chatId, textBody)
                        }
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
            if (code != 1000 && code != 1001) {
                lastError = "WS 已断开（code=$code ${reason.ifBlank { "无原因" }}）"
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            val httpInfo = response?.let { "HTTP ${it.code} ${it.message}" } ?: ""
            val respBody = response?.body?.string().orEmpty().take(300)
            // 404 最常见原因：应用未启用「长连接接收事件」或未发布
            if (response?.code == 404) {
                Log_e("WS 404: 飞书应用可能未启用「长连接接收事件」模式！请到飞书开放后台→应用→事件订阅→选「长连接」→保存并发布。$httpInfo body=$respBody")
            } else {
                Log_e("WS failure: ${t.javaClass.simpleName}: ${t.message} $httpInfo")
            }
            lastError = when (response?.code) {
                404 -> "WS 被拒(404)：请确认飞书后台已启用「长连接接收事件」并发布应用"
                401, 403 -> "WS 被拒(${response.code})：token 无效或应用无权限"
                else -> "WS 连接失败：${t.message ?: "未知"} $httpInfo"
            }
            wsConnected.set(false)
            connected = false
        }
    }

    private fun String.urlEncode(): String =
        java.net.URLEncoder.encode(this, "UTF-8")

    private fun Log_i(s: String) = android.util.Log.i(TAG, "[Feishu] $s")
    private fun Log_w(s: String) = android.util.Log.w(TAG, "[Feishu] $s")
    private fun Log_e(s: String) = android.util.Log.e(TAG, "[Feishu] $s")
}
