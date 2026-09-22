package com.ai.assistance.quro.genui.sdk.style

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * MaterialShapes Expressive 35 形状库（纯 Path 数学实现，无 androidx.graphics:graphics-shapes 依赖）
 *
 * 用法：style.shape 写形状名（camelCase 或 kebab 均可），由 resolveShape 路由：
 * circle / square / slanted / oval / pill / semi_circle / arch / triangle / diamond /
 * pentagon / gem / clamshell / fan / arrow / sunny / very_sunny / burst / soft_burst /
 * boom / soft_boom / flower / clover4 / clover8 / cookie4 / cookie6 / cookie7 / cookie9 /
 * cookie12 / puffy / puffy_diamond / pixel_circle / pixel_triangle / bun / heart / ghostish
 */
object ExpressiveShapes {

    private val alias = mapOf(
        "semicircle" to "semi_circle", "halfcircle" to "semi_circle",
        "clover4leaf" to "clover4", "clover8leaf" to "clover8",
        "cookie4sided" to "cookie4", "cookie6sided" to "cookie6",
        "cookie7sided" to "cookie7", "cookie9sided" to "cookie9", "cookie12sided" to "cookie12",
        "puffydiamond" to "puffy_diamond", "pixelcircle" to "pixel_circle", "pixeltriangle" to "pixel_triangle"
    )

    fun normalize(name: String): String {
        val kebab = name.trim().lowercase().replace(' ', '_').replace('-', '_')
        return alias[kebab] ?: kebab
    }

    /** 是否命中 35 形状库 */
    fun isExpressive(name: String): Boolean = build(normalize(name), 0f, 0f) != null

