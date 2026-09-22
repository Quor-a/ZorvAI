package com.ai.assistance.quro.genui.aiapp.renderx

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * A2UI 扁平邻接表渲染器（v4：props 文本/标题/层级/间距全兼容）
 */
@Composable
fun FlatDocRenderer(doc: FlatDoc, onAction: (String) -> Unit = {}, modifier: Modifier = Modifier) {
    renderNode(doc, doc.root, onAction, modifier)
}

private fun colorOf(s: String?, fallback: Color): Color {
    if (s.isNullOrBlank()) return fallback
    return runCatching {
        androidx.compose.ui.graphics.Color(android.graphics.Color.parseColor(s))
    }.getOrDefault(fallback)
}

@Composable
private fun renderNode(doc: FlatDoc, id: String, onAction: (String) -> Unit, modifier: Modifier = Modifier) {
    val n = doc.nodes[id] ?: return
    val text = n.text.ifBlank { n.props["text"] ?: n.props["title"] ?: n.props["content"] ?: "" }
    val pad = (n.props["padding"]?.toFloatOrNull() ?: 0f).dp
    val outer = Modifier.padding(pad)
    // 纯文字类叶子没有内容 → 不渲染（防垃圾空块）
    if (text.isBlank() && n.kids.isEmpty() && n.type !in setOf("column", "row", "scroll", "card", "divider", "spacer", "image", "progress", "input", "button")) return

    when (n.type) {
        "column" -> Column(modifier.then(outer), verticalArrangement = Arrangement.spacedBy((n.props["gap"]?.toFloatOrNull() ?: 10f).dp)) {
            n.kids.forEach { renderNode(doc, it, onAction) }
        }
        "row" -> Row(modifier.then(outer), horizontalArrangement = Arrangement.spacedBy((n.props["gap"]?.toFloatOrNull() ?: 10f).dp), verticalAlignment = Alignment.CenterVertically) {
            n.kids.forEach { renderNode(doc, it, onAction) }
        }
        "scroll" -> Column(modifier.then(outer).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            n.kids.forEach { renderNode(doc, it, onAction) }
            Spacer(Modifier.height(130.dp))
        }
        "card" -> Surface(
            color = colorOf(n.props["bg"], MaterialTheme.colorScheme.surfaceContainerLow),
            shape = RoundedCornerShape((n.props["radius"]?.toFloatOrNull() ?: 14f).dp),
            tonalElevation = 1.dp,
            modifier = modifier.then(outer).fillMaxWidth()
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (text.isNotBlank()) Text(text, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = colorOf(n.props["color"], MaterialTheme.colorScheme.onSurface))
                n.kids.forEach { renderNode(doc, it, onAction) }
            }
        }
        "text" -> Text(text.ifBlank { " " }, fontSize = (n.props["size"]?.toFloatOrNull() ?: 14f).sp,
            color = colorOf(n.props["color"], MaterialTheme.colorScheme.onSurface),
            fontWeight = if (n.props["bold"] == "true") FontWeight.Bold else FontWeight.Normal,
            modifier = modifier.then(outer))
        "heading" -> {
            val level = (n.props["level"]?.toFloatOrNull() ?: 2f).toInt().coerceIn(1, 3)
            Text(text, fontSize = (26f - level * 3f).sp,
                color = colorOf(n.props["color"], MaterialTheme.colorScheme.onSurface),
                fontWeight = FontWeight.Black, modifier = modifier.then(outer))
        }
        "button" -> Surface(
            color = colorOf(n.props["bg"], MaterialTheme.colorScheme.primary),
            shape = RoundedCornerShape(12.dp),
            modifier = modifier.then(outer).clickable { onAction(text) }
        ) {
            Text(text.ifBlank { "按钮" }, color = colorOf(n.props["color"], Color.White), fontSize = 14.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp))
        }
        "image" -> Box(
            Modifier.then(outer).fillMaxWidth().height((n.props["h"]?.toFloatOrNull() ?: 120f).dp)
                .clip(RoundedCornerShape(12.dp)).background(colorOf(n.props["bg"], MaterialTheme.colorScheme.surfaceVariant)),
            contentAlignment = Alignment.Center
        ) { Text(text.ifBlank { "🖼" }, fontSize = 34.sp) }
        "divider" -> Box(Modifier.then(outer).fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)))
        "spacer" -> Spacer(Modifier.then(outer).height((n.props["h"]?.toFloatOrNull() ?: 12f).dp))
        "progress" -> LinearProgressIndicator(progress = { (n.props["value"]?.toFloatOrNull() ?: 0f).coerceIn(0f, 1f) }, modifier = Modifier.then(outer).fillMaxWidth())
        "chip" -> if (text.isNotBlank()) Surface(color = colorOf(n.props["bg"], MaterialTheme.colorScheme.secondaryContainer), shape = RoundedCornerShape(999.dp), modifier = modifier.then(outer)) {
            Text(text, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
        }
        "input" -> Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(10.dp), modifier = modifier.then(outer).fillMaxWidth()) {
            Text(text.ifBlank { n.props["hint"] ?: "输入…" }, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(10.dp))
        }
        else -> Text(text.ifBlank { n.type }, modifier = modifier.then(outer))
    }
}
