package com.yuanbao.miniapp.render

import android.graphics.Color

/** Length value: px / rpx (750rpx = screen width) / percentage / auto. */
data class Length(val value: Float, val unit: Unit, val calc: CalcExpr? = null) {
    enum class Unit { PX, RPX, PERCENT, AUTO, VW, VH, VMIN, VMAX, CALC }

    companion object {
        val AUTO = Length(0f, Unit.AUTO)
        val ZERO = Length(0f, Unit.PX)

        /** calc(...) 二元表达式载体：a [op] b，op 为 '+' 或 '-'。 */
        fun calc(a: Length, op: Char, b: Length) = Length(0f, Unit.CALC, CalcExpr(a, op, b))

        fun parse(raw: String?): Length {
            val s = (raw ?: "").trim()
            if (s.isEmpty() || s == "auto") return AUTO
            // calc(...) 两项式：calc(100% - 20px) / calc(20px + 5%)（AI 常用）
            if (s.startsWith("calc(") && s.endsWith(")")) {
                val inner = s.substring(5, s.length - 1).trim()
                val m = Regex("""^\s*([^\s]+)\s*([+\-])\s*([^\s]+)\s*$""").matchEntire(inner)
                if (m != null) {
                    return calc(parse(m.groupValues[1]), if (m.groupValues[2] == "-") '-' else '+', parse(m.groupValues[3]))
                }
                return parse(inner)
            }
            return when {
                s.endsWith("vmin") -> Length(s.removeSuffix("vmin").toFloatOrNull() ?: 0f, Unit.VMIN)
                s.endsWith("vmax") -> Length(s.removeSuffix("vmax").toFloatOrNull() ?: 0f, Unit.VMAX)
                s.endsWith("vw") -> Length(s.removeSuffix("vw").toFloatOrNull() ?: 0f, Unit.VW)
                s.endsWith("vh") -> Length(s.removeSuffix("vh").toFloatOrNull() ?: 0f, Unit.VH)
                s.endsWith("rpx") -> Length(s.removeSuffix("rpx").toFloatOrNull() ?: 0f, Unit.RPX)
                s.endsWith("%") -> Length(s.removeSuffix("%").toFloatOrNull() ?: 0f, Unit.PERCENT)
                s.endsWith("px") -> Length(s.removeSuffix("px").toFloatOrNull() ?: 0f, Unit.PX)
                else -> Length(s.toFloatOrNull() ?: 0f, Unit.PX)
            }
        }
    }

    /** Resolves against [parentSize]; returns null when auto。
     *  [screenHeight] 用于 vh/vmin/vmax（默认 = screenWidth，旧调用不受影响）。 */
    fun resolve(parentSize: Float, screenWidth: Float, rpxRatio: Float, screenHeight: Float = screenWidth): Float? = when (unit) {
        Unit.PX -> value
        Unit.RPX -> value * rpxRatio
        Unit.PERCENT -> parentSize * value / 100f
        Unit.AUTO -> null
        Unit.VW -> value / 100f * screenWidth
        Unit.VH -> value / 100f * screenHeight
        Unit.VMIN -> value / 100f * minOf(screenWidth, screenHeight)
        Unit.VMAX -> value / 100f * maxOf(screenWidth, screenHeight)
        Unit.CALC -> calc?.let {
            (it.a.resolve(parentSize, screenWidth, rpxRatio, screenHeight) ?: 0f) +
                (if (it.op == '-') -1f else 1f) * (it.b.resolve(parentSize, screenWidth, rpxRatio, screenHeight) ?: 0f)
        }
    }
}

/** calc 二元表达式载体（a +/- b，每项可为 px/rpx/%/vw/vh）。 */
data class CalcExpr(val a: Length, val op: Char, val b: Length)

data class EdgeInsets(val top: Length, val right: Length, val bottom: Length, val left: Length) {
    companion object {
        val ZERO = EdgeInsets(Length.ZERO, Length.ZERO, Length.ZERO, Length.ZERO)
    }
}

/** Supported display / layout values (self-developed, not Android View layout). */
enum class Display { FLEX, BLOCK, NONE, GRID }

