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

// ── 文件内助手 ──
private fun JsonObject.s9(key: String, def: String? = null): String? = (this[key] as? JsonPrimitive)?.content ?: def
private fun JsonObject.i9(key: String, def: Int = 0): Int = (this[key] as? JsonPrimitive)?.content?.toIntOrNull() ?: def
private fun JsonObject.a9(key: String): List<String> = ((this[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }) ?: emptyList()

/** 网速测试环 */
@Composable
fun SpeedTestRenderer(c: UIComponent, ctx: RenderContext) {
    val p = c.properties
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color(0xFF0F172A)).padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(120.dp).clip(CircleShape).background(Color(0xFF1E293B)).border(6.dp, Color(0xFF22D3EE), CircleShape), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("${p.s9("speed", "0") ?: "0"}", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Black)
                Text("Mbps", color = Color(0xFF94A3B8), fontSize = 11.sp)
            }
        }
        Text("${p.s9("isp", "") ?: ""} · ${p.s9("ping", "") ?: ""}ms", color = Color(0xFF94A3B8), fontSize = 12.sp, modifier = Modifier.padding(top = 12.dp))
    }
}

@Composable
fun ConnectionStatusRenderer(c: UIComponent, ctx: RenderContext) {
    val p = c.properties
    val ok = p.s9("state", "online") == "online"
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(if (ok) Color(0xFFDCFCE7) else Color(0xFFFEE2E2)).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(if (ok) Color(0xFF22C55E) else Color(0xFFEF4444)))
        Spacer(Modifier.width(8.dp))
        Text(if (ok) "网络连接正常" else "网络已断开", fontSize = 13.sp, color = if (ok) Color(0xFF166534) else Color(0xFF991B1B))
        Spacer(Modifier.weight(1f))
        Text(p.s9("net", "WiFi") ?: "WiFi", fontSize = 12.sp, color = if (ok) Color(0xFF166534) else Color(0xFF991B1B))
    }
}

/** 作品卡 */
@Composable
fun PortfolioCardRenderer(c: UIComponent, ctx: RenderContext) {
    val p = c.properties
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceContainerLow)) {
        Box(Modifier.fillMaxWidth().height(110.dp).background(Brush.linearGradient(listOf(p.s9("g1", "#818CF8")?.let { ColorParser.toColor(it, Color(0xFF818CF8)) } ?: Color(0xFF818CF8), p.s9("g2", "#C084FC")?.let { ColorParser.toColor(it, Color(0xFFC084FC)) } ?: Color(0xFFC084FC)))), contentAlignment = Alignment.Center) {
            Text(p.s9("emoji", "🎨") ?: "🎨", fontSize = 38.sp)
        }
        Column(Modifier.padding(12.dp)) {
            Text(p.s9("title", "") ?: "", fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1)
            Text(p.s9("desc", "") ?: "", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, modifier = Modifier.padding(top = 4.dp))
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("❤ ${p.i9("likes", 0)}", fontSize = 11.sp, color = MaterialTheme.colorScheme.outline)
                Spacer(Modifier.width(12.dp))
                Text("👁 ${p.i9("views", 0)}", fontSize = 11.sp, color = MaterialTheme.colorScheme.outline)
                Spacer(Modifier.weight(1f))
                Text(p.s9("tag", "") ?: "", fontSize = 10.sp, color = theme9())
            }
        }
    }
}

@Composable
fun WorkStatsRenderer(c: UIComponent, ctx: RenderContext) {
    val stats = c.properties.a9("stats")
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainerLow).padding(12.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
        stats.forEach { s ->
            val parts = s.split("|")
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(parts.getOrNull(1) ?: "", fontWeight = FontWeight.Black, fontSize = 17.sp)
                Text(parts.getOrNull(0) ?: "", fontSize = 11.sp, color = MaterialTheme.colorScheme.outline)
            }
        }
    }
}

/** 点赞 */
@Composable
fun LikeButtonRenderer(c: UIComponent, ctx: RenderContext) {
    val p = c.properties
    val liked = p.s9("liked", "no") == "yes"
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clip(RoundedCornerShape(18.dp)).background(if (liked) Color(0xFFFEE2E2) else MaterialTheme.colorScheme.surfaceContainerHigh).padding(horizontal = 14.dp, vertical = 7.dp)) {
        Text(if (liked) "❤️" else "🤍", fontSize = 15.sp)
        Text("${p.i9("count", 0)}", fontSize = 13.sp, color = if (liked) Color(0xFFDC2626) else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 6.dp))
    }
}

@Composable
fun LikeListRowRenderer(c: UIComponent, ctx: RenderContext) {
    val p = c.properties
    Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(32.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHighest), contentAlignment = Alignment.Center) { Text(p.s9("emoji", "🙂") ?: "🙂", fontSize = 15.sp) }
        Text(p.s9("name", "") ?: "", fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 10.dp))
        Text("赞了你的作品", fontSize = 12.sp, color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(start = 6.dp))
        Spacer(Modifier.weight(1f))
        Text(p.s9("time", "") ?: "", fontSize = 11.sp, color = MaterialTheme.colorScheme.outline)
    }
}

