package com.ai.assistance.quro.core.ui.card.spec

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 小卡片解析容错回归 —— 2026-11-01 真机截图实证的模型乱写格式。
 *
 * 截图里模型输出（没有任何围栏、没有 type、字段全平铺）：
 * ```
 * quro-card { "title": "Zorv AI 健康度", "value": 98, "unit": "/100",
 *             "trend": "↑ 良好", "trend_color": "#4caf50", "icon": "heart", ...}
 * ```
 * 旧逻辑缺 type 直接返回 null → 整个卡片被跳过 → 用户认为「小卡片彻底坏了」。
 * 修复后：缺 type 按字段形状推断（value+unit/trend → metric），平铺字段自动包进 data 壳。
 */
class CardSpecLenientParseTest {

    /** 截图里的原始格式（一字不改，只去外层 quro-card 前缀）。 */
    @Test
    fun `截图平铺格式 缺type 也必须解析成 metric`() {
        val json = """
            {"title": "Zorv AI 健康度", "value": 98, "unit": "/100",
             "trend": "↑ 良好", "trend_color": "#4caf50", "icon": "heart",
             "description": "所有核心模块运行正常"}
        """.trimIndent()
        val spec = parseCardSpec(json)
        assertNotNull("平铺格式不应被整卡丢弃", spec)
        assertEquals("metric", spec!!.type)
        // 包壳后 renderer 读得到 series[0]：name=指标名、points[0]=数值、labels[0]=单位
        val data = spec.data as CardData.Chart
        assertEquals("metric", data.chartType)
        assertEquals("Zorv AI 健康度", data.series.first().name)
        assertEquals(98f, data.series.first().points.first(), 0.001f)
        assertEquals("/100", data.series.first().labels.first())
    }

    @Test
    fun `type给了但data壳没给 字段平铺在外的也包壳`() {
        val json = """{"type":"status","statusType":"progress","text":"下载中","progress":0.6}"""
        val spec = parseCardSpec(json)
        assertNotNull(spec)
        val data = spec!!.data as CardData.Status
        assertEquals("progress", data.statusType)
        assertEquals("下载中", data.text)
        assertEquals(0.6f, data.progress, 0.001f)
    }

    @Test
    fun `headers_rows 平铺推断为 table`() {
        val json = """{"headers":["A","B"],"rows":[["1","2"]]}"""
        val spec = parseCardSpec(json)
        assertNotNull(spec)
        assertEquals("table", spec!!.type)
        val data = spec.data as CardData.Media
        assertEquals(listOf("A", "B"), data.headers)
    }

    @Test
    fun `buttons 平铺推断为 button_group`() {
        val json = """{"buttons":[{"type":"callback","name":"确定"},{"type":"callback","name":"取消"}]}"""
        val spec = parseCardSpec(json)
        assertNotNull(spec)
        assertEquals("button_group", spec!!.type)
        val data = spec.data as CardData.Form
        assertEquals(2, data.buttons.size)
    }

    @Test
    fun `标准写法不受影响 type+data 原样通过`() {
        val json = """{"type":"metric","data":{"kind":"chart","chartType":"metric",
            "series":[{"name":"在线","points":[1230],"labels":["人"]}]}}"""
        val spec = parseCardSpec(json)
        assertNotNull(spec)
        assertEquals("metric", spec!!.type)
        val data = spec.data as CardData.Chart
        assertEquals("在线", data.series.first().name)
    }

    @Test
    fun `彻底不像任何卡片形状的 仍然返回null 不做无据渲染`() {
        val json = """{"total":3,"rows":[]}""" // rows 有 → 会被当成 table（headers 缺失但 rows 命中）
        // 注意：rows 命中是我们的明确规则（table），这里测的是真正无字段的
        val nothing = """{"foo":"bar","n":1}"""
        assertTrue("完全无卡片形状的字段应返回 null", parseCardSpec(nothing) == null)
        // rows 单独出现按规则判 table（合法行为），只断言它有 type
        assertEquals("table", parseCardSpec(json)!!.type)
    }
}
