package com.ai.assistance.quro.genui.sdk.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.assistance.quro.genui.sdk.dsl.UIComponent
import com.ai.assistance.quro.genui.sdk.render.RenderChildren
import com.ai.assistance.quro.genui.sdk.render.RenderContext
import com.ai.assistance.quro.genui.sdk.style.ColorParser
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

// ═══════════ 领域助手（文件私有） ═══════════
private fun JsonObject.s4(key: String, def: String? = null): String? = (this[key] as? JsonPrimitive)?.content ?: def
private fun JsonObject.f4(key: String, def: Float = 0f): Float = (this[key] as? JsonPrimitive)?.content?.toFloatOrNull() ?: def
private fun JsonObject.i4(key: String, def: Int = 0): Int = (this[key] as? JsonPrimitive)?.content?.toIntOrNull() ?: def
private fun JsonObject.c4(key: String, def: Color): Color = ColorParser.toColor(s4(key), def)
private fun JsonObject.a4(key: String): List<JsonObject> = ((this[key] as? JsonArray)?.filterIsInstance<JsonObject>()) ?: emptyList()

/** 简易进度条（多组件复用） */
@Composable
private fun MiniBar4(frac: Float, color: Color, height: Int = 6) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(height.dp)
            .clip(RoundedCornerShape((height / 2).dp))
            .background(Color.LightGray.copy(alpha = 0.35f))
    ) {
        Box(
            Modifier
                .fillMaxWidth(frac.coerceIn(0f, 1f))
                .height(height.dp)
                .clip(RoundedCornerShape((height / 2).dp))
                .background(color)
        )
    }
}

// ═══════════ 1. 宠物 ═══════════

@Composable
fun PetCardRenderer(c: UIComponent, ctx: RenderContext) {
    val p = c.properties
    val name = p.s4("name", "毛球")
    val emoji = p.s4("emoji", "🐱") ?: "🐱"
    val level = p.i4("level", 1)
    val hunger = p.f4("hunger", 0.7f)
    val energy = p.f4("energy", 0.8f)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(56.dp).clip(CircleShape).background(Brush.verticalGradient(listOf(Color(0xFFFFD54F), Color(0xFFFF8A65)))),
            contentAlignment = Alignment.Center
        ) { Text(emoji, fontSize = 30.sp) }
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text("$name · Lv.$level", fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            MiniBar4(hunger, Color(0xFFFB923C)); Spacer(Modifier.height(4.dp))
            MiniBar4(energy, Color(0xFF34D399))
        }
    }
}

