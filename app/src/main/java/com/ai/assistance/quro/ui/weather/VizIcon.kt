package com.ai.assistance.quro.ui.weather

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

/**
 * 可视化图标 · 设计库（Compose 侧）
 * 与 HTML 展示页对应。每类 [VizIconKind] 是一种**独立图形语言**，由 Canvas 手绘，
 * [VizIconStyle] 控制实心 / 描边 / 双色 / 霓虹 / 渐变 / 像素 等视觉处理。
 * 8 类字形 × 15 种处理 = 120 款独立图标变体。
 */
enum class VizIconKind { Sun, Moon, Cloud, Rain, Snow, Storm, Wind, Fog }

enum class VizIconStyle {
    Filled, Line, Duo, Neon, Bold, Gradient, Mono, Dashed, Dotted, Soft, Glow, Badge, Holographic, Pixel, Sketch,
}

private val STROKE_STYLES = setOf(
    VizIconStyle.Line, VizIconStyle.Neon, VizIconStyle.Bold,
    VizIconStyle.Mono, VizIconStyle.Dashed, VizIconStyle.Dotted, VizIconStyle.Sketch,
)

@Composable
fun VizIcon(
    kind: VizIconKind,
    tint: Color,
    modifier: Modifier = Modifier,
    style: VizIconStyle = VizIconStyle.Filled,
    sizeDp: Dp = 54.dp,
) {
    val strokeW: Dp = when (style) {
        VizIconStyle.Bold -> 5.dp
        VizIconStyle.Mono -> 2.5.dp
        VizIconStyle.Dashed -> 3.dp
        VizIconStyle.Dotted -> 3.dp
        VizIconStyle.Sketch -> 2.5.dp
        VizIconStyle.Line -> 3.dp
        VizIconStyle.Neon -> 3.dp
        else -> 0.dp
    }
    val glow = style == VizIconStyle.Neon || style == VizIconStyle.Glow
    val effTint = if (style == VizIconStyle.Mono) Color(0xFF9AA4B2) else tint
    val stroke = STROKE_STYLES.contains(style)
    val pe = pathEffectFor(style)
    Canvas(modifier.size(sizeDp)) {
        val c = center
        val r = size.minDimension / 2
        if (style == VizIconStyle.Badge) {
            drawRoundRect(
                color = tint.copy(alpha = 0.15f),
                topLeft = Offset(c.x - r, c.y - r),
                size = Size(r * 2, r * 2),
                cornerRadius = CornerRadius(r * 0.35f),
            )
        }
        when (kind) {
            VizIconKind.Sun -> drawSun(c, r, effTint, stroke, strokeW, pe, glow, style)
            VizIconKind.Moon -> drawMoon(c, r, effTint, stroke, strokeW, pe, glow, style)
            VizIconKind.Cloud -> drawCloud(c, r, effTint, stroke, strokeW, pe, glow, style)
            VizIconKind.Rain -> drawRain(c, r, effTint, stroke, strokeW, pe, glow, style)
            VizIconKind.Snow -> drawSnow(c, r, effTint, stroke, strokeW, pe, glow, style)
            VizIconKind.Storm -> drawStorm(c, r, effTint, stroke, strokeW, pe, glow, style)
            VizIconKind.Wind -> drawWind(c, r, effTint, stroke, strokeW, pe, glow, style)
            VizIconKind.Fog -> drawFog(c, r, effTint, stroke, strokeW, pe, glow, style)
        }
    }
}

private fun pathEffectFor(style: VizIconStyle): PathEffect? = when (style) {
    VizIconStyle.Dashed -> PathEffect.dashPathEffect(floatArrayOf(6f, 5f))
    VizIconStyle.Dotted -> PathEffect.dashPathEffect(floatArrayOf(0.1f, 7f), phase = 3f)
    VizIconStyle.Sketch -> PathEffect.dashPathEffect(floatArrayOf(5f, 4f))
    else -> null
}

private fun fillColor(tint: Color, style: VizIconStyle): Color? = when (style) {
    VizIconStyle.Soft -> tint.copy(alpha = 0.35f)
    VizIconStyle.Filled, VizIconStyle.Glow, VizIconStyle.Badge, VizIconStyle.Pixel, VizIconStyle.Duo -> tint
    else -> null
}

