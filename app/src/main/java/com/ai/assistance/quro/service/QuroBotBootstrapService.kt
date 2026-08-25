package com.ai.assistance.quro.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import android.app.Service
import android.content.pm.ServiceInfo
import android.os.IBinder
import com.ai.assistance.quro.activity.QuroMainActivity
import com.ai.assistance.quro.core.bot.QuroBotManager

/**
 * 机器人开机保活服务（前台）。
 *
 * 机器人（飞书 / QQ / 微信 iLink）依赖应用进程常驻：它们用长轮询 / 长连接维持
 * 与官方网关的会话，进程一死连接就断。本服务在以下时机被拉起：
 *   1. 开机（BOOT_COMPLETED）——由 QuroBootReceiver 触发；
 *   2. 应用进入后台且仍有已启用机器人时——由 QuroApplication / BotManager 兜底拉起。
 *
 * 拉起后调用 [QuroBotManager.startEnabled] 连接所有「已启用且已配置」的平台，
 * 并以低优先级前台通知保活，使机器人能在后台持续收发消息。
 *
 * 若没有任何已启用的机器人，服务会自行停止，不残留通知。
 */
class QuroBotBootstrapService : Service() {

    companion object {
        private const val TAG = "QuroBotBootstrap"
        private const val CHANNEL_ID = "quro_bot_channel"
        private const val NOTIF_ID = 9531
        const val ACTION_STOP = "com.ai.assistance.quro.action.BOT_BOOTSTRAP_STOP"

        /** 在上下文里保活式拉起本服务（若已有则忽略）。 */
        fun ensureStarted(ctx: Context) {
            try {
                val i = Intent(ctx, QuroBotBootstrapService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ctx.startForegroundService(i)
                } else {
                    ctx.startService(i)
                }
            } catch (e: Throwable) {
                android.util.Log.e(TAG, "拉起机器人保活服务失败", e)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        try {
            startForeground(NOTIF_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } catch (e: Throwable) {
            android.util.Log.e(TAG, "前台通知创建失败", e)
            stopSelf()
            return
        }

        val ctx = applicationContext
        val manager = QuroBotManager.instance(ctx)
        val prefs = ctx.getSharedPreferences(QuroBotManager.PREFS, Context.MODE_PRIVATE)
        val hasEnabled = manager.registeredPlatforms().any { p ->
            prefs.getBoolean("enabled_${p.name}", false)
        }
        if (!hasEnabled) {
            // 没有启用的机器人，无需保活，直接退出避免残留通知
            android.util.Log.i(TAG, "无启用机器人，保活服务自行退出")
            stopSelf()
            return
        }
        // 连接所有已启用且已配置的平台
        manager.startEnabled(ctx)
        android.util.Log.i(TAG, "机器人保活服务已启动，已拉起已启用平台")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val chan = NotificationChannel(
                CHANNEL_ID, "Zorv 机器人", NotificationManager.IMPORTANCE_LOW,
            ).apply { description = "机器人后台运行中" }
            nm.createNotificationChannel(chan)
        }
        val openIntent = Intent(this, QuroMainActivity::class.java)
        val pi = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Zorv AI 机器人运行中")
            .setContentText("飞书 / QQ / 微信 机器人已在后台连接")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pi)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
