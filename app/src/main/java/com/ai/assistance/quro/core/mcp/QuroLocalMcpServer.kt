package com.ai.assistance.quro.core.mcp

import com.ai.assistance.quro.BuildConfig
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException

/**
 * 本地 MCP Server（AI 部署版）：零依赖 ServerSocket 实现，与 [QuroMcpHttpServer] 同源协议
 * （JSON-RPC 2.0 over HTTP，仅监听 127.0.0.1）。区别在于它的工具清单来自 **AI 通过
 * `mcp_deploy` 提交的定义**（而非内置 QuroTool 引擎）。
 *
 * 这样 AI 写的 MCP 服务器一旦部署，即作为 127.0.0.1 上的标准 MCP 端点存在——现有的
 * [QuroMcpClient]（mcp_call）可直接按别名连它、拉取工具、调用，实现「AI 写 → 部署 →
 * 界面自动拉取并注册」的完整闭环，无需任何外部运行时。
 */
class QuroLocalMcpServer(private val toolDefsJson: String) {

    private val tools: List<JSONObject> = runCatching {
        val arr = JSONArray(toolDefsJson)
        (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
    }.getOrDefault(emptyList())

    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null

    @Volatile var port: Int = 0
        private set
    @Volatile var running: Boolean = false
        private set

    val endpoint: String get() = "http://127.0.0.1:$port/mcp"
    val toolCount: Int get() = tools.size

    /** 启动服务；成功返回监听端口。仅绑定 127.0.0.1。 */
    fun start(): Int {
        if (running) return port
        val ss = ServerSocket(0, 0, InetAddress.getByName("127.0.0.1"))
        port = ss.localPort
        serverSocket = ss
        running = true
        acceptThread = Thread({ acceptLoop(ss) }, "QuroLocalMcpServer").also { it.isDaemon = true; it.start() }
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
                Thread({ handle(sock) }, "QuroLocalMcpClient").also { it.isDaemon = true; it.start() }
            } catch (e: SocketException) {
                if (running) Log.w(TAG, "本地 MCP accept 中断：${e.message}")
                break
            } catch (e: Throwable) {
                Log.w(TAG, "本地 MCP accept 异常：${e.message}")
            }
        }
    }

    private fun handle(sock: Socket) {
        try {
            sock.soTimeout = 30000
            val input = BufferedReader(InputStreamReader(sock.getInputStream(), Charsets.UTF_8))
            val requestLine = input.readLine() ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) { send(sock, 400, "text/plain", "Bad Request"); return }
            val method = parts[0]
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
            Log.w(TAG, "本地 MCP 处理异常：${e.message}")
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
        if (id == null) {
            send(sock, 202, "application/json", "", mapOf("Access-Control-Allow-Origin" to "*"))
            return
        }
        try {
            val result: JSONObject? = when (method) {
                "initialize" -> JSONObject()
                    .put("protocolVersion", "2024-11-05")
                    .put("capabilities", JSONObject().put("tools", JSONObject()))
                    .put("serverInfo", JSONObject().put("name", "QuroLocalMcp").put("version", BuildConfig.VERSION_NAME))
                "ping" -> JSONObject()
                "tools/list" -> JSONObject().put("tools", JSONArray().apply {
                    tools.forEach { put(it) }
                })
                "tools/call" -> {
                    val name = params?.optString("name", "") ?: ""
                    val argsObj = params?.optJSONObject("arguments") ?: JSONObject()
                    val toolDef = tools.firstOrNull { it.optString("name", "") == name }
                    if (toolDef == null) {
                        JSONObject().put("content", JSONArray().put(
                            JSONObject().put("type", "text").put("text", "本地 MCP 未找到工具: $name")
                        )).put("isError", true)
                    } else {
                        val text = QuroLocalMcpDispatcher.dispatch(toolDef, argsObj)
                        JSONObject().put("content", JSONArray().put(
                            JSONObject().put("type", "text").put("text", text)
                        )).put("isError", false)
                    }
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
            Log.w(TAG, "本地 MCP dispatch 异常：${e.message}")
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
        private const val TAG = "QuroLocalMcpServer"
    }
}
