package com.ai.assistance.quro.core.canvas

import com.ai.assistance.quro.core.canvas.Aip.Block
import com.ai.assistance.quro.core.canvas.Aip.Envelope

/**
 * AIP 形态互转 + 导出序列化（PRD M3）。
 *
 * 四种形态互转（[convert]）：
 *  - doc → deck：标题做封面，Section/Heading 开新页，后续段落/列表聚合成要点，图表/表格各成一页；
 *  - doc → mindmap：按标题层级建树，正文截断为叶子；
 *  - deck → doc：每页转 Section + 副标题 + 要点列表 + 图表/表格/引用；
 *  - mindmap → doc：根做一级标题，分支做层级标题，叶子做列表。
 *
 * 导出序列化（[toMarkdown] / [toPptxText]）对接 AiwpsCreateTool：
 *  - docx：markdown 语法（**加粗**、| 表 |、代码围栏）；
 *  - pptx：`---` 分页，每页首行标题、其余要点。
 */
object AipConvert {

    /* ===================== 形态互转 ===================== */

    fun convert(env: Envelope, targetKind: String): Envelope = when (targetKind) {
        env.kind -> env
        "deck" -> toDeck(env)
        "mindmap" -> toMindmap(env)
        else -> toDoc(env)
    }

    /** 任意形态 → deck（已是 deck 原样返回）。 */
    fun toDeck(env: Envelope): Envelope {
        if (env.kind == "deck") return env
        var idc = 0
        fun nid() = "s${idc++}"
        val slides = ArrayList<Block.Slide>()
        var cur: Block.Slide? = null
        fun flush() { cur?.let { slides.add(it) }; cur = null }
        fun newSlide(layout: String, title: String, subtitle: String = ""): Block.Slide {
            flush()
            return Block.Slide(nid(), layout, title, subtitle, ArrayList(), ArrayList(), ArrayList(), null, null, "", "", "", "").also { cur = it }
        }
        fun bullets(): MutableList<String> = cur!!.bullets as ArrayList<String>

        if (env.kind == "mindmap") {
            env.blocks.filterIsInstance<Block.Mindmap>().forEach { mm ->
                newSlide("titleBody", mm.root.text.ifBlank { env.title })
                fun walk(n: Block.Mindmap.Node, depth: Int) {
                    if (depth == 1) bullets().add(n.text)
                    else if (depth == 2) newSlide("titleBody", n.text)
                    n.children.forEach { walk(it, depth + 1) }
                }
                mm.root.children.forEach { walk(it, 1) }
            }
            flush()
            return env.copy(kind = "deck", blocks = slides)
        }

        // 封面
        slides.add(Block.Slide(nid(), "cover", env.title.ifBlank { "演示文稿" }, env.subtitle, emptyList(), emptyList(), emptyList(), null, null, "", "", "", ""))
        for (b in env.blocks) {
            when (b) {
                is Block.Heading -> newSlide(if (b.level <= 1) "section" else "titleBody", b.text)
                is Block.Section -> newSlide(if (b.level <= 1) "section" else "titleBody", b.title)
                is Block.Paragraph -> { if (cur == null) newSlide("titleBody", env.title.ifBlank { "内容" }); (cur!!.bullets as ArrayList<String>).add(b.text) }
                is Block.ListBlock -> { if (cur == null) newSlide("titleBody", env.title.ifBlank { "要点" }); (cur!!.bullets as ArrayList<String>).addAll(b.items) }
                is Block.Chart -> { flush(); slides.add(Block.Slide(nid(), "chart", b.title.ifBlank { "图表" }, "", emptyList(), emptyList(), emptyList(), b, null, "", "", "", "")) }
                is Block.Table -> { flush(); slides.add(Block.Slide(nid(), "table", "表格", "", emptyList(), emptyList(), emptyList(), null, b, "", "", "", "")) }
                is Block.Quote -> { flush(); slides.add(Block.Slide(nid(), "quote", "", "", emptyList(), emptyList(), emptyList(), null, null, "", b.text, b.cite, "")) }
                is Block.Callout -> { flush(); slides.add(Block.Slide(nid(), "titleBody", b.title.ifBlank { "提示" }, "", arrayListOf(b.text), emptyList(), emptyList(), null, null, "", "", "", "")) }
                is Block.Steps -> { newSlide("titleBody", "步骤"); (cur!!.bullets as ArrayList<String>).addAll(b.items) }
                is Block.Timeline -> { newSlide("titleBody", "时间线"); (cur!!.bullets as ArrayList<String>).addAll(b.items.map { "${it.time} · ${it.title}" }) }
                is Block.Columns -> {
                    flush()
                    val cols = b.children.map { col -> "" to col.joinToString("\n") { blkText(it) } }
                    slides.add(Block.Slide(nid(), "twoCol", "对比", "", emptyList(), cols, emptyList(), null, null, "", "", "", ""))
                }
                is Block.Image -> { if (cur == null) newSlide("titleBody", b.caption.ifBlank { "插图" }); (cur!!.bullets as ArrayList<String>).add(b.caption.ifBlank { "（图 ${b.ref}）" }) }
                is Block.Code -> { flush(); slides.add(Block.Slide(nid(), "titleBody", "代码 · ${b.lang.ifBlank { "snippet" }}", "", b.code.lines().take(14), emptyList(), emptyList(), null, null, "", "", "", b.code)) }
                is Block.Mindmap -> { newSlide("titleBody", "导图"); (cur!!.bullets as ArrayList<String>).add(flattenMindmap(b.root, 2)) }
                is Block.Divider -> flush()
                is Block.Slide -> {} // doc 里不应出现；防御性忽略（toDeck 入口已排除 deck）
                is Block.Html -> { flush(); slides.add(Block.Slide(nid(), "titleBody", "网页", "", emptyList(), emptyList(), emptyList(), null, null, "", "", "", b.html)) }
                is Block.Fallback -> { if (cur == null) newSlide("titleBody", env.title.ifBlank { "内容" }); (cur!!.bullets as ArrayList<String>).add(b.text.take(200)) }
            }
        }
        flush()
        return env.copy(kind = "deck", blocks = slides)
    }

