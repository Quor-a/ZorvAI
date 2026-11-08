package com.ai.assistance.quro.genui.sdk.components

import com.ai.assistance.quro.genui.sdk.style.ZorvPalette

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.assistance.quro.genui.sdk.dsl.UIComponent
import com.ai.assistance.quro.genui.sdk.render.RenderContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.math.cos
import kotlin.math.sin

/**
 * 漂浮宠物引擎
 *
 * PetSpec：纯 JSON 可定义（AI 可写 / 用户可导入）：
 * {"name":"团子","body":"#FFB5C2,#FFD9E0","eye":"#3A2E39","accent":"#FF8FA3","form":"blob|cat|ghost","size":76}
 *
 * PetPhase：随 AI 状态切换（正在思考/思考完成/工具调用/规划中/生成回复/回复完成），
 * 驱动眼睛、身体摇摆、头顶标记与气泡文案。
 */
/**
 * 桌宠专属视觉常量：与悬浮层 PetVisuals 同一套深墨蓝 + 青蓝强调体系。
 * 不复用 ZorvPalette 的陶土粉，避免「通用 AI 可爱团子」的廉价感；
 * 用户自定义 spec 仍可覆盖任意颜色。
 */
private object PetArt {
    val Ink = Color(0xFF16223A)
    val InkDeep = Color(0xFF0D1526)
    val Accent = Color(0xFF86ACFF)
    val Violet = Color(0xFFB79BFF)
    val On = Color(0xFFF2F6FF)
    val Muted = Color(0xFF94A5C2)
    val Line = Color(0xFF31405C)
    val LineSoft = Color(0xFF243350)
    val Surface = Color(0xFF111A2B)
    val Success = Color(0xFF7FDEB2)
}

enum class PetPhase(val bubble: String) {
    IDLE(""),
    THINKING("正在思考…"),
    TOOL_CALLING(""),
    PLANNING("规划中…"),
    GENERATING("正在生成回复…"),
    DONE("回复完成"),
    /** 互动动作执行中（挥手/弹跳/聊天…）：气泡空，弹跳动画，label 由 statusText 承载。 */
    PERFORMING("")
}

/**
 * 细描边胶囊气泡（带尖角）：取代 Material Surface 的默认圆角卡片。
 * SDK 不依赖 app 模块，所以这里保留一份同款实现，保证两处桌宠视觉一致。
 */
@Composable
fun PetBubbleCard(text: String, modifier: Modifier = Modifier, accent: Color = PetArt.Accent) {
    if (text.isBlank()) return
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .background(PetArt.Surface.copy(alpha = 0.96f), RoundedCornerShape(999.dp))
                .border(1.dp, PetArt.Line, RoundedCornerShape(999.dp))
                .padding(horizontal = 11.dp, vertical = 6.dp),
            contentAlignment = Alignment.Center,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(5.dp).background(accent, CircleShape))
                Spacer(Modifier.width(7.dp))
                Text(text, color = PetArt.On, fontSize = 11.sp, fontWeight = FontWeight.Medium, maxLines = 3)
            }
        }
        Canvas(Modifier.size(10.dp, 6.dp)) {
            val w = size.width; val h = size.height
            drawPath(Path().apply {
                moveTo(0f, 0f); lineTo(w, 0f); lineTo(w / 2f, h); close()
            }, color = PetArt.Surface.copy(alpha = 0.96f))
            drawPath(Path().apply {
                moveTo(0f, 0f); lineTo(w / 2f, h); lineTo(w, 0f)
            }, color = PetArt.Line, style = Stroke(width = 1.5f, cap = StrokeCap.Round))
        }
    }
}

