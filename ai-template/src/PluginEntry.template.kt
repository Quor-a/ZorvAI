package __PACKAGE__

import com.ai.assistance.quro.plugin.contract.ParamType
import com.ai.assistance.quro.plugin.contract.PluginContext
import com.ai.assistance.quro.plugin.contract.PluginEntry
import com.ai.assistance.quro.plugin.contract.ToolResult
import com.ai.assistance.quro.plugin.dsl.plugin

/**
 * __PLUGIN_NAME__
 * 由 tools/build_plugin.py（或 AI）按 ZorvAI 插件规范生成。
 *
 * 说明：插件不给宿主改任何代码就能加功能 —— 这里注册的每一个扩展点，
 * 宿主都会在对应场景自动取用（AI_TOOL 会被 LLM 直接发现并调用）。
 */
class __ENTRY_CLASS__ : PluginEntry {

    override fun onCreate(ctx: PluginContext) {

        plugin(ctx) {

            // ---------- AI 工具：注册后 LLM 自动可见可调用 ★核心 ----------
            aiTool(
                name = "__TOOL_NAME__",
                // ★ description 要写清「什么时候用它」，LLM 才会在对的时机调用；不要写成版本号
                description = "__TOOL_DESC__"
            ) {
                param("input", ParamType.STRING, "__PARAM_DESC__")

                execute { args ->
                    val input = args.string("input")
                    if (input.isBlank()) return@execute ToolResult.error("输入不能为空")

                    // TODO: 在这里实现真实逻辑（可用任何 Android API / 网络 / 本地库）
                    ToolResult.text(doWork(input))
                }
            }

            // ---------- 可选：同时把能力暴露给 ACI 生态（其他 App / Agent 可调） ----------
            aciCapability(id = "__TOOL_NAME__", description = "__TOOL_DESC__") {
                param("input", ParamType.STRING, "__PARAM_DESC__")
                execute { args -> ToolResult.text(doWork(args.string("input"))) }
            }

            // ---------- 可选：斜杠指令（用户输入 /__TOOL_NAME__ xxx 触发） ----------
            // command("__TOOL_NAME__", "/__TOOL_NAME__ <输入>  __TOOL_DESC__") { args ->
            //     ctx.log("command", doWork(args.trim()))
            //     true
            // }
        }
    }

    override fun onDestroy(ctx: PluginContext) {
        ctx.unregisterAll()
    }

    private fun doWork(input: String): String {
        // TODO 实现
        return "处理结果：$input"
    }
}
