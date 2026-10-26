package com.ai.assistance.quro.core.ui.dynamicui

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 回归：**空内容的动态 UI 围栏不再渲染「⚠️ 动态 UI 解析失败：空内容」红字**。
 *
 * ## 用户原话
 *
 * >「一直出现这种问题，修复」（截图：一张正常渲染的 UI 卡片，卡片正下方一行红字
 * > 「⚠️ 动态 UI 解析失败：空内容」，其余什么都没有）
 *
 * ## 真凶
 *
 * `parseBlock` 只在 `repaired.isBlank()` 时返回 `Failure("空内容")`，
 * 而 `ChatScreen.DynamicUiBlock` 的 Failure 分支会把 reason 画成红字 + 原始 JSON。
 * 空串从哪里来？两条正常路径：
 *
 *  1. **流式时序**（「一直出现」的主因）：模型先吐 ` ```quro-ui `，下一个 token 才到 JSON。
 *     这一帧没有闭合围栏，`ChatScreen.parseTail` 走「已开未闭合」分支按 UI 块实时渲染；
 *     其准入条件是「lang 非空 **或** after 非空」，而 lang="quro-ui" 已足够放行，
 *     于是 `after`（此刻是空串）被塞进 `MsgBlock.DynamicUi`。
 *  2. **模型多写一个空围栏**收尾：` ```quro-ui ` 紧跟 ` ``` `，
 *     `RE_FENCE` 的正则 `(?m)^```([^\n`]*)\n?([\s\S]*?)```` 会解出 `code = ""`。
 *
 * ## 口径
 *
 * 与 #181 / #206 完全一致：**空内容绝不报 Failure，也不产出可见节点**。
 * #206 当时只改了「全空数组」（`fallbackForEmptyArray`），漏了这个更早的空白分支。
 * 空 `Column` 无子节点时高度为 0、不留间隙，AI 真正说的话照常在 UI 块之外渲染。
 */
class Issue206EmptyContentTest {

    /** 空串：流式刚开围栏、正文未到。 */
    @Test
    fun 空串不再报解析失败() {
        val r = QuroUiDslParser.parseBlock("")
        assertTrue(
            "空串**绝不能**报 Failure（真机会画成「解析失败：空内容」红字），实际=$r",
            r is QuroUiParseResult.Success,
        )
    }

    /** 纯空白：模型只吐了换行/空格。 */
    @Test
    fun 纯空白不再报解析失败() {
        for (raw in listOf("   ", "\n", "\n\n\n", "\t\n  \n")) {
            val r = QuroUiDslParser.parseBlock(raw)
            assertTrue(
                "纯空白（${raw.replace("\n", "\\n")}）**绝不能**报 Failure，实际=$r",
                r is QuroUiParseResult.Success,
            )
        }
    }

    /** 空内容不产出任何可见节点（零高度，不留间隙、不自言自语）。 */
    @Test
    fun 空内容不产出可见节点() {
        val root = (QuroUiDslParser.parseBlock("") as QuroUiParseResult.Success).root
        assertTrue("空内容必须解析成空 Column，实际=$root", root is QuroColumnNode)
        assertTrue(
            "空 Column 不能有子节点（否则会留下可见痕迹），实际=${(root as QuroColumnNode).children.size}",
            root.children.isEmpty(),
        )
    }

    /** 走 UI 真正的入口（A2uiInterpreter）也是 Success，不能是 Failure。 */
    @Test
    fun 解释器入口空内容同样不报失败() {
        val r = A2uiInterpreter.interpret("")
        assertTrue(
            "A2uiInterpreter 是渲染层唯一入口，空内容也**绝不能**返回 Failure，实际=$r",
            r is QuroUiParseResult.Success,
        )
    }

    /** 正常 DSL 不受影响（防止守卫误伤了正常渲染）。 */
    @Test
    fun 正常DSL仍然正常解析() {
        val dsl = """{"type":"column","children":[{"type":"text","value":"你好"}]}"""
        val r = QuroUiDslParser.parseBlock(dsl)
        assertTrue("正常 DSL 必须仍然解析成功，实际=$r", r is QuroUiParseResult.Success)
        val root = (r as QuroUiParseResult.Success).root
        assertTrue(
            "正常 DSL 的节点不能被清空，实际=$root",
            root is QuroColumnNode && root.children.isNotEmpty(),
        )
    }
}
