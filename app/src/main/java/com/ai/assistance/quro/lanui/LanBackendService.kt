package com.ai.assistance.quro.lanui

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import kotlin.jvm.Volatile

/**
 * 同设备本地后端前台服务（LAN UI 后端 demo）。
 * 监听 127.0.0.1（端口优先 8080，占用则随机），对外提供：
 *   GET  /lan/ui      → UI 快照 JSON
 *   POST /lan/action  → action 回传
 * 跨设备场景：前端 [LanUiActivity] 直接连接局域网内其他设备的后端地址即可；
 * 本服务仅代表「同设备 demo 后端」，与主应用现有 UI / ACI 完全解耦、互不干扰。
 */
class LanBackendService : Service() {

    private var server: LanHttpServer? = null
    private val backend = LanBackend()

    override fun onBind(intent: Intent?) = null

    override fun onCreate() {
        super.onCreate()
        try {
            server = LanHttpServer(
                onUi = { backend.buildUiSnapshot() },
                onAction = { action, payload -> backend.applyAction(action, payload) }
            )
            val port = server!!.start(PREF_PORT)
            prefs(this).edit().putInt(KEY_PORT, port).putBoolean(KEY_ENABLED, true).apply()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIF_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NOTIF_ID, buildNotification())
            }
        } catch (e: Throwable) {
            prefs(this).edit().putBoolean(KEY_ENABLED, false).apply()
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
        prefs(this).edit().putBoolean(KEY_ENABLED, false).putInt(KEY_PORT, 0).apply()
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val chan = NotificationChannel(CHANNEL_ID, "Zorv LAN 后端", NotificationManager.IMPORTANCE_LOW)
        nm.createNotificationChannel(chan)
        val port = prefs(this).getInt(KEY_PORT, 0)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("ZorvAI · 本地 LAN 后端")
            .setContentText("已在 127.0.0.1:$port 提供 UI 下发（仅本机可访问）")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val NOTIF_ID = 8903
        private const val CHANNEL_ID = "quro_lan_backend"
        private const val PREF_PORT = 8080
        const val ACTION_STOP = "quro.lan.STOP"
        private const val PREFS = "quro_lan_backend"
        private const val KEY_PORT = "port"
        private const val KEY_ENABLED = "enabled"

        private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        fun isEnabled(ctx: Context) = prefs(ctx).getBoolean(KEY_ENABLED, false)
        fun getPort(ctx: Context) = prefs(ctx).getInt(KEY_PORT, 0)

        fun start(ctx: Context) {
            runCatching { ctx.startForegroundService(Intent(ctx, LanBackendService::class.java)) }
        }

        fun stop(ctx: Context) {
            runCatching { ctx.startService(Intent(ctx, LanBackendService::class.java).setAction(ACTION_STOP)) }
            runCatching { ctx.stopService(Intent(ctx, LanBackendService::class.java)) }
        }
    }
}
