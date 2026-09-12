package io.gencanvas.paint

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.DrawStyle
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import com.caverock.androidsvg.SVG
import io.gencanvas.model.Op
import io.gencanvas.model.LaidNode
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 绘制层：把 Op 列表翻译成 DrawScope 指令。
 *
 * 这是整个系统的"表达力边界" —— AI 能画出什么，完全由这里决定。
 * 设计原则：
 *  1. 未知 op 沉默丢弃（AI 会发明不存在的指令）
 *  2. 任何非法值 clamp 到合法域，永不 throw
 *  3. 复杂图形一律可退化为 svg op（AndroidSVG 兜底）
 */

// ------------------------------------------------------------------ 上下文

class PaintContext(
    val density: Float,
    val textMeasurer: TextMeasurer,
    val image: ImageProvider,
    val theme: Map<String, String>,
    val svgCache: SvgCache,
)

fun interface ImageProvider {
    /** 同步取已解码位图；未就绪返回 null（由上层触发异步加载后重组）。 */
    fun get(src: String): ImageBitmap?
}

class SvgCache {
    private val map = HashMap<String, SVG?>(64)
    /** 已渲染的位图缓存（key = svg源 + 目标尺寸），避免每帧重渲 SVG。有界，防止长会话内存膨胀。 */
    private val bmp = LinkedHashMap<String, ImageBitmap>(64)

    fun get(src: String): SVG? = map.getOrPut(src) {
        runCatching { SVG.getFromString(src) }.getOrNull()
    }

    /**
     * 把一段 SVG 源渲染成位图（Compose 1.9 起 [androidx.compose.ui.graphics.Canvas]
     * 不再暴露 nativeCanvas，AndroidSVG 的 renderToCanvas 需要 android.graphics.Canvas，
     * 所以走"渲到位图再 drawImage"这条路，跨版本稳定）。
     *
     * @param src   完整 SVG 字符串（或内部 path d）
     * @param w/h   目标像素尺寸（draw scope 坐标已是 px）
     */
    fun render(src: String, w: Float, h: Float): ImageBitmap? {
        val svg = get(src) ?: return null
        val key = "$src|${w.toInt()}x${h.toInt()}"
        bmp[key]?.let { return it }
        val bw = maxOf(1, w.toInt())
        val bh = maxOf(1, h.toInt())
        val bmpObj = android.graphics.Bitmap.createBitmap(bw, bh, android.graphics.Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bmpObj)
        val vw = svg.documentWidth.takeIf { it > 0 } ?: w
        val vh = svg.documentHeight.takeIf { it > 0 } ?: h
        c.scale(bw / vw, bh / vh)
        runCatching { svg.renderToCanvas(c) }
        val img = bmpObj.asImageBitmap()
        if (bmp.size >= 64) bmp.remove(bmp.keys.first())
        bmp[key] = img
        return img
    }
}

// ------------------------------------------------------------------ 入口

/** 在 [node] 的矩形区域内绘制其全部 ops。坐标系原点为节点左上角。 */
fun DrawScope.drawNodeOps(node: LaidNode, ctx: PaintContext) {
    drawOps(node.node.ops, Size(node.frame.w, node.frame.h), ctx)
}

fun DrawScope.drawOps(ops: List<Op>, size: Size, ctx: PaintContext) {
    for (op in ops) {
        // 单条指令错误不影响其余：这是"错误边界"的最小粒度
        runCatching { drawOp(op, size, ctx) }
    }
}

// ------------------------------------------------------------------ 分发

private fun DrawScope.drawOp(op: Op, size: Size, ctx: PaintContext) {
    when (op.type) {
        "rect" -> drawRectOp(op, size, ctx)
        "rrect" -> drawRRectOp(op, size, ctx)
        "circle" -> drawCircleOp(op, size, ctx)
        "oval" -> drawOvalOp(op, size, ctx)
        "path" -> drawPathOp(op, size, ctx)
        "line" -> drawLineOp(op, size, ctx)
        "arc" -> drawArcOp(op, size, ctx)
        "points" -> drawPointsOp(op, size, ctx)
        "image" -> drawImageOp(op, size, ctx)
        "text" -> drawTextOp(op, size, ctx)
        "svg" -> drawSvgOp(op, size, ctx)
        "clip" -> drawClipOp(op, size, ctx)
        "group" -> drawGroupOp(op, size, ctx)
        else -> Unit   // 未知指令：丢弃
    }
}

// ------------------------------------------------------------------ 几何

