package com.ai.assistance.quro.genui.sdk.components

import com.ai.assistance.quro.genui.sdk.style.ZorvPalette

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.assistance.quro.genui.sdk.dsl.UIComponent
import com.ai.assistance.quro.genui.sdk.render.RenderChildren
import com.ai.assistance.quro.genui.sdk.render.RenderContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private fun JsonObject.s5(key: String, def: String? = null): String? = (this[key] as? JsonPrimitive)?.content ?: def
private fun JsonObject.f5(key: String, def: Float = 0f): Float = (this[key] as? JsonPrimitive)?.content?.toFloatOrNull() ?: def
private fun JsonObject.i5(key: String, def: Int = 0): Int = (this[key] as? JsonPrimitive)?.content?.toIntOrNull() ?: def
private fun JsonObject.a5(key: String): List<JsonObject> = ((this[key] as? JsonArray)?.filterIsInstance<JsonObject>()) ?: emptyList()

// ═══════════ 9. 安卓系统 ═══════════

@Composable
fun AndroidStatusBarRenderer(c: UIComponent, ctx: RenderContext) {
    val time = c.properties.s5("time", "12:00") ?: "12:00"
    val dark = c.properties.s5("style", "dark") == "dark"
    val fg = if (dark) Color.White else Color.Black
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(time, color = fg, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.weight(1f))
        Text("▮▮⋯ ⚡", color = fg, fontSize = 12.sp)
    }
}

@Composable
fun NotificationShadeRenderer(c: UIComponent, ctx: RenderContext) {
    val p = c.properties
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.75f)).padding(14.dp)
    ) {
        Text(p.s5("appEmoji", "📧") ?: "📧", fontSize = 20.sp)
        Column(Modifier.weight(1f).padding(start = 10.dp)) {
            Row { Text(p.s5("app", "邮件") ?: "邮件", fontWeight = FontWeight.Bold, fontSize = 12.sp); Spacer(Modifier.weight(1f)); Text(p.s5("time", "现在") ?: "现在", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Text(p.s5("title", "新通知") ?: "新通知", fontSize = 14.sp)
            Text(p.s5("text", "") ?: "", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
        }
    }
}

// ═══════════ 10. 表情包 ═══════════

@Composable
fun MemeGridRenderer(c: UIComponent, ctx: RenderContext) {
    val emojis = (c.properties["emojis"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content } ?: listOf("😂", "🤣", "😭")
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        emojis.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { e ->
                    Box(Modifier.size(92.dp).clip(RoundedCornerShape(10.dp)).background(ctx.theme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                        Text(e, fontSize = 40.sp)
                    }
                }
            }
        }
    }
}

@Composable
fun MemeLargeRenderer(c: UIComponent, ctx: RenderContext) {
    val emoji = c.properties.s5("emoji", "😎") ?: "😎"
    val caption = c.properties.s5("caption", "淡定") ?: "淡定"
    Box(Modifier.fillMaxWidth().height(200.dp).clip(RoundedCornerShape(10.dp)).background(ctx.theme.colorScheme.onSurface), contentAlignment = Alignment.Center) {
        Text(emoji, fontSize = 40.sp)
        Text(
            caption, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Black,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp)
        )
    }
}

// ═══════════ 11. 手机界面 ═══════════

@Composable
fun PhoneMockupRenderer(c: UIComponent, ctx: RenderContext) {
    Column(
        Modifier.padding(14.dp).width(230.dp).clip(RoundedCornerShape(28.dp))
            .background(ctx.theme.colorScheme.onSurface).padding(8.dp)
    ) {
        Box(
            Modifier.fillMaxWidth().height(400.dp).clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.surface)
        ) {
            Box(Modifier.fillMaxWidth().padding(top = 6.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.width(70.dp).height(16.dp).clip(RoundedCornerShape(50.dp)).background(ctx.theme.colorScheme.onSurface))
            }
            Box(Modifier.fillMaxSize().padding(top = 26.dp)) { if (c.children.isNotEmpty()) RenderChildren(c.children, ctx) }
        }
    }
}

// ═══════════ 12. 胶囊 ═══════════

