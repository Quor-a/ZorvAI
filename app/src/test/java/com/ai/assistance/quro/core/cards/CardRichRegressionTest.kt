package com.ai.assistance.quro.core.cards

import com.ai.assistance.quro.core.ui.card.CardModule
import com.ai.assistance.quro.core.ui.card.spec.parseCardSpec
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 复现 2026-11-01 真机截图：可视化小卡片「残缺」——
 * 以前能渲出渐变+环形的丰富卡，现在只剩标题壳 + 灰盒 "--"。
 * 把模型可能写出的各种「测试报告」形态全过一遍两条渲染路由，找出退化点。
 */
class CardRichRegressionTest {

    private fun whitelistOf(): Set<String> {
        CardModule.init()
        return CardModule.registeredTypes()
    }

    /** 模拟 SelfCardMount 路由：白名单→Canvas 路径；否则→富卡片层。返回 (路径, 结果描述)。 */
    private fun route(json: String): Pair<String, String> {
        val wl = whitelistOf()
        val spec = parseCardSpec(json) ?: return "解析失败" to "parseCardSpec=null"
        val o = JSONObject(json)
        val dataDesc = when (val d = spec.data) {
            is com.ai.assistance.quro.core.ui.card.spec.CardData.Chart ->
                "Chart(series=${d.series.size}, pts=${d.series.firstOrNull()?.points?.size ?: 0})"
            is com.ai.assistance.quro.core.ui.card.spec.CardData.Media -> "Media(${d.mediaType}, rows=${d.rows.size})"
            is com.ai.assistance.quro.core.ui.card.spec.CardData.Form -> "Form(${d.formType}, btns=${d.buttons.size})"
            is com.ai.assistance.quro.core.ui.card.spec.CardData.Status -> "Status(${d.statusType}, ${d.text})"
            else -> "Empty"
        }
        val layoutDesc = spec.layout?.let { "layout(${it.type}, kids=${it.children.size})" } ?: "无layout"
        if (spec.type in wl) {
            return "Canvas白名单[${spec.type}]" to "$dataDesc / $layoutDesc"
        }
        val rich = CardSdk.parseObj(o)
        return "富卡片层[${spec.type}]" to "${rich?.javaClass?.simpleName ?: "null(丢弃!)"} / $dataDesc / $layoutDesc"
    }

    @Test
    fun `A custom 自由布局树_以前丰富卡的写法`() {
        val json = """
            {"type":"custom","title":"后台虚拟屏 · 测试报告","layout":{"type":"column","children":[
              {"type":"text","text":"通过 12/15"},
              {"type":"ring","value":86,"text":"86%"}
            ]}}
        """.trimIndent()
        val (path, desc) = route(json)
        println("A => $path : $desc")
        assertEquals("Canvas白名单[custom]", path)
        assertTrue("layout 树必须解析出来，实际: $desc", desc.contains("layout(column"))
    }

    @Test
    fun `B card 容器+子卡`() {
        val json = """
            {"type":"card","title":"后台虚拟屏 · 测试报告","children":[
              {"type":"stat","label":"通过","value":"12/15"},
              {"type":"metric","label":"失败","value":3}
            ]}
        """.trimIndent()
        val (path, desc) = route(json)
        println("B => $path : $desc")
        assertEquals("富卡片层[card]", path)
        assertTrue("card 容器应产出 CompositeCard，实际: $desc", desc.contains("CompositeCard"))
    }

    @Test
    fun `C metric 平铺字段`() {
        val json = """{"type":"metric","title":"后台虚拟屏 · 测试报告","label":"通过率","value":86,"unit":"%"}"""
        val (path, desc) = route(json)
        println("C => $path : $desc")
        assertEquals("Canvas白名单[metric]", path)
        assertTrue("value 必须包进 series，实际: $desc", desc.contains("pts=1"))
    }

    @Test
    fun `D metric 标准 data 壳`() {
        val json = """{"type":"metric","data":{"kind":"chart","series":[{"name":"通过率","points":[86],"labels":["%"]}]}}"""
        val (path, desc) = route(json)
        println("D => $path : $desc")
        assertTrue("实际: $desc", desc.contains("pts=1"))
    }

    @Test
    fun `E 多段报告卡_stat数组平铺`() {
        // 模型爱写：顶层 title + 多个 stat 平铺（无 children 包裹）
        val json = """
            {"type":"card","title":"后台虚拟屏 · 测试报告","stats":[
              {"label":"通过","value":"12/15"},{"label":"失败","value":3}
            ]}
        """.trimIndent()
        val (path, desc) = route(json)
        println("E => $path : $desc")
    }

    @Test
    fun `F 无type 平铺指标(带trend)`() {
        val json = """{"title":"Zorv AI 健康度","value":98,"unit":"/100","trend":"↑ 良好"}"""
        val (path, desc) = route(json)
        println("F => $path : $desc")
        assertTrue("实际: $desc", desc.contains("pts=1"))
    }

