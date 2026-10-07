package com.codecanvas.renderer.compose

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.PaintingStyle
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.codecanvas.core.highlight.CodeCardLayout
import com.codecanvas.core.highlight.HighlightedLine
import com.codecanvas.core.model.CanvasSpec
import com.codecanvas.core.model.FontSpec
import com.codecanvas.core.render.RenderKind
import com.codecanvas.core.render.RenderOutput
import com.codecanvas.core.render.RenderSource
import com.codecanvas.core.render.Renderer
import com.codecanvas.core.script.DrawCommand
import com.codecanvas.core.script.DrawList
import com.codecanvas.core.script.TextAlign
import kotlin.math.min

/**
 * COMPOSE 后端：直接产出 [ImageBitmap]，供 Compose UI 内联展示或二次合成。
 *
 * 关键修正（相对早期版本）：
 * 1. **Text.align 此前未实现** —— 一律左对齐，脚本传 CENTER/RIGHT 无效。
 * 2. **FontSpec 被忽略** —— family/bold/italic/letterSpacing 全都丢失，
 *    中文字体因此无法指定，不同机型行宽不一致的坑就出在这里。
 * 3. **不支持 fill + stroke 同时绘制** —— 早期只取其一，带描边的卡片会缺边。
 * 4. **支持 CodeCard** —— 复用 [CodeCardLayout]，与 CANVAS 后端版式一致。
 */
class ComposeRenderer : Renderer {

    override val kind: RenderKind = RenderKind.COMPOSE

    override fun supports(source: RenderSource): Boolean = source !is RenderSource.Markup

    override suspend fun render(spec: CanvasSpec, source: RenderSource): RenderOutput {
        val (list, height) = when (source) {
            is RenderSource.Commands -> source.list to spec.height
            is RenderSource.CodeCard -> {
                val lines = highlight(source)
                val (l, needH) = CodeCardLayout.build(
                    lines = lines,
                    canvasWidth = spec.width,
                    title = source.title,
                    showLineNumbers = source.showLineNumbers,
                )
                l to maxOf(spec.height, needH)
            }
            is RenderSource.Markup -> throw IllegalArgumentException("COMPOSE 后端不支持 HTML")
        }

        val usedSpec = if (height != spec.height) spec.copy(height = height) else spec
        var pw = usedSpec.pixelWidth
        var ph = usedSpec.pixelHeight
        val ratio = maxOf(pw, ph).toFloat() / min(usedSpec.maxEdgePx, 4096)
        if (ratio > 1f) { pw = (pw / ratio).toInt(); ph = (ph / ratio).toInt() }
        pw = pw.coerceAtLeast(1); ph = ph.coerceAtLeast(1)

        val bmp = ImageBitmap(pw, ph)
        val canvas = Canvas(bmp)
        val scope = CanvasDrawScope()
        scope.draw(
            density = Density(1f),
            layoutDirection = LayoutDirection.Ltr,
            canvas = canvas,
            size = Size(pw.toFloat(), ph.toFloat()),
        ) {
            drawBackground(usedSpec)
            drawCommands(list, usedSpec)
        }
        return RenderOutput.ComposeBitmap(bmp)
    }

    override fun release() {}

    private suspend fun highlight(card: RenderSource.CodeCard): List<HighlightedLine> =
        runCatching {
            com.codecanvas.core.CodeCanvas.highlighter()
                .highlightToLines(card.code, card.language, card.theme)
        }.getOrDefault(
            card.code.split('\n').mapIndexed { i, l ->
                HighlightedLine(i + 1, listOf(com.codecanvas.core.highlight.Highlighter.Span(l, "#E6EDF3")))
            }
        )

    private fun DrawScope.drawBackground(spec: CanvasSpec) {
        when (val bg = spec.background) {
            is CanvasSpec.Background.Solid ->
                drawRect(androidx.compose.ui.graphics.Color(bg.color))
            is CanvasSpec.Background.LinearGradient -> drawRect(
                brush = androidx.compose.ui.graphics.Brush.linearGradient(
                    colors = bg.colors.map { androidx.compose.ui.graphics.Color(it) }
                )
            )
            CanvasSpec.Background.Transparent -> Unit
        }
    }

