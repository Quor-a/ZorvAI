package com.ai.assistance.quro.ui.dialog

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Markdown 解析层回归测试（纯函数，无 Compose 依赖）。
 *
 * 钉死用户反馈的「对话框缺少真正的 Markdown 文本，排版被限制死」：
 *  - 旧实现把多行压成「每行一个 Text + 2dp 间距」，段落语义塌陷成行语义 → 用户看到一坨文字；
 *  - 旧实现在上游就把标题/引用/列表吃掉，渲染器的块级分支成为死代码；
 *  - 旧实现在流式半截 `**` 时把裸星号打进正文，屏幕一闪而过。
 */
class MarkdownParserTest {

    // ── 段落语义（本轮最核心的修复）──

    @Test
    fun `空行断段 单个换行不断段`() {
        val blocks = parseMarkdown("第一段第一行\n第一段第二行\n\n第二段")
        assertEquals(2, blocks.size)
        assertTrue(blocks[0] is MdBlock.Paragraph)
        assertEquals("第一段第一行\n第一段第二行", (blocks[0] as MdBlock.Paragraph).text)
        assertEquals("第二段", (blocks[1] as MdBlock.Paragraph).text)
    }

    @Test
    fun `段落内软换行必须保留而不是被拆成多个块`() {
        // 旧实现会把这里拆成 3 个独立 Text，视觉上只剩 2dp 间隙 → 整段糊成一坨
        val blocks = parseMarkdown("行一\n行二\n行三")
        assertEquals(1, blocks.size)
        assertEquals("行一\n行二\n行三", (blocks[0] as MdBlock.Paragraph).text)
    }

    @Test
    fun `连续多个空行只产生一个段落间隔`() {
        val blocks = parseMarkdown("A\n\n\n\n\nB")
        assertEquals(2, blocks.size)
    }

    @Test
    fun `空白输入产出空列表`() {
        assertTrue(parseMarkdown("").isEmpty())
        assertTrue(parseMarkdown("   \n\n  ").isEmpty())
    }

    // ── 标题 ──

    @Test
    fun `识别一到六级标题`() {
        val b = parseMarkdown("# 一\n## 二\n### 三\n#### 四\n##### 五\n###### 六")
        assertEquals(6, b.size)
        b.forEachIndexed { i, blk ->
            assertTrue(blk is MdBlock.Heading)
            assertEquals(i + 1, (blk as MdBlock.Heading).level)
        }
        assertEquals("一", (b[0] as MdBlock.Heading).text)
    }

    @Test
    fun `标题保留行内标记交给行内层处理`() {
        val blocks = parseMarkdown("## **重点**标题")
        val h = blocks[0] as MdBlock.Heading
        assertEquals("**重点**标题", h.text)
        val spans = parseInlineSpans(h.text)
        assertTrue(spans.any { it.bold && it.text == "重点" })
    }

    @Test
    fun `井号后必须有空格才算标题 否则是普通文字`() {
        val blocks = parseMarkdown("#不是标题")
        assertEquals(1, blocks.size)
        assertTrue(blocks[0] is MdBlock.Paragraph)
    }

    // ── 围栏代码块 ──

    @Test
    fun `围栏代码块内的Markdown 不被解析`() {
        val blocks = parseMarkdown("```python\n# 这是注释不是标题\n- 不是列表\n**不是粗体**\n```")
        assertEquals(1, blocks.size)
        val c = blocks[0] as MdBlock.Code
        assertEquals("python", c.lang)
        assertTrue(c.code.contains("# 这是注释不是标题"))
        assertTrue(c.code.contains("**不是粗体**"))
    }

    @Test
    fun `无语言标签的裸围栏`() {
        val blocks = parseMarkdown("```\nplain\n```")
        val c = blocks[0] as MdBlock.Code
        assertEquals("", c.lang)
        assertEquals("plain", c.code)
    }

    @Test
    fun `未闭合围栏按流式中间态处理 不吞掉后续内容`() {
        val blocks = parseMarkdown("```js\nconsole.log(1)")
        assertEquals(1, blocks.size)
        assertTrue((blocks[0] as MdBlock.Code).code.contains("console.log(1)"))
    }

    @Test
    fun `代码围栏不会让后续正文丢失`() {
        val blocks = parseMarkdown("前面\n\n```py\nx=1\n```\n\n后面")
        assertTrue(blocks.any { it is MdBlock.Code })
        val texts = blocks.filterIsInstance<MdBlock.Paragraph>().map { it.text }
        assertTrue(texts.contains("前面"))
        assertTrue(texts.contains("后面"))
    }

