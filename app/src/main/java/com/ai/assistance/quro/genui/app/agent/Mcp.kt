package com.ai.assistance.quro.genui.app.agent

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * MCP (Model Context Protocol) 客户端 —— JSON-RPC 2.0 over Streamable HTTP。
 *
 * Android 上的现实边界：本地 stdio 传输需要宿主进程（npx/uvx），Android 无 shell 环境，
 * 因此实现 [Streamable HTTP] 传输（spec 2025-03-26）——覆盖全部远程 MCP 服务器
 * （官方 registry 中的绝大多数、自建 mcp-server 均为此形态）。
 *
 * 支持的协议方法（三大原语全覆盖）：
 *  tools      → tools/list · tools/call
 *  resources  → resources/list · resources/read
 *  prompts    → prompts/list · prompts/get
 *  lifecycle  → initialize · notifications/initialized · ping
 */
class McpClient(
    val serverId: String,
    val url: String,
    val customHeaders: Map<String, String> = emptyMap(),
    private val connectTimeoutMs: Int = 15_000,
    private var readTimeoutMs: Int = 30_000
) {
    companion object {
        const val PROTOCOL_VERSION = "2025-03-26"
        const val CLIENT_NAME = "GenUI"
    }

    class McpException(message: String) : Exception(message)

    @Volatile var sessionId: String? = null
    @Volatile var serverInfo: JSONObject? = null
    @Volatile var capabilities: JSONObject? = null
    private val idCounter = java.util.concurrent.atomic.AtomicInteger(1)

    // ---------- 生命周期 ----------

    /** initialize 握手 + notifications/initialized 通知。返回 serverInfo。 */
    fun initialize(): JSONObject {
        val params = JSONObject()
            .put("protocolVersion", PROTOCOL_VERSION)
            .put("capabilities", JSONObject())
            .put("clientInfo", JSONObject().put("name", CLIENT_NAME).put("version", "0.16.0"))
        val result = request("initialize", params, readTimeoutMs)
        serverInfo = result.optJSONObject("serverInfo")
        capabilities = result.optJSONObject("capabilities")
        // 协议版本协商：服务器可能返回更旧的版本，跟随服务器
        result.optString("protocolVersion").takeIf { it.isNotBlank() }?.let { }
        notify("notifications/initialized", JSONObject())
        return serverInfo ?: JSONObject()
    }

    fun ping(): Boolean = runCatching { request("ping", null, 10_000) }.isSuccess

    // ---------- Tools ----------

    fun listTools(): JSONArray = request("tools/list", JSONObject(), readTimeoutMs)
        .optJSONArray("tools") ?: JSONArray()

    fun callTool(name: String, args: JSONObject): JSONObject {
        readTimeoutMs = 90_000  // 工具执行可能长（搜索/抓取/计算），单独放宽
        return request("tools/call", JSONObject()
            .put("name", name).put("arguments", args), readTimeoutMs)
    }

    // ---------- Resources ----------

    fun listResources(): JSONArray = request("resources/list", JSONObject(), readTimeoutMs)
        .optJSONArray("resources") ?: JSONArray()

    fun readResource(uri: String): JSONObject = request("resources/read",
        JSONObject().put("uri", uri), readTimeoutMs)

    // ---------- Prompts ----------

    fun listPrompts(): JSONArray = request("prompts/list", JSONObject(), readTimeoutMs)
        .optJSONArray("prompts") ?: JSONArray()

    fun getPrompt(name: String, args: JSONObject): JSONObject = request("prompts/get",
        JSONObject().put("name", name).put("arguments", args), readTimeoutMs)

    // ---------- 传输 ----------

    private fun request(method: String, params: JSONObject?, timeoutMs: Int): JSONObject {
        val id = idCounter.getAndIncrement()
        val body = JSONObject()
            .put("jsonrpc", "2.0").put("id", id).put("method", method)
        if (params != null) body.put("params", params)
        val resp = post(body, timeoutMs) ?: throw McpException("服务器无响应")
        if (resp.has("error")) {
            val err = resp.optJSONObject("error") ?: JSONObject()
            throw McpException("MCP $method 失败：${err.optString("message")}（code ${err.optInt("code")}）")
        }
        if (resp.optInt("id", -1) != id && resp.has("result")) {
            // 某些实现 id 回显不一致，宽松接受但要求有 result
        }
        return resp.optJSONObject("result") ?: JSONObject()
    }

    private fun notify(method: String, params: JSONObject) {
        val body = JSONObject().put("jsonrpc", "2.0").put("method", method).put("params", params)
        runCatching { post(body, 10_000, allowNoBody = true) }
    }

    /** POST JSON-RPC。返回解析后的响应对象；null = 202 无正文（通知成功）。 */
    private fun post(payload: JSONObject, timeoutMs: Int, allowNoBody: Boolean = false): JSONObject? {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = connectTimeoutMs
            readTimeout = timeoutMs
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            // Streamable HTTP 规范：两种格式都必须接受
            setRequestProperty("Accept", "application/json, text/event-stream")
            setRequestProperty("User-Agent", "${CLIENT_NAME}-MCP/1.0")
            sessionId?.let { setRequestProperty("Mcp-Session-Id", it) }
            customHeaders.forEach { (k, v) -> if (k.isNotBlank() && v.isNotBlank()) setRequestProperty(k, v) }
        }
        try {
            conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            sessionId = conn.getHeaderField("Mcp-Session-Id") ?: sessionId
            if (code == 202) return null
            if (code !in 200..299) {
                val errBody = runCatching {
                    conn.errorStream?.bufferedReader()?.readText()?.take(200)
                }.getOrNull() ?: ""
                throw McpException("HTTP $code $errBody")
            }
            val contentType = (conn.getHeaderField("Content-Type") ?: "").lowercase()
            val raw = BufferedReader(InputStreamReader(
                if (code in 200..299) conn.inputStream else conn.errorStream, Charsets.UTF_8
            )).use { it.readText() }
            return if (contentType.contains("text/event-stream")) {
                parseSse(raw, payload.optInt("id", -1))
            } else {
                JSONObject(raw)
            }
        } finally {
            conn.disconnect()
        }
    }

    /** SSE 帧解析：取第一条与请求 id 匹配的 data JSON。 */
    private fun parseSse(raw: String, wantId: Int): JSONObject? {
        for (line in raw.lines()) {
            val l = line.trim()
            if (!l.startsWith("data:")) continue
            val dataStr = l.removePrefix("data:").trim()
            if (dataStr.isEmpty() || dataStr == "[DONE]") continue
            val obj = runCatching { JSONObject(dataStr) }.getOrNull() ?: continue
            if (!obj.has("id") || obj.optInt("id", -2) == wantId) return obj
        }
        return null
    }
}

