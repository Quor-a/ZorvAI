package com.codecanvas.renderer.svg

import com.codecanvas.core.highlight.CodeCardLayout
import com.codecanvas.core.highlight.HighlightedLine
import com.codecanvas.core.model.CanvasSpec
import com.codecanvas.core.render.RenderKind
import com.codecanvas.core.render.RenderOutput
import com.codecanvas.core.render.RenderSource
import com.codecanvas.core.render.Renderer
import com.codecanvas.core.script.DrawCommand
import com.codecanvas.core.script.DrawList
import com.codecanvas.core.script.TextAlign
import kotlin.math.cos
import kotlin.math.sin

/**
 * SVG 后端：输出**矢量**而非位图。
 *
 * 适用场景：流程图 / 架构图 / 需要无限放大或印刷 / 要转成 PDF 或插入文档。
 *
 * 关键修正（相对早期版本）：
 * 1. **<g> 标签闭合**：Translate/Rotate/Scale 开的组此前从不关闭，产出的是非法 SVG，
 *    浏览器直接拒绝渲染。现在用统一计数器 openGroups 管理，结束时一次性收口。
 * 2. **渐变角度**：此前 x2/y2 硬编码 100%，angleDeg 被完全忽略。
 * 3. **CodeCard**：此前是空壳（只有文本行，没有窗口栏/行号/高亮/背景），
 *    现在复用 [CodeCardLayout]，与 CANVAS 后端版式一致。
 */
class SvgRenderer : Renderer {

    override val kind: RenderKind = RenderKind.SVG

    override fun supports(source: RenderSource): Boolean =
        source is RenderSource.Commands || source is RenderSource.CodeCard

    override suspend fun render(spec: CanvasSpec, source: RenderSource): RenderOutput {
        when (source) {
            is RenderSource.Commands -> {
                val svg = emit(spec, source.list)
                return RenderOutput.Vector(svg, spec.pixelWidth, spec.pixelHeight)
            }
            is RenderSource.CodeCard -> {
                // 代码卡片按内容自适应高度：矢量输出没有位图的内存上限顾虑，
                // 但仍尊重 spec.maxEdgePx 以免下游转位图时炸掉
                val lines = highlight(source)
                val (list, needH) = CodeCardLayout.build(
                    lines = lines,
                    canvasWidth = spec.width,
                    title = source.title,
                    showLineNumbers = source.showLineNumbers,
                )
                val h = maxOf(spec.height, needH)
                val svg = emit(spec.copy(height = h), list)
                return RenderOutput.Vector(svg, spec.pixelWidth, (h * spec.scale).toInt())
            }
            is RenderSource.Markup -> throw IllegalArgumentException("SVG 后端不支持 HTML")
        }
    }

    override fun release() {}

    /** SVG 后端无 WebView 依赖，用内置 lexer 兜底（由宿主注册的 Highlighter 优先） */
    private suspend fun highlight(card: RenderSource.CodeCard): List<HighlightedLine> =
        runCatching {
            com.codecanvas.core.CodeCanvas.highlighter()
                .highlightToLines(card.code, card.language, card.theme)
        }.getOrDefault(
            card.code.split('\n').mapIndexed { i, l ->
                HighlightedLine(i + 1, listOf(com.codecanvas.core.highlight.Highlighter.Span(l, "#E6EDF3")))
            }
        )

