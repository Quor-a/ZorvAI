package com.ai.assistance.quro.genui.sdk.style

import androidx.compose.ui.graphics.luminance

/**
 * GenUI 主题，组合颜色方案、排版方案、形状方案和间距方案
 * 提供亮色和暗色主题预设
 */
data class GenUITheme(
    val colorScheme: GenUIColorScheme,
    val typography: GenUITypography,
    val shapes: GenUIShapes,
    val spacing: GenUISpacing
) {
    /**
     * 判断是否为暗色主题（基于背景色的亮度）
     */
    val isDark: Boolean
        get() = colorScheme.background.luminance() < 0.5f

    /**
     * 使用 lambda 修改颜色方案，返回新的主题实例
     */
    fun withColors(block: (GenUIColorScheme) -> GenUIColorScheme): GenUITheme {
        return copy(colorScheme = block(colorScheme))
    }

    companion object {
        val Light = GenUITheme(
            colorScheme = GenUIColorScheme.Light,
            typography = GenUITypography.Default,
            shapes = GenUIShapes.Default,
            spacing = GenUISpacing.Default
        )

        val Dark = GenUITheme(
            colorScheme = GenUIColorScheme.Dark,
            typography = GenUITypography.Default,
            shapes = GenUIShapes.Default,
            spacing = GenUISpacing.Default
        )

        /**
         * 根据 isDark 返回对应的默认主题
         */
        fun default(isDark: Boolean): GenUITheme {
            return if (isDark) Dark else Light
        }
    }
}
