# -*- coding: utf-8 -*-
"""#213 第二批：给新增解析能力补回归测试（追加到 MarkdownParserTest 类末尾）。"""
import io
import os

ROOT = r"D:\Calw OS-project\QuroAI"
P = os.path.join(ROOT, r"app\src\test\java\com\ai\assistance\quro\ui\dialog\MarkdownParserTest.kt")

with io.open(P, "r", encoding="utf-8", newline="") as f:
    raw = f.read()
nl = "\r\n" if "\r\n" in raw else "\n"
t = raw.replace("\r\n", "\n")

OLD = """        // 上限不得被绕过：屏宽再大也封顶
        assertEquals(720, messageContentMaxWidth(3000, 34, 32, 0))
    }
}
"""
NEW = """        // 上限不得被绕过：屏宽再大也封顶
        assertEquals(720, messageContentMaxWidth(3000, 34, 32, 0))
    }

    // ══════════════════════════════════════════════════════════
    //  #213：数学公式 / 脚注 / 上下标 / details / 表格导出
    // ══════════════════════════════════════════════════════════

    // `$` 在 Kotlin 字符串里是模板起始符，用 charArray 构造以免到处转义
    private val D = String(charArrayOf('$'))

    @Test
    fun `块级公式独立成块且原文完整`() {
        val blocks = parseMarkdown("${D}${D}E=mc^2${D}${D}")
        assertEquals(1, blocks.size)
        val m = blocks[0] as MdBlock.Math
        // 🔴 关键：^2 绝不能被行内解析吃掉 —— 公式原文必须一字不差
        assertEquals("E=mc^2", m.tex)
    }

    @Test
    fun `行内公式内部不被 Markdown 拆烂`() {
        // 旧行为：`a_i + b_` 被当成斜体区间，公式被切成两半
        val spans = parseInlineSpans("${D}a_i + b_j${D}")
        assertEquals(1, spans.size)
        assertTrue("公式区间必须整体保留", spans[0].math)
        assertFalse("公式内不得产生斜体", spans[0].italic)
        assertEquals("a_i + b_j", spans[0].text)
    }

    @Test
    fun `孤立的美元符不吞掉后文`() {
        // 只有一个 `$` 时不能把后面整段都当成公式
        val spans = parseInlineSpans("价格 5 ${D} 元")
        assertTrue(spans.joinToString("") { it.text }.contains("元"))
    }

    @Test
    fun `脚注定义挂到文末且不劈断正文`() {
        val blocks = parseMarkdown("第一段\\n\\n[^1]: 这是脚注\\n\\n第二段")
        // 段落 + 段落 + 脚注，脚注不得插在两个段落中间
        assertEquals(3, blocks.size)
        assertTrue(blocks[0] is MdBlock.Paragraph)
        assertTrue(blocks[1] is MdBlock.Paragraph)
        val fn = blocks[2] as MdBlock.Footnotes
        assertEquals(1, fn.items.size)
        assertEquals("1", fn.items[0].id)
        assertEquals("这是脚注", fn.items[0].text)
    }

    @Test
    fun `脚注引用渲染为上标角标`() {
        val spans = parseInlineSpans("结论[^1]")
        val sup = spans.firstOrNull { it.sup }
        assertTrue("[^1] 必须是上标角标", sup != null)
        assertEquals("1", sup!!.text)
    }

    @Test
    fun `上标与下标`() {
        val sup = parseInlineSpans("x^2^").firstOrNull { it.sup }
        assertEquals("2", sup?.text)
        val sub = parseInlineSpans("H~2~O").firstOrNull { it.sub }
        assertEquals("2", sub?.text)
        // 双波浪仍是删除线，不能被下标抢走
        val strike = parseInlineSpans("~~删掉~~").firstOrNull { it.strike }
        assertEquals("删掉", strike?.text)
    }

    @Test
    fun `details 折叠区块被识别`() {
        val blocks = parseMarkdown("<details>\\n<summary>点我看细节</summary>\\n隐藏内容\\n</details>")
        val d = blocks.firstOrNull { it is MdBlock.Details } as? MdBlock.Details
        assertTrue("details 必须被解析成折叠块", d != null)
        assertEquals("点我看细节", d!!.summary)
        assertTrue(d.body.contains("隐藏内容"))
    }

    @Test
    fun `表格导出 CSV 并对含逗号单元格加引号`() {
        assertEquals("a,b\\n1,2", tableToCsv(listOf("a", "b"), listOf(listOf("1", "2"))))
        // 含逗号的单元格必须加引号，否则粘进 Excel 会串列
        assertEquals("\\"x,y\\",z", tableToCsv(listOf("x,y", "z"), emptyList()))
        // 引号自身要翻倍转义
        assertEquals("\\"a\\"\\"b\\"", tableToCsv(listOf("a\\"b"), emptyList()))
    }
}
"""
n = t.count(OLD)
print("anchor hits:", n)
if n == 1:
    t = t.replace(OLD, NEW)
    out = t.replace("\n", nl)
    tmp = P + ".tmp"
    with io.open(tmp, "w", encoding="utf-8", newline="") as f:
        f.write(out)
    os.replace(tmp, P)
    print("ok written", os.path.getsize(P), "newline=", repr(nl))
else:
    print("FAIL")
