package com.ai.assistance.quro.core.mika.agent

import com.ai.assistance.quro.core.mika.model.Message
import com.ai.assistance.quro.core.mika.model.Role
import com.ai.assistance.quro.core.mika.model.Usage
import kotlin.math.ceil

/**
 * 下一次请求整份有多大(系统提示词 + 工具定义 + 消息,和 [ContextLimits] 同一口径),固定开销只算一次:
 *
 * - 有锚点(同一模型最近一条带真实用量的回复):那次请求的输入 token(服务商报的,已含当时的提示词和工具定义)
 *   + 这条回复里会再发出去的输出(输出减去服务商单独报出的思考 token,思考不算上下文增长)
 *   + 其后消息的估算 + 固定开销自那次以来的变化(展开了一组工具之类)的估算。
 * - 没有锚点(第一步、换了模型、刚压缩过):消息 + 固定开销,全部估算。
 *
 * 估算一律是 [TokenEstimate] 乘同一个校准比值 [scale](见 [ContextCompressor.scale])。
 */
internal object UsageMeter {
    /** 一条回复的真实用量,和发出那次请求时固定开销的估算(未乘比值)。 */
    data class Anchor(val usage: Usage, val overhead: Int)

    fun of(messages: List<Message>, overhead: Int, scale: Double, anchorOf: (Message) -> Anchor? = { null }): Int {
        val at = messages.indexOfLast { it.role == Role.Assistant && anchorOf(it) != null }
        if (at < 0) return estimate(TokenEstimate.of(messages) + overhead, scale)
        val a = anchorOf(messages[at])!!
        val later = TokenEstimate.of(messages.subList(at + 1, messages.size)) + (overhead - a.overhead)
        return a.usage.inputTokens + resent(a.usage) + estimate(later, scale)
    }

    /** 回复里下一次会作为输入再发出去的部分:输出减去单独报出的思考 token。 */
    fun resent(u: Usage): Int = (u.outputTokens - u.reasoningTokens).coerceAtLeast(0)

    fun estimate(tokens: Int, scale: Double): Int = ceil(tokens * scale).toInt()
}