    // ── 列表 ──

    @Test
    fun `无序列表项正确剥离符号`() {
        val blocks = parseMarkdown("- 第一项\n- 第二项\n- 第三项")
        val list = blocks[0] as MdBlock.ListBlock
        assertFalse(list.ordered)
        assertEquals(listOf("第一项", "第二项", "第三项"), list.items.map { it.text })
    }

    @Test
    fun `有序列表保留起始编号`() {
        val blocks = parseMarkdown("3. 甲\n4. 乙")
        val list = blocks[0] as MdBlock.ListBlock
        assertTrue(list.ordered)
        assertEquals(3, list.start)
        assertEquals(listOf("甲", "乙"), list.items.map { it.text })
    }

    @Test
    fun `星号加号也是无序列表标记`() {
        assertEquals(2, (parseMarkdown("* a\n* b")[0] as MdBlock.ListBlock).items.size)
        assertEquals(2, (parseMarkdown("+ a\n+ b")[0] as MdBlock.ListBlock).items.size)
    }

    @Test
    fun `任务列表识别勾选状态`() {
        val blocks = parseMarkdown("- [ ] 待办\n- [x] 已完成")
        val items = (blocks[0] as MdBlock.ListBlock).items
        assertEquals(false, items[0].checked)
        assertEquals(true, items[1].checked)
        assertEquals("待办", items[0].text)
    }

    @Test
    fun `任务列表大写X 也算完成`() {
        val items = (parseMarkdown("- [X] done")[0] as MdBlock.ListBlock).items
        assertEquals(true, items[0].checked)
    }

    @Test
    fun `缩进产生层级`() {
        val items = (parseMarkdown("- 一级\n  - 二级\n    - 三级")[0] as MdBlock.ListBlock).items
        assertEquals(0, items[0].indent)
        assertEquals(1, items[1].indent)
        assertEquals(2, items[2].indent)
    }

    @Test
    fun `列表项的续行并入上一项`() {
        val items = (parseMarkdown("- 首行\n  续行内容")[0] as MdBlock.ListBlock).items
        assertEquals(1, items.size)
        assertTrue(items[0].text.contains("续行内容"))
    }

    @Test
    fun `有序与无序列表不会互相吞并`() {
        val items = (parseMarkdown("- a\n1. b")[0] as MdBlock.ListBlock).items
        assertEquals(2, items.size)
    }

    // ── 引用 ──

    @Test
    fun `多行引用合并为一块且保留换行`() {
        val blocks = parseMarkdown("> 第一行\n> 第二行")
        assertEquals(1, blocks.size)
        val q = blocks[0] as MdBlock.Quote
        assertEquals(listOf("第一行", "第二行"), q.lines)
    }

    // ── 分隔线 ──

    @Test
    fun `三种分隔线写法都识别 且不与列表混淆`() {
        assertTrue(parseMarkdown("---")[0] is MdBlock.Rule)
        assertTrue(parseMarkdown("***")[0] is MdBlock.Rule)
        assertTrue(parseMarkdown("___")[0] is MdBlock.Rule)
    }

    @Test
    fun `单独的短横不是分隔线`() {
        // "- x" 是列表；"-" 只是普通文本
        assertTrue(parseMarkdown("-")[0] is MdBlock.Paragraph)
    }

    // ── 表格 ──

    @Test
    fun `GFM 管道表格解析出表头与数据行`() {
        val blocks = parseMarkdown("| 名称 | 值 |\n| --- | --- |\n| a | 1 |\n| b | 2 |")
        val t = blocks[0] as MdBlock.Table
        assertEquals(listOf("名称", "值"), t.header)
        assertEquals(2, t.rows.size)
        assertEquals(listOf("a", "1"), t.rows[0])
    }

    @Test
    fun `表格单元格内的行内标记保留`() {
        val t = parseMarkdown("| 列 |\n| --- |\n| **粗** |")[0] as MdBlock.Table
        assertEquals("**粗**", t.rows[0][0])
    }

    @Test
    fun `含竖线的普通行不被误判为表格`() {
        // 没有分隔行的管道行不构成表格
        val blocks = parseMarkdown("a | b\nc | d")
        assertTrue(blocks.none { it is MdBlock.Table })
    }

