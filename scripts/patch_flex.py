import io
p = 'miniapp-sdk/src/main/java/com/yuanbao/miniapp/render/FlexLayout.kt'
data = io.open(p, 'r', encoding='utf-8').read()

def rep(old, new, count=1):
    global data
    n = data.count(old)
    if n != count:
        raise SystemExit('EXPECTED %d of:\n[%s]\nGOT %d' % (count, old[:100], n))
    data = data.replace(old, new)

# ---- F0: resolve wrappers pass viewportHeight (for vh/vmin/vmax) ----
old_res = '''    fun resolve(len: Length, parent: Float): Float = len.resolve(parent, viewportWidth, rpxRatio) ?: 0f

    private fun resolveOrNull(len: Length, parent: Float): Float? =
        if (len.unit == Length.Unit.RPX) len.value * rpxRatio
        else len.resolve(parent, viewportWidth, rpxRatio)'''
new_res = '''    fun resolve(len: Length, parent: Float): Float = len.resolve(parent, viewportWidth, rpxRatio, viewportHeight) ?: 0f

    private fun resolveOrNull(len: Length, parent: Float): Float? =
        if (len.unit == Length.Unit.RPX) len.value * rpxRatio
        else len.resolve(parent, viewportWidth, rpxRatio, viewportHeight)'''
rep(old_res, new_res)

# ---- F1: box-sizing + contentW ----
old_cw = '''        // CSS 规范：父尺寸未定义（auto/内容撑开）时，子元素的百分比尺寸退化为 auto。
        // 修复：AI 给卡片写 height:100%（配 flex 居中），父高是内容撑开时被解析成
        // "父的可用高"（整屏）——卡片瞬间变成几千像素高的白条。
        val specifiedW = if (st.width.unit == Length.Unit.PERCENT && !widthDefinite) null
                         else resolveOrNull(st.width, availW)
        val specifiedH = if (st.height.unit == Length.Unit.PERCENT && !heightDefinite) null
                         else resolveOrNull(st.height, availH)

        val contentW = when {
            specifiedW != null -> max(0f, specifiedW - padH)
            else -> null
        }'''
new_cw = '''        // CSS 规范：父尺寸未定义（auto/内容撑开）时，子元素的百分比尺寸退化为 auto。
        // 修复：AI 给卡片写 height:100%（配 flex 居中），父高是内容撑开时被解析成
        // "父的可用高"（整屏）——卡片瞬间变成几千像素高的白条。
        val specifiedW = if (st.width.unit == Length.Unit.PERCENT && !widthDefinite) null
                         else resolveOrNull(st.width, availW)
        val specifiedH = if (st.height.unit == Length.Unit.PERCENT && !heightDefinite) null
                         else resolveOrNull(st.height, availH)

        // box-sizing：默认 BORDER_BOX（与历史行为一致）；CONTENT 时 width 不含 padding。
        val isBorderBox = st.boxSizing != BoxSizing.CONTENT
        val contentW = when {
            specifiedW != null -> if (isBorderBox) max(0f, specifiedW - padH) else specifiedW
            else -> null
        }'''
rep(old_cw, new_cw)

# ---- F2: aspect-ratio application after the node-type when block ----
old_aspect = '''        }

        // min / max constraints'''
new_aspect = '''        }

        // 宽高比：仅当一边显式设定、另一边未设定时按比值推导另一边（AI 写正方形格子关键）。
        if (st.aspectRatioSet && !st.aspectRatio.isNaN()) {
            if (st.widthSet && !st.heightSet) node.height = node.width / st.aspectRatio
            else if (st.heightSet && !st.widthSet) node.width = node.height * st.aspectRatio
        }

        // min / max constraints'''
rep(old_aspect, new_aspect)

# ---- F3: container node.width respects box-sizing (replace old line) ----
old_cw_end = '''                node.width = specifiedW ?: min(availW, intrinsicW + padH)
                node.height = specifiedH ?: (intrinsicH + padV)
            }'''
