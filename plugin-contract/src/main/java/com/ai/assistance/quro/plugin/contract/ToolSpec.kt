package com.ai.assistance.quro.plugin.contract

/**
 * 插件向 ZorvAI 注册一个 AI 工具（QuroTool）。
 *
 * 这是整个插件框架最重要的扩展点：注册之后 LLM 会自动在 function calling 里
 * 看到这个工具并调用它 —— 宿主不需要为它写任何接线代码（Tool-first 设计）。
 *
 * 数据结构对齐 core/mcp 下的 McpTool / ToolParameter / ToolResult，
 * 宿主的 HostToolBridge 负责把这里的对象转换成宿主真实类型。
 */
data class ToolSpec(
    /** 工具名，全局唯一。建议加插件前缀，如 express_query */
    val name: String,
    /** 给 LLM 看的描述，写得越清楚 LLM 越会用。★ 不要写成版本号 */
    val description: String,
    val parameters: List<ToolParamSpec> = emptyList(),
    /** 该工具是否需要危险操作确认（如删文件、发短信） */
    val requiresConfirm: Boolean = false,
    /** 声明需要的能力（用于宿主权限提示），如 "network" "location" "sms" */
    val capabilities: Set<String> = emptySet()
)

data class ToolParamSpec(
    val name: String,
    val type: ParamType,
    val description: String,
    val required: Boolean = true,
    val enumValues: List<String> = emptyList(),
    val defaultValue: String? = null
)

enum class ParamType(val jsonType: String) {
    STRING("string"),
    INT("integer"),
    NUMBER("number"),
    BOOLEAN("boolean"),
    ARRAY("array"),
    OBJECT("object");
}

/** 工具执行参数 */
class ToolArgs(private val map: Map<String, Any?>) {
    fun string(key: String): String = map[key]?.toString() ?: ""
    fun int(key: String, default: Int = 0): Int =
        (map[key] as? Number)?.toInt() ?: map[key]?.toString()?.toIntOrNull() ?: default
    fun number(key: String, default: Double = 0.0): Double =
        (map[key] as? Number)?.toDouble() ?: map[key]?.toString()?.toDoubleOrNull() ?: default
    fun boolean(key: String, default: Boolean = false): Boolean =
        (map[key] as? Boolean) ?: (map[key]?.toString()?.toBoolean() ?: default)
    fun has(key: String): Boolean = map.containsKey(key)
    fun raw(): Map<String, Any?> = map
}

/** 工具执行结果 */
sealed class ToolResult {
    data class Text(val text: String) : ToolResult()
    data class Error(val message: String) : ToolResult()
    /** 返回结构化数据，宿主会渲染成卡片或 JSON */
    data class Json(val json: String) : ToolResult()

    companion object {
        fun text(t: String) = Text(t)
        fun error(m: String) = Error(m)
        fun json(j: String) = Json(j)
        fun ok(t: String) = Text(t)
    }
}

/** 工具执行体 */
fun interface ToolExecutor {
    suspend fun execute(args: ToolArgs): ToolResult
}
