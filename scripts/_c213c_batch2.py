# -*- coding: utf-8 -*-
"""
#213 第二批：对话框渲染补齐
- 把代码块 / 表格实现提升为公开可组合，接到 ChatScreen 的真路径（MsgBlock.Code / MsgBlock.Table）
- 新增：块级数学公式、脚注定义、可折叠标题（大纲）、表格排序/折叠/复制 CSV
- 全部原子写（.tmp + os.replace），保留原文件行尾
"""
import io
import os
import sys

ROOT = r"D:\Calw OS-project\QuroAI"
RT = os.path.join(ROOT, r"app\src\main\java\com\ai\assistance\quro\ui\dialog\RichText.kt")
CS = os.path.join(ROOT, r"app\src\main\java\com\ai\assistance\quro\ui\ChatScreen.kt")


def load(p):
    with io.open(p, "r", encoding="utf-8", newline="") as f:
        raw = f.read()
    nl = "\r\n" if "\r\n" in raw else "\n"
    return raw.replace("\r\n", "\n"), nl


def save(p, text, nl):
    out = text.replace("\n", nl)
    tmp = p + ".tmp"
    with io.open(tmp, "w", encoding="utf-8", newline="") as f:
        f.write(out)
    os.replace(tmp, p)


def rep(text, old, new, tag, log):
    n = text.count(old)
    if n != 1:
        log.append("FAIL %s: 锚点命中 %d 次" % (tag, n))
        return text
    log.append("ok   %s" % tag)
    return text.replace(old, new)


log = []

# ══════════════════════════════════════════════════════════════
# RichText.kt
# ══════════════════════════════════════════════════════════════
t, nl_rt = load(RT)

# ── 1. MdBlock 新增 Math / Footnotes ──────────────────────────
t = rep(t,
    "    /** `<details><summary>…</summary>…</details>` 折叠区块。 */\n"
    "    data class Details(val summary: String, val body: String) : MdBlock\n"
    "}",
    "    /** `<details><summary>…</summary>…</details>` 折叠区块。 */\n"
    "    data class Details(val summary: String, val body: String) : MdBlock\n"
    "\n"
    "    /**\n"
    "     * 块级数学公式 `$$…$$`（独立成行）。\n"
    "     *\n"
    "     * 行内 `$…$` 由 [parseInlineSpans] 处理；这里只管独占一行的展示级公式。\n"
    "     * 不做真 KaTeX 渲染（那需要 WebView，气泡里一条消息塞 N 个 WebView 会卡死），\n"
    "     * 但保证**公式内部不被 Markdown 拆烂**（`a_i` / `x^2` / `*` 都是公式语法）。\n"
    "     */\n"
    "    data class Math(val tex: String) : MdBlock\n"
    "\n"
    "    /**\n"
    "     * 文末脚注定义（`[^1]: 释义`）。\n"
    "     *\n"
    "     * 正文里的 `[^1]` 已渲染成上标角标，这里统一挂到文末给出释义 ——\n"
    "     * 旧实现只做了角标、没有释义，等于点了没反应。\n"
    "     */\n"
    "    data class Footnotes(val items: List<FootnoteItem>) : MdBlock\n"
    "}\n"
    "\n"
    "/** 一条脚注定义。[id] 不含方括号与 `^`。 */\n"
    "data class FootnoteItem(val id: String, val text: String)",
    "MdBlock+Math/Footnotes", log)

# ── 2. InlineSpan 新增 math ──────────────────────────────────
t = rep(t,
    "    /** 非空表示命中了 `<c=#RRGGBB>` 着色约定。 */\n"
    "    val colorHex: String? = null,\n"
    ")",
    "    /** 非空表示命中了 `<c=#RRGGBB>` 着色约定。 */\n"
    "    val colorHex: String? = null,\n"
    "    /** 数学公式（`$…$` / `$$…$$`）：内部不做任何 Markdown 解析。 */\n"
    "    val math: Boolean = false,\n"
    ")",
    "InlineSpan+math", log)

# ── 3. 正则与常量 ────────────────────────────────────────────
t = rep(t,
    'private val RE_IMAGE_LINE = Regex("^\\\\s*!\\[([^\\\\]]*)\\]\\\\(([^)\\\\s]+)[^)]*\\\\)\\\\s*$")',
    'private val RE_IMAGE_LINE = Regex("^\\\\s*!\\[([^\\\\]]*)\\]\\\\(([^)\\\\s]+)[^)]*\\\\)\\\\s*$")\n'
    '\n'
    '/** 脚注定义行：`[^1]: 释义`。 */\n'
    'private val RE_FOOTNOTE_DEF = Regex("^\\\\s*\\\\[\\\\^([^\\\\]]+)]\\\\s*:\\\\s*(.*)$")\n'
    '\n'
    '/**\n'
    ' * 美元符常量。\n'
    ' *\n'
    ' * 🔴 必须这么写：Kotlin 字符串里 `$` 是模板起始符，`"$$"` 编译不过、\n'
    ' *    `"\\\\$"` 又依赖编译器对 `\\$` 的支持版本。用 charArray 构造最稳。\n'
    ' */\n'
    "private val MATH_DELIM = String(charArrayOf('$', '$'))\n"
    "private val MATH_DOLLAR = String(charArrayOf('$'))",
    "regex+const", log)

