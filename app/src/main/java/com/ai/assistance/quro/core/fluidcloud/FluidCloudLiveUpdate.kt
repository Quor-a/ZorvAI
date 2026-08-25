package com.ai.assistance.quro.core.fluidcloud

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build

/**
 * Android 16+ Live Updates 兜底方案。
 * 当设备不支持 OPPO ContentProvider 流体云时，使用 Android 标准 Live Updates API。
 * ColorOS 16+ 走谷歌实时活动规范，自动映射为流体云胶囊。
 *
 * 实现要点（基于 Android 16 Live Updates 官方文档）：
 * - 需要 POST_PROMOTED_NOTIFICATIONS 权限
 * - 必须调用 setRequestPromotedOngoing(true) 请求提升
 * - 必须为 ongoing 通知
 * - 必须使用标准样式（ProgressStyle / BigTextStyle / CallStyle / MetricStyle）
 * - 不得设置 setColorized(true)
 * - 渠道 importance 不能是 IMPORTANCE_MIN
 */
object FluidCloudLiveUpdate {

    private const val CHANNEL_ID = "zorv_agent_fluid"
    private const val CHANNEL_NAME = "ZorvAI 流体云"
    private const val NOTIFICATION_ID = 0x7A1F

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_DEFAULT  // 必须 DEFAULT 或以上，LOW 不会被 ColorOS 识别为流体云
            ).apply {
                description = "ZorvAI 任务实时状态（流体云/实时更新）"
                setShowBadge(false)
            }
            val manager = context.getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    /**
     * 显示/更新实时更新通知。
     *
     * @param context 上下文
     * @param title 通知标题（必填）
     * @param step 当前步骤描述
     * @param progress 进度 0-100
     */
    fun show(context: Context, title: String, step: String = "", progress: Int = 0) {
        ensureChannel(context)

        val builder = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(title)
            .setContentText("ZorvAI 执行中 $progress%")
            .setOngoing(true)  // 必须为 ongoing
            .setWhen(System.currentTimeMillis())
            .setShowWhen(true)

        if (Build.VERSION.SDK_INT >= 36) {
            // Android 16+：使用 ProgressStyle + setRequestPromotedOngoing
            try {
                val method = Notification.Builder::class.java.getMethod(
                    "setRequestPromotedOngoing", Boolean::class.javaPrimitiveType
                )
                method.invoke(builder, true)
            } catch (e: Exception) {
                android.util.Log.w("FluidCloud", "setRequestPromotedOngoing 不可用: ${e.message}")
            }

            try {
                val progressStyle = Notification.ProgressStyle()
                    .setProgress(progress.coerceIn(0, 100))
                    .setProgressIndeterminate(false)
                builder.setStyle(progressStyle)
            } catch (e: Exception) {
                builder.setProgress(100, progress.coerceIn(0, 100), false)
            }

            if (step.isNotEmpty()) {
                try {
                    builder.setShortCriticalText(step)
                } catch (e: Exception) { }
            }
        } else {
            // Android 8-15：使用 BigTextStyle（ColorOS 识别此样式为流体云候选）
            val bigText = if (step.isNotEmpty()) "$step · $progress%" else "进度 $progress%"
            val bigTextStyle = Notification.BigTextStyle()
                .bigText(bigText)
                .setBigContentTitle(title)
                .setSummaryText("ZorvAI 任务")
            builder.setStyle(bigTextStyle)
            builder.setProgress(100, progress.coerceIn(0, 100), false)
        }

        val manager = context.getSystemService(NotificationManager::class.java)
        manager?.notify(NOTIFICATION_ID, builder.build())
    }

    /**
     * 显示完成状态并移除通知。
     */
    fun finish(context: Context, title: String = "ZorvAI 任务完成") {
        show(context, title, "完成", 100)
        // 延迟移除，让用户看到完成状态
        android.os.Handler(context.mainLooper).postDelayed({
            cancel(context)
        }, 2000)
    }

    fun cancel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager?.cancel(NOTIFICATION_ID)
    }
}
