package com.ai.assistance.quro.service

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.inputmethodservice.InputMethodService
import android.widget.FrameLayout
import android.widget.TextView

/**
 * Quro AI 智能体键盘（Agent IME）。
 *
 * 这是「给 AI agent 用的工具执行端」，不是给人手打的键盘：
 * - UI 极简：键盘区只显示一颗 🤖 悬浮球，点击弹出系统输入法选择器以便切回普通键盘，不干扰目标 App。
 * - AI 通过同进程单例 [instance] 调用 [typeText] / [pressEnter] / [clearText]，
 *   把文本直接 commitText 进当前聚焦的输入框（如 WPS 文档）。
 *
 * 系统限制（Android 固有）：用户需在「系统设置 → 语言与输入法」启用本键盘并切到它，
 * 且目标 App 当前有聚焦的输入框时，[isInputActive] 才为 true，才能打字。
 */
class QuroAiKeyboardService : InputMethodService() {

    companion object {
        /** 同进程单例，供 App 内工具（ai_type_text / ai_press_enter）直接调用。 */
        @Volatile var instance: QuroAiKeyboardService? = null
            private set
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var ball: TextView? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onCreateInputView(): View {
        val density = resources.displayMetrics.density
        val size = (48 * density).toInt()
        val ball = TextView(this).apply {
            text = "🤖"
            textSize = 24f
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#1E88E5"))
            }
            val pad = (8 * density).toInt()
            setPadding(pad, pad, pad, pad)
            // 点击悬浮球：弹出系统输入法选择器，方便用户切回普通键盘
            setOnClickListener {
                runCatching {
                    val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                    imm.showInputMethodPicker()
                }
            }
        }
        this.ball = ball
        return FrameLayout(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            addView(
                ball,
                FrameLayout.LayoutParams(size, size).apply { gravity = Gravity.CENTER },
            )
        }
    }

    /** 当前是否有可输入的聚焦框（IME 已激活且目标 App 有焦点输入框）。 */
    fun isInputActive(): Boolean = currentInputConnection != null

    /** AI 调用：把文本写入当前聚焦输入框（等效于手动打字）。返回是否成功。 */
    fun typeText(text: String): Boolean {
        val ic = currentInputConnection ?: return false
        val ok = ic.commitText(text, 1)
        pulse()
        return ok
    }

    /** AI 调用：发送回车键（多用于提交/换行）。返回是否成功。 */
    fun pressEnter(): Boolean {
        val ic = currentInputConnection ?: return false
        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
        return true
    }

    /** AI 调用：清空当前输入框已有内容（删除光标前/后全部文本）。返回是否成功。 */
    fun clearText(): Boolean {
        val ic = currentInputConnection ?: return false
        val before = ic.getTextBeforeCursor(10000, 0)?.length ?: 0
        if (before > 0) ic.deleteSurroundingText(before, 0)
        val after = ic.getTextAfterCursor(10000, 0)?.length ?: 0
        if (after > 0) ic.deleteSurroundingText(0, after)
        return true
    }

    /** 输入时让悬浮球轻微闪烁，给用户可见反馈。 */
    private fun pulse() {
        val b = ball ?: return
        b.alpha = 0.4f
        mainHandler.postDelayed({ b.alpha = 1f }, 150)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }
}
