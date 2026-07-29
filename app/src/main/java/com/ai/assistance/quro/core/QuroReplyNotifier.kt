package com.ai.assistance.quro.core

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import com.ai.assistance.quro.R
import com.ai.assistance.quro.activity.QuroMainActivity

/**
 * AI 回复通知（仿微信消息）—— 系统通知。
 *
 * 设计原则（与「常住」功能彻底解耦）：
 * - 本类只负责「离开软件时」的**系统通知**（heads-up 弹窗 + 通知栏条目）。
 * - **仅在 app 不在前台时弹出**：[notifyReply] 开头若 [isAppForeground] 为真直接 return，
 *   用户在对话框里/软件内能直接看到回复，不重复弹系统通知。
 * - 应用退到后台、被关掉、来电/锁屏等「离开软件」场景：走 HIGH 渠道 → 锁屏/通知栏以
 *   「弹窗(heads-up)」形式出现，离开软件也能收到。
 * - 点击通知把应用带到前台（QuroMainActivity 为 singleTask，自然回到对话）。
 *
 * 与「常住」是两套独立体系：常驻通知栏由 [com.ai.assistance.quro.service.QuroVoiceBallService]
 * （前台服务常驻条目）负责，本类不管常驻，只管「离开软件时的消息系统通知」。
 *
 * 另外有桌面卡片（AppWidget，见 [QuroReplyWidget]）与设置总开关（"ai_reply_notify"），
 * 二者独立于本通知，始终按开关刷新。
 *
 * [isAppForeground] 由 QuroMainActivity 在 onResume/onPause 维护。
 */
object QuroReplyNotifier {
    private const val CHANNEL_ID = "quro_ai_reply"        // 离开软件：重要级 HIGH → heads-up 弹窗
    private const val NOTIF_ID = 2001

    /** IM（飞书 / QQ 等机器人）入站消息渠道：同样 HIGH → heads-up 弹窗。 */
    private const val CHANNEL_IM_ID = "quro_im"
    internal const val NOTIF_IM_INBOUND = 2002              // 收到 IM 消息
    internal const val NOTIF_IM_REPLY = 2003                // 机器人回复（同会话）

    /** 应用是否处于前台（由 MainActivity 维护）。前台时 [notifyReply] 直接 return，不弹系统通知。 */
    @Volatile var isAppForeground: Boolean = false

    private fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                val ch = NotificationChannel(
                    CHANNEL_ID,
                    "AI 回复通知",
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    description = "离开软件时收到 AI 回复以系统弹窗（heads-up）形式提醒"
                    setShowBadge(true)
                }
                nm.createNotificationChannel(ch)
            }
        }
    }

    /**
     * 收到 AI 回复时调用。**仅当 app 不在前台时**才弹出系统通知（离开软件才能收到）；
     * app 在前台（用户在对话框/软件内）直接 return，不重复打扰。
     * @param ctx   上下文（通常用 ApplicationContext）
     * @param sender 发送者显示名（人格名或 "Quro AI"）
     * @param text  回复正文（自动取首非空行并截断）
     */
    fun notifyReply(ctx: Context, sender: String, text: String) {
        // 在软件内（前台）不弹系统通知：对话框自己能看到
        if (isAppForeground) return
        runCatching {
            ensureChannel(ctx)
            val tapIntent = Intent(ctx, QuroMainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pi = PendingIntent.getActivity(
                ctx,
                0,
                tapIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val snippet = text.lineSequence().firstOrNull { it.isNotBlank() }?.take(200) ?: "（空回复）"
            val me = Person.Builder().setName("我").build()
            val ai = Person.Builder().setName(sender).build()
            val style = NotificationCompat.MessagingStyle(me)
                .addMessage(snippet, System.currentTimeMillis(), ai)
            // 离开软件 → HIGH 渠道 + HIGH 优先级 → heads-up 弹窗
            val notif = NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(sender)
                .setContentText(snippet)
                .setStyle(style)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setAutoCancel(true)
                .setContentIntent(pi)
                .build()
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIF_ID, notif)
        }
    }

    private fun ensureImChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL_IM_ID) == null) {
                val ch = NotificationChannel(
                    CHANNEL_IM_ID,
                    "IM 消息通知",
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    description = "来自飞书 / QQ 等机器人的消息（离开软件时系统弹窗）"
                    setShowBadge(true)
                }
                nm.createNotificationChannel(ch)
            }
        }
    }

    /**
     * IM（机器人）消息系统弹窗：离开软件时弹 heads-up，前台时不弹（用户能在绑定对话里看到）。
     * 用于「飞书 / QQ 收到消息 → 系统级弹窗提醒」与「机器人回复 → 系统级弹窗提醒」两个场景。
     * @param id 通知 id：入站用 [NOTIF_IM_INBOUND]、回复用 [NOTIF_IM_REPLY]，避免两类通知互相覆盖。
     */
    fun notifyImMessage(ctx: Context, sender: String, text: String, id: Int = NOTIF_IM_INBOUND) {
        if (isAppForeground) return
        runCatching {
            ensureImChannel(ctx)
            val tapIntent = Intent(ctx, QuroMainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pi = PendingIntent.getActivity(
                ctx, id, tapIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val snippet = text.lineSequence().firstOrNull { it.isNotBlank() }?.take(200) ?: "（空消息）"
            val notif = NotificationCompat.Builder(ctx, CHANNEL_IM_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(sender)
                .setContentText(snippet)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setAutoCancel(true)
                .setContentIntent(pi)
                .build()
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(id, notif)
        }
    }
}
