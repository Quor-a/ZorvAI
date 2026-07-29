package com.ai.assistance.quro.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 悬浮语音球 UI：3D 粒子球视觉。
 * 由 QuroVoiceBallService 通过 WindowManager 挂到屏幕上。
 *
 * 视觉规格：
 *  - 球面约 2000 个粒子，HSL 彩虹配色（按位置取 hue、饱和 0.9、亮度带随机），多层壳（体积感）；
 *  - 整体绕 Y 轴自转 + 轻微 X 轴摆动；
 *  - 呼吸（每粒子独立相位）+ 噪声流动（表面持续起伏）+ 点击/启动冲击波（径向涟漪）+ 能量膨胀（激活时微胀）；
 *  - 加法发光（BlendMode.Plus）+ 暗色球体核心，背景稀疏星点；
 *  - 状态外圈光环：listening=红 / speaking=蓝 / paused=灰(粒子变暗) / 待命=主色。
 *
 * 实现：Compose Canvas，零 WebView / 零网络依赖、离线可用。
 * 尺寸 72dp，适合作为悬浮球。
 *
 * 去点击：点击改由 Service 的触摸层处理（拖动/轻点），避免双重触发。
 */
private data class BallPoint(
    val bx: Float, val by: Float, val bz: Float,
    val color: Color, val phase: Float,
)
private data class Star(val xf: Float, val yf: Float, val r: Float, val a: Float)

@Composable
fun QuroVoiceBall(listening: Boolean, speaking: Boolean, paused: Boolean, status: String) {
    val transition = rememberInfiniteTransition(label = "ballRot")
    // 自转角，同时充当时间相位 t（0→2π 无缝循环，驱动呼吸/噪声）
    val angleY by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2f * PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(9000, easing = LinearEasing)),
    )
    val breatheT by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            tween(2600, easing = FastOutSlowInEasing),
            RepeatMode.Reverse,
        ),
    )

    // 点击/启动冲击波：listening 或 speaking 任一变 true 时炸开一次，0.9s 衰减
    val pulseAnim = remember { Animatable(0f) }
    LaunchedEffect(listening, speaking) {
        if (listening || speaking) {
            pulseAnim.snapTo(1f)
            pulseAnim.animateTo(0f, tween(900, easing = FastOutSlowInEasing))
        }
    }
    val pulse = pulseAnim.value

    // 激活能量（录音/播放时整体微胀）
    val energy = if (listening || speaking) 0.05f else 0f

    val ringColor = when {
        listening -> Color(0xFFEF4444)
        speaking -> Color(0xFF3B82F6)
        paused -> Color(0xFF9CA3AF)
        else -> MaterialTheme.colorScheme.primary
    }

    // 粒子：Fibonacci 球面 + 多层壳（0.82~1.0）+ HSL 彩虹 + 独立相位，仅算一次
    val points = remember {
        val n = 2000
        val golden = PI * (3 - sqrt(5.0))
        List(n) { i ->
            val yv = 1f - (i + 0.5f) / n * 2f // -1..1
            val shell = 0.82f + Math.random().toFloat() * 0.18f // 体积壳层
            val rr = sqrt(1f - yv * yv)
            val theta = golden * i
            val x = cos(theta) * rr * shell
            val z = sin(theta) * rr * shell
            val hue = (((theta / (2 * PI)) + yv * 0.15) % 1.0).toFloat()
            val light = 0.5f + Math.random().toFloat() * 0.2f
            BallPoint(
                x.toFloat(), yv * shell, z.toFloat(),
                Color.hsv(hue, 0.9f, light),
                Math.random().toFloat(),
            )
        }
    }
    // 背景星点（稀疏、暗）
    val stars = remember {
        List(36) {
            Star(
                Math.random().toFloat(), Math.random().toFloat(),
                Math.random().toFloat() * 0.8f + 0.3f,
                Math.random().toFloat() * 0.22f + 0.08f,
            )
        }
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(modifier = Modifier.size(72.dp)) {
            val w = size.width
            val h = size.height
            val cx = w / 2f
            val cy = h / 2f
            val R = minOf(w, h) * 0.36f
            val focal = R * 2.6f

            // 暗色球体核心：让粒子加法发光更明显
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Color(0xFF0B0B16), Color(0xFF000000)),
                    center = Offset(cx, cy),
                    radius = R * 1.15f,
                ),
                radius = R * 1.15f,
                center = Offset(cx, cy),
            )

            // 背景星点
            for (s in stars) {
                drawCircle(
                    color = Color(0x88AAFF),
                    radius = s.r * (w * 0.012f),
                    center = Offset(cx + (s.xf - 0.5f) * w * 0.92f, cy + (s.yf - 0.5f) * h * 0.92f),
                    alpha = s.a,
                    blendMode = BlendMode.Plus,
                )
            }

            val t = angleY
            val ay = t
            val ax = 0.35f + 0.12f * sin(t * 0.5f)
            val cosY = cos(ay); val sinY = sin(ay)
            val cosX = cos(ax); val sinX = sin(ax)
            val dim = if (paused) 0.4f else 1f

            for (p in points) {
                // 绕 Y 轴
                val x1 = p.bx * cosY + p.bz * sinY
                val z1 = -p.bx * sinY + p.bz * cosY
                val y1 = p.by
                // 绕 X 轴
                val y2 = y1 * cosX - z1 * sinX
                val z2 = y1 * sinX + z1 * cosX
                val x2 = x1

                // 单位向量（用于径向位移）
                val len0 = sqrt(x2 * x2 + y2 * y2 + z2 * z2).coerceAtLeast(1e-4f)
                val ux = x2 / len0; val uy = y2 / len0; val uz = z2 / len0

                // 呼吸（每粒子独立相位）
                val breathe = sin(t * 0.8f + p.phase * 6.2831f) * 0.025f
                // 噪声流动（表面持续起伏）
                val flow = sin(x2 * 4.4f + t * 0.6f) *
                        cos(y2 * 4.4f + t * 0.5f) *
                        sin(z2 * 4.4f + t * 0.7f) * 0.035f
                // 点击/启动冲击波（径向涟漪）
                val shock = sin(len0 * 10f - pulse * 6f) * pulse * 0.10f
                val disp = breathe + flow + shock
                val scale = 1f + energy

                val x = x2 * scale + ux * disp
                val y = y2 * scale + uy * disp
                val z = z2 * scale + uz * disp

                val f = focal / (focal - z * R)
                val sx = cx + x * R * f
                val sy = cy - y * R * f
                val depth = (z + 1f) / 2f // 0..1，正面=1
                val alpha = (0.18f + 0.82f * depth) * dim
                val rad = (0.9f + 2.4f * depth) * f * (0.8f + 0.4f * p.phase)
                drawCircle(
                    color = p.color,
                    radius = rad,
                    center = Offset(sx, sy),
                    alpha = alpha,
                    blendMode = BlendMode.Plus,
                )
            }

            // 状态光环
            drawCircle(
                color = ringColor,
                radius = R * 1.12f,
                center = Offset(cx, cy),
                style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round),
                alpha = 0.9f,
            )
        }

        Spacer(Modifier.height(4.dp))
        Text(
            status,
            color = Color.White,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier
                .background(ringColor.copy(alpha = 0.85f), CircleShape)
                .clip(CircleShape)
                .padding(8.dp, 3.dp),
        )
    }
}
