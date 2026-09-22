package com.ai.assistance.quro.genui.sdk.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
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
private fun JsonObject.s7(key: String, def: String? = null): String? = (this[key] as? JsonPrimitive)?.content ?: def
private fun JsonObject.f7(key: String, def: Float = 0f): Float = (this[key] as? JsonPrimitive)?.content?.toFloatOrNull() ?: def
private fun JsonObject.i7(key: String, def: Int = 0): Int = (this[key] as? JsonPrimitive)?.content?.toIntOrNull() ?: def
private fun JsonObject.c7(key: String, def: Color): Color = ColorParser.toColor(s7(key), def)
private fun JsonObject.a7(key: String): List<String> = ((this[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }) ?: emptyList()
@Composable
private fun theme7(ctx: RenderContext): Color = MaterialTheme.colorScheme.primary

/** 漂浮卡片 — 带双层柔和投影与偏移装饰，营造悬浮感 */
@Composable
fun FloatPanelRenderer(c: UIComponent, ctx: RenderContext) {
    val tilt = c.properties.f7("tilt", 0f).coerceIn(-6f, 6f)
    Column(
        Modifier.fillMaxWidth().padding(16.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(Brush.linearGradient(listOf(Color(0xFFF8FAFC), Color(0xFFE2E8F0))))
            .border(1.dp, Color.White, RoundedCornerShape(20.dp))
            .padding(16.dp)
    ) { com.ai.assistance.quro.genui.sdk.render.RenderChildren(c.children, ctx) }
}

/** 屏幕流动 — 全屏循环流动的波浪渐变背景 */
@Composable
fun FlowBackgroundRenderer(c: UIComponent, ctx: RenderContext) {
    val c1 = c.properties.c7("from", Color(0xFF0F172A))
    val c2 = c.properties.c7("to", Color(0xFF1E3A8A))
    val inf = rememberInfiniteTransition(label = "flow")
    val phase by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(4000, easing = LinearEasing), RepeatMode.Restart), label = "p")
    Canvas(Modifier.fillMaxWidth().height(220.dp)) {
        drawRect(Brush.verticalGradient(listOf(c1, c2)))
        repeat(3) { layer ->
            val amp = (26f + layer * 14f)
            val baseY = size.height * (0.42f + layer * 0.2f)
            val shift = phase * 2f * Math.PI.toFloat() + layer * 1.7f
            val path = Path()
            path.moveTo(0f, size.height)
            var x = 0f
            while (x <= size.width) {
                val y = baseY + amp * kotlin.math.sin(x / size.width * 2f * Math.PI.toFloat() * (1.4f + layer * 0.5f) + shift)
                path.lineTo(x, y)
                x += 12f
            }
            path.lineTo(size.width, size.height)
            path.close()
            drawPath(path, Color.White.copy(alpha = 0.06f + layer * 0.05f))
        }
    }
}

/** 小说阅读页 — 舒适行距排版 */
@Composable
fun NovelReaderRenderer(c: UIComponent, ctx: RenderContext) {
    val p = c.properties
    Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 14.dp)) {
        Text(p.s7("chapter", "第一章") ?: "第一章", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(10.dp))
        Text(
            p.s7("content", "") ?: "",
            fontSize = 16.sp, lineHeight = 30.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(12.dp))
        Text("— ${p.s7("author", "") ?: ""} —", fontSize = 11.sp, color = MaterialTheme.colorScheme.outline, modifier = Modifier.align(Alignment.CenterHorizontally))
    }
}

@Composable
fun ChapterRowRenderer(c: UIComponent, ctx: RenderContext) {
    val p = c.properties
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(p.s7("index", "01") ?: "01", fontSize = 13.sp, color = theme7(ctx), fontWeight = FontWeight.Black)
        Spacer(Modifier.width(12.dp))
        Text(p.s7("title", "") ?: "", fontSize = 15.sp, modifier = Modifier.weight(1f), maxLines = 1)
        Text(p.s7("meta", "") ?: "", fontSize = 11.sp, color = MaterialTheme.colorScheme.outline)
    }
}

/** 像素画 — colors 传 "行,列:十六进制色" 网格 */
@Composable
fun PixelAvatarRenderer(c: UIComponent, ctx: RenderContext) {
    val rows = c.properties.a7("rows")
    val cell = c.properties.f7("cell", 14f)
    Canvas(Modifier.size((cell * (rows.firstOrNull()?.length?.coerceAtLeast(1) ?: 8)).dp, (cell * rows.size.coerceAtLeast(1)).dp)) {
        rows.forEachIndexed { r, line ->
            line.forEachIndexed { col, ch ->
                val hex = when (ch) {
                    '1' -> c.properties.s7("c1", "#1D4ED8"); '2' -> c.properties.s7("c2", "#38BDF8")
                    '3' -> c.properties.s7("c3", "#F59E0B"); '4' -> c.properties.s7("c4", "#EF4444")
                    else -> null
                } ?: return@forEachIndexed
                val color = ColorParser.toColor(hex, Color.Transparent)
                if (color != Color.Transparent) drawRect(color, Offset(col * cell, r * cell), androidx.compose.ui.geometry.Size(cell, cell))
            }
        }
    }
}

