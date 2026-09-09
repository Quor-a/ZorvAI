package com.ai.assistance.quro.util

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

/**
 * 应用语言策略：不内置任何国家/地区语言资源包，直接引用手机系统语言。
 *
 * - 跟随系统语言（默认开启）：AppCompatDelegate.setApplicationLocales(EMPTY)
 *   → Android 用手机系统当前语言，系统切换语言时应用跟着切换。
 * - 关闭跟随：固定在应用首次启动时的系统语言（APP_DEFAULT_LOCALE），
 *   不随系统后续切换；全程不打包任何 country-specific 语言资源。
 */
object QuroLocale {
    /** 应用首次启动时捕获的系统语言，作为「关闭跟随」的回退基准。 */
    @Volatile
    var appDefaultLocale: LocaleListCompat = LocaleListCompat.getDefault()
        private set

    /** 仅在尚未捕获时记录首次启动的系统语言（幂等，后续不被覆盖）。 */
    fun captureDefaultIfNeeded() {
        if (appDefaultLocale.size() == 0) {
            appDefaultLocale = LocaleListCompat.getDefault()
        }
    }

    /**
     * 应用语言设置。
     * @param followSystem true=跟随系统语言（EMPTY，引用系统语言）；false=固定首次启动时的系统语言。
     */
    fun apply(followSystem: Boolean) {
        captureDefaultIfNeeded()
        val locales = if (followSystem) LocaleListCompat.getEmptyLocaleList() else appDefaultLocale
        AppCompatDelegate.setApplicationLocales(locales)
    }
}
