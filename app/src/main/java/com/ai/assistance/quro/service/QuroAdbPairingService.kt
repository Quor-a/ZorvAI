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
import androidx.core.app.RemoteInput
import com.ai.assistance.quro.R
import com.ai.assistance.quro.core.adb.QuroAdbDebug
import com.ai.assistance.quro.core.adb.QuroAdbWireless
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 无线调试配对的前台服务 —— **配对码直接在通知栏里输入**，不用先回 App。
 *
 * ## 为什么要有这个服务
 *
 * Android 11+ 的「使用配对码配对设备」弹窗**关掉就失效**，用户要在两个界面之间来回切：
 * 抄下 6 位码 → 退回 App → 找到输入框 → 填进去。中间切一次后台，弹窗就被系统关掉，
 * 码也就废了。所以配对这件事必须做成**通知栏可直接完成**，用户抄到码就地输入，不切界面。
 *
 * ## 四阶段通知（与用户看到的东西一一对应）
 *
 * 1. **正在搜索配对服务** —— 后台 mDNS 扫 `_adb-tls-pairing._tcp`，带「停止搜索」按钮；
 * 2. **找到配对服务，请输入配对码** —— 带 RemoteInput 内联输入框，直接在通知上填 6 位码；
 * 3. **正在配对…** —— SPAKE2 + TLS 1.3 握手期间；
 * 4. **配对成功 / 配对失败（含具体原因）** —— 成功会自动接着建立连接，然后停止服务。
 *
 * 端口要通过 Intent extra 一路带下去：服务随时可能被系统杀掉重建，
 * 存在成员变量里会丢，用户输完码却不知道往哪连。
 */
class QuroAdbPairingService : Service() {

