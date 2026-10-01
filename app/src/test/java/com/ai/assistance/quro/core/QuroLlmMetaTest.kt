package com.ai.assistance.quro.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [QuroLlmMeta] 单元测试（N3）。
 *
 * 覆盖目标：把「各家 `finish_reason` 拼写不一致」「usage 字段名不一致」「未知必须是 -1 而不是 0」
 * 这三类**最容易悄悄算错**的逻辑钉死。这些判定直接决定用户看到的是
 * 「⚠️ 被截断，回复继续即可」还是「静默的半句话」。
 *
 * 本测试刻意不触碰 `org.json`（Android 单测里它是会抛 "not mocked" 的桩）——
 * JSON 抽取留在 `QuroLlmClient.readMeta`，本类只测**归一化与派生**。
 */
class QuroLlmMetaTest {

    // ───────────────────────── finish_reason 归一化 ─────────────────────────

    @Test
    fun `normalizes stop variants across vendors`() {
        // Anthropic 用 end_turn / stop_sequence，部分网关用 eos
        listOf("stop", "end_turn", "stop_sequence", "eos", "eos_token", "STOP", " Stop ").forEach {
            assertEquals("归一失败：$it", QuroLlmMeta.F_STOP, QuroLlmMeta.normalizeFinishReason(it))
        }
    }

    @Test
    fun `normalizes all truncation spellings to length`() {
        // 这是 N3 最关键的归一：漏掉任何一个拼写，用户都会拿到半句话而无提示。
        // max_tokens 同时是 Anthropic 的截断原因，也是多数网关对 length 的别名，极易漏。
        listOf(
            "length", "max_tokens", "max_output_tokens", "max_completion_tokens", "model_length",
            "LENGTH", "Max_Tokens",
        ).forEach {
            assertEquals("截断未被识别：$it", QuroLlmMeta.F_LENGTH, QuroLlmMeta.normalizeFinishReason(it))
        }
    }

    @Test
    fun `normalizes tool call variants to tool_calls`() {
        listOf("tool_calls", "function_call", "tool_use", "TOOL_USE").forEach {
            assertEquals("工具轮未被识别：$it", QuroLlmMeta.F_TOOL_CALLS, QuroLlmMeta.normalizeFinishReason(it))
        }
    }

    @Test
    fun `normalizes safety variants to content_filter`() {
        // Gemini 用 safety、Anthropic 用 blocklist/recitation、部分国产网关用 prohibited_content。
        listOf(
            "content_filter", "safety", "blocklist", "prohibited_content", "recitation", "SAFETY",
        ).forEach {
            assertEquals("风控未被识别：$it", QuroLlmMeta.F_CONTENT_FILTER, QuroLlmMeta.normalizeFinishReason(it))
        }
    }

    @Test
    fun `null blank and literal null string all map to null`() {
        assertNull(QuroLlmMeta.normalizeFinishReason(null))
        assertNull(QuroLlmMeta.normalizeFinishReason(""))
        assertNull(QuroLlmMeta.normalizeFinishReason("   "))
        // 🔴 Android 的 org.json 在值为 JSON null 时会返回**字面量字符串** "null"
        //    （本仓已踩过：safeString 就是为此存在的）。若这里不吞掉，
        //    finishReason 会变成 "null" 字符串 → normal 判定失败 → 每轮都误报异常。
        assertNull(QuroLlmMeta.normalizeFinishReason("null"))
        assertNull(QuroLlmMeta.normalizeFinishReason("NULL"))
        assertNull(QuroLlmMeta.normalizeFinishReason(" null "))
    }

    @Test
    fun `unknown finish reason is preserved and not treated as normal`() {
        // 宁可让日志出现没见过的值，也不要静默当成 stop —— 否则新的截断原因
        // （上游改一次拼写）会永久逃过监控。
        assertEquals("weird_new_reason", QuroLlmMeta.normalizeFinishReason("weird_new_reason"))
        val m = QuroLlmMeta.ofFinishReason("weird_new_reason")
        assertFalse("未识别的停止原因不得视为正常结束", m.normal)
        assertFalse(m.truncated)
        assertFalse(m.filtered)
    }

    // ───────────────────────── 派生判定 ─────────────────────────