    private fun DrawScope.drawCommands(list: DrawList, spec: CanvasSpec) {
        val stack = ArrayDeque<com.codecanvas.core.model.Style>()
        var style: com.codecanvas.core.model.Style? = null

        // 逻辑坐标 → 物理像素
        drawIntoCanvas { c ->
            c.save()
            c.scale(spec.scale, spec.scale)
            c.translate(spec.padding.horizontal, spec.padding.vertical)
        }

        for (i in 0 until list.size) {
            when (val c = list[i]) {
                is DrawCommand.PushStyle -> { stack.addLast(c.style); style = c.style }
                is DrawCommand.PopStyle -> { stack.removeLastOrNull(); style = stack.lastOrNull() }

                is DrawCommand.Save -> drawIntoCanvas { it.save() }
                is DrawCommand.Restore -> drawIntoCanvas { it.restore() }
                is DrawCommand.Translate -> drawIntoCanvas { it.translate(c.dx, c.dy) }
                is DrawCommand.Rotate -> drawIntoCanvas { it.rotate(c.degrees) }
                is DrawCommand.Scale -> drawIntoCanvas { it.scale(c.sx, c.sy) }
                // DrawScope.clipRect 的唯一重载带 block 参数，不能当语句直接调；
                // Canvas.clipRect(left, top, right, bottom) 才是逐命令语义。
                is DrawCommand.ClipRect -> drawIntoCanvas { canvas ->
                    canvas.clipRect(c.x, c.y, c.x + c.w, c.y + c.h)
                }

                is DrawCommand.Line -> drawIntoCanvas { canvas ->
                    // fill+stroke 同时存在时先填后描
                    style?.fill?.let { col ->
                        canvas.drawLine(
                            Offset(c.x1, c.y1), Offset(c.x2, c.y2),
                            fillPaint(col, style, stroke = false)
                        )
                    }
                    canvas.drawLine(
                        Offset(c.x1, c.y1), Offset(c.x2, c.y2), strokePaint(style)
                    )
                }

                is DrawCommand.Rect -> drawIntoCanvas { canvas ->
                    drawShape(canvas, style) { p, _ ->
                        canvas.drawRect(Rect(c.x, c.y, c.x + c.w, c.y + c.h), p)
                    }
                }
                is DrawCommand.RoundRect -> drawIntoCanvas { canvas ->
                    drawShape(canvas, style) { p, _ ->
                        canvas.drawRoundRect(c.x, c.y, c.x + c.w, c.y + c.h, c.radius, c.radius, p)
                    }
                }
                is DrawCommand.Circle -> drawIntoCanvas { canvas ->
                    drawShape(canvas, style) { p, _ -> canvas.drawCircle(Offset(c.cx, c.cy), c.r, p) }
                }
                is DrawCommand.Oval -> drawIntoCanvas { canvas ->
                    drawShape(canvas, style) { p, _ ->
                        canvas.drawOval(Rect(c.x, c.y, c.x + c.w, c.y + c.h), p)
                    }
                }
                is DrawCommand.Arc -> drawIntoCanvas { canvas ->
                    drawShape(canvas, style) { p, _ ->
                        canvas.drawArc(Rect(c.x, c.y, c.x + c.w, c.y + c.h),
                            c.startDeg, c.sweepDeg, c.useCenter, p)
                    }
                }
                is DrawCommand.Polyline -> drawIntoCanvas { canvas ->
                    val pts = (0 until c.points.size / 2).map { k ->
                        Offset(c.points[k * 2], c.points[k * 2 + 1])
                    }
                    // 实测 Canvas.drawPoints(PointMode, List<Offset>, Paint)——pointMode 在前。
                    canvas.drawPoints(
                        if (c.closed) PointMode.Polygon else PointMode.Lines,
                        pts,
                        strokePaint(style))
                }
                is DrawCommand.Path -> drawIntoCanvas { canvas ->
                    drawShape(canvas, style) { p, _ ->
                        canvas.drawPath(
                            androidx.compose.ui.graphics.vector.PathParser()
                                .parsePathString(c.d).toPath(), p
                        )
                    }
                }

                is DrawCommand.Text -> drawIntoCanvas { canvas ->
                    val p = androidPaint(c.font, style)
                    val x = when (c.align) {
                        TextAlign.LEFT -> c.x
                        TextAlign.CENTER -> c.x - p.measureText(c.text) / 2f
                        TextAlign.RIGHT -> c.x - p.measureText(c.text)
                    }
                    canvas.nativeCanvas.drawText(c.text, x, c.y, p)
                }

                is DrawCommand.RichText -> drawIntoCanvas { canvas ->
                    var x = c.x
                    c.spans.forEach { sp ->
                        val p = androidPaint(c.font, style).apply {
                            color = sp.color
                            if (sp.bold) typeface = android.graphics.Typeface
                                .create(typeface ?: android.graphics.Typeface.MONOSPACE,
                                        android.graphics.Typeface.BOLD)
                        }
                        canvas.nativeCanvas.drawText(sp.text, x, c.y, p)
                        x += p.measureText(sp.text)
                    }
                }

                is DrawCommand.Image -> Unit
            }
        }
    }

