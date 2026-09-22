package com.ai.assistance.quro.genui.sdk.components

import com.ai.assistance.quro.genui.sdk.style.ZorvPalette

import androidx.compose.ui.text.withStyle

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Markdown 增强解析（对齐 llm-ui / multiplatform-markdown-renderer 的 GFM 子集）：
 * 在旧解析（标题/粗斜/行内码/列表）之上补齐：
 * - 任务列表  - [ ] / - [x]（✓ 方框渲染）
 * - 删除线  ~~text~~
 * - 链接  [text](url)（下划线+主题蓝）
 * - 自动链接  https://...（裸 URL 直接着色）
 * - 引用块  > quote（左侧竖线样式，缩进）
 * - 表格  | a | b |（简单两态表：表头粗体+分隔线识别）
 * - GitHub Alerts  > [!NOTE]/[!TIP]/[!WARNING]/[!CAUTION]/[!IMPORTANT]（图标前缀+着色）
 * - 分隔线  ---
 * - 流式安全：未闭合 ** ` ~~ [ ( 自动按字面量渲染（llm-ui 的 broken-markdown 容错思路）
 */
object MarkdownPlus {

    /** 行内富文本（粗/斜/码/删除线/链接/自动链接），未闭合标记按字面量输出（流式安全） */
    fun inline(text: String, linkColor: Color, codeBg: Color? = null): AnnotatedString = buildAnnotatedString {
        var i = 0
        val n = text.length
        while (i < n) {
            val c = text[i]
            when {
                // 粗体 **x**
                c == '*' && i + 1 < n && text[i + 1] == '*' -> {
                    val end = text.indexOf("**", i + 2)
                    if (end != -1) {
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(text.substring(i + 2, end)) }
                        i = end + 2
                    } else { append(text.substring(i)); i = n }   // 流式未闭合：字面量
                }
                // 斜体 *x*
                c == '*' -> {
                    val end = text.indexOf('*', i + 1)
                    if (end != -1 && end > i + 1) {
                        withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(text.substring(i + 1, end)) }
                        i = end + 1
                    } else { append(c); i++ }
                }
                // 删除线 ~~x~~
                c == '~' && i + 1 < n && text[i + 1] == '~' -> {
                    val end = text.indexOf("~~", i + 2)
                    if (end != -1) {
                        withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { append(text.substring(i + 2, end)) }
                        i = end + 2
                    } else { append(text.substring(i)); i = n }
                }
                // 行内码 `x`
                c == '`' -> {
                    val end = text.indexOf('`', i + 1)
                    if (end != -1) {
                        withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBg ?: ZorvPalette.Ink)) { append(text.substring(i + 1, end)) }
                        i = end + 1
                    } else { append(c); i++ }
                }
                // 链接 [x](url)
                c == '[' -> {
                    val close = text.indexOf("](", i + 1)
                    if (close != -1) {
                        val urlEnd = text.indexOf(')', close + 2)
                        if (urlEnd != -1) {
                            withStyle(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)) {
                                append(text.substring(i + 1, close))
                            }
                            i = urlEnd + 1
                            continue
                        }
                    }
                    append(c); i++
                }
                // 自动链接 https://...
                c == 'h' && text.startsWith("http://", i) || c == 'h' && text.startsWith("https://", i) -> {
                    var j = i
                    while (j < n && !text[j].isWhitespace() && text[j] !in "，。）、）]") j++
                    withStyle(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)) {
                        append(text.substring(i, j))
                    }
                    i = j
                }
                else -> { append(c); i++ }
            }
        }
    }

    /** 块级结构识别结果 */
    sealed class Block {
        data class Heading(val level: Int, val text: String) : Block()
        data class Bullet(val text: String) : Block()
        data class Task(val done: Boolean, val text: String) : Block()
        data class Quote(val text: String) : Block()
        data class Alert(val kind: String, val text: String) : Block()
        data class Divider(val text: String = "---") : Block()
        data class Table(val header: List<String>, val rows: List<List<String>>, val raw: String) : Block()
        data class Paragraph(val text: String) : Block()
    }

    /** 块级解析（表格逐行聚合；GFM Alerts 识别） */
    fun blocks(text: String): List<Block> {
        val out = mutableListOf<Block>()
        val lines = text.lines()
        var i = 0
        while (i < lines.size) {
            val t = lines[i].trim()
            when {
                t.isEmpty() -> { }
                t.startsWith("---") && t.all { it == '-' } -> out.add(Block.Divider())
                t.startsWith("#") -> {
                    val m = Regex("^(#{1,6})\\s+(.*)").find(t)
                    if (m != null) out.add(Block.Heading(m.groupValues[1].length, m.groupValues[2])) else out.add(Block.Paragraph(t))
                }
                Regex("^>\\s*\\[!(NOTE|TIP|WARNING|CAUTION|IMPORTANT)]", RegexOption.IGNORE_CASE).containsMatchIn(t) -> {
                    val m = Regex("^>\\s*\\[!(NOTE|TIP|WARNING|CAUTION|IMPORTANT)]\\s*(.*)", RegexOption.IGNORE_CASE).find(t)!!
                    out.add(Block.Alert(m.groupValues[1].uppercase(), m.groupValues[2]))
                }
                t.startsWith("> ") -> out.add(Block.Quote(t.removePrefix("> ")))
                Regex("^[-*+]\\s+\\[[ xX]]\\s+").containsMatchIn(t) -> {
                    val m = Regex("^[-*+]\\s+\\[([ xX])]\\s+(.*)").find(t)!!
                    out.add(Block.Task(m.groupValues[1].isNotBlank(), m.groupValues[2]))
                }
                Regex("^[-*+]\\s+").containsMatchIn(t) -> {
                    out.add(Block.Bullet(Regex("^[-*+]\\s+").replace(t, "")))
                }
                t.startsWith("|") && i + 1 < lines.size && Regex("^\\|[\\s:|-]+\\|?$").containsMatchIn(lines[i + 1].trim()) -> {
                    // 表格头 + 分隔行 + 数据行
                    val header = splitRow(t)
                    i += 2
                    val rows = mutableListOf<List<String>>()
                    while (i < lines.size && lines[i].trim().startsWith("|")) {
                        rows.add(splitRow(lines[i].trim())); i++
                    }
                    out.add(Block.Table(header, rows, ""))
                    continue
                }
                else -> out.add(Block.Paragraph(t))
            }
            i++
        }
        return out
    }

    private fun splitRow(line: String): List<String> =
        line.trim('|').split('|').map { it.trim() }

    /** Alert 色彩 */
    fun alertColor(kind: String): Color = when (kind) {
        "NOTE" -> ZorvPalette.Info
        "TIP" -> ZorvPalette.Success
        "WARNING" -> ZorvPalette.Terracotta
        "CAUTION" -> ZorvPalette.ErrorWarm
        "IMPORTANT" -> ZorvPalette.Terracotta
        else -> ZorvPalette.Info
    }

    fun alertIcon(kind: String): String = when (kind) {
        "NOTE" -> "ℹ️"
        "TIP" -> "💡"
        "WARNING" -> "⚠️"
        "CAUTION" -> "🚫"
        "IMPORTANT" -> "❗"
        else -> "ℹ️"
    }
}