    /**
     * 构建 Path。width/height 为组件尺寸（px）。
     * @return null = 不是形状库名
     */
    fun build(nameRaw: String, width: Float, height: Float): Path? {
        if (width <= 0f || height <= 0f) return null
        val n = normalize(nameRaw)
        val w = width
        val h = height
        val cx = w / 2f
        val cy = h / 2f
        val r = minOf(w, h) / 2f
        return when (n) {
            "circle" -> Path().apply { addOval(Rect(0f, cy - r, w, cy + r)) }
            "square" -> Path().apply { addRect(Rect(0f, 0f, w, h)) }
            "slanted" -> Path().apply {
                moveTo(0f, 0f); lineTo(w, h * 0.15f); lineTo(w, h); lineTo(0f, h * 0.85f); close()
            }
            "oval" -> Path().apply { addOval(Rect(0f, 0f, w, h)) }
            "pill" -> Path().apply {
                val pr = minOf(w, h) / 2f
                addOval(Rect(0f, cy - pr, w, cy + pr))
            }
            "semi_circle" -> Path().apply {
                arcTo(Rect(0f, 0f, w, h * 2f), 180f, 180f, false); close()
            }
            "arch" -> Path().apply {
                // 拱形：底平顶弧
                moveTo(0f, h)
                arcTo(Rect(w * 0.1f, 0f, w * 0.9f, h * 2f), 180f, 180f, false)
                lineTo(w, h); close()
            }
            "triangle" -> roundedPolygon(w, h, 3, cornerRatio = 0.16f)
            "diamond" -> roundedPolygon(w, h, 4, cornerRatio = 0.14f, rotation = 0f)
            "pentagon" -> roundedPolygon(w, h, 5, cornerRatio = 0.12f, rotation = -90f)
            "gem" -> Path().apply {
                // 宝石：上宽下尖的六边
                moveTo(w * 0.5f, h)
                lineTo(0f, h * 0.38f)
                lineTo(w * 0.22f, 0f)
                lineTo(w * 0.78f, 0f)
                lineTo(w, h * 0.38f)
                close()
            }
            "clamshell" -> Path().apply {
                // 贝壳：扇贝
                val rr = minOf(w, h * 2f) / 2f
                arcTo(Rect(cx - rr, h - rr * 2f, cx + rr, h), 180f, 90f, false)
                // 底部扇贝波浪
                val bumps = 5
                val step = w / bumps
                var x = 0f
                lineTo(0f, h)
                repeat(bumps) { i ->
                    val nx = x + step
                    quadraticBezierTo(x + step / 2f, h - h * 0.14f, nx, h)
                    x = nx
                }
                close()
            }
            "fan" -> Path().apply {
                moveTo(cx, h)
                arcTo(Rect(0f, 0f, w, h * 2f), 180f, 180f, false)
                close()
            }
            "arrow" -> Path().apply {
                val bw = w * 0.62f
                moveTo(0f, h * 0.28f)
                lineTo(bw, h * 0.28f); lineTo(bw, 0f); lineTo(w, h * 0.5f)
                lineTo(bw, h); lineTo(bw, h * 0.72f); lineTo(0f, h * 0.72f)
                close()
            }
            "sunny" -> starPath(w, h, points = 12, inner = 0.72f, round = 0.5f)
            "very_sunny" -> starPath(w, h, points = 16, inner = 0.68f, round = 0.6f)
            "burst" -> starPath(w, h, points = 12, inner = 0.5f, round = 0.35f)
            "soft_burst" -> starPath(w, h, points = 12, inner = 0.62f, round = 0.55f)
            "boom" -> starPath(w, h, points = 8, inner = 0.42f, round = 0.3f)
            "soft_boom" -> starPath(w, h, points = 8, inner = 0.55f, round = 0.5f)
            "flower" -> petalFlower(w, h, petals = 6, petalOut = 1f, petalIn = 0.5f)
            "clover4" -> petalFlower(w, h, petals = 4, petalOut = 0.98f, petalIn = 0.42f)
            "clover8" -> petalFlower(w, h, petals = 8, petalOut = 0.98f, petalIn = 0.5f)
            "cookie4" -> cookiePath(w, h, sides = 4)
            "cookie6" -> cookiePath(w, h, sides = 6)
            "cookie7" -> cookiePath(w, h, sides = 7)
            "cookie9" -> cookiePath(w, h, sides = 9)
            "cookie12" -> cookiePath(w, h, sides = 12)
            "puffy" -> puffySquare(w, h)
            "puffy_diamond" -> Path().apply {
                // 蓬松菱形：四瓣膨胀
                moveTo(cx, 0f)
                cubicTo(w, h * 0.28f, w, h * 0.72f, cx, h)
                cubicTo(0f, h * 0.72f, 0f, h * 0.28f, cx, 0f)
                close()
            }
            "pixel_circle" -> Path().apply {
                // 像素圆：阶梯化八边形
                val k = 0.30f
                moveTo(w * k, 0f); lineTo(w * (1 - k), 0f)
                lineTo(w, h * k); lineTo(w, h * (1 - k))
                lineTo(w * (1 - k), h); lineTo(w * k, h)
                lineTo(0f, h * (1 - k)); lineTo(0f, h * k)
                close()
            }
            "pixel_triangle" -> Path().apply {
                val k = 0.22f
                moveTo(w * k, h * (1 - k)); lineTo(w * (1 - k), h * (1 - k)); lineTo(cx, h * k)
                close()
            }
            "bun" -> Path().apply {
                // 小面包：上圆下平底
                moveTo(0f, h)
                cubicTo(-w * 0.05f, h * 0.4f, w * 0.16f, 0f, cx, 0f)
                cubicTo(w * 0.84f, 0f, w * 1.05f, h * 0.4f, w, h)
                close()
            }
            "heart" -> heartPath(w, h)
            "ghostish" -> Path().apply {
                // 幽灵：圆顶波浪底
                moveTo(0f, h * 0.42f)
                val top = Rect(0f, 0f, w, h * 0.84f)
                arcTo(top, 180f, 180f, false)
                val wave = w / 4f
                var x = w
                var i = 0
                while (x > 0f) {
                    quadraticBezierTo(x - wave / 2f, h * (if (i % 2 == 0) 1.02f else 0.9f), (x - wave).coerceAtLeast(0f), h * 0.42f + h * 0.42f * 0.0f + h * 0.42f)
                    x -= wave; i++
                }
                close()
            }
            else -> null
        }
    }

    /** 圆角正多边形 */
    private fun roundedPolygon(w: Float, h: Float, sides: Int, cornerRatio: Float, rotation: Float = -90f): Path {
        val cx = w / 2f; val cy = h / 2f
        val rx = w / 2f; val ry = h / 2f
        val pts = Array(sides) { i ->
            val a = Math.toRadians((rotation + i * 360f / sides).toDouble())
            Offset(cx + rx * cos(a).toFloat(), cy + ry * sin(a).toFloat())
        }
        val path = Path()
        // 简化圆角：顶点内缩 cornerRatio
        for (i in 0 until sides) {
            val prev = pts[(i + sides - 1) % sides]
            val cur = pts[i]
            val next = pts[(i + 1) % sides]
            val toPrev = prev - cur
            val toNext = next - cur
            val lp = cur + toPrev * cornerRatio
            val ln = cur + toNext * cornerRatio
            if (i == 0) path.moveTo(lp.x, lp.y) else path.lineTo(lp.x, lp.y)
            path.quadraticBezierTo(cur.x, cur.y, ln.x, ln.y)
        }
        path.close()
        return path
    }

