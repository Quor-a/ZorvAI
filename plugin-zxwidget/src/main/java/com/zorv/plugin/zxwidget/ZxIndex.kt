package com.zorv.plugin.zxwidget

import org.json.JSONObject

/**
 * 跨会话卡片索引 —— 按内容搜历史卡片。
 *
 * ⑩ 做了跨会话持久化：卡片状态能存下来，重开会话能还原。
 * 但只解决了"我知道是哪张卡"的情况——
 * 靠 cardId 取。
 *
 * 真实情况是用户（和模型）**不记得 cardId**，
 * 只记得内容：
 *
 *   用户：「上周那张预算表，再加一列」
 *   模型：？
 *
 * 它现在唯一的办法是把历史卡片读进上下文逐一找。
 * 一次会话十几张卡、跨会话上百张，全读进来上下文就满了——
 * 而它真正需要的只是"哪一张"和"那张的哪个字段"。
 *
 * 索引要解决的是：**不读全文就能定位**。
 *
 * 三处必须想清楚：
 *
 *   一、**摘要必须短**。索引的价值是省上下文，
 *       如果一条摘要 200 token，搜 10 条就 2000 了，本末倒置。
 *       所以摘要只留：组件名 + 标题 + 关键数值字段名，
 *       不存具体数值（具体数值查到之后再按 cardId 取）。
 *
 *   二、**匹配要给理由**。只返回 cardId 的话，模型无法判断
 *       这是不是它要的那张（可能有三张都叫"预算"）。
 *       带上"命中了哪个词、命中几次"，它才能自己选。
 *
 *   三、**过期数据要能清**。卡片删了/会话删了，
 *       索引里还留着，搜出来一张已经不存在的卡，
 *       比搜不到更糟——模型会按它去取，然后拿到空。
 */
object ZxIndex {

    data class Entry(
        val cardId: String,
        val component: String,
        /** 标题/摘要，短 */
        val title: String,
        /** 参与检索的文本（组件名 + 标题 + 字段名 + 关键词） */
        val hay: String,
        val sessionId: String,
        val t: Long = System.currentTimeMillis(),
        /** 卡片当前的字段路径清单，供 zx_query 精确定位 */
        val paths: List<String> = emptyList()
    )

    private val entries = LinkedHashMap<String, Entry>()
    private const val MAX = 300

    /** 摘要上限：索引的价值是省上下文，摘要太长就本末倒置了 */
    private const val TITLE_MAX = 40

    fun put(cardId: String, component: String, title: String, dsl: JSONObject,
            sessionId: String = "") {
        val t = title.replace(Regex("\\s+"), " ").trim()
            .let { if (it.length > TITLE_MAX) it.take(TITLE_MAX) + "…" else it }
        val fields = collectFields(dsl)
        entries[cardId] = Entry(
            cardId = cardId,
            component = component,
            title = t.ifBlank { component },
            // 参与检索的文本：组件名 + 标题 + 字段名，不含具体数值
            hay = (listOf(component, t) + fields).joinToString(" ").lowercase(),
            sessionId = sessionId,
            paths = fields
        )
        if (entries.size > MAX) {
            val oldest = entries.values.minByOrNull { it.t }
            if (oldest != null) entries.remove(oldest.cardId)
        }
    }

    /** 收集字段名（不取值）——具体数值查到之后再按 cardId 取 */
    private fun collectFields(o: JSONObject, depth: Int = 0): List<String> {
        if (depth > 3) return emptyList()
        val out = mutableListOf<String>()
        val it = o.keys()
        while (it.hasNext()) {
            val k = it.next()
            out.add(k)
            when (val v = o.opt(k)) {
                is JSONObject -> out.addAll(collectFields(v, depth + 1))
                is org.json.JSONArray -> {
                    val first = v.optJSONObject(0)
                    if (first != null) out.addAll(collectFields(first, depth + 1))
                }
            }
        }
        return out.distinct().take(24)
    }

