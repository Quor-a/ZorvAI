package com.ai.assistance.quro.core.canvas

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/**
 * AIP（AI Presentation Protocol）v1 协议层 —— 安卓 AI 对话排版引擎「Canvas」的地基。
 *
 * 设计遵循 PRD（安卓AI对话排版引擎产品设计文档 V1.0）4.1 四条原则：
 *  - 流式友好：任意位置截断都可部分解析（[parse] 的截断修复路径）；
 *  - 模型友好：字段缺失给默认值、类型不符安全转换（L1 字段级修复）；
 *  - 渲染友好：一个 Block 一个可独立渲染的 UI 单元，块间无隐式依赖；
 *  - 演进友好：未知 Block 类型 → [Block.Fallback] 富文本兜底渲染，绝不丢弃。
 *
 * 四级降级（PRD 7.2）：
 *  L1 字段级修复 → [parseBlocks] 的 safe 取值；
 *  L2 块级降级 → 单块解析失败 → [Block.Fallback]（原始文本渲染，其余块不受影响）；
 *  L3 通道降级 → 整体解析失败 → [ParseResult.degradation]=ChannelDown（调用方回退增强 Markdown 通道）；
 *  L4 纯文本兜底 → [ParseResult.degradation]=TextDown（永不空白气泡、JSON 源码不上界面——调用方按纯文本渲染 raw）。
 */
object Aip {

    const val PROTOCOL_VERSION = 1

    enum class Degradation { Ok, FieldRepair, BlockDown, ChannelDown, TextDown }

    sealed interface Block {
        val id: String
        val type: String

        data class Heading(override val id: String, val level: Int, val text: String) : Block {
            override val type = "heading"
        }

        data class Paragraph(override val id: String, val text: String) : Block {
            override val type = "paragraph"
        }

        data class ListBlock(override val id: String, val ordered: Boolean, val items: List<String>) : Block {
            override val type = "list"
        }

        data class Table(
            override val id: String,
            val headers: List<String>,
            val rows: List<List<String>>,
        ) : Block {
            override val type = "table"
        }

        data class Code(override val id: String, val lang: String, val code: String) : Block {
            override val type = "code"
        }

        data class Quote(override val id: String, val text: String, val cite: String) : Block {
            override val type = "quote"
        }

        data class Callout(override val id: String, val tone: String, val title: String, val text: String) : Block {
            override val type = "callout"
        }

        data class Divider(override val id: String) : Block {
            override val type = "divider"
        }

        data class Image(override val id: String, val ref: String, val caption: String, val ratio: String) : Block {
            override val type = "image"
        }

        /** 图表：type=bar|line|pie|radar，data.labels + data.series（可多系列）。 */
        data class Chart(
            override val id: String,
            val chartType: String,
            val title: String,
            val labels: List<String>,
            val series: List<Series>,
        ) : Block {
            override val type = "chart"
            data class Series(val name: String, val values: List<Double>)
        }

        /** 多栏容器：children 是嵌套 Block 列表（最多一层嵌套，深层自动拍平渲染）。 */
        data class Columns(override val id: String, val ratio: List<Int>, val children: List<List<Block>>) : Block {
            override val type = "columns"
        }

        data class Steps(override val id: String, val items: List<String>, val direction: String) : Block {
            override val type = "steps"
        }

        data class Timeline(override val id: String, val items: List<TimelineItem>) : Block {
            override val type = "timeline"
            data class TimelineItem(val time: String, val title: String, val text: String)
        }

        data class Mindmap(override val id: String, val layout: String, val root: Node) : Block {
            override val type = "mindmap"
            data class Node(val id: String, val text: String, val tone: String, val children: List<Node>)
        }

        data class Slide(
            override val id: String,
            val layout: String,
            val title: String,
            val subtitle: String,
            val bullets: List<String>,
            val columns: List<Pair<String, String>>,
            val stats: List<Pair<String, String>>,
            val chart: Chart?,
            val table: Table?,
            val imageRef: String,
            val quote: String,
            val quoteAuthor: String,
            val notes: String,
        ) : Block {
            override val type = "slide"
        }

        data class Section(override val id: String, val level: Int, val title: String) : Block {
            override val type = "section"
        }

