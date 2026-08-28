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
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.ai.assistance.quro.activity.QuroMainActivity
import com.ai.assistance.quro.core.terminal.QuroTerminalSessionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 终端默认会话保活服务（前台 dataSync）。
 *
 * 满足「zorvAI 自启动存活终端保持跟随存话」：
 *  - 应用启动时由 [com.ai.assistance.quro.activity.QuroApplication] 拉起；
 *  - 开机 / 应用更新后由 [com.ai.assistance.quro.receiver.QuroTerminalBootReceiver] 拉起；
 *  - 之后每 15 秒检查默认共享会话（[QuroTerminalSessionManager.defaultSession]）是否存活，
 *    死亡则重建（installIfMissing=false，避免无网络 / 开机早期误下载或消耗流量）。
 *
 * 若默认后端（proot/Ubuntu）尚未安装，[QuroShellSession.create] 会回退到设备 sh，
 * 仍保证存在一个可用的默认会话，使 AI / 使用者随时可调用终端能力。
 */
class QuroTerminalKeepAliveService : Service() {

    companion object {
        private const val TAG = "QuroTermKeepAlive"
        private const val CHANNEL_ID = "quro_terminal_channel"
        private const val NOTIF_ID = 9529
        const val ACTION_STOP = "com.ai.assistance.quro.action.TERMINAL_KEEPALIVE_STOP"

        /** 保活巡检间隔：15 秒。 */
        private const val PERIOD_MS = 15_000L

        /** 在上下文里保活式拉起本服务（若已有则忽略）。 */
        fun ensureStarted(ctx: Context) {
            try {
                val i = Intent(ctx, QuroTerminalKeepAliveService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ctx.startForegroundService(i)
                } else {
                    ctx.startService(i)
                }
            } catch (e: Throwable) {
                android.util.Log.e(TAG, "拉起终端保活服务失败", e)
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loopJob: Job? = null

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
        android.util.Log.i(TAG, "终端保活服务已启动，开始保活默认会话")
        startLoop()
    }

    private fun startLoop() {
        loopJob?.cancel()
        loopJob = scope.launch {
            // 立即保活一次（installIfMissing=false：自启动阶段不下载）
            QuroTerminalSessionManager.ensureDefaultAsync(applicationContext, installIfMissing = false)
            while (isActive) {
                delay(PERIOD_MS)
                QuroTerminalSessionManager.ensureDefaultAsync(applicationContext, installIfMissing = false)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        // 服务被系统意外重建时，确保巡检循环仍运行
        if (loopJob?.isActive != true) startLoop()
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        loopJob?.cancel()
        loopJob = null
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val chan = NotificationChannel(
                CHANNEL_ID, "Zorv 终端", NotificationManager.IMPORTANCE_LOW,
            ).apply { description = "终端默认会话保活中" }
            nm.createNotificationChannel(chan)
        }
        val openIntent = Intent(this, QuroMainActivity::class.java)
        val pi = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Zorv AI 终端会话运行中")
            .setContentText("默认终端会话已保活，可随时被 AI / 你调用")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pi)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
