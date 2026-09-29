import io
p = 'miniapp-sdk/src/main/java/com/yuanbao/miniapp/render/FlexLayout.kt'
data = io.open(p, 'r', encoding='utf-8').read()

def rep(old, new, count=1):
    global data
    n = data.count(old)
    if n != count:
        raise SystemExit('EXPECTED %d of:\n[%s]\nGOT %d' % (count, old[:100], n))
    data = data.replace(old, new)

# measureGridContainer loop: stretch + aspect-ratio
old_m = '''        val rowHeights = ArrayList<Float>()
        for ((i, c) in items.withIndex()) {
            val col = i % cols
            val cw = colWidths[col]
            measure(c, cw, availH, true, st.gridRows.isNotEmpty())
            val cm = marginOf(c, cw, availH)
            val h = c.height + cm.top + cm.bottom
            val row = i / cols
            while (rowHeights.size <= row) rowHeights.add(0f)
            rowHeights[row] = maxOf(rowHeights[row], h)
        }'''
new_m = '''        val rowHeights = ArrayList<Float>()
        for ((i, c) in items.withIndex()) {
            val col = i % cols
            val cw = colWidths[col]
            measure(c, cw, availH, true, st.gridRows.isNotEmpty())
            val cm = marginOf(c, cw, availH)
            // 网格项默认拉伸填满列宽；若显式设定则保持。
            val finalW = if (!c.style.widthSet) maxOf(0f, cw - cm.left - cm.right) else c.width
            // 宽高比：仅一边未设定时按比值推导另一边（否则保持已设定的尺寸）。
            val applyAspect = c.style.aspectRatioSet && !c.style.aspectRatio.isNaN()
            val finalH = when {
                c.style.heightSet -> c.height
                applyAspect && c.style.widthSet -> c.width / c.style.aspectRatio
                applyAspect && !c.style.widthSet -> finalW / c.style.aspectRatio
                else -> c.height
            }
            c.width = finalW
            c.height = finalH
            val h = c.height + cm.top + cm.bottom
            val row = i / cols
            while (rowHeights.size <= row) rowHeights.add(0f)
            rowHeights[row] = maxOf(rowHeights[row], h)
        }'''
rep(old_m, new_m)

# layoutGrid positioning loop: stretch + aspect-ratio
old_l = '''        for ((i, c) in items.withIndex()) {
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
        }'''
new_l = '''        for ((i, c) in items.withIndex()) {
            val col = i % cols
            val row = i / cols
            var cx = contentX
            for (k in 0 until col) cx += colWidths[k] + colGap
            var cy = contentY
            for (k in 0 until row) cy += rowHeights[k] + rowGap
            val cw = colWidths[col]
            val rh = rowHeights[row]
            val cm = marginOf(c, cw, contentH)
            // 与 measureGridContainer 一致：未显式设定则拉伸填满格；aspect-ratio 优先于拉伸。
            val finalW = if (!c.style.widthSet) maxOf(0f, cw - cm.left - cm.right) else c.width
            val applyAspect = c.style.aspectRatioSet && !c.style.aspectRatio.isNaN()
            val finalH = when {
                c.style.heightSet -> c.height
                applyAspect && c.style.widthSet -> c.width / c.style.aspectRatio
                applyAspect && !c.style.widthSet -> finalW / c.style.aspectRatio
                else -> maxOf(0f, rh - cm.top - cm.bottom)
            }
            c.width = finalW
            c.height = finalH
            c.x = (cx - contentX) + cm.left
            c.y = (cy - contentY) + cm.top
            c.absX = cx + cm.left
            c.absY = cy + cm.top
        }'''
rep(old_l, new_l)

print('FlexLayout.kt grid stretch fix applied. len=', len(data))
io.open(p, 'w', encoding='utf-8').write(data)
