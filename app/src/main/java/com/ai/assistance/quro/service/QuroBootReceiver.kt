package com.ai.assistance.quro.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * 开机广播接收器：
 * 设备重启后检查无障碍服务是否启用，如果未启用则发送通知提醒用户。
 *
 * 注册方式：AndroidManifest.xml 中添加
 * <receiver android:name=".service.QuroBootReceiver" android:enabled="true" android:exported="true">
 *     <intent-filter>
 *         <action android:name="android.intent.action.BOOT_COMPLETED" />
 *     </intent-filter>
 * </receiver>
 */
class QuroBootReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "QuroBoot"
        private const val CHANNEL_ID = "quro_a11y_channel"
        private const val NOTIFICATION_ID = 9528
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        Log.i(TAG, "设备已重启，检查无障碍服务状态")

        // 延迟检查（等待系统启动完成）
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            checkAccessibilityService(context)
        }, 5000) // 延迟5秒
    }

    private fun checkAccessibilityService(context: Context) {
        if (!QuroAccessibilityService.isServiceEnabled(context)) {
            Log.w(TAG, "无障碍服务未启用，发送通知提醒用户")
            sendNotification(context)
        } else {
            Log.i(TAG, "无障碍服务已启用")
        }
    }

    private fun sendNotification(context: Context) {
        try {
            // 创建通知渠道
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "无障碍服务",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "提醒用户开启无障碍服务"
                }
                val nm = context.getSystemService(NotificationManager::class.java)
                nm.createNotificationChannel(channel)
            }

            // 点击通知打开无障碍设置
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            val pendingIntent = PendingIntent.getActivity(
                context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setContentTitle("⚠️ 无障碍服务未开启")
                .setContentText("Zorv AI 需要无障碍服务才能正常工作，点击开启")
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build()

            val nm = context.getSystemService(NotificationManager::class.java)
            nm.notify(NOTIFICATION_ID, notification)
            Log.d(TAG, "通知已发送")
        } catch (e: Exception) {
            Log.e(TAG, "发送通知失败", e)
        }
    }
}
