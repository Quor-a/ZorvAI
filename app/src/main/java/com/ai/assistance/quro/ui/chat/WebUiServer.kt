package com.ai.assistance.quro.ui.chat

import android.content.Context
import com.ai.assistance.quro.core.QuroMessage
import com.ai.assistance.quro.ui.QuroChatViewModel
import com.ai.assistance.quro.util.QuroDiag
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.Collections

/**
 * ZorvAI 对话框的**投影服务**。
 *
 * ── 它是什么（用户 2026-10-13 拍板的定位）──────────────────────────────
 * 「web ui 是 zorvAI 的投影」。所以本服务只做一件事：
 * **把 ZorvAI 对话框该被看见的东西投出去**（块模型见 [WebUiProjection]），
 * 让同一个会话能在手机之外的屏幕上被看见、被操作。
 *
 * 它**不是**一个独立应用的服务器：不提供自己的 UI 设计、不提供自己的数据模型、
 * 不提供 zxwidget 的运行时。网页是投影的接收端，不是第二个产品。
 *
 * ── 与上一版的关键差别 ────────────────────────────────────────────────
 * ① **对外可达**。旧版 `ServerSocket(0, 0, InetAddress.getByName("127.0.0.1"))`
 *    只绑回环、端口随机 —— 结果「外部怎么端口 web ui 链接都没有」（用户原话）。
 *    现在默认绑 `0.0.0.0` + 固定端口 [DEFAULT_PORT]（对齐 dsh web 的 3080），
 *    并对外给出局域网直链 [lanUrl]。
 * ② **不再拥有 zxwidget**。旧版有 `/zxwidget.css`、`/zxwidget.js`、`/zx_contracts.json`、
 *    `/api/interact` 四条 zx 专属路由 —— 那是「把 zxwidget 和 webui 混在一起」的
 *    最直接证据（用户原话：「你怎么老是把 zxwidget 和 webui 混合在一起」）。
 *    互动UI界面是独立模块，它的宿主是原生对话框，与本服务无关。
 *
 * ── 安全权衡（写在这里，不藏在注释角落）──────────────────────────────
 * 绑 `0.0.0.0` 意味着**同局域网内的任何设备都能打开这个对话**，且没有任何鉴权。
 * 这是「要外部链接」的必然代价，与 `dsh web` 同类做法一致。
 * 传输层仍是最小 HTTP/1.1，明文（由 network_security_config.xml 的 base-config 放行）。
 * 服务随对话框生灭——关掉对话框即停，不会留一个常驻监听端口在后台。
 */