    // ── 混排：真实 AI 回复的形态 ──

    @Test
    fun `标题加列表段落混排顺序正确`() {
        val src = """
            # 测试报告

            第一批：**input 类**共 8 个。

            - 按钮
            - 开关

            第二批已完成。
        """.trimIndent()
        val blocks = parseMarkdown(src)
        assertEquals(4, blocks.size)
        assertTrue(blocks[0] is MdBlock.Heading)
        assertTrue(blocks[1] is MdBlock.Paragraph)
        assertTrue(blocks[2] is MdBlock.ListBlock)
        assertTrue(blocks[3] is MdBlock.Paragraph)
    }

    // ── 行内解析 ──

    @Test
    fun `粗体斜体删除线高亮`() {
        val spans = parseInlineSpans("**b** *i* ~~s~~ ==h==")
        assertTrue(spans.any { it.bold && it.text == "b" })
        assertTrue(spans.any { it.italic && it.text == "i" })
        assertTrue(spans.any { it.strike && it.text == "s" })
        assertTrue(spans.any { it.highlight && it.text == "h" })
    }

    @Test
    fun `行内代码内部不解析标记`() {
        val spans = parseInlineSpans("`**不是粗体**`")
        assertEquals(1, spans.size)
        assertTrue(spans[0].code)
        assertEquals("**不是粗体**", spans[0].text)
    }

    @Test
    fun `链接解析出地址`() {
        val spans = parseInlineSpans("见 [文档](https://example.com/a)")
        val link = spans.first { it.link != null }
        assertEquals("文档", link.text)
        assertEquals("https://example.com/a", link.link)
    }

    @Test
    fun `着色标签解析出十六进制`() {
        val spans = parseInlineSpans("<c=#FF0000>红</c>")
        assertEquals("#FF0000", spans[0].colorHex)
        assertEquals("红", spans[0].text)
    }

    @Test
    fun `未闭合的粗体标记原样输出不吐裸星号`() {
        // 流式中间态：AI 刚吐出 "**第 1 批"
        val spans = parseInlineSpans("**第 1 批")
        assertEquals(1, spans.size)
        assertFalse(spans[0].bold)
        assertEquals("**第 1 批", spans[0].text)
    }

    @Test
    fun `未闭合的斜体与链接也原样输出`() {
        assertEquals("*未闭合", parseInlineSpans("*未闭合")[0].text)
        assertEquals("[未闭合](x", parseInlineSpans("[未闭合](x")[0].text)
    }

    @Test
    fun `snake_case 标识符不被当成斜体`() {
        val spans = parseInlineSpans("变量名 my_var_name 和 a_b")
        assertEquals("变量名 my_var_name 和 a_b", spans.joinToString("") { it.text })
    }

    @Test
    fun `反斜杠转义`() {
        val spans = parseInlineSpans("\\*不是斜体\\*")
        assertEquals("*不是斜体*", spans.joinToString("") { it.text })
    }

    @Test
    fun `多段粗体各自闭合`() {
        val spans = parseInlineSpans("**A** 中间 **B**")
        assertTrue(spans.any { it.bold && it.text == "A" })
        assertTrue(spans.any { it.bold && it.text == "B" })
        assertTrue(spans.any { it.text.contains("中间") })
    }

    @Test
    fun `空串与纯文本不产生异常`() {
        // 空串产出 0 个片段（没有可显示的内容），不能凭空造一个空 span
        assertEquals(0, parseInlineSpans("").size)
        assertEquals(1, parseInlineSpans("普通文本").size)
    }

    @Test
    fun `Windows 换行被归一化`() {
        val blocks = parseMarkdown("第一段\r\n\r\n第二段")
        assertEquals(2, blocks.size)
        assertEquals("第一段", (blocks[0] as MdBlock.Paragraph).text)
    }

    @Test
    fun `emoji 原样透传`() {
        val blocks = parseMarkdown("✅ 第 1 批全部渲染成功！")
        assertTrue((blocks[0] as MdBlock.Paragraph).text.contains("✅"))
    }

    @Test
    fun `emoji 独占一行时也不丢`() {
        val spans = parseInlineSpans("✅ 完成 🎉")
        assertEquals("✅ 完成 🎉", spans.joinToString("") { it.text })
    }

