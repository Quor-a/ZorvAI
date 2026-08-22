package com.ai.assistance.quro.core.tools

import android.content.Context
import android.content.Intent
import android.net.Uri
import org.json.JSONObject

/**
 * Intent / 广播工具（execute_intent / send_broadcast）。
 * 仅构建并派发常见隐式 Intent（如打开网页、启动设置、发广播），不触碰需要系统权限的动作。
 */
class ExecuteIntentTool : QuroTool {
    override val name = "execute_intent"
    override val description = "构建一个隐式 Intent 并启动（startActivity）。参数 {\"action\":\"android.intent.action.VIEW\",\"data\":\"https://...\",\"type\":\"可选 MIME\",\"extra\":{\"key\":\"value\"}可选}。注意：部分 action 需对应 App 支持。"
    override val parametersJson = """{"type":"object","properties":{"action":{"type":"string","description":"Intent action，如 android.intent.action.VIEW"},"data":{"type":"string","description":"可选 Uri 字符串"},"type":{"type":"string","description":"可选 MIME 类型"},"extra":{"type":"string","description":"可选 JSON 对象字符串，键值对作为 Intent extra"}},"required":["action"]}"""
    override fun run(context: Context, arguments: String): String {
        val jo = JSONObject(arguments)
        val action = jo.optString("action", "")
        if (action.isEmpty()) return "缺少 action 参数"
        val data = jo.optString("data", "")
        val type = jo.optString("type", "")
        val extra = runCatching {
            jo.optString("extra", "").let { if (it.isBlank()) JSONObject() else JSONObject(it) }
        }.getOrElse { return "extra 不是合法 JSON 对象" }
        return try {
            val intent = Intent(action)
            if (data.isNotEmpty()) intent.data = Uri.parse(data)
            if (type.isNotEmpty()) intent.type = type
            extra.keys().forEach { k -> intent.putExtra(k, extra.optString(k)) }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            "已启动 Intent: $action"
        } catch (e: Exception) { "启动失败: ${e.message}" }
    }
}

class SendBroadcastTool : QuroTool {
    override val name = "send_broadcast"
    override val description = "发送一个隐式广播。参数 {\"action\":\"...\",\"extra\":{\"key\":\"value\"}可选}。"
    override val parametersJson = """{"type":"object","properties":{"action":{"type":"string","description":"广播 action"},"extra":{"type":"string","description":"可选 JSON 对象字符串"}},"required":["action"]}"""
    override fun run(context: Context, arguments: String): String {
        val jo = JSONObject(arguments)
        val action = jo.optString("action", "")
        if (action.isEmpty()) return "缺少 action 参数"
        val extra = runCatching {
            jo.optString("extra", "").let { if (it.isBlank()) JSONObject() else JSONObject(it) }
        }.getOrElse { return "extra 不是合法 JSON 对象" }
        return try {
            val intent = Intent(action)
            extra.keys().forEach { k -> intent.putExtra(k, extra.optString(k)) }
            context.sendBroadcast(intent)
            "已发送广播: $action"
        } catch (e: Exception) { "广播失败: ${e.message}" }
    }
}
