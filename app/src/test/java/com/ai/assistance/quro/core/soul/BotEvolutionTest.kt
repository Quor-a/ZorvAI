package com.ai.assistance.quro.core.soul

import com.ai.assistance.quro.core.QuroPersona
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BotEvolutionTest {

    @Test
    fun `未攒够轮数不触发检查`() {
        val state = EvolutionState(completedTurns = 9)
        assertFalse(BotEvolution.shouldCheck(state, EvolutionFrequency.STANDARD))
    }

    @Test
    fun `攒够轮数且不在冷却则触发`() {
        val state = EvolutionState(completedTurns = 10, lastCheckedAt = 0)
        assertTrue(BotEvolution.shouldCheck(state, EvolutionFrequency.STANDARD))
    }

    @Test
    fun `冷却窗口内不触发`() {
        val now = 1_000_000_000_000L
        // 刚检查过（lastCheckedAt = now），标准档冷却 6 小时
        val state = EvolutionState(completedTurns = 10, lastCheckedAt = now)
        assertFalse(BotEvolution.shouldCheck(state, EvolutionFrequency.STANDARD, now))
        // 过了 6 小时后触发
        assertTrue(BotEvolution.shouldCheck(state, EvolutionFrequency.STANDARD, now + 6 * 3600_000L + 1))
    }

    @Test
    fun `失败后退避窗口内不触发`() {
        val now = 1_000_000_000_000L
        val state = EvolutionState(completedTurns = 10, retryAfter = now + 3600_000L)
        assertFalse(BotEvolution.shouldCheck(state, EvolutionFrequency.STANDARD, now))
        assertTrue(BotEvolution.shouldCheck(state, EvolutionFrequency.STANDARD, now + 3600_000L + 1))
    }

    @Test
    fun `解析合法产出`() {
        val out = BotEvolution.parseOutput(
            "[SOUL]\n你开始习惯用更轻松的语气和我说话。\n[FACTS]\n用户喜欢咖啡\n- 用户每天下午 3 点开会\n"
        )
        assertNotNull(out)
        assertEquals("你开始习惯用更轻松的语气和我说话。", out!!.soulDelta)
        assertEquals(listOf("用户喜欢咖啡", "用户每天下午 3 点开会"), out.memoryFacts)
        assertEquals("soul_memory", out.source)
    }

    @Test
    fun `解析空产出`() {
        val out = BotEvolution.parseOutput("[SOUL]\n无\n[FACTS]\n无\n")
        assertNotNull(out)
        assertEquals("", out!!.soulDelta)
        assertTrue(out.memoryFacts.isEmpty())
    }

    @Test
    fun `非法格式返回 null`() {
        assertNull(BotEvolution.parseOutput("乱七八糟没有标记的内容"))
        assertNull(BotEvolution.parseOutput("[FACTS]\n只有事实没有 soul"))
    }

    @Test
    fun `mergeSoul 追加与空增量`() {
        assertEquals("", BotEvolution.mergeSoul("", ""))
        assertEquals("第一段", BotEvolution.mergeSoul("", "第一段"))
        assertEquals("旧画像\n新画像", BotEvolution.mergeSoul("旧画像", "新画像"))
    }

    @Test
    fun `onTurnCompleted 与 onChecked 状态推进`() {
        val now = 1_000_000_000_000L
        val s1 = BotEvolution.onTurnCompleted(EvolutionState())
        assertEquals(1, s1.completedTurns)

        val s2 = BotEvolution.onChecked(EvolutionState(completedTurns = 10, lastFailure = "x", retryAfter = 123L), now)
        assertEquals(0, s2.completedTurns)
        assertEquals(now, s2.lastCheckedAt)
        assertEquals("", s2.lastFailure)
        assertEquals(0, s2.retryAfter)
    }

    @Test
    fun `buildPrompt 包含现有人格画像`() {
        val persona = QuroPersona(name = "测试", soul = "现有的画像")
        val prompt = BotEvolution.buildPrompt(persona, "用户：你好")
        assertTrue(prompt.contains("[SOUL]"))
        assertTrue(prompt.contains("[FACTS]"))
        assertTrue(prompt.contains("现有的画像"))
    }
}
