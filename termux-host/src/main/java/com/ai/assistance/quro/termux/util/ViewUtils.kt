package com.ai.assistance.quro.termux.util

import android.view.View
import android.view.ViewGroup

/**
 * 视图工具（对齐 Termux ViewUtils）。
 */
object ViewUtils {

    @JvmStatic
    fun setLayoutMarginsInDp(view: View, leftDp: Int, topDp: Int, rightDp: Int, bottomDp: Int) {
        val density = view.resources.displayMetrics.density
        val lp = view.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        lp.setMargins(
            (leftDp * density).toInt(),
            (topDp * density).toInt(),
            (rightDp * density).toInt(),
            (bottomDp * density).toInt()
        )
        view.layoutParams = lp
    }
}