# ── 4. 行内解析：数学区间保护 ────────────────────────────────
t = rep(t,
    "            // 链接 [text](url)\n"
    "            c == '[' -> {",
    "            // 数学公式 $…$ / $$…$$：内部一律按字面输出，不做任何 Markdown 解析。\n"
    "            //\n"
    "            // 🔴 不保护会怎样：`$a_i + b_j$` 里的 `_i + b_` 会被当成斜体区间，\n"
    "            //    `$x^2$` 里的 `^2^` 会被当成上标，公式被拆成两半 —— 这是**当前的真实缺陷**，\n"
    "            //    模型一输出公式就烂。保护后至少原文完整、可读。\n"
    "            c == '$' -> {\n"
    "                val dbl = src.startsWith(MATH_DELIM, i)\n"
    "                val delim = if (dbl) MATH_DELIM else MATH_DOLLAR\n"
    "                val from = i + delim.length\n"
    "                val end = src.indexOf(delim, from)\n"
    "                if (end > from) {\n"
    "                    emit(src.substring(from, end)) { it.copy(math = true) }\n"
    "                    i = end + delim.length\n"
    "                } else {\n"
    "                    buf.append(c); i++\n"
    "                }\n"
    "            }\n"
    "\n"
    "            // 链接 [text](url)\n"
    "            c == '[' -> {",
    "inline+math", log)

# ── 5. parseMarkdown：脚注收集 ───────────────────────────────
t = rep(t,
    "fun parseMarkdown(src: String): List<MdBlock> {\n"
    "    val lines = src.replace(\"\\r\\n\", \"\\n\").replace('\\r', '\\n').split(\"\\n\")\n"
    "    val out = mutableListOf<MdBlock>()\n"
    "    var i = 0",
    "fun parseMarkdown(src: String): List<MdBlock> {\n"
    "    val lines = src.replace(\"\\r\\n\", \"\\n\").replace('\\r', '\\n').split(\"\\n\")\n"
    "    val out = mutableListOf<MdBlock>()\n"
    "    val footnotes = mutableListOf<FootnoteItem>()\n"
    "    var i = 0",
    "parseMarkdown+footnoteBuf", log)

t = rep(t,
    "        val line = lines[i]\n"
    "\n"
    "        // ── 围栏代码块（优先级最高，内部一切 Markdown 都不解析）──",
    "        val line = lines[i]\n"
    "\n"
    "        // ── 脚注定义 `[^1]: 释义`：先抽出来，统一挂到文末 ──\n"
    "        //    模型常把脚注定义写在段落中间，就地渲染会把正文劈成两截。\n"
    "        if (RE_FOOTNOTE_DEF.matches(line)) {\n"
    "            val fm = RE_FOOTNOTE_DEF.find(line)!!\n"
    "            val defText = fm.groupValues[2].trim()\n"
    "            if (defText.isNotEmpty()) {\n"
    "                footnotes.add(FootnoteItem(fm.groupValues[1].trim(), defText))\n"
    "                i++\n"
    "                continue\n"
    "            }\n"
    "        }\n"
    "\n"
    "        // ── 围栏代码块（优先级最高，内部一切 Markdown 都不解析）──",
    "parseMarkdown+footnoteBranch", log)

# ── 6. parseMarkdown：块级公式分支 ───────────────────────────
t = rep(t,
    "            RE_IMAGE_LINE.matches(line) -> {",
    "            // 块级公式：$$…$$ 独占一行\n"
    "            line.trim().startsWith(MATH_DELIM) && line.trim().endsWith(MATH_DELIM) &&\n"
    "                line.trim().length > MATH_DELIM.length * 2 -> {\n"
    "                val tl = line.trim()\n"
    "                out.add(MdBlock.Math(tl.substring(MATH_DELIM.length, tl.length - MATH_DELIM.length).trim()))\n"
    "                i++\n"
    "            }\n"
    "\n"
    "            RE_IMAGE_LINE.matches(line) -> {",
    "parseMarkdown+mathBlock", log)

t = rep(t,
    "            else -> {\n"
    "                val buf = StringBuilder()\n"
    "                while (i < lines.size && lines[i].isNotBlank() &&",
    "            else -> {\n"
    "                val buf = StringBuilder()\n"
    "                while (i < lines.size && lines[i].isNotBlank() &&",
    "noop-anchor-check", log)

t = rep(t,
    "                val t = buf.toString().trim()\n"
    "                if (t.isNotEmpty()) out.add(MdBlock.Paragraph(t))\n"
    "            }\n"
    "        }\n"
    "    }\n"
    "    return out\n"
    "}",
    "                val t = buf.toString().trim()\n"
    "                if (t.isNotEmpty()) out.add(MdBlock.Paragraph(t))\n"
    "            }\n"
    "        }\n"
    "    }\n"
    "    // 脚注定义统一挂到文末，避免在正文中间插一坨小字打断阅读\n"
    "    if (footnotes.isNotEmpty()) out.add(MdBlock.Footnotes(footnotes))\n"
    "    return out\n"
    "}",
    "parseMarkdown+footnoteTail", log)

