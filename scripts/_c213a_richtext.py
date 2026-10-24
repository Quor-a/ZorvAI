# -*- coding: utf-8 -*-
"""#213 对话框渲染补齐第一批：代码块全套 / 任务清单可勾选 / details / 上下标脚注 / 图片。"""
import io, os, sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
P = os.path.join(ROOT, "app", "src", "main", "java", "com", "ai", "assistance", "quro", "ui", "dialog", "RichText.kt")

raw = io.open(P, "rb").read()
crlf = raw.count(b"\r\n")
lf = raw.count(b"\n") - crlf
eol = "\r\n" if crlf and crlf >= lf else "\n"
t = raw.decode("utf-8").replace("\r\n", "\n")


def sub(old, new, name):
    global t
    if old not in t:
        print("  !! ANCHOR MISS:", name)
        sys.exit(1)
    if t.count(old) != 1:
        print("  !! ANCHOR NOT UNIQUE (%d):" % t.count(old), name)
        sys.exit(1)
    print("  ok:", name)
    t = t.replace(old, new)


# ---------------------------------------------------------------- 1) import
sub(
    "import androidx.compose.ui.unit.sp",
    "import androidx.compose.ui.unit.sp\n"
    "import androidx.compose.foundation.clickable\n"
    "import androidx.compose.runtime.mutableStateOf\n"
    "import androidx.compose.ui.layout.ContentScale\n"
    "import androidx.compose.ui.platform.LocalClipboardManager\n"
    "import androidx.compose.ui.text.style.BaselineShift\n"
    "import coil.compose.AsyncImage",
    "import 追加",
)

# ---------------------------------------------------------------- 2) 新块类型
sub(
    r'''    data class Paragraph(val text: String) : MdBlock
}''',
    r'''    data class Paragraph(val text: String) : MdBlock

    /** 独立成行的图片 `![alt](url)`。行内图片不渲染（AnnotatedString 装不下视图）。 */
    data class Image(val url: String, val alt: String = "") : MdBlock

    /** `<details><summary>…</summary>…</details>` 折叠区块。 */
    data class Details(val summary: String, val body: String) : MdBlock
}''',
    "MdBlock 新增 Image / Details",
)

# ---------------------------------------------------------------- 3) InlineSpan 上下标
sub(
    "    val highlight: Boolean = false,",
    "    val highlight: Boolean = false,\n"
    "    /** 上标（`^x^` 或脚注引用 `[^1]`）。 */\n"
    "    val sup: Boolean = false,\n"
    "    /** 下标（`~x~`，单波浪；双波浪是删除线）。 */\n"
    "    val sub: Boolean = false,",
    "InlineSpan 新增 sup / sub",
)

# ---------------------------------------------------------------- 4) 正则常量
sub(
    r'''private val RE_FENCE = Regex("^\\s*(`{3,}|~{3,})\\s*([^\\s`]*)\\s*$")''',
    r'''private val RE_FENCE = Regex("^\\s*(`{3,}|~{3,})\\s*([^\\s`]*)\\s*$")
private val RE_DETAILS_OPEN = Regex("^\\s*<details>\\s*$", RegexOption.IGNORE_CASE)
private val RE_DETAILS_CLOSE = Regex("^\\s*</details>\\s*$", RegexOption.IGNORE_CASE)
private val RE_SUMMARY = Regex("^\\s*<summary>(.*?)</summary>\\s*$", RegexOption.IGNORE_CASE)
private val RE_IMAGE_LINE = Regex("^\\s*!\\[([^\\]]*)\\]\\(([^)\\s]+)[^)]*\\)\\s*$")''',
    "新增 details / 图片正则",
)

