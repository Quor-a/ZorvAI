package com.ai.assistance.quro.core.ui.card.compose

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.assistance.quro.core.ui.card.spec.CardData
import com.ai.assistance.quro.core.ui.card.spec.CardSpec
import com.ai.assistance.quro.core.ui.card.spec.CardTypes
import com.ai.assistance.quro.core.ui.card.spec.ColorToken

/**
 * 图表卡（`line_chart` / `bar_chart` / `area_chart` / `pie_chart` / `sparkline_chart`）。
 *
 * ## 架构
 * 一张卡内部按 `chartType` 分派到四种「图形体」（折线/面积 · 柱状 · 饼环 · 迷你线），
 * 但**外壳、图例、坐标轴刻度、x 轴标签、动画相位全部共用** —— 之前每种图表各写一份，
 * 增加一种图就要复制一遍坐标轴与图例逻辑，且各图表的字号/留白互不一致。
 *
 * ## 只把「连续几何」交给 Canvas
 * 折线、柱、扇区无法用离散原语表达，才用 `Canvas`；刻度数字、图例、轴标签全是真正的
 * `Text`，由 Compose 测量排版 —— 彻底修掉旧版「按 `ch.code > 0x2E80` 估字宽」导致的
 * 标签压叠与数字溢出。
 *
 * ## 数值区间
 * 优先用 `axis.yMin/yMax`；否则取全体点的 min/max 并留 8% 余量；全等值时向两侧各撑开 1，
 * 避免 `span == 0` 时整条线贴底（旧版除零后画在边界上，看起来像「没数据」）。
 */
@Composable
fun ChartCardView(
    spec: CardSpec,
    modifier: Modifier = Modifier,
    resolveToken: (ColorToken) -> Color,
) {
    val chart = spec.data as? CardData.Chart ?: return
    if (chart.series.isEmpty()) return
    if (chart.series.all { it.points.isEmpty() }) return

    val phase by animateFloatAsState(
        targetValue = 1f,
        animationSpec = tween(durationMillis = 900, easing = FastOutSlowInEasing),
        label = "chartGrow",
    )

    CardShell(spec, resolveToken, modifier) {
        val kind = chart.chartType.trim().lowercase()
        when {
            kind in PIE_KINDS -> PieBody(chart, resolveToken, phase)
            kind in BAR_KINDS -> BarBody(chart, resolveToken, phase)
            kind in SPARK_KINDS -> SparkBody(chart, resolveToken, phase)
            else -> LineBody(chart, resolveToken, phase, filled = kind == "area")
        }
    }
}

private val PIE_KINDS = setOf("pie", "donut", "doughnut", "ring")
private val BAR_KINDS = setOf("bar", "column", "histogram", "stacked_bar")
private val SPARK_KINDS = setOf("sparkline", "spark")

// ───────────────────────── 折线 / 面积 ─────────────────────────