new_cw_end = '''                node.width = when { specifiedW != null -> if (isBorderBox) specifiedW else specifiedW + padH; else -> min(availW, intrinsicW + padH) }
                node.height = specifiedH ?: (intrinsicH + padV)
                }
            }'''
rep(old_cw_end, new_cw_end)

# ---- F4: grid branch in measure container else block (after innerH) ----
old_grid_meas = '''                val innerH = max(0f, availH - padV)
                val row = isRow(st)'''
new_grid_meas = '''                val innerH = max(0f, availH - padV)
                if (st.display == Display.GRID && node.children.isNotEmpty()) {
                    measureGridContainer(node, availW, availH, innerW, innerH, specifiedW, specifiedH, padH, padV)
                } else {
                val row = isRow(st)'''
rep(old_grid_meas, new_grid_meas)

# ---- F5: grid path in layoutChildren (after contentH) ----
old_lay = '''        val contentH = max(0f, node.height - padT - padB)'''
new_lay = '''        val contentH = max(0f, node.height - padT - padB)

        // 网格布局：自己定位子元素后直接返回（不走下方 flex 流程）。
        if (st.display == Display.GRID && node.children.isNotEmpty()) {
            layoutGrid(node, availW, availH, contentX, contentY, contentW, contentH)
            return
        }'''
rep(old_lay, new_lay)

