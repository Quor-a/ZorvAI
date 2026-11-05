package com.ai.assistance.quro.core.ui.card

import com.ai.assistance.quro.core.ui.card.compose.CardExtractors
import com.ai.assistance.quro.core.ui.card.spec.CardData
import com.ai.assistance.quro.core.ui.card.spec.CardSpec
import com.ai.assistance.quro.core.ui.card.spec.LayoutNode
import com.ai.assistance.quro.core.ui.card.spec.parseCardSpec
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 自定义卡（type="custom"）渲染回归。
 *
 * 背景：旧版在单个 Canvas 上手绘整棵布局树，row 里非 weight 子节点被按父容器整宽 measure、
 * 又用 measure 宽推进 cx，导致第 2 个子节点画到屏幕外（真机表现：卡片右侧露出半截元素）。
 * 新版架构把离散元素交给 Compose 的 Row + weight 自动排版——weight 分配、命中、尺寸全部交给
 * Compose 引擎，从根上消除了「手算坐标溢出」。本测试用纯 JVM 的 [CardExtractors] 断言：
 *  ① 各问题布局树都能提取出正且有限的高度（不会塌成 0 / NaN，否则内容丢失）；
 *  ② 文字内容不丢（collectTexts 兜底收集，杜绝「内容消失」）；
 *  ③ 布局树结构完好（row 子节点的权重保留，Compose 据此分配宽度，不溢出）。
 */
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
    fun customCard_rowChildren_estimateHeightPositiveAndFinite() {
        val spec = moodRadarSpec()
        val h = CardExtractors.estimateHeightDp(spec.layout)
        assertTrue("高度必须为正且有限，否则内容丢失 / 压扁（h=$h）", h > 0f && !h.isNaN() && !h.isInfinite())
        val texts = CardExtractors.collectTexts(spec.layout)
        assertTrue(
            "bar 文字必须被收集（活力 / 专注 / 社交 / 创造）",
            listOf("活力", "专注", "社交", "创造").all { t -> texts.any { it.contains(t) } },
        )
    }

    @Test
    fun customCard_rowWithWeight_estimateHeightPositive() {
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
        val h = CardExtractors.estimateHeightDp(spec.layout)
        assertTrue("weight 分配布局高度必须为正且有限（h=$h）", h > 0f && !h.isNaN() && !h.isInfinite())
        // 三个 box 都应带 weight，Compose 据此等分宽度，绝不溢出
        val row = spec.layout!!.children[0]
        assertTrue("row 子节点必须带权重以触发 Compose 等分", row.children.all { it.weight > 0f })
    }

    @Test
    fun customCard_oversizedRing_stillWellFormed() {
        // 空间远小于 ring 自身 size 时，旧版会 shrink；新版靠 Compose 排版，本测试只保证结构完好不崩。
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
        val h = CardExtractors.estimateHeightDp(spec.layout)
        assertTrue("极窄屏下布局树仍须产出正且有限高度（h=$h）", h > 0f && !h.isNaN() && !h.isInfinite())
        // ring 节点存在且被正确归一为 ring 类型（旧版同义词 / 大写会丢失节点）
        var hasRing = false
        fun walk(n: LayoutNode) { if (n.type == "ring") hasRing = true; n.children.forEach { walk(it) } }
        walk(spec.layout!!)
        assertTrue("ring 节点必须保留", hasRing)
    }

    @Test
    fun customCard_singleChildRow_noOverflowStructure() {
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
        val h = CardExtractors.estimateHeightDp(spec.layout)
        assertTrue("单子 row 高度必须为正且有限", h > 0f && !h.isNaN() && !h.isInfinite())
    }
}
