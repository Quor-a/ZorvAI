package com.codecanvas.core.script

import com.codecanvas.core.model.FontSpec
import com.codecanvas.core.model.Style
import com.codecanvas.core.script.DrawCommand.RichText
import org.json.JSONArray

/**
 * [CanvasBinding] 的官方实现：把脚本调用翻译成 [DrawCommand] 写入 [DrawList]。
 *
 * JS / Lua / Python / Kotlin DSL 四个引擎共用这一个类 ——
 * 这正是「多语言」能做到行为一致的保证。
 */
class DrawListBinding(
    private val out: DrawList,
    private val canvasWidth: Float,
    private val canvasHeight: Float,
    override val args: Map<String, Any?> = emptyMap(),
    private val maxLogs: Int = 200,
) : CanvasBinding {

    private var fill: Int? = null
    private var stroke: Int? = null
    private var strokeWidth = 1f
    private var alpha = 1f
    private var shadow: Style.Shadow? = null
    private val logs = ArrayList<String>(16)

    private fun emit(cmd: DrawCommand) {
        out.add(cmd)
        // 状态类指令后紧跟的图元需要带上当前样式，这里统一补一次 PushStyle 语义由 Renderer 维护栈
    }

    private fun currentStyle() = Style(
        fill = fill,
        stroke = stroke,
        strokeWidth = strokeWidth,
        alpha = alpha,
        shadow = shadow,
    )

    // ---------- 状态 ----------
    override fun save() { emit(DrawCommand.Save); emit(DrawCommand.PushStyle(currentStyle())) }
    override fun restore() { emit(DrawCommand.PopStyle); emit(DrawCommand.Restore) }
    override fun translate(dx: Float, dy: Float) { emit(DrawCommand.Translate(dx, dy)) }
    override fun rotate(degrees: Float) { emit(DrawCommand.Rotate(degrees)) }
    override fun scale(sx: Float, sy: Float) { emit(DrawCommand.Scale(sx, sy)) }

    override fun setFill(color: String) { fill = parseColor(color) }
    override fun setStroke(color: String, width: Float) {
        stroke = parseColor(color); strokeWidth = width
    }

    override fun setAlpha(alpha: Float) { this.alpha = alpha.coerceIn(0f, 1f) }

    override fun setShadow(color: String, blur: Float, dx: Float, dy: Float) {
        shadow = Style.Shadow(parseColor(color), blur, dx, dy)
    }

    override fun clearShadow() { shadow = null }

    // ---------- 图元 ----------
    /**
     * 铺满整个画布的背景。
     *
     * 注意：必须同时把颜色写入 fill —— 否则后续图元仍沿用旧 fill，
     * 而这张背景矩形自己拿不到颜色（这是早期版本最隐蔽的一个 bug）。
     */
    override fun background(color: String) {
        fill = parseColor(color)
        emit(DrawCommand.PushStyle(currentStyle()))
        emit(DrawCommand.Rect(0f, 0f, canvasWidth, canvasHeight))
    }

    override fun line(x1: Float, y1: Float, x2: Float, y2: Float) {
        emit(DrawCommand.PushStyle(currentStyle()))
        emit(DrawCommand.Line(x1, y1, x2, y2))
    }

    override fun rect(x: Float, y: Float, w: Float, h: Float, radius: Float) {
        emit(DrawCommand.PushStyle(currentStyle()))
        emit(if (radius > 0f) DrawCommand.RoundRect(x, y, w, h, radius) else DrawCommand.Rect(x, y, w, h))
    }

    override fun circle(cx: Float, cy: Float, r: Float) {
        emit(DrawCommand.PushStyle(currentStyle()))
        emit(DrawCommand.Circle(cx, cy, r))
    }

    override fun oval(x: Float, y: Float, w: Float, h: Float) {
        emit(DrawCommand.PushStyle(currentStyle()))
        emit(DrawCommand.Oval(x, y, w, h))
    }

    override fun arc(x: Float, y: Float, w: Float, h: Float, start: Float, sweep: Float, useCenter: Boolean) {
        emit(DrawCommand.PushStyle(currentStyle()))
        emit(DrawCommand.Arc(x, y, w, h, start, sweep, useCenter))
    }

    override fun polyline(points: FloatArray, closed: Boolean) {
        emit(DrawCommand.PushStyle(currentStyle()))
        emit(DrawCommand.Polyline(points, closed))
    }

    override fun path(d: String) {
        emit(DrawCommand.PushStyle(currentStyle()))
        emit(DrawCommand.Path(d))
    }

    override fun text(str: String, x: Float, y: Float, size: Float, align: String) {
        emit(DrawCommand.PushStyle(currentStyle()))
        emit(
            DrawCommand.Text(
                text = str, x = x, y = y,
                font = FontSpec(size = size),
                align = when (align.lowercase()) {
                    "center" -> TextAlign.CENTER
                    "right" -> TextAlign.RIGHT
                    else -> TextAlign.LEFT
                },
            )
        )
    }

    /** spansJson: [{"t":"fun","c":"#569CD6","b":false}, ...] */
    override fun richText(spansJson: String, x: Float, y: Float, size: Float) {
        val spans = runCatching {
            val arr = JSONArray(spansJson)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    add(
                        RichText.Span(
                            text = o.optString("t", ""),
                            color = parseColor(o.optString("c", "#000000")),
                            bold = o.optBoolean("b", false),
                        )
                    )
                }
            }
        }.getOrDefault(listOf(RichText.Span(spansJson, fill ?: 0xFF000000.toInt(), false)))

        emit(DrawCommand.PushStyle(currentStyle()))
        emit(RichText(spans, x, y, FontSpec(size = size)))
    }

    // ---------- 信息 ----------
    override fun width(): Float = canvasWidth
    override fun height(): Float = canvasHeight
    override fun log(msg: String) {
        if (logs.size < maxLogs) logs.add(msg)
    }

    fun drainLogs(): List<String> = logs.toList()

    companion object {
        /** 支持 #RGB / #RRGGBB / #AARRGGBB / rgb() 与常见颜色名 */
        fun parseColor(input: String): Int {
            val s = input.trim()
            if (s.startsWith("#")) {
                return when (s.length) {
                    4 -> {
                        val r = s[1].digitToInt(16); val g = s[2].digitToInt(16); val b = s[3].digitToInt(16)
                        android.graphics.Color.rgb(r * 17, g * 17, b * 17)
                    }
                    7 -> android.graphics.Color.parseColor(s)
                    9 -> s.substring(1).toLong(16).toInt()
                    else -> android.graphics.Color.BLACK
                }
            }
            return namedColors[s.lowercase()] ?: runCatching { android.graphics.Color.parseColor(s) }.getOrDefault(android.graphics.Color.BLACK)
        }

        private val namedColors = mapOf(
            "white" to 0xFFFFFFFF.toInt(), "black" to 0xFF000000.toInt(),
            "red" to 0xFFF44336.toInt(), "green" to 0xFF4CAF50.toInt(),
            "blue" to 0xFF2196F3.toInt(), "yellow" to 0xFFFFEB3B.toInt(),
            "gray" to 0xFF9E9E9E.toInt(), "grey" to 0xFF9E9E9E.toInt(),
            "transparent" to 0x00000000,
        )
    }
}