/** 网格轨道类型。`fr` 占剩余空间；`percent`/`px` 固定；`auto` 近似 fr=1。 */
enum class TrackType { FR, PERCENT, PX, AUTO }
data class GridTrack(val type: TrackType, val value: Float)
/** box-sizing：CONTENT 宽不含 padding/border；BORDER_BOX 则含（与历史行为默认一致）。 */
enum class BoxSizing { CONTENT, BORDER_BOX }
enum class FlexDirection { ROW, ROW_REVERSE, COLUMN, COLUMN_REVERSE }
enum class JustifyContent { FLEX_START, FLEX_END, CENTER, SPACE_BETWEEN, SPACE_AROUND }
enum class AlignItems { FLEX_START, FLEX_END, CENTER, STRETCH }
enum class FlexWrap { NOWRAP, WRAP }
enum class PositionType { RELATIVE, ABSOLUTE }
enum class TextAlign { LEFT, CENTER, RIGHT }
enum class Overflow { VISIBLE, HIDDEN, SCROLL }
enum class FontWeight { NORMAL, BOLD }

/**
 * Resolved style for a render node. All layout performed by our own FlexLayout,
 * nothing is delegated to the Android View system.
 */
class Style {
    // 微信小程序规范：view 默认块级（纵向堆叠）；只有显式 display:flex 才是弹性横排。
    // 旧默认 FLEX+强制ROW 曾导致所有未写 display 的容器被横排渲染（v0.26.7 修复）。
    var display: Display = Display.BLOCK
    var flexDirection: FlexDirection = FlexDirection.COLUMN
    var justifyContent: JustifyContent = JustifyContent.FLEX_START
    var alignItems: AlignItems = AlignItems.STRETCH
    var flexWrap: FlexWrap = FlexWrap.NOWRAP

    var width: Length = Length.AUTO
    var height: Length = Length.AUTO
    var minWidth: Length = Length.ZERO
    var minHeight: Length = Length.ZERO
    var maxWidth: Length = Length.AUTO
    var maxHeight: Length = Length.AUTO

    var flexGrow: Float = 0f
    // CSS 规范默认 1：内容超宽时收缩而不是溢出裁切（旧默认 0 导致横排内容被右边切掉）
    // 注意：本引擎默认不收缩（CSS 规范是 1）。端上页面普遍按「内容撑高、超出即溢出」的预期书写，
    // 若默认收缩，纵向长页面会被成比例压扁 → 文字互相重叠。仅显式写 flex-shrink 时才收缩。
    var flexShrink: Float = 0f
    var flexBasis: Length = Length.AUTO

    var margin: EdgeInsets = EdgeInsets.ZERO
    var padding: EdgeInsets = EdgeInsets.ZERO

    var position: PositionType = PositionType.RELATIVE
    var left: Length = Length.AUTO
    var top: Length = Length.AUTO
    var right: Length = Length.AUTO
    var bottom: Length = Length.AUTO

    // transform: translate 偏移（绝对定位元素常用 translate(-50%,-50%) 做居中）
    var translateX: Length = Length.AUTO   // AUTO 视为 0
    var translateY: Length = Length.AUTO
    var translateSet = false

    // flex 间距：gap: <row-gap> <column-gap>（row 方向主轴间距 = column-gap，换行间距 = row-gap）
    var gapRow: Length = Length.AUTO
    var gapColumn: Length = Length.AUTO
    var gapSet = false

    // 网格布局（display:grid）：列/行模板与间隙。
    var gridColumns: List<GridTrack> = emptyList()
    var gridRows: List<GridTrack> = emptyList()

    // 宽高比（AI 写正方形格子最常用 aspect-ratio:1）。
    var aspectRatio: Float = Float.NaN   // NaN = 未设置

    // box-sizing：BORDER_BOX（默认，与历史一致）宽含 padding；CONTENT 宽不含。
    var boxSizing: BoxSizing = BoxSizing.BORDER_BOX

    // 单元素交叉轴对齐（覆盖父 alignItems）。
    var alignSelf: AlignItems = AlignItems.STRETCH

    // 栅格/弹性排序。
    var order: Int = 0

    // 文本控制（AI 常用，缺失会导致溢出/不换行/不省略）。
    var whiteSpace: Int = 0          // 0=normal 1=nowrap 2=pre 3=pre-wrap
    var textOverflow: Int = 0        // 0=clip 1=ellipsis
    var wordBreak: Int = 0           // 0=normal 1=break-all 2=keep-all
    var textDecoration: Int = 0      // 0=none 1=underline 2=line-through
    var letterSpacing: Float = 0f
    var fontFamily: String = ""