private fun fillBrush(style: VizIconStyle): Brush? = when (style) {
    VizIconStyle.Gradient -> Brush.linearGradient(listOf(Color(0xFF6EA8FE), Color(0xFFA18CD1)))
    VizIconStyle.Holographic -> Brush.linearGradient(
        listOf(Color(0xFFFF9A9E), Color(0xFFA18CD1), Color(0xFF84FAB0), Color(0xFF6EA8FE)))
    else -> null
}

private fun DrawScope.paintFill(tint: Color, style: VizIconStyle, block: (color: Color) -> Unit, blockBrush: (brush: Brush) -> Unit) {
    val b = fillBrush(style)
    if (b != null) blockBrush(b) else block(fillColor(tint, style) ?: tint)
}

private fun DrawScope.drawSun(c: Offset, r: Float, tint: Color, stroke: Boolean, sw: Dp, pe: PathEffect?, glow: Boolean, style: VizIconStyle) {
    val core = r * 0.42f
    val rayIn = r * 0.58f
    val rayOut = r * 0.92f
    val w = sw.toPx()
    if (glow) drawCircle(tint.copy(alpha = 0.15f), r * 1.05f, c)
    if (stroke) {
        for (i in 0 until 8) {
            val a = Math.toRadians(i * 45.0)
            val ca = cos(a).toFloat(); val sa = sin(a).toFloat()
            drawLine(tint, Offset(c.x + ca * rayIn, c.y + sa * rayIn),
                Offset(c.x + ca * rayOut, c.y + sa * rayOut), strokeWidth = w, pathEffect = pe)
        }
        drawCircle(tint, core, c, style = Stroke(width = w, pathEffect = pe))
    } else {
        val col = fillColor(tint, style) ?: tint
        val b = fillBrush(style)
        for (i in 0 until 8) {
            val a = Math.toRadians(i * 45.0)
            val ca = cos(a).toFloat(); val sa = sin(a).toFloat()
            if (b != null) drawLine(b, Offset(c.x + ca * rayIn, c.y + sa * rayIn),
                Offset(c.x + ca * rayOut, c.y + sa * rayOut), strokeWidth = r * 0.08f)
            else drawLine(col, Offset(c.x + ca * rayIn, c.y + sa * rayIn),
                Offset(c.x + ca * rayOut, c.y + sa * rayOut), strokeWidth = r * 0.08f)
        }
        if (b != null) drawCircle(b, core, c) else drawCircle(col, core, c)
    }
}

private fun DrawScope.drawMoon(c: Offset, r: Float, tint: Color, stroke: Boolean, sw: Dp, pe: PathEffect?, glow: Boolean, style: VizIconStyle) {
    val path = Path().apply {
        fillType = PathFillType.EvenOdd
        val outer = r * 0.8f
        val inner = r * 0.7f
        val off = r * 0.45f
        addOval(Rect(c.x - outer, c.y - outer, c.x + outer, c.y + outer))
        addOval(Rect(c.x - inner + off, c.y - inner - off * 0.2f, c.x + inner + off, c.y + inner - off * 0.2f))
    }
    if (glow) drawCircle(tint.copy(alpha = 0.15f), r * 1.05f, c)
    if (stroke) drawPath(path, tint, style = Stroke(width = sw.toPx(), pathEffect = pe))
    else paintFill(tint, style, { drawPath(path, it) }, { drawPath(path, it) })
}

private fun cloudPath(c: Offset, r: Float): Path {
    val w = r * 1.7f
    val h = r * 0.9f
    val left = c.x - w / 2
    val top = c.y - h / 2
    return Path().apply {
        addOval(Rect(left + w * 0.05f, top + h * 0.25f, left + w * 0.5f, top + h))
        addOval(Rect(left + w * 0.35f, top, left + w * 0.85f, top + h * 0.85f))
        addOval(Rect(left + w * 0.6f, top + h * 0.25f, left + w * 1.05f, top + h))
        addRect(Rect(left + w * 0.05f, top + h * 0.55f, left + w * 1.05f, top + h))
    }
}

private fun DrawScope.drawCloud(c: Offset, r: Float, tint: Color, stroke: Boolean, sw: Dp, pe: PathEffect?, glow: Boolean, style: VizIconStyle) {
    val p = cloudPath(c, r)
    if (glow) drawCircle(tint.copy(alpha = 0.12f), r * 1.0f, c)
    if (stroke) drawPath(p, tint, style = Stroke(width = sw.toPx(), pathEffect = pe))
    else paintFill(tint, style, { drawPath(p, it) }, { drawPath(p, it) })
}

