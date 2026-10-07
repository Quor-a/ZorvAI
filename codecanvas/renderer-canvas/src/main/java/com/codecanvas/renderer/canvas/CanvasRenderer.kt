package com.codecanvas.renderer.canvas

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
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
import kotlin.math.cos
import kotlin.math.sin

/**
 * CANVAS 后端：纯原生 Bitmap + android.graphics.Canvas。
 *
 * 无额外依赖、速度最快、内存可控，是**脚本绘图**的默认后端。
 *
 * 关键修正（相对早期版本）：
 * 1. **Text.align 此前完全没实现** —— 脚本写 `text(..., 'center')` 会被忽略，
 *    一律按左对齐画。现在按 align 用 measureText 反算起点。
 * 2. **支持 CodeCard** —— 此前只有 WEBVIEW 能出代码卡片，
 *    一旦 WebView 不可用（Android Go / 更新中 / MDM 禁用）整条链路就断。
 *    现在复用 [CodeCardLayout]，无需 WebView 也能出卡片。
 * 3. **Style 栈与 Save/Restore 分离** —— 此前 PopStyle 用 removeLastOrNull 后又取 lastOrNull，
 *    与 Canvas 自己的 save/restore 混用时样式会错乱。
 * 4. **fill + stroke 同时存在时**正确「先填后描」，此前会漏掉描边。
 */
class CanvasRenderer : Renderer {

