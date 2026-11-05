package com.ai.assistance.quro.core.ui.card.spec

import org.json.JSONArray
import org.json.JSONObject

/**
 * 协议层反序列化：把 AI 吐出的卡片 JSON 解析成完整 [CardSpec]。
 *
 * 与动态 UI 的 quro-ui DSL 完全无关——这里是另一套独立的「自研卡片渲染」协议。
 * AI 在回复里写 ```quro-card 围栏，内容是一段 JSON（schema 见下），端上自写测量/排版/绘制。
 *
 * JSON schema（最小可用）：
 * ```json
 * {
 *   "id": "card_xxx",            // 可选，缺省按内容 hash 兜底
 *   "type": "line_chart",        // 必须，命中 CardRegistry 白名单（metric/line_chart/button_group/skeleton…）
 *   "version": 1,                // 可选
 *   "renderHint": "canvas",      // 可选：canvas / view / gl
 *   "data": {                    // 业务数据，按 kind 区分
 *     "kind": "chart",           // chart | media | form | status（缺省=空数据）
 *     "chartType": "line",
 *     "series": [ { "name":"", "color":"primary", "points":[0.1,0.5,0.9] } ],
 *     "axis": { "showX":true, "showY":true }
 *   },
 *   "actions": [ { "type":"callback", "name":"确定" } ],
 *   "style": { "bg":"surface", "fg":"onSurface", "cornerDp":12, "fontSizeSp":14 },
 *   "a11y": { "role":"img", "label":"" },
 *   "layout": { "type":"column", "children":[] }   // 可选自描述布局树
 * }
 * ```
 *
 * 解析失败（JSON 非法 / 缺 type）返回 null，调用方应降级为 Markdown，绝不崩对话框。
 */

/** 把 AI 卡片 JSON 解析成完整 [CardSpec]；失败返回 null。 */
fun parseCardSpec(json: String): CardSpec? {
    return try {
        val obj = JSONObject(json)
        // 🔴 缺 type 不再直接放弃（真机实证：模型高频写出
        //   {"title":"..","value":98,"unit":"/100","trend":"↑"..} 这种无 type 的平铺格式，
        //   旧逻辑整围栏跳过 → 用户看到的还是「卡片坏了」）。
        //   按字段形状推断 type 并把平铺字段**包进 data 壳**，宁可猜一次也不空手而归。
        var type = obj.optString("type").trim().lowercase()
        var dataObj = obj.optJSONObject("data")
        if (type.isBlank()) {
            val inferred = inferTypeAndWrap(obj) ?: return null
            type = inferred.first
            dataObj = inferred.second
        } else if (dataObj == null || dataObj.optString("kind").isBlank()) {
            // 🔴 三分支，优先级从高到低：
            //  ① 按 type 直接包壳（type 已经告诉我们是什么卡；字段同义写法也在这里兼容：
            //     `series[].label/value`（组件库写法）、`chart_type`、`points`/`values`、`segments`…）；
            //  ② 按字段形状推断（模型把字段平铺在外层）；
            //  ③ 已给的 data 壳里有内容但没写 kind → 保留原样，交给 parseCardData 兜空。
            val canonical = CardTypes.canonical(type)
            val typed = wrapByType(canonical, dataObj?.takeIf { it.length() > 0 } ?: obj)
                ?: dataObj?.let { wrapByType(canonical, obj) }
            val flat = if (dataObj == null) {
                inferTypeAndWrap(obj, forceMetric = canonical == CardTypes.METRIC && obj.has("value"))?.second
            } else {
                null
            }
            dataObj = typed ?: flat ?: dataObj ?: JSONObject()
        }
        val id = obj.optString("id").ifBlank { "card_" + json.hashCode().toString(36).replace("-", "m") }
        val version = obj.optInt("version", 1)
        val data = parseCardData(dataObj)
        val actions = parseActions(obj.optJSONArray("actions"))
        val style = parseStyle(obj.optJSONObject("style"))
        val a11y = parseA11y(obj.optJSONObject("a11y"))
        val renderHint = obj.optString("renderHint", "canvas").ifBlank { "canvas" }
        // 标题/说明：卡头统一画（渲染器不再各画各的）。兼容 title/heading/name 三种写法。
        val title = obj.optString("title")
            .ifBlank { obj.optString("heading") }
            .ifBlank { obj.optString("name") }
            .ifBlank { "" }
        val subtitle = obj.optString("subtitle")
            .ifBlank { obj.optString("caption") }
            .ifBlank { obj.optString("desc") }
            .ifBlank { "" }
        // 🔴 layout 三种写法都要认（原来只认对象，其余一律 null → custom 卡渲染成空盒子）：
        //  ① "layout":{...}         对象（标准）
        //  ② "layout":"{...}"       字符串形式的 JSON（模型常这么写）
        //  ③ 没有 layout，但顶层直接给了 children/layout 节点字段 → 用顶层当根
        val layout = parseLayoutNode(obj.optJSONObject("layout"))
            ?: runCatching {
                val s = obj.optString("layout", "")
                if (s.isNotBlank() && s.trimStart().startsWith("{")) parseLayoutNode(JSONObject(s)) else null
            }.getOrNull()
            ?: run {
                // 顶层兜底：{"type":"custom","children":[...]} 或 {"type":"custom","layout_type":"column",...}
                if (obj.has("children") || obj.has("layoutType") || obj.has("layout_type")) {
                    val root = JSONObject(obj.toString())
                    if (!root.has("type") || !isLayoutNodeType(root.optString("type"))) {
                        runCatching { root.put("type", obj.optString("layoutType", obj.optString("layout_type", "column"))) }
                    }
                    parseLayoutNode(root)
                } else null
            }
        CardSpec(
            id = id,
            type = type,
            version = version,
            layout = layout,
            data = data,
            actions = actions,
            style = style,
            a11y = a11y,
            renderHint = renderHint,
            rawJson = json,
            title = title,
            subtitle = subtitle,
        )
    } catch (_: Exception) {
        null
    }
}

