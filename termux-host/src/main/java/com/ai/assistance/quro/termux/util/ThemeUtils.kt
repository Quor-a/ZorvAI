package com.ai.assistance.quro.termux.util

import android.app.Activity
import android.content.Context
import androidx.appcompat.app.AppCompatDelegate

/**
 * 主题工具（对齐 Termux ThemeUtils）。
 */
object ThemeUtils {

    @JvmStatic
    fun setAppNightMode(activity: Activity, nightMode: NightMode, recreateActivity: Boolean) {
        val mode = when (nightMode) {
            NightMode.YES -> AppCompatDelegate.MODE_NIGHT_YES
            NightMode.NO -> AppCompatDelegate.MODE_NIGHT_NO
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        AppCompatDelegate.setDefaultNightMode(mode)
    }

    /** 当前是否应启用暗色主题。 */
    @JvmStatic
    fun shouldEnableDarkTheme(context: Context, nightMode: NightMode): Boolean {
        return when (nightMode) {
            NightMode.YES -> true
            NightMode.NO -> false
            else -> {
                val uiMode = context.resources.configuration.uiMode and
                    android.content.res.Configuration.UI_MODE_NIGHT_MASK
                uiMode == android.content.res.Configuration.UI_MODE_NIGHT_YES
            }
        }
    }
}