@Composable
fun CapsulePillRenderer(c: UIComponent, ctx: RenderContext) {
    val p = c.properties
    val text = p.s5("text", "胶囊") ?: "胶囊"
    val progress = p.f5("progress", -1f)
    Box(
        Modifier.fillMaxWidth().height(36.dp).clip(RoundedCornerShape(50.dp))
            .background(Brush.horizontalGradient(listOf(ctx.theme.colorScheme.info, ctx.theme.colorScheme.info)))
    ) {
        if (progress >= 0f) {
            Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).height(36.dp).clip(RoundedCornerShape(50.dp)).background(Color.White.copy(alpha = 0.35f)))
        }
        Text(text, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.Center))
    }
}

@Composable
fun DynamicIslandRenderer(c: UIComponent, ctx: RenderContext) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.Center
    ) {
        Row(
            Modifier.width(230.dp).clip(RoundedCornerShape(50.dp))
                .background(ctx.theme.colorScheme.onSurface).padding(horizontal = 14.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(c.properties.s5("left", "🎵") ?: "🎵", fontSize = 14.sp)
            Spacer(Modifier.weight(1f))
            if (c.children.isNotEmpty()) RenderChildren(c.children, ctx) else Text(c.properties.s5("text", "") ?: "", color = Color.White, fontSize = 12.sp)
            Spacer(Modifier.weight(1f))
            Text(c.properties.s5("right", "") ?: "", fontSize = 14.sp)
        }
    }
}

// ═══════════ 13. 代码编辑 ═══════════

