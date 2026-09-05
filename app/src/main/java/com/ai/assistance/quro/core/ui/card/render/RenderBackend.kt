package com.ai.assistance.quro.core.ui.card.render

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType
import com.ai.assistance.quro.core.ui.card.spec.ColorToken

/**
 * 渲染层：三档可插拔底座的统一抽象。
 *
 * 关键约束（来自需求）：**绘制指令端上自己下**，不依赖任何内置/三方成品卡片控件。
 * 上层（编排层/布局层）只调用这里的 drawXxx，完全不知道底层是 Canvas / 自定义 View / GL。
 */
interface RenderBackend {
    fun drawRect(left: Float, top: Float, right: Float, bottom: Float, color: Color, radiusDp: Float = 0f)
    fun drawText(text: String, x: Float, y: Float, color: Color, sizeSp: Float, weight: Int = 400)
    fun drawLine(x1: Float, y1: Float, x2: Float, y2: Float, color: Color, widthDp: Float)
    fun drawPath(path: Path, color: Color, widthDp: Float = 1f, fill: Boolean = false)
    fun drawImage(bitmap: Any?, left: Float, top: Float, width: Float, height: Float)
    fun saveLayer()
    fun restore()
    fun clip(left: Float, top: Float, right: Float, bottom: Float)
    fun resolve(token: ColorToken): Color
}

/** 三档底座枚举。 */
enum class BackendKind { CANVAS, VIEW, GL }

/**
 * 默认底座：Compose `DrawScope` 自绘。
 * 绝大多数卡片走这里——声明式管状态，图表/形状全部自己下绘制指令。
 *
 * 文本走标准 [TextMeasurer] + DrawScope.drawText；形状/线条皆为端上自绘。
 */
class CanvasBackend(
    private val scope: DrawScope,
    private val tokenResolver: (ColorToken) -> Color,
    private val textMeasurer: TextMeasurer,
) : RenderBackend {
    private val densityFactor: Float = scope.density
    private fun dp(v: Float) = v * densityFactor
    private fun radiusPx(v: Float) = if (v <= 0f) 0f else dp(v)

    override fun drawRect(left: Float, top: Float, right: Float, bottom: Float, color: Color, radiusDp: Float) {
        val r = radiusPx(radiusDp)
        if (r > 0f) {
            scope.drawRoundRect(
                color = color,
                topLeft = Offset(left, top),
                size = Size(right - left, bottom - top),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(r, r),
            )
        } else {
            scope.drawRect(color, topLeft = Offset(left, top), size = Size(right - left, bottom - top))
        }
    }

    override fun drawText(text: String, x: Float, y: Float, color: Color, sizeSp: Float, weight: Int) {
        val style = TextStyle(
            color = color,
            fontSize = TextUnit(sizeSp, TextUnitType.Sp),
            fontWeight = if (weight >= 600) FontWeight.Bold else FontWeight.Normal,
        )
        scope.drawText(
            textMeasurer = textMeasurer,
            text = text,
            topLeft = Offset(x, y),
            style = style,
        )
    }

    override fun drawLine(x1: Float, y1: Float, x2: Float, y2: Float, color: Color, widthDp: Float) {
        scope.drawLine(color, Offset(x1, y1), Offset(x2, y2), strokeWidth = dp(widthDp))
    }

    override fun drawPath(path: Path, color: Color, widthDp: Float, fill: Boolean) {
        if (fill) scope.drawPath(path, color) else scope.drawPath(path, color, style = Stroke(width = dp(widthDp)))
    }

    override fun drawImage(bitmap: Any?, left: Float, top: Float, width: Float, height: Float) {
        // Bitmap 类型由宿主注入；此处留空（Base64/Android Bitmap 在宿主侧转 DrawScope 图片）。
    }

    override fun saveLayer() { /* DrawScope 无独立图层概念，noop */ }
    override fun restore() { /* noop */ }
    override fun clip(left: Float, top: Float, right: Float, bottom: Float) {
        // 持久裁剪：直接作用在底层原生 Canvas 上，后续绘制均受此裁剪约束。
        scope.drawContext.canvas.nativeCanvas.clipRect(left, top, right, bottom)
    }

    override fun resolve(token: ColorToken): Color = tokenResolver(token)
}

/**
 * 纯自定义 View 底座（零依赖降级 / 跨端同构场景）。
 * 自写 onMeasure/onDraw，行为完全可控。此处仅定义契约，具体 View 在 host 层按 type 实例化。
 */
interface ViewBackend : RenderBackend {
    /** 自写测量：返回期望尺寸（px）。 */
    fun onMeasure(constraints: Int): Pair<Int, Int>
    /** 自写绘制：在 Canvas 上自己下指令。 */
    fun onDraw(canvas: android.graphics.Canvas)
}

/**
 * Surface + GL 底座（高频动画 / 超密点云）。
 * 独立渲染线程，不阻塞消息流滚动。此处仅定义契约。
 */
interface GlBackend : RenderBackend {
    fun beginFrame()
    fun endFrame()
    fun setSurface(surface: Any?)
}
