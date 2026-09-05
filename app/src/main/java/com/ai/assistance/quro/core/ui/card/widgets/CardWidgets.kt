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

/** sp 字号在 density 下实际占的像素行高（含 ascender/descender/行距）。 */
internal fun spPx(sp: Float, density: Float, lineMult: Float = 1.3f): Float = sp * density * lineMult

// ── 1. 指标卡（数据可视化）──
// data.kind="chart"、chartType="metric"：series[0].name=指标名、points[0]=数值、labels[0]=单位。
class MetricCardState : CardState {
    /** measure 时缓存的 density（render 算真实行高用） */
    var density: Float = 2.75f
}
class MetricCardRenderer : CardRenderer<MetricCardState> {
    override fun createInitialState() = MetricCardState()
    override fun measure(spec: CardSpec, state: MetricCardState, maxWidthPx: Float, density: Float): Size {
        state.density = density
        // 内容：padTop + 指标名(13sp) + gap + 数值(26sp) + padBottom；用 density 算真实行高
        val padTop = 16f; val padBottom = 16f; val gap = 8f
        val titleH = spPx(13f, density)
        val valueH = spPx(26f, density)
        val h = padTop + titleH + gap + valueH + padBottom
        return Size(maxWidthPx, h)
    }
    override fun layout(spec: CardSpec, state: MetricCardState, size: Size, density: Float): LayoutResult =
        LayoutResult(size.width, size.height)
    override fun render(backend: RenderBackend, spec: CardSpec, result: LayoutResult, state: MetricCardState) {
        val chart = spec.data as? CardData.Chart
        val s = chart?.series?.firstOrNull()
        val name = s?.name ?: ""
        val value = s?.points?.firstOrNull()
        val unit = s?.labels?.firstOrNull() ?: ""
        // 卡片背景
        backend.drawRect(0f, 0f, result.width, result.height, backend.resolve(ColorToken.SurfaceVariant), radiusDp = 12f)
        val onSurface = backend.resolve(ColorToken.OnSurface)
        val pad = 16f
        // 与 measure 同公式：padTop + 指标名 + gap + 数值，y 用 sp 真实行高算 topLeft
        val padTop = 16f; val gap = 8f
        val titleFs = 13f
        val valueFs = 26f
        val titleY = padTop
        val valueY = padTop + spPx(titleFs, state.density) + gap
        if (name.isNotEmpty()) {
            backend.drawText(name, pad, titleY, onSurface, titleFs, 500)
        }
        if (value != null) {
            val v = if (value == value.toLong().toFloat()) value.toLong().toString() else value.toString()
            backend.drawText(
                if (unit.isEmpty()) v else "$v $unit",
                pad, valueY, backend.resolve(ColorToken.Primary), valueFs, 700,
            )
        } else {
            backend.drawText("--", pad, valueY, backend.resolve(ColorToken.OnSurfaceVariant), valueFs, 700)
        }
        // 右上角小标记：系列数 >1 时提示还有 N 条；宽度按 density 估，防溢出右缘
        val extra = (chart?.series?.size ?: 1) - 1
        if (extra > 0) {
            val tag = "+$extra"
            val estW = tag.length * 12f * state.density * 0.6f
            backend.drawText(tag, result.width - pad - estW, titleY, backend.resolve(ColorToken.OnSurfaceVariant), 12f, 400)
        }
    }
    override fun hitTest(p: Offset, result: LayoutResult, state: MetricCardState): HitTarget? = null
}