# ── 7. inlineToAnnotated：math 着色 ─────────────────────────
t = rep(t,
    "        if (sp.colorHex != null) {",
    "        if (sp.math) style = style.merge(\n"
    "            SpanStyle(\n"
    "                fontFamily = FontFamily.Monospace,\n"
    "                color = cs.primary,\n"
    "                fontStyle = FontStyle.Italic,\n"
    "            )\n"
    "        )\n"
    "        if (sp.colorHex != null) {",
    "inlineToAnnotated+math", log)

# ── 8. RichText：折叠状态 + 分派 ────────────────────────────
t = rep(t,
    "    val blocks = remember(text) { parseMarkdown(text) }\n"
    "    Column(",
    "    val blocks = remember(text) { parseMarkdown(text) }\n"
    "    // #213 大纲折叠：被折叠标题的下标集合。长回答里点标题即可收起它到下一个\n"
    "    // 同级标题之间的全部内容 —— 相当于一份可交互目录，且不需要滚动定位机制\n"
    "    // （气泡在 LazyColumn 里，拿不到稳定的全局锚点，做滚动跳转反而会跳错消息）。\n"
    "    val collapsed = remember { mutableStateOf(emptySet<Int>()) }\n"
    "    Column(",
    "RichText+collapsedState", log)

t = rep(t,
    "        blocks.forEach { block ->\n"
    "            when (block) {",
    "        // 折叠作用域：命中后，层级更深的所有块都不渲染，直到遇到同级或更高级标题\n"
    "        var skipLevel: Int? = null\n"
    "        blocks.forEachIndexed { idx, block ->\n"
    "            if (block is MdBlock.Heading) {\n"
    "                val cur = skipLevel\n"
    "                if (cur != null && block.level > cur) return@forEachIndexed\n"
    "                skipLevel = if (idx in collapsed.value) block.level else null\n"
    "            } else if (skipLevel != null) {\n"
    "                return@forEachIndexed\n"
    "            }\n"
    "            when (block) {",
    "RichText+forEachIndexed", log)

t = rep(t,
    "                is MdBlock.Heading -> MdHeading(block, cs, baseStyle, bodyColor, textAlign)",
    "                is MdBlock.Heading -> MdHeading(\n"
    "                    block, cs, baseStyle, bodyColor, textAlign,\n"
    "                    collapsible = block.level <= 3,\n"
    "                    collapsed = idx in collapsed.value,\n"
    "                    onToggle = {\n"
    "                        collapsed.value =\n"
    "                            if (idx in collapsed.value) collapsed.value - idx else collapsed.value + idx\n"
    "                    },\n"
    "                )",
    "RichText+headingToggle", log)

t = rep(t,
    "                is MdBlock.Table -> MdTable(block, cs, baseStyle)",
    "                is MdBlock.Math -> MdMath(block, cs, baseStyle)\n"
    "                is MdBlock.Footnotes -> MdFootnotes(block, cs, baseStyle, bodyColor)\n"
    "                is MdBlock.Table -> MarkdownTableView(block.header, block.rows)",
    "RichText+math/footnotes/table", log)

# ── 9. MdHeading 支持折叠 ──────────────────────────────────
t = rep(t,
    "private fun MdHeading(\n"
    "    b: MdBlock.Heading,\n"
    "    cs: androidx.compose.material3.ColorScheme,\n"
    "    base: TextStyle,\n"
    "    bodyColor: Color,\n"
    "    textAlign: TextAlign = TextAlign.Start,\n"
    ") {",
    "private fun MdHeading(\n"
    "    b: MdBlock.Heading,\n"
    "    cs: androidx.compose.material3.ColorScheme,\n"
    "    base: TextStyle,\n"
    "    bodyColor: Color,\n"
    "    textAlign: TextAlign = TextAlign.Start,\n"
    "    /** 是否可点击折叠（H1~H3 才有，更深层级折叠收益低还容易误触）。 */\n"
    "    collapsible: Boolean = false,\n"
    "    collapsed: Boolean = false,\n"
    "    onToggle: () -> Unit = {},\n"
    ") {",
    "MdHeading+params", log)

t = rep(t,
    "    Column(\n"
    "        Modifier.fillMaxWidth(),\n"
    "        horizontalAlignment = if (textAlign == TextAlign.End) Alignment.End else Alignment.Start,\n"
    "    ) {\n"
    "        if (b.level <= 2) {",
    "    Column(\n"
    "        Modifier\n"
    "            .fillMaxWidth()\n"
    "            .then(if (collapsible) Modifier.clickable { onToggle() } else Modifier),\n"
    "        horizontalAlignment = if (textAlign == TextAlign.End) Alignment.End else Alignment.Start,\n"
    "    ) {\n"
    "        if (b.level <= 2) {",
    "MdHeading+clickable", log)