/** 消息 */
@Composable
fun ChatRowRenderer(c: UIComponent, ctx: RenderContext) {
    val p = c.properties
    val unread = p.i9("unread", 0)
    Row(Modifier.fillMaxWidth().padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(46.dp).clip(CircleShape).background(Brush.linearGradient(listOf(Color(0xFF60A5FA), Color(0xFFA78BFA)))), contentAlignment = Alignment.Center) { Text(p.s9("emoji", "💬") ?: "💬", fontSize = 20.sp) }
        Column(Modifier.weight(1f).padding(start = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(p.s9("name", "") ?: "", fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.weight(1f), maxLines = 1)
                Text(p.s9("time", "") ?: "", fontSize = 11.sp, color = MaterialTheme.colorScheme.outline)
            }
            Text(p.s9("last", "") ?: "", fontSize = 12.sp, color = MaterialTheme.colorScheme.outline, maxLines = 1)
        }
        if (unread > 0) Box(Modifier.padding(start = 8.dp).size(18.dp).clip(CircleShape).background(Color(0xFFEF4444)), contentAlignment = Alignment.Center) {
            Text("$unread", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun MessageComposerRenderer(c: UIComponent, ctx: RenderContext) {
    val p = c.properties
    Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(36.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) { Text("🎙", fontSize = 15.sp) }
        Box(Modifier.weight(1f).clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(horizontal = 14.dp, vertical = 9.dp)) {
            Text(p.s9("hint", "输入消息…") ?: "输入消息…", fontSize = 13.sp, color = MaterialTheme.colorScheme.outline)
        }
        Box(Modifier.size(36.dp).clip(CircleShape).background(theme9()), contentAlignment = Alignment.Center) { Text("➤", color = Color.White, fontSize = 14.sp) }
    }
}

/** 电脑：桌面窗口 */
@Composable
fun DesktopWindowRenderer(c: UIComponent, ctx: RenderContext) {
    val p = c.properties
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).border(1.dp, Color(0xFF334155), RoundedCornerShape(10.dp)).background(Color(0xFF0F172A))) {
        Row(Modifier.fillMaxWidth().background(Color(0xFF1E293B)).padding(horizontal = 10.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(Color(0xFFEF4444)))
            Spacer(Modifier.width(6.dp))
            Box(Modifier.size(10.dp).clip(CircleShape).background(Color(0xFFFBBF24)))
            Spacer(Modifier.width(6.dp))
            Box(Modifier.size(10.dp).clip(CircleShape).background(Color(0xFF22C55E)))
            Spacer(Modifier.width(10.dp))
            Text(p.s9("title", "窗口") ?: "窗口", color = Color(0xFF94A3B8), fontSize = 12.sp)
        }
        Box(Modifier.fillMaxWidth().background(Color.White).padding(12.dp)) { RenderChildren(c.children, ctx) }
    }
}

@Composable
fun TaskbarDockRenderer(c: UIComponent, ctx: RenderContext) {
    val icons = c.properties.a9("icons")
    Row(Modifier.clip(RoundedCornerShape(16.dp)).background(Color(0xFF0F172A).copy(alpha = 0.9f)).padding(horizontal = 14.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        icons.forEach { Box(Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFF1E293B)), contentAlignment = Alignment.Center) { Text(it, fontSize = 18.sp) } }
    }
}

/** GenUI 个性化介绍 */
@Composable
fun GenUIIntroCardRenderer(c: UIComponent, ctx: RenderContext) {
    val p = c.properties
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Brush.linearGradient(listOf(Color(0xFF4F46E5), Color(0xFF7C3AED), Color(0xFFDB2777)))).padding(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("✨", fontSize = 28.sp)
            Column(Modifier.padding(start = 10.dp)) {
                Text(p.s9("name", "GenUI") ?: "GenUI", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Black)
                Text("Generative UI Engine", color = Color(0xFFE0E7FF), fontSize = 11.sp)
            }
        }
        Text(p.s9("slogan", "你说话，我生成界面") ?: "你说话，我生成界面", color = Color.White, fontSize = 14.sp, modifier = Modifier.padding(top = 12.dp))
        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("490+ 组件", "500 万+ 变体", "实时渲染").forEach { t ->
                Box(Modifier.clip(RoundedCornerShape(8.dp)).background(Color.White.copy(alpha = 0.18f)).padding(horizontal = 8.dp, vertical = 3.dp)) {
                    Text(t, color = Color.White, fontSize = 10.sp)
                }
            }
        }
    }
}

@Composable
fun GenUIFeatureRowRenderer(c: UIComponent, ctx: RenderContext) {
    val p = c.properties
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) { Text(p.s9("emoji", "⚡") ?: "⚡", fontSize = 16.sp) }
        Column(Modifier.padding(start = 10.dp).weight(1f)) {
            Text(p.s9("title", "") ?: "", fontWeight = FontWeight.Bold, fontSize = 13.sp)
            Text(p.s9("desc", "") ?: "", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** 帮助与反馈 */
@Composable
fun HelpFaqRowRenderer(c: UIComponent, ctx: RenderContext) {
    val p = c.properties
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(p.s9("emoji", "❓") ?: "❓", fontSize = 16.sp)
        Text(p.s9("q", "") ?: "", fontSize = 14.sp, modifier = Modifier.padding(start = 10.dp).weight(1f), maxLines = 1)
        Text("›", color = MaterialTheme.colorScheme.outline, fontSize = 16.sp)
    }
}

@Composable
fun FeedbackBoxRenderer(c: UIComponent, ctx: RenderContext) {
    val p = c.properties
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainerLow).padding(14.dp)) {
        Text(p.s9("title", "帮助与反馈") ?: "帮助与反馈", fontWeight = FontWeight.Bold, fontSize = 14.sp)
        Text("遇到问题？告诉我们，或给个好评鼓励一下 ✨", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.primaryContainer).padding(vertical = 8.dp), contentAlignment = Alignment.Center) { Text("💬 意见反馈", fontSize = 12.sp) }
            Box(Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.secondaryContainer).padding(vertical = 8.dp), contentAlignment = Alignment.Center) { Text("⭐ 好评鼓励", fontSize = 12.sp) }
        }
    }
}

private fun theme9(): Color = Color(0xFF6366F1)
