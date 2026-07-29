package com.ai.assistance.quro.core.mcp

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * QuroMcpWsClient（原创）：MCP 的 WebSocket 传输实现。
 *
 * 与 [QuroMcpClient] 的 HTTP / StreamableHTTP 互补，覆盖以 ws:// 或 wss:// 暴露的 MCP 服务器：
 * 建立连接 → 发送 JSON-RPC 请求 → 等待首个非通知（不含 method 字段）响应 → 关闭。
 */
object QuroMcpWsClient {
    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    suspend fun callToolWs(
        url: String,
        method: String,
        params: JSONObject,
        token: String = "",
    ): JSONObject = suspendCancellableCoroutine { cont ->
        val reqBuilder = Request.Builder().url(url.trim())
        if (token.isNotBlank()) reqBuilder.addHeader("Authorization", "Bearer $token")
        reqBuilder.addHeader("MCP-Protocol-Version", "2025-03-26")
        val ws = client.newWebSocket(reqBuilder.build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val msg = JSONObject()
                    .put("jsonrpc", "2.0")
                    .put("id", 1)
                    .put("method", method)
                    .put("params", params)
                    .toString()
                webSocket.send(msg)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val obj = runCatching { JSONObject(text) }.getOrNull()
                // 忽略通知（含 method 字段的服务器推送），只认最终响应
                if (obj != null && !obj.has("method")) {
                    if (cont.isActive) cont.resume(obj)
                    runCatching { webSocket.close(1000, "done") }
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (cont.isActive) {
                    cont.resume(JSONObject().put("error", JSONObject().put("message", "WS 连接失败: ${t.message}")))
                }
            }
        })
        cont.invokeOnCancellation { runCatching { ws.close(1000, "cancel") } }
    }
}
