package com.ai.assistance.quro.genui.sdk.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.assistance.quro.genui.sdk.dsl.UIComponent
import com.ai.assistance.quro.genui.sdk.render.RenderChildren
import com.ai.assistance.quro.genui.sdk.render.RenderContext
import com.ai.assistance.quro.genui.sdk.style.ColorParser
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private fun JsonObject.sD(key: String, def: String? = null): String? = (this[key] as? JsonPrimitive)?.content ?: def
private fun JsonObject.iD(key: String, def: Int = 0): Int = (this[key] as? JsonPrimitive)?.content?.toIntOrNull() ?: def
private fun JsonObject.fD(key: String, def: Float = 0f): Float = (this[key] as? JsonPrimitive)?.content?.toFloatOrNull() ?: def
private fun JsonObject.cD(key: String, def: Color): Color = ColorParser.toColor(sD(key), def)
private fun JsonObject.aD(key: String): List<String> = ((this[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }) ?: emptyList()
@Composable private fun themeD(): Color = MaterialTheme.colorScheme.primary

/** memory_card — 记忆片段卡 */
@Composable
fun MemoryCardRenderer(c: UIComponent, ctx: RenderContext) {
    val p = c.properties
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceContainerLow).padding(10.dp)) {
        Text(p.sD("emoji", "🧠") ?: "🧠", fontSize = 18.sp)
        Column(Modifier.weight(1f).padding(start = 10.dp)) {
            Text(p.sD("text", "") ?: "", fontSize = 13.sp)
            Text(p.sD("time", "刚刚记住") ?: "刚刚记住", fontSize = 10.sp, color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(top = 2.dp))
        }
        Text("📌", fontSize = 13.sp)
    }
}

/** memory_timeline — 记忆时间线 */
@Composable
fun MemoryTimelineRenderer(c: UIComponent, ctx: RenderContext) {
    val items = c.properties.aD("items")
    Column(Modifier.fillMaxWidth().padding(12.dp).padding(start = 8.dp)) {
        items.forEachIndexed { i, text ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(if (i == 0) themeD() else Color.LightGray))
                    if (i < items.size - 1) Box(Modifier.width(2.dp).height(18.dp).background(Color.LightGray.copy(alpha = 0.5f)))
                }
                Text(text, fontSize = 12.sp, modifier = Modifier.padding(start = 10.dp))
            }
        }
    }
}

/** exec_step — 执行步骤行 */
@Composable
fun ExecStepRenderer(c: UIComponent, ctx: RenderContext) {
    val p = c.properties
    val state = p.sD("state", "done") ?: "done"
    val icon = if (state == "done") "✓" else if (state == "run") "◌" else "✗"
    val col = if (state == "done") Color(0xFF16A34A) else if (state == "run") Color(0xFF3B82F6) else Color(0xFFEF4444)
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(20.dp).clip(CircleShape).background(col.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) { Text(icon, color = col, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
        Text(p.sD("text", "") ?: "", fontSize = 13.sp, modifier = Modifier.padding(start = 8.dp))
    }
}

/** exec_progress — 执行总进度 */
@Composable
fun ExecProgressRenderer(c: UIComponent, ctx: RenderContext) {
    val cur = c.properties.iD("current", 0); val total = c.properties.iD("total", 1).coerceAtLeast(1)
    Column(Modifier.fillMaxWidth().padding(12.dp)) {
        Row { Text("执行进度", fontSize = 12.sp); Spacer(Modifier.weight(1f)); Text("$cur/$total", fontSize = 12.sp, color = themeD(), fontWeight = FontWeight.Bold) }
        Spacer(Modifier.height(6.dp))
        Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(Color.LightGray.copy(alpha = 0.35f))) {
            Box(Modifier.fillMaxWidth((cur.toFloat() / total).coerceIn(0f, 1f)).height(6.dp).clip(RoundedCornerShape(3.dp)).background(themeD()))
        }
    }
}

