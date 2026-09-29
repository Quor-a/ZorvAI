import io
p = 'miniapp-sdk/src/main/java/com/yuanbao/miniapp/render/Style.kt'
data = io.open(p, 'r', encoding='utf-8').read()

def rep(old, new, count=1):
    global data
    n = data.count(old)
    if n != count:
        raise SystemExit('EXPECTED %d of:\n[%s]\nGOT %d' % (count, old[:90], n))
    data = data.replace(old, new)

# ---- Edit 7: display grid real (remove downgrade) ----
old_disp = '''                    "display" -> {
                        s.displaySet = true
                        when (v) {
                            "none" -> Display.NONE
                            "block" -> Display.BLOCK
                            "grid" -> { // 网格布局降级模拟：横排+换行（格子自带定宽即可成行成列）
                                s.flexDirection = FlexDirection.ROW
                                s.flexWrap = FlexWrap.WRAP
                                s.wrapSet = true
                                Display.FLEX
                            }
                            else -> Display.FLEX
                        }.also { d -> s.display = d }
                    }'''
new_disp = '''                    "display" -> {
                        s.displaySet = true
                        when (v) {
                            "none" -> Display.NONE
                            "block" -> Display.BLOCK
                            "grid" -> Display.GRID
                            else -> Display.FLEX
                        }.also { d -> s.display = d }
                    }'''
rep(old_disp, new_disp)

# ---- Edit 8: new fromDeclarations cases before else -> Unit ----
old_else = '''                    "-webkit-line-clamp", "max-lines" -> { s.maxLines = v.toIntOrNull() ?: 0; s.maxLinesSet = true }
                    else -> Unit'''
new_cases = (
    '                    "-webkit-line-clamp", "max-lines" -> { s.maxLines = v.toIntOrNull() ?: 0; s.maxLinesSet = true }\n'
    '                    "grid-template-columns" -> { s.gridColumns = parseGridTemplate(v); s.gridColumnsSet = true }\n'
    '                    "grid-template-rows" -> { s.gridRows = parseGridTemplate(v); s.gridRowsSet = true }\n'
    '                    "aspect-ratio" -> { val r = parseAspectRatio(v); if (!r.isNaN()) { s.aspectRatio = r; s.aspectRatioSet = true } }\n'
    '                    "gap" -> { val p = v.split(Regex("\\\\s+")).filter { it.isNotEmpty() }; if (p.isNotEmpty()) { s.gapRow = Length.parse(p[0]); s.gapColumn = Length.parse(p.getOrElse(1) { p[0] }); s.gapSet = true } }\n'
    '                    "row-gap" -> { s.gapRow = Length.parse(v); s.gapSet = true }\n'
    '                    "column-gap" -> { s.gapColumn = Length.parse(v); s.gapSet = true }\n'
    '                    "transform" -> { parseTransform(v)?.let { (tx, ty) -> s.translateX = tx; s.translateY = ty; s.translateSet = true } }\n'
    '                    "box-sizing" -> { s.boxSizing = if (v == "border-box") BoxSizing.BORDER_BOX else BoxSizing.CONTENT; s.boxSizingSet = true }\n'
    '                    "border" -> parseBorder(v)?.let { (w, c) -> s.borderWidth = w; s.borderColor = c; s.borderWidthSet = true; s.borderColorSet = true }\n'
    '                    "align-self" -> { s.alignSelf = parseAlign(v); s.alignSelfSet = true }\n'
    '                    "order" -> { s.order = v.toIntOrNull() ?: 0; s.orderSet = true }\n'
    '                    "white-space" -> { s.whiteSpace = when (v) { "nowrap" -> 1; "pre" -> 2; "pre-wrap" -> 3; else -> 0 }; s.whiteSpaceSet = true }\n'
    '                    "text-overflow" -> { s.textOverflow = if (v == "ellipsis") 1 else 0; s.textOverflowSet = true }\n'
    '                    "word-break" -> { s.wordBreak = when (v) { "break-all" -> 1; "keep-all" -> 2; else -> 0 }; s.wordBreakSet = true }\n'
    '                    "text-decoration" -> { s.textDecoration = when { v.contains("line-through") -> 2; v.contains("underline") -> 1; else -> 0 }; s.textDecorationSet = true }\n'
    '                    "letter-spacing" -> { s.letterSpacing = parsePx(v); s.letterSpacingSet = true }\n'
    '                    "font-family" -> { s.fontFamily = v; s.fontFamilySet = true }\n'
    '                    else -> Unit'
)
rep(old_else, new_cases)

