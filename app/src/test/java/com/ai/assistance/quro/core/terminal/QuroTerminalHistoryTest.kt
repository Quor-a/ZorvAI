package com.ai.assistance.quro.core.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [QuroTerminalHistory] 纯逻辑与 [QuroHistoryCursor] 状态机的单元测试（E-10）。
 *
 * 这两块是终端历史唯一容易出错的地方（顺序、去重语义、上下越界、草稿还原），
 * 且完全不依赖 Android，值得用 JVM 测试锁死。
 */
class QuroTerminalHistoryTest {

    // ════════════════════════════════════════
    // merge
    // ════════════════════════════════════════

    @Test
    fun `merge 追加到末尾（旧到新）`() {
        val r = QuroTerminalHistory.merge(listOf("a", "b"), "c")
        assertEquals(listOf("a", "b", "c"), r)
    }

    @Test
    fun `merge 忽略空白命令`() {
        assertEquals(listOf("a"), QuroTerminalHistory.merge(listOf("a"), ""))
        assertEquals(listOf("a"), QuroTerminalHistory.merge(listOf("a"), "   "))
        assertEquals(listOf("a"), QuroTerminalHistory.merge(listOf("a"), "\n\t"))
    }

    @Test
    fun `merge 会 trim 命令`() {
        assertEquals(listOf("ls -la"), QuroTerminalHistory.merge(emptyList(), "  ls -la  "))
    }

    @Test
    fun `merge 跳过与上一条重复的命令`() {
        val r = QuroTerminalHistory.merge(listOf("a", "ls"), "ls")
        assertEquals(listOf("a", "ls"), r)
    }

    @Test
    fun `merge 不做全局去重`() {
        // bash ignoredups 语义：只压相邻重复。全局去重会把交替执行的历史顺序毁掉。
        val r = QuroTerminalHistory.merge(listOf("ls", "cd ..", "ls"), "cd ..")
        assertEquals(listOf("ls", "cd ..", "ls", "cd .."), r)
    }

    @Test
    fun `merge 超出上限时丢弃最旧的`() {
        val existing = (1..5).map { "cmd$it" }
        val r = QuroTerminalHistory.merge(existing, "cmd6", max = 5)
        assertEquals(5, r.size)
        assertEquals("cmd2", r.first())
        assertEquals("cmd6", r.last())
    }

    @Test
    fun `merge 上限恰好时不丢弃`() {
        val r = QuroTerminalHistory.merge(listOf("a", "b"), "c", max = 3)
        assertEquals(listOf("a", "b", "c"), r)
    }

    @Test
    fun `merge 默认上限为 200`() {
        assertEquals(200, QuroTerminalHistory.MAX_ENTRIES)
        val existing = (1..200).map { "c$it" }
        val r = QuroTerminalHistory.merge(existing, "new")
        assertEquals(200, r.size)
        assertEquals("c2", r.first())
        assertEquals("new", r.last())
    }

    // ════════════════════════════════════════
    // encode / decode
    // ════════════════════════════════════════

    @Test
    fun `encode decode 往返一致`() {
        val list = listOf("ls -la", "cd /data", "echo hi")
        assertEquals(list, QuroTerminalHistory.decode(QuroTerminalHistory.encode(list)))
    }

    @Test
    fun `decode 空串还原为空列表`() {
        assertEquals(emptyList<String>(), QuroTerminalHistory.decode(""))
    }

    @Test
    fun `encode 空列表得到空串`() {
        assertEquals("", QuroTerminalHistory.encode(emptyList()))
    }

    @Test
    fun `编码使用 NUL 分隔从而支持多行命令`() {
        // 用 \n 当分隔符时，这条粘贴的多行命令会被劈成两条历史
        val multiline = "for i in 1 2 3\ndo echo \$i\ndone"
        val roundTrip = QuroTerminalHistory.decode(QuroTerminalHistory.encode(listOf(multiline, "ls")))
        assertEquals(listOf(multiline, "ls"), roundTrip)
        assertEquals(2, roundTrip.size)
    }

    @Test
    fun `编码保持顺序`() {
        val list = listOf("z", "a", "m", "a")
        assertEquals(list, QuroTerminalHistory.decode(QuroTerminalHistory.encode(list)))
    }

    // ════════════════════════════════════════
    // QuroHistoryCursor
    // ════════════════════════════════════════

    @Test
    fun `游标初始停在草稿位`() {
        val c = QuroHistoryCursor(listOf("a", "b"))
        assertTrue(c.atDraft)
        assertEquals(2, c.size)
    }

    @Test
    fun `空历史时上下键都返回 null`() {
        val c = QuroHistoryCursor(emptyList())
        assertNull(c.up("draft"))
        assertNull(c.down())
        assertTrue(c.atDraft)
    }

    @Test
    fun `上键第一次返回最新一条`() {
        val c = QuroHistoryCursor(listOf("old", "mid", "new"))
        assertEquals("new", c.up(""))
        assertFalse(c.atDraft)
    }

    @Test
    fun `连续上键逐条走向更旧`() {
        val c = QuroHistoryCursor(listOf("old", "mid", "new"))
        assertEquals("new", c.up(""))
        assertEquals("mid", c.up(""))
        assertEquals("old", c.up(""))
    }

    @Test
    fun `上键到最旧后停住不越界`() {
        val c = QuroHistoryCursor(listOf("old", "new"))
        c.up("")
        c.up("")
        assertEquals("old", c.up(""))
        assertEquals("old", c.up(""))
    }

    @Test
    fun `下键往更新方向走`() {
        val c = QuroHistoryCursor(listOf("old", "mid", "new"))
        c.up("")   // new
        c.up("")   // mid
        c.up("")   // old
        assertEquals("mid", c.down())
        assertEquals("new", c.down())
    }

    @Test
    fun `下键越过最新一条后还原草稿`() {
        val c = QuroHistoryCursor(listOf("old", "new"))
        assertEquals("new", c.up("我正在打的半截命令"))
        assertEquals("old", c.up(""))
        assertEquals("new", c.down())
        assertEquals("我正在打的半截命令", c.down())
        assertTrue(c.atDraft)
    }

    @Test
    fun `已在草稿位时下键返回 null`() {
        val c = QuroHistoryCursor(listOf("a"))
        assertNull(c.down())
    }

    @Test
    fun `reset 回到草稿位`() {
        val c = QuroHistoryCursor(listOf("a", "b"))
        c.up("")
        assertFalse(c.atDraft)
        c.reset()
        assertTrue(c.atDraft)
        // reset 后再按上键，仍从最新一条开始
        assertEquals("b", c.up(""))
    }

    @Test
    fun `reset 会丢弃暂存的草稿`() {
        val c = QuroHistoryCursor(listOf("a"))
        c.up("旧草稿")
        c.reset()
        assertEquals("a", c.up("新草稿"))
        assertEquals("新草稿", c.down())
    }

    @Test
    fun `只在从草稿位起步时才暂存草稿`() {
        // 第二次 up 传入的 current 不该覆盖已暂存的草稿，
        // 否则草稿会被中途填进输入框的历史条目顶掉
        val c = QuroHistoryCursor(listOf("a", "b"))
        c.up("真草稿")
        c.up("b")           // 输入框此时是 "b"，不是草稿
        c.down()            // -> b
        assertEquals("真草稿", c.down())
    }

    @Test
    fun `游标不受外部列表后续改动影响`() {
        val src = mutableListOf("a", "b")
        val c = QuroHistoryCursor(src)
        src.add("c")
        assertEquals(2, c.size)
        assertEquals("b", c.up(""))
    }
}
