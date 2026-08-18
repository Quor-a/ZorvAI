package com.ai.assistance.quro.core.mcp

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking

/**
 * Zorv AI MCP 客户端：让 AI 调用「外部 MCP 服务器」暴露的工具（MCP 给 AI 使用的能力）。
 *
 * 传输：JSON-RPC 2.0 over HTTP（单 POST + JSON / SSE 流式响应，与 [QuroMcpHttpServer] 同源协议）。
 * 主要 method：tools/list（枚举外部工具）、tools/call（调用外部工具）。
 *
 * v2 较 v1 增强：
 * - 可选 [McpServerConfig.handshake]：开启后先执行 `initialize` 握手（2025-03-26 协议），
 *   并跟踪服务器通过 `Mcp-Session-Id` 响应头下发的会话 ID，后续请求自动携带。
 * - 兼容 `text/event-stream`（SSE）流式响应（Streamable HTTP 传输）。
 * - 提供 [QuroMcpWsClient] 作为 WebSocket 传输互补（config.kind == "ws" 时走 WS）。
 */
object QuroMcpClient {
    private const val TAG = "QuroMcpClient"
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    /** 会话握手状态：url -> Mcp-Session-Id（initialize 后由服务器响应头下发）。 */
    private val sessions = mutableMapOf<String, String?>()
    private val initializedUrls = mutableSetOf<String>()

    /** 已连接的 MCP 服务器配置。 */
    data class McpServerConfig(
        val alias: String,
        val url: String,
        val token: String = "",
        val kind: String = "remote", // "remote"（外部 URL）| "local"（AI 部署到本应用内）| "ws"（WebSocket）
        val toolDefs: String = "",   // 仅 local：AI 定义的工具清单 JSON 字符串
        val handshake: Boolean = false, // 是否执行 initialize 握手（严格 MCP 服务器需要）
    )

    /** 外部服务器暴露的工具（精简描述，供 AI 选择）。 */
    data class McpExternalTool(
        val name: String,
        val description: String,
        val parametersJson: String,
    )

    /** 解析外部工具清单（tools/list）。连接失败或空清单返回 emptyList。 */
    fun listTools(config: McpServerConfig): List<McpExternalTool> {
        ensureInitialized(config)
        val root = if (config.kind == "ws") {
            runBlocking { QuroMcpWsClient.callToolWs(config.url, "tools/list", JSONObject(), config.token) }
        } else {
            rpcRaw(config, "tools/list", JSONObject()).first
        }
        if (root.has("error")) return emptyList()
        val result = root.optJSONObject("result") ?: return emptyList()
        val tools = result.optJSONArray("tools") ?: return emptyList()
        val out = mutableListOf<McpExternalTool>()
        for (i in 0 until tools.length()) {
            val t = tools.optJSONObject(i) ?: continue
            val name = t.optString("name", "")
            if (name.isEmpty()) continue
            val desc = t.optString("description", "")
            val input = t.optJSONObject("inputSchema") ?: t.optJSONObject("parameters") ?: JSONObject()
            out.add(McpExternalTool(name, desc, input.toString()))
        }
        return out
    }

    /** 调用外部工具（tools/call），返回文本内容（截断至 8000 字符）。失败返回可读错误信息。 */
    fun callTool(config: McpServerConfig, toolName: String, arguments: JSONObject): String {
        ensureInitialized(config)
        val params = JSONObject().put("name", toolName).put("arguments", arguments)
        val root = if (config.kind == "ws") {
            runBlocking { QuroMcpWsClient.callToolWs(config.url, "tools/call", params, config.token) }
        } else {
            rpcRaw(config, "tools/call", params).first
        }
        if (root.has("error")) {
            val err = root.optJSONObject("error")
            return "MCP 工具调用失败: ${err?.optString("message", "未知错误") ?: "未知错误"}"
        }
        val result = root.optJSONObject("result") ?: return "MCP 服务器未返回结果"
        // 标准 MCP：result.content = [{type:"text", text:"..."}]
        val content = result.optJSONArray("content")
        if (content != null && content.length() > 0) {
            // 拼接所有 content 片段（文本/资源等），不再只读第一条
            val sb = StringBuilder()
            for (i in 0 until content.length()) {
                val item = content.optJSONObject(i) ?: continue
                val piece = when (item.optString("type", "text")) {
                    "text" -> item.optString("text", "")
                    "resource" -> item.optString("resource", "")
                    else -> item.optString("text", "")
                }
                if (piece.isNotBlank()) {
                    if (sb.isNotEmpty()) sb.append("\n")
                    sb.append(piece)
                }
            }
            if (sb.isNotEmpty()) return truncate(sb.toString())
        }
        // 退路：result 本身可能是直接值或 {result:"..."}
        val direct = result.optString("result", "")
        if (direct.isNotBlank()) return truncate(direct)
        return truncate(result.toString())
    }

