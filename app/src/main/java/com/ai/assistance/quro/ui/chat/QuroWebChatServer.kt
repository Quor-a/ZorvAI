package com.ai.assistance.quro.ui.chat

import android.content.Context
import com.ai.assistance.quro.core.QuroMessage
import com.ai.assistance.quro.core.tools.ZxWebCardQueue
import com.ai.assistance.quro.ui.QuroChatViewModel
import com.ai.assistance.quro.util.QuroDiag
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
 * Web UI 对话框的本地服务。
 *
 * 形态对齐参照物（用户 2026-10-13 指的两个）：
 *  - DeepSeek Harness 的 `dsh web`：本地起服务 → 浏览器开 `http://127.0.0.1:3080`；
 *  - operit 的「外部链接对话框」：`ExternalChatHttpServer`（NanoHTTPD）+ 静态 web-chat + REST 桥。
 *
 * 上一版做错的地方：把 195 KB 组件契约内联进单个 HTML，再用 `loadDataWithBaseURL` 硬灌，
 * 宿主还要拼 JS 字符串往下发。那样既没法当链接分享，也多出一整层转义——漏一个字符整页白。
 * 现在 Web UI 是 `assets/web-chat/` 下真正的前端，由本服务按普通静态资源提供。
 *
 * 传输层：Android 运行时**不含** `com.sun.net.httpserver`，也不引入 NanoHTTPD，
 * 直接基于 [java.net.ServerSocket] 手写最小 HTTP/1.1（与本仓 `QuroMcpHttpServer` 同一套做法）。
 * **只绑 127.0.0.1**，外部网络不可达。
 *
 * 路由（必须与 `assets/web-chat/chat.js` 里的调用逐条对应）：
 * ```
 * GET  /                   → web-chat/index.html      （Web UI 本体）
 * GET  /style.css /chat.js → web-chat 目录下的同名文件（前端资源）
 *                           注意：这里不能写「web-chat 后面跟斜杠星号」——
 *                           Kotlin 的块注释可嵌套，那一小段会把 Kdoc 重新打开、
 *                           永不闭合，报错却是文件末尾的 "Unclosed comment"。
 * GET  /zxwidget.css       → web-chat/zxwidget.css    （zxwidget 的样式）
 * GET  /zxwidget.js        → zx/zxwidget.js           （zxwidget 本体，独立模块）
 * GET  /zx_contracts.json  → zx_contracts.json        （477 组件字段契约）
 * GET  /api/snapshot       → 当前会话快照（前端轮询）
 * POST /api/send           → {text}
 * POST /api/stop           → 停止本轮生成
 * POST /api/new            → 新对话
 * POST /api/interact       → zxwidget 卡片交互回传
 * ```
 */
