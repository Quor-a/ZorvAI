package com.ai.assistance.quro.ui.chat

import android.content.Context
import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import com.ai.assistance.quro.core.QuroAttachment
import com.ai.assistance.quro.core.QuroAttachmentKit
import com.ai.assistance.quro.core.QuroPersonaRepository
import com.ai.assistance.quro.core.model.ApiProviderConfigs
import com.ai.assistance.quro.core.model.QuroCustomProviderRepository
import com.ai.assistance.quro.core.model.QuroLocalModelRepository
import com.ai.assistance.quro.core.model.QuroModelConfigRepository
import com.ai.assistance.quro.core.model.QuroSavedProfileRepository
import com.ai.assistance.quro.core.policy.QuroPolicy
import com.ai.assistance.quro.core.policy.QuroPolicyStore
import com.ai.assistance.quro.core.QuroBrowserBridge
import com.ai.assistance.quro.core.cards.CardAction
import com.ai.assistance.quro.core.cards.CardActionRouter
import com.ai.assistance.quro.core.linux.QuroLinuxEnv
import com.ai.assistance.quro.core.tools.LaunchAppTool
import com.ai.assistance.quro.core.tools.QuroSkillTool
import com.ai.assistance.quro.core.tools.QuroToolRegistry
import com.ai.assistance.quro.core.tools.QuroVoiceFeaturePrefs
import com.ai.assistance.quro.core.tools.QuroUiActionBridge
import com.ai.assistance.quro.core.tools.UiNavigationBus
import com.ai.assistance.quro.core.tools.WorkspacePreferences
import com.ai.assistance.quro.core.tools.ZxWebCardQueue
import com.ai.assistance.quro.core.tools.ui.UiNavigationEvent
import com.ai.assistance.quro.core.ui.dynamicui.QuroCallbackAction
import com.ai.assistance.quro.core.ui.dynamicui.QuroCopyAction
import com.ai.assistance.quro.core.ui.dynamicui.QuroOpenAppAction
import com.ai.assistance.quro.core.ui.dynamicui.QuroOpenScreenAction
import com.ai.assistance.quro.core.ui.dynamicui.QuroOpenUrlAction
import com.ai.assistance.quro.core.ui.dynamicui.QuroRenderHtmlAction
import com.ai.assistance.quro.core.ui.dynamicui.QuroRenderVisproAction
import com.ai.assistance.quro.core.ui.dynamicui.QuroSkillAction
import com.ai.assistance.quro.core.ui.dynamicui.QuroToggleAction
import com.ai.assistance.quro.core.ui.dynamicui.QuroToolCallAction
import com.ai.assistance.quro.core.ui.dynamicui.QuroUiAction
import com.ai.assistance.quro.core.ui.dynamicui.QuroVisualAskAction
import com.ai.assistance.quro.core.ui.dynamicui.QuroVisualPopupAction
import com.ai.assistance.quro.core.ui.dynamicui.QuroZxWebCardAction
import com.ai.assistance.quro.ui.QuroChatViewModel
import com.ai.assistance.quro.util.qstr
import com.ai.assistance.quro.util.QuroDiag
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.Collections
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ZorvAI 的 Web 对话框服务端 —— **对等客户端**，不是投影。
 *
 * ── 定位（用户 2026-10-13 拍板）──────────────────────────────────────
 * 「web ui拥有zorvAI所有功能，界面一样，功能一样，另外一个zorvAI对话框」。
 * 本服务把 ZorvAI 对话框**完整的能力**通过 HTTP/SSE 暴露给浏览器：
 * 同一个会话、同一份状态、同一份渲染链，但网页是**可操作的对等客户端**：
 * 消息收发、开关切换、模型/人格选择、上传、语音、以及全部功能面板入口。
 *
 * ── 与旧版（投影接收端）的关键差别 ──────────────────────────────────
 * 旧版只把对话框「该被看见的东西」投出去，开关只读、无模型/人格选择、
 * 无上传、无面板入口 —— 那是投影，不是对等客户端。本版：
 * ① 所有开关可写回原生同一真源（vm / prefs / policy store / workspace）。
 * ② 模型/人格列表与切换走原生同一仓库与同一分支逻辑。
 * ③ 支持上传（multipart → 应用私有目录 → vm.send 附件）。
 * ④ 全部功能面板入口经 [QuroUiActionBridge] 打开原生界面。
 *
 * ── 安全权衡（不变）────────────────────────────────────────────────
 * 绑 `0.0.0.0` 意味着同局域网内任何设备都能打开这个对话，且没有任何鉴权。
 * 这是「要外部链接」的必然代价，与 dsh web 同类做法一致。
 * 传输层仍是最小 HTTP/1.1，明文（由 network_security_config.xml 放行）。
 *
 * ── 谁来持有它（不变）────────────────────────────────────────────────
 * 由 [com.ai.assistance.quro.service.QuroWebUiService]（前台服务）持有，
 * 经 [QuroWebUiHost] 这一处单例存取。界面只读它的状态，不再决定它的生死。
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

    /** 静态资源内存缓存。 */
    private val assetCache = HashMap<String, ByteArray>()

    /** zxwidget 页壳缓存。 */
    @Volatile private var cachedShell: String? = null

    /** 上传文件存放目录（应用私有，与原生 quro_uploads 同目录）。 */
    private val uploadsDir: File get() = QuroAttachmentKit.uploadsDir(context)

    /** 已上传附件暂存：web 端选择文件后先上传，发送时按 id 引用。 */
    private val pendingAttachments = Collections.synchronizedMap(mutableMapOf<String, QuroAttachment>())

    /**
     * 启动服务，返回监听端口；失败抛异常。
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
                QuroDiag.log(TAG, "Web 对话框服务已启动 host=$host port=$port 回环=$loopbackUrl 局域网=$lanUrl")
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
        pendingAttachments.clear()
        synchronized(subscribers) {
            subscribers.toList().forEach { sub ->
                sub.alive = false
                runCatching { sub.out.close() }
            }
            subscribers.clear()
        }
        QuroDiag.log(TAG, "Web 对话框服务已停止")
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
            val rawPath = parts[1]
            val path = rawPath.substringBefore('?')
            val query = rawPath.substringAfter('?', "")

            var contentLength = 0
            var contentType = ""
            while (true) {
                val line = input.readLine() ?: break
                if (line.isEmpty()) break
                val lower = line.lowercase()
                if (lower.startsWith("content-length:")) {
                    contentLength = lower.substringAfter(":").trim().toIntOrNull() ?: 0
                }
                if (lower.startsWith("content-type:")) {
                    contentType = lower.substringAfter(":").trim()
                }
            }
            val body = if (contentLength > 0) readBody(input, contentLength) else ""

            when {
                method == "GET" || method == "HEAD" -> handedOver = handleGet(sock, path)
                // POST 也可能接管连接：/api/send 是 SSE 长流（见 handleSend）
                method == "POST" -> handedOver = handlePost(sock, path, query, contentType, body)
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

    /* ── GET：快照 + 前端资源 + 模型/人格列表 ─────────────────────────── */

    /**
     * @return true 表示**这条连接已被接管**（SSE 长连接），调用方不得再 close。
     */
    private fun handleGet(sock: Socket, path: String): Boolean {
        when (path) {
            "/api/snapshot" -> respondJson(sock, snapshot())
            "/api/events" -> return serveEvents(sock)
            // 模型分组列表（对等客户端：模型选择弹层数据源）
            "/api/models" -> respondJson(sock, modelsJson())
            // 人格列表 + 当前激活
            "/api/personas" -> respondJson(sock, personasJson())
            // 已上传附件列表（本次服务生命周期内）
            "/api/attachments" -> respondJson(sock, attachmentsJson())
            // zxwidget 页壳：与原生 `ZxWidgetInline` 用的是**同一份** `zx_card_runtime.html`
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
                // /runtimes/xxx 与 /www/xxx：把 assets/runtimes、assets/www 下的前端运行时
                // （mermaid.min.js / mermaid_render.html / marked.min.js / echarts.min.js …）
                // 暴露给 Web 前端，用于**真渲染** Mermaid / AIP / 卡片 / MiniApp。
                val asset = when {
                    path.startsWith("/runtimes/") -> "runtimes/" + path.removePrefix("/runtimes/")
                    path.startsWith("/www/") -> "www/" + path.removePrefix("/www/")
                    else -> STATIC[path]
                }
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

    /** 模型分组列表（对等客户端弹层数据源）。 */
    private fun modelsJson(): String {
        val repo = QuroModelConfigRepository(context)
        val cfg = repo.load()
        val groups = JSONArray()

        // 当前配置组
        val current = JSONArray()
        current.put(JSONObject()
            .put("id", cfg.model.ifBlank { "gpt-4o-mini" })
            .put("name", cfg.model.ifBlank { "未设置模型" })
            .put("desc", "提供商: ${cfg.provider} · ${cfg.baseUrl.ifBlank { "未设置BaseURL" }}")
            .put("provider", cfg.provider.ifBlank { "OPENAI" })
            .put("mark", cfg.provider.firstOrNull()?.uppercase() ?: "Q")
            .put("group", "current"))
        groups.put(JSONObject().put("name", "当前配置").put("models", current))

        // 已保存预设
        val profileRepo = QuroSavedProfileRepository(context)
        val profiles = profileRepo.loadAll()
        if (profiles.isNotEmpty()) {
            val arr = JSONArray()
            profiles.forEach { p ->
                arr.put(JSONObject()
                    .put("id", "__profile__${p.id}")
                    .put("name", p.name)
                    .put("desc", "${p.model} @ ${p.provider} · ${p.baseUrl}")
                    .put("provider", p.customProviderName.ifBlank { p.provider })
                    .put("mark", p.name.firstOrNull()?.uppercase() ?: "S")
                    .put("group", "profile"))
            }
            groups.put(JSONObject().put("name", "已保存预设").put("models", arr))
        }

        // 自定义供应商
        val customRepo = QuroCustomProviderRepository(context)
        val customs = customRepo.loadAll()
        if (customs.isNotEmpty()) {
            val arr = JSONArray()
            customs.forEach { p ->
                arr.put(JSONObject()
                    .put("id", "__custom__${p.id}")
                    .put("name", p.name.ifBlank { "自定义" })
                    .put("desc", "${p.defaultModel.ifBlank { "请在模型配置填写默认模型" }} @ ${p.baseUrl.ifBlank { "未设置地址" }}")
                    .put("provider", p.name.ifBlank { "OTHER" })
                    .put("mark", p.name.firstOrNull()?.uppercase() ?: "C")
                    .put("group", "custom"))
            }
            groups.put(JSONObject().put("name", "自定义供应商").put("models", arr))
        }

        // 本地离线模型
        val localRepo = QuroLocalModelRepository(context)
        val locals = localRepo.loadAll()
        if (locals.isNotEmpty()) {
            val arr = JSONArray()
            locals.forEach { m ->
                val typeLabel = if (m.type.name == "LLAMA_CPP") "llama.cpp" else "MNN"
                arr.put(JSONObject()
                    .put("id", "__local__${m.id}")
                    .put("name", m.name.ifBlank { typeLabel })
                    .put("desc", "$typeLabel 本地推理 · ${m.path.ifBlank { "未设置路径" }}${if (m.modelNames.isNotEmpty()) " · ${m.modelNames.first()}" else ""}")
                    .put("provider", m.type.name)
                    .put("mark", "L")
                    .put("group", "local"))
            }
            groups.put(JSONObject().put("name", "本地离线模型").put("models", arr))
        }

        // API 拉取缓存
        val fetched = repo.loadModelListCache(cfg.baseUrl)
        if (fetched is com.ai.assistance.quro.core.network.QuroModelListResult.Success && fetched.models.isNotEmpty()) {
            val arr = JSONArray()
            fetched.models.forEach { info ->
                arr.put(JSONObject()
                    .put("id", info.id)
                    .put("name", info.id)
                    .put("desc", "从 API 拉取 · ${cfg.baseUrl}")
                    .put("provider", cfg.provider.ifBlank { "OPENAI" })
                    .put("mark", info.id.firstOrNull()?.uppercase() ?: "M")
                    .put("group", "fetched"))
            }
            groups.put(JSONObject().put("name", "从 API 拉取").put("models", arr))
        }

        return JSONObject()
            .put("ok", true)
            .put("current", cfg.model)
            .put("groups", groups)
            .toString()
    }

    /** 人格列表 + 当前激活。 */
    private fun personasJson(): String {
        val repo = QuroPersonaRepository(context)
        val all = repo.loadAll()
        val activeId = repo.getActiveId()
        val arr = JSONArray()
        all.forEach { p ->
            arr.put(JSONObject()
                .put("id", p.id)
                .put("name", p.name)
                .put("avatarEmoji", p.avatarEmoji)
                .put("avatarType", p.avatarType)
                .put("avatarUri", p.avatarUri)
                .put("description", p.description)
                .put("active", p.id == activeId))
        }
        return JSONObject()
            .put("ok", true)
            .put("activeId", activeId)
            .put("personas", arr)
            .toString()
    }

    /** 已上传附件列表。 */
    private fun attachmentsJson(): String {
        val arr = JSONArray()
        synchronized(pendingAttachments) {
            pendingAttachments.forEach { (id, a) ->
                arr.put(JSONObject()
                    .put("id", id)
                    .put("name", a.name)
                    .put("type", a.type)
                    .put("size", a.size)
                    .put("mime", a.mime))
            }
        }
        return JSONObject().put("ok", true).put("attachments", arr).toString()
    }

    /* ── SSE：状态变化即推 ─────────────────────────────────────────────── */

    private class Subscriber(val out: OutputStream) {
        @Volatile var alive = true
    }

    private val subscribers = Collections.synchronizedList(mutableListOf<Subscriber>())

    private var revisionThread: Thread? = null

    /**
     * 正在进行 SSE 发送流的连接数。
     *
     * 流式期间 revision 检测从 80ms 放宽到 1s：增量文本由发送流自己逐帧推送，
     * 快照通道只负责离散状态（工具卡片挂载 / 集群发言 / 开关变化）。
     * 不放宽就是旧病根 —— 每 80ms 全量序列化 + 浏览器整列表重绘，
     * 消息越长越卡，直接表现为「AI 写到一半卡了」。
     */
    @Volatile private var activeSends = AtomicInteger(0)

    private fun ensureRevisionWatch() {
        if (revisionThread?.isAlive == true) return
        revisionThread = Thread({
            var last = ""
            while (running) {
                try {
                    // 流式期间 1s 一次就够（增量走 /api/send 的 SSE）；空闲时 80ms 跟手
                    val interval = if (activeSends.get() > 0) STREAM_REVISION_INTERVAL_MS else REVISION_INTERVAL_MS
                    Thread.sleep(interval)
                    if (subscribers.isEmpty()) { last = ""; continue }
                    val rev = revision()
                    if (rev == last) continue
                    last = rev
                    push(snapshot())
                } catch (_: InterruptedException) {
                    break
                } catch (e: Throwable) {
                    QuroDiag.log(TAG, "状态变化检测异常：${e.message}")
                }
            }
        }, "QuroWebUiRev").also {
            it.isDaemon = true
            it.start()
        }
    }

    /**
     * 状态指纹：**故意便宜**（每 ~80 ms 一次，不能在里面做 JSON 序列化）。
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

    private fun push(payload: String) {
        if (subscribers.isEmpty()) return
        val bytes = sseFrame(payload)
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

    /* ── POST：动作（对等客户端：全部写回原生同一真源）──────────────── */

    /**
     * @return true 表示这条连接已被接管（SSE 长流），调用方不得再 close。
     */
    private fun handlePost(sock: Socket, path: String, query: String, contentType: String, body: String): Boolean {
        when (path) {
            "/api/send" -> return handleSend(sock, body)
            "/api/stop" -> {
                vm.stop()
                respondJson(sock, """{"ok":true}""")
            }
            "/api/new" -> {
                vm.newConversation()
                respondJson(sock, """{"ok":true}""")
            }
            "/api/clear" -> {
                vm.clear()
                respondJson(sock, """{"ok":true}""")
            }
            // 开关写回（对等客户端：可点）
            "/api/toggle" -> handleToggle(sock, body)
            // 三态策略写回
            "/api/policy" -> handlePolicy(sock, body)
            // 工作区设置
            "/api/workspace" -> handleWorkspace(sock, body)
            // 模型选择（原生 onSelectModel 同一分支逻辑）
            "/api/models/select" -> handleModelSelect(sock, body)
            // 人格选择
            "/api/personas/select" -> handlePersonaSelect(sock, body)
            // 上传文件（multipart/form-data）
            "/api/upload" -> handleUpload(sock, contentType, body)
            // 删除暂存附件
            "/api/attachments/remove" -> handleAttachmentRemove(sock, body)
            // 打开原生功能面板
            "/api/ui_action" -> handleUiAction(sock, body)
            // 卡片按钮 / 动态 UI 交互写回（对等客户端：复刻 ChatScreen.handleCardCommand + handleDynamicUiAction）
            "/api/card_action" -> handleCardAction(sock, body)
            // 导出会话
            "/api/export" -> handleExport(sock)
            // 页面自检回传
            "/api/diag" -> {
                val o = runCatching { JSONObject(body) }.getOrNull()
                val msg = o?.optString("msg")?.trim().orEmpty()
                if (msg.isEmpty()) {
                    respondJson(sock, """{"ok":false,"error":"empty msg"}""")
                    return false
                }
                val lvl = o?.optString("level")?.trim().orEmpty().ifBlank { "info" }
                QuroDiag.log(TAG, "页面自检[$lvl] $msg")
                respondJson(sock, """{"ok":true}""")
            }
            "/api/interact" -> {
                val o = runCatching { JSONObject(body) }.getOrNull()
                val component = o?.optString("component")?.trim().orEmpty()
                val payload = o?.opt("payload")
                if (component.isEmpty() || payload == null) {
                    respondJson(sock, """{"ok":false,"error":"need component + payload"}""")
                    return false
                }
                val json = when (payload) {
                    is JSONObject -> payload
                    is String -> runCatching { JSONObject(payload) }.getOrElse {
                        JSONObject().put("action", "raw").put("summary", payload)
                    }
                    else -> JSONObject().put("action", "raw").put("summary", payload.toString())
                }
                val text = ZxWebCard.summarize(component, json)
                QuroDiag.log(TAG, "zxwidget 交互（web）：$text")
                val hit = ZxWebCardQueue.submitByComponent(component, text)
                if (!hit) vm.send(text)
                respondJson(sock, """{"ok":true,"delivered":"${if (hit) "tool" else "chat"}"}""")
            }
            else -> respondJson(sock, """{"ok":false,"error":"unknown api: $path"}""", 404)
        }
        return false
    }

    /* ── 发送：SSE 增量流（对齐 Operit web-chat 的 messages/stream 协议）─
     *
     * 为什么必须流式（用户 2026-10-14 反馈根因）：
     *   旧实现 POST /api/send 只回 {"ok":true}，前端靠 80ms 轮询 revision +
     *   **全量快照重推**看 AI 打字。消息越长，每次快照序列化 + 浏览器整列表
     *   重绘的开销越大 → 表现为「消息延迟半天」「AI 写到一半卡了」。
     *
     * 新协议（事件流，逐帧只推增量）：
     *   event: start            → 流已建立（此时用户消息已进 vm，可显示「已发送」）
     *   event: user_message     → data = 该用户消息的完整投影 JSON（替换前端乐观气泡）
     *   event: assistant_delta  → data = {id, text, reasoning} **增量**文本/思考
     *   event: assistant_done   → data = {message} 最终完整投影（含 blocks/cards/tools）
     *   event: error            → data = {error}
     *
     * 前端读法（Operit 同款）：fetch + response.body.getReader() 逐块解析，
     * **不用 EventSource**（POST 带 body + 需 AbortController 取消）。
     * ─────────────────────────────────────────────────────────────────── */

    /**
     * 发送消息：接管连接，输出 SSE 增量流。
     * @return true = 连接已接管（调用方不得 close）；false = 已按普通 JSON 响应并关闭。
     */
    private fun handleSend(sock: Socket, body: String): Boolean {
        val o = runCatching { JSONObject(body) }.getOrNull()
        val text = o?.optString("text")?.trim().orEmpty()
        val attIds = o?.optJSONArray("attachments")
        if (text.isEmpty() && (attIds == null || attIds.length() == 0)) {
            respondJson(sock, """{"ok":false,"error":"empty text"}""")
            return false
        }
        val atts = mutableListOf<QuroAttachment>()
        if (attIds != null) {
            synchronized(pendingAttachments) {
                for (i in 0 until attIds.length()) {
                    val id = attIds.optString(i)
                    pendingAttachments.remove(id)?.let { atts.add(it) }
                }
            }
        }

        // 发送前的消息 id 集合：用于识别「本轮新增的用户消息」。
        // 绝不用 size 判断——barge-in / 集群 / 语音球都会并发改列表。
        val beforeIds = HashSet<String>().apply { vm.messages.value.forEach { add(it.id) } }

        val out = try {
            sock.getOutputStream()
        } catch (e: Throwable) {
            QuroDiag.log(TAG, "SSE 发送流取输出流失败：${e.message}")
            respondJson(sock, """{"ok":false,"error":"stream unavailable"}""")
            return false
        }

        // SSE 响应头（无 Content-Length，Connection: keep-alive）
        val head = StringBuilder()
        head.append("HTTP/1.1 200 OK\r\n")
        head.append("Content-Type: text/event-stream; charset=utf-8\r\n")
        head.append("Cache-Control: no-store\r\n")
        head.append("Connection: keep-alive\r\n")
        head.append("Access-Control-Allow-Origin: *\r\n")
        head.append("X-Accel-Buffering: no\r\n")
        head.append("\r\n")
        try {
            out.write(head.toString().toByteArray(Charsets.UTF_8))
            out.flush()
        } catch (e: Throwable) {
            QuroDiag.log(TAG, "SSE 发送流首帧失败：${e.message}")
            return false
        }

        // vm.send 会立刻把用户消息写进 _messages 并启动生成协程。
        // 它内部只碰 MutableStateFlow / viewModelScope.launch，从 HTTP 线程调也安全
        // （原生 ChatScreen 的发送按钮在主线程调，两条入口都通向同一条协程链）。
        vm.send(text, atts)

        val sends = activeSends.incrementAndGet()
        QuroDiag.log(TAG, "SSE 发送流接入，当前 $sends 条")

        // 本线程即流线程：轮询 vm.messages，只推增量。
        var segId: String? = null          // 当前跟踪的助手消息 id（跨工具轮次会换段）
        var segTextLen = 0                 // 已推送的正文长度
        var segReasonLen = 0               // 已推送的思考长度
        var idlePolls = 0                  // busy=false 后的静默轮次
        val seenUsers = HashSet<String>()  // 已推送的用户消息 id
        val deadline = System.currentTimeMillis() + SEND_STREAM_MAX_MS
        var lastPing = System.currentTimeMillis()
        var pushedAny = false
        try {
            writeSse(out, "start", JSONObject().put("ok", true))
            while (running && System.currentTimeMillis() < deadline) {
                Thread.sleep(SEND_POLL_MS)
                val msgs = vm.messages.value

                // ① 本轮新增用户消息（含 barge-in 场景下的多条）
                for (m in msgs) {
                    if (m.role == "user" && !m.hidden && m.id !in beforeIds && seenUsers.add(m.id)) {
                        writeSse(out, "user_message", WebUiProjection.message(m, mine = true))
                        pushedAny = true
                    }
                }

                // ② 最后一条可见助手消息的增量
                val am = msgs.lastOrNull { it.role == "assistant" && !it.hidden }
                if (am != null) {
                    if (am.id != segId) {
                        // 新段（首 token 占位气泡 / 工具轮次后的新回复段）：
                        // 前端尚未见过它，把已有全文当作首帧 delta 推过去。
                        segId = am.id
                        segTextLen = am.content.length
                        segReasonLen = am.reasoning?.length ?: 0
                        if (segTextLen > 0 || segReasonLen > 0) {
                            writeSse(
                                out, "assistant_delta",
                                JSONObject()
                                    .put("id", am.id)
                                    .put("text", am.content)
                                    .put("reasoning", am.reasoning ?: ""),
                            )
                            pushedAny = true
                        }
                    } else {
                        val tLen = am.content.length
                        if (tLen > segTextLen) {
                            writeSse(
                                out, "assistant_delta",
                                JSONObject()
                                    .put("id", am.id)
                                    .put("text", am.content.substring(segTextLen))
                                    .put("reasoning", ""),
                            )
                            segTextLen = tLen
                            pushedAny = true
                        }
                        val rLen = am.reasoning?.length ?: 0
                        if (rLen > segReasonLen) {
                            writeSse(
                                out, "assistant_delta",
                                JSONObject()
                                    .put("id", am.id)
                                    .put("text", "")
                                    .put("reasoning", am.reasoning!!.substring(segReasonLen)),
                            )
                            segReasonLen = rLen
                            pushedAny = true
                        }
                    }
                }

                // ③ 结束判定：不忙 + 连续几轮无增量 → 本轮收敛
                if (!vm.busy.value) {
                    idlePolls++
                    if (idlePolls >= SEND_IDLE_DONE_POLLS) break
                } else {
                    idlePolls = 0
                }

                // ④ 心跳：长工具调用期间前端不能看到连接假死
                val now = System.currentTimeMillis()
                if (now - lastPing >= HEARTBEAT_MS) {
                    lastPing = now
                    synchronized(out) { out.write(": ping\n\n".toByteArray(Charsets.UTF_8)); out.flush() }
                }
            }

            // 终帧：最终完整投影（blocks/cards/tools/ui 全部就位），前端据此整段终渲
            val finalMsg = vm.messages.value.lastOrNull { it.role == "assistant" && !it.hidden }
            if (finalMsg != null) {
                writeSse(
                    out, "assistant_done",
                    JSONObject().put("message", WebUiProjection.message(finalMsg, mine = false)),
                )
            } else {
                writeSse(out, "assistant_done", JSONObject().put("message", JSONObject.NULL))
            }
            out.flush()
        } catch (e: Throwable) {
            // 前端断开 / 写失败：生成仍在原生侧继续，不影响对话本身
            QuroDiag.log(TAG, "SSE 发送流异常断开（生成继续）：${e.javaClass.simpleName} ${e.message}")
        } finally {
            activeSends.decrementAndGet()
            runCatching { sock.close() }
            QuroDiag.log(TAG, "SSE 发送流结束（pushed=$pushedAny），剩 ${activeSends.get()} 条")
        }
        return true
    }

    /** 写一个 SSE 事件帧（`event:` + `data:` 双字段，前端按块切分）。 */
    private fun writeSse(out: OutputStream, event: String, data: JSONObject) {
        val frame = "event: $event\ndata: $data\n\n"
        synchronized(out) {
            out.write(frame.toByteArray(Charsets.UTF_8))
            out.flush()
        }
    }

    /** 开关写回。 */
    private fun handleToggle(sock: Socket, body: String) {
        val o = runCatching { JSONObject(body) }.getOrNull()
        val key = o?.optString("key")?.trim().orEmpty()
        val on = o?.optBoolean("on") ?: return respondJson(sock, """{"ok":false,"error":"need on"}""")
        when (key) {
            "deepThink" -> vm.setThinking(on)
            "autoSaveMemory" -> vm.setAutoSaveMemory(on)
            "subAgent" -> vm.setSubAgentEnabled(on)
            "autoRead" -> QuroVoiceFeaturePrefs.setAutoRead(context, on)
            "vision" -> vm.setVisionEnabled(on)
            else -> {
                respondJson(sock, """{"ok":false,"error":"unknown key: $key"}""", 404)
                return
            }
        }
        respondJson(sock, """{"ok":true}""")
    }

    /** 三态策略写回。 */
    private fun handlePolicy(sock: Socket, body: String) {
        val o = runCatching { JSONObject(body) }.getOrNull()
        val key = o?.optString("key")?.trim().orEmpty()
        val value = o?.optString("value")?.trim().orEmpty()
        val policy = when (value) {
            "allow" -> QuroPolicy.ALLOW
            "deny" -> QuroPolicy.DENY
            "ask" -> QuroPolicy.ASK
            else -> {
                respondJson(sock, """{"ok":false,"error":"bad value: $value"}""", 400)
                return
            }
        }
        when (key) {
            "cms" -> QuroPolicyStore.setCms(context, policy)
            "priv" -> QuroPolicyStore.setPriv(context, policy)
            else -> {
                respondJson(sock, """{"ok":false,"error":"unknown key: $key"}""", 404)
                return
            }
        }
        respondJson(sock, """{"ok":true}""")
    }

    /** 工作区设置。 */
    private fun handleWorkspace(sock: Socket, body: String) {
        val o = runCatching { JSONObject(body) }.getOrNull()
        val path = o?.optString("path")?.trim().orEmpty()
        WorkspacePreferences.setCurrentWorkspace(context, path.ifBlank { null })
        respondJson(sock, """{"ok":true}""")
    }

    /** 模型选择：复刻原生 ChatScreen.onSelectModel 分支逻辑。 */
    private fun handleModelSelect(sock: Socket, body: String) {
        val o = runCatching { JSONObject(body) }.getOrNull()
        val id = o?.optString("id")?.trim().orEmpty()
        if (id.isEmpty()) {
            respondJson(sock, """{"ok":false,"error":"empty id"}""")
            return
        }
        val repo = QuroModelConfigRepository(context)
        when {
            id.startsWith("__profile__") -> {
                val profileId = id.removePrefix("__profile__")
                val profile = QuroSavedProfileRepository(context).loadAll().find { it.id == profileId }
                if (profile != null) {
                    QuroSavedProfileRepository(context).applyToConfig(profile, repo)
                }
            }
            id.startsWith("__custom__") -> {
                val cpId = id.removePrefix("__custom__")
                val cp = QuroCustomProviderRepository(context).findById(cpId)
                if (cp != null) {
                    val cfg = repo.load()
                    var newCfg = cfg.copy(
                        provider = "OTHER",
                        customProviderName = cp.name,
                        baseUrl = cp.baseUrl,
                        model = cp.defaultModel,
                    )
                    // 复刻 QuroModelConfigViewModel.update 的自动识别：
                    // baseUrl 变化时用 ApiProviderConfigs 探测真实厂商并覆盖 provider。
                    if (cfg.baseUrl != newCfg.baseUrl && newCfg.baseUrl.isNotBlank()) {
                        val detected = ApiProviderConfigs.detectProviderFromUrl(newCfg.baseUrl)
                        if (detected.name != newCfg.provider) {
                            newCfg = newCfg.copy(provider = detected.name)
                        }
                    }
                    repo.save(newCfg)
                }
            }
            id.startsWith("__local__") -> {
                val lmId = id.removePrefix("__local__")
                val lm = QuroLocalModelRepository(context).findById(lmId)
                if (lm != null) {
                    val cfg = repo.load()
                    repo.save(cfg.copy(
                        provider = lm.type.name,
                        localModelPath = lm.path,
                        model = lm.modelNames.firstOrNull() ?: "",
                    ))
                }
            }
            else -> {
                val cfg = repo.load()
                repo.save(cfg.copy(model = id))
            }
        }
        respondJson(sock, """{"ok":true}""")
    }

    /** 人格选择：写回仓库（vm.send 时 activePersona() 直接读仓库）。 */
    private fun handlePersonaSelect(sock: Socket, body: String) {
        val o = runCatching { JSONObject(body) }.getOrNull()
        val id = o?.optString("id")?.trim().orEmpty()
        if (id.isEmpty()) {
            respondJson(sock, """{"ok":false,"error":"empty id"}""")
            return
        }
        QuroPersonaRepository(context).setActiveId(id)
        respondJson(sock, """{"ok":true}""")
    }

    /**
     * 处理文件上传（base64 JSON 方式）。
     *
     * 前端把文件读成 base64（FileReader.readAsDataURL），POST：
     *   {"name":"photo.jpg","mime":"image/jpeg","dataBase64":"/9j/..."}
     * 服务端解码写入应用私有 uploads 目录，构造 QuroAttachment 存入暂存表。
     *
     * 为什么不用 multipart：本服务是极简 HTTP/1.1 服务器，`readBody` 按 Char 读，
     * 二进制经 String 中转再 split boundary 会有损（图片会坏）。base64 是纯文本，
     * 与现有 readBody 完美兼容。
     */
    private fun handleUpload(sock: Socket, contentType: String, body: String) {
        val o = runCatching { JSONObject(body) }.getOrNull()
        val name = o?.optString("name")?.trim().orEmpty()
        val mime = o?.optString("mime")?.trim().orEmpty()
        val dataB64 = o?.optString("dataBase64")?.trim().orEmpty()
        if (name.isEmpty() || dataB64.isEmpty()) {
            respondJson(sock, """{"ok":false,"error":"need name + dataBase64"}""", 400)
            return
        }
        val bytes = runCatching { android.util.Base64.decode(dataB64, android.util.Base64.DEFAULT) }.getOrNull()
        if (bytes == null || bytes.isEmpty()) {
            respondJson(sock, """{"ok":false,"error":"bad base64"}""", 400)
            return
        }
        uploadsDir.mkdirs()
        val safeName = name.substringAfterLast('/').substringAfterLast('\\').takeIf { it.isNotBlank() } ?: "upload.bin"
        val stored = File(uploadsDir, "web_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}_${safeName}")
        stored.writeBytes(bytes)
        val att = QuroAttachmentKit.fromFile(context, stored, mime.ifBlank { "application/octet-stream" })
        val id = UUID.randomUUID().toString()
        pendingAttachments[id] = att
        respondJson(sock, JSONObject()
            .put("ok", true)
            .put("id", id)
            .put("name", att.name)
            .put("type", att.type)
            .put("size", att.size)
            .put("mime", att.mime)
            .toString())
    }

    /** 删除暂存附件。 */
    private fun handleAttachmentRemove(sock: Socket, body: String) {
        val o = runCatching { JSONObject(body) }.getOrNull()
        val id = o?.optString("id")?.trim().orEmpty()
        if (id.isEmpty()) {
            respondJson(sock, """{"ok":false,"error":"empty id"}""")
            return
        }
        synchronized(pendingAttachments) {
            pendingAttachments.remove(id)
        }
        respondJson(sock, """{"ok":true}""")
    }

    /** 打开原生功能面板。 */
    private fun handleUiAction(sock: Socket, body: String) {
        val o = runCatching { JSONObject(body) }.getOrNull()
        val action = o?.optString("action")?.trim().orEmpty()
        if (action.isEmpty()) {
            respondJson(sock, """{"ok":false,"error":"empty action"}""")
            return
        }
        val d = QuroUiActionBridge.dispatch
        if (d != null) {
            d(action)
            respondJson(sock, """{"ok":true}""")
        } else {
            // 桥未就绪：暂存待派发（ChatScreen 注入时补派）
            QuroUiActionBridge.pendingAction = action
            respondJson(sock, """{"ok":true,"queued":true}""")
        }
    }

    /**
     * 卡片按钮 / 动态 UI 交互写回（对等客户端核心）。
     *
     * 复刻 `ChatScreen.handleCardCommand`（卡片按钮）与 `handleDynamicUiAction`（动态 UI 节点）：
     *  - `command` 字段 → `CardAction.parse` + `CardActionRouter.dispatch`，未命中则走老链路 `when`
     *    （reply:/ui_/linux:install/run:/open:/copy:/ai:/screen:/ui_reply:/ui_open_music_player/__edit_soul_card__）
     *  - `action` 字段（`{type,...}`）→ 映射回 `QuroUiAction` 子类，复刻原生分发
     *    （callback→ui_reply:/tool_call→QuroToolRegistry/skill→QuroSkillTool/copy→剪贴板/
     *      open_app→LaunchAppTool/open_url→内置浏览器/open_screen/render_html/render_vispro/
     *      visual_popup/visual_ask/zx_web_card→UiNavigationBus/toggle→Unit）
     *  - `values` 是渲染器收集的控件值（text_input/select/slider 等）。
     */
    private fun handleCardAction(sock: Socket, body: String) {
        val o = runCatching { JSONObject(body) }.getOrNull()
            ?: return respondJson(sock, """{"ok":false,"error":"bad json"}""", 400)
        // 前端格式：{cmd:"reply:xxx"} / {cmd:"...", value, checked, selected, index, values} /
        //           {ui:{event,data,collectFrom}} / {ui:{tool,arguments}} / {ui:{skill,input}} ...
        val command = o.optString("cmd").ifBlank { o.optString("command") }.trim()
        val values = mutableMapOf<String, String>()
        o.optJSONObject("values")?.let { v ->
            v.keys().forEach { k -> values[k] = v.optString(k) }
        }

        // 路径一：卡片按钮命令（裸串 / JSON 信封）——cmdButton/cmdChip/selfCardAction/表单提交都走这里
        if (command.isNotEmpty()) {
            handleCardCommand(sock, command)
            return
        }

        // 路径二：动态 UI 动作 JSON（uiActionClick 发送的 {ui:{...}}，靠键名推断类型）
        val ui = o.optJSONObject("ui") ?: o.optJSONObject("action") ?: o.optJSONObject("uiAction")
            ?: return respondJson(sock, """{"ok":false,"error":"need cmd or ui"}""", 400)
        handleDynamicUiActionJson(sock, ui, values)
    }

    /** 复刻 `ChatScreen.handleCardCommand`（老链路 + 结构化动作）。 */
    private fun handleCardCommand(sock: Socket, cmd: String) {
        val action = CardAction.parse(cmd)
        if (CardActionRouter.dispatch(action) { a -> handleCardAction(a) }) {
            respondJson(sock, """{"ok":true}""")
            return
        }
        when {
            cmd.startsWith("reply:") -> {
                val t = cmd.removePrefix("reply:").trim()
                if (t.isNotEmpty()) vm.send(t)
            }
            cmd.startsWith("ui_") -> {
                val d = QuroUiActionBridge.dispatch
                if (d != null) d(cmd) else QuroUiActionBridge.pendingAction = cmd
            }
            cmd == "linux:install" -> QuroLinuxEnv.setup(context)
            cmd.startsWith("run:") -> {
                val t = cmd.removePrefix("run:").trim()
                UiNavigationBus.send(UiNavigationEvent.OpenScreen("terminal"))
            }
            cmd.startsWith("open:") -> QuroBrowserBridge.open(cmd.removePrefix("open:").trim())
            cmd.startsWith("copy:") -> {
                copyToClipboard(cmd.removePrefix("copy:").trim())
            }
            cmd.startsWith("ai:") -> {
                val t = cmd.removePrefix("ai:").trim()
                if (t.isNotEmpty()) vm.send(t)
            }
            cmd.startsWith("screen:") -> {
                val d = QuroUiActionBridge.dispatch
                if (d != null) d(cmd.removePrefix("screen:").trim()) else QuroUiActionBridge.pendingAction = cmd.removePrefix("screen:").trim()
            }
            cmd.startsWith(UI_REPLY_PREFIX) -> {
                val t = cmd.removePrefix(UI_REPLY_PREFIX).trim()
                if (t.isNotEmpty()) vm.send(t)
            }
            cmd == "ui_open_music_player" -> {
                UiNavigationBus.send(UiNavigationEvent.OpenScreen("music_player"))
            }
            cmd == "__edit_soul_card__" -> {
                UiNavigationBus.send(UiNavigationEvent.OpenScreen("soul"))
            }
        }
        respondJson(sock, """{"ok":true}""")
    }

    /** 复刻 `ChatScreen.handleCardAction` 的结构化动作分支。返回 true=已处理。 */
    private fun handleCardAction(a: CardAction.Action): Boolean = when (a.kind) {
        CardAction.Kind.REPLY, CardAction.Kind.AI -> {
            val t = a.payload()
            if (t.isNotEmpty()) { vm.send(t); true } else false
        }
        CardAction.Kind.UI, CardAction.Kind.SCREEN -> {
            val d = QuroUiActionBridge.dispatch
            if (d != null) d(a.payload()) else QuroUiActionBridge.pendingAction = a.payload()
            true
        }
        CardAction.Kind.INSTALL -> { QuroLinuxEnv.setup(context); true }
        CardAction.Kind.RUN -> { UiNavigationBus.send(UiNavigationEvent.OpenScreen("terminal")); true }
        CardAction.Kind.OPEN -> { QuroBrowserBridge.open(a.payload()); true }
        CardAction.Kind.COPY -> { copyToClipboard(a.payload()); true }
        CardAction.Kind.NAVIGATE -> {
            val t = a.payload()
            if (t.isNotEmpty()) { UiNavigationBus.send(UiNavigationEvent.NavigateTo(t)); true } else false
        }
        CardAction.Kind.TOAST -> {
            val t = a.payload()
            if (t.isNotEmpty()) { Toast.makeText(context, t, Toast.LENGTH_SHORT).show(); true } else false
        }
        CardAction.Kind.EMIT, CardAction.Kind.UNKNOWN -> false
    }

    /**
     * 复刻 `handleDynamicUiAction`：把前端 `{ui:{...}}` 动作映射回 [QuroUiAction] 子类并分发。
     *
     * 前端 `uiActionClick` 发送的对象**没有 type 字段**，靠键名推断：
     *  - has(event) → [QuroCallbackAction]
     *  - has(tool) → [QuroToolCallAction]
     *  - has(skill) → [QuroSkillAction]
     *  - has(openUrl) → [QuroOpenUrlAction]
     *  - has(copy) → [QuroCopyAction]
     *  - has(openApp) → [QuroOpenAppAction]
     *  - has(openScreen) → [QuroOpenScreenAction]
     *  - has(renderHtml) → [QuroRenderHtmlAction]
     *  - has(renderVispro) → [QuroRenderVisproAction]
     *  - has(visualPopup) → [QuroVisualPopupAction]
     *  - has(visualAsk) → [QuroVisualAskAction]
     *  - has(zxWebCard) → [QuroZxWebCardAction]
     *  - has(toggle) → [QuroToggleAction]
     */
    private fun handleDynamicUiActionJson(sock: Socket, a: JSONObject, values: Map<String, String>) {
        val scope = CoroutineScope(Dispatchers.IO)
        val onCommand: (String) -> Unit = { c ->
            val t = c.removePrefix(UI_REPLY_PREFIX).trim()
            if (t.isNotEmpty()) vm.send(t)
        }
        val onOpenLink: (String) -> Unit = { url -> QuroBrowserBridge.open(url) }

        when {
            a.has("event") -> { // callback
                val event = a.optString("event")
                val data = mutableMapOf<String, String>()
                a.optJSONObject("data")?.let { d -> d.keys().forEach { k -> data[k] = d.optString(k) } }
                val merged = LinkedHashMap<String, String>(values).apply { putAll(data) }
                val body = if (merged.isNotEmpty()) merged.entries.joinToString("\n") { "${it.key}: ${it.value}" } else event
                val payload = if (event.isNotBlank()) "【$event】\n$body" else body
                onCommand(UI_REPLY_PREFIX + payload)
            }
            a.has("toggle") -> Unit // 渲染器内部已处理
            a.has("openUrl") -> {
                val url = a.optString("openUrl").ifBlank { values.values.firstOrNull() ?: "" }
                if (url.isNotBlank()) onOpenLink(url)
            }
            a.has("copy") -> {
                val text = a.optString("copy").ifBlank { values.values.firstOrNull() ?: "" }
                if (text.isNotBlank()) copyToClipboard(text) else Toast.makeText(context, "没有可复制的内容", Toast.LENGTH_SHORT).show()
            }
            a.has("tool") -> { // tool_call
                val toolName = a.optString("tool").ifBlank { values["tool"] ?: "" }
                if (toolName.isBlank()) { Toast.makeText(context, "缺少工具名", Toast.LENGTH_SHORT).show(); return }
                val args = LinkedHashMap<String, String>()
                a.optJSONObject("arguments")?.let { d -> d.keys().forEach { k -> args[k] = d.optString(k) } }
                args.putAll(values)
                scope.launch(Dispatchers.IO) {
                    val tool = QuroToolRegistry.active?.get(toolName)
                    if (tool == null) {
                        withContext(Dispatchers.Main) { onCommand("[ui_tool_request] {\"tool\":\"$toolName\",\"args\":{}}") }
                        return@launch
                    }
                    if (tool.requiredPermissions.isNotEmpty()) {
                        val argsJson = JSONObject().apply { args.forEach { (k, v) -> put(k, v) } }.toString()
                        withContext(Dispatchers.Main) { onCommand("[ui_tool_request] {\"tool\":\"$toolName\",\"args\":$argsJson}") }
                        return@launch
                    }
                    val argsJson = JSONObject().apply { args.forEach { (k, v) -> put(k, v) } }.toString()
                    val result = runCatching { tool.run(context, argsJson) }.getOrDefault("工具执行失败")
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "工具 $toolName 已执行", Toast.LENGTH_SHORT).show()
                        val feedback = if (result.length > 2000) result.take(2000) + "…" else result
                        onCommand("工具 $toolName 返回：$feedback")
                    }
                }
            }
            a.has("skill") -> {
                val skillName = (a.optString("skill").ifBlank { values["skill"] ?: "" }).removePrefix("skill__")
                if (skillName.isBlank()) { Toast.makeText(context, "缺少技能名", Toast.LENGTH_SHORT).show(); return }
                val input = a.optString("input").takeIf { it.isNotBlank() } ?: values.values.joinToString("，")
                scope.launch(Dispatchers.IO) {
                    val directive = runCatching {
                        (QuroToolRegistry.active?.get("skill__$skillName") ?: QuroSkillTool(skillName, context))
                            .run(context, JSONObject().apply { put("input", input) }.toString())
                    }.getOrDefault("技能 $skillName 执行失败")
                    withContext(Dispatchers.Main) { onCommand(directive) }
                }
            }
            a.has("openApp") -> {
                val target = a.optString("openApp").ifBlank { values.values.firstOrNull() ?: "" }
                if (target.isBlank()) { Toast.makeText(context, "缺少应用名", Toast.LENGTH_SHORT).show(); return }
                scope.launch(Dispatchers.Main) {
                    val result = runCatching {
                        LaunchAppTool().run(context, JSONObject().apply {
                            if (target.contains(".")) put("package", target) else put("name", target)
                        }.toString())
                    }.getOrDefault("启动失败")
                    Toast.makeText(context, result.take(120), Toast.LENGTH_SHORT).show()
                }
            }
            a.has("openScreen") -> {
                val target = a.optString("openScreen").ifBlank { values["target"] ?: "" }
                if (target.isBlank()) { Toast.makeText(context, "缺少目标界面", Toast.LENGTH_SHORT).show(); return }
                UiNavigationBus.send(UiNavigationEvent.OpenScreen(target))
            }
            a.has("renderHtml") -> {
                val html = a.optString("renderHtml").ifBlank { values.values.firstOrNull() ?: "" }
                if (html.isBlank()) { Toast.makeText(context, "缺少 HTML", Toast.LENGTH_SHORT).show(); return }
                UiNavigationBus.send(UiNavigationEvent.RenderWidget(
                    type = "miniapp",
                    id = "dyn_html_${System.nanoTime()}",
                    label = "HTML",
                    value = html,
                ))
            }
            a.has("renderVispro") -> {
                val source = a.optString("renderVispro").ifBlank { values.values.firstOrNull() ?: "" }
                if (source.isBlank()) { Toast.makeText(context, "缺少 mermaid 源码", Toast.LENGTH_SHORT).show(); return }
                UiNavigationBus.send(UiNavigationEvent.RenderWidget(
                    type = "mermaid",
                    id = "dyn_mermaid_${System.nanoTime()}",
                    label = "Mermaid",
                    value = source,
                ))
            }
            a.has("visualPopup") -> {
                val vp = a.optJSONObject("visualPopup")
                UiNavigationBus.send(UiNavigationEvent.VisualPopup(
                    title = vp?.optString("title") ?: "",
                    content = vp?.optString("content") ?: "",
                ))
            }
            a.has("visualAsk") -> {
                val va = a.optJSONObject("visualAsk")
                val options = mutableListOf<String>()
                va?.optJSONArray("options")?.let { arr -> for (i in 0 until arr.length()) options.add(arr.optString(i)) }
                UiNavigationBus.send(UiNavigationEvent.VisualAsk(
                    prompt = va?.optString("prompt") ?: "",
                    options = options,
                ))
            }
            a.has("zxWebCard") -> {
                val zx = a.optJSONObject("zxWebCard")
                val component = zx?.optString("component").orEmpty().ifBlank { values["component"] ?: "" }
                val dsl = zx?.optString("dsl").orEmpty().ifBlank { values["dsl"] ?: "" }
                if (component.isBlank() || dsl.isBlank()) { Toast.makeText(context, "缺少 zx 组件信息", Toast.LENGTH_SHORT).show(); return }
                UiNavigationBus.send(UiNavigationEvent.ZxWebCard(
                    title = zx?.optString("title").orEmpty().ifBlank { values["title"] ?: "" },
                    component = component,
                    dsl = dsl,
                ))
            }
            else -> {
                respondJson(sock, """{"ok":false,"error":"unknown ui action: ${a.keys().asSequence().toList()}"}""", 400)
                return
            }
        }
        respondJson(sock, """{"ok":true}""")
    }

    /** 写系统剪贴板（复刻 ChatScreen.copyPlain）。 */
    private fun copyToClipboard(text: String) {
        if (text.isBlank()) return
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("Zorv", text))
        Toast.makeText(context, qstr(com.ai.assistance.quro.R.string.qk_00023), Toast.LENGTH_SHORT).show()
    }

    /** 导出当前会话（生成文件路径）。 */
    private fun handleExport(sock: Socket) {
        val path = runCatching {
            // 复用与原生导出相同的逻辑入口（ChatScreen 里 exportConversation 是私有函数，
            // 这里退化为返回会话纯文本，供网页展示/复制）。
            val sb = StringBuilder()
            vm.messages.value.forEach { m ->
                val who = if (m.role == "user") "我" else (m.senderName ?: "ZorvAI")
                sb.append(who).append(": ").append(m.content).append("\n\n")
            }
            sb.toString()
        }.getOrDefault("")
        respondJson(sock, JSONObject().put("ok", true).put("text", path).toString())
    }

    /* ── 快照 ─────────────────────────────────────────────────────────── */

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
                    vision = vm.visionEnabled.value,
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
        head.append("Content-Length: ").append(bytes.size).append("\r")
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

        /** 默认端口：对齐 dsh web 的 3080。 */
        const val DEFAULT_PORT = 3080

        private const val SOCKET_TIMEOUT_MS = 20000

        private const val REVISION_INTERVAL_MS = 80L

        /** 流式生成期间的 revision 检测间隔（增量由 /api/send SSE 负责，这里只兜离散状态）。 */
        private const val STREAM_REVISION_INTERVAL_MS = 1000L

        /** 发送流轮询 vm.messages 的间隔（≈16fps，肉眼顺滑且不烧 CPU）。 */
        private const val SEND_POLL_MS = 60L

        /** busy=false 后连续静默几轮才判定本轮结束（防抢跑：最后一个 delta 可能还没落）。 */
        private const val SEND_IDLE_DONE_POLLS = 3

        /** 单条发送流的最长寿命（10 分钟硬顶，防生成协程卡死拖住连接）。 */
        private const val SEND_STREAM_MAX_MS = 600_000L

        private const val HEARTBEAT_MS = 15000L

        private const val DEFAULT_TITLE = "ZorvAI"

        /** 动态 UI 回发模型的前缀（对齐 ChatScreen.UI_REPLY_PREFIX）。 */
        private const val UI_REPLY_PREFIX = "ui_reply:"

        /** URL 路径 → assets 相对路径。 */
        private val STATIC = mapOf(
            "/" to "web-chat/index.html",
            "/index.html" to "web-chat/index.html",
            "/style.css" to "web-chat/style.css",
            "/chat.js" to "web-chat/chat.js",
            "/renderers.js" to "web-chat/renderers.js",
        )

        /** 本机局域网 IPv4。 */
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