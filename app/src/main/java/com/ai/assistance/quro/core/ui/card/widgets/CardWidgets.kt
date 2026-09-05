package com.ai.assistance.quro.core.ui.card.widgets

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import com.ai.assistance.quro.core.ui.card.registry.CardRenderer
import com.ai.assistance.quro.core.ui.card.registry.CardState
import com.ai.assistance.quro.core.ui.card.registry.HitBox
import com.ai.assistance.quro.core.ui.card.registry.HitTarget
import com.ai.assistance.quro.core.ui.card.registry.LayoutResult
import com.ai.assistance.quro.core.ui.card.render.RenderBackend
import com.ai.assistance.quro.core.ui.card.spec.CardData
import com.ai.assistance.quro.core.ui.card.spec.CardSpec
import com.ai.assistance.quro.core.ui.card.spec.ColorToken

/**
 * 四类自研渲染器（每组一个实现）。全部自写测量/排版/绘制/命中测试，
 * 不依赖任何内置/三方成品卡片控件。
 *
 * 这里以「默认底座 CanvasBackend」实现；VIEW / GL 底座复用同一套 measure/layout/hitTest，
 * 只是 render() 里换成 ViewBackend / GlBackend 下指令。
 * 渲染器只调用 [RenderBackend] 接口方法（不依赖具体底座），因此三种底座都能渲染——
 * 故不再做 `is CanvasBackend` 守卫（否则 VIEW/GL 底座会被直接跳过）。
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
        backend.drawRect(0f, 0f, result.width, result.height, backend.resolve(ColorToken.SurfaceVariant), radiusDp = 12f)
        backend.drawText("指标", 16f, 24f, backend.resolve(ColorToken.OnSurface), 14f, 600)
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
        val chart = spec.data as? CardData.Chart ?: return
        val pad = 16f
        val w = result.width - pad * 2
        val h = result.height - pad * 2
        // 坐标轴
        backend.drawLine(pad, pad, pad, pad + h, backend.resolve(ColorToken.Outline), 1f)
        backend.drawLine(pad, pad + h, pad + w, pad + h, backend.resolve(ColorToken.Outline), 1f)
        chart.series.firstOrNull()?.let { s ->
            val n = s.points.size
            if (n == 0) return@let
            // 静态渲染（phase<=0，常规情况）画全部点；流式动画期按 phase 截断「逐点生长」。
            // 修复：此前 phase 恒为 0 且无动画推进，drawN 被钳成 1 → 折线图只画 1 个点、几乎空白。
            val drawN = if (state.phase <= 0f) n else ((n * state.phase).toInt()).coerceIn(1, n)
            val path = androidx.compose.ui.graphics.Path()
            s.points.take(drawN).forEachIndexed { i, v ->
                val x = pad + w * (i.toFloat() / (n - 1).coerceAtLeast(1))
                val y = pad + h * (1f - v.coerceIn(0f, 1f))
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            backend.drawPath(path, backend.resolve(s.color), 2f)
            if (s.name.isNotEmpty()) {
                backend.drawText(s.name, pad, 4f, backend.resolve(ColorToken.OnSurfaceVariant), 12f, 400)
            }
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
        val form = spec.data as? CardData.Form
        result.boxes.forEach { box ->
            backend.drawRect(box.left, box.top, box.right, box.bottom, backend.resolve(ColorToken.Primary), radiusDp = 10f)
            // 修复：此前只画了主色矩形条、没画按钮文字，看起来是一排色块。
            val label = form?.buttons?.getOrNull(box.actionIndex)?.label ?: ""
            if (label.isNotEmpty()) {
                val sizeSp = 15f
                val cx = (box.left + box.right) / 2f
                val cy = (box.top + box.bottom) / 2f
                // 粗略水平居中（按字号估算字宽），竖直按基线居中
                val estW = label.length * sizeSp * 0.55f
                backend.drawText(label, cx - estW / 2f, cy - sizeSp / 2f, backend.resolve(ColorToken.OnPrimary), sizeSp, 600)
            }
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
        // 画几根占位条
        repeat(3) { i ->
            val y = 20f + i * 32f
            backend.drawRect(16f, y, result.width - 16f, y + 16f, backend.resolve(ColorToken.SurfaceVariant), radiusDp = 6f)
        }
    }
    override fun hitTest(p: Offset, result: LayoutResult, state: SkeletonState): HitTarget? = null
}
