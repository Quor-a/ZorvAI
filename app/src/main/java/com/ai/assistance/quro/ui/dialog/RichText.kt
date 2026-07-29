package com.ai.assistance.quro.ui.dialog

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.TextStyle

/**
 * 自写轻量富文本 / Markdown 渲染器（原创，Project A1）。
 *
 * 定位：替代 ChatScreen 内散落的 buildRich / parseInlineHtml，成为统一的消息渲染入口。
 * 设计原则：
 * - 零重第三方依赖（不引 Markdown 库），自写块级 + 行内解析。
 * - 颜色全部绑定 Material3 主题（cs.onBackground / primary / outline / surfaceVariant），随深浅色自适应。
 * - 行内支持：**粗体** / *斜体* / `代码` / [文本](url) 链接 / <c=#RRGGBB>着色</c>（前向兼容既有颜色约定）。
 * - 块级支持：#/##/### 标题、``` 围栏代码、> 引用、-列表（减号或星号）、--- 分隔线、段落。
 *
 * 当前为 A1 独立模块，尚未接线 ChatScreen（接线在 A3）；后续可扩展表格 / 有序列表 / 代码语法高亮。
 */

private sealed interface RBlock
private data class RHeading(val level: Int, val text: String) : RBlock
private data class RCode(val lang: String, val code: String) : RBlock
private data class RQuote(val text: String) : RBlock
private data class RListItem(val text: String) : RBlock
private object RRule : RBlock
private data class RParagraph(val raw: String) : RBlock

@Composable
fun RichText(
    text: String,
    modifier: Modifier = Modifier,
    baseStyle: TextStyle = MaterialTheme.typography.bodyMedium,
    onLinkClick: ((String) -> Unit)? = null,
) {
    val cs = MaterialTheme.colorScheme
    val blocks = remember(text) { parseBlocks(text) }
    Column(modifier = modifier.fillMaxWidth()) {
        blocks.forEach { block ->
            when (block) {
                is RHeading -> {
                    val size = when (block.level) {
                        1 -> 22; 2 -> 19; else -> 16
                    }
                    androidx.compose.material3.Text(
                        text = block.text,
                        style = baseStyle.copy(
                            fontSize = androidx.compose.ui.unit.TextUnit(size.toFloat(), androidx.compose.ui.unit.TextUnitType.Sp),
                            fontWeight = FontWeight.Bold,
                            color = cs.onBackground,
                        ),
                        modifier = Modifier.padding(vertical = 4.dp),
                    )
                }
                is RCode -> {
                    androidx.compose.material3.Surface(
                        color = cs.surfaceVariant.copy(alpha = 0.5f),
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    ) {
                        Column(Modifier.padding(10.dp)) {
                            if (block.lang.isNotBlank()) {
                                androidx.compose.material3.Text(
                                    text = block.lang,
                                    style = baseStyle.copy(fontSize = androidx.compose.ui.unit.TextUnit(11f, androidx.compose.ui.unit.TextUnitType.Sp), fontWeight = FontWeight.SemiBold, color = cs.onSurfaceVariant),
                                )
                            }
                            androidx.compose.material3.Text(
                                text = block.code,
                                style = baseStyle.copy(
                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                    fontSize = androidx.compose.ui.unit.TextUnit(13f, androidx.compose.ui.unit.TextUnitType.Sp),
                                    color = cs.onSurface,
                                ),
                            )
                        }
                    }
                }
                is RQuote -> {
                    androidx.compose.material3.Text(
                        text = block.text,
                        style = baseStyle.copy(color = cs.onSurfaceVariant, fontWeight = FontWeight.Light),
                        modifier = Modifier.padding(start = 10.dp, top = 4.dp, bottom = 4.dp),
                    )
                }
                is RListItem -> {
                    val inline = parseInline(block.text, cs, baseStyle)
                    androidx.compose.material3.Text(
                        text = buildAnnotatedString {
                            pushStyle(SpanStyle(color = cs.primary))
                            append("• ")
                            pop()
                            append(inline)
                        },
                        style = baseStyle.copy(color = cs.onBackground),
                        modifier = Modifier.padding(start = 8.dp, top = 2.dp, bottom = 2.dp),
                    )
                }
                is RRule -> androidx.compose.material3.HorizontalDivider(
                    color = cs.outlineVariant.copy(alpha = 0.4f),
                    modifier = Modifier.padding(vertical = 6.dp),
                )
                is RParagraph -> {
                    val inline = parseInline(block.raw, cs, baseStyle)
                    if (onLinkClick != null) {
                        ClickableText(
                            text = inline,
                            style = baseStyle.copy(color = cs.onBackground),
                            modifier = Modifier.padding(vertical = 2.dp),
                        ) { offset ->
                            inline.getStringAnnotations("link", offset, offset)
                                .firstOrNull()?.let { onLinkClick(it.item) }
                        }
                    } else {
                        androidx.compose.material3.Text(
                            text = inline,
                            style = baseStyle.copy(color = cs.onBackground),
                            modifier = Modifier.padding(vertical = 2.dp),
                        )
                    }
                }
            }
        }
    }
}