@Composable
fun PetStateRenderer(c: UIComponent, ctx: RenderContext) {
    val mood = c.properties.s4("mood", "开心") ?: "开心"
    val emoji = c.properties.s4("emoji", "😺") ?: "😺"
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().padding(12.dp)) {
        Text(emoji, fontSize = 52.sp)
        Text(mood, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ═══════════ 2. 直播 ═══════════

@Composable
fun LiveRoomCardRenderer(c: UIComponent, ctx: RenderContext) {
    val p = c.properties
    val title = p.s4("title", "直播间") ?: "直播间"
    val viewers = p.i4("viewers", 0)
    val emoji = p.s4("emoji", "🎥") ?: "🎥"
    Box(
        Modifier.fillMaxWidth().height(150.dp).clip(RoundedCornerShape(14.dp))
            .background(Brush.verticalGradient(listOf(Color(0xFF6366F1), Color(0xFFA855F7))))
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(emoji, fontSize = 46.sp) }
        Row(Modifier.padding(10.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.clip(RoundedCornerShape(6.dp)).background(Color(0xFFEF4444)).padding(horizontal = 8.dp, vertical = 2.dp)) {
                Text("LIVE", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Black)
            }
            Spacer(Modifier.weight(1f))
            Box(Modifier.clip(RoundedCornerShape(6.dp)).background(Color.Black.copy(alpha = 0.4f)).padding(horizontal = 8.dp, vertical = 2.dp)) {
                Text("👁 $viewers", color = Color.White, fontSize = 11.sp)
            }
        }
        Text(title, color = Color.White, fontSize = 13.sp, modifier = Modifier.align(Alignment.BottomStart).padding(10.dp))
    }
}

@Composable
fun GiftBannerRenderer(c: UIComponent, ctx: RenderContext) {
    val user = c.properties.s4("user", "神秘人") ?: "神秘人"
    val gift = c.properties.s4("gift", "火箭") ?: "火箭"
    val count = c.properties.i4("count", 1)
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(999.dp))
            .background(Brush.horizontalGradient(listOf(Color(0xFFF59E0B), Color(0xFFEF4444))))
            .padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("🎁", fontSize = 18.sp)
        Spacer(Modifier.width(8.dp))
        Text("$user 送出 $gift ×$count", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}

// ═══════════ 3. 动态 ═══════════

@Composable
fun FeedCardRenderer(c: UIComponent, ctx: RenderContext) {
    val p = c.properties
    val name = p.s4("name", "用户") ?: "用户"
    val time = p.s4("time", "刚刚") ?: "刚刚"
    val text = p.s4("text", "") ?: ""
    val emoji = p.s4("emoji", "🙂") ?: "🙂"
    Column(Modifier.fillMaxWidth().padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) { Text(emoji, fontSize = 20.sp) }
            Column(Modifier.padding(start = 10.dp)) {
                Text(name, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Text(time, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (text.isNotBlank()) Text(text, fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp))
        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            listOf("👍 赞", "💬 评论", "↗ 分享").forEach { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
    if (c.children.isNotEmpty()) RenderChildren(c.children, ctx)
}

@Composable
fun MomentsGridRenderer(c: UIComponent, ctx: RenderContext) {
    val emojis = (c.properties["emojis"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content } ?: listOf("🖼", "🖼", "🖼")
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        emojis.chunked(3).forEach { rowEmojis ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                rowEmojis.forEach { e ->
                    Box(
                        Modifier.size(86.dp).clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center
                    ) { Text(e, fontSize = 34.sp) }
                }
            }
        }
    }
}

// ═══════════ 4. 游戏 ═══════════

@Composable
fun GameHudRenderer(c: UIComponent, ctx: RenderContext) {
    val p = c.properties
    val hp = p.f4("hp", 1f)
    val coins = p.i4("coins", 0)
    val score = p.i4("score", 0)
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(Color.Black.copy(alpha = 0.65f)).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("❤️", fontSize = 16.sp)
        Box(Modifier.weight(1f).padding(horizontal = 8.dp).height(8.dp).clip(RoundedCornerShape(4.dp)).background(Color.White.copy(alpha = 0.25f))) {
            Box(Modifier.fillMaxWidth(hp.coerceIn(0f, 1f)).height(8.dp).clip(RoundedCornerShape(4.dp)).background(Color(0xFFEF4444)))
        }
        Text("🪙$coins  ⭐$score", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun GamePadRenderer(c: UIComponent, ctx: RenderContext) {
    val a = c.properties.s4("a", "A") ?: "A"
    val b = c.properties.s4("b", "B") ?: "B"
    Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(44.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF334155)), contentAlignment = Alignment.Center) { Text("↑", color = Color.White) }
            Row {
                Box(Modifier.size(44.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF334155)), contentAlignment = Alignment.Center) { Text("←", color = Color.White) }
                Spacer(Modifier.width(4.dp))
                Box(Modifier.size(44.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF334155)), contentAlignment = Alignment.Center) { Text("→", color = Color.White) }
            }
            Box(Modifier.size(44.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF334155)), contentAlignment = Alignment.Center) { Text("↓", color = Color.White) }
        }
        Spacer(Modifier.width(30.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(56.dp).clip(CircleShape).background(Color(0xFF3B82F6)), contentAlignment = Alignment.Center) { Text(b, color = Color.White, fontWeight = FontWeight.Black) }
            Spacer(Modifier.width(12.dp))
            Box(Modifier.size(56.dp).clip(CircleShape).background(Color(0xFFEF4444)), contentAlignment = Alignment.Center) { Text(a, color = Color.White, fontWeight = FontWeight.Black) }
        }
    }
}

@Composable
fun LootBoxRenderer(c: UIComponent, ctx: RenderContext) {
    val rarity = c.properties.s4("rarity", "稀有") ?: "稀有"
    val rc = when (rarity) { "传说" -> Color(0xFFF59E0B); "史诗" -> Color(0xFFA855F7); else -> Color(0xFF3B82F6) }
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(14.dp)) {
        Box(
            Modifier.size(96.dp).clip(RoundedCornerShape(20.dp)).background(Brush.verticalGradient(listOf(rc, rc.copy(alpha = 0.5f)))),
            contentAlignment = Alignment.Center
        ) { Text("🎁", fontSize = 44.sp) }
        Spacer(Modifier.height(8.dp))
        Text(rarity, fontWeight = FontWeight.Black, color = rc)
    }
}

// ═══════════ 5. 终端 ═══════════

@Composable
fun TerminalViewRenderer(c: UIComponent, ctx: RenderContext) {
    val lines = c.properties.a4("lines").map { it.s4("text", "") ?: "" }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF0D1117)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Row { Box(Modifier.size(8.dp).clip(CircleShape).background(Color(0xFFEF4444))); Spacer(Modifier.width(5.dp)); Box(Modifier.size(8.dp).clip(CircleShape).background(Color(0xFFF59E0B))); Spacer(Modifier.width(5.dp)); Box(Modifier.size(8.dp).clip(CircleShape).background(Color(0xFF22C55E))) }
        Spacer(Modifier.height(8.dp))
        if (lines.isEmpty()) Text("$ ls\nOK", color = Color(0xFF4ADE80), fontSize = 12.sp, lineHeight = 17.sp)
        lines.forEach { ln -> Text(ln, color = if (ln.startsWith("$")) Color(0xFF4ADE80) else Color(0xFF8B949E), fontSize = 12.sp, lineHeight = 17.sp) }
    }
}