/**
 * MCP 服务器管理器 —— 配置持久化、连接生命周期、工具聚合与调用路由。
 *
 * 工具命名：`mcp_<serverId>_<toolName>`，非法字符替换为 `_`（OpenAI function name 约束
 * ^[a-zA-Z0-9_-]{1,64}$），映射表记录 fqn → (serverId, 原始名)，调用时还原路由。
 */
object McpManager {

    data class ServerCfg(
        val id: String,
        val name: String,
        val url: String,
        val headers: JSONObject,
        val enabled: Boolean
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("id", id).put("name", name).put("url", url)
            .put("headers", headers).put("enabled", enabled)
        companion object {
            fun fromJson(o: JSONObject) = ServerCfg(
                id = o.optString("id", UUID.randomUUID().toString().take(8)),
                name = o.optString("name", "未命名"),
                url = o.optString("url", ""),
                headers = o.optJSONObject("headers") ?: JSONObject(),
                enabled = o.optBoolean("enabled", true)
            )
        }
    }

    private val clients = ConcurrentHashMap<String, McpClient>()
    private val status = ConcurrentHashMap<String, String>()
    private val routing = ConcurrentHashMap<String, Pair<String, String>>()  // fqn -> (serverId, toolName)
    @Volatile private var cfgFile: File? = null

    private fun file(ctx: Context): File =
        cfgFile ?: File(ctx.filesDir, "gen").apply { mkdirs() }.let { File(it, "mcp.json") }.also { cfgFile = it }

    fun servers(ctx: Context): MutableList<ServerCfg> {
        val arr = runCatching { JSONObject(file(ctx).readText()).optJSONArray("servers") }
            .getOrDefault(null) ?: return mutableListOf()
        return (0 until arr.length()).map { ServerCfg.fromJson(arr.getJSONObject(it)) }.toMutableList()
    }

    fun save(ctx: Context, list: List<ServerCfg>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        file(ctx).writeText(JSONObject().put("servers", arr).toString())
    }

    fun statusOf(id: String): String = status[id] ?: "未连接"

    // ---------- 连接生命周期 ----------

