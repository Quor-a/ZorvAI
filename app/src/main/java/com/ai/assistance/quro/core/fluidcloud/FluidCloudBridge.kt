package com.ai.assistance.quro.core.fluidcloud
import com.ai.assistance.quro.R
import com.ai.assistance.quro.util.qstr

import android.content.ContentProviderClient
import android.content.Context
import android.os.Bundle
import org.json.JSONObject

/**
 * ColorOS 流体云端侧接入桥（Intent Sharing，不上架、release 可用）。
 * 协议：authority=IntelligentIntent，method=shareIntent。
 *
 * 关键字段：
 * - actionStatus: 0=创建, 1=更新, 2=结束
 * - entityName: TASK（通用任务类别，避免受限履约场景）
 * - capsule: 胶囊数据（状态栏显示）
 * - primary: 卡片主要信息
 */
object FluidCloudBridge {

    private const val AUTHORITY = "IntelligentIntent"
    private const val METHOD = "shareIntent"
    private const val TAG = "FluidCloud"

    /**
     * 当前活动任务 id。修复「胶囊不更新 / 关不掉」的确定性 bug：
     * 旧实现 [generateId] 每次调用都用 System.currentTimeMillis() 重新生成，
     * 导致 create / update / finish 三者拿到**不同**的 identifier/entityId，
     * update 作用不到最初 create 的胶囊、finish 也关不掉它 —— 每次都新建一个胶囊。
     * 现在 create 固定 id，update/finish 复用同一个，finish 后清空。
     */
    @Volatile
    private var activeTaskId: String? = null

    // 任务唯一标识：create 时生成并缓存；update/finish 复用缓存值，保证作用到同一胶囊。
    private fun currentTaskId(): String {
        return activeTaskId ?: "zorv-ai-task-${System.currentTimeMillis()}".also { activeTaskId = it }
    }

    fun create(context: Context, title: String, step: String, progress: Int): Boolean {
        activeTaskId = "zorv-ai-task-${System.currentTimeMillis()}"
        return push(context, build(0, title, step, progress))
    }

    fun update(context: Context, title: String, step: String, progress: Int): Boolean {
        return push(context, build(1, title, step, progress))
    }

    fun finish(context: Context): Boolean {
        val id = currentTaskId()
        val json = JSONObject().apply {
            put("intentName", "ZorvAI.AgentTask")
            put("identifier", id)
            put("timestamp", System.currentTimeMillis())
            put("intentAction", JSONObject().apply {
                put("actionStatus", 2)  // 2 = 结束
            })
            put("intentEntity", JSONObject().apply {
                put("entityName", "TASK")
                put("entityId", id)
            })
        }.toString()
        activeTaskId = null
        return push(context, json)
    }

    private fun build(action: Int, title: String, step: String, progress: Int): String {
        val id = currentTaskId()
        return JSONObject().apply {
            put("intentName", "ZorvAI.AgentTask")
            put("identifier", id)
            put("timestamp", System.currentTimeMillis())
            put("intentAction", JSONObject().apply {
                put("actionStatus", action)  // 0=创建, 1=更新
            })
            put("intentEntity", JSONObject().apply {
                put("entityName", "TASK")
                put("entityId", id)
                if (action != 2) {
                    // 里程碑状态
                    put("milestone", JSONObject().apply {
                        put("code", 10)
                        put("text", "running")
                    })
                    // 胶囊数据（状态栏显示）
                    put("capsule", JSONObject().apply {
                        put("leftText", title)
                        put("rightText", "$step $progress%")
                    })
                    // 卡片主要信息
                    put("primary", JSONObject().apply {
                        put("title", title)
                        put("content", "$step · $progress%")
                        put("clickAction", "com.ai.assistance.quro://agent/task")
                    })
                    // 进度条
                    put("secondaryData", JSONObject().apply {
                        put("type", "PROGRESS")
                        put("progress", progress.coerceIn(0, 100))
                        put("style", "inside")
                        put("nodeLabels", arrayOf("开始", "执行中", "完成"))
                    })
                }
            })
        }.toString()
    }

    private fun push(context: Context, intentData: String): Boolean {
        var client: ContentProviderClient? = null
        try {
            client = context.contentResolver.acquireUnstableContentProviderClient(AUTHORITY)
            if (client == null) {
                android.util.Log.w(TAG, "Provider 不可用（系统不支持/流体云关闭）")
                return false
            }
            val bundle = Bundle().apply { putString("intentData", intentData) }
            val result = client.call(METHOD, null, bundle)
            val resultStr = result?.getString("result") ?: "{}"
            val code = JSONObject(resultStr).optInt("code", -1)
            android.util.Log.d(TAG, "push result: code=$code, data=${intentData.take(100)}")
            return code == 0
        } catch (e: Exception) {
            android.util.Log.w(TAG, "push 异常: ${e.message}")
            return false
        } finally {
            client?.close()
        }
    }
}