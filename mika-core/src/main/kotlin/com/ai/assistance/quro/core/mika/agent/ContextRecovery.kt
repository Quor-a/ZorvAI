package com.ai.assistance.quro.core.mika.agent

import com.ai.assistance.quro.core.mika.model.Author
import com.ai.assistance.quro.core.mika.model.Message
import com.ai.assistance.quro.core.mika.model.Part
import com.ai.assistance.quro.core.mika.model.Role

/**
 * 压缩后的确定性兜底:用户原话(含插话)与固定知识/技能规则一条也不能消失。
 * 比对时不看运行时状态(折叠会把它去掉,同一条消息仍算在);真的不见了的放回它在完整历史里的位置,不接到末尾。
 */
internal object ContextRecovery {
    fun ensure(full: List<Message>, compressed: List<Message>): List<Message> {
        val userCounts = compressed.filter { it.author == Author.User }.groupingBy(::userKey).eachCount().toMutableMap()
        val ruleCounts = compressed.mapNotNull(::ruleKey).groupingBy { it }.eachCount().toMutableMap()
        fun <K> MutableMap<K, Int>.take(key: K): Boolean {
            val left = this[key] ?: 0
            if (left > 0) this[key] = left - 1
            return left > 0
        }
        val missing = full.indices.filter { i ->
            val m = full[i]
            if (m.author == Author.User) !userCounts.take(userKey(m)) else ruleKey(m)?.let { !ruleCounts.take(it) } ?: false
        }
        if (missing.isEmpty()) return compressed
        val origins = origins(full, compressed)
        val out = compressed.toMutableList()
        val at = origins.toMutableList<Int?>()
        for (i in missing) {
            // 放在第一条出自它之后的消息前面;不夹进调用与结果之间
            var pos = at.indexOfFirst { it != null && it > i }.let { if (it < 0) out.size else it }
            while (pos < out.size && out[pos].role == Role.Tool) pos++
            out.add(pos, ContextBuilder.withoutRuntimeState(full[i]))
            at.add(pos, i)
        }
        return out
    }

    /** 压缩后的每条消息出自完整历史的哪一条(按调用编号或原样认;摘要等新写的为 null)。 */
    private fun origins(full: List<Message>, compressed: List<Message>): List<Int?> {
        val byId = HashMap<String, Int>()
        val byKey = HashMap<Message, ArrayDeque<Int>>()
        full.forEachIndexed { i, m ->
            ids(m).forEach { byId.putIfAbsent(it, i) }
            byKey.getOrPut(userKey(m)) { ArrayDeque() }.addLast(i)
        }
        return compressed.map { m -> ids(m).firstNotNullOfOrNull(byId::get) ?: byKey[userKey(m)]?.removeFirstOrNull() }
    }

    private fun ids(m: Message): List<String> = m.parts.mapNotNull {
        when (it) {
            is Part.ToolCall -> it.id
            is Part.ToolResult -> it.callId
            else -> null
        }
    }

    private fun userKey(m: Message): Message = ContextBuilder.withoutRuntimeState(m)

    private fun ruleKey(message: Message): Pair<ContextKind, String?>? =
        ContextBuilder.kindOf(message)?.takeIf { it.pinned }?.let { it to ContextBuilder.sourceOf(message) }
}
