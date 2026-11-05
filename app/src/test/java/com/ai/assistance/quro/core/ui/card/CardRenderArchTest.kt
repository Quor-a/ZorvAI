package com.ai.assistance.quro.core.ui.card

import com.ai.assistance.quro.core.ui.card.compose.CardExtractors
import com.ai.assistance.quro.core.ui.card.registry.CardRegistry
import com.ai.assistance.quro.core.ui.card.spec.CardData
import com.ai.assistance.quro.core.ui.card.spec.CardTypes
import com.ai.assistance.quro.core.ui.card.spec.parseCardSpec
import com.ai.assistance.quro.core.ui.card.stream.JsonCompletion
import com.ai.assistance.quro.core.ui.card.stream.StreamAssembler
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2026-11 卡片渲染架构升级的回归测试（纯 JVM）。
 *
 * 盯的是四类「真机上表现为小卡片坏掉、但日志里毫无线索」的失效点：
 *
 * 1. **同义字段不识别** —— 组件库写法（`chart_type` / `series[].label,value` / `segments`）
 *    写进 `quro-card` 围栏后，解析成空序列 → 卡片空白但「什么都没报错」；
 * 2. **百分比两套值域** —— `value:0.86` 被当成 0.86% → 环形/进度条几乎不画；
 * 3. **未知 type 掉空白** —— 模型自造 type（`my_chart`）后整卡消失，用户以为功能坏了；
 * 4. **流式不闭合** —— 半截 JSON 补不齐 → 只能一直显示骨架，感知上「卡住了」。
 */
class CardRenderArchTest {

    // ── 1. 同义字段兼容 ──────────────────────────────────────────────

    @Test
    fun `组件库写法的 chart_type 与 label 加 value 序列能解析成折线点`() {
        val spec = parseCardSpec(
            """{"type":"line_chart","chart_type":"line","series":[{"label":"周一","value":3},{"label":"周二","value":5}]}""",
        )
        assertNotNull("应解析成功", spec)
        val chart = spec!!.data as CardData.Chart
        assertEquals("line", chart.chartType)
        assertEquals(2, chart.series.first().points.size)
        assertEquals(5f, chart.series.first().points[1], 0.001f)
        assertEquals("周二", chart.series.first().labels[1])
    }

    @Test
    fun `segments 写法能解析成饼图扇区`() {
        val spec = parseCardSpec(
            """{"type":"pie_chart","segments":[{"name":"工作","value":40},{"name":"生活","value":60}]}""",
        )
        assertNotNull(spec)
        val chart = spec!!.data as CardData.Chart
        assertEquals("pie", chart.chartType)
        assertEquals(listOf(40f, 60f), chart.series.first().points)
        assertEquals(listOf("工作", "生活"), chart.series.first().labels)
    }

    @Test
    fun `裸点数组配顶层 labels 也能出标签`() {
        val spec = parseCardSpec(
            """{"type":"bar_chart","points":[3,5,2],"labels":["一","二","三"]}""",
        )
        assertNotNull(spec)
        val chart = spec!!.data as CardData.Chart
        assertEquals("bar", chart.chartType)
        assertEquals(3, chart.series.first().points.size)
        assertEquals("二", chart.series.first().labels[1])
    }

    @Test
    fun `code 与 images 的类型自动补 mediaType`() {
        val code = parseCardSpec("""{"type":"code_block","code":"val a = 1","language":"kotlin"}""")
        assertNotNull(code)
        val codeMedia = code!!.data as CardData.Media
        assertEquals("code", codeMedia.mediaType)
        assertEquals("kotlin", codeMedia.lang)

        val imgs = parseCardSpec("""{"type":"image_group","urls":["https://a/1.png"]}""")
        assertNotNull(imgs)
        val imgMedia = imgs!!.data as CardData.Media
        assertEquals("image_group", imgMedia.mediaType)
        assertEquals(1, imgMedia.images.size)
    }

    @Test
    fun `status 的 progress 两套值域都归一成 0 到 1`() {
        val p100 = parseCardSpec("""{"type":"status","statusType":"progress","text":"装包","progress":60}""")
        val p1 = parseCardSpec("""{"type":"status","statusType":"progress","text":"装包","progress":0.6}""")
        assertEquals(0.6f, (p100!!.data as CardData.Status).progress, 0.001f)
        assertEquals(0.6f, (p1!!.data as CardData.Status).progress, 0.001f)
    }

    // ── 2. 百分比归一（环形 / 进度条） ─────────────────────────────

    @Test
    fun `percent 同时接受 0 到 100 与 0 到 1`() {
        assertEquals(0.86f, CardExtractors.percent(mapOf("value" to 86)), 0.001f)
        assertEquals(0.86f, CardExtractors.percent(mapOf("value" to 0.86)), 0.001f)
        assertEquals(1f, CardExtractors.percent(mapOf("value" to 1)), 0.001f)
        assertEquals(0.5f, CardExtractors.percent(mapOf("value" to 25, "max" to 50)), 0.001f)
        assertEquals(0f, CardExtractors.percent(mapOf("value" to -5)), 0.001f)
    }

