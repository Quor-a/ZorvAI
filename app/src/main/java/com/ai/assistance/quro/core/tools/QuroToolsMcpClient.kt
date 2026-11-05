package com.ai.assistance.quro.core.tools
import com.ai.assistance.quro.R
import com.ai.assistance.quro.util.qstr

import android.content.Context
import com.ai.assistance.quro.core.mcp.QuroMcpClient
import com.ai.assistance.quro.core.mcp.QuroMcpClientPrefs
import org.json.JSONObject

/**
 * MCP 客户端工具集（#402）：让 AI 调用外部 MCP 服务器暴露的工具。
 * - mcp_servers    ：列出已配置的外部服务器
 * - mcp_list_tools ：查询某服务器暴露的工具清单
 * - mcp_call       ：调用某服务器的某个工具（AI 使用 MCP 的核心入口）
 */

/** 列出已配置的外部 MCP 服务器。 */
class McpServersTool : QuroTool {
    override val name = "mcp_servers"
    override val description = qstr(R.string.qk_03685)
    override val parametersJson = """{"type":"object","properties":{}}"""
    override fun run(context: Context, arguments: String): String {
        val all = QuroMcpClientPrefs.load(context)
        val list = all.filter { it.enabled }
        if (list.isEmpty()) {
            return if (all.isEmpty()) {
                "尚未配置任何外部 MCP 服务器。请在「设置 → MCP 服务」的「MCP 客户端」中添加服务器地址。"
            } else "已配置 ${all.size} 台 MCP 服务器，但全部处于停用状态；请到「设置 → MCP 服务」启用。"
        }
        return list.joinToString("\n") { "· ${it.alias} → ${it.url}" }
    }
}

/**
 * MCP 能力查询工具：把客户端里「已实现但没有任何入口」的 capabilities / resources /
 * prompts / completion / ping / cancel 方法统一暴露给 AI 与设置页的「能力」测试。
 *
 * 本仓客户端的 capabilities / resources / prompts 等方法齐全却全仓零调用点；
 * 此工具让它们全部可达，且不给主链增加常驻工具负担（不在 ALWAYS_ON）。
 */
class McpCapabilitiesTool : QuroTool {
    override val name = "mcp_capabilities"
    override val description =
        "查询外部 MCP 服务器的能力：initialize 能力声明、resources 资源清单、prompts 提示词清单、" +
            "ping 连通性；也可读取单个资源 / 取提示词 / 补全 / 取消请求。" +
            "参数 {\"server\":\"别名\",\"action\":\"capabilities|resources|prompts|ping|read|prompt|complete|cancel\"}。"
    override val parametersJson = """{
        "type":"object",
        "properties":{
            "server":{"type":"string","description":"已启用的 MCP 服务器别名或地址"},
            "action":{"type":"string","description":"capabilities/resources/prompts/ping/read/prompt/complete/cancel"},
            "uri":{"type":"string","description":"action=read 时的资源 URI"},
            "prompt_name":{"type":"string","description":"action=prompt 时的提示词名"},
            "arguments":{"type":"object","description":"action=prompt / complete 时的参数对象"},
            "ref":{"type":"object","description":"action=complete 时的引用对象"},
            "request_id":{"type":"string","description":"action=cancel 时待取消的请求 id"}
        },
        "required":["server","action"]
    }"""

