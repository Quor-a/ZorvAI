package com.ai.assistance.quro.genui.app.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp
import com.ai.assistance.quro.genui.app.ui.theme.GenTheme

/**
 * 自写轻量富文本 / Markdown 渲染器（移植自 ZorvAI ui/dialog/RichText.kt，Apache 2.0）。
 * 块级：#/##/### 标题、``` 围栏代码（带语言标签）、> 引用、- 列表、--- 分隔线、段落。
 * 行内：**粗体** / *斜体* / `代码` / [文本](url) 可点链接 / <c=#RRGGBB>着色</c>。
 */
private sealed interface RBlock
private data class RHeading(val level: Int, val text: String) : RBlock
private data class RCode(val lang: String, val code: String) : RBlock
private data class RQuote(val text: String) : RBlock
private data class RListItem(val text: String, val marker: String = "•") : RBlock
private object RRule : RBlock
private data class RParagraph(val raw: String) : RBlock

@Composable
fun RichText(
    text: String,
    modifier: Modifier = Modifier,
    baseStyle: TextStyle = TextStyle(fontSize = 14.sp, color = GenTheme.Text),
    onLinkClick: ((String) -> Unit)? = null,
) {
    val blocks = remember(text) { parseBlocks(text) }
    Column(modifier = modifier.fillMaxWidth()) {
        blocks.forEach { block ->
            when (block) {
                is RHeading -> {
                    val size = when (block.level) { 1 -> 19; 2 -> 17; else -> 15 }
                    Text(
                        text = block.text,
                        style = baseStyle.copy(fontSize = size.sp, fontWeight = FontWeight.Bold, color = GenTheme.Amber),
                        modifier = Modifier.padding(vertical = 4.dp),
                    )
                }
                is RCode -> {
                    Column(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp)
                            .clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                            .background(GenTheme.Screen)
                            .padding(10.dp)
                    ) {
                        if (block.lang.isNotBlank()) Text(
                            text = block.lang,
                            style = baseStyle.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = GenTheme.Dim),
                        )
                        Text(
                            text = block.code,
                            style = baseStyle.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = GenTheme.Text),
                        )
                    }
                }
                is RQuote -> Row(
                    Modifier.padding(start = 2.dp, top = 4.dp, bottom = 4.dp)
                ) {
                    Box(
                        Modifier.width(3.dp)
                            .heightIn(min = 20.dp)
                            .background(GenTheme.AmberDim.copy(alpha = 0.7f),
                                RoundedCornerShape(2.dp))
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = block.text,
                        style = baseStyle.copy(color = GenTheme.Dim, fontWeight = FontWeight.Light),
                    )
                }
                is RListItem -> Text(
                    text = buildAnnotatedString {
                        pushStyle(SpanStyle(color = GenTheme.Amber))
                        append(block.marker + " ")
                        pop()
                        append(parseInline(block.text, baseStyle))
                    },
                    style = baseStyle.copy(color = GenTheme.Text),
                    modifier = Modifier.padding(start = 8.dp, top = 2.dp, bottom = 2.dp),
                )
                is RRule -> androidx.compose.material3.HorizontalDivider(
                    color = GenTheme.Line,
                    modifier = Modifier.padding(vertical = 6.dp),
                )
                is RParagraph -> {
                    val inline = parseInline(block.raw, baseStyle)
                    val ctx = androidx.compose.ui.platform.LocalContext.current
                    // 点击链接：外部回调优先，否则系统浏览器打开（裸 URL 自动链接的可点性保障）
                    ClickableText(
                        text = inline,
                        style = baseStyle.copy(color = GenTheme.Text),
                        modifier = Modifier.padding(vertical = 2.dp),
                    ) { offset ->
                        inline.getStringAnnotations("link", offset, offset)
                            .firstOrNull()?.let { link ->
                                if (onLinkClick != null) onLinkClick(link.item)
                                else runCatching {
                                    ctx.startActivity(android.content.Intent(
                                        android.content.Intent.ACTION_VIEW,
                                        android.net.Uri.parse(link.item)))
                                }
                            }
                    }
                }
            }
        }
    }
}

