package com.ai.assistance.quro.core.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextCompactorTest {

    private data class Msg(override val role: String, override val textContent: String) : QuroMessageLike

    private val sample = listOf(
        Msg("system", "你是助手"),
        Msg("user", "你好"),
        Msg("assistant", "你好，有什么可以帮你？"),
        Msg("user", "帮我写个函数"),
        Msg("assistant", "好的，函数如下："),
    )

    @Test
    fun digest_isStableForSameContent() {
        val a = ContextCompactor.digest(sample)
        val b = ContextCompactor.digest(sample.map { it.copy() })
        assertEquals(a, b)
    }

    @Test
    fun digest_changesWhenContentChanges() {
        val a = ContextCompactor.digest(sample)
        val changed = sample.toMutableList().also { it[1] = Msg("user", "你好啊") }
        val b = ContextCompactor.digest(changed)
        assertNotEquals(a, b)
    }

    @Test
    fun digest_ignoresOrderIndependentFields() {
        // role 与 content 决定指纹；这里验证同内容不同引用仍一致（上面已覆盖），
        // 再验证 role 差异会导致指纹变化。
        val a = ContextCompactor.digest(listOf(Msg("user", "hi")))
        val b = ContextCompactor.digest(listOf(Msg("assistant", "hi")))
        assertNotEquals(a, b)
    }

    @Test
    fun estimateTokens_countsCjkAndAscii() {
        val cjk = ContextCompactor.estimateTokens(listOf(Msg("user", "你好世界")))
        assertTrue("4 个汉字应约等于 4 token，实际 $cjk", cjk in 3..5)
    }

    @Test
    fun isStale_detectsDivergence() {
        val record = CompactionRecord(
            id = "r1",
            conversationId = "c1",
            inputMessageCount = sample.size,
            inputDigest = ContextCompactor.digest(sample),
        )
        assertEquals(false, ContextCompactor.isStale(record, sample))
        val grown = sample + Msg("user", "追加一句")
        assertTrue(ContextCompactor.isStale(record, grown))
    }

    @Test
    fun preservedTail_keepsLatestRounds() {
        // 保留最近 1 轮（user..assistant），应只保留最后两条。
        val tail = ContextCompactor.preservedTail(sample, preserveRounds = 1)
        assertEquals(listOf("user", "assistant"), tail.map { it.role })
        assertEquals("帮我写个函数", tail.first().textContent)
    }
}
