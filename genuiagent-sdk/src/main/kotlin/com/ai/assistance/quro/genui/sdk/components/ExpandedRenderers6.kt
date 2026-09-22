package com.ai.assistance.quro.genui.sdk.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.assistance.quro.genui.sdk.dsl.UIComponent
import com.ai.assistance.quro.genui.sdk.render.RenderChildren
import com.ai.assistance.quro.genui.sdk.render.RenderContext
import com.ai.assistance.quro.genui.sdk.style.ColorParser
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private fun JsonObject.s6(key: String, def: String? = null): String? = (this[key] as? JsonPrimitive)?.content ?: def
private fun JsonObject.f6(key: String, def: Float = 0f): Float = (this[key] as? JsonPrimitive)?.content?.toFloatOrNull() ?: def
private fun JsonObject.i6(key: String, def: Int = 0): Int = (this[key] as? JsonPrimitive)?.content?.toIntOrNull() ?: def
private fun JsonObject.b6(key: String, def: Boolean = false): Boolean = (this[key] as? JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: def
private fun JsonObject.a6str(key: String): List<String> = ((this[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }) ?: emptyList()

// ═══════════ 17. 文档排版 ═══════════

@Composable
fun DocParagraphRenderer(c: UIComponent, ctx: RenderContext) {
    val text = c.properties.s6("text", "") ?: ""
    Text(
        text, fontSize = 15.sp, lineHeight = 26.sp,
        modifier = Modifier.fillMaxWidth().padding(start = 30.dp, bottom = 10.dp)
    )
}

@Composable
fun DocHeadingRenderer(c: UIComponent, ctx: RenderContext) {
    val text = c.properties.s6("text", "标题") ?: "标题"
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(4.dp).height(20.dp).clip(RoundedCornerShape(2.dp)).background(MaterialTheme.colorScheme.primary))
        Text(text, fontSize = 19.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 10.dp))
    }
}

@Composable
fun PullQuoteRenderer(c: UIComponent, ctx: RenderContext) {
    val text = c.properties.s6("text", "") ?: ""
    val author = c.properties.s6("author", "") ?: ""
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Box(Modifier.width(4.dp).height(64.dp).clip(RoundedCornerShape(2.dp)).background(MaterialTheme.colorScheme.tertiary))
        Column(Modifier.padding(start = 14.dp)) {
            Text(text, fontSize = 15.sp, fontStyle = FontStyle.Italic)
            if (author.isNotBlank()) Text("—— $author", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

// ═══════════ 18. 标准 ═══════════

@Composable
fun SpecBadgeRenderer(c: UIComponent, ctx: RenderContext) {
    val code = c.properties.s6("code", "GB/T 0000-2024") ?: "GB/T 0000-2024"
    Box(
        Modifier.clip(RoundedCornerShape(6.dp)).background(Color(0xFFF1F5F9))
            .border(1.dp, Color(0xFFCBD5E1), RoundedCornerShape(6.dp)).padding(horizontal = 10.dp, vertical = 4.dp)
    ) { Text(code, fontSize = 11.sp, color = Color(0xFF475569), fontWeight = FontWeight.Bold) }
}

@Composable
fun ComplianceCheckRenderer(c: UIComponent, ctx: RenderContext) {
    val p = c.properties
    val pass = p.b6("pass", true)
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(22.dp).clip(CircleShape).background(if (pass) Color(0xFF22C55E) else Color(0xFFEF4444)), contentAlignment = Alignment.Center) {
            Text(if (pass) "✓" else "✗", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Black)
        }
        Column(Modifier.weight(1f).padding(start = 10.dp)) {
            Text(p.s6("item", "检查项") ?: "检查项", fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Text(p.s6("standard", "") ?: "", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(if (pass) "合格" else "不合格", fontSize = 12.sp, color = if (pass) Color(0xFF16A34A) else Color(0xFFDC2626), fontWeight = FontWeight.Bold)
    }
}

// ═══════════ 19. 表格 ═══════════

@Composable
fun DataTableRenderer(c: UIComponent, ctx: RenderContext) {
    val headers = c.properties.a6str("headers")
    val rows = c.properties["rows"] as? JsonArray
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp))) {
        if (headers.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(vertical = 9.dp)) {
                headers.forEach { h -> Text(h, fontWeight = FontWeight.Bold, fontSize = 12.sp, modifier = Modifier.weight(1f).padding(horizontal = 10.dp)) }
            }
        }
        val rowList = (rows?.mapNotNull { r -> (r as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content } }) ?: emptyList()
        rowList.forEachIndexed { ri, row ->
            Row(Modifier.fillMaxWidth().background(if (ri % 2 == 1) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f) else Color.Transparent).padding(vertical = 9.dp)) {
                row.forEach { cell -> Text(cell, fontSize = 12.sp, modifier = Modifier.weight(1f).padding(horizontal = 10.dp)) }
            }
        }
    }
}

