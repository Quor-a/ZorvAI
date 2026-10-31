package com.ai.assistance.quro.termux.util

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build

/**
 * 通知工具（对齐 Termux NotificationUtils）。
 */
object NotificationUtils {

    @JvmStatic
    fun setupNotificationChannel(
        context: Context,
        channelId: String,
        channelName: String,
        channelDescription: String,
        importance: Int
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(channelId, channelName, importance).apply {
            description = channelDescription
        }
        nm.createNotificationChannel(channel)
    }

    @JvmStatic
    fun notify(
        context: Context,
        notificationId: Int,
        notification: Notification
    ) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        try {
            nm.notify(notificationId, notification)
        } catch (e: Exception) {
            Logger.logStackTraceWithMessage("NotificationUtils", "notify failed", e)
        }
    }
}