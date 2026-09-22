package com.ai.assistance.quro.genui.aiapp.viewmodel

import com.ai.assistance.quro.genui.aiapp.core.GenUIChatMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 上下文窗口化回归测试。
 *
 * 对应线上事故：历史超过窗口后 system 被 `takeLast` 挤掉，
 * 模型丢掉 GenUI 规则后回「您这一轮连续发出了很多条指令，我来整理成清单」。
 */
class ChatHistoryTest {

    private fun sys() = GenUIChatMessage("system", "你是 GenUI 助手")
    private fun u(text: String) = GenUIChatMessage("user", text)
    private fun a(text: String) = GenUIChatMessage("assistant", text)
    private fun tool(text: String) = GenUIChatMessage("tool", text, toolCallId = "c1", toolName = "t")

    /** 造 n 轮 user/assistant 对话 */
    private fun turns(n: Int): List<GenUIChatMessage> {
        val list = mutableListOf<GenUIChatMessage>()
        for (i in 1..n) {
            list.add(u("指令$i"))
            list.add(a("回复$i"))
        }
        return list
    }

    @Test
    fun `system 永远不会被挤掉`() {
        // 100 轮 → 远超前实现 takeLast(20)，system 必须仍在首位
        val history = listOf(sys()) + turns(100)
        val api = ChatHistory.toApiMessages(history)
        assertEquals("system", api.first().role)
        assertEquals("你是 GenUI 助手", api.first().content)
    }

    @Test
    fun `只保留最近 maxTurns 轮`() {
        val history = listOf(sys()) + turns(30)
        val api = ChatHistory.toApiMessages(history, maxTurns = 10, fullTurns = 2)
        val users = api.filter { it.role == "user" }
        assertEquals(10, users.size)
        assertEquals("指令21", users.first().content)
        assertEquals("指令30", users.last().content)
    }

    @Test
    fun `最近两轮保留完整原文 更早的轮次被压缩`() {
        val big = "```genui\n" + "{\"id\":\"x\"}".repeat(300) + "\n```"
        val history = listOf(sys()) +
            listOf(u("老指令"), a(big)) +
            listOf(u("新指令"), a(big))
        val api = ChatHistory.toApiMessages(history, maxTurns = 10, fullTurns = 2)
        val assistants = api.filter { it.role == "assistant" }
        // 两轮都在 fullTurns 内 → 都保留原文
        assertEquals(big, assistants[0].content)
        assertEquals(big, assistants[1].content)

        // 再来 4 轮，把最早那轮挤出「完整窗口」
        val more = history +
            listOf(u("t3"), a("r3"), u("t4"), a("r4"), u("t5"), a("r5"), u("t6"), a("r6"))
        val api2 = ChatHistory.toApiMessages(more, maxTurns = 10, fullTurns = 2)
        val firstAssistant = api2.first { it.role == "assistant" }
        assertTrue("老轮次的界面 JSON 必须被折叠", firstAssistant.content.length < 200)
        assertTrue(firstAssistant.content.contains("已省略"))
        // 最后一轮的回复必须原样保留
        assertEquals("r6", api2.last().content)
    }

    @Test
    fun `切窗后不留孤立 tool 消息`() {
        // 落盘上限/恢复会把历史从中间截断 → 头部可能残留孤立 tool 残片，必须剔除
        val history = listOf(
            sys(),
            tool("{\"success\":true}"),
            u("新命令"), a("ok")
        )
        val api = ChatHistory.toApiMessages(history, maxTurns = 2, fullTurns = 2)
        assertEquals("system", api.first().role)
        assertEquals("user", api[1].role)
        assertTrue("tool 残片不能出现在窗口头部", api.none { it.role == "tool" })
    }

    @Test
    fun `尾部 tool 结果必须保留 否则工具调用失效`() {
        // 工具调用循环中：assistant(tool_calls) + tool 结果必须原样回传
        val history = listOf(
            sys(),
            u("查一下天气"),
            GenUIChatMessage(
                "assistant", "",
                toolCalls = listOf(com.ai.assistance.quro.genui.aiapp.core.GenUIToolCall(id = "c1", name = "weather", arguments = "{}"))
            ),
            tool("{\"temp\":26}")
        )
        val api = ChatHistory.toApiMessages(history, maxTurns = 10, fullTurns = 2)
        assertEquals("tool", api.last().role)
        assertEquals("{\"temp\":26}", api.last().content)
    }

    @Test
    fun `单个新命令只发 system 加一条 user`() {
        val api = ChatHistory.toApiMessages(listOf(sys(), u("做个计算器")))
        assertEquals(2, api.size)
        assertEquals("system", api[0].role)
        assertEquals("user", api[1].role)
    }

    @Test
    fun `空历史与纯 system 不崩`() {
        assertTrue(ChatHistory.toApiMessages(emptyList()).isEmpty())
        val onlySystem = ChatHistory.toApiMessages(listOf(sys()))
        assertEquals(1, onlySystem.size)
        assertEquals("system", onlySystem[0].role)
    }

    @Test
    fun `工具结果过长被截断`() {
        val long = "x".repeat(9_000)
        val msg = GenUIChatMessage("tool", long, toolCallId = "c1", toolName = "t")
        val shrunk = ChatHistory.shrinkMessage(msg)
        assertTrue(shrunk.content.length < 2_100)
        assertTrue(shrunk.content.contains("已截断"))
        // 同一逻辑在窗口化路径里对「老轮次」生效
        val history = listOf(
            sys(), u("t1"), a("r1"),
            u("t2"), a("r2"),
            u("t3"), a("r3"),
            u("t4"), a("r4")
        ).toMutableList()
        history.add(3, msg) // 把超长工具结果塞进最早那一轮
        val api = ChatHistory.toApiMessages(history, maxTurns = 10, fullTurns = 2)
        val toolMsg = api.first { it.role == "tool" }
        assertTrue(toolMsg.content.length < 2_100)
    }
}
