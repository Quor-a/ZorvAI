package com.ai.assistance.quro.core.cards

import org.json.JSONArray
import org.json.JSONObject

/**
 * 卡片围栏协议（v1400 起）—— AI 在**正文里**直接下发可视化组件的第二条通道。
 *
 * ## 为什么要有这条通道
 *
 * 原来卡片只有一条下发路径：模型必须精确构造 `ui_widget` 工具调用。
 * 这有两个硬伤：
 *  1. function call 的参数是结构化的，**错一个键名整张卡直接报废**，而模型在
 *     参数量大时错键很常见；
 *  2. 模型没法「边说边画」——工具调用是离散的，正文里出现卡片时只会变成
 *     一坨 JSON 文本被 Markwon 当代码块渲染（这正是 GenUI 侧查出的同类故障）。
 *
 * 围栏通道让模型可以像写代码块一样自然地画卡片：
 * ```
 * 这是我的分析：
 * ```card
 * {"type":"stat","label":"用户","value":"1.2k","trend":"up"}
 * ```
 * 还需要看明细吗？
 * ```
 *
 * ## 三种围栏头
 *
 * - [FENCE_CARD]  `card`   —— 单组件，一个 JSON 对象
 * - [FENCE_CARDS] `cards`  —— 多组件，JSON 数组，或 `{"layout":..,"children":[..]}`
 * - [FENCE_CARDUI] `cardui` —— A2UI 风格邻接表（扁平 `id` + `children` 引用）
 *
 * 围栏头后面允许跟属性（空格分隔），如 `card compact scroll`。
 *
 * ## 🔴 未闭合围栏必须能解析
 *
 * 流式输出时，正文会**先出现起始 ``` 再逐帧补内容**，围栏闭合在几十帧之后。
 * 若解析器要求结尾也有 ```，那么流式过程中每���帧都会被判成"普通代码块"，
 * 界面会闪一下代码块源码再跳回卡片 —— 用户在 GenUI 侧已经吃过这个亏。
 * 所以 [parse] 把 `closed=false` 也当作合法结果返回，由渲染层决定
 * 是先渲染骨架还是先压住不显示。
 *
 * 本文件是**纯逻辑、无 Android 依赖**，可 JVM 单测。
 */
object CardFence {

    /** 单组件围栏头。 */
    const val FENCE_CARD = "card"

    /** 多组件围栏头。 */
    const val FENCE_CARDS = "cards"

    /** A2UI 风格扁平邻接表围栏头。 */
    const val FENCE_CARDUI = "cardui"

    /** 全部受支持的围栏头。顺序即匹配优先级。 */
    val ALL_FENCES: List<String> = listOf(FENCE_CARD, FENCE_CARDS, FENCE_CARDUI)

    /**
     * 围栏起始行正则。
     *
     * - `card` 必须排在 `cards` 前面匹配吗？**不需要**：正则的 `\b` 已保证
     *   `card` 不会吃掉 `cards` 的前缀（`cards` 里的 `card` 后面跟的是 `s`，
     *   不是词边界），反之 `cards` 也不会误配裸 `card`（`card` 后面必须是空白或行尾）。
     * - 属性部分用 `([^\n`]*)` 而非 `(.*?)`：属性里不该出现反引号，
     *   出现说明围栏已经闭合了，那就是另一个块了。
     */
    private val OPEN_RE = Regex(
        "^[ \\t]*```+[ \\t]*(" + ALL_FENCES.joinToString("|") + ")\\b([^\\n`]*)$",
        RegexOption.MULTILINE
    )

    /** 单独一行（可带缩进）的闭合围栏 —— 任意语言都算闭合标记。 */
    private val CLOSE_RE = Regex("^[ \\t]*```+[ \\t]*$")

    /** 围栏属性里能识别的开关。未识别的属性一律保留在 [CardFenceSlice.attrs] 里但不生效。 */
    private val KNOWN_ATTRS = setOf("compact", "scroll", "bordered", "flat", "dense")