class QuroWebChatServer(
    private val context: Context,
    private val vm: QuroChatViewModel,
) {

    /** 顶栏标题，由对话框每次打开时刷新。 */
    @Volatile var title: String = DEFAULT_TITLE

    /** 深色主题。 */
    @Volatile var dark: Boolean = false

    @Volatile var port: Int = 0
        private set

    @Volatile var running: Boolean = false
        private set

    /** 页面地址。对话框把它交给 WebView；也便于「外部链接」形态下贴到浏览器。 */
    val url: String get() = "http://127.0.0.1:$port/"

    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null

    /**
     * 静态资源内存缓存。
     * `zx_contracts.json` 有 195 KB，每次请求都从 assets 解压读一遍是纯浪费；
     * 服务实例随对话框开关而生灭，缓存不会跨版本残留。
     */
    private val assetCache = HashMap<String, ByteArray>()

    /** 启动服务，返回监听端口；失败抛异常。 */
    fun start(): Int {
        if (running) return port
        // 端口传 0 = 由系统挑空闲端口，避免和 MCP / ACI 那几个服务抢
        val ss = ServerSocket(0, 0, InetAddress.getByName(LOOPBACK))
        serverSocket = ss
        port = ss.localPort
        running = true
        acceptThread = Thread({ acceptLoop(ss) }, "QuroWebChat").also {
            it.isDaemon = true
            it.start()
        }
        QuroDiag.log(TAG, "Web UI 服务已启动 $url")
        return port
    }

    fun stop() {
        running = false
        runCatching { serverSocket?.close() }
        serverSocket = null
        acceptThread = null
        assetCache.clear()
        QuroDiag.log(TAG, "Web UI 服务已停止")
    }

    /* ── 连接处理 ─────────────────────────────────────────────────────── */

    private fun acceptLoop(ss: ServerSocket) {
        while (running && !ss.isClosed) {
            try {
                val sock = ss.accept() ?: continue
                Thread({ handle(sock) }, "QuroWebChatClient").also {
                    it.isDaemon = true
                    it.start()
                }
            } catch (e: SocketException) {
                if (running) QuroDiag.log(TAG, "accept 中断：${e.message}")
                break
            } catch (e: Throwable) {
                QuroDiag.log(TAG, "accept 异常：${e.message}")
            }
        }
    }

    private fun handle(sock: Socket) {
        try {
            sock.soTimeout = SOCKET_TIMEOUT_MS
            val input = BufferedReader(InputStreamReader(sock.getInputStream(), Charsets.UTF_8))
            val requestLine = input.readLine() ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) {
                respond(sock, 400, "text/plain", "Bad Request".toByteArray())
                return
            }
            val method = parts[0].uppercase()
            val path = parts[1].substringBefore('?')

            var contentLength = 0
            while (true) {
                val line = input.readLine() ?: break
                if (line.isEmpty()) break
                val lower = line.lowercase()
                if (lower.startsWith("content-length:")) {
                    contentLength = lower.substringAfter(":").trim().toIntOrNull() ?: 0
                }
            }
            val body = if (contentLength > 0) readBody(input, contentLength) else ""

            when {
                method == "GET" || method == "HEAD" -> handleGet(sock, path)
                method == "POST" -> handlePost(sock, path, body)
                method == "OPTIONS" -> respond(
                    sock, 204, "text/plain", ByteArray(0),
                    mapOf("Access-Control-Allow-Origin" to "*")
                )
                else -> respond(sock, 405, "text/plain", "Method Not Allowed".toByteArray())
            }
        } catch (e: Throwable) {
            QuroDiag.log(TAG, "请求处理异常：${e.message}")
        } finally {
            runCatching { sock.close() }
        }
    }

    private fun readBody(input: BufferedReader, len: Int): String {
        val buf = CharArray(len)
        var read = 0
        while (read < len) {
            val n = input.read(buf, read, len - read)
            if (n < 0) break
            read += n
        }
        return String(buf, 0, read)
    }

    /* ── GET：静态资源 + 快照 ─────────────────────────────────────────── */

    private fun handleGet(sock: Socket, path: String) {
        when (path) {
            "/api/snapshot" -> respondJson(sock, snapshot())
            else -> {
                val asset = STATIC[path]
                if (asset == null) {
                    respond(sock, 404, "text/plain", "Not Found: $path".toByteArray())
                    return
                }
                val bytes = readAsset(asset)
                if (bytes == null) {
                    QuroDiag.log(TAG, "静态资源缺失：$asset")
                    respond(
                        sock, 500, "text/plain",
                        "asset missing: $asset".toByteArray()
                    )
                    return
                }
                respond(sock, 200, contentTypeOf(asset), bytes)
            }
        }
    }

    /* ── POST：动作 ───────────────────────────────────────────────────── */

    private fun handlePost(sock: Socket, path: String, body: String) {
        when (path) {
            "/api/send" -> {
                val text = runCatching { JSONObject(body).optString("text") }.getOrDefault("").trim()
                if (text.isEmpty()) {
                    respondJson(sock, """{"ok":false,"error":"empty text"}""")
                    return
                }
                // 与主对话框同一会话、同一模型配置：cfg 交给 vm.send 的默认值（repo.load()）
                vm.send(text)
                respondJson(sock, """{"ok":true}""")
            }

            "/api/stop" -> {
                vm.stop()
                respondJson(sock, """{"ok":true}""")
            }

            "/api/new" -> {
                vm.newConversation()
                respondJson(sock, """{"ok":true}""")
            }

            "/api/interact" -> {
                onInteract(body)
                respondJson(sock, """{"ok":true}""")
            }

            else -> respondJson(sock, """{"ok":false,"error":"unknown api: $path"}""", 404)
        }
    }

    /**
     * zxwidget 卡片交互回传。
     *
     * 两条回路，与单卡片对话框 [ZxWebCardDialog] 保持同一语义：
     *  ① 有 `zx_web_card` 工具正在等这张卡 → 作为**工具结果**回传，本轮对话直接继续，
     *     模型不必白等 120s 超时；
     *  ② 没有工具在等（来自动态 UI 动作 / 历史重放）→ 整理成一条**用户消息**发出去。
     */
    private fun onInteract(body: String) {
        val json = runCatching { JSONObject(body) }.getOrElse {
            JSONObject().put("action", "raw").put("summary", body)
        }
        val comp = json.optString("component").ifBlank { "zx_card" }
        val text = ZxWebCard.summarize(comp, json)
        QuroDiag.log(TAG, "zxwidget 交互：$text")
        if (!ZxWebCardQueue.submitByComponent(comp, text)) vm.send(text)
    }

    /* ── 快照 ─────────────────────────────────────────────────────────── */

    private fun snapshot(): String {
        val arr = JSONArray()
        vm.messages.value
            .filter { !it.hidden && (it.role == "user" || it.role == "assistant") }
            .forEach { arr.put(messageJson(it)) }
        return JSONObject()
            .put("ok", true)
            .put("title", title)
            .put("subtitle", "127.0.0.1:$port")
            .put("dark", dark)
            .put("busy", vm.busy.value)
            .put("messages", arr)
            .toString()
    }

    private fun messageJson(m: QuroMessage): JSONObject {
        val o = JSONObject()
            .put("id", m.id)
            .put("role", m.role)
            .put("content", m.content)
            .put("reasoning", m.reasoning ?: "")
            .put("senderName", m.senderName ?: "")
        if (!m.toolCalls.isNullOrEmpty()) {
            val calls = JSONArray()
            m.toolCalls.forEach { tc ->
                calls.put(
                    JSONObject()
                        .put("name", tc.name)
                        .put("arguments", tc.arguments)
                        .put("result", tc.result ?: JSONObject.NULL)
                        .put("durationMs", tc.durationMs)
                )
            }
            o.put("toolCalls", calls)
        }
        if (!m.attachments.isNullOrEmpty()) {
            val atts = JSONArray()
            m.attachments.forEach { a ->
                atts.put(
                    JSONObject()
                        .put("type", a.type)
                        .put("uri", "file://" + a.uri)
                        .put("name", a.name)
                )
            }
            o.put("attachments", atts)
        }
        return o
    }

    /* ── 静态资源 ─────────────────────────────────────────────────────── */

    private fun readAsset(path: String): ByteArray? {
        assetCache[path]?.let { return it }
        val bytes = runCatching {
            context.assets.open(path).use { it.readBytes() }
        }.getOrNull() ?: return null
        assetCache[path] = bytes
        return bytes
    }

    private fun contentTypeOf(path: String): String = when {
        path.endsWith(".html") -> "text/html; charset=utf-8"
        path.endsWith(".css") -> "text/css; charset=utf-8"
        path.endsWith(".js") -> "application/javascript; charset=utf-8"
        path.endsWith(".json") -> "application/json; charset=utf-8"
        path.endsWith(".svg") -> "image/svg+xml"
        else -> "application/octet-stream"
    }

    /* ── 响应 ─────────────────────────────────────────────────────────── */

    private fun respondJson(sock: Socket, body: String, status: Int = 200) {
        respond(
            sock, status, "application/json; charset=utf-8",
            body.toByteArray(Charsets.UTF_8),
            mapOf("Access-Control-Allow-Origin" to "*", "Cache-Control" to "no-store")
        )
    }

    private fun respond(
        sock: Socket,
        status: Int,
        contentType: String,
        bytes: ByteArray,
        extraHeaders: Map<String, String> = emptyMap(),
    ) {
        val out: OutputStream = sock.getOutputStream()
        val head = StringBuilder()
        head.append("HTTP/1.1 ").append(status).append(' ').append(statusText(status)).append("\r\n")
        head.append("Content-Type: ").append(contentType).append("\r\n")
        head.append("Content-Length: ").append(bytes.size).append("\r\n")
        head.append("Connection: close\r\n")
        extraHeaders.forEach { (k, v) -> head.append(k).append(": ").append(v).append("\r\n") }
        head.append("\r\n")
        out.write(head.toString().toByteArray(Charsets.UTF_8))
        if (bytes.isNotEmpty()) out.write(bytes)
        out.flush()
    }

    private fun statusText(code: Int) = when (code) {
        200 -> "OK"
        204 -> "No Content"
        400 -> "Bad Request"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        500 -> "Internal Server Error"
        else -> "OK"
    }

    companion object {
        private const val TAG = "QuroWebChatServer"
        private const val LOOPBACK = "127.0.0.1"
        private const val SOCKET_TIMEOUT_MS = 20000
        private const val DEFAULT_TITLE = "ZorvAI"

        /**
         * URL 路径 → assets 相对路径。
         * zxwidget 刻意**不在** web-chat 目录里：它是独立模块，
         * 由别的对话框（单卡片对话框）与 Web UI 共用。
         */
        private val STATIC = mapOf(
            "/" to "web-chat/index.html",
            "/index.html" to "web-chat/index.html",
            "/style.css" to "web-chat/style.css",
            "/chat.js" to "web-chat/chat.js",
            "/zxwidget.css" to "web-chat/zxwidget.css",
            "/zxwidget.js" to "zx/zxwidget.js",
            "/zx_contracts.json" to "zx_contracts.json",
        )
    }
}
