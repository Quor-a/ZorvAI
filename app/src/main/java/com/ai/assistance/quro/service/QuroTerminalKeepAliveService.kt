package com.ai.assistance.quro.service
import com.ai.assistance.quro.R
import com.ai.assistance.quro.util.qstr

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
import com.ai.assistance.quro.core.harness.QuroDshKeepAlive
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

        /**
         * DeepSeek Harness 自启延迟：避开开机负载高峰。
         *
         * 开机后前 20 秒系统在跑 dexopt / 媒体扫描 / 各家自启，此时再 spawn 一个 Node
         * 引擎（要装配 profile、bundle、运行时补丁）会被挤到几十秒开外，还容易超时。
         * 只影响「自启」这一次，日常巡检不受影响。
         */
        private const val DSH_BOOT_DELAY_MS = 20_000L

        /** 引擎活着时的复核间隔（便宜，只探一次 HTTP）。 */
        private const val DSH_ALIVE_PERIOD_MS = 30_000L

        /** 引擎不在时的重试间隔（内层还有失败冷却，见 QuroDshKeepAlive）。 */
        private const val DSH_RETRY_PERIOD_MS = 60_000L

        /** 本服务拉起时是否处于「刚开机」，决定 Harness 引擎自启是否要避开开机负载高峰。 */
        private const val EXTRA_BOOT = "dsh_boot_delay"

        /**
         * 在上下文里保活式拉起本服务（若已有则忽略）。
         *
         * @param fromBoot 开机/应用更新后拉起时传 true：Harness 引擎会延迟自启，
         *   避开 dexopt / 媒体扫描 / 各家自启的开机高峰；正常进 App 拉起时传 false，立即自启。
         */
        fun ensureStarted(ctx: Context, fromBoot: Boolean = false) {
            try {
                val i = Intent(ctx, QuroTerminalKeepAliveService::class.java)
                    .putExtra(EXTRA_BOOT, fromBoot)
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

    /** DeepSeek Harness 自启/保活循环（独立于 shell 巡检：引擎启动慢，不能拖住终端重建）。 */
    private var dshJob: Job? = null

    /** 最近一次拿到的 DSH 端口；null = 引擎未运行。 */
    @Volatile
    private var dshPort: Int? = null

    /** DSH 自启循环是否已启动（只启一次；服务被系统重建时重新计时）。 */
    private var dshLoopStarted = false

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
        // DSH 自启循环放到 onStartCommand：Service 没有 getIntent()，
        // 只有在那里才能读到「是不是开机拉起的」，从而决定要不要延迟。
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
     * DeepSeek Harness 的自启与保活循环。
     *
     * 这是过去**完全缺失**的一环：开机自启接收器只拉起本服务（保 shell 会话），
     * 引擎没人管，于是「ZorvAI 有自启权限但 DSH 不会自己起环境」。
     *
     * 现在由这里负责：
     *  - 开机/更新后延迟 [DSH_BOOT_DELAY_MS] 自启一次；
     *  - 活着就每 [DSH_ALIVE_PERIOD_MS] 复核一次；
     *  - 死了（进程被回收 / 崩了）按 [DSH_RETRY_PERIOD_MS] 重试，内层还有失败冷却；
     *  - 用户关掉「引擎自启」开关、或运行时未安装时**什么都不做**。
     *
     * 与界面进程的关系：引擎可能是界面进程 spawn 的，也可能是这里 spawn 的。
     * [QuroDshKeepAlive.ensureRunning] 内部先走 `attachIfAlive()`，认出已有引擎就秒返回，
     * 所以两边同时调用不会起出两台。
     */
    private fun startDshLoop(delayMs: Long) {
        dshJob?.cancel()
        dshJob = scope.launch {
            if (delayMs > 0) delay(delayMs)
            while (isActive) {
                val port = try {
                    QuroDshKeepAlive.ensureRunning(applicationContext)
                } catch (t: Throwable) {
                    Log.w(TAG, "DSH 自启异常: ${t.message}")
                    null
                }
                if (port != dshPort) {
                    dshPort = port
                    Log.i(TAG, if (port != null) "DSH 引擎在服务，port=$port" else "DSH 引擎未运行")
                    updateNotification()
                }
                delay(if (port != null) DSH_ALIVE_PERIOD_MS else DSH_RETRY_PERIOD_MS)
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
        val dsh = dshPort?.let { " · Harness 引擎 :$it" } ?: ""
        val text = if (sessionAlive) {
            "终端会话运行中（pid=${android.os.Process.myPid()}）$dsh"
        } else {
            "终端会话已死亡，等待重建…$dsh"
        }
        val notification = buildNotification(text)
        nm.notify(NOTIF_ID, notification)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        // DSH 自启循环：只在首次 onCreate 之后的第一个 start 命令里起，
        // 因为只有这里能读到「开机拉起」标记（EXTRA_BOOT）。
        if (!dshLoopStarted) {
            dshLoopStarted = true
            val fromBoot = intent?.getBooleanExtra(EXTRA_BOOT, false) == true
            startDshLoop(if (fromBoot) DSH_BOOT_DELAY_MS else 0L)
        }
        // 服务被系统意外重建时，确保巡检循环仍运行
        if (loopJob?.isActive != true) startLoop()
        return START_STICKY
    }

    override fun onDestroy() {
        Log.i(TAG, "终端保活前台服务被销毁")
        loopJob?.cancel()
        loopJob = null
        dshJob?.cancel()
        dshJob = null
        heldSession?.destroy()
        heldSession = null
        super.onDestroy()
    }

    private fun buildNotification(text: String): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val chan = NotificationChannel(
                CHANNEL_ID, "Zorv AI 终端", NotificationManager.IMPORTANCE_LOW,
            ).apply { description = qstr(R.string.qk_03488) }
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