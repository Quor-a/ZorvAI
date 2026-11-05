package com.ai.assistance.quro.core.chat

import com.ai.assistance.quro.core.QuroLlmMeta
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [TurnOutcomeResolver] 单元测试。
 *
 * 覆盖目标：把「这一轮为什么没答上来」的**判定优先级**钉死。这层判错的代价是
 * 用户拿到错误的处置建议 —— 例如把「模型只思考没回答」判成「网络失败」，
 * 用户会一直重试网络，而真正该做的是换个模型。
 *
 * 重点覆盖三类容易写反的地方：
 * 1. **优先级顺序**（用户停止 > 异常 > 风控 > 截断 > 空正文）；
 * 2. **正文非空时不许判失败**（哪怕工具全挂了，用户读到的内容才是结论）；
 * 3. **边界面**（畸形调用 1 次 vs 2 次、截断字数阈值）。
 */
class TurnOutcomeResolverTest {

    // ───────────────────────── 正常与用户意图 ─────────────────────────

    @Test
    fun `正文非空即正常`() {
        val o = TurnOutcomeResolver.resolve(TurnSnapshot(visibleChars = 12))
        assertEquals(TurnOutcome.OK, o)
        assertFalse(o.isFailure)
    }

    @Test
    fun `用户停止优先于异常`() {
        // 停止与异常同时成立时，必须报「你停止的」，不能报「失败了」——
        // 否则用户会以为系统出故障，实际是他自己按的停止。
        val o = TurnOutcomeResolver.resolve(
            TurnSnapshot(stoppedByUser = true, errorMessage = "SocketTimeout", visibleChars = 0)
        )
        assertEquals(TurnOutcome.STOPPED_BY_USER, o)
    }

    @Test
    fun `被新轮顶掉不算故障`() {
        val o = TurnOutcomeResolver.resolve(TurnSnapshot(superseded = true))
        assertEquals(TurnOutcome.SUPERSEDED, o)
    }

    // ───────────────────────── 上游与长度 ─────────────────────────

    @Test
    fun `风控拦截单独归因`() {
        listOf(QuroLlmMeta.F_CONTENT_FILTER, QuroLlmMeta.F_REFUSAL).forEach { fr ->
            assertEquals(
                "finish_reason=$fr 应判为风控",
                TurnOutcome.CONTENT_FILTERED,
                TurnOutcomeResolver.resolve(TurnSnapshot(finishReason = fr)),
            )
        }
    }

    @Test
    fun `截断但内容很短判截断`() {
        val o = TurnOutcomeResolver.resolve(
            TurnSnapshot(finishReason = QuroLlmMeta.F_LENGTH, visibleChars = 80)
        )
        assertEquals(TurnOutcome.OUTPUT_TRUNCATED, o)
    }

    @Test
    fun `撞到长度上限但已写出实质内容仍算正常`() {
        // 用户已经读到一大段有意义的回答，「被截断」只是尾巴的事，
        // 这里判失败会让一次成功的长回答看起来像故障。
        val o = TurnOutcomeResolver.resolve(
            TurnSnapshot(finishReason = QuroLlmMeta.F_LENGTH, visibleChars = 1200)
        )
        assertEquals(TurnOutcome.OK, o)
    }

    @Test
    fun `截断阈值边界`() {
        // 219 → 截断；220 → 正常（阈值是「小于 220」）
        assertEquals(
            TurnOutcome.OUTPUT_TRUNCATED,
            TurnOutcomeResolver.resolve(TurnSnapshot(finishReason = QuroLlmMeta.F_LENGTH, visibleChars = 219)),
        )
        assertEquals(
            TurnOutcome.OK,
            TurnOutcomeResolver.resolve(TurnSnapshot(finishReason = QuroLlmMeta.F_LENGTH, visibleChars = 220)),
        )
    }

    // ───────────────────────── 空正文的三种成因 ─────────────────────────

    @Test
    fun `只有思考没有正文`() {
        val o = TurnOutcomeResolver.resolve(TurnSnapshot(visibleChars = 0, reasoningChars = 900))
        assertEquals(TurnOutcome.REASONING_ONLY, o)
    }

    @Test
    fun `工具跑完模型没接话`() {
        val o = TurnOutcomeResolver.resolve(
            TurnSnapshot(visibleChars = 0, toolCalls = 3, toolFailures = 0)
        )
        assertEquals(TurnOutcome.EMPTY_AFTER_TOOLS, o)
    }

    @Test
    fun `工具全失败判不可执行`() {
        val o = TurnOutcomeResolver.resolve(
            TurnSnapshot(visibleChars = 0, toolCalls = 2, toolFailures = 2)
        )
        assertEquals(TurnOutcome.UNEXECUTABLE_TOOL_CALL, o)
    }

    @Test
    fun `部分工具失败但没正文仍判模型没接话`() {
        // 有成功也有失败 → 环境没坏透，更可能是模型没接着往下说。
        val o = TurnOutcomeResolver.resolve(
            TurnSnapshot(visibleChars = 0, toolCalls = 3, toolFailures = 1)
        )
        assertEquals(TurnOutcome.EMPTY_AFTER_TOOLS, o)
    }

    @Test
    fun `彻底空回复`() {
        assertEquals(TurnOutcome.EMPTY_RESPONSE, TurnOutcomeResolver.resolve(TurnSnapshot()))
    }

    // ───────────────────────── 畸形工具调用 ─────────────────────────

    @Test
    fun `畸形调用达到阈值才归因`() {
        val once = TurnOutcomeResolver.resolve(TurnSnapshot(malformedToolCalls = 1))
        assertEquals("单次畸形不足以说明是系统性问题", TurnOutcome.EMPTY_RESPONSE, once)

        val twice = TurnOutcomeResolver.resolve(TurnSnapshot(malformedToolCalls = 2))
        assertEquals(TurnOutcome.MALFORMED_TOOL_CALL, twice)
    }

    @Test
    fun `畸形调用优先于空正文`() {
        val o = TurnOutcomeResolver.resolve(
            TurnSnapshot(visibleChars = 0, malformedToolCalls = 3)
        )
        assertEquals(TurnOutcome.MALFORMED_TOOL_CALL, o)
    }

    // ───────────────────────── 占位符口径 ─────────────────────────

    @Test
    fun `渲染占位符不算正文`() {
        // `(已思考完毕)` 是助手侧的兜底文案，不是模型说的话。
        // 若把它算成正文，「只思考没回答」会被洗成正常回复，归因彻底失效。
        assertTrue(TurnOutcomeResolver.NO_CONTENT_PLACEHOLDERS.contains("(已思考完毕)"))
    }

    @Test
    fun `失败态集合覆盖除正常外的全部`() {
        TurnOutcome.entries.filter { it != TurnOutcome.OK }.forEach {
            assertTrue("$it 应被视为失败态", it.isFailure)
        }
        assertFalse(TurnOutcome.OK.isFailure)
    }
}