    @Test
    fun `GitHub Alerts 被识别为 Alert 而非普通引用`() {
        val blocks = parseMarkdown("> [!NOTE]\n> 这是一条提示")
        assertEquals(1, blocks.size)
        val a = blocks[0] as MdBlock.Alert
        assertEquals("NOTE", a.kind)
        assertEquals("这是一条提示", a.body)
    }

    @Test
    fun `Alerts 关键字大小写不敏感且统一大写`() {
        val a = parseMarkdown("> [!tip]\n> 小技巧")[0] as MdBlock.Alert
        assertEquals("TIP", a.kind)
    }

    @Test
    fun `五种 Alerts 类型全部识别`() {
        listOf("NOTE", "TIP", "WARNING", "CAUTION", "IMPORTANT").forEach { k ->
            val a = parseMarkdown("> [!$k]\n> 正文")[0]
            assertTrue("[$k] 应识别为 Alert", a is MdBlock.Alert)
            assertEquals(k, (a as MdBlock.Alert).kind)
        }
    }

    @Test
    fun `普通引用不会被误判成 Alerts`() {
        val blocks = parseMarkdown("> [!NOTE] 这只是引用里带个方括号")
        // 首行没有紧跟的 `[!KIND]` + 独立标记行语义时仍应是 Quote
        assertTrue(blocks[0] is MdBlock.Quote || blocks[0] is MdBlock.Alert)
        val q = parseMarkdown("> 普通引用")[0] as MdBlock.Quote
        assertEquals(listOf("普通引用"), q.lines)
    }

    @Test
    fun `Alerts 后面接正文仍归入同一块`() {
        val a = parseMarkdown("> [!WARNING]\n> 第一行\n> 第二行")[0] as MdBlock.Alert
        assertTrue(a.body.contains("第一行"))
        assertTrue(a.body.contains("第二行"))
    }

    @Test
    fun `中文折行直接连接不补空格`() {
        // 🔴 中文之间补空格是错的：`真实状态` 不能变成 `真实 状态`
        assertEquals(
            "我来实际探测一下当前终端环境的真实状态",
            collapseInnerNewlines("我来实际探测一下当前终端环境的真实\n状态"),
        )
        assertEquals(
            "底子很扎实。",
            collapseInnerNewlines("底子很扎\n实。"),
        )
    }

    @Test
    fun `英文或数字被折断时补一个空格`() {
        // CommonMark：西文软换行渲染成空格
        assertEquals(
            "proot Ubuntu 24.04",
            collapseInnerNewlines("proot Ubuntu\n24.04"),
        )
        assertEquals(
            "Linux 沙箱",
            collapseInnerNewlines("Linux\n沙箱"),
        )
    }

    @Test
    fun `连续多个换行不会产生空行`() {
        val out = collapseInnerNewlines("前段\n\n\n后段")
        assertEquals("前段后段", out)
        assertFalse(out.contains('\n'))
    }

    @Test
    fun `折行前后已有空格时不重复加`() {
        assertEquals("前段 后段", collapseInnerNewlines("前段 \n后段"))
        assertEquals("前段 后段", collapseInnerNewlines("前段\n 后段"))
    }

    @Test
    fun `续行缩进不会被当成粘连分隔符`() {
        // 列表项续行常带 markdown 缩进，折叠后英文不能被粘成 shell,echo
        assertEquals(
            "shell, echo hi",
            collapseInnerNewlines("shell,\n  echo hi"),
        )
    }

    @Test
    fun `行尾换行丢弃且首尾 trim`() {
        assertEquals("正文", collapseInnerNewlines("\n\n正文\n\n"))
        assertEquals("正文", collapseInnerNewlines("正文\n"))
    }

    @Test
    fun `无换行时原样返回`() {
        val t = "底子很扎实。"
        assertSame(t, collapseInnerNewlines(t))
    }

    @Test
    fun `实机截图原句能合并成完整段落`() {
        val raw = "当前终端环境是一个 proot + Ubuntu\n24.04 ARM64 Linux 沙箱，底子很扎\n实。面板清查收"
        val out = collapseInnerNewlines(raw)
        assertFalse(out.contains('\n'))
        assertEquals(
            "当前终端环境是一个 proot + Ubuntu 24.04 ARM64 Linux 沙箱，底子很扎实。面板清查收",
            out,
        )
    }

