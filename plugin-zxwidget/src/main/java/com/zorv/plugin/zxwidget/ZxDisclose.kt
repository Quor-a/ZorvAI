package com.zorv.plugin.zxwidget

import org.json.JSONObject

/**
 * 渐进式披露 —— 长卡自动折叠 + 按需展开。
 *
 * 203 个组件之后，一个很实际的问题：
 * **AI 喜欢给全**。
 *
 *   问"这季度怎么样"，它画一张 40 行的表。
 *   问"对比一下"，它把 12 个维度全列出来。
 *
 * 40 行的表在气泡里要滚很久，而用户真正想看的
 * 往往是最上面那几行。"先给全，用户自己找"
 * 是把筛选成本转嫁给了用户。
 *
 * 但**不能简单截断**——截断丢信息，
 * 用户不知道后面还有，也不会知道少了什么。
 *
 * 所以做三件事：
 *
 *   一、**给摘要**。折叠时显示"共 40 行，还有 35 行"。
 *       用户至少知道规模，能判断值不值得展开。
 *
 *   二、**给重点**。折叠时把最该看的几行留在上面
 *       （异常值、最大值、被标记的行），
 *       而不是机械取前 N 行——前 N 行未必是重点。
 *
 *   三、**记住展开状态**。用户展开过一次，
 *       下次同类卡片（同组件 + 相近规模）默认展开。
 *       不记的话每次都要重新点，等于没做。
 *
 * 关键：这个模块**不改 DSL，只产出披露建议**。
 * 改 DSL 会让"用户看到的"和"模型以为的"不一致，
 * 后面联动、补丁、事务全都对不上。
 */
object ZxDisclose {

    /** 触发阈值：超过这个行数才考虑折叠 */
    private const val MIN_ROWS = 8
    /** 折叠时保留的行数 */
    private const val KEEP = 5
    private val expanded = HashSet<String>()
    private val alwaysExpand = HashMap<String, Int>()

    data class Advice(
        /** 要不要折叠 */
        val collapse: Boolean,
        /** 折叠后保留的行（原始下标） */
        val keep: List<Int>,
        /** 折叠条文案 */
        val hint: String,
        /** 总行数 */
        val total: Int
    )

    /**
     * 从 DSL 里取出主要行数组。
     * 不同组件字段名不同，这里统一识别常见的几个。
     */
    private fun rowsOf(component: String, dsl: JSONObject): org.json.JSONArray? =
        listOf("rows", "items", "tasks", "blocks", "cells", "pts", "nodes",
               "links", "bands", "posts", "steps")
            .firstNotNullOfOrNull { dsl.optJSONArray(it) }

    /**
     * 挑出"最该看的几行"。
     * 机械取前 N 行的问题是：前 N 行未必是重点。
     */
    private fun pickImportant(arr: org.json.JSONArray, keep: Int): List<Int> {
        val n = arr.length()
        if (n <= keep) return (0 until n).toList()
        val scored = (0 until n).map { i ->
            val o = arr.optJSONObject(i)
            var s = 0
            // 有数值就按绝对值——偏离常规的值更值得看
            val v = listOf("v", "act", "d", "cnt", "s").firstNotNullOfOrNull {
                val x = o?.optDouble(it, Double.NaN); if (x?.isNaN() != false) null else x }
            if (v != null) s += (v * 100).toInt()
            // 被显式标记的行优先
            if (o?.optBoolean("mark", false) == true) s += 100000
            if (o?.optBoolean("hot", false) == true) s += 50000
            if (o?.optString("k", "") in listOf("err", "warn")) s += 40000
            i to s
        }
        return scored.sortedWith(compareByDescending<Pair<Int, Int>> { it.second }
            .thenBy { it.first })
            .take(keep).map { it.first }.sorted()
    }

    fun advise(component: String, dsl: JSONObject, heightDp: Int = 800): Advice {
        val arr = rowsOf(component, dsl)
        val n = arr?.length() ?: 0
        if (n < MIN_ROWS) return Advice(false, emptyList(), "", n)
        // 已经展开过同类卡就别再折
        val key = "$component:${n / 8}"
        if (alwaysExpand[key] ?: 0 >= 1) return Advice(false, emptyList(), "", n)
        val keep = pickImportant(arr!!, KEEP)
        val hidden = n - keep.size
        return Advice(true, keep, "共 $n 项，还有 $hidden 项已折叠", n)
    }

    /** 用户点了展开：记下来，下次同类不再折 */
    fun markExpanded(component: String, dsl: JSONObject) {
        val n = rowsOf(component, dsl)?.length() ?: 0
        if (n < MIN_ROWS) return
        val key = "$component:${n / 8}"
        alwaysExpand[key] = (alwaysExpand[key] ?: 0) + 1
        expanded.add(key)
    }

    fun markCollapsed(component: String, dsl: JSONObject) {
        val n = rowsOf(component, dsl)?.length() ?: 0
        if (n < MIN_ROWS) return
        expanded.remove("$component:${n / 8}")
    }

    /** 一次性展开（只影响这一张） */
    fun once(cardId: String) = expanded.add("once:$cardId")
    fun isOnce(cardId: String) = "once:$cardId" in expanded

    fun report(): String = buildString {
        appendLine("渐进式披露（阈值 $MIN_ROWS 项，折叠保留 $KEEP 项）")
        if (expanded.isEmpty()) appendLine("  还没有用户展开过卡片。")
        else expanded.forEach { appendLine("  · $it（同类默认展开）") }
        appendLine()
        appendLine("AI 喜欢给全——问「这季度怎么样」它画 40 行的表。")
        appendLine("而用户真正想看的往往是最上面几行。")
        appendLine("「先给全，用户自己找」是把筛选成本转嫁给用户。")
        appendLine()
        appendLine("但不能简单截断——截断丢信息，")
        appendLine("用户不知道后面还有，也不会知道少了什么。")
        appendLine()
        appendLine("三件事：给摘要、给重点（不机械取前 N 行）、记住展开状态。")
        appendLine("关键：不改 DSL，只产出披露建议——")
        appendLine("改 DSL 会让「用户看到的」和「模型以为的」不一致。")
    }

    fun guide(): String = """
        zx_disclose(op=advise|expand|collapse|report|guide, component?, card?)

        长卡自动折叠 + 按需展开。

        203 个组件之后，一个很实际的问题：**AI 喜欢给全**。

          问「这季度怎么样」，它画一张 40 行的表
          问「对比一下」，它把 12 个维度全列出来

        40 行的表在气泡里要滚很久，而用户真正想看的
        往往是最上面那几行。「先给全，用户自己找」
        是把筛选成本转嫁给了用户。

        但**不能简单截断**——截断丢信息，
        用户不知道后面还有，也不会知道少了什么。

        三件事：
          一、给摘要——「共 40 行，还有 35 行」
          二、给重点——留异常值/最大值/被标记的行，
              而不是机械取前 N 行（前 N 行未必是重点）
          三、记住展开状态——同类卡片下次默认展开

        关键：不改 DSL，只产出披露建议。
        改 DSL 会让「用户看到的」和「模型以为的」不一致，
        后面联动、补丁、事务全都对不上。
    """.trimIndent()
}
