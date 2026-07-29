package com.ai.assistance.quro.core.turn

/**
 * QuroTurnController（原创）：对话轮次（Turn）状态机。
 *
 * 每个会话独立维护一轮「生成」生命周期，支持：
 * - activate：开始一轮生成（自增代际 gen，防止过期协程误复位）
 * - complete：本轮正常结束
 * - interrupt：用户主动打断（barge-in 时先打断旧轮再开新轮）
 *
 * 代际（gen）机制保证：旧协程的 finally 回调若晚于新轮开始执行，
 * 不会把新轮错误标记为已结束（complete/interrupt 仅在 gen 匹配时生效）。
 */
class QuroTurnController {

    enum class State { IDLE, GENERATING, AWAITING_USER, INTERRUPTED }

    private data class Turn(val state: State, val gen: Long)

    private val turns = mutableMapOf<String, Turn>()
    private val gens = mutableMapOf<String, Long>()

    @Synchronized
    fun activate(id: String): Long {
        val g = (gens[id] ?: 0L) + 1
        gens[id] = g
        turns[id] = Turn(State.GENERATING, g)
        return g
    }

    @Synchronized
    fun complete(id: String, gen: Long): Boolean {
        val cur = turns[id] ?: return false
        if (cur.gen != gen) return false
        turns[id] = Turn(State.IDLE, gen)
        return true
    }

    @Synchronized
    fun interrupt(id: String, gen: Long = gens[id] ?: 0L) {
        val cur = turns[id] ?: return
        if (cur.gen != gen) return
        turns[id] = Turn(State.INTERRUPTED, gen)
    }

    @Synchronized
    fun stateOf(id: String): State = turns[id]?.state ?: State.IDLE
}
