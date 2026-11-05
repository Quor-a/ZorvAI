package com.ai.assistance.quro.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.assistance.quro.core.canvas.Aip
import com.ai.assistance.quro.ui.canvas.AipCanvasBlock

/**
 * 轻量 Markdown 渲染（原创，零第三方依赖）：支持
 * 标题(#/##/###)、代码块(代码围栏)、有序/无序列表(以减号或星号开头)、引用(>)、
 * 行内 加粗、斜体、行内代码、链接。
 * 另支持 A 通道增强容器语法（PRD 4.4）：
 *   :::card 标题 ... :::          → 浮起卡片
 *   :::columns ... --- ... :::    → 多栏（用 --- 分栏）
 *   :::chart bar|line|pie|radar 标题
 *     标签: 数值（每行一条）... : → 原生图表
 *   :::steps ... :::              → 步骤条
 * 用于把模型返回的富文本漂亮地渲染出来。
 */

private data class MdBlock(val kind: String, val text: String, val extra: String = "", val param: String = "")

@Composable
fun MarkdownText(
    text: String,
    onLinkClick: (String) -> Unit = {},
    color: Color = MaterialTheme.colorScheme.onSurface,
) {
    val blocks = remember(text) { parseBlocks(text) }
    Column(Modifier.fillMaxWidth()) {
        blocks.forEach { b ->
            when (b.kind) {
                "code" -> CodeBlock(b.text, b.extra)
                "h1" -> Text(b.text, style = MaterialTheme.typography.headlineSmall, color = color, modifier = Modifier.padding(vertical = 2.dp))
                "h2" -> Text(b.text, style = MaterialTheme.typography.titleLarge, color = color, modifier = Modifier.padding(vertical = 2.dp))
                "h3" -> Text(b.text, style = MaterialTheme.typography.titleMedium, color = color, modifier = Modifier.padding(vertical = 2.dp))
                "quote" -> Text(
                    b.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = color,
                    fontStyle = FontStyle.Italic,
                    modifier = Modifier
                        .padding(start = 8.dp, top = 2.dp, bottom = 2.dp)
                        .fillMaxWidth(),
                )
                "list" -> Column(Modifier.padding(start = 8.dp, top = 2.dp, bottom = 2.dp)) {
                    b.text.lines().forEach { line ->
                        Row(Modifier.fillMaxWidth()) {
                            Text("• ", color = color)
                            // 列表项同样走 LaTeX 通道（行内公式常见于列表），无公式时退化为富文本
                            LatexMathText(line, Modifier.weight(1f), color)
                        }
                    }
                }
                "blank" -> Spacer(Modifier.height(4.dp))
                "container" -> ContainerBlock(b, onLinkClick, color)
                "table" -> TableBlock(b, onLinkClick, color)
                "p" -> LatexMathText(b.text, Modifier.fillMaxWidth(), color)
                else -> InlineText(b.text, onLinkClick, color)
            }
        }
    }
}

