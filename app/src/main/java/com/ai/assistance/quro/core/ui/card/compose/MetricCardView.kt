package com.ai.assistance.quro.core.ui.card.compose

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.assistance.quro.core.ui.card.spec.CardData
import com.ai.assistance.quro.core.ui.card.spec.CardSpec
import com.ai.assistance.quro.core.ui.card.spec.ColorToken
import org.json.JSONObject
import kotlin.math.abs

/**
 * 指标卡（`metric`）：指标名 + 大数字（入场滚动）+ 涨跌胶囊 + 可选迷你走势。
 *
 * ## 相比旧版补了什么
 *  - **大数字入场滚动**：0 → 目标值，让「这是刚算出来的数」可感知；
 *    只有纯数字才滚动，`12/15`、`N/A` 这类原文原样显示（不硬转数字，避免变成 0）；
 *  - **涨跌胶囊**：读 `trend` / `delta`，有值才占位；
 *  - **迷你走势**：`series` 里点多于 1 个就画 sparkline（「一个大数 + 一条线」是最常见的指标卡）；
 *  - 外壳统一走 [CardShell]，底色/圆角/描边与其他卡一致（不再出现「有的卡有底、有的没底」）。
 */
@Composable
fun MetricCardView(
    spec: CardSpec,
    modifier: Modifier = Modifier,
    resolveToken: (ColorToken) -> Color,
) {
    val m = CardExtractors.metricValues(spec)
    val chart = spec.data as? CardData.Chart
    val firstSeries = chart?.series?.firstOrNull()
    val raw = runCatching { JSONObject(spec.rawJson) }.getOrNull()
    val trend = raw?.optString("trend", "")?.ifBlank { raw.optString("delta", "") }.orEmpty()

    val phase by animateFloatAsState(
        targetValue = 1f,
        animationSpec = tween(durationMillis = 900, easing = FastOutSlowInEasing),
        label = "metricCount",
    )

    CardShell(spec, resolveToken, modifier, accent = spec.style.accent) {
        Column {
            if (m.name.isNotEmpty()) {
                Text(
                    m.name,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = resolveToken(ColorToken.OnSurfaceVariant),
                )
                Spacer(Modifier.height(4.dp))
            }
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    animatedValue(m.valueText ?: "--", phase),
                    fontSize = 30.sp,
                    fontWeight = FontWeight.Bold,
                    color = resolveToken(ColorToken.Primary),
                )
                if (trend.isNotBlank()) {
                    Spacer(Modifier.width(8.dp))
                    CardPill(
                        text = trend,
                        bg = resolveToken(ColorToken.SurfaceVariant),
                        fg = resolveToken(ColorToken.OnSurfaceVariant),
                        modifier = Modifier.align(Alignment.CenterVertically),
                    )
                }
            }
            if (firstSeries != null && firstSeries.points.size > 1) {
                Spacer(Modifier.height(8.dp))
                MiniSpark(firstSeries.points, resolveToken(ColorToken.Primary), phase)
            }
        }
    }
}

/** 0 → 目标值的数字滚动；非纯数字（`12/15`、`N/A`、`--`）原样返回。 */
private fun animatedValue(text: String, phase: Float): String {
    // 只处理「整体是一个数字（可带单位后缀）」的情况，其余原样显示
    val match = Regex("^(-?\\d+(?:\\.\\d+)?)(.*)$").find(text.trim()) ?: return text
    val number = match.groupValues[1].toDoubleOrNull() ?: return text
    val suffix = match.groupValues[2]
    val current = number * phase
    val body = if (abs(number) >= 1000 || number == number.toLong().toDouble()) {
        current.toLong().toString()
    } else {
        String.format("%.1f", current)
    }
    return body + suffix
}

/** 迷你走势：一条折线 + 末尾点，无坐标轴（指标卡语境里刻度是噪声）。 */
@Composable
private fun MiniSpark(points: List<Float>, color: Color, phase: Float) {
    val lo = points.minOrNull() ?: 0f
    val hi = points.maxOrNull() ?: 1f
    val span = (hi - lo).takeIf { it > 1e-6f } ?: 1f
    Canvas(Modifier.fillMaxWidth().height(34.dp)) {
        val n = points.size
        if (n < 2) return@Canvas
        val drawN = ((n * phase).toInt()).coerceIn(2, n)
        val path = Path()
        points.take(drawN).forEachIndexed { i, v ->
            val x = size.width * (i.toFloat() / (n - 1))
            val y = size.height - size.height * ((v - lo) / span)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color, style = Stroke(1.8.dp.toPx()))
        val lastX = size.width * ((drawN - 1).toFloat() / (n - 1))
        val lastY = size.height - size.height * ((points[drawN - 1] - lo) / span)
        drawCircle(color, radius = 2.dp.toPx(), center = Offset(lastX, lastY))
    }
}
