package com.ai.assistance.quro.genui.sdk.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.border
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
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
import kotlin.math.abs
import kotlin.math.sin

// ── 文件内私有取值助手 ──
private fun JsonObject.str2(key: String, def: String? = null): String? = (this[key] as? JsonPrimitive)?.content ?: def
private fun JsonObject.flt2(key: String, def: Float = 0f): Float = (this[key] as? JsonPrimitive)?.content?.toFloatOrNull() ?: def
private fun JsonObject.int2(key: String, def: Int = 0): Int = (this[key] as? JsonPrimitive)?.content?.toIntOrNull() ?: def
private fun JsonObject.color2(key: String, def: Color): Color = ColorParser.toColor(str2(key), def)
private fun JsonObject.arr2(key: String): List<JsonObject> =
    ((this[key] as? JsonArray)?.mapNotNull { it as? JsonObject }) ?: emptyList()
private fun JsonObject.items2(key: String): List<String> =
    ((this[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }) ?: emptyList()

// ═══════════════ 一、漫画 / 叙事（5 个） ═══════════════

/** comic_panel — 分镜格：粗黑边框 + 内容 + 可选序号 */
@Composable
fun ComicPanelRenderer(c: UIComponent, ctx: RenderContext) {
    val edge = c.properties.color2("edge", Color.Black)
    val no = c.properties.int2("panel", 0)
    Box(
        Modifier
            .fillMaxWidth()
            .border(3.dp, edge, RoundedCornerShape(8.dp))
            .clip(RoundedCornerShape(6.dp))
    ) {
        if (c.children.isNotEmpty()) RenderChildren(c.children, ctx)
        else Text(
            c.properties.str2("text", "") ?: "",
            modifier = Modifier.padding(12.dp),
            fontSize = 15.sp, lineHeight = 22.sp
        )
        if (no > 0) Box(
            Modifier
                .align(Alignment.TopStart)
                .padding(6.dp)
                .size(24.dp)
                .background(Color.Black, CircleShape),
            contentAlignment = Alignment.Center
        ) { Text("$no", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Black) }
    }
}

/** caption_box — 漫画旁白框：黄色底黑框 */
@Composable
fun CaptionBoxRenderer(c: UIComponent, ctx: RenderContext) {
    Box(
        Modifier
            .fillMaxWidth()
            .background(Color(0xFFFDE68A), RoundedCornerShape(4.dp))
            .border(2.dp, Color.Black, RoundedCornerShape(4.dp))
            .padding(10.dp)
    ) {
        Text(
            c.properties.str2("text", "") ?: "",
            fontSize = c.properties.flt2("textSize", 14f).sp,
            fontWeight = FontWeight.SemiBold, lineHeight = 20.sp
        )
    }
}

/** action_lines — 集中线（冲击/强调），中心可放 emoji */
@Composable
fun ActionLinesRenderer(c: UIComponent, ctx: RenderContext) {
    val accent = c.properties.color2("color", Color.Black)
    Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val cx = size.width / 2; val cy = size.height / 2
            val inner = size.minDimension * 0.22f
            for (i in 0 until 28) {
                val ang = i * (2.0 * Math.PI / 28)
                val spread = 0.035 * (if (i % 2 == 0) 1.0 else 0.55)
                val x1 = cx + inner * kotlin.math.cos(ang - spread)
                val y1 = cy + inner * kotlin.math.sin(ang - spread)
                val x2 = cx + size.maxDimension * kotlin.math.cos(ang + spread)
                val y2 = cy + size.maxDimension * kotlin.math.sin(ang + spread)
                drawLine(accent, Offset(x1.toFloat(), y1.toFloat()), Offset(x2.toFloat(), y2.toFloat()), 2.dp.toPx())
            }
        }
        Text(c.properties.str2("emoji", "💥") ?: "", fontSize = 40.sp)
    }
}