private fun parseCardData(o: JSONObject?): CardData {
    if (o == null) return CardData.Empty
    return when (o.optString("kind", "").lowercase()) {
        "chart" -> CardData.Chart(
            // snake_case（组件库写法）与 camelCase 都认
            chartType = o.optString("chartType")
                .ifBlank { o.optString("chart_type") }
                .ifBlank { o.optString("type") }
                .ifBlank { "line" },
            series = parseSeries(o.optJSONArray("series") ?: o.optJSONArray("points") ?: o.optJSONArray("values")),
            axis = parseAxis(o.optJSONObject("axis")),
        )
        "media" -> CardData.Media(
            mediaType = o.optString("mediaType").ifBlank { o.optString("media_type") }.ifBlank { "table" },
            rows = parseStringMatrix(o.optJSONArray("rows")),
            headers = parseStringList(o.optJSONArray("headers")),
            code = (o.optString("code").ifBlank { o.optString("content") }).ifBlank { null },
            lang = (o.optString("lang").ifBlank { o.optString("language") }).ifBlank { null },
            images = parseStringList(o.optJSONArray("images") ?: o.optJSONArray("urls")),
            items = parseTimeline(o.optJSONArray("items")),
        )
        "form" -> CardData.Form(
            formType = o.optString("formType").ifBlank { o.optString("form_type") }.ifBlank { "button_group" },
            buttons = parseButtons(o.optJSONArray("buttons") ?: o.optJSONArray("actions")),
            fields = parseFields(o.optJSONArray("fields")),
            min = o.optDouble("min", 0.0).toFloat(),
            max = o.optDouble("max", 100.0).toFloat(),
            value = o.optDouble("value", 0.0).toFloat(),
            options = parseStringList(o.optJSONArray("options") ?: o.optJSONArray("choices")),
            selected = parseStringList(o.optJSONArray("selected")),
        )
        "status" -> CardData.Status(
            statusType = o.optString("statusType").ifBlank { o.optString("status_type") }.ifBlank { "skeleton" },
            text = o.optString("text").ifBlank { o.optString("label") },
            progress = o.optDouble("progress", o.optDouble("value", 0.0)).toFloat(),
            retryable = o.optBoolean("retryable", false),
            reason = o.optString("reason").ifBlank { null },
        )
        else -> CardData.Empty
    }
}