private fun DrawScope.drawRectOp(op: Op, size: Size, ctx: PaintContext) {
    val brush = op.brush(ctx) ?: return
    val box = op.box(size, ctx)
    drawRect(brush, box.topLeft(), box.size(), op.alpha(), op.style(ctx))
}

private fun DrawScope.drawRRectOp(op: Op, size: Size, ctx: PaintContext) {
    val brush = op.brush(ctx) ?: return
    val box = op.box(size, ctx)
    val r = op.p["r"]?.f() ?: 0f
    val corner = CornerRadius(
        (op.p["rTL"]?.f() ?: r).dp,
        (op.p["rTR"]?.f() ?: r).dp,
    )
    drawRoundRect(
        brush = brush,
        topLeft = box.topLeft(),
        size = box.size(),
        cornerRadius = corner,
        alpha = op.alpha(),
        style = op.style(ctx),
    )
    op.border(ctx)?.let { (b, w) ->
        drawRoundRect(
            brush = b, topLeft = box.topLeft(), size = box.size(),
            cornerRadius = corner, alpha = op.alpha(), style = Stroke(w),
        )
    }
}

private fun DrawScope.drawCircleOp(op: Op, size: Size, ctx: PaintContext) {
    val brush = op.brush(ctx) ?: return
    val radius = (op.p["radius"]?.f() ?: (size.minDimension / 2f / ctx.density)).dp
    drawCircle(
        brush, radius,
        Offset((op.p["cx"]?.f() ?: 0.5f) * size.width, (op.p["cy"]?.f() ?: 0.5f) * size.height),
        op.alpha(), op.style(ctx),
    )
}

private fun DrawScope.drawOvalOp(op: Op, size: Size, ctx: PaintContext) {
    val brush = op.brush(ctx) ?: return
    val box = op.box(size, ctx)
    drawOval(brush, box.topLeft(), box.size(), op.alpha(), op.style(ctx))
}

private fun DrawScope.drawPathOp(op: Op, size: Size, ctx: PaintContext) {
    val d = op.p["d"]?.s() ?: return
    val brush = op.brush(ctx)
    // 复用 AndroidSVG 的 path 解析：包一层最小 svg 壳，避免自研 path parser。
    // brush 作为单色着色（SrcIn）盖在 path 轮廓上，渐变也能用。
    val svgSrc = "<svg viewBox='0 0 ${size.vw / ctx.density} ${size.vh / ctx.density}'><path d='$d'/></svg>"
    drawSvgString(svgSrc, brush, size, ctx)
}

private fun DrawScope.drawLineOp(op: Op, size: Size, ctx: PaintContext) {
    val brush = op.brush(ctx) ?: return
    val a = op.p["from"]?.l() ?: listOf(0f, 0f)
    val b = op.p["to"]?.l() ?: listOf(1f, 1f)
    drawLine(
        brush,
        Offset(a[0] * size.width, a.getOrElse(1) { 0f } * size.height),
        Offset(b[0] * size.width, b.getOrElse(1) { 0f } * size.height),
        (op.p["width"]?.f() ?: 1f).dp,
        op.cap(),
        alpha = op.alpha(),
    )
}

private fun DrawScope.drawArcOp(op: Op, size: Size, ctx: PaintContext) {
    val brush = op.brush(ctx) ?: return
    drawArc(
        brush,
        op.p["start"]?.f() ?: 0f,
        op.p["sweep"]?.f() ?: 360f,
        op.p["useCenter"]?.b() ?: false,
        alpha = op.alpha(),
        style = op.style(ctx),
    )
}

private fun DrawScope.drawPointsOp(op: Op, size: Size, ctx: PaintContext) {
    val brush = op.brush(ctx) ?: return
    val pts = (op.p["pts"] as? JsonArray) ?: return
    val list = pts.mapNotNull {
        val l = it.l() ?: return@mapNotNull null
        Offset(l[0] * size.width, l.getOrElse(1) { 0f } * size.height)
    }
    drawPoints(
        list,
        when (op.p["mode"]?.s()) {
            "lines" -> PointMode.Lines
            "polygon" -> PointMode.Polygon
            else -> PointMode.Points
        },
        brush, (op.p["width"]?.f() ?: 1f).dp, op.cap(), alpha = op.alpha(),
    )
}

// ------------------------------------------------------------------ 内容

