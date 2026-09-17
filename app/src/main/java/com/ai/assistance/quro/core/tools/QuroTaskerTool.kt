package com.ai.assistance.quro.core.tools

import android.content.Context
import android.content.Intent
import org.json.JSONArray
import org.json.JSONObject

/**
 * Tasker 双向集成：
 *  - 出站（ZorvAI → Tasker）：fire 直接点燃指定 Tasker 任务（net.dinglisch.android.tasker.ACTION_FIRE），
 *    broadcast 发送任意自定义广播（可带 extras，供其它 App / 自动化框架消费）。
 *  - 入站（Tasker → ZorvAI）：由 QuroTaskerReceiver 接收 com.ai.assistance.quro.TASKER_TRIGGER，
 *    按 workflow / workflow_id 触发本机已保存工作流（复用 workflow 引擎 WorkflowEngine.run）。
 */
class QuroTaskerTool : QuroTool {
    override val name = "tasker"
    override val description = "Tasker 双向集成：fire 点燃指定 Tasker 任务；broadcast 发送任意自定义广播（带 extras）；state 查询 Tasker 是否安装。" +
        "参数 {\"action\":\"fire|broadcast|state\"," +
        "\"task_name\":\"Tasker 任务名(fire)\"," +
        "\"intent_action\":\"广播 action(broadcast)\"," +
        "\"package\":\"目标包名(broadcast 可选)\"," +
        "\"params\":{键值对→intent extras(fire/broadcast 可选)}}。" +
        "入站：Tasker 用「发送意图」发 action=com.ai.assistance.quro.TASKER_TRIGGER 的广播，" +
        "extras 带 workflow(名称)/workflow_id(精确 id)/inputs(JSON 字符串) 即可触发本机工作流；带 prompt 则转交对话。"
    override val parametersJson = """{
        "type":"object",
        "properties":{
            "action":{"type":"string","description":"fire=点燃 Tasker 任务；broadcast=发自定义广播；state=查 Tasker 安装状态"},
            "task_name":{"type":"string","description":"fire 时要点燃的 Tasker 任务名"},
            "intent_action":{"type":"string","description":"broadcast 时的广播 action"},
            "package":{"type":"string","description":"broadcast 目标包名（可选）"},
            "params":{"type":"object","description":"键值对，平铺为 intent extras（fire 的 task 参数 / broadcast 的自定义参数）"}
        },
        "required":["action"]
    }"""

    companion object {
        const val TASKER_PKG = "net.dinglisch.android.tasker"
        const val ACTION_FIRE = "net.dinglisch.android.tasker.ACTION_FIRE"
    }

    override fun run(context: Context, arguments: String): String {
        val jo = runCatching { JSONObject(arguments) }.getOrElse { JSONObject() }
        return when (jo.optString("action", "")) {
            "state" -> state(context)
            "fire" -> fire(context, jo)
            "broadcast" -> broadcast(context, jo)
            else -> "❌ 未知 action：${jo.optString("action", "")}（支持 fire/broadcast/state）"
        }
    }

    private fun state(context: Context): String {
        val installed = runCatching { context.packageManager.getPackageInfo(TASKER_PKG, 0) }.isSuccess
        return if (installed) "✅ Tasker 已安装（$TASKER_PKG），可 fire 点燃任务，也可接收 TASKER_TRIGGER 入站广播"
        else "⚠️ 未检测到 Tasker（$TASKER_PKG），fire 将无效；入站接收仍可注册（Tasker 安装后生效）"
    }

    private fun fire(context: Context, jo: JSONObject): String {
        val taskName = jo.optString("task_name", "")
        if (taskName.isBlank()) return "❌ fire 需要 task_name"
        val intent = Intent(ACTION_FIRE).apply {
            `package` = TASKER_PKG
            putExtra("task_name", taskName)
            putParams(this, jo.optJSONObject("params"))
        }
        return runCatching {
            context.sendBroadcast(intent)
            "✅ 已向 Tasker 发送点燃任务广播：$taskName"
        }.getOrElse { "❌ 发送失败：${it.message}" }
    }

    private fun broadcast(context: Context, jo: JSONObject): String {
        val action = jo.optString("intent_action", "")
        if (action.isBlank()) return "❌ broadcast 需要 intent_action"
        val intent = Intent(action).apply {
            jo.optString("package", "").let { if (it.isNotBlank()) `package` = it }
            putParams(this, jo.optJSONObject("params"))
        }
        return runCatching {
            context.sendBroadcast(intent)
            "✅ 已发送广播：$action"
        }.getOrElse { "❌ 发送失败：${it.message}" }
    }

    private fun putParams(intent: Intent, params: JSONObject?) {
        params ?: return
        params.keys().forEach { k ->
            val v: Any? = params.opt(k)
            when (v) {
                is String -> intent.putExtra(k, v)
                is Int -> intent.putExtra(k, v)
                is Boolean -> intent.putExtra(k, v)
                is Double -> intent.putExtra(k, v)
                is Long -> intent.putExtra(k, v)
                is JSONArray -> intent.putExtra(k, v.toString())
                is JSONObject -> intent.putExtra(k, v.toString())
                else -> intent.putExtra(k, v?.toString() ?: "")
            }
        }
    }
}