t = rep(t,
    "        Text(\n"
    "            text = inlineToAnnotated(parseInlineSpans(b.text), cs, base),\n"
    "            style = base.copy(fontSize = size, fontWeight = FontWeight.Bold, color = bodyColor, lineHeight = size * 1.3f),\n"
    "            textAlign = textAlign,\n"
    "        )\n"
    "    }\n"
    "}\n"
    "\n"
    "@Composable\n"
    "/**\n"
    " * #213：代码块 —— 语法高亮 / 行号 / 复制 / 长代码折叠 / diff 增删着色 / 横向滚动。",
    "        Row(verticalAlignment = Alignment.CenterVertically) {\n"
    "            if (collapsible) {\n"
    "                Text(\n"
    "                    text = if (collapsed) \"\\u25b8\" else \"\\u25be\",\n"
    "                    style = base.copy(fontSize = 12.sp, color = cs.primary),\n"
    "                    modifier = Modifier.padding(end = 5.dp),\n"
    "                )\n"
    "            }\n"
    "            Text(\n"
    "                text = inlineToAnnotated(parseInlineSpans(b.text), cs, base),\n"
    "                style = base.copy(fontSize = size, fontWeight = FontWeight.Bold, color = bodyColor, lineHeight = size * 1.3f),\n"
    "                textAlign = textAlign,\n"
    "                modifier = Modifier.weight(1f),\n"
    "            )\n"
    "        }\n"
    "    }\n"
    "}\n"
    "\n"
    "@Composable\n"
    "/**\n"
    " * #213：代码块 —— 语法高亮 / 行号 / 复制 / 长代码折叠 / diff 增删着色 / 横向滚动。",
    "MdHeading+indicator", log)

# ── 10. MdCode → 公开 CodeSourceView ───────────────────────
i0 = t.find("@Composable\nprivate fun MdCode(")
i1 = t.find("/** 独立成行的图片：等比铺满宽度")
if i0 < 0 or i1 < 0 or i1 < i0:
    log.append("FAIL CodeSourceView: 定位失败 i0=%d i1=%d" % (i0, i1))
else:
    new_code = (
        "/**\n"
        " * #213：代码源码视图 —— 语法高亮 / 行号 / 复制 / 长代码折叠 / diff 增删着色 / 横向滚动。\n"
        " *\n"
        " * 🔴 **必须公开**。原因：`ChatScreen.parseBlocks` 会把消息里的**所有** ``` 围栏\n"
        " *   先抽成 `MsgBlock.Code`，交给 `ChatScreen.CodeBlock` 渲染；`RichText` 私有的\n"
        " *   `MdCode` 在聊天气泡里**一次都走不到**。只改私有实现 = 白改（本轮第一版就踩了这个坑）。\n"
        " *   现在两边共用这一份实现，改一处两处都生效。\n"
        " *\n"
        " * @param showHeader 是否显示「语言标签 + 复制」头。`ChatScreen` 自带工具栏，传 false。\n"
        " */\n"
        "@Composable\n"
        "fun CodeSourceView(\n"
        "    code: String,\n"
        "    lang: String,\n"
        "    modifier: Modifier = Modifier,\n"
        "    showHeader: Boolean = true,\n"
        ") {\n"
        "    val cs = MaterialTheme.colorScheme\n"
        "    val lines = code.split(\"\\n\")\n"
        "    // 超过这个行数先折叠：两百行代码不该把整条消息顶到屏幕外\n"
        "    val collapseAt = 18\n"
        "    val expandedState = remember(code) { mutableStateOf(lines.size <= collapseAt) }\n"
        "    val expanded = expandedState.value\n"
        "    val visibleLines = if (expanded) lines else lines.take(collapseAt)\n"
        "    val visible = visibleLines.joinToString(\"\\n\")\n"
        "    val clipboard = LocalClipboardManager.current\n"
        "    val isDiff = lang.equals(\"diff\", ignoreCase = true) || lang.equals(\"patch\", ignoreCase = true)\n"
        "    val plain = cs.onSurface\n"
        "    val mono = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 19.sp)\n"
        "    val body = if (isDiff) diffAnnotated(visible, plain) else highlightCode(visible, lang, plain)\n"
        "\n"
        "    Column(\n"
        "        modifier\n"
        "            .fillMaxWidth()\n"
        "            .clip(RoundedCornerShape(10.dp))\n"
        "            .background(cs.surfaceVariant.copy(alpha = 0.45f))\n"
        "    ) {\n"
        "        if (showHeader) {\n"
        "            Row(\n"
        "                Modifier.fillMaxWidth().padding(start = 12.dp, end = 8.dp, top = 7.dp),\n"
        "                verticalAlignment = Alignment.CenterVertically,\n"
        "            ) {\n"
        "                if (lang.isNotBlank()) {\n"
        "                    Text(\n"
        "                        text = lang.uppercase(),\n"
        "                        style = TextStyle(fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurfaceVariant),\n"
        "                    )\n"
        "                }\n"
        "                Spacer(Modifier.weight(1f))\n"
        "                Text(\n"
        "                    text = \"\\u590d\\u5236\",\n"
        "                    style = TextStyle(fontSize = 11.sp, color = cs.primary),\n"
        "                    modifier = Modifier\n"
        "                        .clickable { clipboard.setText(AnnotatedString(code)) }\n"
        "                        .padding(horizontal = 6.dp, vertical = 2.dp),\n"
        "                )\n"
        "            }\n"
        "            Spacer(Modifier.height(2.dp))\n"
        "        }\n"
        "        // 横向滚动：长行不再被硬挤成竖排\n"
        "        Row(\n"
        "            Modifier\n"
        "                .fillMaxWidth()\n"
        "                .horizontalScroll(rememberScrollState())\n"
        "                .padding(start = 10.dp, end = 12.dp, bottom = 10.dp)\n"
        "        ) {\n"
        "            Column {\n"
        "                visibleLines.forEachIndexed { idx, _ ->\n"
        "                    Text(\n"
        "                        text = (idx + 1).toString(),\n"
        "                        style = mono.copy(fontSize = 11.sp, color = cs.onSurfaceVariant.copy(alpha = 0.45f)),\n"
        "                        textAlign = TextAlign.End,\n"
        "                        modifier = Modifier.width(26.dp),\n"
        "                    )\n"
        "                }\n"
        "            }\n"
        "            Spacer(Modifier.width(8.dp))\n"
        "            Text(text = body, style = mono)\n"
        "        }\n"
        "        if (lines.size > collapseAt) {\n"
        "            Text(\n"
        "                text = if (expanded) \"\\u6536\\u8d77\" else \"\\u5c55\\u5f00\\u5168\\u90e8 ${lines.size} \\u884c\",\n"
        "                style = TextStyle(fontSize = 11.sp, color = cs.primary),\n"
        "                modifier = Modifier\n"
        "                    .clickable { expandedState.value = !expanded }\n"
        "                    .padding(start = 12.dp, bottom = 8.dp),\n"
        "            )\n"
        "        }\n"
        "    }\n"
        "}\n"
        "\n"
    )
    t = t[:i0] + new_code + t[i1:]
    log.append("ok   CodeSourceView 公开化")