        /**
         * HTML 块：AI 在排版文档内嵌完整/片段 HTML（网页、图表、Three.js 三维、交互组件等）。
         * 渲染层复用对话框已有的 WebView 渲染器（[com.ai.assistance.quro.ui.HtmlPreviewWebView]），
         * 不再泄漏源码、不再走 Fallback 裸文本。字段兼容 data.html 与块级 html 两种写法。
         */
        data class Html(override val id: String, val html: String) : Block {
            override val type = "html"
        }

        /** L2 块级降级 / 未知类型兜底：原始 JSON 文本按富文本段落渲染，不丢弃。 */
        data class Fallback(override val id: String, override val type: String, val text: String) : Block
    }

    data class Envelope(
        val v: Int,
        val kind: String,               // doc | deck | mindmap | markdown
        val title: String,
        val subtitle: String,
        val author: String,
        val accent: String,             // 主题色（#RRGGBB）
        val themeName: String,
        val blocks: List<Block>,
        val assets: JSONObject?,
    )

    data class ParseResult(
        val envelope: Envelope?,
        val degradation: Degradation,
        val raw: String,
    ) {
        val isAip: Boolean get() = envelope != null
    }

    /** 快速嗅探：内容是否像 AIP 信封（前导 { 且含 "v"/"kind" 字段）。 */
    fun looksLikeAip(source: String): Boolean {
        val t = source.trimStart()
        if (!t.startsWith("{")) return false
        val head = t.take(400)
        return (head.contains("\"kind\"") || head.contains("\"v\"")) &&
            (head.contains("\"blocks\"") || head.contains("\"kind\""))
    }

    /**
     * 解析 AIP 输出（容错）。流程：
     *  1. 完整 JSON → 直接解析（最常见：生成已结束）；
     *  2. 截断修复：状态机扫描找出 blocks 数组内最后一个完整元素的边界，
     *     截掉尾部残块后补 `]}` 再解析（流式中途调用，任意位置截断均可部分解析）；
     *  3. 都失败 → TextDown（L4，调用方按纯文本/增强 Markdown 渲染，不白屏）。
     */
    fun parse(source: String): ParseResult {
        val text = source.trim()
        if (text.isEmpty()) return ParseResult(null, Degradation.TextDown, source)

        // 1. 完整解析
        parseEnvelope(text)?.let { return ParseResult(it, Degradation.Ok, source) }

        // 2. 截断修复：找最后一个安全截断点（完整块边界），补上闭合后缀重解析
        lastSafeCut(text)?.let { cut ->
            parseEnvelope(text.substring(0, cut.pos) + cut.suffix)?.let {
                return ParseResult(it, Degradation.FieldRepair, source)
            }
        }

        // 3. 通道降级（L3/L4 由调用方处理渲染形态）
        return ParseResult(null, Degradation.ChannelDown, source)
    }

    /* ===================== 截断边界扫描 ===================== */

    /**
     * 安全截断点：prefix = text.substring(0, pos)，补上 suffix 后即是合法完整 JSON。
     * 三类安全点：
     *  - blocks 数组内一个完整块元素结束（元素 `}` 3→2）→ 补 `]}`（闭数组+信封）；
     *  - blocks 数组本身闭合（`]` 2→1）→ 补 `}`（闭信封）；
     *  - 根级键值对结束（`}` 2→1，如 meta/theme/assets）→ 补 `}`。
     * 深度模型：0=信封外；1=信封对象内；2=blocks 数组/根级值内；3=块元素内；4+=元素 data 内。
     */
    internal data class SafeCut(val pos: Int, val suffix: String)

    internal fun lastSafeCut(text: String): SafeCut? {
        var depth = 0
        var inString = false
        var escape = false
        var i = 0
        var last: SafeCut? = null
        var insideBlocks = false
        while (i < text.length) {
            val c = text[i]
            if (escape) { escape = false; i++; continue }
            if (inString) {
                when (c) {
                    '\\' -> escape = true
                    '"' -> inString = false
                }
                i++; continue
            }
            when (c) {
                '"' -> {
                    inString = true
                    // 嗅探信封级 "blocks" 键（深度 1），确认值以 [ 开头
                    if (depth == 1 && text.regionMatches(i, "\"blocks\"", 0, 8)) {
                        var j = i + 8
                        while (j < text.length && text[j].isWhitespace()) j++
                        if (j < text.length && text[j] == ':') {
                            j++
                            while (j < text.length && text[j].isWhitespace()) j++
                            if (j < text.length && text[j] == '[') insideBlocks = true
                        }
                    }
                }
                '{', '[' -> depth++
                '}', ']' -> {
                    depth--
                    if (insideBlocks) {
                        when {
                            c == '}' && depth == 2 ->
                                // 一个完整块元素结束（元素属 depth 3，闭合回落到 2）
                                last = SafeCut(i + 1, "]}")
                            c == ']' && depth == 1 -> {
                                // blocks 数组本身闭合：之后的截断切在根级键值上
                                insideBlocks = false
                                last = SafeCut(i + 1, "}")
                            }
                        }
                    } else if (c == '}' && depth == 1) {
                        // 根级键值对结束（blocks 之前或之后，如 meta/theme/assets）
                        last = SafeCut(i + 1, "}")
                    }
                }
            }
            i++
        }
        return last
    }