/** panel_strip — 条漫横滑（横排分镜） */
@Composable
fun PanelStripRenderer(c: UIComponent, ctx: RenderContext) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        c.children.forEach { child ->
            Box(Modifier.width(200.dp)) { RenderChildren(listOf(child), ctx) }
        }
        if (c.children.isEmpty()) {
            c.properties.items2("panels").forEach { p ->
                Box(
                    Modifier
                        .width(200.dp)
                        .height(140.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp))
                        .border(2.dp, Color.Black, RoundedCornerShape(10.dp)),
                    contentAlignment = Alignment.Center
                ) { Text(p, fontSize = 28.sp) }
            }
        }
    }
}

/** manga_bubble — 对话气球（椭圆气泡） */
@Composable
fun MangaBubbleRenderer(c: UIComponent, ctx: RenderContext) {
    val bg = c.properties.color2("bg", Color.White)
    Column(horizontalAlignment = Alignment.Start) {
        Box(
            Modifier
                .background(bg, RoundedCornerShape(50))
                .border(2.dp, Color.Black, RoundedCornerShape(50))
                .padding(horizontal = 16.dp, vertical = 10.dp)
        ) {
            Text(c.properties.str2("text", "") ?: "", fontSize = 14.sp, fontWeight = FontWeight.Medium)
        }
        Canvas(Modifier.size(width = 22.dp, height = 16.dp)) {
            drawLine(Color.Black, Offset(4f, 0f), Offset(2f, size.height), 3f)
            drawLine(Color.Black, Offset(4f, 0f), Offset(10f, size.height), 3f)
        }
    }
}

// ═══════════════ 二、粒子 / 特效（5 个，持续动画） ═══════════════

/** particle_burst — 粒子迸发（循环动画） */
@Composable
fun ParticleBurstRenderer(c: UIComponent, ctx: RenderContext) {
    val base = c.properties.color2("color", MaterialTheme.colorScheme.primary)
    val count = c.properties.int2("count", 14).coerceIn(4, 40)
    val phase by rememberInfiniteTransition(label = "pb").animateFloat(
        0f, 1f, infiniteRepeatable(tween(1400, easing = LinearEasing)), label = "p"
    )
    Canvas(Modifier.fillMaxWidth().height(c.properties.flt2("height", 140f).dp)) {
        val cx = size.width / 2; val cy = size.height / 2
        val maxR = size.minDimension * 0.48f
        val t = phase
        for (i in 0 until count) {
            val ang = i * (2.0 * Math.PI / count) + i * 0.13
            val dist = maxR * t
            val alpha = (1f - t).coerceIn(0f, 1f)
            val r = 3.dp.toPx() * (1f - t * 0.6f) + 1f
            drawCircle(
                base.copy(alpha = alpha), r,
                Offset(cx + dist * kotlin.math.cos(ang).toFloat(), cy + dist * kotlin.math.sin(ang).toFloat())
            )
        }
    }
}

/** confetti_field — 彩带飘落 */
@Composable
fun ConfettiFieldRenderer(c: UIComponent, ctx: RenderContext) {
    val colors = listOf(Color(0xFFF43F5E), Color(0xFF22C55E), Color(0xFF3B82F6), Color(0xFFF59E0B), Color(0xFFA855F7))
    val phase by rememberInfiniteTransition(label = "cf").animateFloat(
        0f, 1f, infiniteRepeatable(tween(2200, easing = LinearEasing)), label = "p"
    )
    val count = c.properties.int2("count", 22).coerceIn(6, 60)
    Canvas(Modifier.fillMaxWidth().height(c.properties.flt2("height", 160f).dp)) {
        for (i in 0 until count) {
            val seed = i * 97.31f
            val x = ((seed * 7.13f) % size.width + size.width) % size.width
            val speed = 0.35f + ((seed * 3.7f) % 0.65f)
            val y = ((phase * speed + (seed % 1f)) % 1f) * size.height
            val w = 4.dp.toPx() + (seed % 3f) * 2f
            val rot = phase * 360f + seed
            drawCircle(colors[i % colors.size], w, Offset(x, y))
        }
    }
}

