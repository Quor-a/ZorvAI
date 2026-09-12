package io.gencanvas.layout

import com.facebook.yoga.YogaAlign
import com.facebook.yoga.YogaDisplay
import com.facebook.yoga.YogaEdge
import com.facebook.yoga.YogaGutter
import com.facebook.yoga.YogaFlexDirection
import com.facebook.yoga.YogaJustify
import com.facebook.yoga.YogaMeasureMode
import com.facebook.yoga.YogaNode
import com.facebook.yoga.YogaNodeFactory
import com.facebook.yoga.YogaPositionType
import com.facebook.yoga.YogaWrap
import io.gencanvas.model.Dim
import io.gencanvas.model.Flex
import io.gencanvas.model.Frame
import io.gencanvas.model.LaidNode
import io.gencanvas.model.Node

/**
 * 布局层：Node 树 → Yoga 树 → 每个节点的 [Frame]。
 *
 * 职责单一：只算位置，不画任何东西。
 * 之所以选 Yoga：C 实现、Flexbox 语义完备（AI 训练语料里 CSS flex 极丰富，
 * 模型写 flex 的准确率远高于任何自造布局 DSL），且 Android 端有官方 maven 产物。
 */
object YogaLayout {

    /**
     * @param widthDp  容器可用宽度
     * @param heightDp 容器可用高度（可用 Float.NaN 表示未限定，让 Yoga 自己撑开）
     * @param density  屏幕密度，dp → px
     */
    fun layout(root: Node, widthDp: Float, heightDp: Float, density: Float): LaidNode {
        val scale = { v: Float -> v * density }
        val yoga = buildYoga(root, scale)
        yoga.calculateLayout(
            if (widthDp.isNaN()) Float.NaN else widthDp * density,
            if (heightDp.isNaN()) Float.NaN else heightDp * density,
        )
        val out = collect(yoga, root, 0f, 0f)
        // 纯 Java 版 Yoga（com.facebook.yoga:yoga）没有 free()；节点由 JVM GC 回收。
        // 每次生成都是一次性短生命周期树，交给 GC 即可，无需手动释放。
        return out
    }

    private fun buildYoga(node: Node, s: (Float) -> Float): YogaNode {
        val y = YogaNodeFactory.create()
        applyFlex(y, node.flex, s)
        node.children.forEach { child ->
            val cy = buildYoga(child, s)
            y.addChildAt(cy, y.childCount)
        }
        return y
    }