    var backgroundColor: Int = Color.TRANSPARENT
    var color: Int = Color.BLACK
    var fontSize: Float = 16f          // px
    var fontWeight: FontWeight = FontWeight.NORMAL
    /**
     * font-size / line-height 的原始单位是否为 rpx。
     * 旧实现把 `32rpx` 的单位直接剥掉当 32px 用，而 width/padding/margin 走 Length
     * 会乘 rpxRatio —— 同一份 WXSS 里字号不缩放、盒模型缩放，比例整体失真。
     * 这里保留标记，由 FlexLayout 在 layout 前按 rpxRatio 换算（那时才知道视口宽）。
     */
    var fontSizeIsRpx = false
    var lineHeightIsRpx = false

    var lineHeight: Float = Float.NaN  // px; NaN => auto (1.2 * fontSize)
    var textAlign: TextAlign = TextAlign.LEFT
    var borderRadius: Float = 0f
    var borderWidth: Float = 0f
    var borderColor: Int = Color.TRANSPARENT
    var opacity: Float = 1f
    var zIndex: Int = 0
    var overflow: Overflow = Overflow.VISIBLE
    var maxLines: Int = 0             // 0 = unlimited

    /**
     * Merges another style on top of this one (inline style wins).
     *
     * ⚠️ 必须同时把 other 的 "已显式设置" 标记带过来。
     * 旧实现只搬值、不搬标记 → merge 完 displaySet/flexDirectionSet 全是 false，
     * 于是 FlexLayout.measure() 里那句
     *   if (!st.flexDirectionSet) st.flexDirection = if (display==FLEX) ROW else COLUMN
     * 会把 WXSS 里写死的 `flex-direction: column` **覆盖成 ROW**：
     * 所有「display:flex + flex-direction:column」的容器都被横排渲染，
     * 右侧信息列被挤成一条竖缝、文字一字一行 —— 这正是排版崩坏的根因。
     */
    fun merge(other: Style) {
        if (other.displaySet) { display = other.display; displaySet = true }
        if (other.flexDirectionSet) { flexDirection = other.flexDirection; flexDirectionSet = true }
        if (other.justifySet) { justifyContent = other.justifyContent; justifySet = true }
        if (other.alignSet) { alignItems = other.alignItems; alignSet = true }
        if (other.wrapSet) { flexWrap = other.flexWrap; wrapSet = true }
        if (other.widthSet) { width = other.width; widthSet = true }
        if (other.heightSet) { height = other.height; heightSet = true }
        if (other.minWidthSet) { minWidth = other.minWidth; minWidthSet = true }
        if (other.minHeightSet) { minHeight = other.minHeight; minHeightSet = true }
        if (other.maxWidthSet) { maxWidth = other.maxWidth; maxWidthSet = true }
        if (other.maxHeightSet) { maxHeight = other.maxHeight; maxHeightSet = true }
        if (other.growSet) { flexGrow = other.flexGrow; growSet = true }
        if (other.shrinkSet) { flexShrink = other.flexShrink; shrinkSet = true }
        if (other.basisSet) { flexBasis = other.flexBasis; basisSet = true }
        if (other.marginSet) { margin = other.margin; marginSet = true }
        if (other.paddingSet) { padding = other.padding; paddingSet = true }
        if (other.positionSet) { position = other.position; positionSet = true }
        if (other.leftSet) { left = other.left; leftSet = true }
        if (other.topSet) { top = other.top; topSet = true }
        if (other.rightSet) { right = other.right; rightSet = true }
        if (other.bottomSet) { bottom = other.bottom; bottomSet = true }
        // 旧实现整段漏合并 transform / gap → AI 写的 translate(-50%) 居中与 gap 间距永远失效
        if (other.translateSet) {
            translateX = other.translateX; translateY = other.translateY; translateSet = true
        }
        if (other.gapSet) { gapRow = other.gapRow; gapColumn = other.gapColumn; gapSet = true }
        if (other.gridColumnsSet) { gridColumns = other.gridColumns; gridColumnsSet = true }
        if (other.gridRowsSet) { gridRows = other.gridRows; gridRowsSet = true }
        if (other.aspectRatioSet) { aspectRatio = other.aspectRatio; aspectRatioSet = true }
        if (other.boxSizingSet) { boxSizing = other.boxSizing; boxSizingSet = true }
        if (other.alignSelfSet) { alignSelf = other.alignSelf; alignSelfSet = true }
        if (other.orderSet) { order = other.order; orderSet = true }
        if (other.whiteSpaceSet) { whiteSpace = other.whiteSpace; whiteSpaceSet = true }
        if (other.textOverflowSet) { textOverflow = other.textOverflow; textOverflowSet = true }
        if (other.wordBreakSet) { wordBreak = other.wordBreak; wordBreakSet = true }
        if (other.textDecorationSet) { textDecoration = other.textDecoration; textDecorationSet = true }
        if (other.letterSpacingSet) { letterSpacing = other.letterSpacing; letterSpacingSet = true }
        if (other.fontFamilySet) { fontFamily = other.fontFamily; fontFamilySet = true }
        if (other.bgSet) { backgroundColor = other.backgroundColor; bgSet = true }
        if (other.colorSet) { color = other.color; colorSet = true }
        if (other.fontSizeSet) {
            fontSize = other.fontSize; fontSizeIsRpx = other.fontSizeIsRpx; fontSizeSet = true
        }
        if (other.weightSet) { fontWeight = other.fontWeight; weightSet = true }
        if (other.lineHeightSet) {
            lineHeight = other.lineHeight; lineHeightIsRpx = other.lineHeightIsRpx; lineHeightSet = true
        }
        if (other.textAlignSet) { textAlign = other.textAlign; textAlignSet = true }
        if (other.radiusSet) { borderRadius = other.borderRadius; radiusSet = true }
        if (other.borderWidthSet) { borderWidth = other.borderWidth; borderWidthSet = true }
        if (other.borderColorSet) { borderColor = other.borderColor; borderColorSet = true }
        if (other.opacitySet) { opacity = other.opacity; opacitySet = true }
        if (other.zSet) { zIndex = other.zIndex; zSet = true }
        if (other.overflowSet) { overflow = other.overflow; overflowSet = true }
        if (other.maxLinesSet) { maxLines = other.maxLines; maxLinesSet = true }
    }