data class PetSpec(
    val name: String = "墨团",
    val body: List<Color> = listOf(Color(0xFF35507F), Color(0xFF16223A)),
    val eye: Color = Color(0xFF0B1220),
    val accent: Color = PetArt.Violet,
    val form: String = "blob",
    val size: Float = 76f
) {
    fun colorToHex(c: Color): String {
        val v = c.value.toLong()
        val argb = ((v shr 32) and 0xFFFFFFFFL).toInt()
        return String.format("#%06X", argb and 0xFFFFFF)
    }

    fun toJson(): String = buildString {
        append("{\"name\":\"").append(name).append("\"")
        append(",\"body\":[\"").append(colorToHex(body[0])).append("\",\"").append(colorToHex(body[1])).append("\"]")
        append(",\"eye\":\"").append(colorToHex(eye)).append("\"")
        append(",\"accent\":\"").append(colorToHex(accent)).append("\"")
        append(",\"form\":\"").append(form).append("\"")
        append(",\"size\":").append(size.toInt())
        append("}")
    }

    companion object {
        fun parse(obj: JsonObject?): PetSpec {
            if (obj == null) return PetSpec()
            fun color(key: String, base: List<Color>): List<Color>? =
                (obj[key] as? JsonPrimitive)?.content?.split(',')?.mapNotNull {
                    runCatching {
                        val h = it.trim().removePrefix("#")
                        when (h.length) {
                            6 -> Color(android.graphics.Color.parseColor("#$h"))
                            8 -> Color(android.graphics.Color.parseColor("#$h"))
                            else -> null
                        }
                    }.getOrNull()
                }?.takeIf { it.size >= 2 }
            val body = color("body", emptyList()) ?: color("color", emptyList())
            val eye = (obj["eye"] as? JsonPrimitive)?.content?.let {
                runCatching { Color(android.graphics.Color.parseColor(it.trim().removePrefix("#").let { h -> if (h.length == 6) "#$h" else h })) }.getOrNull()
            }
            val accent = (obj["accent"] as? JsonPrimitive)?.content?.let {
                runCatching { Color(android.graphics.Color.parseColor(it.trim().removePrefix("#").let { h -> if (h.length == 6) "#$h" else h })) }.getOrNull()
            }
            return PetSpec(
                name = (obj["name"] as? JsonPrimitive)?.content ?: "墨团",
                body = body ?: listOf(Color(0xFF35507F), Color(0xFF16223A)),
                eye = eye ?: Color(0xFF0B1220),
                accent = accent ?: PetArt.Violet,
                form = ((obj["form"] as? JsonPrimitive)?.content ?: "blob").lowercase(),
                size = ((obj["size"] as? JsonPrimitive)?.content?.toFloatOrNull() ?: 76f).coerceIn(48f, 160f)
            )
        }

        /** 从任意 JSON 字符串解析（导入用），失败返回 null */
        fun parseOrNull(text: String): PetSpec? = runCatching {
            val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; isLenient = true }
            val obj = json.parseToJsonElement(text.trim()).let {
                it as? JsonObject ?: (it as? kotlinx.serialization.json.JsonArray)?.firstOrNull() as? JsonObject
            } ?: return null
            parse(obj)
        }.getOrNull()
    }
}

/**
 * 宠物形象（Canvas 自绘：果冻团子/猫耳/幽灵 三形态，呼吸/摇摆/眨眼动画，状态气泡）
 */
