package com.zorv.plugin.zxwidget

import org.json.JSONObject

/**
 * 渲染缓存 —— 同样的 DSL 不重复组合。
 *
 * 为什么需要：会话里 AI 经常重发几乎一样的卡。
 * 用户切个 tab 回来、撤销后又重做、回滚到某版本再回滚回来——
 * 每次都重新走一遍解析、引用解析、校验、组合，主线程白白卡几帧。
 *
 * 缓存的键是「DSL 指纹」而不是 DSL 本身：
 *   指纹 = hash(组件类型 + 规范化后的 DSL + 已解析的引用值 + 主题版本)
 * 引用值必须进指纹，否则上游变了下游会命中旧缓存，画出来的还是旧数据。
 *
 * 三条硬规则：
 *   1. 引用值参与指纹计算，引用一变缓存立即失效（否则联动就被缓存吃掉了）
 *   2. 引用缓存的卡，其依赖卡被回滚/撤销时主动 invalidate
 *   3. 只缓存"已成功渲染过"的，校验失败的绝不入缓存
 */
object ZxCache {

    data class Entry(
        val key: String,
        val component: String,
        /** 命中次数，用于 LRU 淘汰 */
        var hits: Int = 0,
        val createdAt: Long = System.currentTimeMillis(),
        /** 本条目依赖了哪些卡，用于级联失效 */
        val deps: Set<String>
    )

    private const val MAX = 120

    /** 指纹 -> 条目 */
    private val map = linkedMapOf<String, Entry>()

    /** 卡 id -> 它产生的所有指纹，用于级联失效 */
    private val owned = linkedMapOf<String, MutableSet<String>>()

    private var themeVersion = 1
    fun bumpTheme() { themeVersion++; clear() }

    /** 统计 */
    var lookups = 0L; private set
    var hits = 0L; private set
    var misses = 0L; private set

    /**
     * 计算指纹。引用值必须参与——这是缓存与联动能共存的关键。
     */
    fun fingerprint(cardId: String, component: String, dsl: String): String {
        // 引用解析后的实际值拼进去，上游一变指纹就变
        val refs = ZxRef.extract(dsl)
            .sortedBy { it.raw }
            .joinToString("|") { "${it.raw}=${ZxRef.readValue(it.cardId, it.path) ?: ZxRef.MISSING}" }
        // 依赖的卡也要记，供级联失效
        val depIds = ZxRef.extract(dsl).map { it.cardId }.toSet()
        return (component + "#" + dsl.hashCode() + "#" + refs.hashCode() + "#" + themeVersion)
            .also { key ->
                if (cardId.isNotBlank()) {
                    owned.getOrPut(cardId) { mutableSetOf() }.add(key)
                }
            }
            .let { it + "#" + depIds.sorted().joinToString(",").hashCode() }
    }

    fun get(key: String): Entry? {
        lookups++
        val e = map[key]
        if (e != null) { e.hits++; hits++ } else misses++
        return e
    }

    /** 只在渲染成功后调用 */
    fun put(cardId: String, component: String, dsl: String, deps: Set<String>): String {
        val key = fingerprint(cardId, component, dsl)
        if (map.size >= MAX) evict()
        map[key] = Entry(key, component, deps = deps)
        return key
    }

    /** LRU 淘汰：先扔命中次数最少且最老的 */
    private fun evict() {
        val victim = map.values.minWith(compareBy({ it.hits }, { it.createdAt }))
        map.remove(victim.key)
        owned.values.forEach { it.remove(victim.key) }
    }

    /**
     * 级联失效：某张卡变了，引用它的所有缓存条目全部作废。
     * ZxRef.propagate 之后必须调用，否则下游会命中旧缓存。
     */
    fun invalidate(cardId: String): Int {
        var n = 0
        // 1. 这张卡自己产生的条目
        owned[cardId]?.forEach { if (map.remove(it) != null) n++ }
        owned.remove(cardId)
        // 2. 依赖了这张卡的条目
        val stale = map.values.filter { cardId in it.deps }.map { it.key }
        stale.forEach { map.remove(it); n++ }
        return n
    }

    fun clear() {
        map.clear(); owned.clear()
        lookups = 0; hits = 0; misses = 0
    }

    fun size(): Int = map.size
    fun hitRate(): Float = if (lookups == 0L) 0f else hits.toFloat() / lookups

    /** 统计渲染成终端卡，供 /zxcache 查看 */
    fun statsAsTerminal(): String {
        val lines = org.json.JSONArray()
        lines.put(JSONObject().put("lv", "cmd").put("c", "zx_cache stats"))
        lines.put(JSONObject().put("lv", "ok")
            .put("c", "条目 ${size()} / $MAX   命中率 ${"%.1f".format(hitRate() * 100)}%  ($hits/$lookups)"))
        lines.put(JSONObject().put("lv", "out").put("c", "主题版本 v$themeVersion"))
        if (map.isNotEmpty()) {
            lines.put(JSONObject().put("lv", "out").put("c", "Top 命中："))
            map.values.sortedByDescending { it.hits }.take(6).forEach { e ->
                lines.put(JSONObject().put("lv", "out")
                    .put("c", "  ${e.component}  ×${e.hits}" +
                        (if (e.deps.isNotEmpty()) "  依赖 ${e.deps.joinToString()}" else "")))
            }
        }
        lines.put(JSONObject().put("lv", "out")
            .put("c", "引用值参与指纹计算——上游一变下游立即失效，联动不会被缓存吃掉"))
        return JSONObject().put("type", "zx_terminal")
            .put("cmd", "zx_cache stats").put("exit", 0).put("lines", lines).toString()
    }
}
