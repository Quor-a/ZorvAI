package com.ai.assistance.quro.core.ui.card.widgets

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import com.ai.assistance.quro.core.ui.card.registry.CardRenderer
import com.ai.assistance.quro.core.ui.card.registry.CardState
import com.ai.assistance.quro.core.ui.card.registry.HitBox
import com.ai.assistance.quro.core.ui.card.registry.HitTarget
import com.ai.assistance.quro.core.ui.card.registry.LayoutResult
import com.ai.assistance.quro.core.ui.card.render.CanvasBackend
import com.ai.assistance.quro.core.ui.card.render.RenderBackend
import com.ai.assistance.quro.core.ui.card.spec.CardData
import com.ai.assistance.quro.core.ui.card.spec.CardSpec

/**
 * 四类自研渲染器（每组一个实现）。全部自写测量/排版/绘制/命中测试，
 * 不依赖任何内置/三方成品卡片控件。
 *
 * 这里以「默认底座 CanvasBackend」实现；VIEW / GL 底座复用同一套 measure/layout/hitTest，
 * 只是 render() 里换成 ViewBackend / GlBackend 下指令。
 */

// ── 1. 指标卡（数据可视化）──
class MetricCardState : CardState
class MetricCardRenderer : CardRenderer<MetricCardState> {
    override fun createInitialState() = MetricCardState()
    override fun measure(spec: CardSpec, state: MetricCardState, maxWidthPx: Float): Size {
        val h = 96f * (spec.style.fontSizeSp / 14f).coerceAtLeast(1f)
        return Size(maxWidthPx, h)
    }
    override fun layout(spec: CardSpec, state: MetricCardState, size: Size): LayoutResult =
        LayoutResult(size.width, size.height)
    override fun render(backend: RenderBackend, spec: CardSpec, result: LayoutResult, state: MetricCardState) {
        if (backend !is CanvasBackend) return
        backend.drawRect(0f, 0f, result.width, result.height, backend.resolve(com.ai.assistance.quro.core.ui.card.spec.ColorToken.SurfaceVariant), radiusDp = 12f)
        backend.drawText("指标", 16f, 28f, backend.resolve(com.ai.assistance.quro.core.ui.card.spec.ColorToken.OnSurface), 14f, 600)
    }
    override fun hitTest(p: Offset, result: LayoutResult, state: MetricCardState): HitTarget? = null
}

// ── 2. 折线图（数据可视化，逐点生长）──
class LineChartState(var phase: Float = 0f) : CardState {
    override fun canMerge(other: CardState) = other is LineChartState
}
class LineChartRenderer : CardRenderer<LineChartState> {
    override fun createInitialState() = LineChartState()
    override fun measure(spec: CardSpec, state: LineChartState, maxWidthPx: Float): Size {
        val d = spec.data
        val h = if (d is CardData.Chart) 180f else 160f
        return Size(maxWidthPx, h)
    }
    override fun layout(spec: CardSpec, state: LineChartState, size: Size): LayoutResult =
        LayoutResult(size.width, size.height)
    override fun render(backend: RenderBackend, spec: CardSpec, result: LayoutResult, state: LineChartState) {
        if (backend !is CanvasBackend) return
        val chart = spec.data as? CardData.Chart ?: return
        val pad = 16f
        val w = result.width - pad * 2
        val h = result.height - pad * 2
        // 轴线
        backend.drawLine(pad, pad, pad, pad + h, backend.resolve(com.ai.assistance.quro.core.ui.card.spec.ColorToken.Outline), 1f)
        backend.drawLine(pad, pad + h, pad + w, pad + h, backend.resolve(com.ai.assistance.quro.core.ui.card.spec.ColorToken.Outline), 1f)
        // 逐点生长：按 state.phase 截断点数
        chart.series.firstOrNull()?.let { s ->
            val n = s.points.size
            val drawN = ((n * state.phase).toInt()).coerceIn(1, n)
            val path = androidx.compose.ui.graphics.Path()
            s.points.take(drawN).forEachIndexed { i, v ->
                val x = pad + w * (i.toFloat() / (n - 1).coerceAtLeast(1))
                val y = pad + h * (1f - v.coerceIn(0f, 1f))
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            backend.drawPath(path, backend.resolve(s.color), 2f)
        }
    }
    override fun hitTest(p: Offset, result: LayoutResult, state: LineChartState): HitTarget? = null
}

// ── 3. 按钮组（交互控件）──
class ButtonGroupState : CardState
class ButtonGroupRenderer : CardRenderer<ButtonGroupState> {
    override fun createInitialState() = ButtonGroupState()
    override fun measure(spec: CardSpec, state: ButtonGroupState, maxWidthPx: Float): Size {
        val n = (spec.data as? CardData.Form)?.buttons?.size ?: 1
        val rowH = 48f
        return Size(maxWidthPx, rowH * ((n + 1) / 2).coerceAtLeast(1) + 16f)
    }
    override fun layout(spec: CardSpec, state: ButtonGroupState, size: Size): LayoutResult {
        val form = spec.data as? CardData.Form
        val boxes = form?.buttons?.mapIndexed { i, b ->
            val col = i % 2
            val row = i / 2
            HitBox("btn_$i", col * size.width / 2f + 8f, 8f + row * 56f, (col + 1) * size.width / 2f - 8f, 8f + row * 56f + 48f, i)
        } ?: emptyList()
        return LayoutResult(size.width, size.height, boxes)
    }
    override fun render(backend: RenderBackend, spec: CardSpec, result: LayoutResult, state: ButtonGroupState) {
        if (backend !is CanvasBackend) return
        result.boxes.forEach { box ->
            backend.drawRect(box.left, box.top, box.right, box.bottom, backend.resolve(com.ai.assistance.quro.core.ui.card.spec.ColorToken.Primary), radiusDp = 10f)
        }
    }
    override fun hitTest(p: Offset, result: LayoutResult, state: ButtonGroupState): HitTarget? {
        val box = result.boxes.firstOrNull { p.x in it.left..it.right && p.y in it.top..it.bottom } ?: return null
        return HitTarget(box, box.actionIndex)
    }
}

// ── 4. 骨架卡（流式状态，固定占位高度防抖动）──
class SkeletonState : CardState
class SkeletonRenderer : CardRenderer<SkeletonState> {
    override fun createInitialState() = SkeletonState()
    override fun measure(spec: CardSpec, state: SkeletonState, maxWidthPx: Float): Size =
        Size(maxWidthPx, 120f) // 固定占位高度，防流式抖动
    override fun layout(spec: CardSpec, state: SkeletonState, size: Size): LayoutResult =
        LayoutResult(size.width, size.height)
    override fun render(backend: RenderBackend, spec: CardSpec, result: LayoutResult, state: SkeletonState) {
        if (backend !is CanvasBackend) return
        // 画几根占位条
        repeat(3) { i ->
            val y = 20f + i * 32f
            backend.drawRect(16f, y, result.width - 16f, y + 16f, backend.resolve(com.ai.assistance.quro.core.ui.card.spec.ColorToken.SurfaceVariant), radiusDp = 6f)
        }
    }
    override fun hitTest(p: Offset, result: LayoutResult, state: SkeletonState): HitTarget? = null
}
