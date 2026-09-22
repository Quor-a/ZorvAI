package com.ai.assistance.quro.genui.sdk.style

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * GenUI 排版方案 —— ZorvAI 纸质/墨感体系
 * 15 种文字样式：Display(3) + Headline(3) + Title(3) + Body(3) + Label(3)
 *
 * 审美基准（与宿主 ZorvAI 一致）：
 *  - 标题层（display/headline/title）用 **衬线体 + 半粗**，对应「墨问」纸质风，避免无衬线的工具感；
 *  - 正文/标签用无衬线，保证长文与小字可读；
 *  - 大字号收紧字距（-0.25 ~ -0.5sp），小字号放开字距（+0.1 ~ +0.5sp），避免「默认字距」的松散感；
 *  - 行高按 1.35~1.5 倍设定，中文段落不再挤在一起。
 *
 * 原为 Material3 默认数值（无衬线、无字距微调），已整体替换。
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

        /** 默认（纸质/墨感，标题衬线） */
        val Default: GenUITypography by lazy { build(serifTitles = true) }

        /** 全无衬线变体（仪表盘/数据密集场景更利落） */
        val SansTitles: GenUITypography by lazy { build(serifTitles = false) }

        private fun build(serifTitles: Boolean): GenUITypography {
            val title = if (serifTitles) FontFamily.Serif else FontFamily.SansSerif
            return GenUITypography(
                displayLarge = base(57f, FontWeight.Normal, 64f, title, letterSpacing = -0.5f),
                displayMedium = base(45f, FontWeight.Normal, 52f, title, letterSpacing = -0.4f),
                displaySmall = base(36f, FontWeight.SemiBold, 44f, title, letterSpacing = -0.4f),
                headlineLarge = base(32f, FontWeight.SemiBold, 40f, title, letterSpacing = -0.3f),
                headlineMedium = base(28f, FontWeight.SemiBold, 36f, title, letterSpacing = -0.3f),
                headlineSmall = base(24f, FontWeight.SemiBold, 32f, title, letterSpacing = -0.2f),
                titleLarge = base(22f, FontWeight.SemiBold, 30f, title, letterSpacing = -0.2f),
                titleMedium = base(16f, FontWeight.Medium, 24f, FontFamily.SansSerif, letterSpacing = 0f),
                titleSmall = base(14f, FontWeight.Medium, 20f, FontFamily.SansSerif, letterSpacing = 0.1f),
                bodyLarge = base(16f, FontWeight.Normal, 25f, FontFamily.SansSerif, letterSpacing = 0.15f),
                bodyMedium = base(14f, FontWeight.Normal, 22f, FontFamily.SansSerif, letterSpacing = 0.15f),
                bodySmall = base(12f, FontWeight.Normal, 18f, FontFamily.SansSerif, letterSpacing = 0.2f),
                labelLarge = base(14f, FontWeight.Medium, 20f, FontFamily.SansSerif, letterSpacing = 0.1f),
                labelMedium = base(12f, FontWeight.Medium, 16f, FontFamily.SansSerif, letterSpacing = 0.3f),
                labelSmall = base(11f, FontWeight.Medium, 16f, FontFamily.SansSerif, letterSpacing = 0.4f)
            )
        }

        private fun base(
            size: Float,
            weight: FontWeight,
            lineHeight: Float,
            family: FontFamily = FontFamily.Default,
            letterSpacing: Float = 0f
        ): TextStyle {
            return TextStyle(
                fontSize = size.sp,
                fontWeight = weight,
                fontFamily = family,
                lineHeight = lineHeight.sp,
                letterSpacing = letterSpacing.sp
            )
        }
    }
}
