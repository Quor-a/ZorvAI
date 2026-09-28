package com.ai.assistance.quro.util

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

/**
 * 应用语言策略。
 *
 * - "system"（默认）：跟随手机系统语言，系统切换语言时应用跟着切换。
 * - 具体语言代码（如 "zh" / "en" / "ja"）：固定为所选语言，不随系统变化；
 *   未提供对应语言资源时 Android 自动回退到默认(中文)。
 * 切换时 Android 会自动重建当前 Activity 生效。
 */
object QuroLocale {
    /** 应用首次启动时捕获的系统语言，作为无显式选择时的回退基准。 */
    @Volatile
    var appDefaultLocale: LocaleListCompat = LocaleListCompat.getDefault()
        private set

    fun captureDefaultIfNeeded() {
        if (appDefaultLocale.size() == 0) {
            appDefaultLocale = LocaleListCompat.getDefault()
        }
    }

    /**
     * 应用语言设置。
     * @param language "system"=跟随系统；否则为 BCP-47 语言标签（如 "en"/"ja"/"zh-rCN"），空串视为系统。
     */
    fun apply(language: String) {
        captureDefaultIfNeeded()
        val locales = when {
            language.isBlank() || language == "system" -> LocaleListCompat.getEmptyLocaleList()
            else -> LocaleListCompat.forLanguageTags(language)
        }
        AppCompatDelegate.setApplicationLocales(locales)
    }

    /** 语言代码 → 中文显示名（用于设置界面与系统提示词）。 */
    val LANGUAGE_NAMES: Map<String, String> = mapOf(
        "system" to "跟随系统",
        "zh" to "简体中文", "zh-rCN" to "简体中文", "zh-rTW" to "繁體中文",
        "en" to "English", "ja" to "日本語", "ko" to "한국어",
        "fr" to "Français", "de" to "Deutsch", "es" to "Español",
        "ru" to "Русский", "pt" to "Português", "ar" to "العربية", "hi" to "हिन्दी"
    )

    /** 大国语言列表（设置界面选择器使用；小国语言后续补充）。 */
    val MAJOR_LANGUAGES: List<Pair<String, String>> = listOf(
        "system" to (LANGUAGE_NAMES["system"] ?: "跟随系统"),
        "zh" to (LANGUAGE_NAMES["zh"] ?: "简体中文"),
        "en" to (LANGUAGE_NAMES["en"] ?: "English"),
        "ja" to (LANGUAGE_NAMES["ja"] ?: "日本語"),
        "ko" to (LANGUAGE_NAMES["ko"] ?: "한국어"),
        "fr" to (LANGUAGE_NAMES["fr"] ?: "Français"),
        "de" to (LANGUAGE_NAMES["de"] ?: "Deutsch"),
        "es" to (LANGUAGE_NAMES["es"] ?: "Español"),
        "ru" to (LANGUAGE_NAMES["ru"] ?: "Русский"),
        "pt" to (LANGUAGE_NAMES["pt"] ?: "Português"),
        "ar" to (LANGUAGE_NAMES["ar"] ?: "العربية"),
        "hi" to (LANGUAGE_NAMES["hi"] ?: "हिन्दी")
    )
}