/** A 通道增强容器：:::card / :::columns / :::chart / :::steps（未知类型回落纯 Markdown 渲染）。 */
@Composable
private fun ContainerBlock(b: MdBlock, onLinkClick: (String) -> Unit, color: Color) {
    when (b.extra) {
        "card" -> Column(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                .border(
                    1.dp,
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                    RoundedCornerShape(12.dp),
                )
                .padding(12.dp),
        ) {
            if (b.param.isNotBlank()) {
                Text(
                    b.param,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = color,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
            MarkdownText(b.text, onLinkClick, color)
        }
        "columns" -> {
            val cols = b.text.split(Regex("""\n\s*---+\s*\n""")).map { it.trim() }.filter { it.isNotEmpty() }
            if (cols.size >= 2) {
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    cols.forEach { c -> Box(Modifier.weight(1f)) { MarkdownText(c, onLinkClick, color) } }
                }
            } else {
                MarkdownText(b.text, onLinkClick, color)
            }
        }
        "chart" -> {
            // :::chart bar 标题  → 每行「标签: 数值」
            val chartType = b.param.substringBefore(' ').ifBlank { "bar" }.lowercase()
            val title = b.param.substringAfter(' ', "").trim()
            val labels = ArrayList<String>()
            val values = ArrayList<Double>()
            b.text.lines().forEach { ln ->
                val m = Regex("""^(.+?)\s*[:：,，]\s*(-?\d+(?:\.\d+)?)\s*$""").find(ln.trim()) ?: return@forEach
                labels.add(m.groupValues[1].trim())
                values.add(m.groupValues[2].toDouble())
            }
            if (values.isNotEmpty()) {
                AipCanvasBlock(
                    Aip.Block.Chart(
                        id = "md_chart",
                        chartType = chartType,
                        title = title,
                        labels = labels,
                        series = listOf(Aip.Block.Chart.Series("", values)),
                    )
                )
            }
        }
        "steps" -> {
            val items = b.text.lines().map { it.trim() }.filter { it.isNotBlank() }
            if (items.isNotEmpty()) AipCanvasBlock(Aip.Block.Steps("md_steps", items, "vertical"))
        }
        else -> MarkdownText(b.text, onLinkClick, color)
    }
}

/**
 * Markdown 表格渲染（| a | b | 分隔行 | 数据行）。
 * 表格以「分隔行」（--- 与 | 组成）为界，分隔行上方是表头，下方是数据行。
 * 表头加粗，所有单元格水平对齐；无多余内边距，保持紧凑。
 */
@Composable
private fun TableBlock(b: MdBlock, onLinkClick: (String) -> Unit, color: Color) {
    // b.text 是多行原始表格文本；b.extra 存表头行数（1 = 有表头）。
    val lines = b.text.lines().filter { it.trim().isNotEmpty() }
    if (lines.isEmpty()) return
    val hasHeader = b.extra == "1"
    val header = if (hasHeader) lines.firstOrNull() else null
    val body = if (hasHeader) lines.drop(1) else lines

    fun parseCells(line: String): List<String> =
        line.trim().trim('|').split('|').map { it.trim() }

    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f), RoundedCornerShape(8.dp)),
    ) {
        if (header != null) {
            val cols = parseCells(header)
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                    .padding(vertical = 6.dp),
            ) {
                cols.forEach { c ->
                    Box(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                        InlineText(c, onLinkClick, color)
                    }
                }
            }
        }
        body.forEachIndexed { idx, line ->
            val cols = parseCells(line)
            if (cols.isEmpty()) return@forEachIndexed
            Row(
                Modifier
                    .fillMaxWidth()
                    .then(
                        if (idx % 2 == 1) {
                            Modifier.background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.18f))
                        } else Modifier,
                    )
                    .padding(vertical = 5.dp),
            ) {
                cols.forEach { c ->
                    Box(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                        InlineText(c, onLinkClick, color)
                    }
                }
            }
        }
    }
}

@Composable
private fun InlineText(text: String, onLinkClick: (String) -> Unit, color: Color) {
    val annotated = remember(text) { inlineAnnotated(text, color) }
    // 加 fillMaxWidth + 明确最小行高，避免 ClickableText 在个别版本测量异常导致多行文字逐行叠压（"一层覆盖一层"）。
    ClickableText(
        text = annotated,
        style = MaterialTheme.typography.bodyMedium.copy(color = color, lineHeight = 20.sp),
        modifier = Modifier.fillMaxWidth(),
        onClick = { offset ->
            annotated.getStringAnnotations("url", offset, offset).firstOrNull()?.let { onLinkClick(it.item) }
        },
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
                seg.startsWith("**") -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                    append(seg.removeSurrounding("**"))
                }
                seg.startsWith("`") -> withStyle(
                    SpanStyle(fontFamily = FontFamily.Monospace, background = Color(0xFFE5E7EB), color = Color(0xFF111827)),
                ) {
                    append(seg.removeSurrounding("`"))
                }
                seg.startsWith("[") -> {
                    val mm = Regex("""\[([^\]]+)\]\(([^)]+)\)""").find(seg)
                    if (mm != null) {
                        val (t, u) = mm.destructured
                        pushStringAnnotation("url", u)
                        withStyle(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)) { append(t) }
                        pop()
                    } else {
                        append(seg)
                    }
                }
                else -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                    append(seg.removeSurrounding("*"))
                }
            }
            last = m.range.last + 1
        }
        append(text.substring(last))
    }
}

