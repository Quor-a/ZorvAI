package com.ai.assistance.quro.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.noties.jlatexmath.JLatexMathDrawable
import java.util.concurrent.ConcurrentHashMap

/**
 * LaTeX 公式渲染（原创封装，基于 jlatexmath 纯 Java 渲染）。
 *
 * 支持：
 *  - `$...$`       行内公式
 *  - `$$...$$`     独立块级公式
 *  - `\(...\)`     行内公式
 *  - `\[...\]`     独立块级公式
 * 其余文本按普通 Markdown 行内格式（加粗/斜体/行内代码/链接）渲染。
 *
 * 渲染结果按 (latex, color) 缓存，避免重复渲染。
 */

private data class LatexSpan(val isLatex: Boolean, val content: String, val display: Boolean)

private data class LatexCacheKey(val latex: String, val color: Int)

private object LatexBitmapCache {
    private const val MAX = 128
    private val map = object : LinkedHashMap<LatexCacheKey, Bitmap>(MAX, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<LatexCacheKey, Bitmap>?): Boolean =
            size > MAX
    }
    private val lock = Any()

    fun get(key: LatexCacheKey): Bitmap? = synchronized(lock) { map[key] }

    fun put(key: LatexCacheKey, bmp: Bitmap) {
        synchronized(lock) {
            map[key] = bmp
        }
    }
}

private fun parseLatexSpans(text: String): List<LatexSpan> {
    val spans = mutableListOf<LatexSpan>()
    val buf = StringBuilder()
    var i = 0
    fun flush() {
        if (buf.isNotEmpty()) {
            spans.add(LatexSpan(false, buf.toString(), false))
            buf.clear()
        }
    }
    while (i < text.length) {
        val remaining = text.substring(i)
        // 代码块保护：跳过 ``` 与 ` 之间的内容
        if (remaining.startsWith("```")) {
            val end = remaining.indexOf("```", 3)
            if (end >= 0) {
                buf.append(remaining.substring(0, end + 3)); i += end + 3; continue
            }
        }
        if (remaining.startsWith("`") && !remaining.startsWith("```")) {
            val end = remaining.indexOf('`', 1)
            if (end >= 0) {
                buf.append(remaining.substring(0, end + 1)); i += end + 1; continue
            }
        }
        // $$ 块级
        if (remaining.startsWith("$$")) {
            val end = remaining.indexOf("$$", 2)
            if (end >= 0) {
                val latex = remaining.substring(2, end).trim()
                if (latex.isNotBlank()) {
                    flush()
                    spans.add(LatexSpan(true, latex, true))
                    i += end + 2
                    continue
                }
            }
        }
        // \[ 块级
        if (remaining.startsWith("\\[")) {
            val end = remaining.indexOf("\\]", 2)
            if (end >= 0) {
                val latex = remaining.substring(2, end).trim()
                if (latex.isNotBlank()) {
                    flush()
                    spans.add(LatexSpan(true, latex, true))
                    i += end + 2
                    continue
                }
            }
        }
        // \( 行内
        if (remaining.startsWith("\\(")) {
            val end = remaining.indexOf("\\)", 2)
            if (end >= 0) {
                val latex = remaining.substring(2, end).trim()
                if (latex.isNotBlank()) {
                    flush()
                    spans.add(LatexSpan(true, latex, false))
                    i += end + 2
                    continue
                }
            }
        }
        // $ 行内（非 $$，非 \ 转义）
        if (remaining.startsWith("$")) {
            val prev = if (i > 0) text[i - 1] else ' '
            if (prev != '\\') {
                val lineEnd = remaining.indexOf('\n').let { if (it < 0) remaining.length else it }
                var end = remaining.indexOf('$', 1)
                while (end in 1 until lineEnd && remaining[end - 1] == '\\') {
                    end = remaining.indexOf('$', end + 1)
                }
                if (end in 1 until lineEnd) {
                    val latex = remaining.substring(1, end).trim()
                    if (latex.isNotBlank() && isLikelyLatex(latex)) {
                        flush()
                        spans.add(LatexSpan(true, latex, false))
                        i += end + 1
                        continue
                    }
                }
            }
        }
        // \$ 转义
        if (remaining.startsWith("\\$")) {
            buf.append('$'); i += 2; continue
        }
        buf.append(remaining[0])
        i++
    }
    flush()
    return spans
}

/** 启发式判断 $...$ 内容是否更像公式而非自然语言（避免误伤「价格 $5」）。 */
private fun isLikelyLatex(content: String): Boolean {
    val t = content.trim()
    if (t.isEmpty()) return false
    if (Regex("""\\[a-zA-Z]+""").containsMatchIn(t)) return true
    if (Regex("""[\^_]\{""").containsMatchIn(t)) return true
    if (Regex("""^-?\d+(\.\d+)?\\?%?$""").matches(t)) return true
    if (t.length == 1 && t[0].isLetterOrDigit()) return true
    val hasMathOps = Regex("""[+\-*/=<>|^_]""").containsMatchIn(t)
    if (Regex("""[.!?;:]["») \t]""").containsMatchIn(t)) return false
    val words = t.split(Regex("""\s+"""))
    val prose = Regex("""\b(and|or|the|a|an|in|on|to|for|of|is|are|was|were|it|we|you|he|she|they|costs?|price|dollar|another|other|this|that|each|per|total|only|about|more|less|than|with|from|by|can|will|would|should|could|may|not|but|one|two|three|four|five|ten|new|old|some|all|any|just|also|has|have|had|what)\b""", RegexOption.IGNORE_CASE)
    val hasProse = words.any { w -> w.length > 1 && prose.matches(w) }
    if (hasProse && !hasMathOps) return false
    if (words.size > 5 && !hasMathOps) return false
    if (t.none { it.isLetter() } && !hasMathOps) return false
    return true
}

