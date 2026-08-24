package com.ai.assistance.quro.core.browser

import ai.aidl.aci.core.AidlAciResponse
import ai.aidl.aci.core.Capability
import android.content.Context
import android.os.Bundle
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * ZorvBrowser-ACI 桥接器：将 ZorvBrowser 浏览器的 ACI 能力暴露给 ZorvAI 控制方。
 *
 * 架构：
 * 1. ZorvBrowser 能力 → ACI Capability 映射：将每个浏览器工具转换为 ACI Capability 格式
 * 2. ACI 调用路由：当 ACI 调用浏览器能力时，路由到 ZorvBrowser 执行
 * 3. 能力发现：将 ZorvBrowser 的工具暴露给 ACI 能力发现系统
 * 4. MCP 集成：支持通过 MCP 协议调用 ZorvBrowser 工具
 * 5. ACI Token 认证：支持 ACI Token 认证机制
 *
 * 使用方式：
 * - ZorvBrowserAciBridge.init(context) 初始化
 * - ZorvBrowserAciBridge.getBrowserCapabilities() 获取所有浏览器工具的 ACI 能力列表
 * - ZorvBrowserAciBridge.callBrowserTool(toolName, arguments) 调用浏览器工具
 */
object ZorvBrowserAciBridge {
    private const val TAG = "ZorvBrowserAciBridge"
    const val BROWSER_PACKAGE = "com.ai.assistance.quro.browser" // ZorvBrowser 包名
    
    private var appContext: Context? = null
    
    // ZorvBrowser 工具 → ACI 能力映射：key = toolName
    private val browserToolToAciMap = ConcurrentHashMap<String, BrowserToolAciMapping>()
    
    /**
     * 初始化 ZorvBrowser-ACI 桥接器
     */
    fun init(context: Context) {
        appContext = context.applicationContext
        Log.i(TAG, "ZorvBrowser-ACI 桥接器已初始化")
        
        // 注册 ZorvBrowser 工具
        registerBrowserTools()
    }
    
