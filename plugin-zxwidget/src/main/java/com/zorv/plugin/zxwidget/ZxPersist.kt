package com.zorv.plugin.zxwidget

import org.json.JSONObject

/**
 * 跨会话卡片持久化 —— 关掉对话再回来，卡片的交互状态还在。
 *
 * 为什么需要：卡片是有状态的（用户填了一半的表单、勾掉的清单、
 * 展开的节点）。会话一关这些就丢了，用户回来要重新填一遍，
 * 而**重新填的过程本身会丢信息**——他上次为什么勾掉那一项，
 * 现在未必记得。
 *
 * 存什么 / 不存什么，是这里唯一的难点：
 *
 *   存：用户显式做过的事（勾选、填值、展开、选中）
 *   不存：渲染中间态（滚动位置、动画进度、临时高亮）
 *
 * 渲染中间态存了反而有害——恢复时会把用户带到一个
 * 他自己没到过的位置，而且这些值几乎每次都不同，
 * 会让"有没有变化"的判断失效。
 *
 * 另外存**版本号**：组件改版后字段可能变，
 * 用旧字段恢复新版卡片会得到半初始化的状态。
 * 版本不匹配时丢弃而不是硬恢复——宁可让用户重填，
 * 也不要给他一个看起来填好了实际是错的卡。
 */
object ZxPersist {

    private const val SCHEMA = 3

    data class Record(
        val cardId: String,
        val component: String,
        val dsl: String,
        /** 用户改动：路径 → 值 */
        val edits: Map<String, String>,
        /** UI 态：折叠/展开、选中项 */
        val ui: Map<String, String>,
        val version: Int = SCHEMA,
        val t: Long = System.currentTimeMillis()
    )

    private val store = linkedMapOf<String, Record>()
    /** 每个组件的结构版本，改版时 +1 */
    private val compVer = mutableMapOf<String, Int>()

    fun bumpVersion(component: String) {
        compVer[component] = (compVer[component] ?: 1) + 1
    }

    fun versionOf(component: String): Int = compVer[component] ?: 1

    /** 保存一张卡的当前状态 */
    fun save(cardId: String, edits: Map<String, String> = emptyMap(),
             ui: Map<String, String> = emptyMap()): Boolean {
        val node = ZxRef.get(cardId) ?: return false
        store[cardId] = Record(cardId, node.component, node.dsl,
                               edits.toMap(), ui.toMap())
        return true
    }

    fun saveAll() {
        ZxRef.graph.keys.forEach { save(it) }
    }

    fun load(cardId: String): Record? = store[cardId]

    /**
     * 恢复。返回 null 表示不能恢复（组件改过版或卡片已不存在）。
     * 调用方拿到 null 应该走"重新渲染"而不是"硬塞一个空状态"。
     */
    fun restore(cardId: String): Record? {
        val r = store[cardId] ?: return null
        // 版本不匹配直接丢弃——半初始化的状态比没有状态更糟
        if (r.version != SCHEMA) return null
        if (compVer[r.component] != null && versionOf(r.component) > 1
            && r.dsl.hashCode() != ZxRef.get(cardId)?.dsl?.hashCode()) return null
        return r
    }

    fun all(): List<Record> = store.values.toList()

    /** 导出成可存盘的 JSON */
    fun export(): String {
        val arr = org.json.JSONArray()
        store.values.forEach { r ->
            arr.put(JSONObject()
                .put("id", r.cardId).put("c", r.component)
                .put("v", r.version).put("t", r.t)
                .put("dsl", r.dsl)
                .put("edits", JSONObject().apply { r.edits.forEach { (k, v) -> put(k, v) } })
                .put("ui", JSONObject().apply { r.ui.forEach { (k, v) -> put(k, v) } }))
        }
        return JSONObject().put("schema", SCHEMA).put("cards", arr).toString(2)
    }

    /** 从 JSON 导入 */
    fun import(json: String): Pair<Int, Int> {
        val root = runCatching { JSONObject(json) }.getOrNull()
            ?: return 0 to 0
        if (root.optInt("schema", 0) != SCHEMA)
            return 0 to 0   // schema 不同就不导入，不做字段级兼容
        val arr = root.optJSONArray("cards") ?: return 0 to 0
        var ok = 0; var skip = 0
        for (i in 0 until arr.length()) {
            val c = arr.optJSONObject(i) ?: continue
            val id = c.optString("id")
            if (id.isBlank()) { skip++; continue }
            val edits = mutableMapOf<String, String>()
            val eo = c.optJSONObject("edits")
            if (eo != null) eo.keys().forEach { edits[it] = eo.optString(it) }
            val ui = mutableMapOf<String, String>()
            val uo = c.optJSONObject("ui")
            if (uo != null) uo.keys().forEach { ui[it] = uo.optString(it) }
            store[id] = Record(id, c.optString("c"), c.optString("dsl"),
                               edits, ui, c.optInt("v", SCHEMA), c.optLong("t"))
            ok++
        }
        return ok to skip
    }

    fun forget(cardId: String) { store.remove(cardId) }
    fun clear() { store.clear() }
    fun size() = store.size

    fun report(): String = buildString {
        appendLine("已持久化 ${store.size} 张卡（schema v$SCHEMA）")
        if (store.isEmpty()) { appendLine("（无）"); return@buildString }
        store.values.forEach { r ->
            val e = if (r.edits.isEmpty()) "无改动" else "${r.edits.size} 处改动"
            appendLine("  ${r.cardId} · ${r.component} · $e")
        }
        appendLine()
        appendLine("只存用户显式做过的事（勾选/填值/展开/选中），")
        appendLine("不存渲染中间态（滚动位置/动画进度/临时高亮）——")
        appendLine("中间态恢复会把用户带到他自己没到过的位置。")
    }

    fun guide(): String = """
        zx_persist(op=save|saveAll|restore|export|import|forget|clear|report|guide, card?, json?)

        跨会话卡片持久化：关掉对话再回来，卡片的交互状态还在。

        存什么 / 不存什么是这里唯一的难点：
          存    用户显式做过的事（勾选、填值、展开、选中）
          不存  渲染中间态（滚动位置、动画进度、临时高亮）
        中间态存了反而有害——恢复时会把用户带到一个他自己没到过的位置。

        版本不匹配时丢弃而不是硬恢复：宁可让用户重填，
        也不要给他一个看起来填好了实际是错的卡。

        schema 不同就不导入，不做字段级兼容——
        半初始化的状态比没有状态更糟。
    """.trimIndent()
}