/** sparkle_rain — 星光闪烁 */
@Composable
fun SparkleRainRenderer(c: UIComponent, ctx: RenderContext) {
    val tint = c.properties.color2("color", Color(0xFFFDE047))
    val phase by rememberInfiniteTransition(label = "sp").animateFloat(
        0f, 1f, infiniteRepeatable(tween(1200, easing = LinearEasing), RepeatMode.Reverse), label = "p"
    )
    Canvas(Modifier.fillMaxWidth().height(c.properties.flt2("height", 100f).dp)) {
        for (i in 0 until 18) {
            val seed = i * 53.77f
            val x = ((seed * 11.7f) % size.width + size.width) % size.width
            val y = ((seed * 29.3f) % size.height + size.height) % size.height
            val a = 0.25f + 0.75f * abs(sin(phase * 6.28f + i))
            drawCircle(tint.copy(alpha = a), (1.5f + (seed % 2.2f)).dp.toPx(), Offset(x, y))
        }
    }
}

/** rain_effect — 雨滴下落（天气氛围） */
@Composable
fun RainEffectRenderer(c: UIComponent, ctx: RenderContext) {
    val tint = c.properties.color2("color", Color(0xFF93C5FD))
    val phase by rememberInfiniteTransition(label = "rn").animateFloat(
        0f, 1f, infiniteRepeatable(tween(700, easing = LinearEasing)), label = "p"
    )
    Canvas(Modifier.fillMaxWidth().height(c.properties.flt2("height", 150f).dp)) {
        for (i in 0 until 26) {
            val seed = i * 41.9f
            val x = ((seed * 13.1f) % size.width + size.width) % size.width
            val speed = 0.5f + (seed % 0.5f)
            val y = ((phase * speed + (seed % 1f)) % 1f) * size.height
            drawLine(tint.copy(alpha = 0.55f), Offset(x, y), Offset(x - 3f, y + 16f), 2.dp.toPx(), StrokeCap.Round)
        }
    }
}

/** pulse_ring — 呼吸光环（强调/直播氛围） */
@Composable
fun PulseRingRenderer(c: UIComponent, ctx: RenderContext) {
    val tint = c.properties.color2("color", MaterialTheme.colorScheme.primary)
    val phase by rememberInfiniteTransition(label = "pr").animateFloat(
        0f, 1f, infiniteRepeatable(tween(1100, easing = LinearEasing)), label = "p"
    )
    Box(Modifier.fillMaxWidth().height(c.properties.flt2("height", 120f).dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val cx = size.width / 2; val cy = size.height / 2
            for (k in 0 until 3) {
                val t = ((phase + k / 3f) % 1f)
                drawCircle(tint.copy(alpha = (1f - t) * 0.5f), size.minDimension * 0.18f + t * size.minDimension * 0.32f, Offset(cx, cy), style = androidx.compose.ui.graphics.drawscope.Stroke(3.dp.toPx()))
            }
        }
        Text(c.properties.str2("emoji", "❤️") ?: "", fontSize = 30.sp)
    }
}

// ═══════════════ 三、音频 / 播放器（3 个） ═══════════════

/** equalizer_bars — 均衡器跳动条 */
@Composable
fun EqualizerBarsRenderer(c: UIComponent, ctx: RenderContext) {
    val tint = c.properties.color2("color", MaterialTheme.colorScheme.primary)
    val bars = c.properties.int2("bars", 16).coerceIn(4, 32)
    val phase by rememberInfiniteTransition(label = "eq").animateFloat(
        0f, 1f, infiniteRepeatable(tween(650, easing = LinearEasing)), label = "p"
    )
    Row(Modifier.fillMaxWidth().height(c.properties.flt2("height", 64f).dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        repeat(bars) { i ->
            val h = 0.25f + 0.75f * abs(sin(i * 1.7f + phase * 6.28f))
            Box(
                Modifier
                    .weight(1f)
                    .hFraction(h)
                    .background(tint.copy(alpha = 0.55f + 0.45f * h), RoundedCornerShape(3.dp))
            )
        }
    }
}

/** player_bar — 迷你播放条 */
@Composable
fun PlayerBarRenderer(c: UIComponent, ctx: RenderContext) {
    val title = c.properties.str2("title", "未命名曲目") ?: ""
    val artist = c.properties.str2("artist", "") ?: ""
    val cover = c.properties.str2("emoji", "🎵") ?: ""
    val progress = c.properties.flt2("progress", 0.4f).coerceIn(0f, 1f)
    val accent = c.properties.color2("color", MaterialTheme.colorScheme.primary)
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(16.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(44.dp).background(accent.copy(alpha = 0.18f), CircleShape), contentAlignment = Alignment.Center) {
            Text(cover, fontSize = 22.sp)
        }
        Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Text(artist, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            Box(Modifier.fillMaxWidth().padding(top = 6.dp).height(4.dp).background(Color.LightGray.copy(alpha = 0.5f), RoundedCornerShape(2.dp))) {
                Box(Modifier.wFraction(progress).height(4.dp).background(accent, RoundedCornerShape(2.dp)))
            }
        }
        Text("▶", fontSize = 20.sp, color = accent, modifier = Modifier.padding(start = 6.dp))
    }
}

