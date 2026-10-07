package com.ai.assistance.quro.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [QuroModelOutputBudget] 单测。
 *
 * 覆盖重点：
 * 1. 未知模型必须**保守回落**（绝不猜大 —— 猜大就是上游 400/500）；
 * 2. 族表「先具体后宽泛」（`gpt-4o` 不能被 `gpt-4` 抢走）；
 * 3. 短关键字必须落词边界（`o1` 不能命中 `foo1-bar`）；
 * 4. 请求值更小则采纳、更大则被钳住。
 */
class QuroModelOutputBudgetTest {

    @Test
    fun `未知模型保守回落 4096 而不是猜大`() {
        val b = QuroModelOutputBudget.resolve("some-unknown-model-v9")
        assertEquals(
            "未识别模型必须用最保守值，否则就是拿用户请求去赌上游会不会 500",
            QuroModelOutputBudget.CONSERVATIVE_OUTPUT_TOKENS, b.outputTokens
        )
        assertEquals(QuroModelOutputBudget.Source.CONSERVATIVE, b.source)
        assertTrue(b.inferred)
    }

    @Test
    fun `空模型名也走保守回落`() {
        val b = QuroModelOutputBudget.resolve("")
        assertEquals(QuroModelOutputBudget.CONSERVATIVE_OUTPUT_TOKENS, b.outputTokens)
    }

    @Test
    fun `gpt-4o 取 16384 不被 gpt-4 抢走`() {
        val b = QuroModelOutputBudget.resolve("gpt-4o")
        assertEquals(16_384, b.outputTokens)
        assertEquals("gpt-4o", b.familyHint)
    }

    @Test
    fun `gpt-4o-mini 命中 gpt-4o`() {
        assertEquals(16_384, QuroModelOutputBudget.resolve("gpt-4o-mini").outputTokens)
    }

    @Test
    fun `gpt-4-turbo 是 4096 不是 gpt-4 的 8192`() {
        val b = QuroModelOutputBudget.resolve("gpt-4-turbo")
        assertEquals(4_096, b.outputTokens)
        assertEquals("gpt-4-turbo", b.familyHint)
    }

    @Test
    fun `gpt-4-turbo-2024-04-09 日期后缀仍命中具体档`() {
        assertEquals(4_096, QuroModelOutputBudget.resolve("gpt-4-turbo-2024-04-09").outputTokens)
    }

    @Test
    fun `claude 取 8192`() {
        assertEquals(8_192, QuroModelOutputBudget.resolve("claude-3-5-sonnet-20241022").outputTokens)
    }

    @Test
    fun `gpt-5 取 128K`() {
        val b = QuroModelOutputBudget.resolve("gpt-5")
        assertEquals(128_000, b.outputTokens)
        assertEquals("gpt-5", b.familyHint)
    }

    @Test
    fun `o3 排在 o4 之前互不串档`() {
        assertEquals(100_000, QuroModelOutputBudget.resolve("o3-mini").outputTokens)
        assertEquals(100_000, QuroModelOutputBudget.resolve("o4-mini").outputTokens)
    }

    @Test
    fun `带厂商前缀的模型名也能命中`() {
        assertEquals(8_192, QuroModelOutputBudget.resolve("deepseek/deepseek-chat").outputTokens)
        // qwen2.5 走qwen2.5 档（32768），不能被宽泛的 qwen 档（8192）抢走
        assertEquals(32_768, QuroModelOutputBudget.resolve("Qwen/Qwen2.5-72B-Instruct").outputTokens)
    }

    @Test
    fun `短关键字 o1 不命中 foo1-bar 这类无关名字`() {
        // o1 命中会给 100000 的荒谬上限，而猜大正是触发上游 400/500 的方向
        assertEquals(
            QuroModelOutputBudget.CONSERVATIVE_OUTPUT_TOKENS,
            QuroModelOutputBudget.resolve("foo1-bar").outputTokens
        )
    }

    @Test
    fun `短关键字 glm 落词边界`() {
        assertEquals(8_192, QuroModelOutputBudget.resolve("glm-4-plus").outputTokens)
        assertEquals(
            QuroModelOutputBudget.CONSERVATIVE_OUTPUT_TOKENS,
            QuroModelOutputBudget.resolve("myglm").outputTokens
        )
    }

    @Test
    fun `请求值更小则采纳用户设置`() {
        val b = QuroModelOutputBudget.resolve("gpt-4o", requestedMaxTokens = 2_000)
        assertEquals(2_000, b.outputTokens)
        assertEquals(16_384, b.hardLimit)
        assertEquals(QuroModelOutputBudget.Source.USER_SETTING, b.source)
    }

    @Test
    fun `请求值更大则被模型硬上限钳住`() {
        // 这是「部分模型不能用」的核心防线：QuroModelConfig 默认 65536，
        // gpt-4o 只有 16384，放行 65536 就是拿 400 去赌。
        val b = QuroModelOutputBudget.resolve("gpt-4o", requestedMaxTokens = 65_536)
        assertEquals(16_384, b.outputTokens)
        assertEquals(QuroModelOutputBudget.Source.MODEL_TABLE, b.source)
    }

    @Test
    fun `未指定请求值时用模型档`() {
        val b = QuroModelOutputBudget.resolve("gpt-4o", requestedMaxTokens = 0)
        assertEquals(16_384, b.outputTokens)
    }

    @Test
    fun `未指定请求值时未知模型取保守值`() {
        val b = QuroModelOutputBudget.resolve("mystery-model", requestedMaxTokens = -1)
        assertEquals(QuroModelOutputBudget.CONSERVATIVE_OUTPUT_TOKENS, b.outputTokens)
    }

    @Test
    fun `qwen3 大于 qwen2`() {
        assertEquals(32_768, QuroModelOutputBudget.resolve("qwen3-32b").outputTokens)
        assertEquals(8_192, QuroModelOutputBudget.resolve("qwen2-7b-instruct").outputTokens)
    }

    @Test
    fun `所有族档都不超过绝对天花板`() {
        val models = listOf(
            "gpt-5", "gpt-4.1", "gpt-4o", "claude", "gemini-2.5", "qwen-long",
            "qwen3", "deepseek", "glm-4", "kimi", "grok", "minimax", "gemma"
        )
        models.forEach { m ->
            val v = QuroModelOutputBudget.resolve(m).outputTokens
            assertTrue("$m 超过天花板: $v", v <= QuroModelOutputBudget.ABSOLUTE_MAX_OUTPUT_TOKENS)
            assertTrue("$m 低于最小可感知值", v >= 256)
        }
    }

    @Test
    fun `describe 保守回落时给出可操作提示`() {
        val text = QuroModelOutputBudget.describe(
            QuroModelOutputBudget.resolve("totally-unknown-xyz")
        )
        assertTrue(text.contains("保守兜底"))
        assertTrue("必须告诉用户内容可能被腰斩", text.contains("腰斩"))
    }
}
