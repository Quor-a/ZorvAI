package com.ai.assistance.quro.aciapp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * ACI 唤醒 Receiver：收到 [ACTION_WAKE] 广播后拉起主 Activity，
 * 让受控进程从停止态变为活跃态，使控制端后续的 bindService 能成功。
 */
class AciAppWakeReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "AciApp-Wake"
        private const val ACTION_WAKE = "ai.aci.core.ACTION_WAKE"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        Log.d(TAG, "收到广播: action=$action")
        if (action == ACTION_WAKE) {
            try {
                val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
                if (launch != null) {
                    launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(launch)
                    Log.d(TAG, "已拉起主 Activity ✓")
                } else {
                    Log.w(TAG, "getLaunchIntentForPackage 返回 null！Manifest 可能缺少 LAUNCHER Activity")
                }
            } catch (e: Throwable) {
                Log.e(TAG, "启动 Activity 失败: ${e.message}", e)
            }
        }
    }
}