# ── 11. MdTable → 公开 MarkdownTableView ───────────────────
j0 = t.find("@Composable\nprivate fun MdTable(")
j1 = t.find("/**\n * 把 [InlineSpan] 序列按主题着色成 [AnnotatedString]")
if j0 < 0 or j1 < 0 or j1 < j0:
    log.append("FAIL MarkdownTableView: 定位失败 j0=%d j1=%d" % (j0, j1))
else:
    new_table = (
        "/**\n"
        " * #213：表格统一渲染实现（公开，`RichText` 与 `ChatScreen.RenderTable` 共用一份）。\n"
        " *\n"
        " * ## 为什么必须公开\n"
        " * 仓库里原先**两处各写一遍**表格，且都只有「横向滚动的裸文本」：\n"
        " *   - `RichText.MdTable`（GFM 管道表格）\n"
        " *   - `ChatScreen.RenderTable`（HTML `<table>`）\n"
        " * 只改一处，另一处的表格照样是老样子 —— 用户眼里就是「有的表好、有的表烂」。\n"
        " *\n"
        " * ## 本轮补齐\n"
        " * 表头点击排序（数字优先比较）、超 12 行折叠、复制为 CSV、斑马纹、\n"
        " * 列宽按内容自适应、单元格内富文本与链接可点。\n"
        " *\n"
        " * 排序/折叠都是**局部状态**，不写回外部数据，流式重组安全。\n"
        " */\n"
        "@Composable\n"
        "fun MarkdownTableView(\n"
        "    header: List<String>,\n"
        "    rows: List<List<String>>,\n"
        "    modifier: Modifier = Modifier,\n"
        "    base: TextStyle = MaterialTheme.typography.bodyMedium,\n"
        "    onLinkClick: ((String) -> Unit)? = null,\n"
        ") {\n"
        "    val cs = MaterialTheme.colorScheme\n"
        "    val cellColor = base.color\n"
        "    val sortCol = remember { mutableStateOf<Int?>(null) }\n"
        "    val sortAsc = remember { mutableStateOf(true) }\n"
        "    val expanded = remember { mutableStateOf(false) }\n"
        "    val clipboard = LocalClipboardManager.current\n"
        "\n"
        "    val colCount = remember(header, rows) {\n"
        "        maxOf(header.size, rows.maxOfOrNull { it.size } ?: 0)\n"
        "    }\n"
        "    // 列宽按该列最长单元格估算：短表不再撑满屏，长表不再挤成竖条\n"
        "    val colWidths = remember(header, rows) {\n"
        "        (0 until colCount).map { c ->\n"
        "            val longest = maxOf(\n"
        "                header.getOrElse(c) { \"\" }.length,\n"
        "                rows.maxOfOrNull { it.getOrElse(c) { \"\" }.length } ?: 0,\n"
        "            )\n"
        "            (longest * 7 + 20).coerceIn(72, 240).dp\n"
        "        }\n"
        "    }\n"
        "    val ordered = remember(header, rows, sortCol.value, sortAsc.value) {\n"
        "        val col = sortCol.value\n"
        "        if (col == null) rows\n"
        "        else rows.sortedWith(Comparator { a, b ->\n"
        "            compareTableCells(a.getOrElse(col) { \"\" }, b.getOrElse(col) { \"\" })\n"
        "        }).let { if (sortAsc.value) it else it.reversed() }\n"
        "    }\n"
        "    val limit = 12\n"
        "    val visible = if (expanded.value) ordered else ordered.take(limit)\n"
        "    val cellStyle = base.copy(fontSize = 13.sp, color = cellColor)\n"
        "\n"
        "    Column(\n"
        "        modifier\n"
        "            .fillMaxWidth()\n"
        "            .clip(RoundedCornerShape(8.dp))\n"
        "            .border(1.dp, cs.outlineVariant.copy(alpha = 0.6f), RoundedCornerShape(8.dp))\n"
        "            .horizontalScroll(rememberScrollState())\n"
        "    ) {\n"
        "        Row(Modifier.background(cs.surfaceVariant.copy(alpha = 0.55f))) {\n"
        "            header.forEachIndexed { c, cell ->\n"
        "                val sorted = sortCol.value == c\n"
        "                val raw = cell + if (sorted) (if (sortAsc.value) \" \\u25b2\" else \" \\u25bc\") else \"\"\n"
        "                Text(\n"
        "                    text = inlineToAnnotated(parseInlineSpans(raw), cs, base),\n"
        "                    style = cellStyle.copy(fontWeight = FontWeight.SemiBold),\n"
        "                    modifier = Modifier\n"
        "                        .width(colWidths.getOrElse(c) { 72.dp })\n"
        "                        .clickable {\n"
        "                            if (sortCol.value == c) {\n"
        "                                if (sortAsc.value) sortAsc.value = false\n"
        "                                else { sortCol.value = null; sortAsc.value = true }\n"
        "                            } else { sortCol.value = c; sortAsc.value = true }\n"
        "                        }\n"
        "                        .padding(horizontal = 10.dp, vertical = 8.dp),\n"
        "                )\n"
        "            }\n"
        "        }\n"
        "        visible.forEachIndexed { ri, row ->\n"
        "            HorizontalDivider(color = cs.outlineVariant.copy(alpha = 0.35f))\n"
        "            Row(\n"
        "                Modifier.background(\n"
        "                    if (ri % 2 == 1) cs.surfaceVariant.copy(alpha = 0.25f) else Color.Transparent\n"
        "                )\n"
        "            ) {\n"
        "                row.forEachIndexed { c, cell ->\n"
        "                    val rich = inlineToAnnotated(parseInlineSpans(cell), cs, base)\n"
        "                    val cellMod = Modifier\n"
        "                        .width(colWidths.getOrElse(c) { 72.dp })\n"
        "                        .padding(horizontal = 10.dp, vertical = 7.dp)\n"
        "                    if (onLinkClick != null) {\n"
        "                        ClickableText(\n"
        "                            text = rich,\n"
        "                            style = cellStyle,\n"
        "                            onClick = { off ->\n"
        "                                rich.getStringAnnotations(\"link\", off, off)\n"
        "                                    .firstOrNull()?.item?.let { onLinkClick(it) }\n"
        "                            },\n"
        "                            modifier = cellMod,\n"
        "                        )\n"
        "                    } else {\n"
        "                        Text(text = rich, style = cellStyle, modifier = cellMod)\n"
        "                    }\n"
        "                }\n"
        "            }\n"
        "        }\n"
        "        if (header.isNotEmpty()) {\n"
        "            HorizontalDivider(color = cs.outlineVariant.copy(alpha = 0.35f))\n"
        "            Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp)) {\n"
        "                Text(\n"
        "                    text = \"\\u590d\\u5236\\u8868\\u683c\",\n"
        "                    style = base.copy(fontSize = 11.sp, color = cs.primary),\n"
        "                    modifier = Modifier\n"
        "                        .clickable { clipboard.setText(AnnotatedString(tableToCsv(header, ordered))) }\n"
        "                        .padding(end = 4.dp),\n"
        "                )\n"
        "                if (ordered.size > limit) {\n"
        "                    Spacer(Modifier.width(12.dp))\n"
        "                    Text(\n"
        "                        text = if (expanded.value) \"\\u6536\\u8d77\"\n"
        "                        else \"\\u5c55\\u5f00\\u5168\\u90e8 ${ordered.size} \\u884c\",\n"
        "                        style = base.copy(fontSize = 11.sp, color = cs.primary),\n"
        "                        modifier = Modifier.clickable { expanded.value = !expanded.value },\n"
        "                    )\n"
        "                }\n"
        "            }\n"
        "        }\n"
        "    }\n"
        "}\n"
        "\n"
        "/** 表格排序：数字优先（`1,234` / `12.5%` / `-3` 先剥成 Double），否则按字典序。 */\n"
        "private fun compareTableCells(a: String, b: String): Int {\n"
        "    fun num(s: String): Double? =\n"
        "        s.trim().replace(\",\", \"\").removeSuffix(\"%\").toDoubleOrNull()\n"
        "    val na = num(a)\n"
        "    val nb = num(b)\n"
        "    return when {\n"
        "        na != null && nb != null -> na.compareTo(nb)\n"
        "        na != null -> -1\n"
        "        nb != null -> 1\n"
        "        else -> a.trim().compareTo(b.trim())\n"
        "    }\n"
        "}\n"
        "\n"
        "/** 表格转 CSV，方便直接粘进 Excel / 飞书表格。 */\n"
        "internal fun tableToCsv(header: List<String>, rows: List<List<String>>): String {\n"
        "    fun esc(s: String): String = if (s.any { it == ',' || it == '\"' || it == '\\n' }) {\n"
        "        \"\\\"\" + s.replace(\"\\\"\", \"\\\"\\\"\") + \"\\\"\"\n"
        "    } else s\n"
        "    val sb = StringBuilder()\n"
        "    if (header.isNotEmpty()) sb.append(header.joinToString(\",\") { esc(it) }).append(\"\\n\")\n"
        "    rows.forEach { r -> sb.append(r.joinToString(\",\") { esc(it) }).append(\"\\n\") }\n"
        "    return sb.toString().trimEnd('\\n')\n"
        "}\n"
        "\n"
    )
    t = t[:j0] + new_table + t[j1:]
    log.append("ok   MarkdownTableView 公开化")

