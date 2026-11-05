package com.ai.assistance.quro.core.ui.card

import com.ai.assistance.quro.core.ui.card.compose.CardExtractors
import com.ai.assistance.quro.core.ui.card.registry.CardRegistry
import com.ai.assistance.quro.core.ui.card.spec.CardData
import com.ai.assistance.quro.core.ui.card.spec.CardTypes
import com.ai.assistance.quro.core.ui.card.spec.parseCardSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 自研卡片渲染管线 JVM 单测（不依赖真机 / Android 运行环境）：
 * 验证 ①parseCardSpec 反序列化出正确 CardData；②7 种类型都注册了 Composable 渲染函数；
 * ③未注册类型不在白名单（应降级 Markdown，不崩溃）；④按钮组按序号解析动作（替代旧 hitTest）。
 *
 * 注：新架构下离散元素由 Compose 原语排版、连续几何走局部 Canvas，无法在纯 JVM 回放像素，
 * 故改用纯 JVM 的 [CardExtractors] 与注册表契约做回归断言。
 */
class CardPipelineTest {

    @Test
    fun parseCardSpec_chart_producesChartData() {
        val json = """{"type":"line_chart","data":{"kind":"chart","chartType":"line","series":[{"name":"CPU","color":"primary","points":[0.1,0.5,0.9]}]}}"""
        val spec = parseCardSpec(json)
        assertNotNull(spec)
        assertEquals("line_chart", spec!!.type)
        assertTrue(spec.data is CardData.Chart)
        assertEquals(3, (spec.data as CardData.Chart).series.first().points.size)
    }

    @Test
    fun parseCardSpec_form_producesFormData() {
        val json = """{"type":"button_group","data":{"kind":"form","formType":"button_group","buttons":[{"label":"确定","action":{"type":"callback","name":"ok"}},{"label":"取消","action":{"type":"callback","name":"cancel"}}]}}"""
        val spec = parseCardSpec(json)
        assertNotNull(spec)
        assertTrue(spec!!.data is CardData.Form)
        assertEquals(2, (spec.data as CardData.Form).buttons.size)
    }

    @Test
    fun parseCardSpec_statusAndInvalid() {
        val status = parseCardSpec("""{"type":"skeleton","data":{"kind":"status","statusType":"skeleton"}}""")
        assertNotNull(status)
        assertTrue(status!!.data is CardData.Status)

        assertNull("非法 JSON 应降级为 null", parseCardSpec("not json {{{"))
        assertNull("缺 type 应降级为 null", parseCardSpec("""{"foo":1}"""))
    }

    @Test
    fun registry_allCanonicalTypesRegistered() {
        CardModule.init()
        val types = CardModule.registeredTypes()
        listOf("metric", "line_chart", "button_group", "skeleton", "table", "status", "custom").forEach {
            assertTrue("类型 $it 必须注册", it in types)
        }
        // 扩展后的自有类型（图表族 / 媒体族 / 表单族）必须全在册且都有渲染函数
        listOf("bar_chart", "pie_chart", "area_chart", "sparkline_chart", "media_card", "code_block", "image_group", "form_card")
            .forEach { assertTrue("扩展类型 $it 必须注册", it in types) }
        // 注册集合必须与 CardTypes.CANONICAL_TYPES 完全一致（加类型时两边一起改，防漏注册）
        assertEquals(
            "注册类型应与 CardTypes.CANONICAL_TYPES 一致",
            CardTypes.CANONICAL_TYPES,
            types,
        )
        // 注册的类型必须能取到渲染函数（否则白名单命中却无渲染器 → 崩溃）
        types.forEach { t ->
            assertNotNull("类型 $t 必须有渲染函数", CardRegistry.resolveOrNull(t))
        }
        // 别名必须都指向已注册类型
        CardRegistry.registeredAliases().forEach { (alias, target) ->
            assertTrue("别名 $alias 指向了未注册的 $target", target in types)
            assertNotNull("别名 $alias 必须能解析出渲染器", CardRegistry.resolveOrNull(alias))
        }
    }

    @Test
    fun registry_unknownType_isNotWhitelisted() {
        CardModule.init()
        assertTrue("已知 metric 应在白名单", CardRegistry.isWhitelisted("metric"))
        // 组件库名册类型（误写进 quro-card）不应在白名单，应降级到富卡片层
        assertTrue("未知类型不应在白名单", !CardRegistry.isWhitelisted("composite"))
        assertTrue("未知类型不应有渲染函数", CardRegistry.resolveOrNull("composite") == null)
    }

    @Test
    fun registry_infersRendererFromDataShape() {
        CardModule.init()
        // type 自造 + 数据形状明确 → 三级解析按 data 推断，不能落空
        val spec = parseCardSpec(
            """{"type":"my_own_thing","data":{"kind":"chart","chartType":"bar","series":[{"name":"A","points":[1,2]}]}}""",
        )
        assertNotNull(spec)
        val resolved = CardRegistry.resolveOrInfer(spec!!)
        assertNotNull("未知 type 应按 data 形状推断出渲染器", resolved)
        assertEquals("bar_chart", resolved!!.first)
    }

    @Test
    fun buttonGroup_actionResolvesByIndex() {
        val json = """{"type":"button_group","data":{"kind":"form","formType":"button_group","buttons":[{"label":"确定","action":{"type":"callback","name":"ok"}},{"label":"取消","action":{"type":"callback","name":"cancel"}}]}}"""
        val spec = parseCardSpec(json)!!
        val a0 = CardExtractors.resolveButtonAction(spec, 0)
        val a1 = CardExtractors.resolveButtonAction(spec, 1)
        assertNotNull(a0)
        assertNotNull(a1)
        assertEquals("callback", a0!!.type)
        assertEquals("ok", a0.name)
        assertEquals("cancel", a1!!.name)
    }

    @Test
    fun table_parse_mediaHeadersAndRows() {
        val json = """{"type":"table","data":{"kind":"media","mediaType":"table","headers":["列1","列2"],"rows":[["a","b"],["c","d"]]}}"""
        val spec = parseCardSpec(json)!!
        assertTrue("表格卡应解析为 CardData.Media", spec.data is CardData.Media)
        val media = spec.data as CardData.Media
        assertEquals(2, media.headers.size)
        assertEquals(2, media.rows.size)
    }

    @Test
    fun status_parse_progressAndError() {
        val p = parseCardSpec("""{"type":"status","data":{"kind":"status","statusType":"progress","text":"处理中","progress":0.5}}""")!!
        val sp = p.data as CardData.Status
        assertEquals("progress", sp.statusType)
        assertEquals(0.5f, sp.progress)
        val e = parseCardSpec("""{"type":"status","data":{"kind":"status","statusType":"error","text":"失败","reason":"网络错误","retryable":true}}""")!!
        val se = e.data as CardData.Status
        assertEquals("error", se.statusType)
        assertEquals("网络错误", se.reason)
        assertTrue(se.retryable)
    }

    @Test
    fun skeleton_parse() {
        val spec = parseCardSpec("""{"type":"skeleton","data":{"kind":"status","statusType":"skeleton"}}""")!!
        assertTrue(spec.data is CardData.Status)
        assertEquals("skeleton", (spec.data as CardData.Status).statusType)
    }
}