    // ── 内部：JSON-RPC 2.0（HTTP / SSE）──

    /** 发起一次 JSON-RPC 调用，返回（根对象, 本次响应的 Mcp-Session-Id）。 */
    private fun rpcRaw(config: McpServerConfig, method: String, params: JSONObject, captureSession: Boolean = false): Pair<JSONObject, String?> {
        val body = JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", System.nanoTime())
            .put("method", method)
            .put("params", params)
            .toString()
        val reqBuilder = Request.Builder().url(config.url.trim())
            .post(body.toRequestBody(jsonMediaType))
            .addHeader("Accept", "application/json, text/event-stream")
        if (config.token.isNotBlank()) reqBuilder.addHeader("Authorization", "Bearer ${config.token}")
        // 握手成功后携带会话 ID（部分服务器要求后续请求带同一 session）
        sessions[config.url]?.let { sid -> if (sid != null) reqBuilder.addHeader("Mcp-Session-Id", sid) }
        reqBuilder.addHeader("MCP-Protocol-Version", "2025-03-26")
        val request = reqBuilder.build()
        return try {
            client.newCall(request).execute().use { resp ->
                val sid = resp.header("Mcp-Session-Id")
                if (captureSession && sid != null) sessions[config.url] = sid
                val raw = resp.body?.string() ?: ""
                if (!resp.isSuccessful) {
                    // OkHttp 对 4xx/5xx 不抛异常，需主动捕获，否则会当成正常响应去 parse
                    val msg = "HTTP ${resp.code} ${resp.message}：${raw.take(400)}"
                    Log.w(TAG, "MCP $method -> $msg")
                    return JSONObject().put("error", JSONObject().put("message", msg)) to sid
                }
                parseBody(raw, resp.header("Content-Type")) to sid
            }
        } catch (e: Exception) {
            Log.w(TAG, "MCP 调用失败 $method: ${e.message}")
            JSONObject().put("error", JSONObject().put("message", "连接失败: ${e.message}")) to null
        }
    }

    /** 解析 JSON-RPC 响应；兼容 SSE（text/event-stream）逐行 data: 形式。 */
    private fun parseBody(raw: String, contentType: String?): JSONObject {
        if (raw.isBlank()) return JSONObject()
        if (contentType?.contains("text/event-stream") == true || raw.contains("data:")) {
            val sb = StringBuilder()
            raw.lineSequence().forEach { line ->
                val t = line.trim()
                if (t.startsWith("data:")) {
                    val json = t.removePrefix("data:").trim()
                    if (json.isNotEmpty() && json != "[DONE]") sb.append(json)
                }
            }
            val merged = sb.toString()
            if (merged.isNotEmpty()) {
                return runCatching { JSONObject(merged) }.getOrDefault(JSONObject())
            }
        }
        return runCatching { JSONObject(raw) }.getOrElse {
            JSONObject().put("error", JSONObject().put("message", "响应不是合法 JSON: ${raw.take(200)}"))
        }
    }

    /** 执行 initialize 握手（2025-03-26 协议），并发送 initialized 通知。 */
    fun initialize(config: McpServerConfig): Boolean {
        val params = JSONObject()
            .put("protocolVersion", "2025-03-26")
            .put("capabilities", JSONObject())
            .put("clientInfo", JSONObject().put("name", "Zorv AI").put("version", "1.0"))
        val (root, _) = rpcRaw(config, "initialize", params, captureSession = true)
        if (root.has("error")) return false
        // 部分服务器要求握手后发送 initialized 通知（best-effort）
        rpcRaw(config, "notifications/initialized", JSONObject())
        return true
    }

    /** 按需执行一次握手（幂等）：仅当 config.handshake=true 且尚未初始化时。 */
    private fun ensureInitialized(config: McpServerConfig) {
        if (!config.handshake) return
        if (config.url in initializedUrls) return
        initialize(config)
        initializedUrls.add(config.url)
    }

    private fun truncate(s: String): String =
        if (s.length > 8000) s.take(8000) + "\n...[结果已截断]" else s
}
