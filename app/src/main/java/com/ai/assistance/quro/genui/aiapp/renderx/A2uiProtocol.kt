package com.ai.assistance.quro.genui.aiapp.renderx

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * A2UI 官方协议 → FlatDoc 适配层（ZorvAI 侧适配）
 *
 * 背景：GenUI Agent 的 A2UI 通道原本只认自造的「扁平邻接表」（顶层 root + components），
 * 而模型被 "a2ui" 一词触发时，输出的多半是 **A2UI 官方协议**（a2ui.org 规范）：
 *   - v0.9：createSurface / updateComponents / updateDataModel / deleteSurface（JSONL，一行一条）
 *   - v0.8：surfaceUpdate / dataModelUpdate / beginRendering（组件为 {id, component:{Type:{...}}} 嵌套写法）
 * 两者结构完全不同，原解析器取不到顶层 root 即返回 null → 通道打不开 → 「A2UI 不行」。
 *
 * 本适配层把官方协议（JSONL / 对象数组 / 单对象信封，含 v0.8 与 v0.9 两种组件写法、
 * 数据模型指针绑定）翻译成 FlatDoc，复用既有的 FlatDocRenderer / GenUI SDK 渲染。
 * 同时兼容 ZorvAI 自有方言：createSurface.root 直接是一棵嵌套节点树（quro-ui 风格）。
 */
object A2uiProtocol {

    fun toFlatDoc(content: String): FlatDoc? = runCatching { Impl(content).build() }.getOrNull()

    private val ENVELOPE_KEYS = setOf(
        "surfaceUpdate", "updateComponents", "beginRendering", "createSurface",
        "dataModelUpdate", "updateDataModel", "deleteSurface"
    )

    /** 结构字段：不作为 props 透传（由 kids/type/text 单独解析） */
    private val STRUCT_KEYS = setOf(
        "id", "component", "children", "kids", "child", "childId", "checks", "styles",
        "type", "t", "props", "componentId", "dataBinding"
    )

    private val HEADING_NAMES = setOf(
        "heading", "heading1", "heading2", "heading3", "heading4", "heading5", "heading6",
        "h1", "h2", "h3", "h4", "h5", "h6", "title", "headline", "headline1",
        "display", "display1", "display2", "display3"
    )

    /** 官方目录组件名 → A2UI 通道白名单类型 */
    private val TYPE_MAP = mapOf(
        // 文本
        "text" to "text", "label" to "text", "caption" to "text", "body" to "text",
        "paragraph" to "text", "markdown" to "text", "richtext" to "text", "copy" to "text",
        "subtitle" to "text", "subhead" to "text", "overline" to "text", "quote" to "text",
        "code" to "text", "codeblock" to "text", "link" to "button", "linkbutton" to "button",
        "textbutton" to "button",
        // 布局
        "column" to "column", "vstack" to "column", "list" to "column", "listview" to "column",
        "flow" to "column", "wrap" to "column", "stack" to "column", "table" to "column",
        "datatable" to "column", "tabs" to "column", "tabbar" to "column", "timeline" to "column",
        "grid" to "column", "chart" to "column", "accordion" to "column", "multiplechoice" to "column",
        "row" to "row", "hstack" to "row", "buttonrow" to "row", "chiprow" to "row",
        "scroll" to "scroll", "scrollview" to "scroll", "scrollable" to "scroll",
        "card" to "card", "surface" to "card", "panel" to "card", "container" to "card",
        "box" to "card", "sheet" to "card", "bottomsheet" to "card", "modal" to "card",
        "dialog" to "card", "expander" to "card", "video" to "card", "audioplayer" to "card",
        // 分隔 / 占位
        "divider" to "divider", "separator" to "divider", "hr" to "divider", "rule" to "divider",
        "horizontaldivider" to "divider", "spacer" to "spacer", "gap" to "spacer",
        // 媒体
        "image" to "image", "img" to "image", "picture" to "image", "avatar" to "image",
        "imageurl" to "image", "photo" to "image", "icon" to "chip",
        // 交互
        "button" to "button", "filledbutton" to "button", "outlinedbutton" to "button",
        "elevatedbutton" to "button", "tonalbutton" to "button", "iconbutton" to "button",
        "fab" to "button", "actionchip" to "button", "action" to "button", "submit" to "button",
        "chip" to "chip", "tag" to "chip", "badge" to "chip", "pill" to "chip",
        "filterchip" to "chip",
        // 输入
        "textfield" to "input", "textinput" to "input", "input" to "input", "inputfield" to "input",
        "textbox" to "input", "textarea" to "input", "formfield" to "input", "searchfield" to "input",
        "checkbox" to "input", "checkboxgroup" to "input", "switch" to "input", "toggle" to "input",
        "slider" to "input", "radiobutton" to "input", "radio" to "input", "select" to "input",
        "dropdown" to "input", "datepicker" to "input", "datetimeinput" to "input",
        "autocomplete" to "input", "picker" to "input",
        // 进度
        "progress" to "progress", "progressbar" to "progress", "linearprogress" to "progress",
        "circularprogress" to "progress", "meter" to "progress", "rating" to "progress"
    )