/**
 * 序列解析，兼容三种真实写法（模型三种都会写）：
 *  1. 标准：`[{"name":"CPU","points":[0.1,0.5],"labels":["一","二"]}]`
 *  2. 组件库 / 图表库写法：`[{"label":"周一","value":3},{"label":"周二","value":5}]`
 *     —— 每个元素就是一个数据点，这里合成**单序列**（点名进 labels，值进 points）；
 *  3. 裸数组：`[1,3,5,2]` —— 直接当单序列的点。
 */
private fun parseSeries(a: JSONArray?): List<CardData.Series> {
    if (a == null) return emptyList()
    if (a.length() == 0) return emptyList()

    // ③ 裸数字数组
    if ((0 until a.length()).all { a.opt(it) is Number }) {
        return listOf(
            CardData.Series(
                name = "",
                points = (0 until a.length()).map { a.optDouble(it, 0.0).toFloat() },
            ),
        )
    }

    // ② 每项都是 {label,value} 形状 → 视为同一序列的多个点
    val allPointLike = (0 until a.length()).all { i ->
        val o = a.optJSONObject(i) ?: return@all false
        o.has("value") && !o.has("points") && !o.has("values")
    }
    if (allPointLike) {
        return listOf(
            CardData.Series(
                name = "",
                points = (0 until a.length()).map { a.optJSONObject(it)?.optDouble("value", 0.0)?.toFloat() ?: 0f },
                labels = (0 until a.length()).map {
                    a.optJSONObject(it)?.optString("label", "")?.ifBlank { a.optJSONObject(it)?.optString("name", "") } ?: ""
                },
            ),
        )
    }

    // ① 标准多序列
    val out = mutableListOf<CardData.Series>()
    for (i in 0 until a.length()) {
        val s = a.optJSONObject(i) ?: continue
        var points = parseFloatList(s.optJSONArray("points") ?: s.optJSONArray("values"))
        if (points.isEmpty() && s.has("value")) points = listOf(s.optDouble("value", 0.0).toFloat())
        out.add(
            CardData.Series(
                name = s.optString("name").ifBlank { s.optString("label") }.ifBlank { s.optString("title") },
                color = colorTokenOf(s.optString("color", "primary")),
                points = points,
                labels = parseStringList(s.optJSONArray("labels") ?: s.optJSONArray("categories")),
            ),
        )
    }
    return out
}

private fun parseAxis(o: JSONObject?): CardData.AxisConfig {
    if (o == null) return CardData.AxisConfig()
    return CardData.AxisConfig(
        showX = o.optBoolean("showX", true),
        showY = o.optBoolean("showY", true),
        yMin = if (o.has("yMin")) o.optDouble("yMin", 0.0).toFloat() else null,
        yMax = if (o.has("yMax")) o.optDouble("yMax", 1.0).toFloat() else null,
    )
}

private fun parseTimeline(a: JSONArray?): List<CardData.TimelineItem> {
    if (a == null) return emptyList()
    val out = mutableListOf<CardData.TimelineItem>()
    for (i in 0 until a.length()) {
        val it = a.optJSONObject(i) ?: continue
        out.add(
            CardData.TimelineItem(
                time = it.optString("time", ""),
                text = it.optString("text", ""),
                done = it.optBoolean("done", false),
            ),
        )
    }
    return out
}

private fun parseButtons(a: JSONArray?): List<CardData.ButtonSpec> {
    if (a == null) return emptyList()
    val out = mutableListOf<CardData.ButtonSpec>()
    for (i in 0 until a.length()) {
        val b = a.optJSONObject(i) ?: continue
        out.add(
            CardData.ButtonSpec(
                label = b.optString("label", ""),
                action = parseAction(b.optJSONObject("action")) ?: Action(type = "callback"),
                variant = b.optString("variant", "filled").ifBlank { "filled" },
            ),
        )
    }
    return out
}