/** calendar_month — 月历网格 */
@Composable
fun CalendarMonthRenderer(c: UIComponent, ctx: RenderContext) {
    val p = c.properties
    val month = p.sD("month", "1月") ?: "1月"
    val today = p.iD("today", 0)
    val startDay = p.iD("startDay", 0).coerceIn(0, 6)
    val days = p.iD("days", 30).coerceIn(28, 31)
    Column(Modifier.fillMaxWidth().padding(10.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainerLow).padding(10.dp)) {
        Text(month, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        Spacer(Modifier.height(6.dp))
        Row { listOf("一", "二", "三", "四", "五", "六", "日").forEach { Text(it, fontSize = 10.sp, color = MaterialTheme.colorScheme.outline, modifier = Modifier.weight(1f), textAlign = TextAlign.Center) } }
        var day = 1; var cell = 0
        while (day <= days) {
            Row(Modifier.fillMaxWidth()) {
                for (w in 0 until 7) {
                    Box(Modifier.weight(1f).height(26.dp), contentAlignment = Alignment.Center) {
                        if (cell >= startDay && day <= days) {
                            val isToday = day == today
                            Box(Modifier.size(22.dp).clip(CircleShape).background(if (isToday) themeD() else Color.Transparent), contentAlignment = Alignment.Center) {
                                Text("$day", fontSize = 11.sp, color = if (isToday) Color.White else MaterialTheme.colorScheme.onSurface)
                            }
                            day++
                        }
                    }
                    cell++
                }
            }
        }
    }
}

/** event_row — 日历事件行 */
@Composable
fun EventRowRenderer(c: UIComponent, ctx: RenderContext) {
    val p = c.properties
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).clip(RoundedCornerShape(8.dp)).background(p.cD("color", Color(0xFF7C3AED)).copy(alpha = 0.15f)).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(3.dp).height(28.dp).clip(RoundedCornerShape(2.dp)).background(p.cD("color", Color(0xFF7C3AED))))
        Column(Modifier.padding(start = 10.dp)) {
            Text(p.sD("title", "事件") ?: "事件", fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Text(p.sD("time", "") ?: "", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** contact_row — 通讯录行 */
@Composable
fun ContactRowRenderer(c: UIComponent, ctx: RenderContext) {
    val p = c.properties
    val name = p.sD("name", "联系人") ?: "联系人"
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(36.dp).clip(CircleShape).background(Brush.linearGradient(listOf(Color(0xFF60A5FA), Color(0xFFA78BFA)))), contentAlignment = Alignment.Center) {
            Text(name.take(1), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        }
        Text(name, fontSize = 14.sp, modifier = Modifier.weight(1f).padding(start = 10.dp))
        Text(p.sD("phone", "") ?: "", fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
    }
}

/** sms_bubble — 短信气泡（in/out） */
@Composable
fun SmsBubbleRenderer(c: UIComponent, ctx: RenderContext) {
    val p = c.properties
    val outgoing = p.sD("role", "in") == "out"
    Box(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 3.dp), contentAlignment = if (outgoing) Alignment.CenterEnd else Alignment.CenterStart) {
        Box(Modifier.fillMaxWidth(0.72f).clip(RoundedCornerShape(14.dp)).background(if (outgoing) Color(0xFF3B82F6) else MaterialTheme.colorScheme.surfaceContainerHigh).padding(10.dp)) {
            Column {
                Text(p.sD("text", "") ?: "", color = if (outgoing) Color.White else MaterialTheme.colorScheme.onSurface, fontSize = 13.sp)
                Text(p.sD("time", "") ?: "", color = MaterialTheme.colorScheme.outline.copy(alpha = 0.7f), fontSize = 9.sp, modifier = Modifier.padding(top = 2.dp).align(Alignment.End))
            }
        }
    }
}

/** sms_code_row — 验证码短信 */
@Composable
fun SmsCodeRowRenderer(c: UIComponent, ctx: RenderContext) {
    val p = c.properties
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).clip(RoundedCornerShape(10.dp)).border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp)).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("✉️", fontSize = 16.sp)
        Text(p.sD("sender", "验证码服务") ?: "", fontSize = 11.sp, color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(start = 8.dp))
        Spacer(Modifier.weight(1f))
        Text(p.sD("code", "000000") ?: "000000", fontSize = 16.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.primary)
    }
}

/** recording_bar — 录音进行中 */
@Composable
fun RecordingBarRenderer(c: UIComponent, ctx: RenderContext) {
    val sec = c.properties.iD("seconds", 0)
    Row(Modifier.fillMaxWidth().padding(12.dp).clip(RoundedCornerShape(999.dp)).background(Color(0xFFEF4444).copy(alpha = 0.12f)).padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(Color(0xFFEF4444)))
        Text("正在录音", fontSize = 12.sp, color = Color(0xFFEF4444), fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 8.dp))
        Spacer(Modifier.weight(1f))
        Text("%02d:%02d".format(sec / 60, sec % 60), fontSize = 13.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
        Text("⏹", fontSize = 15.sp, modifier = Modifier.padding(start = 10.dp))
    }
}

/** dir_tree — 目录树 */
@Composable
fun DirTreeRenderer(c: UIComponent, ctx: RenderContext) {
    val entries = c.properties.aD("entries")
    Column(Modifier.fillMaxWidth().padding(10.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFF0F172A)).padding(10.dp)) {
        entries.forEach { e ->
            val depth = e.count { it == '/' }
            val name = e.trimEnd('/')
            Row(Modifier.padding(start = (depth * 14).dp, top = 2.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (e.endsWith("/")) "📁" else "📄", fontSize = 12.sp)
                Text(name.substringAfterLast('/'), color = Color(0xFFA5F3FC), fontSize = 12.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(start = 6.dp))
            }
        }
    }
}

/** breadcrumb — 面包屑 */
@Composable
fun BreadcrumbRenderer(c: UIComponent, ctx: RenderContext) {
    val crumbs = c.properties.aD("crumbs")
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        crumbs.forEachIndexed { i, crumb ->
            Text(crumb, fontSize = 12.sp, color = if (i == crumbs.size - 1) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, fontWeight = if (i == crumbs.size - 1) FontWeight.Bold else FontWeight.Normal)
            if (i < crumbs.size - 1) Text(" / ", fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
        }
    }
}

