package com.codecanvas.core.script

import com.codecanvas.core.model.FontSpec
import com.codecanvas.core.model.Style

/**
 * 绘图指令集 —— SDK 的「中间表示层」。
 *
 * 这是整套多架构设计的核心：**任何脚本语言只负责产生 DrawCommand 列表**，
 * 不碰 Android API；**任何渲染后端只负责消费 DrawCommand**，不关心脚本来源。
 * 于是 4 种脚本 × 4 种渲染后端 = 16 种组合，实现成本却是 4 + 4。
 *
 * 指令故意保持「Canvas 风格」的最小闭包：能覆盖 90% 的图表/卡片/流程图场景，
 * 且四个后端都能无歧义实现（SVG 也能 1:1 映射）。
 */
sealed interface DrawCommand {
    // ---- 状态类 ----
    data class PushStyle(val style: Style) : DrawCommand
    object PopStyle : DrawCommand
    object Save : DrawCommand
    object Restore : DrawCommand
    data class ClipRect(val x: Float, val y: Float, val w: Float, val h: Float) : DrawCommand
    data class Translate(val dx: Float, val dy: Float) : DrawCommand
    data class Rotate(val degrees: Float) : DrawCommand
    data class Scale(val sx: Float, val sy: Float) : DrawCommand

    // ---- 图元类 ----
    data class Line(
        val x1: Float, val y1: Float, val x2: Float, val y2: Float,
    ) : DrawCommand

    data class Rect(
        val x: Float, val y: Float, val w: Float, val h: Float,
    ) : DrawCommand

    data class RoundRect(
        val x: Float, val y: Float, val w: Float, val h: Float, val radius: Float,
    ) : DrawCommand

    data class Circle(val cx: Float, val cy: Float, val r: Float) : DrawCommand
    data class Oval(val x: Float, val y: Float, val w: Float, val h: Float) : DrawCommand
    data class Arc(
        val x: Float, val y: Float, val w: Float, val h: Float,
        val startDeg: Float, val sweepDeg: Float, val useCenter: Boolean = false,
    ) : DrawCommand

    /** points 为 [x0,y0,x1,y1,...] 扁平数组，减少跨语言桥接开销 */
    data class Polyline(val points: FloatArray, val closed: Boolean = false) : DrawCommand

    /** SVG path 子集：M/L/C/Q/Z，大小写均支持。四个后端都能解析 */
    data class Path(val d: String) : DrawCommand

    data class Text(
        val text: String,
        val x: Float,
        val y: Float,
        val font: FontSpec = FontSpec(),
        val maxWidth: Float? = null,
        val align: TextAlign = TextAlign.LEFT,
    ) : DrawCommand

    /** 富文本：已高亮的代码行，按 span 着色（代码卡片场景专用） */
    data class RichText(
        val spans: List<Span>,
        val x: Float,
        val y: Float,
        val font: FontSpec = FontSpec(),
    ) : DrawCommand {
        data class Span(val text: String, val color: Int, val bold: Boolean = false)
    }

    /** 内嵌位图（base64 或 assets 路径） */
    data class Image(
        val source: String,
        val x: Float, val y: Float, val w: Float, val h: Float,
    ) : DrawCommand
}

enum class TextAlign { LEFT, CENTER, RIGHT }

/**
 * 脚本产出的指令列表。带写入保护：超过 maxCommands 直接拒绝，
 * 防止恶意/失控脚本用死循环把内存打满。
 */
class DrawList(maxCommands: Int = 20_000) {

    private val items = ArrayList<DrawCommand>(256)
    val maxCommands: Int = maxCommands

    val size: Int get() = items.size
    fun isEmpty() = items.isEmpty()
    operator fun get(i: Int): DrawCommand = items[i]
    fun toList(): List<DrawCommand> = items.toList()

    fun add(cmd: DrawCommand) {
        if (items.size >= maxCommands) {
            throw ScriptLimitException("DrawCommand 超出上限 $maxCommands，疑似死循环")
        }
        items.add(cmd)
    }

    fun addAll(cmds: Collection<DrawCommand>) = cmds.forEach(::add)
    fun clear() = items.clear()
}

class ScriptLimitException(message: String) : RuntimeException(message)
