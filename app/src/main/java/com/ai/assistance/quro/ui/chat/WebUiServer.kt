package com.ai.assistance.quro.ui.chat

import android.content.Context
import com.ai.assistance.quro.core.QuroMessage
import com.ai.assistance.quro.core.policy.QuroPolicyStore
import com.ai.assistance.quro.core.tools.QuroVoiceFeaturePrefs
import com.ai.assistance.quro.core.tools.ZxWebCardQueue
import com.ai.assistance.quro.core.tools.WorkspacePreferences
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
 *
 * ── 谁来持有它（2026-10-13 改）────────────────────────────────────────
 * 🔴 改前：本服务在对话框 Composable 的 `remember` 里创建，**随对话框生灭**。
 *    后果是真机上「返回就断」——用户原话「返回 ZorvAI 内的 web ui 界面就不能链接了」。
 *    而 Web UI 的定位就是「在别的屏幕上看/用同一个对话框」，它天生要求
 *    「ZorvAI 在后台也一直服务」。
 * 现在由 [com.ai.assistance.quro.service.QuroWebUiService]（前台服务）持有，
 * 经 [QuroWebUiHost] 这一处单例存取。界面只读它的状态，不再决定它的生死。
 *
 * 也因此，本类**不再**假设「实例生命周期 == 一次会话」：
 * [assetCache] / [cachedShell] 都是进程级缓存（资源随 APK 版本固定，安全）。
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
     * zxwidget 页壳缓存。契约 ~195 KB，每个 zxwidget 块各读一遍纯浪费；
     * 资源随 APK 版本固定，进程内不会变，缓存安全。
     */
    @Volatile private var cachedShell: String? = null

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
        cachedShell = null
        // 长连接必须一起关：否则 stop 之后 SSE 线程还抱着 socket 写，
        // 表现为「服务停了，网页那边还在收旧数据」。
        synchronized(subscribers) {
            subscribers.toList().forEach { sub ->
                sub.alive = false
                runCatching { sub.out.close() }
            }
            subscribers.clear()
        }
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
        // SSE（/api/events）要**长期持有**这条连接：把 socket 交给事件循环后
        // 不能再走 finally 的 close，否则客户端刚连上就被关、EventSource 无限重连。
        var handedOver = false
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
                method == "GET" || method == "HEAD" -> handedOver = handleGet(sock, path)
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
            if (!handedOver) runCatching { sock.close() }
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

    /**
     * @return true 表示**这条连接已被接管**（SSE 长连接），调用方不得再 close。
     */
    private fun handleGet(sock: Socket, path: String): Boolean {
        when (path) {
            "/api/snapshot" -> respondJson(sock, snapshot())
            // 流式通道：投影一变就推，前端不再轮询。见 [Subscriber]。
            "/api/events" -> return serveEvents(sock)
            // zxwidget 页壳：与原生 `ZxWidgetInline` 用的是**同一份** `zx_card_runtime.html`
            // （只换桥）。每块界面用 iframe 引它一次，hash 里带 component/dsl。
            "/zx/shell" -> {
                val shell = zxShell()
                if (shell.isEmpty()) {
                    QuroDiag.log(TAG, "zx 运行时资源缺失：${ZxWebCard.ASSET_NAME}")
                    respond(sock, 500, "text/plain", "zx runtime missing".toByteArray())
                } else {
                    respond(sock, 200, "text/html; charset=utf-8", shell.toByteArray(Charsets.UTF_8))
                }
            }
            else -> {
                val asset = STATIC[path]
                if (asset == null) {
                    respond(sock, 404, "text/plain", "Not Found: $path".toByteArray())
                    return false
                }
                val bytes = readAsset(asset)
                if (bytes == null) {
                    QuroDiag.log(TAG, "静态资源缺失：$asset")
                    respond(sock, 500, "text/plain", "asset missing: $asset".toByteArray())
                    return false
                }
                respond(sock, 200, contentTypeOf(asset), bytes)
            }
        }
        return false
    }

    /**
     * zxwidget 页壳（进程级缓存）。
     *
     * 🔴 这里**不发** `assets/zx/zxwidget.js` 那一份编译器 —— 页壳内联的拷贝
     *    才是两个对话框都在用的那一份（原生 `ZxWebCard.buildHtml` 同源）。
     *    发另一份等于 Web UI 与原生跑两个编译器，正是历史上 24/58 块漂移的来源。
     */
    private fun zxShell(): String {
        cachedShell?.let { return it }
        val (html, contracts) = ZxWebCard.assets(context)
        if (html.isBlank()) return ""
        val shell = ZxWebCard.buildWebShell(html, contracts)
        cachedShell = shell
        return shell
    }

    /* ── SSE：投影变化即推 ─────────────────────────────────────────────── */

    /** 一个在线的流式订阅者。断开即从集合移除，写失败即视为断开。 */
    private class Subscriber(val out: OutputStream) {
        @Volatile var alive = true
    }

    private val subscribers = Collections.synchronizedList(mutableListOf<Subscriber>())

    /**
     * 服务已有一条长连接，但**变化检测只跑一个线程**（不论几个客户端）：
     * 它按 [REVISION_INTERVAL_MS] 采一次投影指纹，变了就把快照推给全部订阅者。
     *
     * 为什么用「服务端单点指纹」而不是「前端轮询」：
     *   · 延迟：前端轮询最快的档位是 180 ms（还只在 busy 时），空闲档 1200 ms ——
     *     后者正是用户报的「消息同步延迟很大」。这里稳定在 ~80 ms，且只在**真的变了**
     *     时才传输（轮询是「不变也传」）。
     *   · 一条连接、一个线程、一份快照，N 个客户端不再各自拉全量。
     */
    private var revisionThread: Thread? = null

    private fun ensureRevisionWatch() {
        if (revisionThread?.isAlive == true) return
        revisionThread = Thread({
            var last = ""
            while (running) {
                try {
                    Thread.sleep(REVISION_INTERVAL_MS)
                    if (subscribers.isEmpty()) { last = ""; continue }
                    val rev = revision()
                    if (rev == last) continue
                    last = rev
                    push(snapshot())
                } catch (_: InterruptedException) {
                    break
                } catch (e: Throwable) {
                    QuroDiag.log(TAG, "投影变化检测异常：${e.message}")
                }
            }
        }, "QuroWebUiRev").also {
            it.isDaemon = true
            it.start()
        }
    }

    /**
     * 投影指纹：**故意便宜**（每 ~80 ms 一次，不能在里面做 JSON 序列化）。
     *
     * 覆盖「会让网页看到的东西变了的全部维度」：
     *   · 消息条数 —— 新增/删除；
     *   · 每条消息的长度组合 —— 正文在长（流式）、工具结果回填；
     *   · 最后一条消息的 id —— 换会话；
     *   · busy / 五个开关 / 两个策略 / 工作区 / 主题 —— 控制条与顶栏。
     *
     * 刻意**不**拼消息正文：那会把「检测变化」变成「每 80 ms 序列化整段对话」，
     * 长会话下就是白烧 CPU。长度 + id 足以捕捉一切展示层变化
     * （正文内容变而长度不变的情况只有「等长替换」，本产品的流式是纯追加）。
     */
    private fun revision(): String = buildString {
        val msgs = vm.messages.value
        append(msgs.size).append('|')
        msgs.forEach { append(it.id.hashCode()).append(':').append(it.content.length).append(',') }
        append('|').append(vm.busy.value)
        append(vm.thinking.value).append(vm.autoSaveMemory.value).append(vm.subAgentEnabled.value)
        append(vm.visionEnabled.value).append(dark)
        append('|').append(title).append(modelName).append(personaName)
        append('|').append(QuroVoiceFeaturePrefs.getAutoRead(context))
        append(WorkspacePreferences.getCurrentWorkspace(context).orEmpty())
        append(QuroPolicyStore.getCms(context)).append(QuroPolicyStore.getPriv(context))
        append('|').append(port)
    }

    private fun serveEvents(sock: Socket): Boolean {
        val out = try {
            sock.getOutputStream()
        } catch (e: Throwable) {
            QuroDiag.log(TAG, "SSE 取流失败：${e.message}")
            return false
        }
        val head = StringBuilder()
        head.append("HTTP/1.1 200 OK\r\n")
        head.append("Content-Type: text/event-stream; charset=utf-8\r\n")
        head.append("Cache-Control: no-store\r\n")
        head.append("Connection: keep-alive\r\n")
        head.append("Access-Control-Allow-Origin: *\r\n")
        head.append("X-Accel-Buffering: no\r\n")
        head.append("\r\n")
        return try {
            out.write(head.toString().toByteArray(Charsets.UTF_8))
            // 首帧立刻给一份，页面不必等第一次变化才有内容
            out.write(sseFrame(snapshot()))
            out.flush()
        } catch (e: Throwable) {
            QuroDiag.log(TAG, "SSE 首帧失败：${e.message}")
            return false
        }.let {
            val sub = Subscriber(out)
            subscribers.add(sub)
            ensureRevisionWatch()
            QuroDiag.log(TAG, "SSE 订阅者接入，当前 ${subscribers.size} 个")
            // 心跳线程：代理/系统会在空闲若干秒后掐掉「没有数据的连接」，
            // 掐掉后 EventSource 会重连（表现为「过一会儿闪一下」）。注释行（`: ping`）
            // 不触发前端 onmessage，但足以把连接养住。
            Thread({
                while (running && sub.alive) {
                    try {
                        Thread.sleep(HEARTBEAT_MS)
                        synchronized(out) {
                            out.write(": ping\n\n".toByteArray(Charsets.UTF_8))
                            out.flush()
                        }
                    } catch (_: Throwable) {
                        break
                    }
                }
                sub.alive = false
                subscribers.remove(sub)
                runCatching { sock.close() }
                QuroDiag.log(TAG, "SSE 订阅者断开，剩 ${subscribers.size} 个")
            }, "QuroWebUiSse").also { it.isDaemon = true; it.start() }
            true
        }
    }

    private fun sseFrame(payload: String): ByteArray =
        "data: $payload\n\n".toByteArray(Charsets.UTF_8)

    /** 把一份快照推给全部订阅者；写失败的当作已断开，就地清掉。 */
    private fun push(payload: String) {
        if (subscribers.isEmpty()) return
        val bytes = sseFrame(payload)
        // 拷贝一份再遍历：写失败会从原集合里移除，遍历中改集合会炸
        val snapshotList = synchronized(subscribers) { subscribers.toList() }
        snapshotList.forEach { sub ->
            try {
                synchronized(sub.out) {
                    sub.out.write(bytes)
                    sub.out.flush()
                }
            } catch (_: Throwable) {
                sub.alive = false
                subscribers.remove(sub)
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

            // 页面自检回传。
            //
            // 🔴 这条路由是**替代品**，不是新增功能：改前页面跑在应用内嵌的 WebView 里，
            //    它的 console 由 WebChromeClient.onConsoleMessage 收进 QuroDiag。
            //    现在页面跑在**别的设备的浏览器**里 —— console 宿主根本看不见，
            //    页面自己那套「高度链自检 / guardCollapse」在真机上就等于白写。
            //    所以自检必须自己走回来：页面在检测到异常时 POST 一条，宿主写进诊断日志。
            //    只在**异常时**发（正常不上报），避免把日志刷成噪音。
            "/api/diag" -> {
                val o = runCatching { JSONObject(body) }.getOrNull()
                val msg = o?.optString("msg")?.trim().orEmpty()
                if (msg.isEmpty()) {
                    respondJson(sock, """{"ok":false,"error":"empty msg"}""")
                    return
                }
                val lvl = o?.optString("level")?.trim().orEmpty().ifBlank { "info" }
                QuroDiag.log(TAG, "页面自检[$lvl] $msg")
                respondJson(sock, """{"ok":true}""")
            }

            // 互动UI界面（zxwidget）里的一次操作 —— 与原生 `ZxWidgetInline.onInteract`
            // **逐字同一套回路**（见 ChatScreen 里那段）：两条回路必须分开，
            // 否则模型收到重复内容：
            //   ① 有 zx_web_card 工具正在等 → 作为**工具结果**回传，本轮直接继续；
            //   ② 没有（历史重放 / 动态 UI 动作来源）→ 作为一条**用户消息**发出去。
            "/api/interact" -> {
                val o = runCatching { JSONObject(body) }.getOrNull()
                val component = o?.optString("component")?.trim().orEmpty()
                val payload = o?.opt("payload")
                if (component.isEmpty() || payload == null) {
                    respondJson(sock, """{"ok":false,"error":"need component + payload"}""")
                    return
                }
                val json = when (payload) {
                    is JSONObject -> payload
                    is String -> runCatching { JSONObject(payload) }.getOrElse {
                        JSONObject().put("action", "raw").put("summary", payload)
                    }
                    else -> JSONObject().put("action", "raw").put("summary", payload.toString())
                }
                // 组件名以 **URL 上带来的那个** 为准：页面里的 `ctx.interact` 也会塞一份，
                // 但两者不一致时说明父页面与 iframe 的说法对不上，此时信父页面
                // （它才是从投影快照里读出 component 的那一方）。
                val text = ZxWebCard.summarize(component, json)
                QuroDiag.log(TAG, "zxwidget 交互（web）：$text")
                val hit = ZxWebCardQueue.submitByComponent(component, text)
                if (!hit) vm.send(text)
                respondJson(sock, """{"ok":true,"delivered":"${if (hit) "tool" else "chat"}"}""")
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
                    // ── 真源在 ViewModel 的四个 ──
                    busy = vm.busy.value,
                    deepThink = vm.thinking.value,
                    autoSaveMemory = vm.autoSaveMemory.value,
                    subAgent = vm.subAgentEnabled.value,
                    vision = vm.visionEnabled.value,

                    // ── 真源在持久化 prefs 的四个 ──
                    // 🔴 这四个**不是** ViewModel 状态，而是原生 `ChatScreen` 的局部
                    //    `remember` state，各自的真源都是 prefs（原生每次切换都写回）：
                    //      autoRead  → QuroVoiceFeaturePrefs（ChatScreen.toggleAutoRead）
                    //      workspace → WorkspacePreferences（WorkspaceSelectionDialog 回调）
                    //      cms/priv  → QuroPolicyStore（PermissionModeBar 的策略 chips）
                    //
                    //    这里**每次快照现读**，绝不在服务里存一份缓存副本。缓存会在原生切换后
                    //    变旧，症状正是「手机上开了、网页这边还显示关着」，而且零报错——
                    //    查起来得横跨两个介质，属于典型的高代价低收益故障。
                    //    现读的代价只是四次 SharedPreferences 命中，随快照频率（最快 180ms）无压力。
                    autoRead = QuroVoiceFeaturePrefs.getAutoRead(context),
                    workspace = WorkspacePreferences.getCurrentWorkspace(context).orEmpty(),
                    cms = QuroPolicyStore.getCms(context),
                    priv = QuroPolicyStore.getPriv(context),
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

        /**
         * 投影指纹采样间隔。
         *
         * 80 ms ≈ 12.5 次/秒：比人眼分辨「打字机效果」所需还密一档，
         * 而一次采样只做「消息条数 + id/长度 + 几个开关」的字符串拼接
         * （不做 JSON 序列化，见 [revision]），长会话下也只是微秒级。
         * 快照推送**只在指纹变化时**发生，所以空闲时这条线程几乎不产生流量。
         */
        private const val REVISION_INTERVAL_MS = 80L

        /** SSE 心跳：注释行不触发前端 onmessage，只用来防止长连接被空闲掐断。 */
        private const val HEARTBEAT_MS = 15000L

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


/**
 * 进程级 Web UI 宿主 —— **唯一**持有 [QuroWebUiServer] 的地方。
 *
 * 为什么要有这么一层（而不是让服务直接 new 一个）：
 *   1. 应用内那块「Web UI 设置」（开关 / 地址 / 复制）要能读到**同一个**实例的
 *      实时状态。没有单一持有者时，界面只能自己再 new 一个 → 两个服务抢同一个
 *      端口，第二个绑不上，表现为「设置页显示没开、其实在跑」。
 *   2. 服务被系统回收重建时，必须先把上一个实例停掉再起新的，
 *      否则旧 ServerSocket 还占着 3080，新实例只能退到临时端口，
 *      于是「通知里的地址」和「真实在听的地址」不一致。
 *
 * 刻意**不**在 stop 时清掉 [QuroChatViewModel] 之外的东西：本对象不持有 Context 引用
 * （只在 start 时用一下 applicationContext 构造服务），不会泄漏 Activity。
 */
object QuroWebUiHost {
    private const val TAG = "QuroWebUiHost"

    @Volatile private var server: QuroWebUiServer? = null

    /**
     * 启动监听。已经起着的直接返回现有地址（幂等）。
     * @return 可访问地址；null = 三次绑定都失败（见 [QuroWebUiServer.start]）
     */
    @Synchronized
    fun start(ctx: Context, vm: QuroChatViewModel): String? {
        server?.takeIf { it.running }?.let { return it.accessUrl }
        // 上一个实例还留着 socket 就先收干净，避免抢端口
        server?.let { runCatching { it.stop() } }
        val s = QuroWebUiServer(ctx.applicationContext, vm)
        return try {
            s.start()
            server = s
            s.accessUrl
        } catch (e: Throwable) {
            QuroDiag.log(TAG, "Web UI 监听失败：${e.javaClass.simpleName} ${e.message}")
            runCatching { s.stop() }
            server = null
            null
        }
    }

    @Synchronized
    fun stop() {
        server?.let { runCatching { it.stop() } }
        server = null
    }

    fun isRunning(): Boolean = server?.running == true

    fun accessUrl(): String? = server?.takeIf { it.running }?.accessUrl

    /** 更新顶栏 / 主题这类随会话变化的字段；服务没在跑就静默跳过。 */
    fun update(mutate: (QuroWebUiServer) -> Unit) {
        server?.takeIf { it.running }?.let(mutate)
    }
}