    /**
     * 一段围栏切片。
     *
     * @param fence       围栏头（card / cards / cardui）
     * @param body        围栏内文本（原样，未 trim —— 交给解析层去容忍空白）
     * @param attrs       围栏属性键集合（已小写）
     * @param start       正文中的起始偏移（含）
     * @param end         正文中的结束偏移（不含，指向围栏末尾之后）
     * @param closed      围栏是否已闭合。`false` = 流式中间态
     * @param raw         原始切片文本（含围栏头与围栏身），供"显示源码"降级
     */
    data class Slice(
        val fence: String,
        val body: String,
        val attrs: Set<String>,
        val start: Int,
        val end: Int,
        val closed: Boolean,
        val raw: String,
    ) {
        /** 是否为多组件围栏。 */
        val isMulti: Boolean get() = fence == FENCE_CARDS || fence == FENCE_CARDUI

        /** 紧凑模式：卡片内边距收紧。 */
        val compact: Boolean get() = "compact" in attrs

        /** 可滚动：内容超高时卡片内部滚动而非把气泡撑长。 */
        val scroll: Boolean get() = "scroll" in attrs
    }

    /**
     * 解析正文里全部卡片围栏。
     *
     * 规则：
     *  1. 只认行首（可带缩进）的围栏头，避免误伤正文里行中的 `card` 字样；
     *  2. 遇到受支持的围栏头，一路吃到**下一个**闭合围栏；中间的普通围栏
     *     （比如模型误写了 ```python）不算闭合 —— 但会继续往后找，
     *     因为模型写错闭合语言是很常见的，单个杂散闭合标记不该让整张卡消失；
     *  3. 走到文本末尾仍未闭合 → [Slice.closed] = `false`，正常返回。
     *
     * 不做嵌套：卡片围栏内不再解析卡片围栏。理由是卡片 JSON 里出现
     * "```" 只有两种可能——模型在字符串里写了反引号（罕见），或者模型
     * 想嵌套（更罕见且必然导致 JSON 本身非法）。支持嵌套会让
     * "截断的 JSON" 和 "嵌套的 JSON" 无法区分。
     */
    fun parse(text: String): List<Slice> {
        if (text.isEmpty()) return emptyList()
        val out = ArrayList<Slice>()
        val open = OPEN_RE.find(text) ?: return emptyList()
        // findAll + 手动推进：闭合围栏不是 OPEN_RE 的匹配（闭合行不含受支持的头）
        var cursor = 0
        while (cursor < text.length) {
            val m = OPEN_RE.find(text, cursor) ?: break
            val head = m.groupValues[1].lowercase()
            val attrText = m.groupValues[2].trim().lowercase()
            val bodyStart = m.range.last + 1

            // 从 bodyStart 起找闭合围栏
            val close = findClose(text, bodyStart)
            val body: String
            val end: Int
            val closed: Boolean
            if (close != null) {
                body = text.substring(bodyStart, close)
                end = close + 1
                closed = true
            } else {
                body = text.substring(bodyStart)
                end = text.length
                closed = false
            }
            out.add(
                Slice(
                    fence = head,
                    body = body,
                    attrs = parseAttrs(attrText),
                    start = m.range.first,
                    end = end,
                    closed = closed,
                    raw = text.substring(m.range.first, end),
                )
            )
            cursor = if (end > cursor) end else m.range.last + 1
        }
        return out
    }

    /**
     * 从 [from] 起找第一个闭合围栏行。
     *
     * 找不到返回 null。**不用非贪婪正则**，因为围栏可能嵌套且数量不定，
     * 逐行扫描才可预测 —— 这与 `ChatHistory.foldFences` 用同样的理由。
     */
    private fun findClose(text: String, from: Int): Int? {
        var i = from
        while (i <= text.length) {
            val nl = text.indexOf('\n', i)
            val lineEnd = if (nl < 0) text.length else nl
            val line = text.substring(i, lineEnd)
            if (CLOSE_RE.containsMatchIn(line)) {
                // CLOSE_RE 允许行首缩进，用 find 拿到该行**起始**的绝对下标
                return CLOSE_RE.find(line)?.let { it.range.first + i }
            }
            if (nl < 0) break
            i = nl + 1
        }
        return null
    }

    /** 围栏属性 → 键集合。空属性返回空集合。 */
    fun parseAttrs(attrText: String): Set<String> {
        if (attrText.isBlank()) return emptySet()
        return attrText.split(Regex("[\\s,]+"))
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .toSet()
    }

