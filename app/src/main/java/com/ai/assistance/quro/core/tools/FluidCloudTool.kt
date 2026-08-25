package com.ai.assistance.quro.core.tools

import android.content.Context
import com.ai.assistance.quro.core.fluidcloud.FluidCloudBridge
import com.ai.assistance.quro.core.fluidcloud.FluidCloudLiveUpdate
import org.json.JSONObject

/**
 * 流体云通知工具：LLM 执行任务时自动创建/更新/结束状态栏胶囊。
 *
 * 双模式：
 * 1. OPPO ContentProvider（ColorOS 14+ 流体云）
 * 2. Android 16+ Live Updates 通知
 * 自动检测，优先 ContentProvider，失败降级 Live Updates。
 */
class FluidCloudTool(private val appContext: Context) : QuroTool {
    override val name = "fluid_cloud_notify"
    override val description = "在执行 AI 任务时，于状态栏显示流体云胶囊/实时更新通知（创建/更新/结束）。"
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
                "description": "任务标题"
            },
            "step": {
                "type": "string",
                "description": "当前步骤描述"
            },
            "progress": {
                "type": "integer",
                "description": "进度0-100"
            }
        },
        "required": ["action"]
    }"""

    override fun run(context: Context, arguments: String): String {
        return try {
            val args = JSONObject(arguments)
            val action = args.optString("action", "")
            val title = args.optString("title", "ZorvAI 任务")
            val step = args.optString("step", "执行中")
            val progress = args.optInt("progress", 0)

            when (action) {
                "create" -> {
                    val providerOk = FluidCloudBridge.create(appContext, title, step, progress)
                    if (!providerOk) FluidCloudLiveUpdate.show(appContext, title, step, progress)
                    "流体云已创建（${if (providerOk) "ContentProvider" else "LiveUpdates"}）"
                }
                "update" -> {
                    val providerOk = FluidCloudBridge.update(appContext, title, step, progress)
                    if (!providerOk) FluidCloudLiveUpdate.show(appContext, title, step, progress)
                    "流体云已更新（${if (providerOk) "ContentProvider" else "LiveUpdates"}）"
                }
                "finish" -> {
                    val providerOk = FluidCloudBridge.finish(appContext)
                    if (!providerOk) FluidCloudLiveUpdate.finish(appContext)
                    "流体云已结束"
                }
                else -> "未知action: $action，可选值：create/update/finish"
            }
        } catch (e: Exception) {
            "执行异常: ${e.message}"
        }
    }
}
