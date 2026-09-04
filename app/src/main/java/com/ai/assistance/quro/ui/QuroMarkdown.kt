package com.ai.assistance.quro.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp

/**
 * 轻量 Markdown 渲染（原创，零第三方依赖）：支持
 * 标题(#/##/###)、代码块(代码围栏)、有序/无序列表(以减号或星号开头)、引用(>)、
 * 行内 加粗、斜体、行内代码、链接。
 * 用于把模型返回的富文本漂亮地渲染出来。
 */

private data class MdBlock(val kind: String, val text: String, val extra: String = "")

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
                            InlineText(line, onLinkClick, color)
                        }
                    }
                }
                "blank" -> Spacer(Modifier.height(4.dp))
                else -> InlineText(b.text, onLinkClick, color)
            }
        }
    }
}

@Composable
private fun InlineText(text: String, onLinkClick: (String) -> Unit, color: Color) {
    val annotated = remember(text) { inlineAnnotated(text, color) }
    ClickableText(
        text = annotated,
        style = MaterialTheme.typography.bodyMedium.copy(color = color),
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

private fun parseBlocks(src: String): List<MdBlock> {
    val lines = src.replace("\r\n", "\n").split("\n")
    val out = mutableListOf<MdBlock>()
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        when {
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
                    && !lines[i].startsWith(">") && !lines[i].matches(Regex("""^[-*]\s+.+"""))
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
