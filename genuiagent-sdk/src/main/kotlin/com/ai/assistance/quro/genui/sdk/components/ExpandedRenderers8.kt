package com.ai.assistance.quro.genui.sdk.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.assistance.quro.genui.sdk.dsl.UIComponent
import com.ai.assistance.quro.genui.sdk.render.RenderContext
import com.ai.assistance.quro.genui.sdk.style.ColorParser
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

// ── 文件内助手 ──
private fun JsonObject.s8(key: String, def: String? = null): String? = (this[key] as? JsonPrimitive)?.content ?: def
private fun JsonObject.f8(key: String, def: Float = 0f): Float = (this[key] as? JsonPrimitive)?.content?.toFloatOrNull() ?: def
private fun JsonObject.i8(key: String, def: Int = 0): Int = (this[key] as? JsonPrimitive)?.content?.toIntOrNull() ?: def
private fun JsonObject.c8(key: String, def: Color): Color = ColorParser.toColor(s8(key), def)
private fun JsonObject.a8(key: String): List<String> = ((this[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }) ?: emptyList()
@Composable
private fun theme8(ctx: RenderContext): Color = MaterialTheme.colorScheme.primary

/** 闹钟行 */
@Composable
fun AlarmRowRenderer(c: UIComponent, ctx: RenderContext) {
    val p = c.properties
    val on = p.s8("state", "on") == "on"
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(p.s8("time", "07:00") ?: "07:00", fontSize = 24.sp, fontWeight = FontWeight.Light, color = if (on) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline)
            Text(p.s8("days", "周一至周五") ?: "周一至周五", fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
        }
        Box(Modifier.width(44.dp).height(24.dp).clip(RoundedCornerShape(10.dp)).background(if (on) theme8(ctx) else Color.LightGray), contentAlignment = Alignment.CenterEnd) {
            Box(Modifier.padding(end = if (on) 2.dp else 0.dp).size(20.dp).clip(CircleShape).background(Color.White).align(if (on) Alignment.CenterEnd else Alignment.CenterStart))
        }
    }
}

/** 响铃全屏 */
@Composable
fun AlarmRingRenderer(c: UIComponent, ctx: RenderContext) {
    val inf = rememberInfiniteTransition(label = "ring")
    val pulse by inf.animateFloat(0.9f, 1.08f, infiniteRepeatable(tween(600), RepeatMode.Reverse), label = "s")
    Column(Modifier.fillMaxWidth().background(ctx.theme.colorScheme.onSurface).padding(vertical = 30.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("⏰", fontSize = 40.sp, modifier = Modifier.graphicsLayer8(pulse))
        Text(c.properties.s8("time", "07:00") ?: "07:00", color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.Light, modifier = Modifier.padding(top = 12.dp))
        Text(c.properties.s8("label", "闹钟") ?: "闹钟", color = ctx.theme.colorScheme.info, fontSize = 14.sp)
        Row(Modifier.padding(top = 24.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(Modifier.size(width = 110.dp, height = 44.dp).clip(RoundedCornerShape(20.dp)).background(ctx.theme.colorScheme.info), contentAlignment = Alignment.Center) { Text("稍后", color = Color.White) }
            Box(Modifier.size(width = 110.dp, height = 44.dp).clip(RoundedCornerShape(20.dp)).background(ctx.theme.colorScheme.success), contentAlignment = Alignment.Center) { Text("起床", color = Color.White) }
        }
    }
}

private fun Modifier.graphicsLayer8(scale: Float): Modifier = this.graphicsLayer { scaleX = scale; scaleY = scale }

/** 邮件行 */
@Composable
fun MailRowRenderer(c: UIComponent, ctx: RenderContext) {
    val p = c.properties
    val unread = p.s8("unread", "0") != "0"
    Row(Modifier.fillMaxWidth().padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(if (unread) ctx.theme.colorScheme.info else Color.Transparent))
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(p.s8("from", "") ?: "", fontWeight = if (unread) FontWeight.Bold else FontWeight.Normal, fontSize = 14.sp, modifier = Modifier.weight(1f), maxLines = 1)
                Text(p.s8("time", "") ?: "", fontSize = 11.sp, color = MaterialTheme.colorScheme.outline)
            }
            Text(p.s8("subject", "") ?: "", fontSize = 12.sp, maxLines = 1, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(p.s8("snippet", "") ?: "", fontSize = 12.sp, color = MaterialTheme.colorScheme.outline, maxLines = 1)
        }
    }
}