    /**
     * 把一段围栏体解析成卡片列表。
     *
     * 宽容处理（模型输出不守规矩是常态，不是异常）：
     *  - `[单组件]` 对象 → 1 张卡
     *  - `[多组件]` 数组 → N 张卡
     *  - `[多组件]` `{"layout":..,"children":[..]}` → 合成 1 张 [QuroChatCard.CompositeCard]
     *  - `[A2UI]` `{"surface":..,"components":{id:..}}` → 邻接表还原为树
     *  - 数组里混入非对象元素（字符串/null）→ 跳过，不整体失败
     *  - 一个都解不出来 → 返回空列表，由调用方决定是否降级显示源码
     */
    fun toCards(fence: String, body: String): List<QuroChatCard> {
        val t = body.trim()
        if (t.isEmpty()) return emptyList()
        return when (fence) {
            FENCE_CARD -> listOfNotNull(safeParse(t))
            FENCE_CARDS -> parseCardsBlock(t)
            FENCE_CARDUI -> parseA2uiBlock(t)
            else -> emptyList()
        }
    }

    /** 解析围栏 + 转卡片，一步到位。 */
    fun extract(text: String): List<Pair<Slice, List<QuroChatCard>>> =
        parse(text).mapNotNull { s ->
            val cards = toCards(s.fence, s.body)
            if (cards.isEmpty()) null else s to cards
        }

    /**
     * 把 [protected] 区间内的花括号就地抹成空格（**长度与下标完全不变**）。
     *
     * 用途：正文里既有 ```card 围栏、又有内联组件 JSON 时，外层扫描不能把围栏里的 JSON
     * 当成「内联组件」再抽一遍 —— 那样围栏通道与内联通道会把同一张卡渲染两遍。
     * 抹成空格后，[parse] 之外的一切扫描（花括号平衡、索引、长度）都不用改一行：
     * 花括号找不到，自然就跳过那一段。
     *
     * 保持长度不变是有意为之：调用方可以继续用原始下标定位围栏本身。
     */
    fun blankBraces(text: String, protected: List<IntRange>): String {
        if (protected.isEmpty()) return text
        return text.toCharArray().also { ch ->
            for (r in protected) {
                val from = maxOf(r.first, 0)
                val to = minOf(r.last, ch.lastIndex)
                for (x in from..to) if (ch[x] == '{') ch[x] = ' '
            }
        }.concatToString()
    }

    private fun safeParse(json: String): QuroChatCard? = runCatching { parseComponentSpec(json) }.getOrNull()

    /** `cards` 围栏体。 */
    private fun parseCardsBlock(t: String): List<QuroChatCard> {
        // 形态 A：{"layout":..,"children":[..]} → 合成组合卡
        if (t.startsWith("{")) {
            runCatching { JSONObject(t) }.getOrNull()?.let { o ->
                val children = o.optJSONArray("children")
                if (children != null) {
                    val kids = children.objects().mapNotNull { safeParse(it.toString()) }
                    if (kids.isNotEmpty()) {
                        return listOf(
                            QuroChatCard.CompositeCard(
                                id = o.optString("id", QuroChatCardStore.newId()),
                                title = o.optString("title", "").ifBlank { "" },
                                layout = o.optString("layout", "stack"),
                                children = kids,
                                description = o.optString("description", "").ifBlank { null },
                            )
                        )
                    }
                    return emptyList()
                }
            }
            // 形态 B：单个对象也允许（模型常偷懒）
            return listOfNotNull(safeParse(t))
        }
        // 形态 C：数组
        if (t.startsWith("[")) {
            val arr = runCatching { JSONArray(t) }.getOrNull() ?: return emptyList()
            return arr.objects().mapNotNull { safeParse(it.toString()) }
        }
        return emptyList()
    }

