package com.ai.assistance.quro.core.fluidcloud

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build

/**
 * ColorOS 16+ 兜底：Android 16 Notification.ProgressStyle。
 * 系统自动映射为流体云胶囊，无需 ContentProvider / OPPO 白名单。
 */
object FluidCloudLiveUpdate {

    private const val CHANNEL_ID = "zorv_agent"
    private const val CHANNEL_NAME = "ZorvAI Agent"
    private const val NOTIFICATION_ID = 0x7A1F

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "ZorvAI Agent 任务状态"
            }
            val manager = context.getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    fun show(context: Context, title: String, progress: Int) {
        ensureChannel(context)

        val notification = if (Build.VERSION.SDK_INT >= 35) {
            // Android 16+ 使用 ProgressStyle
            Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setContentTitle(title)
                .setContentText("ZorvAI 执行中 $progress%")
                .setOngoing(true)
                .build()
        } else {
            // 旧版本使用普通通知
            Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setContentTitle(title)
                .setContentText("ZorvAI 执行中 $progress%")
                .setOngoing(true)
                .setProgress(100, progress, false)
                .build()
        }

        val manager = context.getSystemService(NotificationManager::class.java)
        manager?.notify(NOTIFICATION_ID, notification)
    }

    fun cancel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager?.cancel(NOTIFICATION_ID)
    }
}