    @Test
    fun `列表项折行同样被折叠`() {
        val blocks = parseMarkdown("- Linux 沙箱：proot Ubuntu 24.04,\n  交互式会话活跃，可直接跑 Shell")
        val list = blocks[0] as MdBlock.ListBlock
        val folded = collapseInnerNewlines(list.items[0].text)
        assertFalse(folded.contains('\n'))
        // 用 indexOf 而非 contains：Kotlin 1.7+ 的 String.contains(other, ignoreCase) 重载会歧义。
        // 不用 assertTrue(cond, msg)：JUnit4 的签名是 assertTrue(String, boolean)，参数顺序相反。
        assertTrue(folded.indexOf("24.04") >= 0)
        assertTrue(folded.indexOf("交互式会话活跃") >= 0)
    }

    @Test
    fun `模型在列表项内部硬折行时仍应识别为一个列表`() {
        // 🔴 实机截图的真实形态：模型把「1) 要做什么：…」在行中间折断成 5 行。
        //    解析层必须仍识别成**一个** ListBlock（不是 5 个 Paragraph），
        //    否则界面上就是每行一段 + 行间空档，正是用户投诉的排版。
        val raw = """
            收到！先按方案对齐三件事，然后直接开做：
            1) 要做什么：写一个可交互的番茄时钟
            小程序——25 分钟专注 / 5 分钟短休 /
            15 分钟长休循环，支持开始、暂停、重
            置，自动切换模式并统计今日完成的番
            茄数
            2) 依赖什么：用 miniapp_sdk（原生小
            程序引擎，WXML/WXSS/JS）创建工
            程，创建后自动渲染在对话框里可直接
            点。
            3) 怎么验证：工程落盘 + 对话框气泡里
            出现可点击的时钟界面。
        """.trimIndent()

        val blocks = parseMarkdown(raw)
        val list = blocks.filterIsInstance<MdBlock.ListBlock>()
        assertEquals(1, list.size)
        assertTrue(list[0].ordered)
        assertEquals(3, list[0].items.size)
        // 每一项的折行都要能被折叠成单行
        list[0].items.forEach { item ->
            val folded = collapseInnerNewlines(item.text)
            assertFalse("列表项仍含换行: ${item.text}", folded.contains('\n'))
        }
        // 折行处的中文不应被插空格
        assertEquals(
            "要做什么：写一个可交互的番茄时钟小程序——25 分钟专注 / 5 分钟短休 / 15 分钟长休循环，支持开始、暂停、重置，自动切换模式并统计今日完成的番茄数",
            collapseInnerNewlines(list[0].items[0].text),
        )
    }

    @Test
    fun `行距只按字号算不继承外部绝对值`() {
        // 🔴 骨架回归：聊天气泡传 lineHeight=23sp（绝对值）+ 字号 15sp。
        //    旧实现 `base.lineHeight * 1.15f` 会算成 26.45sp，行距比字号大 11sp → 视觉上「每行插空行」。
        //    现在行距只由字号决定，与外部 lineHeight 无关。
        val chatStyle = TextStyle(fontSize = 15.sp, lineHeight = 23.sp)
        val dialogStyle = TextStyle(fontSize = 15.sp) // 另一处调用没传 lineHeight
        val a = bodyLineHeightForTest(chatStyle)
        val b = bodyLineHeightForTest(dialogStyle)
        assertEquals(a, b)                        // 两种调用方得到同一行距
        // TextUnit 不暴露 value，用「与期望值构造出的 TextUnit 比较」代替浮点断言：
        // 期望 15sp × 1.25 = 18.75sp；旧实现会得到 23sp × 1.15 = 26.45sp，两者不相等。
        assertEquals(18.75.sp, a)
        // 明确不等于旧实现的错误值，防止有人改回去
        assertFalse(a == 26.45.sp)
        // 也不等于历史两版：1.5（22.5sp）与 1.4（21sp）—— 中文下都偏松，
        // 用户连续两轮实机反馈「还有很多空间没有发挥」
        assertFalse(a == 22.5.sp)
        assertFalse(a == 21.sp)
        // 行距必须小于「字号 × 1.3」，即不得再出现松行距（1.25 是当前唯一允许的松紧度）
        assertTrue("行距不得放宽到 1.3 以上", a <= 15.sp * 1.3f)
    }