/** 公众号卡片 */
@Composable
fun OfficialAccountCardRenderer(c: UIComponent, ctx: RenderContext) {
    val p = c.properties
    Row(Modifier.fillMaxWidth().padding(12.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceContainerLow).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(44.dp).clip(CircleShape).background(Brush.linearGradient(listOf(ctx.theme.colorScheme.success, ctx.theme.colorScheme.success))), contentAlignment = Alignment.Center) {
            Text(p.s8("emoji", "📢") ?: "📢", fontSize = 20.sp)
        }
        Column(Modifier.padding(start = 10.dp).weight(1f)) {
            Text(p.s8("name", "") ?: "", fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Text("${p.i8("followers", 0)} 关注者 · ${p.s8("desc", "") ?: ""}", fontSize = 11.sp, color = MaterialTheme.colorScheme.outline, maxLines = 1)
        }
        Box(Modifier.clip(RoundedCornerShape(14.dp)).background(ctx.theme.colorScheme.success).padding(horizontal = 14.dp, vertical = 5.dp)) {
            Text("+ 关注", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun ArticleRowRenderer(c: UIComponent, ctx: RenderContext) {
    val p = c.properties
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Column(Modifier.weight(1f)) {
            Text(p.s8("title", "") ?: "", fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 2)
            Text("${p.s8("author", "") ?: ""} · ${p.s8("reads", "") ?: ""}阅读", fontSize = 11.sp, color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(top = 6.dp))
        }
        Spacer(Modifier.width(10.dp))
        Box(Modifier.size(64.dp).clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
            Text(p.s8("emoji", "📰") ?: "📰", fontSize = 24.sp)
        }
    }
}

/** 文件行 */
@Composable
fun FileRowRenderer(c: UIComponent, ctx: RenderContext) {
    val p = c.properties
    val icon = when (p.s8("kind", "file")) { "dir" -> "📁"; "img" -> "🖼"; "doc" -> "📄"; "code" -> "🧾"; else -> "📦" }
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(icon, fontSize = 20.sp)
        Column(Modifier.padding(start = 10.dp).weight(1f)) {
            Text(p.s8("name", "") ?: "", fontSize = 14.sp, maxLines = 1)
            Text("${p.s8("size", "") ?: ""} · ${p.s8("date", "") ?: ""}", fontSize = 11.sp, color = MaterialTheme.colorScheme.outline)
        }
        Text("⋮", color = MaterialTheme.colorScheme.outline)
    }
}

@Composable
fun StorageMeterRenderer(c: UIComponent, ctx: RenderContext) {
    val used = c.properties.f8("used", 0f).coerceIn(0f, 1f)
    Column(Modifier.fillMaxWidth().padding(12.dp)) {
        Row { Text("存储空间", fontSize = 12.sp); Spacer(Modifier.weight(1f)); Text("${(used * 100).toInt()}%", fontSize = 12.sp, color = MaterialTheme.colorScheme.outline) }
        Spacer(Modifier.height(8.dp))
        Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(6.dp)).background(Color.LightGray.copy(alpha = 0.3f))) {
            Box(Modifier.fillMaxWidth(used).height(8.dp).clip(RoundedCornerShape(6.dp)).background(Brush.horizontalGradient(listOf(ctx.theme.colorScheme.info, ctx.theme.colorScheme.info))))
        }
    }
}

/** 外观：主题色选择行 */
@Composable
fun ThemePickerRowRenderer(c: UIComponent, ctx: RenderContext) {
    val colors = c.properties.a8("colors")
    val selected = c.properties.i8("selected", 0)
    Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        colors.forEachIndexed { i, hex ->
            val col = ColorParser.toColor(hex, Color.Gray)
            Box(Modifier.size(34.dp).clip(CircleShape).background(col).border(
                if (i == selected) 3.dp else 0.dp, MaterialTheme.colorScheme.primary, CircleShape
            ))
        }
    }
}

@Composable
fun FontPreviewRowRenderer(c: UIComponent, ctx: RenderContext) {
    val p = c.properties
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest), contentAlignment = Alignment.Center) {
            Text("Aa", fontWeight = FontWeight.Bold)
        }
        Column(Modifier.padding(start = 10.dp).weight(1f)) {
            Text(p.s8("name", "默认字体") ?: "默认字体", fontSize = 14.sp)
            Text(p.s8("sample", "") ?: "", fontSize = 12.sp, color = MaterialTheme.colorScheme.outline, maxLines = 1)
        }
        if (p.s8("active", "no") == "yes") Text("✓", color = theme8(ctx), fontWeight = FontWeight.Bold)
    }
}

/** 智能体卡 */
@Composable
fun AgentCardRenderer(c: UIComponent, ctx: RenderContext) {
    val p = c.properties
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Brush.linearGradient(listOf(ctx.theme.colorScheme.info, ctx.theme.colorScheme.onSurface))).padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(p.s8("emoji", "🤖") ?: "🤖", fontSize = 24.sp)
            Column(Modifier.padding(start = 10.dp)) {
                Text(p.s8("name", "GenUI 智能体") ?: "GenUI 智能体", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Text(p.s8("model", "") ?: "", color = ctx.theme.colorScheme.info, fontSize = 11.sp)
            }
            Spacer(Modifier.weight(1f))
            Text("● 在线", color = ctx.theme.colorScheme.success, fontSize = 11.sp)
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            p.a8("skills").take(4).forEach { sk ->
                Box(Modifier.clip(RoundedCornerShape(6.dp)).background(Color.White.copy(alpha = 0.12f)).padding(horizontal = 8.dp, vertical = 3.dp)) {
                    Text(sk, color = ctx.theme.colorScheme.infoContainer, fontSize = 11.sp)
                }
            }
        }
    }
}