# ---- Edit 9: helper functions after parseTransform ----
old_help = '''            return tx to ty
        }
    }
}'''
new_help = (
    '            return tx to ty\n'
    '        }\n'
    '\n'
    '        /** grid-template-columns/rows：支持 repeat(N, X) 与空格分隔的 fr/%/px/auto。 */\n'
    '        private fun parseGridTemplate(v: String): List<GridTrack> {\n'
    '            val out = ArrayList<GridTrack>()\n'
    '            val s = v.trim()\n'
    '            val rep = Regex("repeat\\\\(\\\\s*(\\\\d+)\\\\s*,\\\\s*([^)]+)\\\\)").find(s)\n'
    '            if (rep != null) {\n'
    '                val n = rep.groupValues[1].toIntOrNull() ?: 0\n'
    '                val inner = rep.groupValues[2].trim()\n'
    '                for (i in 0 until n) out.add(parseTrack(inner))\n'
    '                val before = s.substring(0, rep.range.first).trim()\n'
    '                val after = s.substring(rep.range.last + 1).trim()\n'
    '                for (seg in listOf(before, after)) {\n'
    '                    for (tok in seg.split(Regex("\\\\s+")).filter { it.isNotEmpty() }) out.add(parseTrack(tok))\n'
    '                }\n'
    '            } else {\n'
    '                for (tok in s.split(Regex("\\\\s+")).filter { it.isNotEmpty() }) out.add(parseTrack(tok))\n'
    '            }\n'
    '            return out\n'
    '        }\n'
    '\n'
    '        private fun parseTrack(tok: String): GridTrack {\n'
    '            val t = tok.trim()\n'
    '            return when {\n'
    '                t.endsWith("fr") -> GridTrack(TrackType.FR, t.removeSuffix("fr").toFloatOrNull() ?: 1f)\n'
    '                t.endsWith("%") -> GridTrack(TrackType.PERCENT, t.removeSuffix("%").toFloatOrNull() ?: 0f)\n'
    '                t == "auto" -> GridTrack(TrackType.AUTO, 1f)\n'
    '                else -> GridTrack(TrackType.PX, Length.parse(t).value)\n'
    '            }\n'
    '        }\n'
    '\n'
    '        /** aspect-ratio: "1" / "1/1" / "16/9" -> 宽/高 比值；无法解析返回 NaN。 */\n'
    '        private fun parseAspectRatio(v: String): Float {\n'
    '            val s = v.trim().replace(Regex("\\\\s+"), "")\n'
    '            if (s.contains("/")) {\n'
    '                val parts = s.split("/")\n'
    '                val a = parts.getOrNull(0)?.toFloatOrNull() ?: return Float.NaN\n'
    '                val b = parts.getOrNull(1)?.toFloatOrNull() ?: return Float.NaN\n'
    '                return if (b != 0f) a / b else Float.NaN\n'
    '            }\n'
    '            return v.trim().toFloatOrNull() ?: Float.NaN\n'
    '        }\n'
    '\n'
    '        /** border 简写：border: 1px solid #fff -> (width, color)，忽略线型。 */\n'
    '        private fun parseBorder(v: String): Pair<Float, Int>? {\n'
    '            var w = 0f\n'
    '            var c: Int? = null\n'
    '            for (part in v.trim().split(Regex("\\\\s+"))) {\n'
    '                if (part.endsWith("px")) w = parsePx(part)\n'
    '                parseColor(part)?.let { c = it }\n'
    '            }\n'
    '            return if (w > 0f || c != null) (w to (c ?: Color.TRANSPARENT)) else null\n'
    '        }\n'
    '    }\n'
    '}'
)
rep(old_help, new_help)

print('Style.kt part3 applied. len=', len(data))
io.open(p, 'w', encoding='utf-8').write(data)
