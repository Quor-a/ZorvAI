package com.ai.assistance.quro.termux.util

import android.app.Activity
import android.content.Context
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.view.WindowManager

/**
 * 软键盘工具（对齐 Termux KeyboardUtils 常用方法）。
 */
object KeyboardUtils {

    @JvmStatic
    fun showSoftKeyboard(context: Context, view: View) {
        try {
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            view.requestFocus()
            imm.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
        } catch (e: Exception) {
            Logger.logStackTraceWithMessage("KeyboardUtils", "showSoftKeyboard failed", e)
        }
    }

    @JvmStatic
    fun hideSoftKeyboard(context: Context, view: View) {
        try {
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(view.windowToken, 0)
        } catch (e: Exception) {
            Logger.logStackTraceWithMessage("KeyboardUtils", "hideSoftKeyboard failed", e)
        }
    }

    @JvmStatic
    fun toggleSoftKeyboard(context: Context) {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.toggleSoftInput(InputMethodManager.SHOW_IMPLICIT, 0)
    }

    @JvmStatic
    fun disableSoftKeyboard(context: Context, view: View) {
        // 通过设置输入法不可见标志实现
        setSoftInputModeAdjustResize(context)
        hideSoftKeyboard(context, view)
    }

    @JvmStatic
    fun clearDisableSoftKeyboardFlags(context: Context) {
        // 清除 adjustResize 标志
        if (context is Activity) {
            context.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
    }

    @JvmStatic
    fun areDisableSoftKeyboardFlagsSet(context: Context): Boolean {
        if (context is Activity) {
            val mode = context.window.attributes.softInputMode
            return mode and WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST == WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
        }
        return false
    }

    @JvmStatic
    fun shouldSoftKeyboardBeDisabled(
        context: Context,
        softKeyboardEnabled: Boolean,
        onlyIfNoHardware: Boolean
    ): Boolean {
        if (!softKeyboardEnabled) return true
        if (onlyIfNoHardware) {
            val hasHardwareKeyboard = context.resources.configuration.keyboard != android.content.res.Configuration.KEYBOARD_NOKEYS
            return !hasHardwareKeyboard
        }
        return false
    }

    @JvmStatic
    fun setSoftInputModeAdjustResize(context: Context) {
        if (context is Activity) {
            context.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
    }

    @JvmStatic
    fun setSoftKeyboardAlwaysHiddenFlags(context: Context) {
        if (context is Activity) {
            context.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN or
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
    }

    @JvmStatic
    fun setSoftKeyboardVisibility(
        showSoftKeyboardRunnable: Runnable,
        context: Context,
        view: View,
        show: Boolean
    ) {
        if (show) {
            view.postDelayed(showSoftKeyboardRunnable, 100)
        } else {
            hideSoftKeyboard(context, view)
        }
    }
}