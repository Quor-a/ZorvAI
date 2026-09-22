package com.ai.assistance.quro.genui.sdk.components

import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.assistance.quro.genui.sdk.dsl.UIComponent
import com.ai.assistance.quro.genui.sdk.render.RenderChildren
import com.ai.assistance.quro.genui.sdk.render.RenderContext
import com.ai.assistance.quro.genui.sdk.style.ColorParser
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

// ── 文件内私有取值助手 ──
private fun JsonObject.s(key: String, def: String? = null): String? = (this[key] as? JsonPrimitive)?.content ?: def
private fun JsonObject.f(key: String, def: Float = 0f): Float = (this[key] as? JsonPrimitive)?.content?.toFloatOrNull() ?: def
private fun JsonObject.i(key: String, def: Int = 0): Int = (this[key] as? JsonPrimitive)?.content?.toIntOrNull() ?: def
private fun JsonObject.col(key: String, def: Color): Color = ColorParser.toColor(s(key), def)
private fun JsonObject.listS(key: String): List<String> =
    ((this[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }) ?: emptyList()

// ═══════════════ 五、办公 / 效率（4 个） ═══════════════

/** todo_item — 待办事项行（勾选态样式切换） */
@Composable
fun TodoItemRenderer(c: UIComponent, ctx: RenderContext) {
    val text = c.properties.s("text", "") ?: ""
    val done = (c.properties.s("value") ?: c.properties.s("done")) == "true"
    Row(
        Modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp))
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(20.dp).clip(CircleShape)
                .background(if (done) MaterialTheme.colorScheme.primary else Color.Transparent)
                .border(2.dp, if (done) MaterialTheme.colorScheme.primary else Color.LightGray, CircleShape),
            contentAlignment = Alignment.Center
        ) { if (done) Text("✓", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Black) }
        Text(
            text, fontSize = 14.sp,
            textDecoration = if (done) androidx.compose.ui.text.style.TextDecoration.LineThrough else null,
            color = if (done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(start = 10.dp).weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis
        )
    }
}

/** kanban_column — 看板列（标题+卡片堆叠） */
@Composable
fun KanbanColumnRenderer(c: UIComponent, ctx: RenderContext) {
    val title = c.properties.s("title", "待办") ?: ""
    val accent = c.properties.col("color", MaterialTheme.colorScheme.primary)
    Column(
        Modifier.width(160.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(14.dp))
            .padding(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).background(accent, CircleShape))
            Text(title, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 6.dp))
        }
        Spacer(Modifier.height(8.dp))
        if (c.children.isNotEmpty()) RenderChildren(c.children, ctx)
        else c.properties.listS("cards").forEach { card ->
            Text(
                card, fontSize = 12.sp,
                modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)
                    .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(8.dp))
                    .padding(8.dp), maxLines = 2, overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** meeting_card — 会议卡（时间块+主题+参与人） */
