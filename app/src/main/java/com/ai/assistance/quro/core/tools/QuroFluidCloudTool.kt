package com.ai.assistance.quro.core.tools

import android.content.Context
import com.ai.assistance.quro.core.fluidcloud.FluidCloudManager
import org.json.JSONObject

/**
 * 流体云工具 - AI调用控制OPPO流体云
 *
 * 功能：
 * - create: 创建流体云（状态栏显示胶囊）
 * - update: 更新流体云（进度/状态）
 * - end: 结束流体云（移除胶囊）
 *
 * 使用通用entityName（TASK/NAVIGATION）避免受限履约场景
 * release 包与 debug 包行为一致
 */
class QuroFluidCloudTool : QuroTool {
    override val name = "fluid_cloud"
    override val description = "控制OPPO流体云，显示状态栏胶囊和卡片。用于AI思考中、任务进度、工具执行等场景。"
    override val parametersJson = """{
        "type": "object",
        "properties": {
            "action": {
                "type": "string",
                "description": "操作类型：create(创建)/update(更新)/end(结束)",
                "enum": ["create", "update", "end"]
            },
            "title": {
                "type": "string",
                "description": "标题（创建/更新时使用）"
            },
            "content": {
                "type": "string",
                "description": "内容（创建/更新时使用）"
            },
            "progress": {
                "type": "integer",
                "description": "进度0-100（更新时使用，显示进度条）"
            }
        },
        "required": ["action"]
    }"""

    override fun run(context: Context, arguments: String): String {
        return try {
            val args = JSONObject(arguments)
            val action = args.optString("action", "")

            when (action) {
                "create" -> createFluidCloud(context, args)
                "update" -> updateFluidCloud(context, args)
                "end" -> endFluidCloud(context)
                else -> "未知action: $action，可选值：create/update/end"
            }
        } catch (e: Exception) {
            "执行异常: ${e.message}"
        }
    }

    private fun createFluidCloud(context: Context, args: JSONObject): String {
        val title = args.optString("title", "ZorvAI")
        val content = args.optString("content", "处理中")
        val progress = args.optInt("progress", 20)

        val result = FluidCloudManager.create(context, title, content, progress)

        return if (result.isSuccess) {
            "流体云已创建"
        } else {
            "创建失败: ${result.message}"
        }
    }

    private fun updateFluidCloud(context: Context, args: JSONObject): String {
        val title = args.optString("title", "任务更新")
        val content = args.optString("content", "状态已更新")
        val progress = args.optInt("progress", 50)

        val result = FluidCloudManager.update(context, title, content, progress)

        return if (result.isSuccess) {
            "流体云已更新"
        } else {
            "更新失败: ${result.message}"
        }
    }

    private fun endFluidCloud(context: Context): String {
        val result = FluidCloudManager.finish(context)

        return if (result.isSuccess) {
            "流体云已结束"
        } else {
            "结束失败: ${result.message}"
        }
    }
}
