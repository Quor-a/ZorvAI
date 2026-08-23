package com.ai.assistance.quro.core.tools

import android.content.Context
import org.json.JSONObject

/**
 * 工具发现工具
 * 
 * 让AI能主动查询工具能力目录：
 * 1. 查询所有工具分类
 * 2. 根据意图匹配工具
 * 3. 获取工具使用示例
 * 4. 获取工具最佳实践
 * 
 * 解决AI不会主动使用工具的问题
 */
class ToolDiscoveryTool : QuroTool {
    override val name = "tool_discovery"
    override val description = "工具发现：查询可用工具、根据意图匹配工具、获取工具使用指南"
    override val parametersJson = """{
        "type": "object",
        "properties": {
            "action": {
                "type": "string",
                "description": "操作类型：list_categories/list_tools/match_intent/get_tool_info/get_best_practices/get_directory_summary",
                "enum": ["list_categories", "list_tools", "match_intent", "get_tool_info", "get_best_practices", "get_directory_summary"]
            },
            "category": {
                "type": "string",
                "description": "工具分类（list_tools时使用）：BASIC/SYSTEM_CONTROL/FILE_OPERATION/TERMINAL_LINUX/NETWORK_WEB/MEDIA/UI_CARDS/KNOWLEDGE_MEMORY/WORKSPACE/ACCESSIBILITY/APP_MANAGEMENT/COMMUNICATION/AI_CAPABILITIES/SECURITY"
            },
            "intent": {
                "type": "string",
                "description": "用户意图（match_intent时使用）：描述用户想做什么"
            },
            "tool_name": {
                "type": "string",
                "description": "工具名称（get_tool_info时使用）"
            }
        },
        "required": ["action"]
    }"""
    
    override fun run(context: Context, arguments: String): String {
        val args = JSONObject(arguments)
        val action = args.optString("action", "list_categories")
        
        return when (action) {
            "list_categories" -> listCategories()
            "list_tools" -> listTools(args.optString("category", ""))
            "match_intent" -> matchIntent(args.optString("intent", ""))
            "get_tool_info" -> getToolInfo(args.optString("tool_name", ""))
            "get_best_practices" -> getBestPractices()
            "get_directory_summary" -> getDirectorySummary()
            else -> "未知操作：$action"
        }
    }
    
    private fun listCategories(): String {
        val categories = ToolCapabilityDirectory.getAllCategories()
        val sb = StringBuilder()
        sb.appendLine("## 工具分类列表")
        sb.appendLine()
        for (category in categories) {
            val tools = ToolCapabilityDirectory.getToolsByCategory(category)
            sb.appendLine("- **${category.displayName}**（${category.description}）- ${tools.size}个工具")
        }
        sb.appendLine()
        sb.appendLine("使用 `list_tools` 并指定 category 查看具体工具")
        return sb.toString()
    }
    
    private fun listTools(category: String): String {
        val categoryEnum = try {
            ToolCapabilityDirectory.ToolCategory.valueOf(category)
        } catch (e: Exception) {
            null
        }
        
        val tools = if (categoryEnum != null) {
            ToolCapabilityDirectory.getToolsByCategory(categoryEnum)
        } else {
            ToolCapabilityDirectory.getAllCategories().flatMap { 
                ToolCapabilityDirectory.getToolsByCategory(it) 
            }
        }
        
        val sb = StringBuilder()
        if (categoryEnum != null) {
            sb.appendLine("## ${categoryEnum.displayName}工具列表")
        } else {
            sb.appendLine("## 所有工具列表")
        }
        sb.appendLine()
        
        tools.sortedByDescending { it.priority }.forEach { tool ->
            sb.appendLine("### ${tool.name}")
            sb.appendLine("- **分类**：${tool.category.displayName}")
            sb.appendLine("- **说明**：${tool.description}")
            sb.appendLine("- **使用场景**：${tool.useCases.joinToString("、")}")
            if (tool.examples.isNotEmpty()) {
                sb.appendLine("- **调用示例**：`${tool.examples.first()}`")
            }
            sb.appendLine()
        }
        
        return sb.toString()
    }
    
