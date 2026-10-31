package com.ai.assistance.quro.termux.util

import android.util.Log

/**
 * 轻量日志工具（对齐 Termux Logger 的常用静态方法）。
 */
object Logger {
    const val LOG_TAG = "TermuxHost"

    @JvmStatic
    fun logVerbose(tag: String, message: String) = Log.v(tag, message)

    @JvmStatic
    fun logDebug(tag: String, message: String) = Log.d(tag, message)

    @JvmStatic
    fun logInfo(tag: String, message: String) = Log.i(tag, message)

    @JvmStatic
    fun logWarn(tag: String, message: String) = Log.w(tag, message)

    @JvmStatic
    fun logError(tag: String, message: String) = Log.e(tag, message)

    @JvmStatic
    fun logStackTraceWithMessage(tag: String, message: String, e: Throwable) = Log.e(tag, message, e)

    @JvmStatic
    fun logStackTrace(tag: String, e: Throwable) = Log.e(tag, "stacktrace", e)

    @JvmStatic
    fun logInfoAndShowToast(context: android.content.Context, tag: String, message: String) {
        Log.i(tag, message)
        showToast(context, message, true)
    }

    @JvmStatic
    fun logErrorAndShowToast(context: android.content.Context, tag: String, message: String) {
        Log.e(tag, message)
        showToast(context, message, true)
    }

    @JvmStatic
    fun showToast(context: android.content.Context, message: String, longDuration: Boolean) {
        val text = message
        if (text.isEmpty()) return
        val toast = android.widget.Toast.makeText(
            context,
            text,
            if (longDuration) android.widget.Toast.LENGTH_LONG else android.widget.Toast.LENGTH_SHORT
        )
        toast.setGravity(android.view.Gravity.TOP, 0, 0)
        toast.show()
    }
}