@Composable
fun PetSprite(
    spec: PetSpec,
    phase: PetPhase,
    modifier: Modifier = Modifier,
    toolName: String? = null,
    showBubble: Boolean = true,
    sizeOverride: Float? = null
) {
    val transition = rememberInfiniteTransition(label = "pet")
    val breathe by transition.animateFloat(
        initialValue = 0f, targetValue = (2 * Math.PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(2600, easing = LinearEasing)), label = "breathe"
    )
    val blinkT by transition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(3200, easing = LinearEasing)), label = "blink"
    )
    val blink = if (blinkT > 0.94f) 0.12f else 1f
    val sway by transition.animateFloat(
        initialValue = -1f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(if (phase == PetPhase.THINKING) 900 else 2200, easing = LinearEasing), RepeatMode.Reverse),
        label = "sway"
    )
    val spin by transition.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(1500, easing = LinearEasing)), label = "spin"
    )
    val s = (sizeOverride ?: spec.size).dp
    val bubbleText = when {
        phase == PetPhase.TOOL_CALLING && !toolName.isNullOrBlank() -> "正在调用 $toolName"
        phase == PetPhase.TOOL_CALLING -> "工具调用中…"
        else -> phase.bubble
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = modifier) {
        if (showBubble && bubbleText.isNotBlank()) {
            PetBubbleCard(bubbleText)
        }
        Canvas(modifier = Modifier.size(s).padding(2.dp)) {
            val w = this.size.width
            val h = this.size.height
            // 地面影子（扁椭圆，随呼吸微缩放）
            drawOval(
                Color.Black.copy(alpha = 0.09f),
                topLeft = Offset(w * 0.16f, h * 0.955f),
                size = androidx.compose.ui.geometry.Size(
                    w * (0.68f + sin(breathe) * 0.01f), h * 0.045f
                )
            )
            val phaseT = when (phase) {
                PetPhase.IDLE -> 0f
                PetPhase.THINKING, PetPhase.PLANNING -> 1f
                PetPhase.TOOL_CALLING -> 2f
                PetPhase.GENERATING -> 3f
                PetPhase.DONE -> 4f
                PetPhase.PERFORMING -> 5f
            }
            // 🔴 PERFORMING：互动动作的弹跳（挥手/聊天等动作的通用视觉）
            val hop = if (phase == PetPhase.PERFORMING)
                -kotlin.math.abs(kotlin.math.sin(breathe * 2f)) * h * 0.06f else 0f
            // 身体（PERFORMING 时整体弹跳）
            translate(left = 0f, top = hop) {
                rotate(if (phase == PetPhase.THINKING || phase == PetPhase.PLANNING) sway * 4f else 0f, pivot = Offset(w / 2f, h)) {
                    scale(1f + sin(breathe) * 0.03f, 1f - sin(breathe) * 0.02f, pivot = Offset(w / 2f, h * 0.6f)) {
                        drawBody(w, h, spec, phaseT)
                    }
                }
            }
            // 眼睛
            translate(left = 0f, top = hop + if (phase == PetPhase.THINKING) -h * 0.02f * sway else 0f) {
                drawEyes(w, h, spec, phase, blink, sway)
            }
            // 头顶标记：思考=问号泡，工具=旋转环
            if (phase == PetPhase.THINKING || phase == PetPhase.PLANNING) {
                drawCircle(Color.White.copy(alpha = 0.85f), radius = w * 0.09f, center = Offset(w * 0.86f, h * 0.12f))
                drawCircle(PetArt.Accent, radius = w * 0.045f, center = Offset(w * 0.86f, h * 0.12f))
            }
            if (phase == PetPhase.TOOL_CALLING) {
                val c = Offset(w * 0.86f, h * 0.12f)
                val r = w * 0.08f
                repeat(3) { i ->
                    val a = spin + i * 120f
                    drawCircle(
                        PetArt.Violet.copy(alpha = 0.9f),
                        radius = w * 0.025f,
                        center = Offset(c.x + cos(Math.toRadians(a.toDouble())).toFloat() * r,
                            c.y + sin(Math.toRadians(a.toDouble())).toFloat() * r)
                    )
                }
            }
            if (phase == PetPhase.DONE) {
                drawCircle(PetArt.Success.copy(alpha = 0.9f), radius = w * 0.06f, center = Offset(w * 0.86f, h * 0.12f))
                drawCircle(Color.White, radius = w * 0.028f, center = Offset(w * 0.86f, h * 0.12f))
            }
        }
    }
}

