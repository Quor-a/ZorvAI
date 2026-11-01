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
        } else if (dataObj == null) {
            // type 给了但 data 壳没给（字段平铺在外层）：也包一次壳
            dataObj = inferTypeAndWrap(obj)?.second ?: JSONObject()
        }
        val id = obj.optString("id").ifBlank { "card_" + json.hashCode().toString(36).replace("-", "m") }
        val version = obj.optInt("version", 1)
        val data = parseCardData(dataObj)
        val actions = parseActions(obj.optJSONArray("actions"))
        val style = parseStyle(obj.optJSONObject("style"))
        val a11y = parseA11y(obj.optJSONObject("a11y"))
        val renderHint = obj.optString("renderHint", "canvas").ifBlank { "canvas" }
        val layout = parseLayoutNode(obj.optJSONObject("layout"))
        CardSpec(id, type, version, layout, data, actions, style, a11y, renderHint, rawJson = json)
    } catch (_: Exception) {
        null
    }
}

private fun parseCardData(o: JSONObject?): CardData {
    if (o == null) return CardData.Empty
    return when (o.optString("kind", "").lowercase()) {
        "chart" -> CardData.Chart(
            chartType = o.optString("chartType", "line").ifBlank { "line" },
            series = parseSeries(o.optJSONArray("series")),
            axis = parseAxis(o.optJSONObject("axis")),
        )
        "media" -> CardData.Media(
            mediaType = o.optString("mediaType", "table").ifBlank { "table" },
            rows = parseStringMatrix(o.optJSONArray("rows")),
            headers = parseStringList(o.optJSONArray("headers")),
            code = o.optString("code").ifBlank { null },
            lang = o.optString("lang").ifBlank { null },
            images = parseStringList(o.optJSONArray("images")),
            items = parseTimeline(o.optJSONArray("items")),
        )
        "form" -> CardData.Form(
            formType = o.optString("formType", "button_group").ifBlank { "button_group" },
            buttons = parseButtons(o.optJSONArray("buttons")),
            fields = parseFields(o.optJSONArray("fields")),
            min = o.optDouble("min", 0.0).toFloat(),
            max = o.optDouble("max", 100.0).toFloat(),
            value = o.optDouble("value", 0.0).toFloat(),
            options = parseStringList(o.optJSONArray("options")),
            selected = parseStringList(o.optJSONArray("selected")),
        )
        "status" -> CardData.Status(
            statusType = o.optString("statusType", "skeleton").ifBlank { "skeleton" },
            text = o.optString("text", ""),
            progress = o.optDouble("progress", 0.0).toFloat(),
            retryable = o.optBoolean("retryable", false),
            reason = o.optString("reason").ifBlank { null },
        )
        else -> CardData.Empty
    }
}

private fun parseSeries(a: JSONArray?): List<CardData.Series> {
    if (a == null) return emptyList()
    val out = mutableListOf<CardData.Series>()
    for (i in 0 until a.length()) {
        val s = a.optJSONObject(i) ?: continue
        out.add(
            CardData.Series(
                name = s.optString("name", ""),
                color = colorTokenOf(s.optString("color", "primary")),
                points = parseFloatList(s.optJSONArray("points")),
                labels = parseStringList(s.optJSONArray("labels")),
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

private fun parseLayoutNode(o: JSONObject?): LayoutNode? {
    if (o == null) return null
    return LayoutNode(
        type = o.optString("type", "box").ifBlank { "box" },
        id = o.optString("id").ifBlank { null },
        weight = o.optDouble("weight", 0.0).toFloat(),
        widthDp = if (o.has("widthDp")) o.optDouble("widthDp", 0.0).toFloat() else null,
        heightDp = if (o.has("heightDp")) o.optDouble("heightDp", 0.0).toFloat() else null,
        flex = o.optInt("flex", 0),
        style = parseStyle(o.optJSONObject("style")),
        children = parseLayoutChildren(o.optJSONArray("children")),
        props = toMap(o.optJSONObject("props")).mapValues { it.value },
    )
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
private fun inferTypeAndWrap(o: JSONObject): Pair<String, JSONObject>? {
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
    // metric：value + (unit|trend|delta|trend_color)
    if (o.has("value") && (o.has("unit") || o.has("trend") || o.has("delta") || o.has("trend_color"))) {
        data.put("kind", "chart")
        data.put("chartType", "metric")
        val name = o.optString("title", o.optString("label", "指标")).ifBlank { "指标" }
        val value = when {
            o.optJSONArray("value") != null -> o.optJSONArray("value")?.optDouble(0) ?: 0.0
            else -> o.optDouble("value", Double.NaN)
        }
        val point = if (value.isNaN()) o.optString("value", "0").toDoubleOrNull() ?: 0.0 else value
        val s0 = JSONObject().apply {
            put("name", name)
            put("points", org.json.JSONArray().put(point))
            val unit = o.optString("unit", "")
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