/** video_card — 视频封面卡（封面+时长+播放键） */
@Composable
fun VideoCardRenderer(c: UIComponent, ctx: RenderContext) {
    val title = c.properties.str2("title", "") ?: ""
    val duration = c.properties.str2("duration", "00:00") ?: ""
    val emoji = c.properties.str2("emoji", "🎬") ?: ""
    Box(
        Modifier
            .fillMaxWidth()
            .height(180.dp)
            .background(
                Brush.verticalGradient(listOf(Color(0xFF312E81), Color(0xFF0F172A))),
                RoundedCornerShape(16.dp)
            )
    ) {
        Text(emoji, fontSize = 44.sp, modifier = Modifier.align(Alignment.Center))
        Box(
            Modifier.align(Alignment.Center).size(52.dp).background(Color.White.copy(alpha = 0.22f), CircleShape),
            contentAlignment = Alignment.Center
        ) { Text("▶", color = Color.White, fontSize = 22.sp) }
        Box(
            Modifier.align(Alignment.BottomEnd).padding(8.dp)
                .background(Color.Black.copy(alpha = 0.65f), RoundedCornerShape(6.dp))
                .padding(horizontal = 6.dp, vertical = 2.dp)
        ) { Text(duration, color = Color.White, fontSize = 11.sp) }
        if (title.isNotBlank()) Text(
            title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.align(Alignment.BottomStart).padding(10.dp)
        )
    }
}

// ═══════════════ 四、游戏化（6 个） ═══════════════

/** xp_bar — 经验条（等级+进度+奖励文案） */
@Composable
fun XpBarRenderer(c: UIComponent, ctx: RenderContext) {
    val level = c.properties.int2("level", 1)
    val value = c.properties.flt2("value", 0.5f).coerceIn(0f, 1f)
    val accent = c.properties.color2("color", Color(0xFF22C55E))
    Column {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Lv.$level", fontSize = 13.sp, fontWeight = FontWeight.Black, color = accent)
            Spacer(Modifier.width(8.dp))
            Text(c.properties.str2("hint", "") ?: "", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            Text("${(value * 100).toInt()}%", fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
        Box(Modifier.fillMaxWidth().padding(top = 4.dp).height(10.dp).background(Color.LightGray.copy(alpha = 0.4f), RoundedCornerShape(5.dp))) {
            Box(Modifier.wFraction(value).height(10.dp).background(Brush.horizontalGradient(listOf(accent, accent.copy(alpha = 0.6f))), RoundedCornerShape(5.dp)))
        }
    }
}

/** hp_bar — 对战血条（红绿分色+数值） */
@Composable
fun HpBarRenderer(c: UIComponent, ctx: RenderContext) {
    val cur = c.properties.int2("current", 50)
    val max = c.properties.int2("max", 100).coerceAtLeast(1)
    val frac = (cur.toFloat() / max).coerceIn(0f, 1f)
    val col = if (frac > 0.5f) Color(0xFF22C55E) else if (frac > 0.2f) Color(0xFFF59E0B) else Color(0xFFEF4444)
    Column {
        Row(Modifier.fillMaxWidth()) {
            Text("HP", fontSize = 12.sp, fontWeight = FontWeight.Black)
            Spacer(Modifier.weight(1f))
            Text("$cur/$max", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = col)
        }
        Box(Modifier.fillMaxWidth().padding(top = 4.dp).height(12.dp).background(Color.Black.copy(alpha = 0.15f), RoundedCornerShape(6.dp))) {
            Box(Modifier.wFraction(frac).height(12.dp).background(col, RoundedCornerShape(6.dp)))
        }
    }
}

/** coin_stack — 金币数量展示 */
@Composable
fun CoinStackRenderer(c: UIComponent, ctx: RenderContext) {
    val amount = c.properties.int2("amount", 0)
    val gold = Color(0xFFF59E0B)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(34.dp).background(Brush.verticalGradient(listOf(Color(0xFFFBBF24), gold)), CircleShape), contentAlignment = Alignment.Center) {
            Text("🪙", fontSize = 17.sp)
        }
        Text(
            "+$amount", fontSize = 18.sp, fontWeight = FontWeight.Black, color = gold,
            modifier = Modifier.padding(start = 8.dp)
        )
        c.properties.str2("label")?.let {
            Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 6.dp))
        }
    }
}

