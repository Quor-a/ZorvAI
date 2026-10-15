package com.ai.assistance.quro.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * issue #11 问题6 第1步「识别」回归锁：上下文超限判定矩阵。
 *
 * 用户实测：超限时客户端不预检、不报错，直接发出 → 上游 400 → 自动重试 →
 * 界面持续转圈。修复关键第一步就是「按文案认出它」，且**不能误伤**其它错误
 * （否则会把参数错误也导向「请新开对话」的错误建议）。
 */
class QuroLlmErrorsTest {

    /* ── 必须识别为「上下文超限」的各家网关文案 ── */

    @Test
    fun detectsOpenAiStyleMessage() {
        val raw = """{"error":{"message":"This model's maximum context length is 8192 tokens. " +
            "However your messages resulted in 12000 tokens. Please reduce the length of the messages.","type":"invalid_request_error"}}"""
        assertTrue(QuroLlmErrors.isContextLengthExceeded(raw))
    }

    @Test
    fun detectsContextLengthExceededPhrase() {
        assertTrue(QuroLlmErrors.isContextLengthExceeded("context length exceeded"))
        assertTrue(QuroLlmErrors.isContextLengthExceeded("CONTEXT_LENGTH_EXCEEDED"))
    }

    @Test
    fun detectsYouRequestedTokens() {
        // issue 原文点名的句式：不带任何标记词，靠「token + 超限语义」组合规则覆盖
        val raw = "You requested 30000 tokens but the maximum is 8192"
        assertTrue(QuroLlmErrors.isContextLengthExceeded(raw))
    }

    @Test
    fun detectsBedrockStringTooLong() {
        assertTrue(QuroLlmErrors.isContextLengthExceeded(
            "ValidationException: Input is too long for requested model"))
    }

    @Test
    fun detectsPromptTooLong() {
        assertTrue(QuroLlmErrors.isContextLengthExceeded("prompt is too long"))
    }

    @Test
    fun detectsHtmlWrappedError() {
        // 网关把错误包进 HTML（friendlyHttpError 同款场景）
        assertTrue(QuroLlmErrors.isContextLengthExceeded(
            "<html><body>400 Bad Request: maximum context length is 128000 tokens</body></html>"))
    }

    @Test
    fun detectsUpstream500WithContextText() {
        // 本仓实测：部分中转把超限透传为 500（QuroConversation 注释有载）——
        // 按内容判、不按状态码判，才能拦住这条 otherwise 会落入重试集合的路
        assertTrue(QuroLlmErrors.isContextLengthExceeded(
            "upstream error: context length exceeded"))
    }

    /* ── 绝不能误判为「上下文超限」的常见错误 ── */

    @Test
    fun doesNotFlagMaxTokensParamError() {
        // max_tokens 参数非法：带 maximum/token 但不带超限语义 → 是参数问题，不是上下文超限
        assertFalse(QuroLlmErrors.isContextLengthExceeded(
            "Invalid 'max_tokens': must be a positive integer"))
    }

    @Test
    fun doesNotFlagAuthErrors() {
        assertFalse(QuroLlmErrors.isContextLengthExceeded("401 Unauthorized: invalid api key"))
        assertFalse(QuroLlmErrors.isContextLengthExceeded("403 permission denied"))
    }

    @Test
    fun doesNotFlagRateLimit() {
        assertFalse(QuroLlmErrors.isContextLengthExceeded("429 Too Many Requests"))
    }

    @Test
    fun doesNotFlagServerErrors() {
        assertFalse(QuroLlmErrors.isContextLengthExceeded("502 Bad Gateway"))
        assertFalse(QuroLlmErrors.isContextLengthExceeded("Internal Server Error"))
    }

    @Test
    fun doesNotFlagBlankOrNull() {
        assertFalse(QuroLlmErrors.isContextLengthExceeded(null))
        assertFalse(QuroLlmErrors.isContextLengthExceeded(""))
        assertFalse(QuroLlmErrors.isContextLengthExceeded("   "))
    }

    /* ── 提示文案（第3步「提示 + 处置入口」） ── */

    @Test
    fun messageHasStablePrefixForUiDetection() {
        val msg = QuroLlmErrors.contextLimitMessage("maximum context length is 8192 tokens")
        // UI 靠这个前缀识别错误气泡并渲染「新建对话」按钮（见 ChatScreen.MessageRow）
        assertTrue(msg.startsWith(QuroLlmErrors.CONTEXT_LIMIT_MESSAGE_PREFIX))
    }

    @Test
    fun messageIsActionable() {
        val msg = QuroLlmErrors.contextLimitMessage("context length exceeded")
        assertTrue(msg.contains("重试不会成功"))
        assertTrue(msg.contains("新建对话"))
        assertTrue(msg.contains("模型配置"))
    }

    @Test
    fun messageCarriesUpstreamDetail() {
        val msg = QuroLlmErrors.contextLimitMessage(
            """{"error":{"message":"maximum context length is 8192 tokens"}}""")
        assertTrue(msg.contains("8192"))
    }

    @Test
    fun extractKeySentencePullsJsonMessage() {
        val s = QuroLlmErrors.extractKeySentence(
            """{"error":{"message":"maximum context length is 8192 tokens, you requested 12000"}}""")
        assertNotNull(s)
        assertTrue(s!!.contains("maximum context length"))
    }
}
