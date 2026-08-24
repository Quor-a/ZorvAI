package com.ai.assistance.quro.core.mcp

import ai.aidl.aci.core.AidlAciResponse
import ai.aidl.aci.core.Capability
import android.content.Context
import android.os.Bundle
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * MCP-ACI 桥接器：将外部 MCP 服务器的工具暴露为 ACI 能力，让 ACI 控制方能够调用 MCP 工具。
 *
 * 架构：
 * 1. MCP 工具 → ACI Capability 映射：将每个 MCP 工具转换为 ACI Capability 格式
 * 2. ACI 调用路由：当 ACI 调用 MCP 能力时，路由到 QuroMcpClient 执行
 * 3. 能力发现：将 MCP 服务器的工具暴露给 ACI 能力发现系统
 *
 * 使用方式：
 * - McpAciBridge.init(context) 初始化
 * - McpAciBridge.getMcpCapabilities() 获取所有 MCP 工具的 ACI 能力列表
 * - McpAciBridge.callMcpTool(serverAlias, toolName, arguments) 调用 MCP 工具
 */
object McpAciBridge {
    private const val TAG = "McpAciBridge"
    private const val MCP_PACKAGE = "mcp_bridge" // MCP 桥接的虚拟包名
    
    private var appContext: Context? = null
    
    // MCP 服务器别名 → 工具列表缓存
    private val serverToolsCache = ConcurrentHashMap<String, List<QuroMcpClient.McpExternalTool>>()
    
    // MCP 工具 → ACI 能力映射：key = "serverAlias::toolName"
    private val mcpToolToAciMap = ConcurrentHashMap<String, McpToolAciMapping>()
    
    /**
     * 初始化 MCP-ACI 桥接器
     */
    fun init(context: Context) {
        appContext = context.applicationContext
        Log.i(TAG, "MCP-ACI 桥接器已初始化")
        
        // 加载已配置的 MCP 服务器
        refreshMcpServers()
    }
    
    /**
     * 刷新 MCP 服务器列表，更新工具缓存和 ACI 能力映射
     */
    fun refreshMcpServers() {
        val ctx = appContext ?: return
        
        // 清空旧缓存
        serverToolsCache.clear()
        mcpToolToAciMap.clear()
        
        // 加载所有 MCP 服务器配置
        val servers = QuroMcpClientPrefs.load(ctx)
        Log.i(TAG, "刷新 MCP 服务器：${servers.size} 个")
        
        for (server in servers) {
            try {
                // 获取服务器工具列表
                val tools = QuroMcpClient.listTools(server)
                serverToolsCache[server.alias] = tools
                
                // 将每个 MCP 工具转换为 ACI 能力
                for (tool in tools) {
                    val mapping = McpToolAciMapping(
                        serverAlias = server.alias,
                        serverUrl = server.url,
                        toolName = tool.name,
                        toolDescription = tool.description,
                        toolParametersJson = tool.parametersJson
                    )
                    val key = "${server.alias}::${tool.name}"
                    mcpToolToAciMap[key] = mapping
                    
                    Log.d(TAG, "映射 MCP 工具: ${server.alias}/${tool.name} → ACI 能力")
                }
                
                Log.i(TAG, "服务器 ${server.alias}：${tools.size} 个工具已映射为 ACI 能力")
            } catch (e: Exception) {
                Log.w(TAG, "获取服务器 ${server.alias} 工具失败：${e.message}")
            }
        }
    }
    
    /**
     * 获取所有 MCP 工具的 ACI 能力列表
     * 用于集成到 ACI 能力发现系统
     */
    fun getMcpCapabilities(): List<Capability> {
        val capabilities = mutableListOf<Capability>()
        
        for ((key, mapping) in mcpToolToAciMap) {
            try {
                val capability = createCapabilityFromMcpTool(mapping)
                capabilities.add(capability)
            } catch (e: Exception) {
                Log.w(TAG, "创建 ACI 能力失败：$key → ${e.message}")
            }
        }
        
        Log.i(TAG, "生成 MCP ACI 能力：${capabilities.size} 个")
        return capabilities
    }
    
    /**
     * 获取 MCP 能力提示词（用于 LLM 系统提示）
     */
    fun getMcpCapabilityPrompt(): String {
        if (mcpToolToAciMap.isEmpty()) {
            return "MCP 桥接：无可用 MCP 工具"
        }
        
        val sb = StringBuilder()
        sb.appendLine("MCP 桥接工具（通过 ACI 调用外部 MCP 服务器工具）：")
        
        // 按服务器分组
        val byServer = mcpToolToAciMap.values.groupBy { it.serverAlias }
        for ((serverAlias, tools) in byServer) {
            sb.appendLine("\n服务器: $serverAlias")
            for (tool in tools) {
                sb.appendLine("  - ${tool.toolName}: ${tool.toolDescription}")
            }
        }
        
        return sb.toString()
    }
    