# ── 12. MdMath / MdFootnotes 新块 ─────────────────────────
t = rep(t,
    "/** 独立成行的图片：等比铺满宽度",
    "/**\n"
    " * 块级数学公式：等宽居中展示 LaTeX 原文。\n"
    " *\n"
    " * 不做真 KaTeX 渲染 —— 气泡里每来一个公式就塞一个 WebView，长回答会直接卡死滚动。\n"
    " * 保证的是「公式不被 Markdown 拆烂」+「视觉上明确是公式」，可读性够用。\n"
    " */\n"
    "@Composable\n"
    "private fun MdMath(\n"
    "    b: MdBlock.Math,\n"
    "    cs: androidx.compose.material3.ColorScheme,\n"
    "    base: TextStyle,\n"
    ") {\n"
    "    Column(\n"
    "        Modifier\n"
    "            .fillMaxWidth()\n"
    "            .clip(RoundedCornerShape(8.dp))\n"
    "            .background(cs.surfaceVariant.copy(alpha = 0.4f))\n"
    "            .padding(horizontal = 12.dp, vertical = 10.dp),\n"
    "        horizontalAlignment = Alignment.CenterHorizontally,\n"
    "    ) {\n"
    "        Text(\n"
    "            text = b.tex,\n"
    "            style = base.copy(\n"
    "                fontFamily = FontFamily.Monospace,\n"
    "                fontSize = 14.sp,\n"
    "                color = cs.primary,\n"
    "                lineHeight = 20.sp,\n"
    "            ),\n"
    "            textAlign = TextAlign.Center,\n"
    "        )\n"
    "    }\n"
    "}\n"
    "\n"
    "/** 文末脚注列表：正文里的 `[^1]` 上标角标指向这里。 */\n"
    "@Composable\n"
    "private fun MdFootnotes(\n"
    "    b: MdBlock.Footnotes,\n"
    "    cs: androidx.compose.material3.ColorScheme,\n"
    "    base: TextStyle,\n"
    "    bodyColor: Color,\n"
    ") {\n"
    "    Column(Modifier.fillMaxWidth().padding(top = 2.dp)) {\n"
    "        HorizontalDivider(color = cs.outlineVariant.copy(alpha = 0.4f))\n"
    "        Spacer(Modifier.height(6.dp))\n"
    "        b.items.forEach { item ->\n"
    "            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {\n"
    "                Text(\n"
    "                    text = item.id,\n"
    "                    style = base.copy(\n"
    "                        fontSize = 11.sp,\n"
    "                        color = cs.primary,\n"
    "                        baselineShift = BaselineShift.Superscript,\n"
    "                    ),\n"
    "                    modifier = Modifier.widthIn(min = 16.dp),\n"
    "                )\n"
    "                Text(\n"
    "                    text = inlineToAnnotated(parseInlineSpans(item.text), cs, base),\n"
    "                    style = base.copy(fontSize = 12.sp, color = bodyColor.copy(alpha = 0.8f), lineHeight = 17.sp),\n"
    "                    modifier = Modifier.weight(1f),\n"
    "                )\n"
    "            }\n"
    "        }\n"
    "    }\n"
    "}\n"
    "\n"
    "/** 独立成行的图片：等比铺满宽度",
    "MdMath/MdFootnotes", log)