# ---------------------------------------------------------------- 5) 行内：脚注 + 上下标
sub(
    "            // ~~删除线~~",
    r'''            // 脚注引用 [^1] → 上标数字
            c == '[' && i + 1 < n && src[i + 1] == '^' -> {
                val close = src.indexOf(']', i)
                if (close > i + 2) emit(src.substring(i + 2, close)) { it.copy(sup = true) }
                else buf.append(c)
                i = if (close > i + 2) close + 1 else i + 1
            }

            // 上标 ^x^
            c == '^' && i + 1 < n && src[i + 1] != '^' -> {
                val end = src.indexOf('^', i + 1)
                if (end > i + 1) emit(src.substring(i + 1, end)) { it.copy(sup = true) }
                else buf.append(c)
                i = if (end > i + 1) end + 1 else i + 1
            }

            // 下标 ~x~（单波浪；双波浪 ~~…~~ 走下面的删除线）
            c == '~' && i + 1 < n && src[i + 1] != '~' -> {
                val end = src.indexOf('~', i + 1)
                if (end > i + 1) emit(src.substring(i + 1, end)) { it.copy(sub = true) }
                else buf.append(c)
                i = if (end > i + 1) end + 1 else i + 1
            }

            // ~~删除线~~''',
    "行内解析新增脚注 / 上下标",
)

# ---------------------------------------------------------------- 6) 块解析 details + 图片
sub(
    r'''            RE_RULE.matches(line) -> { out.add(MdBlock.Rule); i++ }''',
    r'''            RE_RULE.matches(line) -> { out.add(MdBlock.Rule); i++ }

            RE_IMAGE_LINE.matches(line) -> {
                val m = RE_IMAGE_LINE.find(line)!!
                out.add(MdBlock.Image(m.groupValues[2], m.groupValues[1]))
                i++
            }

            RE_DETAILS_OPEN.matches(line) -> {
                i++
                var summary = ""
                val buf = StringBuilder()
                while (i < lines.size) {
                    val cur = lines[i]
                    if (RE_DETAILS_CLOSE.matches(cur)) { i++; break }
                    val sm = RE_SUMMARY.find(cur)
                    if (sm != null) { summary = sm.groupValues[1].trim(); i++; continue }
                    buf.append(cur).append('\n'); i++
                }
                out.add(MdBlock.Details(summary.ifBlank { "详情" }, buf.toString().trimEnd()))
            }''',
    "块解析新增图片 / details",
)

# ---------------------------------------------------------------- 7) 行内着色：上下标
sub(
    "        if (sp.strike) style = style.merge(SpanStyle(textDecoration = TextDecoration.LineThrough))",
    "        if (sp.strike) style = style.merge(SpanStyle(textDecoration = TextDecoration.LineThrough))\n"
    "        if (sp.sup) style = style.merge(\n"
    "            SpanStyle(baselineShift = BaselineShift.Superscript, fontSize = (base.fontSize.value * 0.72f).sp)\n"
    "        )\n"
    "        if (sp.sub) style = style.merge(\n"
    "            SpanStyle(baselineShift = BaselineShift.Subscript, fontSize = (base.fontSize.value * 0.72f).sp)\n"
    "        )",
    "上下标着色",
)