    @Test
    fun `truncated is true only for length`() {
        assertTrue(QuroLlmMeta.ofFinishReason("length").truncated)
        assertTrue(QuroLlmMeta.ofFinishReason("max_tokens").truncated)
        assertFalse(QuroLlmMeta.ofFinishReason("stop").truncated)
        assertFalse(QuroLlmMeta.ofFinishReason("tool_calls").truncated)
        assertFalse(QuroLlmMeta.ofFinishReason("content_filter").truncated)
        assertFalse(QuroLlmMeta.EMPTY.truncated)
    }

    @Test
    fun `filtered and refused are exclusive`() {
        val f = QuroLlmMeta.ofFinishReason("content_filter")
        assertTrue(f.filtered)
        assertFalse(f.refused)
        assertFalse(f.truncated)

        val r = QuroLlmMeta.ofFinishReason("refusal")
        assertTrue(r.refused)
        assertFalse(r.filtered)
    }

    @Test
    fun `normal covers stop tool_calls and absent reason`() {
        assertTrue(QuroLlmMeta.ofFinishReason("stop").normal)
        assertTrue(QuroLlmMeta.ofFinishReason("tool_calls").normal)
        assertTrue(QuroLlmMeta.EMPTY.normal)
        assertFalse(QuroLlmMeta.ofFinishReason("length").normal)
        assertFalse(QuroLlmMeta.ofFinishReason("content_filter").normal)
    }

    // ───────────────────────── usage 未知语义 ─────────────────────────

    @Test
    fun `unknown usage defaults to minus one not zero`() {
        // 🔴 这是本类的**核心不变式**：-1 = 上游没给，0 = 真的是 0。
        //    若默认 0，「思考占比」会算出 0%，看起来像「思考不花 token」——真相反。
        val m = QuroLlmMeta.ofFinishReason("stop")
        assertEquals(QuroLlmMeta.UNKNOWN, m.promptTokens)
        assertEquals(QuroLlmMeta.UNKNOWN, m.completionTokens)
        assertEquals(QuroLlmMeta.UNKNOWN, m.reasoningTokens)
        assertEquals(QuroLlmMeta.UNKNOWN, m.cachedTokens)
        assertEquals(QuroLlmMeta.UNKNOWN, m.totalTokens)
        assertFalse(m.hasUsage)
    }

    @Test
    fun `hasUsage is true when any usage field present`() {
        assertTrue(QuroLlmMeta(promptTokens = 100).hasUsage)
        assertTrue(QuroLlmMeta(completionTokens = 0).hasUsage) // 0 是合法值，也算「给了」
        assertTrue(QuroLlmMeta(totalTokens = 5).hasUsage)
        assertFalse(QuroLlmMeta(cachedTokens = 10).hasUsage)   // 仅有缓存数不算用量
    }

    @Test
    fun `reasoning share percent computed only when measurable`() {
        // 311 / 418 ≈ 74% —— 这就是「开了思考回复又短又慢」的量化解释。
        assertEquals(74, QuroLlmMeta(completionTokens = 418, reasoningTokens = 311).reasoningSharePercent)
        assertEquals(100, QuroLlmMeta(completionTokens = 100, reasoningTokens = 100).reasoningSharePercent)
        assertEquals(0, QuroLlmMeta(completionTokens = 100, reasoningTokens = 0).reasoningSharePercent)
    }

    @Test
    fun `reasoning share percent is minus one when not measurable`() {
        // 未回 usage / 输出为 0 / 未回 reasoning_tokens → 一律 -1（测不出），不得返回 0。
        assertEquals(-1, QuroLlmMeta.ofFinishReason("stop").reasoningSharePercent)
        assertEquals(-1, QuroLlmMeta(completionTokens = 0, reasoningTokens = 0).reasoningSharePercent)
        assertEquals(-1, QuroLlmMeta(completionTokens = -1, reasoningTokens = 50).reasoningSharePercent)
        assertEquals(-1, QuroLlmMeta(completionTokens = 100).reasoningSharePercent)
    }