@Composable
private fun LineBody(
    chart: CardData.Chart,
    resolveToken: (ColorToken) -> Color,
    phase: Float,
    filled: Boolean,
) {
    val range = valueRange(chart)
    val legend = chart.series.mapIndexed { i, s ->
        (s.name.ifBlank { "序列${i + 1}" }) to resolveToken(CardTypes.seriesColor(i, s.color))
    }
    if (chart.series.size > 1) {
        ChartLegend(legend)
        Spacer(Modifier.height(8.dp))
    }
    AxisScaffold(range, resolveToken) {
        Canvas(Modifier.fillMaxWidth().height(126.dp)) {
            val pad = 2.dp.toPx()
            val left = pad
            val right = size.width - pad
            val top = pad
            val bottom = size.height - pad
            // 网格：3 条淡横线，给「大概多高」一个参照
            repeat(3) { i ->
                val y = top + (bottom - top) * (i / 2f)
                drawLine(
                    resolveToken(ColorToken.Outline).copy(alpha = 0.35f),
                    Offset(left, y), Offset(right, y), 1.dp.toPx(),
                )
            }
            chart.series.forEachIndexed { si, s ->
                val n = s.points.size
                if (n == 0) return@forEachIndexed
                val color = resolveToken(CardTypes.seriesColor(si, s.color))
                val drawN = if (phase <= 0f) n else ((n * phase).toInt()).coerceIn(1, n)
                val path = Path()
                s.points.take(drawN).forEachIndexed { i, v ->
                    val x = left + (right - left) * (i.toFloat() / (n - 1).coerceAtLeast(1))
                    val y = top + (bottom - top) * (1f - range.ratio(v))
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                if (filled && drawN > 1) {
                    val area = Path().apply { addPath(path) }
                    val lastX = left + (right - left) * ((drawN - 1).toFloat() / (n - 1).coerceAtLeast(1))
                    area.lineTo(lastX, bottom)
                    area.lineTo(left, bottom)
                    area.close()
                    drawPath(
                        area,
                        Brush.verticalGradient(
                            listOf(color.copy(alpha = 0.35f), color.copy(alpha = 0.02f)),
                            startY = top, endY = bottom,
                        ),
                    )
                }
                drawPath(path, color, style = Stroke(2.dp.toPx()))
                // 末尾点：给「最新值」一个锚点
                if (drawN == n && n > 1) {
                    val lastX = left + (right - left)
                    val lastY = top + (bottom - top) * (1f - range.ratio(s.points.last()))
                    drawCircle(color, radius = 2.5.dp.toPx(), center = Offset(lastX, lastY))
                }
            }
        }
        XLabels(chart, resolveToken)
    }
}

// ───────────────────────── 柱状 ─────────────────────────

@Composable
private fun BarBody(
    chart: CardData.Chart,
    resolveToken: (ColorToken) -> Color,
    phase: Float,
) {
    val range = valueRange(chart)
    val legend = chart.series.mapIndexed { i, s ->
        (s.name.ifBlank { "序列${i + 1}" }) to resolveToken(CardTypes.seriesColor(i, s.color))
    }
    if (chart.series.size > 1) {
        ChartLegend(legend)
        Spacer(Modifier.height(8.dp))
    }
    AxisScaffold(range, resolveToken) {
        Canvas(Modifier.fillMaxWidth().height(126.dp)) {
            val pad = 2.dp.toPx()
            val categories = chart.series.maxOf { it.points.size }.coerceAtLeast(1)
            val seriesCount = chart.series.size
            val slot = (size.width - pad * 2) / categories
            val barW = (slot * 0.62f / seriesCount).coerceAtLeast(2f)
            val bottom = size.height - pad
            val usable = size.height - pad * 2
            repeat(3) { i ->
                val y = pad + usable * (i / 2f)
                drawLine(
                    resolveToken(ColorToken.Outline).copy(alpha = 0.35f),
                    Offset(pad, y), Offset(size.width - pad, y), 1.dp.toPx(),
                )
            }
            chart.series.forEachIndexed { si, s ->
                val color = resolveToken(CardTypes.seriesColor(si, s.color))
                s.points.forEachIndexed { i, v ->
                    val h = usable * range.ratio(v) * phase
                    val groupLeft = pad + slot * i + (slot - barW * seriesCount) / 2f
                    val x = groupLeft + barW * si
                    drawRoundRect(
                        color = color,
                        topLeft = Offset(x, bottom - h),
                        size = Size(barW * 0.86f, h.coerceAtLeast(0f)),
                        cornerRadius = CornerRadius(barW * 0.3f, barW * 0.3f),
                    )
                }
            }
        }
        XLabels(chart, resolveToken)
    }
}

// ───────────────────────── 饼 / 环 ─────────────────────────

/** 一个扇区（饼/环共用）。文件级定义：放在 @Composable 里做局部类会触发局部类型推断的限制。 */
private data class PieSlice(val label: String, val value: Float, val color: Color)


@Composable
private fun PieBody(
    chart: CardData.Chart,
    resolveToken: (ColorToken) -> Color,
    phase: Float,
) {
    // 两种写法都认：多序列各 1 个值（扇区=序列），或单序列多点（扇区=点）
    val slices: List<PieSlice> = if (chart.series.size > 1) {
        chart.series.mapIndexed { i, s ->
            PieSlice(s.name.ifBlank { "项${i + 1}" }, s.points.firstOrNull() ?: 0f, resolveToken(CardTypes.seriesColor(i, s.color)))
        }
    } else {
        val s = chart.series.first()
        s.points.mapIndexed { i, v ->
            val label = s.labels.getOrNull(i) ?: "项${i + 1}"
            PieSlice(label, v, resolveToken(CardTypes.seriesColor(i, null)))
        }
    }
    val total = slices.sumOf { it.value.toDouble() }.toFloat().takeIf { it > 0f } ?: return
    val donut = chart.chartType.lowercase() != "pie"

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(104.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(104.dp)) {
                val stroke = 18.dp.toPx()
                var start = -90f
                slices.forEach { slice ->
                    val sweep = 360f * (slice.value / total) * phase
                    if (sweep <= 0f) return@forEach
                    if (donut) {
                        drawArc(
                            color = slice.color,
                            startAngle = start,
                            sweepAngle = sweep - 1.2f,
                            useCenter = false,
                            style = Stroke(stroke),
                            topLeft = Offset(stroke / 2f, stroke / 2f),
                            size = Size(size.width - stroke, size.height - stroke),
                        )
                    } else {
                        drawArc(
                            color = slice.color,
                            startAngle = start,
                            sweepAngle = sweep - 1.2f,
                            useCenter = true,
                        )
                    }
                    start += sweep
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    trimNum(total),
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = resolveToken(ColorToken.OnSurface),
                )
                Text("总计", fontSize = 10.sp, color = resolveToken(ColorToken.OnSurfaceVariant))
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            slices.take(6).forEach { slice ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).background(slice.color, RoundedCornerShape(2.dp)))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        slice.label,
                        fontSize = 12.sp,
                        color = resolveToken(ColorToken.OnSurface),
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                    )
                    Text(
                        "${(slice.value / total * 1000).toInt() / 10f}%",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = resolveToken(ColorToken.OnSurfaceVariant),
                    )
                }
            }
        }
    }
}

