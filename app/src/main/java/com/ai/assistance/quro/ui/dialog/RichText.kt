package com.ai.assistance.quro.ui.dialog

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ════════════════════════════════════════════════════════════════════
//  第一部分：纯解析层（零 Compose 依赖，可直接单元测试）
// ════════════════════════════════════════════════════════════════════

/**
 * Markdown 块级节点。
 *
 * 抽成公开类型是为了让 [parseMarkdown] 能被纯 JVM 单测覆盖 —— 渲染层再花哨，
 * 只要解析结果不对就全白搭，而 Compose 渲染无法在无 Robolectric 环境下断言。
 */
sealed interface MdBlock {
    /** `#`~`######` 标题。[level] 1~6。 */
    data class Heading(val level: Int, val text: String) : MdBlock

    /** 围栏代码块。[lang] 可能为空。 */
    data class Code(val lang: String, val code: String) : MdBlock

    /** 引用块（连续的 `>` 行已合并）。 */
    data class Quote(val lines: List<String>) : MdBlock

    /**
     * GitHub Alerts：`> [!NOTE]` / `[!TIP]` / `[!WARNING]` / `[!CAUTION]` / `[!IMPORTANT]`。
     *
     * 这是 ChatGPT / Claude / GitHub 都在用的提示块约定，模型很爱输出它，之前仓库里
     * 只有 GenUI 的 [com.ai.assistance.quro.genui.sdk.components.MarkdownPlus] 认得，
     * 而那条路径在聊天气泡里根本不可达 —— 等于白写。这里补齐。
     *
     * @param kind 大写关键字（NOTE/TIP/…）。
     * @param body 去掉 `[!KIND]` 后的正文。
     */
    data class Alert(val kind: String, val body: String) : MdBlock

    /** 有序 / 无序列表。[ordered] 为 true 时按 [start] 起始编号。 */
    data class ListBlock(val ordered: Boolean, val start: Int, val items: List<MdListItem>) : MdBlock

    /** GFM 管道表格。 */
    data class Table(val header: List<String>, val rows: List<List<String>>) : MdBlock

    /** 水平分隔线。 */
    data object Rule : MdBlock

    /**
     * 段落。
     *
     * 🔴 [text] 内部保留 `\n` 作为**软换行** —— 这正是 Markdown 语义：
     * 空行才是段落分隔，单个换行只是同一段里换一行。旧渲染器把每一行都当成独立
     * `Text` 且只给 2dp 上下间距，导致整段文字糊成一坨（用户反馈"排版被限制死"）。
     */
    data class Paragraph(val text: String) : MdBlock
}

/**
 * 列表项。
 *
 * @param checked `null` = 普通列表项；`true`/`false` = 任务列表项（`- [x]` / `- [ ]`）。
 * @param indent  缩进层级（每 2 个空格一级），用于渲染层级缩进。
 */
data class MdListItem(
    val text: String,
    val checked: Boolean? = null,
    val indent: Int = 0,
)

/**
 * 行内片段（已解析但**未着色**）。
 *
 * 颜色与字号由渲染层按主题注入，因此解析结果不依赖 [androidx.compose.material3.ColorScheme]，
 * 可以在纯 JVM 里断言"这段是不是粗体""这个链接地址是什么"。
 */
data class InlineSpan(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val strike: Boolean = false,
    val code: Boolean = false,
    val highlight: Boolean = false,
    /** 非空表示这是一个链接，值为目标 URL。 */
    val link: String? = null,
    /** 非空表示命中了 `<c=#RRGGBB>` 着色约定。 */
    val colorHex: String? = null,
)

private val RE_HEADING = Regex("^(#{1,6})\\s+(.*)$")
private val RE_RULE = Regex("^\\s*(?:\\*{3,}|-{3,}|_{3,})\\s*$")
private val RE_QUOTE = Regex("^\\s*>\\s?(.*)$")

/** GitHub Alerts 标记：`[!NOTE]` / `[!tip]` 等，挂在引用块首行。 */
private val RE_ALERT = Regex("^\\s*\\[!(NOTE|TIP|WARNING|CAUTION|IMPORTANT)]\\s*(.*)$", RegexOption.IGNORE_CASE)
private val RE_UL_ITEM = Regex("^(\\s*)[-*+]\\s+(.*)$")
private val RE_OL_ITEM = Regex("^(\\s*)(\\d+)[.)]\\s+(.*)$")
private val RE_TASK = Regex("^\\[([ xX])\\]\\s*(.*)$")
private val RE_FENCE = Regex("^\\s*(`{3,}|~{3,})\\s*([^\\s`]*)\\s*$")

/**
 * 🔴 围栏闭合行判定：**整行只有同种标记符号**，且长度 >= 开启围栏（CommonMark §4.5）。
 *
 * 与 [RE_FENCE] 的区别：`RE_FENCE` 允许带 info string（\`\`\`python），
 * 而**闭合围栏绝不能有 info string**。
 *
 * ## 旧实现的两处坑（用户实测「围栏乱围」）
 *
 * 1. `cur.trim().all { it == marker[0] }` 只判"字符全同"，**没判marker 长度下界**，
 *    也没保证不是别的字符（`~` 围栏遇到 ``` 会被误认）。
 * 2. 判定失败时**没有"未闭合"出口** —— 模型忘写结尾 ``` / 流式被 max_tokens 截断时，
 *    循环一路吃到文末，把后面整篇正文全塞进 Code 块，
 *    于是标题/列表/表格全变黑底（用户原话「什么都围，乱围」）。
 */
private fun isClosingFence(line: String, marker: String): Boolean {
    val c = marker.firstOrNull() ?: return false
    if (c != '`' && c != '~') return false
    val t = line.trim()
    if (t.length < marker.length) return false
    return t.all { it == c }
}