private fun DrawScope.drawBody(w: Float, h: Float, spec: PetSpec, phaseT: Float) {
    val brush = Brush.verticalGradient(spec.body, startY = 0f, endY = h)
    when (spec.form) {
        "cat" -> {
            // 耳朵
            val ear = Path().apply {
                moveTo(w * 0.14f, h * 0.30f); lineTo(w * 0.08f, h * 0.02f); lineTo(w * 0.42f, h * 0.16f); close()
                moveTo(w * 0.86f, h * 0.30f); lineTo(w * 0.92f, h * 0.02f); lineTo(w * 0.58f, h * 0.16f); close()
            }
            drawPath(ear, brush)
            // 内耳（accent 色）
            val innerEar = Path().apply {
                moveTo(w * 0.19f, h * 0.26f); lineTo(w * 0.145f, h * 0.075f); lineTo(w * 0.345f, h * 0.175f); close()
                moveTo(w * 0.81f, h * 0.26f); lineTo(w * 0.855f, h * 0.075f); lineTo(w * 0.655f, h * 0.175f); close()
            }
            drawPath(innerEar, spec.accent.copy(alpha = 0.75f))
            drawPath(bodyPath(w, h, "blob"), brush)
        }
        "ghost" -> {
            val p = Path().apply {
                moveTo(0f, h * 0.55f)
                quadraticBezierTo(0f, 0f, w / 2f, 0f)
                quadraticBezierTo(w, 0f, w, h * 0.55f)
                val wave = w / 4f
                for (i in 0..3) {
                    quadraticBezierTo(
                        w - wave * i - wave / 2f, h * (if (i % 2 == 0) 0.92f else 1.0f),
                        w - wave * (i + 1), h * 0.55f
                    )
                }
                close()
            }
            drawPath(p, brush)
        }
        else -> drawPath(bodyPath(w, h, "blob"), brush)
    }
    // 身体轮廓描边（与形态一致，ghost 用自己的波浪路径）
    val outlinePath = if (spec.form == "ghost") {
        Path().apply {
            moveTo(0f, h * 0.55f)
            quadraticBezierTo(0f, 0f, w / 2f, 0f)
            quadraticBezierTo(w, 0f, w, h * 0.55f)
            val wave = w / 4f
            for (i in 0..3) {
                quadraticBezierTo(
                    w - wave * i - wave / 2f, h * (if (i % 2 == 0) 0.92f else 1.0f),
                    w - wave * (i + 1), h * 0.55f
                )
            }
            close()
        }
    } else bodyPath(w, h, "blob")
    drawPath(
        outlinePath,
        PetArt.InkDeep,
        style = androidx.compose.ui.graphics.drawscope.Stroke(width = w * 0.022f)
    )
    // 小脚（blob/cat：底部两个半圆）
    if (spec.form != "ghost") {
        val footColor = spec.body[1].copy(alpha = 0.9f)
        drawCircle(footColor, radius = w * 0.085f, center = Offset(w * 0.34f, h * 0.90f))
        drawCircle(footColor, radius = w * 0.085f, center = Offset(w * 0.66f, h * 0.90f))
    }
    // 顶部大高光（柔光椭圆；ghost 圆顶更小高光上移）
    val hlTop = if (spec.form == "ghost") 0.06f else 0.10f
    drawOval(
        Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.40f), Color.White.copy(alpha = 0.05f))),
        topLeft = Offset(w * 0.18f, h * hlTop),
        size = androidx.compose.ui.geometry.Size(w * 0.44f, h * 0.20f)
    )
    // 高光点
    drawCircle(Color.White.copy(alpha = 0.45f), radius = w * 0.07f, center = Offset(w * 0.32f, h * 0.24f))
}

private fun bodyPath(w: Float, h: Float, form: String): Path = Path().apply {
    // 胖椭圆体（矮胖 Q 版：宽 w、高 0.84h，底部贴 0.98h）
    addOval(Rect(0f, h * 0.14f, w, h * 0.98f))
}