    /* ===================== 信封解析（L1 字段修复） ===================== */

    private fun parseEnvelope(json: String): Envelope? {
        val obj = runCatching { JSONTokener(json).nextValue() as? JSONObject }.getOrNull() ?: return null
        return runCatching { buildEnvelope(obj) }.getOrNull()
    }

    private fun buildEnvelope(obj: JSONObject): Envelope {
        val blocksArr = obj.optJSONArray("blocks") ?: JSONArray()
        val meta = obj.optJSONObject("meta")
        val theme = obj.optJSONObject("theme")
        return Envelope(
            v = obj.optInt("v", PROTOCOL_VERSION),
            kind = obj.optString("kind", "doc").ifBlank { "doc" },
            title = meta?.optString("title")?.trim().orEmpty(),
            subtitle = meta?.optString("subtitle")?.trim().orEmpty(),
            author = meta?.optString("author")?.trim().orEmpty(),
            accent = theme?.optString("accent")?.trim().orEmpty(),
            themeName = theme?.optString("name", "aurora").orEmpty(),
            blocks = parseBlocks(blocksArr),
            assets = obj.optJSONObject("assets"),
        )
    }

    /** blocks 数组 → 类型化 Block 列表；单块失败 → Fallback（L2，原始文本渲染）。 */
    internal fun parseBlocks(arr: JSONArray): List<Block> {
        val out = ArrayList<Block>(arr.length())
        for (i in 0 until arr.length()) {
            val bo = arr.optJSONObject(i) ?: continue
            val block = runCatching { parseBlock(bo) }.getOrElse {
                Block.Fallback(
                    id = bo.optString("id", "b$i"),
                    type = bo.optString("type", "unknown"),
                    text = bo.toString(),
                )
            }
            out.add(block)
        }
        return out
    }

    private fun parseBlock(bo: JSONObject): Block {
        val id = bo.optString("id", "b_${bo.hashCode().toUInt()}")
        val type = bo.optString("type", "paragraph").lowercase()
        val style = bo.optString("style", "")
        val d = bo.optJSONObject("data") ?: JSONObject()
        return when (type) {
            "heading" -> Block.Heading(id, d.optInt("level", 2).coerceIn(1, 6), d.optString("text"))
            "paragraph" -> Block.Paragraph(id, d.optString("text"))
            "list" -> {
                val items = d.optJSONArray("items")?.map { it.toString() } ?: emptyList()
                Block.ListBlock(id, d.optBoolean("ordered", false), items)
            }
            "table" -> {
                val headers = d.optJSONArray("headers")?.map { it.toString() } ?: emptyList()
                val rows = d.optJSONArray("rows")?.map { row ->
                    (row as? JSONArray)?.map { it.toString() } ?: emptyList()
                } ?: emptyList()
                Block.Table(id, headers, rows)
            }
            "code" -> Block.Code(id, d.optString("lang", ""), d.optString("code"))
            "quote" -> Block.Quote(id, d.optString("text"), d.optString("cite"))
            "callout" -> Block.Callout(id, d.optString("tone", "info"), d.optString("title"), d.optString("text"))
            "divider" -> Block.Divider(id)
            "image" -> Block.Image(id, d.optString("ref"), d.optString("caption"), d.optString("ratio", "auto"))
            "chart" -> parseChart(id, d)
            "columns" -> {
                val ratio = d.optJSONArray("ratio")?.map { (it as? Int) ?: 1 } ?: emptyList()
                val children = d.optJSONArray("children")?.map { child ->
                    val co = child as? JSONObject
                    if (co != null) listOf(parseBlock(co)) else emptyList()
                } ?: emptyList()
                Block.Columns(id, ratio, children)
            }
            "steps" -> {
                val items = d.optJSONArray("items")?.map { it.toString() } ?: emptyList()
                Block.Steps(id, items, d.optString("direction", "vertical"))
            }
            "timeline" -> {
                val items = d.optJSONArray("items")?.map { el ->
                    val io = el as? JSONObject ?: JSONObject()
                    Block.Timeline.TimelineItem(io.optString("time"), io.optString("title"), io.optString("text"))
                } ?: emptyList()
                Block.Timeline(id, items)
            }
            "mindmap" -> {
                val root = parseNode(d.optJSONObject("root") ?: JSONObject())
                Block.Mindmap(id, d.optString("layout", "right"), root)
            }
            "slide" -> parseSlide(id, d)
            "section" -> Block.Section(id, d.optInt("level", 1).coerceIn(1, 4), d.optString("title"))
            // HTML 块：复用对话框既有 WebView 渲染。AI 可能把 HTML 放在 data.html 或块级 html 字段。
            "html" -> {
                val html = d.optString("html", "").ifBlank { bo.optString("html", "") }
                Block.Html(id, html)
            }
            else -> Block.Fallback(id, type, bo.toString())   // 未知类型：兜底富文本，不丢弃
        }
    }