    private class Impl(private val content: String) {
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }
        private val nodes = LinkedHashMap<String, FlatNode>()

        /** 数据模型（updateDataModel / dataModelUpdate），按 JSON Pointer 归档，供 {path:"/x"} 取值 */
        private val pointerMap = LinkedHashMap<String, JsonElement>()

        private var declaredRoot: String? = null
        private var nestedRoot: JsonObject? = null

        fun build(): FlatDoc? {
            val msgs = collect()
            if (msgs.isEmpty() || !looksA2ui(msgs)) return null

            // 第一趟：数据模型（updateDataModel 常排在报文末尾，组件里 {path:"/x"} 取值必须提前备好）
            msgs.forEach { m ->
                (m["updateDataModel"] as? JsonObject)?.let { dm ->
                    val v = dm["value"]
                    val path = bound(dm["path"])
                    val p = if (path.isBlank()) "/" else "/" + path.trim().trim('/')
                    if (v is JsonObject && (p == "/" || p.isEmpty())) {
                        // 根对象：逐键摊平，同时保留整棵
                        v.forEach { (k, e) -> pointerMap["/" + k] = e }
                        pointerMap["/"] = v
                    } else if (v != null) {
                        pointerMap[p] = v
                    }
                }
                (m["dataModelUpdate"] as? JsonObject)?.let { dm ->
                    mergeLegacyContents(dm["contents"])
                }
            }

            // 第二趟：组件 + 根声明
            msgs.forEach { m ->
                // 组件定义：v0.9 updateComponents / v0.8 surfaceUpdate（也兼容裸 components）
                (m["surfaceUpdate"] ?: m["updateComponents"] ?: m["components"]?.takeIf { it is JsonArray })?.let { env ->
                    addComponents((env as? JsonObject)?.get("components") ?: env)
                }
                // 根组件：v0.8 beginRendering.root / v0.9 约定 id == "root"
                (m["beginRendering"] as? JsonObject)?.get("root")?.let { r ->
                    bound(r).ifBlank { null }?.let { declaredRoot = it }
                }
                (m["createSurface"] as? JsonObject)?.let { cs ->
                    when (val r = cs["root"]) {
                        // ZorvAI 自有方言：root 直接是一棵嵌套节点树（quro-ui 风格）
                        is JsonObject -> nestedRoot = r
                        else -> bound(r).ifBlank { null }?.let { declaredRoot = it }
                    }
                }
                // 模型简写：顶层 root 字段
                m["root"]?.takeIf { it !is JsonObject }?.let { r ->
                    bound(r).ifBlank { null }?.let { declaredRoot = it }
                }
            }

            // root 是嵌套树 → 复用扁平解析器的树形展开
            nestedRoot?.let { r -> return FlatDoc.flatFromJson(JsonObject(mapOf("root" to r))) }

            if (nodes.isEmpty()) return null
            val root = resolveRoot() ?: return null
            return FlatDoc(root, nodes)
        }

        // ------------------------------------------------------------ 报文收集

        private fun collect(): List<JsonObject> {
            // 三条路子都走一遍再择优——JSONL 报文常常被模型「漂亮打印」成多行，
            // 逐行解析会全灭；而整体 lenient 解析又会把行尾多余内容悄悄截断，只留下一封信。
            val scanned = scanObjects()
            val byLine = parseLines()
            val whole = parseWhole()

            listOf(scanned, byLine, whole).forEach { c ->
                if (c.any(::hasEnvelope)) return c
            }
            return when {
                whole.isNotEmpty() -> whole
                byLine.isNotEmpty() -> byLine
                else -> scanned
            }
        }

        private fun parseWhole(): List<JsonObject> {
            val out = mutableListOf<JsonObject>()
            runCatching { json.parseToJsonElement(content) }.getOrNull()?.let { unwrap(it, out) }
            return out
        }