/** 表格行：以 | 开头/包含 |，且不是分隔行。 */
private fun isTableRow(line: String): Boolean =
    line.trim().startsWith("|") && !isTableSeparator(line)

/** 表格分隔行：由 |、-、:、空格组成，且至少含一个 -。 */
private fun isTableSeparator(line: String): Boolean {
    val t = line.trim()
    if (!t.startsWith("|") || !t.endsWith("|")) return false
    val inner = t.trim('|').trim()
    if (inner.isEmpty() || !inner.contains('-')) return false
    // 每个单元格必须是 -+: 组合
    return inner.split('|').all { cell ->
        cell.isNotBlank() && cell.all { it == '-' || it == ':' || it == ' ' }
    }
}

private fun parseBlocks(src: String): List<MdBlock> {
    val lines = src.replace("\r\n", "\n").split("\n")
    val out = mutableListOf<MdBlock>()
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        when {
            // :::type 参数 ... :::（A 通道增强容器；流式未闭合时也能渲染已到的内容）
            line.startsWith(":::") && line.trim() != ":::" -> {
                val decl = line.trim().removePrefix(":::").trim()
                val ctype = decl.substringBefore(' ').trim().lowercase()
                val param = decl.substringAfter(' ', "").trim()
                val inner = StringBuilder()
                i++
                while (i < lines.size && lines[i].trim() != ":::") {
                    inner.appendLine(lines[i]); i++
                }
                i++ // 跳过收尾 :::（流式截断时没有也照常结束）
                out.add(MdBlock("container", inner.toString().trimEnd(), ctype, param))
            }
            line.startsWith("```") -> {
                val lang = line.removePrefix("```").trim()
                val code = StringBuilder()
                i++
                while (i < lines.size && !lines[i].startsWith("```")) {
                    code.appendLine(lines[i]); i++
                }
                i++
                out.add(MdBlock("code", code.toString().trimEnd(), lang))
            }
            line.startsWith("# ") -> { out.add(MdBlock("h1", line.substring(2))); i++ }
            line.startsWith("## ") -> { out.add(MdBlock("h2", line.substring(3))); i++ }
            line.startsWith("### ") -> { out.add(MdBlock("h3", line.substring(4))); i++ }
            line.startsWith(">") -> { out.add(MdBlock("quote", line.removePrefix(">").trim())); i++ }
            isTableRow(line) && i + 1 < lines.size && isTableSeparator(lines[i + 1]) -> {
                // Markdown 表格：| 表头 |（下一行是分隔行）
                val rows = mutableListOf<String>()
                rows.add(line)
                i++ // 表头
                i++ // 分隔行（跳过）
                while (i < lines.size && isTableRow(lines[i])) {
                    rows.add(lines[i]); i++
                }
                out.add(MdBlock("table", rows.joinToString("\n"), "1"))
            }
            line.matches(Regex("""^[-*]\s+.+""")) -> {
                val items = mutableListOf<String>()
                while (i < lines.size && lines[i].matches(Regex("""^[-*]\s+.+"""))) {
                    items.add(lines[i].replaceFirst(Regex("""^[-*]\s+"""), "")); i++
                }
                out.add(MdBlock("list", items.joinToString("\n")))
            }
            line.isBlank() -> { out.add(MdBlock("blank", "")); i++ }
            else -> {
                val para = StringBuilder()
                val startI = i
                while (i < lines.size && lines[i].isNotBlank()
                    && !lines[i].startsWith("```") && !lines[i].startsWith("#")
                    && !lines[i].startsWith(">") && !lines[i].startsWith(":::")
                    && !lines[i].matches(Regex("""^[-*]\s+.+"""))
                    && !isTableRow(lines[i])
                ) {
                    para.appendLine(lines[i]); i++
                }
                if (i == startI) i++ // 首行不匹配任何块类型时兜底推进，避免死循环
                out.add(MdBlock("p", para.toString().trimEnd()))
            }
        }
    }
    return out
}
