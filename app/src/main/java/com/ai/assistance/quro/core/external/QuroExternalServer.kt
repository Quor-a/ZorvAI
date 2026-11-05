package com.ai.assistance.quro.core.external

import android.content.Context
import android.util.Log
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException

/**
 * 外部连接 Server（原创）：把本应用作为 **OpenAI 兼容模型服务** 暴露给外部工具。
 *
 * 与 MCP 是两回事，必须分开两个服务：
 * - MCP（[com.ai.assistance.quro.core.mcp.QuroMcpHttpServer]）：暴露**工具**给本机客户端，绑 127.0.0.1；
 * - 外部连接（本类）：暴露**对话补全**（/v1/models、/v1/chat/completions）给外部工具
 *   （桌面端 ChatBox / 脚本 / 自动化），绑 **0.0.0.0**（局域网可达，同一 Wi-Fi 下的电脑直连），
 *   Bearer 密钥校验走 [QuroExternalAccessPrefs]，未开启或无有效密钥一律 401。
 *
 * ## 模型列表与路由（「怎么拉已经保存的模型」）
 * - `GET /v1/models`：返回**当前活跃配置的模型 + 全部已保存预设（QuroSavedProfile）的模型**，
 *   按 model id 去重——外部客户端下拉里看到的就是应用里真实保存过的那些模型；
 * - `POST /v1/chat/completions` 的 `model` 参数按 **model 名或预设名** 匹配已保存预设，
 *   命中即用该预设自己的 baseUrl / apiKey / temperature / maxTokens 上游转发；
 *   未命中回落当前活跃配置。外部调用哪个模型，就走哪个模型自己的接入点。
 *
 * ## 流式修正（此前致命 bug）
 * [com.ai.assistance.quro.core.network.QuroLlmClient] 的 `onToken` / `onThinking`
 * 回传的都是「截至当前的**完整累计文本**」——此前外部 SSE 把它当**增量**原样转发，
 * 外部客户端收到的内容会重复膨胀成 `A / AB / ABC…`，完全不可用。
 * 现按长度差分出真增量再下发；思考增量走 OpenAI 兼容的 `delta.reasoning_content`，
 * 外部客户端（ChatBox / NextChat 等）能直接显示思考过程。
 *
 * ## 工具与思考透传（外部客户端也能看到「AI 在干什么」）
 * - **tools**：外部请求若自带 `tools`（OpenAI function calling 定义），本服务把定义**原样转发**给上游，
 *   上游返回的 `tool_calls` 再转成标准 OpenAI `tool_calls` 结构回给外部客户端——
 *   外部客户端自己执行工具后回传 `role=tool` 消息，本服务继续下一轮，闭环成立。
 * - **思考**：上游的 `reasoning_content` / `reasoning` / `thinking` 三种字段名都识别，
 *   流式走 `delta.reasoning_content`，非流式走 `message.reasoning_content`。
 *
 * ## HTTP 解析（本轮修的"根本不能用"根因）
 * ① **body 按字节读**：`Content-Length` 是**字节数**，此前用 [BufferedReader] 按**字符**读，
 *    中文 UTF-8 请求体被截断 → JSON 解析失败 → 400。改为直接读原始字节流再 UTF-8 解码。
 * ② **支持 chunked**：部分客户端用 `Transfer-Encoding: chunked` 发 body，此前完全不支持 → body 为空 → 400。
 * ③ **路径剥 query**：`GET /v1/models?limit=10` 此前匹配不上 → 404。
 * ④ **keep-alive**：响应头带 `Connection: keep-alive` 并正确处理 `Connection: close`，
 *    避免客户端复用连接时读到脏数据。
 */
class QuroExternalServer(private val appContext: Context) {

    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null

    @Volatile var port: Int = 0
        private set
    @Volatile var running: Boolean = false
        private set

    /** 启动服务：绑定 0.0.0.0（局域网可达），端口随机分配并持久化。返回监听端口。 */
    fun start(): Int {
        if (running) return port
        val ss = openServerSocket()
        port = ss.localPort
        QuroExternalAccessPrefs.publishPort(appContext, port)
        serverSocket = ss
        running = true
        runningFlow.value = true
        acceptThread = Thread({ acceptLoop(ss) }, "QuroExternalServer").also { it.isDaemon = true; it.start() }
        Log.i(TAG, "外部连接服务已启动，端口 $port（0.0.0.0，局域网可达）")
        return port
    }

    /**
     * 服务运行状态的**可观察**版本（实例委托到 companion 的进程级流）。
     *
     * 此前 UI 只能拿「端口是否为 0」判断运行状态 —— 端口改为永久保留后这个信号消失了，
     * 必须有独立的状态流给 UI 用。
     */
    fun runningFlow(): kotlinx.coroutines.flow.StateFlow<Boolean> = Companion.runningFlow

