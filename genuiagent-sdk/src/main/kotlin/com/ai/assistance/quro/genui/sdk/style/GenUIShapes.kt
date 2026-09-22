package com.ai.assistance.quro.genui.sdk.style

import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/**
 * GenUI 形状方案 —— ZorvAI 纸质感圆角体系
 * 6 种圆角：extraSmall, small, medium, large, extraLarge, full
 *
 * 审美基准：比 Material3 默认更圆一档（纸片/卡片的柔和边缘），
 * 卡片默认走 large(20dp)，胶囊类走 full，避免「方框堆砌」的工具感。
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
            extraSmall = RoundedCornerShape(6.dp),
            small = RoundedCornerShape(10.dp),
            medium = RoundedCornerShape(14.dp),
            large = RoundedCornerShape(20.dp),
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
