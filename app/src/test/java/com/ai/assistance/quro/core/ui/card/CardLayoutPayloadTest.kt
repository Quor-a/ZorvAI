package com.ai.assistance.quro.core.ui.card

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import com.ai.assistance.quro.core.ui.card.registry.CardState
import com.ai.assistance.quro.core.ui.card.render.RenderBackend
import com.ai.assistance.quro.core.ui.card.spec.ColorToken
import com.ai.assistance.quro.core.ui.card.spec.parseCardSpec
import com.ai.assistance.quro.core.ui.card.widgets.CustomCardRenderer
import com.ai.assistance.quro.core.ui.card.widgets.CustomCardState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `custom` 自由布局卡的**载荷解析**回归测试（2026-11-02 修复钉死）。
 *
 * 背景（用户真机反馈「可视化小卡片仍然不完整」）：AI 写 ```` ```quro-card ```` 的
 * `type:"custom"` 布局树时，字段是**平铺**在节点上的，而解析层原来只从 `props`
 * 子对象读 → text/color/fontSize/gradient/value 全部丢失 → 卡片只剩骨架、内容空白。
 *
 * 本测试覆盖四类曾导致「内容消失」的缺陷：
 *  ① 平铺字段必须并入 props（否则 text 为空、样式全默认）
 *  ② 节点 type 必须归一（AI 写 "Column"/"Text" 时原样比较匹配不上 when 分支）
 *  ③ 节点同义词必须映射（progress/badge 等落到 else 就整块消失）
 *  ④ layout 写成 JSON 字符串 / 直接给顶层 children 时也必须能建出布局
 *  ⑤ 未知节点不得返回 0 尺寸（返回 0 会让整块区域消失、兄弟节点错位）
 */
class CardLayoutPayloadTest {

    /** 录制型假 backend：只统计各类绘制指令次数，验证「真的画了东西」。 */
    private class RecordingBackend : RenderBackend {
        var rects = 0
        var texts = 0
        val drawnTexts = mutableListOf<String>()
        override fun drawRect(l: Float, t: Float, r: Float, b: Float, c: Color, radiusDp: Float) { rects++ }
        override fun drawText(text: String, x: Float, y: Float, c: Color, sizeSp: Float, weight: Int) {
            texts++
            drawnTexts.add(text)
        }
        override fun drawLine(x1: Float, y1: Float, x2: Float, y2: Float, c: Color, widthDp: Float) {}
        override fun drawPath(p: Path, c: Color, widthDp: Float, fill: Boolean) {}
        override fun drawImage(bitmap: Any?, l: Float, t: Float, w: Float, h: Float) {}
        override fun saveLayer() {}
        override fun restore() {}
        override fun clip(l: Float, t: Float, r: Float, b: Float) {}
        override fun resolve(token: ColorToken): Color = Color.Gray
    }

    private fun measureOf(json: String): Size {
        val spec = parseCardSpec(json)
        assertNotNull("spec 应解析成功: $json", spec)
        val r = CustomCardRenderer()
        val st = r.createInitialState()
        return r.measure(spec!!, st, 360f, 2.75f)
    }

    // ── ① 平铺字段并入 props ──

    @Test
    fun flatTextAndStyle_areKeptInProps() {
        val json = """{"type":"custom","layout":{"type":"column","children":[
            {"type":"text","text":"86%","fontSize":32,"color":"#C25A38"},
            {"type":"bar","value":72,"h":8,"gradient":["#C25A38","#B8902F"]}
        ]}}"""
        val spec = parseCardSpec(json)
        assertNotNull(spec)
        val root = spec!!.layout
        assertNotNull("layout 必须解析出来（null 会渲染成空盒子）", root)
        val textNode = root!!.children[0]
        assertEquals("平铺的 text 必须进 props", "86%", textNode.props["text"])
        assertEquals("平铺的 fontSize 必须进 props", 32.0, (textNode.props["fontSize"] as Number).toDouble(), 0.001)
        assertEquals("平铺的 color 必须进 props", "#C25A38", textNode.props["color"])
        val barNode = root.children[1]
        assertEquals("平铺的 value 必须进 props", 72.0, (barNode.props["value"] as Number).toDouble(), 0.001)
        assertTrue("平铺的 gradient 必须进 props", barNode.props["gradient"] is List<*>)
    }

    @Test
    fun explicitPropsObject_stillWins() {
        val json = """{"type":"custom","layout":{"type":"text","text":"外层","props":{"text":"内层"}}}"""
        val spec = parseCardSpec(json)!!
        assertEquals("显式 props 子对象应覆盖平铺值", "内层", spec.layout!!.props["text"])
    }

    // ── ② 节点 type 归一 ──

    @Test
    fun nodeType_isNormalizedToLowercase() {
        val json = """{"type":"custom","layout":{"type":"Column","children":[
            {"type":"Text","text":"大写节点"},
            {"type":"Box","padding":8}
        ]}}"""
        val spec = parseCardSpec(json)!!
        val root = spec.layout!!
        assertEquals("column", root.type)
        assertEquals("text", root.children[0].type)
        assertEquals("box", root.children[1].type)
        assertTrue("大写 Text 节点也应能测出高度", measureOf(json).height > 0f)
    }

    // ── ③ 同义词映射 ──

    @Test
    fun aliasTypes_mapToRenderableNodes() {
        fun t(nodeJson: String): String {
            val spec = parseCardSpec("""{"type":"custom","layout":$nodeJson}""")!!
            return spec.layout!!.type
        }
        assertEquals("progress 必须映射成 bar", "bar", t("""{"type":"progress","value":50}"""))
        assertEquals("progressBar 必须映射成 bar", "bar", t("""{"type":"progressBar","value":50}"""))
        assertEquals("label 必须映射成 text", "text", t("""{"type":"label","text":"x"}"""))
        assertEquals("container 必须映射成 column", "column", t("""{"type":"container"}"""))
        assertEquals("hstack 必须映射成 row", "row", t("""{"type":"hstack"}"""))
        assertEquals("circle 必须映射成 ring", "ring", t("""{"type":"circle","value":60}"""))
        assertEquals("separator 必须映射成 divider", "divider", t("""{"type":"separator"}"""))
    }

    // ── ④ layout 的三种写法 ──

    @Test
    fun layoutAsJsonString_isParsed() {
        val inner = """{"type":"column","children":[{"type":"text","text":"字符串布局"}]}"""
        val escaped = inner.replace("\"", "\\\"")
        val json = """{"type":"custom","layout":"$escaped"}"""
        val spec = parseCardSpec(json)
        assertNotNull(spec)
        assertNotNull("layout 写成 JSON 字符串也必须解析", spec!!.layout)
        assertEquals("字符串布局", spec.layout!!.children[0].props["text"])
    }

    @Test
    fun topLevelChildren_fallsBackToRootLayout() {
        val json = """{"type":"custom","children":[{"type":"text","text":"顶层 children"}]}"""
        val spec = parseCardSpec(json)
        assertNotNull(spec)
        assertNotNull("没有 layout 但顶层有 children 时，要把它当布局根", spec!!.layout)
        assertEquals("顶层 children", spec.layout!!.children[0].props["text"])
    }

    // ── ⑤ 未知节点不得塌陷成 0 尺寸 ──

    @Test
    fun unknownNode_doesNotCollapseToZeroHeight() {
        val json = """{"type":"custom","layout":{"type":"column","children":[
            {"type":"totally_unknown_node","text":"我是一段文字","fontSize":14},
            {"type":"another_unknown","h":30}
        ]}}"""
        val size = measureOf(json)
        assertTrue("未知节点不能返回 0 尺寸（会让整块区域消失），实际高度=${size.height}", size.height > 0f)
    }

    @Test
    fun unknownNode_rendersItsText() {
        val json = """{"type":"custom","layout":{"type":"column","children":[
            {"type":"mystery_widget","text":"不该消失的文字"}
        ]}}"""
        val spec = parseCardSpec(json)!!
        val r = CustomCardRenderer()
        val st = r.createInitialState() as CustomCardState
        val size = r.measure(spec, st, 360f, 2.75f)
        val be = RecordingBackend()
        r.render(be, spec, r.layout(spec, st, size, 2.75f), st)
        assertTrue(
            "未知节点的文字必须兜底画出来，实际画出=${be.drawnTexts}",
            be.drawnTexts.any { it.contains("不该消失") },
        )
    }

    // ── 新增节点类型真的会绘制 ──

    @Test
    fun badgeButtonIcon_areDrawn() {
        val json = """{"type":"custom","layout":{"type":"column","gap":8,"children":[
            {"type":"badge","text":"进行中","bg":"#C25A38"},
            {"type":"button","text":"立即开始"},
            {"type":"icon","text":"★","size":20}
        ]}}"""
        val spec = parseCardSpec(json)!!
        val r = CustomCardRenderer()
        val st = r.createInitialState() as CustomCardState
        val size = r.measure(spec, st, 360f, 2.75f)
        assertTrue("三个节点都要占高度", size.height > 0f)
        val be = RecordingBackend()
        r.render(be, spec, r.layout(spec, st, size, 2.75f), st)
        assertTrue("badge 要画背景矩形", be.rects >= 1)
        assertTrue("badge/button/icon 的文字都要画出来", be.texts >= 3)
    }

    @Test
    fun fontWeight_readFromFontWeightNotLayoutWeight() {
        // weight 是布局权重（结构性键），不能被当字体粗细；字体粗细走 fontWeight
        val json = """{"type":"custom","layout":{"type":"row","children":[
            {"type":"text","text":"粗","fontWeight":700,"weight":1}
        ]}}"""
        val spec = parseCardSpec(json)!!
        val node = spec.layout!!.children[0]
        assertEquals("weight 依然是布局权重", 1.0, node.weight.toDouble(), 0.001)
        assertEquals("fontWeight 进 props", 700.0, (node.props["fontWeight"] as Number).toDouble(), 0.001)
    }

    // ── 列表节点 ──

    @Test
    fun listNode_rendersItemsText() {
        val json = """{"type":"custom","layout":{"type":"list","items":["第一行","第二行","第三行"]}}"""
        val spec = parseCardSpec(json)!!
        val r = CustomCardRenderer()
        val st = r.createInitialState() as CustomCardState
        val size = r.measure(spec, st, 360f, 2.75f)
        val be = RecordingBackend()
        r.render(be, spec, r.layout(spec, st, size, 2.75f), st)
        assertEquals("三条 items 都要画出来", 3, be.texts)
    }
}