    /** 任意形态 → doc（已是 doc 原样返回）。 */
    fun toDoc(env: Envelope): Envelope {
        if (env.kind == "doc") return env
        val out = ArrayList<Block>()
        var idc = 0
        fun nid() = "d${idc++}"
        when (env.kind) {
            "deck" -> env.blocks.filterIsInstance<Block.Slide>().forEach { s ->
                if (s.title.isNotBlank()) out.add(Block.Section(nid(), 2, s.title))
                if (s.subtitle.isNotBlank()) out.add(Block.Paragraph(nid(), s.subtitle))
                if (s.stats.isNotEmpty()) out.add(Block.ListBlock(nid(), false, s.stats.map { "${it.second}：**${it.first}**" }))
                if (s.bullets.isNotEmpty()) out.add(Block.ListBlock(nid(), false, s.bullets))
                if (s.columns.isNotEmpty()) out.add(
                    Block.Columns(nid(), List(s.columns.size) { 1 }, s.columns.map { (t, txt) -> listOf<Block>(Block.Paragraph(nid(), if (t.isBlank()) txt else "**$t**\n$txt")) })
                )
                s.chart?.let { out.add(it) }
                s.table?.let { out.add(it) }
                if (s.quote.isNotBlank()) out.add(Block.Quote(nid(), s.quote, s.quoteAuthor))
                if (s.notes.isNotBlank()) out.add(Block.Paragraph(nid(), s.notes))
            }
            "mindmap" -> env.blocks.filterIsInstance<Block.Mindmap>().forEach { mm ->
                out.add(Block.Section(nid(), 1, mm.root.text))
                fun walk(node: Block.Mindmap.Node, depth: Int) {
                    if (depth > 0) out.add(Block.Heading(nid(), (depth + 1).coerceIn(2, 4), node.text))
                    val (leaf, branch) = node.children.partition { it.children.isEmpty() }
                    if (leaf.isNotEmpty()) out.add(Block.ListBlock(nid(), false, leaf.map { it.text }))
                    branch.forEach { walk(it, depth + 1) }
                }
                mm.root.children.forEach { walk(it, 1) }
            }
        }
        return env.copy(kind = "doc", blocks = out)
    }

