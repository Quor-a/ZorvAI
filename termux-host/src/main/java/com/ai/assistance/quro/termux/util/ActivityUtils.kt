package com.ai.assistance.quro.termux.util

import android.content.Context
import android.content.Intent

/**
 * Activity 工具（对齐 Termux ActivityUtils）。
 */
object ActivityUtils {

    @JvmStatic
    fun startActivity(context: Context, intent: Intent) {
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            Logger.logStackTraceWithMessage("ActivityUtils", "startActivity failed", e)
        }
    }
}