private fun DrawScope.drawEyes(w: Float, h: Float, spec: PetSpec, phase: PetPhase, blink: Float, sway: Float) {
    val eyeY = h * 0.52f
    val eyeDX = w * 0.21f
    val eyeR = w * 0.088f
    // 瞳孔偏移：思考时上移+扫视；生成时跟随 sway
    val pupilShift = when (phase) {
        PetPhase.THINKING, PetPhase.PLANNING -> Offset(w * 0.02f * sway, -eyeR * 0.35f)
        PetPhase.GENERATING -> Offset(w * 0.03f * sway, 0f)
        else -> Offset.Zero
    }
    val closed = blink < 0.5f
    for (dir in listOf(-1f, 1f)) {
        val c = Offset(w / 2f + dir * eyeDX, eyeY)
        if (closed) {
            drawLine(spec.eye, Offset(c.x - eyeR, c.y), Offset(c.x + eyeR, c.y), strokeWidth = w * 0.03f)
        } else {
            drawCircle(Color.White, radius = eyeR, center = c)
            drawCircle(spec.eye, radius = eyeR * 0.55f, center = c + pupilShift)
            // 双高光：主高光 + 次高光
            drawCircle(Color.White, radius = eyeR * 0.22f, center = c + pupilShift - Offset(eyeR * 0.20f, eyeR * 0.22f))
            drawCircle(Color.White.copy(alpha = 0.7f), radius = eyeR * 0.11f, center = c + pupilShift + Offset(eyeR * 0.22f, eyeR * 0.12f))
        }
    }
    // 眉毛（短平圆头线，距眼留空，跟随各自眼睛水平；思考时左眉微挑）
    for (dir in listOf(-1f, 1f)) {
        val browCx = w / 2f + dir * eyeDX
        val lift = if (phase == PetPhase.THINKING || phase == PetPhase.PLANNING)
            (if (dir < 0) eyeR * 0.35f else 0f) else 0f
        val browY = eyeY - eyeR * 1.9f - lift
        drawLine(
            spec.eye.copy(alpha = 0.7f),
            Offset(browCx - eyeR * 0.5f, browY + eyeR * 0.08f),
            Offset(browCx + eyeR * 0.5f, browY - eyeR * 0.08f),
            strokeWidth = w * 0.022f,
            cap = androidx.compose.ui.graphics.StrokeCap.Round
        )
    }
    // 鼻子（小圆点，accent 深色调）
    drawCircle(spec.accent.copy(alpha = 0.9f), radius = w * 0.028f, center = Offset(w / 2f, h * 0.595f))
    // ω 嘴（两段弧线，从鼻子下方展开）
    val mouthY = h * 0.625f
    drawArc(
        spec.eye.copy(alpha = 0.75f),
        startAngle = 160f, sweepAngle = 200f, useCenter = false,
        topLeft = Offset(w / 2f - w * 0.085f, mouthY - w * 0.045f),
        size = androidx.compose.ui.geometry.Size(w * 0.085f, w * 0.075f),
        style = androidx.compose.ui.graphics.drawscope.Stroke(width = w * 0.018f)
    )
    drawArc(
        spec.eye.copy(alpha = 0.75f),
        startAngle = 180f, sweepAngle = 180f, useCenter = false,
        topLeft = Offset(w / 2f + w * 0.001f, mouthY - w * 0.045f),
        size = androidx.compose.ui.geometry.Size(w * 0.085f, w * 0.075f),
        style = androidx.compose.ui.graphics.drawscope.Stroke(width = w * 0.018f)
    )
    // 腮红
    drawCircle(spec.accent.copy(alpha = 0.4f), radius = w * 0.07f, center = Offset(w / 2f - w * 0.30f, h * 0.63f))
    drawCircle(spec.accent.copy(alpha = 0.4f), radius = w * 0.07f, center = Offset(w / 2f + w * 0.30f, h * 0.63f))
}

/** "pet" 组件渲染器：AI 在 GenUI 页面里直接写 {"type":"pet","properties":{"spec":{...},"phase":"thinking"}} */
@Composable
fun PetComponentRenderer(component: UIComponent, ctx: RenderContext, modifier: Modifier = Modifier) {
    val specObj = component.properties["spec"] as? JsonObject
        ?: (component.properties["spec"] as? JsonPrimitive)?.content?.let { txt ->
            runCatching { kotlinx.serialization.json.Json { ignoreUnknownKeys = true; isLenient = true }
                .parseToJsonElement(txt) as? JsonObject }.getOrNull()
        }
    val spec = remember(specObj) { PetSpec.parse(specObj) }
    val phase = when (component.propString("phase")?.lowercase()) {
        "thinking" -> PetPhase.THINKING
        "tool" -> PetPhase.TOOL_CALLING
        "planning" -> PetPhase.PLANNING
        "generating" -> PetPhase.GENERATING
        "done" -> PetPhase.DONE
        else -> PetPhase.IDLE
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        PetSprite(spec, phase, toolName = component.propString("toolName"), showBubble = true)
    }
}


// ═══════════════════════════════════════════════════════════════════
// spritesheet 帧动画渲染：
// 位图网格（24×24 viewBox 语义：按 cellWidth/cellHeight 裁帧），姿态→动画行
// 姿态候选映射，帧推进 loop/once/hold，drawImage 源矩形裁剪。
// 无包时仍走上方 Canvas 矢量 PetSprite（内置形象兜底）。
// ═══════════════════════════════════════════════════════════════════

