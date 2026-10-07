package com.ai.assistance.quro.core.cards

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #181 mermaid 失败可定位性。
 *
 * 真机截图（23:44:54）原文：
 * ```
 * 流程图渲染失败：
 * Lexical error on line 4. Unrecognized text.
 * ...quro-ui 渲染原生控件」 B -->{D[纯文本回复] C -
 * -----------
 * ```
 */
class Issue181MermaidDiagTest {

    /** 与真机报错完全一致的文案。 */
    private val realErr = "Lexical error on line 4. Unrecognized text.\n" +
        "...quro-ui 渲染原生控件」 B -->{D[纯文本回复] C -\n" +
        "-----------"

    private val realSrc = """
        flowchart TD
            A[需求确认] --> B[组件下发]
            B --> C[结果验收]
            ...quro-ui 渲染原生控件」 B -->{D[纯文本回复] C -
    """.trimIndent()

    @Test
    fun 能从真机报错里定位到第4行() {
        val d = mermaidErrorDiag(realErr, realSrc)
        assertEquals("行号必须解析成 4，实际=$d", 4, d.line)
        assertTrue("出错行原文必须带出来，实际=${d.lineText}", d.lineText.contains("quro-ui 渲染原生控件"))
        assertTrue(d.hasLine)
    }

    @Test
    fun 兼容Parse_error_与on_line_两种格式() {
        assertEquals(12, mermaidErrorDiag("Parse error on line 12:", "a\nb\nc").line)
        assertEquals(3, mermaidErrorDiag("error at line 3, column 5:", "a\nb\nc").line)
        assertEquals(7, mermaidErrorDiag("LEXICAL ERROR ON LINE 7", (1..10).joinToString("\n")).line)
    }

    @Test
    fun 行号越界时不谎报行号() {
        val d = mermaidErrorDiag("Lexical error on line 999:", "graph TD\nA-->B")
        assertEquals(999, d.line)
        assertFalse("越界行没有原文，不能声称有行内容", d.hasLine)
    }

    @Test
    fun 没有行号信息时只显示原始报错() {
        val d = mermaidErrorDiag("Unknown diagram type", "graph TD\nA-->B")
        assertEquals(null, d.line)
        assertFalse(d.hasLine)
    }

    @Test
    fun 纯散文行被转成注释() {
        val out = sanitizeMermaidSource("graph TD\n这是一句自然语言说明\nA-->B")
        assertTrue("散文行必须加%% 前缀，实际=$out", out.contains("%% 这是一句自然语言说明"))
        assertTrue("图定义与边必须原样保留，实际=$out", out.contains("graph TD") && out.contains("A-->B"))
    }

    @Test
    fun 节点行绝不会被误删() {
        val src = "flowchart TD\n    A[需求确认] --> B[组件下发]\nsubgraph S1\nend"
        assertEquals("含结构符号的行必须原样返回（同一引用）", src, sanitizeMermaidSource(src))
    }

    @Test
    fun 行内混排不动手只报错() {
        // 真机第 4 行就是这种形态：散文和边粘在一起。猜错的风险远大于收益，
        // 交给 mermaidErrorDiag 指出行号，不在这里乱切。
        val d = mermaidErrorDiag(realErr, realSrc)
        assertTrue(d.hasLine)
        assertTrue("第4 行必须仍带得出来", d.lineText.contains("B -->{D"))
    }

    @Test
    fun 注释行不被二次处理() {
        val src = "graph TD\n%% 这是已有注释\nA-->B"
        assertEquals(src, sanitizeMermaidSource(src))
    }
}