    /**
     * `cardui` 围栏体：A2UI 风格邻接表。
     *
     * 支持两种外层：
     *  - `[{"id":"a","component":"Column","children":["b"]},…]`（A2UI 官方扁平表）
     *  - `{"components":{id:{…}}}`（字典形态）
     *
     * 组件名归一：把 A2UI 的 PascalCase 组件名映射到本 SDK 的 snake_case type
     * （`Text` → `info`、`Button` → `button`、`Column` → `stack`…）。
     * 映射不到的**不丢弃**，包一层 [CustomCard] 保留原始内容，
     * 至少让用户看到 AI 试图画什么。
     */
    private fun parseA2uiBlock(t: String): List<QuroChatCard> {
        val nodes = LinkedHashMap<String, JSONObject>()
        val rootId: String? = when {
            t.startsWith("[") -> {
                val arr = runCatching { JSONArray(t) }.getOrNull() ?: return emptyList()
                for (o in arr.objects()) {
                    val id = o.optString("id", "").ifBlank { o.optString("component", "") }
                    if (id.isNotBlank()) nodes[id] = o
                }
                nodes.keys.firstOrNull()
            }
            t.startsWith("{") -> {
                val o = runCatching { JSONObject(t) }.getOrNull() ?: return emptyList()
                // 🔴 不能用 optString：遇到 JSONObject 它会返回整段 JSON 而不是空串，
                // 扁平邻接表里 "root" 是节点本身，那样 rootId 会变成一长串 JSON，整棵树全丢。
                val root = when (val r0 = if (o.has("root")) o.get("root") else if (o.has("rootId")) o.get("rootId") else null) {
                    is String -> r0
                    else -> ""
                }
                val comps = o.optJSONObject("components") ?: o.optJSONObject("nodes")
                if (comps != null) {
                    // A2UI 标准形态：{"root":"id","components":{...}}
                    val keys = comps.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        comps.optJSONObject(k)?.let { nd -> nodes[k] = nd }
                    }
                } else {
                    // 扁平邻接表：顶层每个值都是节点（模型手写时最自然的一种）
                    val ks = o.keys()
                    while (ks.hasNext()) {
                        val k = ks.next()
                        o.optJSONObject(k)?.let { nd -> nodes[k] = nd }
                    }
                }
                root.ifBlank { pickRoot(nodes) }
            }
            else -> null
        } ?: return emptyList()