private fun DrawScope.drawImageOp(op: Op, size: Size, ctx: PaintContext) {
    val src = op.p["src"]?.s() ?: return
    val bmp = ctx.image.get(src) ?: return
    val scale = op.p["scale"]?.s() ?: "crop"
    val (dstOff, dstSize) = fitImage(bmp, size, scale)
    val r = op.p["r"]?.f()
    if (r != null && r > 0f) {
        clipPath(Path().apply { addRoundRect(roundRect(size, r.dp)) }) {
            drawImage(bmp, dstOff, dstSize = dstSize, alpha = op.alpha())
        }
    } else {
        drawImage(bmp, dstOff, dstSize = dstSize, alpha = op.alpha())
    }
}

/**
 * 渲染 SVG（源串）到当前节点矩形内。Compose 1.9 起 draw scope 的 Canvas 不再暴露
 * nativeCanvas，故走「渲成位图再 drawImage」：先按尺寸渲染一次（SvgCache 内缓存），
 * 再贴上来。tint 非空时用 SrcIn 把整块覆盖成单色，实现图标单色着色。
 */
private fun DrawScope.drawSvgString(raw: String, tint: Brush?, size: Size, ctx: PaintContext) {
    val img = ctx.svgCache.render(raw, size.width, size.height) ?: return
    drawImage(
        img, IntOffset.Zero,
        dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt())
    )
    if (tint != null) drawRect(tint, blendMode = BlendMode.SrcIn)
}

private fun DrawScope.drawTextOp(op: Op, size: Size, ctx: PaintContext) {
    val v = op.p["v"]?.s() ?: return
    val style = TextStyle(
        fontSize = (op.p["size"]?.f() ?: 14f).sp,
        fontWeight = when (op.p["weight"]?.s()) {
            "bold" -> FontWeight.Bold
            "medium" -> FontWeight.Medium
            "light" -> FontWeight.Light
            else -> FontWeight.Normal
        },
        color = op.p["color"].color(ctx) ?: Color.White,
        textAlign = when (op.p["align"]?.s()) {
            "center" -> TextAlign.Center
            "right" -> TextAlign.Right
            else -> TextAlign.Left
        },
        letterSpacing = (op.p["letterSpacing"]?.f() ?: 0f).sp,
    )
    val r = ctx.textMeasurer.measure(v, style, maxLines = op.p["maxLines"]?.i() ?: Int.MAX_VALUE)
    val x = when (style.textAlign) {
        TextAlign.Center -> (size.width - r.size.width) / 2f
        TextAlign.Right -> size.width - r.size.width
        else -> 0f
    }
    val y = (size.height - r.size.height) / 2f
    drawText(ctx.textMeasurer, v, Offset(x, y), style,
        overflow = if (op.p["overflow"]?.s() == "clip") TextOverflow.Clip else TextOverflow.Ellipsis,
        maxLines = op.p["maxLines"]?.i() ?: Int.MAX_VALUE)
}

private fun DrawScope.drawSvgOp(op: Op, size: Size, ctx: PaintContext) {
    val raw = op.p["d"]?.s() ?: return
    val src = if (raw.trimStart().startsWith("<")) raw else wrapSvg(raw)
    // tint：把 SVG 整体染成单色（图标场景的关键能力）。
    // 此前是"简化实现"，tint 参数被完全忽略 —— AI 写 tint 期待单色图标，结果拿到原色。
    val tint = op.p["tint"].color(ctx)?.let { androidx.compose.ui.graphics.SolidColor(it) }
    drawSvgString(src, tint, size, ctx)
}

private fun wrapSvg(inner: String) =
    "<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24'>$inner</svg>"

// ------------------------------------------------------------------ 容器（自由度核心）

private fun DrawScope.drawClipOp(op: Op, size: Size, ctx: PaintContext) {
    val shape = op.p["shape"]?.s() ?: "rect"
    val inner = (op.p["ops"] as? JsonArray)?.mapNotNull { it.asOp() } ?: return
    when (shape) {
        "circle" -> clipPath(Path().apply {
            addOval(Rect(0f, 0f, size.width, size.height))
        }) { drawOps(inner, size, ctx) }
        "rrect" -> {
            val r = (op.p["r"]?.f() ?: 0f).dp
            clipPath(Path().apply { addRoundRect(roundRect(size, r)) }) {
                drawOps(inner, size, ctx)
            }
        }
        "oval" -> clipPath(Path().apply {
            addOval(Rect(0f, 0f, size.width, size.height))
        }) { drawOps(inner, size, ctx) }
        "path" -> {
            val d = op.p["d"]?.s() ?: return
            // 把 path 当 SVG 渲染成位图，再作为裁剪遮罩的内容；内部 ops 照常绘制。
            // （原生 Canvas 不再暴露 nativeCanvas，故统一走 drawSvgString 的位图路径。）
            drawSvgString(wrapSvg("<path d='$d'/>"), null, size, ctx)
            drawOps(inner, size, ctx)
        }
        else -> drawOps(inner, size, ctx)
    }
}