    private fun applyFlex(y: YogaNode, f: Flex, s: (Float) -> Float) {
        y.setSize(f.w, s, { n, v -> n.setWidth(v) }, { n, p -> n.setWidthPercent(p) })
        y.setSize(f.h, s, { n, v -> n.setHeight(v) }, { n, p -> n.setHeightPercent(p) })
        y.setSize(f.minW, s, { n, v -> n.setMinWidth(v) }, { n, p -> n.setMinWidthPercent(p) })
        y.setSize(f.maxW, s, { n, v -> n.setMaxWidth(v) }, { n, p -> n.setMaxWidthPercent(p) })
        y.setSize(f.minH, s, { n, v -> n.setMinHeight(v) }, { n, p -> n.setMinHeightPercent(p) })
        y.setSize(f.maxH, s, { n, v -> n.setMaxHeight(v) }, { n, p -> n.setMaxHeightPercent(p) })
        y.setSize(f.basis, s, { n, v -> n.setFlexBasis(v) }, { n, p -> n.setFlexBasisPercent(p) })

        f.grow?.let { y.flexGrow = it }
        f.shrink?.let { y.flexShrink = it }
        f.ratio?.let { y.aspectRatio = it }

        f.direction?.let {
            y.flexDirection = when (it) {
                "row" -> YogaFlexDirection.ROW
                "row-reverse" -> YogaFlexDirection.ROW_REVERSE
                "column-reverse" -> YogaFlexDirection.COLUMN_REVERSE
                else -> YogaFlexDirection.COLUMN
            }
        }
        f.justify?.let {
            y.justifyContent = when (it) {
                "center" -> YogaJustify.CENTER
                "end" -> YogaJustify.FLEX_END
                "between" -> YogaJustify.SPACE_BETWEEN
                "around" -> YogaJustify.SPACE_AROUND
                "evenly" -> YogaJustify.SPACE_EVENLY
                else -> YogaJustify.FLEX_START
            }
        }
        f.align?.let { y.alignItems = parseAlign(it) }
        f.alignSelf?.let { y.alignSelf = parseAlign(it) }
        f.wrap?.let { y.wrap = if (it == "wrap") YogaWrap.WRAP else YogaWrap.NO_WRAP }
        f.gap?.let { y.setGap(YogaGutter.ALL, s(it)) }

        f.pad?.let { y.setPadding(YogaEdge.ALL, s(it)) }
        f.padX?.let { y.setPadding(YogaEdge.HORIZONTAL, s(it)) }
        f.padY?.let { y.setPadding(YogaEdge.VERTICAL, s(it)) }
        f.padL?.let { y.setPadding(YogaEdge.LEFT, s(it)) }
        f.padT?.let { y.setPadding(YogaEdge.TOP, s(it)) }
        f.padR?.let { y.setPadding(YogaEdge.RIGHT, s(it)) }
        f.padB?.let { y.setPadding(YogaEdge.BOTTOM, s(it)) }

        f.margin?.let { y.setMargin(YogaEdge.ALL, s(it)) }
        f.marginX?.let { y.setMargin(YogaEdge.HORIZONTAL, s(it)) }
        f.marginY?.let { y.setMargin(YogaEdge.VERTICAL, s(it)) }
        f.marginL?.let { y.setMargin(YogaEdge.LEFT, s(it)) }
        f.marginT?.let { y.setMargin(YogaEdge.TOP, s(it)) }
        f.marginR?.let { y.setMargin(YogaEdge.RIGHT, s(it)) }
        f.marginB?.let { y.setMargin(YogaEdge.BOTTOM, s(it)) }

        if (f.position == "absolute") y.positionType = YogaPositionType.ABSOLUTE
        f.l?.let { y.setPosition(YogaEdge.LEFT, s(it)) }
        f.t?.let { y.setPosition(YogaEdge.TOP, s(it)) }
        f.r?.let { y.setPosition(YogaEdge.RIGHT, s(it)) }
        f.b?.let { y.setPosition(YogaEdge.BOTTOM, s(it)) }
    }

    private fun parseAlign(v: String): YogaAlign = when (v) {
        "center" -> YogaAlign.CENTER
        "end" -> YogaAlign.FLEX_END
        "stretch" -> YogaAlign.STRETCH
        "baseline" -> YogaAlign.BASELINE
        else -> YogaAlign.FLEX_START
    }

    /**
     * 尺寸三态：px / 百分比 / auto。非法值（NaN、Infinity）一律按 auto 处理。
     * [pct] 按轴分别落到 Yoga 的 widthPercent / heightPercent 等原生 API ——
     * 旧实现把所有百分比都写成宽度百分比，`h:"50%"` 会静默变成"高度未设"。
     */
    private fun YogaNode.setSize(
        d: Dim?, s: (Float) -> Float,
        setter: (YogaNode, Float) -> Unit,
        pct: (YogaNode, Float) -> Unit
    ) {
        when (d) {
            null -> Unit
            Dim.Auto -> setter(this, Float.NaN)
            is Dim.Pt -> if (d.v.isFinite()) setter(this, s(d.v))
            is Dim.Pct -> if (d.v.isFinite()) pct(this, d.v)
        }
    }

    private fun collect(y: YogaNode, node: Node, ox: Float, oy: Float): LaidNode {
        val f = Frame(y.layoutX, y.layoutY, y.layoutWidth, y.layoutHeight)
        val kids = mutableListOf<LaidNode>()
        for (i in 0 until y.childCount) {
            val src = node.children.getOrNull(i) ?: break
            kids += collect(y.getChildAt(i), src, 0f, 0f)
        }
        return LaidNode(node, f, kids)
    }

    private fun Float.isFinite() = !isNaN() && !isInfinite()

    /** 供外部隐藏不可见节点（visible=false 时不参与布局）。 */
    fun hide(y: YogaNode) { y.display = YogaDisplay.NONE }
}
