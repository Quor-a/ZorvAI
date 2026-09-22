package com.ai.assistance.quro.genui.sdk.style

import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/**
 * GenUI 形状方案，基于 Material3 设计规范
 * 包含 6 种圆角形状：extraSmall, small, medium, large, extraLarge, full
 */
data class GenUIShapes(
    val extraSmall: Shape,
    val small: Shape,
    val medium: Shape,
    val large: Shape,
    val extraLarge: Shape,
    val full: Shape
) {
    companion object {
        val Default = GenUIShapes(
            extraSmall = RoundedCornerShape(4.dp),
            small = RoundedCornerShape(8.dp),
            medium = RoundedCornerShape(12.dp),
            large = RoundedCornerShape(16.dp),
            extraLarge = RoundedCornerShape(28.dp),
            full = RoundedCornerShape(CornerSize(50))
        )

        /**
         * 根据 dp 值创建圆角形状
         * @param dp 圆角大小，<=0 时返回矩形，>=999 时返回全圆角
         */
        fun rounded(dp: Float): Shape {
            return when {
                dp <= 0f -> RectangleShape
                dp >= 999f -> RoundedCornerShape(CornerSize(50))
                else -> RoundedCornerShape(dp.dp)
            }
        }
    }
}
