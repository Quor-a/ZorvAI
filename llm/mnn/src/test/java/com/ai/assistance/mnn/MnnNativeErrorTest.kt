package com.ai.assistance.mnn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 锁死 [MnnNativeError] 的错误码解析与中文文案约定。
 *
 * 背景：用户看到的原文案是「⚠️ MNN 推理未产生任何输出（ok=false）」——
 * 既说不清是模板、tokenizer 还是内存问题，也没告诉用户下一步该干什么。
 * 本类负责把原生 `"错误码|英文补充"` 翻成「发生了什么 + 该怎么办」。
 *
 * 这里的断言有两个硬性要求：
 * 1. **错误码字符串必须与 mnnllmnative.cpp 的 `setLastError` 完全一致**，改名要两边同步；
 * 2. 未知错误码不能丢信息——否则会退化成比原来还模糊的提示。
 */
class MnnNativeErrorTest {

    /** 与 native 侧 `setLastError` 一一对应的全部错误码。少一个都说明两边失配。 */
    private val nativeCodes = listOf(
        "E_MNN_NO_MESSAGES",
        "E_MNN_BAD_MESSAGES",
        "E_MNN_NO_VALID_MESSAGE",
        "E_MNN_TEMPLATE_THROW",
        "E_MNN_TEMPLATE_EMPTY",
        "E_MNN_EMPTY_TOKENS",
        "E_MNN_STREAM_THROW",
    )

    /** 原生没留下线索时（null / 空串）必须原样使用兜底文案，不能编造原因。 */
    @Test
    fun `null or blank raw falls back to provided text`() {
        val fallback = "推理没有产生输出，请重试。"

        for (raw in listOf(null, "", "   ")) {
            val parsed = MnnNativeError.parse(raw, fallback)
            assertEquals("E_MNN_NO_DETAIL", parsed.code)
            assertEquals("", parsed.detail)
            assertEquals(fallback, parsed.message)
        }
    }

    /** `错误码|补充说明` 必须被正确拆分，补充说明附在中文提示末尾方便贴日志。 */
    @Test
    fun `code and detail are split on separator`() {
        val parsed = MnnNativeError.parse(
            "E_MNN_TEMPLATE_EMPTY|chat_template rendered 0 chars",
            "兜底",
        )

        assertEquals("E_MNN_TEMPLATE_EMPTY", parsed.code)
        assertEquals("chat_template rendered 0 chars", parsed.detail)
        assertTrue(parsed.message.contains("chat_template rendered 0 chars"))
        assertFalse("不应回落到兜底文案", parsed.message == "兜底")
    }

    /** 没有分隔符时整串就是错误码，detail 为空且提示里不出现空括号。 */
    @Test
    fun `code without detail produces no trailing parenthesis`() {
        val parsed = MnnNativeError.parse("E_MNN_EMPTY_TOKENS", "兜底")

        assertEquals("E_MNN_EMPTY_TOKENS", parsed.code)
        assertEquals("", parsed.detail)
        assertFalse("detail 为空时不应出现「原生诊断：」", parsed.message.contains("原生诊断"))
    }

    /**
     * 每个已知错误码都必须给出**可操作**的中文提示：
     * 不能等于兜底文案、不能只是把错误码原样抄一遍、且长度足以承载处置建议。
     */
    @Test
    fun `every known code maps to an actionable chinese message`() {
        for (code in nativeCodes) {
            val parsed = MnnNativeError.parse("$code|native detail", "兜底文案")

            assertEquals(code, parsed.code)
            assertFalse("$code 退化成了兜底文案", parsed.message == "兜底文案")
            assertFalse("$code 的提示里不应出现裸错误码", parsed.message.startsWith(code))
            assertTrue("$code 的提示过短，缺少处置建议", parsed.message.length >= 20)
            assertTrue("$code 未附带原生诊断", parsed.message.contains("native detail"))
        }
    }

    /** 模板为空是本次修复的主场景，必须明确指向「换带完整 llm_config.json 的模型」。 */
    @Test
    fun `template empty message tells user how to fix`() {
        val parsed = MnnNativeError.parse("E_MNN_TEMPLATE_EMPTY", "兜底")

        assertTrue(parsed.message.contains("chat_template"))
        assertTrue(parsed.message.contains("llm_config.json"))
    }

    /** 未知错误码不得吞掉信息：错误码本身和原生说明都要带出来。 */
    @Test
    fun `unknown code keeps both code and detail`() {
        val parsed = MnnNativeError.parse("E_MNN_FUTURE_CODE|something odd", "兜底")

        assertEquals("E_MNN_FUTURE_CODE", parsed.code)
        assertTrue(parsed.message.contains("E_MNN_FUTURE_CODE"))
        assertTrue(parsed.message.contains("something odd"))
    }

    /** 原生可能带上换行/空格，解析时要 trim 掉，避免 UI 上出现诡异缩进。 */
    @Test
    fun `whitespace around code and detail is trimmed`() {
        val parsed = MnnNativeError.parse("  E_MNN_STREAM_THROW | std::bad_alloc  ", "兜底")

        assertEquals("E_MNN_STREAM_THROW", parsed.code)
        assertEquals("std::bad_alloc", parsed.detail)
    }
}