    // "was explicitly set" flags, so merging only overrides real declarations
    var displaySet = false
    var flexDirectionSet = false
    var justifySet = false
    var alignSet = false
    var wrapSet = false
    var widthSet = false
    var heightSet = false
    var minWidthSet = false
    var minHeightSet = false
    var maxWidthSet = false
    var maxHeightSet = false
    var growSet = false
    var shrinkSet = false
    var basisSet = false
    var marginSet = false
    var paddingSet = false
    var positionSet = false
    var leftSet = false
    var topSet = false
    var rightSet = false
    var bottomSet = false
    var bgSet = false
    var colorSet = false
    var fontSizeSet = false
    var weightSet = false
    var lineHeightSet = false
    var textAlignSet = false
    var radiusSet = false
    var borderWidthSet = false
    var borderColorSet = false
    var opacitySet = false
    var zSet = false
    var overflowSet = false
    var maxLinesSet = false
    var gridColumnsSet = false
    var gridRowsSet = false
    var aspectRatioSet = false
    var boxSizingSet = false
    var alignSelfSet = false
    var orderSet = false
    var whiteSpaceSet = false
    var textOverflowSet = false
    var wordBreakSet = false
    var textDecorationSet = false
    var letterSpacingSet = false
    var fontFamilySet = false