/** 块级解析：围栏代码优先，其余按行识别标题/引用/列表/分隔线/段落。 */
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
                i++ // 跳过结束 ```
                out.add(RCode(lang, buf.toString().trimEnd()))
            }
            line.startsWith("###") -> out.add(RHeading(3, line.removePrefix("###").trim()))
            line.startsWith("##") -> out.add(RHeading(2, line.removePrefix("##").trim()))
            line.startsWith("#") -> out.add(RHeading(1, line.removePrefix("#").trim()))
            line.startsWith(">") -> out.add(RQuote(line.removePrefix(">").trim()))
            line.matches(Regex("^\\s*---\\s*$")) -> out.add(RRule)
            line.matches(Regex("^\\s*[-*]\\s+.*")) -> {
                // 合并连续列表项
                val buf = StringBuilder(line.replaceFirst(Regex("^\\s*[-*]\\s+"), ""))
                i++
                while (i < lines.size && lines[i].matches(Regex("^\\s*[-*]\\s+.*"))) {
                    buf.append("\n").append(lines[i].replaceFirst(Regex("^\\s*[-*]\\s+"), "")); i++
                }
                out.add(RListItem(buf.toString()))
                continue
            }
            line.isBlank() -> { /* 跳过空行 */ }
            else -> out.add(RParagraph(line))
        }
        i++
    }
    return out
}

/**
 * 行内解析：单遍扫描，优先级 代码(`) > 链接([..](..)) > 粗体(**) > 斜体(*) > 着色(<c=#..>)。
 * 返回带注解的 AnnotatedString（链接标注 "link" 标签，供 ClickableText 接管）。
 */
private fun parseInline(raw: String, cs: ColorScheme, base: TextStyle): AnnotatedString {
    return buildAnnotatedString {
        var i = 0
        val n = raw.length
        while (i < n) {
            when {
                // 代码 `...`
                raw[i] == '`' -> {
                    val end = raw.indexOf('`', i + 1)
                    if (end > i) {
                        pushStyle(SpanStyle(background = cs.surfaceVariant.copy(alpha = 0.6f), color = cs.onSurface, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace))
                        append(raw.substring(i + 1, end))
                        pop()
                        i = end + 1
                    } else { append(raw[i]); i++ }
                }
                // 链接 [text](url)
                raw[i] == '[' -> {
                    val close = raw.indexOf(']', i)
                    val paren = if (close > i) raw.indexOf('(', close) else -1
                    val urlEnd = if (paren > close) raw.indexOf(')', paren) else -1
                    if (close > i && paren == close + 1 && urlEnd > paren) {
                        val t = raw.substring(i + 1, close)
                        val u = raw.substring(paren + 1, urlEnd)
                        pushStringAnnotation("link", u)
                        withStyle(SpanStyle(color = cs.primary, textDecoration = TextDecoration.Underline)) { append(t) }
                        pop()
                        i = urlEnd + 1
                    } else { append(raw[i]); i++ }
                }
                // 粗体 **...**
                raw[i] == '*' && i + 1 < n && raw[i + 1] == '*' -> {
                    val end = raw.indexOf("**", i + 2)
                    if (end > i) {
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = cs.primary)) { append(raw.substring(i + 2, end)) }
                        i = end + 2
                    } else { append(raw[i]); i++ }
                }
                // 斜体 *...*
                raw[i] == '*' -> {
                    val end = raw.indexOf('*', i + 1)
                    if (end > i) {
                        withStyle(SpanStyle(fontWeight = FontWeight.Light, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)) { append(raw.substring(i + 1, end)) }
                        i = end + 1
                    } else { append(raw[i]); i++ }
                }
                // 着色 <c=#RRGGBB>...</c>
                raw.startsWith("<c=", i) -> {
                    val closeTag = raw.indexOf("</c>", i)
                    val hexEnd = raw.indexOf('>', i)
                    if (closeTag > hexEnd && hexEnd > i) {
                        val hex = raw.substring(i + 3, hexEnd).trim()
                        val color = runCatching { Color(android.graphics.Color.parseColor(hex)) }.getOrNull() ?: cs.onBackground
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