/** 表格分隔行，如 `|---|:--:|` 或 `--- | ---`。 */
private fun isTableSeparator(line: String): Boolean {
    val t = line.trim()
    if (!t.contains('-')) return false
    return t.all { it == '-' || it == '|' || it == ':' || it == ' ' }
}

private fun isTableRow(line: String): Boolean {
    val t = line.trim()
    return t.startsWith("|") && t.endsWith("|") && t.length > 1
}

private fun splitTableRow(line: String): List<String> =
    line.trim().removePrefix("|").removeSuffix("|").split('|').map { it.trim() }

/**
 * 块级 Markdown 解析（纯函数）。
 *
 * 优先级：围栏代码 → 表格 → 标题 → 分隔线 → 引用 → 列表 → 段落。
 * 段落聚合规则遵循 CommonMark：**空行断段，行内换行不断段**。
 */
fun parseMarkdown(src: String): List<MdBlock> {
    val lines = src.replace("\r\n", "\n").replace('\r', '\n').split("\n")
    val out = mutableListOf<MdBlock>()
    var i = 0
    while (i < lines.size) {
        val line = lines[i]

        // ── 围栏代码块（优先级最高，内部一切 Markdown 都不解析）──
        val fence = RE_FENCE.find(line)
        if (fence != null) {
            val marker = fence.groupValues[1]
            val lang = fence.groupValues[2]
            val buf = StringBuilder()
            var closed = false
            // 🔴🔴 嵌套深度（用户实测「什么都围，乱围」的根因修复）
            //
            // 场景：模型输出「markdown 教学文档」——外层 ```markdown 里套 ```python。
            // CommonMark 靠**标记长度**区分嵌套（外层得用 4 个反引号），但模型几乎不会这么写，
            // 于是内层那个裸 ``` 会把**外层**提前关掉 → 后面整篇文档漏成裸文本
            // （实测 `python def markdown_test...` / `json {...}` / `bash` 全变成正文），
            // 并留下一堆空代码卡片。
            //
            // 规则（顺序敏感，**先判闭合再判嵌套**）：
            //   1. 纯标记行且长度 >= 开启长度 → 闭合，深度-1，减到 0 才真正结束；
            //   2. 同种字符 + **带非空 info string** → 嵌套开启，深度+1，**该行原样写入内容**；
            //   3. 其余 → 普通内容。
            //
            // 🔴 顺序踩过的坑：裸 ``` 同时能被 RE_FENCE 匹配（info 为空），
            //    若先判"嵌套"就会把闭合行误当开启，深度只增不减 → 围栏永不闭合 → 吞掉全文。
            //🔴 第2 条必须把该行写回 buf，否则内层 ```python 会在渲染时丢失。
            var depth = 1
            i++
            while (i < lines.size) {
                val cur = lines[i]
                val inner = RE_FENCE.find(cur)
                if (isClosingFence(cur, marker)) {
                    depth--
                    if (depth == 0) { closed = true; i++; break }
                    // 深度仍 > 0：这是**内层**的闭合行，必须原样保留在内容里
                    buf.append(cur).append('\n')
                } else if (inner != null && inner.groupValues[1][0] == marker[0] &&
                    inner.groupValues[2].isNotBlank()
                ) {
                    depth++
                    buf.append(cur).append('\n')
                } else {
                    buf.append(cur).append('\n')
                }
                i++
            }
            val body = buf.toString().trimEnd('\n')
            // 🔴 空围栏（流式刚起手、或文档里多余的 ```）**不产出空代码块** ——
            //    空黑卡片既无信息又会打断阅读节奏（用户截图里就有两个）。
            if (body.isNotBlank()) {
                out.add(MdBlock.Code(lang, body))
            }
            continue
        }

        // ── 表格：当前行是管道行，且下一行是分隔行 ──
        if (isTableRow(line) && i + 1 < lines.size && isTableSeparator(lines[i + 1])) {
            val header = splitTableRow(line)
            i += 2
            val rows = mutableListOf<List<String>>()
            while (i < lines.size && isTableRow(lines[i])) {
                rows.add(splitTableRow(lines[i])); i++
            }
            out.add(MdBlock.Table(header, rows))
            continue
        }

        when {
            RE_HEADING.matches(line) -> {
                val m = RE_HEADING.find(line)!!
                out.add(MdBlock.Heading(m.groupValues[1].length, m.groupValues[2].trim()))
                i++
            }

            RE_RULE.matches(line) -> { out.add(MdBlock.Rule); i++ }

            RE_QUOTE.matches(line) -> {
                val buf = mutableListOf<String>()
                while (i < lines.size && RE_QUOTE.matches(lines[i])) {
                    buf.add(RE_QUOTE.find(lines[i])!!.groupValues[1]); i++
                }
                // 🔴 第一行是 `[!KIND]` 时按 Alerts 渲染，而不是退化成普通引用。
                //    首行标记之后的所有引用行都要并进 body，否则多行提示只剩一个空标签。
                val first = buf.firstOrNull() ?: ""
                val am = RE_ALERT.find(first)
                if (am != null && am.groupValues[1].isNotBlank()) {
                    val rest = buf.drop(1).filter { it.isNotBlank() }
                    val body = (listOf(am.groupValues[2].trim()) + rest)
                        .filter { it.isNotEmpty() }
                        .joinToString("\n")
                    out.add(MdBlock.Alert(am.groupValues[1].uppercase(), body))
                } else {
                    out.add(MdBlock.Quote(buf))
                }
            }

            RE_UL_ITEM.matches(line) || RE_OL_ITEM.matches(line) -> {
                var ordered = RE_OL_ITEM.matches(line)
                var start = if (ordered) RE_OL_ITEM.find(line)!!.groupValues[2].toIntOrNull() ?: 1 else 1
                val items = mutableListOf<MdListItem>()
                while (i < lines.size) {
                    val cur = lines[i]
                    val um = RE_UL_ITEM.find(cur)
                    val om = RE_OL_ITEM.find(cur)
                    when {
                        um != null -> {
                            val (body, ch) = splitTask(um.groupValues[2])
                            items.add(MdListItem(body, ch, indentOf(um.groupValues[1])))
                            i++
                        }
                        om != null -> {
                            val (body, ch) = splitTask(om.groupValues[3])
                            if (items.isEmpty()) start = om.groupValues[2].toIntOrNull() ?: 1
                            items.add(MdListItem(body, ch, indentOf(om.groupValues[1])))
                            i++
                        }
                        // 列表项的续行（缩进但不是新列表项）→ 并入上一项
                        items.isNotEmpty() && cur.isNotBlank() &&
                            !RE_HEADING.matches(cur) && !RE_RULE.matches(cur) -> {
                            val last = items.removeAt(items.size - 1)
                            items.add(last.copy(text = last.text + "\n" + cur.trim()))
                            i++
                        }
                        else -> break
                    }
                }
                out.add(MdBlock.ListBlock(ordered, start, items))
            }

            // ── 空行：断段落，不产出块（段落间距由渲染层的 Column spacedBy 统一给）──
            line.isBlank() -> i++

            else -> {
                val buf = StringBuilder()
                while (i < lines.size && lines[i].isNotBlank() &&
                    !RE_HEADING.matches(lines[i]) && !RE_RULE.matches(lines[i]) &&
                    !RE_QUOTE.matches(lines[i]) && !RE_UL_ITEM.matches(lines[i]) &&
                    !RE_OL_ITEM.matches(lines[i]) && RE_FENCE.find(lines[i]) == null &&
                    !(isTableRow(lines[i]) && i + 1 < lines.size && isTableSeparator(lines[i + 1]))
                ) {
                    if (buf.isNotEmpty()) buf.append('\n')
                    buf.append(lines[i]); i++
                }
                val t = buf.toString().trim()
                if (t.isNotEmpty()) out.add(MdBlock.Paragraph(t))
            }
        }
    }
    return out
}

