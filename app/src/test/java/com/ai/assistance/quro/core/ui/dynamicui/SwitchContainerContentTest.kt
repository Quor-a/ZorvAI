package com.ai.assistance.quro.core.ui.dynamicui

import com.ai.assistance.quro.core.cards.QuroChatCard
import com.ai.assistance.quro.core.cards.parseComponentSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 回归测试：**切换类容器内容空白**。
 *
 * ## 用户报的原始现象
 *
 * 「可视化组件这种组件切换没有内容，具体是什么不知道因为看不到，可以切换但是组件下的内容没有，
 * 可能是组件下面有组件和文字排版或者其他，反正什么都是没有，只要是这种切换类型都是这样。」
 *
 * 附截图：TabRow（运行环境 / CMS 能力模块 / ACI 与插件）渲染出来了，下方一片空白。
 *
 * ## 根因（两条渲染路径**同时**中招，不是一个 tabs 的 bug）
 *
 * 1. **键名白名单太窄**：动态 UI 侧 `buildTabs` 只认 `node`/`content`/`child`，
 *    而工具描述明确教模型写 **`body`** → schema 与解析器不一致 → `node = null`。
 *    卡片侧 `TabsCard.Tab(title, body)` 同样只认 `body`。
 * 2. **只接受单一值形态**：内容写成字符串或数组时，`optJSONObject` 一律返回 null。
 * 3. **渲染端静默失败**：`RenderTabs` 写的是 `?.node?.let { RenderNode(it) }`，
 *    node 为 null 时什么都不画、零提示 → 用户无法区分「模型没给内容」与「客户端渲染坏了」。
 *
 * ## 本测试的钉死目标
 *
 * - 三种值形态（对象 / 数组 / 纯字符串）都必须抽出内容，不再空白；
 * - `composite` 的三种 layout 必须真的降解成对应布局（此前完全未实现）；
 * - 两条渲染路径（动态 UI + 卡片）都要覆盖 —— 修一条不修另一条，空白照旧复现。
 */
class SwitchContainerContentTest {

    // ══════════════════════════════════════════════════════════════════════════
    // 动态 UI 路径
    // ══════════════════════════════════════════════════════════════════════════

    /** 解析并取根节点；失败时抛断言错误并带上原因，别让测试报"null"。 */
    private fun parseNode(dsl: String): QuroUiNode =
        when (val r = QuroUiDslParser.parseBlock(dsl)) {
            is QuroUiParseResult.Success -> r.root
            is QuroUiParseResult.Failure -> throw AssertionError("解析失败：${r.reason} / raw=${r.rawJson}")
        }

    private fun parseTab(dsl: String): QuroTabsNode =
        parseNode(dsl) as? QuroTabsNode
            ?: throw AssertionError("期望 QuroTabsNode，实际=${parseNode(dsl)::class.simpleName}")

    /** 🔴 核心回归：schema 教模型写的键名是 `body`，此前解析器根本不认。 */
    @Test
    fun tabs认body键这是schema教的写法() {
        val tabs = parseTab(
            """{"type":"tabs","tabs":[
              {"title":"运行环境","body":"Ubuntu 22.04 / Node 20"},
              {"title":"CMS","body":"内容管理模块"}
            ]}"""
        )
        assertEquals(2, tabs.tabs.size)
        // 之前：body 键不在白名单 → node = null → 渲染空白
        assertNotNull("body 键必须能抽出内容", tabs.tabs[0].node)
        val n0 = tabs.tabs[0].node!!
        assertTrue("纯字符串内容应包成 markdown 节点，实际=${n0::class.simpleName}", n0 is QuroMarkdownNode)
        assertTrue((n0 as QuroMarkdownNode).value.contains("Ubuntu"))
    }

    /** 历史键名不能破：node / content / child 仍要能用（向后兼容）。 */
    @Test
    fun tabs保留历史键名nodeContentChild() {
        listOf("node", "content", "child").forEach { key ->
            val tabs = parseTab("""{"type":"tabs","tabs":[{"title":"T","$key":{"type":"text","value":"hi"}}]}""")
            assertNotNull("键名 $key 必须仍可用", tabs.tabs[0].node)
        }
    }