    /**
     * 注册 ZorvBrowser 工具
     */
    private fun registerBrowserTools() {
        // 清空旧缓存
        browserToolToAciMap.clear()
        
        // 注册 ZorvBrowser 的 30 个 ACI 工具
        val browserTools = listOf(
            // 基础导航工具
            BrowserTool("browser_open", "打开网页", """{"type":"object","properties":{"url":{"type":"string","description":"要打开的 URL"}},"required":["url"]}"""),
            BrowserTool("browser_back", "返回上一页", """{"type":"object","properties":{}}"""),
            BrowserTool("browser_forward", "前进下一页", """{"type":"object","properties":{}}"""),
            BrowserTool("browser_reload", "刷新当前页面", """{"type":"object","properties":{}}"""),
            BrowserTool("browser_close", "关闭当前标签页", """{"type":"object","properties":{}}"""),
            BrowserTool("browser_screenshot", "截取当前页面截图", """{"type":"object","properties":{"fullPage":{"type":"boolean","description":"是否截取整页","default":false}}}"""),
            
            // 标签页管理
            BrowserTool("browser_tabs_list", "列出所有标签页", """{"type":"object","properties":{}}"""),
            BrowserTool("browser_tabs_switch", "切换到指定标签页", """{"type":"object","properties":{"tabId":{"type":"string","description":"标签页 ID"}},"required":["tabId"]}"""),
            BrowserTool("browser_tabs_new", "新建标签页", """{"type":"object","properties":{"url":{"type":"string","description":"要打开的 URL"}}}"""),
            BrowserTool("browser_tabs_close", "关闭指定标签页", """{"type":"object","properties":{"tabId":{"type":"string","description":"标签页 ID"}},"required":["tabId"]}"""),
            
            // DOM 操作工具
            BrowserTool("browser_dom_query", "查询页面元素", """{"type":"object","properties":{"selector":{"type":"string","description":"CSS 选择器"}},"required":["selector"]}"""),
            BrowserTool("browser_dom_text", "获取元素文本", """{"type":"object","properties":{"selector":{"type":"string","description":"CSS 选择器"}},"required":["selector"]}"""),
            BrowserTool("browser_dom_attr", "获取元素属性", """{"type":"object","properties":{"selector":{"type":"string","description":"CSS 选择器"},"attribute":{"type":"string","description":"属性名"}},"required":["selector","attribute"]}"""),
            BrowserTool("browser_dom_click", "点击元素", """{"type":"object","properties":{"selector":{"type":"string","description":"CSS 选择器"}},"required":["selector"]}"""),
            BrowserTool("browser_dom_type", "在元素中输入文本", """{"type":"object","properties":{"selector":{"type":"string","description":"CSS 选择器"},"text":{"type":"string","description":"要输入的文本"}},"required":["selector","text"]}"""),
            
            // 内容提取工具
            BrowserTool("browser_crawl", "提取页面结构化正文和出站链接", """{"type":"object","properties":{}}"""),
            BrowserTool("browser_html", "获取页面完整 HTML", """{"type":"object","properties":{}}"""),
            BrowserTool("browser_text", "获取页面纯文本", """{"type":"object","properties":{}}"""),
            BrowserTool("browser_links", "获取页面所有链接", """{"type":"object","properties":{}}"""),
            
            // JavaScript 执行工具
            BrowserTool("browser_script", "在页面上下文执行 JavaScript", """{"type":"object","properties":{"script":{"type":"string","description":"要执行的 JavaScript 代码"}},"required":["script"]}"""),
            
            // 输入模拟工具
            BrowserTool("browser_input_click", "模拟点击（系统级）", """{"type":"object","properties":{"x":{"type":"integer","description":"X 坐标"},"y":{"type":"integer","description":"Y 坐标"}},"required":["x","y"]}"""),
            BrowserTool("browser_input_type", "模拟键盘输入（系统级）", """{"type":"object","properties":{"text":{"type":"string","description":"要输入的文本"}},"required":["text"]}"""),
            BrowserTool("browser_input_scroll", "模拟滚动（系统级）", """{"type":"object","properties":{"deltaX":{"type":"integer","description":"水平滚动量"},"deltaY":{"type":"integer","description":"垂直滚动量"}}}"""),
            
            // HTTP 请求工具
            BrowserTool("browser_http_request", "发送 HTTP 请求（支持 LAN 明文）", """{"type":"object","properties":{"url":{"type":"string","description":"请求 URL"},"method":{"type":"string","description":"HTTP 方法","default":"GET"},"headers":{"type":"object","description":"请求头"},"body":{"type":"string","description":"请求体"}},"required":["url"]}"""),
            
            // 高级功能工具
            BrowserTool("browser_find_text", "在页面中查找文本", """{"type":"object","properties":{"text":{"type":"string","description":"要查找的文本"}},"required":["text"]}"""),
            BrowserTool("browser_pdf", "将当前页面导出为 PDF", """{"type":"object","properties":{"filename":{"type":"string","description":"文件名"}}}"""),
            BrowserTool("browser_print", "打印当前页面", """{"type":"object","properties":{}}"""),
            
            // 书签和历史
            BrowserTool("browser_bookmarks_list", "列出所有书签", """{"type":"object","properties":{}}"""),
            BrowserTool("browser_bookmarks_add", "添加书签", """{"type":"object","properties":{"url":{"type":"string","description":"书签 URL"},"title":{"type":"string","description":"书签标题"}},"required":["url","title"]}"""),
            BrowserTool("browser_history_list", "列出浏览历史", """{"type":"object","properties":{"limit":{"type":"integer","description":"返回数量限制","default":10}}}""")
        )
        
        // 将每个工具转换为 ACI 能力映射
        for (tool in browserTools) {
            val mapping = BrowserToolAciMapping(
                toolName = tool.name,
                toolDescription = tool.description,
                toolParametersJson = tool.parametersJson
            )
            browserToolToAciMap[tool.name] = mapping
            Log.d(TAG, "注册浏览器工具: ${tool.name} → ACI 能力")
        }
        
        Log.i(TAG, "ZorvBrowser 工具注册完成：${browserToolToAciMap.size} 个工具")
    }
    
    /**
     * 获取所有浏览器工具的 ACI 能力列表
     * 用于集成到 ACI 能力发现系统
     */
    fun getBrowserCapabilities(): List<Capability> {
        val capabilities = mutableListOf<Capability>()
        
        for ((toolName, mapping) in browserToolToAciMap) {
            try {
                val capability = createCapabilityFromBrowserTool(mapping)
                capabilities.add(capability)
            } catch (e: Exception) {
                Log.w(TAG, "创建 ACI 能力失败：$toolName → ${e.message}")
            }
        }
        
        Log.i(TAG, "生成浏览器 ACI 能力：${capabilities.size} 个")
        return capabilities
    }
    