private fun DrawScope.drawRain(c: Offset, r: Float, tint: Color, stroke: Boolean, sw: Dp, pe: PathEffect?, glow: Boolean, style: VizIconStyle) {
    drawCloud(c.copy(y = c.y - r * 0.25f), r * 0.85f, tint, stroke, sw, pe, false, style)
    val base = c.y + r * 0.55f
    val w = r * 0.1f
    for (i in -1..1) {
        val x = c.x + i * r * 0.4f
        drawLine(tint, Offset(x, base), Offset(x - r * 0.1f, base + r * 0.35f), strokeWidth = w)
    }
}

private fun DrawScope.drawSnow(c: Offset, r: Float, tint: Color, stroke: Boolean, sw: Dp, pe: PathEffect?, glow: Boolean, style: VizIconStyle) {
    drawCloud(c.copy(y = c.y - r * 0.25f), r * 0.85f, tint, stroke, sw, pe, false, style)
    val base = c.y + r * 0.55f
    for (i in -1..1) {
        val x = c.x + i * r * 0.4f
        drawCircle(tint, r * 0.08f, Offset(x, base + r * 0.2f))
    }
}

private fun DrawScope.drawStorm(c: Offset, r: Float, tint: Color, stroke: Boolean, sw: Dp, pe: PathEffect?, glow: Boolean, style: VizIconStyle) {
    drawCloud(c.copy(y = c.y - r * 0.3f), r * 0.85f, tint, stroke, sw, pe, false, style)
    val bolt = Path().apply {
        moveTo(c.x + r * 0.1f, c.y + r * 0.2f)
        lineTo(c.x - r * 0.25f, c.y + r * 0.6f)
        lineTo(c.x + r * 0.02f, c.y + r * 0.6f)
        lineTo(c.x - r * 0.12f, c.y + r * 0.95f)
        lineTo(c.x + r * 0.3f, c.y + r * 0.45f)
        lineTo(c.x + r * 0.02f, c.y + r * 0.45f)
        close()
    }
    if (stroke) drawPath(bolt, tint, style = Stroke(width = sw.toPx(), pathEffect = pe))
    else paintFill(tint, style, { drawPath(bolt, it) }, { drawPath(bolt, it) })
}

private fun DrawScope.drawWind(c: Offset, r: Float, tint: Color, stroke: Boolean, sw: Dp, pe: PathEffect?, glow: Boolean, style: VizIconStyle) {
    val w = r * 0.12f
    val y0 = c.y - r * 0.4f
    val y1 = c.y
    val y2 = c.y + r * 0.4f
    val lines = listOf(Pair(y0, c.x + r * 0.5f), Pair(y1, c.x + r * 0.3f), Pair(y2, c.x + r * 0.6f))
    if (stroke) {
        lines.forEach { (y, x2) ->
            drawLine(tint, Offset(c.x - r * 0.7f, y), Offset(x2, y), strokeWidth = sw.toPx(), pathEffect = pe)
        }
    } else {
        paintFill(tint, style,
            { col -> lines.forEach { (y, x2) -> drawLine(col, Offset(c.x - r * 0.7f, y), Offset(x2, y), strokeWidth = w) } },
            { b -> lines.forEach { (y, x2) -> drawLine(b, Offset(c.x - r * 0.7f, y), Offset(x2, y), strokeWidth = w) } })
    }
}

private fun DrawScope.drawFog(c: Offset, r: Float, tint: Color, stroke: Boolean, sw: Dp, pe: PathEffect?, glow: Boolean, style: VizIconStyle) {
    val w = r * 0.12f
    if (stroke) {
        for (i in -1..2) {
            val y = c.y + i * r * 0.32f
            drawLine(tint, Offset(c.x - r * 0.7f, y), Offset(c.x + r * 0.7f, y), strokeWidth = sw.toPx(), pathEffect = pe)
        }
    } else {
        paintFill(tint, style,
            { col -> for (i in -1..2) { val y = c.y + i * r * 0.32f; drawLine(col, Offset(c.x - r * 0.7f, y), Offset(c.x + r * 0.7f, y), strokeWidth = w) } },
            { b -> for (i in -1..2) { val y = c.y + i * r * 0.32f; drawLine(b, Offset(c.x - r * 0.7f, y), Offset(c.x + r * 0.7f, y), strokeWidth = w) } })
    }
}
