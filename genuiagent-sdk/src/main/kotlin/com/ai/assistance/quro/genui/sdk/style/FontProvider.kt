package com.ai.assistance.quro.genui.sdk.style

import android.content.Context
import android.graphics.Typeface
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import java.util.Locale

/**
 * 字体提供器，管理系统字体和自定义字体
 * 支持按名称解析字体，并提供 CJK 和阿拉伯语字体的特殊处理
 */
object FontProvider {

    const val CJK_ALIAS = "cjk"
    const val ARABIC_ALIAS = "arabic"

    private val customFonts = mutableMapOf<String, FontFamily>()

    val Default: FontFamily get() = FontFamily.Default
    val SansSerif: FontFamily get() = FontFamily.SansSerif
    val Serif: FontFamily get() = FontFamily.Serif
    val Monospace: FontFamily get() = FontFamily.Monospace

    /**
     * 注册自定义字体
     */
    fun registerFont(name: String, family: FontFamily) {
        customFonts[name.lowercase(Locale.ROOT)] = family
    }

    /**
     * 从 assets 加载字体文件
     */
    fun loadFontFamilyFromAsset(context: Context, path: String): FontFamily {
        return runCatching {
            FontFamily(Font(path, context.assets))
        }.getOrDefault(FontFamily.SansSerif)
    }

    /**
     * 注册 Typeface 字体
     * 注意：由于 Compose 公共 API 限制，Typeface 无法直接转换为 FontFamily，
     * 当前回退到 SansSerif。如需注册自定义字体，请使用 loadFontFamilyFromAsset 或 registerFontFamily。
     */
    fun registerTypeface(name: String, typeface: Typeface) {
        registerFont(name, FontFamily.SansSerif)
    }

    /**
     * 注册多个 Font 组成的字体族
     */
    fun registerFontFamily(name: String, vararg fonts: Font) {
        registerFont(name, FontFamily(*fonts))
    }

    /**
     * 根据字体名称解析 FontFamily
     * 支持的别名：sans-serif, sans, default, serif, monospace, mono,
     *             cjk, zh, kr, ko, jp, ja, ar, arabic
     */
    fun resolve(fontFamily: String, locale: Locale = Locale.getDefault()): FontFamily {
        val lower = fontFamily.lowercase(Locale.ROOT)

        return when (lower) {
            "sans-serif", "sansserif", "sans", "default" -> FontFamily.SansSerif
            "serif" -> FontFamily.Serif
            "monospace", "mono" -> FontFamily.Monospace
            CJK_ALIAS, "zh", "kr", "ko", "jp", "ja" -> resolveCjk()
            "ar", ARABIC_ALIAS -> resolveArabic()
            else -> {
                // 先查自定义字体
                customFonts[lower]?.let { return it }
                // 按 locale 解析
                resolveForLocale(locale)
            }
        }
    }

    /**
     * 根据语言环境解析字体
     */
    fun resolveForLocale(locale: Locale): FontFamily {
        return when (locale.language.lowercase(Locale.ROOT)) {
            "zh", "ko", "ja" -> resolveCjk()
            "ar", "ur", "he", "fa" -> resolveArabic()
            else -> FontFamily.SansSerif
        }
    }

    private fun resolveCjk(): FontFamily {
        return customFonts[CJK_ALIAS] ?: FontFamily.SansSerif
    }

    private fun resolveArabic(): FontFamily {
        return customFonts[ARABIC_ALIAS] ?: FontFamily.SansSerif
    }
}