    override val kind: RenderKind = RenderKind.CANVAS

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
            is RenderSource.Markup -> throw IllegalArgumentException("CANVAS 后端不支持 HTML")
        }

        val usedSpec = if (height != spec.height) spec.copy(height = height) else spec
        return RenderOutput.Bitmap(draw(usedSpec, list))
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

    private fun draw(spec: CanvasSpec, list: DrawList): Bitmap {
        var pw = spec.pixelWidth
        var ph = spec.pixelHeight
        val ratio = maxOf(pw, ph).toFloat() / spec.maxEdgePx
        if (ratio > 1f) { pw = (pw / ratio).toInt(); ph = (ph / ratio).toInt() }
        pw = pw.coerceAtLeast(1); ph = ph.coerceAtLeast(1)

        val bmp = Bitmap.createBitmap(pw, ph, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)

        when (val bg = spec.background) {
            is CanvasSpec.Background.Solid ->
                canvas.drawColor(bg.color, PorterDuff.Mode.SRC)
            is CanvasSpec.Background.LinearGradient -> {
                val rad = Math.toRadians(bg.angleDeg.toDouble())
                val dx = cos(rad).toFloat(); val dy = sin(rad).toFloat()
                Paint().apply {
                    shader = LinearGradient(
                        0f, 0f, pw * dx, ph * dy, bg.colors, null, Shader.TileMode.CLAMP
                    )
                    canvas.drawPaint(this)
                }
            }
            CanvasSpec.Background.Transparent -> Unit
        }

        canvas.save()
        canvas.scale(spec.scale, spec.scale)
        canvas.translate(spec.padding.horizontal, spec.padding.vertical)

        val painter = Painter(canvas, spec.antialias)
        for (i in 0 until list.size) painter.apply(list[i])
        canvas.restore()
        return bmp
    }

    private class Painter(private val c: Canvas, private val antialias: Boolean) {

        private val paint = Paint()
        private val styleStack = ArrayDeque<com.codecanvas.core.model.Style>()
        private var style: com.codecanvas.core.model.Style? = null

        fun apply(cmd: DrawCommand) {
            when (cmd) {
                is DrawCommand.PushStyle -> style = cmd.style.also { styleStack.addLast(it) }
                is DrawCommand.PopStyle -> { styleStack.removeLastOrNull(); style = styleStack.lastOrNull() }

                // Save/Restore 交给 Canvas 自己管矩阵；样式栈独立维护，互不干扰
                is DrawCommand.Save -> c.save()
                is DrawCommand.Restore -> c.restore()

                is DrawCommand.Translate -> c.translate(cmd.dx, cmd.dy)
                is DrawCommand.Rotate -> c.rotate(cmd.degrees)
                is DrawCommand.Scale -> c.scale(cmd.sx, cmd.sy)
                is DrawCommand.ClipRect -> c.clipRect(cmd.x, cmd.y, cmd.x + cmd.w, cmd.y + cmd.h)

                is DrawCommand.Line -> shape { c.drawLine(cmd.x1, cmd.y1, cmd.x2, cmd.y2, it) }
                is DrawCommand.Rect -> shape { c.drawRect(cmd.x, cmd.y, cmd.x + cmd.w, cmd.y + cmd.h, it) }
                is DrawCommand.RoundRect -> shape {
                    c.drawRoundRect(RectF(cmd.x, cmd.y, cmd.x + cmd.w, cmd.y + cmd.h), cmd.radius, cmd.radius, it)
                }
                is DrawCommand.Circle -> shape { c.drawCircle(cmd.cx, cmd.cy, cmd.r, it) }
                is DrawCommand.Oval -> shape { c.drawOval(RectF(cmd.x, cmd.y, cmd.x + cmd.w, cmd.y + cmd.h), it) }
                is DrawCommand.Arc -> shape {
                    c.drawArc(RectF(cmd.x, cmd.y, cmd.x + cmd.w, cmd.y + cmd.h),
                        cmd.startDeg, cmd.sweepDeg, cmd.useCenter, it)
                }
                is DrawCommand.Polyline -> shape { p ->
                    val path = Path()
                    val n = cmd.points.size / 2
                    for (k in 0 until n) {
                        val x = cmd.points[k * 2]; val y = cmd.points[k * 2 + 1]
                        if (k == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }
                    if (cmd.closed) path.close()
                    c.drawPath(path, p)
                }
                is DrawCommand.Path -> shape { c.drawPath(PathParser.parse(cmd.d), it) }

                is DrawCommand.Text -> {
                    val p = textPaint(cmd.font)
                    // align 曾在此被完全忽略，脚本传 'center' 也是左对齐
                    val x = when (cmd.align) {
                        TextAlign.LEFT -> cmd.x
                        TextAlign.CENTER -> cmd.x - p.measureText(cmd.text) / 2f
                        TextAlign.RIGHT -> cmd.x - p.measureText(cmd.text)
                    }
                    cmd.maxWidth?.let { max ->
                        if (p.measureText(cmd.text) > max) {
                            c.save()
                            c.clipRect(x, cmd.y - cmd.font.size * 2, x + max, cmd.y + cmd.font.size)
                        }
                    }
                    c.drawText(cmd.text, x, cmd.y, p)
                    if (cmd.maxWidth != null) c.restore()
                }

                is DrawCommand.RichText -> {
                    var x = cmd.x
                    cmd.spans.forEach { sp ->
                        val p = textPaint(cmd.font).apply {
                            color = sp.color
                            if (sp.bold) typeface = Typeface.create(typeface ?: Typeface.MONOSPACE, Typeface.BOLD)
                        }
                        c.drawText(sp.text, x, cmd.y, p)
                        x += p.measureText(sp.text)
                    }
                }

                is DrawCommand.Image -> Unit // 由宿主解码后自行绘制，SDK 不强制依赖图片库
            }
        }

        /** 统一处理填充 + 描边：两者同时存在时先填后描，缺一补黑 */
        private inline fun shape(block: (Paint) -> Unit) {
            val st = style
            paint.reset()
            paint.isAntiAlias = antialias

            if (st == null) {
                paint.style = Paint.Style.FILL
                paint.color = Color.BLACK
                block(paint)
                return
            }

            paint.alpha = (255 * st.alpha).toInt().coerceIn(0, 255)
            st.shadow?.let { sh ->
                // setShadowLayer 需要 color 带 alpha，否则阴影恒为不透明
                paint.setShadowLayer(sh.blur, sh.dx, sh.dy, sh.color)
            }

            val hasFill = st.fill != null
            val hasStroke = st.stroke != null

            when {
                hasFill && hasStroke -> {
                    paint.style = Paint.Style.FILL
                    paint.color = st.fill!!
                    block(paint)
                    paint.style = Paint.Style.STROKE
                    paint.color = st.stroke!!
                    paint.strokeWidth = st.strokeWidth
                    block(paint)
                }
                hasFill -> {
                    paint.style = Paint.Style.FILL
                    paint.color = st.fill!!
                    block(paint)
                }
                hasStroke -> {
                    paint.style = Paint.Style.STROKE
                    paint.color = st.stroke!!
                    paint.strokeWidth = st.strokeWidth
                    block(paint)
                }
                else -> {
                    paint.style = Paint.Style.FILL
                    paint.color = Color.BLACK
                    block(paint)
                }
            }
        }

        private fun textPaint(f: FontSpec): Paint {
            // 🔴 必须在 apply{} 之外取style：receiver 是 Paint，它自带一个 `style: Paint.Style`
            //   属性，在 apply 作用域里写 `style?.fill` 会被解析成 Paint.style（一个枚举，
            //   没有 fill 也没有 ?.），编译报 Unresolved reference 'fill'。
            val fill = style?.fill
            return Paint(Paint.ANTI_ALIAS_FLAG).apply {
            isAntiAlias = antialias
            color = fill ?: Color.BLACK
            textSize = f.size
            typeface = when (f.family) {
                "monospace" -> Typeface.MONOSPACE
                "serif" -> Typeface.SERIF
                "sans", "sans-serif" -> Typeface.SANS_SERIF
                else -> runCatching { Typeface.create(f.family, Typeface.NORMAL) }
                    .getOrDefault(Typeface.MONOSPACE)
            }
            if (f.bold || f.italic) {
                val styleFlag = when {
                    f.bold && f.italic -> Typeface.BOLD_ITALIC
                    f.bold -> Typeface.BOLD
                    else -> Typeface.ITALIC
                }
                typeface = Typeface.create(typeface, styleFlag)
            }
            if (f.letterSpacing != 0f) letterSpacing = f.letterSpacing
            }
        }
    }
}

