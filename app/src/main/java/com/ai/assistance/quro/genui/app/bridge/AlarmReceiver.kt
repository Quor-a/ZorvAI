package com.ai.assistance.quro.genui.app.bridge

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat

/**
 * 闹钟广播接收器（对应上游 GenUI 的 AlarmReceiver）。
 *
 * alarm_set 工具用 AlarmManager 注册精确闹钟，到点后系统广播到这里，
 * 由本接收器弹出一条系统通知，把 label 作为提醒内容呈现给用户。
 */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val label = intent.getStringExtra("label").orEmpty().ifBlank { "GenUI 提醒" }
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "genui_alarm"
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(channelId, "GenUI 闹钟", NotificationManager.IMPORTANCE_HIGH)
            nm.createNotificationChannel(ch)
        }
        val n = NotificationCompat.Builder(context, channelId)
            .setContentTitle("⏰ 闹钟")
            .setContentText(label)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        nm.notify(("alarm_" + label).hashCode(), n)
    }
}
