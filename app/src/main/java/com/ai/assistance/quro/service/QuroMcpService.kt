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
import com.ai.assistance.quro.core.mcp.QuroMcpHttpServer

/**
 * 本地 MCP Server 前台服务。
 * 仅监听 127.0.0.1，外部网络不可达；端口随机分配并持久化，供设置页展示连接地址。
 * 工具调用经 [QuroMcpHttpServer] → 原创 [com.ai.assistance.quro.core.tools.QuroTool] 引擎，
 * 与 AI 对话内工具 100% 同源。
 */
class QuroMcpService : Service() {

    private var server: QuroMcpHttpServer? = null

    override fun onBind(intent: Intent?) = null

    override fun onCreate() {
        super.onCreate()
        try {
            val s = QuroMcpHttpServer(applicationContext)
            val port = s.start()
            server = s
            prefs(applicationContext).edit()
                .putInt(KEY_PORT, port)
                .putBoolean(KEY_ENABLED, true)
                .apply()
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIF_ID, buildNotification(), type)
            } else {
                startForeground(NOTIF_ID, buildNotification())
            }
        } catch (e: Throwable) {
            prefs(applicationContext).edit().putBoolean(KEY_ENABLED, false).apply()
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 已通过 onCreate 启动；收到停止动作时自杀
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        runCatching { server?.stop() }
        server = null
        prefs(applicationContext).edit()
            .putBoolean(KEY_ENABLED, false)
            .putInt(KEY_PORT, 0)
            .apply()
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val chan = NotificationChannel(CHANNEL_ID, "Quro MCP 服务", NotificationManager.IMPORTANCE_LOW)
        nm.createNotificationChannel(chan)
        val port = prefs(this).getInt(KEY_PORT, 0)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Quro AI · 本地 MCP 服务")
            .setContentText("已在 127.0.0.1:$port 提供工具调用（仅本机可访问）")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val NOTIF_ID = 8803
        private const val CHANNEL_ID = "quro_mcp"
        const val ACTION_STOP = "quro.mcp.STOP"
        private const val PREFS = "quro_mcp"
        private const val KEY_PORT = "port"
        private const val KEY_ENABLED = "enabled"

        private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        fun isEnabled(ctx: Context) = prefs(ctx).getBoolean(KEY_ENABLED, false)
        fun getPort(ctx: Context) = prefs(ctx).getInt(KEY_PORT, 0)

        fun start(ctx: Context) {
            val intent = Intent(ctx, QuroMcpService::class.java)
            runCatching { ctx.startForegroundService(intent) }
        }

        fun stop(ctx: Context) {
            val intent = Intent(ctx, QuroMcpService::class.java).setAction(ACTION_STOP)
            runCatching { ctx.startService(intent) }
            runCatching { ctx.stopService(Intent(ctx, QuroMcpService::class.java)) }
        }
    }
}