        private fun parseLines(): List<JsonObject> {
            val out = mutableListOf<JsonObject>()
            content.lines().forEach { raw ->
                val t = raw.trim().removeSuffix(",")
                if (t.startsWith("{")) {
                    runCatching { json.parseToJsonElement(t) }.getOrNull()?.let {
                        if (it is JsonObject) out.add(it)
                    }
                }
            }
            return out
        }

        private fun unwrap(el: JsonElement?, out: MutableList<JsonObject>) {
            when (el) {
                is JsonArray -> el.forEach { unwrap(it, out) }
                is JsonObject -> {
                    val boxed = listOf("messages", "a2ui", "payload").mapNotNull { el[it] }.firstOrNull()
                    if (boxed != null && !hasEnvelope(el)) unwrap(boxed, out) else out.add(el)
                }
                else -> {}
            }
        }

        private fun hasEnvelope(o: JsonObject): Boolean = o.keys.any { it in ENVELOPE_KEYS }

        private fun looksA2ui(msgs: List<JsonObject>): Boolean = msgs.any { m ->
            hasEnvelope(m) || (m["components"] as? JsonArray)?.any { c ->
                c is JsonObject && c.containsKey("component")
            } == true
        }

        private fun scanObjects(): List<JsonObject> {
            val out = mutableListOf<JsonObject>()
            var depth = 0
            var start = -1
            var inStr = false
            var esc = false
            content.forEachIndexed { i, ch ->
                if (inStr) {
                    when {
                        esc -> esc = false
                        ch == '\\' -> esc = true
                        ch == '"' -> inStr = false
                    }
                    return@forEachIndexed
                }
                when (ch) {
                    '"' -> inStr = true
                    '{' -> {
                        if (depth == 0) start = i
                        depth++
                    }
                    '}' -> {
                        depth--
                        if (depth == 0 && start >= 0) {
                            val seg = content.substring(start, i + 1)
                            runCatching { json.parseToJsonElement(seg) }.getOrNull()?.let {
                                if (it is JsonObject) out.add(it)
                            }
                            start = -1
                        }
                    }
                }
            }
            return out
        }

        // ------------------------------------------------------------ 组件翻译

        private fun addComponents(comps: JsonElement?) {
            when (comps) {
                is JsonArray -> comps.forEach { (it as? JsonObject)?.let { o -> addNode(withId(o)) } }
                is JsonObject -> comps.forEach { (id, v) ->
                    (v as? JsonObject)?.let { o -> addNode(withId(o, id)) }
                }
                else -> {}
            }
        }

        private fun withId(o: JsonObject, fallback: String? = null): JsonObject {
            val id = bound(o["id"]).ifBlank { fallback ?: "" }
            if (id.isNotBlank()) return o
            return JsonObject(o + ("id" to JsonPrimitive(fallback ?: ("n" + nodes.size))))
        }

        private fun addNode(o: JsonObject) {
            val id = bound(o["id"]).ifBlank { "n" + nodes.size }
            if (nodes.containsKey(id)) return

            // 类型名 + 属性体：v0.9 扁平（component:"Text" + 同级属性）/ v0.8 嵌套（component:{"Text":{...}}）
            var typeName = ""
            var body: JsonObject = o
            when (val c = o["component"]) {
                is JsonPrimitive -> typeName = c.content
                is JsonObject -> {
                    typeName = c.keys.firstOrNull().orEmpty()
                    body = (c[typeName] as? JsonObject) ?: JsonObject(emptyMap())
                }
                else -> {}
            }
            if (typeName.isBlank()) typeName = bound(o["type"] ?: o["t"])

            val kids = kidsOf(body).ifEmpty { kidsOf(o) }
            val type = mapType(typeName, kids.isNotEmpty(), body, o)
            val text = textOf(body).ifBlank { textOf(o) }
            nodes[id] = FlatNode(id, type, text, kids, propsOf(body, o))

            // 内联嵌套子节点（children 里直接放完整对象）
            (body["children"] ?: o["children"])?.let { ch ->
                (ch as? JsonArray)?.forEachIndexed { i, k ->
                    if (k is JsonObject) addNode(withId(k, id + "_" + i))
                }
            }
        }