@Composable
fun CodeEditorLineRenderer(c: UIComponent, ctx: RenderContext) {
    val p = c.properties
    val no = p.i5("line", 1)
    val code = p.s5("code", "") ?: ""
    val kwColor = when {
        code.trimStart().startsWith("//") || code.trimStart().startsWith("#") -> ctx.theme.colorScheme.outline
        "fun " in code || "class " in code || "def " in code -> ctx.theme.colorScheme.primary
        "return" in code -> ctx.theme.colorScheme.primary
        else -> ctx.theme.colorScheme.info
    }
    Row(Modifier.fillMaxWidth().background(ctx.theme.colorScheme.onSurface).padding(horizontal = 12.dp, vertical = 3.dp)) {
        Text("$no".padStart(3), color = ctx.theme.colorScheme.onSurfaceVariant, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        Spacer(Modifier.width(12.dp))
        Text(code, color = kwColor, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
    }
}

@Composable
fun DiffRowRenderer(c: UIComponent, ctx: RenderContext) {
    val kind = c.properties.s5("kind", "add") ?: "add"
    val text = c.properties.s5("text", "") ?: ""
    val bg = if (kind == "add") ctx.theme.colorScheme.onSurface else ctx.theme.colorScheme.onSurface
    val fg = if (kind == "add") ctx.theme.colorScheme.success else ctx.theme.colorScheme.error
    val sign = if (kind == "add") "+" else "-"
    Row(Modifier.fillMaxWidth().background(bg).padding(horizontal = 12.dp, vertical = 2.dp)) {
        Text("$sign $text", color = fg, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
    }
}

// ═══════════ 14. 节点编辑器 ═══════════

@Composable
fun NodeBoxRenderer(c: UIComponent, ctx: RenderContext) {
    val title = c.properties.s5("title", "节点") ?: "节点"
    val inputs = (c.properties["inputs"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content } ?: listOf("in")
    val outputs = (c.properties["outputs"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content } ?: listOf("out")
    Column(
        Modifier.width(170.dp).clip(RoundedCornerShape(10.dp))
            .background(ctx.theme.colorScheme.onSurface).border(1.dp, ctx.theme.colorScheme.info, RoundedCornerShape(10.dp))
    ) {
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp)).background(ctx.theme.colorScheme.info).padding(8.dp)) {
            Text(title, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
        Row(Modifier.padding(10.dp)) {
            Column { inputs.forEach { PortDot(it, ctx.theme.colorScheme.info) } }
            Spacer(Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.End) { outputs.forEach { PortDot(it, ctx.theme.colorScheme.primary) } }
        }
        if (c.children.isNotEmpty()) Column(Modifier.padding(8.dp)) { RenderChildren(c.children, ctx) }
    }
}

@Composable
private fun PortDot(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(5.dp))
        Text(label, color = ZorvPalette.Info, fontSize = 11.sp)
    }
}

@Composable
fun NodeConnectorRenderer(c: UIComponent, ctx: RenderContext) {
    val label = c.properties.s5("label", "") ?: ""
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
        Box(Modifier.fillMaxWidth().height(2.dp).background(Brush.horizontalGradient(listOf(ctx.theme.colorScheme.info, ctx.theme.colorScheme.primary))))
        if (label.isNotBlank()) Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ═══════════ 15. 运行时 ═══════════

@Composable
fun RuntimeLogRowRenderer(c: UIComponent, ctx: RenderContext) {
    val p = c.properties
    val level = p.s5("level", "INFO") ?: "INFO"
    val lvColor = when (level) { "ERROR" -> ctx.theme.colorScheme.error; "WARN" -> ctx.theme.colorScheme.primary; "DEBUG" -> ctx.theme.colorScheme.info; else -> ctx.theme.colorScheme.success }
    Row(Modifier.fillMaxWidth().background(ctx.theme.colorScheme.onSurface).padding(horizontal = 12.dp, vertical = 2.dp)) {
        Text(p.s5("time", "00:00") ?: "00:00", color = ctx.theme.colorScheme.onSurfaceVariant, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        Spacer(Modifier.width(8.dp))
        Text("[$level]", color = lvColor, fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(8.dp))
        Text(p.s5("message", "") ?: "", color = ctx.theme.colorScheme.info, fontSize = 11.sp, fontFamily = FontFamily.Monospace, maxLines = 2)
    }
}

@Composable
fun MemoryMeterRenderer(c: UIComponent, ctx: RenderContext) {
    val p = c.properties
    val used = p.i5("used", 0)
    val total = p.i5("total", 8)
    val frac = if (total > 0) used.toFloat() / total else 0f
    Column(Modifier.fillMaxWidth().padding(8.dp)) {
        Row { Text("内存", fontSize = 12.sp, fontWeight = FontWeight.Bold); Spacer(Modifier.weight(1f)); Text("$used / $total GB", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Spacer(Modifier.height(5.dp))
        Row(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(6.dp)).background(Color.LightGray.copy(alpha = 0.35f))) {
            Box(Modifier.fillMaxWidth(frac.coerceIn(0f, 1f)).fillMaxHeight0().clip(RoundedCornerShape(6.dp)).background(Brush.horizontalGradient(listOf(ctx.theme.colorScheme.success, ctx.theme.colorScheme.error))))
        }
    }
}

private fun Modifier.fillMaxHeight0(): Modifier = this.then(Modifier.fillMaxHeight(1f))

// ═══════════ 16. 语音 ═══════════

@Composable
fun VoiceMessageRenderer(c: UIComponent, ctx: RenderContext) {
    val duration = c.properties.i5("seconds", 12)
    Row(
        Modifier.clip(RoundedCornerShape(topStart = 4.dp, topEnd = 18.dp, bottomStart = 18.dp, bottomEnd = 18.dp))
            .background(MaterialTheme.colorScheme.primaryContainer).padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(26.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) { Text("▶", color = Color.White, fontSize = 11.sp) }
        Spacer(Modifier.width(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            listOf(6, 12, 8, 16, 10, 14, 7, 11, 9, 15).forEach { h ->
                Box(Modifier.width(3.dp).height(h.dp).clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)))
            }
        }
        Spacer(Modifier.width(8.dp))
        Text("\"$duration\"", fontSize = 12.sp, color = MaterialTheme.colorScheme.onPrimaryContainer)
    }
}

@Composable
fun MicButtonRenderer(c: UIComponent, ctx: RenderContext) {
    val active = c.properties.s5("state", "idle") == "recording"
    Box(Modifier.fillMaxWidth().padding(14.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier.size(64.dp).clip(CircleShape)
                .background(if (active) ctx.theme.colorScheme.error else MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center
        ) { Text("🎤", fontSize = 24.sp) }
    }
}