    /** 任意形态 → mindmap（已是 mindmap 原样返回）。 */
    fun toMindmap(env: Envelope): Envelope {
        if (env.kind == "mindmap") return env
        var idc = 0
        fun nid() = "n${idc++}"
        fun short(s: String) = s.replace(Regex("\\s+"), " ").let { if (it.length > 26) it.take(26) + "…" else it }

        val roots = ArrayList<Block.Mindmap.Node>()
        when (env.kind) {
            "deck" -> env.blocks.filterIsInstance<Block.Slide>().forEach { s ->
                roots.add(Block.Mindmap.Node(
                    nid(), s.title.ifBlank { short(s.subtitle) }, "default",
                    (s.bullets.map { Block.Mindmap.Node(nid(), short(it), "default", emptyList()) } +
                        s.stats.map { Block.Mindmap.Node(nid(), "${it.second} ${it.first}", "default", emptyList()) }),
                ))
            }
            else -> {
                // 按标题层级建树（1~3 级），正文挂当前最深标题下
                val stack = ArrayDeque<Block.Mindmap.Node>()
                fun current(): Block.Mindmap.Node =
                    stack.lastOrNull() ?: Block.Mindmap.Node(nid(), "内容", "default", ArrayList()).also { roots.add(it); stack.addLast(it) }
                fun addLeaf(text: String) {
                    if (text.isBlank()) return
                    val node = current()
                    // 本分支创建的节点 children 都是 ArrayList（current()/heading() 构造时传入）
                    @Suppress("UNCHECKED_CAST")
                    (node.children as? ArrayList<Block.Mindmap.Node>)?.add(Block.Mindmap.Node(nid(), short(text), "default", ArrayList()))
                }
                fun heading(level: Int, text: String) {
                    while (stack.size >= level) stack.removeLast()
                    val node = Block.Mindmap.Node(nid(), short(text), "default", ArrayList())
                    (stack.lastOrNull()?.children as? ArrayList<Block.Mindmap.Node>)?.add(node) ?: roots.add(node)
                    stack.addLast(node)
                }
                for (b in env.blocks) {
                    when (b) {
                        is Block.Heading -> heading(b.level.coerceIn(1, 3), b.text)
                        is Block.Section -> heading(b.level.coerceIn(1, 3), b.title)
                        is Block.Paragraph -> addLeaf(b.text)
                        is Block.ListBlock -> b.items.forEach { addLeaf(it) }
                        is Block.Quote -> addLeaf(b.text)
                        is Block.Callout -> addLeaf(b.title.ifBlank { b.text })
                        is Block.Steps -> b.items.forEach { addLeaf(it) }
                        is Block.Timeline -> b.items.forEach { addLeaf("${it.time} ${it.title}") }
                        is Block.Chart -> addLeaf("图表：${b.title.ifBlank { b.chartType }}")
                        is Block.Table -> addLeaf("表格 ${b.headers.size} 列 × ${b.rows.size} 行")
                        is Block.Mindmap -> roots.add(b.root)
                        is Block.Html -> addLeaf("网页/HTML 内容")
                        else -> {}
                    }
                }
            }
        }
        val root = Block.Mindmap.Node("nroot", env.title.ifBlank { "导图" }, "accent", roots)
        return env.copy(kind = "mindmap", blocks = listOf(Block.Mindmap("mm0", "right", root)))
    }

    /* ===================== 导出序列化（对接 AiwpsCreateTool） ===================== */

    /** docx / md 导出：信封 → Markdown 全文。 */
    fun toMarkdown(env: Envelope): String {
        val sb = StringBuilder()
        sb.append("# ").append(env.title.ifBlank { if (env.kind == "deck") "演示文稿" else "文档" }).append("\n\n")
        if (env.subtitle.isNotBlank()) sb.append("*").append(env.subtitle).append("*\n\n")
        for (b in env.blocks) sb.append(blockToMarkdown(b)).append('\n')
        return sb.toString().trimEnd() + "\n"
    }