# ── 13. import ClickableText ─────────────────────────────
if "import androidx.compose.foundation.text.ClickableText" not in t:
    t = rep(t,
        "import androidx.compose.foundation.horizontalScroll",
        "import androidx.compose.foundation.horizontalScroll\n"
        "import androidx.compose.foundation.text.ClickableText",
        "import ClickableText", log)
else:
    log.append("ok   ClickableText 已存在")

save(RT, t, nl_rt)
log.append("written RichText.kt %d bytes" % os.path.getsize(RT))

# ══════════════════════════════════════════════════════════════
# ChatScreen.kt
# ══════════════════════════════════════════════════════════════
c, nl_cs = load(CS)

c = rep(c,
    "import com.ai.assistance.quro.ui.dialog.RichText",
    "import com.ai.assistance.quro.ui.dialog.RichText\n"
    "import com.ai.assistance.quro.ui.dialog.CodeSourceView\n"
    "import com.ai.assistance.quro.ui.dialog.MarkdownTableView",
    "ChatScreen+import", log)

# RenderTable → 委托统一实现
c = rep(c,
    "private fun RenderTable(\n"
    "    header: List<String>,\n"
    "    rows: List<List<String>>,\n"
    "    scaled: (Int) -> androidx.compose.ui.unit.TextUnit,\n"
    "    textColor: Color,\n"
    "    onOpenLink: (String) -> Unit,\n"
    ") {\n"
    "    val cs = MaterialTheme.colorScheme\n"
    "    val all = if (header.isNotEmpty()) listOf(header) + rows else rows\n"
    "    Column(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {\n"
    "        all.forEachIndexed { ri, row ->\n"
    "            Row(Modifier.fillMaxWidth()) {\n"
    "                row.forEach { cell ->\n"
    "                    val rich = buildRich(\n"
    "                        cell,\n"
    "                        TextStyle(fontSize = scaled(13), color = if (ri == 0) cs.primary else textColor, lineHeight = scaled(18)),\n"
    "                        boldColor = cs.primary, linkColor = cs.primary,\n"
    "                        codeBackground = cs.surfaceVariant.copy(alpha = 0.5f),\n"
    "                    )\n"
    "                    ClickableText(\n"
    "                        text = rich,\n"
    "                        style = TextStyle(fontSize = scaled(13), color = if (ri == 0) cs.primary else textColor, lineHeight = scaled(18)),\n"
    "                        onClick = { offset -> rich.getStringAnnotations(\"link\", offset, offset).firstOrNull()?.item?.let { onOpenLink(it) } },\n"
    "                        modifier = Modifier.widthIn(min = 80.dp, max = 220.dp).padding(6.dp),\n"
    "                    )\n"
    "                }\n"
    "            }\n"
    "            HorizontalDivider(color = Line)\n"
    "        }\n"
    "    }\n"
    "}",
    "private fun RenderTable(\n"
    "    header: List<String>,\n"
    "    rows: List<List<String>>,\n"
    "    scaled: (Int) -> androidx.compose.ui.unit.TextUnit,\n"
    "    textColor: Color,\n"
    "    onOpenLink: (String) -> Unit,\n"
    ") {\n"
    "    // #213：委托给统一的表格实现（排序 / 折叠 / 复制 CSV / 斑马纹 / 列宽自适应）。\n"
    "    // 这里原来自己画了一遍「只能横滚的裸文本」，与 RichText 那份重复且都缺功能。\n"
    "    MarkdownTableView(\n"
    "        header = header,\n"
    "        rows = rows,\n"
    "        base = TextStyle(fontSize = scaled(15), color = textColor, lineHeight = scaled(23)),\n"
    "        onLinkClick = onOpenLink,\n"
    "        modifier = Modifier.fillMaxWidth(),\n"
    "    )\n"
    "}",
    "ChatScreen+RenderTable", log)

