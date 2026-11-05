package com.ai.assistance.quro.core.mika.memory

import com.ai.assistance.quro.core.mika.agent.TokenEstimate

/** [message] 为 null 表示这次没有记忆可带;[usedIds] 是块里实际列出的条目,顺序同列表。 */
data class MemoryBlock(val message: String?, val usedIds: List<String>)

/**
 * 每个任务的记忆块:作为一条用户消息放在对话上文之后、任务原文之前。
 *
 * 两种给法:
 * - [Mode.Relevant]:置顶的 + 过阈值的检索结果(已批准的做法单列且有上限),总长受 [MAX_TOKENS] 约束。记忆多的老做法。
 * - [Mode.Full]:一条不落全给(做法仍受 [MAX_PROCEDURES] 约束),总长受 [FULL_MAX_TOKENS] 约束。
 *
 * 产品的默认是 [Mode.Auto]:记忆加起来没超过 [FULL_MAX_TOKENS] 就全量注入(做法也全给),超了才退回检索。
 * 记忆本来就不多(几十条、几千字),而现在的模型上下文动辄几十万;全量注入不会漏,
 * 前缀还固定,命中上下文缓存后比"每次检索出来的都不一样"更便宜。
 * 但记忆涨到几万字以后全塞会把上下文吃光,所以保留检索这条退路。
 */
object MemoryContext {
    const val MAX_TOKENS = 800
    /** 全量注入的总上限;超过就说明该退回检索了(见 [Mode.Auto])。 */
    const val FULL_MAX_TOKENS = 4_000
    const val MAX_PINNED = 5
    const val MAX_RELEVANT = 8
    const val MAX_PROCEDURES = 3
    const val MIN_SCORE = 0.5

    /** [Relevant]:只带置顶与检索到的;[Full]:全量(超预算时丢最旧的);[Auto]:能全量就全量,否则退回 [Relevant]。 */
    enum class Mode { Relevant, Full, Auto }

    private const val FACTS_HEADER = "Memories about the user (things the user told you; may be out of date; when they conflict with what the user says now, follow what they say now):"
    private const val PROCEDURES_HEADER = "Ways of doing things the user approved (when they conflict with what the user says now, follow what they say now):"

    /**
     * [rewrite]:做法里提到的旧工具名换成现在的(见 LegacyNames.rewrite);默认原样。
     * [mode] 默认保持老的检索行为;产品路径(AgentLoop)显式传 [Mode.Auto]。
     */
    fun build(
        items: List<MemoryItem>,
        goal: String,
        lastUserTurn: String?,
        rewrite: (String) -> String = { it },
        mode: Mode = Mode.Relevant,
    ): MemoryBlock {
        val active = items.filter { it.status == MemoryStatus.active }
        if (active.isEmpty()) return MemoryBlock(null, emptyList())
        if (mode == Mode.Full) full(active, rewrite)?.let { return it }
        // Auto:装得下就一条不落全给;装不下(记忆已经很多)退回检索,不硬塞几万字进上下文
        if (mode == Mode.Auto) fullIfFits(active, rewrite)?.let { return it }

        val query = listOfNotNull(goal, lastUserTurn).joinToString("\n")
        val ranked = MemoryIndex(active).search(query, limit = active.size)
        val score = ranked.associate { it.first.id to it.second }

        // 做法的上限把置顶的也算在内
        var procedures = 0
        fun procedureFits(m: MemoryItem) = m.kind != MemoryKind.procedure || procedures < MAX_PROCEDURES

        val pinned = mutableListOf<MemoryItem>()
        for (m in active.filter { it.pinned }
            .sortedWith(compareByDescending<MemoryItem> { score[it.id] ?: 0.0 }.thenByDescending { it.updatedAt })) {
            if (pinned.size == MAX_PINNED) break
            if (!procedureFits(m)) continue
            if (m.kind == MemoryKind.procedure) procedures++
            pinned += m
        }
        val relevant = mutableListOf<MemoryItem>()
        for ((item, s) in ranked) {
            if (s < MIN_SCORE || relevant.size == MAX_RELEVANT) break
            if (item.pinned || !procedureFits(item)) continue
            if (item.kind == MemoryKind.procedure) procedures++
            relevant += item
        }

        // 保留优先级从高到低:置顶(按分数)在前,相关(按分数)在后;超长从末尾去掉
        val keep = (pinned + relevant).toMutableList()
        while (keep.isNotEmpty()) {
            val (message, ids) = render(keep, rewrite)
            if (TokenEstimate.of(message) <= MAX_TOKENS) return MemoryBlock(message, ids)
            keep.removeAt(keep.lastIndex)
        }
        return MemoryBlock(null, emptyList())
    }

    /**
     * 全量:置顶的在前,其余按更新时间倒序;做法最多 [MAX_PROCEDURES] 条。
     * 超 [FULL_MAX_TOKENS] 时先丢最旧的普通条目,再丢做法;全丢光了还超就返回 null(交给检索)。
     */
    private fun full(active: List<MemoryItem>, rewrite: (String) -> String): MemoryBlock? {
        val (allFacts, allProcedures) = ordered(active)
        val facts = allFacts.toMutableList()
        val procedures = allProcedures.toMutableList()
        while (facts.isNotEmpty() || procedures.isNotEmpty()) {
            val (message, ids) = render(facts + procedures, rewrite)
            if (TokenEstimate.of(message) <= FULL_MAX_TOKENS) return MemoryBlock(message, ids)
            if (facts.isNotEmpty()) facts.removeAt(facts.lastIndex) else procedures.removeAt(procedures.lastIndex)
        }
        return null
    }

    /**
     * 装得下才全量;装不下返回 null,由调用方退回检索。
     * 做法也一条不落:按更新时间截前几条会丢掉和这次任务相关的那条(截断只在检索里按相关度做)。
     */
    private fun fullIfFits(active: List<MemoryItem>, rewrite: (String) -> String): MemoryBlock? {
        val (facts, procedures) = ordered(active, maxProcedures = Int.MAX_VALUE)
        val (message, ids) = render(facts + procedures, rewrite)
        return if (TokenEstimate.of(message) <= FULL_MAX_TOKENS) MemoryBlock(message, ids) else null
    }

    private fun ordered(active: List<MemoryItem>, maxProcedures: Int = MAX_PROCEDURES): Pair<List<MemoryItem>, List<MemoryItem>> {
        val sorted = active.sortedWith(compareByDescending<MemoryItem> { it.pinned }.thenByDescending { it.updatedAt })
        return sorted.filter { it.kind != MemoryKind.procedure } to
            sorted.filter { it.kind == MemoryKind.procedure }.take(maxProcedures)
    }

    private fun render(items: List<MemoryItem>, rewrite: (String) -> String): Pair<String, List<String>> {
        val (procedures, facts) = items.partition { it.kind == MemoryKind.procedure }
        val sections = listOf(FACTS_HEADER to facts, PROCEDURES_HEADER to procedures).filter { it.second.isNotEmpty() }
        val message = sections.joinToString("\n\n") { (header, list) ->
            header + "\n" + list.withIndex().joinToString("\n") { (i, m) ->
                "${i + 1}. ${if (m.kind == MemoryKind.procedure) rewrite(m.content) else m.content}"
            }
        }
        return message to (facts + procedures).map { it.id }
    }
}