    /**
     * 搜索。返回带命中理由——只给 cardId 的话，
     * 模型无法判断这是不是它要的那张。
     */
    fun search(q: String, topK: Int = 6): List<Pair<Entry, Int>> {
        val keys = q.lowercase().split(Regex("[\\s,，、]+")).filter { it.length >= 1 }
        if (keys.isEmpty()) return emptyList()
        return entries.values.mapNotNull { e ->
            var score = 0
            keys.forEach { k ->
                if (e.hay.contains(k)) score += if (e.component.contains(k)) 3 else 1
                if (e.title.lowercase().contains(k)) score += 2
            }
            if (score == 0) null else e to score
        }.sortedWith(compareByDescending<Pair<Entry, Int>> { it.second }
            .thenByDescending { it.first.t })
            .take(topK)
    }

    /** 供模型读的检索结果，刻意保持很短 */
    fun format(q: String, topK: Int = 6): String {
        val r = search(q, topK)
        if (r.isEmpty()) return "没有匹配「$q」的卡片（索引里共 ${entries.size} 张）。"
        return buildString {
            appendLine("匹配「$q」的卡片 ${r.size} 张（索引共 ${entries.size} 张）：")
            r.forEach { (e, s) ->
                appendLine("  ${e.cardId}  ${e.component}  「${e.title}」  命中 $s")
                if (e.paths.isNotEmpty())
                    appendLine("    字段：${e.paths.take(8).joinToString(" / ")}")
            }
            appendLine()
            appendLine("拿到 cardId 后用 zx_query 取具体数值——摘要里不带数值，")
            appendLine("因为索引的价值是省上下文，带上就本末倒置了。")
        }
    }

    /** 会话删除时清掉对应条目 */
    fun dropSession(sessionId: String): Int {
        val hit = entries.values.filter { it.sessionId == sessionId }
        hit.forEach { entries.remove(it.cardId) }
        return hit.size
    }

    fun remove(cardId: String): Boolean = entries.remove(cardId) != null

    fun size() = entries.size

    /** 自检：索引条目是否还指向存在的卡片 */
    fun audit(exists: (String) -> Boolean): List<String> =
        entries.values.filter { !exists(it.cardId) }
            .map { "索引里有 ${it.cardId}，但卡片已不存在——搜出来一张不存在的卡比搜不到更糟" }

    fun clear() = entries.clear()

    fun report(): String = buildString {
        appendLine("索引 ${entries.size} 条（上限 $MAX，超出按最旧淘汰）")
        appendLine("摘要上限 $TITLE_MAX 字 · 只存字段名不存具体数值")
        appendLine()
        appendLine("⑩ 解决了「我知道是哪张卡」，靠 cardId 取。")
        appendLine("但真实情况是模型不记得 cardId，只记得内容：")
        appendLine()
        appendLine("  用户：「上周那张预算表，再加一列」")
        appendLine("  模型：？")
        appendLine()
        appendLine("它唯一的办法是把历史卡片读进上下文逐一找。")
        appendLine("一次会话十几张、跨会话上百张，全读进来上下文就满了——")
        appendLine("而它真正需要的只是「哪一张」。")
    }

    fun guide(): String = """
        zx_index(op=put|search|drop|audit|report|guide, q?, card?, session?, topK?)

        按内容搜历史卡片，不读全文就能定位。

        ⑩ 解决了「我知道是哪张卡」，靠 cardId 取。
        但真实情况是模型不记得 cardId，只记得内容：

          用户：「上周那张预算表，再加一列」
          模型：？

        它唯一的办法是把历史卡片读进上下文逐一找。
        一次会话十几张、跨会话上百张，全读进来上下文就满了——
        而它真正需要的只是「哪一张」。

        三处必须想清楚：
          一、摘要必须短——索引的价值是省上下文，
              摘要 200 token 搜 10 条就 2000 了，本末倒置。
              只留组件名 + 标题 + 字段名，不存具体数值
          二、匹配要给理由——只返回 cardId 的话，
              模型无法判断这是不是它要的那张（可能有三张都叫「预算」）
          三、过期数据要能清——卡片删了索引里还留着，
              搜出来一张已经不存在的卡，比搜不到更糟
    """.trimIndent()
}