private fun indentOf(spaceStr: String): Int = spaceStr.length / 2

private fun splitTask(body: String): Pair<String, Boolean?> {
    val m = RE_TASK.find(body) ?: return body to null
    return m.groupValues[2] to (m.groupValues[1].lowercase() == "x")
}

/**
 * 行内 Markdown 解析（纯函数，单遍扫描）。
 *
 * 优先级：转义 `\>` → 行内代码 `` ` `` → 链接 `[t](u)` → 粗体 `**`/`__`
 * → 删除线 `~~` → 高亮 `==` → 斜体 `*`/`_` → 着色 `<c=#hex>`。
 *
 * 🔴 **未闭合标记一律原样输出**（而不是吐出一个裸星号）：AI 流式输出时经常会先吐出
 * `**第 1 批` 这种半截标记，旧实现在闭合符缺失时走 `append(raw[i])` 把 `*` 打进正文，
 * 用户屏幕上就会看到裸星号一闪而过。
 */
fun parseInlineSpans(src: String): List<InlineSpan> {
    val out = mutableListOf<InlineSpan>()
    val buf = StringBuilder()
    var i = 0
    val n = src.length

    fun flush() {
        if (buf.isNotEmpty()) { out.add(InlineSpan(buf.toString())); buf.setLength(0) }
    }
    fun emit(s: String, f: (InlineSpan) -> InlineSpan) {
        flush()
        out.add(f(InlineSpan(s)))
    }

    while (i < n) {
        val c = src[i]
        when {
            // 反斜杠转义：下一个字符按字面输出
            c == '\\' && i + 1 < n -> { buf.append(src[i + 1]); i += 2 }

            // 行内代码：优先级最高，内部不再解析任何标记
            c == '`' -> {
                val end = src.indexOf('`', i + 1)
                if (end > i) emit(src.substring(i + 1, end)) { it.copy(code = true) }
                else buf.append(c)
                i = if (end > i) end + 1 else i + 1
            }

            // 链接 [text](url)
            c == '[' -> {
                val close = src.indexOf(']', i)
                val paren = if (close > i) src.indexOf('(', close) else -1
                val urlEnd = if (paren == close + 1) src.indexOf(')', paren) else -1
                if (close > i && paren == close + 1 && urlEnd > paren) {
                    emit(src.substring(i + 1, close)) {
                        it.copy(link = src.substring(paren + 1, urlEnd))
                    }
                    i = urlEnd + 1
                } else buf.append(c).also { i++ }
            }

            // 着色 <c=#RRGGBB>...</c>
            src.startsWith("<c=", i) -> {
                val hexEnd = src.indexOf('>', i)
                val closeTag = if (hexEnd > i) src.indexOf("</c>", hexEnd) else -1
                if (closeTag > hexEnd) {
                    emit(src.substring(hexEnd + 1, closeTag)) {
                        it.copy(colorHex = src.substring(i + 3, hexEnd).trim())
                    }
                    i = closeTag + 4
                } else buf.append(c).also { i++ }
            }

            // ***粗斜体*** / **粗体** / __粗体__
            (c == '*' || c == '_') && i + 1 < n && src[i + 1] == c -> {
                val marker = "$c$c"
                val end = src.indexOf(marker, i + 2)
                if (end > i + 1) {
                    val inner = src.substring(i + 2, end)
                    val triple = inner.startsWith(c.toString())
                    emit(inner) { sp ->
                        if (triple) sp.copy(bold = true, italic = true, text = inner.substring(1))
                        else sp.copy(bold = true)
                    }
                    i = end + 2
                } else buf.append(c).also { i++ }
            }

            // ~~删除线~~
            c == '~' && i + 1 < n && src[i + 1] == '~' -> {
                val end = src.indexOf("~~", i + 2)
                if (end > i + 1) emit(src.substring(i + 2, end)) { it.copy(strike = true) }
                else buf.append(c).also { i++ }
                if (end > i + 1) i = end + 2
            }

            // ==高亮==
            c == '=' && i + 1 < n && src[i + 1] == '=' -> {
                val end = src.indexOf("==", i + 2)
                if (end > i + 1) emit(src.substring(i + 2, end)) { it.copy(highlight = true) }
                else buf.append(c).also { i++ }
                if (end > i + 1) i = end + 2
            }

            // *斜体* / _斜体_
            (c == '*' || c == '_') -> {
                val end = src.indexOf(c, i + 1)
                // 🔴 下划线斜体必须**两侧都不挨着标识符字符**，否则 `snake_case_name` 会被
                // 拆成 my + 斜体(var) + name（用户可见的实际 bug）。右界已在上面判过，
                // 这里补左界：`_` 前面紧邻字母/数字时说明这是标识符的一部分，不是标记。
                val leftOk = c == '*' || (i == 0 || !(src[i - 1].isLetterOrDigit()))
                val rightOk = c == '*' || (i + 1 < n && !src[i + 1].isWhitespace())
                if (end > i + 1 && leftOk && rightOk) emit(src.substring(i + 1, end)) { it.copy(italic = true) }
                else buf.append(c).also { i++ }
                if (end > i + 1 && leftOk && rightOk) i = end + 1
            }

            else -> { buf.append(c); i++ }
        }
    }
    flush()
    return out
}