@Composable
fun TableSortHeaderRenderer(c: UIComponent, ctx: RenderContext) {
    val label = c.properties.s6("label", "列") ?: "列"
    val dir = c.properties.s6("dir", "none") ?: "none"
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Text(if (dir == "asc") " ▲" else if (dir == "desc") " ▼" else " ↕", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ═══════════ 20. 天气 ═══════════

@Composable
fun WeatherBigRenderer(c: UIComponent, ctx: RenderContext) {
    val p = c.properties
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        Text(p.s6("emoji", "☀️") ?: "☀️", fontSize = 72.sp)
        Row(verticalAlignment = Alignment.Top) {
            Text(p.i6("temp", 25).toString(), fontSize = 64.sp, fontWeight = FontWeight.Thin)
            Text("°C", fontSize = 22.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(p.s6("desc", "晴") ?: "晴", fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun HourlyForecastRenderer(c: UIComponent, ctx: RenderContext) {
    val hours = c.properties.a6str("hours")
    val temps = c.properties.a6str("temps")
    val emojis = c.properties.a6str("emojis")
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)).padding(vertical = 12.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
        hours.forEachIndexed { i, h ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(h, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(emojis.getOrNull(i) ?: "☀️", fontSize = 20.sp)
                Text(temps.getOrNull(i) ?: "--", fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

// ═══════════ 21. 时间 ═══════════

@Composable
fun WorldClockRowRenderer(c: UIComponent, ctx: RenderContext) {
    val p = c.properties
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(p.s6("city", "上海") ?: "上海", fontSize = 15.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.weight(1f))
        Column(horizontalAlignment = Alignment.End) {
            Text(p.s6("time", "12:00") ?: "12:00", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text(p.s6("delta", "GMT+8") ?: "GMT+8", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun TimeBadgeRenderer(c: UIComponent, ctx: RenderContext) {
    val time = c.properties.s6("time", "12:00") ?: "12:00"
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(MaterialTheme.colorScheme.primaryContainer).padding(horizontal = 12.dp, vertical = 5.dp)) {
        Text("🕐", fontSize = 12.sp)
        Text(time, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.padding(start = 5.dp))
    }
}

// ═══════════ 22. 动作卡 ═══════════

@Composable
fun ActionCardRenderer(c: UIComponent, ctx: RenderContext) {
    val p = c.properties
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
            Text(p.s6("emoji", "⚡") ?: "⚡", fontSize = 20.sp)
        }
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(p.s6("title", "操作") ?: "操作", fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Text(p.s6("desc", "") ?: "", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text("›", fontSize = 22.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun QuickActionGridRenderer(c: UIComponent, ctx: RenderContext) {
    val items = c.properties.a6str("items")
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items.chunked(4).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                row.forEach { it2 ->
                    Box(Modifier.size(64.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
                        Text(it2, fontSize = 24.sp)
                    }
                }
            }
        }
    }
}

// ═══════════ 23. 卡片 ═══════════

@Composable
fun ElevatedCardRenderer(c: UIComponent, ctx: RenderContext) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
            .padding(16.dp)
    ) {
        c.properties.s6("title", "")?.takeIf { it.isNotBlank() }?.let {
            Text(it, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Spacer(Modifier.height(8.dp))
        }
        if (c.children.isNotEmpty()) RenderChildren(c.children, ctx)
    }
}

@Composable
fun StackedCardsRenderer(c: UIComponent, ctx: RenderContext) {
    Box(Modifier.fillMaxWidth().height(190.dp).padding(top = 12.dp)) {
        Box(Modifier.fillMaxWidth(0.86f).height(150.dp).align(Alignment.TopCenter).clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)))
        Box(Modifier.fillMaxWidth(0.93f).height(160.dp).align(Alignment.TopCenter).clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.75f)))
        Box(Modifier.fillMaxWidth().height(170.dp).align(Alignment.TopCenter).clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surface).padding(16.dp)) {
            if (c.children.isNotEmpty()) RenderChildren(c.children, ctx)
            else Text(c.properties.s6("title", "叠卡") ?: "叠卡", fontWeight = FontWeight.Bold)
        }
    }
}

// ═══════════ 24. 情绪 ═══════════

@Composable
fun EmotionFaceRenderer(c: UIComponent, ctx: RenderContext) {
    val p = c.properties
    val intensity = p.f6("intensity", 0.6f)
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().padding(12.dp)) {
        Text(p.s6("emoji", "😊") ?: "😊", fontSize = 64.sp)
        Text(p.s6("label", "平静") ?: "平静", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        MiniBar6(intensity, MaterialTheme.colorScheme.tertiary)
    }
}

@Composable
fun MoodTrackerWeekRenderer(c: UIComponent, ctx: RenderContext) {
    val days = c.properties.a6str("days")
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        days.forEachIndexed { i, e ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(38.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
                    Text(e, fontSize = 18.sp)
                }
                Text(listOf("一", "二", "三", "四", "五", "六", "日").getOrElse(i) { "" }, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

// ═══════════ 25. 标签 ═══════════

@Composable
fun TagCloudRenderer(c: UIComponent, ctx: RenderContext) {
    val tags = c.properties.a6str("tags")
    val colors = listOf(Color(0xFF3B82F6), Color(0xFF22C55E), Color(0xFFF59E0B), Color(0xFFEF4444), Color(0xFFA855F7), Color(0xFF06B6D4))
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        tags.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEachIndexed { i, t ->
                    Box(Modifier.clip(RoundedCornerShape(999.dp)).background(colors[(tags.indexOf(t)) % colors.size].copy(alpha = 0.16f)).padding(horizontal = 14.dp, vertical = 6.dp)) {
                        Text("#$t", fontSize = 13.sp, color = colors[(tags.indexOf(t)) % colors.size], fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
fun LabelPillRenderer(c: UIComponent, ctx: RenderContext) {
    val p = c.properties
    val bg = p.s6("color", "#3B82F6") ?: "#3B82F6"
    val c6 = ColorParser.toColor(bg, MaterialTheme.colorScheme.primary)
    Box(Modifier.clip(RoundedCornerShape(999.dp)).background(c6.copy(alpha = 0.16f)).padding(horizontal = 12.dp, vertical = 5.dp)) {
        Text(p.s6("text", "标签") ?: "标签", fontSize = 12.sp, color = c6, fontWeight = FontWeight.Bold)
    }
}

// ═══════════ 26. 代办 ═══════════

@Composable
fun TodoGroupRenderer(c: UIComponent, ctx: RenderContext) {
    val items = c.properties.a6str("items")
    val doneFlags = c.properties["done"] as? JsonArray
    val doneList = (doneFlags?.mapNotNull { (it as? JsonPrimitive)?.content?.toBooleanStrictOrNull() }) ?: List(items.size) { false }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surface).padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items.forEachIndexed { i, item ->
            val done = doneList.getOrNull(i) ?: false
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(20.dp).clip(CircleShape).background(if (done) MaterialTheme.colorScheme.primary else Color.Transparent).border(2.dp, if (done) Color.Transparent else MaterialTheme.colorScheme.outline, CircleShape), contentAlignment = Alignment.Center) {
                    if (done) Text("✓", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Black)
                }
                Text(item, fontSize = 14.sp, modifier = Modifier.padding(start = 10.dp),
                    textDecoration = if (done) androidx.compose.ui.text.style.TextDecoration.LineThrough else null,
                    color = if (done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}

@Composable
fun TodoProgressRenderer(c: UIComponent, ctx: RenderContext) {
    val p = c.properties
    val done = p.i6("done", 0); val total = p.i6("total", 10).coerceAtLeast(1)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(44.dp).clip(CircleShape).background(Brush.linearGradient(listOf(Color(0xFF22C55E), Color(0xFF10B981)))), contentAlignment = Alignment.Center) {
            Text("${(done * 100 / total)}%", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Black)
        }
        Column(Modifier.padding(start = 10.dp)) {
            Text("今日完成", fontWeight = FontWeight.Bold, fontSize = 13.sp)
            Text("$done / $total 项", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ═══════════ 27. 播放器 ═══════════

@Composable
fun PlaylistRowRenderer(c: UIComponent, ctx: RenderContext) {
    val p = c.properties
    val playing = p.b6("playing", false)
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(46.dp).clip(RoundedCornerShape(8.dp)).background(Brush.verticalGradient(listOf(Color(0xFF818CF8), Color(0xFFC084FC)))), contentAlignment = Alignment.Center) {
            Text(p.s6("emoji", "🎵") ?: "🎵", fontSize = 20.sp)
        }
        Column(Modifier.weight(1f).padding(start = 10.dp)) {
            Text(p.s6("title", "歌曲") ?: "歌曲", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = if (playing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
            Text(p.s6("artist", "未知") ?: "未知", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(p.s6("duration", "3:45") ?: "3:45", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun MiniPlayerRenderer(c: UIComponent, ctx: RenderContext) {
    val p = c.properties
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color(0xFF1E1B4B)).padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(42.dp).clip(RoundedCornerShape(8.dp)).background(Brush.verticalGradient(listOf(Color(0xFFF472B6), Color(0xFF818CF8)))), contentAlignment = Alignment.Center) {
                Text(p.s6("emoji", "🎧") ?: "🎧", fontSize = 18.sp)
            }
            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                Text(p.s6("title", "正在播放") ?: "正在播放", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                Text(p.s6("artist", "") ?: "", color = Color(0xFFA5B4FC), fontSize = 11.sp, maxLines = 1)
            }
            Text("⏮  ▶  ⏭", color = Color.White, fontSize = 14.sp)
        }
        Spacer(Modifier.height(10.dp))
        Box(Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.2f))) {
            Box(Modifier.fillMaxWidth(p.f6("progress", 0.35f).coerceIn(0f, 1f)).height(3.dp).clip(RoundedCornerShape(2.dp)).background(Color.White))
        }
    }
}

// ── 文件内进度条 ──
@Composable
private fun MiniBar6(frac: Float, color: Color) {
    Box(Modifier.fillMaxWidth(0.6f).height(6.dp).clip(RoundedCornerShape(3.dp)).background(Color.LightGray.copy(alpha = 0.35f))) {
        Box(Modifier.fillMaxWidth(frac.coerceIn(0f, 1f)).height(6.dp).clip(RoundedCornerShape(3.dp)).background(color))
    }
}
