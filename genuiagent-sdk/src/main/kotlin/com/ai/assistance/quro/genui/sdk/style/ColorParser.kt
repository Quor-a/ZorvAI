package com.ai.assistance.quro.genui.sdk.style

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Color.Companion.Black
import androidx.compose.ui.graphics.Color.Companion.Blue
import androidx.compose.ui.graphics.Color.Companion.Green
import androidx.compose.ui.graphics.Color.Companion.Gray

import androidx.compose.ui.graphics.Color.Companion.Red
import androidx.compose.ui.graphics.Color.Companion.Transparent
import androidx.compose.ui.graphics.Color.Companion.Unspecified
import androidx.compose.ui.graphics.Color.Companion.White
import androidx.compose.ui.graphics.Color.Companion.Yellow
import java.util.Locale

/**
 * 颜色解析器，支持多种颜色格式：
 * - 命名颜色 (red, blue, green, transparent 等)
 * - 十六进制颜色 (#RGB, #RRGGBB, #AARRGGBB)
 * - 主题角色颜色 (primary, onPrimary, surface 等)
 */
object ColorParser {

    private val namedColors = mapOf(
        "black" to Black,
        "white" to White,
        "red" to Red,
        "green" to Green,
        "blue" to Blue,
        "yellow" to Yellow,
        "gray" to Gray,
        "grey" to Gray,
        "orange" to Color(0xFFFFA500),
        "purple" to Color(0xFF800080.toInt()),
        "transparent" to Transparent
    )

    private val themeRoleNames = setOf(
        "primary", "onprimary", "primarycontainer", "onprimarycontainer",
        "secondary", "onsecondary", "tertiary", "ontertiary",
        "background", "onbackground", "surface", "onsurface",
        "surfacevariant", "onsurfacevariant", "surfacecontainer",
        "error", "onerror", "outline", "outlinevariant", "scrim"
    )

    /**
     * 将颜色字符串解析为 Compose Color
     * @param value 颜色字符串（命名颜色、十六进制或主题角色）
     * @param fallback 解析失败时的回退颜色
     * @return 解析后的颜色，如果解析失败且无主题色匹配则返回 fallback
     */
    fun toColor(value: String?, fallback: Color = Unspecified): Color {
        if (value.isNullOrBlank()) return fallback

        val trimmed = value.trim()

        // 主题角色颜色返回 Unspecified，由调用方通过 resolveThemeColor 处理
        if (trimmed.lowercase(Locale.ROOT) in themeRoleNames) {
            return Unspecified
        }

        // 命名颜色
        namedColors[trimmed.lowercase(Locale.ROOT)]?.let { return it }

        // css 函数式颜色 rgba(r,g,b,a) / rgb(r,g,b)
        parseCssFunction(trimmed)?.let { return it }

        // 十六进制颜色
        parseHex(trimmed)?.let { return it }

        return fallback
    }

    private fun parseHex(value: String): Color? {
        if (!value.startsWith("#")) return null

        val hex = value.removePrefix("#")
        return try {
            when (hex.length) {
                3 -> {
                    // #RGB 格式
                    val r = hexValue(hex[0]) * 17 / 255f
                    val g = hexValue(hex[1]) * 17 / 255f
                    val b = hexValue(hex[2]) * 17 / 255f
                    Color(r, g, b)
                }
                4 -> {
                    // #RGBA 格式
                    val r = hexValue(hex[0]) * 17 / 255f
                    val g = hexValue(hex[1]) * 17 / 255f
                    val b = hexValue(hex[2]) * 17 / 255f
                    val a = hexValue(hex[3])
                    Color(r, g, b, a)
                }
                6 -> {
                    // #RRGGBB 格式
                    Color(android.graphics.Color.parseColor("#$hex"))
                }
                8 -> {
                    // #AARRGGBB 格式
                    Color(hex.toLong(16).toInt())
                }
                else -> null
            }
        } catch (e: NumberFormatException) {
            null
        }
    }

    private fun hexValue(c: Char): Float = Character.digit(c, 16) / 15f

    /**
     * 解析主题角色颜色
     * @param role 主题角色名称 (如 "primary", "onSurface")
     * @param scheme 颜色方案
     * @return 对应的主题颜色，如果不是有效角色则返回 null
     */
    fun resolveThemeColor(role: String, scheme: GenUIColorScheme): Color? {
        return when (role.lowercase(Locale.ROOT)) {
            "primary" -> scheme.primary
            "onprimary" -> scheme.onPrimary
            "primarycontainer" -> scheme.primaryContainer
            "onprimarycontainer" -> scheme.onPrimaryContainer
            "secondary" -> scheme.secondary
            "onsecondary" -> scheme.onSecondary
            "tertiary" -> scheme.tertiary
            "ontertiary" -> scheme.onTertiary
            "background" -> scheme.background
            "onbackground" -> scheme.onBackground
            "surface" -> scheme.surface
            "onsurface" -> scheme.onSurface
            "surfacevariant" -> scheme.surfaceVariant
            "onsurfacevariant" -> scheme.onSurfaceVariant
            "surfacecontainer" -> scheme.surfaceContainer
            "error" -> scheme.error
            "onerror" -> scheme.onError
            "outline" -> scheme.outline
            "outlinevariant" -> scheme.outlineVariant
            "scrim" -> scheme.scrim
            else -> null
        }
    }
}

    /** css 函数式颜色：rgba(255, 0, 0, 0.5) / rgb(255, 0, 0) */
    private fun parseCssFunction(value: String): Color? {
        val m = Regex("^rgba?\\((\\d+)\\s*,\\s*(\\d+)\\s*,\\s*(\\d+)\\s*(?:,\\s*([0-9.]+))?\\)$", RegexOption.IGNORE_CASE).find(value.trim()) ?: return null
        val (r, g, b) = m.destructured
        val a = m.groupValues.getOrNull(4)?.toFloatOrNull() ?: 1f
        return Color(
            red = r.toInt().coerceIn(0, 255) / 255f,
            green = g.toInt().coerceIn(0, 255) / 255f,
            blue = b.toInt().coerceIn(0, 255) / 255f,
            alpha = a.coerceIn(0f, 1f)
        )
    }
