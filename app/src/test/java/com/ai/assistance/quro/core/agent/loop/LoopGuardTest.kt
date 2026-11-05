package com.ai.assistance.quro.core.agent.loop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 工具调用打转检测的回归测试。
 *
 * 关键要求：**不能误杀合法长任务**。排查类任务每步都是新命令（签名不同），
 * 只有「同样的工具 + 同样的参数」反复发才算打转。
 */
class LoopGuardTest {

    @Test
    fun `fresh calls never trip`() {
        val g = LoopGuard()
        repeat(20) { i ->
            assertEquals(LoopGuard.Decision.Continue, g.onRound(listOf("read_file|path=$i")))
        }
        assertTrue(!g.hasNudged)
    }

    @Test
    fun `text only round is treated as wrapping up`() {
        val g = LoopGuard()
        repeat(10) { g.onRound(listOf("t|a")) }
        // 中间出现一轮纯文本（模型在出结论）→ 计数清零
        assertEquals(LoopGuard.Decision.Continue, g.onRound(emptyList()))
        assertEquals(LoopGuard.Decision.Continue, g.onRound(listOf("t|a")))
    }

    @Test
    fun `same call repeated nudges then stops`() {
        val g = LoopGuard()
        assertEquals(LoopGuard.Decision.Continue, g.onRound(listOf("terminal|{\"action\":\"status\"}")))
        // 第二次发同一调用 → 立刻给一次收尾提示
        assertEquals(LoopGuard.Decision.Nudge, g.onRound(listOf("terminal|{\"action\":\"status\"}")))
        assertEquals(LoopGuard.Decision.Continue, g.onRound(listOf("terminal|{\"action\":\"status\"}")))
        // 连续第 3 次 → 停
        assertEquals(LoopGuard.Decision.Stop, g.onRound(listOf("terminal|{\"action\":\"status\"}")))
        assertTrue(g.hasNudged)
    }

    @Test
    fun `hard stop regardless of repetition`() {
        val g = LoopGuard(hardStopAt = 8)
        var last = LoopGuard.Decision.Continue
        repeat(10) { i ->
            last = g.onRound(listOf("cmd|$i")) // 每次都是新签名，不会触发重复判定
            if (last == LoopGuard.Decision.Stop) return@repeat
        }
        assertEquals("到硬上限必须停，避免无限轮次", LoopGuard.Decision.Stop, last)
    }

    @Test
    fun `window forgets old signatures`() {
        val g = LoopGuard(window = 3)
        assertEquals(LoopGuard.Decision.Continue, g.onRound(listOf("a|1")))
        assertEquals(LoopGuard.Decision.Continue, g.onRound(listOf("b|1")))
        assertEquals(LoopGuard.Decision.Continue, g.onRound(listOf("c|1")))
        assertEquals(LoopGuard.Decision.Continue, g.onRound(listOf("d|1")))
        // 早已滑出窗口的 a|1 重新出现不应被判为打转
        assertEquals(LoopGuard.Decision.Continue, g.onRound(listOf("a|1")))
    }
}
