package com.ai.assistance.quro.core.tools

import android.content.Context
import com.ai.assistance.quro.core.fluidcloud.FluidCloudBridge
import org.json.JSONObject

/**
 * 注册到 ZorvAI(Quro) 的流体云通知工具，LLM 执行任务时可自动创建/更新/结束胶囊。
 */
class FluidCloudTool(private val appContext: Context) : QuroTool {
    override val name = "fluid_cloud_notify"
    override val description = "在执行 AI 任务时，于 ColorOS 状态栏显示流体云胶囊/进度（创建/更新/结束）。"
    override val parametersJson = """{
        "type": "object",
        "properties": {
            "action": {
                "type": "string",
                "description": "操作类型：create(创建)/update(更新)/finish(结束)",
                "enum": ["create", "update", "finish"]
            },
            "title": {
                "type": "string",
                "description": "任务标题（创建/更新时使用）"
            },
            "step": {
                "type": "string",
                "description": "当前步骤描述（创建/更新时使用）"
            },
            "progress": {
                "type": "integer",
                "description": "进度0-100（创建/更新时使用）"
            }
        },
        "required": ["action"]
    }"""

    override fun run(context: Context, arguments: String): String {
        return try {
            val args = JSONObject(arguments)
            val action = args.optString("action", "")

            when (action) {
                "create" -> {
                    val title = args.optString("title", "ZorvAI 任务")
                    val step = args.optString("step", "启动")
                    val progress = args.optInt("progress", 0)
                    FluidCloudBridge.create(appContext, title, step, progress)
                    "流体云已创建"
                }
                "update" -> {
                    val title = args.optString("title", "ZorvAI 任务")
                    val step = args.optString("step", "执行中")
                    val progress = args.optInt("progress", 0)
                    FluidCloudBridge.update(appContext, title, step, progress)
                    "流体云已更新"
                }
                "finish" -> {
                    FluidCloudBridge.finish(appContext)
                    "流体云已结束"
                }
                else -> "未知action: $action，可选值：create/update/finish"
            }
        } catch (e: Exception) {
            "执行异常: ${e.message}"
        }
    }
}
