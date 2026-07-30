package com.ai.assistance.quro.browser

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * ACI 唤醒 Receiver（v4 + 诊断日志）。
 *
 * 收到 ACTION_WAKE 广播后拉起主 Activity（让受控进程从停止态变为活跃态，
 * 使后续 bindService 能成功）。同时写诊断日志到 app-specific 外部目录。
 */
class QuroAciWakeReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "QuroACI-Wake"
    }

    private fun diag(ctx: Context, msg: String) {
        try {
            Log.d(TAG, msg)
            val dir = ctx.getExternalFilesDir("QuroAI_logs") ?: ctx.filesDir
            dir.mkdirs()
            val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(Date())
            File(dir, "quro_browser_diag.log").appendText("[$ts] [WakeReceiver] $msg\n")
        } catch (ignored: Throwable) {}
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: "null"
        diag(context, "收到广播: action=$action, from=${intent?.`package`}")

        if (action == "ai.aci.core.ACTION_WAKE") {
            try {
                val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
                if (launchIntent != null) {
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(launchIntent)
                    diag(context, "已拉起主 Activity ✓")
                } else {
                    diag(context, "⚠️ getLaunchIntentForPackage 返回 null！Manifest 可能缺少 LAUNCHER Activity")
                }
            } catch (e: Throwable) {
                diag(context, "启动 Activity 失败: ${e.message}")
            }
        } else {
            diag(context, "忽略非 ACTION_WAKE 广播")
        }
    }
}
