package com.ai.assistance.quro.core.tools

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
    override val description = "列出当前已连接的外部 MCP 服务器（别名与地址）。调用外部工具前先用本工具查看可用服务器。"
    override val parametersJson = """{"type":"object","properties":{}}"""
    override fun run(context: Context, arguments: String): String {
        val list = QuroMcpClientPrefs.load(context)
        if (list.isEmpty()) {
            return "尚未配置任何外部 MCP 服务器。请在「设置 → MCP 服务」的「MCP 客户端」中添加服务器地址。"
        }
        return list.joinToString("\n") { "· ${it.alias} → ${it.url}" }
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
    override val description = "调用外部 MCP 服务器暴露的某个工具，让 AI 能够使用其它 MCP 服务的能力。" +
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