/**
 * SVG path 子集解析器：M/L/H/V/C/Q/S/T/A/Z（大小写均可）。
 * 只实现绘图常用的子集，够用且不引入依赖。
 */
internal object PathParser {

    fun parse(d: String): Path {
        val p = Path()
        val tok = Regex("[MmLlHhVvCcSsQqTtAaZz]|-?\\d*\\.?\\d+(?:e[-+]?\\d+)?")
            .findAll(d).map { it.value }.toList()
        var i = 0
        var x = 0f; var y = 0f; var sx = 0f; var sy = 0f
        var cmd = 'M'
        var lastCtrl: Pair<Float, Float>? = null
        fun n(): Float = tok.getOrNull(i++)?.toFloatOrNull() ?: 0f

        while (i < tok.size) {
            val t = tok[i]
            if (t.length == 1 && t[0].isLetter()) { cmd = t[0]; i++ }
            when (cmd) {
                'M', 'm' -> {
                    x = n() + if (cmd == 'm') x else 0f
                    y = n() + if (cmd == 'm') y else 0f
                    p.moveTo(x, y); sx = x; sy = y
                    cmd = if (cmd == 'm') 'l' else 'L'
                    lastCtrl = null
                }
                'L', 'l' -> {
                    x = n() + if (cmd == 'l') x else 0f
                    y = n() + if (cmd == 'l') y else 0f
                    p.lineTo(x, y); lastCtrl = null
                }
                'H', 'h' -> { x = n() + if (cmd == 'h') x else 0f; p.lineTo(x, y); lastCtrl = null }
                'V', 'v' -> { y = n() + if (cmd == 'v') y else 0f; p.lineTo(x, y); lastCtrl = null }
                'C', 'c' -> {
                    val x1 = n(); val y1 = n(); val x2 = n(); val y2 = n()
                    val nx = n(); val ny = n()
                    if (cmd == 'c') { p.cubicTo(x + x1, y + y1, x + x2, y + y2, x + nx, y + ny); x += nx; y += ny }
                    else { p.cubicTo(x1, y1, x2, y2, nx, ny); x = nx; y = ny }
                    lastCtrl = x2 to y2
                }
                'S', 's' -> {
                    // 平滑三次贝塞尔：首控制点由上一个控制点反射得到
                    val (rx, ry) = reflect(lastCtrl, x, y)
                    val x2 = n(); val y2 = n(); val nx = n(); val ny = n()
                    if (cmd == 's') { p.cubicTo(rx, ry, x + x2, y + y2, x + nx, y + ny); x += nx; y += ny }
                    else { p.cubicTo(rx, ry, x2, y2, nx, ny); x = nx; y = ny }
                    lastCtrl = x2 to y2
                }
                'Q', 'q' -> {
                    val x1 = n(); val y1 = n(); val nx = n(); val ny = n()
                    if (cmd == 'q') { p.quadTo(x + x1, y + y1, x + nx, y + ny); x += nx; y += ny }
                    else { p.quadTo(x1, y1, nx, ny); x = nx; y = ny }
                    lastCtrl = x1 to y1
                }
                'T', 't' -> {
                    val (rx, ry) = reflect(lastCtrl, x, y)
                    val nx = n(); val ny = n()
                    if (cmd == 't') { p.quadTo(rx, ry, x + nx, y + ny); x += nx; y += ny }
                    else { p.quadTo(rx, ry, nx, ny); x = nx; y = ny }
                    lastCtrl = rx to ry
                }
                'A', 'a' -> {
                    val rx = n(); val ry = n(); val rot = n()
                    val laf = n(); val sf = n(); val nx = n(); val ny = n()
                    val ex = nx + if (cmd == 'a') x else 0f
                    val ey = ny + if (cmd == 'a') y else 0f
                    arcTo(p, x, y, rx, ry, rot, laf != 0f, sf != 0f, ex, ey)
                    x = ex; y = ey; lastCtrl = null
                }
                'Z', 'z' -> { p.close(); x = sx; y = sy; lastCtrl = null }
                else -> n()
            }
        }
        return p
    }