# ---- F6: append grid helper functions before final class close ----
old_tail = '''    private fun fallbackWidth(text: String, fontSize: Float): Float = text.length * fontSize * 0.6f
}'''
grid_fns = '''    private fun fallbackWidth(text: String, fontSize: Float): Float = text.length * fontSize * 0.6f

    // ---------------------------------------------------------------- grid
    /** 解析一组网格轨道尺寸（fr 占剩余、percent/px 固定、auto 近似 fr=1）。 */
    private fun gridTrackSizes(tracks: List<GridTrack>, basis: Float): List<Float> {
        if (tracks.isEmpty()) return listOf(basis)
        val sizes = ArrayList<Float>(tracks.size)
        var fixed = 0f
        val frWeights = ArrayList<Float>()
        for (t in tracks) {
            when (t.type) {
                TrackType.PX -> { sizes.add(t.value); fixed += t.value }
                TrackType.PERCENT -> { val v = basis * t.value / 100f; sizes.add(v); fixed += v }
                TrackType.FR -> { sizes.add(0f); frWeights.add(t.value) }
                TrackType.AUTO -> { sizes.add(0f); frWeights.add(1f) }
            }
        }
        val frTotal = frWeights.sum()
        val leftover = basis - fixed
        if (frTotal > 0f && leftover > 0f) {
            var k = 0
            for (i in tracks.indices) {
                if (tracks[i].type == TrackType.FR || tracks[i].type == TrackType.AUTO) {
                    sizes[i] = leftover * (frWeights[k] / frTotal); k++
                }
            }
        }
        return sizes
    }

    /** 网格容器在 measure 阶段的尺寸计算：按列宽测量子项、累加行高。 */
    private fun measureGridContainer(node: RenderNode, availW: Float, availH: Float, innerW: Float, innerH: Float,
                                    specifiedW: Float?, specifiedH: Float?, padH: Float, padV: Float) {
        val st = node.style
        val items = node.children.filter { it.style.display != Display.NONE }
        val cols = if (st.gridColumns.isNotEmpty()) st.gridColumns.size else 1
        val isBB = st.boxSizing != BoxSizing.CONTENT
        val basisW = if (specifiedW != null) (if (isBB) specifiedW - padH else specifiedW) else innerW
        val colWidths = gridTrackSizes(st.gridColumns, basisW)
        val colGap = if (st.gapSet) (resolveOrNull(st.gapColumn, basisW) ?: 0f) else 0f
        val basisH = if (specifiedH != null) (if (isBB) specifiedH - padV else specifiedH) else innerH
        val rowGap = if (st.gapSet) (resolveOrNull(st.gapRow, basisH) ?: 0f) else 0f
        val rowHeights = ArrayList<Float>()
        for ((i, c) in items.withIndex()) {
            val col = i % cols
            val cw = colWidths[col]
            measure(c, cw, availH, true, st.gridRows.isNotEmpty())
            val cm = marginOf(c, cw, availH)
            val h = c.height + cm.top + cm.bottom
            val row = i / cols
            while (rowHeights.size <= row) rowHeights.add(0f)
            rowHeights[row] = maxOf(rowHeights[row], h)
        }
        val rows = if (st.gridRows.isNotEmpty()) st.gridRows.size else rowHeights.size
        val usedRows = if (st.gridRows.isNotEmpty()) gridTrackSizes(st.gridRows, basisH) else rowHeights
        var contentWGrid = 0f; for (x in colWidths) contentWGrid += x
        contentWGrid += (cols - 1) * colGap
        var contentHGrid = 0f; for (x in usedRows) contentHGrid += x
        contentHGrid += (rows - 1) * rowGap
        node.width = when { specifiedW != null -> if (isBB) specifiedW else specifiedW + padH; else -> minOf(availW, contentWGrid + padH) }
        node.height = when { specifiedH != null -> if (isBB) specifiedH else specifiedH + padV; else -> contentHGrid + padV }
    }

    /** 网格容器在 layout 阶段的子元素定位 + 递归子树布局。 */
    private fun layoutGrid(node: RenderNode, availW: Float, availH: Float, contentX: Float, contentY: Float, contentW: Float, contentH: Float) {
        val st = node.style
        val items = node.children.filter { it.style.display != Display.NONE }
        // 绝对定位子元素照常放置
        for (c in node.children) {
            if (c.style.display == Display.NONE) continue
            if (c.style.position == PositionType.ABSOLUTE) placeAbsolute(c, contentX, contentY, contentW, contentH)
        }
        if (items.isEmpty()) return
        val cols = if (st.gridColumns.isNotEmpty()) st.gridColumns.size else 1
        val colWidths = gridTrackSizes(st.gridColumns, contentW)
        val colGap = if (st.gapSet) (resolveOrNull(st.gapColumn, contentW) ?: 0f) else 0f
        val rowGap = if (st.gapSet) (resolveOrNull(st.gapRow, contentH) ?: 0f) else 0f
        val autoRowHeights = ArrayList<Float>()
        for ((i, c) in items.withIndex()) {
            val col = i % cols
            val cw = colWidths[col]
            val cm = marginOf(c, cw, contentH)
            val h = c.height + cm.top + cm.bottom
            val row = i / cols
            while (autoRowHeights.size <= row) autoRowHeights.add(0f)
            autoRowHeights[row] = maxOf(autoRowHeights[row], h)
        }
        val rowHeights = if (st.gridRows.isNotEmpty()) gridTrackSizes(st.gridRows, contentH) else autoRowHeights
        for ((i, c) in items.withIndex()) {
            val col = i % cols
            val row = i / cols
            var cx = contentX
            for (k in 0 until col) cx += colWidths[k] + colGap
            var cy = contentY
            for (k in 0 until row) cy += rowHeights[k] + rowGap
            val cw = colWidths[col]
            val rh = rowHeights[row]
            val cm = marginOf(c, cw, contentH)
            if (!c.style.widthSet) c.width = maxOf(0f, cw - cm.left - cm.right)
            if (!c.style.heightSet && !(c.style.aspectRatioSet && c.style.widthSet)) c.height = maxOf(0f, rh - cm.top - cm.bottom)
            c.x = (cx - contentX) + cm.left
            c.y = (cy - contentY) + cm.top
            c.absX = cx + cm.left
            c.absY = cy + cm.top
        }
        for ((i, c) in items.withIndex()) {
            val cw = colWidths[i % cols]
            val rh = rowHeights[i / cols]
            layoutChildren(c, cw, rh)
        }
        for (c in node.children) {
            if (c.style.display == Display.NONE) continue
            if (c.style.position == PositionType.ABSOLUTE) layoutChildren(c, contentW, contentH)
        }
    }
}'''
rep(old_tail, grid_fns)

print('FlexLayout.kt patched. len=', len(data))
io.open(p, 'w', encoding='utf-8').write(data)