private fun parseFields(a: JSONArray?): List<CardData.FieldSpec> {
    if (a == null) return emptyList()
    val out = mutableListOf<CardData.FieldSpec>()
    for (i in 0 until a.length()) {
        val f = a.optJSONObject(i) ?: continue
        out.add(
            CardData.FieldSpec(
                key = f.optString("key", ""),
                label = f.optString("label", ""),
                placeholder = f.optString("placeholder", ""),
                value = f.optString("value", ""),
            ),
        )
    }
    return out
}

private fun parseActions(a: JSONArray?): List<Action> {
    if (a == null) return emptyList()
    val out = mutableListOf<Action>()
    for (i in 0 until a.length()) {
        val o = a.optJSONObject(i) ?: continue
        parseAction(o)?.let { out.add(it) }
    }
    return out
}

private fun parseAction(o: JSONObject?): Action? {
    if (o == null) return null
    return Action(
        type = o.optString("type", ""),
        name = o.optString("name").ifBlank { null },
        url = o.optString("url").ifBlank { null },
        appName = o.optString("appName").ifBlank { null },
        payload = toMap(o.optJSONObject("payload")),
    )
}

private fun parseStyle(o: JSONObject?): StyleToken {
    if (o == null) return StyleToken()
    return StyleToken(
        bg = colorTokenOf(o.optString("bg", "surface")),
        fg = colorTokenOf(o.optString("fg", "onSurface")),
        accent = colorTokenOf(o.optString("accent", "primary")),
        cornerDp = o.optDouble("cornerDp", 12.0).toFloat(),
        paddingDp = o.optDouble("paddingDp", 12.0).toFloat(),
        fontSizeSp = o.optDouble("fontSizeSp", 14.0).toFloat(),
        fontWeight = o.optInt("fontWeight", 400),
    )
}

private fun parseA11y(o: JSONObject?): A11y {
    if (o == null) return A11y()
    return A11y(
        role = o.optString("role").ifBlank { null },
        label = o.optString("label").ifBlank { null },
        hint = o.optString("hint").ifBlank { null },
        state = o.optString("state").ifBlank { null },
    )
}

/**
 * 布局节点解析。
 *
 * 🔴 2026-11-02 修：原来 `props` **只**从 `"props"` 子对象读，`type` 也不做大小写归一。
 * AI 写自定义布局时 99% 是把字段**平铺**在节点上（`{"type":"text","text":"86%","fontSize":32}`），
 * 于是 text/color/fontSize/gradient/value/radius/padding/gap 全部丢失 →
 * 卡片只剩骨架、内容空白（用户反馈「可视化小卡片不完整」的根因之一）。
 *
 * 现在：节点上除结构性键（type/id/weight/...）以外的**所有平铺字段都并入 props**，
 * 显式 `props` 子对象优先覆盖。节点 type 统一 trim+lowercase，并把常见同义词归一
 * （progress→bar、container/view/div→column、label→text 等），避免渲染器 when 匹配不上。
 */
private fun parseLayoutNode(o: JSONObject?): LayoutNode? {
    if (o == null) return null

    // 结构性键：不并入 props（它们有专门字段）
    val structural = setOf(
        "type", "id", "weight", "widthDp", "heightDp", "flex",
        "style", "children", "props",
    )
    // 平铺字段 → props（AI 绝大多数时候直接写在节点上）
    val flat = LinkedHashMap<String, Any?>()
    val keys = o.keys()
    while (keys.hasNext()) {
        val k = keys.next()
        if (k !in structural) flat[k] = jsonToPlain(o.opt(k))
    }
    // 显式 props 子对象优先级更高（同名覆盖平铺值）
    val explicit = toMap(o.optJSONObject("props")).mapValues { jsonToPlain(it.value) }
    flat.putAll(explicit)

    return LayoutNode(
        type = normalizeNodeType(o.optString("type", "box")),
        id = o.optString("id").ifBlank { null },
        weight = o.optDouble("weight", 0.0).toFloat(),
        widthDp = if (o.has("widthDp")) o.optDouble("widthDp", 0.0).toFloat() else null,
        heightDp = if (o.has("heightDp")) o.optDouble("heightDp", 0.0).toFloat() else null,
        flex = o.optInt("flex", 0),
        style = parseStyle(o.optJSONObject("style")),
        children = parseLayoutChildren(o.optJSONArray("children")),
        props = flat,
    )
}

