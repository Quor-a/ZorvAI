package com.ai.assistance.quro.kaleidobox.samples

import com.ai.assistance.quro.kaleidobox.core.model.KValue
import com.ai.assistance.quro.kaleidobox.core.util.Json
import kotlin.random.Random

/**
 * 世界书条目 —— 对齐 SillyTavern World Info / Lorebook 的语义。
 *
 * 世界书不是"卡片仓库"，而是一套**按关键词把内容动态注入提示词**的引擎：
 * 扫描聊天文本 → 命中关键词 → 按顺序/权重把条目内容拼进发给 AI 的上下文。
 */
data class LoreEntry(
    val id: String,
    /** 备注/标题（不参与触发，仅供人看）。 */
    val comment: String = "",
    /** 主关键词：命中任意一个即"主触发"。 */
    val keys: List<String> = emptyList(),
    /** 次关键词：配合 [logic] 做叠加/排除。 */
    val secondaryKeys: List<String> = emptyList(),
    /** AND_ANY | AND_ALL | NOT_ANY | NOT_ALL。 */
    val logic: String = "AND_ANY",
    /** 命中后注入的正文。 */
    val content: String = "",
    /** 插入顺序：数值越大越靠后（对输出影响更大）。 */
    val order: Int = 100,
    /** 插入深度（0 = 提示词最底部）。 */
    val depth: Int = 4,
    /** BEFORE_CHAR | AFTER_CHAR | AT_DEPTH。 */
    val position: String = "BEFORE_CHAR",
    /** 同组竞争时的权重。 */
    val weight: Int = 100,
    /** 常驻：无需关键词，始终激活。 */
    val constant: Boolean = false,
    /** 触发概率（0-100），用于随机事件。 */
    val probability: Int = 100,
    /** 包含组：同组多条同时命中时只保留一条。 */
    val group: String = "",
    val enabled: Boolean = true,
    /** 关键词是否区分大小写。 */
    val caseSensitive: Boolean = false,
    /** 关键词是否按正则解释。 */
    val regex: Boolean = false,
) {
    fun toMap(): Map<String, Any?> = mapOf(
        "id" to id, "comment" to comment,
        "keys" to keys, "secondaryKeys" to secondaryKeys,
        "logic" to logic, "content" to content,
        "order" to order, "depth" to depth, "position" to position,
        "weight" to weight, "constant" to constant, "probability" to probability,
        "group" to group, "enabled" to enabled,
        "caseSensitive" to caseSensitive, "regex" to regex,
    )
}

/**
 * 世界书引擎：解析 / 序列化 / 扫描激活 / 拼装注入文本。
 *
 * 纯 Kotlin、无宿主依赖，供"世界书"插件（编辑与测试）与"AI 助手"插件（实际注入）共用。
 */
object LorebookEngine {

    private val LOGICS = listOf("AND_ANY", "AND_ALL", "NOT_ANY", "NOT_ALL")

    // ---------------------------------------------------------------- 解析

    @Suppress("UNCHECKED_CAST")
    fun parse(raw: String?): List<LoreEntry> {
        if (raw.isNullOrBlank()) return emptyList()
        val parsed = runCatching { Json.parse(raw) }.getOrNull() as? List<*> ?: return emptyList()
        return parsed.mapNotNull { it as? Map<*, *> }.map { m -> fromMap(m) }
    }

    /** 把一条（来自 JSON / 状态的）Map 还原成 [LoreEntry]，缺字段用默认值兜底。 */
    fun fromMap(m: Map<*, *>): LoreEntry {
        fun str(k: String, d: String = "") = m[k]?.toString()?.takeIf { it != "null" } ?: d
        fun int(k: String, d: Int) = m[k]?.toString()?.toIntOrNull() ?: d
        fun bool(k: String, d: Boolean) = when (val v = m[k]) {
            is Boolean -> v
            null -> d
            else -> v.toString().equals("true", true)
        }
        fun strs(k: String): List<String> =
            (m[k] as? List<*>)?.mapNotNull { it?.toString() }?.filter { it.isNotBlank() } ?: emptyList()
        return LoreEntry(
            id = str("id").ifBlank { "wb_${System.currentTimeMillis()}_${(0..9999).random()}" },
            comment = str("comment"),
            keys = strs("keys"),
            secondaryKeys = strs("secondaryKeys"),
            logic = str("logic", "AND_ANY").takeIf { it in LOGICS } ?: "AND_ANY",
            content = str("content"),
            order = int("order", 100),
            depth = int("depth", 4),
            position = str("position", "BEFORE_CHAR"),
            weight = int("weight", 100),
            constant = bool("constant", false),
            probability = int("probability", 100),
            group = str("group"),
            enabled = bool("enabled", true),
            caseSensitive = bool("caseSensitive", false),
            regex = bool("regex", false),
        )
    }