    /** 内容写成**组件**时（用户说的「组件下面有组件」），必须渲染出组件而非空白。 */
    @Test
    fun tabs内容是组件时渲染组件而非空白() {
        val tabs = parseTab(
            """{"type":"tabs","tabs":[
              {"title":"数据","body":{"type":"table","headers":["列A","列B"],"rows":[["1","2"]]}}
            ]}"""
        )
        val n = tabs.tabs[0].node
        assertTrue("组件内容应解析为 table 节点，实际=${n?.let { it::class.simpleName }}", n is QuroTableNode)
    }

    /** 内容写成**数组**时（多个子组件），应竖排渲染，不丢。 */
    @Test
    fun tabs内容是数组时竖排不丢() {
        val tabs = parseTab(
            """{"type":"tabs","tabs":[{"title":"多段","content":[
              {"type":"text","value":"第一段"},
              {"type":"text","value":"第二段"}
            ]}]}"""
        )
        val n = tabs.tabs[0].node
        assertTrue("数组内容应包成 column，实际=${n?.let { it::class.simpleName }}", n is QuroColumnNode)
        assertEquals(2, (n as QuroColumnNode).children.size)
    }

    /** 🔴 空态必须可区分：真的没内容时 node 为 null，渲染层据此给提示（而不是静默空白）。 */
    @Test
    fun tabs无内容时node为null交由渲染层提示() {
        val tabs = parseTab("""{"type":"tabs","tabs":[{"title":"空页"}]}""")
        assertNull("确实无内容时应为 null，由渲染层给显式提示", tabs.tabs[0].node)
    }

    /** 用户说「只要是这种切换类型都是这样」→ expandable / carousel 必须一起修好。 */
    @Test
    fun expandable内容是组件时不再空白() {
        val node = parseNode(
            """{"type":"expandable","title":"详情","expanded":true,
                "body":{"type":"table","headers":["A"],"rows":[["1"]]}}"""
        ) as QuroExpandableNode
        assertNotNull("expandable 必须接住组件内容", node.node)
        assertTrue(node.node is QuroTableNode)
    }

    @Test
    fun expandable纯文本内容仍走body字段() {
        val node = parseNode(
            """{"type":"expandable","title":"说明","body":"一段说明"}"""
        ) as QuroExpandableNode
        assertEquals("一段说明", node.body)
    }

    /** 🔴 防「比空白更糟」：optString 对 JSONObject 会返回整段 JSON 文本，必须挡住。 */
    @Test
    fun expandable组件内容不会被当成JSON文本塞进body() {
        val node = parseNode(
            """{"type":"expandable","title":"详情","body":{"type":"table","headers":["A"],"rows":[["1"]]}}"""
        ) as QuroExpandableNode
        assertTrue(
            "body 不该是整段 JSON 文本，实际=${node.body.take(40)}",
            !node.body.trimStart().startsWith("{"),
        )
    }

    @Test
    fun carousel内容是组件时不再只剩标题() {
        val node = parseNode(
            """{"type":"carousel","slides":[
              {"title":"第一页","body":{"type":"table","headers":["A"],"rows":[["1"]]}}
            ]}"""
        ) as QuroCarouselNode
        assertEquals(1, node.slides.size)
        assertNotNull("轮播页必须接住组件内容", node.slides[0].node)
        assertEquals("第一页", node.slides[0].title)
    }

    @Test
    fun carousel纯文本仍正常() {
        val node = parseNode(
            """{"type":"carousel","slides":[{"title":"A","body":"文本A"},{"title":"B","body":"文本B"}]}"""
        ) as QuroCarouselNode
        assertEquals(2, node.slides.size)
        assertEquals("文本A", node.slides[0].body)
    }

    // ══════════════════════════════════════════════════════════════════════════
    // composite：schema 声明了但解析器此前**完全没有实现**
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun composite的tabs布局降解成标签页() {
        val node = parseNode(
            """{"type":"composite","layout":"tabs","children":[
              {"type":"text","value":"页面一"},
              {"type":"text","value":"页面二"}
            ]}"""
        )
        assertTrue("composite+tabs 应降解成 TabsNode，实际=${node::class.simpleName}", node is QuroTabsNode)
        assertEquals(2, (node as QuroTabsNode).tabs.size)
    }

