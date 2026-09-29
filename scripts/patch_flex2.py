import io
p = 'miniapp-sdk/src/main/java/com/yuanbao/miniapp/render/FlexLayout.kt'
data = io.open(p, 'r', encoding='utf-8').read()

def rep(old, new, count=1):
    global data
    n = data.count(old)
    if n != count:
        raise SystemExit('EXPECTED %d of:\n[%s]\nGOT %d' % (count, old[:100], n))
    data = data.replace(old, new)

# positionLine: 让 gap 在「主轴」上也生效（旧实现只在 justify-content 间距里算，nowrap flex 行永远无 gap）。
old_gap = '''        val (gapStart, gapBetween) = when (st.justifyContent) {
            JustifyContent.FLEX_START -> 0f to 0f
            JustifyContent.FLEX_END -> free to 0f
            JustifyContent.CENTER -> free / 2f to 0f
            JustifyContent.SPACE_BETWEEN -> 0f to (if (ordered.size > 1) free / (ordered.size - 1) else 0f)
            JustifyContent.SPACE_AROUND -> {
                val g = free / ordered.size
                (g / 2f) to g
            }
        }'''
new_gap = '''        // gap：主轴方向相邻子项的固定间距（row 方向=column-gap，column 方向=row-gap）。
        val mainGapRaw = if (row) st.gapColumn else st.gapRow
        val mainGap = if (st.gapSet) (resolveOrNull(mainGapRaw, mainSize) ?: 0f) else 0f
        val nItems = ordered.size
        val freeWithGap = max(0f, free - mainGap * max(0, nItems - 1))
        val (gapStart, gapBetween) = when (st.justifyContent) {
            JustifyContent.FLEX_START -> 0f to mainGap
            JustifyContent.FLEX_END -> freeWithGap to mainGap
            JustifyContent.CENTER -> freeWithGap / 2f to mainGap
            JustifyContent.SPACE_BETWEEN -> 0f to (if (nItems > 1) mainGap + freeWithGap / (nItems - 1) else mainGap)
            JustifyContent.SPACE_AROUND -> {
                val g = if (nItems > 0) freeWithGap / nItems else 0f
                (g / 2f) to (mainGap + g)
            }
        }'''
rep(old_gap, new_gap)

print('FlexLayout.kt gap fix applied. len=', len(data))
io.open(p, 'w', encoding='utf-8').write(data)