// ════════════════════════════════════════════════════════════════════
//  第二部分：渲染层
// ════════════════════════════════════════════════════════════════════

/**
 * 段落 / 块之间的统一间距。
 *
 * 🔴 原为 10.dp，与气泡层 `Column(spacedBy(8.dp))` 叠加后实际段距达18dp ——
 * 而段内行距只有 22.5sp(≈15dp 字号 ×1.5)，**段间距比行间距还大**，
 * 于是「段」和「行」在视觉上拉平，正文看起来像一长串等距文本而非分段。
 * 段间距必须明显小于行间距（否则分段失效），故收到 4.dp。
 */
private val MD_BLOCK_GAP = 4.dp
private val MD_LIST_GAP = 3.dp

/**
 * 🔴 正文的**唯一行距口径**：按字号倍数算，**不继承**调用方传入的绝对 lineHeight。
 *
 * ## 为什么这是骨架（用户实机截图「行间插空行」的真正根因）
 *
 * 聊天气泡调用时传的是 `TextStyle(fontSize = scaled(15), lineHeight = scaled(23))`
 * —— 23 是**绝对 sp**。渲染层此前写 `lineHeight = base.lineHeight * 1.15f`，
 * 于是最终行距 = 23sp × 1.15 = **26.45sp**，而字号只有 15sp：
 * 行距比字号大 11sp（约 0.74 倍），叠加 [MD_BLOCK_GAP] 后每行之间都像插了空行。
 *
 * 更糟的是这种「外部绝对值 × 内部系数」**无法约束**：调用方改字号时行距不会跟着变，
 * 任何一侧调整都会让另一侧失配。
 *
 * 现在改成 `字号 × 1.25`：行距永远与字号同步，且落在中文正文舒适区。
 *
 * 🔴 为何一路从 1.5 降到 1.25（用户连续两轮反馈「还有很多空间没有发挥」）：
 *  - 1.5 是**西文**正文上限。中文方块字天然比西文字身高，同样倍数在中文下显得更松。
 *  - 1.4 仍偏松：15sp 正文行距 21sp，每屏（约 580dp 可视高度）只排下约 27.6 行。
 *  - 1.25 → 18.75sp，每屏约 30.9 行，**多排约 3 行**，且中文小字号（13~15sp）
 *    在 1.25 下仍有清晰行间层次，不会糊成一片。
 *  - 保留的呼吸感由**段间距**（[MD_BLOCK_GAP]）承担，二者分工明确：行内紧凑、段间可辨。
 * 调用方即便传了 `lineHeight = TextUnit.Unspecified` 也能正常工作。
 */
private const val MD_LINE_HEIGHT_RATIO = 1.25f

/** 按字号算正文行距。[base] 只取它的 fontSize，其余样式由各渲染器自行 copy。 */
private fun bodyLineHeight(base: TextStyle): TextUnit =
    base.fontSize * MD_LINE_HEIGHT_RATIO

/**
 * 供单测调用的行距桥接（[bodyLineHeight] 是 private）。
 *
 * 存在的意义：行距口径是本轮「行间插空行」的骨架，必须能被 JVM 单测钉死，
 * 否则很容易又退回「外部绝对 lineHeight × 内部系数」这种无法约束的写法。
 */
internal fun bodyLineHeightForTest(base: TextStyle): TextUnit = bodyLineHeight(base)

