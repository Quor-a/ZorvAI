package com.ai.assistance.quro.genui.app.bridge

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat

/**
 * 开机自启动接收器。
 *
 * Android 10+ 禁止后台直接拉起 Activity，所以这里不能 startActivity——否则会被系统直接干掉。
 * 正确做法：开机后发一条通知，用户点通知才回到 App。真正"开机自动进界面"是否生效，
 * 还取决于厂商 ROM 的自启动管理（华为/小米/OPPO 等各自有一层白名单），那一层只能
 * 引导用户去系统设置开启；本类只负责"收到开机广播后让 App 就绪"。
 *
 * 是否启用由 PermRegistry 的 autostart 开关控制（用户在权限屏点「去授权」即视为开启）。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != "android.intent.action.QUICKBOOT_POWERON"
        ) return
        // 开关未开则不动作（避免无谓打扰）
        if (!com.ai.assistance.quro.genui.app.perms.PermRegistry.autostartEnabled(context)) return

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL, "启动就绪", NotificationManager.IMPORTANCE_LOW
        ).apply { description = "开机后提示 GenUI 已就绪" }
        nm.createNotificationChannel(channel)

        val open = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pi = open?.let {
            PendingIntent.getActivity(
                context, 0, it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
        val note = NotificationCompat.Builder(context, CHANNEL)
            .setContentTitle("GenUI 已就绪")
            .setContentText("点击回到你的生成式界面")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        nm.notify(NOTIFY_ID, note)
    }

    companion object {
        private const val CHANNEL = "gen_boot"
        private const val NOTIFY_ID = 1001
    }
}
