package com.ai.assistance.quro.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * N14 / N15 回归：工具结果压缩与归档。
 *
 * 三条核心契约（改造前都不成立）：
 *  - 被丢弃的中间段里的**错误行必须被救回**（编译错误/堆栈恰在那里）；
 *  - 截断文案**绝不承诺不存在的文件**（旧文案「完整日志见本机文件」是空头支票）；
 *  - 合计超总量预算时，从**最旧**的工具结果开始削（最新的才是当前决策依据）。
 */
class QuroConversationCompactionTest {

    private fun toolMsg(content: String, id: String = "c1") =
        QuroChatMessage(role = "tool", content = content, toolCallId = id)

    /** 检测孤立代理项（emoji / 生僻字被 UTF-16 半截切断的痕迹）。 */
    private fun hasLoneSurrogate(s: String): Boolean {
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c.isHighSurrogate() -> {
                    if (i + 1 >= s.length || !s[i + 1].isLowSurrogate()) return true
                    i += 2
                }
                c.isLowSurrogate() -> return true
                else -> i++
            }
        }
        return false
    }

    // ═══ extractKeyLines ═══════════════════════════════════════════════════

    @Test
    fun `extractKeyLines picks english error lines`() {
        val dropped = "ok\nall good\nERROR: build failed\nstill ok\n"
        val out = extractKeyLines(dropped)
        assertTrue(out.contains("ERROR: build failed"))
        assertFalse(out.contains("all good"))
    }

    @Test
    fun `extractKeyLines picks chinese error lines`() {
        val dropped = "正常输出\n编译失败：找不到符号\n继续输出\n"
        val out = extractKeyLines(dropped)
        assertTrue(out.contains("编译失败"))
    }

    @Test
    fun `extractKeyLines picks stack trace markers`() {
        val out = extractKeyLines("Caused by: java.lang.NullPointerException\nat a.b(C.java:1)")
        assertTrue(out.contains("Caused by"))
    }

    @Test
    fun `extractKeyLines returns empty when nothing matches`() {
        assertEquals("", extractKeyLines("line one\nline two\nline three"))
    }

    @Test
    fun `extractKeyLines returns empty for blank input`() {
        assertEquals("", extractKeyLines(""))
        assertEquals("", extractKeyLines("   \n  \n"))
    }

    @Test
    fun `extractKeyLines skips absurdly long single lines`() {
        // 一整行 minified JS / base64 里恰好含 "error" 时，不该把它当成「错误行」整段塞回去
        val huge = "x".repeat(5_000) + "error"
        assertEquals("", extractKeyLines("ok\n$huge\n"))
    }

    @Test
    fun `extractKeyLines deduplicates repeated lines`() {
        val out = extractKeyLines("ERROR: same\nERROR: same\nERROR: same")
        assertEquals(1, out.split("\n").count { it.contains("ERROR: same") })
    }

    @Test
    fun `extractKeyLines honours the line budget`() {
        val dropped = (0 until 40).joinToString("\n") { "ERROR: distinct failure $it" }
        val lines = extractKeyLines(dropped, maxLines = 5).split("\n")
        assertEquals(5, lines.size)
    }

    @Test
    fun `extractKeyLines honours the char budget`() {
        val dropped = (0 until 12).joinToString("\n") { "ERROR: " + "d".repeat(90) + " $it" }
        val out = extractKeyLines(dropped, maxLines = 12, maxChars = 300)
        assertTrue("字符预算应生效，实际 ${out.length}", out.length <= 300)
        assertTrue(out.isNotEmpty())
    }

    @Test
    fun `extractKeyLines collapses runs of whitespace`() {
        val out = extractKeyLines("ERROR:      a        b")
        assertEquals("ERROR: a b", out)
    }

    @Test
    fun `extractKeyLines keeps source order`() {
        val out = extractKeyLines("ERROR: first\nnoise\nERROR: second")
        assertTrue(out.indexOf("first") < out.indexOf("second"))
    }

    // ═══ truncateToolResult ════════════════════════════════════════════════

    @Test
    fun `truncation rescues error lines from the dropped middle`() {
        val sb = StringBuilder()
        sb.append("head-line\n")
        repeat(200) { sb.append("noise line $it padding padding padding padding\n") }
        sb.append("ERROR: build failed with exit code 1\n")
        repeat(200) { sb.append("more noise $it padding padding padding padding\n") }
        sb.append("tail-line\n")
        val text = sb.toString()
        val out = truncateToolResult(text, cap = 800)
        assertTrue("被丢弃中间段里的错误行必须被救回", out.contains("ERROR: build failed with exit code 1"))
        assertTrue(out.contains("head-line"))
        assertTrue(out.contains("tail-line"))
    }

    @Test
    fun `truncation stays within a sane size even with key lines attached`() {
        val text = (0 until 500).joinToString("\n") { "ERROR: failure number $it with padding" }
        val out = truncateToolResult(text, cap = 1_600)
        // 头 640 + 尾 960 + 关键行 600 + 文案 ≈ 2500 上限
        assertTrue("截断后仍应受控，实际 ${out.length}", out.length < 3_000)
    }

    @Test
    fun `truncation names the real archive path when archiving succeeds`() {
        val out = truncateToolResult("a".repeat(5_000), cap = 1_000, archive = { "/data/x/tool_abc.txt" })
        assertTrue(out.contains("/data/x/tool_abc.txt"))
        assertFalse("不得再出现没有指路的承诺", out.contains("见本机文件"))
    }

    @Test
    fun `truncation never promises a file when archiving fails`() {
        val out = truncateToolResult("a".repeat(5_000), cap = 1_000, archive = { null })
        assertFalse(out.contains("见本机文件"))
        assertFalse(out.contains("已归档"))
        assertTrue("归档失败要引导重新执行并缩小范围", out.contains("重新执行"))
    }

    @Test
    fun `truncation without an archiver never promises a file`() {
        val out = truncateToolResult("a".repeat(5_000), cap = 1_000)
        assertFalse(out.contains("见本机文件"))
        assertTrue(out.contains("重新执行"))
    }

    @Test
    fun `archiver receives the full original text`() {
        val full = "b".repeat(5_000)
        var seen: String? = null
        truncateToolResult(full, cap = 1_000, archive = { seen = it; null })
        assertEquals("归档器必须拿到完整原文（而不是已截断的片段）", full, seen)
    }

    @Test
    fun `no key line header is emitted when nothing was rescued`() {
        val out = truncateToolResult("z".repeat(5_000), cap = 1_000)
        assertFalse("抽不到关键行时不该留下占位文案", out.contains("以下是其中的关键行"))
        assertTrue(out.contains("工具输出过长已截断"))
    }

    @Test
    fun `truncation never splits a surrogate pair`() {
        // 全部由 emoji（代理对）组成，按 UTF-16 截断必然切裂
        val text = "\uD83D\uDE00".repeat(3_000)
        val out = truncateToolResult(text, cap = 1_000)
        assertFalse("不得产生孤立代理项", hasLoneSurrogate(out))
    }

    @Test
    fun `truncation survives tiny caps`() {
        val out = truncateToolResult("hello world ".repeat(200), cap = 1)
        assertTrue(out.isNotEmpty())
        assertFalse(hasLoneSurrogate(out))
    }

    // ═══ compactToolResults ════════════════════════════════════════════════

    @Test
    fun `short tool results are left untouched`() {
        val list = listOf(toolMsg("short result"))
        assertEquals(list, compactToolResults(list))
    }

    @Test
    fun `lists without tool messages are returned as is`() {
        val list = listOf(
            QuroChatMessage(role = "user", content = "u"),
            QuroChatMessage(role = "assistant", content = "a"),
        )
        assertEquals(list, compactToolResults(list))
    }

    @Test
    fun `only tool messages are rewritten`() {
        val list = listOf(
            QuroChatMessage(role = "user", content = "x".repeat(5_000)),
            QuroChatMessage(role = "assistant", content = "y".repeat(5_000)),
            toolMsg("z".repeat(5_000)),
        )
        val out = compactToolResults(list)
        assertEquals(list[0].content, out[0].content)
        assertEquals(list[1].content, out[1].content)
        assertTrue(out[2].content.length < list[2].content.length)
    }

    @Test
    fun `oversized single result is compacted but stays paired`() {
        val list = listOf(toolMsg("z".repeat(5_000)))
        val out = compactToolResults(list)
        assertEquals(1, out.size)
        assertEquals("c1", out[0].toolCallId)
        assertEquals("tool", out[0].role)
    }

    @Test
    fun `total budget shrinks oldest tool results first`() {
        val messages = (0 until 30).map { i ->
            QuroChatMessage(role = "tool", content = "x".repeat(1_000) + "-$i", toolCallId = "c$i")
        }
        val out = compactToolResults(messages)
        val total = out.filter { it.role == "tool" }.sumOf { it.content.length }
        assertTrue("合计必须被压进总预算，实际 $total", total <= TOOL_RESULTS_TOTAL_CAP)
        // 消息条数不变 → call↔result 配对不被破坏
        assertEquals(messages.size, out.size)
        // 最新的结果（当前决策依据）不该被动
        assertEquals(messages.last().content, out.last().content)
        // 最旧的必须被削
        assertTrue(out.first().content.length < messages.first().content.length)
    }

    @Test
    fun `total budget leaves small collections alone`() {
        // 每条都远小于单条上限、合计也远小于总预算 → 必须原样返回（零开销、零行为变更）
        val messages = (0 until 5).map { i ->
            QuroChatMessage(role = "tool", content = "small $i", toolCallId = "c$i")
        }
        assertEquals(messages, compactToolResults(messages))
    }

    @Test
    fun `archiver is invoked for every truncated result`() {
        val messages = listOf(toolMsg("z".repeat(5_000), "c1"), toolMsg("y".repeat(5_000), "c2"))
        var calls = 0
        compactToolResults(messages) { calls++; "/tmp/tool.txt" }
        assertEquals(2, calls)
    }

    // ═══ toLlmMessages 集成 ════════════════════════════════════════════════

    @Test
    fun `toLlmMessages threads the archiver into tool compaction`() {
        val store = QuroConversationStore()
        val call = QuroToolCall(id = "c1", name = "terminal_exec", arguments = "{}")
        store.add(QuroMessage(role = "assistant", content = "", toolCalls = listOf(call)))
        store.add(QuroMessage(role = "tool", content = "z".repeat(5_000), toolCallId = "c1"))
        var archived = 0
        val msgs = store.toLlmMessages(contextWindow = 100_000, archive = { archived++; "/tmp/a.txt" })
        assertEquals("归档器必须一路传到压缩层", 1, archived)
        assertTrue(msgs.first { it.role == "tool" }.content.contains("/tmp/a.txt"))
    }

    @Test
    fun `toLlmMessages without an archiver still compacts`() {
        val store = QuroConversationStore()
        val call = QuroToolCall(id = "c1", name = "terminal_exec", arguments = "{}")
        store.add(QuroMessage(role = "assistant", content = "", toolCalls = listOf(call)))
        store.add(QuroMessage(role = "tool", content = "z".repeat(5_000), toolCallId = "c1"))
        val msgs = store.toLlmMessages(contextWindow = 100_000)
        val toolOut = msgs.first { it.role == "tool" }.content
        assertTrue(toolOut.length < 5_000)
        assertFalse(toolOut.contains("见本机文件"))
    }

    @Test
    fun `unspecified budget falls back to the conservative value not to the ceiling`() {
        // contextWindow = 0（子智能体走的就是这条）不该等于「完全不设防」
        assertEquals(
            com.ai.assistance.quro.core.network.QuroModelContextBudget.CONSERVATIVE_INPUT_TOKENS,
            32_768,
        )
        assertEquals(
            MODEL_MAX_INPUT_TOKENS,
            com.ai.assistance.quro.core.network.QuroModelContextBudget.ABSOLUTE_MAX_INPUT_TOKENS,
        )
    }
}
