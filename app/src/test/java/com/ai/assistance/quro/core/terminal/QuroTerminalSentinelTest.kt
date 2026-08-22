package com.ai.assistance.quro.core.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [QuroTerminalSentinel] 单元测试（E-8）。
 *
 * 覆盖点全部围绕「旧固定哨兵 `QURO_DONE` 会被命令输出误触发」这个真实缺陷：
 * 随机 token、严格结构解析、解析失败必须返回 null（而不是给出一个错误的退出码）。
 */
class QuroTerminalSentinelTest {

    private val rs = QuroTerminalSentinel.RS

    // ════════ token 生成 ════════

    @Test
    fun `newToken 带前缀且长度固定`() {
        val t = QuroTerminalSentinel.newToken()
        assertTrue("应带 QURO_DONE_ 前缀，实际=$t", t.startsWith(QuroTerminalSentinel.PREFIX))
        assertEquals(QuroTerminalSentinel.PREFIX.length + 16, t.length)
    }

    @Test
    fun `newToken 每次不同`() {
        // 随机性是 E-8 的根，退化成固定值就等于回到旧缺陷
        val a = QuroTerminalSentinel.newToken()
        val b = QuroTerminalSentinel.newToken()
        assertNotEquals(a, b)
    }

    @Test
    fun `newToken 只含大写十六进制字符`() {
        val body = QuroTerminalSentinel.newToken().removePrefix(QuroTerminalSentinel.PREFIX)
        assertTrue("body=$body", body.all { it in '0'..'9' || it in 'A'..'F' })
    }

    // ════════ emitCommand ════════

    @Test
    fun `emitCommand 用 POSIX 八进制转义而非 bash 的 x1e`() {
        // \xHH 是 bash/GNU 扩展，dash / 部分 BusyBox printf 会原样输出 "x1e"
        val cmd = QuroTerminalSentinel.emitCommand("TOK")
        assertTrue(cmd.contains("\\036"))
        assertFalse(cmd.contains("\\x1e"))
    }

    @Test
    fun `emitCommand 写到 stderr 并带上退出码与 PWD`() {
        val cmd = QuroTerminalSentinel.emitCommand("TOK")
        assertTrue(cmd.contains(">&2"))
        assertTrue(cmd.contains("\"\$?\""))
        assertTrue(cmd.contains("\"\$PWD\""))
        assertTrue(cmd.contains("TOK:%d:%s"))
    }

    // ════════ 预筛 ════════

    @Test
    fun `looksLikeSentinel 只在包含 token 时为真`() {
        assertTrue(QuroTerminalSentinel.looksLikeSentinel("x${rs}TOK:0:/root$rs", "TOK"))
        assertFalse(QuroTerminalSentinel.looksLikeSentinel("hello world", "TOK"))
    }

    // ════════ parse 正常路径 ════════

    @Test
    fun `parse 解析标准哨兵行`() {
        val done = QuroTerminalSentinel.parse("${rs}TOK:0:/data/local/tmp$rs", "TOK")
        assertEquals(QuroTerminalSentinel.Done(0, "/data/local/tmp"), done)
    }

    @Test
    fun `parse 解析非零退出码`() {
        val done = QuroTerminalSentinel.parse("${rs}TOK:127:/root$rs", "TOK")
        assertEquals(127, done?.exitCode)
    }

    @Test
    fun `parse 允许 cwd 里带冒号`() {
        // 目录名合法字符包含 ':'，按第一个冒号切会把路径截断
        val done = QuroTerminalSentinel.parse("${rs}TOK:0:/sd:card/a:b$rs", "TOK")
        assertEquals("/sd:card/a:b", done?.cwd)
    }

    @Test
    fun `parse 容忍哨兵与命令输出粘在同一行`() {
        val done = QuroTerminalSentinel.parse("hello${rs}TOK:3:/tmp$rs", "TOK")
        assertEquals(3, done?.exitCode)
        assertEquals("/tmp", done?.cwd)
    }

    @Test
    fun `parse 容忍尾部换行与空白`() {
        val done = QuroTerminalSentinel.parse("${rs}TOK:0:/root$rs  \r", "TOK")
        assertEquals(QuroTerminalSentinel.Done(0, "/root"), done)
    }

    // ════════ parse 失败路径（关键：绝不能返回一个"看起来对"的结果）════════

    @Test
    fun `parse 对不含 token 的行返回 null`() {
        assertNull(QuroTerminalSentinel.parse("just some output", "TOK"))
    }

    @Test
    fun `parse 对缺少冒号结构的行返回 null`() {
        // 这就是 echo <token> 的场景：token 在，但没有 :exit:cwd
        assertNull(QuroTerminalSentinel.parse("TOK", "TOK"))
        assertNull(QuroTerminalSentinel.parse("echo TOK done", "TOK"))
    }

    @Test
    fun `parse 对只有一个冒号的行返回 null`() {
        assertNull(QuroTerminalSentinel.parse("TOK:0", "TOK"))
    }

    @Test
    fun `parse 对非整数退出码返回 null`() {
        assertNull(QuroTerminalSentinel.parse("${rs}TOK:abc:/root$rs", "TOK"))
    }

    @Test
    fun `parse 对空退出码返回 null`() {
        assertNull(QuroTerminalSentinel.parse("${rs}TOK::/root$rs", "TOK"))
    }

    @Test
    fun `parse 用错误 token 不会命中`() {
        // 不同会话的哨兵不能互相误认
        assertNull(QuroTerminalSentinel.parse("${rs}TOK_A:0:/root$rs", "TOK_B"))
    }

    // ════════ stripSentinel ════════

    @Test
    fun `stripSentinel 保留哨兵前的真实输出`() {
        assertEquals("hello", QuroTerminalSentinel.stripSentinel("hello${rs}TOK:0:/root$rs", "TOK"))
    }

    @Test
    fun `stripSentinel 纯哨兵行结果为空串`() {
        assertEquals("", QuroTerminalSentinel.stripSentinel("${rs}TOK:0:/root$rs", "TOK"))
    }

    @Test
    fun `stripSentinel 无 token 时原样返回`() {
        assertEquals("plain line", QuroTerminalSentinel.stripSentinel("plain line", "TOK"))
    }

    // ════════ 回归：旧固定哨兵的误触发场景 ════════

    @Test
    fun `回归 用户 echo 出的 token 字面量不会被当成命令完成`() {
        val token = QuroTerminalSentinel.newToken()
        // 用户执行 `echo <token>`，shell 原样打印一行
        val userLine = token
        assertTrue("预筛会命中（这没问题）", QuroTerminalSentinel.looksLikeSentinel(userLine, token))
        // 但严格解析必须失败 —— 否则 busy 被提前复位、退出码错乱
        assertNull("严格解析必须拒绝", QuroTerminalSentinel.parse(userLine, token))
    }

    @Test
    fun `回归 grep 命中行不会被当成命令完成`() {
        val token = QuroTerminalSentinel.newToken()
        val grepLine = "QuroTerminalSentinel.kt:44:    const val PREFIX = \"$token\""
        assertNull(QuroTerminalSentinel.parse(grepLine, token))
    }
}
