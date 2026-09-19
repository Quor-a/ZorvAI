package com.yuanbao.miniapp.render

import kotlin.math.max
import kotlin.math.min

/**
 * Self-developed flexbox layout engine (a practical subset of CSS flexbox).
 *
 * Supports: row/column direction with reverse, wrap, justify-content,
 * align-items, flex-grow / flex-shrink / flex-basis, margin, padding,
 * absolute positioning, min/max constraints and intrinsic content size
 * (text measured by TextLayout, images by their intrinsic size).
 */
class FlexLayout(private val viewportWidth: Float, private val viewportHeight: Float) {

    /** px per rpx unit. */
    var rpxRatio: Float = viewportWidth / 750f

    /** Callback used to measure text nodes: (text, fontSizePx, bold, maxWidth) -> measured result. */
    var textMeasurer: ((text: String, fontSize: Float, bold: Boolean, maxWidth: Float) -> TextMeasurer.Result)? = null

    fun layout(root: RenderNode, availableWidth: Float = viewportWidth, availableHeight: Float = viewportHeight) {
        rpxRatio = viewportWidth / 750f
        root.x = 0f
        root.y = 0f
        measure(root, availableWidth, availableHeight)
        root.width = availableWidth
        root.height = availableHeight
        root.absX = 0f
        root.absY = 0f
        layoutChildren(root, availableWidth, availableHeight)
    }

    // ---------------------------------------------------------------- measure
    private fun measure(node: RenderNode, availW: Float, availH: Float,
                        widthDefinite: Boolean = true, heightDefinite: Boolean = true) {
        val st = node.style
        if (st.display == Display.NONE) {
            node.width = 0f; node.height = 0f
            return
        }
        // CSS 规范：显式 display:flex 的容器 flex-direction 初始值是 row；
        // 块级容器（未写 display，微信 view 默认）纵向堆叠（v0.26.7：修全屏横排 bug）
        if (!st.flexDirectionSet) {
            st.flexDirection = if (st.display == Display.FLEX) FlexDirection.ROW
                               else FlexDirection.COLUMN
        }
        val padH = resolve(st.padding.left, availW) + resolve(st.padding.right, availW)
        val padV = resolve(st.padding.top, availH) + resolve(st.padding.bottom, availH)

        // CSS 规范：父尺寸未定义（auto/内容撑开）时，子元素的百分比尺寸退化为 auto。
        // 修复：AI 给卡片写 height:100%（配 flex 居中），父高是内容撑开时被解析成
        // "父的可用高"（整屏）——卡片瞬间变成几千像素高的白条。
        val specifiedW = if (st.width.unit == Length.Unit.PERCENT && !widthDefinite) null
                         else resolveOrNull(st.width, availW)
        val specifiedH = if (st.height.unit == Length.Unit.PERCENT && !heightDefinite) null
                         else resolveOrNull(st.height, availH)

        val contentW = when {
            specifiedW != null -> max(0f, specifiedW - padH)
            else -> null
        }

        when (node.type) {
            NodeType.TEXT -> {
                val fs = st.fontSize
                val lh = if (st.lineHeight.isNaN()) fs * 1.25f else st.lineHeight
                val maxW = contentW ?: max(0f, availW - padH)
                val res = textMeasurer?.invoke(node.text, fs, st.fontWeight == FontWeight.BOLD, maxW)
                node.lines = res?.lines ?: fallbackLines(node.text, fs, maxW)
                node.lineHeightPx = lh
                val intrinsicW = res?.width ?: fallbackWidth(node.text, fs)
                node.width = specifiedW ?: min(availW, intrinsicW + padH)
                node.height = specifiedH ?: (node.lineHeightPx * node.lines.size + padV)
            }
            NodeType.IMAGE -> {
                val w = node.attributes["__intrinsicWidth"]?.toFloatOrNull()
                val h = node.attributes["__intrinsicHeight"]?.toFloatOrNull()
                node.width = specifiedW ?: (w ?: 0f)
                node.height = specifiedH ?: (h ?: 0f)
            }
            NodeType.INPUT, NodeType.BUTTON -> {
                val fs = st.fontSize
                val lh = if (st.lineHeight.isNaN()) fs * 1.4f else st.lineHeight
                node.lineHeightPx = lh
                val txt = node.text.ifEmpty { node.attributes["placeholder"] ?: "" }
                val maxW = contentW ?: max(0f, availW - padH)
                val res = textMeasurer?.invoke(txt, fs, st.fontWeight == FontWeight.BOLD, maxW)
                node.lines = res?.lines ?: listOf(txt)
                node.width = specifiedW ?: min(availW, (res?.width ?: fallbackWidth(txt, fs)) + padH)
                node.height = specifiedH ?: (lh + padV)
            }
            else -> {
                // container: children measured first for intrinsic size
                val innerW = contentW ?: max(0f, availW - padH)
                var intrinsicW = 0f
                var intrinsicH = 0f
                val row = isRow(st)
                // 页面根（ROOT）尺寸就是视口，对子元素而言是"已确定尺寸"的父级。
                // 否则 `.page{height:100%}` 被判成 auto（父高未知，百分比退化），
                // 页面背景只包住内容高度 → 表现为"背景被围栏框住、不满屏"，flex:1 的页脚也失效。
                val rootNode = node.type == NodeType.ROOT
                val childWDefinite = specifiedW != null || rootNode
                val childHDefinite = specifiedH != null || rootNode
                for (c in node.children) {
                    measure(c, innerW, availH, childWDefinite, childHDefinite)
                    // 注意：绝对定位子元素仍参与父级内在尺寸计算。
                    // 严格 CSS 里它不该参与，但端上大量 AI 生成页面用「父容器不写高度 +
                    // 绝对定位子元素」撑开布局；一旦排除，父高变 0，而 painter 对
                    // height<=0 的节点直接跳过 → 整棵子树不绘制（表现为整块空白）。
                    val cm = marginOf(c, innerW, availH)
                    val cw = c.width + cm.left + cm.right
                    val ch = c.height + cm.top + cm.bottom
                    if (row) {
                        intrinsicW += cw
                        intrinsicH = max(intrinsicH, ch)
                    } else {
                        intrinsicW = max(intrinsicW, cw)
                        intrinsicH += ch
                    }
                }
                node.width = specifiedW ?: min(availW, intrinsicW + padH)
                node.height = specifiedH ?: (intrinsicH + padV)
            }
        }

        // min / max constraints
        val minW = resolveOrNull(st.minWidth, availW)
        val maxW = resolveOrNull(st.maxWidth, availW)
        val minH = resolveOrNull(st.minHeight, availH)
        val maxH = resolveOrNull(st.maxHeight, availH)
        if (minW != null) node.width = max(node.width, minW)
        if (maxW != null) node.width = min(node.width, maxW)
        if (minH != null) node.height = max(node.height, minH)
        if (maxH != null) node.height = min(node.height, maxH)
    }

