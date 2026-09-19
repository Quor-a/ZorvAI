package com.ai.assistance.quro.genui.app.render

import org.json.JSONArray
import org.json.JSONObject
import androidx.compose.runtime.mutableStateMapOf

/**
 * 动态组件注册表 —— GenUI 与 AGenUI/flutter_genui 等固定 Catalog 路线的根本分野：
 *
 *   固定 Catalog：端上预置组件清单，AI 只能从中挑选（组件面固定、端上零自由度）；
 *   GenUI 路线：AI 现场**写**组件模板 → `MoBridge.ui.component(name, 模板)` **注册** →
 *   在组件树里**自己复用** `<name prop=...>` → 原生渲染。组件面由 AI 按需生长。
 *
 * 模板语法（JSON 组件树 + 两类占位）：
 *   {{prop}}       实例化时替换为传入属性值（字符串值内插值）；
 *   {"slot":true}  模板节点标记为插槽 → 实例化时替换为实例的 children 子树。
 *
 * 安全：展开深度 ≤ 8、节点数 ≤ 400，模板自引用由深度上限兜底；
 *       注册名归一为 [a-z0-9_-]，重复注册覆盖。
 */
object DynamicComponents {

    private const val MAX_DEPTH = 8
    private const val MAX_NODES = 400

    // Compose 快照态注册表：AI 重新注册（编辑）同名组件 → 依赖它的界面自动重渲染
    private val comps = mutableStateMapOf<String, String>()

    /** 注册自定义组件。模板必须是 {"type": ...} 组件树 */
    fun register(name: String, template: String): JSONObject {
        val key = normalize(name)
        if (key.isBlank()) throw IllegalArgumentException("组件名非法：$name")
        val t = JSONObject(template)
        if (!t.has("type")) throw IllegalArgumentException("组件模板缺少 type 字段")
        comps[key] = t.toString()
        return JSONObject().put("ok", true).put("name", key)
            .put("total", comps.size)
            .put("hint", "组件树里直接用 {\"type\":\"$key\", ...属性} 复用")
    }

    fun clear() = comps.clear()

    fun registeredNames(): List<String> = comps.keys.asSequence().toList()

    /** 是否已注册 */
    fun has(name: String): Boolean = comps.containsKey(normalize(name))

    /** 直接实例化：名字 + 属性 → 展开后的组件树 JSON（未注册返回 null） */
    fun resolve(name: String, props: org.json.JSONObject): String? {
        val t = comps[normalize(name)] ?: return null
        budget = 0
        return runCatching { expandNode(org.json.JSONObject(t).let { substitute(it, props) }, 0).toString() }
            .getOrNull()
    }

    /** 渲染前展开：未知类型若命中注册表 → 模板实例化（否则原样透传） */
    fun expandTree(desc: String): String = runCatching {
        expandNode(JSONObject(desc), 0).toString()
    }.getOrDefault(desc)

    private fun normalize(name: String) =
        name.trim().lowercase().replace(Regex("[^a-z0-9_-]"), "")

    private var budget = 0

    private fun expandNode(node: JSONObject, depth: Int): JSONObject {
        if (depth > MAX_DEPTH || budget > MAX_NODES) return node
        budget++
        val out = JSONObject()
        val type = node.optString("type").lowercase()
        val template = comps[type]

        if (template != null) {
            // 实例化：属性 = 实例节点除去 type/children 的全部键
            val props = JSONObject()
            for (k in node.keys()) if (k != "type" && k != "children") props.put(k, node.get(k))
            val inst = substitute(JSONObject(template), props)
            // 实例 children → 填进模板里标记 {"slot":true} 的位置
            val instChildren = node.optJSONArray("children")
            val withSlot = if (instChildren != null) fillSlots(inst, instChildren, depth) else inst
            return expandNode(withSlot, depth + 1)
        }

        // 非自定义组件：原样保留，但递归展开子树
        for (k in node.keys()) {
            when (val v = node.get(k)) {
                is JSONObject -> out.put(k, expandNode(v, depth + 1))
                is JSONArray -> {
                    val arr = JSONArray()
                    for (i in 0 until v.length()) {
                        val c = v.optJSONObject(i)
                        arr.put(if (c != null) expandNode(c, depth + 1) else v.get(i))
                    }
                    out.put(k, arr)
                }
                else -> out.put(k, v)
            }
        }
        return out
    }

    /** 递归查找 {"slot":true} 节点并替换为实例子树 */
    private fun fillSlots(node: JSONObject, children: JSONArray, depth: Int): JSONObject {
        if (depth > MAX_DEPTH) return node
        val out = JSONObject()
        for (k in node.keys()) {
            when (val v = node.get(k)) {
                is JSONObject -> {
                    out.put(k, if (v.optBoolean("slot")) children else fillSlots(v, children, depth + 1))
                }
                is JSONArray -> {
                    val arr = JSONArray()
                    for (i in 0 until v.length()) {
                        val c = v.optJSONObject(i)
                        arr.put(if (c != null && c.optBoolean("slot")) children
                        else if (c != null) fillSlots(c, children, depth + 1) else v.get(i))
                    }
                    out.put(k, arr)
                }
                else -> out.put(k, v)
            }
        }
        return out
    }

    /** 模板内 {{prop}} 插值（支持 props.x 与裸 x；未提供的占位保留原样以暴露问题） */
    private fun substitute(node: JSONObject, props: JSONObject): JSONObject {
        val out = JSONObject()
        val re = Regex("\\{\\{\\s*([a-zA-Z0-9_.]+)\\s*\\}\\}")
        for (k in node.keys()) {
            when (val v = node.get(k)) {
                is String -> out.put(k, v.replace(re) { m ->
                    val key = m.groupValues[1]
                    val direct = props.opt(key)
                    (direct ?: props.optJSONObject("props")?.opt(key))?.toString() ?: m.value
                })
                is JSONObject -> out.put(k, substitute(v, props))
                is JSONArray -> {
                    val arr = JSONArray()
                    for (i in 0 until v.length()) {
                        val c = v.optJSONObject(i)
                        arr.put(if (c != null) substitute(c, props) else v.get(i))
                    }
                    out.put(k, arr)
                }
                else -> out.put(k, v)
            }
        }
        return out
    }
}