    // ------------------------------------------------------------------
    private fun emit(spec: CanvasSpec, list: DrawList): String = buildString {
        val w = spec.pixelWidth
        val h = spec.pixelHeight
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
        append("<svg xmlns=\"http://www.w3.org/2000/svg\" version=\"1.1\" ")
        append("width=\"$w\" height=\"$h\" viewBox=\"0 0 $w $h\">")

        append(backgroundDefs(spec))

        val scale = spec.scale
        append("<g transform=\"translate(${f(spec.padding.horizontal * scale)},${f(spec.padding.vertical * scale)}) scale(${f(scale)})\">")

        var style: DrawCommand.PushStyle? = null
        val styleStack = ArrayDeque<DrawCommand.PushStyle>()
        // 所有开组的指令统一计数，收口时一次性闭合 —— 早期版本只统计 Save/Restore，
        // 导致 translate/rotate/scale 的 <g> 泄漏成非法文档
        var openGroups = 0

        for (i in 0 until list.size) {
            when (val c = list[i]) {
                is DrawCommand.PushStyle -> { styleStack.addLast(c); style = c }
                is DrawCommand.PopStyle -> style = styleStack.removeLastOrNull()
                    ?.let { styleStack.lastOrNull() }

                is DrawCommand.Save -> { append("<g>"); openGroups++ }
                is DrawCommand.Restore -> if (openGroups > 0) { append("</g>"); openGroups-- }

                is DrawCommand.Translate -> { append("<g transform=\"translate(${f(c.dx)},${f(c.dy)})\">"); openGroups++ }
                is DrawCommand.Rotate -> { append("<g transform=\"rotate(${f(c.degrees)})\">"); openGroups++ }
                is DrawCommand.Scale -> { append("<g transform=\"scale(${f(c.sx)},${f(c.sy)})\">"); openGroups++ }

                is DrawCommand.ClipRect -> append(
                    "<clipPath id=\"cp$i\"><rect x=\"${f(c.x)}\" y=\"${f(c.y)}\" " +
                        "width=\"${f(c.w)}\" height=\"${f(c.h)}\"/></clipPath>"
                )

                is DrawCommand.Line -> append(
                    "<line x1=\"${f(c.x1)}\" y1=\"${f(c.y1)}\" x2=\"${f(c.x2)}\" y2=\"${f(c.y2)}\" ${strokeAttrs(style)}/>"
                )
                is DrawCommand.Rect -> append(
                    "<rect x=\"${f(c.x)}\" y=\"${f(c.y)}\" width=\"${f(c.w)}\" height=\"${f(c.h)}\" ${shapeAttrs(style)}/>"
                )
                is DrawCommand.RoundRect -> append(
                    "<rect x=\"${f(c.x)}\" y=\"${f(c.y)}\" width=\"${f(c.w)}\" height=\"${f(c.h)}\" " +
                        "rx=\"${f(c.radius)}\" ry=\"${f(c.radius)}\" ${shapeAttrs(style)}/>"
                )
                is DrawCommand.Circle -> append(
                    "<circle cx=\"${f(c.cx)}\" cy=\"${f(c.cy)}\" r=\"${f(c.r)}\" ${shapeAttrs(style)}/>"
                )
                is DrawCommand.Oval -> append(
                    "<ellipse cx=\"${f(c.x + c.w / 2)}\" cy=\"${f(c.y + c.h / 2)}\" " +
                        "rx=\"${f(c.w / 2)}\" ry=\"${f(c.h / 2)}\" ${shapeAttrs(style)}/>"
                )
                is DrawCommand.Arc -> append(arcPath(c, style))
                is DrawCommand.Polyline -> {
                    val pts = (0 until c.points.size / 2).joinToString(" ") { k ->
                        "${f(c.points[k * 2])},${f(c.points[k * 2 + 1])}"
                    }
                    append(
                        if (c.closed) "<polygon points=\"$pts\" ${shapeAttrs(style)}/>"
                        else "<polyline points=\"$pts\" fill=\"none\" ${strokeAttrs(style)}/>"
                    )
                }
                is DrawCommand.Path -> append("<path d=\"${escAttr(c.d)}\" ${shapeAttrs(style)}/>")

                is DrawCommand.Text -> append(
                    "<text x=\"${f(c.x)}\" y=\"${f(c.y)}\" ${textAttrs(c.font, style)} " +
                        "text-anchor=\"${svgAnchor(c.align)}\" xml:space=\"preserve\">${esc(c.text)}</text>"
                )
                is DrawCommand.RichText -> append(richText(c, style))
                is DrawCommand.Image -> append(
                    "<image x=\"${f(c.x)}\" y=\"${f(c.y)}\" width=\"${f(c.w)}\" " +
                        "height=\"${f(c.h)}\" href=\"${escAttr(c.source)}\"/>"
                )
            }
        }
        repeat(openGroups) { append("</g>") }
        append("</g></svg>")
    }

    // ---------- 背景 ----------
    private fun backgroundDefs(spec: CanvasSpec): String {
        val w = spec.pixelWidth
        val h = spec.pixelHeight
        return when (val bg = spec.background) {
            is CanvasSpec.Background.Solid ->
                "<rect width=\"$w\" height=\"$h\" fill=\"${hex(bg.color)}\"/>"

            is CanvasSpec.Background.LinearGradient -> buildString {
                val rad = Math.toRadians(bg.angleDeg.toDouble())
                // 从中心向 angleDeg 方向投影，保证与 Android LinearGradient 视觉一致
                val x2 = (50 + 50 * cos(rad)).toFloat()
                val y2 = (50 + 50 * sin(rad)).toFloat()
                val x1 = (50 - (x2 - 50)).toFloat()
                val y1 = (50 - (y2 - 50)).toFloat()
                append("<defs><linearGradient id=\"bgGrad\" ")
                append("x1=\"${f(x1)}%\" y1=\"${f(y1)}%\" x2=\"${f(x2)}%\" y2=\"${f(y2)}%\">")
                val n = (bg.colors.size - 1).coerceAtLeast(1)
                bg.colors.forEachIndexed { i, c ->
                    append("<stop offset=\"${(i * 100f / n).toInt()}%\" stop-color=\"${hex(c)}\"/>")
                }
                append("</linearGradient></defs>")
                append("<rect width=\"$w\" height=\"$h\" fill=\"url(#bgGrad)\"/>")
            }

            CanvasSpec.Background.Transparent -> ""
        }
    }

