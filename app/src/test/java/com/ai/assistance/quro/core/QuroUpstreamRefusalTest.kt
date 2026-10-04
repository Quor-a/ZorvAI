package com.ai.assistance.quro.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 上游「拒答回执」归一化的单测。
 *
 * 真机故障：`The request was rejected because it was considered high risk` 原样糊进气泡，
 * 且被当成正常回复存进历史、下一轮继续喂给模型（工具与环境层的异常没归一化）。
 */
class QuroUpstreamRefusalTest {

    @Test
    fun `the exact screenshot phrase is detected`() {
        val t = "The request was rejected because it was considered high risk"
        assertTrue(t, QuroUpstreamRefusal.detect(t))
        assertEquals(QuroUpstreamRefusal.CODE, QuroUpstreamRefusal.code(t))
    }

    @Test
    fun `common upstream refusal phrasings are detected`() {
        listOf(
            "Request rejected: content policy violation",
            "Your last message was blocked because of our safety policy.",
            "I'm sorry, but I can't assist with that request.",
            "Sorry, I cannot help you with this.",
            "抱歉，我无法提供该内容的回答。",
            "我的安全策略禁止生成此类内容。",
            "The prompt violates the content policy.",
        ).forEach { assertTrue(it, QuroUpstreamRefusal.detect(it)) }
    }

    @Test
    fun `normal answers mentioning risk or policy are not flagged`() {
        // 误判比漏判更糟：真答案被换成「被拦了」，用户连原问题都答不上来。
        listOf(
            "这个方案的风险（risk）主要在并发写入，建议先做压测。",
            "该开源协议（policy）允许商用，但要求保留署名。",
            "这轮我帮不了你了，换台机器再跑一次大概就行。",
            "抱歉让你久等了，下面是完整结果：",
            "项目当前的上下文预算已经不小了，别再往上堆。",
        ).forEach { assertFalse(it, QuroUpstreamRefusal.detect(it)) }
    }

    @Test
    fun `blank text is not a refusal`() {
        assertFalse(QuroUpstreamRefusal.detect(""))
        assertFalse(QuroUpstreamRefusal.detect("   \n  "))
    }

    @Test
    fun `normalize gives Chinese actionable text with no English back`() {
        val out = QuroUpstreamRefusal.normalize("The request was rejected because it was considered high risk")
        assertTrue(out.isNotEmpty())
        assertTrue(out, out.contains("上游安全策略"))
        assertTrue(out, out.contains("换个说法"))
        assertTrue(out, out.contains("设置"))
        // 不得保留原英文回执
        assertFalse(out, out.contains("rejected", ignoreCase = true))
        assertFalse(out, out.contains("risk", ignoreCase = true))
    }

    @Test
    fun `code is null when not a refusal`() {
        assertEquals(null, QuroUpstreamRefusal.code("依赖版本要统一到 1.2.3，别混着用。"))
    }
}
