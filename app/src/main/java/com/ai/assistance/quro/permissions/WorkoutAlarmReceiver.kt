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
        val alarmId = intent.getStringExtra(QuroAlarmScheduler.EXTRA_ALARM_ID)
        val title = intent.getStringExtra(EXTRA_TITLE) ?: "ZorvAI 提醒"
        val content = intent.getStringExtra(EXTRA_CONTENT) ?: ""
        AlarmPermissionHelper(context).postNotification(title, content)
        // 应用内闹钟：触发后续排下一次（重复）或置失效（一次性）
        if (!alarmId.isNullOrEmpty()) {
            QuroAlarmScheduler(context).onFired(alarmId)
        }
    }

    companion object {
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_CONTENT = "extra_content"

        /**
         * 构造广播 PendingIntent（不可变，兼容 Android 12+）。
         * @param alarmId 应用内闹钟 id；非空时由 [QuroAlarmScheduler] 接管续排/失效逻辑，
         *               为空时退回「健康提醒」旧行为（仅弹通知）。requestCode 用 alarmId 哈希，
         *               保证多条闹钟的 PendingIntent 互不覆盖。
         */
        fun buildIntent(context: Context, alarmId: String, title: String, content: String): PendingIntent {
            val intent = Intent(context, WorkoutAlarmReceiver::class.java).apply {
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_CONTENT, content)
                putExtra(QuroAlarmScheduler.EXTRA_ALARM_ID, alarmId)
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
            val req = if (alarmId.isNotEmpty()) alarmId.hashCode() else title.hashCode()
            return PendingIntent.getBroadcast(context, req, intent, flags)
        }
    }
}