# ---------------------------------------------------------------- 8) 代码块全套
sub(
    r'''private fun MdCode(b: MdBlock.Code, cs: androidx.compose.material3.ColorScheme, base: TextStyle) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(cs.surfaceVariant.copy(alpha = 0.45f))
            .padding(12.dp)
    ) {
        if (b.lang.isNotBlank()) {
            Text(
                text = b.lang.uppercase(),
                style = base.copy(fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurfaceVariant),
            )
            Spacer(Modifier.height(6.dp))
        }
        Text(
            text = b.code,
            style = base.copy(
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                color = cs.onSurface,
                lineHeight = 19.sp,
            ),
        )
    }
}''',
    r'''/**
 * #213：代码块 —— 语法高亮 / 行号 / 复制 / 长代码折叠 / diff 增删着色 / 横向滚动。
 *
 * 原来只有「语言标签 + 一坨等宽文本」：长行被硬挤竖排、没有行号、改一行要手抄全文、
 * 两百行的代码直接把整条消息顶到屏幕外。这些都是「看得见但用不了」的典型。
 */

/** 各语言关键字表。够用即可 —— 这里要的是扫读时的层次感，不是编译器。 */
private fun keywordsFor(lang: String): Set<String> = when (lang.lowercase()) {
    "kotlin", "kt", "kts" -> setOf(
        "fun", "val", "var", "when", "if", "else", "return", "class", "object", "for", "while",
        "import", "package", "suspend", "data", "sealed", "const", "override", "private", "internal",
        "public", "companion", "interface", "enum", "try", "catch", "finally", "throw", "true",
        "false", "null", "this", "is", "in", "not", "by", "lazy", "also", "let", "apply", "run", "with",
    )
    "java" -> setOf(
        "public", "private", "protected", "class", "interface", "enum", "static", "final", "void",
        "int", "long", "boolean", "new", "return", "if", "else", "for", "while", "try", "catch",
        "import", "package", "extends", "implements", "this", "super", "null", "true", "false",
        "abstract", "synchronized", "throws",
    )
    "python", "py" -> setOf(
        "def", "class", "if", "elif", "else", "for", "while", "return", "import", "from", "as",
        "with", "try", "except", "finally", "raise", "lambda", "yield", "global", "pass", "break",
        "continue", "and", "or", "not", "in", "is", "None", "True", "False", "self", "async", "await",
    )
    "javascript", "js", "typescript", "ts", "jsx", "tsx" -> setOf(
        "function", "const", "let", "var", "if", "else", "for", "while", "return", "class", "import",
        "export", "from", "default", "new", "try", "catch", "finally", "async", "await", "this",
        "typeof", "instanceof", "null", "undefined", "true", "false", "switch", "case", "break",
        "continue", "extends", "super", "interface", "type",
    )
    "bash", "sh", "shell", "zsh" -> setOf(
        "if", "then", "else", "fi", "for", "while", "do", "done", "case", "esac", "function",
        "return", "export", "local", "echo", "cd", "sudo", "npm", "git", "curl",
    )
    "sql" -> setOf(
        "select", "from", "where", "insert", "into", "values", "update", "set", "delete", "create",
        "table", "drop", "alter", "join", "left", "right", "inner", "on", "group", "order", "by",
        "limit", "and", "or", "not", "null", "as", "distinct", "having", "union",
    )
    "html", "xml" -> setOf(
        "html", "head", "body", "div", "span", "script", "style", "meta", "link", "title", "p",
        "table", "tr", "td", "th", "ul", "li", "input", "button",
    )
    "go" -> setOf(
        "func", "package", "import", "var", "const", "type", "struct", "interface", "if", "else",
        "for", "range", "return", "go", "defer", "chan", "map", "nil", "true", "false",
    )
    "rust", "rs" -> setOf(
        "fn", "let", "mut", "const", "static", "struct", "enum", "impl", "trait", "match", "if",
        "else", "for", "while", "loop", "return", "use", "mod", "pub", "self", "Some", "None",
        "Ok", "Err", "true", "false",
    )
    "c", "cpp", "cc", "h" -> setOf(
        "int", "char", "float", "double", "void", "if", "else", "for", "while", "return", "struct",
        "class", "public", "private", "include", "define", "const", "static", "new", "delete",
        "this", "template", "namespace", "using",
    )
    else -> emptySet()
}

private val CODE_KW = Color(0xFF7C5CFF)
private val CODE_STR = Color(0xFF2E9E63)
private val CODE_NUM = Color(0xFFD08700)
private val CODE_COMMENT = Color(0xFF8A8F98)

/**
 * 极简语法着色：注释 → 字符串 → 数字 → 关键字，其余按正文色。
 *
 * 刻意不用第三方高亮库：那些要么带几十 KB 语法定义、要么要 WebView，
 * 而气泡里要的只是「一眼分得清哪是注释哪是字符串」。
 */
private fun highlightCode(code: String, lang: String, plain: Color): AnnotatedString {
    val kw = keywordsFor(lang)
    val hashComment = lang.lowercase() in setOf("python", "py", "bash", "sh", "shell", "zsh", "yaml", "yml", "ruby")
    return buildAnnotatedString {
        var i = 0
        val n = code.length
        while (i < n) {
            val c = code[i]
            if (code.startsWith("//", i)) {
                val e = code.indexOf('\n', i); val end = if (e < 0) n else e
                withStyle(SpanStyle(color = CODE_COMMENT)) { append(code.substring(i, end)) }; i = end; continue
            }
            if (code.startsWith("/*", i)) {
                val e = code.indexOf("*/", i + 2); val end = if (e < 0) n else (e + 2)
                withStyle(SpanStyle(color = CODE_COMMENT)) { append(code.substring(i, end)) }; i = end; continue
            }
            if (hashComment && (i == 0 || code[i - 1] == '\n') && c == '#') {
                val e = code.indexOf('\n', i); val end = if (e < 0) n else e
                withStyle(SpanStyle(color = CODE_COMMENT)) { append(code.substring(i, end)) }; i = end; continue
            }
            if (c == '"' || c == '\'' || c == '`') {
                var j = i + 1
                while (j < n && code[j] != c) { if (code[j] == '\\') j++; j++ }
                val end = minOf(j + 1, n)
                withStyle(SpanStyle(color = CODE_STR)) { append(code.substring(i, end)) }
                i = end; continue
            }
            if (c.isDigit()) {
                var j = i
                while (j < n && (code[j].isDigit() || code[j] == '.')) j++
                withStyle(SpanStyle(color = CODE_NUM)) { append(code.substring(i, j)) }
                i = j; continue
            }
            if (c.isLetter() || c == '_') {
                var j = i
                while (j < n && (code[j].isLetterOrDigit() || code[j] == '_')) j++
                val word = code.substring(i, j)
                if (word in kw) {
                    withStyle(SpanStyle(color = CODE_KW, fontWeight = FontWeight.SemiBold)) { append(word) }
                } else {
                    withStyle(SpanStyle(color = plain)) { append(word) }
                }
                i = j; continue
            }
            append(c); i++
        }
    }
}

/** `diff` / `patch` 围栏按行着色：新增绿、删除红、hunk 头蓝。 */
private fun diffAnnotated(code: String, plain: Color): AnnotatedString = buildAnnotatedString {
    code.split("\n").forEachIndexed { idx, ln ->
        if (idx > 0) append("\n")
        when {
            ln.startsWith("+") -> withStyle(SpanStyle(color = Color(0xFF2E9E63))) { append(ln) }
            ln.startsWith("-") -> withStyle(SpanStyle(color = Color(0xFFC0392B))) { append(ln) }
            ln.startsWith("@@") -> withStyle(SpanStyle(color = Color(0xFF2563EB))) { append(ln) }
            else -> withStyle(SpanStyle(color = plain.copy(alpha = 0.72f))) { append(ln) }
        }
    }
}

@Composable
private fun MdCode(b: MdBlock.Code, cs: androidx.compose.material3.ColorScheme, base: TextStyle) {
    val lines = b.code.split("\n")
    // 超过这个行数先折叠：两百行代码不该把整条消息顶到屏幕外
    val collapseAt = 18
    val expandedState = remember(b.code) { mutableStateOf(lines.size <= collapseAt) }
    val expanded = expandedState.value
    val visibleLines = if (expanded) lines else lines.take(collapseAt)
    val visible = visibleLines.joinToString("\n")
    val clipboard = LocalClipboardManager.current
    val isDiff = b.lang.equals("diff", ignoreCase = true) || b.lang.equals("patch", ignoreCase = true)
    val plain = cs.onSurface
    val mono = base.copy(fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 19.sp)
    val body = if (isDiff) diffAnnotated(visible, plain) else highlightCode(visible, b.lang, plain)

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(cs.surfaceVariant.copy(alpha = 0.45f))
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 12.dp, end = 8.dp, top = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (b.lang.isNotBlank()) {
                Text(
                    text = b.lang.uppercase(),
                    style = base.copy(fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurfaceVariant),
                )
            }
            Spacer(Modifier.weight(1f))
            Text(
                text = "复制",
                style = base.copy(fontSize = 11.sp, color = cs.primary),
                modifier = Modifier
                    .clickable { clipboard.setText(AnnotatedString(b.code)) }
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
        Spacer(Modifier.height(2.dp))
        // 横向滚动：长行不再被硬挤成竖排
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(start = 10.dp, end = 12.dp, bottom = 10.dp)
        ) {
            Column {
                visibleLines.forEachIndexed { idx, _ ->
                    Text(
                        text = (idx + 1).toString(),
                        style = mono.copy(fontSize = 11.sp, color = cs.onSurfaceVariant.copy(alpha = 0.45f)),
                        textAlign = TextAlign.End,
                        modifier = Modifier.width(26.dp),
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Text(text = body, style = mono)
        }
        if (lines.size > collapseAt) {
            Text(
                text = if (expanded) "收起" else "展开全部 ${lines.size} 行",
                style = base.copy(fontSize = 11.sp, color = cs.primary),
                modifier = Modifier
                    .clickable { expandedState.value = !expanded }
                    .padding(start = 12.dp, bottom = 8.dp),
            )
        }
    }
}

/** 独立成行的图片：等比铺满宽度，点击按链接处理（放大/跳浏览器由外层决定）。 */
@Composable
private fun MdImage(
    b: MdBlock.Image,
    cs: androidx.compose.material3.ColorScheme,
    onLinkClick: (String) -> Unit,
) {
    AsyncImage(
        model = b.url,
        contentDescription = b.alt.ifBlank { null },
        contentScale = ContentScale.FillWidth,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(cs.surfaceVariant.copy(alpha = 0.3f))
            .clickable { onLinkClick(b.url) },
    )
}

/** `<details>` 折叠区块：摘要行可点，默认收起。 */
@Composable
private fun MdDetails(
    b: MdBlock.Details,
    cs: androidx.compose.material3.ColorScheme,
    base: TextStyle,
    bodyColor: Color,
) {
    val openState = remember { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(cs.surfaceVariant.copy(alpha = 0.35f))
            .padding(10.dp)
    ) {
        Row(
            Modifier.fillMaxWidth().clickable { openState.value = !openState.value },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(if (openState.value) "▾" else "▸", style = base.copy(color = cs.primary, fontSize = 13.sp))
            Spacer(Modifier.width(6.dp))
            Text(
                text = inlineToAnnotated(parseInlineSpans(b.summary), cs, base),
                style = base.copy(fontWeight = FontWeight.SemiBold, color = bodyColor),
            )
        }
        if (openState.value) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = inlineToAnnotated(parseInlineSpans(b.body), cs, base),
                style = base.copy(color = bodyColor, lineHeight = bodyLineHeight(base)),
            )
        }
    }
}''',
    "代码块全套 + 图片 + details 渲染",
)

