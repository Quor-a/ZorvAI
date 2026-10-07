package com.ai.assistance.quro.cluster.engine.orchestration

import com.ai.assistance.quro.cluster.engine.EngineEnv
import com.ai.assistance.quro.cluster.model.*
import kotlin.math.max

/**
 * 选人路由器 —— 主持的"人事参谋"。
 *
 * 打分 = 职责/关键词重合(0.45) + 技能命中(0.20) + 历史成功率(0.20) + 负载与预算余量(0.15)
 * 被排除的：主持本人、已停用、模型不可用、本任务已超预算、本轮已发过言（避免刷屏）。
 */
class Router(private val env: EngineEnv) {

    data class Score(val agentId: AgentId, val score: Float, val reason: String)

    suspend fun rank(
        clusterId: ClusterId,
        taskId: TaskId,
        node: TaskNode,
        candidates: List<AgentConfig>,
        exclude: Set<AgentId> = emptySet(),
        profiles: Map<AgentId, com.ai.assistance.quro.cluster.engine.memory.AgentProfile> = emptyMap()
    ): List<Score> {
        val query = (node.title + " " + node.instruction).lowercase()
        val terms = tokenize(query)
        val models = env.gateway.models()
        val spent = env.store.budgetOf(taskId).groupBy { it.agentId }
            .mapValues { (_, v) -> v.sumOf { it.tokensOut.toLong() } }

        return candidates.asSequence()
            .filter { it.enabled }
            .filter { it.id != HOST_AGENT_ID }
            .filter { it.id !in exclude }
            .filter { a ->
                models.any { it.id == a.modelBinding.hostModelId && it.available } ||
                    a.modelBinding.fallbackChain.any { f -> models.any { it.id == f && it.available } }
            }
            .map { a ->
                var s = 0f
                val reasons = ArrayList<String>()

                val dutyText = a.routingText().lowercase()
                val hit = terms.count { dutyText.contains(it) }
                val dutyScore = (hit.toFloat() / max(1, terms.size)).coerceAtMost(1f)
                s += dutyScore * 0.45f
                if (hit > 0) reasons += "职责命中${hit}项"

                val skillHit = a.skillIds.count { sid ->
                    dutyText.contains(sid.value.lowercase())
                }
                s += (if (skillHit > 0) 0.20f else 0f)
                if (skillHit > 0) reasons += "技能命中"

                val p = profiles[a.id]
                if (p != null) {
                    s += p.successRate * 0.20f
                    reasons += "历史成功率${"%.0f".format(p.successRate * 100)}%"
                }

                val used = spent[a.id] ?: 0L
                val quota = a.modelBinding.maxTokensPerTask
                val budgetLeft = if (quota <= 0) 1f else (1f - (used.toFloat() / quota)).coerceIn(0f, 1f)
                s += 0.15f * budgetLeft
                if (budgetLeft < 0.2f) reasons += "预算吃紧"
                if (a.id == node.assignee) { s += 0.5f; reasons += "节点指定" }

                Score(a.id, s, reasons.joinToString("，").ifBlank { "综合相关度" })
            }
            .sortedByDescending { it.score }
            .toList()
    }

    suspend fun pick(
        clusterId: ClusterId,
        taskId: TaskId,
        node: TaskNode,
        candidates: List<AgentConfig>,
        topK: Int,
        exclude: Set<AgentId> = emptySet(),
        profiles: Map<AgentId, com.ai.assistance.quro.cluster.engine.memory.AgentProfile> = emptyMap()
    ): List<Score> = rank(clusterId, taskId, node, candidates, exclude, profiles).take(topK)

    private fun tokenize(s: String): List<String> =
        s.split(Regex("[\\s,，。、；;：:（）()\\[\\]]+"))
            .filter { it.length >= 2 }
            .distinct()
            .take(24)
}
