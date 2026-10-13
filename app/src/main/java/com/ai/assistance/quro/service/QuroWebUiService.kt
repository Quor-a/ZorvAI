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
import com.ai.assistance.quro.R
import com.ai.assistance.quro.activity.QuroMainActivity
import com.ai.assistance.quro.ui.QuroChatViewModel
import com.ai.assistance.quro.ui.chat.QuroWebUiHost
import com.ai.assistance.quro.util.QuroDiag
import com.ai.assistance.quro.util.qstr

/**
 * ZorvAI Web UI 的**常驻宿主**（前台服务）。
 *
 * ── 为什么必须有它（这是真机上「Web UI 不能后台」的根因）────────────────
 * 改前 `QuroWebUiServer` 是在对话框 Composable 的 `remember` 里创建的，
 * 生命周期跟着那个界面走。于是用户一按返回（或切到别的应用），
 * 服务就被 `onDispose` 停掉 —— 网页那边表现为**链接突然打不开了**。
 * 用户原话：「返回 ZorvAI 内的 web UI 界面就不能链接了」。
 *
 * Web UI 的定位是「在**别的屏幕**上看/用同一个对话框」，它天生要求
 * 「ZorvAI 在后台也要一直服务」。所以承载者必须是前台服务，不是某个界面。
 *
 * ── 与应用内界面的关系（用户 2026-10-13 拍板）──────────────────────────
 * 「ZorvAI 内的 Web UI 只是设置，通过外部打开」。即：
 *   · 应用内**不再**嵌一个 WebView 去看它（那是「在手机上看手机」，
 *     而且那个嵌进来的界面一关，服务就没了 —— 正是本类要消灭的耦合）；
 *   · 应用内只保留「开关 + 地址 + 复制」这一小块设置；
 *   · 真正的使用者是手机之外的浏览器。
 *
 * ── 生命周期 ─────────────────────────────────────────────────────────
 * 前台通知常驻（IMPORTANCE_LOW，不响不弹），通知里带地址与「停止」动作。
 * `START_STICKY` 让系统回收后重建；重建时若拿不到 ViewModel
 * （进程被冷启、还没进过对话框），服务会明确停下 —— 而不是留一个
 * 「在跑但没有内容可投」的空壳服务在后台骗人。
 */
class QuroWebUiService : Service() {

    companion object {
        private const val TAG = "QuroWebUiService"
        private const val CHANNEL_ID = "quro_webui_channel"
        private const val NOTIF_ID = 9541

        const val ACTION_START = "com.ai.assistance.quro.action.WEBUI_START"
        const val ACTION_STOP = "com.ai.assistance.quro.action.WEBUI_STOP"

        /** 「用户希望 Web UI 常驻」这个意图的持久化位置。 */
        private const val PREFS = "quro_web_ui"
        private const val KEY_ENABLED = "enabled"

        fun isEnabled(ctx: Context): Boolean =
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)

        private fun setEnabled(ctx: Context, on: Boolean) {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_ENABLED, on).apply()
        }

        /**
         * 用户打开开关 → 记下意图并拉起前台服务。
         * @return true 表示服务已接受启动请求（不代表已监听成功，监听结果看 [QuroWebUiHost]）
         */
        fun start(ctx: Context): Boolean {
            setEnabled(ctx, true)
            return runCatching {
                val i = Intent(ctx, QuroWebUiService::class.java).setAction(ACTION_START)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
                else ctx.startService(i)
                true
            }.getOrElse {
                QuroDiag.log(TAG, "拉起 Web UI 服务失败：${it.message}")
                false
            }
        }

        /** 用户关掉开关 → 清掉意图并停服务（服务里会顺带停监听）。 */
        fun stop(ctx: Context) {
            setEnabled(ctx, false)
            runCatching { ctx.stopService(Intent(ctx, QuroWebUiService::class.java)) }
        }

        /**
         * 应用回到前台时兜底拉起。
         *
         * 为什么需要：`START_STICKY` 只保证「进程被杀后重建」，不覆盖
         * 「用户手动在系统设置里停掉了应用」之外的更多情况（部分 ROM 会
         * 清掉前台服务）。回到前台时补一次，是最省事又最可靠的续命点。
         */
        fun ensureIfEnabled(ctx: Context) {
            if (!isEnabled(ctx)) return
            if (QuroWebUiHost.isRunning()) return
            runCatching {
                val i = Intent(ctx, QuroWebUiService::class.java).setAction(ACTION_START)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
                else ctx.startService(i)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // 先把通知挂上：Android 12+ 要求在 startForegroundService 后 5 秒内
        // 调 startForeground，否则直接 ANR/崩。所以这一句必须最先做，
        // 哪怕此刻还不知道监听能不能起来（地址稍后用 notify 补上）。
        val started = runCatching {
            startForeground(
                NOTIF_ID,
                buildNotification(qstr(R.string.qk_04080)),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
            true
        }.getOrElse {
            QuroDiag.log(TAG, "前台通知创建失败：${it.message}")
            false
        }
        if (!started) {
            stopSelf()
            return
        }

        val vm = runCatching { QuroChatViewModel.instance }.getOrNull()
        if (vm == null) {
            // 没有对话框会话就没有可投的内容。明确停下，不留一个空壳服务。
            QuroDiag.log(TAG, "拿不到对话框会话，Web UI 服务停止（请先打开 ZorvAI 对话框）")
            notify(buildNotification(qstr(R.string.qk_04081)))
            stopSelf()
            return
        }

        val url = QuroWebUiHost.start(applicationContext, vm)
        if (url == null) {
            notify(buildNotification(qstr(R.string.qk_04082)))
            stopSelf()
            return
        }
        notify(buildNotification(url))
        QuroDiag.log(TAG, "Web UI 已常驻：$url")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            setEnabled(this, false)
            QuroWebUiHost.stop()
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        // 服务没了，监听也不该留着 —— 否则「开关显示关着、端口却还开着」，
        // 这种「界面与真实状态不一致」是最难查的一类问题。
        QuroWebUiHost.stop()
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // 从最近任务里划掉：用户明确要「后台也能用」，所以不跟着任务走。
        // 但 intent 记的是「用户开关的状态」，开关没关就继续留着。
        if (!isEnabled(this)) {
            stopSelf()
        }
        super.onTaskRemoved(rootIntent)
    }

    /* ── 通知 ─────────────────────────────────────────────────────────── */

    private fun notify(n: Notification) {
        runCatching {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIF_ID, n)
        }
    }

    private fun buildNotification(text: String): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val chan = NotificationChannel(
                CHANNEL_ID, qstr(R.string.qk_04079), NotificationManager.IMPORTANCE_LOW,
            ).apply { description = qstr(R.string.qk_04083) }
            nm.createNotificationChannel(chan)
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, QuroMainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stopPi = PendingIntent.getService(
            this, 1,
            Intent(this, QuroWebUiService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(qstr(R.string.qk_04078))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(open)
            .addAction(0, qstr(R.string.qk_04087), stopPi)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
