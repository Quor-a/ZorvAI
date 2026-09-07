package com.ai.assistance.quro.core.ui.card

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import com.ai.assistance.quro.core.ui.card.render.RenderBackend
import com.ai.assistance.quro.core.ui.card.spec.CardData
import com.ai.assistance.quro.core.ui.card.spec.CardSpec
import com.ai.assistance.quro.core.ui.card.spec.ColorToken
import com.ai.assistance.quro.core.ui.card.spec.LayoutNode
import com.ai.assistance.quro.core.ui.card.widgets.CustomCardRenderer
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 自定义卡（type="custom"）横向溢出回归测试。
 *
 * 背景：row 里非 weight 子节点此前一律按父容器整宽（inner）measure，
 * drawChildren 又用 measure 宽推进 cx → 第 2 个子节点被画到 x+inner+gap，
 * 直接跑到屏幕外（真机表现为卡片右侧露出半截元素）。
 *
 * 这里用录制型 backend 记录所有绘制指令的右边界，断言绝不越过卡片宽度。
 */
private class BoundsRecordingBackend : RenderBackend {
    var maxRight = 0f
    var maxBottom = 0f

    private fun track(right: Float, bottom: Float) {
        if (right > maxRight) maxRight = right
        if (bottom > maxBottom) maxBottom = bottom
    }

    override fun drawRect(l: Float, t: Float, r: Float, b: Float, c: Color, radiusDp: Float) = track(r, b)
    override fun drawText(text: String, x: Float, y: Float, c: Color, sizeSp: Float, weight: Int) {
        // CJK 按 1em、ASCII 按 0.55em 粗估（与渲染器同口径）
        var w = 0f
        for (ch in text) w += (if (ch.code > 0x2E80) 1f else 0.55f) * sizeSp * 2.75f
        track(x + w, y + sizeSp * 2.75f * 1.3f)
    }
    override fun drawLine(x1: Float, y1: Float, x2: Float, y2: Float, c: Color, widthDp: Float) = track(maxOf(x1, x2), maxOf(y1, y2))
    override fun drawPath(p: Path, c: Color, widthDp: Float, fill: Boolean) {}
    override fun drawArc(l: Float, t: Float, r: Float, b: Float, startDeg: Float, sweepDeg: Float, c: Color, widthDp: Float) = track(r, b)
    override fun drawGradientRect(l: Float, t: Float, r: Float, b: Float, colors: List<Color>, angleDeg: Float, radiusDp: Float) = track(r, b)
    override fun drawImage(bitmap: Any?, l: Float, t: Float, w: Float, h: Float) = track(l + w, t + h)
    override fun saveLayer() {}
    override fun restore() {}
    override fun clip(l: Float, t: Float, r: Float, b: Float) {}
    override fun resolve(token: ColorToken): Color = Color.Gray
}

class CustomCardOverflowTest {

    /** 构造「情绪雷达」形态：card > [text, row[column(4×bar), ring]] —— 真机溢出的复现结构。 */
    private fun moodRadarSpec(): CardSpec = CardSpec(
        id = "mood-radar",
        type = "custom",
        layout = LayoutNode(
            type = "card",
            props = mapOf("padding" to 16f, "radius" to 16f, "gap" to 10f),
            children = listOf(
                LayoutNode(type = "text", props = mapOf("text" to "情绪雷达", "fontSize" to 16f)),
                LayoutNode(
                    type = "row",
                    props = mapOf("gap" to 12f),
                    children = listOf(
                        LayoutNode(
                            type = "column",
                            props = mapOf("gap" to 8f),
                            children = listOf(
                                LayoutNode(type = "text", props = mapOf("text" to "活力", "fontSize" to 13f)),
                                LayoutNode(type = "bar", props = mapOf("value" to 80f, "h" to 10f)),
                                LayoutNode(type = "text", props = mapOf("text" to "专注", "fontSize" to 13f)),
                                LayoutNode(type = "bar", props = mapOf("value" to 65f, "h" to 10f)),
                                LayoutNode(type = "text", props = mapOf("text" to "社交", "fontSize" to 13f)),
                                LayoutNode(type = "bar", props = mapOf("value" to 45f, "h" to 10f)),
                                LayoutNode(type = "text", props = mapOf("text" to "创造", "fontSize" to 13f)),
                                LayoutNode(type = "bar", props = mapOf("value" to 90f, "h" to 10f)),
                            ),
                        ),
                        LayoutNode(type = "ring", props = mapOf("size" to 90f, "value" to 72f, "text" to "72")),
                    ),
                ),
            ),
        ),
        data = CardData.Empty,
    )