    /**
     * 打开监听 socket。
     *
     * 🔴 **端口必须固定**（外部客户端保存的 Base URL 不能随重启漂移）：
     * 选择顺序：① 用户改过的自定义端口 → ② 上次成功监听的端口 → ③ 固定默认端口
     * [DEFAULT_PORT] → ④ 随机（前三者全被占时的兜底）。
     *
     * 外部调用走云端固定地址模式，Base URL 天然不变；
     * 本地服务的等价做法就是端口从一而终——首次启动就锁 [DEFAULT_PORT]，
     * 外部客户端（ChatBox / 脚本）第一次配置后永远不用再改。
     */
    private fun openServerSocket(): ServerSocket {
        val candidates = buildList {
            QuroExternalAccessPrefs.getCustomPort(appContext)
                .takeIf { it in 1024..65535 }?.let { add(it) }
            QuroExternalAccessPrefs.getPort(appContext)
                .takeIf { it in 1024..65535 }?.let { add(it) }
            add(DEFAULT_PORT)
        }.distinct()
        for (p in candidates) {
            runCatching {
                ServerSocket(p, 64, java.net.InetAddress.getByName("0.0.0.0"))
            }.getOrNull()?.let {
                Log.i(TAG, "外部连接服务监听端口 $p（复用既定端口）")
                return it
            }
        }
        Log.w(TAG, "既定端口 ${candidates.joinToString("/")} 全部被占用，改用随机端口（建议在设置里更换自定义端口）")
        return ServerSocket(0, 64, java.net.InetAddress.getByName("0.0.0.0"))
    }

    fun stop() {
        running = false
        try { serverSocket?.close() } catch (_: Throwable) {}
        serverSocket = null
        acceptThread = null
        // 🔴 端口**不清零**（此前 publishPort(appContext, 0) 是「Base URL 一直在变」的直接根因）：
        //   清零后下次 start() 读到 0 → 又随机分配 → 外部客户端里保存的 Base URL 每次重启都失效。
        //   外部调用走云端固定地址，Base URL 永不变：
        //   本地服务的等价做法就是「端口一旦分配就永久保留」，停止只是不再监听，
        //   下次启动优先抢回同一个端口。运行与否由 [runningFlow] 表达，UI 不再拿端口==0 当「未运行」。
        runningFlow.value = false
        Log.i(TAG, "外部连接服务已停止（端口 $port 保留，下次启动优先复用）")
    }

    private fun acceptLoop(ss: ServerSocket) {
        while (running && !ss.isClosed) {
            val sock = try { ss.accept() } catch (e: SocketException) { break } catch (e: Throwable) { continue }
            Thread({ handle(sock) }, "QuroExtClient").apply { isDaemon = true }.start()
        }
    }

    private fun handle(sock: Socket) {
        try {
            sock.soTimeout = 120_000   // 长生成（外部流式调用可能持续数分钟）
            // 🔴 必须用**字节级**读取器，不能用 BufferedReader。
            // BufferedReader 会一次性预读 8KB，把 body 一起吞进它自己的缓冲区；
            // 之后再从 socket 原始流读 body 时早已是空 → 永远 400 "messages is required"。
            // 这正是此前「外部根本用不了」最致命的一条。
            val reader = HttpByteReader(sock.getInputStream())
            val requestLine = reader.readLine() ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) { send(sock, 400, "text/plain", "Bad Request"); return }
            val httpMethod = parts[0]
            // ① 路径剥掉 query string：/v1/models?limit=10 → /v1/models
            val path = parts[1].substringBefore('?')
            var contentLength = 0
            var chunked = false
            var authHeader: String? = null
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                val lower = line.lowercase()
                when {
                    lower.startsWith("content-length:") ->
                        contentLength = lower.substringAfter(":").trim().toIntOrNull() ?: 0
                    lower.startsWith("transfer-encoding:") ->
                        chunked = lower.substringAfter(":").contains("chunked", ignoreCase = true)
                    lower.startsWith("authorization:") ->
                        authHeader = line.substringAfter(":").trim()
                    lower.startsWith("x-api-key:") ->
                        // 部分客户端（OpenAI SDK 兼容实现）用 x-api-key 而不是 Authorization
                        if (authHeader.isNullOrBlank()) authHeader = "Bearer " + line.substringAfter(":").trim()
                }
            }
            // ② body 按**字节**读（Content-Length 是字节数，中文 UTF-8 此前被按字符截断）
            val body = when {
                chunked -> readChunked(reader)
                contentLength > 0 -> {
                    val bytes = ByteArray(contentLength)
                    var read = 0
                    while (read < contentLength) {
                        val n = reader.readBytes(bytes, read, contentLength - read)
                        if (n <= 0) break
                        read += n
                    }
                    String(bytes, 0, read, Charsets.UTF_8)
                }
                else -> ""
            }