    // ---------------------------------------------------------------- layout
    private fun layoutChildren(node: RenderNode, availW: Float, availH: Float) {
        val st = node.style
        val padL = resolve(st.padding.left, availW)
        val padT = resolve(st.padding.top, availH)
        val padR = resolve(st.padding.right, availW)
        val padB = resolve(st.padding.bottom, availH)

        val contentX = node.absX + padL
        val contentY = node.absY + padT
        val contentW = max(0f, node.width - padL - padR)
        val contentH = max(0f, node.height - padT - padB)

        val flow = ArrayList<RenderNode>()
        for (c in node.children) {
            if (c.style.display == Display.NONE) continue
            if (c.style.position == PositionType.ABSOLUTE) {
                placeAbsolute(c, contentX, contentY, contentW, contentH)
            } else {
                flow.add(c)
            }
        }
        // 关键：flow 为空（父容器只含绝对定位子元素 / 子元素全 display:none）时**不能直接 return**。
        // 旧实现提前返回 → 下方的子层递归布局与 overflow 内容尺寸计算整段被跳过，
        // 绝对定位子树的 absX/absY 保持 0，整棵子树叠在页面左上角（多层卡片糊成一坨）。
        val row = isRow(st)
        val reversed = st.flexDirection == FlexDirection.ROW_REVERSE || st.flexDirection == FlexDirection.COLUMN_REVERSE

        if (flow.isEmpty()) {
            // 无流内子元素：跳过主轴排布，直接走下方递归
        } else if (st.flexWrap == FlexWrap.NOWRAP) {
            // ---- single line (nowrap) ----
            val mainSize = if (row) contentW else contentH
            applyFlex(node, flow, mainSize, row, contentW, contentH)
            positionLine(node, flow, contentX, contentY, contentW, contentH, row, reversed, true)
        } else {
            // ---- wrap: split into lines ----
            // 换行主轴尺寸要扣除行间 gap
            val crossGapWrap = if (row)
                (resolveOrNull(st.gapRow, contentH) ?: 0f)
            else (resolveOrNull(st.gapColumn, contentW) ?: 0f)
            val lines = ArrayList<ArrayList<RenderNode>>()
            var current = ArrayList<RenderNode>()
            var used = 0f
            for (c in flow) {
                val m = marginOf(c, contentW, contentH)
                val main = (if (row) c.width + m.left + m.right else c.height + m.top + m.bottom)
                if (current.isNotEmpty() && used + main > mainSizeOf(contentW, contentH, row)) {
                    lines.add(current)
                    current = ArrayList()
                    used = 0f
                }
                current.add(c)
                used += main
            }
            if (current.isNotEmpty()) lines.add(current)

            var crossOffset = 0f
            for (line in lines) {
                applyFlex(node, line, mainSizeOf(contentW, contentH, row), row, contentW, contentH)
                val lineCross = line.maxOf { c ->
                    val m = marginOf(c, contentW, contentH)
                    if (row) c.height + m.top + m.bottom else c.width + m.left + m.right
                }
                val startX = if (row) contentX else contentX + crossOffset
                val startY = if (row) contentY + crossOffset else contentY
                val lineW = if (row) contentW else lineCross
                val lineH = if (row) lineCross else contentH
                positionLine(node, line, startX, startY, lineW, lineH, row, reversed, true)
                crossOffset += lineCross + crossGapWrap
            }
        }

        // 递归布局所有子层（流内 + 绝对定位一视同仁）：
        // 绝对定位子元素自己的坐标已由 placeAbsolute 定好，其子孙必须继续往下布局，
        // 否则嵌套结构（卡片 > 角标 > 文字）会停在旧坐标。
        for (c in node.children) {
            if (c.style.display == Display.NONE) continue
            layoutChildren(c, contentW, contentH)
        }
        if (st.overflow == Overflow.SCROLL) {
            // 真实内容尺寸 = 子元素包围盒 + 内边距。
            // 旧实现直接把容器自身内高写进 contentHeight → 滚动上限恒为 0（contentHeight - height ≈ -padding），
            // 表现为 scroll-view 完全不能滚、列表只能显示第一屏 = 功能不可用。
            var maxX = 0f
            var maxY = 0f
            for (c in node.children) {
                if (c.style.display == Display.NONE) continue
                val m = marginOf(c, contentW, contentH)
                maxX = max(maxX, (c.absX - contentX) + c.width + m.right)
                maxY = max(maxY, (c.absY - contentY) + c.height + m.bottom)
            }
            node.contentWidth = padL + maxX + padR
            node.contentHeight = padT + maxY + padB
        }
    }

