package com.ai.assistance.quro.core.ui.dynamicui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 任务 #181 回归：**动态 UI 空数组不再报红字**。
 *
 * ## 用户原话
 *
 * >「动态UI组件仍然有问题」（截图显示）
 * > ⚠️ 动态 UI 解析失败：数组里没有可用元素（元素应为组件对象、字符串或嵌套数组）
 * > `[` `,` `,` `,` `,` `,` `]`
 *
 * ## 已排除的可能（别再往这些方向钻）
 *
 * `repair()` 的三个子步骤（`normalizeQuotes` / `fixOutsideStrings` / `sanitizeJson`）
 * 与 `extractUiBlocks` 都**不可能**把 `{"..."}` 内容删掉只留逗号：
 * `sanitizeJson` 只插入或丢弃孤立的闭合符，`fixOutsideStrings` 只删尾逗号，
 * `normalizeQuotes` 只改引号。把 `repair()` 逐行等价移植为 Python 后，
 * 用 8 组输入（规范数组 / 空串元素 / null 元素 / 散文包裹 / 字符串内含 `}` /
 * 流式未闭合 / 单引号 / 伪数组）实测，**没有一组产出空壳**。
 *
 * 结论：内容在到达解析器之前就没了。所以本轮不再改repair，
 * 改的是**行为口径** —— 读不出来 ≠ 没有，必须降级展示而非报错。
 */
class Issue181DynamicUiFallbackTest {

    /** 截图里的空壳原样复现：换行全在，元素全空。 */
    private val emptyShell = "[\n,\n,\n,\n,\n,\n]"

    // ── 🔴 核心：绝不再返回 Failure ──

    @Test
    fun 空壳数组不再报解析失败() {
        val r = QuroUiDslParser.parseBlock(emptyShell)
        assertTrue(
            "空壳数组**绝不能**报解析失败（真机上会渲染成红字+ 原始 JSON），实际=$r",
            r is QuroUiParseResult.Success,
        )
    }

    /**
     * 🔴 #206 改判：空壳数组**不产出任何可见节点**。
     *
     * #181 原本要求「至少产出一条可见内容」，当时用的是一句
     * 「（这一段内容是空的，没有可显示的组件）」。真机上那句裸文本
     * 直接出现在聊天流里（用户截图投诉对象）—— 它是解析器的自言自语，
     * 不是 AI 想表达的内容。
     *
     * 改成空 Column 是安全的：Column 无子节点即 0 高度，不留间隙；
     * AI 真正要说的话照常渲染在 UI 块之外的 Markdown 里。
     */
    @Test
    fun 空壳数组不再往聊天里塞解析器元信息() {
        val r = QuroUiDslParser.parseBlock(emptyShell)
        assertTrue(r is QuroUiParseResult.Success)
        val root = (r as QuroUiParseResult.Success).root as QuroColumnNode
        assertTrue(
            "空的 UI 块不该产出任何可见节点（否则又是一句解析器自言自语），实际=${root.children}",
            root.children.isEmpty(),
        )
        val flat = flatten(root)
        assertTrue(
            "绝不能再出现「没有可显示的组件」这类面向用户的解析器提示，实际=$flat",
            flat.none { it.contains("没有可显示的组件") },
        )
    }

    /** 反向保护：真的有内容时照常渲染，不能被「空则不渲染」的口径误伤。 */
    @Test
    fun 有内容的数组不受空块口径影响() {
        val r = QuroUiDslParser.parseBlock("""[{"type":"text","content":"真内容"}]""")
        assertTrue(r is QuroUiParseResult.Success)
        val root = (r as QuroUiParseResult.Success).root as QuroColumnNode
        assertTrue("有内容就必须渲染出来，实际=${root.children}", root.children.isNotEmpty())
    }

    @Test
    fun 元素全是空对象的数组也能降级而不是报错() {
        // 模型另一种常见输出：给了数组但每个元素都是 {}
        val r = QuroUiDslParser.parseBlock("[{},{},{}]")
        assertTrue("空对象数组也不该报失败，实际=$r", r is QuroUiParseResult.Success)
    }

    @Test
    fun 元素全是null的数组也能降级() {
        val r = QuroUiDslParser.parseBlock("[null,null,null]")
        assertTrue("null 元素数组也不该报失败，实际=$r", r is QuroUiParseResult.Success)
    }

