package com.ai.assistance.quro.genui.sdk.style

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * GenUI 排版方案，基于 Material3 设计规范
 * 包含 15 种文字样式：Display(3) + Headline(3) + Title(3) + Body(3) + Label(3)
 */
data class GenUITypography(
    val displayLarge: TextStyle,
    val displayMedium: TextStyle,
    val displaySmall: TextStyle,
    val headlineLarge: TextStyle,
    val headlineMedium: TextStyle,
    val headlineSmall: TextStyle,
    val titleLarge: TextStyle,
    val titleMedium: TextStyle,
    val titleSmall: TextStyle,
    val bodyLarge: TextStyle,
    val bodyMedium: TextStyle,
    val bodySmall: TextStyle,
    val labelLarge: TextStyle,
    val labelMedium: TextStyle,
    val labelSmall: TextStyle
) {
    companion object {
        val Default: GenUITypography by lazy {
            GenUITypography(
                displayLarge = base(size = 57f, weight = FontWeight.Normal, lineHeight = 64f),
                displayMedium = base(size = 45f, weight = FontWeight.Normal, lineHeight = 52f),
                displaySmall = base(size = 36f, weight = FontWeight.Normal, lineHeight = 44f),
                headlineLarge = base(size = 32f, weight = FontWeight.Normal, lineHeight = 40f),
                headlineMedium = base(size = 28f, weight = FontWeight.Normal, lineHeight = 36f),
                headlineSmall = base(size = 24f, weight = FontWeight.Normal, lineHeight = 32f),
                titleLarge = base(size = 22f, weight = FontWeight.Normal, lineHeight = 28f),
                titleMedium = base(size = 16f, weight = FontWeight.Medium, lineHeight = 24f),
                titleSmall = base(size = 14f, weight = FontWeight.Medium, lineHeight = 20f),
                bodyLarge = base(size = 16f, weight = FontWeight.Normal, lineHeight = 24f),
                bodyMedium = base(size = 14f, weight = FontWeight.Normal, lineHeight = 20f),
                bodySmall = base(size = 12f, weight = FontWeight.Normal, lineHeight = 16f),
                labelLarge = base(size = 14f, weight = FontWeight.Medium, lineHeight = 20f),
                labelMedium = base(size = 12f, weight = FontWeight.Medium, lineHeight = 16f),
                labelSmall = base(size = 11f, weight = FontWeight.Medium, lineHeight = 16f)
            )
        }

        private fun base(
            size: Float,
            weight: FontWeight,
            lineHeight: Float,
            family: FontFamily = FontFamily.Default
        ): TextStyle {
            return TextStyle(
                fontSize = size.sp,
                fontWeight = weight,
                fontFamily = family,
                lineHeight = lineHeight.sp
            )
        }
    }
}