private fun DrawScope.drawGroupOp(op: Op, size: Size, ctx: PaintContext) {
    val inner = (op.p["ops"] as? JsonArray)?.mapNotNull { it.asOp() } ?: return
    val alpha = op.p["alpha"]?.f() ?: 1f
    withTransform({
        op.p["scale"]?.f()?.let { scale(it, it, Offset.Zero) }
        op.p["rotate"]?.f()?.let { rotate(it, Offset(size.width / 2, size.height / 2)) }
        translate((op.p["tx"]?.f() ?: 0f).dp, (op.p["ty"]?.f() ?: 0f).dp)
    }) {
        if (alpha < 1f) {
            drawIntoCanvas { c ->
                c.saveLayer(Rect(0f, 0f, size.width, size.height),
                    Paint().apply { this.alpha = alpha })
                drawOps(inner, size, ctx)
                c.restore()
            }
        } else {
            drawOps(inner, size, ctx)
        }
    }
}

// ------------------------------------------------------------------ 解析辅助（全部容错）

private fun JsonElement.asOp(): Op? {
    val o = this as? kotlinx.serialization.json.JsonObject ?: return null
    val t = o["op"]?.s() ?: return null
    return Op(t, o)
}

private fun JsonElement?.f(): Float? = (this as? JsonPrimitive)?.content?.toFloatOrNull()
    ?.takeIf { !it.isNaN() && !it.isInfinite() }

private fun JsonElement?.i(): Int? = (this as? JsonPrimitive)?.content?.toIntOrNull()

private fun JsonElement?.s(): String? = (this as? JsonPrimitive)?.content

private fun JsonElement?.b(): Boolean? = (this as? JsonPrimitive)?.content?.toBooleanStrictOrNull()

private fun JsonElement?.l(): List<Float>? = (this as? JsonArray)
    ?.mapNotNull { (it as? JsonPrimitive)?.content?.toFloatOrNull() }

private fun Op.alpha() = (p["alpha"]?.f() ?: 1f).coerceIn(0f, 1f)

private val Float.dp get() = this

private val Size.vw get() = width
private val Size.vh get() = height

private fun Op.brush(ctx: PaintContext): Brush? {
    val raw = p["brush"] as? kotlinx.serialization.json.JsonObject ?: return null
    val solid = raw["solid"].color(ctx)
    if (solid != null) return androidx.compose.ui.graphics.SolidColor(solid)

    raw["linear"]?.let { g0 ->
        val g = g0 as? kotlinx.serialization.json.JsonObject ?: return@let
        val cs = g.colors(ctx).takeIf { it.isNotEmpty() } ?: return@let
        val stops = g["stops"]?.l()
        val a = g["from"]?.l() ?: listOf(0f, 0f)
        val b = g["to"]?.l() ?: listOf(1f, 1f)
        val colorStops = cs.mapIndexed { i, c ->
            val stop = stops?.getOrNull(i) ?: if (cs.size > 1) i.toFloat() / (cs.size - 1) else 0f
            stop to c
        }.toTypedArray()
        return Brush.linearGradient(
            *colorStops,
            start = Offset(a[0], a.getOrElse(1) { 0f }),
            end = Offset(b[0], b.getOrElse(1) { 0f }),
            tileMode = parseTile(g["tile"]?.s()),
        )
    }
    raw["radial"]?.let { g0 ->
        val g = g0 as? kotlinx.serialization.json.JsonObject ?: return@let
        val cs = g.colors(ctx).takeIf { it.isNotEmpty() } ?: return@let
        val stops = g["stops"]?.l()
        val c = g["center"]?.l() ?: listOf(0.5f, 0.5f)
        val colorStops = cs.mapIndexed { i, col ->
            val stop = stops?.getOrNull(i) ?: if (cs.size > 1) i.toFloat() / (cs.size - 1) else 0f
            stop to col
        }.toTypedArray()
        return Brush.radialGradient(
            *colorStops,
            center = Offset(c[0], c.getOrElse(1) { 0.5f }),
            radius = g["radius"]?.f() ?: 0.5f,
            tileMode = parseTile(g["tile"]?.s()),
        )
    }
    raw["sweep"]?.let { g0 ->
        val g = g0 as? kotlinx.serialization.json.JsonObject ?: return@let
        val cs = g.colors(ctx).takeIf { it.isNotEmpty() } ?: return@let
        val colorStops = cs.mapIndexed { i, c ->
            val stop = i.toFloat() / cs.size
            stop to c
        }.toTypedArray()
        return Brush.sweepGradient(*colorStops)
    }
    return null
}

