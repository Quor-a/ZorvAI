package com.ai.assistance.quro.genui.sdk.components

import androidx.compose.material3.minimumInteractiveComponentSize

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate as canvasRotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.assistance.quro.genui.sdk.dsl.UIComponent
import com.ai.assistance.quro.genui.sdk.render.RenderChildren
import com.ai.assistance.quro.genui.sdk.render.RenderContext
import com.ai.assistance.quro.genui.sdk.style.ColorParser
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

// ═══════════════════════════════════════════════════════════════
// 属性读取辅助（宽容解析，缺省不崩）
// ═══════════════════════════════════════════════════════════════

private fun JsonObject.str(key: String, def: String = ""): String =
    (this[key] as? JsonPrimitive)?.content ?: def

private fun JsonObject.flt(key: String, def: Float = 0f): Float =
    (this[key] as? JsonPrimitive)?.floatOrNull ?: def

private fun JsonObject.int(key: String, def: Int = 0): Int =
    (this[key] as? JsonPrimitive)?.intOrNull ?: def

private fun JsonObject.bool(key: String, def: Boolean = false): Boolean =
    (this[key] as? JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: def

private fun JsonObject.color(key: String, fallback: Color): Color =
    ColorParser.toColor((this[key] as? JsonPrimitive)?.content, fallback)

private fun JsonObject.entries(key: String): List<Pair<String, Float>> =
    (this[key] as? kotlinx.serialization.json.JsonArray)?.mapNotNull { el ->
        val obj = el as? JsonObject ?: return@mapNotNull null
        val label = obj.str("label", obj.str("name", "·"))
        val value = obj.flt("value", 0f)
        label to value
    } ?: emptyList()

@Composable
private fun themeColor(ctx: RenderContext, dark: Boolean = false): Color =
    if (dark) ctx.theme.colorScheme.onSurface else MaterialTheme.colorScheme.primary

// ═══════════════════════════════════════════════════════════════
// 一、数据可视化 DataViz（8 个）
// ═══════════════════════════════════════════════════════════════

/** 柱状图 bar_chart — data:[{label,value}] */
@Composable
fun BarChartRenderer(c: UIComponent, ctx: RenderContext) {
    val data = c.properties.entries("data")
    val barColor = c.properties.color("color", MaterialTheme.colorScheme.primary)
    val h = c.style.height?.let { 200f } ?: 180f
    Column(Modifier.padding(8.dp)) {
        if (data.isEmpty()) {
            Text("暂无数据", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
        } else {
            val maxV = data.maxOf { it.second }.coerceAtLeast(0.001f)
            Row(
                Modifier.fillMaxWidth().height(h.coerceIn(80f, 400f).dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.Bottom
            ) {
                data.forEach { (label, v) ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            Modifier
                                .width(18.dp)
                                .height((v / maxV * (h - 40)).coerceAtLeast(4f).dp)
                                .background(barColor, RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                    }
                }
            }
        }
    }
}

/** 折线图 line_chart — Canvas 折线 */
@Composable
fun LineChartRenderer(c: UIComponent, ctx: RenderContext) {
    val data = c.properties.entries("data")
    val lineColor = c.properties.color("color", MaterialTheme.colorScheme.primary)
    val h = (c.style.height?.let { 200f } ?: 160f).coerceIn(80f, 400f)
    Canvas(Modifier.fillMaxWidth().height(h.dp).padding(8.dp)) {
        if (data.size >= 2) {
            val maxV = data.maxOf { it.second }.coerceAtLeast(0.001f)
            val stepX = size.width / (data.size - 1)
            val path = Path()
            data.forEachIndexed { i, (_, v) ->
                val x = i * stepX
                val y = size.height - (v / maxV * size.height * 0.9f) - size.height * 0.05f
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, lineColor, style = Stroke(width = 6f, cap = StrokeCap.Round))
            data.forEachIndexed { i, (_, v) ->
                val x = i * stepX
                val y = size.height - (v / maxV * size.height * 0.9f) - size.height * 0.05f
                drawCircle(lineColor, radius = 10f, center = Offset(x, y))
            }
        }
    }
}

/** 环形图 donut_chart — value 0-100 */
@Composable
fun DonutChartRenderer(c: UIComponent, ctx: RenderContext) {
    val value = c.properties.flt("value", 0f).coerceIn(0f, 100f)
    val ringColor = c.properties.color("color", MaterialTheme.colorScheme.primary)
    val label = c.properties.str("label")
    val sizeDp = (c.style.height?.let { 160f } ?: 140f).coerceIn(80f, 300f)
    Box(Modifier.size(sizeDp.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = size.minDimension * 0.12f
            drawArc(
                Color.LightGray.copy(alpha = 0.3f), -90f, 360f, false,
                style = Stroke(stroke, cap = StrokeCap.Round)
            )
            drawArc(
                ringColor, -90f, value / 100f * 360f, false,
                style = Stroke(stroke, cap = StrokeCap.Round)
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("${value.toInt()}%", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            if (label.isNotEmpty()) Text(label, style = MaterialTheme.typography.labelSmall)
        }
    }
}

/** 热力条 heat_strip — data 数组按值着色 */
@Composable
fun HeatStripRenderer(c: UIComponent, ctx: RenderContext) {
    val data = c.properties.entries("data")
    val base = c.properties.color("color", MaterialTheme.colorScheme.primary)
    Row(
        Modifier.fillMaxWidth().height(28.dp).clip(RoundedCornerShape(6.dp)),
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        if (data.isEmpty()) {
            Box(Modifier.fillMaxSize().background(Color.LightGray.copy(alpha = 0.3f)))
        } else {
            val maxV = data.maxOf { it.second }.coerceAtLeast(0.001f)
            data.forEach { (_, v) ->
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxSize()
                        .background(base.copy(alpha = (v / maxV).coerceIn(0.15f, 1f)))
                )
            }
        }
    }
}

/** 迷你走势 sparkline — 无轴纯折线 */
@Composable
fun SparklineRenderer(c: UIComponent, ctx: RenderContext) {
    val data = c.properties.entries("data")
    val lineColor = c.properties.color("color", MaterialTheme.colorScheme.primary)
    Canvas(Modifier.fillMaxWidth().height(40.dp)) {
        if (data.size >= 2) {
            val maxV = data.maxOf { it.second }.coerceAtLeast(0.001f)
            val stepX = size.width / (data.size - 1)
            val path = Path()
            data.forEachIndexed { i, (_, v) ->
                val y = size.height - (v / maxV * size.height * 0.85f) - size.height * 0.075f
                if (i == 0) path.moveTo(0f, y) else path.lineTo(i * stepX, y)
            }
            drawPath(path, lineColor, style = Stroke(5f, cap = StrokeCap.Round))
        }
    }
}

/** 仪表盘 gauge — value/min/max */
@Composable
fun GaugeRenderer(c: UIComponent, ctx: RenderContext) {
    val value = c.properties.flt("value", 0f)
    val min = c.properties.flt("min", 0f)
    val max = c.properties.flt("max", 100f).coerceAtLeast(min + 0.001f)
    val frac = ((value - min) / (max - min)).coerceIn(0f, 1f)
    val arcColor = c.properties.color("color", MaterialTheme.colorScheme.primary)
    Box(Modifier.size(130.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            drawArc(Color.LightGray.copy(alpha = 0.3f), 135f, 270f, false, style = Stroke(22f, cap = StrokeCap.Round))
            drawArc(arcColor, 135f, 270f * frac, false, style = Stroke(22f, cap = StrokeCap.Round))
        }
        Text(
            "${value.toInt()}",
            fontSize = 24.sp, fontWeight = FontWeight.Bold
        )
    }
}

/** 统计块 stat_tile — value/label/delta/trend */
@Composable
fun StatTileRenderer(c: UIComponent, ctx: RenderContext) {
    val value = c.properties.str("value", "0")
    val label = c.properties.str("label")
    val delta = c.properties.str("delta")
    val up = c.properties.str("trend", "up") != "down"
    val accent = c.properties.color("color", MaterialTheme.colorScheme.primary)
    Surface(
        color = accent.copy(alpha = 0.08f),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = Color.Gray)
            Text(value, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            if (delta.isNotEmpty()) {
                Text(
                    (if (up) "▲ " else "▼ ") + delta,
                    color = if (up) ctx.theme.colorScheme.success else ctx.theme.colorScheme.error,
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
    }
}

/** 进度环 progress_ring — value 0-1 */
@Composable
fun ProgressRingRenderer(c: UIComponent, ctx: RenderContext) {
    val value = c.properties.flt("value", 0f).coerceIn(0f, 1f)
    val ringColor = c.properties.color("color", MaterialTheme.colorScheme.primary)
    Box(Modifier.size(90.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            drawArc(Color.LightGray.copy(alpha = 0.3f), -90f, 360f, false, style = Stroke(14f, cap = StrokeCap.Round))
            drawArc(ringColor, -90f, 360f * value, false, style = Stroke(14f, cap = StrokeCap.Round))
        }
        Text("${(value * 100).toInt()}%", fontWeight = FontWeight.Bold)
    }
}

// ═══════════════════════════════════════════════════════════════
// 二、交互控件 Interaction（8 个）
// ═══════════════════════════════════════════════════════════════

/** 评分条 rating_bar — value 0-5 */
@Composable
fun RatingBarRenderer(c: UIComponent, ctx: RenderContext) {
    var rating by remember(c.id) { mutableStateOf(c.properties.flt("value", 5f).coerceIn(0f, 5f)) }
    val max = c.properties.int("max", 5).coerceIn(1, 10)
    val starColor = c.properties.color("color", ctx.theme.colorScheme.primary)
    Row(verticalAlignment = Alignment.CenterVertically) {
        repeat(max) { i ->
            Text(
                if (i < rating.toInt()) "★" else "☆",
                fontSize = 24.sp,
                color = if (i < rating.toInt()) starColor else Color.LightGray,
                modifier = Modifier
                    .minimumInteractiveComponentSize().clickable(
                        interactionSource = androidx.compose.foundation.interaction.MutableInteractionSource(),
                        indication = null
                    ) { rating = (i + 1).toFloat() }
                    .padding(2.dp)
            )
        }
    }
}

/** 滑块 slider — value/min/max */
@Composable
fun SliderRenderer(c: UIComponent, ctx: RenderContext) {
    var v by remember(c.id) {
        mutableStateOf(c.properties.flt("value", 50f).coerceIn(c.properties.flt("min", 0f), c.properties.flt("max", 100f)))
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
        Slider(
            value = v,
            onValueChange = { v = it },
            valueRange = c.properties.flt("min", 0f)..c.properties.flt("max", 100f)
        )
        Text("${v.toInt()}", style = MaterialTheme.typography.labelSmall, modifier = Modifier.align(Alignment.End))
    }
}

/** 开关 switch_toggle */
@Composable
fun SwitchToggleRenderer(c: UIComponent, ctx: RenderContext) {
    var on by remember(c.id) { mutableStateOf(c.properties.bool("value", true)) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(c.properties.str("label"), Modifier.weight(1f))
        Switch(checked = on, onCheckedChange = { on = it })
    }
}

/** 复选项 checkbox_item */
@Composable
fun CheckboxItemRenderer(c: UIComponent, ctx: RenderContext) {
    var checked by remember(c.id) { mutableStateOf(c.properties.bool("value", false)) }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Checkbox(checked = checked, onCheckedChange = { checked = it })
        Text(c.properties.str("label"))
    }
}

/** 步进器 stepper — value/step/min/max */
@Composable
fun StepperRenderer(c: UIComponent, ctx: RenderContext) {
    val step = c.properties.flt("step", 1f)
    var v by remember(c.id) { mutableStateOf(c.properties.flt("value", 1f)) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.background(
            MaterialTheme.colorScheme.surfaceVariant,
            RoundedCornerShape(20.dp)
        )
    ) {
        Text(
            "−", fontSize = 24.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier
                .minimumInteractiveComponentSize().clickable { v = (v - step).coerceAtLeast(c.properties.flt("min", 0f)) }
                .padding(horizontal = 16.dp, vertical = 6.dp)
        )
        Text("${v.toInt()}", modifier = Modifier.padding(horizontal = 8.dp), fontWeight = FontWeight.Bold)
        Text(
            "+", fontSize = 24.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier
                .minimumInteractiveComponentSize().clickable { v = (v + step).coerceAtMost(c.properties.flt("max", 99f)) }
                .padding(horizontal = 16.dp, vertical = 6.dp)
        )
    }
}

/** 倒计时 countdown_timer — seconds + 环形进度 */
@Composable
fun CountdownTimerRenderer(c: UIComponent, ctx: RenderContext) {
    val total = c.properties.int("totalSeconds", 60).coerceAtLeast(1)
    val remain = c.properties.int("seconds", total).coerceIn(0, total)
    val frac = remain.toFloat() / total
    val ringColor = c.properties.color("color", MaterialTheme.colorScheme.primary)
    Box(Modifier.size(110.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            drawArc(Color.LightGray.copy(alpha = 0.3f), -90f, 360f, false, style = Stroke(16f, cap = StrokeCap.Round))
            drawArc(ringColor, -90f, 360f * frac, false, style = Stroke(16f, cap = StrokeCap.Round))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                String.format("%02d:%02d", remain / 60, remain % 60),
                fontWeight = FontWeight.Bold, fontSize = 20.sp
            )
            Text(c.properties.str("label", "剩余时间"), style = MaterialTheme.typography.labelSmall)
        }
    }
}

/** 筛选 chips chip_filter — options:[..] */
@Composable
fun ChipFilterRenderer(c: UIComponent, ctx: RenderContext) {
    var selected by remember(c.id) { mutableStateOf(c.properties.int("selectedIndex", 0)) }
    val options = (c.properties["options"] as? kotlinx.serialization.json.JsonArray)
        ?.mapNotNull { (it as? JsonPrimitive)?.content } ?: emptyList()
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        options.forEachIndexed { i, opt ->
            val sel = i == selected
            Text(
                opt,
                color = if (sel) Color.White else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(
                        if (sel) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceVariant
                    )
                    .minimumInteractiveComponentSize().clickable { selected = i }
                    .padding(horizontal = 14.dp, vertical = 7.dp)
            )
        }
    }
}

/** 徽标 badge — count/label */
@Composable
fun BadgeRenderer(c: UIComponent, ctx: RenderContext) {
    val count = c.properties.int("count", 0)
    val label = c.properties.str("label", if (count > 99) "99+" else if (count > 0) "$count" else "")
    if (label.isEmpty()) return
    Box(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(c.properties.color("color", ctx.theme.colorScheme.error))
            .padding(horizontal = 8.dp, vertical = 2.dp)
    ) {
        Text(label, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}

// ═══════════════════════════════════════════════════════════════
// 三、动漫 / 人物 / 美术 Character & Art（8 个）
// ═══════════════════════════════════════════════════════════════

/** 头像 avatar — emoji/name/gradient */
@Composable
fun AvatarRenderer(c: UIComponent, ctx: RenderContext) {
    val emoji = c.properties.str("emoji", "🙂")
    val name = c.properties.str("name")
    val sizeDp = (c.style.height?.let { 56f } ?: 56f).coerceIn(28f, 200f)
    val ring = c.properties.color("color", MaterialTheme.colorScheme.primary)
    Box(
        Modifier
            .size(sizeDp.dp)
            .border(3.dp, Brush.linearGradient(listOf(ring, ring.copy(alpha = 0.4f))), CircleShape)
            .padding(3.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center
    ) {
        Text(
            emoji.ifEmpty { name.take(1).ifEmpty { "?" } },
            fontSize = (sizeDp * 0.5f).sp
        )
    }
}

/** 头像组 avatar_group — emojis:[..] */
@Composable
fun AvatarGroupRenderer(c: UIComponent, ctx: RenderContext) {
    val emojis = (c.properties["emojis"] as? kotlinx.serialization.json.JsonArray)
        ?.mapNotNull { (it as? JsonPrimitive)?.content } ?: emptyList()
    Row {
        emojis.take(5).forEachIndexed { i, e ->
            Box(
                Modifier
                    .padding(start = if (i > 0) (-10).dp else 0.dp)
                    .size(40.dp)
                    .border(2.dp, MaterialTheme.colorScheme.surface, CircleShape)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Text(e, fontSize = 20.sp)
            }
        }
    }
}

/** 角色卡 character_card — 动漫风角色卡片 */
@Composable
fun CharacterCardRenderer(c: UIComponent, ctx: RenderContext) {
    val emoji = c.properties.str("emoji", "🧙")
    val name = c.properties.str("name", "神秘角色")
    val title = c.properties.str("title", "")
    val mood = c.properties.str("mood", "平静")
    val c1 = c.properties.color("gradientStart", ctx.theme.colorScheme.primary)
    val c2 = c.properties.color("gradientEnd", ctx.theme.colorScheme.primary)
    Surface(
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Box(
            Modifier
                .background(Brush.linearGradient(listOf(c1, c2)))
                .padding(18.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(emoji, fontSize = 40.sp)
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(name, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    if (title.isNotEmpty()) {
                        Text(title, style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.85f))
                    }
                    Spacer(Modifier.height(6.dp))
                    Text("✦ $mood", style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.8f))
                }
            }
        }
    }
}

/** 心情徽章 mood_badge */
@Composable
fun MoodBadgeRenderer(c: UIComponent, ctx: RenderContext) {
    val emoji = c.properties.str("emoji", "✨")
    val label = c.properties.str("label", "心情")
    Surface(
        shape = RoundedCornerShape(50),
        color = c.properties.color("color", MaterialTheme.colorScheme.secondaryContainer)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            Text(emoji, fontSize = 16.sp)
            Spacer(Modifier.width(6.dp))
            Text(label, style = MaterialTheme.typography.labelMedium)
        }
    }
}

/** 渐变光球 gradient_orb — 装饰性美术元素 */
@Composable
fun GradientOrbRenderer(c: UIComponent, ctx: RenderContext) {
    val c1 = c.properties.color("from", ctx.theme.colorScheme.info)
    val c2 = c.properties.color("to", ctx.theme.colorScheme.primary)
    val sizeDp = (c.style.height?.let { 120f } ?: 120f).coerceIn(40f, 400f)
    Canvas(Modifier.size(sizeDp.dp)) {
        drawCircle(Brush.radialGradient(listOf(c1, c2.copy(alpha = 0.6f), Color.Transparent)))
        drawCircle(c1, radius = size.minDimension * 0.25f, center = Offset(size.width * 0.4f, size.height * 0.35f))
    }
}

/** 贴纸大表情 sticker_emoji */
@Composable
fun StickerEmojiRenderer(c: UIComponent, ctx: RenderContext) {
    val emoji = c.properties.str("emoji", "🎉")
    val rot = c.properties.flt("rotate", -8f).coerceIn(-30f, 30f)
    val sizeDp = (c.style.height?.let { 96f } ?: 96f).coerceIn(40f, 240f)
    Box(Modifier.size(sizeDp.dp), contentAlignment = Alignment.Center) {
        Text(emoji, fontSize = sizeDp.sp, modifier = Modifier.rotate(rot))
    }
}

/** 勋章 rank_medal — rank 决定配色 */
@Composable
fun RankMedalRenderer(c: UIComponent, ctx: RenderContext) {
    val rank = c.properties.int("rank", 1)
    val label = c.properties.str("label", "Lv.$rank")
    val color = when (rank) {
        1 -> ctx.theme.colorScheme.primary
        2 -> ctx.theme.colorScheme.info
        3 -> ctx.theme.colorScheme.primary
        else -> MaterialTheme.colorScheme.primary
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(64.dp)
                .clip(RoundedCornerShape(topStart = 50.dp, topEnd = 50.dp, bottomStart = 12.dp, bottomEnd = 12.dp))
                .background(Brush.verticalGradient(listOf(color, color.copy(alpha = 0.6f)))),
            contentAlignment = Alignment.Center
        ) {
            Text(if (rank <= 3) "$rank" else label.take(2), fontSize = 24.sp, fontWeight = FontWeight.Black, color = Color.White)
        }
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

/** 气泡对话 speech_bubble — 带尾巴的气泡 */
@Composable
fun SpeechBubbleRenderer(c: UIComponent, ctx: RenderContext) {
    val text = c.properties.str("text", "……")
    val from = c.properties.str("role", "left")
    val bubbleColor = c.properties.color("color", MaterialTheme.colorScheme.primaryContainer)
    Column(horizontalAlignment = if (from == "right") Alignment.End else Alignment.Start) {
        Surface(color = bubbleColor, shape = RoundedCornerShape(14.dp)) {
            Text(text, Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
        }
        Canvas(Modifier.size(18.dp, 12.dp).padding(start = if (from == "right") 0.dp else 12.dp, end = if (from == "right") 12.dp else 0.dp)) {
            val path = Path().apply {
                moveTo(0f, 0f)
                lineTo(size.width, 0f)
                lineTo(if (from == "right") size.width * 0.7f else size.width * 0.3f, size.height)
                close()
            }
            drawPath(path, bubbleColor)
        }
        RenderChildren(c.children, ctx)
    }
}

// ═══════════════════════════════════════════════════════════════
// 四、布局 / 媒体 Layout & Media（4 个）
// ═══════════════════════════════════════════════════════════════

/** 主视觉横幅 banner_hero */
@Composable
fun BannerHeroRenderer(c: UIComponent, ctx: RenderContext) {
    val title = c.properties.str("title", "")
    val subtitle = c.properties.str("subtitle", "")
    val cta = c.properties.str("cta", "")
    val c1 = c.properties.color("gradientStart", ctx.theme.colorScheme.info)
    val c2 = c.properties.color("gradientEnd", ctx.theme.colorScheme.primary)
    val click = ctx.clickHandler(c)
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Brush.linearGradient(listOf(c1, c2)))
            .minimumInteractiveComponentSize().clickable(enabled = click != null) { click?.invoke() }
            .padding(20.dp)
    ) {
        Column {
            if (title.isNotEmpty()) Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White)
            if (subtitle.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.9f))
            }
            if (cta.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text(
                    cta, color = c1, fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(Color.White)
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
        }
    }
}

/** 毛玻璃卡 glass_card */
@Composable
fun GlassCardRenderer(c: UIComponent, ctx: RenderContext) {
    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.65f),
        shape = RoundedCornerShape(20.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.4f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp)) { RenderChildren(c.children, ctx) }
    }
}

/** 音频波形 audio_wave — data 驱动竖条 */
@Composable
fun AudioWaveRenderer(c: UIComponent, ctx: RenderContext) {
    val waveColor = c.properties.color("color", MaterialTheme.colorScheme.primary)
    val seed = c.properties.str("track", "wave").hashCode()
    Row(
        Modifier.fillMaxWidth().height(48.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(28) { i ->
            val frac = ((seed * (i + 7)) % 100) / 100f
            Box(
                Modifier
                    .width(5.dp)
                    .height((12f + frac * 36f).dp)
                    .background(waveColor.copy(alpha = 0.4f + frac * 0.6f), RoundedCornerShape(6.dp))
            )
        }
    }
}

/** 分段进度 timer_progress — segments */
@Composable
fun TimerProgressRenderer(c: UIComponent, ctx: RenderContext) {
    val total = c.properties.int("total", 8).coerceIn(1, 30)
    val done = c.properties.int("done", 0).coerceIn(0, total)
    val fillColor = c.properties.color("color", MaterialTheme.colorScheme.primary)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        repeat(total) { i ->
            Box(
                Modifier
                    .weight(1f)
                    .height(6.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (i < done) fillColor else Color.LightGray.copy(alpha = 0.4f))
            )
        }
    }
}
