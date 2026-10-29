package com.ai.assistance.quro.genui.aiapp.renderx

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * A2UI 风格扁平邻接表（v4 兼容四种模型写法）：
 * ① A2UI 官方协议信封（createSurface / updateComponents…，见 [A2uiProtocol]）
 * ② nodes 数组 + parent 反引用（ZorvBrain 提示词教的形状，属性分居 properties/style）
 * ③ root + components 邻接表（本家写法，components 可为数组或对象）
 * ④ 裸嵌套节点树（顶层直接 {type, children:[…]}）
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
            "h4" to "heading", "h5" to "heading", "h6" to "heading",
            "title" to "heading", "sub" to "text", "subtitle" to "text",
            "label" to "chip", "line" to "text", "panel" to "card",
            "container" to "card", "list" to "column", "btn" to "button",
            "caption" to "text", "body" to "text", "paragraph" to "text"
        )

        private fun normType(raw: String, hasKids: Boolean = false): String {
            val t = raw.lowercase().trim()
            if (t.isBlank()) return if (hasKids) "column" else "text"
            // ⚠️ 别名必须先于白名单判断：白名单里同时住着 h1/h2/h3/sub/title/line/panel
            // 这些**别名**，先判 whitelist 会把它们原样留下 → 渲染端没有 h1 分支，
            // 标题/副标题/分隔线全掉进 else 分支当普通文字画出来。上游是别名优先。
            typeAlias[t]?.let { return it }
            if (t in whitelist) return t
            // A2UI 官方目录（header_bar / hero / list_view / text_field / badge…）：
            // nodes 邻接表格式里模型高频直写官方组件名，不映射的话全掉进下面的兜底
            // —— 有子的当 column、没子的当 text，官方组件的语义（头栏/列表/输入框）全丢。
            // 复用 A2uiProtocol 的 TYPE_MAP，一处维护。
            val n = t.replace("_", "").replace("-", "").replace(" ", "")
            A2uiProtocol.TYPE_MAP[n]?.let { return it }
            val stripped = n.removePrefix("w").removePrefix("ui").removePrefix("x").removeSuffix("component")
            if (stripped != n) A2uiProtocol.TYPE_MAP[stripped]?.let { return it }
            // 未知类型但有子节点 → 当容器渲染，避免「丢内容」
            return if (hasKids) "column" else "text"
        }

        /**
         * 结构键不算属性：type/id/children/kids/props 是骨架，混进 props 会污染样式解析。
         * `parent`/`parentId`/`properties`/`style` 同样要排除 —— nodes 邻接表格式里
         * 这四个键的值都是**对象**，旧实现用 primStr 拍平出来是空串，白占键位；
         * 真正的内容在 properties/style 两个子对象里，见下面的合并。
         */
        private val structural = setOf(
            "type", "t", "id", "children", "kids", "props", "items",
            "parent", "parentid", "properties", "style"
        )

        /** 边距类键名（值是对象时按 CSS 边距语义合成字符串） */
        private val edgeKeys = setOf("padding", "margin", "inset", "insets", "spacing")

        private fun flatten(o: JsonObject, topLevelProps: Boolean = false): Triple<String, String, Map<String, String>> {
            val own = if (topLevelProps) {
                val m = LinkedHashMap<String, String>()
                o.forEach { (k, v) ->
                    if (k in structural) return@forEach
                    when (v) {
                        is JsonPrimitive -> if (v !is JsonNull && v.content.isNotBlank()) m[k] = v.content
                        is JsonObject -> flattenNested(k, v).forEach { (nk, nv) -> m[nk] = nv }
                        is JsonArray -> m[k] = v.joinToString(",") { (it as? JsonPrimitive)?.content ?: "" }
                        else -> {}
                    }
                }
                m
            } else emptyMap()
            // props 子对象 + properties/style 两个嵌套对象（nodes 格式的属性分居这两位）。
            // 后者覆盖前者：style 是外观的最终裁决者。
            val props = own +
                ((o["props"] as? JsonObject)?.let { flattenObj(it) } ?: emptyMap()) +
                ((o["properties"] as? JsonObject)?.let { flattenObj(it) } ?: emptyMap()) +
                ((o["style"] as? JsonObject)?.let { styleObj(it) } ?: emptyMap())
            // 文本落点兼容：text / props.text / title / content / value
            //  —— `value` 是模型高频写法（与 A2UI 的 `text` 等价），此前不认会让整块文字渲染成空白。
            val text = o["text"]?.let { primStr(it) }
                ?: props["text"] ?: props["title"] ?: props["content"] ?: props["value"]
                ?: ""
            return Triple(o["t"]?.let { primStr(it) } ?: o["type"]?.let { primStr(it) } ?: "text", text, props)
        }

        /** 普通嵌套对象 → 扁平字符串（标量直取，数组逗号连接，再嵌一层用 "父.子" 键） */
        private fun flattenObj(o: JsonObject): Map<String, String> {
            val out = LinkedHashMap<String, String>()
            o.forEach { (k, v) ->
                when (v) {
                    is JsonPrimitive -> if (v !is JsonNull && v.content.isNotBlank()) out[k] = v.content
                    is JsonObject -> flattenNested(k, v).forEach { (nk, nv) -> out[nk] = nv }
                    is JsonArray -> out[k] = v.joinToString(",") { (it as? JsonPrimitive)?.content ?: "" }
                    else -> {}
                }
            }
            return out
        }

        /**
         * style 对象 → 扁平 props：先把 CSS 流行为（paddingHorizontal/paddingVertical 等）
         * 合并进 padding/margin，再走通用展平；边距类对象按 CSS 序合成 "上,右,下,左"。
         */
        private fun styleObj(style: JsonObject): Map<String, String> = flattenObj(mergeCssEdgeAliases(style))

        /**
         * CSS 流行为合并：`paddingHorizontal`/`paddingVertical`（Android 命名）与
         * `marginHorizontal`/`marginVertical` 并回 padding/margin 对象，
         * 否则这两个高频写法会被通用展平拍成 "paddingHorizontal" 键，样式解析一个都不认。
         */
        private fun mergeCssEdgeAliases(style: JsonObject): JsonObject {
            val norm = style.keys.associateBy { it.lowercase().replace("_", "") }
            val h = norm["paddinghorizontal"] ?: norm["marginhorizontal"]
            val v = norm["paddingvertical"] ?: norm["marginvertical"]
            if (h == null && v == null) return style
            val isMargin = norm.containsKey("marginhorizontal") || norm.containsKey("marginvertical")
            val target = if (isMargin) "margin" else "padding"
            val base = (style[target] as? JsonObject) ?: JsonObject(emptyMap())
            val merged = LinkedHashMap<String, JsonElement>(base)
            // h/v 是 norm 里的原始键名，style[h] 必然非空；用 ?.let 顺带过可空类型
            h?.let { key -> style[key]?.let { merged["horizontal"] = it } }
            v?.let { key -> style[key]?.let { merged["vertical"] = it } }
            return JsonObject(style + mapOf(target to JsonObject(merged)))
        }

        /**
         * 嵌套对象展平。边距类键（padding/margin/inset）按 CSS 边距语义合成
         * "上,右,下,左" 四段串（下游 [FlatDocToGenUI] 的 edge() 认这个序）；
         * 其余对象用 "父.子" 键平铺，不丢信息也不污染边距解析。
         *
         * 模型实际写法（A2UI 官方 style 形态，用户截图实锤）：
         * `"padding":{"all":16}` → "16"；`"padding":{"bottom":8}` → "0,0,8,0"；
         * `"padding":{"horizontal":12,"vertical":8}` → "8,12,8,12"。
         */
        private fun flattenNested(key: String, o: JsonObject): Map<String, String> {
            val k = key.lowercase().replace("_", "").replace("-", "")
            if (k in edgeKeys || k.endsWith("padding") || k.endsWith("margin")) {
                edgeString(o)?.let { return mapOf(key to it) }
            }
            return flattenObj(o).mapKeys { (sk, _) -> "$key.$sk" }
        }

        /**
         * 边距对象 → CSS "上,右,下,左" 串。
         * 支持 all / horizontal+vertical / top+right+bottom+left / 单侧（start→左、end→右）。
         * 单键纯标量（`{"padding":16}` 简写）原样返回，兼容旧扁平方言。
         */
        private fun edgeString(o: JsonObject): String? {
            if (o.isEmpty()) return null
            // 单键纯标量简写（{"all":16} / {"value":16} / {"dp":16}）原样返回，兼容旧扁平方语。
            // 🔴 轴名键（horizontal/top/start/…）绝不走这条捷径：{"horizontal":12}
            // 返回 "12" 等于宣布四边都是 12，bottom/top 全错（⑥c 实测挂在这）。
            // 轴名必须落到下面的完整解析，按 CSS 序拍 "上,右,下,左"。
            if (o.size == 1) {
                val (k, v) = o.entries.first()
                val nk = k.lowercase().replace("_", "")
                val axisLike = nk in setOf(
                    "horizontal", "vertical", "top", "bottom",
                    "left", "right", "start", "end"
                )
                if (!axisLike && v is JsonPrimitive && v !is JsonNull && v.content.isNotBlank()) {
                    return v.content.trim()
                }
            }
            var all: Float? = null; var h: Float? = null; var vv: Float? = null
            var t: Float? = null; var b: Float? = null; var l: Float? = null; var r: Float? = null
            fun f(e: JsonElement?) = (e as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content?.toFloatOrNull()
            o.forEach { (k, e) ->
                when (k.lowercase().replace("_", "")) {
                    "all" -> all = f(e)
                    "horizontal" -> h = f(e)
                    "vertical" -> vv = f(e)
                    "top" -> t = f(e)
                    "bottom" -> b = f(e)
                    "left" -> l = f(e)
                    "right" -> r = f(e)
                    "start" -> l = f(e)
                    "end" -> r = f(e)
                }
            }
            if (all == null && h == null && vv == null && t == null && b == null && l == null && r == null) return null
            val top = t ?: vv ?: all ?: 0f
            val bottom = b ?: vv ?: all ?: 0f
            val left = l ?: h ?: all ?: 0f
            val right = r ?: h ?: all ?: 0f
            if (all != null && h == null && vv == null && t == null && b == null && l == null && r == null) {
                return all.toString()
            }
            return "$top,$right,$bottom,$left"
        }

        /** parent 反引用取值：字符串 / {id:"x"} / null / "null" 都归一成 id 或 null */
        private fun parentOf(o: JsonObject): String? {
            val p = o["parent"] ?: o["parentId"] ?: return null
            return when (p) {
                is JsonNull -> null
                is JsonPrimitive -> p.content.trim().takeIf { it.isNotBlank() && it != "null" }
                is JsonObject -> listOf("id", "componentId", "nodeId").firstNotNullOfOrNull { k ->
                    (p[k] as? JsonPrimitive)?.content?.trim()?.takeIf { it.isNotBlank() }
                }
                else -> null
            }
        }

        private fun primStr(e: JsonElement): String = (e as? JsonPrimitive)?.content ?: ""

        /**
         * 标题级别不能丢：`h1/h2/h3` 经 [normType] 一律变成 `heading`，
         * 级别信息若不留进 props，下游只能吃默认值 —— 三级标题全长一个大小，版式层次就没了。
         *
         * 消费方是 FlatDocToGenUI.normalizeType：它读 `props["level"]` 决定映射到
         * heading1/2/3（SDK 的字号由组件类型决定，见 typographyConfig）。官方协议那条路
         * （A2uiProtocol）同样把 level 写进 props，两条路在这里对齐。
         */
        private fun withHeadingLevel(rawType: String, props: Map<String, String>): Map<String, String> {
            if (props.containsKey("level")) return props
            val lv = when (rawType.lowercase().trim()) {
                "h1" -> "1"; "h2" -> "2"; "h3" -> "3"
                "h4" -> "4"; "h5" -> "5"; "h6" -> "6"
                else -> return props
            }
            return props + ("level" to lv)
        }


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
            (el as? JsonObject)?.let { obj ->
                // ② nodes 邻接表（parent 反引用）：**必须排在 flatFromJson 之前** ——
                //    混合形状（root 键 + nodes 数组都带）时 flatFromJson 只认 children/kids，
                //    建出来的树是断的（parent 反引用的 kids 全丢），必须让能建全树的先走。
                runCatching { nodesArrayToFlatDoc(obj) }.getOrNull()?.let { return it }
                // ③ 本家扁平邻接表（root + components）
                runCatching { flatFromJson(obj) }.getOrNull()?.let { return it }
            }
            // ④ 裸嵌套节点树：顶层直接就是 {type, children:[...]}，既没有 root 也没有 components。
            //    这是模型被 "a2ui" 触发时最常写的一种形状，此前三处都不认 →
            //    整篇解析失败 → 通道退化成「原文画布」→ 用户看到的是一屏 JSON 源码而不是界面。
            return bareTreeToFlatDoc(el)
        }

        /**
         * ② nodes 邻接表（parent 反引用）→ 扁平邻接表。
         *
         * 形状（ZorvBrain 的 a2ui 锁定提示词原文教的，用户截图实锤）：
         * ```
         * {"nodes":[
         *   {"id":"root","type":"scroll","parent":null,
         *    "properties":{"direction":"vertical"},
         *    "style":{"padding":{"all":16},"backgroundColor":"background"}},
         *   {"id":"header","type":"header_bar","parent":"root",
         *    "properties":{"title":"北京"},"style":{"padding":{"all":16}}}
         * ]}
         * ```
         * 特征：顶层只有 nodes 数组（**没有 root 键**），父子关系靠 `parent` 字段反引用，
         * 属性分居 `properties`（内容/行为）与 `style`（外观）两个嵌套对象。
         *
         * 为什么旧代码整篇不认：flatFromJson 要 root 键、bareTreeToFlatDoc 要顶层 type 键、
         * A2uiProtocol 要信封 —— 三处全落空 → parse 返回 null → 通道退化成
         * 「（结构未识别，原文）」JSON 源码页。用户报的「A2UI 通道仍然没有恢复」实锤这一处。
         *
         * 兜底策略：
         *  · parent 指向不存在的 id → 当作根级节点（不丢内容）；
         *  · 多个 parent 为空的根 → 合成一个 column 根包起来（而不是只取第一个）；
         *  · 内联 children/kids 与 parent 反引用混用也认（两种写法都收）。
         */
        private fun nodesArrayToFlatDoc(obj: JsonObject): FlatDoc? {
            val arr = (obj["nodes"] ?: obj["components"]) as? JsonArray ?: return null
            val objs = arr.mapNotNull { it as? JsonObject }
            if (objs.isEmpty()) return null
            // 必须是「节点」形状：至少一个元素带 parent/parentId/type/t/children/kids 键，
            // 避免把 {"nodes":[{id,label}],"edges":[…]} 这类数据图误当成界面树。
            val looksLikeNodes = objs.any { o ->
                o.containsKey("parent") || o.containsKey("parentId") ||
                    o.containsKey("type") || o.containsKey("t") ||
                    o.containsKey("children") || o.containsKey("kids")
            }
            if (!looksLikeNodes) return null
            // 🔴 让路旧形状：带 root 键（尤其 root 是完整节点对象）且数组里没有 parent 反引用的，
            //    归 flatFromJson —— 它认 `root` 对象形态，本函数只扫数组会漏掉 root 对象本身。
            val hasParentRef = objs.any { it.containsKey("parent") || it.containsKey("parentId") }
            if (obj.containsKey("root") && !hasParentRef) return null

            val nodes = LinkedHashMap<String, FlatNode>()
            val order = ArrayList<String>()           // 数组序（kids 保序用）
            val referenced = LinkedHashSet<String>()  // 被别的节点当孩子引用的（parent 或内联 children）
            val parentMap = HashMap<String, String>() // id → parent id（register 时记录，防 autoId 二次漂移）
            var autoId = 0                            // 无 id 节点的单调计数器（防撞名）

            fun idOf(o: JsonObject): String =
                o["id"]?.let { primStr(it) }?.trim()?.takeIf { it.isNotBlank() } ?: ("n" + autoId++)

            fun register(o: JsonObject, parentHint: String?) {
                val id = idOf(o)
                if (nodes.containsKey(id)) return // 幂等：重名取第一个
                order.add(id)
                // 自己是别人的孩子（parent 反引用或内联 children）→ 不是根候选
                val parent = parentOf(o) ?: parentHint?.takeIf { it.isNotBlank() }
                if (!parent.isNullOrBlank()) {
                    referenced.add(id)
                    parentMap[id] = parent
                }

                val (rawT, text, props0) = flatten(o, topLevelProps = true)
                val props = withHeadingLevel(rawT, props0)
                val kids = ArrayList<String>()

                // items 为裸字符串数组 → 合成 text 子节点（列表内容不丢）
                val itemsArr = o["items"] as? JsonArray
                val kidsArr = (o["children"] ?: o["kids"]) as? JsonArray
                if (kidsArr == null && itemsArr != null && itemsArr.all { it is JsonPrimitive }) {
                    itemsArr.forEachIndexed { i, itemEl ->
                        val sid = id + "_i" + i
                        kids.add(sid)
                        referenced.add(sid)
                        nodes[sid] = FlatNode(sid, "text", primStr(itemEl), emptyList(), emptyMap())
                    }
                } else if (kidsArr != null) {
                    kidsArr.forEachIndexed { i, kidEl ->
                        val kId = when (kidEl) {
                            is JsonObject -> idOf(kidEl)
                            is JsonPrimitive -> kidEl.content.trim()
                            else -> ""
                        }
                        if (kId.isBlank()) return@forEachIndexed
                        kids.add(kId)
                        referenced.add(kId)
                        if (kidEl is JsonObject) register(kidEl, id)
                    }
                }
                nodes[id] = FlatNode(id, normType(rawT, kids.isNotEmpty()), text, kids, props)
            }

            objs.forEach { o -> register(o, null) }

            // parent 反引用补 kids（按数组序保序；内联 kids 已在节点上，合并去重）
            val kidsByParent = LinkedHashMap<String, MutableList<String>>()
            order.forEach { id ->
                val p = parentMap[id]?.takeIf { it.isNotBlank() && nodes.containsKey(it) } ?: return@forEach
                kidsByParent.getOrPut(p) { mutableListOf() }.add(id)
            }
            kidsByParent.forEach { (pid, kids) ->
                val cur = nodes[pid] ?: return@forEach
                val merged = (cur.kids + kids).distinct()
                if (merged != cur.kids) nodes[pid] = cur.copy(kids = merged)
            }

            // 根：parent 为空/缺失/指向不存在者；多个 → 合成 column 根；全被引用（环）→ 取第一个有子的
            val roots = order.filter { it !in referenced }
            val rootId = when {
                roots.size == 1 -> roots.first()
                roots.isEmpty() -> order.firstOrNull { (nodes[it]?.kids?.size ?: 0) > 0 } ?: order.firstOrNull()
                else -> {
                    // 合成根 id 不能撞上已有节点（模型真会给某个节点起名 "root"）
                    var synth = "root"
                    while (nodes.containsKey(synth)) synth = "_$synth"
                    nodes[synth] = FlatNode(synth, "column", "", roots, emptyMap())
                    synth
                }
            } ?: return null
            val rootNode = nodes[rootId] ?: return null
            if (rootNode.kids.isEmpty() && rootNode.text.isBlank()) return null
            return FlatDoc(rootId, nodes)
        }

        /**
         * ④ 裸嵌套节点树 → 扁平邻接表。
         * 形状（模型高频写法，子节点内联，无任何信封）：
         * ```
         * {"type":"column","spacing":14,"children":[
         *    {"type":"card","title":"…","backgroundColor":"#6C5CE7","children":[ … ]}
         * ]}
         * ```
         * 与另外几种的区别：不是 `{root,components}` 邻接表、不是 nodes 反引用表、也不是 `createSurface` 报文。
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
                val (rawT, text, props0) = flatten(o, topLevelProps = true)
                val props = withHeadingLevel(rawT, props0)
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
                // ⚠️ topLevelProps = true：邻接表里样式是**平铺在节点上**的
                // （`{"t":"card","bg":"#FFF6F1EC","radius":16}`，上游提示词就是这么教的）。
                // 只读 `props` 子对象的话，bg/color/size/radius/padding 会被**静默丢掉**，
                // 卡片就没有底色、没有圆角、没有层次 —— 用户看到的"组件不好看"有相当一部分
                // 是这里丢样式丢出来的，不是模型没写。A2UI 官方协议那条路(popsOf)本来就认平铺，
                // 这里对齐，两种写法都能拿到样式。
                val (rawT0, text, props0) = flatten(o, topLevelProps = true)
                val rawT = rawT0
                val props = withHeadingLevel(rawT, props0)
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
