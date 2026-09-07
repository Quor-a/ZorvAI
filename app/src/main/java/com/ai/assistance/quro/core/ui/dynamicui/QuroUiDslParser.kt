package com.ai.assistance.quro.core.ui.dynamicui

import org.json.JSONArray
import org.json.JSONObject

/**
 * ZorvAI 动态 UI DSL 解析器（参照 Kai `KaiUiParser` 的三级流水线重写）。
 *
 * 流水线：
 *  1. **代码块提取** —— 从模型回复中定位 ```quro-ui 围栏，取出原始 body；
 *  2. **语法修复** —— 修正 LLM 常见 JSON 错误（`"key=[` 漏冒号、多余闭合括号、截断未闭合），
 *     使严格 JSON 解析器能成功读入；
 *  3. **逐字段构建** —— 递归走 JSONObject 树构造节点，每个字段独立容错，
 *     缺失或类型错乱一律回落默认值，保证「单个节点坏掉不影响整棵树」。
 *
 * 只有第 2 步的严格解析会产出 [QuroUiParseResult.Failure]，
 * 其后所有读取都是 best-effort，**不会**再抛异常中断整棵树。
 */
object QuroUiDslParser {

    /** UI 代码块的围栏语言标识。同时兼容历史写法 zorv-ui。 */
    private val FENCE_LANGS = setOf("quro-ui", "zorv-ui", "quro_ui")

    // =========================================================================================
    // 1. 代码块提取
    // =========================================================================================

    /**
     * 从 markdown 文本中提取所有 UI 代码块的原始内容（不含围栏行）。
     * 支持 ```lang ... ``` 与 ~~~lang ... ~~~ 两种围栏。
     */
    fun extractUiBlocks(markdown: String): List<String> {
        if (markdown.isBlank()) return emptyList()
        val blocks = mutableListOf<String>()
        val lines = markdown.lines()
        var i = 0
        while (i < lines.size) {
            val open = lines[i].trimStart()
            val fenceChar = when {
                open.startsWith("```") -> "```"
                open.startsWith("~~~") -> "~~~"
                else -> { i++; continue }
            }
            val lang = open.removePrefix(fenceChar).trim()
                .takeWhile { !it.isWhitespace() }.lowercase()
            if (lang !in FENCE_LANGS) { i++; continue }

            // 收集围栏体，直到匹配闭合围栏
            val body = StringBuilder()
            var j = i + 1
            var closed = false
            while (j < lines.size) {
                val l = lines[j]
                if (l.trimStart().startsWith(fenceChar)) { closed = true; break }
                body.append(l).append('\n')
                j++
            }
            // 未闭合也照收（模型流式输出常被截断），交给语法修复阶段补齐
            if (body.isNotBlank()) blocks.add(body.toString())
            i = if (closed) j + 1 else j
        }
        return blocks
    }

    /** 判断文本中是否含有 UI 代码块。 */
    fun hasUiBlock(markdown: String): Boolean = extractUiBlocks(markdown).isNotEmpty()

    // =========================================================================================
    // 2. 语法修复
    // =========================================================================================

    /** LLM 常把 `"children":[` 写成 `"children=[`，补回冒号。 */
    private val brokenKeySyntax = Regex(""""(\w+)=([{\[])""")

    /** 去除行尾多余逗号（JSON 不合法但 LLM 高频产出）。 */
    private val trailingComma = Regex(""",(\s*[}\]])""")

    /**
     * 用栈匹配修复括号不平衡：
     *  - 多余的闭合符号直接跳过；
     *  - 未闭合的结构逆向补齐（} 或 ]）。
     * 全程跟踪字符串与转义状态，避免把字符串内容里的括号当结构符号。
     */
    fun sanitizeJson(raw: String): String {
        if (raw.isBlank()) return raw
        val first = raw.trim().firstOrNull()
        if (first != '{' && first != '[') return raw

        val stack = mutableListOf<Char>()
        val out = StringBuilder()
        var inString = false
        var escaped = false
        // 最近一个非空白结构字符，用于识别「对象数组里忘记闭合每个对象」：
        // 形如 [{a:1,{b:2}] —— 在数组上下文里遇到 ,{ 说明上一个对象缺 }
        var lastSig = ' '

        for (c in raw) {
            if (escaped) {
                escaped = false
                out.append(c)
                continue
            }
            if (inString && c == '\\') {
                escaped = true
                out.append(c)
                continue
            }
            if (c == '"') {
                inString = !inString
                out.append(c)
                lastSig = c
                continue
            }
            if (inString) {
                out.append(c)
                continue
            }
            if (c.isWhitespace()) {
                out.append(c)
                continue
            }

            when (c) {
                '{', '[' -> {
                    // 数组内的对象忘了闭合：在 ,{ / ,[ 前补一个 }
                    if (c == '{' && lastSig == ',' &&
                        stack.lastOrNull() == '{' &&
                        stack.getOrNull(stack.size - 2) == '['
                    ) {
                        out.append('}')
                        stack.removeAt(stack.lastIndex)
                    }
                    stack.add(c)
                    out.append(c)
                    lastSig = c
                }
                '}', ']' -> {
                    val open = if (c == '}') '{' else '['
                    if (stack.lastOrNull() == open) {
                        stack.removeAt(stack.lastIndex)
                        out.append(c)
                        lastSig = c
                    } else if (stack.contains(open)) {
                        // 闭合顺序错乱（如 ] 出现在 } 之前，或 } 出现在 [ 之前）：
                        // 自动补上中间层级所有未闭合括号，再消费当前闭合符，
                        // 避免兄弟键被错误地吞进错误的数组/对象层级。
                        // 例：{"a":[1,2},"b":3} —— 遇到 } 时栈顶是 [，但更深处有 {，
                        // 此时依次弹出并补 ] 与 }，让 "b" 成为同级兄弟而不是被吞进数组。
                        while (stack.isNotEmpty() && stack.lastOrNull() != open) {
                            val inner = stack.removeAt(stack.lastIndex)
                            out.append(if (inner == '{') '}' else ']')
                        }
                        if (stack.lastOrNull() == open) {
                            stack.removeAt(stack.lastIndex)
                            out.append(c)
                        }
                        lastSig = c
                    } else {
                        // 多余闭合符：直接丢弃
                        lastSig = c
                    }
                }
                else -> {
                    out.append(c)
                    lastSig = c
                }
            }
        }

        // 逆向补齐未闭合结构
        while (stack.isNotEmpty()) {
            val open = stack.removeAt(stack.lastIndex)
            out.append(if (open == '{') '}' else ']')
        }
        return out.toString()
    }