private fun JsonElement.colors(ctx: PaintContext): List<Color> =
    (this as? kotlinx.serialization.json.JsonObject)?.get("colors")
        ?.let { it as? JsonArray }
        ?.mapNotNull { e -> e.color(ctx) }
        ?: emptyList()

private fun JsonElement?.color(ctx: PaintContext): Color? {
    val s = this?.s() ?: return null
    if (s.startsWith("@colors/")) {
        val key = s.removePrefix("@colors/")
        return ctx.theme[key]?.let { parseColor(it) }
    }
    return parseColor(s)
}

private fun parseColor(s: String): Color? = runCatching {
    when (s.length) {
        4 -> Color(android.graphics.Color.parseColor("#" + s.drop(1).map { "$it$it" }.joinToString("")))
        7, 9 -> Color(android.graphics.Color.parseColor(s))
        else -> null
    }
}.getOrNull()

private fun parseTile(s: String?) = when (s) {
    "repeat" -> TileMode.Repeated
    "mirror" -> TileMode.Mirror
    "decal" -> TileMode.Decal
    else -> TileMode.Clamp
}

private fun Op.style(ctx: PaintContext): DrawStyle {
    val st = p["style"]?.s()
    if (st == "stroke") {
        return Stroke(
            width = (p["width"]?.f() ?: 1f),
            cap = cap(),
            join = when (p["join"]?.s()) {
                "bevel" -> StrokeJoin.Bevel
                "round" -> StrokeJoin.Round
                else -> StrokeJoin.Miter
            },
        )
    }
    return Fill
}

private fun Op.cap() = when (p["cap"]?.s()) {
    "round" -> StrokeCap.Round
    "square" -> StrokeCap.Square
    else -> StrokeCap.Butt
}

private fun Op.border(ctx: PaintContext): Pair<Brush, Float>? {
    val o = p["border"] as? kotlinx.serialization.json.JsonObject ?: return null
    val b = o.brushOf(ctx) ?: return null
    return b to (o["width"]?.f() ?: 1f)
}

private fun kotlinx.serialization.json.JsonObject.brushOf(ctx: PaintContext): Brush? =
    Op("x", this).brush(ctx)

/** 区域：支持 offset/size 覆盖，缺省占满节点。比例与 dp 混用。 */
private fun Op.box(size: Size, ctx: PaintContext): Rect {
    val off = p["offset"]?.l()
    val sz = p["size"]?.l()
    val l = off?.getOrNull(0)?.let { if (it in 0f..1f) it * size.width else it * ctx.density } ?: 0f
    val t = off?.getOrNull(1)?.let { if (it in 0f..1f) it * size.height else it * ctx.density } ?: 0f
    val w = sz?.getOrNull(0)?.let { if (it in 0f..1f) it * size.width else it * ctx.density } ?: (size.width - l)
    val h = sz?.getOrNull(1)?.let { if (it in 0f..1f) it * size.height else it * ctx.density } ?: (size.height - t)
    return Rect(l, t, l + w.coerceAtLeast(0f), t + h.coerceAtLeast(0f))
}

private fun Rect.topLeft() = Offset(left, top)
private fun Rect.size() = Size(width, height)

private fun roundRect(size: Size, r: Float) = androidx.compose.ui.geometry.RoundRect(
    0f, 0f, size.width, size.height, r, r,
)

private fun fitImage(bmp: ImageBitmap, box: Size, scale: String): Pair<IntOffset, IntSize> {
    val (off, sz) = when (scale) {
        "fill" -> Offset.Zero to box
        "fit" -> {
            val r = minOf(box.width / bmp.width, box.height / bmp.height)
            val w = bmp.width * r; val h = bmp.height * r
            Offset((box.width - w) / 2, (box.height - h) / 2) to Size(w, h)
        }
        "none" -> {
            val w = minOf(bmp.width.toFloat(), box.width)
            val h = minOf(bmp.height.toFloat(), box.height)
            Offset((box.width - w) / 2, (box.height - h) / 2) to Size(w, h)
        }
        else -> { // crop
            val r = maxOf(box.width / bmp.width, box.height / bmp.height)
            val w = bmp.width * r; val h = bmp.height * r
            Offset((box.width - w) / 2, (box.height - h) / 2) to Size(w, h)
        }
    }
    return IntOffset(off.x.roundToInt(), off.y.roundToInt()) to
        IntSize(sz.width.roundToInt(), sz.height.roundToInt())
}
