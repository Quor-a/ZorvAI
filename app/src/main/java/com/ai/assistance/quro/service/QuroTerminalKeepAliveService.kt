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
import android.util.Log
import androidx.core.app.NotificationCompat
import com.ai.assistance.quro.activity.QuroMainActivity
import com.ai.assistance.quro.core.terminal.QuroShellSession
import com.ai.assistance.quro.core.terminal.QuroTerminalSessionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 终端保活前台服务（specialUse）。
 *
 * 职责：
 * 1. 以前台服务身份存活 → 系统不会因内存/电量杀死本进程；
 * 2. 在本服务进程内 fork 出 shell 子进程（QuroShellSession.create），
 *    shell 进程是本服务进程的子进程 → 服务存活 = shell 子进程存活；
 * 3. 每 15 秒巡检：会话死亡则重建，ACI 服务未启动则拉起。
 *
 * 因此：息屏 / 切 App / 后台运行时，终端会话不会被杀。
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
                Log.e(TAG, "拉起终端保活服务失败", e)
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loopJob: Job? = null

    /** 服务内持有的终端会话——shell 子进程是本服务进程的 fork，服务活则子进程活。 */
    var heldSession: QuroShellSession? = null
        private set

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        try {
            startForeground(NOTIF_ID, buildNotification("启动中…"), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } catch (e: Throwable) {
            Log.e(TAG, "前台通知创建失败（SPECIAL_USE），尝试 DATA_SYNC 降级…", e)
            try {
                startForeground(NOTIF_ID, buildNotification("启动中…"), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } catch (e2: Throwable) {
                Log.e(TAG, "前台通知创建彻底失败", e2)
                stopSelf()
                return
            }
        }
        Log.i(TAG, "终端保活前台服务已启动（pid=${android.os.Process.myPid()}）")
        startLoop()
    }

    private fun startLoop() {
        loopJob?.cancel()
        loopJob = scope.launch {
            // 立即尝试确保默认会话（installIfMissing=false：自启动阶段不下载）
            ensureSessionSafe(installIfMissing = false)
            updateNotification()

            // 同时确保 ACI 服务在跑
            try { QuroTerminalAciService.ensureStarted(applicationContext, installIfMissing = false) } catch (_: Throwable) {}

            while (isActive) {
                delay(PERIOD_MS)

                // 巡检：会话死了就重建
                ensureSessionSafe(installIfMissing = false)
                updateNotification()

                // 巡检：ACI 服务在跑
                try { QuroTerminalAciService.ensureStarted(applicationContext, installIfMissing = false) } catch (_: Throwable) {}
            }
        }
    }

    /**
     * 确保终端会话存活。在本服务进程内 fork shell 子进程，
     * 子进程归属于本服务进程 → 服务存活时子进程不被系统杀。
     */
    private suspend fun ensureSessionSafe(installIfMissing: Boolean) {
        try {
            // 先检查已有会话是否还活着
            val existing = heldSession
            if (existing != null && !existing.exited) return

            // 会话不存在或已死亡 → 在本服务进程内重新创建
            Log.i(TAG, "终端会话${if (existing != null) "已死亡" else "不存在"}，在服务进程内重建…")
            val session = QuroTerminalSessionManager.ensureDefault(applicationContext, installIfMissing)
            if (session != null) {
                heldSession = session
                session.onExit = { code ->
                    Log.w(TAG, "终端会话退出(exit=$code)，15 秒后由保活循环重建")
                    heldSession = null
                }
                Log.i(TAG, "✅ 终端会话已在服务进程内创建（pid=${android.os.Process.myPid()}）")
            }
        } catch (e: Throwable) {
            Log.e(TAG, "确保终端会话失败: ${e.message}")
        }
    }

    private fun updateNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val sessionAlive = heldSession?.exited == false
        val text = if (sessionAlive) "终端会话运行中（pid=${android.os.Process.myPid()}）" else "终端会话已死亡，等待重建…"
        val notification = buildNotification(text)
        nm.notify(NOTIF_ID, notification)
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
        Log.i(TAG, "终端保活前台服务被销毁")
        loopJob?.cancel()
        loopJob = null
        heldSession?.destroy()
        heldSession = null
        super.onDestroy()
    }

    private fun buildNotification(text: String): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val chan = NotificationChannel(
                CHANNEL_ID, "Zorv AI 终端", NotificationManager.IMPORTANCE_LOW,
            ).apply { description = "终端会话前台保活服务" }
            nm.createNotificationChannel(chan)
        }
        val openIntent = Intent(this, QuroMainActivity::class.java)
        val pi = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Zorv AI 终端运行中")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pi)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
