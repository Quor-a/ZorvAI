package com.ai.assistance.quro.genui.aiapp.tools

import com.ai.assistance.quro.genui.aiapp.core.GenUIToolSpec

/**
 * GenUI 工具接口
 *
 * 所有可被 AI 通过 function calling 调用的工具都需要实现此接口。
 * 工具的执行结果以 JSON 字符串形式返回。
 */
interface GenUITool {
    /** 工具名称，唯一标识 */
    val name: String

    /** 工具功能描述，用于 LLM 理解工具用途 */
    val description: String

    /**
     * 工具参数的 JSON Schema 定义
     * 格式为标准的 JSON Schema 对象字符串，描述工具接受的参数结构。
     */
    val parametersJson: String

    /**
     * 执行工具
     *
     * @param arguments 工具参数字符串（JSON 格式）
     * @return 执行结果（JSON 格式字符串）
     */
    suspend fun execute(arguments: String): String
}

/**
 * GenUI 工具注册表
 *
 * 管理所有已注册的工具，提供注册、查询、执行等功能。
 * 同时负责将工具转换为 LLM 可识别的 GenUIToolSpec 格式。
 */
object GenUIToolRegistry {
    private val tools = mutableMapOf<String, GenUITool>()

    /**
     * 注册一个工具
     *
     * @param tool 要注册的工具实例
     */
    fun register(tool: GenUITool) {
        tools[tool.name] = tool
    }

    /**
     * 根据名称获取工具
     *
     * @param name 工具名称
     * @return 工具实例，如果未注册则返回 null
     */
    fun get(name: String): GenUITool? {
        return tools[name]
    }

    /**
     * 获取所有工具的规格定义
     * 用于传递给 LLM 的 tools 参数
     *
     * @return 工具规格列表
     */
    fun getAllSpecs(): List<GenUIToolSpec> {
        return tools.values.map { tool ->
            GenUIToolSpec(
                name = tool.name,
                description = tool.description,
                parametersJson = tool.parametersJson
            )
        }
    }

    /**
     * 执行指定名称的工具
     *
     * @param name 工具名称
     * @param arguments 工具参数（JSON 字符串）
     * @return 执行结果（JSON 格式字符串）
     * @throws IllegalArgumentException 如果工具不存在
     */
    suspend fun executeTool(name: String, arguments: String): String {
        val tool = tools[name]
            ?: throw IllegalArgumentException("工具不存在: $name")
        return tool.execute(arguments)
    }

    /**
     * 检查工具是否已注册
     *
     * @param name 工具名称
     * @return 是否已注册
     */
    fun isRegistered(name: String): Boolean {
        return name in tools
    }

    /**
     * 获取已注册工具的数量
     */
    val size: Int
        get() = tools.size
}
