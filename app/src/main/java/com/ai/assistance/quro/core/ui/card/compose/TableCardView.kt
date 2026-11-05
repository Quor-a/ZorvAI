package com.ai.assistance.quro.core.ui.card.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.assistance.quro.core.ui.card.spec.CardData
import com.ai.assistance.quro.core.ui.card.spec.CardSpec
import com.ai.assistance.quro.core.ui.card.spec.ColorToken

/**
 * 表格卡（`table`）：表头 + 数据行，真实 Compose 网格。
 *
 * - 单元格用 `Text(maxLines = 1, ellipsis)` 自动省略 —— 不再手算「最多能放几个字」；
 * - 列宽用 `weight(1f)` 等分：旧版按字符数估宽，中文列永远被压窄、数字列永远空一大截；
 * - 行底色 / 分隔线用 `Row` 背景 + `HorizontalDivider`，彻底消除手绘裁切；
 * - 表头单独上色并加圆角裁切，长表格一眼能看出「哪行是表头」。
 */
@Composable
fun TableCardView(
    spec: CardSpec,
    modifier: Modifier = Modifier,
    resolveToken: (ColorToken) -> Color,
) {
    val media = spec.data as? CardData.Media ?: return
    val headers = media.headers
    val allRows = if (headers.isNotEmpty()) listOf(headers) + media.rows else media.rows
    if (allRows.isEmpty()) return
    val colCount = allRows.maxOf { it.size }.coerceAtLeast(1)

    CardShell(spec, resolveToken, modifier, padding = 10.dp) {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))) {
            allRows.forEachIndexed { ri, row ->
                val isHeader = ri == 0 && headers.isNotEmpty()
                val bg: Color = if (isHeader) {
                    resolveToken(ColorToken.SurfaceVariant)
                } else {
                    Color.Transparent // 交给卡片底色，避免「每行一块白条」的格子感
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(bg)
                        .padding(vertical = 7.dp),
                ) {
                    for (ci in 0 until colCount) {
                        Text(
                            row.getOrNull(ci) ?: "",
                            fontSize = 12.5.sp,
                            color = if (isHeader) resolveToken(ColorToken.OnSurface) else resolveToken(ColorToken.OnSurface),
                            fontWeight = if (isHeader) FontWeight.SemiBold else FontWeight.Normal,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 7.dp),
                        )
                    }
                }
                if (ri != allRows.lastIndex) {
                    HorizontalDivider(color = resolveToken(ColorToken.Outline).copy(alpha = 0.5f), thickness = 1.dp)
                }
            }
        }
    }
}
