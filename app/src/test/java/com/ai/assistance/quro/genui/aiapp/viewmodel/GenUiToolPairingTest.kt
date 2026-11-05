package com.ai.assistance.quro.genui.aiapp.viewmodel

import com.ai.assistance.quro.genui.aiapp.core.GenUIChatMessage
import com.ai.assistance.quro.genui.aiapp.core.GenUIToolCall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `tool` 消息与 `assistant[tool_calls]` 的配对修复回归测试。
 *
 * 对应故障：工具调用跑到一半用户点「停止」→ 历史里留下孤儿的 assistant[tool_calls]，
 * 此后每一轮请求都被上游 400，界面表现是「一直转圈然后报错，发什么都不回」。
 * 这类问题在真机上极难看出原因（界面只说「请求失败」），所以必须在纯逻辑层钉死。
 */
class GenUiToolPairingTest {

    private fun sys() = GenUIChatMessage("system", "你是助手")

    private fun user(text: String) = GenUIChatMessage("user", text)

    private fun assistCall(id: String, name: String = "web_search") = GenUIChatMessage(
        role = "assistant",
        content = "",
        toolCalls = listOf(GenUIToolCall(id = id, name = name, arguments = """{"q":"x"}""")),
    )

    private fun toolMsg(id: String, result: String = """{"success":true}""") = GenUIChatMessage(
        role = "tool",
        content = result,
        toolCallId = id,
        toolName = "web_search",
    )

    /** 每个 assistant[tool_calls] 之后，它列出的每个 id 都必须有一条 tool 回应。 */
    private fun assertPaired(messages: List<GenUIChatMessage>) {
        var i = 0
        while (i < messages.size) {
            val m = messages[i]
            val calls = m.toolCalls
            if (m.role == "assistant" && !calls.isNullOrEmpty()) {
                val ids = calls.map { it.id }.toSet()
                val answered = HashSet<String>()
                var j = i + 1
                while (j < messages.size && messages[j].role == "tool") {
                    answered += messages[j].toolCallId.orEmpty()
                    j++
                }
                assertEquals("assistant[${ids}] 的 tool 回应不完整", ids, answered.filter { it.isNotEmpty() }.toSet())
                i = j
                continue
            }
            assertFalse("列表里出现了没有前置 assistant[tool_calls] 的孤儿 tool 消息", m.role == "tool")
            i++
        }
    }

    @Test
    fun `配对完整的对话原样保留`() {
        val input = listOf(sys(), user("查一下"), assistCall("c1"), toolMsg("c1"), GenUIChatMessage("assistant", "查到了"))
        val out = ChatHistory.normalizeToolMessages(input)
        assertEquals(input, out)
        assertPaired(out)
    }

    @Test
    fun `孤儿 tool 消息被丢弃`() {
        // 历史裁剪 / 持久化丢了 toolCallId 的典型形态
        val input = listOf(sys(), user("继续"), toolMsg("c9", """{"stale":true}"""), GenUIChatMessage("assistant", "好的"))
        val out = ChatHistory.normalizeToolMessages(input)
        assertEquals(listOf(sys(), user("继续"), GenUIChatMessage("assistant", "好的")), out)
        assertPaired(out)
    }

    @Test
    fun `assistant 缺少 tool 回应时补一条可判定的失败结果`() {
        // 用户点停止：assistant[tool_calls] 已落库，tool 结果没来得及写
        val out = ChatHistory.normalizeToolMessages(listOf(sys(), user("搜"), assistCall("c1")))
        assertPaired(out)
        val patched = out.last()
        assertEquals("tool", patched.role)
        assertEquals("c1", patched.toolCallId)
        assertTrue("补齐的结果必须让模型判别为失败", patched.content.contains("\"success\":false"))
    }

    @Test
    fun `空 assistant 占位被剔除`() {
        val input = listOf(
            sys(), user("查"),
            GenUIChatMessage("assistant", "   "), // 进度占位气泡
            assistCall("c1"), toolMsg("c1"),
        )
        val out = ChatHistory.normalizeToolMessages(input)
        assertEquals(4, out.size)
        assertEquals("assistant", out[2].role)
        assertEquals("c1", out[2].toolCalls?.first()?.id)
        assertPaired(out)
    }

    @Test
    fun `tool 回应 id 对不上时丢弃错位的一条并补齐`() {
        val input = listOf(sys(), user("查"), assistCall("c1"), toolMsg("cX", """{"wrong":true}"""))
        val out = ChatHistory.normalizeToolMessages(input)
        assertPaired(out)
        // 错位那条被丢掉，补的是 c1
        val toolResult = out.last()
        assertEquals("c1", toolResult.toolCallId)
        assertFalse("错位结果不得混进来", out.any { it.content.contains("\"wrong\":true") })
    }

    @Test
    fun `多轮工具调用各自配对互不干扰`() {
        val input = listOf(
            sys(), user("第一轮"),
            assistCall("a1"), toolMsg("a1"),
            GenUIChatMessage("assistant", "第一轮结果"),
            user("第二轮"),
            assistCall("b1"), toolMsg("b1"),
            GenUIChatMessage("assistant", "第二轮结果"),
        )
        val out = ChatHistory.normalizeToolMessages(input)
        assertEquals(input, out)
        assertPaired(out)
    }
}
