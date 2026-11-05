package com.ai.assistance.quro.genui.aiapp.renderx
import androidx.compose.ui.res.stringResource
import com.ai.assistance.quro.R
import com.ai.assistance.quro.util.qstr

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
import androidx.compose.ui.text.style.TextOverflow
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

/**
 * 边距串 → [PaddingValues]，与 FlatDocToGenUI.edge 同套段数语义：
 * `"16"` 四边 / `"16,8"` 水平16垂直8（Android 方言）/ 三段 `"上,左右,下"` /
 * 四段 `"上,右,下,左"`（FlatDoc.edgeString 的产出）。解析不了一律 0。
 */
private fun edgePadding(raw: String?): PaddingValues {
    if (raw.isNullOrBlank()) return PaddingValues(0.dp)
    val parts = raw.split(",").map { it.trim() }
    fun num(i: Int) = parts.getOrNull(i)?.toFloatOrNull()
    val a = num(0) ?: 0f
    return when (parts.size) {
        0, 1 -> PaddingValues(a.dp)
        2 -> {
            // 水平,垂直（Android 方言）：parts[0] 走左右、parts[1] 走上下
            val b = num(1) ?: a
            PaddingValues(horizontal = a.dp, vertical = b.dp)
        }
        3 -> {
            val b = num(1) ?: a
            val c = num(2) ?: a
            PaddingValues(start = b.dp, top = a.dp, end = b.dp, bottom = c.dp)
        }
        else -> {
            val b = num(1) ?: a
            val c = num(2) ?: a
            val d = num(3) ?: a
            PaddingValues(start = d.dp, top = a.dp, end = b.dp, bottom = c.dp)
        }
    }
}

@Composable
private fun renderNode(doc: FlatDoc, id: String, onAction: (String) -> Unit, modifier: Modifier = Modifier) {
    val n = doc.nodes[id] ?: return
    val text = n.text.ifBlank { n.props["text"] ?: n.props["title"] ?: n.props["content"] ?: "" }
    // 🔴 padding 可能是多段值：FlatDoc.edgeString 把 style 里的边距对象拍成
    // CSS 四段串（"上,右,下,左"），模型也常写 "16,8"（水平,垂直）。
    // 旧代码 `toFloatOrNull()` 对多段串直接返回 null → 0f → **所有内边距静默丢失**
    // （卡片内容贴边、元素挤成一团，用户报的「没间距」一半是这里）。
    val padVals = edgePadding(n.props["padding"])
    val outer = Modifier.padding(padVals)
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
            Text(text.ifBlank { stringResource(R.string.qk_03650) }, color = colorOf(n.props["color"], Color.White), fontSize = 14.sp, fontWeight = FontWeight.Bold,
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
            Text(text.ifBlank { n.props["hint"] ?: qstr(R.string.qk_03706) }, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(10.dp))
        }
        // 🔴 头/尾栏分支（2026-11-04）：A2uiProtocol 的 TYPE_MAP 会产出 header_bar/footer，
        //    此前没有对应分支 —— 走到兜底退化成一行普通文字（页面头尾"消失"）。
        "header_bar" -> Surface(
            color = colorOf(n.props["bg"], MaterialTheme.colorScheme.surfaceContainer),
            shape = RoundedCornerShape(14.dp),
            modifier = modifier.then(outer).fillMaxWidth()
        ) {
            Row(
                Modifier.padding(horizontal = 14.dp, vertical = 12.dp).fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                    color = colorOf(n.props["color"], MaterialTheme.colorScheme.onSurface),
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                n.kids.forEach { renderNode(doc, it, onAction) }
            }
        }
        "footer" -> Surface(
            color = colorOf(n.props["bg"], MaterialTheme.colorScheme.surfaceContainer),
            shape = RoundedCornerShape(14.dp),
            modifier = modifier.then(outer).fillMaxWidth()
        ) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (text.isNotBlank()) Text(text, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    n.kids.forEach { renderNode(doc, it, onAction) }
                }
            }
        }
        else -> Text(text.ifBlank { n.type }, modifier = modifier.then(outer))
    }
}