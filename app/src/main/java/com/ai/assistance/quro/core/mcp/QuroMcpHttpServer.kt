package com.ai.assistance.quro.core.mcp

import com.ai.assistance.quro.BuildConfig
import android.content.Context
import android.util.Log
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import com.ai.assistance.quro.core.tools.QuroToolEngine
import com.ai.assistance.quro.core.tools.buildQuroRegistry
import com.ai.assistance.quro.core.tools.jsonToMap
import com.ai.assistance.quro.core.tools.toMcpTool

/**
 * QuroAI 本地 MCP Server（零依赖实现）。
 *
 * ⚠️ 关于传输层：Android 运行时**不含** `com.sun.net.httpserver.HttpServer`（它不是 Android
 * 核心库的一部分，运行时必抛 ClassNotFound）。因此这里直接基于 [java.net.ServerSocket]
 * 手写最小 HTTP/1.1 服务，完全零三方依赖、在 Android 上 100% 可用。
 *
 * 协议：JSON-RPC 2.0 over HTTP（single POST，plain JSON 响应，兼容 MCP Inspector 的 HTTP 模式）。
 * 仅监听 **127.0.0.1**（环回），外部网络不可达，天然规避暴露风险。
 *
 * 支持的 method：
 * - `initialize`            → 返回协议能力 / serverInfo
 * - `notifications/initialized` 等通知类方法 → 202 空响应（通知，无 id）
 * - `ping`                  → {result:{}}
 * - `tools/list`            → 复用 [DroidMcp.listToolsJson]（47 个工具）
 * - `tools/call`            → 复用 [DroidMcp.callTool]（经原创 QuroTool 引擎派发）
 *
 * 工具实现与「AI 默认动作空间」100% 同源：本 Server 与对话内的工具调用走同一份
 * [com.ai.assistance.quro.core.tools.QuroTool] 真相源，无需复制。
 */
class QuroMcpHttpServer(private val appContext: Context) {

    private val engine = QuroToolEngine(buildQuroRegistry(appContext))
    private val droidMcp: DroidMcp = DroidMcp.builder()
        .addTools(buildQuroRegistry(appContext).all().map { it.toMcpTool(engine) })
        .build()

    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null

    @Volatile var port: Int = 0
        private set
    @Volatile var running: Boolean = false
        private set

    val endpoint: String get() = "http://127.0.0.1:$port/mcp"
    val toolCount: Int get() = runCatching { droidMcp.listTools().size }.getOrDefault(0)

    /** 启动服务；成功返回监听端口，失败抛异常。仅绑定 127.0.0.1。 */
    fun start(): Int {
        if (running) return port
        engine.setContext(appContext)
        val ss = ServerSocket(0, 0, InetAddress.getByName("127.0.0.1"))
        port = ss.localPort
        serverSocket = ss
        running = true
        acceptThread = Thread({ acceptLoop(ss) }, "QuroMcpServer").also { it.isDaemon = true; it.start() }
        return port
    }

    fun stop() {
        running = false
        try { serverSocket?.close() } catch (_: Throwable) {}
        serverSocket = null
        acceptThread = null
    }

    private fun acceptLoop(ss: ServerSocket) {
        while (running && !ss.isClosed) {
            try {
                val sock = ss.accept() ?: continue
                Thread({ handle(sock) }, "QuroMcpClient").also { it.isDaemon = true; it.start() }
            } catch (e: SocketException) {
                if (running) Log.w(TAG, "MCP accept 中断：${e.message}")
                break
            } catch (e: Throwable) {
                Log.w(TAG, "MCP accept 异常：${e.message}")
            }
        }
    }