    // ── 3. 未知 type 的推断与别名 ─────────────────────────────────

    @Test
    fun `别名与大小写连字符都能命中自有类型`() {
        assertEquals(CardTypes.BAR_CHART, CardTypes.canonical("Bar-Chart"))
        assertEquals(CardTypes.LINE_CHART, CardTypes.canonical("linechart"))
        assertEquals(CardTypes.PIE_CHART, CardTypes.canonical("donut"))
        // CardSdk 的名字绝不能被别名表吃掉
        assertTrue("chart 不应被本通道识别", !CardTypes.isCanonical("chart"))
        assertTrue("stat 不应被本通道识别", !CardTypes.isCanonical("stat"))
        assertTrue("timeline 不应被本通道识别", !CardTypes.isCanonical("timeline"))
    }

    @Test
    fun `type 自造时按 data 形状推断渲染器`() {
        CardModule.init()
        val spec = parseCardSpec(
            """{"type":"whatever_chart","data":{"kind":"chart","chartType":"pie","series":[{"name":"A","points":[1]},{"name":"B","points":[2]}]}}""",
        )
        assertNotNull(spec)
        val resolved = CardRegistry.resolveOrInfer(spec!!)
        assertNotNull("必须推断出渲染器，否则整卡消失", resolved)
        assertEquals(CardTypes.PIE_CHART, resolved!!.first)
    }

    @Test
    fun `custom 布局树按 layout 兜底推断`() {
        CardModule.init()
        val spec = parseCardSpec(
            """{"type":"zzz","layout":{"type":"card","children":[{"type":"text","text":"hi"}]}}""",
        )
        assertNotNull(spec)
        assertEquals(CardTypes.CUSTOM, CardTypes.inferType(spec!!))
        assertNotNull(CardRegistry.resolveOrInfer(spec))
    }

    // ── 4. 流式半截 JSON 补全 ─────────────────────────────────────

    @Test
    fun `半截 JSON 能补齐并解析出部分数据`() {
        val partial = """{"type":"line_chart","data":{"kind":"chart","chartType":"line","series":[{"points":[12,18"""
        val closed = JsonCompletion.close(partial)
        assertNotNull("半截 JSON 应能补齐", closed)
        val spec = parseCardSpec(closed!!)
        assertNotNull("补齐后应能解析", spec)
        val chart = spec!!.data as CardData.Chart
        assertEquals(2, chart.series.first().points.size)
    }

    @Test
    fun `字符串没收完时补引号`() {
        val closed = JsonCompletion.close("""{"type":"metric","title":"通过""")
        assertNotNull(closed)
        assertNotNull(JSONObject(closed!!))
    }

    @Test
    fun `末尾悬空的逗号与冒号会被砍掉`() {
        // 悬空逗号贴着 `}`（流式里极常见）
        assertNotNull(JsonCompletion.close("""{"type":"metric","data":{"kind":"chart",}}"""))
        // 末尾悬空冒号 → 砍掉后补壳
        assertNotNull(JsonCompletion.close("""{"type":"metric","data":"""))
        // 悬空逗号贴着 `]`
        assertNotNull(JsonCompletion.close("""{"type":"bar_chart","series":[1,2,"""))
    }

    @Test
    fun `流式切在逗号边界时不会因为空键整卡消失`() {
        // `{"a":1,` 的下一个 chunk 往往是键名的开引号，补引号后会变成空键 `{"a":1,""}`
        val closed = JsonCompletion.close("""{"type":"metric",""")
        assertNotNull("逗号边界必须能补出一个合法但内容较少的 card", closed)
        assertNotNull(JSONObject(closed!!))
        // 反过来也必须成立：数值数组切在逗号上也一样
        assertNotNull(JsonCompletion.close("""{"type":"bar_chart","series":[1,"""))
    }

    @Test
    fun `非 JSON 开头直接放弃`() {
        assertNull(JsonCompletion.close("hello"))
        assertNull(JsonCompletion.close("```json"))
    }

    @Test
    fun `流式装配在完整后回调一次并保留最新数据`() {
        var completed = 0
        var lastTitle = ""
        val assembler = StreamAssembler("s1", onComplete = { completed++; lastTitle = it.title })
        assembler.feed("""{"type":"metric","title":"在线""")
        assembler.feed("""","data":{"kind":"chart","chartType":"metric","series":[{"name":"在线","points":[42]}]}}""")
        assertEquals("只应在完整时回调一次", 1, completed)
        assertEquals("在线", lastTitle)
    }
}
