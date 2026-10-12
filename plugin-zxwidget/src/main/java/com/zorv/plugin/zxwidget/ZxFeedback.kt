package com.zorv.plugin.zxwidget

import org.json.JSONObject

/**
 * 渲染回执 —— 把"这张卡实际渲染成什么样"回写给模型。
 *
 * 为什么需要：模型下发 DSL 之后是**盲的**。它不知道：
 *   · 那张卡到底渲染成功了没有（校验过了也可能被运行时拦下）
 *   · 用户点的是哪一项
 *   · 它填的 8 列是不是被压成了 3 列
 *   · 它给的一堆字段里，哪些渲染器根本没用上
 *
 * 没有回执，模型下一轮只能靠猜，于是会重复问已经答过的问题，
 * 或者"再发一次同样的卡"——因为它在自己看来没画成功。
 *
 * 回执刻意**短**：它不是日志，是要进上下文的东西。
 * 一条一行，最多 8 条，超过就只留最重要的。
 *
 * 另一件关键事：**未使用字段要报**。
 * 模型辛辛苦苦生成的字段如果渲染器根本没读，
 * 那是纯粹的 token 浪费，而且它会一直浪费下去——
 * 不告诉它，它永远不知道。
 */
object ZxFeedback {

    enum class Level { OK, WARN, ERR }

    data class Item(
        val cardId: String,
        val component: String,
        val level: Level,
        val msg: String,
        val t: Long = System.currentTimeMillis()
    ) {
        fun line(): String = "[${level.name}] ${component}#${cardId}: $msg"
    }

    /** 各类组件实际读取的字段白名单——用来反查"哪些字段白写了" */
    private val USED_FIELDS = mapOf(
        "zx_table" to setOf("type", "cols", "rows", "sel", "title"),
        "zx_chart" to setOf("type", "style", "labels", "points", "title", "max"),
        "zx_form" to setOf("type", "fields", "title", "submit"),
        "zx_compare" to setOf("type", "columns", "rows", "title", "rec"),
        "zx_terminal" to setOf("type", "lines", "text"),
        "zx_price" to setOf("type", "plans"),
        "zx_stat" to setOf("type", "items"),
        "zx_meter" to setOf("type", "v", "min", "max", "label", "levels"),
        "zx_notice" to setOf("type", "items", "ttlMs")
    )

    private val buf = ArrayDeque<Item>()
    private const val MAX = 60

    fun log(cardId: String, component: String, level: Level, msg: String) {
        buf.addLast(Item(cardId, component, level, msg.take(140)))
        if (buf.size > MAX) buf.removeFirst()
    }

    fun ok(cardId: String, component: String, msg: String) =
        log(cardId, component, Level.OK, msg)

    fun warn(cardId: String, component: String, msg: String) =
        log(cardId, component, Level.WARN, msg)

    fun err(cardId: String, component: String, msg: String) =
        log(cardId, component, Level.ERR, msg)

    /**
     * 渲染成功后调用：顺带算出未使用字段。
     * 这是整套机制里最有价值的一条——模型不知道自己白写了什么。
     */
    fun rendered(cardId: String, component: String, dsl: String): String {
        val root = runCatching { JSONObject(dsl) }.getOrNull()
        val notes = mutableListOf<String>()
        if (root != null) {
            val used = USED_FIELDS[component]
            if (used != null) {
                val keys = root.keys().asSequence().toList()
                val unused = keys.filter { it !in used && !it.startsWith("_") }
                if (unused.isNotEmpty())
                    notes.add("未使用的字段：${unused.joinToString()}（不必再生成）")
            }
            if (root.has("_caps") && root.optJSONArray("_caps")?.length() == 0)
                notes.add("_caps 为空数组，等同于未声明任何能力")
        }
        val msg = if (notes.isEmpty()) "渲染成功" else notes.joinToString("；")
        log(cardId, component, if (notes.isEmpty()) Level.OK else Level.WARN, msg)
        return msg
    }

    /**
     * 给模型的回执。刻意短——这是要进上下文的东西，不是日志。
     */
    fun digest(limit: Int = 8): String {
        val list = buf.takeLast(limit)
        if (list.isEmpty()) return "（本轮没有卡片回执）"
        return buildString {
            appendLine("渲染回执（最近 ${list.size} 条，共 ${buf.size}）：")
            list.forEach { appendLine(it.line()) }
            val e = list.count { it.level == Level.ERR }
            if (e > 0) appendLine()
            if (e > 0) appendLine("有 $e 条失败。失败的卡不会出现在界面上——")
            if (e > 0) appendLine("不要重复下发同样的 DSL，先按回执里的原因改。")
        }
    }

    /** 只给失败与警告——成功的不用占上下文 */
    fun problems(): String {
        val list = buf.filter { it.level != Level.OK }.takeLast(8)
        return if (list.isEmpty()) "没有需要处理的渲染问题。"
               else list.joinToString("\n") { it.line() }
    }

    fun of(cardId: String): String {
        val l = buf.filter { it.cardId == cardId }
        return if (l.isEmpty()) "卡片 $cardId 没有回执。"
               else l.joinToString("\n") { it.line() }
    }

    /** 统计：哪些组件老出问题，用来决定要不要改组件而不是改 DSL */
    fun stats(): String {
        val byComp = buf.groupBy { it.component }
        return buildString {
            appendLine("按组件统计（共 ${buf.size} 条）：")
            byComp.entries.sortedByDescending { it.value.size }.forEach { (c, l) ->
                val e = l.count { it.level == Level.ERR }
                appendLine("  %-16s %3d 条，其中失败 %d".format(c, l.size, e))
            }
        }
    }

    fun clear() = buf.clear()
    fun size() = buf.size

    fun guide(): String = """
        zx_feedback(op=digest|problems|card|stats|clear|guide, card?, limit?)

        渲染回执：把「这张卡实际渲染成什么样」回写给模型。

        为什么需要：模型下发 DSL 之后是盲的——
        它不知道渲染成功了没有、用户点了哪一项、
        它填的 8 列是不是被压成了 3 列。

        没有回执，模型下一轮只能靠猜，于是会重复问已经答过的问题，
        或者「再发一次同样的卡」，因为它在自己看来没画成功。

        刻意短——这是要进上下文的东西，不是日志。

        最有价值的一条：未使用字段会报出来。
        模型辛辛苦苦生成的字段渲染器根本没读，那是纯粹的 token 浪费，
        不告诉它，它会一直浪费下去。
    """.trimIndent()
}