    /**
     * 组合修复：截前后垃圾 → 去 BOM → 单引号归一 → 补冒号 → 去尾逗号 → 括号栈平衡。
     *
     * 目标：让 LLM 常见的「不标准 JSON」也能被 [org.json] 读入。模型偶尔会：
     *  - 在 JSON 前后夹说明文字（「这是卡片：{...}」）；
     *  - 用单引号代替双引号（{'a':'b'}）；
     *  - 残留 UTF-8 BOM。
     * 这些都让严格 JSON 解析器直接抛异常，必须先在修复阶段兜底。
     */
    fun repair(raw: String): String {
        var s = raw.trim()
        // 去掉 UTF-8 BOM（部分模型输出会带上）
        s = s.removePrefix("\uFEFF")
        // 截掉首个 { / [ 之前的非 JSON 前缀（如「这是卡片：」）
        val first = s.indexOfFirst { it == '{' || it == '[' }
        if (first > 0) s = s.substring(first)
        // 截掉末个 } / ] 之后的尾部垃圾（如残留的 ``` 或说明文字）
        val last = s.indexOfLast { it == '}' || it == ']' }
        if (last in 0 until s.length - 1) s = s.substring(0, last + 1)
        // 单引号归一为双引号（仅在成对作为字符串定界符、且不在双引号串内时）
        s = normalizeQuotes(s)
        // 修复：原 brokenKeySyntax/trailingComma 正则在字符串内容里也生效，
        // 如 "a=[x" 或内容里 ",}" 被误改写。改为状态机逐字符处理，跳过字符串内部。
        s = fixOutsideStrings(s)
        // 括号栈平衡
        s = sanitizeJson(s)
        return s
    }