    private fun reflect(ctrl: Pair<Float, Float>?, x: Float, y: Float): Pair<Float, Float> =
        if (ctrl == null) x to y else 2 * x - ctrl.first to 2 * y - ctrl.second

    /** 椭圆弧用多段三次贝塞尔近似（与浏览器实现同思路） */
    private fun arcTo(
        p: Path, x0: Float, y0: Float, rx: Float, ry: Float,
        rotDeg: Float, large: Boolean, sweep: Boolean, x1: Float, y1: Float,
    ) {
        if (rx == 0f || ry == 0f || (x0 == x1 && y0 == y1)) { p.lineTo(x1, y1); return }
        val phi = Math.toRadians(rotDeg.toDouble())
        val cosp = Math.cos(phi).toFloat(); val sinp = Math.sin(phi).toFloat()
        val dx2 = (x0 - x1) / 2f; val dy2 = (y0 - y1) / 2f
        val x1p = cosp * dx2 + sinp * dy2
        val y1p = -sinp * dx2 + cosp * dy2
        var rxa = kotlin.math.abs(rx); var rya = kotlin.math.abs(ry)
        val lam = (x1p * x1p) / (rxa * rxa) + (y1p * y1p) / (rya * rya)
        if (lam > 1) { val s = kotlin.math.sqrt(lam); rxa *= s; rya *= s }
        val num = rxa * rxa * rya * rya - rxa * rxa * y1p * y1p - rya * rya * x1p * x1p
        val den = rxa * rxa * y1p * y1p + rya * rya * x1p * x1p
        var co = if (den == 0f) 0f else kotlin.math.sqrt((num / den).coerceAtLeast(0f))
        if (large == sweep) co = -co
        val cxp = co * rxa * y1p / rya
        val cyp = -co * rya * x1p / rxa
        val cx = cosp * cxp - sinp * cyp + (x0 + x1) / 2f
        val cy = sinp * cxp + cosp * cyp + (y0 + y1) / 2f

        fun ang(ux: Float, uy: Float, vx: Float, vy: Float): Double {
            // 统一到 Double 再算：Float 的 acos / 除法在 Kotlin 2.3 下类型检查更严，
            // 且 Float 相除会累积误差，角度算歪了会让弧形文字整段错位。
            val uXd = ux.toDouble(); val uYd = uy.toDouble()
            val vXd = vx.toDouble(); val vYd = vy.toDouble()
            val dot = uXd * vXd + uYd * vYd
            val ln = Math.hypot(uXd, uYd) * Math.hypot(vXd, vYd)
            val a = if (ln == 0.0) 0.0 else Math.acos((dot / ln).coerceIn(-1.0, 1.0))
            return if (uXd * vYd - uYd * vXd < 0.0) -a else a
        }

        val th1 = ang(1f, 0f, (x1p - cxp) / rxa, (y1p - cyp) / rya)
        var dth = ang((x1p - cxp) / rxa, (y1p - cyp) / rya, (-x1p - cxp) / rxa, (-y1p - cyp) / rya)
        if (!sweep && dth > 0) dth -= 2 * Math.PI
        else if (sweep && dth < 0) dth += 2 * Math.PI

        val n = maxOf(1, Math.ceil(Math.abs(dth) / (Math.PI / 2)).toInt())
        val delta = dth / n
        val t = 4.0 / 3.0 * Math.tan(delta / 4)
        for (k in 0 until n) {
            val th = th1 + k * delta
            val c1 = Math.cos(th); val s1 = Math.sin(th)
            val c2 = Math.cos(th + delta); val s2 = Math.sin(th + delta)
            val e1x = (c1 - t * s1).toFloat(); val e1y = (s1 + t * c1).toFloat()
            val e2x = (c2 + t * s2).toFloat(); val e2y = (s2 - t * c2).toFloat()
            p.cubicTo(
                cx + rxa * (cosp * e1x - sinp * e1y), cy + rya * (sinp * e1x + cosp * e1y),
                cx + rxa * (cosp * e2x - sinp * e2y), cy + rya * (sinp * e2x + cosp * e2y),
                cx + rxa * (cosp * c2.toFloat() - sinp * s2.toFloat()),
                cy + rya * (sinp * c2.toFloat() + cosp * s2.toFloat()),
            )
        }
    }
}