    private fun mainSizeOf(contentW: Float, contentH: Float, row: Boolean) = if (row) contentW else contentH

    /** Applies grow / shrink along the main axis. */
    private fun applyFlex(
        node: RenderNode,
        line: List<RenderNode>,
        mainSize: Float,
        row: Boolean,
        contentW: Float,
        contentH: Float
    ) {
        var used = 0f
        var totalGrow = 0f
        var totalShrink = 0f
        var shrinkable = 0f
        for (c in line) {
            val m = marginOf(c, contentW, contentH)
            val main = (if (row) c.width else c.height) + (if (row) m.left + m.right else m.top + m.bottom)
            used += main
            totalGrow += c.style.flexGrow
            totalShrink += c.style.flexShrink
            shrinkable += if (row) c.width else c.height
        }
        val free = mainSize - used
        if (free > 0 && totalGrow > 0) {
            for (c in line) {
                val g = c.style.flexGrow / totalGrow * free
                if (row) c.width += g else c.height += g
            }
        } else if (free < 0 && totalShrink > 0) {
            val deficit = -free
            for (c in line) {
                val base = (if (row) c.width else c.height)
                val share = if (shrinkable > 0) base / shrinkable else 0f
                val reduce = min(base, deficit * share)
                if (row) c.width -= reduce else c.height -= reduce
            }
        }
    }

