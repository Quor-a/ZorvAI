package com.ai.assistance.quro.permissions

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * 闹钟触发的广播接收器（内部，不导出）。
 *
 * [AlarmPermissionHelper.setExactAlarm] / [AlarmPermissionHelper.scheduleReminder]
 * 通过 [buildIntent] 把提醒标题/内容塞进 PendingIntent；闹钟到点后系统广播到此 Receiver，
 * 由它弹出通知。Receiver 收到的 Context 不是 Activity，因此 [AlarmPermissionHelper]
 * 以 [Context] 构造（而非 AppCompatActivity）。
 */
class WorkoutAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val title = intent.getStringExtra(EXTRA_TITLE) ?: "ZorvAI 提醒"
        val content = intent.getStringExtra(EXTRA_CONTENT) ?: ""
        AlarmPermissionHelper(context).postNotification(title, content)
    }

    companion object {
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_CONTENT = "extra_content"

        /** 构造携带标题/内容的广播 PendingIntent（不可变，兼容 Android 12+）。 */
        fun buildIntent(context: Context, title: String, content: String): PendingIntent {
            val intent = Intent(context, WorkoutAlarmReceiver::class.java).apply {
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_CONTENT, content)
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
            return PendingIntent.getBroadcast(context, title.hashCode(), intent, flags)
        }
    }
}