    fun copy(): Style {
        val s = Style()
        s.display = display; s.flexDirection = flexDirection
        s.justifyContent = justifyContent; s.alignItems = alignItems; s.flexWrap = flexWrap
        s.width = width; s.height = height; s.minWidth = minWidth; s.minHeight = minHeight
        s.maxWidth = maxWidth; s.maxHeight = maxHeight
        s.flexGrow = flexGrow; s.flexShrink = flexShrink; s.flexBasis = flexBasis
        s.margin = margin; s.padding = padding
        s.position = position; s.left = left; s.top = top; s.right = right; s.bottom = bottom
        s.translateX = translateX; s.translateY = translateY; s.translateSet = translateSet
        s.gapRow = gapRow; s.gapColumn = gapColumn; s.gapSet = gapSet
        s.gridColumns = gridColumns; s.gridRows = gridRows
        s.gridColumnsSet = gridColumnsSet; s.gridRowsSet = gridRowsSet
        s.aspectRatio = aspectRatio; s.aspectRatioSet = aspectRatioSet
        s.boxSizing = boxSizing; s.boxSizingSet = boxSizingSet
        s.alignSelf = alignSelf; s.alignSelfSet = alignSelfSet
        s.order = order; s.orderSet = orderSet
        s.whiteSpace = whiteSpace; s.whiteSpaceSet = whiteSpaceSet
        s.textOverflow = textOverflow; s.textOverflowSet = textOverflowSet
        s.wordBreak = wordBreak; s.wordBreakSet = wordBreakSet
        s.textDecoration = textDecoration; s.textDecorationSet = textDecorationSet
        s.letterSpacing = letterSpacing; s.letterSpacingSet = letterSpacingSet
        s.fontFamily = fontFamily; s.fontFamilySet = fontFamilySet
        s.backgroundColor = backgroundColor; s.color = color
        s.fontSize = fontSize; s.fontWeight = fontWeight; s.lineHeight = lineHeight
        s.textAlign = textAlign; s.borderRadius = borderRadius
        s.borderWidth = borderWidth; s.borderColor = borderColor
        s.opacity = opacity; s.zIndex = zIndex; s.overflow = overflow; s.maxLines = maxLines
        s.fontSizeIsRpx = fontSizeIsRpx; s.lineHeightIsRpx = lineHeightIsRpx
        // 标记必须一起复制：否则 copy() 出来的隐式文本节点会被当成"没写过宽度"，
        // 在列向 stretch 时被强行铺满父宽，定宽文字（如右对齐的温度列）全部失效。
        s.displaySet = displaySet; s.flexDirectionSet = flexDirectionSet
        s.justifySet = justifySet; s.alignSet = alignSet; s.wrapSet = wrapSet
        s.widthSet = widthSet; s.heightSet = heightSet
        s.minWidthSet = minWidthSet; s.minHeightSet = minHeightSet
        s.maxWidthSet = maxWidthSet; s.maxHeightSet = maxHeightSet
        s.growSet = growSet; s.shrinkSet = shrinkSet; s.basisSet = basisSet
        s.marginSet = marginSet; s.paddingSet = paddingSet
        s.positionSet = positionSet; s.leftSet = leftSet; s.topSet = topSet
        s.rightSet = rightSet; s.bottomSet = bottomSet
        s.bgSet = bgSet; s.colorSet = colorSet; s.fontSizeSet = fontSizeSet
        s.weightSet = weightSet; s.lineHeightSet = lineHeightSet; s.textAlignSet = textAlignSet
        s.radiusSet = radiusSet; s.borderWidthSet = borderWidthSet; s.borderColorSet = borderColorSet
        s.opacitySet = opacitySet; s.zSet = zSet; s.overflowSet = overflowSet
        s.maxLinesSet = maxLinesSet
        return s
    }

