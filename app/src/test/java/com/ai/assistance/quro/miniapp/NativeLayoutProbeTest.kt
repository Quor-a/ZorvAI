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
 * 原生小程序（miniapp-sdk）排版探针。
 *
 * 目的：把「AI 写的 WXSS 在引擎里到底排出什么宽度」直接打印出来。
 * 截图里出现的病态排版（右侧列被压成一字一行、网格塌成一根竖条）都发生在
 * FlexLayout 的宽度分配上，这里用真实管线（WxmlParser → WxssParser →
 * VDomBuilder → FlexLayout）复现，避免靠读代码猜。
 *
 * 文本度量用确定性桩（CJK=fontSize，其他=0.55*fontSize），
 * 因为单测里 Android Paint 走 isReturnDefaultValues 恒返回 0，会掩盖折行问题。
 */
class NativeLayoutProbeTest {

    private val viewportW = 1080f
    private val viewportH = 2400f

    /** 与 TextMeasurer 同构的确定性度量：贪心折行 + 最宽行宽度。 */
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
                        lines.add(cur.toString())
                        cur.clear()
                        cw = 0f
                    }
                    cur.append(c)
                    cw += w
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

    private fun dump(node: RenderNode, sb: StringBuilder, depth: Int): StringBuilder {
        val cls = node.attributes["class"] ?: ""
        val label = node.text.take(22).replace("\n", "\\n")
        sb.append("  ".repeat(depth))
            .append(node.tag)
            .append(if (cls.isEmpty()) "" else "[$cls]")
            .append(" w=").append(node.width.toInt())
            .append(" h=").append(node.height.toInt())
            .append(" x=").append(node.x.toInt())
            .append(" lines=").append(node.lines.size)
            .append(if (node.lines.size in 2..3) " lines=${node.lines}" else "")
            .append(if (label.isEmpty()) "" else " text=\"$label\"")
            .append('\n')
        for (c in node.children) dump(c, sb, depth + 1)
        return sb
    }

    private fun probe(title: String, wxml: String, wxss: String): String {
        val root = layoutOf(wxml, wxss)
        val sb = StringBuilder()
        sb.append("\n===== $title =====\n")
        dump(root, sb, 0)
        return sb.toString()
    }

    @Test
    fun probeLayoutPatterns() {
        val out = StringBuilder()
        out.append("viewport=${viewportW.toInt()}x${viewportH.toInt()} rpxRatio=${viewportW / 750f}\n")

        // A. 卡片 + space-between 行 + 右侧信息列（截图 5/6 的病态：右列被压成一字一行）
        out.append(
            probe(
                "A 卡片内 space-between 行 + 右侧列",
                """
                <view class="page">
                  <view class="card">
                    <view class="today-row">
                      <view class="today-left">
                        <text class="cond">多云转晴</text>
                      </view>
                      <view class="today-right">
                        <text class="temp">29°C</text>
                        <text class="updated">当前 09:16 更新</text>
                        <text class="updated">紫外线指数 24% 强</text>
                      </view>
                    </view>
                    <text class="hi-label">最高</text>
                    <text class="hi">32.6°C</text>
                  </view>
                </view>
                """.trimIndent(),
                """
                .page { padding: 24rpx; }
                .card { background-color: #ffffff; border-radius: 20rpx; padding: 28rpx; }
                .today-row { display: flex; flex-direction: row; justify-content: space-between; }
                .today-left { display: flex; flex-direction: column; align-items: center; }
                .today-right { display: flex; flex-direction: column; align-items: flex-end; }
                .cond { font-size: 30rpx; }
                .temp { font-size: 88rpx; }
                .updated { font-size: 22rpx; }
                .hi-label { font-size: 24rpx; color: #888888; }
                .hi { font-size: 44rpx; }
                """.trimIndent()
            )
        )

        // B. 列表行：定宽子项 + flex:1 中间项（截图 1~3 的病态：hi/lo 挤在右边缘一字一行）
        out.append(
            probe(
                "B 列表行 定宽子项 + flex:1",
                """
                <view class="page">
                  <view class="day">
                    <text class="d-name">周四 9/24</text>
                    <text class="d-cond">阴天 · 无降水</text>
                    <text class="d-hi">33.1°</text>
                    <text class="d-lo">25.4°</text>
                  </view>
                </view>
                """.trimIndent(),
                """
                .page { padding: 24rpx; }
                .day { display: flex; flex-direction: row; align-items: center; }
                .d-name { font-size: 26rpx; width: 120rpx; }
                .d-cond { font-size: 24rpx; flex: 1; }
                .d-hi { font-size: 26rpx; width: 80rpx; text-align: right; }
                .d-lo { font-size: 26rpx; width: 80rpx; text-align: right; }
                """.trimIndent()
            )
        )

        // C. 2 列网格（截图 6 里这块是好的，作为对照组）
        out.append(
            probe(
                "C 2 列网格 width:50%",
                """
                <view class="page">
                  <view class="grid">
                    <view class="cell"><text class="t">最高温度</text></view>
                    <view class="cell"><text class="t">最低温度</text></view>
                  </view>
                </view>
                """.trimIndent(),
                """
                .page { padding: 24rpx; }
                .grid { display: flex; flex-wrap: wrap; }
                .cell { width: 50%; }
                .t { font-size: 26rpx; }
                """.trimIndent()
            )
        )

        // D. 不支持的长度写法（calc/vw/fr）—— 期望 auto，实际可能是 0
        out.append(
            probe(
                "D calc / vw / fr 宽度",
                """
                <view class="page">
                  <view class="half"><text class="t">calc 减半</text></view>
                  <view class="vw"><text class="t">vw 宽</text></view>
                  <view class="fr"><text class="t">fr 宽</text></view>
                </view>
                """.trimIndent(),
                """
                .page { padding: 24rpx; }
                .half { width: calc(50% - 20rpx); }
                .vw { width: 50vw; }
                .fr { width: 1fr; }
                .t { font-size: 26rpx; }
                """.trimIndent()
            )
        )

        // E. 4x4 棋盘（截图 7 的病态：塌成一根竖条）
        out.append(
            probe(
                "E 4x4 棋盘 flex-wrap + 25%",
                """
                <view class="page">
                  <view class="board">
                    <view class="cell"><text class="n">2</text></view>
                    <view class="cell"><text class="n">2</text></view>
                    <view class="cell"><text class="n">4</text></view>
                    <view class="cell"><text class="n">8</text></view>
                  </view>
                </view>
                """.trimIndent(),
                """
                .page { padding: 24rpx; }
                .board { display: flex; flex-wrap: wrap; background-color: #bbada0; padding: 12rpx; }
                .cell { width: 25%; height: 160rpx; align-items: center; }
                .n { font-size: 40rpx; }
                """.trimIndent()
            )
        )

        val text = out.toString()
        println(text)
        File(System.getProperty("user.dir"), "native_layout_probe.txt").writeText(text)
    }

    /** 直接把解析出来的 Style 打出来，隔离「解析丢声明」与「布局算错」。 */
    @Test
    fun probeStyleParsing() {
        val css = """
            .col { display: flex; flex-direction: column; align-items: flex-end; }
            .row { display: flex; flex-direction: row; }
            .cell { width: 25%; }
            .half { width: calc(50% - 20rpx); }
            .nofl { display: flex; }
        """.trimIndent()
        val rules = WxssParser().parse(css)
        println("parsed rules=${rules.size}")
        for (r in rules) {
            println("  rule selectors=${r.selectors.size} decls=${r.declarations}")
        }
        for (cls in listOf("col", "row", "cell", "half", "nofl")) {
            val tpl = WxmlParser().parse("<view class=\"$cls\"></view>")
            val n = tpl.children.first()
            val s = WxssParser().styleFor(n, rules)
            println(
                "class=$cls -> display=${s.display}(set=${s.displaySet}) " +
                    "dir=${s.flexDirection}(set=${s.flexDirectionSet}) " +
                    "align=${s.alignItems}(set=${s.alignSet}) " +
                    "justify=${s.justifyContent}(set=${s.justifySet}) " +
                    "width=${s.width.value}/${s.width.unit}(set=${s.widthSet}) " +
                    "grow=${s.flexGrow} shrink=${s.flexShrink}"
            )
        }
    }

    /** 追加的布局场景：显式 column、窄定宽容器、四格 flex:1 行。 */
    @Test
    fun probeLayoutPatternsExtra() {
        val out = StringBuilder()

        // F. 显式 column 容器：子项应当纵向堆叠（y 递增），不是横排
        out.append(
            probe(
                "F 显式 flex-direction:column",
                """
                <view class="page">
                  <view class="col">
                    <text class="a">第一行</text>
                    <text class="b">第二行</text>
                  </view>
                </view>
                """.trimIndent(),
                """
                .page { padding: 24rpx; }
                .col { display: flex; flex-direction: column; }
                .a { font-size: 30rpx; }
                .b { font-size: 30rpx; }
                """.trimIndent()
            )
        )

        // G. 窄定宽容器 + 长文本：应当按容器宽度折行（而不是一字一行）
        out.append(
            probe(
                "G 窄定宽容器 200rpx + 长文本",
                """
                <view class="page">
                  <view class="narrow"><text class="t">紫外线指数 24% 强 注意防晒</text></view>
                </view>
                """.trimIndent(),
                """
                .page { padding: 24rpx; }
                .narrow { width: 200rpx; }
                .t { font-size: 26rpx; }
                """.trimIndent()
            )
        )

        // H. 四格 flex:1 行（截图 1「今日概况」的那一行）
        out.append(
            probe(
                "H 四格 flex:1 行",
                """
                <view class="page">
                  <view class="stats">
                    <view class="stat"><text class="k">最高温</text><text class="v">32.7°C</text></view>
                    <view class="stat"><text class="k">最低温</text><text class="v">28.3°C</text></view>
                    <view class="stat"><text class="k">日出</text><text class="v">06:03</text></view>
                    <view class="stat"><text class="k">日落</text><text class="v">18:10</text></view>
                  </view>
                </view>
                """.trimIndent(),
                """
                .page { padding: 24rpx; }
                .stats { display: flex; flex-direction: row; }
                .stat { flex: 1; display: flex; flex-direction: column; }
                .k { font-size: 22rpx; color: #888888; }
                .v { font-size: 30rpx; }
                """.trimIndent()
            )
        )

        val text = out.toString()
        println(text)
        File(System.getProperty("user.dir"), "native_layout_probe2.txt").writeText(text)
    }
}