// ───────────────────────── 迷你线 ─────────────────────────

@Composable
private fun SparkBody(
    chart: CardData.Chart,
    resolveToken: (ColorToken) -> Color,
    phase: Float,
) {
    val range = valueRange(chart)
    Canvas(Modifier.fillMaxWidth().height(44.dp)) {
        val pad = 2.dp.toPx()
        val left = pad
        val right = size.width - pad
        val top = pad
        val bottom = size.height - pad
        val s = chart.series.first()
        val n = s.points.size
        if (n == 0) return@Canvas
        val color = resolveToken(CardTypes.seriesColor(0, s.color))
        val path = Path()
        val drawN = ((n * phase).toInt()).coerceIn(1, n)
        s.points.take(drawN).forEachIndexed { i, v ->
            val x = left + (right - left) * (i.toFloat() / (n - 1).coerceAtLeast(1))
            val y = top + (bottom - top) * (1f - range.ratio(v))
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        val area = Path().apply { addPath(path) }
        area.lineTo(left + (right - left) * ((drawN - 1).toFloat() / (n - 1).coerceAtLeast(1)), bottom)
        area.lineTo(left, bottom)
        area.close()
        drawPath(
            area,
            Brush.verticalGradient(listOf(color.copy(alpha = 0.3f), color.copy(alpha = 0f))),
        )
        drawPath(path, color, style = Stroke(1.6.dp.toPx()))
    }
}

// ───────────────────────── 共用：坐标轴 + x 标签 ─────────────────────────

/** 数值区间（含余量），供四种图形体共用。 */
private class ValueRange(val min: Float, val max: Float) {
    private val span: Float = (max - min).takeIf { it > 1e-6f } ?: 1f
    fun ratio(v: Float): Float = ((v - min) / span).coerceIn(0f, 1f)
}

private fun valueRange(chart: CardData.Chart): ValueRange {
    val all = chart.series.flatMap { it.points }
    if (all.isEmpty()) return ValueRange(0f, 1f)
    var lo = chart.axis.yMin ?: (all.minOrNull() ?: 0f)
    var hi = chart.axis.yMax ?: (all.maxOrNull() ?: 1f)
    if (chart.axis.yMin == null || chart.axis.yMax == null) {
        val span = hi - lo
        if (span < 1e-6f) {
            // 全等值：向两侧撑开，否则连线贴底看起来像「没数据」
            lo -= 1f
            hi += 1f
        } else {
            val margin = span * 0.08f
            if (chart.axis.yMin == null) lo -= margin
            if (chart.axis.yMax == null) hi += margin
        }
    }
    return ValueRange(lo, hi)
}

/** 左侧三档刻度（max / mid / min）+ 右侧内容插槽，让数字与图形严格对齐。 */
@Composable
private fun AxisScaffold(
    range: ValueRange,
    resolveToken: (ColorToken) -> Color,
    content: @Composable () -> Unit,
) {
    Row(Modifier.fillMaxWidth()) {
        Column(
            Modifier.width(38.dp).height(126.dp),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.End,
        ) {
            listOf(range.max, (range.max + range.min) / 2f, range.min).forEach { v ->
                Text(
                    trimNum(v),
                    fontSize = 9.sp,
                    color = resolveToken(ColorToken.OnSurfaceVariant),
                    modifier = Modifier.padding(end = 4.dp),
                    maxLines = 1,
                )
            }
        }
        Column(Modifier.weight(1f)) { content() }
    }
}

/** x 轴标签：只画首 / 中 / 尾，避免密集刻度互相压叠（真实 Text，自动测量）。 */
@Composable
private fun XLabels(chart: CardData.Chart, resolveToken: (ColorToken) -> Color) {
    val labels = chart.series.firstOrNull()?.labels?.takeIf { it.size >= 2 } ?: return
    val n = labels.size
    val idx = linkedSetOf(0, n - 1).apply { if (n > 2) add(n / 2) }
    Row(
        Modifier.fillMaxWidth().padding(start = 38.dp, top = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        idx.filter { it < n }.forEach {
            Text(
                labels[it],
                fontSize = 9.sp,
                color = resolveToken(ColorToken.OnSurfaceVariant),
                maxLines = 1,
            )
        }
    }
}