/**
 * 布局节点 type 归一：trim + 小写 + 常见同义词映射。
 *
 * 渲染器 `CustomCardView` 的 `when(node.type)` 全是小写字面量，
 * AI 写 `"Column"`/`"Text"` 或写 `progress`/`label` 这些常见别名时
 * 原样比较会落到 `else` → 节点被忽略、布局塌陷。这里一次性对齐。
 */
internal fun normalizeNodeType(raw: String): String {
    val t = raw.trim().lowercase().replace('-', '_')
    return when (t) {
        "" -> "box"
        // 容器同义词
        "container", "view", "div", "vstack", "vertical" -> "column"
        "hstack", "horizontal" -> "row"
        "stack", "frame", "wrapper" -> "column"
        // 叶子同义词
        "label", "span", "p", "heading", "title" -> "text"
        "progress", "progressbar", "progress_bar", "linear_progress" -> "bar"
        "circle", "donut", "arc", "circular_progress", "ring_chart" -> "ring"
        "space", "gap" -> "spacer"
        "line", "hr", "separator" -> "divider"
        else -> t
    }
}

/** 布局节点合法 type 集合（归一后）。用于判断顶层对象能否直接当布局根。 */
private val LAYOUT_NODE_TYPES = setOf(
    "card", "box", "column", "row", "text", "spacer", "divider", "ring", "bar",
    "badge", "chip", "tag", "pill", "button", "icon", "image", "avatar",
    "list", "items", "group", "grid",
)

/** 该字符串（归一后）是否是一个布局节点 type（而非卡片 type 如 custom/metric）。 */
private fun isLayoutNodeType(raw: String): Boolean = normalizeNodeType(raw) in LAYOUT_NODE_TYPES

/**
 * 把 org.json 的容器值转成 Kotlin 原生容器。
 *
 * 🔴 必须转：`JSONObject.opt()` 对数组返回的是 **JSONArray**，而渲染器里
 * `(props["gradient"] as? List<*>)` / `(props["items"] as? List<*>)` 只认 List，
 * 直接放 JSONArray 会让 `as? List` 一律返回 null ——
 * 结果就是**渐变从来没生效过**（用户反馈「以前是渐变，现在没了」）、列表节点画不出条目。
 */
private fun jsonToPlain(v: Any?): Any? = when (v) {
    is org.json.JSONArray -> (0 until v.length()).map { jsonToPlain(v.opt(it)) }
    is JSONObject -> {
        val m = LinkedHashMap<String, Any?>()
        val ks = v.keys()
        while (ks.hasNext()) {
            val k = ks.next()
            m[k] = jsonToPlain(v.opt(k))
        }
        m
    }
    else -> v
}

private fun parseLayoutChildren(a: JSONArray?): List<LayoutNode> {
    if (a == null) return emptyList()
    val out = mutableListOf<LayoutNode>()
    for (i in 0 until a.length()) out.add(parseLayoutNode(a.optJSONObject(i)) ?: continue)
    return out
}

private fun colorTokenOf(s: String): ColorToken {
    return try { ColorToken.valueOf(s.lowercase().replaceFirstChar { it.uppercase() }) } catch (_: Exception) { ColorToken.Primary }
}

private fun parseFloatList(a: JSONArray?): List<Float> {
    if (a == null) return emptyList()
    val out = mutableListOf<Float>()
    for (i in 0 until a.length()) out.add(a.optDouble(i, 0.0).toFloat())
    return out
}

private fun parseStringList(a: JSONArray?): List<String> {
    if (a == null) return emptyList()
    val out = mutableListOf<String>()
    for (i in 0 until a.length()) out.add(a.optString(i, ""))
    return out
}