        private fun mapType(raw: String, hasKids: Boolean, vararg bodies: JsonObject): String {
            val n = raw.lowercase().replace("_", "").replace("-", "").replace(" ", "")
            if (n.isBlank()) return if (hasKids) "column" else "text"
            if (n in HEADING_NAMES) return "heading"
            if (bodies.any { headingLevel(it) > 0 }) return "heading"
            TYPE_MAP[n]?.let { return it }
            // 供应商前缀 / 后缀（WButton / ButtonComponent / ui-text）
            val stripped = n.removePrefix("w").removePrefix("ui").removePrefix("x").removeSuffix("component")
            TYPE_MAP[stripped]?.let { return it }
            // 未知类型：有子节点当容器渲染（不丢内容），无子节点当文本
            return if (hasKids) "column" else "text"
        }

        private fun headingLevel(o: JsonObject): Int {
            bound(o["level"] ?: o["headingLevel"]).toIntOrNull()?.let { if (it in 1..6) return it }
            val hint = bound(o["usageHint"] ?: o["variant"]).lowercase()
            return when {
                hint == "h1" || hint == "title" || hint == "headline" || hint == "display" -> 1
                hint == "h2" || hint == "heading" -> 2
                hint == "h3" -> 3
                hint == "h4" -> 4
                hint == "h5" -> 5
                hint == "h6" -> 6
                else -> 0
            }
        }

        private fun kidsOf(o: JsonObject): List<String> {
            val out = mutableListOf<String>()
            when (val c = o["children"] ?: o["kids"]) {
                is JsonArray -> c.forEach { bound(it).takeIf { s -> s.isNotBlank() }?.let(out::add) }
                is JsonObject -> {
                    (c["explicitList"] as? JsonArray)?.forEach { bound(it).takeIf { s -> s.isNotBlank() }?.let(out::add) }
                    // v0.8 List 模板：{template:{componentId:"x", dataBinding:"/items"}}
                    (c["template"] as? JsonObject)?.let { t ->
                        bound(t["componentId"]).takeIf { s -> s.isNotBlank() }?.let(out::add)
                    }
                }
                is JsonPrimitive -> c.content.takeIf { it.isNotBlank() }?.let(out::add)
                else -> {}
            }
            if (out.isEmpty()) {
                bound(o["child"] ?: o["childId"]).takeIf { s -> s.isNotBlank() }?.let(out::add)
            }
            return out
        }

        private fun textOf(o: JsonObject): String {
            val keys = listOf(
                "text", "label", "title", "value", "content", "placeholder",
                "caption", "description", "message", "heading", "name", "alt"
            )
            keys.forEach { k ->
                val s = bound(o[k])
                if (s.isNotBlank()) return s
            }
            return ""
        }

        /** 归一 props：别名映射 + 保留原始标量，供 FlatDocRenderer / GenUI 样式解析 */
        private fun propsOf(vararg os: JsonObject): Map<String, String> {
            val out = LinkedHashMap<String, String>()
            os.forEach { o ->
                o.forEach { (k, v) ->
                    if (k in STRUCT_KEYS) return@forEach
                    when (val v = o[k]) {
                        is JsonPrimitive -> if (v !is JsonNull && v.content.isNotBlank()) {
                            if (!out.containsKey(k)) out[k] = v.content
                        }
                        is JsonArray -> bound(v).takeIf { it.isNotBlank() }?.let { s ->
                            if (!out.containsKey(k)) out[k] = s
                        }
                        else -> {}
                    }
                }
            }
            val src = os.firstOrNull { it["children"] != null || it["component"] != null } ?: os.first()
            fun pick(vararg keys: String): String {
                keys.forEach { k -> os.forEach { o -> bound(o[k]).takeIf { it.isNotBlank() }?.let { return it } } }
                return ""
            }
            pick("backgroundColor", "bgColor", "bg").let { if (it.isNotBlank()) out["bg"] = it }
            pick("textColor", "foregroundColor", "color").let { if (it.isNotBlank()) out["color"] = it }
            pick("fontSize", "textSize").toFloatOrNull()?.let { out["size"] = it.toString() }
            pick("cornerRadius", "radius", "borderRadius").toFloatOrNull()?.let { out["radius"] = it.toString() }
            val weight = pick("fontWeight")
            if (weight.equals("bold", true) || weight == "700" || weight == "800" || weight == "900") out["bold"] = "true"
            if (bound(src["bold"]).equals("true", true)) out["bold"] = "true"
            val lv = headingLevel(src)
            if (lv > 0) out["level"] = lv.toString()
            pick("value", "progress", "percent").toFloatOrNull()?.let { out["value"] = it.toString() }
            pick("url", "src", "imageUrl", "image").let { if (it.isNotBlank()) out["url"] = it }
            pick("hint", "helperText").let { if (it.isNotBlank()) out["hint"] = it }
            actionOf(os).let { if (it.isNotBlank()) out["action"] = it }
            return out
        }