    private fun blockToMarkdown(b: Block): String = when (b) {
        is Block.Heading -> "${"#".repeat(b.level.coerceIn(1, 6))} ${b.text}\n"
        is Block.Section -> "${"#".repeat((b.level + 1).coerceIn(2, 6))} ${b.title}\n"
        is Block.Paragraph -> "${b.text}\n"
        is Block.ListBlock -> b.items.mapIndexed { i, t -> "${if (b.ordered) "${i + 1}." else "-"} $t" }.joinToString("\n") + "\n"
        is Block.Table -> buildString {
            append("| ").append(b.headers.joinToString(" | ")).append(" |\n")
            append("|").append(b.headers.joinToString("") { " --- |" }).append("\n")
            b.rows.forEach { r -> append("| ").append(r.joinToString(" | ")).append(" |\n") }
        }
        is Block.Code -> "```${b.lang}\n${b.code}\n```\n"
        is Block.Quote -> "> ${b.text}${if (b.cite.isNotBlank()) "\n> —— ${b.cite}" else ""}\n"
        is Block.Callout -> "> **[${b.tone}] ${b.title}** ${b.text}\n"
        is Block.Divider -> "---\n"
        is Block.Image -> "![${b.caption}](${b.ref})\n"
        is Block.Chart -> buildString {
            append("**${b.title.ifBlank { "图表" }}**\n\n| ").append(b.labels.joinToString(" | "))
            append(" |\n|").append(b.labels.joinToString("") { " --- |" }).append("|\n")
            val max = b.series.maxOfOrNull { it.values.size } ?: 0
            for (r in 0 until max) {
                append("| ").append(b.series.joinToString(" | ") { s -> s.values.getOrNull(r)?.toString() ?: "" }).append(" |\n")
            }
        }
        is Block.Columns -> b.children.joinToString("\n") { col -> col.joinToString("\n") { blockToMarkdown(it) } } + "\n"
        is Block.Steps -> b.items.mapIndexed { i, t -> "${i + 1}. $t" }.joinToString("\n") + "\n"
        is Block.Timeline -> b.items.joinToString("\n") { "- **${it.time}** ${it.title}：${it.text}" } + "\n"
        is Block.Mindmap -> buildString {
            fun walk(n: Block.Mindmap.Node, depth: Int) {
                append("${"  ".repeat(depth)}- ${n.text}\n")
                n.children.forEach { walk(it, depth + 1) }
            }
            walk(b.root, 0)
        }
        is Block.Slide -> buildString {
            append("### ").append(b.title.ifBlank { b.subtitle }).append("\n")
            if (b.subtitle.isNotBlank() && b.title.isNotBlank()) append(b.subtitle).append("\n\n")
            b.stats.forEach { append("- ${it.second}：**${it.first}**\n") }
            b.bullets.forEach { append("- $it\n") }
            b.columns.forEach { (t, txt) -> append(if (t.isBlank()) "- $txt\n" else "- **$t**：$txt\n") }
            if (b.quote.isNotBlank()) append("> “${b.quote}”").append(if (b.quoteAuthor.isNotBlank()) " —— ${b.quoteAuthor}" else "").append('\n')
            b.chart?.let { append(blockToMarkdown(it)) }
            b.table?.let { append(blockToMarkdown(it)) }
        }
        is Block.Fallback -> "${b.text}\n"
        is Block.Html -> b.html + "\n"
    }

    /** pptx 导出：`---` 分页，每页首行标题、其余要点（非 deck 先转 deck）。 */
    fun toPptxText(env: Envelope): String {
        val deck = toDeck(env)
        return deck.blocks.filterIsInstance<Block.Slide>().joinToString("\n---\n") { s ->
            buildString {
                appendLine(s.title.ifBlank { s.subtitle.ifBlank { " " } })
                s.stats.forEach { appendLine("${it.second}：${it.first}") }
                s.bullets.forEach { appendLine(it) }
                s.columns.forEach { (t, txt) -> appendLine(if (t.isBlank()) txt else "$t：$txt") }
                if (s.quote.isNotBlank()) appendLine("“${s.quote}”${if (s.quoteAuthor.isNotBlank()) " —— ${s.quoteAuthor}" else ""}")
            }.trimEnd()
        }
    }

    /** 导出文件名（净化标题）。 */
    fun exportFileStem(env: Envelope): String =
        env.title.ifBlank { if (env.kind == "deck") "幻灯片" else if (env.kind == "mindmap") "导图" else "文档" }
            .replace(Regex("[\\\\/:*?\"<>|\\s]+"), "_").take(40).ifBlank { "aip_export" }

    private fun blkText(b: Block): String = when (b) {
        is Block.Paragraph -> b.text
        is Block.Fallback -> b.text
        is Block.Callout -> listOf(b.title, b.text).filter { it.isNotBlank() }.joinToString("：")
        is Block.Heading -> b.text
        is Block.ListBlock -> b.items.joinToString("；")
        is Block.Html -> "网页内容"
        else -> ""
    }

    private fun flattenMindmap(node: Block.Mindmap.Node, depth: Int): String = buildString {
        fun walk(n: Block.Mindmap.Node, d: Int) {
            if (d in 1..depth) append("  ".repeat(d - 1)).append("- ").append(n.text).append('\n')
            n.children.forEach { walk(it, d + 1) }
        }
        walk(node, 0)
    }.trimEnd()
}