    @Test
    fun `withDerivedTotal fills total from prompt plus completion`() {
        val m = QuroLlmMeta(promptTokens = 1832, completionTokens = 418).withDerivedTotal()
        assertEquals(2250, m.totalTokens)

        // 已有 total → 原样保留（上游给的才是准的）
        assertEquals(999, QuroLlmMeta(promptTokens = 1, completionTokens = 2, totalTokens = 999).withDerivedTotal().totalTokens)

        // 什么都没给 → 不编造 total
        assertEquals(QuroLlmMeta.UNKNOWN, QuroLlmMeta.ofFinishReason("stop").withDerivedTotal().totalTokens)

        // 负数（半未知）按 0 计，不得让 total 变成负数
        assertEquals(100, QuroLlmMeta(promptTokens = 100, completionTokens = -1).withDerivedTotal().totalTokens)
    }

    // ───────────────────────── 面向用户的文案 ─────────────────────────

    @Test
    fun `truncationHint present only when truncated and actionable`() {
        assertNull("非截断不得出现提示", QuroLlmMeta.ofFinishReason("stop").truncationHint())
        assertNull(QuroLlmMeta.ofFinishReason("content_filter").truncationHint())

        val hint = QuroLlmMeta.ofFinishReason("length").truncationHint()
        assertNotNull(hint)
        // 必须给出**可执行的下一步**，而不只是描述故障：
        requireNotNull(hint)
        assertTrue("应告诉用户怎么接着要内容", hint.contains("继续"))
        assertTrue("应告诉用户怎么根治", hint.contains("最大输出长度"))
    }

    @Test
    fun `filterHint present only when filtered and explains it is not a network issue`() {
        assertNull(QuroLlmMeta.ofFinishReason("stop").filterHint())
        assertNull(QuroLlmMeta.ofFinishReason("length").filterHint())

        val hint = QuroLlmMeta.ofFinishReason("safety").filterHint()
        assertNotNull(hint)
        requireNotNull(hint)
        assertTrue("必须点明是上游安全策略而非网络故障", hint.contains("安全策略"))
    }

    // ───────────────────────── summary 诊断行 ─────────────────────────

    @Test
    fun `summary always carries finish reason and marks missing usage`() {
        val s = QuroLlmMeta.ofFinishReason("stop").summary()
        assertTrue(s.contains("finish=stop"))
        assertTrue("上游没回 usage 必须显式标注，否则日志里分不清「0」和「没给」", s.contains("未回 usage"))
    }

    @Test
    fun `summary reports reasoning share and anomaly flags`() {
        val truncated = QuroLlmMeta(
            finishReason = QuroLlmMeta.F_LENGTH,
            promptTokens = 1832,
            completionTokens = 418,
            reasoningTokens = 311,
            cachedTokens = 1024,
        ).summary()
        assertTrue(truncated.contains("prompt=1832"))
        assertTrue(truncated.contains("reasoning=311(74%思考)"))
        assertTrue(truncated.contains("cached=1024"))
        assertTrue("截断必须在日志里一眼可见", truncated.contains("已截断"))

        assertTrue(QuroLlmMeta.ofFinishReason("content_filter").summary().contains("被风控拦截"))
        assertTrue(QuroLlmMeta.ofFinishReason("refusal").summary().contains("模型拒答"))
    }

    @Test
    fun `summary does not print unknown fields as zero`() {
        // -1 的字段根本不出现，避免日志出现「completion=0」这种误导性数字。
        val s = QuroLlmMeta.ofFinishReason("length").summary()
        assertFalse(s.contains("prompt="))
        assertFalse(s.contains("completion="))
        assertFalse(s.contains("total="))
        assertTrue(s.contains("finish=length"))
    }

    // ───────────────────────── 与 QuroLlmResult 的契约 ─────────────────────────

    @Test
    fun `llm result carries meta with safe default`() {
        // 默认值必须是 EMPTY，保证「没接 meta 的构造点」行为与改造前一致（零回归）。
        assertEquals(QuroLlmMeta.EMPTY, QuroLlmResult.Text("hi").meta)
        assertEquals(QuroLlmMeta.EMPTY, QuroLlmResult.Text("hi", "think").meta)
        assertEquals(QuroLlmMeta.EMPTY, QuroLlmResult.ToolCalls(emptyList()).meta)

        val m = QuroLlmMeta.ofFinishReason("length")
        assertEquals(m, QuroLlmResult.Text("半句话", null, m).meta)
    }
}
