package com.ai.assistance.quro.termux.app

import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver

/**
 * 全屏模式布局修正（移植自 Termux `FullScreenWorkAround`）。
 *
 * 解决全屏模式下软键盘弹出时 ExtraKeysView 被遮挡的问题：
 * 监听全局布局变化，当软键盘可见时把内容高度压缩到键盘上方 + 导航栏高度，
 * 保证额外按键栏始终可见。
 */
class FullScreenWorkAround private constructor(activity: TermuxHostActivity) {

    private val mChildOfContent: View
    private val mViewGroupLayoutParams: ViewGroup.LayoutParams
    private val mNavBarHeight: Int
    private var mUsableHeightPrevious = 0

    init {
        val content: ViewGroup = activity.findViewById(android.R.id.content)
        mChildOfContent = content.getChildAt(0)
        mViewGroupLayoutParams = mChildOfContent.layoutParams
        mNavBarHeight = activity.navBarHeight
        mChildOfContent.viewTreeObserver.addOnGlobalLayoutListener(GlobalLayoutListener())
    }

    private inner class GlobalLayoutListener : ViewTreeObserver.OnGlobalLayoutListener {
        override fun onGlobalLayout() {
            possiblyResizeChildOfContent()
        }
    }

    private fun possiblyResizeChildOfContent() {
        val usableHeightNow = computeUsableHeight()
        if (usableHeightNow != mUsableHeightPrevious) {
            val usableHeightSansKeyboard = mChildOfContent.rootView.height
            val heightDifference = usableHeightSansKeyboard - usableHeightNow
            if (heightDifference > usableHeightSansKeyboard / 4) {
                // 键盘刚弹出：把内容高度压到键盘上方 + 导航栏
                mViewGroupLayoutParams.height = (usableHeightSansKeyboard - heightDifference) + mNavBarHeight
            } else {
                // 键盘刚隐藏：恢复全高
                mViewGroupLayoutParams.height = usableHeightSansKeyboard
            }
            mChildOfContent.requestLayout()
            mUsableHeightPrevious = usableHeightNow
        }
    }

    private fun computeUsableHeight(): Int {
        val r = Rect()
        mChildOfContent.getWindowVisibleDisplayFrame(r)
        return r.bottom - r.top
    }

    companion object {
        @JvmStatic
        fun apply(activity: TermuxHostActivity) {
            FullScreenWorkAround(activity)
        }
    }
}