    /**
     * 获取浏览器能力提示词（用于 LLM 系统提示）
     */
    fun getBrowserCapabilityPrompt(): String {
        if (browserToolToAciMap.isEmpty()) {
            return "ZorvBrowser 浏览器：无可用工具"
        }
        
        val sb = StringBuilder()
        sb.appendLine("ZorvBrowser 浏览器工具（30 个 ACI 能力）：")
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
                val mapping = browserToolToAciMap[toolName] ?: continue
                sb.appendLine("  - $toolName: ${mapping.toolDescription}")
            }
            sb.appendLine()
        }
        
        return sb.toString()
    }
    
    /**
     * 通过 ACI 调用浏览器工具
     *
     * @param toolName 浏览器工具名称
     * @param arguments 工具参数
     * @return ACI 调用响应
     */
    fun callBrowserTool(toolName: String, arguments: JSONObject): AidlAciResponse {
        val mapping = browserToolToAciMap[toolName]
        
        if (mapping == null) {
            return AidlAciResponse.error(404, "浏览器工具未找到: $toolName")
        }
        
        return try {
            // 查找 ZorvBrowser 的 ACI 服务
            val ctx = appContext ?: return AidlAciResponse.error(500, "上下文未初始化")
            
            // 通过 ACI 调用 ZorvBrowser
            val response = com.ai.assistance.quro.core.aidlaci.QuroAidlAciManager.getInstance()
                .call(BROWSER_PACKAGE, toolName, Bundle().apply {
                    // 将 JSONObject 参数转换为 Bundle
                    arguments.keys().forEach { key ->
                        val value = arguments.get(key)
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
            
            response
        } catch (e: Exception) {
            Log.w(TAG, "调用浏览器工具失败: $toolName → ${e.message}")
            AidlAciResponse.error(500, "浏览器工具调用失败: ${e.message}")
        }
    }
    
    /**
     * 从浏览器工具创建 ACI Capability
     */
    private fun createCapabilityFromBrowserTool(mapping: BrowserToolAciMapping): Capability {
        // 构建 ACI 能力 JSON
        val capJson = JSONObject().apply {
            put("id", mapping.toolName)
            put("description", mapping.toolDescription)
            put("requireUserConfirm", false)
            
            // 解析参数
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
                Log.w(TAG, "解析浏览器工具参数失败: ${mapping.toolName}")
            }
            put("parameters", params)
        }
        
        // 从 JSON 创建 Capability 对象
        val jsonArray = JSONArray().apply { put(capJson) }
        val capabilities = Capability.fromJSONArray(jsonArray)
        
        return if (capabilities.isNotEmpty()) {
            capabilities.first()
        } else {
            throw IllegalStateException("无法创建浏览器工具的 ACI 能力: ${mapping.toolName}")
        }
    }
    
    /**
     * 检查是否为浏览器能力
     */
    fun isBrowserCapability(capabilityId: String): Boolean {
        return capabilityId.startsWith("browser_")
    }
    
    /**
     * 从 ACI 能力 ID 提取浏览器工具信息
     */
    fun extractBrowserToolFromCapability(capabilityId: String): String? {
        if (!capabilityId.startsWith("browser_")) return null
        return capabilityId
    }
    
    /**
     * 获取浏览器工具映射信息
     */
    fun getBrowserToolMapping(toolName: String): BrowserToolAciMapping? {
        return browserToolToAciMap[toolName]
    }
    
    /**
     * 获取所有浏览器工具映射
     */
    fun getAllBrowserToolMappings(): Map<String, BrowserToolAciMapping> {
        return browserToolToAciMap.toMap()
    }
    
    /**
     * 浏览器工具数据类
     */
    data class BrowserTool(
        val name: String,
        val description: String,
        val parametersJson: String
    )
    
    /**
     * 浏览器工具到 ACI 能力的映射数据类
     */
    data class BrowserToolAciMapping(
        val toolName: String,
        val toolDescription: String,
        val toolParametersJson: String
    )
}