// ── 2. 折线图（数据可视化，逐点生长）──
class LineChartState(var phase: Float = 0f) : CardState {
    /** measure 时缓存 density（render 算 sp 真实行高用，与 CustomCardState/TableState 同模式） */
    var density: Float = 2.75f
    override fun canMerge(other: CardState) = other is LineChartState
}
class LineChartRenderer : CardRenderer<LineChartState> {
    override fun createInitialState() = LineChartState()
    override fun measure(spec: CardSpec, state: LineChartState, maxWidthPx: Float, density: Float): Size {
        // pad 16 + 顶部系列名图例(sp 11) + 数据图区 + x 轴标签(sp 10) + pad 16
        // 必须用 density 算 sp→px 的真实行高，否则 Canvas 会裁切下半部（v1.0.82 cardfix6 教训）
        state.density = density
        val topLabelH = spPx(11f, density) + 4f
        val xLabelH = spPx(10f, density) + 6f
        val padTop = 16f; val padBottom = 16f
        val dataArea = 130f
        return Size(maxWidthPx, padTop + topLabelH + dataArea + xLabelH + padBottom)
    }
    override fun layout(spec: CardSpec, state: LineChartState, size: Size, density: Float): LayoutResult =
        LayoutResult(size.width, size.height)
    override fun render(backend: RenderBackend, spec: CardSpec, result: LayoutResult, state: LineChartState) {
        val chart = spec.data as? CardData.Chart ?: return
        val d = state.density
        // 数据区垂直布局：顶部系列名图例(spPx+padding) + 数据图区(取余量) + x 轴标签(spPx+padding)
        // 必须与 measure 同公式，否则 11sp 图例/10sp 轴标会撑破区段造成重叠（v1.0.82 cardfix7 教训）
        val pad = 16f
        val topLegendH = spPx(11f, d) + 4f
        val xLabelH = spPx(10f, d) + 6f
        val chartTop = pad + topLegendH
        val chartBottom = result.height - pad - xLabelH
        val w = result.width - pad * 2
        val h = (chartBottom - chartTop).coerceAtLeast(40f)
        // 坐标轴
        backend.drawLine(pad, chartTop, pad, chartTop + h, backend.resolve(ColorToken.Outline), 1f)
        backend.drawLine(pad, chartTop + h, pad + w, chartTop + h, backend.resolve(ColorToken.Outline), 1f)
        // 全系列数值范围（自动归一化：任意量纲的 points 都能铺满绘图区，不再硬钳 0..1）
        val all = chart.series.flatMap { it.points }
        if (all.isEmpty()) return
        val vMin = chart.axis.yMin ?: all.min()
        val vMax = chart.axis.yMax ?: all.max()
        val span = (vMax - vMin).takeIf { it > 1e-6f } ?: 1f
        // y 轴上下限标注：画在图区内侧顶端/底端，避免溢出到顶部图例/底部 x 轴标签区
        val yLabelFs = 10f
        val yLabelH = spPx(yLabelFs, d)
        backend.drawText(trimNum(vMax), 2f, chartTop + yLabelH * 0.1f, backend.resolve(ColorToken.OnSurfaceVariant), yLabelFs, 400)
        backend.drawText(trimNum(vMin), 2f, chartTop + h - yLabelH * 1.1f, backend.resolve(ColorToken.OnSurfaceVariant), yLabelFs, 400)
        // 多系列全部绘制（各自 series.color 区分）
        chart.series.forEach { s ->
            val n = s.points.size
            if (n == 0) return@forEach
            val drawN = if (state.phase <= 0f) n else ((n * state.phase).toInt()).coerceIn(1, n)
            val path = androidx.compose.ui.graphics.Path()
            s.points.take(drawN).forEachIndexed { i, v ->
                val x = pad + w * (i.toFloat() / (n - 1).coerceAtLeast(1))
                val y = chartTop + h * (1f - ((v - vMin) / span).coerceIn(0f, 1f))
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            backend.drawPath(path, backend.resolve(s.color), 2f)
        }
        // x 轴标签（首条系列的 labels）：首/尾 + 中间点稀疏展示，画在图区下方 x 轴标签区内
        chart.series.firstOrNull()?.labels?.takeIf { it.size >= 2 }?.let { labels ->
            val n = labels.size
            val showIdx = LinkedHashSet<Int>()
            showIdx.add(0); showIdx.add(n - 1)
            if (n > 2) showIdx.add(n / 2)
            showIdx.filter { it < n }.forEach { i ->
                val x = pad + w * (i.toFloat() / (n - 1).coerceAtLeast(1))
                // 按文字真实像素宽（density 换算）居中在数据点下方，首尾防溢出
                val estW = labels[i].length * 10f * d * 0.6f
                val maxX = (result.width - pad - estW).coerceAtLeast(pad)
                val tx = (x - estW / 2f).coerceIn(pad, maxX)
                backend.drawText(labels[i], tx, chartBottom + 2f, backend.resolve(ColorToken.OnSurfaceVariant), 10f, 400)
            }
        }
        // 系列名图例（顶部图例区，多系列时逐个排开）；y 在顶部图例区内居中
        val legendFs = 11f
        val legendTopY = pad + (topLegendH - spPx(legendFs, d)) / 2f
        var lx = pad
        chart.series.filter { it.name.isNotEmpty() }.take(3).forEach { s ->
            backend.drawText(s.name, lx, legendTopY, backend.resolve(s.color), legendFs, 500)
            // advance 按 density 换算真实像素宽，否则多系列图例横向互相压叠
            lx += s.name.length * legendFs * d * 0.6f + 24f
        }
    }
    override fun hitTest(p: Offset, result: LayoutResult, state: LineChartState): HitTarget? = null
}

// ── 3. 按钮组（交互控件）──
class ButtonGroupState : CardState {
    /** measure 时缓存的 density（render 算文字真实行高用，与其他 renderer 同模式） */
    var density: Float = 2.75f
}
class ButtonGroupRenderer : CardRenderer<ButtonGroupState> {
    override fun createInitialState() = ButtonGroupState()
    override fun measure(spec: CardSpec, state: ButtonGroupState, maxWidthPx: Float, density: Float): Size {
        state.density = density
        val n = (spec.data as? CardData.Form)?.buttons?.size ?: 1
        // 按钮高度按 sp 15 文字密度算（含 padding）；按钮间纵向 gap 12 px
        val btnH = spPx(15f, density) + 16f
        val btnGap = 12f
        val rows = ((n + 1) / 2).coerceAtLeast(1)
        val padV = 8f
        val h = padV + rows * btnH + (rows - 1) * btnGap + padV
        return Size(maxWidthPx, h)
    }
    override fun layout(spec: CardSpec, state: ButtonGroupState, size: Size, density: Float): LayoutResult {
        val form = spec.data as? CardData.Form
        val btnH = spPx(15f, density) + 16f
        val btnGap = 12f
        val padV = 8f
        val boxes = form?.buttons?.mapIndexed { i, b ->
            val col = i % 2
            val row = i / 2
            val x0 = col * size.width / 2f + 8f
            val x1 = (col + 1) * size.width / 2f - 8f
            val y0 = padV + row * (btnH + btnGap)
            val y1 = y0 + btnH
            HitBox("btn_$i", x0, y0, x1, y1, i)
        } ?: emptyList()
        return LayoutResult(size.width, size.height, boxes)
    }
    override fun render(backend: RenderBackend, spec: CardSpec, result: LayoutResult, state: ButtonGroupState) {
        val form = spec.data as? CardData.Form
        result.boxes.forEach { box ->
            backend.drawRect(box.left, box.top, box.right, box.bottom, backend.resolve(ColorToken.Primary), radiusDp = 10f)
            val label = form?.buttons?.getOrNull(box.actionIndex)?.label ?: ""
            if (label.isNotEmpty()) {
                val sizeSp = 15f
                val d = state.density
                val cx = (box.left + box.right) / 2f
                val cy = (box.top + box.bottom) / 2f
                // 宽度按 density 估真实像素宽；y 按文字真实行高居中（sp 当 px 会溢出按钮底边）
                val estW = label.length * sizeSp * d * 0.55f
                val textH = spPx(sizeSp, d)
                backend.drawText(label, cx - estW / 2f, cy - textH / 2f, backend.resolve(ColorToken.OnPrimary), sizeSp, 600)
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
    override fun measure(spec: CardSpec, state: SkeletonState, maxWidthPx: Float, density: Float): Size =
        Size(maxWidthPx, 120f) // 固定占位高度，防流式抖动
    override fun layout(spec: CardSpec, state: SkeletonState, size: Size, density: Float): LayoutResult =
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

/** 数值标注去尾零：8432.0→"8432"、0.85→"0.85"，超长截断到 8 字符。 */
private fun trimNum(v: Float): String {
    val s = if (v == v.toLong().toFloat()) v.toLong().toString()
    else String.format("%.2f", v).trimEnd('0').trimEnd('.')
    return if (s.length > 8) s.take(8) else s
}