    private fun matchIntent(intent: String): String {
        if (intent.isBlank()) {
            return "请提供用户意图，例如：'我想打开网页'、'帮我算个数'、'生成一张图片'"
        }
        
        val matchedTools = ToolCapabilityDirectory.matchToolsByIntent(intent)
        
        val sb = StringBuilder()
        sb.appendLine("## 意图匹配结果")
        sb.appendLine()
        sb.appendLine("**用户意图**：$intent")
        sb.appendLine()
        
        if (matchedTools.isEmpty()) {
            sb.appendLine("**未找到匹配工具**")
            sb.appendLine("建议：")
            sb.appendLine("1. 尝试更具体的描述")
            sb.appendLine("2. 使用 `list_categories` 查看所有工具分类")
            sb.appendLine("3. 使用 `get_best_practices` 获取工具使用指南")
        } else {
            sb.appendLine("**推荐工具**（按优先级排序）：")
            for ((index, tool) in matchedTools.withIndex()) {
                sb.appendLine()
                sb.appendLine("${index + 1}. **${tool.name}**（${tool.category.displayName}）")
                sb.appendLine("   - 说明：${tool.description}")
                sb.appendLine("   - 使用场景：${tool.useCases.take(3).joinToString("、")}")
                if (tool.examples.isNotEmpty()) {
                    sb.appendLine("   - 调用示例：`${tool.examples.first()}`")
                }
                if (tool.tips.isNotEmpty()) {
                    sb.appendLine("   - 使用技巧：${tool.tips.first()}")
                }
            }
        }
        
        return sb.toString()
    }
    
    private fun getToolInfo(toolName: String): String {
        if (toolName.isBlank()) {
            return "请提供工具名称，例如：'run_code'、'ui_widget'、'ai_browser'"
        }
        
        val toolInfo = ToolCapabilityDirectory.getToolInfo(toolName)
        
        val sb = StringBuilder()
        sb.appendLine("## 工具详情：$toolName")
        sb.appendLine()
        
        if (toolInfo == null) {
            sb.appendLine("**未找到该工具**")
            sb.appendLine("可能原因：")
            sb.appendLine("1. 工具名称拼写错误")
            sb.appendLine("2. 该工具不在目录中")
            sb.appendLine("使用 `list_tools` 查看所有可用工具")
        } else {
            sb.appendLine("- **分类**：${toolInfo.category.displayName}")
            sb.appendLine("- **说明**：${toolInfo.description}")
            sb.appendLine("- **优先级**：${toolInfo.priority}/5")
            sb.appendLine()
            sb.appendLine("### 使用场景")
            toolInfo.useCases.forEach { sb.appendLine("- $it") }
            sb.appendLine()
            sb.appendLine("### 调用示例")
            toolInfo.examples.forEach { sb.appendLine("- `$it`") }
            sb.appendLine()
            if (toolInfo.parameters.isNotEmpty()) {
                sb.appendLine("### 参数说明")
                toolInfo.parameters.forEach { (key, value) ->
                    sb.appendLine("- **$key**：$value")
                }
                sb.appendLine()
            }
            if (toolInfo.tips.isNotEmpty()) {
                sb.appendLine("### 使用技巧")
                toolInfo.tips.forEach { sb.appendLine("- $it") }
                sb.appendLine()
            }
            if (toolInfo.relatedTools.isNotEmpty()) {
                sb.appendLine("### 相关工具")
                toolInfo.relatedTools.forEach { sb.appendLine("- $it") }
            }
        }
        
        return sb.toString()
    }
    
    private fun getBestPractices(): String {
        return ToolCapabilityDirectory.buildBestPractices()
    }
    
    private fun getDirectorySummary(): String {
        return ToolCapabilityDirectory.buildDirectorySummary()
    }
}