private fun renderLatex(latex: String, textSize: Float, color: Int): Bitmap? = try {
    val drawable = JLatexMathDrawable.builder(latex).textSize(textSize).color(color).build()
    val w = drawable.intrinsicWidth.takeIf { it > 0 } ?: (textSize * 10).toInt()
    val h = drawable.intrinsicHeight.takeIf { it > 0 } ?: (textSize * 2).toInt()
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    drawable.setBounds(0, 0, w, h)
    drawable.draw(canvas)
    bmp
} catch (_: Exception) {
    null
}

/** 把 LaTeX 渲染成 Bitmap（异步，带缓存）。 */
suspend fun renderLatexBitmap(latex: String, textSize: Float, color: Int): Bitmap? {
    val key = LatexCacheKey(latex, color)
    LatexBitmapCache.get(key)?.let { return it }
    val bmp = withContext(Dispatchers.Default) { renderLatex(latex, textSize, color) }
    if (bmp != null) LatexBitmapCache.put(key, bmp)
    return bmp
}

/**
 * 渲染含 LaTeX 公式的文本（行内 + 块级混合）。
 * 非公式部分走普通 Markdown 行内格式（加粗/斜体/行内代码/链接）。
 */
@Composable
fun LatexMathText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurface,
    textSize: Float = 20f,
) {
    val spans = remember(text) { parseLatexSpans(text) }
    Column(modifier = modifier) {
        spans.forEach { span ->
            if (span.isLatex) {
                LatexFormula(span.content, span.display, color, textSize)
            } else {
                InlineRichText(span.content, color)
            }
        }
    }
}

@Composable
private fun LatexFormula(latex: String, display: Boolean, color: Color, textSize: Float) {
    val argb = color.toArgbCompat()
    var bitmap by remember(latex, argb) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(latex, argb) {
        bitmap = renderLatexBitmap(latex, textSize, argb)
    }
    val bmp = bitmap
    if (bmp != null) {
        val density = LocalDensity.current
        val w = with(density) { bmp.width.toFloat().div(density.density).dp }
        val h = with(density) { bmp.height.toFloat().div(density.density).dp }
        if (display) {
            Box(Modifier.fillMaxWidth().padding(vertical = 6.dp), contentAlignment = Alignment.Center) {
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = latex,
                    modifier = Modifier.size(width = w, height = h),
                )
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = latex,
                    modifier = Modifier.size(width = w, height = h),
                )
            }
        }
    } else {
        // 渲染失败时回退为纯文本（保留原始 LaTeX）
        Text(
            text = if (display) "$$$latex$$" else "\$$latex\$",
            color = color,
            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
        )
    }
}

/** 普通 Markdown 行内富文本（与 MarkdownText 的 inlineAnnotated 一致的格式）。 */
@Composable
private fun InlineRichText(text: String, color: Color) {
    val annotated = remember(text, color) { inlineAnnotated(text, color) }
    androidx.compose.foundation.text.ClickableText(
        text = annotated,
        style = MaterialTheme.typography.bodyMedium.copy(color = color, lineHeight = 20.sp),
        modifier = Modifier.fillMaxWidth(),
        onClick = {},
    )
}

private fun inlineAnnotated(text: String, color: Color): AnnotatedString {
    val linkColor = Color(0xFF2563EB)
    val pattern = Regex("""(\*\*.+?\*\*)|(`[^`]+`)|(\[[^\]]+\]\([^)]+\))|(\*.+?\*)""")
    return buildAnnotatedString {
        var last = 0
        for (m in pattern.findAll(text)) {
            append(text.substring(last, m.range.first))
            val seg = m.value
            when {
                seg.startsWith("**") -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(seg.removeSurrounding("**")) }
                seg.startsWith("`") -> withStyle(SpanStyle(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)) { append(seg.removeSurrounding("`")) }
                seg.startsWith("[") -> {
                    val mm = Regex("""\[([^\]]+)\]\(([^)]+)\)""").find(seg)
                    if (mm != null) {
                        val (t, _) = mm.destructured
                        withStyle(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)) { append(t) }
                    } else append(seg)
                }
                else -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(seg.removeSurrounding("*")) }
            }
            last = m.range.last + 1
        }
        append(text.substring(last))
    }
}

private fun Color.toArgbCompat(): Int {
    val r = (red * 255).toInt().coerceIn(0, 255)
    val g = (green * 255).toInt().coerceIn(0, 255)
    val b = (blue * 255).toInt().coerceIn(0, 255)
    val a = (alpha * 255).toInt().coerceIn(0, 255)
    return (a shl 24) or (r shl 16) or (g shl 8) or b
}