    // ---------- 片段 ----------
    private fun richText(c: DrawCommand.RichText, style: DrawCommand.PushStyle?): String {
        // 用 tspan 的 x 累加定位；SVG viewer 会自行按字体度量排布，
        // 这里只需保证顺序与颜色正确
        return buildString {
            append("<text x=\"${f(c.x)}\" y=\"${f(c.y)}\" ${textAttrs(c.font, style)} xml:space=\"preserve\">")
            c.spans.forEach { sp ->
                val bold = if (sp.bold) " font-weight=\"bold\"" else ""
                append("<tspan fill=\"${hex(sp.color)}\"$bold>${esc(sp.text)}</tspan>")
            }
            append("</text>")
        }
    }

    private fun arcPath(c: DrawCommand.Arc, style: DrawCommand.PushStyle?): String {
        val cx = c.x + c.w / 2
        val cy = c.y + c.h / 2
        val rx = c.w / 2
        val ry = c.h / 2
        val a0 = Math.toRadians(c.startDeg.toDouble())
        val a1 = Math.toRadians((c.startDeg + c.sweepDeg).toDouble())
        val x0 = cx + rx * cos(a0)
        val y0 = cy + ry * sin(a0)
        val x1 = cx + rx * cos(a1)
        val y1 = cy + ry * sin(a1)
        val large = if (c.sweepDeg > 180f) 1 else 0
        val sweep = if (c.sweepDeg > 0f) 1 else 0
        val d = buildString {
            append("M ${f(x0.toFloat())} ${f(y0.toFloat())} ")
            append("A ${f(rx)} ${f(ry)} 0 $large $sweep ${f(x1.toFloat())} ${f(y1.toFloat())}")
            if (c.useCenter) append(" L ${f(cx)} ${f(cy)} Z")
        }
        return "<path d=\"$d\" fill=\"${style?.style?.fill?.let { hex(it) } ?: "none"}\" ${strokeAttrs(style)}/>"
    }

    private fun shapeAttrs(s: DrawCommand.PushStyle?): String {
        val st = s?.style ?: return "fill=\"#000000\""
        return buildString {
            append("fill=\"${st.fill?.let { hex(it) } ?: "none"}\"")
            val stroke = st.stroke
            if (stroke != null) append(" stroke=\"${hex(stroke)}\" stroke-width=\"${f(st.strokeWidth)}\"")
            if (st.alpha < 1f) append(" opacity=\"${f(st.alpha)}\"")
        }
    }

    private fun strokeAttrs(s: DrawCommand.PushStyle?): String {
        val st = s?.style
        val color = st?.stroke ?: st?.fill ?: 0xFF000000.toInt()
        return buildString {
            append("stroke=\"${hex(color)}\" stroke-width=\"${f(st?.strokeWidth ?: 1f)}\"")
            if (st != null && st.alpha < 1f) append(" opacity=\"${f(st.alpha)}\"")
        }
    }

    private fun textAttrs(font: com.codecanvas.core.model.FontSpec, s: DrawCommand.PushStyle?): String {
        val color = s?.style?.fill ?: 0xFF000000.toInt()
        val fam = if (font.family == "monospace") "monospace" else font.family
        return buildString {
            append("font-family=\"${escAttr(fam)}, monospace\" font-size=\"${f(font.size)}\" fill=\"${hex(color)}\"")
            if (font.bold) append(" font-weight=\"bold\"")
            if (font.italic) append(" font-style=\"italic\"")
        }
    }

    private fun svgAnchor(a: TextAlign) = when (a) {
        TextAlign.LEFT -> "start"
        TextAlign.CENTER -> "middle"
        TextAlign.RIGHT -> "end"
    }

    /** 去掉浮点尾巴：1.0 → "1"，避免 SVG 里出现 12.0000001 这种噪声 */
    private fun f(v: Float): String =
        if (v == v.toInt().toFloat()) v.toInt().toString() else "%.3f".format(v).trimEnd('0').trimEnd('.')

    private fun hex(color: Int): String {
        val a = (color ushr 24) and 0xFF
        return if (a == 0xFF) "#%06X".format(color and 0x00FFFFFF) else "#%08X".format(color)
    }

    private fun esc(s: String) = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun escAttr(s: String) = esc(s).replace("\"", "&quot;")
}
