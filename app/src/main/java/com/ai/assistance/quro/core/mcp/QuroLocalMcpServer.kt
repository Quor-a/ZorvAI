package com.ai.assistance.quro.core.mcp

import com.ai.assistance.quro.BuildConfig
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
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
 *
 * ## 互控双通道（同一 server，两个协议面）
 *
 * 同一个 server 同时向 DeepSeek Harness 暴露两个面，供
 * [HarnessBridge][com.ai.assistance.quro.core.tools.HarnessBridge] 组多通道：
 *   · `/mcp`          —— JSON-RPC 2.0（MCP 通道；harness 侧 `@deepseek-ai/dsh-mcp-client`，工具名 `mcp__zorvai__*`）；
 *   · `/tool/<工具名>` —— 纯 HTTP POST（内置插件通道；harness 侧 `@dsh-android/dsh-android-zorvai-tools`，工具名 `zorvai_*`）。
 *
 * 两个面走**同一个** [QuroLocalMcpDispatcher] 与同一份工具定义，语义等价：
 * 一条通道失败换另一条即可。
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
            val input = BufferedInputStream(sock.getInputStream(), 8192)
            val requestLine = readLineBytes(input) ?: return
            if (requestLine.isBlank()) return
            val parts = requestLine.split(" ")
            if (parts.size < 2) { send(sock, 400, "text/plain", "Bad Request"); return }
            val method = parts[0]
            var contentLength = 0
            while (true) {
                val line = readLineBytes(input) ?: break
                if (line.isEmpty()) break
                val lower = line.lowercase()
                if (lower.startsWith("content-length:")) {
                    contentLength = lower.substringAfter(":").trim().toIntOrNull() ?: 0
                }
            }
            // 字节级读 body：content-length 是字节数，中文实参（UTF-8 多字节）按字符读会
            // 少读后空转到 soTimeout——两个通道的实参都大量中文，必须按字节精确读。
            val body = if (contentLength > 0) readBodyBytes(input, contentLength) else ""
            when (method) {
                "POST" -> {
                    // 互控双通道：/tool/<工具名> 进内置插件通道（纯 HTTP 面），其余按 MCP JSON-RPC 处理
                    val target = parts[1].substringBefore('?')
                    if (target.startsWith(TOOL_ROUTE_PREFIX)) dispatchTool(sock, target, body)
                    else dispatchRpc(sock, body)
                }
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

    /**
     * 字节级读一行 HTTP 头（停在 `\n`，剥掉尾部 `\r`）。
     *
     * 为什么不用 BufferedReader/InputStreamReader：它按 UTF-8 把字节解码成字符后，
     * 字符数与 content-length（字节数）在多字节下不一致，body 会被读成半截再空转；
     * [BufferedInputStream] 只缓冲不解码，字节原样可达，头部仍是 ASCII 逐字节安全。
     *
     * @return null = 连接已关（EOF，且未读到任何字节）。
     */
    private fun readLineBytes(input: BufferedInputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == 10) return sb.toString().trimEnd('\r')
            sb.append(b.toChar())
        }
    }

    /** 精确读 content-length 字节（TCP 分段下循环读全），按 UTF-8 解码成字符串。 */
    private fun readBodyBytes(input: BufferedInputStream, contentLength: Int): String {
        val out = ByteArrayOutputStream(contentLength.coerceAtMost(1 shl 20))
        var remaining = contentLength
        val chunk = ByteArray(8192)
        while (remaining > 0) {
            val n = input.read(chunk, 0, minOf(remaining, chunk.size))
            if (n < 0) break
            out.write(chunk, 0, n)
            remaining -= n
        }
        return out.toString(Charsets.UTF_8.name())
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
                // 🔴 MCP 规范要求每条工具带 `inputSchema`（JSON Schema 对象）。
                // 服务端内部模型用的是 `parameters`，这里**必须映射**：
                // harness 侧 @modelcontextprotocol/sdk 会校验字段，缺 inputSchema 直接
                //   SdkError INVALID_RESULT: path ["tools",0,"inputSchema"] expected object, received undefined
                // → connect() 抛错 → 客户端 connectedAt 永远 undefined →
                //   上层 list_mcp_resources 一律报 `server is disconnected`（实测根因，2026-10-26）。
                // `handler_type` / `handler_config` 是服务端路由信息，规范里没有这两个键，不往外吐。
                "tools/list" -> JSONObject().put("tools", JSONArray().apply {
                    tools.forEach { t ->
                        put(
                            JSONObject().apply {
                                put("name", t.optString("name", ""))
                                put("description", t.optString("description", ""))
                                put("inputSchema", mcpInputSchema(t))
                            },
                        )
                    }
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

    /**
     * 内置插件通道（harness ⇢ ZorvAI）：`POST /tool/<工具名>`，body 为模型实参 JSON。
     *
     * 与 [dispatchRpc]（MCP 通道）同源同分发器——同一个 [QuroLocalMcpDispatcher]、同一份
     * 工具定义；区别只在协议形状：这里返回 harness 内置插件
     * `@dsh-android/dsh-android-zorvai-tools` 期望的扁平 `{"ok":boolean,"text":string}`。
     *
     * 工具名走 URL 解码（插件侧 encodeURIComponent）；未找到 / 执行异常一律转成
     * `{"ok":false,"text":...}` 回执（HTTP 200）——execute 侧绝不向引擎抛异常。
     */
    private fun dispatchTool(sock: Socket, target: String, body: String) {
        val rawName = target.removePrefix(TOOL_ROUTE_PREFIX)
        val toolName = runCatching { java.net.URLDecoder.decode(rawName, "UTF-8") }.getOrDefault(rawName)
        val args = runCatching { JSONObject(body) }.getOrElse { JSONObject() }
        val toolDef = tools.firstOrNull { it.optString("name", "") == toolName }
        val payload = when {
            toolName.isBlank() ->
                JSONObject().put("ok", false).put("text", "缺少工具名：POST /tool/<工具名>")
            toolDef == null ->
                JSONObject().put("ok", false).put("text", "ZorvAI 未找到工具: $toolName")
            else -> try {
                val text = QuroLocalMcpDispatcher.dispatch(toolDef, args)
                JSONObject().put("ok", true).put("text", text)
            } catch (t: Throwable) {
                Log.w(TAG, "内置插件通道执行异常 $toolName: ${t.message}")
                JSONObject().put("ok", false)
                    .put("text", "ZorvAI 工具执行失败: ${t.message ?: t.javaClass.simpleName}")
            }
        }
        send(sock, 200, "application/json", payload.toString(), mapOf("Access-Control-Allow-Origin" to "*"))
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

        /** 内置插件通道的路由前缀（harness ⇢ ZorvAI 纯 HTTP 面；与 HarnessBridge.TOOL_PATH 对应）。 */
        private const val TOOL_ROUTE_PREFIX = "/tool/"
    }
}


/**
 * 把服务端内部的工具定义转成 MCP 规范要求的 `inputSchema` 对象。
 *
 * 为什么单独抽成顶层函数：这是**协议契约**，不是实现细节 —— 它值得被单测钉住
 * （见 QuroLocalMcpProtocolTest）。历史上这里直接把内部定义原样吐出，
 * 缺 `inputSchema` 导致 harness 侧 SDK 校验失败、connect() 抛错、
 * `list_mcp_resources` 永远报 `server is disconnected`，排查了三轮。
 *
 * 三种输入都要兜住：
 *  - 正常：`parameters` 是 JSON 对象 → 原样作为 schema；
 *  - 历史/AI 手写：`parameters` 是被编码成**字符串**的 JSON → 解一次；
 *  - 缺失或为空：给 `{"type":"object"}`，绝不返回 undefined（规范要求必须是 object）。
 */
internal fun mcpInputSchema(tool: JSONObject): JSONObject {
    val raw = tool.opt("parameters")
    val parsed: JSONObject? = when (raw) {
        is JSONObject -> raw
        is String -> runCatching { JSONObject(raw) }.getOrNull()
        else -> null
    }
    return if (parsed == null || parsed.length() == 0) {
        JSONObject().put("type", "object")
    } else {
        // 复制一份：调用方可能继续持有原定义，别把补出来的键写回共享状态。
        JSONObject(parsed.toString())
    }
}