    private fun handle(sock: Socket) {
        try {
            sock.soTimeout = 30000
            val input = BufferedReader(InputStreamReader(sock.getInputStream(), Charsets.UTF_8))
            // 读请求行
            val requestLine = input.readLine() ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) { send(sock, 400, "text/plain", "Bad Request"); return }
            val method = parts[0]
            // 跳过其余请求头直到空行
            var contentLength = 0
            while (true) {
                val line = input.readLine() ?: break
                if (line.isEmpty()) break
                val lower = line.lowercase()
                if (lower.startsWith("content-length:")) {
                    contentLength = lower.substringAfter(":").trim().toIntOrNull() ?: 0
                }
            }
            val body = if (contentLength > 0) {
                val buf = CharArray(contentLength)
                var read = 0
                while (read < contentLength) {
                    val n = input.read(buf, read, contentLength - read)
                    if (n < 0) break
                    read += n
                }
                String(buf, 0, read)
            } else ""

            when (method) {
                "POST" -> dispatchRpc(sock, body)
                "OPTIONS" -> send(sock, 204, "text/plain", "", mapOf(
                    "Access-Control-Allow-Origin" to "*",
                    "Access-Control-Allow-Methods" to "POST, OPTIONS",
                    "Access-Control-Allow-Headers" to "*"
                ))
                else -> send(sock, 405, "text/plain", "Method Not Allowed")
            }
        } catch (e: Throwable) {
            Log.w(TAG, "MCP 处理异常：${e.message}")
        } finally {
            try { sock.close() } catch (_: Throwable) {}
        }
    }

    private fun dispatchRpc(sock: Socket, body: String) {
        val id: Any?
        val method: String
        val params: JSONObject?
        try {
            val req = JSONObject(body)
            id = if (req.has("id")) req.opt("id") else null
            method = req.optString("method", "")
            params = if (req.has("params")) req.optJSONObject("params") else null
        } catch (e: Throwable) {
            send(sock, 200, "application/json", jsonRpcError(null, -32700, "Parse error"))
            return
        }

        // 通知（无 id）→ 202 空响应
        if (id == null) {
            send(sock, 202, "application/json", "", mapOf("Access-Control-Allow-Origin" to "*"))
            return
        }

        try {
            val result: JSONObject? = when (method) {
                "initialize" -> JSONObject()
                    .put("protocolVersion", "2024-11-05")
                    .put("capabilities", JSONObject().put("tools", JSONObject()))
                    .put("serverInfo", JSONObject().put("name", "QuroAI").put("version", BuildConfig.VERSION_NAME))
                "ping" -> JSONObject()
                "tools/list" -> JSONObject().put("tools", JSONArray(droidMcp.listToolsJson()))
                "tools/call" -> {
                    val name = params?.optString("name", "") ?: ""
                    val argsObj = params?.optJSONObject("arguments") ?: JSONObject()
                    val argsMap = jsonToMap(argsObj.toString())
                    val res = runBlocking { droidMcp.callTool(name, argsMap) }
                    val text = if (res.isSuccess) {
                        res.data?.get("result")?.toString() ?: "OK"
                    } else "工具执行失败: ${res.errorMessage}"
                    JSONObject().put("content", JSONArray().put(
                        JSONObject().put("type", "text").put("text", text)
                    )).put("isError", !res.isSuccess)
                }
                else -> null
            }

            if (result != null) {
                val resp = JSONObject().put("jsonrpc", "2.0").put("id", id).put("result", result)
                send(sock, 200, "application/json", resp.toString(), mapOf("Access-Control-Allow-Origin" to "*"))
            } else {
                send(sock, 200, "application/json", jsonRpcError(id, -32601, "Method not found: $method"),
                    mapOf("Access-Control-Allow-Origin" to "*"))
            }
        } catch (e: Throwable) {
            Log.w(TAG, "MCP dispatch 异常：${e.message}")
            send(sock, 200, "application/json", jsonRpcError(id, -32603, "Internal error: ${e.message}"),
                mapOf("Access-Control-Allow-Origin" to "*"))
        }
    }

    private fun jsonRpcError(id: Any?, code: Int, msg: String): String =
        JSONObject().put("jsonrpc", "2.0").put("id", id ?: JSONObject.NULL)
            .put("error", JSONObject().put("code", code).put("message", msg)).toString()

    private fun send(sock: Socket, status: Int, contentType: String, body: String, extraHeaders: Map<String, String> = emptyMap()) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val out: OutputStream = sock.getOutputStream()
        val sb = StringBuilder()
        sb.append("HTTP/1.1 $status ${statusText(status)}\r\n")
        sb.append("Content-Type: $contentType; charset=utf-8\r\n")
        sb.append("Content-Length: ${bytes.size}\r\n")
        sb.append("Connection: close\r\n")
        extraHeaders.forEach { (k, v) -> sb.append("$k: $v\r\n") }
        sb.append("\r\n")
        out.write(sb.toString().toByteArray(Charsets.UTF_8))
        if (bytes.isNotEmpty()) out.write(bytes)
        out.flush()
    }

    private fun statusText(code: Int) = when (code) {
        200 -> "OK"
        202 -> "Accepted"
        204 -> "No Content"
        400 -> "Bad Request"
        405 -> "Method Not Allowed"
        else -> "OK"
    }

    companion object {
        private const val TAG = "QuroMcpHttpServer"
    }
}