        return listOfNotNull(buildFrom(rootId!!, nodes, mutableSetOf(), 0))
    }

    /**
     * 从邻接表里挑根节点。
     *
     * 不能用「第一个 key」：org.json 的 keys() 走 HashMap 迭代，顺序不固定，
     * 扁平表里根节点可能根本不是第一个（真机上就会把 Text 叶子当成根，
     * 整棵树只剩一个叶子节点）。所以这里按**没有父节点**来挑，
     * 并优先叫 root / rootId 的那个。
     */
    private fun pickRoot(nodes: Map<String, JSONObject>): String {
        if (nodes.isEmpty()) return ""
        val referenced = HashSet<String>()
        nodes.values.forEach { nd ->
            nd.optJSONArray("children")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val c = arr.opt(i)
                    val cid = when (c) {
                        is String -> c
                        is JSONObject -> c.optString("id", "").ifBlank { c.optString("component", "") }
                        else -> ""
                    }
                    if (cid.isNotBlank()) referenced.add(cid)
                }
            }
        }
        for (name in listOf("root", "rootId")) {
            if (name in nodes) return name
        }
        nodes.keys.firstOrNull { it !in referenced }?.let { return it }
        return nodes.keys.firstOrNull() ?: ""
    }

    /** 邻接表 → 树。深度上限防环（A/B 互相引用会无限递归）。 */
    private fun buildFrom(
        id: String,
        nodes: Map<String, JSONObject>,
        visiting: MutableSet<String>,
        depth: Int,
    ): QuroChatCard? {
        if (depth > 24 || id in visiting) return null
        val node = nodes[id] ?: return null
        visiting.add(id)

        // A2UI 用 component/children；本 SDK 用 type/children
        val compName = node.optString("component", "").ifBlank { node.optString("type", "") }
        val type = normalizeType(compName)

        val childIds = buildList {
            node.optJSONArray("children")?.let { arr ->
                for (i in 0 until arr.length()) {
                    // 两种写法都吃：{"children":[{"id":"a"}]} 与 {"children":["a"]}
                    val cid = when (val c = arr.opt(i)) {
                        is String -> c
                        is JSONObject -> c.optString("id", "").ifBlank { c.optString("component", "") }
                        else -> ""
                    }
                    if (cid.isNotBlank() && cid != id) add(cid)
                }
            }
        }
        val kids = childIds.mapNotNull { buildFrom(it, nodes, visiting, depth + 1) }
        visiting.remove(id)

        // props / properties 合并成一张扁平 JSON，让 parseComponentSpec 能吃
        val spec = JSONObject().apply {
            put("type", type)
            val nid = node.optString("id", "").ifBlank { QuroChatCardStore.newId() }
            put("id", nid)
            val ntitle = node.optString("title", "").ifBlank { "" }
            put("title", ntitle)
            val props = node.optJSONObject("properties") ?: node.optJSONObject("props")
            if (props != null) {
                val keys = props.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    put(k, props.get(k))
                }
            }
            // 容器语义：容器组件把子节点塞进 children
            if (kids.isNotEmpty()) {
                put("children", JSONArray().also { a -> kids.forEach { a.put(serializeCard(it)) } })
            }
        }

        // 有子节点时优先走组合卡，保留结构
        if (kids.isNotEmpty() && type in CONTAINER_TYPES) {
            return QuroChatCard.CompositeCard(
                id = node.optString("id", QuroChatCardStore.newId()),
                title = node.optString("title", "").ifBlank { "" },
                layout = if (type == "row") "stack" else "stack",
                children = kids,
                description = null,
            )
        }

        val parsed = safeParse(spec.toString())
        if (parsed != null) return parsed

        // 映射不到 → 原样保留，附上原始组件名，别让 AI 的意图凭空消失
        return CustomCard(
            id = node.optString("id", QuroChatCardStore.newId()),
            title = node.optString("title", "").ifBlank { compName },
            kind = compName,
            payload = spec.toString(),
            children = kids,
        )
    }

    /** 容器型 type：子节点需要挂进 children 而不是被 props 吞掉。 */
    private val CONTAINER_TYPES = setOf("stack", "column", "row", "card", "container", "list", "grid", "tabs")

    /**
     * A2UI / 常见 PascalCase 组件名 → 本 SDK 的 snake_case type。
     *
     * 覆盖 A2UI basic catalog 里的高频组件 + 业界常见别名。
     * 未命中的原样返回（小写化），交给上层走 CustomCard 兜底。
     */
    fun normalizeType(name: String): String {
        val n = name.trim()
        if (n.isEmpty()) return "custom"
        val lower = n.lowercase()
        // 已是本 SDK 的 snake_case
        if (lower in KNOWN_SNAKE) return lower
        return COMPONENT_ALIASES[n] ?: lower.replace(Regex("([a-z])([A-Z])"), "$1_$2").lowercase()
    }

    /** 本 SDK 已知的 type 集合（解析失败时也用它做一次兜底判断）。 */
    private val KNOWN_SNAKE: Set<String> = setOf(
        "button", "toggle", "slider", "progress", "stat", "alert", "table", "list", "segmented",
        "pie", "rating", "countdown", "tabs", "expandable", "form", "chips", "steps", "gauge",
        "media", "info", "toolcall", "stream", "mediaplay", "quickreply", "quickaction",
        "timeline", "heatmap", "compare", "radar", "timer", "carousel", "kanban", "yuanbao",
        "color", "counter", "breadcrumb", "tagcloud", "badge", "avatargroup", "mermaid",
        "htmlpreview", "miniapp", "composite", "todo", "chart", "note", "actions", "custom",
    )

    /** 跨 SDK 组件名映射。键为 A2UI/业界写法，值为本 SDK type。 */
    private val COMPONENT_ALIASES: Map<String, String> = mapOf(
        "Text" to "info", "Paragraph" to "note", "Markdown" to "note", "Code" to "note",
        "Button" to "button", "IconButton" to "button", "FAB" to "button", "Chip" to "chips",
        "Chips" to "chips", "Toggle" to "toggle", "Switch" to "toggle", "Checkbox" to "toggle",
        "RadioButton" to "toggle", "Slider" to "slider", "Rating" to "rating", "Stepper" to "counter",
        "TextField" to "form", "TextBox" to "form", "Select" to "segmented", "Dropdown" to "segmented",
        "Column" to "stack", "Row" to "stack", "Stack" to "stack", "Card" to "stack",
        "Container" to "stack", "List" to "list", "Grid" to "list", "Tabs" to "tabs",
        "Accordion" to "expandable", "Divider" to "info", "Image" to "media", "Video" to "mediaplay",
        "Audio" to "mediaplay", "ProgressBar" to "progress", "Progress" to "progress",
        "CircularProgress" to "ring", "Table" to "table", "BarChart" to "chart", "LineChart" to "chart",
        "PieChart" to "pie", "RadarChart" to "radar", "Gauge" to "gauge", "Heatmap" to "heatmap",
        "Mermaid" to "mermaid", "Markdown" to "note", "Custom" to "custom",
    )

    /** 扩展 JSONArray 遍历：只取对象元素，字符串/null/数字一律跳过。 */
    private fun JSONArray.objects(): List<JSONObject> {
        val out = ArrayList<JSONObject>(length())
        for (i in 0 until length()) {
            optJSONObject(i)?.let { out.add(it) }
        }
        return out
    }
}
