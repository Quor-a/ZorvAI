package com.ai.assistance.quro.core.mcp

import android.content.Context
import com.ai.assistance.quro.core.tools.QuroTool
import org.json.JSONObject

/**
 * MCP-ACI 桥接工具集：让 AI 通过 ACI 调用外部 MCP 服务器的工具。
 * - mcp_aci_list ：列出所有可通过 ACI 调用的 MCP 工具
 * - mcp_aci_call ：通过 ACI 调用 MCP 工具（统一入口）
 */

/** 列出所有可通过 ACI 调用的 MCP 工具。 */
class McpAciListTool : QuroTool {
    override val name = "mcp_aci_list"
    override val description = "列出所有可通过 ACI 调用的 MCP 工具（外部 MCP 服务器暴露的工具）。" +
            "这些工具来自已配置的 MCP 服务器，可以通过 mcp_aci_call 调用。参数为空 {}。"
    override val parametersJson = """{"type":"object","properties":{}}"""
    
    override fun run(context: Context, arguments: String): String {
        val mappings = McpAciBridge.getAllMcpToolMappings()
        
        if (mappings.isEmpty()) {
            return "尚无可用的 MCP 桥接工具。请先在「设置 → MCP 服务」中添加外部 MCP 服务器。"
        }
        
        val sb = StringBuilder()
        sb.appendLine("可通过 ACI 调用的 MCP 工具：")
        sb.appendLine()
        
        // 按服务器分组
        val byServer = mappings.values.groupBy { it.serverAlias }
        for ((serverAlias, tools) in byServer) {
            sb.appendLine("【服务器: $serverAlias】")
            for (tool in tools) {
                sb.appendLine("  - ${tool.toolName}: ${tool.toolDescription}")
                sb.appendLine("    ACI 能力 ID: mcp_${tool.toolName}")
            }
            sb.appendLine()
        }
        
        sb.appendLine("使用 mcp_aci_call 工具调用这些工具，参数格式：")
        sb.appendLine("{\"capability\":\"mcp_{工具名}\",\"args\":{参数}}")
        
        return sb.toString()
    }
}

/** 通过 ACI 调用 MCP 工具。 */
class McpAciCallTool : QuroTool {
    override val name = "mcp_aci_call"
    override val description = "通过 ACI 调用外部 MCP 服务器的工具。这是调用 MCP 工具的统一入口。" +
            "参数 {\"capability\":\"mcp_{工具名}\",\"args\":{...}}。" +
            "先用 mcp_aci_list 查看可用工具。" +
            "示例：{\"capability\":\"mcp_web_search\",\"args\":{\"query\":\"AI 新闻\"}}"
    override val parametersJson = """{
        "type":"object",
        "properties":{
            "capability":{"type":"string","description":"MCP 工具的 ACI 能力 ID（格式：mcp_{工具名}）"},
            "args":{"type":"object","description":"工具参数对象"}
        },
        "required":["capability"]
    }"""
    
    override fun run(context: Context, arguments: String): String {
        val jo = runCatching { JSONObject(arguments) }.getOrElse { return "参数不是合法 JSON" }
        val capability = jo.optString("capability", "").trim()
        val argsObj = jo.optJSONObject("args") ?: JSONObject()
        
        if (capability.isEmpty()) {
            return "缺少 capability 参数（格式：mcp_{工具名}）"
        }
        
        // 检查是否为 MCP 桥接能力
        if (!McpAciBridge.isMcpAciCapability(capability)) {
            return "能力 ID 必须以 'mcp_' 开头（MCP 桥接工具）"
        }
        
        // 通过 ACI 调用 MCP 工具
        return try {
            val response = com.ai.assistance.quro.core.aidlaci.QuroAidlAciManager.getInstance()
                .call("mcp_bridge", capability, android.os.Bundle().apply {
                    // 将 JSONObject 参数转换为 Bundle
                    argsObj.keys().forEach { key ->
                        val value = argsObj.get(key)
                        when (value) {
                            is String -> putString(key, value)
                            is Int -> putInt(key, value)
                            is Long -> putLong(key, value)
                            is Double -> putDouble(key, value)
                            is Boolean -> putBoolean(key, value)
                            else -> putString(key, value?.toString() ?: "")
                        }
                    }
                })
            
            if (response.isSuccess()) {
                val result = response.getResult()
                if (result != null) {
                    // 提取 MCP 调用结果
                    val mcpResult = result.getString("mcp_result")
                    val serverAlias = result.getString("server_alias")
                    val toolName = result.getString("tool_name")
                    
                    if (mcpResult != null) {
                        "✅ MCP 工具调用成功 ($serverAlias/$toolName)\n\n$mcpResult"
                    } else {
                        "✅ MCP 工具调用成功，但无返回数据"
                    }
                } else {
                    "✅ MCP 工具调用成功，但无返回数据"
                }
            } else {
                "❌ MCP 工具调用失败（错误码=${response.getErrorCode()}）：${response.getErrorMessage()}"
            }
        } catch (e: Exception) {
            "❌ 调用 MCP 工具异常：${e.message}"
        }
    }
}

/** MCP-ACI 桥接管理工具。 */
class McpAciBridgeTool : QuroTool {
    override val name = "mcp_aci_bridge"
    override val description = "管理 MCP-ACI 桥接器。支持操作：refresh（刷新 MCP 服务器工具列表）、status（查看桥接状态）。" +
            "参数：{\"action\":\"refresh|status\"}"
    override val parametersJson = """{
        "type":"object",
        "properties":{
            "action":{"type":"string","enum":["refresh","status"],"description":"操作类型"}
        },
        "required":["action"]
    }"""
    
    override fun run(context: Context, arguments: String): String {
        val jo = runCatching { JSONObject(arguments) }.getOrElse { return "参数不是合法 JSON" }
        val action = jo.optString("action", "").trim()
        
        if (action.isEmpty()) {
            return "缺少 action 参数（refresh/status）"
        }
        
        return when (action) {
            "refresh" -> {
                McpAciBridge.refreshMcpServers()
                val mappings = McpAciBridge.getAllMcpToolMappings()
                "✅ MCP-ACI 桥接器已刷新\n\n当前映射的 MCP 工具：${mappings.size} 个\n" +
                    mappings.values.groupBy { it.serverAlias }.map { (server, tools) ->
                        "  $server: ${tools.size} 个工具"
                    }.joinToString("\n")
            }
            "status" -> {
                val mappings = McpAciBridge.getAllMcpToolMappings()
                val servers = QuroMcpClientPrefs.load(context)
                
                buildString {
                    appendLine("📊 MCP-ACI 桥接器状态")
                    appendLine()
                    appendLine("MCP 服务器：${servers.size} 个")
                    servers.forEach { server ->
                        appendLine("  - ${server.alias}: ${server.url}")
                    }
                    appendLine()
                    appendLine("映射的 MCP 工具：${mappings.size} 个")
                    mappings.values.groupBy { it.serverAlias }.forEach { (server, tools) ->
                        appendLine("  【$server】")
                        tools.forEach { tool ->
                            appendLine("    - ${tool.toolName}: ${tool.toolDescription}")
                        }
                    }
                }
            }
            else -> "未知操作: $action（支持 refresh/status）"
        }
    }
}