    private fun parseChart(id: String, d: JSONObject): Block.Chart {
        val labels = d.optJSONArray("labels")?.map { it.toString() } ?: emptyList()
        val seriesArr = d.optJSONArray("series")
        val series = ArrayList<Block.Chart.Series>()
        if (seriesArr != null) {
            for (i in 0 until seriesArr.length()) {
                val so = seriesArr.optJSONObject(i) ?: continue
                series.add(Block.Chart.Series(so.optString("name", "系列$i"), so.optJSONArray("data")?.map { (it as? Number)?.toDouble() ?: 0.0 } ?: emptyList()))
            }
        }
        // 单系列兜底：data 直接是数值数组
        if (series.isEmpty()) {
            val flat = d.optJSONArray("data")
            if (flat != null && flat.length() > 0 && flat.opt(0) is Number) {
                series.add(Block.Chart.Series("", flat.map { (it as? Number)?.toDouble() ?: 0.0 }))
            }
        }
        return Block.Chart(id, d.optString("type", "bar").lowercase(), d.optString("title"), labels, series)
    }

    private fun parseSlide(id: String, d: JSONObject): Block.Slide {
        val cols = d.optJSONArray("columns")?.map { el ->
            val co = el as? JSONObject ?: JSONObject()
            co.optString("title", "") to co.optString("text", "")
        } ?: emptyList()
        val stats = d.optJSONArray("stats")?.map { el ->
            val so = el as? JSONObject ?: JSONObject()
            so.optString("value", "") to so.optString("label", "")
        } ?: emptyList()
        return Block.Slide(
            id = id,
            layout = d.optString("layout", "titleBody").ifBlank { "titleBody" },
            title = d.optString("title"),
            subtitle = d.optString("subtitle"),
            bullets = d.optJSONArray("bullets")?.map { it.toString() } ?: emptyList(),
            columns = cols,
            stats = stats,
            chart = d.optJSONObject("chart")?.let { parseChart(id + "_c", it) },
            table = d.optJSONObject("table")?.let {
                Block.Table(
                    id + "_t",
                    it.optJSONArray("headers")?.map { o -> o.toString() } ?: emptyList(),
                    it.optJSONArray("rows")?.map { row -> (row as? JSONArray)?.map { o -> o.toString() } ?: emptyList() } ?: emptyList(),
                )
            },
            imageRef = d.optString("image", ""),
            quote = d.optString("quote"),
            quoteAuthor = d.optString("author"),
            notes = d.optString("notes"),
        )
    }

    private fun parseNode(no: JSONObject): Block.Mindmap.Node {
        return Block.Mindmap.Node(
            id = no.optString("id"),
            text = no.optString("text"),
            tone = no.optString("tone", "default"),
            children = no.optJSONArray("children")?.let { arr ->
                (0 until arr.length()).mapNotNull { i -> arr.opt(i) as? JSONObject }.map(::parseNode)
            }.orEmpty(),
        )
    }
}

/** JSONArray 元素映射糖（org.json 没有内建 map）。 */
private fun <T> JSONArray.map(f: (Any?) -> T): List<T> = (0 until length()).map { f(opt(it)) }
