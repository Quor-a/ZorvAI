package com.ai.assistance.quro.core.mcp

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * QuroAI MCP 客户端：让 AI 调用「外部 MCP 服务器」暴露的工具（MCP 给 AI 使用的能力）。
 *
 * 传输：JSON-RPC 2.0 over HTTP（单 POST + 普通 JSON 响应，与 [QuroMcpHttpServer] 同源协议）。
 * 主要 method：tools/list（枚举外部工具）、tools/call（调用外部工具）。
 *
 * 已知局限（v1）：
 * - 不强制 prior `initialize` 握手。QuroMcpHttpServer 与多数 HTTP 模式 MCP 服务器无需握手即可
 *   响应 tools/list / tools/call；若接入要求会话握手的严格服务器，后续版本再补。
 * - 仅处理 plain JSON 响应；未处理 `text/event-stream`（SSE）流式的 streamable HTTP 传输。
 */
object QuroMcpClient {
    private const val TAG = "QuroMcpClient"
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    /** 已连接的 MCP 服务器配置。 */
    data class McpServerConfig(
        val alias: String,
        val url: String,
        val token: String = "",
        val kind: String = "remote", // "remote"（外部 URL）| "local"（AI 部署到本应用内）
        val toolDefs: String = "",   // 仅 local：AI 定义的工具清单 JSON 字符串
    )

    /** 外部服务器暴露的工具（精简描述，供 AI 选择）。 */
    data class McpExternalTool(
        val name: String,
        val description: String,
        val parametersJson: String,
    )

    /** 解析外部工具清单（tools/list）。连接失败或空清单返回 emptyList。 */
    fun listTools(config: McpServerConfig): List<McpExternalTool> {
        val root = rpcRaw(config, "tools/list", JSONObject())
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
        val params = JSONObject().put("name", toolName).put("arguments", arguments)
        val root = rpcRaw(config, "tools/call", params)
        if (root.has("error")) {
            val err = root.optJSONObject("error")
            return "MCP 工具调用失败: ${err?.optString("message", "未知错误") ?: "未知错误"}"
        }
        val result = root.optJSONObject("result") ?: return "MCP 服务器未返回结果"
        // 标准 MCP：result.content = [{type:"text", text:"..."}]
        val content = result.optJSONArray("content")
        if (content != null && content.length() > 0) {
            val first = content.optJSONObject(0) ?: JSONObject()
            val text = first.optString("text", "")
            if (text.isNotEmpty()) return truncate(text)
        }
        // 退路：result 本身可能是直接值或 {result:"..."}
        val direct = result.optString("result", "")
        if (direct.isNotEmpty()) return truncate(direct)
        return truncate(result.toString())
    }

    // ── 内部：JSON-RPC 2.0 单 POST ──

    /** 发起一次 JSON-RPC 调用，返回解析后的根对象（含 result 或 error）。 */
    private fun rpcRaw(config: McpServerConfig, method: String, params: JSONObject): JSONObject {
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
        reqBuilder.addHeader("MCP-Protocol-Version", "2024-11-05")
        val request = reqBuilder.build()
        return try {
            client.newCall(request).execute().use { resp ->
                val raw = resp.body?.string() ?: ""
                runCatching { JSONObject(raw) }.getOrElse {
                    JSONObject().put("error", JSONObject().put("message", "响应不是合法 JSON: ${raw.take(200)}"))
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "MCP 调用失败 $method: ${e.message}")
            JSONObject().put("error", JSONObject().put("message", "连接失败: ${e.message}"))
        }
    }

    private fun truncate(s: String): String =
        if (s.length > 8000) s.take(8000) + "\n...[结果已截断]" else s
}