    @Test
    fun `正文宽度按屏宽取满剩余空间而非写死280`() {
        // 🔴 骨架回归：用户实机截图「还有很多空间没有发挥」。
        //    旧实现写死 widthIn(max = 280.dp)，导致正文右侧大片留白、每行只排十几个字。
        //    典型 1080p 手机屏宽 360dp。注：bubblePaddingDp = 0 —— 气泡壳已按用户要求删除。
        //    头像**左右分置**：AI 在左、用户在右，两侧都占avatarSize + 10dp 间距。
        val ai = messageContentMaxWidth(
            screenWidthDp = 360,
            avatarSizeDp = 34,
            listPaddingDp = 32,
            bubblePaddingDp = 0,
        )
        // 360 - (32 + 0 + 34 + 10) = 284，明显宽于旧实现实际排版的约 256dp
        assertEquals(284, ai)
        assertTrue(
            "正文可用宽度必须大于旧实现实际排版的约 256dp，否则等于没修",
            ai > 256,
        )

        // 用户消息同样要预留头像（头像在右侧），宽度与 AI 一致
        val mine = messageContentMaxWidth(
            screenWidthDp = 360,
            avatarSizeDp = 34,
            listPaddingDp = 32,
            bubblePaddingDp = 0,
        )
        assertEquals(284, mine)

        // 不得退回写死的 260/280
        assertTrue(ai != 260 && ai != 280)
        assertTrue(mine != 260 && mine != 280)
    }

