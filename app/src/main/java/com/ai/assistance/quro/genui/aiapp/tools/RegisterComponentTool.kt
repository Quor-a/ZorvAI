package com.ai.assistance.quro.genui.aiapp.tools

import com.ai.assistance.quro.genui.sdk.dsl.DslParser
import com.ai.assistance.quro.genui.sdk.skill.GlobalDynamicRegistry
import org.json.JSONObject

/**
 * 注册自定义组件工具
 *
 * 允许 AI 在运行时动态注册新的组件类型到 GenUI SDK。
 * 注册后可以在后续的 UI 生成中直接使用该组件类型。
 *
 * 参数说明：
 * - name: 组件类型名称，小写+下划线，如 "weather_card"
 * - description: 组件功能描述
 * - category: 分类，如 custom/layout/display
 * - template: 组件模板，一个完整的 UIComponent JSON 对象
 * - variables: 变量映射，key 是变量名，value 是默认值说明
 */
class RegisterComponentTool : GenUITool {

    override val name: String = "register_component"

    override val description: String =
        "注册一个新的自定义组件类型到 GenUI SDK。注册后可以在后续的 UI 生成中直接使用该组件类型。" +
        "当你需要的组件不在内置组件列表中时，使用此工具先注册再使用。"

    override val parametersJson: String = """
        {
          "type": "object",
          "properties": {
            "name": {
              "type": "string",
              "description": "组件类型名称，使用小写字母和下划线，如 weather_card"
            },
            "description": {
              "type": "string",
              "description": "组件功能描述，说明这个组件的用途"
            },
            "category": {
              "type": "string",
              "description": "组件分类，如 custom/layout/display/button/input 等",
              "default": "custom"
            },
            "template": {
              "type": "object",
              "description": "组件模板，一个完整的 UIComponent JSON 对象，包含 type、properties、style、children 等"
            },
            "variables": {
              "type": "object",
              "description": "变量映射，key 是变量名，value 是默认值说明",
              "additionalProperties": {
                "type": "string"
              }
            }
          },
          "required": ["name", "template"]
        }
    """.trimIndent()

    override suspend fun execute(arguments: String): String {
        return try {
            val argsJson = JSONObject(arguments)

            val compName = argsJson.optString("name", "").trim()
            if (compName.isEmpty()) {
                return errorResult("组件名称不能为空")
            }

            val templateObj = argsJson.optJSONObject("template")
            if (templateObj == null) {
                return errorResult("组件模板 template 不能为空")
            }

            val description = argsJson.optString("description", "")
            val category = argsJson.optString("category", "custom")

            // 解析 variables
            val variables = mutableMapOf<String, String>()
            argsJson.optJSONObject("variables")?.let { varsObj ->
                val keys = varsObj.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    variables[key] = varsObj.optString(key, "")
                }
            }

            // 解析 template 为 UIComponent
            val templateComponent = try {
                DslParser.parseComponent(templateObj.toString())
            } catch (e: Exception) {
                return errorResult("组件模板解析失败: ${e.message}")
            }

            // 注册到动态注册表
            val registry = GlobalDynamicRegistry.get()
            if (registry == null) {
                return errorResult("动态注册表未初始化")
            }

            registry.registerTemplate(
                name = compName,
                description = description,
                template = templateComponent,
                category = category,
                variables = variables
            )

            // 返回成功结果
            JSONObject().apply {
                put("success", true)
                put("message", "组件 $compName 注册成功")
                put("registered_type", compName)
                put("description", description)
                put("category", category)
            }.toString()

        } catch (e: Exception) {
            errorResult("注册组件失败: ${e.message}")
        }
    }

    private fun errorResult(message: String): String {
        return JSONObject().apply {
            put("success", false)
            put("message", message)
            put("registered_type", "")
        }.toString()
    }
}