/**
 * Markdown 增强渲染组件（块级结构 + 富行内），流式安全。
 */
@Composable
fun MarkdownPlusView(
    text: String,
    modifier: Modifier = Modifier,
    linkColor: Color = ZorvPalette.Info,
    codeBg: Color? = null,
    textColor: Color = Color.Unspecified
) {
    val blocks = remember(text) { MarkdownPlus.blocks(text) }
    Column(modifier = modifier) {
        blocks.forEach { b ->
            when (b) {
                is MarkdownPlus.Block.Heading -> Text(
                    MarkdownPlus.inline(b.text, linkColor, codeBg),
                    fontSize = when (b.level) { 1 -> 26.sp; 2 -> 22.sp; 3 -> 18.sp; else -> 16.sp }.let { it },
                    fontWeight = if (b.level <= 2) FontWeight.Bold else FontWeight.SemiBold,
                    color = textColor,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
                is MarkdownPlus.Block.Bullet -> Row {
                    Text("•  ", color = textColor)
                    Text(MarkdownPlus.inline(b.text, linkColor, codeBg), color = textColor)
                }
                is MarkdownPlus.Block.Task -> Row {
                    Text(if (b.done) "☑ " else "☐ ", color = if (b.done) ZorvPalette.Success else textColor, fontSize = 16.sp)
                    Text(MarkdownPlus.inline(b.text, linkColor, codeBg), color = textColor)
                }
                is MarkdownPlus.Block.Quote -> Row {
                    Spacer(Modifier.width(3.dp).padding(vertical = 0.dp))
                    androidx.compose.foundation.layout.Box(
                        Modifier.width(3.dp).padding(vertical = 1.dp)
                            .then(Modifier)
                    ) {}
                    Text(
                        MarkdownPlus.inline(b.text, linkColor, codeBg),
                        color = if (textColor == Color.Unspecified) ZorvPalette.Muted else textColor,
                        fontStyle = FontStyle.Italic,
                        modifier = Modifier.padding(start = 10.dp, top = 2.dp, bottom = 2.dp)
                    )
                }
                is MarkdownPlus.Block.Alert -> Row {
                    Text("${MarkdownPlus.alertIcon(b.kind)} ", fontSize = 14.sp)
                    Text(
                        MarkdownPlus.inline(b.text, linkColor, codeBg),
                        color = MarkdownPlus.alertColor(b.kind),
                        modifier = Modifier.padding(start = 2.dp)
                    )
                }
                is MarkdownPlus.Block.Divider -> Text(
                    "───────────",
                    color = ZorvPalette.Muted,
                    modifier = Modifier.padding(vertical = 2.dp)
                )
                is MarkdownPlus.Block.Table -> Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                    // 表头
                    Row {
                        b.header.forEach { cell ->
                            Text(
                                MarkdownPlus.inline(cell, linkColor, codeBg),
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                                color = textColor,
                                modifier = Modifier.weight(1f).padding(horizontal = 4.dp, vertical = 4.dp)
                            )
                        }
                    }
                    // 分隔线
                    Text("─".repeat(40), color = ZorvPalette.Muted, fontSize = 12.sp)
                    // 数据行
                    b.rows.forEach { row ->
                        Row {
                            row.forEach { cell ->
                                Text(
                                    MarkdownPlus.inline(cell, linkColor, codeBg),
                                    fontSize = 12.sp,
                                    color = textColor,
                                    modifier = Modifier.weight(1f).padding(horizontal = 4.dp, vertical = 4.dp)
                                )
                            }
                        }
                    }
                }
                is MarkdownPlus.Block.Paragraph -> Text(
                    MarkdownPlus.inline(b.text, linkColor, codeBg),
                    fontSize = 14.sp,
                    color = textColor,
                    modifier = Modifier.padding(vertical = 2.dp)
                )
            }
        }
    }
}
