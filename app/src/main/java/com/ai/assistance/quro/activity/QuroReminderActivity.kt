package com.ai.assistance.quro.activity

import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import com.ai.assistance.quro.R

/**
 * 息屏 / 锁屏全屏提醒 Activity。
 *
 * 设计要点：
 * - 配合通知的 [android.app.Notification.fullScreenIntent] 使用：当定时任务 / AI 提醒
 *   在「息屏 / 锁屏」状态到达时，系统会以本 Activity 作为全屏覆盖界面弹出（覆盖在锁屏之上）。
 * - 通过主题 [com.ai.assistance.quro.R.style.Theme_Quro_Reminder] 的
 *   `android:windowShowWhenLocked` + `android:windowTurnScreenOn` 实现「锁屏之上显示 + 自动亮屏」。
 *   另在本 Activity 内对 API 27+ 调 `setShowWhenLocked(true)` / `setTurnScreenOn(true)` 双保险，
 *   并对较低版本用 `WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED` 兜底。
 * - 读 intent extra 的 [EXTRA_TITLE] / [EXTRA_TEXT] 渲染标题与正文；点击屏幕任意处 / 「知道了」即关闭。
 *
 * 不依赖任何 UI 状态（Compose 未组合时也能工作），是纯系统层提醒。
 */
class QuroReminderActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_TITLE = "reminder_title"
        const val EXTRA_TEXT = "reminder_text"
        const val EXTRA_BADGE = "reminder_badge"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 双保险：锁屏之上显示 + 自动亮屏（兼容不同 ROM / API 行为）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
            )
        }
        // 息屏时点亮屏幕（部分 ROM 需要 WakeLock 真正唤醒）
        val pm = getSystemService(POWER_SERVICE) as? PowerManager
        val wl = pm?.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "Quro:Reminder"
        )
        wl?.apply {
            setReferenceCounted(false)
            acquire(10 * 1000L)
        }

        setContentView(R.layout.activity_reminder)

        val title = intent.getStringExtra(EXTRA_TITLE) ?: "提醒"
        val text = intent.getStringExtra(EXTRA_TEXT) ?: ""
        val badge = intent.getStringExtra(EXTRA_BADGE) ?: "Zorv AI 提醒"

        findViewById<android.widget.TextView>(R.id.reminder_title).text = title
        findViewById<android.widget.TextView>(R.id.reminder_text).text = text
        findViewById<android.widget.TextView>(R.id.reminder_badge).text = badge

        val dismiss: () -> Unit = {
            wl?.release()
            finish()
        }
        findViewById<View>(R.id.reminder_root).setOnClickListener { dismiss() }
        findViewById<View>(R.id.reminder_dismiss).setOnClickListener { dismiss() }
    }

    override fun onDestroy() {
        super.onDestroy()
    }
}
