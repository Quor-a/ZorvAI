package com.ai.assistance.quro.genui.sdk.render

/**
 * 自动注册引擎
 *
 * 收集渲染过程中遇到的未知组件类型，并生成注册建议。
 * 与 ComponentRegistry 配合工作：当 resolve() 找不到组件类型时，
 * 自动将其收集到"未知组件集合"中，方便后续分析和注册。
 *
 * 主要用途：
 * 1. 诊断：统计哪些自定义组件被 AI 使用但未注册
 * 2. 建议：为未知组件生成建议的注册提示
 * 3. 调试：帮助开发者了解 AI 输出了哪些不支持的组件
 */
object AutoRegisterEngine {

    /**
     * 未知组件集合
     * 收集所有在渲染过程中遇到的未注册组件类型
     */
    private val unknownComponents = mutableSetOf<String>()

    /**
     * 收集一个未知组件类型
     *
     * @param type 未知的组件类型名称
     */
    fun collectUnknown(type: String) {
        if (type.isNotBlank()) {
            unknownComponents.add(type)
        }
    }

    /**
     * 获取所有遇到的未知组件
     *
     * @return 未知组件类型的不可变集合
     */
    fun getUnknownComponents(): Set<String> {
        return unknownComponents.toSet()
    }

    /**
     * 检查是否有未知组件
     */
    fun hasUnknownComponents(): Boolean {
        return unknownComponents.isNotEmpty()
    }

    /**
     * 生成指定组件的注册建议
     *
     * 为一个未知组件类型生成一段提示文本，
     * 描述如何注册该组件以及建议的模板结构。
     *
     * @param type 未知组件类型名称
     * @return 注册建议文本
     */
    fun suggestRegistration(type: String): String {
        return buildString {
            appendLine("【组件注册建议】")
            appendLine("组件类型 '$type' 未在 GenUI SDK 中注册。")
            appendLine()
            appendLine("解决方案：")
            appendLine("1. 如果这是一个标准组件，请检查组件名称拼写是否正确。")
            appendLine("2. 如果这是一个自定义组件，请使用 register_component 工具先注册再使用。")
            appendLine()
            appendLine("注册示例（使用 register_component 工具）：")
            appendLine("""
            {
              "name": "$type",
              "description": "${type.replace("_", " ")} 组件",
              "category": "custom",
              "template": {
                "type": "card",
                "properties": { "title": "$type" },
                "children": []
              },
              "variables": {}
            }
            """.trimIndent())
            appendLine()
            appendLine("注册后，即可在 UI 生成中使用 type: \"$type\"")
        }
    }

    /**
     * 生成所有未知组件的汇总报告
     *
     * @return 汇总报告文本
     */
    fun generateReport(): String {
        if (unknownComponents.isEmpty()) {
            return "【组件使用报告】\n所有组件均已正确注册，无未知组件。"
        }

        return buildString {
            appendLine("【组件使用报告】")
            appendLine("发现 ${unknownComponents.size} 个未注册的组件类型：")
            appendLine()
            unknownComponents.forEachIndexed { index, type ->
                appendLine("  ${index + 1}. $type")
            }
            appendLine()
            appendLine("建议：使用 register_component 工具逐一注册上述组件，")
            appendLine("或在生成 UI 时使用已有的内置组件替代。")
        }
    }

    /**
     * 清空未知组件集合
     */
    fun clear() {
        unknownComponents.clear()
    }

    /**
     * 获取未知组件数量
     */
    val unknownCount: Int
        get() = unknownComponents.size
}
