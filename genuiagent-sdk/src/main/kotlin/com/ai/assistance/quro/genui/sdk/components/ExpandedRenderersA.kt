package com.ai.assistance.quro.genui.sdk.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.assistance.quro.genui.sdk.dsl.UIComponent
import com.ai.assistance.quro.genui.sdk.render.RenderContext
import com.ai.assistance.quro.genui.sdk.style.ColorParser
import com.ai.assistance.quro.genui.sdk.render.RenderChildren
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.shape.CircleShape
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private fun JsonObject.sA(key: String, def: String? = null): String? = (this[key] as? JsonPrimitive)?.content ?: def
private fun JsonObject.fA(key: String, def: Float = 0f): Float = (this[key] as? JsonPrimitive)?.content?.toFloatOrNull() ?: def
private fun JsonObject.iA(key: String, def: Int = 0): Int = (this[key] as? JsonPrimitive)?.content?.toIntOrNull() ?: def
private fun JsonObject.cA(key: String, def: Color): Color = ColorParser.toColor(sA(key), def)
private fun JsonObject.aA(key: String): List<String> = ((this[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }) ?: emptyList()
@Composable private fun themeA(): Color = MaterialTheme.colorScheme.primary

/** cube_3d — Canvas 等轴测 3D 立方体（可旋转动画） */
@Composable
fun Cube3dRenderer(c: UIComponent, ctx: RenderContext) {
    val sizeDp = c.properties.fA("size", 120f).coerceIn(40f, 240f)
    val t = rememberInfiniteTransition(label = "cube")
    val spin by t.animateFloat(0f, 1f, infiniteRepeatable(tween(4000, easing = LinearEasing)), label = "s")
    val top = c.properties.cA("top", ctx.theme.colorScheme.info)
    val left = c.properties.cA("left", ctx.theme.colorScheme.info)
    val right = c.properties.cA("right", ctx.theme.colorScheme.info)
    Canvas(Modifier.fillMaxWidth().height((sizeDp * 1.2f).dp)) {
        val s = sizeDp.dp.toPx() * (0.92f + 0.08f * spin) / 2f
        val cx = size.width / 2f; val cy = size.height / 2f
        val h = s * 0.5f
        // 顶面
        drawPath(Path().apply {
            moveTo(cx, cy - s); lineTo(cx + s * 0.87f, cy - s + h); lineTo(cx, cy - s + 2 * h); lineTo(cx - s * 0.87f, cy - s + h); close()
        }, top)
        // 左面
        drawPath(Path().apply {
            moveTo(cx - s * 0.87f, cy - s + h); lineTo(cx, cy - s + 2 * h); lineTo(cx, cy + 2 * h); lineTo(cx - s * 0.87f, cy + h); close()
        }, left)
        // 右面
        drawPath(Path().apply {
            moveTo(cx + s * 0.87f, cy - s + h); lineTo(cx, cy - s + 2 * h); lineTo(cx, cy + 2 * h); lineTo(cx + s * 0.87f, cy + h); close()
        }, right)
    }
}

/** iso_card — 2.5D 等轴测层叠卡（children 悬浮） */
@Composable
fun IsoCardRenderer(c: UIComponent, ctx: RenderContext) {
    val base = c.properties.cA("base", ctx.theme.colorScheme.onSurface)
    Box(Modifier.fillMaxWidth().padding(16.dp)) {
        Box(Modifier.fillMaxWidth().height(120.dp).clip(RoundedCornerShape(14.dp)).background(base.copy(alpha = 0.25f)).padding(top = 6.dp, start = 6.dp)) {
            Box(Modifier.fillMaxSize2().clip(RoundedCornerShape(14.dp)).background(base.copy(alpha = 0.55f)).padding(top = 6.dp, start = 6.dp)) {
                Box(Modifier.fillMaxSize2().clip(RoundedCornerShape(14.dp)).background(base).padding(14.dp)) {
                    RenderChildren(c.children, ctx)
                }
            }
        }
    }
}

private fun Modifier.fillMaxSize2(): Modifier = this.then(Modifier.fillMaxSize())

/** flat_shapes — 2D 扁平几何装饰行 */
@Composable
fun FlatShapesRenderer(c: UIComponent, ctx: RenderContext) {
    val colors = c.properties.aA("colors").ifEmpty { listOf("#60A5FA", "#F472B6", "#FBBF24", "#34D399") }
    Row(Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
        colors.take(6).forEachIndexed { i, hex ->
            val col = ColorParser.toColor(hex, themeA())
            Box(Modifier.size((36 + (i % 3) * 10).dp).clip(if (i % 2 == 0) CircleShape else RoundedCornerShape(6.dp)).background(col))
        }
    }
}

/** dimension_axis — 3D/4维 坐标轴可视化 */
@Composable
fun DimensionAxisRenderer(c: UIComponent, ctx: RenderContext) {
    val labels = c.properties.aA("labels").ifEmpty { listOf("X", "Y", "Z", "T") }
    Canvas(Modifier.fillMaxWidth().height(130.dp)) {
        val ox = size.width / 2f; val oy = size.height * 0.72f
        val ax = ctx.theme.colorScheme.info
        drawLine(ax, Offset(ox, oy), Offset(ox + size.width * 0.32f, oy), 3f)
        drawLine(ax, Offset(ox, oy), Offset(ox - size.width * 0.1f, oy - size.height * 0.7f), 3f)
        drawLine(ax, Offset(ox, oy), Offset(ox - size.width * 0.3f, oy - size.height * 0.18f), 3f)
        drawLine(ctx.theme.colorScheme.primary, Offset(ox, oy), Offset(ox + size.width * 0.14f, oy - size.height * 0.55f), 3f)
        listOf(Offset(ox + size.width * 0.32f, oy), Offset(ox - size.width * 0.1f, oy - size.height * 0.7f),
            Offset(ox - size.width * 0.3f, oy - size.height * 0.18f), Offset(ox + size.width * 0.14f, oy - size.height * 0.55f))
            .forEachIndexed { i, p -> drawCircle(Color.White.copy(alpha = 0.9f), 5f, p); }
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
        labels.take(4).forEach { Text(it, fontSize = 11.sp, color = MaterialTheme.colorScheme.outline) }
    }
}

/** cosmos_scene — 宇宙星场（闪烁星星+星云） */
@Composable
fun CosmosSceneRenderer(c: UIComponent, ctx: RenderContext) {
    val nebula = c.properties.cA("nebula", ctx.theme.colorScheme.primary)
    val stars = c.properties.iA("stars", 60).coerceIn(10, 150)
    val t = rememberInfiniteTransition(label = "cosmos")
    val tw by t.animateFloat(0f, 1f, infiniteRepeatable(tween(2200, easing = LinearEasing), RepeatMode.Reverse), label = "tw")
    Canvas(Modifier.fillMaxWidth().height(170.dp).clip(RoundedCornerShape(14.dp))) {
        drawRect(Brush.verticalGradient(listOf(ctx.theme.colorScheme.surfaceContainerHighest, ctx.theme.colorScheme.onSurface)))
        drawCircle(nebula.copy(alpha = 0.25f), size.width * 0.3f, Offset(size.width * 0.7f, size.height * 0.35f))
        drawCircle(nebula.copy(alpha = 0.15f), size.width * 0.2f, Offset(size.width * 0.25f, size.height * 0.6f))
        repeat(stars) { i ->
            val sx = (i * 137.5f) % size.width
            val sy = (i * 91.7f) % size.height
            val a = 0.25f + 0.75f * kotlin.math.abs(kotlin.math.sin(i.toFloat() + tw * 2f * Math.PI.toFloat()))
            drawCircle(Color.White.copy(alpha = a), (if (i % 7 == 0) 2.4f else 1.3f), Offset(sx, sy))
        }
    }
}

/** planet_card — 行星卡（光环） */
@Composable
fun PlanetCardRenderer(c: UIComponent, ctx: RenderContext) {
    val emoji = c.properties.sA("emoji", "🪐") ?: "🪐"
    val name = c.properties.sA("name", "土星") ?: "土星"
    val fact = c.properties.sA("fact", "") ?: ""
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(ctx.theme.colorScheme.surfaceContainerHighest).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(emoji, fontSize = 32.sp)
        Column(Modifier.padding(start = 12.dp)) {
            Text(name, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            if (fact.isNotBlank()) Text(fact, color = ctx.theme.colorScheme.info, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

/** grain_overlay — 颗粒噪点质感层 */
@Composable
fun GrainOverlayRenderer(c: UIComponent, ctx: RenderContext) {
    val density = c.properties.iA("density", 90).coerceIn(20, 240)
    Canvas(Modifier.fillMaxWidth().height(110.dp).clip(RoundedCornerShape(14.dp))) {
        drawRect(ctx.theme.colorScheme.onSurface)
        repeat(density) { i ->
            val gx = (i * 173.3f) % size.width
            val gy = (i * 61.7f) % size.height
            drawCircle(Color.White.copy(alpha = 0.05f + (i % 5) * 0.03f), 1.6f, Offset(gx, gy))
        }
    }
}

/** particle_drift — 颗粒漂浮动画 */
@Composable
fun ParticleDriftRenderer(c: UIComponent, ctx: RenderContext) {
    val count = c.properties.iA("count", 24).coerceIn(6, 60)
    val color = c.properties.cA("color", ctx.theme.colorScheme.info)
    val t = rememberInfiniteTransition(label = "drift")
    val ph by t.animateFloat(0f, 1f, infiniteRepeatable(tween(3600, easing = LinearEasing)), label = "p")
    Canvas(Modifier.fillMaxWidth().height(120.dp)) {
        repeat(count) { i ->
            val baseX = (i * 97.3f) % size.width
            val baseY = (i * 53.1f) % size.height
            val y = ((baseY - ph * size.height) % size.height + size.height) % size.height
            drawCircle(color.copy(alpha = 0.15f + (i % 4) * 0.12f), 2f + (i % 3), Offset(baseX, y))
        }
    }
}

/** nebula_pill — 星云胶囊（渐变+漂浮点） */
@Composable
fun NebulaPillRenderer(c: UIComponent, ctx: RenderContext) {
    val g1 = c.properties.cA("from", ctx.theme.colorScheme.info)
    val g2 = c.properties.cA("to", ctx.theme.colorScheme.primary)
    Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier.clip(RoundedCornerShape(50.dp))
                .background(Brush.linearGradient(listOf(g1, g2)))
                .padding(horizontal = 22.dp, vertical = 10.dp)
        ) { Text(c.properties.sA("text", "∞ 星云无限") ?: "∞ 星云无限", color = Color.White, fontWeight = FontWeight.Bold) }
    }
}

/** orbit_ring — 轨道环（中心+环绕点动画） */
@Composable
fun OrbitRingRenderer(c: UIComponent, ctx: RenderContext) {
    val t = rememberInfiniteTransition(label = "orbit")
    val ang by t.animateFloat(0f, 1f, infiniteRepeatable(tween(3000, easing = LinearEasing)), label = "a")
    Canvas(Modifier.size(110.dp)) {
        val cx = size.width / 2f; val cy = size.height / 2f; val r = size.minDimension / 2f - 6f
        drawCircle(ctx.theme.colorScheme.info, r, Offset(cx, cy), style = androidx.compose.ui.graphics.drawscope.Stroke(2f))
        val a = ang * 2f * Math.PI.toFloat()
        drawCircle(ctx.theme.colorScheme.info, 6f, Offset(cx + r * kotlin.math.cos(a), cy + r * kotlin.math.sin(a)))
        drawCircle(ctx.theme.colorScheme.primary, 6f, Offset(cx - r * kotlin.math.cos(a), cy - r * kotlin.math.sin(a)))
        drawCircle(ctx.theme.colorScheme.info, 12f, Offset(cx, cy))
    }
}
