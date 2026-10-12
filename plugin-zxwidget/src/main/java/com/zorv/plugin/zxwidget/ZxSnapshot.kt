package com.zorv.plugin.zxwidget

import org.json.JSONObject
import java.security.MessageDigest

/**
 * 卡片快照回归测试 —— DSL 改动前后比对，防渲染回归。
 *
 * 为什么需要：卡片是 AI 生成的，改一版 DSL 很容易连带改坏别的卡。
 * 现有校验能保证"这一版画得出来"，但保证不了"这一版和上一版长得一样"。
 * 典型事故：给表格加一列，结果依赖它的图表引用错位了，
 * 校验全过，界面却是错的。
 *
 * 做像素级比对不现实（要真渲染），所以比**结构指纹**：
 * 把 DSL 里影响渲染结果的部分抽出来算哈希，忽略不影响渲染的字段。
 *
 * 关键设计：忽略白名单。
 * 时间戳、卡片 ID 这类字段每次都变，如果不忽略，
 * 每次比对都是"变了"，这个机制就废了。
 */
object ZxSnapshot {

    /** 不参与指纹的字段：每次都变，但不影响渲染 */
    private val IGNORE = setOf("id", "ts", "createdAt", "updatedAt", "seq", "rev", "_meta")

    /** 只比影响布局的字段：位置/尺寸/数量类 */
    private val SHAPE_HINT = listOf(
        "rows", "cols", "columns", "items", "points", "labels", "cells",
        "steps", "states", "sectors", "nodes", "links", "blocks", "children"
    )

    data class Snap(
        val cardId: String,
        val component: String,
        val fp: String,
        val shape: String,
        val t: Long = System.currentTimeMillis()
    )

    data class Diff(
        val cardId: String,
        val component: String,
        val kind: Kind,
        val detail: String
    ) {
        enum class Kind { ADDED, REMOVED, CONTENT, SHAPE }
        fun line(): String = "%-8s %-14s %s".format(kind.name, component, detail)
    }

    private val baseline = linkedMapOf<String, Snap>()

    private fun md5(s: String): String {
        val b = MessageDigest.getInstance("MD5").digest(s.toByteArray())
        return b.joinToString("") { "%02x".format(it) }
    }

    /** 规范化：去忽略字段、排序 key——同样的语义必须得到同样的串 */
    private fun canon(o: Any?): String = when (o) {
        is JSONObject -> {
            val keys = o.keys().asSequence().toList()
                .filter { it !in IGNORE }.sorted()
            keys.joinToString(",", "{", "}") { "$it:" + canon(o.opt(it)) }
        }
        is org.json.JSONArray ->
            (0 until o.length()).joinToString(",", "[", "]") { canon(o.opt(it)) }
        JSONObject.NULL -> "null"
        null -> "null"
        is Number -> o.toString()
        is Boolean -> o.toString()
        else -> JSONObject.quote(o.toString())
    }

    /** 结构摘要：只取数量与关键尺寸，用于判断"形状变了没" */
    private fun shape(o: Any?, depth: Int = 0): String {
        if (depth > 4) return "…"
        return when (o) {
            is JSONObject -> {
                val keys = o.keys().asSequence().toList()
                    .filter { it !in IGNORE }.sorted()
                keys.joinToString(",") { k ->
                    val v = o.opt(k)
                    when {
                        k in SHAPE_HINT && v is org.json.JSONArray -> "$k#${v.length()}"
                        k in SHAPE_HINT && v is JSONObject -> "$k{${v.length()}}"
                        v is org.json.JSONArray -> "$k#${v.length()}"
                        v is JSONObject -> "$k{${shape(v, depth + 1)}}"
                        else -> k
                    }
                }
            }
            is org.json.JSONArray -> "[${o.length()}]"
            else -> ""
        }
    }

    /** 取当前快照 */
    fun capture(cardId: String): Snap? {
        val node = ZxRef.get(cardId) ?: return null
        val root = runCatching { JSONObject(node.dsl) }.getOrNull() ?: return null
        return Snap(cardId, node.component, md5(canon(root)), shape(root))
    }

