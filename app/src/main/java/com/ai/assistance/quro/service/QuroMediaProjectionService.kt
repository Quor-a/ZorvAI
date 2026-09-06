package com.ai.assistance.quro.service

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.ai.assistance.quro.activity.QuroMainActivity
import com.ai.assistance.quro.core.vision.ScreenCaptureController
import com.ai.assistance.quro.ui.QuroChatViewModel

/**
 * 屏幕捕获（MediaProjection / 媒体投影 / 录屏投屏）前台服务。
 *
 * Android 14（API 34+）红线：调用 [MediaProjectionManager.getMediaProjection] 之前，
 * 应用必须有一个 `mediaProjection` 类型的前台服务正在运行，否则抛
 * `SecurityException: Media projections require a foreground service of type
 * ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION`。
 *
 * 因此这里把"拿到系统授权回调 → 调 getMediaProjection → 注入 ScreenCaptureController/QuroVisionLoop"
 * 整条链路搬进本服务：由 ChatScreen.mpLauncher 在用户允许授权后先 startForegroundService 拉起本服务，
 * 本服务 onCreate 即 startForeground(mediaProjection) 把自身置为合规前台服务，onStartCommand 内
 * 在合规状态下调用 getMediaProjection 并注入。注入完成后 MediaProjection 实例由全局单例持有，服务即 stopSelf。
 *
 * 仿 QuroVoiceBallService（MICROPHONE 类型）的 startForeground + 通知写法。
 */
class QuroMediaProjectionService : Service() {

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // 必须在任何 getMediaProjection 调用之前把自身置为 mediaProjection 前台服务。
        // Android 14+ 必须在 5 秒内 startForeground，否则系统杀进程。
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIF_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            } else {
                startForeground(NOTIF_ID, buildNotification())
            }
        } catch (e: Throwable) {
            mainHandler.post { Toast.makeText(applicationContext, "屏幕捕获服务启动失败：${e.message}", Toast.LENGTH_SHORT).show() }
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        val data = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION") intent?.getParcelableExtra<Intent>(EXTRA_DATA)
        }
        try {
            if (resultCode == Activity.RESULT_OK && data != null) {
                val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                // 此时本服务已是 mediaProjection 前台服务，调用合规。
                val mp = mpm.getMediaProjection(resultCode, data)
                if (mp != null) {
                    // 注入到全局共享的 ScreenCaptureController + QuroVisionLoop（与对话框同源）。
                    val vm = runCatching { QuroChatViewModel.instance }.getOrNull()
                    if (vm != null) {
                        vm.attachMediaProjection(mp)
                    } else {
                        // 兜底：VM 未就绪时直接挂到共享控制器，保证投影被持有、后续可读帧。
                        val ctrl = ScreenCaptureController.shared(applicationContext)
                        ctrl.attach(mp)
                        ctrl.start()
                    }
                    mainHandler.post { Toast.makeText(applicationContext, "屏幕捕获已启用，长按可再次申请", Toast.LENGTH_SHORT).show() }
                } else {
                    mainHandler.post { Toast.makeText(applicationContext, "屏幕捕获授权失败，已 fallback 到无障碍节点树", Toast.LENGTH_SHORT).show() }
                }
            } else {
                mainHandler.post { Toast.makeText(applicationContext, "屏幕捕获授权被取消，已 fallback 到无障碍节点树", Toast.LENGTH_SHORT).show() }
            }
        } catch (e: Throwable) {
            mainHandler.post { Toast.makeText(applicationContext, "屏幕捕获启动失败：${e.message}", Toast.LENGTH_SHORT).show() }
        } finally {
            // MediaProjection 实例已由单例持有，独立于本服务存活；服务在注入完成后即可结束。
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val chan = NotificationChannel(
            CHANNEL_ID, "ZorvAI 屏幕捕获", NotificationManager.IMPORTANCE_LOW,
        )
        nm.createNotificationChannel(chan)
        val contentIntent = Intent(this, QuroMainActivity::class.java)
        val pi = PendingIntent.getActivity(
            this, 0, contentIntent,
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Zorv AI")
            .setContentText("屏幕捕获进行中")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    companion object {
        /** ChatScreen.mpLauncher 传入：系统授权回调的 resultCode。 */
        const val EXTRA_RESULT_CODE = "extra_result_code"
        /** ChatScreen.mpLauncher 传入：系统授权回调的 data Intent（含投影 token）。 */
        const val EXTRA_DATA = "extra_data"
        private const val NOTIF_ID = 8901
        private const val CHANNEL_ID = "quro_media_projection"
    }
}