    companion object {
        fun parseColor(raw: String?): Int? {
            val s = (raw ?: "").trim()
            if (s.isEmpty()) return null
            return when {
                s.startsWith("#") -> runCatching { Color.parseColor(s) }.getOrNull()
                s == "transparent" -> Color.TRANSPARENT
                s == "white" -> Color.WHITE
                s == "black" -> Color.BLACK
                s == "red" -> Color.RED
                s == "green" -> Color.GREEN
                s == "blue" -> Color.BLUE
                s == "gray" || s == "grey" -> Color.GRAY
                s.startsWith("rgb") -> {
                    val nums = s.substringAfter("(").substringBefore(")").split(",")
                    val r = nums.getOrNull(0)?.trim()?.toIntOrNull() ?: 0
                    val g = nums.getOrNull(1)?.trim()?.toIntOrNull() ?: 0
                    val b = nums.getOrNull(2)?.trim()?.toIntOrNull() ?: 0
                    when {
                        // rgba：alpha 0~1（旧逻辑直接丢 alpha，半透明遮罩变不透明色块盖住内容）
                        nums.size >= 4 -> Color.argb(
                            ((nums[3].trim().toFloatOrNull() ?: 1f) * 255f).toInt().coerceIn(0, 255),
                            r, g, b
                        )
                        else -> Color.rgb(r, g, b)
                    }
                }
                else -> runCatching { Color.parseColor(s) }.getOrNull()
            }
        }

        /** Builds a Style from a declaration map (keys already normalized to lower case). */
        fun fromDeclarations(decls: Map<String, String>): Style {
            val s = Style()
            for ((rawKey, rawValue) in decls) {
                val key = rawKey.trim().lowercase()
                val v = rawValue.trim()
                when (key) {
                    "display" -> {
                        s.displaySet = true
                        when (v) {
                            "none" -> Display.NONE
                            "block" -> Display.BLOCK
                            "grid" -> Display.GRID
                            else -> Display.FLEX
                        }.also { d -> s.display = d }
                    }
                    "flex-direction" -> { s.flexDirection = parseDirection(v); s.flexDirectionSet = true }
                    "justify-content" -> { s.justifyContent = parseJustify(v); s.justifySet = true }
                    "align-items" -> { s.alignItems = parseAlign(v); s.alignSet = true }
                    "flex-wrap" -> { s.flexWrap = if (v == "wrap") FlexWrap.WRAP else FlexWrap.NOWRAP; s.wrapSet = true }
                    "width" -> { s.width = Length.parse(v); s.widthSet = true }
                    "height" -> { s.height = Length.parse(v); s.heightSet = true }
                    "min-width" -> { s.minWidth = Length.parse(v); s.minWidthSet = true }
                    "min-height" -> { s.minHeight = Length.parse(v); s.minHeightSet = true }
                    "max-width" -> { s.maxWidth = Length.parse(v); s.maxWidthSet = true }
                    "max-height" -> { s.maxHeight = Length.parse(v); s.maxHeightSet = true }
                    "flex" -> {
                        val parts = v.split(" ").filter { it.isNotEmpty() }
                        if (parts.size >= 3) {
                            s.flexGrow = parts[0].toFloatOrNull() ?: 0f
                            s.flexShrink = parts[1].toFloatOrNull() ?: 1f
                            s.flexBasis = Length.parse(parts[2])
                        } else if (parts.size == 1) {
                            val n = parts[0].toFloatOrNull()
                            if (n != null && n == 0f) {
                                s.flexGrow = 0f; s.flexShrink = 0f; s.flexBasis = Length.ZERO
                            } else {
                                s.flexGrow = n ?: 1f; s.flexShrink = 1f; s.flexBasis = Length.ZERO
                            }
                        }
                        s.growSet = true; s.shrinkSet = true; s.basisSet = true
                    }
                    "flex-grow" -> { s.flexGrow = v.toFloatOrNull() ?: 0f; s.growSet = true }
                    "flex-shrink" -> { s.flexShrink = v.toFloatOrNull() ?: 1f; s.shrinkSet = true }
                    "flex-basis" -> { s.flexBasis = Length.parse(v); s.basisSet = true }
                    "margin" -> { s.margin = parseInsets(v); s.marginSet = true }
                    "margin-top" -> { s.margin = s.margin.copy(top = Length.parse(v)); s.marginSet = true }
                    "margin-right" -> { s.margin = s.margin.copy(right = Length.parse(v)); s.marginSet = true }
                    "margin-bottom" -> { s.margin = s.margin.copy(bottom = Length.parse(v)); s.marginSet = true }
                    "margin-left" -> { s.margin = s.margin.copy(left = Length.parse(v)); s.marginSet = true }
                    "padding" -> { s.padding = parseInsets(v); s.paddingSet = true }
                    "padding-top" -> { s.padding = s.padding.copy(top = Length.parse(v)); s.paddingSet = true }
                    "padding-right" -> { s.padding = s.padding.copy(right = Length.parse(v)); s.paddingSet = true }
                    "padding-bottom" -> { s.padding = s.padding.copy(bottom = Length.parse(v)); s.paddingSet = true }
                    "padding-left" -> { s.padding = s.padding.copy(left = Length.parse(v)); s.paddingSet = true }
                    "position" -> { s.position = if (v == "absolute") PositionType.ABSOLUTE else PositionType.RELATIVE; s.positionSet = true }
                    "left" -> { s.left = Length.parse(v); s.leftSet = true }
                    "top" -> { s.top = Length.parse(v); s.topSet = true }
                    "right" -> { s.right = Length.parse(v); s.rightSet = true }
                    "bottom" -> { s.bottom = Length.parse(v); s.bottomSet = true }
                    "background", "background-color" -> { parseColor(v)?.let { s.backgroundColor = it; s.bgSet = true } }
                    "color" -> { parseColor(v)?.let { s.color = it; s.colorSet = true } }
                    "font-size" -> { s.fontSize = parsePx(v); if (v.trim().endsWith("rpx")) s.fontSizeIsRpx = true; s.fontSizeSet = true }
                    "font-weight" -> { s.fontWeight = if (v == "bold" || v.toIntOrNull() ?: 0 >= 600) FontWeight.BOLD else FontWeight.NORMAL; s.weightSet = true }
                    "line-height" -> { s.lineHeight = parsePx(v); if (v.trim().endsWith("rpx")) s.lineHeightIsRpx = true; s.lineHeightSet = true }
                    "text-align" -> { s.textAlign = when (v) { "center" -> TextAlign.CENTER; "right" -> TextAlign.RIGHT; else -> TextAlign.LEFT }; s.textAlignSet = true }
                    "border-radius" -> { s.borderRadius = parsePx(v); s.radiusSet = true }
                    "border-width" -> { s.borderWidth = parsePx(v); s.borderWidthSet = true }
                    "border-color" -> { parseColor(v)?.let { s.borderColor = it; s.borderColorSet = true } }
                    "opacity" -> { s.opacity = v.toFloatOrNull() ?: 1f; s.opacitySet = true }
                    "z-index" -> { s.zIndex = v.toIntOrNull() ?: 0; s.zSet = true }
                    "overflow" -> { s.overflow = when (v) { "hidden" -> Overflow.HIDDEN; "scroll", "auto" -> Overflow.SCROLL; else -> Overflow.VISIBLE }; s.overflowSet = true }
                    "-webkit-line-clamp", "max-lines" -> { s.maxLines = v.toIntOrNull() ?: 0; s.maxLinesSet = true }
                    "grid-template-columns" -> { s.gridColumns = parseGridTemplate(v); s.gridColumnsSet = true }
                    "grid-template-rows" -> { s.gridRows = parseGridTemplate(v); s.gridRowsSet = true }
                    "aspect-ratio" -> { val r = parseAspectRatio(v); if (!r.isNaN()) { s.aspectRatio = r; s.aspectRatioSet = true } }
                    "gap" -> { val p = v.split(Regex("\\s+")).filter { it.isNotEmpty() }; if (p.isNotEmpty()) { s.gapRow = Length.parse(p[0]); s.gapColumn = Length.parse(p.getOrElse(1) { p[0] }); s.gapSet = true } }
                    "row-gap" -> { s.gapRow = Length.parse(v); s.gapSet = true }
                    "column-gap" -> { s.gapColumn = Length.parse(v); s.gapSet = true }
                    "transform" -> { parseTransform(v)?.let { (tx, ty) -> s.translateX = tx; s.translateY = ty; s.translateSet = true } }
                    "box-sizing" -> { s.boxSizing = if (v == "border-box") BoxSizing.BORDER_BOX else BoxSizing.CONTENT; s.boxSizingSet = true }
                    "border" -> parseBorder(v)?.let { (w, c) -> s.borderWidth = w; s.borderColor = c; s.borderWidthSet = true; s.borderColorSet = true }
                    "align-self" -> { s.alignSelf = parseAlign(v); s.alignSelfSet = true }
                    "order" -> { s.order = v.toIntOrNull() ?: 0; s.orderSet = true }
                    "white-space" -> { s.whiteSpace = when (v) { "nowrap" -> 1; "pre" -> 2; "pre-wrap" -> 3; else -> 0 }; s.whiteSpaceSet = true }
                    "text-overflow" -> { s.textOverflow = if (v == "ellipsis") 1 else 0; s.textOverflowSet = true }
                    "word-break" -> { s.wordBreak = when (v) { "break-all" -> 1; "keep-all" -> 2; else -> 0 }; s.wordBreakSet = true }
                    "text-decoration" -> { s.textDecoration = when { v.contains("line-through") -> 2; v.contains("underline") -> 1; else -> 0 }; s.textDecorationSet = true }
                    "letter-spacing" -> { s.letterSpacing = parsePx(v); s.letterSpacingSet = true }
                    "font-family" -> { s.fontFamily = v; s.fontFamilySet = true }
                    else -> Unit
                }
            }
            return s
        }

        private fun parseDirection(v: String) = when (v) {            "row" -> FlexDirection.ROW
            "row-reverse" -> FlexDirection.ROW_REVERSE
            "column-reverse" -> FlexDirection.COLUMN_REVERSE
            else -> FlexDirection.COLUMN
        }

        private fun parseJustify(v: String) = when (v) {
            "flex-end" -> JustifyContent.FLEX_END
            "center" -> JustifyContent.CENTER
            "space-between" -> JustifyContent.SPACE_BETWEEN
            "space-around" -> JustifyContent.SPACE_AROUND
            else -> JustifyContent.FLEX_START
        }

        private fun parseAlign(v: String) = when (v) {
            "flex-start" -> AlignItems.FLEX_START
            "flex-end" -> AlignItems.FLEX_END
            "center" -> AlignItems.CENTER
            else -> AlignItems.STRETCH
        }

        private fun parsePx(v: String): Float {
            val s = v.trim()
            return when {
                s.endsWith("rpx") -> s.removeSuffix("rpx").toFloatOrNull() ?: 0f
                s.endsWith("px") -> s.removeSuffix("px").toFloatOrNull() ?: 0f
                else -> s.toFloatOrNull() ?: 0f
            }
        }

        private fun parseInsets(v: String): EdgeInsets {
            val parts = v.trim().split(Regex("\\s+")).map { Length.parse(it) }
            return when (parts.size) {
                1 -> EdgeInsets(parts[0], parts[0], parts[0], parts[0])
                2 -> EdgeInsets(parts[0], parts[1], parts[0], parts[1])
                3 -> EdgeInsets(parts[0], parts[1], parts[2], parts[1])
                4 -> EdgeInsets(parts[0], parts[1], parts[2], parts[3])
                else -> EdgeInsets.ZERO
            }
        }

        /** 解析 transform 中的 translate / translateX / translateY（支持 px / rpx / %）。 */
        private fun parseTransform(v: String): Pair<Length, Length>? {
            val matches = Regex("translate(?:X|Y)?\\s*\\(([^)]*)\\)").findAll(v).toList()
            if (matches.isEmpty()) return null
            var tx = Length.AUTO
            var ty = Length.AUTO
            for (m in matches) {
                val fn = m.value.substringBefore("(").trim()
                val args = m.groupValues[1].split(",").map { it.trim() }
                when (fn) {
                    "translateX" -> tx = Length.parse(args.getOrElse(0) { "0" })
                    "translateY" -> ty = Length.parse(args.getOrElse(0) { "0" })
                    else -> {
                        tx = Length.parse(args.getOrElse(0) { "0" })
                        ty = Length.parse(args.getOrElse(1) { "0" })
                    }
                }
            }
            return tx to ty
        }

        /** grid-template-columns/rows：支持 repeat(N, X) 与空格分隔的 fr/%/px/auto。 */
        private fun parseGridTemplate(v: String): List<GridTrack> {
            val out = ArrayList<GridTrack>()
            val s = v.trim()
            val rep = Regex("repeat\\(\\s*(\\d+)\\s*,\\s*([^)]+)\\)").find(s)
            if (rep != null) {
                val n = rep.groupValues[1].toIntOrNull() ?: 0
                val inner = rep.groupValues[2].trim()
                for (i in 0 until n) out.add(parseTrack(inner))
                val before = s.substring(0, rep.range.first).trim()
                val after = s.substring(rep.range.last + 1).trim()
                for (seg in listOf(before, after)) {
                    for (tok in seg.split(Regex("\\s+")).filter { it.isNotEmpty() }) out.add(parseTrack(tok))
                }
            } else {
                for (tok in s.split(Regex("\\s+")).filter { it.isNotEmpty() }) out.add(parseTrack(tok))
            }
            return out
        }

        private fun parseTrack(tok: String): GridTrack {
            val t = tok.trim()
            return when {
                t.endsWith("fr") -> GridTrack(TrackType.FR, t.removeSuffix("fr").toFloatOrNull() ?: 1f)
                t.endsWith("%") -> GridTrack(TrackType.PERCENT, t.removeSuffix("%").toFloatOrNull() ?: 0f)
                t == "auto" -> GridTrack(TrackType.AUTO, 1f)
                else -> GridTrack(TrackType.PX, Length.parse(t).value)
            }
        }

        /** aspect-ratio: "1" / "1/1" / "16/9" -> 宽/高 比值；无法解析返回 NaN。 */
        private fun parseAspectRatio(v: String): Float {
            val s = v.trim().replace(Regex("\\s+"), "")
            if (s.contains("/")) {
                val parts = s.split("/")
                val a = parts.getOrNull(0)?.toFloatOrNull() ?: return Float.NaN
                val b = parts.getOrNull(1)?.toFloatOrNull() ?: return Float.NaN
                return if (b != 0f) a / b else Float.NaN
            }
            return v.trim().toFloatOrNull() ?: Float.NaN
        }

        /** border 简写：border: 1px solid #fff -> (width, color)，忽略线型。 */
        private fun parseBorder(v: String): Pair<Float, Int>? {
            var w = 0f
            var c: Int? = null
            for (part in v.trim().split(Regex("\\s+"))) {
                if (part.endsWith("px")) w = parsePx(part)
                parseColor(part)?.let { c = it }
            }
            return if (w > 0f || c != null) (w to (c ?: Color.TRANSPARENT)) else null
        }
    }
}
