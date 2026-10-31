package com.ai.assistance.quro.termux.util

/**
 * 夜间模式枚举（对齐 Termux NightMode）。
 */
enum class NightMode(val mode: String, val value: Int) {
    SYSTEM("system", 0),
    YES("yes", 1),
    NO("no", 2);

    companion object {
        @JvmStatic
        fun modeOf(mode: String?): NightMode {
            return when (mode?.lowercase()) {
                "yes", "true", "1" -> YES
                "no", "false", "0" -> NO
                else -> SYSTEM
            }
        }

        @JvmStatic
        fun valueOf(value: Int): NightMode {
            return when (value) {
                1 -> YES
                2 -> NO
                else -> SYSTEM
            }
        }

        @Volatile
        private var appNightMode: NightMode = SYSTEM

        @JvmStatic
        fun setAppNightMode(mode: NightMode) {
            appNightMode = mode
        }

        @JvmStatic
        fun getAppNightMode(): NightMode = appNightMode
    }
}