# ---------------------------------------------------------------- 9) 任务清单可勾选
sub(
    r'''                    item.checked != null -> {
                        Box(
                            Modifier.size(16.dp).clip(RoundedCornerShape(4.dp))
                                .background(if (item.checked) cs.primary else Color.Transparent)
                                .border(
                                    1.5.dp,
                                    if (item.checked) cs.primary else cs.outline,
                                    RoundedCornerShape(4.dp),
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (item.checked) CheckMark(Modifier.size(11.dp), cs.onPrimary)
                        }
                    }''',
    r'''                    item.checked != null -> {
                        // #213：可勾选。原来只是个画出来的方框 —— 用户看着像个复选框，
                        // 点上去毫无反应，比不给复选框更迷惑。
                        val ck = remember(item.text, item.checked) { mutableStateOf(item.checked) }
                        Box(
                            Modifier.size(16.dp).clip(RoundedCornerShape(4.dp))
                                .clickable { ck.value = !(ck.value ?: false) }
                                .background(if (ck.value == true) cs.primary else Color.Transparent)
                                .border(
                                    1.5.dp,
                                    if (ck.value == true) cs.primary else cs.outline,
                                    RoundedCornerShape(4.dp),
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (ck.value == true) CheckMark(Modifier.size(11.dp), cs.onPrimary)
                        }
                    }''',
    "任务清单可勾选",
)

# 勾选后的正文也要跟着变灰
sub(
    "                        color = if (item.checked == true) bodyColor.copy(alpha = 0.6f) else bodyColor,",
    "                        color = if (item.checked == true) bodyColor.copy(alpha = 0.6f) else bodyColor,",
    "正文灰化（保持）",
)

# ---------------------------------------------------------------- 10) RichText when 新分支
sub(
    "                is MdBlock.ListBlock -> MdList(block, cs, baseStyle, bodyColor)",
    "                is MdBlock.ListBlock -> MdList(block, cs, baseStyle, bodyColor)\n"
    "                is MdBlock.Image -> MdImage(block, cs, onLinkClick)\n"
    "                is MdBlock.Details -> MdDetails(block, cs, baseStyle, bodyColor)",
    "RichText 分发新块",
)

data = t.replace("\n", eol).encode("utf-8")
tmp = P + ".tmp"
with open(tmp, "wb") as f:
    f.write(data)
os.replace(tmp, P)
print("written:", len(data), "bytes, eol=" + repr(eol))