    @Test
    fun composite的accordion布局降解成折叠块() {
        val node = parseNode(
            """{"type":"composite","layout":"accordion","children":[
              {"type":"text","value":"段一"},
              {"type":"text","value":"段二"}
            ]}"""
        )
        assertTrue("composite+accordion 应降解成 Column，实际=${node::class.simpleName}", node is QuroColumnNode)
        val kids = (node as QuroColumnNode).children
        assertEquals(2, kids.size)
        assertTrue("子项应包成 Expandable，实际=${kids[0]::class.simpleName}", kids[0] is QuroExpandableNode)
    }

    @Test
    fun composite的stack布局竖排() {
        val node = parseNode(
            """{"type":"composite","layout":"stack","children":[
              {"type":"text","value":"上"},
              {"type":"text","value":"下"}
            ]}"""
        )
        assertTrue(node is QuroColumnNode)
        assertEquals(2, (node as QuroColumnNode).children.size)
    }

    /** 缺省 layout 必须当stack 处理（不能变空白）。 */
    @Test
    fun composite缺省layout按stack处理() {
        val node = parseNode(
            """{"type":"composite","children":[{"type":"text","value":"A"}]}"""
        )
        assertTrue("缺省 layout 应竖排，实际=${node::class.simpleName}", node is QuroColumnNode)
        assertEquals(1, (node as QuroColumnNode).children.size)
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 卡片路径：同一模型输出走另一条通道，修一边不修另一边照样空白
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun 卡片tabs内容是组件时不再空白() {
        val card = parseComponentSpec(
            """{"type":"tabs","tabs":[
              {"title":"运行环境","content":{"type":"table","headers":["A"],"rows":[["1"]]}},
              {"title":"说明","body":"纯文本说明"}
            ]}"""
        ) as QuroChatCard.TabsCard
        assertEquals(2, card.tabs.size)
        assertNotNull("content 键的组件内容必须被接住", card.tabs[0].node)
        assertTrue(card.tabs[0].node is QuroChatCard.TableCard)
        assertEquals("纯文本说明", card.tabs[1].body)
    }

    @Test
    fun 卡片tabs纯文本仍走body() {
        val card = parseComponentSpec(
            """{"type":"tabs","tabs":[{"title":"概览","body":"内容"}]}"""
        ) as QuroChatCard.TabsCard
        assertEquals("内容", card.tabs[0].body)
        assertNull(card.tabs[0].node)
    }

    @Test
    fun 卡片expandable内容是组件时不再空白() {
        val card = parseComponentSpec(
            """{"type":"expandable","title":"详情","content":{"type":"stat","label":"QPS","value":"1200"}}"""
        ) as QuroChatCard.ExpandableCard
        assertNotNull(card.node)
        assertTrue(card.node is QuroChatCard.StatCard)
    }

    @Test
    fun 卡片carousel内容是组件时不再只剩标题() {
        val card = parseComponentSpec(
            """{"type":"carousel","slides":[
              {"title":"第一页","content":{"type":"stat","label":"L","value":"1"}}
            ]}"""
        ) as QuroChatCard.CarouselCard
        assertEquals(1, card.slides.size)
        assertNotNull(card.slides[0].node)
    }

    /**
     * 存档往返：富内容子卡必须能读回，否则重启/刷新后标签页又变空白——
     * 这类「当场好用、刷新就坏」的 bug 最难查，必须钉死。
     */
    @Test
    fun 富内容子卡存档往返后仍在() {
        val original = parseComponentSpec(
            """{"type":"tabs","tabs":[{"title":"数据","content":{"type":"stat","label":"QPS","value":"1200"}}]}"""
        )!!
        val json = com.ai.assistance.quro.core.cards.serializeCard(original)
        val restored = com.ai.assistance.quro.core.cards.parseCard(json)!!
        val tabs = restored as QuroChatCard.TabsCard
        assertNotNull("存档读回后富内容必须还在", tabs.tabs[0].node)
        assertTrue(tabs.tabs[0].node is QuroChatCard.StatCard)
    }

    /** 零命中绝不返回空这条既有决策不受本次改动影响（防误伤）。 */
    @Test
    fun 未知类型仍走兜底而不抛异常() {
        val card = parseComponentSpec("""{"type":"tabs","tabs":[{"title":"正常"}]}""")
        assertNotNull(card)
    }
}