class QuroWebUiServer(
    private val context: Context,
    private val vm: QuroChatViewModel,
) {

    /** 顶栏标题，由对话框每次打开时刷新。 */
    @Volatile var title: String = DEFAULT_TITLE

    /** 顶栏模型名（原生 ChatTopBar 的 modelName）。 */
    @Volatile var modelName: String = ""

    /** 顶栏人格名（原生 ChatTopBar 的 persona）。 */
    @Volatile var personaName: String = ""

    /** 深色主题。 */
    @Volatile var dark: Boolean = false

    @Volatile var port: Int = 0
        private set

    /** 真正绑定成功的地址；`0.0.0.0` = 已对局域网开放。 */
    @Volatile var boundHost: String = ""
        private set

    @Volatile var running: Boolean = false
        private set

    /** 回环地址，本机 WebView 用这个（不经网卡，最稳）。 */
    val loopbackUrl: String get() = "http://127.0.0.1:$port/"

    /**
     * 局域网直链，给「复制到别的设备浏览器打开」用。
     * 拿不到局域网 IP（未联网 / 只绑了回环）时返回 null。
     */
    val lanUrl: String? get() {
        if (!running || port <= 0 || boundHost == LOOPBACK) return null
        val ip = lanAddress() ?: return null
        return "http://$ip:$port/"
    }

    /** 界面展示给用户的链接：优先局域网直链，退回回环。 */
    val accessUrl: String get() = lanUrl ?: loopbackUrl

    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null

    /**
     * 静态资源内存缓存。
     * 投影服务只发三个文件（index/style/chat），缓存它们可省掉每次请求的 assets 解压。
     * 服务实例随对话框生灭，缓存不会跨版本残留。
     */
    private val assetCache = HashMap<String, ByteArray>()

    /**
     * 启动服务，返回监听端口；失败抛异常。
     *
     * 绑定顺序（每一步失败才走下一步，保证「能开就一定能访问」）：
     *  ① 局域网 `0.0.0.0` + 固定端口 [DEFAULT_PORT]  —— 可复制链接给别人
     *  ② 局域网 `0.0.0.0` + 系统临时端口            —— 固定端口被占（如另一个实例还开着）
     *  ③ 回环 `127.0.0.1` + 系统临时端口            —— 环境不允许对外监听时的兜底
     */
    fun start(): Int {
        if (running) return port

        val attempt = listOf(
            ANY_HOST to DEFAULT_PORT,
            ANY_HOST to 0,
            LOOPBACK to 0,
        )
        var last: Throwable? = null
        for ((host, p) in attempt) {
            try {
                val ss = ServerSocket(p, 0, InetAddress.getByName(host))
                serverSocket = ss
                port = ss.localPort
                boundHost = host
                running = true
                acceptThread = Thread({ acceptLoop(ss) }, "QuroWebUi").also {
                    it.isDaemon = true
                    it.start()
                }
                QuroDiag.log(TAG, "投影服务已启动 host=$host port=$port 回环=$loopbackUrl 局域网=$lanUrl")
                return port
            } catch (e: Throwable) {
                last = e
                QuroDiag.log(TAG, "绑定 $host:$p 失败：${e.javaClass.simpleName} ${e.message}")
            }
        }
        throw last ?: IllegalStateException("无法绑定任何地址")
    }

    fun stop() {
        running = false
        runCatching { serverSocket?.close() }
        serverSocket = null
        acceptThread = null
        assetCache.clear()
        QuroDiag.log(TAG, "投影服务已停止")
    }

    /* ── 连接处理 ─────────────────────────────────────────────────────── */

    private fun acceptLoop(ss: ServerSocket) {
        while (running && !ss.isClosed) {
            try {
                val sock = ss.accept() ?: continue
                Thread({ handle(sock) }, "QuroWebUiClient").also {
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

    /* ── GET：投影快照 + 前端资源 ─────────────────────────────────────── */

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
                    respond(sock, 500, "text/plain", "asset missing: $asset".toByteArray())
                    return
                }
                respond(sock, 200, contentTypeOf(asset), bytes)
            }
        }
    }

    /* ── POST：动作（一律转成对同一个会话的操作）──────────────────────── */

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

            else -> respondJson(sock, """{"ok":false,"error":"unknown api: $path"}""", 404)
        }
    }

    /* ── 投影快照 ─────────────────────────────────────────────────────── */

    /**
     * 快照 = 原生对话框的投影。
     *
     * 形状由 [WebUiProjection] 决定，这里只负责「取哪些消息」与「补顶栏/输入区」。
     * 过滤规则与原生一致：`hidden` 的消息原生也不显示（用户看不见），
     * 而 `excludeFromLlm` 的消息**必须投**——那正是「用户看得见」的那一类
     * （集群发言、互动UI界面来源条都属于它）。
     */
    private fun snapshot(): String {
        val arr = JSONArray()
        vm.messages.value
            .filter { !it.hidden && (it.role == "user" || it.role == "assistant") }
            .forEach { m -> arr.put(WebUiProjection.message(m, mine = m.role == "user")) }

        return JSONObject()
            .put("ok", true)
            .put("bar", WebUiProjection.bar(title, modelName, personaName))
            .put(
                "composer", WebUiProjection.composer(
                    busy = vm.busy.value,
                    deepThink = vm.thinking.value,
                    autoSaveMemory = vm.autoSaveMemory.value,
                    subAgent = vm.subAgentEnabled.value,
                    autoRead = false,      // TODO: 接通原生 PermissionModeBar 的 autoRead 状态
                    vision = vm.visionEnabled.value,
                    workspace = "",
                )
            )
            .put("dark", dark)
            .put("lan", lanUrl ?: "")
            .put("port", port)
            .put("messages", arr)
            .toString()
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
        private const val TAG = "QuroWebUi"
        private const val LOOPBACK = "127.0.0.1"

        /** 对外监听地址：局域网可达。 */
        private const val ANY_HOST = "0.0.0.0"

        /** 默认端口：对齐 dsh web 的 3080，便于记忆与「贴给别人打开」。 */
        const val DEFAULT_PORT = 3080

        private const val SOCKET_TIMEOUT_MS = 20000
        private const val DEFAULT_TITLE = "ZorvAI"

        /**
         * URL 路径 → assets 相对路径。
         * 🔴 这里**只**有投影自己的三个文件。任何 `zx` 开头的路径都不属于本服务
         * —— 互动UI界面是独立模块，由原生对话框承载，不从这里发。
         */
        private val STATIC = mapOf(
            "/" to "web-chat/index.html",
            "/index.html" to "web-chat/index.html",
            "/style.css" to "web-chat/style.css",
            "/chat.js" to "web-chat/chat.js",
        )

        /**
         * 本机局域网 IPv4。
         * 用于把「可复制给别人打开的链接」拼出来——没有它，用户只能看到
         * `127.0.0.1`，而那个地址在**别的设备上打不开**，等于没有链接。
         */
        private fun lanAddress(): String? = runCatching {
            Collections.list(NetworkInterface.getNetworkInterfaces())
                .filter { n -> runCatching { n.isUp && !n.isLoopback && !n.isVirtual }.getOrDefault(false) }
                .flatMap { n -> Collections.list(n.inetAddresses) }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { it.isSiteLocalAddress }
                ?.hostAddress
        }.getOrNull()
    }
}