    override fun run(context: Context, arguments: String): String {
        val jo = runCatching { JSONObject(arguments) }.getOrElse { return "参数不是合法 JSON" }
        val ref = jo.optString("server", "").trim()
        val action = jo.optString("action", "capabilities").trim().lowercase()
        if (ref.isEmpty()) return "缺少 server 参数"
        val cfg = QuroMcpClientPrefs.find(context, ref)
            ?: return "未找到已启用的 MCP 服务器: $ref（先用 mcp_servers 查看，或在设置里启用）"
        return runCatching {
            when (action) {
                "ping" -> "ping: ${if (QuroMcpClient.ping(cfg)) "ok" else "无响应"}"
                "capabilities" -> "capabilities: ${QuroMcpClient.getServerCapabilities(cfg)}"
                "resources" -> {
                    val r = QuroMcpClient.listResources(cfg)
                    if (r.isEmpty()) "该服务器未提供 resources。" else r.joinToString("\n") { "· $it" }
                }
                "prompts" -> {
                    val p = QuroMcpClient.listPrompts(cfg)
                    if (p.isEmpty()) "该服务器未提供 prompts。" else p.joinToString("\n") { "· $it" }
                }
                "read" -> {
                    val uri = jo.optString("uri", "").trim()
                    if (uri.isEmpty()) "action=read 需要 uri 参数" else QuroMcpClient.readResource(cfg, uri)
                }
                "prompt" -> {
                    val n = jo.optString("prompt_name", "").trim()
                    if (n.isEmpty()) "action=prompt 需要 prompt_name 参数"
                    else QuroMcpClient.getPrompt(cfg, n, jo.optJSONObject("arguments") ?: JSONObject())
                }
                "complete" -> {
                    val r = jo.optJSONObject("ref")
                    if (r == null) "action=complete 需要 ref 对象"
                    else QuroMcpClient.complete(cfg, r, jo.optJSONObject("arguments") ?: JSONObject())
                }
                "cancel" -> {
                    val id = jo.optString("request_id", "").trim()
                    if (id.isEmpty()) "action=cancel 需要 request_id 参数"
                    else { QuroMcpClient.cancelRequest(cfg, id); "已发送 notifications/cancelled" }
                }
                else -> "未知 action：$action（可用：capabilities/resources/prompts/ping/read/prompt/complete/cancel）"
            }
        }.getOrElse { "MCP $action 失败: ${it.message}" }
    }
}

/** 列出某外部 MCP 服务器暴露的工具。 */
class McpListToolsTool : QuroTool {
    override val name = "mcp_list_tools"
    override val description = "查询指定外部 MCP 服务器暴露的工具清单（名称+说明），便于选择调用。参数 {\"server\":\"别名或地址\"}。"
    override val parametersJson = """{
        "type":"object",
        "properties":{"server":{"type":"string","description":"已配置的服务器别名或地址"}},
        "required":["server"]
    }"""
    override fun run(context: Context, arguments: String): String {
        val ref = runCatching { JSONObject(arguments) }.getOrElse { return "参数不是合法 JSON" }
            .optString("server", "").trim()
        if (ref.isEmpty()) return "缺少 server 参数"
        val cfg = QuroMcpClientPrefs.find(context, ref) ?: return "未找到 MCP 服务器: $ref"
        return try {
            val tools = QuroMcpClient.listTools(cfg)
            if (tools.isEmpty()) return "该服务器未暴露任何工具（或连接失败）。"
            tools.joinToString("\n\n") { "▸ ${it.name}\n  ${it.description}" }
        } catch (e: Exception) {
            "查询工具清单失败: ${e.message}"
        }
    }
}

/** 调用外部 MCP 服务器的某个工具（AI 使用 MCP 的核心入口）。 */
class McpCallTool : QuroTool {
    override val name = "mcp_call"
    override val description = qstr(R.string.qk_03647) +
            "参数 {\"server\":\"别名或地址\",\"tool\":\"工具名\",\"arguments\":{...}}。" +
            "先用 mcp_servers / mcp_list_tools 了解可用服务器与工具。"
    override val parametersJson = """{
        "type":"object",
        "properties":{
            "server":{"type":"string","description":"已配置的外部 MCP 服务器别名或地址"},
            "tool":{"type":"string","description":"要调用的工具名称（来自 mcp_list_tools）"},
            "arguments":{"type":"object","description":"传给该工具的参数对象"}
        },
        "required":["server","tool"]
    }"""
    override fun run(context: Context, arguments: String): String {
        val jo = runCatching { JSONObject(arguments) }.getOrElse { return "参数不是合法 JSON" }
        val ref = jo.optString("server", "").trim()
        val toolName = jo.optString("tool", "").trim()
        if (ref.isEmpty() || toolName.isEmpty()) return "缺少 server 或 tool 参数"
        val argsObj = jo.optJSONObject("arguments") ?: JSONObject()
        val cfg = QuroMcpClientPrefs.find(context, ref)
            ?: return "未找到 MCP 服务器: $ref（先用 mcp_servers 查看已配置服务器，或在设置中添加）"
        return try {
            QuroMcpClient.callTool(cfg, toolName, argsObj)
        } catch (e: Exception) {
            "调用外部 MCP 工具失败: ${e.message}"
        }
    }
}