/** AI 思考中 — 三点跳动动画 */
@Composable
fun AiThinkingRenderer(c: UIComponent, ctx: RenderContext) {
    val inf = rememberInfiniteTransition(label = "ai")
    val t by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(900, easing = LinearEasing)), label = "t")
    val text = c.properties.s8("text", "AI 正在思考") ?: "AI 正在思考"
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(10.dp)) {
        Text(text, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            repeat(3) { i ->
                val up = kotlin.math.sin((t * 2 * Math.PI) - i * 0.9f).toFloat()
                Box(Modifier.size(6.dp).clip(CircleShape).background(theme8(ctx).copy(alpha = 0.4f + 0.6f * up)).padding(bottom = (4 * up.coerceAtLeast(0f)).dp))
            }
        }
    }
}

/** AI 对话气泡 */
@Composable
fun AiChatBubbleRenderer(c: UIComponent, ctx: RenderContext) {
    val p = c.properties
    val ai = p.s8("role", "ai") == "ai"
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = if (ai) Arrangement.Start else Arrangement.End) {
        Box(
            Modifier.weight(0.86f).clip(RoundedCornerShape(14.dp))
                .background(if (ai) MaterialTheme.colorScheme.surfaceContainerHigh else theme8(ctx).copy(alpha = 0.12f))
                .padding(12.dp)
        ) {
            Column {
                Text(if (ai) "🤖 ${p.s8("name", "GenUI") ?: "GenUI"}" else (p.s8("name", "我") ?: "我"), fontSize = 11.sp, color = MaterialTheme.colorScheme.outline)
                Text(p.s8("text", "") ?: "", fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

/** 番茄钟 */
@Composable
fun FocusTimerRenderer(c: UIComponent, ctx: RenderContext) {
    val p = c.properties
    val frac = p.f8("remaining", 1f).coerceIn(0f, 1f)
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(ctx.theme.colorScheme.onSurface).padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("🍅 专注中 · ${p.s8("task", "") ?: ""}", color = ctx.theme.colorScheme.infoContainer, fontSize = 12.sp)
        Text(p.s8("time", "25:00") ?: "25:00", color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.Light)
        Spacer(Modifier.height(12.dp))
        Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(6.dp)).background(Color.White.copy(alpha = 0.15f))) {
            Box(Modifier.fillMaxWidth(frac).height(6.dp).clip(RoundedCornerShape(6.dp)).background(ctx.theme.colorScheme.success))
        }
    }
}

/** 会员横幅 */
@Composable
fun VipBannerRenderer(c: UIComponent, ctx: RenderContext) {
    val p = c.properties
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Brush.horizontalGradient(listOf(ctx.theme.colorScheme.onSurface, ctx.theme.colorScheme.primary))).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("👑", fontSize = 24.sp)
        Column(Modifier.padding(start = 10.dp).weight(1f)) {
            Text(p.s8("title", "开通会员") ?: "开通会员", color = ctx.theme.colorScheme.warning, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Text(p.s8("desc", "") ?: "", color = ctx.theme.colorScheme.outline, fontSize = 11.sp)
        }
        Box(Modifier.clip(RoundedCornerShape(14.dp)).background(ctx.theme.colorScheme.warning).padding(horizontal = 14.dp, vertical = 6.dp)) {
            Text(p.s8("cta", "立即开通") ?: "立即开通", color = ctx.theme.colorScheme.primary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun PricingCardRenderer(c: UIComponent, ctx: RenderContext) {
    val p = c.properties
    val hot = p.s8("hot", "no") == "yes"
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .background(if (hot) ctx.theme.colorScheme.onSurface else MaterialTheme.colorScheme.surfaceContainerLow)
            .border(if (hot) 2.dp else 1.dp, if (hot) ctx.theme.colorScheme.info else MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp))
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(p.s8("plan", "月度会员") ?: "月度会员", fontWeight = FontWeight.Bold, color = if (hot) Color.White else MaterialTheme.colorScheme.onSurface)
            if (hot) Box(Modifier.padding(start = 8.dp).clip(RoundedCornerShape(6.dp)).background(ctx.theme.colorScheme.error).padding(horizontal = 6.dp, vertical = 2.dp)) { Text("热门", color = Color.White, fontSize = 11.sp) }
        }
        Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(top = 8.dp)) {
            Text("¥${p.s8("price", "0") ?: "0"}", fontSize = 28.sp, fontWeight = FontWeight.Black, color = if (hot) ctx.theme.colorScheme.warning else MaterialTheme.colorScheme.onSurface)
            Text(" / ${p.s8("period", "月") ?: "月"}", fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
        }
        p.a8("benefits").forEach { b -> Text("✓ $b", fontSize = 12.sp, color = if (hot) ctx.theme.colorScheme.infoContainer else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp)) }
    }
}
