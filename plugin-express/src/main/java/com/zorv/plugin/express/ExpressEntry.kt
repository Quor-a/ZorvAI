package com.zorv.plugin.express

import com.ai.assistance.quro.plugin.contract.ParamType
import com.ai.assistance.quro.plugin.contract.PluginContext
import com.ai.assistance.quro.plugin.contract.PluginEntry
import com.ai.assistance.quro.plugin.contract.ToolResult
import com.ai.assistance.quro.plugin.dsl.plugin

/**
 * 示例插件：给 ZorvAI 增加「快递查询」能力 —— 这是宿主原本没有的功能。
 *
 * 注册了三个扩展点：
 *  1. aiTool        → LLM 自动发现，用户问「我的快递到哪了」就会调用
 *  2. aciCapability → 通过 ACI 暴露给其他 App / 其他 Agent 调用
 *  3. command       → 用户可直接输入 /express SF1234567890
 *
 * 宿主不需要为这个插件改任何代码 —— 装完插件，AI 的工具集里就多了 express_query。
 */
class ExpressEntry : PluginEntry {

    override fun onCreate(ctx: PluginContext) {
        ctx.log("ExpressEntry", "插件启动 v${ctx.pluginVersion}")

        plugin(ctx) {

            // ---------- 1. AI 工具（LLM 自动发现并调用） ----------
            aiTool(
                name = "express_query",
                description = "根据快递单号查询物流轨迹。当用户询问快递到哪了、物流状态、包裹进度时调用。"
            ) {
                param("no", ParamType.STRING, "快递单号，如 SF1234567890")
                param(
                    "company", ParamType.STRING, "快递公司编码，可留空自动识别",
                    required = false, enum = listOf("sf", "jd", "zto", "yto")
                )
                execute { args ->
                    val no = args.string("no")
                    if (no.isBlank()) ToolResult.error("缺少快递单号")
                    else {
                        val tracks = ExpressApi.query(no)
                        if (tracks.isEmpty()) ToolResult.text("未查询到单号 $no 的物流信息")
                        else ToolResult.text(tracks.joinToString("\n"))
                    }
                }
            }

            // ---------- 2. ACI 能力（融合进 ACI 生态，外部 App 可调） ----------
            aciCapability(
                id = "query_express",
                description = "查询快递物流轨迹，输入快递单号返回最新状态"
            ) {
                param("no", ParamType.STRING, "快递单号")
                execute { args ->
                    ToolResult.text(ExpressApi.query(args.string("no")).lastOrNull() ?: "无记录")
                }
            }

            // ---------- 3. 斜杠指令 ----------
            command("express", "/express <单号>  查询物流") { args ->
                val tracks = ExpressApi.query(args.trim())
                ctx.log("command", tracks.lastOrNull() ?: "无记录")
                tracks.isNotEmpty()
            }
        }
    }

    override fun onDestroy(ctx: PluginContext) {
        ctx.unregisterAll()
    }
}

/** 模拟数据源，真实插件里换成你的 HTTP 请求 */
internal object ExpressApi {
    fun query(no: String): List<String> = listOf(
        "【$no】已揽收 - 深圳中转场",
        "【$no】运输中 - 已到达广州",
        "【$no】派送中 - 快递员 138****8888"
    )
}