@Composable
fun MeetingCardRenderer(c: UIComponent, ctx: RenderContext) {
    val time = c.properties.s("time", "10:00") ?: ""
    val title = c.properties.s("title", "会议") ?: ""
    val members = c.properties.listS("members")
    val accent = c.properties.col("color", MaterialTheme.colorScheme.primary)
    Row(
        Modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
            .padding(10.dp)
    ) {
        Box(Modifier.width(4.dp).height(46.dp).background(accent, RoundedCornerShape(2.dp)))
        Column(Modifier.padding(start = 10.dp).weight(1f)) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(time, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (members.isNotEmpty()) Text(
                members.joinToString(" ") { it }, fontSize = 12.sp,
                modifier = Modifier.padding(top = 4.dp), maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** calendar_strip — 周历条（7 天高亮今天） */
@Composable
fun CalendarStripRenderer(c: UIComponent, ctx: RenderContext) {
    val today = c.properties.i("today", 0).coerceIn(0, 6)
    val days = listOf("一", "二", "三", "四", "五", "六", "日")
    val startDay = c.properties.i("startDay", 1)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        days.forEachIndexed { i, d ->
            val isToday = i == today
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier.size(36.dp)
                        .background(if (isToday) MaterialTheme.colorScheme.primary else Color.Transparent, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "${startDay + i}", fontSize = 14.sp,
                        fontWeight = if (isToday) FontWeight.Black else FontWeight.Normal,
                        color = if (isToday) Color.White else MaterialTheme.colorScheme.onSurface
                    )
                }
                Text(d, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

// ═══════════════ 六、电商（4 个） ═══════════════

/** coupon_ticket — 优惠券票券（缺口+虚线） */
@Composable
fun CouponTicketRenderer(c: UIComponent, ctx: RenderContext) {
    val value = c.properties.s("value", "¥20") ?: ""
    val label = c.properties.s("label", "满100可用") ?: ""
    Row(
        Modifier.fillMaxWidth()
            .background(Brush.horizontalGradient(listOf(Color(0xFFEF4444), Color(0xFFF97316))), RoundedCornerShape(10.dp))
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(value, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Black, modifier = Modifier.padding(start = 16.dp))
        Spacer(Modifier.width(14.dp))
        Box(Modifier.width(1.dp).height(30.dp)) {
            repeat(6) { i -> Box(Modifier.padding(bottom = (i * 5).dp).width(1.dp).height(3.dp).background(Color.White.copy(alpha = 0.8f))) }
        }
        Text(label, color = Color.White, fontSize = 13.sp, modifier = Modifier.padding(start = 12.dp).weight(1f))
        Text("领取", color = Color(0xFFEF4444), fontSize = 13.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(end = 12.dp).background(Color.White, RoundedCornerShape(50)).padding(horizontal = 12.dp, vertical = 5.dp))
    }
}

/** flash_sale_strip — 秒杀横条（价格+倒计时+进度） */
@Composable
fun FlashSaleRenderer(c: UIComponent, ctx: RenderContext) {
    val price = c.properties.s("price", "¥0") ?: ""
    val orig = c.properties.s("originalPrice", "") ?: ""
    val time = c.properties.s("timeLeft", "01:59:59") ?: ""
    val sold = c.properties.f("sold", 0.7f).coerceIn(0f, 1f)
    Column(
        Modifier.fillMaxWidth()
            .background(Brush.horizontalGradient(listOf(Color(0xFFDC2626), Color(0xFFEA580C))), RoundedCornerShape(12.dp))
            .padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("⚡ 限时秒杀", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Text("⏰ $time", color = Color(0xFFFEF08A), fontSize = 13.sp, fontWeight = FontWeight.Black)
        }
        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.Bottom) {
            Text(price, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Black)
            if (orig.isNotBlank()) Text(
                orig, color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp,
                textDecoration = androidx.compose.ui.text.style.TextDecoration.LineThrough,
                modifier = Modifier.padding(start = 8.dp, bottom = 3.dp)
            )
            Spacer(Modifier.weight(1f))
            Box(Modifier.width(90.dp).height(8.dp).background(Color.White.copy(alpha = 0.35f), RoundedCornerShape(4.dp))) {
                Box(Modifier.wFraction2(sold).height(8.dp).background(Color(0xFFFEF08A), RoundedCornerShape(4.dp)))
            }
        }
    }
}

/** review_row — 评价行（星级+内容+头像） */
@Composable
fun ReviewRowRenderer(c: UIComponent, ctx: RenderContext) {
    val name = c.properties.s("name", "用户") ?: ""
    val stars = c.properties.i("stars", 5).coerceIn(1, 5)
    val text = c.properties.s("text", "") ?: ""
    val emoji = c.properties.s("emoji", "🙂") ?: ""
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Box(Modifier.size(32.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f), CircleShape), contentAlignment = Alignment.Center) {
            Text(emoji, fontSize = 15.sp)
        }
        Column(Modifier.weight(1f).padding(start = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(name, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.width(6.dp))
                Text("★".repeat(stars), color = Color(0xFFF59E0B), fontSize = 11.sp)
            }
            Text(text, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 18.sp)
        }
    }
}

/** shipping_track — 物流轨迹（节点条） */
@Composable
fun ShippingTrackRenderer(c: UIComponent, ctx: RenderContext) {
    val steps = c.properties.listS("steps").ifEmpty { listOf("下单", "发货", "运输", "签收") }
    val current = c.properties.i("current", 0).coerceIn(0, steps.size - 1)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        steps.forEachIndexed { i, step ->
            val done = i <= current
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                Box(
                    Modifier.size(18.dp)
                        .background(if (done) MaterialTheme.colorScheme.primary else Color.LightGray.copy(alpha = 0.6f), CircleShape)
                ) { if (done) Text("✓", color = Color.White, fontSize = 10.sp, modifier = Modifier.align(Alignment.Center)) }
                Text(step, fontSize = 10.sp, color = if (done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
            if (i < steps.size - 1) Box(Modifier.weight(0.6f).height(2.dp).background(if (i < current) MaterialTheme.colorScheme.primary else Color.LightGray.copy(alpha = 0.6f)))
        }
    }
}

// ═══════════════ 七、健康 / 生活方式（4 个） ═══════════════

/** activity_rings — 三环活动（圆环叠放） */
@Composable
fun ActivityRingsRenderer(c: UIComponent, ctx: RenderContext) {
    val move = c.properties.f("move", 0.6f).coerceIn(0f, 1f)
    val exercise = c.properties.f("exercise", 0.4f).coerceIn(0f, 1f)
    val stand = c.properties.f("stand", 0.8f).coerceIn(0f, 1f)
    Box(Modifier.size(120.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val specs = listOf(
                Triple(move, Color(0xFFEF4444), size.minDimension * 0.46f),
                Triple(exercise, Color(0xFF22C55E), size.minDimension * 0.36f),
                Triple(stand, Color(0xFF3B82F6), size.minDimension * 0.26f)
            )
            specs.forEach { (frac, col, radius) ->
                drawCircle(col.copy(alpha = 0.2f), radius, style = androidx.compose.ui.graphics.drawscope.Stroke(10.dp.toPx()))
                drawArc(
                    col, -90f, 360f * frac, false,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(10.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round)
                )
            }
        }
    }
}

/** step_counter — 步数卡（大数字+目标进度） */
@Composable
fun StepCounterRenderer(c: UIComponent, ctx: RenderContext) {
    val steps = c.properties.i("steps", 0)
    val goal = c.properties.i("goal", 8000).coerceAtLeast(1)
    val frac = (steps.toFloat() / goal).coerceIn(0f, 1f)
    Column(
        Modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(16.dp))
            .padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text("👟", fontSize = 24.sp)
            Text(
                "%,d".format(steps).replace(",", ","),
                fontSize = 28.sp, fontWeight = FontWeight.Black, modifier = Modifier.padding(start = 8.dp)
            )
            Text("步 / $goal", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 6.dp, bottom = 5.dp))
        }
        Box(Modifier.fillMaxWidth().padding(top = 10.dp).height(8.dp).background(Color.LightGray.copy(alpha = 0.4f), RoundedCornerShape(4.dp))) {
            Box(Modifier.wFraction2(frac).height(8.dp).background(Brush.horizontalGradient(listOf(Color(0xFF3B82F6), Color(0xFF22C55E))), RoundedCornerShape(4.dp)))
        }
    }
}

/** water_track — 喝水打卡（8 杯小水滴） */
@Composable
fun WaterTrackRenderer(c: UIComponent, ctx: RenderContext) {
    val done = c.properties.i("done", 0).coerceIn(0, 8)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(8) { i ->
            Box(
                Modifier.weight(1f).height(34.dp)
                    .background(
                        if (i < done) Brush.verticalGradient(listOf(Color(0xFF60A5FA), Color(0xFF3B82F6)))
                        else Brush.verticalGradient(listOf(Color.LightGray.copy(alpha = 0.4f), Color.LightGray.copy(alpha = 0.3f))),
                        RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp, topStart = 4.dp, topEnd = 4.dp)
                    ),
                contentAlignment = Alignment.Center
            ) { if (i < done) Text("💧", fontSize = 12.sp) }
        }
    }
}

