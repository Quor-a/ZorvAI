package com.yuanbao.miniapp.render

import android.graphics.Paint
import android.text.TextUtils

/**
 * Self-developed text layout: measurement + line breaking.
 *
 * - CJK breaks between characters (禁则处理简化：不允许行首出现收尾标点)
 * - Latin breaks on spaces, falls back to character breaking for long words
 * - ellipsis ("…") when maxLines is exceeded
 */
object TextMeasurer {

    private val paintCache = HashMap<String, Paint>()

    private fun paintFor(fontSize: Float, bold: Boolean): Paint {
        val key = "${fontSize}_$bold"
        return paintCache.getOrPut(key) {
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = fontSize
                isFakeBoldText = bold
            }
        }
    }

    class Result(
        val lines: List<String>,
        val width: Float,      // widest line
        val height: Float      // lines.size * lineHeight
    )

    private val NO_LINE_START = setOf('，', '。', '、', '；', '：', '？', '！', '）', '】', '》', '」', '』', ',', '.', ';', ':', '?', '!', ')', ']', '}')
    private val NO_LINE_END = setOf('（', '【', '《', '「', '『', '(', '[', '{')

    /** Breaks [text] into lines no wider than [maxWidth]. */
    fun layout(text: String, fontSize: Float, bold: Boolean, maxWidth: Float, lineHeight: Float, maxLines: Int = 0): Result {
        if (text.isEmpty()) return Result(emptyList(), 0f, 0f)
        if (maxWidth <= 0) return Result(listOf(text), measure(text, fontSize, bold), lineHeight)

        val paint = paintFor(fontSize, bold)
        val lines = ArrayList<String>()
        val current = StringBuilder()
        var currentWidth = 0f

        var i = 0
        while (i < text.length) {
            val ch = text[i]
            if (ch == '\n') {
                lines.add(current.toString())
                current.clear()
                currentWidth = 0f
                i++
                continue
            }

            // take the next atomic unit: a latin word or a single CJK/other char
            val (unit, nextIndex) = nextUnit(text, i)
            val unitWidth = measure(unit, fontSize, bold)

            if (currentWidth + unitWidth <= maxWidth || current.isEmpty()) {
                // 避免行首禁则标点
                if (current.isEmpty() && ch in NO_LINE_START && lines.isNotEmpty()) {
                    val prev = lines.removeAt(lines.size - 1)
                    val prevLen = prev.length
                    if (prevLen > 0 && paint.measureText(prev) + unitWidth <= maxWidth * 1.05f) {
                        current.append(prev).append(unit)
                        currentWidth = measure(current.toString(), fontSize, bold)
                        i = nextIndex
                        continue
                    } else {
                        lines.add(prev)
                    }
                }
                // hard-break a single unit longer than the line
                if (current.isEmpty() && unitWidth > maxWidth) {
                    val hard = hardBreak(unit, maxWidth, paint)
                    hard.dropLast(1).forEach { lines.add(it) }
                    current.append(hard.last())
                    currentWidth = measure(current.toString(), fontSize, bold)
                    i = nextIndex
                    continue
                }
                current.append(unit)
                currentWidth += unitWidth
                i = nextIndex
            } else {
                lines.add(current.toString())
                current.clear()
                currentWidth = 0f
                // do not advance i: retry this unit on the new line
            }
        }
        if (current.isNotEmpty() || lines.isEmpty()) lines.add(current.toString())

        val capped = if (maxLines > 0 && lines.size > maxLines) {
            val kept = lines.take(maxLines).toMutableList()
            val last = kept.last()
            kept[kept.lastIndex] = ellipsize(last, maxWidth, paint)
            kept
        } else lines

        val widest = capped.maxOf { measure(it, fontSize, bold) }
        return Result(capped, widest, capped.size * lineHeight)
    }

    private fun nextUnit(text: String, from: Int): Pair<String, Int> {
        val c = text[from]
        if (isCjk(c)) return c.toString() to from + 1
        if (c == ' ' || c == '\t') {
            var j = from
            while (j < text.length && (text[j] == ' ' || text[j] == '\t')) j++
            return text.substring(from, j) to j
        }
        var j = from
        while (j < text.length && !isCjk(text[j]) && text[j] != ' ' && text[j] != '\t' && text[j] != '\n') j++
        return text.substring(from, j) to j
    }

    private fun isCjk(c: Char): Boolean {
        val code = c.code
        return code in 0x2E80..0x9FFF || code in 0xAC00..0xD7A3 || code in 0xF900..0xFAFF ||
            code in 0xFF00..0xFFEF || code in 0x3000..0x303F
    }

    private fun hardBreak(word: String, maxWidth: Float, paint: Paint): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var w = 0f
        for (ch in word) {
            val cw = paint.measureText(ch.toString())
            if (w + cw > maxWidth && sb.isNotEmpty()) {
                out.add(sb.toString())
                sb.clear()
                w = 0f
            }
            sb.append(ch)
            w += cw
        }
        if (sb.isNotEmpty()) out.add(sb.toString())
        return out
    }

    private fun ellipsize(line: String, maxWidth: Float, paint: Paint): String {
        val dots = "…"
        val dotsWidth = paint.measureText(dots)
        var end = line.length
        while (end > 0 && paint.measureText(line.substring(0, end)) + dotsWidth > maxWidth) end--
        return line.substring(0, end) + dots
    }

    fun measure(text: String, fontSize: Float, bold: Boolean): Float =
        paintFor(fontSize, bold).measureText(text)

    fun fontHeight(fontSize: Float, bold: Boolean): Float {
        val fm = paintFor(fontSize, bold).fontMetrics
        return fm.bottom - fm.top
    }

    fun clearCache() = paintCache.clear()
}
