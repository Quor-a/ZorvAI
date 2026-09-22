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

        private fun flatten(o: JsonObject): Triple<String, String, Map<String, String>> {
            val props = (o["props"] as? JsonObject)?.mapValues { (_, v) -> primStr(v) } ?: emptyMap()
            val text = o["text"]?.let { primStr(it) } ?: props["text"] ?: props["title"] ?: props["content"] ?: ""
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
            // ② 本家扁平邻接表（root + components）
            return runCatching {
                val el: JsonElement = if (isYaml) yamlToJson(content) else json.parseToJsonElement(content)
                flatFromJson(el.jsonObject)
            }.getOrNull()
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