/**
 * 🔴 正文列**宽屏封顶**口径（纯函数，供 JVM 单测钉死）。
 *
 * ## 🔴🔴 这个函数已经不负责左右对齐了（别再拿它当对齐骨架用）
 *
 * 旧实现用 `Row(horizontalArrangement = Arrangement.End)` + `widthIn(max)` 做用户消息右对齐，
 * 这套做法**从根上就是错的**：
 *
 * - 内容列内部有 `fillMaxWidth()`，所以它永远被撑到`max`；
 * - `max` 若恰好等于 Row 可用宽度（屏宽 − 列表内边距 − 头像 − 间距），
 *   `Arrangement.End` 就**一点剩余空间都分不到**，子项全部从左缘开始排；
 * - 结果：头像被顶到行尾（看着"对"），但**名称与正文整块贴在屏幕左边** —— 真机截图里
 *   「我 · 13:44」和「1+1什么时候等于3」都在左侧，只有头像在右。
 *
 * 曾误以为是"上限算大了"，只把 `avatarSizeDp` 从 `if (mine) 0 else avatarSize` 修回`avatarSize`
 * （把上限从 >Row 可用拉回 == Row 可用）—— 症状一模一样，因为**问题不是算大了还是算小了，
 * 而是"用 Arrangement 去推一个满宽子项"这个做法本身不成立**。
 *
 * 现在改为：内容列 `Modifier.weight(1f)` 占满行内剩余空间，头像由 Row 自然顶到行尾；
 * 正文左右对齐由 `RichText(textAlign = ...)` 显式下发（用户 `End` / AI `Start`）。
 *
 * ## 本函数剩下的唯一职责：宽屏行长封顶
 *
 * 内容列现在是 `fillMaxWidth()`（父为 Box），在平板/横屏上会无限拉宽，
 * 中文一行超过约 40 字就难回扫，所以仍要给一个上限。下限 200.dp：超窄屏/分屏下不得压到不可读。
 *
 * 🔴 `avatarSizeDp` / `bubblePaddingDp` 现在**只参与宽屏封顶计算，不再影响左右对齐**
 * （头像已改为悬浮在角落、不再挤压正文）。保留参数是为了不破坏调用方签名；
 * 但**不要再把"扣头像宽度"当成对齐手段** —— 那正是本轮之前反复踩的坑。
 *
 * @param screenWidthDp 屏幕可用宽度（dp）
 * @param avatarSizeDp 头像边长（dp）。仅用于宽屏封顶的保守预留，不影响对齐。
 * @param listPaddingDp 消息列表左右内边距之和
 * @param bubblePaddingDp 气泡左右内边距之和；气泡壳已删除时传 0
 */
/**
 * 🔴 名字行相对**悬浮头像**的垂直居中补偿（dp）。
 *
 * 头像 `align(TopStart/TopEnd)` 悬浮在消息 Box 顶端，名字行也在顶端 → 两者顶边平齐，
 * 11sp 的名字只占头像上半截，视觉上"名字飘在头像上面"。名字行下移即可与头像中线对齐。
 *
 * ## 真机实测（R7-12 裁剪图 `Screenshot_2026-10-06-14-27-41-31`，用头像边长=80px 标定 → 1px=0.35dp）
 * ```
 * 头像   中心 y≈37.5px  → 13.1dp
 * 名字行 中心 y≈29.5px  → 10.3dp← 比头像中心高 2.8dp
 * ```
 * 故28dp 头像需要下移约 3dp。
 *
 * ## 🔴 别把它和 [bodyTopInsetDp] 混为一谈
 *
 * 名字行是正文**上方**的兄弟节点：给名字行加top 不会改变正文相对头像的避让关系，
 * 只会把名字推近头像中线。两者的目标是**互补**的，必须分别调、分别测。
 *
 * @param avatarSizeDp 头像边长（dp）。
 * @return 名字行应额外下移的 dp 数（>= 0）。
 */
internal fun nameRowCenterOffsetDp(avatarSizeDp: Int): Int {
    if (avatarSizeDp <= NAME_ROW_TEXT_REF_DP) return 0
    return ((avatarSizeDp - NAME_ROW_TEXT_REF_DP) / 4f).toInt()
        .coerceIn(0, NAME_ROW_CENTER_OFFSET_MAX_DP)
}

/** 名字行文字自身高度基准（dp）：11sp 文字行高 + 其bottom padding。 */
private const val NAME_ROW_TEXT_REF_DP = 16

/** 名字行垂直补偿封顶（dp）：再大名字就沉到头像下方了。 */
private const val NAME_ROW_CENTER_OFFSET_MAX_DP = 4

/**
 * 🔴 正文块相对悬浮头像的**顶部内缩**（dp）—— 让正文从头像下方开始排。
 *
 * 名字行下移后，正文紧随其后，顶部仍会压在头像下半截；这部分让位由本函数负责。
 * **注意**：正文总下移 = [nameRowCenterOffsetDp]（名字行下移，间接带动） + 本函数（直接内缩），
 * 两个都是必要量，不是重复计算。
 *
 * ## 真机实测（`Screenshot_2026-10-06-14-27-41-31`，用户气泡裁剪图）
 * ```
 * 用户头像  T=0  B=42px     （42px 直径 ≈ 28dp）
 * 用户正文  T≈52px         ← 头像底部只差约 10px，正文首行几乎贴住头像下巴
 * ```
 * 用户原话：「文本离头像怎么近，你觉得正常吗」。
 *
 * ## 口径
 *
 * 名字行整体下移 [nameRowCenterOffsetDp] 后，正文也跟着下移同等距离，所以本函数
 * 只补**剩余缺口**，不重复计入。28dp 头像：名字行下移 3dp + 本函数 7dp = 正文总下移 10dp。
 *
 * @param avatarSizeDp 头像边长（dp）。
 * @return 正文块应**额外**下移的 dp 数（>= 0）。
 */
internal fun bodyTopInsetDp(avatarSizeDp: Int): Int {
    if (avatarSizeDp <= NAME_ROW_BLOCK_DP) return 0
    return (avatarSizeDp - NAME_ROW_BLOCK_DP).coerceAtMost(MAX_BODY_TOP_INSET_DP)
}

/** 名字行整块垂直占位（dp）：11sp 文字行高 + bottom padding + 渲染块间距。 */
private const val NAME_ROW_BLOCK_DP = 21

/**
 * 正文顶部内缩封顶（dp）。
 *
 * 🔴 原为 4，**太紧**：用户真机反复反馈「文本离头像太近」，指的就是这里。
 * 28dp 头像时 `bodyTopInsetDp` 算出的 7 被这个 4 砍掉一半，
 * 正文顶部仍压在头像下半截——头像里那只恐龙的下巴几乎贴着正文首行。
 * 旧注释担心的「与名字行脱节」在 10dp 处仍未发生（名字行自身还会再下移
 * [nameRowCenterOffsetDp]，两者相加才决定正文最终落点），所以放宽是安全的。
 */
