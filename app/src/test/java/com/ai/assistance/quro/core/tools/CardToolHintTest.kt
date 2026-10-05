package com.ai.assistance.quro.core.tools

import com.ai.assistance.quro.core.cards.CardSdk
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 四个富卡片工具的「提示词面」自检。
 *
 * 背景：这几轮修的缺陷几乎全是同一族——**静默失效**。
 * 编译干净、运行不报错、日志无一行，但模型就是写不对卡片：
 *  - 名册从 40 种扩到上百种，系统提示词与两份工具描述里的手抄清单一个字没跟着变；
 *  - 工具描述把卡片挂在 `ui_control(action="card"/"widget")` 名下，那是个**不存在的 action**；
 *  - 工具描述让人去查 `CARD_CATALOG`，那是个 Kotlin val，**模型根本调不到**（真入口是 `card_catalog` 工具）；
 *  - 「七大归类」，而名册实际 9 个类目。
 *
 * 这类缺陷跑不进任何执行路径，只能在**文字层面**设卡子：名册一扩这里必须跟着对，
 * 否则新组件又变成「只对碰巧见过的模型有效」。
 */
class CardToolHintTest {

    private val widget = UiWidgetTool().description
    private val card = UiCardTool().description
    private val catalog = CardCatalogTool().description
    private val patch = CardPatchTool().description

    @Test
    fun `四工具描述都不提那个调不到的 CARD_CATALOG`() {
        listOf(
            "ui_widget" to widget,
            "ui_card" to card,
            "card_catalog" to catalog,
            "card_patch" to patch,
        ).forEach { (n, d) ->
            assertTrue("$n 描述里出现了模型调不到的 CARD_CATALOG（真入口是 card_catalog 工具）", !d.contains("CARD_CATALOG"))
        }
    }

    @Test
    fun `ui_widget 描述报出的类目与名册一致`() {
        val cats = CardSdk.byCategory.keys
        assertTrue("名册类目数为空", cats.isNotEmpty())
        // 名册有几个类目，描述里就该提几个 —— 写死「七大」时名册已是 9 个
        listOf("七大", "八大", "六大", "五大", "四大", "三大", "七大归类").forEach { wrong ->
            assertTrue("ui_widget 描述仍写着过时的「${wrong}归类」（实际 ${cats.size} 个）", !widget.contains(wrong))
        }
        cats.forEach { cat ->
            assertTrue("ui_widget 描述没提到类目 $cat", widget.contains(cat))
        }
    }

    @Test
    fun `ui_widget 描述不把常用几类的手写字段段冒充全量`() {
        // 这段手写字段是唯一常驻的字段来源，故意保留；但必须标明它不是全量
        assertTrue(
            "手写字段段必须标明「不是全量」，否则模型以为清单就这些",
            widget.contains("这不是全量"),
        )
    }

    @Test
    fun `下发工具现读名册数量而不是写死`() {
        val n = CardSdk.typeCount.toString()
        assertTrue("card_catalog 描述没现读名册数量（应含 $n）", catalog.contains(n))
        assertTrue("ui_card 描述没现读名册数量（应含 $n）", card.contains(n))
        assertTrue("ui_widget 描述没现读名册数量（应含 $n）", widget.contains(n))
    }

    @Test
    fun `三份提示词面都带全量紧凑清单`() {
        // compactCatalog() 是名册的单一真源出口，三处都在用才算接上这一环
        listOf("ui_widget" to widget, "ui_card" to card).forEach { (n, d) ->
            assertTrue("$n 描述没带紧凑清单", d.contains(CardSdk.compactCatalog()))
        }
    }

    @Test
    fun `工具用法提示表覆盖四个富卡片工具`() {
        // buildToolUseDirective / QuroVoiceBallService 都会遍历这张表往提示词注入说明。
        // 表里缺项 = 这些工具只有一句干巴巴的描述，没有「用户会怎么说」的映射。
        listOf("ui_widget", "ui_card", "card_catalog", "card_patch").forEach { k ->
            assertTrue("TOOL_USAGE_HINTS 缺 $k 的专属提示", !QuroToolUsageHints.TOOL_USAGE_HINTS[k].isNullOrBlank())
        }
    }

    @Test
    fun `工具用法提示表不再把卡片挂在 ui_control 的 action 上`() {
        val uiControl = QuroToolUsageHints.TOOL_USAGE_HINTS["ui_control"]
        assertTrue("ui_control 提示缺失", !uiControl.isNullOrBlank())
        assertTrue(
            "ui_control 提示仍宣称能用 action=card/widget 渲染卡片（不存在的 action）",
            !uiControl!!.contains("action=\"card\"") && !uiControl.contains("action=\"widget\""),
        )
    }
}
