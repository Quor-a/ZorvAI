package com.ai.assistance.quro.miniapp

import com.yuanbao.miniapp.render.FlexLayout
import com.yuanbao.miniapp.render.RenderNode
import com.yuanbao.miniapp.render.TextMeasurer
import com.yuanbao.miniapp.util.Json
import com.yuanbao.miniapp.view.VDomBuilder
import com.yuanbao.miniapp.view.WxmlParser
import com.yuanbao.miniapp.view.WxssParser
import org.junit.Test
import java.io.File

/**
 * 接地气探针：复现 AI 写 game2048 类的典型 WXSS 写法，dump 引擎真实排版，
 * 用来定位「到底哪些 CSS 特性缺失/错算」导致单元格被拉高、控件叠网格。
 * 本测试只打印，不断言（纯诊断）。
 */
class EngineGapProbeTest {

    private val viewportW = 1080f
    private val viewportH = 2400f

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

    private fun dump(node: RenderNode, sb: StringBuilder, depth: Int) {
        val cls = node.attributes["class"] ?: ""
        sb.append("  ".repeat(depth))
            .append(node.tag).append(if (cls.isEmpty()) "" else "[$cls]")
            .append(" w=").append(node.width.toInt())
            .append(" h=").append(node.height.toInt())
            .append(" x=").append(node.x.toInt())
            .append(" y=").append(node.absY.toInt())
            .append("\n")
        for (c in node.children) if (c.style.display != com.yuanbao.miniapp.render.Display.NONE) dump(c, sb, depth + 1)
    }

    private fun probe(title: String, wxml: String, wxss: String): String {
        val sb = StringBuilder()
        sb.append("\n===== ").append(title).append(" =====\n")
        try { dump(layoutOf(wxml, wxss), sb, 0) } catch (e: Exception) { sb.append("ERROR: ").append(e.message).append("\n") }
        return sb.toString()
    }

    @Test
    fun probeGap() {
        val out = StringBuilder()
        val cells = (1..16).joinToString("") { """<view class="cell"><text class="n">$it</text></view>""" }
        val page = """<view class="page"><view class="board">$cells</view><view class="controls"><text class="t">控制区</text></view></view>"""

        out.append(probe("A flex-wrap+width25%无高度", page,
            ".page{padding:20rpx}.board{display:flex;flex-wrap:wrap;width:100%;background:#bbada0;padding:12rpx}.cell{width:25%}.n{font-size:36rpx}"))

        out.append(probe("B +aspect-ratio:1", page,
            ".page{padding:20rpx}.board{display:flex;flex-wrap:wrap;width:100%;background:#bbada0;padding:12rpx}.cell{width:25%;aspect-ratio:1;background:#cdc1b4}.n{font-size:36rpx}"))

        out.append(probe("C grid repeat(4,1fr)+gap+aspect", page,
            ".page{padding:20rpx}.board{display:grid;grid-template-columns:repeat(4,1fr);gap:12rpx;background:#bbada0;padding:12rpx}.cell{aspect-ratio:1;background:#cdc1b4}.n{font-size:36rpx}"))

        out.append(probe("D flex-wrap+padding-bottom:25%", page,
            ".page{padding:20rpx}.board{display:flex;flex-wrap:wrap;width:100%;background:#bbada0;padding:12rpx}.cell{width:25%;padding-bottom:25%;background:#cdc1b4}.n{font-size:36rpx}"))

        out.append(probe("E grid 1fr 1fr 1fr 1fr", page,
            ".page{padding:20rpx}.board{display:grid;grid-template-columns:1fr 1fr 1fr 1fr;gap:12rpx;background:#bbada0;padding:12rpx}.cell{aspect-ratio:1;background:#cdc1b4}.n{font-size:36rpx}"))

        val text = out.toString()
        println(text)
        File(System.getProperty("user.dir"), "engine_gap_probe.txt").writeText(text)
    }
}
