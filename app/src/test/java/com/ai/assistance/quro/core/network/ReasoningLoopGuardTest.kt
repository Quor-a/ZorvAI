package com.ai.assistance.quro.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 推理打转掐断的回归测试。
 *
 * 背景：模型在 reasoning 里复读时，流式读取会一路吐到 token 上限，
 * 上层几十秒到几分钟拿不到返回 —— 用户看到的就是「死循环思考」。
 * 轮次级检测救不了它（压根走不到下一轮），只能在接收过程中判断并断流。
 *
 * 这里不联网，只钉住「什么时候该断、什么时候绝不能误断」。
 */
class ReasoningLoopGuardTest {

    private fun feed(guard: ReasoningLoopGuard, text: String, step: Int = 40): Boolean {
        var i = step
        while (i < text.length) {
            if (guard.onReasoning(text.substring(0, i))) return true
            i += step
        }
        return guard.onReasoning(text)
    }

    @Test
    fun `normal reasoning is never cut`() {
        // 每段都在引入新概念：结构、依赖、步骤各不相同
        val text = buildString {
            repeat(30) { i ->
                appendLine("第 $i 步：先确认输入参数 $i 是否合法，再检查对应的输出通道 $i 的返回值。")
                appendLine("如果失败要回滚到上一状态 $i，并记录一条诊断日志，编号 zz$i。")
            }
        }
        val guard = ReasoningLoopGuard()
        assertFalse("正常推理不能被打断", feed(guard, text))
        assertFalse(guard.stalled)
    }

    @Test
    fun `verbatim repetition is detected`() {
        val one = "我需要先确认这个接口的返回结构，然后检查字段类型是否匹配，" +
            "如果不匹配就要转换一次，转换后还要再验证一遍边界情况。"
        val guard = ReasoningLoopGuard()
        // 同一段复制 20 次 = 典型复读
        assertTrue("原地复读必须被掐断", feed(guard, one.repeat(40)))
        assertTrue(guard.stalled)
        assertTrue(guard.stallReason!!.contains("重复"))
    }

    @Test
    fun `slightly reworded repetition is still detected`() {
        // 换几个字但说的完全是同一件事 —— 真实复读常常如此，不能只认逐字相同
        val a = "我需要先确认这个接口的返回结构，然后检查字段类型是否匹配，如果不匹配就要转换一次。"
        val b = "我需要先确认一下这个接口的返回结构，然后检查字段类型是不是匹配，如果不匹配就要转换一次。"
        val guard = ReasoningLoopGuard()
        assertTrue(feed(guard, (a + b).repeat(20)))
    }

    @Test
    fun `excessive reasoning without body is cut`() {
        val guard = ReasoningLoopGuard(maxChars = 2_000)
        val text = buildString {
            repeat(400) { i -> append("思考片段$i ") }
        }
        assertTrue("超长无正文也要断", feed(guard, text))
        assertTrue(guard.stallReason!!.contains("上限"))
    }

    @Test
    fun `short reasoning passes immediately`() {
        val guard = ReasoningLoopGuard()
        assertFalse(guard.onReasoning("先看一下。"))
        assertFalse(guard.stalled)
    }

    @Test
    fun `similarity separates repetition from progression`() {
        val a = "检查接口返回结构是否匹配字段类型"
        val b = "检查接口返回结构是否匹配字段类型"
        val c = "接下来安装依赖并启动本地服务验证端口"
        assertEquals(1.0, ReasoningLoopGuard.similarity(a, b), 0.001)
        assertTrue("不同内容相似度必须低", ReasoningLoopGuard.similarity(a, c) < 0.2)
    }

    @Test
    fun `guard stays stalled once tripped`() {
        val guard = ReasoningLoopGuard()
        val one = "重复的一段推演内容，用来触发判定。"
        feed(guard, one.repeat(80))
        assertTrue(guard.stalled)
        // 判定后即使喂正常内容也保持中断语义（调用方已经 break）
        assertTrue(guard.onReasoning("无关内容"))
    }
}
