package com.ai.assistance.quro.permissions

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * 闹钟与提醒（Android 12+ 精确闹钟为特殊权限）。
 *
 * 链路：
 * 1. Manifest 声明 SCHEDULE_EXACT_ALARM / USE_EXACT_ALARM / POST_NOTIFICATIONS / WAKE_LOCK。
 * 2. 精确闹钟（[setExactAlarm]）需 canScheduleExactAlarms()；无权限时跳
 *    [openExactAlarmSettings] 引导用户在设置页开启（无法运行时弹窗）。
 * 3. 普通提醒（[scheduleReminder]）用非精确闹钟，无需特殊权限。
 * 4. 闹钟触发由 [WorkoutAlarmReceiver] 接收并弹出通知。
 */
class AlarmPermissionHelper(private val context: Context) {

    private val alarmManager: AlarmManager =
        context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    companion object {
        const val CHANNEL_ID = "zorv_health_reminder"
        const val CHANNEL_NAME = "健康提醒"
    }

    /** 精确闹钟授权状态（Android 12+）。 */
    fun alarmState(): PermState {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // 检查是否已授权精确闹钟权限（SCHEDULE_EXACT_ALARM 或 USE_EXACT_ALARM）
            if (alarmManager.canScheduleExactAlarms()) {
                PermState.Granted
            } else {
                // 尝试打开设置页面引导用户授权
                PermState.NeedSettings
            }
        } else {
            // Android 12 以下不需要特殊权限
            PermState.Granted
        }
    }

    /** 跳转到「精确闹钟」授权设置页（特殊权限，必须手动开启）。 */
    fun openExactAlarmSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }

    /**
     * 设置精确闹钟（运动开始提醒等）。
     * 需要 SCHEDULE_EXACT_ALARM 且 canScheduleExactAlarms()；无权限则引导去设置页。
     */
    fun setExactAlarm(triggerAtMillis: Long, title: String, content: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
            openExactAlarmSettings()
            return
        }
        val pi = WorkoutAlarmReceiver.buildIntent(context, title, content)
        alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi)
    }

    /** 普通提醒用非精确闹钟（窗口对齐，不需特殊权限）。 */
    fun scheduleReminder(triggerAtMillis: Long, title: String, content: String) {
        val pi = WorkoutAlarmReceiver.buildIntent(context, title, content)
        alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi)
    }

    /** 确保通知渠道存在（Android 8+）。 */
    fun ensureReminderChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT
            )
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    /** 立即发一条通知（闹钟触发时由 [WorkoutAlarmReceiver] 调用）。 */
    fun postNotification(title: String, content: String) {
        ensureReminderChannel()
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(content)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(System.currentTimeMillis().toInt(), notification)
    }
}
