package com.ai.assistance.quro.core.fluidcloud

import android.content.ContentProviderClient
import android.content.Context
import android.os.Bundle
import org.json.JSONObject

/**
 * ColorOS 流体云端侧接入桥（Intent Sharing，不上架、release 可用）。
 * 协议：authority=IntelligentIntent，actionStatus 0/1/2 = 创建/更新/结束。
 */
object FluidCloudBridge {

    private const val AUTHORITY = "IntelligentIntent"
    private const val METHOD = "shareIntent"

    // 任务唯一标识，创建/更新/结束必须保持一致
    private const val ID = "zorv-ai-agent-task"

    fun create(context: Context, title: String, step: String, progress: Int) =
        push(context, build(0, title, step, progress))

    fun update(context: Context, title: String, step: String, progress: Int) =
        push(context, build(1, title, step, progress))

    fun finish(context: Context) {
        val json = JSONObject().apply {
            put("intentName", "ZorvAI.AgentTask")
            put("identifier", ID)
            put("timestamp", System.currentTimeMillis())
            put("serviceId", JSONObject().put("launcher", "999800001").put("fluidCloud", "999900001"))
            put("intentAction", JSONObject().put("actionStatus", 2))
            put("intentEntity", JSONObject().put("entityName", "TASK").put("entityId", ID))
        }.toString()
        push(context, json)
    }

    private fun build(action: Int, title: String, step: String, progress: Int): String =
        JSONObject().apply {
            put("intentName", "ZorvAI.AgentTask")
            put("identifier", ID)
            put("timestamp", System.currentTimeMillis())
            put("serviceId", JSONObject().put("launcher", "999800001").put("fluidCloud", "999900001"))
            put("intentAction", JSONObject().put("actionStatus", action))
            put("intentEntity", JSONObject().apply {
                put("entityName", "TASK")
                put("entityId", ID)
                if (action != 2) {
                    put("milestone", JSONObject().put("code", 10).put("text", "running"))
                    put("capsule", JSONObject().put("rightText", "$step $progress%"))
                    put("primary", JSONObject().apply {
                        put("title", arrayOf(JSONObject().put("text", title)
                            .put("color", "#FFFFFF").put("darkColor", "#000000")))
                        put("content", "$step · $progress%")
                        put("clickAction", "com.ai.assistance.quro://agent/task")
                    })
                    put("secondaryData", JSONObject().apply {
                        put("type", "PROGRESS")
                        put("progress", progress.coerceIn(0, 100))
                        put("style", "inside")
                        put("nodeLabels", arrayOf("开始", "执行中", "完成"))
                    })
                }
            })
        }.toString()

    private fun push(context: Context, intentData: String) {
        var client: ContentProviderClient? = null
        try {
            client = context.contentResolver.acquireUnstableContentProviderClient(AUTHORITY)
            if (client == null) {
                android.util.Log.w("FluidCloud", "未获取到 Provider（系统不支持/流体云关闭）")
                return
            }
            val bundle = Bundle().apply { putString("intentData", intentData) }
            val result = client.call(METHOD, null, bundle)
            val code = JSONObject(result?.getString("result") ?: "{}").optInt("code", -1)
            if (code != 0) android.util.Log.w("FluidCloud", "code=$code")
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            client?.close()
        }
    }
}