    /**
     * 通过 ACI 调用 MCP 工具
     *
     * @param serverAlias MCP 服务器别名
     * @param toolName MCP 工具名称
     * @param arguments 工具参数
     * @return ACI 调用响应
     */
    fun callMcpTool(serverAlias: String, toolName: String, arguments: JSONObject): AidlAciResponse {
        val key = "$serverAlias::$toolName"
        val mapping = mcpToolToAciMap[key]
        
        if (mapping == null) {
            return AidlAciResponse.error(404, "MCP 工具未找到: $key")
        }
        
        return try {
            // 查找 MCP 服务器配置
            val ctx = appContext ?: return AidlAciResponse.error(500, "上下文未初始化")
            val config = QuroMcpClientPrefs.find(ctx, serverAlias)
                ?: return AidlAciResponse.error(404, "MCP 服务器未找到: $serverAlias")
            
            // 调用 MCP 工具
            val result = QuroMcpClient.callTool(config, toolName, arguments)
            
            // 将结果转换为 ACI 响应格式
            val bundle = Bundle()
            bundle.putString("mcp_result", result)
            bundle.putString("server_alias", serverAlias)
            bundle.putString("tool_name", toolName)
            
            AidlAciResponse.success(bundle)
        } catch (e: Exception) {
            Log.w(TAG, "调用 MCP 工具失败: $key → ${e.message}")
            AidlAciResponse.error(500, "MCP 工具调用失败: ${e.message}")
        }
    }
    
    /**
     * 从 MCP 工具创建 ACI Capability
     */
    private fun createCapabilityFromMcpTool(mapping: McpToolAciMapping): Capability {
        // 构建 ACI 能力 JSON
        val capJson = JSONObject().apply {
            put("id", "mcp_${mapping.toolName}")
            put("description", "MCP 工具: ${mapping.toolDescription} (服务器: ${mapping.serverAlias})")
            put("requireUserConfirm", false)
            
            // 解析 MCP 工具参数作为 ACI 参数
            val params = JSONObject()
            try {
                val mcpParams = JSONObject(mapping.toolParametersJson)
                val properties = mcpParams.optJSONObject("properties")
                if (properties != null) {
                    for (key in properties.keys()) {
                        val paramDef = properties.optJSONObject(key) ?: continue
                        val paramJson = JSONObject().apply {
                            put("type", paramDef.optString("type", "string"))
                            put("description", paramDef.optString("description", ""))
                        }
                        params.put(key, paramJson)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "解析 MCP 工具参数失败: ${mapping.toolName}")
            }
            put("parameters", params)
        }
        
        // 从 JSON 创建 Capability 对象
        val jsonArray = JSONArray().apply { put(capJson) }
        val capabilities = Capability.fromJSONArray(jsonArray)
        
        return if (capabilities.isNotEmpty()) {
            capabilities.first()
        } else {
            // 兜底：从 JSON 手动创建 Capability
            val id = capJson.optString("id", mapping.toolName)
            val description = capJson.optString("description", "MCP 工具: ${mapping.toolDescription}")
            val requireUserConfirm = capJson.optBoolean("requireUserConfirm", false)
            
            // 使用反射或工厂方法创建 Capability
            // 由于 Capability 构造函数不可用，我们返回一个虚拟能力
            // 实际使用中，getMcpCapabilities() 应该返回 fromJSONArray 解析成功的列表
            Log.w(TAG, "无法创建 Capability 对象，使用虚拟能力: $id")
            
            // 返回第一个能力（如果有的话），否则抛出异常
            if (capabilities.isNotEmpty()) {
                capabilities.first()
            } else {
                throw IllegalStateException("无法创建 MCP 工具的 ACI 能力: $id")
            }
        }
    }
    
    /**
     * 检查是否为 MCP 桥接能力
     */
    fun isMcpAciCapability(capabilityId: String): Boolean {
        return capabilityId.startsWith("mcp_")
    }
    
    /**
     * 从 ACI 能力 ID 提取 MCP 工具信息
     */
    fun extractMcpToolFromCapability(capabilityId: String): Pair<String, String>? {
        if (!capabilityId.startsWith("mcp_")) return null
        
        // 格式: mcp_{toolName}
        val toolName = capabilityId.removePrefix("mcp_")
        
        // 查找包含该工具的服务器
        for ((key, mapping) in mcpToolToAciMap) {
            if (mapping.toolName == toolName) {
                return Pair(mapping.serverAlias, toolName)
            }
        }
        
        return null
    }
    
    /**
     * 获取 MCP 工具映射信息
     */
    fun getMcpToolMapping(serverAlias: String, toolName: String): McpToolAciMapping? {
        val key = "$serverAlias::$toolName"
        return mcpToolToAciMap[key]
    }
    
    /**
     * 获取所有 MCP 工具映射
     */
    fun getAllMcpToolMappings(): Map<String, McpToolAciMapping> {
        return mcpToolToAciMap.toMap()
    }
    
    /**
     * MCP 工具到 ACI 能力的映射数据类
     */
    data class McpToolAciMapping(
        val serverAlias: String,
        val serverUrl: String,
        val toolName: String,
        val toolDescription: String,
        val toolParametersJson: String
    )
}