/** calorie_ring — 卡路里环（中心数值） */
@Composable
fun CalorieRingRenderer(c: UIComponent, ctx: RenderContext) {
    val value = c.properties.i("value", 0)
    val goal = c.properties.i("goal", 2000).coerceAtLeast(1)
    val frac = (value.toFloat() / goal).coerceIn(0f, 1f)
    val accent = c.properties.col("color", Color(0xFFF97316))
    Box(Modifier.size(110.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            drawCircle(accent.copy(alpha = 0.15f), style = androidx.compose.ui.graphics.drawscope.Stroke(12.dp.toPx()))
            drawArc(accent, -90f, 360f * frac, false, style = androidx.compose.ui.graphics.drawscope.Stroke(12.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("$value", fontSize = 24.sp, fontWeight = FontWeight.Black)
            Text("/ $goal 千卡", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ═══════════════ 八、金融（3 个） ═══════════════

/** candle_chart — K 线图（红涨绿跌） */
@Composable
fun CandleChartRenderer(c: UIComponent, ctx: RenderContext) {
    val data = c.properties.arr("data")
    val up = Color(0xFFEF4444); val down = Color(0xFF22C55E)
    Row(
        Modifier.fillMaxWidth().height(c.properties.f("height", 120f).dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        val list = if (data.isEmpty()) (1..8).map { JsonObject(emptyMap()) } else data
        val maxV = list.maxOfOrNull { it.f("high", 10f) } ?: 10f
        list.forEachIndexed { i, o ->
            val open = o.f("open", 5f + i); val close = o.f("close", 6f + i)
            val high = o.f("high", 8f + i); val low = o.f("low", 4f + i)
            val bull = close >= open
            val col = if (bull) up else down
            val top = (high / maxV); val bottom = (low / maxV)
            val bodyTop = (maxOf(open, close) / maxV); val bodyBottom = (minOf(open, close) / maxV)
            Box(Modifier.weight(1f).fillMaxHeightF(1f)) {
                Box(Modifier.align(Alignment.TopCenter).width(2.dp).fillMaxHeightF((top - bottom).coerceAtLeast(0.02f)).padding(top = ((1f - top) * 120).dp).background(col)) {}
                Box(
                    Modifier.align(Alignment.TopCenter).padding(top = ((1f - bodyTop) * 120).dp)
                        .fillMaxWidth().height(((bodyTop - bodyBottom).coerceAtLeast(0.03f) * 120).dp)
                        .background(col, RoundedCornerShape(2.dp))
                ) {}
            }
        }
    }
}

/** price_delta — 涨跌幅（带箭头与配色） */
@Composable
fun PriceDeltaRenderer(c: UIComponent, ctx: RenderContext) {
    val pct = c.properties.f("percent", 0f)
    val upTint = c.properties.col("upColor", Color(0xFFEF4444))
    val downTint = c.properties.col("downColor", Color(0xFF22C55E))
    val up = pct >= 0
    val col = if (up) upTint else downTint
    Text(
        "${if (up) "▲" else "▼"} ${"%.2f".format(kotlin.math.abs(pct))}%",
        color = col, fontSize = 16.sp, fontWeight = FontWeight.Black,
        modifier = Modifier.background(col.copy(alpha = 0.12f), RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 5.dp)
    )
}

/** wallet_card — 钱包卡（余额+渐变卡面） */
@Composable
fun WalletCardRenderer(c: UIComponent, ctx: RenderContext) {
    val balance = c.properties.s("balance", "¥0.00") ?: ""
    val label = c.properties.s("label", "账户余额") ?: ""
    val g1 = c.properties.col("gradientStart", Color(0xFF1E293B))
    val g2 = c.properties.col("gradientEnd", Color(0xFF334155))
    Column(
        Modifier.fillMaxWidth()
            .background(Brush.linearGradient(listOf(g1, g2)), RoundedCornerShape(18.dp))
            .padding(16.dp)
    ) {
        Text(label, color = Color.White.copy(alpha = 0.75f), fontSize = 12.sp)
        Text(balance, color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Black, modifier = Modifier.padding(top = 6.dp))
        Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("充值", "提现", "明细").forEach { action ->
                Text(
                    action, color = Color.White, fontSize = 12.sp,
                    modifier = Modifier.background(Color.White.copy(alpha = 0.18f), RoundedCornerShape(50)).padding(horizontal = 12.dp, vertical = 5.dp)
                )
            }
        }
    }
}

// ═══════════════ 九、教育 / 旅行（3 个） ═══════════════

/** flash_card — 单词卡（正面词+背面释义） */
@Composable
fun FlashCardRenderer(c: UIComponent, ctx: RenderContext) {
    val word = c.properties.s("word", "") ?: ""
    val meaning = c.properties.s("meaning", "") ?: ""
    Column(
        Modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(16.dp))
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f), RoundedCornerShape(16.dp))
            .padding(18.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(word, fontSize = 26.sp, fontWeight = FontWeight.Black)
        if (meaning.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(meaning, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** quiz_option — 选择题选项（A/B/C/D + 正确态） */
@Composable
fun QuizOptionRenderer(c: UIComponent, ctx: RenderContext) {
    val label = c.properties.s("label", "A") ?: ""
    val text = c.properties.s("text", "") ?: ""
    val state = c.properties.s("state", "idle") ?: "idle"
    val (bg, fg, borderC) = when (state) {
        "correct" -> Triple(Color(0xFFDCFCE7), Color(0xFF15803D), Color(0xFF22C55E))
        "wrong" -> Triple(Color(0xFFFEE2E2), Color(0xFFB91C1C), Color(0xFFEF4444))
        "selected" -> Triple(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f), MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.primary)
        else -> Triple(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurface, Color.Transparent)
    }
    Row(
        Modifier.fillMaxWidth()
            .background(bg, RoundedCornerShape(12.dp))
            .border(1.5.dp, borderC, RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 15.sp, fontWeight = FontWeight.Black, color = fg)
        Text(text, fontSize = 14.sp, modifier = Modifier.padding(start = 10.dp).weight(1f))
        if (state == "correct") Text("✓", color = fg, fontWeight = FontWeight.Black)
        if (state == "wrong") Text("✗", color = fg, fontWeight = FontWeight.Black)
    }
}

/** boarding_pass — 登机牌/车票（双段式票面） */
@Composable
fun BoardingPassRenderer(c: UIComponent, ctx: RenderContext) {
    val from = c.properties.s("from", "上海") ?: ""
    val to = c.properties.s("to", "东京") ?: ""
    val code = c.properties.s("code", "MU 523") ?: ""
    val gate = c.properties.s("gate", "A12") ?: ""
    val seat = c.properties.s("seat", "32F") ?: ""
    val time = c.properties.s("time", "09:30") ?: ""
    Column(
        Modifier.fillMaxWidth()
            .background(Color.White, RoundedCornerShape(16.dp))
            .border(1.dp, Color(0xFFE2E8F0), RoundedCornerShape(16.dp))
    ) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column { Text(from, fontSize = 22.sp, fontWeight = FontWeight.Black); Text(time, fontSize = 11.sp, color = Color(0xFF64748B)) }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("✈ ─────────", fontSize = 12.sp, color = Color(0xFF94A3B8))
                Text(code, fontSize = 11.sp, color = Color(0xFF64748B))
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) { Text(to, fontSize = 22.sp, fontWeight = FontWeight.Black) }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFFE2E8F0)))
        Row(Modifier.fillMaxWidth().padding(12.dp)) {
            listOf("登机口" to gate, "座位" to seat).forEach { (k, v) ->
                Column(Modifier.weight(1f)) {
                    Text(k, fontSize = 10.sp, color = Color(0xFF94A3B8))
                    Text(v, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color(0xFF0F172A))
                }
            }
            Text("🎫", fontSize = 20.sp)
        }
    }
}

// ── 私有尺寸助手 ──
private fun JsonObject.arr(key: String): List<JsonObject> =
    ((this[key] as? JsonArray)?.filterIsInstance<JsonObject>()) ?: emptyList()

private fun Modifier.wFraction2(f: Float): Modifier = this.then(Modifier.fillMaxWidth(f.coerceIn(0f, 1f)))
private fun Modifier.fillMaxHeightF(f: Float): Modifier = this.then(Modifier.fillMaxHeight(f.coerceIn(0f, 1f)))
