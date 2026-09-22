package com.ai.assistance.quro.genui.sdk.style

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * GenUI 间距方案
 * 包含 7 种间距尺寸：none, xs, sm, md, lg, xl, xxl
 */
data class GenUISpacing(
    val none: Dp,
    val xs: Dp,
    val sm: Dp,
    val md: Dp,
    val lg: Dp,
    val xl: Dp,
    val xxl: Dp
) {
    companion object {
        val Default = GenUISpacing(
            none = 0.dp,
            xs = 4.dp,
            sm = 8.dp,
            md = 12.dp,
            lg = 16.dp,
            xl = 24.dp,
            xxl = 32.dp
        )
    }
}
