package com.ai.assistance.quro.genui.aiapp.renderx

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * A2UI 风格扁平邻接表（v4 兼容三种模型写法）：
 * ① components 为数组 [{id,t,text|props,kids|children}]
 * ② components 为对象 {id:{t,text|props,children}}（多数模型偏好）
 * ③ root 可为 id 字符串或完整节点对象
 * 文本兼容顶层 text 与 props.text；层级兼容 kids 与 children。
 */
data class FlatNode(
    val id: String,
    val type: String,
    val text: String = "",
    val kids: List<String> = emptyList(),
    val props: Map<String, String> = emptyMap()
)

data class FlatDoc(
    val root: String,
    val nodes: Map<String, FlatNode>
) {
    companion object {
        val json = Json { ignoreUnknownKeys = true; isLenient = true }

        private val whitelist = setOf(
            "text", "heading", "column", "row", "scroll", "card", "button",
            "divider", "spacer", "image", "progress", "chip", "input",
            "h1", "h2", "h3", "sub", "title", "line", "panel"
        )

        private val typeAlias = mapOf(
            "h1" to "heading", "h2" to "heading", "h3" to "heading",
            "title" to "heading", "sub" to "text", "subtitle" to "text",
            "label" to "chip", "line" to "text", "panel" to "card",
            "container" to "card", "list" to "column", "btn" to "button",
            "caption" to "text", "body" to "text", "paragraph" to "text"
        )

        private fun normType(raw: String, hasKids: Boolean = false): String {
            val t = raw.lowercase().trim()
            return when {
                t.isBlank() -> if (hasKids) "column" else "text"
                t in whitelist -> t
                typeAlias.containsKey(t) -> typeAlias[t]!!
                // 未知类型但有子节点 → 当容器渲染，避免「丢内容」
                hasKids -> "column"
                else -> "text"
            }
        }

        private fun flatten(o: JsonObject, topLevelProps: Boolean = false): Triple<String, String, Map<String, String>> {
            // 结构键不算属性：type/id/children/kids/props 是骨架，混进 props 会污染样式解析
            val structural = setOf("type", "t", "id", "children", "kids", "props", "items")
            val own = if (topLevelProps) {
                o.filterKeys { it !in structural }.mapValues { (_, v) -> primStr(v) }
            } else emptyMap()
            val props = own + ((o["props"] as? JsonObject)?.mapValues { (_, v) -> primStr(v) } ?: emptyMap())
            // 文本落点兼容：text / props.text / title / content / value
            //  —— `value` 是模型高频写法（与 A2UI 的 `text` 等价），此前不认会让整块文字渲染成空白。
            val text = o["text"]?.let { primStr(it) }
                ?: props["text"] ?: props["title"] ?: props["content"] ?: props["value"]
                ?: ""
            return Triple(o["t"]?.let { primStr(it) } ?: o["type"]?.let { primStr(it) } ?: "text", text, props)
        }

        private fun primStr(e: JsonElement): String = (e as? JsonPrimitive)?.content ?: ""

        private fun kidsOf(o: JsonObject): List<String> =
            ((o["kids"] ?: o["children"]) as? JsonArray)?.mapNotNull { primStr(it.takeIf { n -> n is JsonPrimitive } ?: JsonPrimitive("")) }
                ?: emptyList()

        fun parse(content: String, isYaml: Boolean): FlatDoc? {
            // ① A2UI 官方协议适配（createSurface / updateComponents / surfaceUpdate / beginRendering，
            //    支持 JSONL 逐行报文、v0.8 嵌套写法与 v0.9 扁平写法、数据模型指针绑定）
            A2uiProtocol.toFlatDoc(content)?.let { return it }
            val el: JsonElement = runCatching {
                if (isYaml) yamlToJson(content) else json.parseToJsonElement(content)
            }.getOrNull() ?: return null
            // ② 本家扁平邻接表（root + components）
            (el as? JsonObject)?.let { obj -> runCatching { flatFromJson(obj) }.getOrNull()?.let { return it } }
            // ③ 裸嵌套节点树：顶层直接就是 {type, children:[...]}，既没有 root 也没有 components。
            //    这是模型被 "a2ui" 触发时最常写的一种形状，此前三处都不认 →
            //    整篇解析失败 → 通道退化成「原文画布」→ 用户看到的是一屏 JSON 源码而不是界面。
            return bareTreeToFlatDoc(el)
        }

        /**
         * ③ 裸嵌套节点树 → 扁平邻接表。
         *
         * 形状（模型高频写法，子节点内联，无任何信封）：
         * ```
         * {"type":"column","spacing":14,"children":[
         *    {"type":"card","title":"…","backgroundColor":"#6C5CE7","children":[ … ]}
         * ]}
         * ```
         * 与另外两种的区别：不是 `{root,components}` 邻接表，也不是 `createSurface` 报文。
         * 顶层也可能是数组（此时按 column 包一层）。
         *
         * 在此路径上额外做两件事（模型这种写法特有的保真处理）：
         *  · 节点属性取**顶层标量**（title/padding/corner_radius/backgroundColor…），
         *    它们的写法是平铺的，不在 props 里，只读 props 会把卡片标题、底色全丢掉；
         *  · `items:["a","b"]`（裸字符串数组）合成 text 子节点，否则列表内容整块消失。
         */
        private fun bareTreeToFlatDoc(el: JsonElement): FlatDoc? {
            val rootObj: JsonObject = when (el) {
                is JsonObject -> el
                is JsonArray -> JsonObject(linkedMapOf<String, JsonElement>(
                    "type" to JsonPrimitive("column"), "children" to el
                ))
                else -> return null
            }
            // 只认「节点」形状：必须带类型键，避免把 messages 信封之类的文档误当成节点树
            if (!(rootObj.containsKey("type") || rootObj.containsKey("t"))) return null

            val nodes = LinkedHashMap<String, FlatNode>()

            fun walk(id: String, o: JsonObject) {
                val (rawT, text, props) = flatten(o, topLevelProps = true)
                val kidsArr = (o["children"] ?: o["kids"]) as? JsonArray
                    ?: ((o["props"] as? JsonObject)?.get("children") as? JsonArray)
                val kids = ArrayList<String>()

                fun kidId(kidEl: JsonElement, idx: Int): String =
                    (kidEl as? JsonObject)?.let { ko ->
                        ko["id"]?.let { primStr(it) }.takeUnless { it.isNullOrBlank() }
                    } ?: (id + "_" + idx)

                // items 为裸字符串数组 → 合成 text 子节点（列表内容不丢）
                val itemsArr = o["items"] as? JsonArray
                if (kidsArr == null && itemsArr != null && itemsArr.all { it is JsonPrimitive }) {
                    itemsArr.forEachIndexed { idx, itemEl ->
                        val sid = id + "_i" + idx
                        kids.add(sid)
                        nodes[sid] = FlatNode(sid, "text", primStr(itemEl), emptyList(), emptyMap())
                    }
                } else {
                    kidsArr?.forEachIndexed { idx, kidEl ->
                        val kId = kidId(kidEl, idx)
                        kids.add(kId)
                        if (kidEl is JsonObject) walk(kId, kidEl)
                        else nodes[kId] = FlatNode(kId, "text", primStr(kidEl), emptyList(), emptyMap())
                    }
                }
                nodes[id] = FlatNode(id, normType(rawT, kids.isNotEmpty()), text, kids, props)
            }

            walk("root", rootObj)
            val r = nodes["root"] ?: return null
            if (r.kids.isEmpty() && r.text.isBlank()) return null
            return FlatDoc("root", nodes)
        }

        /**
         * 对已解析的 JSON 对象做扁平邻接表展开。
         * 供 [parse] 与 A2UI 官方协议适配层（createSurface.root 为嵌套节点树时）复用。
         */
        internal fun flatFromJson(obj: JsonObject): FlatDoc? {
            val nodes = LinkedHashMap<String, FlatNode>()

            // 子节点引用 id：对象元素用显式 id 或 父id_序号（与展平注册完全一致）
            fun kidRef(parentId: String, idx: Int, kidEl: kotlinx.serialization.json.JsonElement): String? = when (kidEl) {
                is JsonObject -> kidEl["id"]?.let { primStr(it) }.takeUnless { it.isNullOrBlank() } ?: (parentId + "_" + idx)
                else -> primStr(kidEl).takeUnless { it.isBlank() }
            }

            fun addNode(id: String, o: JsonObject) {
                val (rawT, text, props) = flatten(o)
                val kidsArr = (o["children"] ?: o["kids"]) as? JsonArray
                var kids = kidsArr?.mapIndexed { idx, el -> kidRef(id, idx, el) }?.filterNotNull() ?: emptyList()
                // 模型可能把 children 写在 props 里
                if (kids.isEmpty()) (o["props"] as? JsonObject)?.let { pp ->
                    (pp["children"] as? JsonArray)?.let { ca ->
                        kids = ca.mapIndexedNotNull { i, el -> kidRef(id, i, el) }
                    }
                }
                val type = normType(rawT, kids.isNotEmpty())
                nodes[id] = FlatNode(id, type, text, kids, props)
                // 树形嵌套：children/kids 里直接放完整节点对象 → 注册为独立节点
                kidsArr?.forEachIndexed { idx, kidEl ->
                    if (kidEl is JsonObject) {
                        val kidId = kidRef(id, idx, kidEl) ?: return@forEachIndexed
                        if (!nodes.containsKey(kidId)) addNode(kidId, kidEl)
                    }
                }
            }

            val rootEl = obj["root"]
            var rootId = ""
            when (rootEl) {
                is JsonPrimitive -> rootId = rootEl.content
                is JsonObject -> {
                    rootId = rootEl["id"]?.let { primStr(it) }.takeUnless { it.isNullOrBlank() } ?: "root"
                    addNode(rootId, rootEl)
                }
                else -> return null
            }

            // components：数组或对象两种形态
            (obj["components"] ?: obj["nodes"])?.let { comps ->
                when (comps) {
                    is JsonArray -> comps.forEach { item ->
                        runCatching {
                            val o = item.jsonObject
                            val id = o["id"]?.let { primStr(it) } ?: return@runCatching
                            addNode(id, o)
                        }
                    }
                    is JsonObject -> comps.forEach { (id, nodeEl) ->
                        runCatching {
                            (nodeEl as? JsonObject)?.let { addNode(id, it) }
                        }
                    }
                    else -> {}
                }
            }

            val rootNode = nodes[rootId]
            if (nodes.isEmpty() || rootNode == null) return null
            if (rootNode.kids.isEmpty() && rootNode.text.isBlank()) return null
            return FlatDoc(rootId, nodes)
        }

        private fun yamlToJson(yaml: String): JsonElement {
            val node = com.charleskorn.kaml.Yaml.default.parseToYamlNode(yaml)
            return convert(node)
        }

        private fun convert(node: com.charleskorn.kaml.YamlNode): JsonElement = when (node) {
            is com.charleskorn.kaml.YamlMap -> {
                val m = LinkedHashMap<String, JsonElement>()
                node.entries.forEach { e ->
                    val key = (e.key as? com.charleskorn.kaml.YamlScalar)?.content ?: ""
                    m[key] = convert(e.value)
                }
                JsonObject(m)
            }
            is com.charleskorn.kaml.YamlList -> JsonArray(node.items.map { convert(it) })
            is com.charleskorn.kaml.YamlNull -> JsonPrimitive("")
            is com.charleskorn.kaml.YamlScalar -> {
                val c = node.content
                when {
                    c == "true" || c == "false" -> JsonPrimitive(c.toBoolean())
                    else -> c.toLongOrNull()?.let { JsonPrimitive(it) }
                        ?: c.toDoubleOrNull()?.let { JsonPrimitive(it) }
                        ?: JsonPrimitive(c)
                }
            }
            else -> JsonPrimitive("")
        }
    }
}
