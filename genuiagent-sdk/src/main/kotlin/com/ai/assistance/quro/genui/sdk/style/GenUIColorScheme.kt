package com.ai.assistance.quro.genui.sdk.style

import androidx.compose.ui.graphics.Color

/**
 * GenUI 颜色方案，基于 Material3 设计规范
 * 包含 20 种颜色角色，支持亮色和暗色主题
 */
data class GenUIColorScheme(
    val primary: Color,
    val onPrimary: Color,
    val primaryContainer: Color,
    val onPrimaryContainer: Color,
    val secondary: Color,
    val onSecondary: Color,
    val tertiary: Color,
    val onTertiary: Color,
    val background: Color,
    val onBackground: Color,
    val surface: Color,
    val onSurface: Color,
    val surfaceVariant: Color,
    val onSurfaceVariant: Color,
    val surfaceContainer: Color,
    val error: Color,
    val onError: Color,
    val outline: Color,
    val outlineVariant: Color,
    val scrim: Color
) {
    companion object {
        val Light = GenUIColorScheme(
            primary = Color(0xFF6750A4.toInt()),
            onPrimary = Color(0xFFFFFFFF),
            primaryContainer = Color(0xFFEADDFF.toInt()),
            onPrimaryContainer = Color(0xFF21005D.toInt()),
            secondary = Color(0xFF625B71.toInt()),
            onSecondary = Color(0xFFFFFFFF),
            tertiary = Color(0xFF7D5260.toInt()),
            onTertiary = Color(0xFFFFFFFF),
            background = Color(0xFFFFFBFE.toInt()),
            onBackground = Color(0xFF1C1B1F.toInt()),
            surface = Color(0xFFFFFBFE.toInt()),
            onSurface = Color(0xFF1C1B1F.toInt()),
            surfaceVariant = Color(0xFFE7E0EC.toInt()),
            onSurfaceVariant = Color(0xFF49454F.toInt()),
            surfaceContainer = Color(0xFFF3EDF7.toInt()),
            error = Color(0xFFB3261E.toInt()),
            onError = Color(0xFFFFFFFF),
            outline = Color(0xFF79747E.toInt()),
            outlineVariant = Color(0xFFCAC4D0.toInt()),
            scrim = Color(0xFF000000)
        )

        val Dark = GenUIColorScheme(
            primary = Color(0xFFD0BCFF.toInt()),
            onPrimary = Color(0xFF381E72.toInt()),
            primaryContainer = Color(0xFF4F378B.toInt()),
            onPrimaryContainer = Color(0xFFEADDFF.toInt()),
            secondary = Color(0xFFCCC2DC.toInt()),
            onSecondary = Color(0xFF332D41.toInt()),
            tertiary = Color(0xFFEFB8C8.toInt()),
            onTertiary = Color(0xFF492532.toInt()),
            background = Color(0xFF1C1B1F.toInt()),
            onBackground = Color(0xFFE6E1E5.toInt()),
            surface = Color(0xFF1C1B1F.toInt()),
            onSurface = Color(0xFFE6E1E5.toInt()),
            surfaceVariant = Color(0xFF49454F.toInt()),
            onSurfaceVariant = Color(0xFFCAC4D0.toInt()),
            surfaceContainer = Color(0xFF49454F.toInt()),
            error = Color(0xFFF2B8B5.toInt()),
            onError = Color(0xFF601410.toInt()),
            outline = Color(0xFF938F99.toInt()),
            outlineVariant = Color(0xFF49454F.toInt()),
            scrim = Color(0xFF000000)
        )
    }
}
