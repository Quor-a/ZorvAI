package com.ai.assistance.quro.tasker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.ai.assistance.quro.workflow.data.WorkflowRepository
import com.ai.assistance.quro.workflow.executor.WorkflowEngine
import org.json.JSONObject

/**
 * 入站 Tasker 触发器（静态注册、exported）：
 * Tasker 用「发送意图」动作发送 action=com.ai.assistance.quro.TASKER_TRIGGER 的广播，
 * extras 带 workflow(名称) / workflow_id(精确 id) / inputs(JSON 字符串) 即可点燃本机已保存工作流；
 * 带 prompt 字段则转交对话层（best-effort 本地广播 TASKER_PROMPT，由 UI 接管）。
 *
 * 关闭缺失清单 #39（Tasker 集成）：ZorvAI 既能被 Tasker 触发，也能向 Tasker 派发动作。
 */
class QuroTaskerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_IN) return
        runCatching { WorkflowEngine.init(context.applicationContext) }

        val wfName = intent.getStringExtra("workflow") ?: ""
        val wfId = intent.getStringExtra("workflow_id") ?: ""
        val inputsJson = intent.getStringExtra("inputs") ?: "{}"
        val prompt = intent.getStringExtra("prompt") ?: ""

        val resolvedId = when {
            wfId.isNotBlank() -> wfId
            wfName.isNotBlank() -> resolveByName(wfName)
            else -> ""
        }

        when {
            resolvedId.isNotBlank() -> {
                val inputs = LinkedHashMap<String, String>()
                runCatching { JSONObject(inputsJson) }.getOrElse { JSONObject() }.keys().forEach {
                    inputs[it] = (runCatching { JSONObject(inputsJson) }.getOrNull()?.optString(it) ?: "")
                }
                val runId = WorkflowEngine.run(resolvedId, inputs)
                toast(context, "Tasker 触发工作流：${wfName.ifBlank { wfId }} → run $runId")
            }
            prompt.isNotBlank() -> {
                context.sendBroadcast(Intent(ACTION_PROMPT).apply { putExtra("prompt", prompt) })
                toast(context, "Tasker 收到指令：$prompt（已转交对话）")
            }
            else -> toast(context, "Tasker 触发缺少 workflow / workflow_id / prompt")
        }
    }

    private fun resolveByName(name: String): String =
        runCatching {
            WorkflowRepository.getAll().firstOrNull { it.name == name || it.id == name }?.id ?: ""
        }.getOrDefault("")

    private fun toast(context: Context, msg: String) {
        Handler(Looper.getMainLooper()).post {
            runCatching { Toast.makeText(context.applicationContext, msg, Toast.LENGTH_LONG).show() }
        }
    }

    companion object {
        const val ACTION_IN = "com.ai.assistance.quro.TASKER_TRIGGER"
        const val ACTION_PROMPT = "com.ai.assistance.quro.TASKER_PROMPT"
    }
}
