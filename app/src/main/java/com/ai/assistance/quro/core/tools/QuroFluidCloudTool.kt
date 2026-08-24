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
            "entityId": {
                "type": "string",
                "description": "实体ID（更新/结束时必填，创建时返回）"
            },
            "progress": {
                "type": "integer",
                "description": "进度0-100（更新时使用，显示进度条）"
            }
        },
        "required": ["action"]
    }"""

    private lateinit var fluidCloudManager: FluidCloudManager

    private fun ensureManager(context: Context) {
        if (!::fluidCloudManager.isInitialized) {
            fluidCloudManager = FluidCloudManager(context)
        }
    }

    override fun run(context: Context, arguments: String): String {
        ensureManager(context)

        return try {
            val args = JSONObject(arguments)
            val action = args.optString("action", "")

            when (action) {
                "create" -> createFluidCloud(args)
                "update" -> updateFluidCloud(args)
                "end" -> endFluidCloud(args)
                else -> "未知action: $action，可选值：create/update/end"
            }
        } catch (e: Exception) {
            "执行异常: ${e.message}"
        }
    }

    private fun createFluidCloud(args: JSONObject): String {
        val title = args.optString("title", "ZorvAI")
        val content = args.optString("content", "处理中")
        val entityId = "zorvai_${System.currentTimeMillis()}"

        val result = fluidCloudManager.createFluidCloud(
            capsuleLeftText = "ZorvAI",
            capsuleRightText = title,
            primaryTitle = title,
            primaryContent = content
        )

        return if (result?.isSuccess == true) {
            "流体云已创建: $entityId"
        } else {
            "创建失败: ${result?.message}"
        }
    }

    private fun updateFluidCloud(args: JSONObject): String {
        val entityId = args.optString("entityId", "")
        if (entityId.isEmpty()) {
            return "缺少entityId参数"
        }

        val progress = if (args.has("progress")) args.optInt("progress") else null
        val title = args.optString("title", "任务更新")
        val content = args.optString("content", "状态已更新")

        val result = fluidCloudManager.updateFluidCloud(
            entityId = entityId,
            capsuleRightText = if (progress != null) "${progress}%" else title,
            primaryTitle = title,
            primaryContent = content,
            progress = progress
        )

        return if (result?.isSuccess == true) {
            "流体云已更新"
        } else {
            "更新失败: ${result?.message}"
        }
    }

    private fun endFluidCloud(args: JSONObject): String {
        val entityId = args.optString("entityId", "")
        if (entityId.isEmpty()) {
            return "缺少entityId参数"
        }

        val result = fluidCloudManager.endFluidCloud(entityId = entityId)

        return if (result?.isSuccess == true) {
            "流体云已结束"
        } else {
            "结束失败: ${result?.message}"
        }
    }
}