private const val MAX_BODY_TOP_INSET_DP = 10

internal fun messageContentMaxWidth(
    screenWidthDp: Int,
    avatarSizeDp: Int,
    listPaddingDp: Int,
    bubblePaddingDp: Int,
): Int {
    val reserved = listPaddingDp + bubblePaddingDp + avatarSizeDp + AVATAR_GAP_DP
    return (screenWidthDp - reserved).coerceIn(MIN_CONTENT_WIDTH_DP, MAX_CONTENT_WIDTH_DP)
}


/** 头像与内容列之间的固定间距（dp）—— 与 MessageRow 里两处 `Spacer(10.dp)` 必须一致。 */
private const val AVATAR_GAP_DP = 10

/** 正文宽度下限（dp）：超窄屏/分屏下不得把正文压到不可读。 */
private const val MIN_CONTENT_WIDTH_DP = 200

/** 正文宽度上限（dp）：平板/横屏下不让行长失控。 */
private const val MAX_CONTENT_WIDTH_DP = 720

/**
 * 项目统一 Markdown 渲染器（自写，零第三方依赖）。
 *
 * ## 定位
 * 聊天气泡里唯一的正文渲染入口。**块级 + 行内全语法**都在这里解析，
 * 因此不再依赖上游把文本预先切碎 —— 上游切碎会让标题/列表/引用/表格等
 * 块级语法永远命中不到分支（传入的已是单行文本）。
 *
 * ## 支持语法
 * - 块级：`#`~`######` 标题、``` 围栏代码（含语言标签）、`>` 引用、`-`/`*`/`+` 与 `1.` 列表、
 *   `- [ ]`/`- [x]` 任务列表（支持缩进层级）、GFM 管道表格、`---` 分隔线、段落（空行断段 / 行内软换行）
 * - 行内：`**粗体**`、`*斜体*`、`~~删除线~~`、`==高亮==`、`` `代码` ``、`[文本](链接)`、`<c=#hex>` 着色
 *
 * 颜色全部绑定 Material3 主题，随深浅色自适应。
 */
/**
 * 折叠段落内部的单换行为**一个空格**（CommonMark 软换行语义）。
 *
 * ## 为什么需要它
 *
 * 大量模型输出中文时会**自行折行**：一句话被拆成若干物理行，语义上连续，但解析层按
 * CommonMark 规则已把它们并进同一个 [MdBlock.Paragraph]（这步是对的）。若渲染层把换行符
 * 原样交给 `Text`，Compose 会在每个换行处硬断行 —— 用户实机截图里表现为：句子中间
 * 凭空断行、词被拆开、相邻两行之间出现不该有的空档。
 *
 * 真正的段落分隔是**空行**，那已由解析层切成不同 block，间距由 [MD_BLOCK_GAP] 统一给。
 *
 * ## 边界
 *
 * - 连续多个换行（模型偶尔多吐）折叠成**一个**空格，不产生空行；
 * - 折叠点前后已有空格时不重复加（不产生双空格）；
 * - 行尾换行直接丢弃，首尾做 trim。
 *
 * 纯函数、无 Compose 依赖，可直接单测。
 */
fun collapseInnerNewlines(text: String): String {
    if (text.indexOf('\n') < 0) return text
    val sb = StringBuilder(text.length)
    var i = 0
    val n = text.length
    while (i < n) {
        val c = text[i]
        if (!isBreakChar(c)) {
            sb.append(c); i++; continue
        }
        // 吃掉整串换行
        while (i < n && isBreakChar(text[i])) i++
        if (i >= n) break                       // 行尾换行：丢弃
        val before = sb.lastOrNull()
        // 🔴 续行常带 markdown 缩进（列表项 `  交互式...`）。这些缩进是排版缩进、不是内容，
        //    必须**跳过并只留一个分隔符**，否则会粘连成 `shell,echo` 或撑出 `shell,   echo`。
        var j = i
        while (j < n && (text[j] == ' ' || text[j] == '\t')) j++
        if (j >= n) break
        val hadIndent = j > i
        val after = text[j]
        // 判定是否补空格：
        //  - 源文本已有空格在折点前 → 不重复加；
        //  - 源文本在折点后写了缩进 → 缩进即分隔符，折叠成一个空格；
        //  - 硬折行（两侧都没空白）→ 仅当至少一侧是 ASCII 字母/数字时按 CommonMark 补空格，
        //    中文之间直接连接（`真实状态` 绝不能变成 `真实 状态`）。
        if (before != ' ' && before != '\t' &&
            (hadIndent || isAsciiWordChar(before) || isAsciiWordChar(after))
        ) {
            sb.append(' ')
        }
        i = j
    }
    return sb.toString().trim()
}

/** 是否是软换行类字符（LF / 垂直制表 / 换页）。模型偶尔会吐 VT/FF 当换行用。 */
private fun isBreakChar(c: Char): Boolean =
    c == '\n' || c == '\u000B' || c == '\u000C'

private fun isAsciiWordChar(c: Char?): Boolean {
    if (c == null) return false
    return c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9'
}

