package com.ai.assistance.quro.core.fluidcloud

import android.content.ContentProviderClient
import android.content.Context
import android.os.Bundle
import android.util.Log
import org.json.JSONObject

/**
 * OPPO 流体云端侧管理器（官方标准写法）
 *
 * 不上架最小可行路径：ContentProviderClient + 意图共享
 * release 包与 debug 包行为一致，只要包名一致、流体云开关开启、场景非敏感履约
 *
 * 使用条件：
 * 1. ColorOS 14+（推荐16+）
 * 2. 设置 → 通知与控制中心 → 流体云 总开关开
 * 3. 包名一致、签名有效
 *
 * 支持的操作：
 * - ActionStatus 0: 创建流体云（胶囊/卡片）
 * - ActionStatus 1: 更新流体云
 * - ActionStatus 2: 结束流体云
 */
object FluidCloudManager {
    private const val TAG = "FluidCloud"
    private const val AUTHORITY = "IntelligentIntent"
    private const val METHOD = "shareIntent"

    // 返回码
    const val CODE_SUCCESS = 0
    const val CODE_NO_PERMISSION = 10101001
    const val CODE_PERMISSION_EXPIRED = 10101002
    const val CODE_PARAM_ERROR = 10102001
    const val CODE_DATA_TOO_LARGE = 10103001
    const val CODE_FREQ_LIMIT = 10103002
    const val CODE_SWITCH_OFF = 10103003
    const val CODE_ENTITY_LIMIT = 10103004
    const val CODE_VERSION_NOT_SUPPORT = 10103005

    /**
     * 创建流体云（显示胶囊）
     * @param context Context
     * @param title 标题
     * @param content 内容
     * @param progress 进度 0-100（可选）
     */
    fun create(
        context: Context,
        title: String = "ZorvAI",
        content: String = "处理中",
        progress: Int = 20
    ): FluidCloudResult {
        return send(context, build(0, title, content, progress))
    }

    /**
     * 更新流体云
     * @param context Context
     * @param title 标题
     * @param content 内容
     * @param progress 进度 0-100
     */
    fun update(
        context: Context,
        title: String = "任务更新",
        content: String = "状态已更新",
        progress: Int = 50
    ): FluidCloudResult {
        return send(context, build(1, title, content, progress))
    }

    /**
     * 结束流体云（移除胶囊）
     * @param context Context
     */
    fun finish(context: Context): FluidCloudResult {
        return send(context, build(2, "", "", 100))
    }

    /**
     * 构建 IntentData JSON（官方标准格式）
     */
    private fun build(
        actionStatus: Int,
        title: String,
        content: String,
        progress: Int
    ): String {
        val ts = System.currentTimeMillis()
        val identifier = "zorvai_${ts}_${(Math.random() * 10000).toInt()}"

        return JSONObject().apply {
            put("intentName", "ZorvAI.Task")
            put("identifier", identifier)
            put("timestamp", ts)
            put("serviceId", JSONObject().apply {
                put("launcher", "999800001")
                put("fluidCloud", "999900001")
            })
            put("intentAction", JSONObject().put("actionStatus", actionStatus))
            put("intentEntity", JSONObject().apply {
                put("entityName", "TASK") // 使用通用模板，避免受限履约场景
                put("entityId", "zorvai_task_$ts")

                if (actionStatus != 2) { // 非结束操作
                    put("milestone", JSONObject().apply {
                        put("code", 10)
                        put("text", "running")
                    })
                    put("capsule", JSONObject().apply {
                        put("leftText", "ZorvAI")
                        put("rightText", if (progress in 0..100) "$progress%" else title)
                    })
                    put("primary", JSONObject().apply {
                        put("title", org.json.JSONArray().apply {
                            put(JSONObject().apply {
                                put("text", title.ifEmpty { "ZorvAI" })
                                put("color", "#FFFFFF")
                                put("darkColor", "#000000")
                            })
                        })
                        put("content", content.ifEmpty { "处理中" })
                    })
                    put("secondaryData", JSONObject().apply {
                        put("type", "PROGRESS")
                        put("progress", progress)
                        put("style", "inside")
                    })
                }
            })
        }.toString()
    }

    /**
     * 端侧调用（官方标准写法）
     */
    private fun send(context: Context, intentData: String): FluidCloudResult {
        var client: ContentProviderClient? = null
        return try {
            client = context.contentResolver.acquireUnstableContentProviderClient(AUTHORITY)
            if (client == null) {
                Log.w(TAG, "未获取到 Provider（系统不支持/流体云关闭）")
                return FluidCloudResult(
                    code = -1,
                    message = "未获取到 Provider（系统不支持/流体云关闭）"
                )
            }

            val bundle = Bundle().apply {
                putString("intentData", intentData)
            }
            val result = client.call(METHOD, null, bundle)
            val shareResult = result?.getString("result")
            Log.d(TAG, "FluidCloud result = $shareResult")

            val code = JSONObject(shareResult ?: "{}").optInt("code", -1)
            val message = JSONObject(shareResult ?: "{}").optString("message", "")

            if (code == CODE_SUCCESS) {
                FluidCloudResult(code = code, message = "成功")
            } else {
                val errorMsg = getErrorMessage(code)
                Log.w(TAG, "FluidCloud 调用失败: $errorMsg")
                FluidCloudResult(code = code, message = errorMsg)
            }
        } catch (e: Exception) {
            Log.e(TAG, "FluidCloud 调用异常", e)
            FluidCloudResult(code = -1, message = "调用异常: ${e.message}")
        } finally {
            client?.close()
        }
    }

    /**
     * 获取错误信息
     */
    private fun getErrorMessage(code: Int): String {
        return when (code) {
            CODE_NO_PERMISSION -> "应用无意图共享权限（检查流体云开关/是否被拦截）"
            CODE_PERMISSION_EXPIRED -> "意图共享权限已过期"
            CODE_PARAM_ERROR -> "意图共享参数错误"
            CODE_DATA_TOO_LARGE -> "意图共享数据大小超过限制"
            CODE_FREQ_LIMIT -> "意图共享频次超过限制"
            CODE_SWITCH_OFF -> "流体云总开关已关闭"
            CODE_ENTITY_LIMIT -> "意图共享实体数量超限"
            CODE_VERSION_NOT_SUPPORT -> "系统版本不支持（需 ColorOS 14+）"
            else -> "调用失败 code=$code"
        }
    }

    /**
     * FluidCloudResult 数据类
     */
    data class FluidCloudResult(
        val code: Int,
        val message: String
    ) {
        val isSuccess: Boolean get() = code == CODE_SUCCESS
    }
}
