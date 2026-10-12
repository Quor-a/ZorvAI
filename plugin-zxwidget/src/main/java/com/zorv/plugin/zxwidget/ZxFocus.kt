package com.zorv.plugin.zxwidget

import org.json.JSONArray
import org.json.JSONObject

/**
 * 无障碍焦点顺序声明 —— 读屏遍历顺序可指定，而不靠布局默认顺序。
 *
 * 为什么需要：Compose 的默认遍历顺序是布局顺序（近似视觉顺序），
 * 但卡片是 AI 生成的，布局顺序经常不等于语义顺序。
 * 典型问题：定价卡里"推荐"徽章排在价格前面，
 * 读屏会先念"推荐"再念"¥39"，用户听到的是乱的。
 *
 * 所以让 DSL 显式声明 order，并做三类检查：
 *   一、完整性：标了 order 的卡，所有可聚焦节点都得有序号，
 *       漏一个就会导致那一项读不到
 *   二、无重复：重复序号会让遍历顺序不确定
 *   三、无跳号：跳号说明中间漏了节点，通常是节点 id 写错
 *
 * 另外提供"回环"：最后一项的下一个是第一项，
 * 触屏读屏靠左右滑遍历，不回环就到不了开头。
 */
object ZxFocus {

    data class Node(
        val id: String,
        val order: Int,
        val label: String,
        /** 读屏念的内容，缺省用 label */
        val hint: String = "",
        /** 是否可聚焦；false 表示纯装饰，读屏跳过 */
        val focusable: Boolean = true
    )

    data class Issue(val level: Level, val detail: String) {
        enum class Level { ERROR, WARN }
        fun line(): String = "[${level.name}] $detail"
    }

    private val table = mutableMapOf<String, MutableList<Node>>()

    fun declare(cardId: String, nodes: List<Node>) {
        table[cardId] = nodes.toMutableList()
    }

    fun declareFromDsl(cardId: String, dsl: String): Result {
        val root = runCatching { JSONObject(dsl) }.getOrNull()
            ?: return Result(false, "DSL 无法解析")
        val arr = root.optJSONArray("_focus")
            ?: return Result(false, "DSL 里没有 _focus 数组")
        val list = mutableListOf<Node>()
        for (i in 0 until minOf(arr.length(), 24)) {
            val n = arr.optJSONObject(i)
                ?: return Result(false, "_focus[$i] 不是对象，应为 {\"id\":,\"order\":,\"label\":}")
            val id = n.optString("id")
            if (id.isBlank()) return Result(false, "_focus[$i] 缺少 id")
            if (!n.has("order")) return Result(false, "_focus[$i] 缺少 order（遍历序号）")
            list.add(Node(
                id = id,
                order = n.optInt("order", 0),
                label = n.optString("label").ifBlank { id },
                hint = n.optString("hint"),
                focusable = n.optBoolean("focusable", true)))
        }
        val issues = check(list)
        if (issues.any { it.level == Issue.Level.ERROR })
            return Result(false, issues.filter { it.level == Issue.Level.ERROR }
                .joinToString("；") { it.detail })
        declare(cardId, list)
        return Result(true, "已声明 ${list.size} 个焦点节点")
    }

    /** 三类检查 */
    fun check(nodes: List<Node>): List<Issue> {
        val out = mutableListOf<Issue>()
        if (nodes.isEmpty()) return out

        val focusable = nodes.filter { it.focusable }
        if (focusable.isEmpty()) {
            out.add(Issue(Issue.Level.WARN, "没有可聚焦节点——整张卡读屏会跳过"))
            return out
        }

        // 一、重复序号
        val byOrder = focusable.groupBy { it.order }
        byOrder.filter { it.value.size > 1 }.forEach { (o, list) ->
            out.add(Issue(Issue.Level.ERROR,
                "order=$o 有 ${list.size} 个节点（${list.joinToString { it.id }}）——遍历顺序不确定"))
        }

        // 二、跳号：期望 1..n 连续
        val sorted = focusable.map { it.order }.distinct().sorted()
        val expect = (sorted.first()..sorted.first() + sorted.size - 1).toList()
        val missing = expect - sorted.toSet()
        if (missing.isNotEmpty())
            out.add(Issue(Issue.Level.ERROR,
                "order 跳号，缺 ${missing.joinToString()}——通常是节点 id 写错了"))

        // 三、label 为空
        focusable.filter { it.label.isBlank() }.forEach {
            out.add(Issue(Issue.Level.WARN, "${it.id} 没有 label，读屏会念 id"))
        }
        return out
    }

    /** 按 order 排序后的遍历序列 */
    fun sequence(cardId: String): List<Node> =
        table[cardId]?.filter { it.focusable }?.sortedBy { it.order } ?: emptyList()

    /** 回环：最后一项的下一个是第一项 */
    fun next(cardId: String, currentId: String): Node? {
        val seq = sequence(cardId)
        if (seq.isEmpty()) return null
        val i = seq.indexOfFirst { it.id == currentId }
        return if (i < 0) seq.first() else seq[(i + 1) % seq.size]
    }

    fun prev(cardId: String, currentId: String): Node? {
        val seq = sequence(cardId)
        if (seq.isEmpty()) return null
        val i = seq.indexOfFirst { it.id == currentId }
        return if (i < 0) seq.last() else seq[(i - 1 + seq.size) % seq.size]
    }

    /** 生成可写回 DSL 的 _focus 数组 */
    fun toDslArray(nodes: List<Node>): JSONArray = JSONArray().apply {
        nodes.sortedBy { it.order }.forEach { n ->
            put(JSONObject().put("id", n.id).put("order", n.order)
                .put("label", n.label)
                .apply { if (n.hint.isNotBlank()) put("hint", n.hint) }
                .apply { if (!n.focusable) put("focusable", false) })
        }
    }

    fun report(cardId: String): String {
        val seq = sequence(cardId)
        if (seq.isEmpty()) return "卡片 $cardId 没有声明焦点顺序。"
        return buildString {
            appendLine("焦点顺序 ${seq.size} 个节点（回环）：")
            seq.forEachIndexed { i, n ->
                val nx = seq[(i + 1) % seq.size]
                appendLine("  ${n.order}. ${n.id} — 「${n.label}」${if (n.hint.isNotBlank()) " · ${n.hint}" else ""}")
                appendLine("       ↓ 下一个：${nx.id}")
            }
        }
    }

    fun guide(): String = """
        zx_focus(op=declare|check|report|next|guide, card?, dsl?)

        无障碍焦点顺序声明：读屏遍历顺序可指定，而不靠布局默认顺序。

        为什么需要：Compose 默认遍历顺序是布局顺序，
        但卡片是 AI 生成的，布局顺序经常不等于语义顺序。
        典型问题——定价卡里「推荐」徽章排在价格前面，
        读屏先念「推荐」再念「¥39」，用户听到的是乱的。

        三类检查：
          重复 order  遍历顺序不确定
          跳号        中间漏了节点，通常是 id 写错
          缺 label    读屏会念 id 而不是人话

        回环：最后一项的下一个是第一项——
        触屏读屏靠左右滑遍历，不回环就到不了开头。
    """.trimIndent()

    data class Result(val ok: Boolean, val reason: String)
}