private fun parseStringMatrix(a: JSONArray?): List<List<String>> {
    if (a == null) return emptyList()
    val out = mutableListOf<List<String>>()
    for (i in 0 until a.length()) out.add(parseStringList(a.optJSONArray(i)))
    return out
}

/** 把 JSONObject 浅转为 Map<String, Any?>（值保持 String/Number/Boolean/List/Map）。 */
private fun toMap(o: JSONObject?): Map<String, Any?> {
    if (o == null) return emptyMap()
    val map = LinkedHashMap<String, Any?>()
    val it = o.keys()
    while (it.hasNext()) {
        val k = it.next()
        map[k] = when (val v = o.get(k)) {
            is JSONObject -> toMap(v)
            is JSONArray -> (0 until v.length()).map { v.get(it) }
            else -> v
        }
    }
    return map
}

/**
 * 按**平铺字段形状**推断小卡片 type，并把外层平铺字段包进标准 data 壳。
 *
 * ## 为什么必须有这层（2026-11-01 真机截图实证）
 *
 * 模型写小卡片最常见的错误不是写错 type，而是**根本不写 type**、字段全平铺：
 * `{"title":"Zorv AI 健康度","value":98,"unit":"/100","trend":"↑ 良好",...}`。
 * 旧逻辑缺 type 直接 return null → 整围栏跳过 → 用户以为小卡片彻底坏了。
 * 这里按「哪些字段同时出现」判型（只判本模块 7 种预设，不臆测其它通道）：
 *  - headers/rows → table
 *  - statusType / (text+progress) → status
 *  - buttons → button_group
 *  - chartType/series → line_chart
 *  - value + (unit|trend|delta) → metric（包成 kind=chart/chartType=metric 的 series[0]）
 *  - layout 树 → custom
 * 都不像 → null（调用方照旧降级，不做无据渲染）。
 */
private fun inferTypeAndWrap(o: JSONObject, forceMetric: Boolean = false): Pair<String, JSONObject>? {
    val data = JSONObject()
    // table：headers / rows
    if (o.has("headers") || o.has("rows")) {
        data.put("kind", "media")
        data.put("mediaType", "table")
        o.optJSONArray("headers")?.let { data.put("headers", it) }
        o.optJSONArray("rows")?.let { data.put("rows", it) }
        return "table" to data
    }
    // status：statusType 或 text+progress
    if (o.has("statusType") || (o.has("text") && o.has("progress"))) {
        data.put("kind", "status")
        data.put("statusType", o.optString("statusType", "progress").ifBlank { "progress" })
        data.put("text", o.optString("text", ""))
        data.put("progress", if (o.has("progress")) o.optDouble("progress", 0.0) else 0.0)
        o.optBoolean("retryable", false).let { if (it) data.put("retryable", true) }
        o.optString("reason").takeIf { it.isNotBlank() }?.let { data.put("reason", it) }
        return "status" to data
    }
    // button_group：buttons
    o.optJSONArray("buttons")?.let {
        data.put("kind", "form")
        data.put("formType", o.optString("formType", "button_group").ifBlank { "button_group" })
        data.put("buttons", it)
        return "button_group" to data
    }
    // line_chart：chartType / series
    val series = o.optJSONArray("series")
    if (o.has("chartType") || series != null) {
        data.put("kind", "chart")
        data.put("chartType", o.optString("chartType", "line").ifBlank { "line" })
        series?.let { data.put("series", it) }
        o.optJSONObject("axis")?.let { data.put("axis", it) }
        return "line_chart" to data
    }
    // metric：value + (unit|trend|delta|trend_color)；type=metric 平铺时 forceMetric 放行
    if ((o.has("value") && (o.has("unit") || o.has("trend") || o.has("delta") || o.has("trend_color"))) || forceMetric) {
        data.put("kind", "chart")
        data.put("chartType", "metric")
        val name = o.optString("title", o.optString("label", "指标")).ifBlank { "指标" }
        // 🔴 value 容错（2026-11-01 残缺卡）：模型高频写 "86%" / "12/15" 这类字符串值。
        // 旧逻辑 optDouble → NaN → point=0 → 渲染成 "0"，用户看到的就是残缺卡。
        //  - "86%"  → point=86，unit 补 "%"
        //  - "12/15" 等非数值串 → point 置 0，原文留给渲染器走 rawJson 兜底直显
        var point = 0.0
        var unit = o.optString("unit", "")
        when (val rawVal = o.opt("value")) {
            is Number -> point = rawVal.toDouble()
            is String -> {
                val m = Regex("^\\s*([0-9]+(?:\\.[0-9]+)?)\\s*%?\\s*$").find(rawVal)
                if (m != null) {
                    point = m.groupValues[1].toDouble()
                    if (rawVal.contains('%') && unit.isBlank()) unit = "%"
                } else if (unit.isBlank()) {
                    unit = rawVal
                }
            }
        }
        val s0 = JSONObject().apply {
            put("name", name)
            put("points", org.json.JSONArray().put(point))
            if (unit.isNotBlank()) put("labels", org.json.JSONArray().put(unit))
        }
        data.put("series", org.json.JSONArray().put(s0))
        return "metric" to data
    }
    // custom：layout 树
    o.optJSONObject("layout")?.let {
        return "custom" to JSONObject().apply { put("kind", "empty") }
    }
    return null
}