    private fun positionLine(
        node: RenderNode,
        line: List<RenderNode>,
        startX: Float,
        startY: Float,
        contentW: Float,
        contentH: Float,
        row: Boolean,
        reversed: Boolean,
        isRootLine: Boolean
    ) {
        val st = node.style
        val ordered = if (reversed) line.reversed() else line

        var totalMain = 0f
        for (c in ordered) {
            val m = marginOf(c, contentW, contentH)
            totalMain += (if (row) c.width + m.left + m.right else c.height + m.top + m.bottom)
        }
        val mainSize = if (row) contentW else contentH
        val free = max(0f, mainSize - totalMain)

        val (gapStart, gapBetween) = when (st.justifyContent) {
            JustifyContent.FLEX_START -> 0f to 0f
            JustifyContent.FLEX_END -> free to 0f
            JustifyContent.CENTER -> free / 2f to 0f
            JustifyContent.SPACE_BETWEEN -> 0f to (if (ordered.size > 1) free / (ordered.size - 1) else 0f)
            JustifyContent.SPACE_AROUND -> {
                val g = free / ordered.size
                (g / 2f) to g
            }
        }

        var cursor = if (row) startX + gapStart else startY + gapStart
        for (c in ordered) {
            val m = marginOf(c, contentW, contentH)
            val crossSize = if (row) contentH else contentW
            val childCross = if (row) c.height + m.top + m.bottom else c.width + m.left + m.right

            val crossPos = when (st.alignItems) {
                AlignItems.FLEX_START -> 0f
                AlignItems.FLEX_END -> crossSize - childCross
                AlignItems.CENTER -> (crossSize - childCross) / 2f
                AlignItems.STRETCH -> {
                    if (row) {
                        if (!c.style.heightSet) c.height = max(0f, crossSize - m.top - m.bottom)
                        0f
                    } else {
                        if (!c.style.widthSet) c.width = max(0f, crossSize - m.left - m.right)
                        0f
                    }
                }
            }

            if (row) {
                c.x = cursor + m.left
                c.y = crossPos + m.top
                c.absX = startX + (c.x - startX)
                c.absY = startY + crossPos + m.top
                cursor += c.width + m.left + m.right + gapBetween
            } else {
                c.x = crossPos + m.left
                c.y = cursor + m.top
                c.absX = startX + crossPos + m.left
                c.absY = startY + (c.y - startY)
                cursor += c.height + m.top + m.bottom + gapBetween
            }
        }
    }

    private fun placeAbsolute(child: RenderNode, contentX: Float, contentY: Float, contentW: Float, contentH: Float) {
        val st = child.style
        val l = resolveOrNull(st.left, contentW)
        val t = resolveOrNull(st.top, contentH)
        val r = resolveOrNull(st.right, contentW)
        val b = resolveOrNull(st.bottom, contentH)
        val x = when {
            l != null -> contentX + l
            r != null -> contentX + contentW - child.width - r
            else -> contentX
        }
        val y = when {
            t != null -> contentY + t
            b != null -> contentY + contentH - child.height - b
            else -> contentY
        }
        // transform: translate 偏移（% 相对自身尺寸——支持 AI 常写的 left:50% + translateX(-50%) 居中）
        val tx = resolveOrNull(st.translateX, child.width) ?: 0f
        val ty = resolveOrNull(st.translateY, child.height) ?: 0f
        child.x = x - contentX + tx
        child.y = y - contentY + ty
        child.absX = x + tx
        child.absY = y + ty
    }

    // ---------------------------------------------------------------- utils
    private fun isRow(st: Style) =
        st.flexDirection == FlexDirection.ROW || st.flexDirection == FlexDirection.ROW_REVERSE

    fun resolve(len: Length, parent: Float): Float = len.resolve(parent, viewportWidth, rpxRatio) ?: 0f

    private fun resolveOrNull(len: Length, parent: Float): Float? =
        if (len.unit == Length.Unit.RPX) len.value * rpxRatio
        else len.resolve(parent, viewportWidth, rpxRatio)

    private fun marginOf(node: RenderNode, contentW: Float, contentH: Float): Margins {
        val m = node.style.margin
        return Margins(
            resolve(m.left, contentW), resolve(m.top, contentH),
            resolve(m.right, contentW), resolve(m.bottom, contentH)
        )
    }

    data class Margins(val left: Float, val top: Float, val right: Float, val bottom: Float)

    private fun fallbackLines(text: String, fontSize: Float, maxWidth: Float): List<String> {
        if (text.isEmpty()) return emptyList()
        val perLine = max(1, (maxWidth / (fontSize * 0.6f)).toInt())
        return text.chunked(perLine)
    }

    private fun fallbackWidth(text: String, fontSize: Float): Float = text.length * fontSize * 0.6f
}
