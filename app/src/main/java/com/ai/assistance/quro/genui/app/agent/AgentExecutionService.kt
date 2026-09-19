package com.ai.assistance.quro.genui.app.agent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat

/**
 * Agent 执行期前台服务 —— 「AI 执行挂后台直接无法执行任务」的根治。
 *
 * 根因：chat/generate 跑在 Activity 协程域，无任何前台服务——app 一进后台，
 * 国产 ROM 冻结进程 + 切断网络，LLM 流式连接直接断（2026-09-17 确诊）。
 *
 * 方案（官方 dataSync 型 FGS，用户交互后启动、后台 ≤6h/24h，覆盖 AI 任务时长）：
 * - 执行开始 → start()：升前台 + 通知栏"AI 正在执行" + WakeLock/WiFiLock
 * - 执行结束 → stop()：降级回后台
 * 服务本身零业务逻辑，只负责"让进程活着、网络不断"。
 */
class AgentExecutionService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val label = intent?.getStringExtra(EXTRA_LABEL) ?: "AI 正在执行"
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CH_ID, "Agent 执行", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val n: Notification = NotificationCompat.Builder(this, CH_ID)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle("GenUI Agent")
            .setContentText(label)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTI_ID, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTI_ID, n)
        }
        // 执行期持锁：防 CPU 休眠打断流式读取；WiFiLock 防 Doze 断网
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "genui:agent_exec").apply {
            setReferenceCounted(false); acquire(30 * 60 * 1000L)
        }
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "genui:agent_wifi").apply {
            setReferenceCounted(false); acquire()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        runCatching { wakeLock?.release() }
        runCatching { wifiLock?.release() }
        super.onDestroy()
    }

    companion object {
        private const val CH_ID = "agent_exec"
        private const val NOTI_ID = 1001
        private const val EXTRA_LABEL = "label"

        fun start(ctx: Context, label: String) {
            val i = Intent(ctx, AgentExecutionService::class.java).putExtra(EXTRA_LABEL, label)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, AgentExecutionService::class.java))
        }
    }
}