private fun parseBlocks(src: String): List<RBlock> {
    val lines = src.replace("\r\n", "\n").split("\n")
    val out = mutableListOf<RBlock>()
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        when {
            line.startsWith("```") -> {
                val lang = line.removePrefix("```").trim()
                val buf = StringBuilder()
                i++
                while (i < lines.size && !lines[i].startsWith("```")) {
                    buf.append(lines[i]).append("\n"); i++
                }
                i++
                out.add(RCode(lang, buf.toString().trimEnd()))
            }
            line.startsWith("###") -> out.add(RHeading(3, line.removePrefix("###").trim()))
            line.startsWith("##") -> out.add(RHeading(2, line.removePrefix("##").trim()))
            line.startsWith("#") -> out.add(RHeading(1, line.removePrefix("#").trim()))
            line.startsWith(">") -> out.add(RQuote(line.removePrefix(">").trim()))
            line.matches(Regex("^\\s*---\\s*$")) -> out.add(RRule)
            line.matches(Regex("^\\s*[-*]\\s+.*")) -> {
                val buf = StringBuilder(line.replaceFirst(Regex("^\\s*[-*]\\s+"), ""))
                i++
                while (i < lines.size && lines[i].matches(Regex("^\\s*[-*]\\s+.*"))) {
                    buf.append("\n").append(lines[i].replaceFirst(Regex("^\\s*[-*]\\s+"), "")); i++
                }
                out.add(RListItem(buf.toString()))
                continue
            }
            line.matches(Regex("^\\s*\\d+\\.\\s+.*")) -> {
                // 有序列表：保留原编号做 marker（1. 2. 3.），不再渲染成难看的段落
                val num = Regex("^\\s*(\\d+)\\.").find(line)?.groupValues?.get(1) ?: "1"
                val buf = StringBuilder(line.replaceFirst(Regex("^\\s*\\d+\\.\\s+"), ""))
                i++
                while (i < lines.size && lines[i].matches(Regex("^\\s*\\d+\\.\\s+.*"))) {
                    val n2 = Regex("^\\s*(\\d+)\\.").find(lines[i])?.groupValues?.get(1) ?: ""
                    buf.append("\n").append(n2).append(".\u0009")
                        .append(lines[i].replaceFirst(Regex("^\\s*\\d+\\.\\s+"), "")); i++
                }
                // 多行有序项的续行 marker 处理复杂，仅单行保真：换行处用空 marker
                out.add(RListItem(buf.toString(), marker = "$num."))
                continue
            }
            line.isBlank() -> { }
            else -> out.add(RParagraph(line))
        }
        i++
    }
    return out
}

private fun parseInline(raw: String, base: TextStyle): AnnotatedString {
    return buildAnnotatedString {
        var i = 0
        val n = raw.length
        while (i < n) {
            when {
                raw[i] == '`' -> {
                    val end = raw.indexOf('`', i + 1)
                    if (end > i) {
                        pushStyle(SpanStyle(background = GenTheme.Screen, color = GenTheme.Text, fontFamily = FontFamily.Monospace))
                        append(raw.substring(i + 1, end))
                        pop()
                        i = end + 1
                    } else { append(raw[i]); i++ }
                }
                raw[i] == '[' -> {
                    val close = raw.indexOf(']', i)
                    val paren = if (close > i) raw.indexOf('(', close) else -1
                    val urlEnd = if (paren > close) raw.indexOf(')', paren) else -1
                    if (close > i && paren == close + 1 && urlEnd > paren) {
                        val t = raw.substring(i + 1, close)
                        val u = raw.substring(paren + 1, urlEnd)
                        pushStringAnnotation("link", u)
                        withStyle(SpanStyle(color = GenTheme.Amber, textDecoration = TextDecoration.Underline)) { append(t) }
                        pop()
                        i = urlEnd + 1
                    } else { append(raw[i]); i++ }
                }
                raw[i] == '*' && i + 1 < n && raw[i + 1] == '*' -> {
                    val end = raw.indexOf("**", i + 2)
                    if (end > i) {
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = GenTheme.Amber)) { append(raw.substring(i + 2, end)) }
                        i = end + 2
                    } else { append(raw[i]); i++ }
                }
                raw[i] == '*' -> {
                    val end = raw.indexOf('*', i + 1)
                    if (end > i) {
                        withStyle(SpanStyle(fontWeight = FontWeight.Light, fontStyle = FontStyle.Italic)) { append(raw.substring(i + 1, end)) }
                        i = end + 1
                    } else { append(raw[i]); i++ }
                }
                raw.startsWith("http://", i) || raw.startsWith("https://", i) -> {
                    // 裸 URL 自动成链：抓到空白/右括号/中文标点为止
                    val end = raw.length
                    var j = i
                    while (j < end && !raw[j].isWhitespace() && raw[j] != ')' && raw[j] != '」'
                        && raw[j] != '，' && raw[j] != '。' && raw[j] != '）' && raw[j] != '"') j++
                    val url = raw.substring(i, j).trimEnd('.', ')')
                    pushStyle(SpanStyle(color = Color(0xFF6FA8DC), textDecoration = TextDecoration.Underline))
                    pushStringAnnotation("link", url)
                    append(url)
                    pop(); pop()
                    i += url.length
                }
                raw.startsWith("<c=", i) -> {
                    val closeTag = raw.indexOf("</c>", i)
                    val hexEnd = raw.indexOf('>', i)
                    if (closeTag > hexEnd && hexEnd > i) {
                        val hex = raw.substring(i + 3, hexEnd).trim()
                        val color = runCatching { Color(android.graphics.Color.parseColor(hex)) }.getOrNull() ?: GenTheme.Text
                        val inner = raw.substring(hexEnd + 1, closeTag)
                        withStyle(SpanStyle(color = color)) { append(inner) }
                        i = closeTag + 4
                    } else { append(raw[i]); i++ }
                }
                else -> { append(raw[i]); i++ }
            }
        }
    }
}