    /**
     * 状态机版「补冒号 + 去尾逗号」：只在字符串外处理，字符串内容原样保留。
     *  - "key"=[ / "key"={ → "key":[ / "key":{（漏冒号）
     *  - ,} / ,] → } / ]（尾逗号）
     */
    private fun fixOutsideStrings(s: String): String {
        val out = StringBuilder(s.length)
        var inStr = false
        var escaped = false
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (inStr) {
                out.append(c)
                if (escaped) escaped = false
                else if (c == '\\') escaped = true
                else if (c == '"') inStr = false
                i++
                continue
            }
            when {
                c == '"' -> { inStr = true; out.append(c); i++ }
                // "key"=[ / "key"={ 漏冒号：上一个非空白字符是 '"' 且当前是 '='，下一个是 { 或 [
                c == '=' && out.lastOrNull() == '"' &&
                    i + 1 < s.length && (s[i + 1] == '{' || s[i + 1] == '[') -> {
                    out.append(':'); i++ // 跳过 '='，直接写 ':'
                }
                // 尾逗号：当前是 ','，往后跳过空白后是 } 或 ]
                c == ',' -> {
                    var j = i + 1
                    while (j < s.length && s[j].isWhitespace()) j++
                    if (j < s.length && (s[j] == '}' || s[j] == ']')) {
                        i++ // 丢弃这个逗号
                    } else {
                        out.append(c); i++
                    }
                }
                else -> { out.append(c); i++ }
            }
        }
        return out.toString()
    }

    /**
     * 把成对单引号当作字符串定界符归一为双引号（JSON 只允许双引号）。
     * 仅转换「不在双引号字符串内」的单引号，避免误伤双引号串里的撇号。
     * best-effort：覆盖 `{'a':'b'}` 这类最常见的 LLM 写法。
     *
     * 修复：原实现不跟踪单引号字符串态，`{'a':'don't'}` 的撇号也被转成 `"`，
     * 产出 `{"a":"don"t"}` JSON 损坏。改为：单引号后跟 `,` `:` `}` `]` 或空白
     * 才视为定界符转换，其余（如 don't 的撇号）原样保留。
     */
    private fun normalizeQuotes(s: String): String {
        val out = StringBuilder(s.length)
        var inDbl = false
        var inSgl = false
        var escaped = false
        for (i in s.indices) {
            val c = s[i]
            when {
                escaped -> { out.append(c); escaped = false }
                c == '\\' && (inDbl || inSgl) -> { out.append(c); escaped = true }
                inDbl -> {
                    out.append(c)
                    if (c == '"') inDbl = false
                }
                inSgl -> {
                    // 单引号串内：只有「后面跟 , : } ] 空白 或结尾」的 ' 才是闭合符
                    if (c == '\'') {
                        val next = s.getOrNull(i + 1)
                        if (next == null || next == ',' || next == ':' || next == '}' || next == ']' || next.isWhitespace()) {
                            out.append('"')
                            inSgl = false
                        } else {
                            out.append(c) // don't 的撇号，原样保留
                        }
                    } else {
                        out.append(c)
                    }
                }
                c == '"' -> { inDbl = true; out.append(c) }
                c == '\'' -> {
                    // 串外单引号：前面是 { [ , : 或空白 → 视为字符串起始定界符
                    val prev = out.lastOrNull()
                    if (prev == null || prev == '{' || prev == '[' || prev == ',' || prev == ':' || prev.isWhitespace()) {
                        out.append('"')
                        inSgl = true
                    } else {
                        out.append(c)
                    }
                }
                else -> out.append(c)
            }
        }
        return out.toString()
    }

    // =========================================================================================
    // 3. 解析入口
    // =========================================================================================

    /**
     * 解析一个 UI 代码块 body。
     * 支持两种形态：
     *  - 单个 JSON 对象：{"type":"column", ...}
     *  - NDJSON（每行一个对象）：自动包进 [QuroColumnNode]
     *  - JSON 数组：自动包进 [QuroColumnNode]
     */
    fun parseBlock(rawBlock: String): QuroUiParseResult {
        val repaired = repair(rawBlock)
        if (repaired.isBlank()) {
            return QuroUiParseResult.Failure(repaired, "空内容")
        }

        // NDJSON：多行且每行都是独立对象
        val lines = repaired.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.size > 1 && lines.all { it.startsWith("{") }) {
            val children = lines.mapNotNull { runCatching { buildNode(JSONObject(it)) }.getOrNull() }
            if (children.isNotEmpty()) {
                return QuroUiParseResult.Success(QuroColumnNode(children = children), repaired)
            }
        }

        return try {
            when (repaired.first()) {
                '[' -> {
                    val arr = JSONArray(repaired)
                    val children = (0 until arr.length()).mapNotNull { idx ->
                        arr.optJSONObject(idx)?.let { buildNode(it) }
                    }
                    if (children.isEmpty()) QuroUiParseResult.Failure(repaired, "数组为空")
                    else QuroUiParseResult.Success(QuroColumnNode(children = children), repaired)
                }
                '{' -> {
                    val node = buildNode(JSONObject(repaired))
                    // 正常流程里 buildNode 已对未知类型降级为「带样式容器」、不会返回 null；
                    // 此处 node==null 仅当 buildNode 抛异常（极个别结构崩溃），按失败处理。
                    if (node == null) QuroUiParseResult.Failure(repaired, "节点构建异常")
                    else QuroUiParseResult.Success(node, repaired)
                }
                else -> QuroUiParseResult.Failure(repaired, "内容不是 JSON 对象或数组")
            }
        } catch (e: Exception) {
            QuroUiParseResult.Failure(repaired, e.message ?: "JSON 解析失败")
        }
    }

    /** 解析 markdown 中的第一个 UI 代码块（无则返回 null）。 */
    fun parseFirst(markdown: String): QuroUiParseResult? {
        val block = extractUiBlocks(markdown).firstOrNull() ?: return null
        return parseBlock(block)
    }

    // =========================================================================================
    // 节点构建
    // =========================================================================================

    /**
     * 由 JSONObject 构建节点。type 缺失时按结构推断（有 children → column）。
     * 任何字段读取失败都回落默认值，绝不抛异常。
     */
    fun buildNode(json: JSONObject): QuroUiNode? {
        val type = json.optString("type", json.optString("kind", ""))
            .trim().lowercase().ifBlank { if (json.has("children")) "column" else "text" }

        return try {
            when (type) {
                "column", "vbox", "vertical" -> buildColumn(json)
                "row", "hbox", "horizontal" -> buildRow(json)
                "box", "stack" -> QuroBoxNode(
                    id = json.optStringOrNull("id"),
                    // 通用样式：背景/圆角/边框/阴影/边距/尺寸/透明度等均收进 style，
                    // 不再用已废弃的 backgroundColor/borderRadius 平铺字段。
                    style = buildStyle(json),
                    children = buildChildren(json),
                    weight = json.optDoubleOrNull("weight")?.toFloat(),
                )
                "pane", "panes", "multi_pane", "multipane" -> QuroPaneNode(
                    id = json.optStringOrNull("id"),
                    style = buildStyle(json),
                    children = buildChildren(json),
                    direction = json.optStringOrNull("direction")
                        ?: json.optStringOrNull("orient")
                        ?: json.optStringOrNull("layout"),
                    spacing = json.optIntOrNull("spacing"),
                    padding = json.optIntOrNull("padding"),
                )
                "card" -> QuroCardNode(
                    id = json.optStringOrNull("id"),
                    style = buildStyle(json),
                    children = buildChildren(json),
                    title = json.optStringOrNull("title"),
                    padding = json.optIntOrNull("padding"),
                    cornerRadius = json.optIntOrNull("corner_radius")
                        ?: json.optIntOrNull("cornerRadius"),
                    onClick = json.optJSONObject("on_click")?.let { buildAction(it) },
                    weight = json.optDoubleOrNull("weight")?.toFloat(),
                )
                "text", "label" -> buildText(json)
                "image", "img" -> QuroImageNode(
                    id = json.optStringOrNull("id"),
                    style = buildStyle(json),
                    url = json.optStringOrNull("url") ?: json.optStringOrNull("src") ?: "",
                    alt = json.optStringOrNull("alt"),
                    height = json.optIntOrNull("height"),
                    aspectRatio = json.optDoubleOrNull("aspect_ratio")?.toFloat()
                        ?: json.optDoubleOrNull("aspectRatio")?.toFloat(),
                    cornerRadius = json.optIntOrNull("corner_radius"),
                )
                "icon" -> QuroIconNode(
                    id = json.optStringOrNull("id"),
                    style = buildStyle(json),
                    name = json.optStringOrNull("name") ?: "info",
                    size = json.optIntOrNull("size"),
                    tint = json.optColorOrNull(),
                    description = json.optStringOrNull("description"),
                )
                "markdown", "md", "richtext", "doc" -> QuroMarkdownNode(
                    id = json.optStringOrNull("id"),
                    style = buildStyle(json),
                    value = json.optStringOrNull("value")
                        ?: json.optStringOrNull("content")
                        ?: json.optStringOrNull("text") ?: "",
                )
                "video", "videoplayer" -> QuroVideoNode(
                    id = json.optStringOrNull("id"),
                    style = buildStyle(json),
                    url = json.optStringOrNull("url") ?: json.optStringOrNull("src") ?: "",
                    title = json.optStringOrNull("title"),
                )
                "audio", "music", "audioplayer" -> QuroAudioNode(
                    id = json.optStringOrNull("id"),
                    style = buildStyle(json),
                    url = json.optStringOrNull("url") ?: json.optStringOrNull("src") ?: "",
                    title = json.optStringOrNull("title"),
                )
                "browser", "webview", "web" -> QuroBrowserNode(
                    id = json.optStringOrNull("id"),
                    style = buildStyle(json),
                    url = json.optStringOrNull("url") ?: "",
                    height = json.optIntOrNull("height"),
                )
                // 修复：AI 写 {"type":"html","html":"..."} 时，"html" 不在白名单，
                // 走 unknown fallback 只保留 style/children，html 内容字段被整个丢弃。
                // 新增 QuroHtmlNode 承接「自写 UI」能力：AI 直接写完整 HTML 内联渲染。
                "html", "raw_html", "htmlview" -> QuroHtmlNode(
                    id = json.optStringOrNull("id"),
                    style = buildStyle(json),
                    html = json.optStringOrNull("html")
                        ?: json.optStringOrNull("content")
                        ?: json.optStringOrNull("value") ?: "",
                    height = json.optIntOrNull("height"),
                )
                "code", "codeblock", "source" -> QuroCodeNode(
                    id = json.optStringOrNull("id"),
                    style = buildStyle(json),
                    code = json.optStringOrNull("code")
                        ?: json.optStringOrNull("content")
                        ?: json.optStringOrNull("value") ?: "",
                    lang = json.optStringOrNull("lang") ?: json.optStringOrNull("language"),
                    title = json.optStringOrNull("title"),
                    runnable = json.optBoolean("runnable", false) || json.optBoolean("run", false),
                )
                "badge", "chip", "tag" -> QuroBadgeNode(
                    id = json.optStringOrNull("id"),
                    style = buildStyle(json),
                    // 修复：parser 只读 text/value，但 prompt 与真实 AI 产物大量写 `label`
                    // （badge/chip/tag 语义上就是"标签"，label 最自然）。原写法 14 个新闻
                    // 标签全部被丢弃、badge 显示成空色块，用户感知为「quro-ui 没渲染」。
                    // 补上 label 兜底，并允许 style.label 嵌套写法。
                    text = json.optStringOrNull("text")
                        ?: json.optStringOrNull("value")
                        ?: json.optStringOrNull("label")
                        ?: json.optJSONObject("style")?.optStringOrNull("label")
                        ?: "",
                    color = json.optStringOrNull("color"),
                    background = json.optStringOrNull("background"),
                )
                "progress" -> QuroProgressNode(
                    id = json.optStringOrNull("id"),
                    style = buildStyle(json),
                    progress = json.optDoubleOrNull("progress")?.toFloat()
                        ?: json.optDoubleOrNull("value")?.toFloat(),
                    label = json.optStringOrNull("label"),
                )
                "divider" -> QuroDividerNode(
                    id = json.optStringOrNull("id"),
                    style = buildStyle(json),
                    thickness = json.optIntOrNull("thickness"),
                    padding = json.optIntOrNull("padding"),
                )
                "spacer" -> QuroSpacerNode(
                    id = json.optStringOrNull("id"),
                    style = buildStyle(json),
                    height = json.optIntOrNull("height"),
                    width = json.optIntOrNull("width"),
                )
                "button", "btn" -> buildButton(json)
                "text_input", "input", "textinput", "textfield" -> QuroTextInputNode(
                    id = json.optStringOrNull("id") ?: stableId("input", json),
                    style = buildStyle(json),
                    label = json.optStringOrNull("label"),
                    placeholder = json.optStringOrNull("placeholder")
                        ?: json.optStringOrNull("hint"),
                    value = json.optStringOrNull("value"),
                    multiline = json.optBoolean("multiline", false),
                    lines = json.optIntOrNull("lines"),
                    inputType = json.optStringOrNull("input_type")
                        ?: json.optStringOrNull("inputType"),
                )
                "checkbox", "check" -> QuroCheckboxNode(
                    id = json.optStringOrNull("id") ?: stableId("check", json),
                    style = buildStyle(json),
                    label = json.optStringOrNull("label") ?: json.optStringOrNull("text") ?: "",
                    checked = json.optBoolean("checked", false),
                )
                "switch", "toggle" -> QuroSwitchNode(
                    id = json.optStringOrNull("id") ?: stableId("switch", json),
                    style = buildStyle(json),
                    label = json.optStringOrNull("label"),
                    checked = json.optBoolean("checked", json.optBoolean("value", false)),
                )
                "select", "dropdown", "spinner" -> QuroSelectNode(
                    id = json.optStringOrNull("id") ?: stableId("select", json),
                    style = buildStyle(json),
                    label = json.optStringOrNull("label"),
                    options = buildStringList(json),
                    selected = json.optStringOrNull("selected") ?: json.optStringOrNull("value"),
                )
                "slider" -> QuroSliderNode(
                    id = json.optStringOrNull("id") ?: stableId("slider", json),
                    style = buildStyle(json),
                    label = json.optStringOrNull("label"),
                    value = (json.optDoubleOrNull("value") ?: 0.0).toFloat(),
                    min = (json.optDoubleOrNull("min") ?: 0.0).toFloat(),
                    max = (json.optDoubleOrNull("max") ?: 100.0).toFloat(),
                    step = json.optIntOrNull("step") ?: 1,
                )
                "list" -> QuroListNode(
                    id = json.optStringOrNull("id"),
                    style = buildStyle(json),
                    items = buildStringList(json),
                    itemTemplate = json.optJSONObject("item")?.let { buildNode(it) }
                        ?: json.optJSONObject("item_template")?.let { buildNode(it) },
                    maxHeight = json.optIntOrNull("max_height"),
                )
                "tabs" -> QuroTabsNode(
                    id = json.optStringOrNull("id"),
                    style = buildStyle(json),
                    tabs = buildTabs(json),
                )
                // 容错：未知节点类型不再返回 null 导致整张卡片判失败，
                // 而是降级为一个竖向「带样式容器」（保留 AI 给的通用 style 与子节点），
                // 并在顶部追加一行降级提示。与「单个节点坏掉不影响整棵树」的承诺一致。
                else -> QuroColumnNode(
                    id = json.optStringOrNull("id"),
                    style = buildStyle(json),
                    children = listOf(
                        QuroTextNode(
                            value = "⚠️ 未识别的节点类型：$type（已降级为普通容器）",
                            typography = "caption",
                            color = "warning",
                        )
                    ) + buildChildren(json),
                )
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun buildColumn(json: JSONObject) = QuroColumnNode(
        id = json.optStringOrNull("id"),
        style = buildStyle(json),
        children = buildChildren(json),
        spacing = json.optIntOrNull("spacing"),
        padding = json.optIntOrNull("padding"),
        horizontalAlign = json.optStringOrNull("align")
            ?: json.optStringOrNull("horizontal_align"),
        scrollable = json.optBoolean("scrollable", false),
        weight = json.optDoubleOrNull("weight")?.toFloat(),
    )

    private fun buildRow(json: JSONObject) = QuroRowNode(
        id = json.optStringOrNull("id"),
        style = buildStyle(json),
        children = buildChildren(json),
        spacing = json.optIntOrNull("spacing"),
        padding = json.optIntOrNull("padding"),
        verticalAlign = json.optStringOrNull("align") ?: json.optStringOrNull("vertical_align"),
        scrollable = json.optBoolean("scrollable", false),
        weight = json.optDoubleOrNull("weight")?.toFloat(),
    )

    private fun buildText(json: JSONObject) = QuroTextNode(
        id = json.optStringOrNull("id"),
        // 通用视觉样式（背景/边距/尺寸等）挂在 style 上。
        style = buildStyle(json),
        value = json.optStringOrNull("value")
            ?: json.optStringOrNull("text")
            ?: json.optStringOrNull("content")
            ?: "",
        // typography：旧 style 字符串语义（title/headline/body/caption/label）。
        // 若 `style` 是对象（AI 高频 `style:{fontSize,fontWeight,color,...}`），则各子字段
        // 独立提取到 size/bold/color，typography 留空（避免与对象式样式冲突）。
        typography = if (json.has("style") && json.opt("style") is String) json.optString("style") else null,
        bold = run {
            val s = json.optJSONObject("style")
            // 修复：原判定只覆盖 style.fontWeight=="bold" 字符串与顶层 bold 布尔。
            // 但 CSS 习惯与多数 AI 产物写法是 `weight: 700`（数字权重）——
            // 700 在 CSS 里就是 Bold，600 是 SemiBold。此处统一数值语义：
            // weight ≥ 600 → bold；否则按原 string/bool 判定。
            s?.optStringOrNull("fontWeight") == "bold"
                || s?.optStringOrNull("font_weight") == "bold"
                || (s?.optIntOrNull("fontWeight") ?: s?.optIntOrNull("font_weight") ?: 0) >= 600
                || (json.optIntOrNull("weight") ?: json.optIntOrNull("font_weight") ?: 0) >= 600
                || json.optBoolean("bold", false)
        },
        italic = json.optBoolean("italic", false),
        color = run {
            val s = json.optJSONObject("style")
            s?.optStringOrNull("color")
                ?: s?.optStringOrNull("text_color")
                ?: json.optColorOrNull()
        },
        size = run {
            val s = json.optJSONObject("style")
            s?.optIntOrNull("fontSize")
                ?: s?.optIntOrNull("font_size")
                ?: json.optIntOrNull("fontSize")
                ?: json.optIntOrNull("font_size")
                ?: json.optIntOrNull("size")
        },
        maxLines = json.optIntOrNull("max_lines") ?: json.optIntOrNull("maxLines"),
        // align 兼容对象式 style（style.align / style.text_align）与顶层写法（CSS 习惯同 color/fontSize）
        align = run {
            val s = json.optJSONObject("style")
            s?.optStringOrNull("align")
                ?: s?.optStringOrNull("text_align")
                ?: s?.optStringOrNull("textAlign")
                ?: json.optStringOrNull("align")
                ?: json.optStringOrNull("text_align")
        },
    )

    private fun buildButton(json: JSONObject): QuroButtonNode {
        val action = json.optJSONObject("action")?.let { buildAction(it) }
            ?: json.optJSONObject("on_click")?.let { buildAction(it) }
            ?: json.optJSONObject("onClick")?.let { buildAction(it) }
        return QuroButtonNode(
            id = json.optStringOrNull("id"),
            style = buildStyle(json),
            label = json.optStringOrNull("label")
                ?: json.optStringOrNull("text")
                ?: json.optStringOrNull("value")
                ?: "",
            action = action,
            variant = json.optStringOrNull("variant") ?: json.optStringOrNull("style"),
            enabled = json.optBoolean("enabled", true),
            icon = json.optStringOrNull("icon"),
        )
    }

    /** 构建动作。type 缺失时按字段名推断（有 tool → tool_call，有 url → open_url ...）。 */
    fun buildAction(json: JSONObject): QuroUiAction? {
        val type = json.optString("type", json.optString("kind", "")).trim().lowercase()
            .ifBlank {
                when {
                    json.has("tool") -> "tool_call"
                    json.has("skill") -> "skill"
                    json.has("url") -> "open_url"
                    json.has("copy") || json.has("text") -> "copy"
                    json.has("target_id") || json.has("targetId") -> "toggle"
                    json.has("package") || json.has("package_name") -> "open_app"
                    else -> "callback"
                }
            }
        return try {
            when (type) {
                "callback", "event", "submit" -> QuroCallbackAction(
                    event = json.optStringOrNull("event") ?: json.optStringOrNull("name") ?: "submit",
                    data = buildStringMap(json.optJSONObject("data")),
                    collectFrom = json.optStringList("collect_from")
                        .ifEmpty { json.optStringList("collectFrom") },
                )
                "toggle", "visibility" -> QuroToggleAction(
                    targetId = json.optStringOrNull("target_id")
                        ?: json.optStringOrNull("targetId") ?: "",
                )
                "open_url", "url", "link" -> QuroOpenUrlAction(
                    url = json.optStringOrNull("url") ?: "",
                )
                "copy", "copy_to_clipboard", "clipboard" -> QuroCopyAction(
                    text = json.optStringOrNull("text") ?: json.optStringOrNull("copy") ?: "",
                    label = json.optStringOrNull("label"),
                )
                "tool_call", "tool", "call_tool" -> QuroToolCallAction(
                    tool = json.optStringOrNull("tool") ?: json.optStringOrNull("name") ?: "",
                    arguments = buildStringMap(json.optJSONObject("arguments"))
                        .ifEmpty { buildStringMap(json.optJSONObject("args")) },
                    collectFrom = json.optStringList("collect_from")
                        .ifEmpty { json.optStringList("collectFrom") },
                )
                "skill", "run_skill" -> QuroSkillAction(
                    skill = json.optStringOrNull("skill") ?: json.optStringOrNull("name") ?: "",
                    input = json.optStringOrNull("input"),
                    collectFrom = json.optStringList("collect_from")
                        .ifEmpty { json.optStringList("collectFrom") },
                )
                "open_app", "app", "launch" -> QuroOpenAppAction(
                    // 修复：prompt 教 AI 写 {"type":"open_app","app_name":"微信"}，
                    // 但 parser 只读 package_name/package/app，app_name 直接被丢弃，
                    // 导致 packageName=""，open_app 动作落地即失效。补上 app_name 兜底。
                    packageName = json.optStringOrNull("package_name")
                        ?: json.optStringOrNull("app_name")
                        ?: json.optStringOrNull("package")
                        ?: json.optStringOrNull("app") ?: "",
                )
                // ─── 多层渲染 / 深链导航（v1.0.82 新增）───
                // 打开 ZorvAI 内置界面（终端/模型配置/可视化编程/小程序/工具中心等）
                "open_screen", "open_screen_target", "screen", "openscreen" -> QuroOpenScreenAction(
                    target = json.optStringOrNull("target")
                        ?: json.optStringOrNull("screen")
                        ?: json.optStringOrNull("name") ?: "",
                )
                // 直接渲染 HTML / 小程序到对话气泡（第一层渲染）
                "render_html", "open_html", "html", "miniapp", "render_miniapp" -> QuroRenderHtmlAction(
                    html = json.optStringOrNull("html")
                        ?: json.optStringOrNull("content")
                        ?: json.optStringOrNull("source") ?: "",
                )
                // 直接渲染可视化编程（mermaid）到对话气泡（第一层渲染）
                "render_vispro", "render_mermaid", "vispro", "mermaid" -> QuroRenderVisproAction(
                    source = json.optStringOrNull("source")
                        ?: json.optStringOrNull("mermaid")
                        ?: json.optStringOrNull("content")
                        ?: json.optStringOrNull("html") ?: "",
                )
                // 可视化弹窗（标题 + 内容）
                "visual_popup", "popup", "dialog", "alert" -> QuroVisualPopupAction(
                    title = json.optStringOrNull("title") ?: "",
                    content = json.optStringOrNull("content")
                        ?: json.optStringOrNull("body")
                        ?: json.optStringOrNull("text") ?: "",
                )
                // 可视化询问（prompt + 选项，选中项回发对话）
                "visual_ask", "ask", "choose", "select_option" -> QuroVisualAskAction(
                    prompt = json.optStringOrNull("prompt")
                        ?: json.optStringOrNull("title")
                        ?: json.optStringOrNull("question") ?: "",
                    options = json.optStringList("options")
                        .ifEmpty { buildStringList(json) },
                )
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }

    // =========================================================================================
    // 通用样式解析（v1.0.83）：把嵌套 style 对象或顶层平铺别名收进 QuroUiStyle
    // =========================================================================================

    /**
     * 解析通用样式对象。两种写法等价：
     *  - 嵌套对象：`"style":{"backgroundColor":"#fff","borderRadius":12,"padding":8}`
     *  - 顶层平铺：`{"type":"box","backgroundColor":"#fff","borderRadius":12,"padding":8}`
     * 任一字段非法都回落默认值；全空则返回 null（不挂多余空对象）。
     */
    private fun buildStyle(json: JSONObject): QuroUiStyle? {
        val s = json.optJSONObject("style")
        val background = buildBackground(s, json)
        val padding = buildEdges(s?.opt("padding") ?: json.opt("padding"))
        val margin = buildEdges(s?.opt("margin") ?: json.opt("margin"))
        val width = buildSize(s?.opt("width") ?: json.opt("width"))
        val height = buildSize(s?.opt("height") ?: json.opt("height"))
        val opacityRaw = readNum(s, json, "opacity")
        val opacity = opacityRaw?.let { if (it > 1f) it / 100f else it }
        val style = QuroUiStyle(
            background = background,
            borderColor = readStr(s, json, "borderColor", "border_color"),
            borderWidth = readInt(s, json, "borderWidth", "border_width"),
            borderRadius = readInt(s, json, "borderRadius", "border_radius", "corner_radius", "cornerRadius"),
            shadowElevation = readInt(s, json, "shadowElevation", "shadow", "elevation"),
            shadowColor = readStr(s, json, "shadowColor"),
            padding = padding,
            margin = margin,
            width = width,
            height = height,
            maxWidth = readInt(s, json, "maxWidth", "max_width"),
            maxHeight = readInt(s, json, "maxHeight", "max_height"),
            opacity = opacity,
            align = readStr(s, json, "align"),
            visible = readBool(s, json, "visible"),
        )
        return if (style == QuroUiStyle()) null else style
    }

    /** 背景：纯色或渐变。 */
    private fun buildBackground(s: JSONObject?, json: JSONObject): QuroUiBackground? {
        val gradientObj = s?.optJSONObject("gradient") ?: json.optJSONObject("gradient")
        val bgObj = s?.optJSONObject("background") ?: json.optJSONObject("background")
        // 渐变：gradient 对象，或 background 对象含 colors 数组
        val gradSrc = gradientObj ?: bgObj?.takeIf { it.has("colors") }
        if (gradSrc != null) {
            val colors = readColorList(gradSrc)
            if (colors.isNotEmpty()) {
                return QuroUiBackground.Gradient(
                    colors = colors,
                    direction = gradSrc.optStringOrNull("direction"),
                    angle = gradSrc.optIntOrNull("angle"),
                )
            }
        }
        // background 是对象但只给了 color 字段
        if (bgObj != null && gradientObj == null) {
            bgObj.optStringOrNull("color")?.let { return QuroUiBackground.Solid(it) }
        }
        // 纯色：backgroundColor / background（字符串）
        val solid = readStr(s, json, "backgroundColor", "background_color", "background")
        return solid?.let { QuroUiBackground.Solid(it) }
    }

    /** 边距：数字 → 四边同值；对象 → 单边/双边分别取。 */
    private fun buildEdges(raw: Any?): QuroUiEdges? {
        val edges = when (raw) {
            is Int -> QuroUiEdges(all = raw)
            is Number -> QuroUiEdges(all = raw.toInt())
            is String -> raw.trim().toIntOrNull()?.let { QuroUiEdges(all = it) }
            is JSONObject -> QuroUiEdges(
                all = raw.optIntOrNull("all"),
                horizontal = raw.optIntOrNull("horizontal") ?: raw.optIntOrNull("h"),
                vertical = raw.optIntOrNull("vertical") ?: raw.optIntOrNull("v"),
                top = raw.optIntOrNull("top"),
                bottom = raw.optIntOrNull("bottom"),
                start = raw.optIntOrNull("start") ?: raw.optIntOrNull("left"),
                end = raw.optIntOrNull("end") ?: raw.optIntOrNull("right"),
            )
            else -> null
        }
        return if (edges == null || edges == QuroUiEdges()) null else edges
    }

    /** 尺寸：数字 → 固定 dp；字符串 fill/auto → 撑满/自适应；对象 → {weight} 或 {fixed}。 */
    private fun buildSize(raw: Any?): QuroUiSize? {
        return when (raw) {
            is Int -> QuroUiSize.Fixed(raw)
            is Number -> QuroUiSize.Fixed(raw.toInt())
            is String -> when (raw.trim().lowercase()) {
                "fill", "fill_parent", "match_parent", "100%", "max" -> QuroUiSize.Fill()
                "auto", "wrap", "wrap_content", "wrapcontent" -> QuroUiSize.Wrap
                else -> raw.trim().toIntOrNull()?.let { QuroUiSize.Fixed(it) }
            }
            is JSONObject -> {
                val weight = raw.optDoubleOrNull("weight")?.toFloat()
                    ?: raw.optDoubleOrNull("fill")?.toFloat()
                if (weight != null) QuroUiSize.Fill(weight)
                else raw.optIntOrNull("fixed")?.let { QuroUiSize.Fixed(it) }
                    ?: raw.optIntOrNull("dp")?.let { QuroUiSize.Fixed(it) }
            }
            else -> null
        }
    }

    private fun readStr(s: JSONObject?, json: JSONObject, vararg keys: String): String? {
        for (k in keys) s?.optStringOrNull(k)?.let { return it }
        for (k in keys) json.optStringOrNull(k)?.let { return it }
        return null
    }

    private fun readInt(s: JSONObject?, json: JSONObject, vararg keys: String): Int? {
        for (k in keys) s?.optIntOrNull(k)?.let { return it }
        for (k in keys) json.optIntOrNull(k)?.let { return it }
        return null
    }

    private fun readNum(s: JSONObject?, json: JSONObject, vararg keys: String): Float? {
        for (k in keys) s?.optDoubleOrNull(k)?.toFloat()?.let { return it }
        for (k in keys) json.optDoubleOrNull(k)?.toFloat()?.let { return it }
        return null
    }

    private fun readBool(s: JSONObject?, json: JSONObject, vararg keys: String): Boolean? {
        for (k in keys) {
            val v = s?.opt(k)
            if (v is Boolean) return v
        }
        for (k in keys) {
            val v = json.opt(k)
            if (v is Boolean) return v
        }
        return null
    }

    /** 读取颜色数组（["#a","#b"] 或 [{"color":"#a"}]）。 */
    private fun readColorList(json: JSONObject): List<String> {
        val arr = json.optJSONArray("colors") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            when (val v = arr.opt(i)) {
                is String -> v
                is JSONObject -> v.optStringOrNull("color") ?: v.optStringOrNull("value")
                else -> null
            }
        }
    }

    // =========================================================================================
    // 工具方法：容错读取
    // =========================================================================================

    /** children 既接受数组也接受单个对象。 */
    private fun buildChildren(json: JSONObject): List<QuroUiNode> {
        val arr = json.optJSONArray("children")
            ?: json.optJSONArray("items")?.takeIf { json.optString("type") != "list" }
            ?: json.optJSONArray("content")
        if (arr != null) {
            return (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let { buildNode(it) }
            }
        }
        // 单子节点直接包一层
        return json.optJSONObject("child")?.let { buildNode(it) }?.let { listOf(it) } ?: emptyList()
    }

    /** 读取字符串列表，兼容 ["a","b"] 与 [{"value":"a"}] 两种写法。 */
    private fun buildStringList(json: JSONObject): List<String> {
        val arr = json.optJSONArray("options") ?: json.optJSONArray("items") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            when (val v = arr.opt(i)) {
                is String -> v
                is JSONObject -> v.optStringOrNull("value")
                    ?: v.optStringOrNull("text")
                    ?: v.optStringOrNull("label")
                    ?: v.optStringOrNull("title")
                else -> v?.toString()
            }
        }
    }

    private fun buildStringMap(json: JSONObject?): Map<String, String> {
        if (json == null) return emptyMap()
        val map = LinkedHashMap<String, String>()
        json.keys().forEach { key ->
            val v = json.opt(key)
            // LLM 常把数字/布尔写进 data，这里统一强制转字符串（与 Kai dataAsStrings 同思路）
            if (v != null && v != JSONObject.NULL) map[key] = v.toString()
        }
        return map
    }

    private fun buildTabs(json: JSONObject): List<QuroTabItem> {
        val arr = json.optJSONArray("tabs") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            QuroTabItem(
                title = o.optStringOrNull("title") ?: o.optStringOrNull("label") ?: "Tab ${i + 1}",
                node = o.optJSONObject("node")?.let { buildNode(it) }
                    ?: o.optJSONObject("content")?.let { buildNode(it) }
                    ?: o.optJSONObject("child")?.let { buildNode(it) },
            )
        }
    }

    private fun JSONObject.optStringOrNull(key: String): String? =
        takeIf { has(key) }?.optString(key)?.takeIf { it.isNotBlank() && it != "null" }

    /**
     * 修复：原代码缺 id 的控件用 "input_${System.nanoTime()}" 兜底，流式重渲染每帧
     * 重解析都会生成新 id → remember(node.id) 重置、用户输入被清空、collectFrom
     * 收不到值。改为基于 JSON 内容 hashCode 的稳定 id：同一 UI 结构每次解析得到
     * 同一 id；UI 结构改变 id 也随之变。防 id 冲突由 buildChildren 的索引参与。
     */
    private fun stableId(prefix: String, json: JSONObject): String =
        "${prefix}_${json.toString().hashCode().toUInt().toString(36)}"

    private fun JSONObject.optIntOrNull(key: String): Int? =
        takeIf { has(key) }?.opt(key)?.let { v ->
            when (v) {
                is Int -> v
                is Number -> v.toInt()
                is String -> v.trim().toIntOrNull()
                else -> null
            }
        }

    private fun JSONObject.optDoubleOrNull(key: String): Double? =
        takeIf { has(key) }?.opt(key)?.let { v ->
            when (v) {
                is Double -> v
                is Number -> v.toDouble()
                is String -> v.trim().toDoubleOrNull()
                else -> null
            }
        }

    /** 颜色字段：优先 color，其次 text_color / tint。 */
    private fun JSONObject.optColorOrNull(): String? =
        optStringOrNull("color") ?: optStringOrNull("text_color") ?: optStringOrNull("tint")

    private fun JSONObject.optStringList(key: String): List<String> {
        val arr = optJSONArray(key) ?: return emptyList()
        // 修复：optString 对数字/布尔返回空串被过滤，options:[1,2,3] 全丢。
        // 改用 opt(i)?.toString() 保留原始类型字面量。
        return (0 until arr.length()).mapNotNull { i ->
            arr.opt(i)?.toString()?.takeIf { it.isNotBlank() && it != "null" }
        }
    }
}