    @Test
    fun 数组里混了可读文字时文字必须可见() {
        // 「读不出来 ≠ 没有」：只要有肉眼可读的内容就必须显示出来
        val r = QuroUiDslParser.parseBlock("[第一步,第二步,第三步]")
        assertTrue("含可读文字的数组必须成功，实际=$r", r is QuroUiParseResult.Success)
        val root = (r as QuroUiParseResult.Success).root
        val flat = flatten(root)
        assertTrue(
            "可读文字必须可见，实际=$flat",
            flat.any { it.contains("第一步") },
        )
    }

    // ── 不能因此破坏原有正常路径 ──

    @Test
    fun 规范数组仍然正常渲染且不被降级路径截胡() {
        val r = QuroUiDslParser.parseBlock(
            """[{"type":"text","content":"标题"},{"type":"button","label":"点我"}]"""
        )
        assertTrue(r is QuroUiParseResult.Success)
        val root = (r as QuroUiParseResult.Success).root as QuroColumnNode
        assertEquals("两个元素都应保留", 2, root.children.size)
        assertTrue(root.children[0] is QuroTextNode || root.children[0] is QuroMarkdownNode)
    }

    @Test
    fun 字符串元素数组仍按markdown渲染() {
        val r = QuroUiDslParser.parseBlock("""["第一行","第二行","第三行"]""")
        assertTrue(r is QuroUiParseResult.Success)
        val col = (r as QuroUiParseResult.Success).root as QuroColumnNode
        assertEquals(3, col.children.size)
        assertTrue(col.children.all { it is QuroMarkdownNode })
    }

    // ────── 🔴🔴 真机形态：空壳数组 + 尾部正文（#181 的核心 bug）──────────

    /** 与真机截图一致的原文：空壳数组在下，「• 选项 A / B」在它之后。 */
    private val realWorldCase = "[\n,\n,\n,\n]\n• 选项 A\n• 选项 B"

    @Test
    fun repair不能把空壳数组后面的正文整段砍掉() {
        val out = QuroUiDslParser.repair(realWorldCase)
        assertTrue(
            "尾部正文被 repair() 截掉了（这正是真机「内容明明在屏幕上却只显示空数组」的真凶），实际=$out",
            out.contains("选项"),
        )
    }

    @Test
    fun 空壳数组后面的选项必须被渲染出来() {
        val r = QuroUiDslParser.parseBlock(realWorldCase)
        assertTrue("不应报解析失败，实际=$r", r is QuroUiParseResult.Success)
        val flat = flatten((r as QuroUiParseResult.Success).root)
        assertTrue(
            "选项 A/B 必须可见（零内容丢失），实际=$flat",
            flat.any { it.contains("选项 A") } && flat.any { it.contains("选项 B") },
        )
    }

    @Test
    fun 第二个并列数组的内容也必须被渲染() {
        // 模型常把组件拆成多个并列数组；后面的那份同样不能丢
        val r = QuroUiDslParser.parseBlock("[\n,\n]\n[\"选项 A\",\"选项 B\"]")
        assertTrue("不应报失败，实际=$r", r is QuroUiParseResult.Success)
        val flat = flatten((r as QuroUiParseResult.Success).root)
        assertTrue("后面那份数组的内容也要看得见，实际=$flat", flat.any { it.contains("选项") })
    }

    @Test
    fun 尾部正文不被截断时规范数组仍完好() {
        // 反向保护：本次修复不能破坏原本就正常的路径
        val r = QuroUiDslParser.parseBlock("""[{"type":"text","content":"a"},{"type":"text","content":"b"}]""")
        assertTrue(r is QuroUiParseResult.Success)
        val col = (r as QuroUiParseResult.Success).root as QuroColumnNode
        assertEquals(2, col.children.size)
    }

    @Test
    fun 尾部有说明文字时既保JSON又保说明() {
        val r = QuroUiDslParser.parseBlock("""[{"type":"text","value":"x"}]\n\n以上是全部内容。""")
        assertTrue(r is QuroUiParseResult.Success)
        val flat = flatten((r as QuroUiParseResult.Success).root)
        assertTrue("尾部说明必须可见，实际=$flat", flat.any { it.contains("以上是全部内容") })
    }

    /** 把节点树里的所有 markdown 文本摊平成一个列表，便于断言「内容可见」。 */
    private fun flatten(node: QuroUiNode): List<String> = buildList {
        when (node) {
            is QuroColumnNode -> node.children.forEach { addAll(flatten(it)) }
            is QuroRowNode -> node.children.forEach { addAll(flatten(it)) }
            is QuroMarkdownNode -> add(node.value)
            is QuroTextNode -> add(node.value)
            else -> Unit
        }
    }
}