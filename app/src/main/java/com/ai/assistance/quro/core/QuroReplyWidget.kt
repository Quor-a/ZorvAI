package com.ai.assistance.quro.core

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.ai.assistance.quro.R
import com.ai.assistance.quro.activity.QuroMainActivity

/**
 * 桌面卡片（AppWidget）：展示最近一条 AI 回复摘要，点击进入应用。
 *
 * - 数据持久化在 "quro_widget_reply" SharedPreferences，由 [updateLatest] 刷新。
 * - 无实例时不报错（getAppWidgetIds 为空 → 跳过更新）。
 * - 系统重启/首次添加 widget 时 [onUpdate] 从已存数据回填。
 */
class QuroReplyWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        val (sender, text) = load(ctx)
        val rv = build(ctx, sender, text)
        ids.forEach { mgr.updateAppWidget(it, rv) }
    }

    override fun onEnabled(ctx: Context) {
        val (sender, text) = load(ctx)
        AppWidgetManager.getInstance(ctx)
            .updateAppWidget(ComponentName(ctx, QuroReplyWidget::class.java), build(ctx, sender, text))
    }

    companion object {
        private const val PREFS = "quro_widget_reply"

        /** 刷新桌面卡片：保存最新摘要并推送给所有已添加的 widget 实例。 */
        fun updateLatest(ctx: Context, sender: String, text: String) {
            val snippet = text.lineSequence().firstOrNull { it.isNotBlank() }?.take(200) ?: "（空回复）"
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("sender", sender)
                .putString("text", snippet)
                .apply()
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, QuroReplyWidget::class.java))
            if (ids.isEmpty()) return
            val rv = build(ctx, sender, snippet)
            ids.forEach { mgr.updateAppWidget(it, rv) }
        }

        private fun load(ctx: Context): Pair<String, String> {
            val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            return (p.getString("sender", "") ?: "") to (p.getString("text", "") ?: "")
        }

        private fun build(ctx: Context, sender: String, text: String): RemoteViews {
            val rv = RemoteViews(ctx.packageName, R.layout.widget_reply)
            // 品牌头
            rv.setTextViewText(R.id.widget_title, if (sender.isBlank()) "Zorv AI" else sender)
            rv.setTextViewText(R.id.widget_status, "AI 执行体 · ${java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date())}")
            // 聊天预览
            rv.setTextViewText(R.id.widget_text, if (text.isBlank()) "暂无新回复，点击 💬 开始对话" else text)
            // 主卡片点击 → 打开应用
            val mainIntent = Intent(ctx, QuroMainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val mainPi = PendingIntent.getActivity(
                ctx, 0, mainIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            rv.setOnClickPendingIntent(R.id.widget_root, mainPi)
            // 💬 按钮 → 打开应用对话
            val chatIntent = Intent(ctx, QuroMainActivity::class.java).apply {
                action = "android.intent.action.MAIN"
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("open_chat", true)
            }
            val chatPi = PendingIntent.getActivity(
                ctx, 1, chatIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            rv.setOnClickPendingIntent(R.id.widget_btn_chat, chatPi)
            // 🎤 按钮 → 切换语音球（通过广播通知 Service）
            val voiceIntent = Intent("com.ai.assistance.quro.TOGGLE_VOICE_BALL")
            val voicePi = PendingIntent.getBroadcast(
                ctx, 2, voiceIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            rv.setOnClickPendingIntent(R.id.widget_btn_voice, voicePi)
            return rv
        }
    }
}
