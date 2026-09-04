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
                        // 闭合顺序错乱（如 ] 出现在 } 之前）：丢弃这个错位闭合符
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
        // "key"=[ / "key"={ 漏冒号
        s = brokenKeySyntax.replace(s) { "\"${it.groupValues[1]}\":${it.groupValues[2]}" }
        // 去尾逗号
        s = trailingComma.replace(s) { it.groupValues[1] }
        // 括号栈平衡
        s = sanitizeJson(s)
        return s
    }

    /**
     * 把成对单引号当作字符串定界符归一为双引号（JSON 只允许双引号）。
     * 仅转换「不在双引号字符串内」的单引号，避免误伤双引号串里的撇号。
     * best-effort：覆盖 `{'a':'b'}` 这类最常见的 LLM 写法。
     */
    private fun normalizeQuotes(s: String): String {
        val out = StringBuilder(s.length)
        var inDbl = false
        for (c in s) {
            if (c == '"') {
                inDbl = !inDbl
                out.append(c)
            } else if (!inDbl && c == '\'') {
                out.append('"')
            } else {
                out.append(c)
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
                    // 正常流程里 buildNode 已对未知类型降级为提示文本节点、不会返回 null；
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
                    children = buildChildren(json),
                    padding = json.optIntOrNull("padding"),
                    weight = json.optDoubleOrNull("weight")?.toFloat(),
                )
                "pane", "panes", "multi_pane", "multipane" -> QuroPaneNode(
                    id = json.optStringOrNull("id"),
                    children = buildChildren(json),
                    direction = json.optStringOrNull("direction")
                        ?: json.optStringOrNull("orient")
                        ?: json.optStringOrNull("layout"),
                    spacing = json.optIntOrNull("spacing"),
                    padding = json.optIntOrNull("padding"),
                )
                "card" -> QuroCardNode(
                    id = json.optStringOrNull("id"),
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
                    url = json.optStringOrNull("url") ?: json.optStringOrNull("src") ?: "",
                    alt = json.optStringOrNull("alt"),
                    height = json.optIntOrNull("height"),
                    aspectRatio = json.optDoubleOrNull("aspect_ratio")?.toFloat()
                        ?: json.optDoubleOrNull("aspectRatio")?.toFloat(),
                    cornerRadius = json.optIntOrNull("corner_radius"),
                )
                "icon" -> QuroIconNode(
                    id = json.optStringOrNull("id"),
                    name = json.optStringOrNull("name") ?: "info",
                    size = json.optIntOrNull("size"),
                    tint = json.optColorOrNull(),
                    description = json.optStringOrNull("description"),
                )
                "markdown", "md", "richtext", "doc" -> QuroMarkdownNode(
                    id = json.optStringOrNull("id"),
                    value = json.optStringOrNull("value")
                        ?: json.optStringOrNull("content")
                        ?: json.optStringOrNull("text") ?: "",
                )
                "video", "videoplayer" -> QuroVideoNode(
                    id = json.optStringOrNull("id"),
                    url = json.optStringOrNull("url") ?: json.optStringOrNull("src") ?: "",
                    title = json.optStringOrNull("title"),
                )
                "audio", "music", "audioplayer" -> QuroAudioNode(
                    id = json.optStringOrNull("id"),
                    url = json.optStringOrNull("url") ?: json.optStringOrNull("src") ?: "",
                    title = json.optStringOrNull("title"),
                )
                "browser", "webview", "web" -> QuroBrowserNode(
                    id = json.optStringOrNull("id"),
                    url = json.optStringOrNull("url") ?: "",
                    height = json.optIntOrNull("height"),
                )
                "code", "codeblock", "source" -> QuroCodeNode(
                    id = json.optStringOrNull("id"),
                    code = json.optStringOrNull("code")
                        ?: json.optStringOrNull("content")
                        ?: json.optStringOrNull("value") ?: "",
                    lang = json.optStringOrNull("lang") ?: json.optStringOrNull("language"),
                    title = json.optStringOrNull("title"),
                    runnable = json.optBoolean("runnable", false) || json.optBoolean("run", false),
                )
                "badge", "chip", "tag" -> QuroBadgeNode(
                    id = json.optStringOrNull("id"),
                    text = json.optStringOrNull("text") ?: json.optStringOrNull("value") ?: "",
                    color = json.optStringOrNull("color"),
                    background = json.optStringOrNull("background"),
                )
                "progress" -> QuroProgressNode(
                    id = json.optStringOrNull("id"),
                    progress = json.optDoubleOrNull("progress")?.toFloat()
                        ?: json.optDoubleOrNull("value")?.toFloat(),
                    label = json.optStringOrNull("label"),
                )
                "divider" -> QuroDividerNode(
                    id = json.optStringOrNull("id"),
                    thickness = json.optIntOrNull("thickness"),
                    padding = json.optIntOrNull("padding"),
                )
                "spacer" -> QuroSpacerNode(
                    id = json.optStringOrNull("id"),
                    height = json.optIntOrNull("height"),
                    width = json.optIntOrNull("width"),
                )
                "button", "btn" -> buildButton(json)
                "text_input", "input", "textinput", "textfield" -> QuroTextInputNode(
                    id = json.optStringOrNull("id") ?: "input_${System.nanoTime()}",
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
                    id = json.optStringOrNull("id") ?: "check_${System.nanoTime()}",
                    label = json.optStringOrNull("label") ?: json.optStringOrNull("text") ?: "",
                    checked = json.optBoolean("checked", false),
                )
                "switch", "toggle" -> QuroSwitchNode(
                    id = json.optStringOrNull("id") ?: "switch_${System.nanoTime()}",
                    label = json.optStringOrNull("label"),
                    checked = json.optBoolean("checked", json.optBoolean("value", false)),
                )
                "select", "dropdown", "spinner" -> QuroSelectNode(
                    id = json.optStringOrNull("id") ?: "select_${System.nanoTime()}",
                    label = json.optStringOrNull("label"),
                    options = buildStringList(json),
                    selected = json.optStringOrNull("selected") ?: json.optStringOrNull("value"),
                )
                "slider" -> QuroSliderNode(
                    id = json.optStringOrNull("id") ?: "slider_${System.nanoTime()}",
                    label = json.optStringOrNull("label"),
                    value = (json.optDoubleOrNull("value") ?: 0.0).toFloat(),
                    min = (json.optDoubleOrNull("min") ?: 0.0).toFloat(),
                    max = (json.optDoubleOrNull("max") ?: 100.0).toFloat(),
                    step = json.optIntOrNull("step") ?: 1,
                )
                "list" -> QuroListNode(
                    id = json.optStringOrNull("id"),
                    items = buildStringList(json),
                    itemTemplate = json.optJSONObject("item")?.let { buildNode(it) }
                        ?: json.optJSONObject("item_template")?.let { buildNode(it) },
                    maxHeight = json.optIntOrNull("max_height"),
                )
                "tabs" -> QuroTabsNode(
                    id = json.optStringOrNull("id"),
                    tabs = buildTabs(json),
                )
                // 容错：未知节点类型不再返回 null 导致整张卡片判失败，
                // 而是降级为一行提示文本（与文件头「单个节点坏掉不影响整棵树」承诺一致）。
                else -> QuroTextNode(
                    id = null,
                    value = "⚠️ 未识别的节点类型：$type",
                    style = "caption",
                    color = "warning",
                )
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun buildColumn(json: JSONObject) = QuroColumnNode(
        id = json.optStringOrNull("id"),
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
        children = buildChildren(json),
        spacing = json.optIntOrNull("spacing"),
        padding = json.optIntOrNull("padding"),
        verticalAlign = json.optStringOrNull("align") ?: json.optStringOrNull("vertical_align"),
        scrollable = json.optBoolean("scrollable", false),
        weight = json.optDoubleOrNull("weight")?.toFloat(),
    )

    private fun buildText(json: JSONObject) = QuroTextNode(
        id = json.optStringOrNull("id"),
        value = json.optStringOrNull("value")
            ?: json.optStringOrNull("text")
            ?: json.optStringOrNull("content")
            ?: "",
        style = json.optStringOrNull("style"),
        bold = json.optBoolean("bold", false),
        italic = json.optBoolean("italic", false),
        color = json.optColorOrNull(),
        size = json.optIntOrNull("size"),
        maxLines = json.optIntOrNull("max_lines") ?: json.optIntOrNull("maxLines"),
        align = json.optStringOrNull("align"),
    )

    private fun buildButton(json: JSONObject): QuroButtonNode {
        val action = json.optJSONObject("action")?.let { buildAction(it) }
            ?: json.optJSONObject("on_click")?.let { buildAction(it) }
            ?: json.optJSONObject("onClick")?.let { buildAction(it) }
        return QuroButtonNode(
            id = json.optStringOrNull("id"),
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
                    packageName = json.optStringOrNull("package_name")
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
        return (0 until arr.length()).mapNotNull { i -> arr.optString(i).takeIf { it.isNotBlank() } }
    }
}
