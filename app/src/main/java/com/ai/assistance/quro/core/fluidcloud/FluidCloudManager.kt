package com.ai.assistance.quro.core.fluidcloud

import android.content.ContentProviderClient
import android.content.Context
import android.os.Bundle
import org.json.JSONObject
import java.util.UUID

/**
 * OPPO 流体云端侧管理器
 * 不上架最小可行路径：ContentProviderClient + 意图共享
 *
 * 使用条件：
 * 1. ColorOS 14+（推荐16+）
 * 2. 设置 → 通知与控制中心 → 流体云 总开关开
 * 3. 自建 Demo App（自己包名，debug 签名）
 *
 * 支持的操作：
 * - ActionStatus 0: 创建流体云（胶囊/卡片）
 * - ActionStatus 1: 更新流体云
 * - ActionStatus 2: 结束流体云
 */
class FluidCloudManager(private val context: Context) {

    companion object {
        // 流体云 ContentProvider authority
        private const val AUTHORITY = "IntelligentIntent"
        private const val METHOD_SHARE_INTENT = "shareIntent"

        // 意图名称模板
        const val INTENT_NAME_TASK = "ZorvAI.Task"
        const val INTENT_NAME_NAVIGATION = "ZorvAI.Navigation"
        const val INTENT_NAME_TIMER = "ZorvAI.Timer"
        const val INTENT_NAME_PROGRESS = "ZorvAI.Progress"

        // 垂域名称（entityName）- 通用模板避免受限场景
        const val ENTITY_NAME_TASK = "TASK"
        const val ENTITY_NAME_NAVIGATION = "NAVIGATION"

        // ActionStatus
        const val ACTION_CREATE = 0
        const val ACTION_UPDATE = 1
        const val ACTION_END = 2
    }

    /**
     * 发送意图共享数据到流体云
     * @param intentData IntentData JSON字符串
     * @return ShareResult 或 null（失败时）
     */
    fun shareIntent(intentData: String): ShareResult? {
        return try {
            val client: ContentProviderClient? =
                context.contentResolver.acquireUnstableContentProviderClient(AUTHORITY)

            if (client == null) {
                return ShareResult(code = -1, message = "无法获取ContentProviderClient，请检查流体云开关")
            }

            try {
                val extras = Bundle().apply {
                    putString("intentData", intentData)
                }

                val resultBundle = client.call(METHOD_SHARE_INTENT, null, extras)
                val resultJson = resultBundle?.getString("result")

                if (resultJson != null) {
                    parseShareResult(resultJson)
                } else {
                    ShareResult(code = -1, message = "返回结果为空")
                }
            } finally {
                client.close()
            }
        } catch (e: Exception) {
            ShareResult(code = -1, message = "调用异常: ${e.message}")
        }
    }

    /**
     * 创建流体云（显示胶囊）
     * @param intentName 意图名称
     * @param entityName 垂域名称（TASK/NAVIGATION等通用模板）
     * @param entityId 实体ID（订单号等）
     * @param capsuleLeftText 胶囊左侧文字
     * @param capsuleRightText 胶囊右侧文字
     * @param primaryTitle 卡片主标题
     * @param primaryContent 卡片主内容
     */
    fun createFluidCloud(
        intentName: String = INTENT_NAME_TASK,
        entityName: String = ENTITY_NAME_TASK,
        entityId: String = UUID.randomUUID().toString(),
        capsuleLeftText: String = "ZorvAI",
        capsuleRightText: String = "进行中",
        primaryTitle: String = "任务进行中",
        primaryContent: String = "点击查看详情"
    ): ShareResult? {
        val intentData = buildIntentData(
            actionStatus = ACTION_CREATE,
            intentName = intentName,
            entityName = entityName,
            entityId = entityId,
            capsuleLeftText = capsuleLeftText,
            capsuleRightText = capsuleRightText,
            primaryTitle = primaryTitle,
            primaryContent = primaryContent
        )
        return shareIntent(intentData)
    }

