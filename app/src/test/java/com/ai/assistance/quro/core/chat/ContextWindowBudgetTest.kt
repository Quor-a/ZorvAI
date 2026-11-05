package com.ai.assistance.quro.core.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ContextWindowCalculator] 单元测试。
 *
 * 这一层不做 tokenize，只做**结构占比**的估算。所以测试不追求「等于真实 tokenizer 的结果」，
 * 而是钉死三件事：
 * 1. 语言混合口径不能反（中文按字、英文按四字符）—— 反了会让中文用户看到「我只说了几十字却吃掉几千 token」；
 * 2. 排序必须按 token 降序 —— 面板打开第一行就是答案；
 * 3. 窗口未知（0）时不能算出 NaN / 负数 / 100% 这种会让人误判的值。
 */
class ContextWindowBudgetTest {

    // ───────────────────────── token 估算 ─────────────────────────

    @Test
    fun `空串为零`() {
        assertEquals(0, ContextWindowCalculator.estimateTokens(""))
    }

    @Test
    fun `非空至少一个 token`() {
        // 一个字的提示词算 0 token 会让「工具定义占 0%」这类假结论出现
        assertEquals(1, ContextWindowCalculator.estimateTokens("a"))
        assertEquals(1, ContextWindowCalculator.estimateTokens("好"))
    }

    @Test
    fun `中文按一字一 token`() {
        val s = "上下文窗口占用"
        assertEquals(s.length, ContextWindowCalculator.estimateTokens(s))
    }

    @Test
    fun `英文按四字符一 token`() {
        val s = "abcdefgh" // 8 字符 → 2
        assertEquals(2, ContextWindowCalculator.estimateTokens(s))
    }

    @Test
    fun `中英混排按各自口径相加`() {
        // 4 个汉字(4) + 8 个 ASCII(2) = 6
        assertEquals(6, ContextWindowCalculator.estimateTokens("上下文窗abcdefgh"))
    }

    @Test
    fun `全角标点按中文口径`() {
        // 「，」「。」都在全角区，按 1 token 计
        val s = "好，的。"
        assertEquals(s.length, ContextWindowCalculator.estimateTokens(s))
    }

    // ───────────────────────── 组装与排序 ─────────────────────────

    @Test
    fun `分段按 token 降序`() {
        val usage = ContextWindowCalculator.build(
            parts = mapOf(
                ContextSegmentKind.SYSTEM_TOOLS to "x".repeat(4000),      // ~1000
                ContextSegmentKind.SYSTEM_PROMPT to "提".repeat(3000),    // 3000
                ContextSegmentKind.MESSAGES to "y".repeat(40),            // ~10
            ),
            window = 128_000,
        )
        assertEquals(
            listOf(
                ContextSegmentKind.SYSTEM_PROMPT,
                ContextSegmentKind.SYSTEM_TOOLS,
                ContextSegmentKind.MESSAGES,
            ),
            usage.segments.map { it.kind },
        )
    }

    @Test
    fun `空分段被剔除`() {
        val usage = ContextWindowCalculator.build(
            parts = mapOf(
                ContextSegmentKind.SYSTEM_PROMPT to "有内容",
                ContextSegmentKind.MCP_TOOLS to "",
            ),
            window = 32_000,
        )
        assertEquals(1, usage.segments.size)
        assertEquals(ContextSegmentKind.SYSTEM_PROMPT, usage.segments.first().kind)
    }

    @Test
    fun `已用等于各段之和`() {
        val usage = ContextWindowCalculator.build(
            parts = mapOf(
                ContextSegmentKind.SYSTEM_PROMPT to "提".repeat(100),
                ContextSegmentKind.MESSAGES to "历".repeat(50),
            ),
            window = 8000,
        )
        assertEquals(usage.segments.sumOf { it.tokens }, usage.used)
        assertEquals(150, usage.used)
    }

    // ───────────────────────── 窗口与预算 ─────────────────────────

    @Test
    fun `窗口未知时所有派生值安全`() {
        val usage = ContextWindowCalculator.build(
            parts = mapOf(ContextSegmentKind.SYSTEM_PROMPT to "提".repeat(500)),
            window = 0,
        )
        assertEquals(0f, usage.usedRatio, 0.0001f)
        assertEquals(0, usage.free)
        assertFalse(usage.shouldCompact)
        assertFalse(usage.isCritical)
    }

    @Test
    fun `回复预留不低于 2048`() {
        // 小窗口模型（8k）若按 12% 只留 960，回复会被腰斩
        assertEquals(2048, ContextWindowCalculator.reserveForReply(8000))
        assertEquals(2048, ContextWindowCalculator.reserveForReply(4000))
    }

    @Test
    fun `回复预留按窗口百分比`() {
        assertEquals(12_000, ContextWindowCalculator.reserveForReply(100_000))
    }

    @Test
    fun `剩余被扣掉回复预留且不为负`() {
        val usage = ContextWindowCalculator.build(
            parts = mapOf(ContextSegmentKind.MESSAGES to "字".repeat(9500)),
            window = 10_000,
        )
        // 9500 已用 + 2048 预留 > 10000 → 剩余夹到 0，不能是负数
        assertEquals(0, usage.free)
        assertTrue(usage.isCritical)
        assertTrue(usage.shouldCompact)
    }

    @Test
    fun `窗口宽裕时不提示压缩`() {
        val usage = ContextWindowCalculator.build(
            parts = mapOf(ContextSegmentKind.MESSAGES to "字".repeat(1_000)),
            window = 128_000,
        )
        assertFalse(usage.shouldCompact)
        assertFalse(usage.isCritical)
    }

    @Test
    fun `占比被夹在 0 到 1`() {
        val usage = ContextWindowCalculator.build(
            parts = mapOf(ContextSegmentKind.MESSAGES to "字".repeat(100_000)),
            window = 10_000,
        )
        assertEquals(1f, usage.usedRatio, 0.0001f)
    }
}