/** quest_card — 任务卡（目标+进度+奖励） */
@Composable
fun QuestCardRenderer(c: UIComponent, ctx: RenderContext) {
    val title = c.properties.str2("title", "每日任务") ?: ""
    val cur = c.properties.int2("current", 0)
    val goal = c.properties.int2("goal", 1).coerceAtLeast(1)
    val reward = c.properties.str2("reward", "") ?: ""
    val done = cur >= goal
    Column(
        Modifier.fillMaxWidth()
            .background(if (done) Color(0xFFDCFCE7) else MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(14.dp))
            .padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (done) "✅" else "🎯", fontSize = 18.sp)
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 8.dp).weight(1f), maxLines = 1)
            Text(if (done) "已完成" else "$cur/$goal", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = if (done) Color(0xFF15803D) else MaterialTheme.colorScheme.primary)
        }
        Box(Modifier.fillMaxWidth().padding(top = 8.dp).height(6.dp).background(Color.White.copy(alpha = 0.7f), RoundedCornerShape(3.dp))) {
            Box(Modifier.wFraction((cur.toFloat() / goal).coerceIn(0f, 1f)).height(6.dp).background(Color(0xFF22C55E), RoundedCornerShape(3.dp)))
        }
        if (reward.isNotBlank()) Text("🎁 $reward", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
    }
}

/** leaderboard_row — 排行榜行（名次徽标+头像+分数） */
@Composable
fun LeaderboardRowRenderer(c: UIComponent, ctx: RenderContext) {
    val rank = c.properties.int2("rank", 1)
    val name = c.properties.str2("name", "匿名") ?: ""
    val score = c.properties.str2("score", "") ?: ""
    val emoji = c.properties.str2("emoji", "🙂") ?: ""
    val medal = mapOf(1 to "🥇", 2 to "🥈", 3 to "🥉")[rank]
    Row(
        Modifier.fillMaxWidth()
            .background(if (rank <= 3) Color(0xFFFEF3C7) else MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(medal ?: "$rank", fontSize = 16.sp, fontWeight = FontWeight.Black, modifier = Modifier.width(34.dp))
        Box(Modifier.size(34.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f), CircleShape), contentAlignment = Alignment.Center) {
            Text(emoji, fontSize = 17.sp)
        }
        Column(Modifier.weight(1f).padding(start = 10.dp)) {
            Text(name, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1)
        }
        Text(score, fontSize = 15.sp, fontWeight = FontWeight.Black)
    }
}

/** streak_flame — 连击火焰（打卡连胜） */
@Composable
fun StreakFlameRenderer(c: UIComponent, ctx: RenderContext) {
    val days = c.properties.int2("days", 0)
    val hot = days >= 7
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(if (hot) "🔥" else "✨", fontSize = 26.sp)
        Column(Modifier.padding(start = 10.dp)) {
            Text("$days 天", fontSize = 20.sp, fontWeight = FontWeight.Black)
            Text(c.properties.str2("label", "连续打卡") ?: "", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun Modifier.hFraction(f: Float): Modifier =
    this.then(Modifier.fillMaxHeight(f.coerceIn(0f, 1f)))

private fun Modifier.wFraction(f: Float): Modifier =
    this.then(Modifier.fillMaxWidth(f.coerceIn(0f, 1f)))
