package com.ai.assistance.quro.termux.settings

import android.content.Context
import android.content.SharedPreferences

/**
 * 终端宿主共享偏好（对齐 TermuxAppSharedPreferences 的核心键）。
 */
class TermuxAppSharedPreferences private constructor(
    private val context: Context,
    private val preferences: SharedPreferences
) {

    companion object {
        private const val PREF_FILE = "com.ai.assistance.quro.termux_preferences"

        private const val PREF_KEY_FONT_SIZE = "font_size"
        private const val PREF_KEY_KEEP_SCREEN_ON = "keep_screen_on"
        private const val PREF_KEY_SHOW_TERMINAL_TOOLBAR = "show_terminal_toolbar"
        private const val PREF_KEY_SOFT_KEYBOARD_ENABLED = "soft_keyboard_enabled"
        private const val PREF_KEY_SOFT_KEYBOARD_ENABLED_ONLY_IF_NO_HARDWARE = "soft_keyboard_enabled_only_if_no_hardware"
        private const val PREF_KEY_TERMINAL_MARGIN_ADJUSTMENT = "terminal_margin_adjustment"
        private const val PREF_KEY_CURRENT_SESSION = "current_session"

        const val DEFAULT_FONT_SIZE = 14
        const val MIN_FONT_SIZE = 6
        const val MAX_FONT_SIZE = 32

        @Volatile
        private var instance: TermuxAppSharedPreferences? = null

        @JvmStatic
        fun build(context: Context, forceCreate: Boolean): TermuxAppSharedPreferences? {
            if (instance == null) {
                synchronized(this) {
                    if (instance == null) {
                        val prefs = context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
                        instance = TermuxAppSharedPreferences(context.applicationContext, prefs)
                    }
                }
            }
            return instance
        }

        @JvmStatic
        fun getInstance(): TermuxAppSharedPreferences? = instance
    }

    fun shouldShowTerminalToolbar(): Boolean =
        preferences.getBoolean(PREF_KEY_SHOW_TERMINAL_TOOLBAR, true)

    fun toogleShowTerminalToolbar(): Boolean {
        val current = shouldShowTerminalToolbar()
        val newValue = !current
        preferences.edit().putBoolean(PREF_KEY_SHOW_TERMINAL_TOOLBAR, newValue).apply()
        return newValue
    }

    fun setShowTerminalToolbar(value: Boolean) {
        preferences.edit().putBoolean(PREF_KEY_SHOW_TERMINAL_TOOLBAR, value).apply()
    }

    fun isTerminalMarginAdjustmentEnabled(): Boolean =
        preferences.getBoolean(PREF_KEY_TERMINAL_MARGIN_ADJUSTMENT, true)

    fun isSoftKeyboardEnabled(): Boolean =
        preferences.getBoolean(PREF_KEY_SOFT_KEYBOARD_ENABLED, true)

    fun setSoftKeyboardEnabled(value: Boolean) {
        preferences.edit().putBoolean(PREF_KEY_SOFT_KEYBOARD_ENABLED, value).apply()
    }

    fun isSoftKeyboardEnabledOnlyIfNoHardware(): Boolean =
        preferences.getBoolean(PREF_KEY_SOFT_KEYBOARD_ENABLED_ONLY_IF_NO_HARDWARE, false)

    fun setSoftKeyboardEnabledOnlyIfNoHardware(value: Boolean) {
        preferences.edit().putBoolean(PREF_KEY_SOFT_KEYBOARD_ENABLED_ONLY_IF_NO_HARDWARE, value).apply()
    }

    fun shouldKeepScreenOn(): Boolean =
        preferences.getBoolean(PREF_KEY_KEEP_SCREEN_ON, false)

    fun setKeepScreenOn(value: Boolean) {
        preferences.edit().putBoolean(PREF_KEY_KEEP_SCREEN_ON, value).apply()
    }

    fun getFontSize(): Int =
        preferences.getInt(PREF_KEY_FONT_SIZE, DEFAULT_FONT_SIZE)

    fun setFontSize(value: Int) {
        val clamped = value.coerceIn(MIN_FONT_SIZE, MAX_FONT_SIZE)
        preferences.edit().putInt(PREF_KEY_FONT_SIZE, clamped).apply()
    }

    fun changeFontSize(increase: Boolean) {
        val delta = if (increase) 1 else -1
        setFontSize(getFontSize() + delta)
    }

    fun isTerminalViewKeyLoggingEnabled(): Boolean = false

    fun setCurrentSession(sessionHandle: String?) {
        preferences.edit().putString(PREF_KEY_CURRENT_SESSION, sessionHandle).apply()
    }

    fun getCurrentSession(): String? =
        preferences.getString(PREF_KEY_CURRENT_SESSION, null)
}