package com.codecanvas.core.highlight

import com.codecanvas.core.model.FontSpec
import com.codecanvas.core.script.DrawCommand
import com.codecanvas.core.script.DrawList
import com.codecanvas.core.script.TextAlign

/**
 * 代码卡片布局器 —— 把「源码 + 语言」排成一组 [DrawCommand]。
 *
 * 存在的意义：让 **CANVAS / COMPOSE / SVG 三个后端都能出代码卡片**，
 * 不再只有 WEBVIEW 一条路。WebView 观感最好（CSS 排版），但它有硬伤：
 * - Android Go / 系统 WebView 更新中 / MDM 禁用时初始化会失败
 * - 长图要截整页，内存峰值高
 * - 无头场景（后台批量出图）根本用不了
 *
 * 这里走的是「高亮 → 指令」路线，与服务端 `_codecard.py` 完全同构，
 * 因此同一份代码在移动端与服务端出来的版式一致。
 *
 * 主题以 atom-one-dark 为准；换主题只需改 [Theme] 里的配色。
 */
object CodeCardLayout {

    /** 卡片主题配色 */
    data class Theme(
        val windowBg: String = "#0D1117",
        val barBg: String = "#161B22",
        val foreground: String = "#E6EDF3",
        val lineNumber: String = "#6E7681",
        val dots: List<String> = listOf("#FF5F57", "#FEBC2E", "#28C840"),
        val radius: Float = 14f,
    ) {
        companion object {
            val DARK = Theme()
            val LIGHT = Theme(
                windowBg = "#FFFFFF",
                barBg = "#F6F8FA",
                foreground = "#24292F",
                lineNumber = "#8C959F",
            )
        }
    }

    /** 布局参数（逻辑像素） */
    data class Metrics(
        val padX: Float = 40f,
        val padY: Float = 36f,
        val barHeight: Float = 56f,
        val lineHeight: Float = 34f,
        val bodyPad: Float = 30f,
        val fontSize: Float = 20f,
        val numberSize: Float = 17f,
        val gutterWidth: Float = 72f,
        val dotRadius: Float = 6.5f,
        val dotGap: Float = 26f,
    )

    /**
     * 生成卡片指令。
     *
     * @param lines 已高亮的行（来自 [Highlighter.highlightToLines]）
     * @param canvasWidth 可用宽度（逻辑像素）
     * @return Pair(指令列表, 卡片实际需要的高度)
     */
    fun build(
        lines: List<HighlightedLine>,
        canvasWidth: Float,
        title: String? = null,
        showLineNumbers: Boolean = true,
        theme: Theme = Theme.DARK,
        m: Metrics = Metrics(),
        maxCommands: Int = 20_000,
    ): Pair<DrawList, Float> {
        val list = DrawList(maxCommands.coerceAtLeast(lines.size * 4 + 32))

        val bodyHeight = lines.size * m.lineHeight
        val cardHeight = m.barHeight + bodyHeight + m.bodyPad
        val contentWidth = (canvasWidth - m.padX * 2).coerceAtLeast(120f)
        val totalHeight = m.padY * 2 + cardHeight + m.bodyPad

        fun push(color: String) = list.add(
            DrawCommand.PushStyle(
                com.codecanvas.core.model.Style(fill = parseHex(color))
            )
        )

        // 1. 窗口主体
        push(theme.windowBg)
        list.add(DrawCommand.RoundRect(m.padX, m.padY, contentWidth, cardHeight, theme.radius))

        // 2. 标题栏：先画圆角矩形，再用矩形盖住下半部 → 只有上方两个圆角
        push(theme.barBg)
        list.add(DrawCommand.RoundRect(m.padX, m.padY, contentWidth, m.barHeight, theme.radius))
        list.add(DrawCommand.Rect(m.padX, m.padY + m.barHeight / 2, contentWidth, m.barHeight / 2))

        // 3. 三个窗口按钮
        theme.dots.forEachIndexed { i, c ->
            push(c)
            list.add(
                DrawCommand.Circle(
                    m.padX + 28 + i * m.dotGap,
                    m.padY + m.barHeight / 2,
                    m.dotRadius,
                )
            )
        }

        // 4. 标题
        if (!title.isNullOrBlank()) {
            push(theme.lineNumber)
            list.add(
                DrawCommand.Text(
                    text = title,
                    x = m.padX + 120f,
                    y = m.padY + m.barHeight / 2,
                    font = FontSpec(family = "sans", size = 17f),
                )
            )
        }

        // 5. 代码行
        val mono = FontSpec(family = "monospace", size = m.fontSize)
        val numFont = FontSpec(family = "monospace", size = m.numberSize)
        val codeX = m.padX + (m.gutterWidth.takeIf { showLineNumbers } ?: 26f)
        val numX = m.padX + m.gutterWidth - 20f

        var y = m.padY + m.barHeight + m.bodyPad
        lines.forEach { line ->
            if (showLineNumbers) {
                push(theme.lineNumber)
                list.add(
                    DrawCommand.Text(
                        text = line.lineNumber.toString(),
                        x = numX,
                        y = y,
                        font = numFont,
                        align = TextAlign.RIGHT,
                    )
                )
            }
            push(theme.foreground)
            val spans = line.spans.ifEmpty {
                listOf(Highlighter.Span("", theme.foreground, false))
            }.map { DrawCommand.RichText.Span(it.text, parseHex(it.color), it.bold) }
            list.add(DrawCommand.RichText(spans, codeX, y, mono))
            y += m.lineHeight
        }

        return list to totalHeight
    }

    /** #RGB / #RRGGBB / #AARRGGBB → ARGB Int */
    internal fun parseHex(input: String): Int {
        val s = input.trim()
        if (!s.startsWith("#")) return 0xFF000000.toInt()
        return when (s.length) {
            4 -> {
                val r = s[1].digitToIntOrNull(16) ?: 0
                val g = s[2].digitToIntOrNull(16) ?: 0
                val b = s[3].digitToIntOrNull(16) ?: 0
                (0xFF shl 24) or (r * 17 shl 16) or (g * 17 shl 8) or (b * 17)
            }
            7 -> (0xFF shl 24) or (s.substring(1).toLong(16).toInt() and 0x00FFFFFF)
            9 -> s.substring(1).toLong(16).toInt()
            else -> 0xFF000000.toInt()
        }
    }
}