            when {
                httpMethod == "OPTIONS" -> send(sock, 204, "text/plain", "", mapOf(
                    "Access-Control-Allow-Origin" to "*",
                    "Access-Control-Allow-Methods" to "GET, POST, OPTIONS",
                    "Access-Control-Allow-Headers" to "*",
                    "Access-Control-Expose-Headers" to "*",
                ))
                httpMethod == "GET" && (path == "/v1/models" || path == "/v1/model") -> {
                    if (!authorize(sock, authHeader)) return
                    handleListModels(sock)
                }
                httpMethod == "GET" && path.startsWith("/v1/models/") -> {
                    if (!authorize(sock, authHeader)) return
                    val id = path.removePrefix("/v1/models/").trim()
                    sendJson(sock, 200, JSONObject()
                        .put("id", id).put("object", "model").put("owned_by", "zorv-ai").toString())
                }
                httpMethod == "POST" && (path == "/v1/chat/completions" || path == "/chat/completions") -> {
                    if (!authorize(sock, authHeader)) return
                    handleChatCompletions(sock, body, authHeader)
                }
                httpMethod == "GET" && (path == "/" || path == "/health" || path == "/v1/health") ->
                    sendJson(sock, 200, JSONObject()
                        .put("status", "ok").put("service", "zorv-ai-external")
                        .put("models", exposedModelIds().size).toString())
                else -> sendJson(sock, 404, openAiError("Not found: $httpMethod $path"))
            }
        } catch (e: SocketTimeoutException) {
            Log.w(TAG, "外部连接读取超时：${e.message}")
        } catch (e: Throwable) {
            Log.w(TAG, "外部连接处理异常：${e.message}")
        } finally {
            try { sock.close() } catch (_: Throwable) {}
        }
    }

    /**
     * 字节级 HTTP 读取器。
     *
     * 为什么不用 [BufferedReader]：它会预读 8KB 进自己的缓冲区，body 被吞掉后再也取不回来；
     * 而且 chunk size / Content-Length 都是**字节数**，按字符读中文 body 必然错位。
     * 这里统一在**同一个缓冲**上按字节顺序供给「行」与「块」，保证任何组合都不丢字节。
     */
    private class HttpByteReader(private val input: java.io.InputStream) {
        private val buf = ByteArray(8 * 1024)
        private var pos = 0
        private var len = 0

        /** 缓冲区里还有多少字节未取。 */
        private fun available(): Int = len - pos

        /** 缓冲区空则从 socket 补货；返回新的可用字节数（EOF → 0）。 */
        private fun fill(): Int {
            if (available() > 0) return available()
            pos = 0
            len = input.read(buf)
            return if (len <= 0) 0 else len
        }

        /** 读 n 个字节到 out[off..)，返回实际读到的字节数。 */
        fun readBytes(out: ByteArray, off: Int, n: Int): Int {
            var total = 0
            while (total < n) {
                if (fill() <= 0) break
                val take = minOf(available(), n - total)
                System.arraycopy(buf, pos, out, off + total, take)
                pos += take
                total += take
            }
            return total
        }

        /** 读一行（不含 CRLF）；EOF 且无残留时返回 null。 */
        fun readLine(): String? {
            val sb = StringBuilder()
            while (true) {
                if (fill() <= 0) return if (sb.isEmpty()) null else sb.toString()
                while (pos < len) {
                    val b = buf[pos++].toInt() and 0xFF
                    if (b == 10) {   // '\n'
                        var s = sb.toString()
                        if (s.endsWith("\r")) s = s.dropLast(1)
                        return s
                    }
                    sb.append(b.toChar())
                }
            }
        }
    }

    /** 读取 chunked 请求体：逐块读 size(hex) 行 + 数据字节 + CRLF，直到 size=0。 */
    private fun readChunked(reader: HttpByteReader): String {
        val out = java.io.ByteArrayOutputStream()
        while (true) {
            val sizeLine = reader.readLine() ?: break
            val size = sizeLine.trim().substringBefore(';').trim().toIntOrNull(16) ?: break
            if (size == 0) {
                // 读完 trailer（可能还有几行 header）直到空行
                while (true) {
                    val t = reader.readLine() ?: break
                    if (t.isEmpty()) break
                }
                break
            }
            val bytes = ByteArray(size)
            var read = 0
            while (read < size) {
                val n = reader.readBytes(bytes, read, size - read)
                if (n <= 0) break
                read += n
            }
            out.write(bytes, 0, read)
            reader.readBytes(ByteArray(2), 0, 2)   // 吃掉块尾 CRLF
        }
        return String(out.toByteArray(), Charsets.UTF_8)
    }

    /** Bearer 密钥校验：总开关关闭或密钥无效 → 401。 */
    private fun authorize(sock: Socket, authHeader: String?): Boolean {
        if (!QuroExternalAccessPrefs.isEnabled(appContext)) {
            sendJson(sock, 401, openAiError("外部连接未开启，请在 zorv AI 设置 → 模型服务 → 外部连接 中开启"))
            return false
        }
        val key = QuroExternalAccessPrefs.authenticate(appContext, authHeader)
        if (key == null) {
            sendJson(sock, 401, openAiError("Invalid API key"))
            return false
        }
        return true
    }

    // ── 模型列表：活跃配置 + 全部已保存预设，按 model id 去重 ──

    /**
     * 当前活跃配置 + 全部已保存预设的 model id（去重，有序）。
     *
     * 用户在外部连接页勾选了要暴露的模型时，只返回勾中的那些；
     * 勾选集合里若全是已删除的模型，则回落全部，避免外部客户端拿到空列表。
     */
    private fun exposedModelIds(): List<String> {
        val ids = linkedSetOf<String>()
        runCatching {
            QuroExternalAccessPrefs.activeConfig(appContext).model.takeIf { it.isNotBlank() }?.let { ids.add(it) }
            QuroExternalAccessPrefs.savedProfiles(appContext).forEach { p ->
                p.model.takeIf { it.isNotBlank() }?.let { ids.add(it) }
            }
        }
        if (ids.isEmpty()) { ids.add("zorv-ai"); return ids.toList() }
        val picked = QuroExternalAccessPrefs.exposedModels(appContext)
        if (picked.isEmpty()) return ids.toList()
        val filtered = ids.filter { it in picked }
        return filtered.ifEmpty { ids.toList() }
    }

    private fun handleListModels(sock: Socket) {
        val data = JSONArray()
        exposedModelIds().forEach { id ->
            data.put(JSONObject().put("id", id).put("object", "model").put("owned_by", "zorv-ai"))
        }
        sendJson(sock, 200, JSONObject().put("object", "list").put("data", data).toString())
    }

    /** 请求的 model → 命中已保存预设（按 model 名或预设名）用该预设接入点；否则回落活跃配置。 */
    private fun resolveModel(requested: String): QuroExternalAccessPrefs.UpstreamConfig {
        if (requested.isNotBlank()) {
            QuroExternalAccessPrefs.savedProfiles(appContext).firstOrNull { p ->
                p.model == requested || p.name == requested
            }?.let { return it }
        }
        return QuroExternalAccessPrefs.activeConfig(appContext).let {
            it.copy(model = requested.ifBlank { it.model })
        }
    }

    // ── /v1/chat/completions ──

    private fun handleChatCompletions(sock: Socket, body: String, authHeader: String?) {
        try {
            val req = JSONObject(body)
            val requestedModel = req.optString("model", "")
            val upstream = resolveModel(requestedModel)
            val temperature = if (req.has("temperature")) req.optDouble("temperature").toFloat() else upstream.temperature
            val maxTokens = req.optInt("max_tokens", req.optInt("max_completion_tokens", upstream.maxTokens))
            val stream = req.optBoolean("stream", false)
            val msgs = mutableListOf<com.ai.assistance.quro.core.QuroChatMessage>()
            val arr = req.optJSONArray("messages") ?: JSONArray()
            // 🔴 先独立预扫描全部 assistant.tool_calls，建立 tool_call_id → function.name 映射。
            //    标准 OpenAI 协议的 role=tool 消息**不带 name**（工具名只在前一条 assistant 的
            //    tool_calls[].function.name 里）；外部路径不经过 QuroConversation.attachToolNames，
            //    Kimi K3 等严格上游要求 tool 消息自带 name，否则 400 —— 这是上一轮
            //    tool_calls 修复遗留的漏洞。独立预扫描可容忍消息顺序异常。
            val toolNameById = HashMap<String, String>()
            for (k in 0 until arr.length()) {
                val mm = arr.optJSONObject(k) ?: continue
                val tcs = mm.optJSONArray("tool_calls") ?: continue
                for (j in 0 until tcs.length()) {
                    val tc = tcs.optJSONObject(j) ?: continue
                    val fn = tc.optJSONObject("function") ?: continue
                    val id = tc.optString("id", "")
                    val name = fn.optString("name", "")
                    if (id.isNotBlank() && name.isNotBlank()) toolNameById[id] = name
                }
            }
            for (i in 0 until arr.length()) {
                val m = arr.optJSONObject(i) ?: continue
                val role = m.optString("role", "user")
                // 🔴 工具调用闭环的**关键修复**（"外部只能思考，调用工具/回复等不了"的根因）：
                //   此前只取 role+content —— 外部客户端第二轮回传的
                //   assistant(tool_calls) 与 role=tool 消息被砍成普通文本，
                //   发给上游的消息序列不合法（assistant 声明了 tool_calls 却没有、
                //   tool 结果没有 tool_call_id）→ 严格上游 400 → 外部永远等不到下一轮。
                //   现按 OpenAI 协议完整还原三类字段。
                when (role) {
                    "assistant" -> {
                        val tcs = m.optJSONArray("tool_calls")
                        val calls = if (tcs == null) null else {
                            val list = mutableListOf<com.ai.assistance.quro.core.QuroToolCall>()
                            for (j in 0 until tcs.length()) {
                                val tc = tcs.optJSONObject(j) ?: continue
                                val fn = tc.optJSONObject("function") ?: continue
                                list += com.ai.assistance.quro.core.QuroToolCall(
                                    id = tc.optString("id", "call_${System.currentTimeMillis()}_$j"),
                                    name = fn.optString("name", ""),
                                    arguments = fn.optString("arguments", "{}"),
                                )
                            }
                            list
                        }
                        msgs += com.ai.assistance.quro.core.QuroChatMessage(
                            role = role,
                            content = parseContent(m.opt("content")),
                            toolCalls = calls?.takeIf { it.isNotEmpty() },
                            // 外部客户端回传思考时用三种字段名，全都认（此前只认一种）
                            reasoning = listOf("reasoning_content", "reasoning", "thinking")
                                .firstNotNullOfOrNull { m.optString(it, "").takeIf { s -> s.isNotBlank() } },
                        )
                    }
                    "tool" -> {
                        val callId = m.optString("tool_call_id", "")
                        msgs += com.ai.assistance.quro.core.QuroChatMessage(
                            role = role,
                            content = parseContent(m.opt("content")),
                            toolCallId = callId.takeIf { it.isNotBlank() },
                            // 客户端显式带了 name 优先；否则按 tool_call_id 反查补全
                            toolName = (m.optString("name", "").takeIf { it.isNotBlank() }
                                ?: toolNameById[callId])?.takeIf { it.isNotBlank() },
                        )
                    }
                    else -> msgs += com.ai.assistance.quro.core.QuroChatMessage(
                        role = role,
                        content = parseContent(m.opt("content")),
                    )
                }
            }
            if (msgs.isEmpty()) {
                sendJson(sock, 400, openAiError("messages is required"))
                return
            }
            // 🔴 灵魂注入（可选，默认关）：外部客户端常把自己当"纯模型代理"用，
            //    不带任何 system 消息。开启后把当前激活人格注入为 system，
            //    让外部调用得到的不是裸模型，而是与 App 内聊天同一个「人」。
            //    · 客户端自带 system → 一律不覆盖（外部对自己的提示词有最终解释权）；
            //    · 只注入身份/角色/Soul/聊天设定/标签，不含长期记忆、技能全文、设备工具清单
            //      （局域网调用方不该拿到用户隐私与设备能力）；
            //    · 与 QuroChatViewModel.buildSystemPrompt 同源 QuroSoulPromptEngine。
            if (QuroExternalAccessPrefs.isInjectPersona(appContext) && msgs.none { it.role == "system" }) {
                val soul = runCatching {
                    val repo = com.ai.assistance.quro.core.QuroPersonaRepository(appContext)
                    val activeId = repo.getActiveId()
                    val p = repo.loadAll().firstOrNull { it.id == activeId }
                    if (p == null) null else {
                        val tags = com.ai.assistance.quro.core.QuroTagRepository(appContext)
                            .resolve(p.tags)
                        com.ai.assistance.quro.core.soul.QuroSoulPromptEngine.build(
                            com.ai.assistance.quro.core.soul.SoulContext(
                                persona = p,
                                tags = tags,
                                memories = emptyList(),
                                autoSaveMemory = false,
                                voiceStyleHint = null,
                            ),
                        )
                    }
                }.getOrNull()
                if (!soul.isNullOrBlank()) {
                    msgs.add(0, com.ai.assistance.quro.core.QuroChatMessage(role = "system", content = soul))
                }
            }
            // 🔴 system 规范化到数组首位：外部客户端可能把它放在中途，
            //    QuroLlmClient.normalizeToolCallMessages 会把「工具调用组之间的插队消息」
            //    当成修复 DeepSeek 400 的垃圾直接剔除 —— 人格/指令就这么无声消失了。
            //    提到首位后既符合协议惯例，又不会被顺序护栏误伤。
            // 判定：存在 system 且**不在首位**才重排（indexOfFirst 返回 0 = 已合规，
            // -1 = 一条 system 都没有，都无需动）。
            if (msgs.indexOfFirst { it.role == "system" } > 0) {
                val systems = msgs.filter { it.role == "system" }
                val rest = msgs.filter { it.role != "system" }
                msgs.clear()
                msgs.addAll(systems)
                msgs.addAll(rest)
            }
            // 外部请求自带的 tools 定义 → 原样转成 QuroToolSpec 下发给上游，
            // 让外部客户端的 function calling 真正闭环（此前完全丢弃 → 外部永远看不到工具调用）。
            val tools = parseTools(req.optJSONArray("tools"))
            val promptTokens = msgs.sumOf { it.content.length / 3 }
            val t0 = System.currentTimeMillis()
            val model = upstream.model.ifBlank { requestedModel.ifBlank { "zorv-ai" } }
            val client = com.ai.assistance.quro.core.network.QuroLlmClient()
            // 调用方密钥 id：用于「最近使用」里显示是哪把密钥发起的（此前固定写 "external"，全部显示成"已吊销密钥"）
            val keyId = QuroExternalAccessPrefs.authenticate(appContext, authHeader)?.id ?: "external"

            val result = runBlocking {
                if (stream) {
                    // SSE：onToken/onThinking 都是**累计文本**，按长度差分出真增量再下发
                    //
                    // 🔴 长连接稳定性（"外部连接能用但不稳定"的根因之二）：
                    //   读超时会让**流式生成中途断流**。模型在长思考 / 长生成时可能
                    //   几十秒不吐一个 token，此时 socket 的 soTimeout 一到就抛
                    //   SocketTimeoutException → 外部客户端看到"生成到一半断了"。
                    //   所以：进入 SSE 模式后把 soTimeout 归零（= 无限等待），
                    //   由客户端断开（SocketException）来结束，而不是被本地超时打断。
                    runCatching { sock.soTimeout = 0 }
                    beginSse(sock)
                    var prevContent = 0
                    var prevReason = 0
                    val r = client.chat(
                        baseUrl = upstream.baseUrl, apiKey = upstream.apiKey, model = model,
                        messages = msgs, temperature = temperature, maxTokens = maxTokens,
                        tools = tools,
                        // 🔴 按命中的预设透传厂商：client.chat 默认 OPENAI，会让
                        //    QuroReasoningControl / ReasoningEcho 按错误厂商决定
                        //    思考字段与回传策略（o 系 / GPT-5 尤其敏感）。
                        provider = upstream.provider,
                        stream = true,
                        onToken = { acc ->
                            if (acc.length > prevContent) {
                                val delta = acc.substring(prevContent)
                                prevContent = acc.length
                                sendSseChunk(sock, model, content = delta, reasoning = null)
                            }
                        },
                        onThinking = { acc ->
                            if (acc.length > prevReason) {
                                val delta = acc.substring(prevReason)
                                prevReason = acc.length
                                sendSseChunk(sock, model, content = null, reasoning = delta)
                            }
                        },
                    )
                    // 🔴 流式工具调用的**关键修复**（"外部调用不了工具"的第二根因）：
                    //   流式结果若是 ToolCalls，此前只发 finish_reason=stop + [DONE] ——
                    //   tool_calls 整个被丢弃，外部客户端永远收不到「该执行工具了」。
                    //   正确做法：按 OpenAI 流式协议，把每个工具调用拆成 delta 帧
                    //   （首帧带 id+name，后续帧拼 arguments），finish_reason 用 "tool_calls"。
                    if (r is com.ai.assistance.quro.core.QuroLlmResult.ToolCalls) {
                        runCatching {
                            val out = sock.getOutputStream()
                            r.calls.forEachIndexed { idx, c ->
                                val callId = c.id.ifBlank { "call_${System.currentTimeMillis()}_$idx" }
                                // 首帧：id + name + arguments 首段（多数上游一次性给全，这里整段发一帧即可被标准客户端解析）
                                val first = JSONObject()
                                    .put("id", "chatcmpl-${System.currentTimeMillis()}")
                                    .put("object", "chat.completion.chunk")
                                    .put("model", model)
                                    .put("choices", JSONArray().put(
                                        JSONObject().put("index", 0)
                                            .put("delta", JSONObject().put("tool_calls", JSONArray().put(
                                                JSONObject()
                                                    .put("index", idx)
                                                    .put("id", callId)
                                                    .put("type", "function")
                                                    .put("function", JSONObject()
                                                        .put("name", c.name)
                                                        .put("arguments", c.arguments.ifBlank { "{}" })))))
                                            .put("finish_reason", JSONObject.NULL)))
                                out.write("data: $first\n\n".toByteArray(Charsets.UTF_8))
                            }
                            out.flush()
                        }
                        // 收尾：finish_reason = tool_calls
                        runCatching {
                            val final = JSONObject()
                                .put("id", "chatcmpl-${System.currentTimeMillis()}")
                                .put("object", "chat.completion.chunk")
                                .put("model", model)
                                .put("choices", JSONArray().put(
                                    JSONObject().put("index", 0)
                                        .put("delta", JSONObject())
                                        .put("finish_reason", "tool_calls")))
                            sock.getOutputStream().write("data: $final\n\n".toByteArray(Charsets.UTF_8))
                            sock.getOutputStream().write("data: [DONE]\n\n".toByteArray(Charsets.UTF_8))
                            sock.getOutputStream().flush()
                        }
                        recordUsage(keyId, model, promptTokens, 0, t0)
                        // 流式 tool_calls 已完整下发；直接结束（非流式分发走 `if (stream) return`）。
                        null
                    } else {
                        // 流式文本收尾：finish_reason 帧后跟 [DONE]
                        runCatching {
                            val final = JSONObject()
                                .put("id", "chatcmpl-${System.currentTimeMillis()}")
                                .put("object", "chat.completion.chunk")
                                .put("model", model)
                                .put("choices", JSONArray().put(
                                    JSONObject().put("index", 0)
                                        .put("delta", JSONObject())
                                        .put("finish_reason", "stop")))
                            sock.getOutputStream().write("data: $final\n\n".toByteArray(Charsets.UTF_8))
                            sock.getOutputStream().write("data: [DONE]\n\n".toByteArray(Charsets.UTF_8))
                            sock.getOutputStream().flush()
                        }
                        val ct = prevContent / 3
                        recordUsage(keyId, model, promptTokens, ct, t0)
                        r
                    }
                } else {
                    client.chat(
                        baseUrl = upstream.baseUrl, apiKey = upstream.apiKey, model = model,
                        messages = msgs, temperature = temperature, maxTokens = maxTokens,
                        tools = tools,
                        provider = upstream.provider,
                    )
                }
            }

            if (stream || result == null) return  // 流式已在上面收尾（result==null = 流式 tool_calls 已下发）

            when (result) {
                is com.ai.assistance.quro.core.QuroLlmResult.Text -> {
                    val ct = result.content.length / 3
                    val message = JSONObject().put("role", "assistant").put("content", result.content)
                    result.reasoning?.takeIf { it.isNotBlank() }?.let { message.put("reasoning_content", it) }
                    sendJson(sock, 200, chatCompletionJson(model, message, "stop", promptTokens, ct))
                    recordUsage(keyId, model, promptTokens, ct, t0)
                }
                is com.ai.assistance.quro.core.QuroLlmResult.ToolCalls -> {
                    // 上游要求工具调用：转成 OpenAI tool_calls 结构透传给外部客户端，
                    // 由外部客户端执行后回传 role=tool 消息继续（标准 OpenAI 协议闭环）。
                    val message = JSONObject().put("role", "assistant")
                    result.content?.takeIf { it.isNotBlank() }?.let { message.put("content", it) }
                    result.reasoning?.takeIf { it.isNotBlank() }?.let { message.put("reasoning_content", it) }
                    val tc = JSONArray()
                    result.calls.forEach { c ->
                        tc.put(JSONObject()
                            .put("id", c.id.ifBlank { "call_${System.currentTimeMillis()}" })
                            .put("type", "function")
                            .put("function", JSONObject()
                                .put("name", c.name)
                                .put("arguments", c.arguments.ifBlank { "{}" })))
                    }
                    message.put("tool_calls", tc)
                    sendJson(sock, 200, chatCompletionJson(model, message, "tool_calls", promptTokens, 0))
                }
                is com.ai.assistance.quro.core.QuroLlmResult.Error -> {
                    sendJson(sock, 502, openAiError("Upstream model error: ${result.message}"))
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "外部连接 chat 异常：${e.message}")
            runCatching { sendJson(sock, 500, openAiError("Internal error: ${e.message}")) }
        }
    }

    /** 外部请求的 OpenAI tools 数组 → QuroToolSpec（name / description / parameters JSON）。 */
    private fun parseTools(arr: JSONArray?): List<com.ai.assistance.quro.core.QuroToolSpec> {
        if (arr == null || arr.length() == 0) return emptyList()
        val out = mutableListOf<com.ai.assistance.quro.core.QuroToolSpec>()
        for (i in 0 until arr.length()) {
            val t = arr.optJSONObject(i) ?: continue
            val fn = t.optJSONObject("function") ?: continue
            val name = fn.optString("name", "").trim()
            if (name.isEmpty()) continue
            val params = fn.optJSONObject("parameters")
            out += com.ai.assistance.quro.core.QuroToolSpec(
                name = name,
                description = fn.optString("description", ""),
                parametersJson = params?.toString() ?: """{"type":"object","properties":{}}""",
            )
        }
        return out
    }

    /** content 兼容字符串与 OpenAI 多段数组（[{type:"text",text:"…"}]）。 */
    private fun parseContent(v: Any?): String = when (v) {
        null -> ""
        is String -> v
        is JSONArray -> buildString {
            for (i in 0 until v.length()) {
                val o = v.optJSONObject(i) ?: continue
                if (o.optString("type", "text") == "text") append(o.optString("text"))
            }
        }
        else -> v.toString()
    }

    private fun chatCompletionJson(model: String, message: JSONObject, finish: String, pt: Int, ct: Int): String =
        JSONObject()
            .put("id", "chatcmpl-${System.currentTimeMillis()}")
            .put("object", "chat.completion")
            .put("created", System.currentTimeMillis() / 1000)
            .put("model", model)
            .put("choices", JSONArray().put(
                JSONObject().put("index", 0).put("message", message).put("finish_reason", finish)))
            .put("usage", JSONObject()
                .put("prompt_tokens", pt).put("completion_tokens", ct).put("total_tokens", pt + ct))
            .toString()

    private fun recordUsage(keyId: String, model: String, pt: Int, ct: Int, t0: Long) {
        runCatching {
            QuroExternalAccessPrefs.recordCall(appContext, QuroExternalAccessPrefs.RecentCall(
                keyId = keyId, at = t0, model = model, promptTokens = pt, completionTokens = ct,
            ))
        }
    }

    // ── HTTP 底层 ──

    private fun beginSse(sock: Socket) {
        val out: OutputStream = sock.getOutputStream()
        out.write(
            "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream; charset=utf-8\r\nCache-Control: no-cache\r\nConnection: close\r\nAccess-Control-Allow-Origin: *\r\n\r\n"
                .toByteArray(Charsets.UTF_8),
        )
        out.flush()
    }

    /** SSE 增量帧：content 与 reasoning_content 二选一（都为空则不发）。 */
    private fun sendSseChunk(sock: Socket, model: String, content: String?, reasoning: String?) {
        if (content.isNullOrEmpty() && reasoning.isNullOrEmpty()) return
        val delta = JSONObject()
        if (!content.isNullOrEmpty()) delta.put("content", content)
        if (!reasoning.isNullOrEmpty()) delta.put("reasoning_content", reasoning)
        val chunk = JSONObject()
            .put("id", "chatcmpl-${System.currentTimeMillis()}")
            .put("object", "chat.completion.chunk")
            .put("model", model)
            .put("choices", JSONArray().put(
                JSONObject().put("index", 0).put("delta", delta)))
            .toString()
        runCatching {
            sock.getOutputStream().write("data: $chunk\n\n".toByteArray(Charsets.UTF_8))
            sock.getOutputStream().flush()
        }
    }

    private fun openAiError(msg: String): String =
        JSONObject().put("error", JSONObject().put("message", msg).put("type", "invalid_request_error")).toString()

    /**
     * 统一 JSON 响应。
     *
     * 🔴 响应一律 `Connection: close`：本服务**每个连接只处理一个请求**，
     * 此前却声明 `keep-alive` —— 客户端复用连接时读到的是被关闭的 socket，
     * 于是表现为"连上了但没有响应"。诚实声明 close 才是正确做法。
     */
    private fun sendJson(sock: Socket, status: Int, json: String) =
        send(sock, status, "application/json", json, mapOf(
            "Access-Control-Allow-Origin" to "*",
            "Access-Control-Expose-Headers" to "*",
        ))

    private fun send(
        sock: Socket,
        status: Int,
        contentType: String,
        body: String,
        extraHeaders: Map<String, String> = emptyMap(),
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
        200 -> "OK"; 204 -> "No Content"; 400 -> "Bad Request"
        401 -> "Unauthorized"; 404 -> "Not Found"; 500 -> "Internal Server Error"
        502 -> "Bad Gateway"; else -> "OK"
    }

    companion object {
        private const val TAG = "QuroExternalServer"

        /** 默认对外端口：首次启动即固定用它，外部客户端保存的 Base URL 从此不再变。 */
        const val DEFAULT_PORT = 8849

        /**
         * 服务运行状态的**可观察**版本（进程级单例，UI 直接 collect）。
         *
         * 此前 UI 只能拿「端口是否为 0」判断运行状态 —— 端口改为永久保留后这个信号消失了，
         * 必须有独立的状态流。服务可能在独立 Service 进程里另起实例，
         * 所以启停时用静态字段同步（同进程内），跨进程场景仍以 prefs 为准。
         */
        private val runningFlow = kotlinx.coroutines.flow.MutableStateFlow(false)
        fun runningFlow(): kotlinx.coroutines.flow.StateFlow<Boolean> = runningFlow

        /** 本机局域网 IPv4（展示用；多网卡取第一个非回回环地址）。 */
        fun lanIpAddress(): String? = runCatching {
            java.net.NetworkInterface.getNetworkInterfaces().asSequence()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.asSequence() }
                .filterIsInstance<java.net.Inet4Address>()
                .firstOrNull { !it.isLoopbackAddress }
                ?.hostAddress
        }.getOrNull()
    }
}
