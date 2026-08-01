package com.ai.assistance.quro.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 主应用 ACI 唤醒 Receiver。
 *
 * 收到 ACTION_WAKE 广播后拉起主应用 ACI Service（让受控进程从停止态变为活跃态，
 * 使后续 bindService 能成功）。与浏览器端 QuroAciWakeReceiver 不同：主应用没有
 * 需要弹出的 Activity 壳，因此用 startService 直接拉起 ACI Service（不弹 UI）。
 */
class QuroAciWakeReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "QuroACI-Wake"
        const val ACTION_WAKE = "ai.aci.core.ACTION_WAKE"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == ACTION_WAKE) {
            try {
                val svc = Intent(context, QuroMainAciService::class.java)
                context.startService(svc)
                Log.d(TAG, "已拉起主应用 ACI Service（进程唤醒）")
            } catch (e: Throwable) {
                Log.e(TAG, "startService 失败: ${e.message}")
            }
        }
    }
}