@Composable
fun PixelBannerRenderer(c: UIComponent, ctx: RenderContext) {
    Box(Modifier.fillMaxWidth().height(64.dp).background(Color(0xFF0F172A)), contentAlignment = Alignment.Center) {
        Text(c.properties.s7("text", "GAME OVER") ?: "GAME OVER", color = Color(0xFF38BDF8), fontSize = 20.sp, fontWeight = FontWeight.Black)
    }
}

/** 授权卡 — 密钥打码展示 */
@Composable
fun LicenseCardRenderer(c: UIComponent, ctx: RenderContext) {
    val p = c.properties
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0xFF0F172A)).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(p.s7("plan", "Pro 授权") ?: "Pro 授权", color = Color(0xFFFBBF24), fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Text("✓ 已激活", color = Color(0xFF4ADE80), fontSize = 12.sp)
        }
        Spacer(Modifier.height(8.dp))
        Text(p.s7("key", "XXXX-XXXX-XXXX") ?: "XXXX-XXXX-XXXX", color = Color(0xFF94A3B8), fontSize = 13.sp)
        Text("有效期至 ${p.s7("until", "2099-12-31") ?: ""}", color = Color(0xFF64748B), fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
fun AuthStepRowRenderer(c: UIComponent, ctx: RenderContext) {
    val p = c.properties
    val done = p.i7("state", 1)
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(26.dp).clip(CircleShape).background(if (done >= 1) theme7(ctx) else Color.LightGray), contentAlignment = Alignment.Center) {
            Text("${p.i7("step", 1)}", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(10.dp))
        Text(p.s7("text", "") ?: "", fontSize = 14.sp, color = if (done >= 1) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline)
    }
}

/** 地图定位卡 */
@Composable
fun MapPinCardRenderer(c: UIComponent, ctx: RenderContext) {
    val p = c.properties
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainerLow).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(42.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFFDCFCE7)), contentAlignment = Alignment.Center) { Text("📍", fontSize = 20.sp) }
        Column(Modifier.padding(start = 10.dp).weight(1f)) {
            Text(p.s7("place", "") ?: "", fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1)
            Text(p.s7("addr", "") ?: "", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
        Text("${p.f7("km", 0f)}km", fontSize = 12.sp, color = theme7(ctx), fontWeight = FontWeight.Bold)
    }
}

@Composable
fun RouteStepsRenderer(c: UIComponent, ctx: RenderContext) {
    val steps = c.properties.a7("steps")
    Column(Modifier.fillMaxWidth().padding(12.dp)) {
        steps.forEachIndexed { i, s ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(theme7(ctx)))
                if (i < steps.lastIndex) Box(Modifier.width(2.dp).height(18.dp).background(Color.LightGray))
            }
            if (i < steps.lastIndex) Spacer(Modifier.height(2.dp))
            Text(s, fontSize = 13.sp, modifier = Modifier.padding(start = 18.dp, bottom = 6.dp))
        }
    }
}

/** 软件图标 — squircle 渐变+emoji */
@Composable
fun AppIconRenderer(c: UIComponent, ctx: RenderContext) {
    val p = c.properties
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(56.dp).clip(RoundedCornerShape(14.dp))
                .background(Brush.linearGradient(listOf(p.c7("g1", Color(0xFF6366F1)), p.c7("g2", Color(0xFFA855F7))))),
            contentAlignment = Alignment.Center
        ) { Text(p.s7("emoji", "🚀") ?: "🚀", fontSize = 26.sp) }
        Text(p.s7("name", "") ?: "", fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp), maxLines = 1)
    }
}

@Composable
fun IconGridRenderer(c: UIComponent, ctx: RenderContext) {
    val emojis = c.properties.a7("emojis")
    val names = c.properties.a7("names")
    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        emojis.chunked(4).forEachIndexed { r, chunk ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                chunk.forEachIndexed { i, e ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest), contentAlignment = Alignment.Center) { Text(e, fontSize = 22.sp) }
                        Text(names.getOrNull(r * 4 + i) ?: "", fontSize = 10.sp, modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
        }
    }
}

/** 自然风景 — 层叠山丘+太阳 Canvas */
@Composable
fun SceneryCardRenderer(c: UIComponent, ctx: RenderContext) {
    val sky1 = c.properties.c7("sky1", Color(0xFF7DD3FC))
    val sky2 = c.properties.c7("sky2", Color(0xFFE0F2FE))
    val hill = c.properties.c7("hill", Color(0xFF16A34A))
    Canvas(Modifier.fillMaxWidth().height(150.dp)) {
        drawRect(Brush.verticalGradient(listOf(sky1, sky2)))
        drawCircle(Color(0xFFFDE047), 22f, Offset(size.width * 0.75f, size.height * 0.28f))
        val hill1 = Path().apply {
            moveTo(0f, size.height)
            quadraticBezierTo(size.width * 0.3f, size.height * 0.45f, size.width * 0.62f, size.height)
            close()
        }
        drawPath(hill1, hill.copy(alpha = 0.55f))
        val hill2 = Path().apply {
            moveTo(size.width * 0.35f, size.height)
            quadraticBezierTo(size.width * 0.75f, size.height * 0.55f, size.width, size.height * 0.72f)
            lineTo(size.width, size.height); close()
        }
        drawPath(hill2, hill)
    }
}