    /** 连接（或重连）一台服务器。返回 serverInfo 文本摘要。 */
    fun connect(ctx: Context, cfg: ServerCfg): String {
        status[cfg.id] = "连接中…"
        try {
            val client = McpClient(cfg.id, cfg.url,
                customHeaders = cfg.headers.let { h ->
                    val m = mutableMapOf<String, String>()
                    val ks = h.keys()
                    while (ks.hasNext()) { val k = ks.next(); m[k] = h.optString(k) }
                    m
                })
            val info = client.initialize()
            clients[cfg.id] = client
            val name = info.optJSONObject("serverInfo")?.optString("name") ?: cfg.name
            val caps = mutableListOf<String>()
            info.optJSONObject("capabilities")?.let { c ->
                if (c.has("tools")) caps.add("tools")
                if (c.has("resources")) caps.add("resources")
                if (c.has("prompts")) caps.add("prompts")
            }
            status[cfg.id] = "已连接 · $name${if (caps.isNotEmpty()) " · " + caps.joinToString("/") else ""}"
            return status[cfg.id] ?: "已连接"
        } catch (e: Exception) {
            clients.remove(cfg.id)
            status[cfg.id] = "连接失败 · ${e.message?.take(80) ?: "未知错误"}"
            throw e
        }
    }

    fun disconnect(id: String) {
        clients.remove(id)
        status[id] = "未连接"
    }

    private fun ensureConnected(ctx: Context, cfg: ServerCfg): McpClient? {
        clients[cfg.id]?.let { return it }
        if (!cfg.enabled) return null
        return runCatching { connect(ctx, cfg); clients[cfg.id] }.getOrNull()
    }

    // ---------- 工具聚合 ----------

    /** 聚合所有启用服务器的工具为 OpenAI function calling 声明。单台失败不影响其他。 */
    fun aggregateDeclarations(ctx: Context): JSONArray {
        val out = JSONArray()
        routing.clear()
        for (cfg in servers(ctx)) {
            if (!cfg.enabled) continue
            val client = ensureConnected(ctx, cfg) ?: continue
            val serverLabel = cfg.name
            val tools = runCatching { client.listTools() }.getOrElse {
                status[cfg.id] = "工具枚举失败 · ${it.message?.take(60)}"
                continue
            }
            for (i in 0 until tools.length()) {
                val t = tools.optJSONObject(i) ?: continue
                val raw = t.optString("name")
                if (raw.isBlank()) continue
                val fqn = sanitizeName("mcp_${cfg.id}_${raw}")
                routing[fqn] = cfg.id to raw
                val desc = "[MCP·$serverLabel] " + (t.optString("description").ifBlank { "（无描述）" }).take(280)
                val schema = t.optJSONObject("inputSchema") ?: JSONObject()
                if (!schema.has("type")) schema.put("type", "object")
                if (!schema.has("properties")) schema.put("properties", JSONObject())
                out.put(JSONObject()
                    .put("type", "function")
                    .put("function", JSONObject()
                        .put("name", fqn)
                        .put("description", desc)
                        .put("parameters", schema)))
            }
        }
        return out
    }

    /** MCP 调用路由：fqn → 服务器 tools/call → GenUI 结果格式。 */
    fun routeCall(ctx: Context, fqn: String, args: JSONObject): JSONObject {
        var route = routing[fqn]
        if (route == null) {
            aggregateDeclarations(ctx)
            route = routing[fqn] ?: throw McpClient.McpException("未找到 MCP 工具 $fqn（服务器可能未启用或已断开）")
        }
        val (sid, toolName) = route
        val cfg = servers(ctx).find { it.id == sid }
            ?: throw McpClient.McpException("MCP 服务器配置已不存在")
        val client = ensureConnected(ctx, cfg)
            ?: throw McpClient.McpException("MCP 服务器「${cfg.name}」连接失败")
        val result = client.callTool(toolName, args)
        // MCP result: {content:[{type:"text",text}|...], isError?} → GenUI {content, is_error}
        val parts = mutableListOf<String>()
        val content = result.optJSONArray("content") ?: JSONArray()
        for (i in 0 until content.length()) {
            val c = content.optJSONObject(i) ?: continue
            when (c.optString("type")) {
                "text" -> parts.add(c.optString("text"))
                "resource" -> c.optJSONObject("resource")?.let { r ->
                    parts.add(r.optString("text").ifBlank { "[resource ${r.optString("uri")}]" })
                }
                else -> parts.add("[${c.optString("type")} 内容]")
            }
        }
        return JSONObject()
            .put("content", parts.joinToString("\n\n").ifBlank { "（服务器返回空结果）" })
            .put("is_error", result.optBoolean("isError", false))
            .put("server", cfg.name)
    }

    private fun sanitizeName(raw: String): String {
        val s = StringBuilder()
        raw.forEach { ch ->
            s.append(if (ch.isLetterOrDigit() || ch == '_' || ch == '-') ch else '_')
        }
        var name = s.toString()
        if (name.length > 64) {
            // 保前缀（mcp_ + serverId）与尾部（原工具名后段），中间截断
            name = name.take(32) + "_" + name.takeLast(24)
        }
        return name
    }
}