/** 姿态 → 动画名候选。pose 用 13 标准动画名小写域。 */
fun petPoseAnimationCandidates(pose: String, facingRight: Boolean, mode: String): List<String> = when (pose) {
    "stand" -> listOf("stand")
    "fall" -> listOf("fall")
    "dragged", "edgeattached" -> listOf("dragged")
    "thrown" -> listOf("thrown")
    "celebrate" -> listOf("celebrate")
    "think" -> listOf("think")
    // 🔴 2026-11-07 修复：候选名必须与 PetPack.REQUIRED_ANIMATIONS 的 camelCase key 一致，
    //    此前全小写导致 animations[it] 永远查不到、落到兜底 stand 行，帧动画形同失效。
    "usetool" -> listOf("useTool")
    "reply" -> listOf("reply")
    "walk" -> if (mode == "pinned") listOf("stand")
             else listOf(if (facingRight) "walkRight" else "walkLeft", "stand")
    "climb" -> listOf(if (facingRight) "climbRightEdge" else "climbLeftEdge", "dragged")
    else -> listOf(pose, "stand")
}

/**
 * spritesheet 宠物渲染。
 * @param bitmap     整张 spritesheet 位图
 * @param animations 动画名→定义（row/frames/duration/playback）
 * @param pose       13 标准姿态名（小写）
 * @param clockMs    系统时间毫秒（驱动帧推进）
 */
@Composable
fun PetSpriteFrames(
    bitmap: androidx.compose.ui.graphics.ImageBitmap,
    cellWidth: Int,
    cellHeight: Int,
    displayWidth: Int,
    displayHeight: Int,
    animations: Map<String, PetPackAnimDef>,
    pose: String,
    facingRight: Boolean = true,
    mode: String = "physics",
    bubbleText: String = "",
    /** 当前动作所在的分片位图；宠物包把上百段动作拆成多张图时由外层按需提供，
     *  避免一次性解码单张巨型雪碧图。null 时仍用整包主图 [bitmap]。 */
    sheetBitmap: androidx.compose.ui.graphics.ImageBitmap? = null,
    modifier: Modifier = Modifier,
    scale: Float = 1f,
) {
    // 候选行：按顺序找第一个存在的动画，兜底 stand，再兜底首个
    val def = petPoseAnimationCandidates(pose.lowercase(), facingRight, mode)
        .firstNotNullOfOrNull { animations[it] }
        ?: animations.values.firstOrNull()
    val w = (displayWidth * scale).dp
    val h = (displayHeight * scale).dp
    // 帧推进：loop 循环 / once 停末帧 / hold 停首帧
    var frameIdx by remember(def) { mutableIntStateOf(0) }
    if (def != null && def.frames > 1) {
        androidx.compose.runtime.LaunchedEffect(def) {
            while (true) {
                // 外部导入的包可能写 0/1ms，不设下限会变成忙循环把主线程打满。
                kotlinx.coroutines.delay(def.frameDurationMs.coerceAtLeast(16L))
                frameIdx = when (def.playback.lowercase()) {
                    "once" -> (frameIdx + 1).coerceAtMost(def.frames - 1)
                    "hold" -> 0
                    else -> (frameIdx + 1) % def.frames
                }
            }
        }
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = modifier) {
        if (bubbleText.isNotBlank()) {
            PetBubbleCard(bubbleText)
        }
        Canvas(Modifier.size(w, h).padding(2.dp)) {
            val d = def ?: return@Canvas
            val col = frameIdx % d.frames.coerceAtLeast(1)
            // 朝向不镜像——walkRight/walkLeft 用不同动画行
            drawImage(
                image = sheetBitmap ?: bitmap,
                srcOffset = androidx.compose.ui.unit.IntOffset(col * cellWidth, d.row * cellHeight),
                srcSize = androidx.compose.ui.unit.IntSize(cellWidth, cellHeight),
                dstSize = androidx.compose.ui.unit.IntSize(this.size.width.toInt(), this.size.height.toInt()),
                filterQuality = androidx.compose.ui.graphics.FilterQuality.Low,
            )
        }
    }
}

/** PetPack.AnimDef 的 SDK 侧轻量镜像（SDK 不依赖 app 模块）。 */
data class PetPackAnimDef(val row: Int, val frames: Int, val frameDurationMs: Long, val playback: String)