    /** 星形/放射（round=内点内缩比例做圆角） */
    private fun starPath(w: Float, h: Float, points: Int, inner: Float, round: Float): Path {
        val cx = w / 2f; val cy = h / 2f
        val rx = w / 2f; val ry = h / 2f
        val path = Path()
        val total = points * 2
        val angles = FloatArray(total) { -90f + it * 360f / total }
        for (i in 0 until total) {
            val isOuter = i % 2 == 0
            val rad = if (isOuter) 1f else inner
            val a = Math.toRadians(angles[i].toDouble())
            val px = cx + rx * rad * cos(a).toFloat()
            val py = cy + ry * rad * sin(a).toFloat()
            if (i == 0) path.moveTo(px, py)
            else {
                // 用 quadratic 圆滑连接
                val pa = Math.toRadians((angles[i - 1] + angles[i]) / 2f * 1f + 0.0)
                val prevRad = if ((i - 1) % 2 == 0) 1f else inner
                val mid = (rad + prevRad) / 2f * round + ((rad + prevRad) / 2f) * (1 - round)
                val mx = cx + rx * mid * cos(pa).toFloat()
                val my = cy + ry * mid * sin(pa).toFloat()
                path.quadraticBezierTo(mx, my, px, py)
            }
        }
        path.close()
        return path
    }

    /** 花瓣花/三叶草（n 瓣，瓣尖外圈 petalOut、瓣间内圈 petalIn） */
    private fun petalFlower(w: Float, h: Float, petals: Int, petalOut: Float, petalIn: Float): Path {
        val cx = w / 2f; val cy = h / 2f
        val rx = w / 2f; val ry = h / 2f
        val path = Path()
        val steps = petals * 4
        var started = false
        for (i in 0 until steps) {
            val phase = i.toFloat() / steps
            val a = phase * 2f * PI.toFloat()
            // 花瓣半径波形：cos 驱动
            val wave = (1f + cos(a * petals).toFloat()) / 2f   // 0..1
            val rad = petalIn + (petalOut - petalIn) * (wave * wave * (3 - 2 * wave))  // smoothstep
            val px = cx + rx * rad * cos(a)
            val py = cy + ry * rad * sin(a)
            if (!started) { path.moveTo(px, py); started = true } else path.lineTo(px, py)
        }
        path.close()
        return path
    }

    /** Cookie：连续圆角多边形（每边中点外鼓） */
    private fun cookiePath(w: Float, h: Float, sides: Int): Path {
        val cx = w / 2f; val cy = h / 2f
        val rx = w / 2f; val ry = h / 2f
        val path = Path()
        // 顶点
        val verts = Array(sides) { i ->
            val a = Math.toRadians((-90.0 + i * 360.0 / sides))
            Offset(cx + rx * cos(a).toFloat(), cy + ry * sin(a).toFloat())
        }
        // 每边用两段 cubic 外鼓（控制点外推）
        for (i in 0 until sides) {
            val cur = verts[i]
            val next = verts[(i + 1) % sides]
            val mid = (cur + next) / 2f
            val outward = (mid - Offset(cx, cy)) * 0.35f
            val c1 = cur + (next - cur) * 0.25f + outward * 0.4f
            val c2 = cur + (next - cur) * 0.75f + outward * 0.4f
            if (i == 0) path.moveTo(cur.x, cur.y)
            path.cubicTo(c1.x, c1.y, c2.x, c2.y, next.x, next.y)
        }
        path.close()
        return path
    }

    /** 蓬松方（四边外鼓） */
    private fun puffySquare(w: Float, h: Float): Path {
        val path = Path()
        val k = 0.5f
        path.moveTo(w * 0.18f, 0f)
        path.cubicTo(w * (0.5f + k * 0.32f), -h * 0.06f, w * (1 + k * 0.10f), h * 0.16f, w, h * 0.5f)
        path.cubicTo(w * (1 + k * 0.10f), h * 0.84f, w * (0.5f + k * 0.32f), h * 1.06f, w * 0.82f, h)
        path.cubicTo(w * (0.5f - k * 0.32f), h * 1.06f, -w * k * 0.10f, h * 0.84f, 0f, h * 0.5f)
        path.cubicTo(-w * k * 0.10f, h * 0.16f, w * (0.5f - k * 0.32f), -h * 0.06f, w * 0.18f, 0f)
        path.close()
        return path
    }

    /** 心形 */
    private fun heartPath(w: Float, h: Float): Path {
        val path = Path()
        path.moveTo(w / 2f, h)
        path.cubicTo(-w * 0.28f, h * 0.62f, -w * 0.10f, -h * 0.05f, w * 0.5f, h * 0.24f)
        path.cubicTo(w * 1.10f, -h * 0.05f, w * 1.28f, h * 0.62f, w / 2f, h)
        path.close()
        return path
    }

    private operator fun Offset.div(s: Float) = Offset(x / s, y / s)
    private operator fun Offset.plus(o: Offset) = Offset(x + o.x, y + o.y)
    private operator fun Offset.minus(o: Offset) = Offset(x - o.x, y - o.y)
    private operator fun Offset.times(s: Float) = Offset(x * s, y * s)
    private operator fun Offset.unaryMinus() = Offset(-x, -y)
}