    @Test
    fun `G composite 名册标准容器`() {
        val json = """
            {"type":"composite","title":"后台虚拟屏 · 测试报告","layout":"stack","children":[
              {"type":"stat","label":"通过","value":"12/15"},
              {"type":"progress","label":"完成","value":86,"max":100}
            ]}
        """.trimIndent()
        val (path, desc) = route(json)
        println("G => $path : $desc")
        assertTrue("实际: $desc", desc.contains("CompositeCard"))
    }

    @Test
    fun `H section 分区卡`() {
        val json = """
            {"type":"section","title":"后台虚拟屏 · 测试报告","sections":[
              {"title":"结果","rows":[["通过","12"],["失败","3"]]}
            ]}
        """.trimIndent()
        val (path, desc) = route(json)
        println("H => $path : $desc")
        assertTrue("实际: $desc", desc.contains("SectionCard"))
    }

    // ════════════ 修复回归：残缺卡的三处容错 ════════════

    @Test
    fun `I1 card容器写stats键也认`() {
        val json = """
            {"type":"card","title":"后台虚拟屏 · 测试报告","stats":[
              {"type":"stat","label":"通过","value":"12/15"},
              {"type":"stat","label":"失败","value":"3"}
            ]}
        """.trimIndent()
        val rich = CardSdk.parseObj(JSONObject(json))
        val comp = rich as? QuroChatCard.CompositeCard
        assertTrue("stats 键的子卡应解析成 CompositeCard，实际 ${rich?.javaClass?.simpleName}", comp != null)
        assertEquals(2, comp!!.children.size)
    }

    @Test
    fun `I2 metric只有value没有unit也包壳`() {
        val json = """{"type":"metric","label":"通过率","value":86}"""
        val spec = parseCardSpec(json)!!
        val chart = spec.data as com.ai.assistance.quro.core.ui.card.spec.CardData.Chart
        assertEquals(1, chart.series.first().points.size)
        assertEquals(86f, chart.series.first().points.first())
    }

    @Test
    fun `I3 metric字符串值86%解析成86加单位`() {
        val json = """{"type":"metric","label":"热门指数","value":"86%"}"""
        val spec = parseCardSpec(json)!!
        val chart = spec.data as com.ai.assistance.quro.core.ui.card.spec.CardData.Chart
        val s0 = chart.series.first()
        assertEquals(86f, s0.points.first())
        assertEquals("%", s0.labels.firstOrNull())
    }

    @Test
    fun `I4 metric无chart数据时渲染器回读rawJson不出破折号`() {
        // data 壳存在但没有 series（模型只写了 title）——渲染器必须回读 rawJson
        val json = """{"type":"metric","title":"后台虚拟屏 · 测试报告","value":"12/15"}"""
        val spec = parseCardSpec(json)!!
        val renderer = com.ai.assistance.quro.core.ui.card.widgets.MetricCardRenderer()
        val state = renderer.createInitialState()
        val size = renderer.measure(spec, state, 600f, 2.75f)
        val backend = TextRecordingBackend()
        renderer.render(backend, spec, com.ai.assistance.quro.core.ui.card.registry.LayoutResult(size.width, size.height), state)
        assertTrue(
            "应直显原文 12/15，实际画了: ${backend.texts}",
            backend.texts.any { it.contains("12/15") },
        )
        assertTrue("不应画 -- 占位", backend.texts.none { it == "--" })
    }

    @Test
    fun `I5 metric无任何数据仍保底dash不崩`() {
        val json = """{"type":"metric"}"""
        val spec = parseCardSpec(json)!!
        val renderer = com.ai.assistance.quro.core.ui.card.widgets.MetricCardRenderer()
        val state = renderer.createInitialState()
        val size = renderer.measure(spec, state, 600f, 2.75f)
        val backend = TextRecordingBackend()
        renderer.render(backend, spec, com.ai.assistance.quro.core.ui.card.registry.LayoutResult(size.width, size.height), state)
        assertTrue("完全没数据时保底 --", backend.texts.any { it == "--" })
    }
}

/** 只录文本的假 backend（I4/I5 渲染器断言用）。 */
private class TextRecordingBackend : com.ai.assistance.quro.core.ui.card.render.RenderBackend {
    val texts = mutableListOf<String>()
    override fun drawRect(l: Float, t: Float, r: Float, b: Float, c: androidx.compose.ui.graphics.Color, radiusDp: Float) {}
    override fun drawText(text: String, x: Float, y: Float, c: androidx.compose.ui.graphics.Color, sizeSp: Float, weight: Int) { texts.add(text) }
    override fun drawLine(x1: Float, y1: Float, x2: Float, y2: Float, c: androidx.compose.ui.graphics.Color, widthDp: Float) {}
    override fun drawPath(p: androidx.compose.ui.graphics.Path, c: androidx.compose.ui.graphics.Color, widthDp: Float, fill: Boolean) {}
    override fun drawImage(bitmap: Any?, l: Float, t: Float, w: Float, h: Float) {}
    override fun saveLayer() {}
    override fun restore() {}
    override fun clip(l: Float, t: Float, r: Float, b: Float) {}
    override fun resolve(token: com.ai.assistance.quro.core.ui.card.spec.ColorToken): androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color.Gray
}