    @Test
    fun customCard_rowChildrenNeverOverflowCardWidth() {
        val r = CustomCardRenderer()
        val spec = moodRadarSpec()
        val state = r.createInitialState()
        val screenW = 1080f          // 典型 1080p 屏宽（px）
        val d = 2.75f
        val size = r.measure(spec, state, screenW, d)
        assertTrue("卡片宽度不得超过可用宽度", size.width <= screenW + 0.5f)

        val layout = r.layout(spec, state, size, d)
        val b = BoundsRecordingBackend()
        r.render(b, spec, layout, state)

        assertTrue(
            "row 子节点不得画到卡片右边界之外（实际最大右边界=${b.maxRight}, 卡片宽=${size.width}）",
            b.maxRight <= size.width + 0.5f,
        )
    }

    @Test
    fun customCard_rowWithWeight_childrenSplitWithoutOverflow() {
        val r = CustomCardRenderer()
        val spec = CardSpec(
            id = "row-weight",
            type = "custom",
            layout = LayoutNode(
                type = "card",
                props = mapOf("padding" to 12f, "gap" to 8f),
                children = listOf(
                    LayoutNode(
                        type = "row",
                        props = mapOf("gap" to 8f),
                        children = listOf(
                            LayoutNode(type = "box", weight = 1f, props = mapOf("h" to 40f, "bg" to "#3366FF")),
                            LayoutNode(type = "box", weight = 1f, props = mapOf("h" to 40f, "bg" to "#FF6633")),
                            LayoutNode(type = "box", weight = 2f, props = mapOf("h" to 40f, "bg" to "#33FF66")),
                        ),
                    ),
                ),
            ),
            data = CardData.Empty,
        )
        val state = r.createInitialState()
        val screenW = 1080f
        val size = r.measure(spec, state, screenW, 2.75f)
        val b = BoundsRecordingBackend()
        r.render(b, spec, r.layout(spec, state, size, 2.75f), state)
        assertTrue("weight 分配也不得溢出（maxRight=${b.maxRight}, w=${size.width}）", b.maxRight <= size.width + 0.5f)
        assertTrue("卡片高度应>0", size.height > 0f)
    }

    @Test
    fun customCard_oversizedRing_shrinksInsteadOfOverflowing() {
        // 空间远小于 ring 自身 size 时，圆环应缩小而不是画到屏外
        val r = CustomCardRenderer()
        val spec = CardSpec(
            id = "huge-ring",
            type = "custom",
            layout = LayoutNode(
                type = "card",
                props = mapOf("padding" to 8f, "gap" to 8f),
                children = listOf(
                    LayoutNode(
                        type = "row",
                        props = mapOf("gap" to 8f),
                        children = listOf(
                            LayoutNode(type = "box", weight = 1f, props = mapOf("h" to 30f)),
                            LayoutNode(type = "ring", props = mapOf("size" to 400f, "value" to 50f)),
                        ),
                    ),
                ),
            ),
            data = CardData.Empty,
        )
        val state = r.createInitialState()
        val screenW = 360f            // 极窄屏
        val size = r.measure(spec, state, screenW, 1f)
        val b = BoundsRecordingBackend()
        r.render(b, spec, r.layout(spec, state, size, 1f), state)
        assertTrue("极窄屏下圆环也不得溢出（maxRight=${b.maxRight}, w=${size.width}）", b.maxRight <= size.width + 0.5f)
    }

    @Test
    fun customCard_singleChildRow_noOverflow() {
        val r = CustomCardRenderer()
        val spec = CardSpec(
            id = "single",
            type = "custom",
            layout = LayoutNode(
                type = "card",
                props = mapOf("padding" to 12f),
                children = listOf(
                    LayoutNode(
                        type = "row",
                        children = listOf(LayoutNode(type = "box", props = mapOf("h" to 50f))),
                    ),
                ),
            ),
            data = CardData.Empty,
        )
        val state = r.createInitialState()
        val size = r.measure(spec, state, 720f, 2f)
        val b = BoundsRecordingBackend()
        r.render(b, spec, r.layout(spec, state, size, 2f), state)
        assertTrue("单子 row 不得溢出", b.maxRight <= size.width + 0.5f)
    }
}
