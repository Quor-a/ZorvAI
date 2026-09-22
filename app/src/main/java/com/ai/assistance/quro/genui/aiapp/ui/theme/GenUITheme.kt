package com.ai.assistance.quro.genui.aiapp.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * GenUI 主题 — 深青琥珀配色系统
 *
 * 设计理念：
 * - 主色：深青色(Teal) — 沉稳、专业、独特，避开 AI 紫俗套
 * - 辅助：暖琥珀色(Amber) — 温暖点缀，形成冷暖对比
 * - 中性色：暖灰色系(Stone) — 柔和、有温度、不刺眼
 * - 整体风格：精致、克制、有品质感
 */

// ========== 浅色主题 ==========
private val LightColors = lightColorScheme(
    // 主色 — 深青
    primary = Color(0xFF0D9488),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFCCFBF1),
    onPrimaryContainer = Color(0xFF134E4A),

    // 次色 — 暖琥珀
    secondary = Color(0xFFD97706),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFFEF3C7),
    onSecondaryContainer = Color(0xFF78350F),

    // 三级色 — 暖玫红（强调用）
    tertiary = Color(0xFFE11D48),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFE4E6),
    onTertiaryContainer = Color(0xFF881337),

    // 背景/表面 — 暖灰
    background = Color(0xFFFAFAF9),
    onBackground = Color(0xFF1C1917),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1C1917),
    surfaceVariant = Color(0xFFF5F5F4),
    onSurfaceVariant = Color(0xFF57534E),
    surfaceTint = Color(0xFF0D9488),

    // 边框/分割线
    outline = Color(0xFFD6D3D1),
    outlineVariant = Color(0xFFE7E5E4),

    // 错误色
    error = Color(0xFFE11D48),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFE4E6),
    onErrorContainer = Color(0xFF881337),

    // 反色
    inverseSurface = Color(0xFF292524),
    inverseOnSurface = Color(0xFFF5F5F4),
    inversePrimary = Color(0xFF5EEAD4),

    // 其他
    scrim = Color(0xFF0C0A09),
)

// ========== 深色主题 ==========
private val DarkColors = darkColorScheme(
    // 主色 — 深青亮色
    primary = Color(0xFF5EEAD4),
    onPrimary = Color(0xFF134E4A),
    primaryContainer = Color(0xFF115E59),
    onPrimaryContainer = Color(0xFFCCFBF1),

    // 次色 — 暖琥珀亮色
    secondary = Color(0xFFFCD34D),
    onSecondary = Color(0xFF78350F),
    secondaryContainer = Color(0xFFB45309),
    onSecondaryContainer = Color(0xFFFEF3C7),

    // 三级色
    tertiary = Color(0xFFFDA4AF),
    onTertiary = Color(0xFF881337),
    tertiaryContainer = Color(0xFF9F1239),
    onTertiaryContainer = Color(0xFFFFE4E6),

    // 背景/表面 — 深暖灰
    background = Color(0xFF0C0A09),
    onBackground = Color(0xFFE7E5E4),
    surface = Color(0xFF1C1917),
    onSurface = Color(0xFFE7E5E4),
    surfaceVariant = Color(0xFF292524),
    onSurfaceVariant = Color(0xFFA8A29E),
    surfaceTint = Color(0xFF5EEAD4),

    // 边框/分割线
    outline = Color(0xFF44403C),
    outlineVariant = Color(0xFF292524),

    // 错误色
    error = Color(0xFFFDA4AF),
    onError = Color(0xFF881337),
    errorContainer = Color(0xFF9F1239),
    onErrorContainer = Color(0xFFFFE4E6),

    // 反色
    inverseSurface = Color(0xFFE7E5E4),
    inverseOnSurface = Color(0xFF292524),
    inversePrimary = Color(0xFF0D9488),

    // 其他
    scrim = Color(0xFF000000),
)

// ========== 自定义排版 ==========
private val GenUITypography = Typography(
    displayLarge = TextStyle(
        fontSize = 32.sp,
        fontWeight = FontWeight.Bold,
        lineHeight = 40.sp,
        letterSpacing = (-0.5).sp
    ),
    headlineLarge = TextStyle(
        fontSize = 24.sp,
        fontWeight = FontWeight.SemiBold,
        lineHeight = 32.sp
    ),
    headlineMedium = TextStyle(
        fontSize = 20.sp,
        fontWeight = FontWeight.SemiBold,
        lineHeight = 28.sp
    ),
    titleLarge = TextStyle(
        fontSize = 18.sp,
        fontWeight = FontWeight.SemiBold,
        lineHeight = 26.sp
    ),
    titleMedium = TextStyle(
        fontSize = 16.sp,
        fontWeight = FontWeight.Medium,
        lineHeight = 24.sp
    ),
    titleSmall = TextStyle(
        fontSize = 14.sp,
        fontWeight = FontWeight.Medium,
        lineHeight = 20.sp
    ),
    bodyLarge = TextStyle(
        fontSize = 15.sp,
        fontWeight = FontWeight.Normal,
        lineHeight = 22.sp
    ),
    bodyMedium = TextStyle(
        fontSize = 13.sp,
        fontWeight = FontWeight.Normal,
        lineHeight = 19.sp
    ),
    bodySmall = TextStyle(
        fontSize = 12.sp,
        fontWeight = FontWeight.Normal,
        lineHeight = 18.sp
    ),
    labelLarge = TextStyle(
        fontSize = 14.sp,
        fontWeight = FontWeight.Medium,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp
    ),
    labelMedium = TextStyle(
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        lineHeight = 16.sp,
        letterSpacing = 0.3.sp
    ),
    labelSmall = TextStyle(
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        lineHeight = 14.sp,
        letterSpacing = 0.5.sp
    )
)

/**
 * GenUI 应用主题
 *
 * @param darkTheme 是否使用深色主题，默认跟随系统
 * @param content 主题内容
 */
@Composable
fun GenUITheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colors = if (darkTheme) DarkColors else LightColors

    MaterialTheme(
        colorScheme = colors,
        typography = GenUITypography,
        content = content
    )
}
