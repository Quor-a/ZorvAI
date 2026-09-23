package com.ai.assistance.quro.miniapp

import com.yuanbao.miniapp.render.FlexLayout
import com.yuanbao.miniapp.render.RenderNode
import com.yuanbao.miniapp.render.TextMeasurer
import com.yuanbao.miniapp.util.Json
import com.yuanbao.miniapp.view.VDomBuilder
import com.yuanbao.miniapp.view.WxmlParser
import com.yuanbao.miniapp.view.WxssParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * flex-wrap 容器「内在尺寸」回归测试。
 *
 * 根因（截图 memory-match / game2048 的错位叠加）：
 * FlexLayout.measure() 的容器分支对 flex-wrap 容器把所有子项当成「单行」算交叉轴尺寸，
 * 于是 4x4 网格容器高度只算一行，后继兄弟（「重新开始」按钮 / 方向盘）被排在网格第 1 行之后，
 * 直接压在网格第 2~4 行上。
 *
 * 用真实管线（WxmlParser -> WxssParser -> VDomBuilder -> FlexLayout）复现，并断言：
 *  1) 4x4 网格容器高度 ~= 4 行（而非 1 行）—— 直接命中被修的 measure 分支；
 *  2) 网格后的「重新开始」按钮被排在网格底部（4 行）之下，不重叠；
 *  3) 16 个格子分布在 4 个 distinct y 带（4 行可见）。
 */
class FlexWrapRegressionTest {

    private val viewportW = 1080f
    private val viewportH = 2400f
    private val rpxRatio = viewportW / 750f

    private fun stubMeasurer(): (String, Float, Boolean, Float) -> TextMeasurer.Result =
        { text, fontSize, bold, maxWidth ->
            fun charW(c: Char) = if (c.code > 0x2E80) fontSize else fontSize * 0.55f
            fun lineW(s: String) = s.fold(0f) { acc, c -> acc + charW(c) }
            if (text.isEmpty()) {
                TextMeasurer.Result(emptyList(), 0f, 0f)
            } else if (maxWidth <= 0f) {
                TextMeasurer.Result(listOf(text), lineW(text), fontSize * 1.25f)
            } else {
                val lines = ArrayList<String>()
                val cur = StringBuilder()
                var cw = 0f
                for (c in text) {
                    val w = charW(c)
                    if (cur.isNotEmpty() && cw + w > maxWidth) {
                        lines.add(cur.toString()); cur.clear(); cw = 0f
                    }
                    cur.append(c); cw += w
                }
                if (cur.isNotEmpty()) lines.add(cur.toString())
                val widest = lines.maxOfOrNull { lineW(it) } ?: 0f
                TextMeasurer.Result(lines, widest, lines.size * fontSize * 1.25f)
            }
        }

    private fun layoutOf(wxml: String, wxss: String): RenderNode {
        val tpl = WxmlParser().parse(wxml)
        val rules = WxssParser().parse(wxss)
        val root = VDomBuilder().build(tpl, Json.Obj(), rules)
        val engine = FlexLayout(viewportW, viewportH)
        engine.textMeasurer = stubMeasurer()
        engine.layout(root, viewportW, viewportH)
        return root
    }

    @Test
    fun wrapBoard_reservesFourRows_andSiblingDoesNotOverlap() {
        val root = layoutOf(
            """
            <view class="page">
              <view class="board">
                <view class="cell"><text class="n">2</text></view>
                <view class="cell"><text class="n">4</text></view>
                <view class="cell"><text class="n">8</text></view>
                <view class="cell"><text class="n">16</text></view>
                <view class="cell"><text class="n">2</text></view>
                <view class="cell"><text class="n">4</text></view>
                <view class="cell"><text class="n">8</text></view>
                <view class="cell"><text class="n">16</text></view>
                <view class="cell"><text class="n">2</text></view>
                <view class="cell"><text class="n">4</text></view>
                <view class="cell"><text class="n">8</text></view>
                <view class="cell"><text class="n">16</text></view>
                <view class="cell"><text class="n">2</text></view>
                <view class="cell"><text class="n">4</text></view>
                <view class="cell"><text class="n">8</text></view>
                <view class="cell"><text class="n">16</text></view>
              </view>
              <view class="footer"><text class="n">重新开始</text></view>
            </view>
            """.trimIndent(),
            """
            .page { padding: 24rpx; }
            .board { display: flex; flex-wrap: wrap; background-color: #bbada0; padding: 12rpx; }
            .cell { width: 25%; height: 160rpx; }
            .n { font-size: 40rpx; }
            .footer { height: 80rpx; }
            """.trimIndent()
        )

        val page = root.children[0]
        val board = page.children.first { it.attributes["class"] == "board" }
        val footer = page.children.first { it.attributes["class"] == "footer" }

        val cellH = 160f * rpxRatio
        val pad = 12f * rpxRatio
        val expectedBoardH = 4f * cellH + 2f * pad

        // 1) 核心：网格容器高度必须 ~= 4 行（旧 bug 会把 4x4 网格算成 1 行高度）。
        assertTrue(
            "board 高度应~=4行(${expectedBoardH.toInt()})，实得 ${board.height.toInt()}（旧 bug 会塌成 1 行）",
            kotlin.math.abs(board.height - expectedBoardH) <= 2f
        )

        // 2) 真实症状：后继「重新开始」必须排在网格底部（4 行）之下，不能压在网格第 2~4 行上。
        val boardBottom = board.y + 4f * cellH
        assertTrue(
            "footer(y=${footer.y.toInt()}) 必须 >= board 4 行底部(${boardBottom.toInt()})，否则重叠",
            footer.y >= boardBottom - 1f
        )

        // 3) 16 个格子应分布在 4 个 distinct y 带（4 行可见，而非塌成 1 行）。
        val cellYs = board.children
            .filter { it.attributes["class"] == "cell" }
            .map { it.absY }
        assertEquals("应有 16 个格子", 16, cellYs.size)
        val uniqueRows = cellYs.map { (it / (cellH / 2f + 1f)).toInt() }.toSet()
        assertTrue("格子应分布在 4 行，实得 ${uniqueRows.size} 行", uniqueRows.size == 4)
    }
}