@Composable
fun CommandHintRenderer(c: UIComponent, ctx: RenderContext) {
    val cmd = c.properties.s4("command", "help") ?: "help"
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF1F2937)).padding(horizontal = 12.dp, vertical = 8.dp)
    ) { Text("$ ", color = Color(0xFF4ADE80), fontSize = 13.sp); Text(cmd, color = Color(0xFFE5E7EB), fontSize = 13.sp) }
}

// ═══════════ 6. 可视化 ═══════════

@Composable
fun TreemapTileRenderer(c: UIComponent, ctx: RenderContext) {
    val data = c.properties.a4("data")
    val palette = listOf(Color(0xFF3B82F6), Color(0xFF22C55E), Color(0xFFF59E0B), Color(0xFFEF4444), Color(0xFFA855F7))
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        data.chunked(2).forEachIndexed { ri, rowItems ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                rowItems.forEachIndexed { ci, item ->
                    val v = item.f4("value", 1f).coerceAtLeast(0.5f)
                    Box(
                        Modifier.weight(v).height(64.dp).clip(RoundedCornerShape(8.dp))
                            .background(palette[(ri * 2 + ci) % palette.size]),
                        contentAlignment = Alignment.Center
                    ) { Text("${item.s4("label", "")}", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
                }
            }
        }
    }
}

@Composable
fun FunnelChartRenderer(c: UIComponent, ctx: RenderContext) {
    val data = c.properties.a4("data")
    val maxV = data.maxOfOrNull { it.f4("value", 1f) }?.coerceAtLeast(1f) ?: 1f
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth().padding(8.dp)) {
        data.forEachIndexed { i, item ->
            val frac = (item.f4("value") / maxV).coerceIn(0.15f, 1f)
            Box(
                Modifier.fillMaxWidth(frac).clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 1f - i * 0.18f)),
                contentAlignment = Alignment.Center
            ) { Text("${item.s4("label", "")} ${item.f4("value").toInt()}", color = Color.White, fontSize = 12.sp, modifier = Modifier.padding(vertical = 6.dp)) }
        }
    }
}

// ═══════════ 7. 弹窗 ═══════════

@Composable
fun ModalConfirmRenderer(c: UIComponent, ctx: RenderContext) {
    val p = c.properties
    val title = p.s4("title", "提示") ?: "提示"
    val message = p.s4("message", "") ?: ""
    val cancel = p.s4("cancel", "取消") ?: "取消"
    val confirm = p.s4("confirm", "确定") ?: "确定"
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surface).padding(20.dp)
    ) {
        Text(title, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        if (message.isNotBlank()) Text(message, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
        Row(Modifier.fillMaxWidth().padding(top = 18.dp), horizontalArrangement = Arrangement.End) {
            Text(cancel, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold, modifier = Modifier.padding(end = 24.dp))
            Text(confirm, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun ToastPillRenderer(c: UIComponent, ctx: RenderContext) {
    val text = c.properties.s4("text", "已保存") ?: "已保存"
    Box(
        Modifier.fillMaxWidth().padding(vertical = 20.dp),
        contentAlignment = Alignment.Center
    ) {
        Box(Modifier.clip(RoundedCornerShape(999.dp)).background(Color.Black.copy(alpha = 0.78f)).padding(horizontal = 18.dp, vertical = 9.dp)) {
            Text(text, color = Color.White, fontSize = 13.sp)
        }
    }
}

// ═══════════ 8. 浏览器 ═══════════

@Composable
fun BrowserBarRenderer(c: UIComponent, ctx: RenderContext) {
    val url = c.properties.s4("url", "https://genui.app") ?: "https://genui.app"
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(999.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("🔒", fontSize = 12.sp)
        Spacer(Modifier.width(8.dp))
        Text(url, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
        Text("⟳", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun WebPreviewCardRenderer(c: UIComponent, ctx: RenderContext) {
    val p = c.properties
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface).border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
    ) {
        Box(Modifier.fillMaxWidth().height(96.dp).background(Brush.verticalGradient(listOf(Color(0xFF93C5FD), Color(0xFF60A5FA)))), contentAlignment = Alignment.Center) {
            Text(p.s4("favicon", "🌐") ?: "🌐", fontSize = 36.sp)
        }
        Column(Modifier.padding(12.dp)) {
            Text(p.s4("title", "网页标题") ?: "网页标题", fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1)
            Text(p.s4("desc", "") ?: "", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, modifier = Modifier.padding(top = 4.dp))
            Text(p.s4("url", "") ?: "", fontSize = 11.sp, color = Color(0xFF3B82F6), modifier = Modifier.padding(top = 6.dp))
        }
    }
}
