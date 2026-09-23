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
import kotlin.math.abs

/**
 * miniapp-sdk 原生渲染「缺失 CSS 特性」补齐后的回归测试。
 *
 * 覆盖前一轮「AI 写的任何小程序渲染崩」根因里被补齐的能力：
 *   - aspect-ratio：单值/比值，只定一边时按比值推导另一边（方格 hack）；
 *   - display:grid 真支持：grid-template-columns 的 repeat(N,1fr) / 1fr 1fr 1fr 1fr + gap；
 *   - 百分比 padding 相对「包含块宽度」解析（旧实现错当成高度，导致单元格被拉爆）；
 *   - calc(100% - Npx) 两项式解析；
 *   - vw/vh/vmin/vmax 视口单位；
 *   - gap 在 flex 主轴生效。
 *
 * 全部用真实管线（WxmlParser -> WxssParser -> VDomBuilder -> FlexLayout）复现并用断言守卫，
 * 防止这些能力被后续改动悄悄退化回「崩」的状态。
 */
class MiniAppCssFeaturesTest {

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
                    if (cur.isNotEmpty() && cw + w > maxWidth) { lines.add(cur.toString()); cur.clear(); cw = 0f }
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

    private fun cells(root: RenderNode): List<RenderNode> {
        val board = root.children[0].children.first { it.attributes["class"] == "board" }
        return board.children.filter { it.attributes["class"] == "cell" }
    }

    private val board4x4 = (1..16).joinToString("") { """<view class="cell"><text class="n">$it</text></view>""" }
    private val page4x4 = """<view class="page"><view class="board">$board4x4</view><view class="controls"><text class="t">控制区</text></view></view>"""

    @Test
    fun aspectRatio_squareFromWidth() {
        val cells = cells(layoutOf(page4x4,
            ".page{padding:20rpx}.board{display:flex;flex-wrap:wrap;width:100%;background:#bbada0;padding:12rpx}.cell{width:25%;aspect-ratio:1;background:#cdc1b4}.n{font-size:36rpx}"))
        val c0 = cells.first()
        assertTrue("aspect-ratio:1 下单元格应成正方形，实得 w=${c0.width.toInt()} h=${c0.height.toInt()}",
            abs(c0.width - c0.height) <= 2f)
    }

    @Test
    fun gridRepeat_fourColumnsFourRowsSquare() {
        val root = layoutOf(page4x4,
            ".page{padding:20rpx}.board{display:grid;grid-template-columns:repeat(4,1fr);gap:12rpx;background:#bbada0;padding:12rpx}.cell{aspect-ratio:1;background:#cdc1b4}.n{font-size:36rpx}")
        val cs = cells(root)
        val xs = cs.map { it.absX }.toSet()
        val ys = cs.map { it.absY }.toSet()
        assertEquals("网格应排成 4 列", 4, xs.size)
        assertEquals("网格应排成 4 行", 4, ys.size)
        val c0 = cs.first()
        assertTrue("网格项应成正方形，实得 w=${c0.width.toInt()} h=${c0.height.toInt()}",
            abs(c0.width - c0.height) <= 2f)
    }

    @Test
    fun gridExplicitFractions_square() {
        val root = layoutOf(page4x4,
            ".page{padding:20rpx}.board{display:grid;grid-template-columns:1fr 1fr 1fr 1fr;gap:12rpx;background:#bbada0;padding:12rpx}.cell{aspect-ratio:1;background:#cdc1b4}.n{font-size:36rpx}")
        val cs = cells(root)
        assertEquals("显式 1fr×4 也应排成 4 列", 4, cs.map { it.absX }.toSet().size)
        assertEquals("显式 1fr×4 也应排成 4 行", 4, cs.map { it.absY }.toSet().size)
    }

    @Test
    fun paddingPercent_isWidthRelative_notHeight() {
        // 旧实现把 padding-bottom:25% 当成「高度」解析，单元格被拉成 ~664px 崩掉。
        // 正确语义：百分比 padding 相对包含块「宽度」解析，单元格高度应为「内容 + 25%宽度」，远小于旧崩值。
        val cells = cells(layoutOf(page4x4,
            ".page{padding:20rpx}.board{display:flex;flex-wrap:wrap;width:100%;background:#bbada0;padding:12rpx}.cell{width:25%;padding-bottom:25%;background:#cdc1b4}.n{font-size:36rpx}"))
        val c0 = cells.first()
        // 守卫：绝不能回到旧 bug 的 ~664px 高度；正常应 < 500px 且 > 单元格宽度（padding 贡献了高度）。
        assertTrue("百分比 padding 不应被当成高度导致单元格爆高，实得 h=${c0.height.toInt()}", c0.height < 500f)
        assertTrue("百分比 padding 应贡献高度（h 应大于宽度 w=${c0.width.toInt()}），实得 h=${c0.height.toInt()}", c0.height > c0.width)
    }

    @Test
    fun calcWidth_resolvesBinaryExpr() {
        val root = layoutOf("""<view class="box"><text>hi</text></view>""",
            ".box{width:calc(100% - 200px);height:100px}")
        val box = root.children[0]
        // 包含块宽度 = 视口 1080，calc(100% - 200px) => 880。
        assertTrue("calc(100% - 200px) 应解析为 ~880，实得 ${box.width.toInt()}", abs(box.width - 880f) <= 2f)
    }

    @Test
    fun viewportUnits_resolveWithHeight() {
        val root = layoutOf("""<view class="vbox"><text>x</text></view><view class="mbox"><text>y</text></view>""",
            ".vbox{width:100px;height:50vh}.mbox{width:10vmin;height:10vmax}")
        val vbox = root.children[0]
        val mbox = root.children[1]
        // 50vh => 0.5 * 2400 = 1200；10vmin => 0.1 * min(1080,2400)=108；10vmax => 0.1 * 2400 = 240。
        assertTrue("height:50vh 应=1200，实得 ${vbox.height.toInt()}", abs(vbox.height - 1200f) <= 2f)
        assertTrue("width:10vmin 应=108，实得 ${mbox.width.toInt()}", abs(mbox.width - 108f) <= 2f)
        assertTrue("height:10vmax 应=240，实得 ${mbox.height.toInt()}", abs(mbox.height - 240f) <= 2f)
    }

    @Test
    fun gapMainAxis_appliesInFlexRow() {
        val root = layoutOf("""<view class="row"><view class="it"/><view class="it"/><view class="it"/></view>""",
            ".row{display:flex;gap:30px;width:100%}.it{width:100px;height:100px}")
        val row = root.children[0]
        val its = row.children.filter { it.attributes["class"] == "it" }
        assertEquals("flex 行应有 3 个 it 子项（自闭合 <view/> 不应被丢弃）", 3, its.size)
        val step0 = its[1].x - its[0].x
        val step1 = its[2].x - its[1].x
        assertTrue("flex 主轴 gap 应生效（间距≈130=100+30），实得 $step0 / $step1",
            abs(step0 - 130f) <= 1f && abs(step1 - 130f) <= 1f)
    }
}