    fun serialize(list: List<LoreEntry>): String =
        Json.write(KValue.of(list.map { it.toMap() }))

    // ---------------------------------------------------------------- 扫描

    /** 一次激活判定的结果。 */
    data class Hit(val entry: LoreEntry, val matched: List<String>)

    /**
     * 扫描 [text]，返回被激活的条目（已做包含组互斥与排序）。
     *
     * @param recursion 是否开启递归激活（被激活条目的正文再次参与扫描）。
     */
    fun scan(entries: List<LoreEntry>, text: String, recursion: Boolean = true): List<Hit> {
        val active = LinkedHashMap<String, Hit>()
        var buffer = text
        var pass = 0
        val maxPass = if (recursion) 3 else 1

        while (pass < maxPass) {
            var added = false
            entries.filter { it.enabled && it.id !in active }.forEach { e ->
                val hit = match(e, buffer)
                if (hit != null) {
                    active[e.id] = hit
                    added = true
                    if (recursion) buffer = buffer + "\n" + e.content
                }
            }
            if (!added) break
            pass++
        }
        return resolveGroups(active.values.toList())
    }

    /** 单条匹配：常驻直接命中；否则主关键词 + 次关键词逻辑 + 概率。 */
    private fun match(e: LoreEntry, text: String): Hit? {
        if (e.probability <= 0) return null
        if (e.probability < 100 && Random.nextInt(100) >= e.probability) return null

        if (e.constant) return Hit(e, listOf("(常驻)"))

        val matched = e.keys.filter { contains(text, it, e) }
        if (matched.isEmpty()) return null

        val sec = e.secondaryKeys
        val secMatched = sec.filter { contains(text, it, e) }
        val ok = when (e.logic) {
            "AND_ALL" -> sec.all { contains(text, it, e) }
            "NOT_ANY" -> secMatched.isEmpty()
            "NOT_ALL" -> secMatched.size < sec.size
            else -> sec.isEmpty() || secMatched.isNotEmpty() // AND_ANY
        }
        return if (ok) Hit(e, matched) else null
    }

    private fun contains(text: String, key: String, e: LoreEntry): Boolean {
        if (key.isBlank()) return false
        return if (e.regex) {
            runCatching {
                val opts = if (e.caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE)
                Regex(key, opts).containsMatchIn(text)
            }.getOrDefault(false)
        } else {
            text.contains(key, ignoreCase = !e.caseSensitive)
        }
    }

    /**
     * 包含组互斥：同一组内只保留权重最高的一条（权重相同取 order 更大者）。
     */
    private fun resolveGroups(hits: List<Hit>): List<Hit> {
        val byGroup = LinkedHashMap<String, Hit>()
        val out = mutableListOf<Hit>()
        hits.forEach { h ->
            val g = h.entry.group
            if (g.isBlank()) {
                out += h
            } else {
                val cur = byGroup[g]
                val better = cur == null ||
                    h.entry.weight > cur.entry.weight ||
                    (h.entry.weight == cur.entry.weight && h.entry.order > cur.entry.order)
                if (better) byGroup[g] = h
            }
        }
        return (out + byGroup.values).sortedBy { it.entry.order }
    }

    // ---------------------------------------------------------------- 注入

    /**
     * 把激活条目拼成注入文本。
     *
     * @param budget 字符预算上限（超出则按 order 从后往前丢弃低优先级条目）。
     */
    fun buildInjection(hits: List<Hit>, budget: Int = 4000): String {
        var used = 0
        val kept = mutableListOf<LoreEntry>()
        // order 大的影响更大，优先保留
        hits.sortedByDescending { it.entry.order }.forEach { h ->
            val len = h.entry.content.length
            if (used + len <= budget) { kept += h.entry; used += len }
        }
        if (kept.isEmpty()) return ""
        return buildString {
            appendLine("[世界书]")
            kept.sortedBy { it.order }.forEach { e ->
                if (e.comment.isNotBlank()) appendLine("· ${e.comment}")
                appendLine(e.content)
            }
        }.trim()
    }
}
