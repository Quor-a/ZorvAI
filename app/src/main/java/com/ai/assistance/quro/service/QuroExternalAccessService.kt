package com.ai.assistance.quro.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import com.ai.assistance.quro.core.external.QuroExternalServer

/**
 * 外部连接前台服务（独立于 MCP 服务）。
 *
 * 职责单一：托管 [QuroExternalServer]，把本应用作为 OpenAI 兼容模型服务暴露到
 * **0.0.0.0**（同一 Wi-Fi 下的电脑/设备可直连），端口随机分配并持久化。
 * 与 MCP 服务（QuroMcpService，127.0.0.1 工具暴露）互不依赖、可分别开关。
 */
class QuroExternalAccessService : Service() {

    private var server: QuroExternalServer? = null

    override fun onBind(intent: Intent?) = null

    override fun onCreate() {
        super.onCreate()
        try {
            val s = QuroExternalServer(applicationContext)
            s.start()
            server = s
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIF_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NOTIF_ID, buildNotification())
            }
        } catch (e: Throwable) {
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        runCatching { server?.stop() }
        server = null
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "zorv AI 外部连接", NotificationManager.IMPORTANCE_LOW))
        val port = server?.port ?: 0
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("zorv AI · 外部连接")
            .setContentText("OpenAI 兼容端点运行中（端口 $port，局域网可访问）")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val NOTIF_ID = 8804
        private const val CHANNEL_ID = "quro_external_access"
        const val ACTION_STOP = "quro.external.STOP"

        fun start(ctx: Context) {
            runCatching { ctx.startForegroundService(Intent(ctx, QuroExternalAccessService::class.java)) }
        }

        fun stop(ctx: Context) {
            runCatching { ctx.startService(Intent(ctx, QuroExternalAccessService::class.java).setAction(ACTION_STOP)) }
            runCatching { ctx.stopService(Intent(ctx, QuroExternalAccessService::class.java)) }
        }
    }
}