    companion object {
        private const val TAG = "QuroAdbPairingService"
        private const val CHANNEL_ID = "quro_adb_pairing"
        private const val NOTIF_ID = 9101

        private const val ACTION_START = "com.ai.assistance.quro.action.ADB_PAIR_START"
        private const val ACTION_REPLY = "com.ai.assistance.quro.action.ADB_PAIR_REPLY"
        private const val ACTION_STOP = "com.ai.assistance.quro.action.ADB_PAIR_STOP"

        private const val REQ_REPLY = 11
        private const val REQ_STOP = 12

        /** RemoteInput 结果键 */
        private const val KEY_CODE = "quro_pairing_code"
        /** 配对端口（mDNS 找到后带进 PendingIntent） */
        private const val EXTRA_PORT = "quro_pairing_port"

        fun start(context: Context) {
            val i = Intent(context, QuroAdbPairingService::class.java).setAction(ACTION_START)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(i)
                } else {
                    context.startService(i)
                }
            }.onFailure { Log.w(TAG, "启动配对服务失败", it) }
        }

        fun stop(context: Context) {
            val i = Intent(context, QuroAdbPairingService::class.java).setAction(ACTION_STOP)
            runCatching { context.startService(i) }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var searchJob: Job? = null

    @Volatile
    private var foundPort: Int = -1

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.qk_04418),
                    // HIGH：用户正在等这一步，必须立刻出现在通知栏顶部
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    setSound(null, null)
                    setShowBadge(false)
                    setAllowBubbles(false)
                },
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                pushForeground(searchingNotification())
                beginSearch()
            }

            ACTION_REPLY -> {
                // RemoteInput 的文本只有从「回复 Intent」里才取得到
                val code = RemoteInput.getResultsFromIntent(intent)
                    ?.getCharSequence(KEY_CODE)?.toString()?.trim().orEmpty()
                val port = intent.getIntExtra(EXTRA_PORT, -1)
                if (code.length < 6) {
                    // 码不对就地提示，不打断流程：重新给一个可输入的搜索通知
                    pushForeground(inputNotification(port.takeIf { it > 0 } ?: foundPort))
                } else {
                    pushForeground(workingNotification())
                    doPair(code, port.takeIf { it > 0 } ?: foundPort)
                }
            }

            ACTION_STOP -> {
                searchJob?.cancel()
                stopSelfSafely()
            }

            else -> return START_NOT_STICKY
        }
        return START_REDELIVER_INTENT
    }

    // ─────────────────────── 搜索配对端口 ───────────────────────

    private fun beginSearch() {
        if (searchJob?.isActive == true) return
        searchJob = scope.launch {
            // 最多找约 90 秒：用户打开「使用配对码配对设备」弹窗后通常几秒内就能扫到，
            // 扫太久会白耗电，也说明弹窗其实没打开。
            repeat(45) { i ->
                if (!isActive) return@launch
                val port = runCatching { QuroAdbDebug.findPairingPort(this@QuroAdbPairingService, 2000) }
                    .getOrNull()
                if (port != null && port > 0) {
                    foundPort = port
                    Log.i(TAG, "找到配对端口 :$port（第 ${i + 1} 次扫描）")
                    pushForeground(inputNotification(port))
                    return@launch
                }
                delay(2000)
            }
            Log.w(TAG, "90 秒内没扫到配对端口")
            notifyFinal(
                getString(R.string.qk_04431),
                getString(R.string.qk_04432),
            )
            stopSelfSafely()
        }
    }

    // ─────────────────────── 执行配对 ───────────────────────

    private fun doPair(code: String, port: Int) {
        scope.launch {
            val r = runCatching {
                if (port > 0) {
                    QuroAdbWireless.pair(this@QuroAdbPairingService, port, code)
                } else {
                    // 没拿到端口：让底层自己再扫一次（它内部有 mDNS 兜底）
                    QuroAdbWireless.pair(this@QuroAdbPairingService, null, code)
                }
            }.getOrElse { "❌ 配对异常：${it.message}" }

            val ok = r.contains("成功") || r.contains("✅")
            if (ok) {
                // 配对成功后立刻建立长期连接，用户不用再点一次「连接」
                val cr = runCatching { QuroAdbWireless.ensureConnected(this@QuroAdbPairingService) }
                    .getOrDefault(false)
                notifyFinal(
                    getString(R.string.qk_04428),
                    if (cr) getString(R.string.qk_04429)
                    else getString(R.string.qk_04430),
                )
            } else {
                notifyFinal(getString(R.string.qk_04431), r.take(180))
            }
            stopSelfSafely()
        }
    }

    // ─────────────────────── 通知 ───────────────────────

    private fun base(title: String): NotificationCompat.Builder =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle(title)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOnlyAlertOnce(true)
            .setOngoing(true)

    private fun searchingNotification(): Notification =
        base(getString(R.string.qk_04419))
            .setContentText(getString(R.string.qk_04420))
            .setProgress(0, 0, true)
            .addAction(
                0,
                getString(R.string.qk_04421),
                PendingIntent.getService(
                    this, REQ_STOP,
                    Intent(this, QuroAdbPairingService::class.java).setAction(ACTION_STOP),
                    piFlags(),
                ),
            )
            .build()

    /**
     * 找到端口后的通知：**这里是整个流程的关键** —— 直接在通知栏内联输入 6 位配对码。
     *
     * RemoteInput 挂在一个 ACTION_REPLY 的 PendingIntent 上，端口塞进这个 Intent 的 extra。
     * 用户点「输入配对码」时系统弹出内联输入框，不离开当前界面（配对弹窗也就不会被关掉）。
     */
    private fun inputNotification(port: Int): Notification {
        val replyIntent = Intent(this, QuroAdbPairingService::class.java)
            .setAction(ACTION_REPLY)
            .putExtra(EXTRA_PORT, port)

        // Android 12+ 要求带 RemoteInput 的 PendingIntent 必须 MUTABLE
        val pi = PendingIntent.getService(
            this, REQ_REPLY, replyIntent,
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val remoteInput = RemoteInput.Builder(KEY_CODE)
            .setLabel(getString(R.string.qk_04425))
            .build()

        val action = NotificationCompat.Action.Builder(
            0,
            getString(R.string.qk_04424),
            pi,
        ).addRemoteInput(remoteInput).build()

        return base(getString(R.string.qk_04422))
            .setContentText(
                getString(R.string.qk_04423).format(port)
            )
            .setProgress(0, 0, false)
            .addAction(action)
            .build()
    }

    private fun workingNotification(): Notification =
        base(getString(R.string.qk_04426))
            .setContentText(getString(R.string.qk_04427))
            .setProgress(0, 0, true)
            .build()

    /** 结果通知：不 ongoing，用户可划掉。 */
    private fun notifyFinal(title: String, text: String) {
        val n = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setOngoing(false)
            .build()
        runCatching {
            getSystemService(NotificationManager::class.java).notify(NOTIF_ID, n)
        }
    }

    private fun pushForeground(n: Notification) {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIF_ID, n)
            }
        }.onFailure {
            // Android 12+ 从后台启动前台服务会被拒；此时退化成普通通知，流程仍可走完
            Log.w(TAG, "startForeground 被拒，降级为普通通知", it)
            runCatching { getSystemService(NotificationManager::class.java).notify(NOTIF_ID, n) }
        }
    }

    private fun stopSelfSafely() {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_DETACH)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(false)
            }
        }
        stopSelf()
    }

    private fun piFlags(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else PendingIntent.FLAG_UPDATE_CURRENT

    override fun onDestroy() {
        searchJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }
}
