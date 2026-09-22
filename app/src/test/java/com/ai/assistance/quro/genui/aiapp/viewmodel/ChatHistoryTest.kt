package com.ai.assistance.quro.genui.aiapp.viewmodel

import com.ai.assistance.quro.genui.aiapp.core.GenUIChatMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        assertTrue(firstAssistant.content.contains("系统折叠提示"))
        // 折叠标记必须明确叫模型别复述 —— 中性文案会被模型当成界面内容复述到画布上
        assertTrue(firstAssistant.content.contains("不要在任何回复或界面里复述"))
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

    @Test
    fun `裸 JSON 没有围栏也必须整段折叠 绝不留下截断残片`() {
        // 实测模型经常不写 ```genui 围栏，整段 JSON 裸着输出（诊断行 含genui围栏=false）。
        // 旧实现只能"超长截 800 字符" → 历史里留半截 JSON → 模型接着往下编/把格式缝在一起。
        val bare = "好的，给你做个天气卡。\n" +
            "{\"id\":\"root\",\"root\":{\"type\":\"card\",\"children\":[" +
            (1..60).joinToString(",") { "{\"type\":\"text\",\"properties\":{\"text\":\"行$it\"}}" } +
            "]}}"
        assertTrue("构造的样例要足够长", bare.length > 1_000)

        val out = ChatHistory.compressAssistant(bare)
        assertTrue("人话要留着：$out", out.contains("给你做个天气卡"))
        assertTrue("大段 JSON 要被折叠掉", out.contains("系统折叠提示"))
        assertTrue("不能留下任何 JSON 残片：$out", !out.contains("{"))
        assertTrue("压缩后应远短于原文", out.length < 300)
    }

    @Test
    fun `折叠标记必须长得像系统提示且明确禁止复述`() {
        // 回归：早先的文案是「〔genui 内容 96 字符已省略〕」，模型把它当成上一轮的界面内容
        // 原样复述到画布上（用户截图里画布孤零零一行这个字样）。
        // 折叠标记进的是**模型上下文**，措辞必须把"别复述"写死。
        val folded = ChatHistory.foldFences("```genui\n{\"a\":1}\n```")
        assertTrue("要标明是系统提示：$folded", folded.contains("系统折叠提示"))
        assertTrue("要写明禁止复述：$folded", folded.contains("不要在任何回复或界面里复述"))
        assertFalse("不能再用中性括号文案（会被模型当正文）：$folded", folded.contains("〔"))
    }

    @Test
    fun `短的内联 JSON 不该被误折叠`() {
        val s = "参数是 {\"a\":1} 这样传。"
        assertEquals(s, ChatHistory.stripJsonBlobs(s))
    }

    @Test
    fun `说明用的花括号不会被当成 JSON`() {
        val s = "下面是「{说明}」这段文字，里面没有键值对，请朗读。"
        assertEquals(s, ChatHistory.stripJsonBlobs(s))
    }

    @Test
    fun `文本里多处 JSON 全部折叠 只留人话`() {
        val big = (1..40).joinToString(",") { "\"k$it\":$it" }
        val s = "前一句 {\"root\":{$big}} 中间 {\"another\":{$big}} 后一句"
        val out = ChatHistory.stripJsonBlobs(s)
        assertTrue("两处都要折掉：$out", !out.contains("\"k1\""))
        assertTrue(out.contains("前一句"))
        assertTrue(out.contains("后一句"))
    }
}