    fun captureAll(): List<Snap> = ZxRef.graph.keys.mapNotNull { capture(it) }

    /** 存基线 */
    fun save(ids: List<String> = ZxRef.graph.keys.toList()): Int {
        var n = 0
        ids.forEach { capture(it)?.let { s -> baseline[s.cardId] = s; n++ } }
        return n
    }

    fun baselineSize(): Int = baseline.size
    fun clear() = baseline.clear()

    /** 与基线比对 */
    fun diff(): List<Diff> {
        val out = mutableListOf<Diff>()
        val now = captureAll().associateBy { it.cardId }

        for ((id, base) in baseline) {
            val cur = now[id]
            if (cur == null) {
                out.add(Diff(id, base.component, Diff.Kind.REMOVED, "卡片已不存在"))
                continue
            }
            if (cur.fp != base.fp) {
                // 形状变了比内容变了更严重：形状变会影响下游引用
                val kind = if (cur.shape != base.shape) Diff.Kind.SHAPE else Diff.Kind.CONTENT
                val d = if (kind == Diff.Kind.SHAPE)
                    "结构 ${base.shape} → ${cur.shape}" else "内容变化（结构未变）"
                out.add(Diff(id, cur.component, kind, d))
            }
        }
        for ((id, cur) in now) {
            if (id !in baseline)
                out.add(Diff(id, cur.component, Diff.Kind.ADDED, "新增卡片"))
        }
        return out
    }

    /**
     * 冒烟测试：改一张卡之前先存基线，改完比对，
     * 把"我动了这个"之外的变化全报出来——那些就是回归。
     */
    fun checkAfter(cardId: String, mutate: () -> Unit): String {
        val before = captureAll().associateBy { it.cardId }
        val had = before[cardId] != null
        mutate()
        val after = captureAll().associateBy { it.cardId }
        val changed = mutableListOf<Diff>()
        for ((id, a) in after) {
            val b = before[id]
            when {
                b == null -> changed.add(Diff(id, a.component, Diff.Kind.ADDED, "新增"))
                b.fp != a.fp -> changed.add(Diff(id, a.component,
                    if (b.shape != a.shape) Diff.Kind.SHAPE else Diff.Kind.CONTENT,
                    if (b.shape != a.shape) "结构 ${b.shape} → ${a.shape}" else "内容变化"))
            }
        }
        val unintended = changed.filter { it.cardId != cardId }
        return buildString {
            appendLine("改动目标：$cardId${if (!had) "（原本不存在）" else ""}")
            appendLine("实际变化 ${changed.size} 处，其中预期外 ${unintended.size} 处：")
            if (unintended.isEmpty()) appendLine("  （无预期外变化——没有回归）")
            else unintended.forEach { appendLine("  ${it.line()}") }
            if (changed.any { it.kind == Diff.Kind.SHAPE })
                appendLine()
            if (changed.any { it.kind == Diff.Kind.SHAPE })
                appendLine("注意：结构变化会影响引用它的卡片——那些卡的 zx_ref 可能已错位。")
        }
    }

    fun report(): String {
        val d = diff()
        return buildString {
            appendLine("基线 ${baseline.size} 张 · 差异 ${d.size} 处")
            if (d.isEmpty()) appendLine("无变化。")
            d.groupBy { it.kind }.forEach { (k, list) ->
                appendLine()
                appendLine("【$k】${list.size}")
                list.forEach { appendLine("  ${it.line()}") }
            }
        }
    }

    fun guide(): String = """
        zx_snapshot(op=save|diff|check|report|clear|guide, card?)

        卡片快照回归测试：比结构指纹而非像素，防 DSL 改动引起渲染回归。

        为什么不做像素比对：要真渲染，代价太大。
        比影响渲染结果的结构指纹就够了——
        给表格加一列导致图表引用错位，结构指纹一定变。

        忽略字段：${IGNORE.joinToString("/")}
        这些字段每次都变但不影响渲染，不忽略的话每次比对都是「变了」，机制就废了。

        op=check 卡ID  →  改这张卡之前存基线，改完比对，
        把「我动了这个」之外的变化全报出来——那些就是回归。
    """.trimIndent()
}
