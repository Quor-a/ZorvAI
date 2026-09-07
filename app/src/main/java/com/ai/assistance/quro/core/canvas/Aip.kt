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
     *  1.5 宽松修复：AI 直接在正文写 AIP 信封时，字符串字面量内常带裸换行/制表符/控制字符
     *      （尤其 code 块内容、多行 paragraph 文本），org.json 的 JSONTokener 会抛
     *      "Unterminated string" 导致整体解析失败 → L3 通道降级（显示降级横幅 + JSON 原文）。
     *      先 [sanitizeJson] 把字符串内部的裸控制字符转义后再解析；
     *  2. 截断修复：状态机扫描找出 blocks 数组内最后一个完整元素的边界，
     *     截掉尾部残块后补 `]}` 再解析（流式中途调用，任意位置截断均可部分解析）；
     *  3. 都失败 → ChannelDown（L3，调用方回退增强 Markdown / 纯文本渲染，不白屏）。
     */
    fun parse(source: String): ParseResult {
        // BOM（\uFEFF）不是空白，trim() 不去除，先手动剥掉避免 JSONTokener 首个字符报错
        val text = source.trim().trimStart('\uFEFF', '\u00A0')
        if (text.isEmpty()) return ParseResult(null, Degradation.TextDown, source)

        // 1. 完整解析
        parseEnvelope(text)?.let { return ParseResult(it, Degradation.Ok, source) }

        // 1.5 宽松修复（L1 字段修复级别）：转义字符串内的裸控制字符后重试
        val sanitized = sanitizeJson(text)
        if (sanitized != text) {
            parseEnvelope(sanitized)?.let { return ParseResult(it, Degradation.FieldRepair, source) }
        }

        // 1.6 提取信封：AI 在 ```aip 围栏里先写「以下是 XX」等说明文字再贴 JSON 时，
        //     从文本中定位第一个完整 {…} 平衡段（外层信封）再解析；说明文字不进信封、不影响渲染。
        extractEnvelopeJson(sanitized)?.let { envJson ->
            if (envJson != sanitized) {
                parseEnvelope(envJson)?.let { return ParseResult(it, Degradation.FieldRepair, source) }
            }
        }

        // 2. 截断修复：找最后一个安全截断点（完整块边界），补上闭合后缀重解析
        lastSafeCut(sanitized)?.let { cut ->
            parseEnvelope(sanitized.substring(0, cut.pos) + cut.suffix)?.let {
                return ParseResult(it, Degradation.FieldRepair, source)
            }
        }

        // 3. 通道降级（L3/L4 由调用方处理渲染形态）
        return ParseResult(null, Degradation.ChannelDown, source)
    }

    /**
     * 宽松 JSON 修复：逐字符状态机扫描，**仅**把「字符串字面量内部」的裸控制字符转义为
     * `\n`/`\r`/`\t`/`\uXXXX`，不动 JSON 结构、不动已有转义序列、不改字符串外任何字符。
     *
     * 背景：AI 直接写 ```aip 围栏时，code 块内容 / 多行 paragraph 文本里的换行经常不转义，
     * org.json 的 JSONTokener 遇到字符串内的裸换行直接抛 "Unterminated string"，
     * 导致整个信封解析失败 → L3 通道降级（"排版引擎已降级为 Markdown 显示" + JSON 原文）。
     * 此函数把这类错误修掉，让信封能正常进入 L1 解析（其余字段仍走 safe 取值兜底）。
     */
    internal fun sanitizeJson(json: String): String {
        val sb = StringBuilder(json.length + 32)
        var inStr = false
        var esc = false
        var i = 0
        while (i < json.length) {
            val c = json[i]
            if (inStr) {
                if (esc) {
                    sb.append(c); esc = false
                } else when (c) {
                    '\\' -> { sb.append(c); esc = true }   // 进入转义，下一字符原样保留
                    '"'  -> { sb.append(c); inStr = false }
                    '\n' -> sb.append("\\n")
                    '\r' -> sb.append("\\r")
                    '\t' -> sb.append("\\t")
                    else -> if (c.code < 0x20) sb.append("\\u%04x".format(c.code)) else sb.append(c)
                }
            } else {
                if (c == '"') { sb.append(c); inStr = true } else sb.append(c)
            }
            i++
        }
        return sb.toString()
    }

    /**
     * 从任意文本里提取「第一个完整 {…} 平衡 JSON 段」。AI 在 ```aip 围栏里常先写
     * 「以下是 XX 文档：」再贴信封 JSON；或把多个 JSON 塞在一起。此函数定位首个 `{` 起、
     * 括号深度归零（字符串字面量内的括号不计数）的完整段，返回该子串。
     * 找不到完整段返回 null。
     */
    internal fun extractEnvelopeJson(text: String): String? {
        val start = text.indexOf('{')
        if (start < 0) return null
        var depth = 0
        var inStr = false
        var esc = false
        for (j in start until text.length) {
            val c = text[j]
            if (inStr) {
                if (esc) esc = false
                else if (c == '\\') esc = true
                else if (c == '"') inStr = false
                continue
            }
            when (c) {
                '"' -> inStr = true
                '{', '[' -> depth++
                '}', ']' -> {
                    depth--
                    if (depth == 0) return text.substring(start, j + 1)
                }
            }
        }
        return null
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
        // 兼容扁平结构（AI 按 aip_compose 工具参数 / 系统提示写的 {kind,title,subtitle,author,accent,blocks}）
        // 与嵌套 meta/theme 两种写法——任一存在即采用，避免「标题/参数缺失、AIP 不渲染」。
        val topTitle = obj.optString("title", "").trim()
        val topSub = obj.optString("subtitle", "").trim()
        val topAuthor = obj.optString("author", "").trim()
        val topAccent = obj.optString("accent", "").trim()
        val metaTitle = meta?.optString("title")?.trim().orEmpty()
        val metaSub = meta?.optString("subtitle")?.trim().orEmpty()
        val metaAuthor = meta?.optString("author")?.trim().orEmpty()
        val themeAccent = theme?.optString("accent")?.trim().orEmpty()
        val themeName = (theme?.optString("name")?.trim().orEmpty()).ifBlank { obj.optString("theme", "").trim() }
        return Envelope(
            v = obj.optInt("v", PROTOCOL_VERSION),
            kind = (obj.optString("kind", "").ifBlank { meta?.optString("kind", "").orEmpty() }).ifBlank { "doc" }.lowercase(),
            title = metaTitle.ifBlank { topTitle },
            subtitle = metaSub.ifBlank { topSub },
            author = metaAuthor.ifBlank { topAuthor },
            accent = themeAccent.ifBlank { topAccent },
            themeName = themeName.ifBlank { "aurora" },
            blocks = parseBlocks(blocksArr),
            assets = obj.optJSONObject("assets"),
        )
    }

    /**
     * 由文档工具内容（title/content/format）构造 AIP 信封（kind=doc），
     * 使工具箱-文档类工具（chat_doc / workspace_doc / enhanced_doc_create）输出与 AIP Canvas 引擎
     * （B 通道）兼容的「完整结构化文档」，替代旧的 [渲染卡片] 极简卡（那张卡不是 AIP 文档）。
     *
     * - 标题由 AipCanvas 头部统一渲染（env.title），故不再额外塞 heading 块，避免重复。
     * - note 可选：生成信息（如文件路径/大小/类型），以 info 卡片显示在文档顶部。
     * - format 映射：html → html 块（WebView 渲染）；代码类 → code 块；其余 → paragraph（MarkdownText 富文本）。
     */
    fun docEnvelope(title: String, content: String, format: String, language: String = "", note: String = ""): String {
        val blocks = JSONArray()
        if (note.isNotBlank()) {
            blocks.put(JSONObject().apply {
                put("id", "note")
                put("type", "callout")
                put("data", JSONObject().apply {
                    put("tone", "info")
                    put("title", "生成信息")
                    put("text", note)
                })
            })
        }
        val fmt = format.lowercase()
        when {
            fmt == "html" || fmt == "htm" ->
                blocks.put(JSONObject().apply {
                    put("id", "b1"); put("type", "html")
                    put("data", JSONObject().apply { put("html", content) })
                })
            fmt in setOf("code", "json", "xml", "yaml", "yml", "css", "js", "javascript",
                "java", "kt", "kotlin", "py", "python", "c", "cpp", "go", "rust", "swift",
                "ts", "typescript", "bash", "sh", "sql", "csv", "svg") ->
                blocks.put(JSONObject().apply {
                    put("id", "b1"); put("type", "code")
                    put("data", JSONObject().apply {
                        put("lang", language.ifBlank { fmt })
                        put("code", content)
                    })
                })
            else ->
                blocks.put(JSONObject().apply {
                    put("id", "b1"); put("type", "paragraph")
                    put("data", JSONObject().apply { put("text", content) })
                })
        }
        return JSONObject().apply {
            put("v", PROTOCOL_VERSION)
            put("kind", "doc")
            put("meta", JSONObject().apply { put("title", title.ifBlank { "文档" }) })
            put("theme", JSONObject().apply { put("name", "aurora"); put("accent", "#2E6BE6") })
            put("blocks", blocks)
        }.toString()
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