// ───────────────────────── 按 type 包壳（同义字段兼容） ─────────────────────────

/**
 * 「type 已知」时的包壳：把散在外层的字段装进标准 `data` 壳，并补上该类型的默认子类型。
 *
 * ## 为什么需要它（而不是只靠字段形状推断）
 *
 * 字段推断只能猜「大概是什么卡」，而 `type` 是**明确的**：模型写 `bar_chart` 时，
 * 即使它没写 `chartType`，也该按柱状画，而不是按形状猜成折线。
 * 这一层同时吃掉模型/组件库里的同义写法：
 *
 * | 场景 | 兼容写法 |
 * | --- | --- |
 * | 图表 | `series` / `segments` / `points` / `values` / `data`；`chartType` / `chart_type` |
 * | 序列 | `[{name,points,labels}]` · `[{label,value}]` · `[1,2,3]` |
 * | 媒体 | `code` / `content`；`lang` / `language`；`images` / `urls`；`mediaType` / `media_type` |
 * | 表单 | `buttons` / `actions`；`options` / `choices`；`fields` |
 * | 状态 | `progress` / `value`（0~1 与 0~100 都归一） |
 *
 * @return 标准 data 壳；该 type 不需要包壳（metric / custom）时返回 null
 */
private fun wrapByType(canonical: String, o: JSONObject): JSONObject? = when (canonical) {
    CardTypes.LINE_CHART, CardTypes.BAR_CHART, CardTypes.AREA_CHART,
    CardTypes.SPARKLINE, CardTypes.PIE_CHART,
    -> chartShell(canonical, o)
    CardTypes.MEDIA, CardTypes.CODE, CardTypes.IMAGES, CardTypes.TABLE -> mediaShell(canonical, o)
    CardTypes.FORM, CardTypes.BUTTON_GROUP -> formShell(canonical, o)
    CardTypes.STATUS, CardTypes.SKELETON -> statusShell(canonical, o)
    else -> null
}

