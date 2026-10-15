package com.ai.assistance.quro.core.novaterm

import com.ai.assistance.quro.core.novaterm.command.CommandParser
import com.ai.assistance.quro.core.novaterm.command.CommandType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * QuroTerm 沙盒命令序列解析测试（issue #11 ④）。
 *
 * 钉死的真实缺陷：旧 CommandParser 只认管道/重定向/后台，**不认 `;` `&&` `||`**，
 * 于是 `echo hi; ls` 被当成 echo 的参数整串回显、后半段从不执行也不报错
 * （「静默错误」）。同时覆盖引号内的分隔符不被误切。
 */
class CommandSequenceTest {

    // ════════ `;` 序列（issue 主诉求）═══════════

    @Test
    fun `分号切出序列且运算符正确`() {
        val p = CommandParser.parse("echo hi; ls")
        assertEquals(CommandType.SEQUENCE, p.type)
        assertEquals(2, p.sequence.size)
        assertEquals("", p.operators[0])
        assertEquals(";", p.operators[1])
        assertEquals("echo", p.sequence[0].mainCommand.name)
        assertEquals("ls", p.sequence[1].mainCommand.name)
        assertFalse("序列不应误判为后台", p.background)
    }

    @Test
    fun `三段序列运算符依次为分号`() {
        val p = CommandParser.parse("cd /tmp; ls; pwd")
        assertEquals(CommandType.SEQUENCE, p.type)
        assertEquals(listOf("", ";", ";"), p.operators)
        assertEquals(listOf("cd", "ls", "pwd"), p.sequence.map { it.mainCommand.name })
    }

    @Test
    fun `引号内的分号不切段`() {
        val p = CommandParser.parse("""echo "a;b"; ls""")
        assertEquals(CommandType.SEQUENCE, p.type)
        assertEquals(2, p.sequence.size)
        // 第一段必须是简单 echo，参数是带分号的整串（不是被切开的两段）
        assertEquals(CommandType.SIMPLE, p.sequence[0].type)
        assertEquals("echo", p.sequence[0].mainCommand.name)
        assertEquals(listOf("a;b"), p.sequence[0].mainCommand.args)
    }

    @Test
    fun `单行只有引号分号时不是序列`() {
        val p = CommandParser.parse("""echo "a;b" """.trim())
        assertEquals(CommandType.SIMPLE, p.type)
        assertEquals(listOf("a;b"), p.mainCommand.args)
    }

    // ════════ && / || ════════

    @Test
    fun `且或运算符切出序列`() {
        val p = CommandParser.parse("mkdir test && cd test || echo fail")
        assertEquals(CommandType.SEQUENCE, p.type)
        assertEquals(listOf("", "&&", "||"), p.operators)
        assertEquals(listOf("mkdir", "cd", "echo"), p.sequence.map { it.mainCommand.name })
    }

    @Test
    fun `且或结尾不误判为后台`() {
        // 旧实现无脑 removeSuffix("&")：`echo a &&` 会被剥成 `echo a &`，语义直接改错
        val p = CommandParser.parse("echo a &&")
        assertFalse("&& 结尾不是后台执行", p.background)
        assertEquals(CommandType.SEQUENCE, p.type)
    }

    @Test
    fun `结尾单 and 是后台且不切段`() {
        val p = CommandParser.parse("sleep 1 &")
        assertTrue("结尾 & 是后台执行", p.background)
        assertEquals(CommandType.SIMPLE, p.type)
        assertEquals("sleep", p.mainCommand.name)
    }

    // ════════ 空段与回归 ════════

    @Test
    fun `连续分号产生空段但不崩`() {
        val p = CommandParser.parse("echo a;; echo b")
        assertEquals(CommandType.SEQUENCE, p.type)
        assertEquals(3, p.sequence.size)
        assertTrue("中间段应为空命令", p.sequence[1].mainCommand.name.isEmpty())
    }

    @Test
    fun `纯管道仍是管道不升级为序列`() {
        val p = CommandParser.parse("cat file | grep x")
        assertEquals(CommandType.PIPELINE, p.type)
        assertEquals(2, p.pipeline.size)
    }

    @Test
    fun `管道加分号先切序列再切管道`() {
        val p = CommandParser.parse("cat a | grep b; ls")
        assertEquals(CommandType.SEQUENCE, p.type)
        assertEquals(2, p.sequence.size)
        assertEquals(CommandType.PIPELINE, p.sequence[0].type)
        assertEquals(2, p.sequence[0].pipeline.size)
        assertEquals(CommandType.SIMPLE, p.sequence[1].type)
    }

    @Test
    fun `引号内的竖线不切管道`() {
        // 存量 bug：旧实现 cleanInput.split("|") 会把 `echo "a|b"` 切成三段
        val p = CommandParser.parse("""echo "a|b" """.trim())
        assertEquals(CommandType.SIMPLE, p.type)
        assertEquals(listOf("a|b"), p.mainCommand.args)
    }

    @Test
    fun `重定向仍是重定向`() {
        val p = CommandParser.parse("echo hi > out.txt")
        assertEquals(CommandType.REDIRECTION, p.type)
        assertEquals("echo", p.mainCommand.name)
        assertEquals("out.txt", p.redirectTarget)
    }

    @Test
    fun `简单命令不受影响`() {
        val p = CommandParser.parse("ls -la /tmp")
        assertEquals(CommandType.SIMPLE, p.type)
        assertEquals("ls", p.mainCommand.name)
        assertEquals(listOf("-la", "/tmp"), p.mainCommand.args)
    }
}