        private fun actionOf(os: Array<out JsonObject>): String {
            os.forEach { o ->
                when (val a = o["action"]) {
                    is JsonPrimitive -> if (a !is JsonNull && a.content.isNotBlank()) return a.content
                    is JsonObject -> {
                        (a["event"] as? JsonObject)?.let { e ->
                            bound(e["name"]).takeIf { it.isNotBlank() }?.let { return it }
                        }
                        listOf("name", "action", "handlerId", "handler", "id").forEach { k ->
                            bound(a[k]).takeIf { it.isNotBlank() }?.let { return it }
                        }
                    }
                    else -> {}
                }
                bound(o["onClick"] ?: o["onTap"]).takeIf { it.isNotBlank() }?.let { return it }
            }
            return ""
        }

        // ------------------------------------------------------------ 根节点解析

        private fun resolveRoot(): String? {
            declaredRoot?.takeIf { nodes.containsKey(it) }?.let { return it }
            nodes["root"]?.let { return "root" }                       // v0.9 约定：根组件 id == "root"
            val referenced = nodes.values.flatMap { it.kids }.toSet()
            val candidates = nodes.keys.filter { it !in referenced }
            candidates.firstOrNull { (nodes[it]?.kids?.size ?: 0) > 0 }?.let { return it }
            if (candidates.size == 1) return candidates.first()
            return candidates.firstOrNull() ?: nodes.keys.firstOrNull()
        }

        // ------------------------------------------------------------ 取值工具

        /** 取标量值：支持字面量、v0.8 的 {literalString:...}、数据模型指针 {path:"/x"} */
        private fun bound(e: JsonElement?): String = when (e) {
            null, is JsonNull -> ""
            is JsonPrimitive -> e.content
            is JsonObject -> {
                for (k in listOf("literalString", "literalNumber", "literalBoolean", "literal", "text", "value")) {
                    val v = e[k]
                    if (v != null && v !is JsonNull && v !is JsonObject) return bound(v)
                }
                bound(e["path"]).takeIf { it.isNotBlank() }?.let { p -> return resolvePointer(p) }
                ""
            }
            is JsonArray -> e.mapNotNull { bound(it).takeIf { s -> s.isNotBlank() } }.joinToString(", ")
        }

        private fun resolvePointer(path: String): String {
            val norm = "/" + path.trim().trim('/')
            pointerMap[norm]?.let { return bound(it) }
            // 落到根对象里按层级下钻（updateDataModel path="/" 的常见形态）
            var cur: JsonElement = pointerMap["/"] ?: return ""
            norm.trim('/').split("/").filter { it.isNotBlank() }.forEach { s ->
                cur = when (val c = cur) {
                    is JsonObject -> c[s] ?: return ""
                    is JsonArray -> c.getOrNull(s.toIntOrNull() ?: -1) ?: return ""
                    else -> return ""
                }
            }
            return bound(cur)
        }

        /** v0.8 dataModelUpdate.contents：[{key, valueString|valueNumber|valueMap|...}] → 数据模型 */
        private fun mergeLegacyContents(contents: JsonElement?) {
            val arr = contents as? JsonArray ?: return
            arr.forEach { row ->
                val o = row as? JsonObject ?: return@forEach
                val key = bound(o["key"])
                if (key.isBlank()) return@forEach
                val v = listOf(
                    "valueString", "valueNumber", "valueInt", "valueBoolean", "valueMap", "valueList", "value"
                ).firstNotNullOfOrNull { o[it] } ?: return@forEach
                pointerMap["/" + key] = if (v is JsonArray) JsonArray(v.map { legacyToJson(it) }) else legacyToJson(v)
            }
        }

        private fun legacyToJson(e: JsonElement): JsonElement {
            val o = e as? JsonObject ?: return e
            if (!o.containsKey("key")) return o
            val map = LinkedHashMap<String, JsonElement>()
            (o["valueMap"] as? JsonArray)?.forEach { item ->
                val io = item as? JsonObject ?: return@forEach
                val k = bound(io["key"])
                if (k.isBlank()) return@forEach
                map[k] = listOf("valueString", "valueNumber", "valueInt", "valueBoolean", "valueMap", "valueList")
                    .firstNotNullOfOrNull { io[it] }?.let { legacyToJson(it) } ?: JsonNull
            }
            return JsonObject(map)
        }
    }
}