# CodeBlock 源码区 → 高亮代码视图
c = rep(c,
    "            } else {\n"
    "                // 默认：源码文本展示（双向滚动 + 裁剪到圆角框内，避免长代码/HTML 撑出卡片）\n"
    "                // v396 修复：长单行代码/HTML 不再横向溢出——改为水平滚动 + 裁剪，\n"
    "                // softWrap=false 让长行保持单行、由 horizontalScroll 承载。\n"
    "                SelectionContainer(\n"
    "                    Modifier\n"
    "                        .fillMaxWidth()\n"
    "                        .clip(RoundedCornerShape(8.dp))\n"
    "                        .heightIn(max = 300.dp)\n"
    "                        .verticalScroll(rememberScrollState())\n"
    "                        .horizontalScroll(rememberScrollState())\n"
    "                        .padding(horizontal = 10.dp)\n"
    "                        .padding(bottom = 8.dp)\n"
    "                ) {\n"
    "                    Text(\n"
    "                        code,\n"
    "                        fontFamily = FontFamily.Monospace,\n"
    "                        fontSize = 13.sp,\n"
    "                        color = cs.onSurface,\n"
    "                        softWrap = false\n"
    "                    )\n"
    "                }\n"
    "            }",
    "            } else {\n"
    "                // #213：改用统一代码视图 —— 语法高亮 / 行号 / 长代码折叠 / diff 着色 / 横向滚动。\n"
    "                //\n"
    "                // 旧实现是「纯文本 + softWrap=false」：没有层次、没有行号，\n"
    "                // 想改其中一行只能整段手抄；两百行的代码还会把消息顶到屏幕外。\n"
    "                // 🔴 外层气泡已在 SelectionContainer 内（见消息体最外层），这里不再嵌套，\n"
    "                //    嵌套 SelectionContainer 会让两侧的选区互相截断。\n"
    "                CodeSourceView(\n"
    "                    code = code,\n"
    "                    lang = lang,\n"
    "                    showHeader = false,\n"
    "                    modifier = Modifier\n"
    "                        .fillMaxWidth()\n"
    "                        .padding(horizontal = 6.dp)\n"
    "                        .padding(bottom = 8.dp),\n"
    "                )\n"
    "            }",
    "ChatScreen+CodeBlock", log)

save(CS, c, nl_cs)
log.append("written ChatScreen.kt %d bytes" % os.path.getsize(CS))

print("\n".join(log))
