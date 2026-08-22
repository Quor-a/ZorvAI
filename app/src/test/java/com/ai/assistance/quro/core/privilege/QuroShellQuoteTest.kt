package com.ai.assistance.quro.core.privilege

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [QuroShellQuote] 单元测试（E-7）。
 *
 * 这个函数是 root / Shizuku 通道**唯一**的注入防线：
 * `su -c <cmd>` 与 Shizuku AIDL `exec("su -c " + cmd)` 都要靠它把用户/模型给的
 * 命令包成单个 argv。转义写错等于把设备交出去，所以逐条钉死行为。
 */
class QuroShellQuoteTest {

    @Test
    fun `普通命令被整体包进单引号`() {
        assertEquals("'ls -la /sdcard'", QuroShellQuote.quote("ls -la /sdcard"))
    }

    @Test
    fun `空串得到一对空单引号`() {
        // 必须是 ''，不能是空串：空串会让 su -c 少吃一个参数
        assertEquals("''", QuroShellQuote.quote(""))
    }

    @Test
    fun `内部单引号被正确转义`() {
        // 闭合 -> 转义的单引号 -> 重开
        assertEquals("'echo it'\\''s'", QuroShellQuote.quote("echo it's"))
    }

    @Test
    fun `连续多个单引号全部被转义`() {
        assertEquals("'a'\\''b'\\''c'", QuroShellQuote.quote("a'b'c"))
    }

    @Test
    fun `美元符与反引号保持字面量`() {
        // 单引号内不做变量展开与命令替换，因此原样保留即为正确
        val q = QuroShellQuote.quote("echo \$HOME `id`")
        assertEquals("'echo \$HOME `id`'", q)
    }

    @Test
    fun `分号与逻辑运算符不会逃出引号`() {
        val q = QuroShellQuote.quote("ls; rm -rf /")
        assertEquals("'ls; rm -rf /'", q)
        // 关键断言：除首尾外不存在裸露的单引号边界，命令无法逃逸
        assertTrue(q.startsWith("'"))
        assertTrue(q.endsWith("'"))
    }

    @Test
    fun `注入用的引号闭合尝试被挡住`() {
        // 攻击载荷：想用 ' 提前闭合再拼接自己的命令
        val payload = "x'; rm -rf /sdcard; echo '"
        val q = QuroShellQuote.quote(payload)
        assertEquals("'x'\\''; rm -rf /sdcard; echo '\\'''", q)

        // 真正要保证的性质不是「结果里没有某个子串」——
        // `'\''` 这个转义序列本身就包含 `'; ` 这样的字符组合，用子串黑名单去断言是错的。
        // 唯一有意义的不变量是：shell 按 POSIX 分词后得到**一个**单词，且内容等于原文。
        val (value, isSingleWord) = shellTokenize(q)
        assertEquals("解引号后必须还原成原始载荷", payload, value)
        assertTrue("载荷必须被约束在一个 shell 单词内，不能逃逸成多个参数", isSingleWord)
    }

    @Test
    fun `任意载荷都满足 引号往返 与 单词不逃逸`() {
        val payloads = listOf(
            "",
            "ls",
            "ls -la /sdcard",
            "it's",
            "a'b'c",
            "'",
            "''",
            "\\",
            "a\\'b",
            "\$(whoami)",
            "`id`",
            "x'; rm -rf /; echo '",
            "x\"; rm -rf /; echo \"",
            "foo && bar || baz; qux",
            "多字节 中文 路径/名字",
            "tab\there",
            "new\nline",
        )
        for (p in payloads) {
            val (value, isSingleWord) = shellTokenize(QuroShellQuote.quote(p))
            assertEquals("往返失败: <$p>", p, value)
            assertTrue("逃逸成多个单词: <$p>", isSingleWord)
        }
    }

    /**
     * 极简 POSIX shell 分词器，仅用于测试断言。
     *
     * 模拟 shell 读取一个单词时的规则：
     *  - 单引号内：一切字符都是字面量，直到下一个 `'`；
     *  - 单引号外：`\` 转义下一个字符；
     *  - 单引号外出现**未转义的空白**，就意味着单词被切断（= 注入成功）。
     *
     * @return (解引号后的内容, 是否仍是单个单词)
     */
    private fun shellTokenize(s: String): Pair<String, Boolean> {
        val sb = StringBuilder()
        var i = 0
        var inQuote = false
        var singleWord = true
        while (i < s.length) {
            val c = s[i]
            when {
                inQuote && c == '\'' -> { inQuote = false; i++ }
                inQuote -> { sb.append(c); i++ }
                c == '\'' -> { inQuote = true; i++ }
                c == '\\' && i + 1 < s.length -> { sb.append(s[i + 1]); i += 2 }
                c == ' ' || c == '\t' || c == '\n' -> { singleWord = false; i++ }
                else -> { sb.append(c); i++ }
            }
        }
        // 引号没闭合同样是逃逸（shell 会继续吃后面的内容）
        if (inQuote) singleWord = false
        return sb.toString() to singleWord
    }

    @Test
    fun `换行与制表符原样保留`() {
        assertEquals("'a\nb\tc'", QuroShellQuote.quote("a\nb\tc"))
    }

    @Test
    fun `含空格的路径可安全用于 cd`() {
        assertEquals("'/sdcard/My Docs/子 目录'", QuroShellQuote.quote("/sdcard/My Docs/子 目录"))
    }

    @Test
    fun `引号数量守恒`() {
        // 每个输入里的 ' 会产生 3 个额外的 '（'\''），再加首尾各 1 个
        val input = "a'b'c'd"
        val inner = input.count { it == '\'' }
        val q = QuroShellQuote.quote(input)
        assertEquals(2 + inner * 3, q.count { it == '\'' })
    }
}
