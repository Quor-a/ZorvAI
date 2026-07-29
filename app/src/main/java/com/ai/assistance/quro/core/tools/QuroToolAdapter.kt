package com.ai.assistance.quro.core.tools

import com.ai.assistance.quro.core.mcp.McpTool
import com.ai.assistance.quro.core.mcp.ParameterType
import com.ai.assistance.quro.core.mcp.ToolAnnotations
import com.ai.assistance.quro.core.mcp.ToolParameter
import com.ai.assistance.quro.core.mcp.ToolResult
import org.json.JSONArray
import org.json.JSONObject

/**
 * 适配器：把我们的原创 [QuroTool] 适配进 vendored droid-mcp 引擎（Apache-2.0）。
 * 这样「工具实现 100% 原创」与「引擎来自 Apache-2.0 公用库」干净分离。
 */
fun QuroTool.toMcpTool(engine: QuroToolEngine): McpTool {
    val self = this
    return object : McpTool {
        override val name = self.name
        override val description = self.description
        override val parameters = jsonSchemaToToolParameters(self.parametersJson)
        override val annotations = ToolAnnotations(readOnlyHint = true)

        override suspend fun execute(params: Map<String, Any>): ToolResult {
            val ctx = engine.getContext() ?: return ToolResult.error("上下文不可用")
            val argsJson = mapToJson(params)
            return try {
                val r = self.run(ctx, argsJson)
                ToolResult.success(mapOf("result" to r))
            } catch (e: Exception) {
                ToolResult.error(e.message ?: "error")
            }
        }
    }
}

/** 解析 OpenAI/JSON-Schema 参数定义 → droid-mcp ToolParameter 列表。 */
fun jsonSchemaToToolParameters(schemaJson: String): List<ToolParameter> {
    val out = mutableListOf<ToolParameter>()
    runCatching {
        val root = JSONObject(schemaJson)
        val props = root.optJSONObject("properties") ?: return emptyList()
        val required = root.optJSONArray("required")
        val reqSet = mutableSetOf<String>()
        required?.let { for (i in 0 until it.length()) reqSet.add(it.optString(i)) }
        val keys = props.keys()
        while (keys.hasNext()) {
            val name = keys.next()
            val p = props.optJSONObject(name) ?: continue
            val type = when (p.optString("type", "string")) {
                "integer" -> ParameterType.INTEGER
                "number" -> ParameterType.NUMBER
                "boolean" -> ParameterType.BOOLEAN
                "array" -> ParameterType.ARRAY
                "object" -> ParameterType.OBJECT
                else -> ParameterType.STRING
            }
            out.add(ToolParameter(name, p.optString("description", ""), type, reqSet.contains(name)))
        }
    }
    return out
}

/** Map<String, Any> → JSON 字符串（供 QuroTool.run 使用）。 */
fun mapToJson(map: Map<String, Any>): String {
    val o = JSONObject()
    map.forEach { (k, v) -> o.put(k, v) }
    return o.toString()
}

/** JSON 字符串 → Map<String, Any>（LLM 的 tool_calls.arguments → droid-mcp params）。 */
fun jsonToMap(json: String): Map<String, Any> {
    val map = LinkedHashMap<String, Any>()
    val o = JSONObject(json)
    val keys = o.keys()
    while (keys.hasNext()) {
        val k = keys.next()
        map[k] = o.get(k)
    }
    return map
}
