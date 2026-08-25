package com.ai.assistance.quro.workflow.trigger

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 系统事件接收器（静态注册）：开机 / 用户在场 / 亮灭屏 / 网络变化 / 应用更新。
 * 收到事件后交给 TriggerEngine 武装 time 触发器并触发匹配的 event 工作流。
 */
class WorkflowTriggerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        TriggerEngine.ensureInit(context.applicationContext)
        TriggerEngine.onSystemEvent(action)
    }
}