private fun chartShell(canonical: String, o: JSONObject): JSONObject {
    val data = JSONObject()
    data.put("kind", "chart")
    val explicit = o.optString("chartType").ifBlank { o.optString("chart_type") }
    data.put(
        "chartType",
        explicit.ifBlank {
            when (canonical) {
                CardTypes.BAR_CHART -> "bar"
                CardTypes.PIE_CHART -> "pie"
                CardTypes.AREA_CHART -> "area"
                CardTypes.SPARKLINE -> "sparkline"
                else -> "line"
            }
        },
    )
    val raw = o.optJSONArray("series")
        ?: o.optJSONArray("segments")
        ?: o.optJSONArray("points")
        ?: o.optJSONArray("values")
        ?: o.optJSONArray("data")
    if (raw != null) {
        // 裸点数组 + 顶层 labels → 合成 [{label,value}]，x 轴标签才不会丢
        val labels = o.optJSONArray("labels") ?: o.optJSONArray("categories")
        val isBareNumbers = (0 until raw.length()).all { raw.opt(it) is Number }
        if (isBareNumbers && labels != null) {
            val arr = JSONArray()
            for (i in 0 until raw.length()) {
                arr.put(
                    JSONObject().apply {
                        put("label", labels.optString(i, ""))
                        put("value", raw.optDouble(i, 0.0))
                    },
                )
            }
            data.put("series", arr)
        } else {
            data.put("series", raw)
        }
    }
    o.optJSONObject("axis")?.let { data.put("axis", it) }
    return data
}

private fun mediaShell(canonical: String, o: JSONObject): JSONObject {
    val data = JSONObject()
    data.put("kind", "media")
    val mediaType = when (canonical) {
        CardTypes.CODE -> "code"
        CardTypes.IMAGES -> "image_group"
        CardTypes.TABLE -> "table"
        else -> o.optString("mediaType").ifBlank { o.optString("media_type") }.ifBlank {
            when {
                o.has("code") || o.has("content") -> "code"
                o.has("images") || o.has("urls") -> "image_group"
                o.has("items") -> "timeline"
                else -> "table"
            }
        }
    }
    data.put("mediaType", mediaType)
    o.optJSONArray("rows")?.let { data.put("rows", it) }
    o.optJSONArray("headers")?.let { data.put("headers", it) }
    val code = o.optString("code").ifBlank { o.optString("content") }
    if (code.isNotBlank()) data.put("code", code)
    val lang = o.optString("lang").ifBlank { o.optString("language") }
    if (lang.isNotBlank()) data.put("lang", lang)
    (o.optJSONArray("images") ?: o.optJSONArray("urls"))?.let { data.put("images", it) }
    o.optJSONArray("items")?.let { data.put("items", it) }
    return data
}

private fun formShell(canonical: String, o: JSONObject): JSONObject {
    val data = JSONObject()
    data.put("kind", "form")
    data.put(
        "formType",
        if (canonical == CardTypes.BUTTON_GROUP) "button_group"
        else o.optString("formType").ifBlank { o.optString("form_type") }.ifBlank { "form" },
    )
    (o.optJSONArray("buttons") ?: o.optJSONArray("actions"))?.let { data.put("buttons", it) }
    o.optJSONArray("fields")?.let { data.put("fields", it) }
    (o.optJSONArray("options") ?: o.optJSONArray("choices"))?.let { data.put("options", it) }
    o.optJSONArray("selected")?.let { data.put("selected", it) }
    if (o.has("min")) data.put("min", o.optDouble("min", 0.0))
    if (o.has("max")) data.put("max", o.optDouble("max", 100.0))
    if (o.has("value")) data.put("value", o.optDouble("value", 0.0))
    return data
}

private fun statusShell(canonical: String, o: JSONObject): JSONObject {
    val data = JSONObject()
    data.put("kind", "status")
    data.put(
        "statusType",
        if (canonical == CardTypes.SKELETON) "skeleton"
        else o.optString("statusType").ifBlank { o.optString("status_type") }.ifBlank { "progress" },
    )
    data.put("text", o.optString("text").ifBlank { o.optString("label") })
    data.put("progress", normalizeProgress(o.optDouble("progress", o.optDouble("value", 0.0))))
    if (o.optBoolean("retryable", false)) data.put("retryable", true)
    o.optString("reason").takeIf { it.isNotBlank() }?.let { data.put("reason", it) }
    return data
}

/** 进度归一：0~100 与 0~1 两种写法统一成 0~1（渲染层只认 0~1）。 */
private fun normalizeProgress(v: Double): Double = when {
    v > 1.0 -> (v / 100.0).coerceIn(0.0, 1.0)
    v < 0.0 -> 0.0
    else -> v
}
