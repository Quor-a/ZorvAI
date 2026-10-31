package com.ai.assistance.quro.termux.app

import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.LinearLayout
import androidx.annotation.Nullable

/**
 * 增强终端宿主根视图（移植自 Termux `TermuxActivityRootView`，去品牌化精简版）。
 *
 * 解决软键盘（特别是 Gboard 候选栏）弹出时额外按键/终端被遮挡的问题：
 * 监听全局布局变化，当底部 space 视图不可见时给根视图加底部边距，
 * 把内容推离键盘遮挡区。
 */
class TermuxHostActivityRootView : LinearLayout, ViewTreeObserver.OnGlobalLayoutListener {

    var mActivity: TermuxHostActivity? = null
    var marginBottom: Int? = null
    var lastMarginBottom: Int? = null
    var lastMarginBottomTime: Long = 0
    var lastMarginBottomExtraTime: Long = 0

    private var mRootViewLoggingEnabled = false

    companion object {
        private const val LOG_TAG = "TermuxHostActivityRootView"
        private var mStatusBarHeight = 0
    }

    constructor(context: Context) : super(context)
    constructor(context: Context, @Nullable attrs: AttributeSet?) : super(context, attrs)
    constructor(context: Context, @Nullable attrs: AttributeSet?, defStyleAttr: Int) : super(context, attrs, defStyleAttr)

    fun setActivity(activity: TermuxHostActivity) {
        mActivity = activity
    }

    fun setIsRootViewLoggingEnabled(value: Boolean) {
        mRootViewLoggingEnabled = value
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)

        val margin = marginBottom
        if (margin != null) {
            val params = layoutParams as ViewGroup.MarginLayoutParams
            params.setMargins(0, 0, 0, margin)
            layoutParams = params
            marginBottom = null
            requestLayout()
        }
    }

    override fun onGlobalLayout() {
        val activity = mActivity ?: return
        val bottomSpaceView = findViewById<View>(activity.bottomSpaceViewId) ?: return
        val visibleBottomSpace = bottomSpaceView.getLocalVisibleRect(Rect())

        if (visibleBottomSpace) {
            if (marginBottom != null) {
                marginBottom = null
                requestLayout()
            }
        } else {
            val keyboardHeight = activity.getKeyboardHeight()
            if (keyboardHeight > 0) {
                val newMargin = keyboardHeight
                if (newMargin != marginBottom) {
                    marginBottom = newMargin
                    requestLayout()
                }
            }
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        viewTreeObserver.addOnGlobalLayoutListener(this)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        viewTreeObserver.removeOnGlobalLayoutListener(this)
    }
}