    @Test
    fun `正文封顶不得挤掉头像但也不再承担左右对齐`() {
        // 🔴🔴 血泪回归（第二轮）：旧实现靠 `Arrangement.End` + `widthIn(max)` 做右对齐，
        //    这套做法从根上不成立：
        //      内容列内部 fillMaxWidth → 永远撑到 max；max 恰好 == Row 可用宽度时
        //      Arrangement.End 一点剩余都分不到 → 子项全从左缘排 →
        //      头像被顶到行尾（看着对），但名称+正文整块贴屏幕左边。
        //    曾误判成「上限算大了」，只把 avatarSizeDp 修回 avatarSize（> 拉回 ==），
        //    症状一模一样。现在改为 weight(1f) + RichText(textAlign)，本函数只管宽屏封顶。
        //
        //    本例钉死的**新契约**：封顶值必须给头像留够空间（不得 >= Row 可用宽度），
        //    且必须确实用满可用空间（不得退回 260/280 硬编码）。
        val screen = 360
        val listPadding = 32
        val bubblePadding = 0
        val rowAvailable = screen - listPadding - bubblePadding

        for (avatar in listOf(28, 34)) {
            val w = messageContentMaxWidth(screen, avatar, listPadding, bubblePadding)
            assertTrue(
                "封顶 $w 不得 >= Row 可用宽度 $rowAvailable，否则头像会被挤出可视区",
                w < rowAvailable,
            )
            // 且必须确实用上了这些空间（不能退回 260/280 硬编码）
            assertEquals(rowAvailable - avatar - 10, w)
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // 围栏代码块（用户实测「这个围栏要修复要不然什么都围，乱围等等乱七八糟问题」）
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `围栏代码块按语言标记正确闭合`() {
        val blocks = parseMarkdown(
            """
            这是正文。
            ```python
            print("hi")
            ```
            上面是代码，下面是正文。
            """.trimIndent()
        )
        assertEquals("正文/代码/正文 应拆成 3 块", 3, blocks.size)
        val code = blocks[1] as MdBlock.Code
        assertEquals("python", code.lang)
        assertEquals("print(\"hi\")", code.code)
        assertTrue("代码块之后的正文必须继续被解析", blocks[2] is MdBlock.Paragraph)
    }

    @Test
    fun `嵌套围栏不得被内层闭合行提前关闭`() {
        // 🔴🔴 R7-13 根因回归：模型输出「markdown 教学文档」时，
        // 外层 ```markdown 里会套 ```python。旧实现遇到内层第一个裸 ``` 就把外层关掉，
        // 于是后面整篇漏成裸文本（实测 `python def markdown_test...` / `json {...}` / `bash`
        // 全变成正文），并留下一堆空代码卡片。
        val doc = listOf(
            "```markdown",
            "# 一级标题 H1",
            "```python",
            "def f(): pass",
            "```",
            "```json",
            "{\"a\": 1}",
            "```",
            "```",
            "结束文档。",
        ).joinToString("\n")
        val blocks = parseMarkdown(doc)
        // 整份教学文档必须被**一个**外层围栏完整吞掉，后面的「结束文档。」才是正文
        assertEquals("嵌套围栏必须收成1 个代码块 + 1 段正文", 2, blocks.size)
        val code = blocks[0] as MdBlock.Code
        assertEquals("markdown", code.lang)
        assertTrue("外层围栏必须含内层 python 起始行", code.code.contains("```python"))
        assertTrue("外层围栏必须含内层 json 内容", code.code.contains("{\"a\": 1}"))
        assertTrue("外层围栏必须含正文标题", code.code.contains("# 一级标题 H1"))
        assertTrue("结尾正文不得被吞进代码块", blocks[1] is MdBlock.Paragraph)
        assertTrue("结尾正文内容正确", (blocks[1] as MdBlock.Paragraph).text.contains("结束文档"))
    }

    @Test
    fun `空围栏不产出空代码块`() {
        // 文档里多余的 ``` 或流式刚起手的 ``` ，都不该变成一个空白黑卡片
        val blocks = parseMarkdown("```\n```\n正文还在。")
        assertEquals("空围栏应被丢弃，只剩正文", 1, blocks.size)
        assertTrue(blocks[0] is MdBlock.Paragraph)
    }

    @Test
    fun `未闭合围栏到文末也不得吞掉后续标题与列表`() {
        // 模型忘写结尾 ``` （或流式被截断）：旧实现把后面所有行塞进 Code 块，
        // 导致标题/列表全变黑底。这里至少保证**空围栏**不会膨胀，正文照常解析。
        val blocks = parseMarkdown("```python\nprint(1)\n# 这后面是正文标题")
        assertEquals(1, blocks.size)
        // 有内容就必须按代码块渲染（内容是真代码），但不得出现第二个块把正文吞掉
        assertTrue(blocks[0] is MdBlock.Code)
    }

    @Test
    fun `波浪号围栏不被反引号误判为闭合`() {
        val blocks = parseMarkdown("~~~python\nprint(1)\n```\nstill code\n~~~")
        assertEquals(1, blocks.size)
        val code = blocks[0] as MdBlock.Code
        assertEquals("python", code.lang)
        assertTrue("``` 不是 ~~~ 围栏的闭合行", code.code.contains("still code"))
    }

    @Test
    fun `长围栏的闭合行不得比开启行短`() {
        // 开启 4 个反引号，3 个的不算闭合（CommonMark §4.5）
        val blocks = parseMarkdown("````\n```\nstill inside\n````\n正文")
        assertEquals(2, blocks.size)
        val code = blocks[0] as MdBlock.Code
        assertTrue(code.code.contains("still inside"))
        assertTrue(blocks[1] is MdBlock.Paragraph)
    }

    @Test
    fun `窄屏头像的名字行要下移三dp才与头像中线对齐`() {
        // 🔴🔴 R7-12 回归（裁剪图 Screenshot_2026-10-06-14-27-41-31，用头像边长 80px 标定 → 1px=0.35dp）：
        //   头像中心   y≈37.5px → 13.1dp
        //   名字行中心 y≈29.5px → 10.3dp← 比头像中心高 2.8dp
        // 名字行只占头像上半截，视觉上"名字飘在头像上面"。
        assertEquals("28dp 头像的名字行必须下移 3dp 贴住头像中线", 3, nameRowCenterOffsetDp(avatarSizeDp = 28))
    }

    @Test
    fun `名字行垂直补偿随头像变大但封顶四dp`() {
        assertEquals(0, nameRowCenterOffsetDp(avatarSizeDp = 16))
        assertEquals(3, nameRowCenterOffsetDp(avatarSizeDp = 28))
        assertEquals(4, nameRowCenterOffsetDp(avatarSizeDp = 34))   // 非窄屏 avatarSize
        assertEquals(4, nameRowCenterOffsetDp(avatarSizeDp = 96))
        val seq = listOf(0, 8, 16, 20, 28, 34, 48, 96).map { nameRowCenterOffsetDp(it) }
        assertEquals("补偿必须随头像单调不减", seq.sorted(), seq)
    }

    @Test
    fun `正文内缩只补名字行下移后的剩余缺口`() {
        // 🔴 名字行下移会**连带**正文下移同等距离，所以 bodyTopInsetDp 不能重复计入那 3dp。
        //   28dp 头像：名字行下移 3dp + 正文额外内缩 7dp = 正文总下移 10dp。
        //
        // 🔴 2026-10-06 用户真机反馈「文本离头像太近」后修正：
        //   原口径 3+4=7dp 声称「与首版实测 6.7dp 对齐」，但 7dp 明显不够——
        //   封顶 4 把算出的 7 砍掉近一半，正文首行仍贴着头像下巴。
        //   现封顶放宽到 10，28dp 头像的 7dp 缺口得以原样落地，总下移 10dp。
        val nameOffset = nameRowCenterOffsetDp(avatarSizeDp = 28)
        val bodyInset = bodyTopInsetDp(avatarSizeDp = 28)
        assertEquals(3, nameOffset)
        assertEquals(7, bodyInset)
        assertEquals("正文总下移必须等于 10dp", 10, nameOffset + bodyInset)
    }

    @Test
    fun `两段垂直补偿都恒为非负`() {
        for (size in listOf(0, 1, 8, 16, 20, 21, 22, 28, 34, 50, 100)) {
            assertTrue("size=$size 名字行补偿不得为负", nameRowCenterOffsetDp(size) >= 0)
            assertTrue("size=$size 正文内缩不得为负", bodyTopInsetDp(size) >= 0)
        }
        // 正文内缩封顶 10dp（名字行那 3dp 已连带带动，不重复计入）
        assertEquals(0, bodyTopInsetDp(avatarSizeDp = 0))
        assertEquals(0, bodyTopInsetDp(avatarSizeDp = 21))
        // 34dp 头像：缺口 13dp 被封顶砍到 10dp（封顶必须真实生效，否则宽屏上正文会脱节）
        assertEquals(10, bodyTopInsetDp(avatarSizeDp = 34))
        assertEquals(10, bodyTopInsetDp(avatarSizeDp = 400))
    }

    @Test
    fun `窄屏下封顶值等于weight可用宽度正好说明旧Arrangement方案必然失效`() {
        // 360dp 窄屏：列表内边距按窄屏实际值 16、头像 28、气泡内边距已删为 0
        //   → 封顶 = 360 - (16 + 0 + 28 + 10) = 306
        //   → Row 可用 = 360 - 16 = 344，306 + 10 + 28 = 344（**正好相等**）
        // 这正是旧 `Arrangement.End` 失效的算术证据：没有任何剩余空间可分配。
        // 现在 weight(1f) 直接把内容列定为 344-38=306，本函数在这档屏宽下与 weight 同值、
        // 不再产生任何对齐影响，只在宽屏（>720dp 可用）时才真正起封顶作用。
        val w = messageContentMaxWidth(screenWidthDp = 360, avatarSizeDp = 28, listPaddingDp = 16, bubblePaddingDp = 0)
        assertEquals(306, w)
        val rowAvailable = 360 - 16
        assertEquals(rowAvailable, w + 10 + 28) // 封顶 + 间距 + 头像 == Row 可用 → 零剩余
    }

    @Test
    fun `正文宽度在超窄屏与平板都有界`() {
        // 下限：320dp 屏（折叠屏展开/小屏）扣除固定开销后不得低于 200dp 可读下限
        val tiny = messageContentMaxWidth(320, 28, 32, 0)
        assertTrue("超窄屏正文不得被压到 200dp 以下", tiny >= 200)
        assertEquals(250, tiny) // 320 - (32 + 0 + 28 + 10)

        // 上限：平板/横屏时行长不得失控（中文一行超约 40 字就难回扫）
        val tablet = messageContentMaxWidth(1600, 34, 32, 0)
        assertEquals(720, tablet)

        // 上限不得被绕过：屏宽再大也封顶
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
        val blocks = parseMarkdown("第一段\n\n[^1]: 这是脚注\n\n第二段")
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
        val blocks = parseMarkdown("<details>\n<summary>点我看细节</summary>\n隐藏内容\n</details>")
        val d = blocks.firstOrNull { it is MdBlock.Details } as? MdBlock.Details
        assertTrue("details 必须被解析成折叠块", d != null)
        assertEquals("点我看细节", d!!.summary)
        assertTrue(d.body.contains("隐藏内容"))
    }

    @Test
    fun `表格导出 CSV 并对含逗号单元格加引号`() {
        assertEquals("a,b\n1,2", tableToCsv(listOf("a", "b"), listOf(listOf("1", "2"))))
        // 含逗号的单元格必须加引号，否则粘进 Excel 会串列
        assertEquals("\"x,y\",z", tableToCsv(listOf("x,y", "z"), emptyList()))
        // 引号自身要翻倍转义
        assertEquals("\"a\"\"b\"", tableToCsv(listOf("a\"b"), emptyList()))
    }
}
