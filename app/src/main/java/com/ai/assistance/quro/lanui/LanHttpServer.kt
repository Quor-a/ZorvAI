package com.ai.assistance.quro.lanui

import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException

/**
 * 零依赖本地 HTTP 服务（复用 [com.ai.assistance.quro.core.mcp.QuroMcpHttpServer] 的
 * ServerSocket 手写范式，因为 Android 运行时不含 com.sun.net.httpserver.HttpServer）。
 *
 * 仅监听 127.0.0.1（同设备）。路由：
 *   GET  /lan/ui      → 返回 UI 快照 JSON（后端下发当前界面描述）
 *   POST /lan/action  → 接收前端回传的 action，更新状态
 * 由 [LanBackendService] 持有并驱动。
 */
class LanHttpServer(
    private val onUi: () -> JSONObject,
    private val onAction: (action: String, payload: JSONObject?) -> JSONObject
) {

    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null

    @Volatile var port: Int = 0
        private set
    @Volatile var running: Boolean = false
        private set

    val endpoint: String get() = "http://127.0.0.1:$port/lan/ui"

    /** 启动；优先绑定首选端口，被占用则回退随机端口。返回实际监听端口。 */
    fun start(preferredPort: Int = 8080): Int {
        if (running) return port
        val ss = try {
            ServerSocket(preferredPort, 0, InetAddress.getByName("127.0.0.1"))
        } catch (e: Throwable) {
            Log.w(TAG, "端口 $preferredPort 占用，回退随机端口：${e.message}")
            ServerSocket(0, 0, InetAddress.getByName("127.0.0.1"))
        }
        port = ss.localPort
        serverSocket = ss
        running = true
        acceptThread = Thread({ acceptLoop(ss) }, "LanHttpServer").also { it.isDaemon = true; it.start() }
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
                Thread({ handle(sock) }, "LanHttpClient").also { it.isDaemon = true; it.start() }
            } catch (e: SocketException) {
                if (running) Log.w(TAG, "accept 中断：${e.message}")
                break
            } catch (e: Throwable) {
                Log.w(TAG, "accept 异常：${e.message}")
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
            val path = parts[1]
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

            when {
                method == "GET" && path.startsWith("/lan/ui") -> {
                    val json = runCatching { onUi() }.getOrDefault(JSONObject().put("error", "backend error"))
                    send(sock, 200, "application/json", json.toString(), cors())
                }
                method == "POST" && path.startsWith("/lan/action") -> {
                    val (action, payload) = try {
                        val o = JSONObject(body)
                        val p = if (o.has("payload")) o.optJSONObject("payload") else o
                        o.optString("action", "") to p
                    } catch (e: Throwable) {
                        "" to null
                    }
                    if (action.isBlank()) {
                        send(sock, 400, "application/json",
                            JSONObject().put("ok", false).put("error", "missing action").toString(), cors())
                        return
                    }
                    val res = runCatching { onAction(action, payload) }
                        .getOrDefault(JSONObject().put("ok", false).put("error", "action failed"))
                    send(sock, 200, "application/json", res.toString(), cors())
                }
                method == "OPTIONS" -> send(sock, 204, "text/plain", "", cors())
                else -> send(sock, 404, "application/json",
                    JSONObject().put("ok", false).put("error", "not found: $path").toString(), cors())
            }
        } catch (e: Throwable) {
            Log.w(TAG, "处理异常：${e.message}")
        } finally {
            try { sock.close() } catch (_: Throwable) {}
        }
    }

    private fun cors(): Map<String, String> = mapOf("Access-Control-Allow-Origin" to "*")

    private fun send(
        sock: Socket, status: Int, contentType: String,
        body: String, extraHeaders: Map<String, String> = emptyMap()
    ) {
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
        204 -> "No Content"
        400 -> "Bad Request"
        404 -> "Not Found"
        else -> "OK"
    }

    companion object {
        private const val TAG = "LanHttpServer"
    }
}
