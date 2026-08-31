package com.ai.assistance.quro.core.ui.dynamicui

import androidx.compose.ui.graphics.Color

/**
 * 动态 UI 颜色解析。
 *
 * AI 产出的颜色字符串五花八门（`#f00`、`#ff8800`、`#80ff8800`、`red`、`primary`），
 * 这里统一兜底：解析失败一律返回 null，由调用方回落到主题默认色，
 * 绝不因为一个坏颜色值就让整块 UI 崩掉。
 */
object QuroUiColor {

    /** 常用语义色名。AI 更爱写名字而不是十六进制。 */
    private val NAMED: Map<String, Color> = mapOf(
        // 基础
        "black" to Color(0xFF000000),
        "white" to Color(0xFFFFFFFF),
        "gray" to Color(0xFF9E9E9E),
        "grey" to Color(0xFF9E9E9E),
        "red" to Color(0xFFF44336),
        "green" to Color(0xFF4CAF50),
        "blue" to Color(0xFF2196F3),
        "yellow" to Color(0xFFFFEB3B),
        "orange" to Color(0xFFFF9800),
        "purple" to Color(0xFF9C27B0),
        "pink" to Color(0xFFE91E63),
        "cyan" to Color(0xFF00BCD4),
        "teal" to Color(0xFF009688),
        "indigo" to Color(0xFF3F51B5),
        "brown" to Color(0xFF795548),
        "lime" to Color(0xFFCDDC39),
        "amber" to Color(0xFFFFC107),
        // 语义（由调用方决定具体取值，这里给出 Material 基线色）
        "primary" to Color(0xFF6750A4),
        "secondary" to Color(0xFF625B71),
        "error" to Color(0xFFB3261E),
        "warning" to Color(0xFFFF9800),
        "success" to Color(0xFF4CAF50),
        "info" to Color(0xFF2196F3),
        "muted" to Color(0xFF9E9E9E),
        "transparent" to Color.Transparent,
    )

    /**
     * 解析颜色字符串。失败返回 null。
     * 支持：`#RGB`、`#RRGGBB`、`#AARRGGBB`、带或不带 `#`、以及 [NAMED] 中的名字。
     */
    fun parse(raw: String?): Color? {
        if (raw.isNullOrBlank()) return null
        val s = raw.trim().lowercase()

        NAMED[s]?.let { return it }

        val hex = s.removePrefix("#")
        if (hex.isEmpty()) return null
        if (hex.any { it !in '0'..'9' && it !in 'a'..'f' }) return null

        return try {
            when (hex.length) {
                3 -> { // #RGB -> #RRGGBB（每位重复一次）
                    val r = hex[0].toString().repeat(2).toInt(16)
                    val g = hex[1].toString().repeat(2).toInt(16)
                    val b = hex[2].toString().repeat(2).toInt(16)
                    Color(0xFF000000.toInt() or (r shl 16) or (g shl 8) or b)
                }
                4 -> { // #ARGB -> #AARRGGBB
                    val a = hex[0].toString().repeat(2).toInt(16)
                    val r = hex[1].toString().repeat(2).toInt(16)
                    val g = hex[2].toString().repeat(2).toInt(16)
                    val b = hex[3].toString().repeat(2).toInt(16)
                    Color((a shl 24) or (r shl 16) or (g shl 8) or b)
                }
                6 -> Color(0xFF000000.toInt() or hex.toLong(16).toInt())
                8 -> Color(hex.toLong(16).toInt())
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }

    /** 解析失败时用于占位的主题中性色。 */
    fun parseOr(raw: String?, fallback: Color): Color = parse(raw) ?: fallback
}