    /** 统一「先填后描」，缺一补黑 */
    private inline fun drawShape(
        canvas: Canvas,
        style: com.codecanvas.core.model.Style?,
        block: (Paint, Boolean) -> Unit,
    ) {
        // style 是 core 模块的 public API 属性，跨模块不能 smart cast，先取局部变量。
        val fill = style?.fill
        val stroke = style?.stroke
        when {
            fill != null && stroke != null -> {
                block(fillPaint(fill, style, stroke = false), false)
                block(strokePaint(style), true)
            }
            fill != null -> block(fillPaint(fill, style, stroke = false), false)
            stroke != null -> block(strokePaint(style), true)
            else -> block(fillPaint(0xFF000000.toInt(), style, stroke = false), false)
        }
    }

    private fun fillPaint(color: Int, st: com.codecanvas.core.model.Style?, stroke: Boolean) = Paint().apply {
        this.color = androidx.compose.ui.graphics.Color(color)
        this.style = if (stroke) PaintingStyle.Stroke else PaintingStyle.Fill
        this.strokeWidth = st?.strokeWidth ?: 1f
        if (st != null && st.alpha < 1f) this.alpha = st.alpha
    }

    private fun strokePaint(st: com.codecanvas.core.model.Style?) = Paint().apply {
        val color = st?.stroke ?: st?.fill ?: 0xFF000000.toInt()
        this.color = androidx.compose.ui.graphics.Color(color)
        this.style = PaintingStyle.Stroke
        this.strokeWidth = st?.strokeWidth ?: 1f
        if (st != null && st.alpha < 1f) this.alpha = st.alpha
    }

    /** 保留 FontSpec 的 family / bold / italic / letterSpacing */
    private fun androidPaint(f: FontSpec, st: com.codecanvas.core.model.Style?) =
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = st?.fill ?: 0xFF000000.toInt()
            textSize = f.size
            typeface = when (f.family) {
                "monospace" -> android.graphics.Typeface.MONOSPACE
                "serif" -> android.graphics.Typeface.SERIF
                "sans", "sans-serif" -> android.graphics.Typeface.SANS_SERIF
                else -> runCatching {
                    android.graphics.Typeface.create(f.family, android.graphics.Typeface.NORMAL)
                }.getOrDefault(android.graphics.Typeface.MONOSPACE)
            }
            if (f.bold || f.italic) {
                typeface = android.graphics.Typeface.create(typeface, when {
                    f.bold && f.italic -> android.graphics.Typeface.BOLD_ITALIC
                    f.bold -> android.graphics.Typeface.BOLD
                    else -> android.graphics.Typeface.ITALIC
                })
            }
            if (f.letterSpacing != 0f) letterSpacing = f.letterSpacing
        }
}
