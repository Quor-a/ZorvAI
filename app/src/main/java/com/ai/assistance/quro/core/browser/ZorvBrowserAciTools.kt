package com.ai.assistance.quro.core.browser

import android.content.Context
import com.ai.assistance.quro.core.tools.QuroTool
import org.json.JSONObject

/**
 * ZorvBrowser-ACI 工具集：让 AI 通过 ACI 调用 ZorvBrowser 浏览器的工具。
 * - browser_aci_list ：列出所有可通过 ACI 调用的浏览器工具
 * - browser_aci_call ：通过 ACI 调用浏览器工具（统一入口）
 * - browser_aci_bridge ：管理 ZorvBrowser-ACI 桥接器
 */

/** 列出所有可通过 ACI 调用的 ZorvBrowser 工具。 */
class BrowserAciListTool : QuroTool {
    override val name = "browser_aci_list"
    override val description = "列出所有可通过 ACI 调用的 ZorvBrowser 浏览器工具（30 个 ACI 能力）。" +
            "这些工具来自 ZorvBrowser 浏览器，可以通过 browser_aci_call 调用。参数为空 {}。"
    override val parametersJson = """{"type":"object","properties":{}}"""
    
    override fun run(context: Context, arguments: String): String {
        val mappings = ZorvBrowserAciBridge.getAllBrowserToolMappings()
        
        if (mappings.isEmpty()) {
            return "尚无可用的 ZorvBrowser 浏览器工具。请先安装 ZorvBrowser 应用。"
        }
        
        val sb = StringBuilder()
        sb.appendLine("可通过 ACI 调用的 ZorvBrowser 浏览器工具：")
        sb.appendLine()
        
        // 按类别分组
        val categories = mapOf(
            "基础导航" to listOf("browser_open", "browser_back", "browser_forward", "browser_reload", "browser_close", "browser_screenshot"),
            "标签页管理" to listOf("browser_tabs_list", "browser_tabs_switch", "browser_tabs_new", "browser_tabs_close"),
            "DOM 操作" to listOf("browser_dom_query", "browser_dom_text", "browser_dom_attr", "browser_dom_click", "browser_dom_type"),
            "内容提取" to listOf("browser_crawl", "browser_html", "browser_text", "browser_links"),
            "JavaScript 执行" to listOf("browser_script"),
            "输入模拟" to listOf("browser_input_click", "browser_input_type", "browser_input_scroll"),
            "HTTP 请求" to listOf("browser_http_request"),
            "高级功能" to listOf("browser_find_text", "browser_pdf", "browser_print"),
            "书签和历史" to listOf("browser_bookmarks_list", "browser_bookmarks_add", "browser_history_list")
        )
        
        for ((category, toolNames) in categories) {
            sb.appendLine("【$category】")
            for (toolName in toolNames) {
                val mapping = mappings[toolName] ?: continue
                sb.appendLine("  - $toolName: ${mapping.toolDescription}")
            }
            sb.appendLine()
        }
        
        sb.appendLine("使用 browser_aci_call 工具调用这些工具，参数格式：")
        sb.appendLine("{\"tool\":\"{工具名}\",\"args\":{参数}}")
        
        return sb.toString()
    }
}

/** 通过 ACI 调用 ZorvBrowser 浏览器工具。 */
class BrowserAciCallTool : QuroTool {
    override val name = "browser_aci_call"
    override val description = "通过 ACI 调用 ZorvBrowser 浏览器的工具。这是调用浏览器工具的统一入口。" +
            "参数 {\"tool\":\"{工具名}\",\"args\":{...}}。" +
            "先用 browser_aci_list 查看可用工具。" +
            "示例：{\"tool\":\"browser_open\",\"args\":{\"url\":\"https://example.com\"}}"
    override val parametersJson = """{
        "type":"object",
        "properties":{
            "tool":{"type":"string","description":"浏览器工具名称"},
            "args":{"type":"object","description":"工具参数对象"}
        },
        "required":["tool"]
    }"""
    
    override fun run(context: Context, arguments: String): String {
        val jo = runCatching { JSONObject(arguments) }.getOrElse { return "参数不是合法 JSON" }
        val toolName = jo.optString("tool", "").trim()
        val argsObj = jo.optJSONObject("args") ?: JSONObject()
        
        if (toolName.isEmpty()) {
            return "缺少 tool 参数（浏览器工具名称）"
        }
        
        // 检查是否为浏览器能力
        if (!ZorvBrowserAciBridge.isBrowserCapability(toolName)) {
            return "工具名称必须以 'browser_' 开头（ZorvBrowser 浏览器工具）"
        }
        
        // 通过 ACI 调用浏览器工具
        return try {
            val response = ZorvBrowserAciBridge.callBrowserTool(toolName, argsObj)
            
            if (response.isSuccess()) {
                val result = response.getResult()
                if (result != null) {
                    // 提取浏览器调用结果
                    val browserResult = result.getString("browser_result")
                    val toolUsed = result.getString("tool_used")
                    
                    if (browserResult != null) {
                        "✅ ZorvBrowser 工具调用成功 ($toolUsed)\n\n$browserResult"
                    } else {
                        "✅ ZorvBrowser 工具调用成功，但无返回数据"
                    }
                } else {
                    "✅ ZorvBrowser 工具调用成功，但无返回数据"
                }
            } else {
                "❌ ZorvBrowser 工具调用失败（错误码=${response.getErrorCode()}）：${response.getErrorMessage()}"
            }
        } catch (e: Exception) {
            "❌ 调用 ZorvBrowser 工具异常：${e.message}"
        }
    }
}

/** ZorvBrowser-ACI 桥接管理工具。 */
class BrowserAciBridgeTool : QuroTool {
    override val name = "browser_aci_bridge"
    override val description = "管理 ZorvBrowser-ACI 桥接器。支持操作：refresh（刷新浏览器工具列表）、status（查看桥接状态）。" +
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
                ZorvBrowserAciBridge.init(context)
                val mappings = ZorvBrowserAciBridge.getAllBrowserToolMappings()
                "✅ ZorvBrowser-ACI 桥接器已刷新\n\n当前注册的浏览器工具：${mappings.size} 个\n" +
                    mappings.values.groupBy { it.toolName.substringAfter("browser_").substringBefore("_") }.map { (category, tools) ->
                        "  $category: ${tools.size} 个工具"
                    }.joinToString("\n")
            }
            "status" -> {
                val mappings = ZorvBrowserAciBridge.getAllBrowserToolMappings()
                
                buildString {
                    appendLine("📊 ZorvBrowser-ACI 桥接器状态")
                    appendLine()
                    appendLine("浏览器工具：${mappings.size} 个")
                    appendLine("包名：${ZorvBrowserAciBridge.BROWSER_PACKAGE}")
                    appendLine()
                    appendLine("工具列表：")
                    mappings.values.forEach { tool ->
                        appendLine("  - ${tool.toolName}: ${tool.toolDescription}")
                    }
                }
            }
            else -> "未知操作: $action（支持 refresh/status）"
        }
    }
}