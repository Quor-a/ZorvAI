import io
p = 'miniapp-sdk/src/main/java/com/yuanbao/miniapp/render/Style.kt'
data = io.open(p, 'r', encoding='utf-8').read()

def rep(old, new, count=1):
    global data
    n = data.count(old)
    if n != count:
        raise SystemExit('EXPECTED %d of:\n[%s]\nGOT %d' % (count, old[:100], n))
    data = data.replace(old, new)

old_block = '''    // 网格布局（display:grid）：列/行模板与间隙。
    var gridColumns: List<GridTrack> = emptyList()
    var gridRows: List<GridTrack> = emptyList()
    var gridColumnsSet = false
    var gridRowsSet = false

    // 宽高比（AI 写正方形格子最常用 aspect-ratio:1）。
    var aspectRatio: Float = Float.NaN   // NaN = 未设置
    var aspectRatioSet = false

    // box-sizing：BORDER_BOX（默认，与历史一致）宽含 padding；CONTENT 宽不含。
    var boxSizing: BoxSizing = BoxSizing.BORDER_BOX
    var boxSizingSet = false

    // 单元素交叉轴对齐（覆盖父 alignItems）。
    var alignSelf: AlignItems = AlignItems.STRETCH
    var alignSelfSet = false

    // 栅格/弹性排序。
    var order: Int = 0
    var orderSet = false

    // 文本控制（AI 常用，缺失会导致溢出/不换行/不省略）。
    var whiteSpace: Int = 0          // 0=normal 1=nowrap 2=pre 3=pre-wrap
    var whiteSpaceSet = false
    var textOverflow: Int = 0        // 0=clip 1=ellipsis
    var textOverflowSet = false
    var wordBreak: Int = 0           // 0=normal 1=break-all 2=keep-all
    var wordBreakSet = false
    var textDecoration: Int = 0      // 0=none 1=underline 2=line-through
    var textDecorationSet = false
    var letterSpacing: Float = 0f
    var letterSpacingSet = false
    var fontFamily: String = ""
    var fontFamilySet = false'''
new_block = '''    // 网格布局（display:grid）：列/行模板与间隙。
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
    var fontFamily: String = ""'''
rep(old_block, new_block)

print('Style.kt dedup applied. len=', len(data))
io.open(p, 'w', encoding='utf-8').write(data)