@Composable
fun RichText(
    text: String,
    modifier: Modifier = Modifier,
    baseStyle: TextStyle = MaterialTheme.typography.bodyMedium,
    onLinkClick: ((String) -> Unit)? = null,
    /**
     * 🔴 段落水平对齐（用户消息传 [TextAlign.End] / AI 消息传 [TextAlign.Start]）。
     *
     * ## 为什么必须由消息行显式下发（别指望"内容列放到右边"就自动靠右）
     *
     * 内容列用 `weight` 占满行内剩余空间（这样头像才被顶到行尾），
     * 而 `RichText` 根节点是 `fillMaxWidth()` —— **内容列有多宽，正文就铺多宽**。
     * 于是无论内容列摆在行的哪一侧，`Text` 都从它的左缘开始排：
     * 用户消息的短文本照样贴左，视觉上就是"我发的消息跑到左边去了"。
     *
     * 对齐是 `Text` 自身的属性，父级位置管不了它，只能显式传。
     */
    textAlign: TextAlign = TextAlign.Start,
) {
    val cs = MaterialTheme.colorScheme
    // 正文颜色取自baseStyle：用户气泡用暖棕、AI 用主题前景色，必须区分。
    // 标题/列表等块级样式则统一用 onBackground，避免用户消息里出现灰标题。
    val bodyColor = baseStyle.color
    val blocks = remember(text) { parseMarkdown(text) }
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(MD_BLOCK_GAP),
        horizontalAlignment = if (textAlign == TextAlign.End) Alignment.End else Alignment.Start,
    ) {
        blocks.forEach { block ->
            when (block) {
                is MdBlock.Heading -> MdHeading(block, cs, baseStyle, bodyColor, textAlign)
                is MdBlock.Code -> MdCode(block, cs, baseStyle)
                is MdBlock.Quote -> MdQuote(block, cs, baseStyle, bodyColor, textAlign)
                is MdBlock.Alert -> MdAlert(block, cs, baseStyle, bodyColor)
                is MdBlock.ListBlock -> MdList(block, cs, baseStyle, bodyColor)
                is MdBlock.Table -> MdTable(block, cs, baseStyle)
                is MdBlock.Rule -> HorizontalDivider(
                    color = cs.outlineVariant.copy(alpha = 0.5f),
                    modifier = Modifier.padding(vertical = 2.dp),
                )
                is MdBlock.Paragraph -> Text(
                    // 🔴 段落内的**单换行必须折叠成空格**，不能原样丢给 Text 渲染。
                    // 原因：大量模型输出中文时会自己折行（"当前终端环境是一个 proot + Ubuntu" /
                    // "24.04 ARM64 Linux 沙箱，底子很扎实。面色排查收"），解析层已按 CommonMark
                    // 把它们并进同一个 Paragraph；但渲染层若原样输出换行符，Text 会在每个换行处硬断 ——
                    // 用户实机截图里表现为：句子中间凭空断行、词被拆开（"很扎 实"）、
                    // 相邻两行之间出现不该有的空档。
                    text = inlineToAnnotated(
                        parseInlineSpans(collapseInnerNewlines(block.text)), cs, baseStyle),
                    style = baseStyle.copy(lineHeight = bodyLineHeight(baseStyle)),
                    textAlign = textAlign,
                )
            }
        }
    }
}

@Composable
private fun MdHeading(
    b: MdBlock.Heading,
    cs: androidx.compose.material3.ColorScheme,
    base: TextStyle,
    bodyColor: Color,
    textAlign: TextAlign = TextAlign.Start,
) {
    // 层级越高字号越大，但正文是 15sp，标题只留有限梯度，避免小屏上标题反而比正文还挤
    val size = when (b.level) {
        1 -> 22.sp; 2 -> 19.sp; 3 -> 17.sp; 4 -> 16.sp; else -> 15.sp
    }
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = if (textAlign == TextAlign.End) Alignment.End else Alignment.Start,
    ) {
        if (b.level <= 2) {
            // 一二级标题加一条主题色短横，形成清晰的视觉分层
            Box(Modifier.padding(top = 2.dp).width(28.dp).height(3.dp)
                .clip(RoundedCornerShape(2.dp)).background(cs.primary.copy(alpha = 0.7f)))
            Spacer(Modifier.height(6.dp))
        }
        Text(
            text = inlineToAnnotated(parseInlineSpans(b.text), cs, base),
            style = base.copy(fontSize = size, fontWeight = FontWeight.Bold, color = bodyColor, lineHeight = size * 1.3f),
            textAlign = textAlign,
        )
    }
}

@Composable
private fun MdCode(b: MdBlock.Code, cs: androidx.compose.material3.ColorScheme, base: TextStyle) {
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
}

@Composable
private fun MdQuote(
    b: MdBlock.Quote,
    cs: androidx.compose.material3.ColorScheme,
    base: TextStyle,
    bodyColor: Color,
    textAlign: TextAlign = TextAlign.Start,
) {
    Row(Modifier.fillMaxWidth()) {
        // 经典引用样式：左侧主题色竖条
        Box(Modifier.width(3.dp).heightIn(min = 20.dp)
            .clip(RoundedCornerShape(2.dp)).background(cs.primary.copy(alpha = 0.55f)))
        Spacer(Modifier.width(10.dp))
        Text(
            text = inlineToAnnotated(parseInlineSpans(collapseInnerNewlines(b.lines.joinToString("\n"))), cs, base),
            style = base.copy(color = bodyColor.copy(alpha = 0.78f), fontStyle = FontStyle.Italic, lineHeight = bodyLineHeight(base)),
            textAlign = textAlign,
        )
    }
}

/**
 * GitHub Alerts 渲染：左侧竖条 + 图标 + 大写类型标签 + 正文。
 *
 * 竖条/底色按 kind 区分语义（NOTE 蓝、TIP 绿、WARNING 橙、CAUTION 红），比纯文本更易扫读。
 */
