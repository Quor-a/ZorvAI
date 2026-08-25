package com.ai.assistance.quro.workflow.trigger

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.ai.assistance.quro.workflow.executor.WorkflowEngine

/**
 * AlarmManager 闹钟回调：触发对应的工作流，并在其仍是定时触发器时重新武装。
 */
class WorkflowAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val wfId = intent.getStringExtra("wf_id") ?: return
        TriggerEngine.ensureInit(context.applicationContext)
        WorkflowEngine.run(wfId)
        TriggerEngine.armAfterFire(wfId)
    }
}