/** panel_docked — 停靠面板（children=主内容） */
@Composable
fun PanelDockedRenderer(c: UIComponent, ctx: RenderContext) {
    val p = c.properties
    Row(Modifier.fillMaxWidth().padding(10.dp).height(180.dp).clip(RoundedCornerShape(12.dp)).border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))) {
        Column(Modifier.width(88.dp).fillMaxHeight().background(Color(0xFF1E293B)).padding(vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(p.sD("panelTitle", "面板") ?: "面板", color = Color(0xFF94A3B8), fontSize = 10.sp)
            p.aD("tools").take(5).forEach { Box(Modifier.size(30.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF334155)), contentAlignment = Alignment.Center) { Text(it, fontSize = 13.sp) } }
        }
        Box(Modifier.weight(1f).fillMaxHeight().padding(10.dp)) { RenderChildren(c.children, ctx) }
    }
}

/** download_row — 下载行 */
@Composable
fun DownloadRowRenderer(c: UIComponent, ctx: RenderContext) {
    val p = c.properties
    val frac = p.fD("progress", 0f).coerceIn(0f, 1f)
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(p.sD("kind", "📥") ?: "📥", fontSize = 15.sp)
            Text(p.sD("name", "download.file") ?: "", fontSize = 12.sp, modifier = Modifier.weight(1f).padding(start = 8.dp), maxLines = 1)
            Text(p.sD("speed", "") ?: "", fontSize = 11.sp, color = MaterialTheme.colorScheme.outline)
        }
        Spacer(Modifier.height(4.dp))
        Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color.LightGray.copy(alpha = 0.35f))) {
            Box(Modifier.fillMaxWidth(frac).height(4.dp).clip(RoundedCornerShape(2.dp)).background(themeD()))
        }
    }
}

/** element_card — 元素周期表样式卡 */
@Composable
fun ElementCardRenderer(c: UIComponent, ctx: RenderContext) {
    val p = c.properties
    val col = p.cD("color", Color(0xFF3B82F6))
    Box(
        Modifier.size(84.dp).clip(RoundedCornerShape(10.dp))
            .background(Brush.verticalGradient(listOf(col.copy(alpha = 0.9f), col.copy(alpha = 0.55f))))
            .border(1.dp, Color.White.copy(alpha = 0.3f), RoundedCornerShape(10.dp)).padding(7.dp)
    ) {
        Text("${p.iD("number", 1)}", color = Color.White.copy(alpha = 0.8f), fontSize = 10.sp)
        Column(Modifier.align(Alignment.Center)) {
            Text(p.sD("symbol", "H") ?: "H", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Black, modifier = Modifier.align(Alignment.CenterHorizontally))
            Text(p.sD("name", "") ?: "", color = Color.White.copy(alpha = 0.85f), fontSize = 9.sp, modifier = Modifier.align(Alignment.CenterHorizontally))
        }
    }
}

/** scroll_indicator — 滚动位置指示条 */
@Composable
fun ScrollIndicatorRenderer(c: UIComponent, ctx: RenderContext) {
    val frac = c.properties.fD("position", 0.3f).coerceIn(0f, 1f)
    Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Box(Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)).background(Color.LightGray.copy(alpha = 0.4f)))
        Box(Modifier.fillMaxWidth(0.3f).height(3.dp).clip(RoundedCornerShape(2.dp)).background(themeD()).offset(x = (frac * 240f).dp))
    }
}

/** pager_dots — 页面指示点 */
@Composable
fun PagerDotsRenderer(c: UIComponent, ctx: RenderContext) {
    val total = c.properties.iD("total", 3).coerceIn(1, 8)
    val current = c.properties.iD("current", 0).coerceIn(0, total - 1)
    Row(Modifier.fillMaxWidth().padding(10.dp), horizontalArrangement = Arrangement.Center) {
        repeat(total) { i ->
            Box(Modifier.padding(horizontal = 4.dp).size(if (i == current) 8.dp else 6.dp).clip(CircleShape).background(if (i == current) MaterialTheme.colorScheme.primary else Color.LightGray))
        }
    }
}

/** long_press_hint — 长按进度环 */
@Composable
fun LongPressHintRenderer(c: UIComponent, ctx: RenderContext) {
    val p = c.properties
    val frac = p.fD("progress", 0.5f).coerceIn(0f, 1f)
    Box(Modifier.fillMaxWidth().padding(14.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.size(70.dp), contentAlignment = Alignment.Center) {
            val ringColor = themeD()
            Canvas(Modifier.fillMaxWidth().height(70.dp)) {
                drawCircle(Color.LightGray.copy(alpha = 0.3f), size.minDimension / 2f - 4f, style = androidx.compose.ui.graphics.drawscope.Stroke(5f))
                drawArc(ringColor, -90f, 360f * frac, false, style = androidx.compose.ui.graphics.drawscope.Stroke(5f, cap = StrokeCap.Round))
            }
            Text(p.sD("emoji", "👆") ?: "👆", fontSize = 22.sp)
        }
    }
}