    /**
     * 更新流体云
     */
    fun updateFluidCloud(
        intentName: String = INTENT_NAME_TASK,
        entityName: String = ENTITY_NAME_TASK,
        entityId: String,
        capsuleRightText: String = "更新中",
        primaryTitle: String = "任务更新",
        primaryContent: String = "状态已更新",
        progress: Int? = null
    ): ShareResult? {
        val intentData = buildIntentData(
            actionStatus = ACTION_UPDATE,
            intentName = intentName,
            entityName = entityName,
            entityId = entityId,
            capsuleRightText = capsuleRightText,
            primaryTitle = primaryTitle,
            primaryContent = primaryContent,
            progress = progress
        )
        return shareIntent(intentData)
    }

    /**
     * 结束流体云（移除胶囊）
     */
    fun endFluidCloud(
        intentName: String = INTENT_NAME_TASK,
        entityName: String = ENTITY_NAME_TASK,
        entityId: String
    ): ShareResult? {
        val intentData = buildIntentData(
            actionStatus = ACTION_END,
            intentName = intentName,
            entityName = entityName,
            entityId = entityId
        )
        return shareIntent(intentData)
    }

    /**
     * 构建IntentData JSON
     */
    private fun buildIntentData(
        actionStatus: Int,
        intentName: String,
        entityName: String,
        entityId: String,
        capsuleLeftText: String = "",
        capsuleRightText: String = "",
        primaryTitle: String = "",
        primaryContent: String = "",
        progress: Int? = null
    ): String {
        val timestamp = System.currentTimeMillis()
        val identifier = UUID.randomUUID().toString()

        val intentData = JSONObject().apply {
            put("intentName", intentName)
            put("identifier", identifier)
            put("timestamp", timestamp)

            // serviceId - 卡片ID
            put("serviceId", JSONObject().apply {
                put("launcher", "999800001")
                put("fluidCloud", "999900001")
            })

            // intentAction - 动作
            put("intentAction", JSONObject().apply {
                put("actionStatus", actionStatus)
            })

            // intentEntity - 实体信息
            put("intentEntity", JSONObject().apply {
                put("entityName", entityName)
                put("entityId", entityId)

                // capsule - 胶囊信息
                if (capsuleLeftText.isNotEmpty() || capsuleRightText.isNotEmpty()) {
                    put("capsule", JSONObject().apply {
                        if (capsuleLeftText.isNotEmpty()) put("leftText", capsuleLeftText)
                        if (capsuleRightText.isNotEmpty()) put("rightText", capsuleRightText)
                    })
                }

                // primary - 卡片主要信息
                if (primaryTitle.isNotEmpty() || primaryContent.isNotEmpty()) {
                    put("primary", JSONObject().apply {
                        if (primaryTitle.isNotEmpty()) {
                            put("title", org.json.JSONArray().apply {
                                put(JSONObject().apply {
                                    put("text", primaryTitle)
                                    put("color", "#FF6B35")
                                })
                            })
                        }
                        if (primaryContent.isNotEmpty()) put("content", primaryContent)
                    })
                }

                // secondaryData - 扩展信息（可选，用于进度等）
                if (progress != null) {
                    put("secondaryData", JSONObject().apply {
                        put("type", "PROGRESS")
                        put("progress", progress)
                        put("style", "inside")
                    })
                }
            })
        }

        return intentData.toString()
    }

    /**
     * 解析ShareResult
     */
    private fun parseShareResult(resultJson: String): ShareResult {
        return try {
            val json = JSONObject(resultJson)
            ShareResult(
                code = json.getInt("code"),
                message = json.getString("message"),
                data = json.optString("data", null)
            )
        } catch (e: Exception) {
            ShareResult(code = -1, message = "解析结果失败: ${e.message}")
        }
    }

    /**
     * ShareResult 数据类
     */
    data class ShareResult(
        val code: Int,
        val message: String,
        val data: String? = null
    ) {
        val isSuccess: Boolean get() = code == 0
    }
}