@Composable
private fun MdAlert(
    b: MdBlock.Alert,
    cs: androidx.compose.material3.ColorScheme,
    base: TextStyle,
    bodyColor: Color,
) {
    val tint = when (b.kind) {
        "TIP" -> Color(0xFF2E9E63)
        "WARNING", "IMPORTANT" -> Color(0xFFD08700)
        "CAUTION" -> Color(0xFFC0392B)
        else -> cs.primary
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(tint.copy(alpha = 0.08f))
            .padding(start = 10.dp, end = 10.dp, top = 8.dp, bottom = 8.dp)
    ) {
        Box(
            Modifier.width(3.dp).heightIn(min = 18.dp)
                .clip(RoundedCornerShape(2.dp)).background(tint)
        )
        Spacer(Modifier.width(9.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = b.kind,
                style = base.copy(fontSize = 10.sp, fontWeight = FontWeight.Bold, color = tint, letterSpacing = 0.6.sp),
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = inlineToAnnotated(parseInlineSpans(b.body), cs, base),
                style = base.copy(color = bodyColor, lineHeight = bodyLineHeight(base)),
            )
        }
    }
}

@Composable
private fun MdList(
    b: MdBlock.ListBlock,
    cs: androidx.compose.material3.ColorScheme,
    base: TextStyle,
    bodyColor: Color,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(MD_LIST_GAP)) {
        b.items.forEachIndexed { idx, item ->
            Row(Modifier.fillMaxWidth().padding(start = (item.indent * 14).dp)) {
                // 任务列表画复选框，普通列表画项目符号 / 序号
                when {
                    item.checked != null -> {
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
                    }
                    b.ordered -> Text(
                        text = "${b.start + idx}.",
                        style = base.copy(color = cs.primary, fontWeight = FontWeight.SemiBold),
                        modifier = Modifier.width(22.dp),
                    )
                    else -> Box(
                        Modifier.width(22.dp).padding(top = 7.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(Modifier.size(5.dp).clip(RoundedCornerShape(3.dp)).background(cs.primary))
                    }
                }
                Spacer(Modifier.width(6.dp))
                Text(
                    // 列表项同样可能带模型自行折行的单换行，折叠后再交给 Text，
                    // 否则实机会看到「proot Ubuntu 24.04,」/「交互式会话活跃」这种硬断行。
                    text = inlineToAnnotated(
                        parseInlineSpans(collapseInnerNewlines(item.text)), cs, base),
                    style = base.copy(
                        color = if (item.checked == true) bodyColor.copy(alpha = 0.6f) else bodyColor,
                        lineHeight = bodyLineHeight(base),
                    ),
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** 任务列表的勾选标记。用矢量路径画，避免为一个图标引入整套 extended 图标依赖。 */
@Composable
private fun CheckMark(modifier: Modifier, tint: Color) {
    androidx.compose.foundation.Canvas(modifier) {
        val w = this.size.width
        val h = this.size.height
        val path = androidx.compose.ui.graphics.Path().apply {
            moveTo(w * 0.18f, h * 0.52f)
            lineTo(w * 0.42f, h * 0.76f)
            lineTo(w * 0.84f, h * 0.24f)
        }
        drawPath(path, tint, style = androidx.compose.ui.graphics.drawscope.Stroke(width = w * 0.16f))
    }
}

@Composable
private fun MdTable(
    b: MdBlock.Table,
    cs: androidx.compose.material3.ColorScheme,
    base: TextStyle,
) {
    // 表格可能比气泡宽，横向可滚动，避免单元格文字被硬挤成竖排
    val scroll = rememberScrollState()
    val cellColor = base.color
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, cs.outlineVariant.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
            .horizontalScroll(scroll)
    ) {
        Row(Modifier.background(cs.surfaceVariant.copy(alpha = 0.5f))) {
            b.header.forEach { cell ->
                Text(
                    text = inlineToAnnotated(parseInlineSpans(cell), cs, base),
                    style = base.copy(fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = cellColor),
                    modifier = Modifier
                        .widthIn(min = 72.dp)
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                )
            }
        }
        b.rows.forEach { row ->
            HorizontalDivider(color = cs.outlineVariant.copy(alpha = 0.35f))
            Row {
                row.forEach { cell ->
                    Text(
                        text = inlineToAnnotated(parseInlineSpans(cell), cs, base),
                        style = base.copy(fontSize = 13.sp, color = cellColor),
                        modifier = Modifier
                            .widthIn(min = 72.dp)
                            .padding(horizontal = 10.dp, vertical = 7.dp),
                    )
                }
            }
        }
    }
}

/**
 * 把 [InlineSpan] 序列按主题着色成 [AnnotatedString]，并给链接打上 "link" 注解
 * 供点击回调使用。
 */
private fun inlineToAnnotated(
    spans: List<InlineSpan>,
    cs: androidx.compose.material3.ColorScheme,
    base: TextStyle,
): AnnotatedString = buildAnnotatedString {
    spans.forEach { sp ->
        var style = SpanStyle()
        if (sp.code) {
            style = style.merge(
                SpanStyle(
                    background = cs.surfaceVariant.copy(alpha = 0.7f),
                    color = cs.onSurface,
                    fontFamily = FontFamily.Monospace,
                )
            )
        }
        if (sp.highlight) {
            style = style.merge(SpanStyle(background = cs.primary.copy(alpha = 0.18f)))
        }
        if (sp.bold) style = style.merge(SpanStyle(fontWeight = FontWeight.Bold))
        if (sp.italic) style = style.merge(SpanStyle(fontStyle = FontStyle.Italic))
        if (sp.strike) style = style.merge(SpanStyle(textDecoration = TextDecoration.LineThrough))
        if (sp.colorHex != null) {
            val col = runCatching { Color(android.graphics.Color.parseColor(sp.colorHex)) }.getOrNull()
            if (col != null) style = style.merge(SpanStyle(color = col))
        }
        if (sp.link != null) {
            style = style.merge(SpanStyle(color = cs.primary, textDecoration = TextDecoration.Underline))
            pushStringAnnotation("link", sp.link)
        }
        withStyle(style) { append(sp.text) }
        if (sp.link != null) pop()
    }
}