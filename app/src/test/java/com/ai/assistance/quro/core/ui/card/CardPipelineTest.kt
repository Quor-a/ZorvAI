package com.ai.assistance.quro.core.ui.card

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import com.ai.assistance.quro.core.ui.card.registry.CardRenderer
import com.ai.assistance.quro.core.ui.card.registry.CardRegistry
import com.ai.assistance.quro.core.ui.card.registry.CardState
import com.ai.assistance.quro.core.ui.card.registry.LayoutResult
import com.ai.assistance.quro.core.ui.card.render.RenderBackend
import com.ai.assistance.quro.core.ui.card.spec.CardData
import com.ai.assistance.quro.core.ui.card.spec.ColorToken
import com.ai.assistance.quro.core.ui.card.spec.parseCardSpec
import com.ai.assistance.quro.core.ui.card.widgets.ButtonGroupRenderer
import com.ai.assistance.quro.core.ui.card.widgets.LineChartRenderer
import com.ai.assistance.quro.core.ui.card.widgets.SkeletonRenderer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 自研卡片渲染管线 JVM 单测（不依赖真机/Android 运行环境）：
 * 验证 ①parseCardSpec 反序列化出正确 CardData；②各渲染器 measure 出非零高度；
 * ③render 在假 backend 上真的下了绘制指令（不是空操作）；④按钮组命中测试可用。
 *
 * 注：渲染器只调用 [RenderBackend] 接口方法，故用录制型假 backend 即可验证。
 */
private class RecordingBackend : RenderBackend {
    var rects = 0
    var texts = 0
    var lines = 0
    var paths = 0
    override fun drawRect(l: Float, t: Float, r: Float, b: Float, c: Color, radiusDp: Float) { rects++ }
    override fun drawText(text: String, x: Float, y: Float, c: Color, sizeSp: Float, weight: Int) { texts++ }
    override fun drawLine(x1: Float, y1: Float, x2: Float, y2: Float, c: Color, widthDp: Float) { lines++ }
    override fun drawPath(p: Path, c: Color, widthDp: Float, fill: Boolean) { paths++ }
    override fun drawImage(bitmap: Any?, l: Float, t: Float, w: Float, h: Float) {}
    override fun saveLayer() {}
    override fun restore() {}
    override fun clip(l: Float, t: Float, r: Float, b: Float) {}
    override fun resolve(token: ColorToken): Color = Color.Gray
}

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
    fun registry_resolveAndMeasure_nonZeroHeight() {
        CardModule.init()
        val spec = parseCardSpec("""{"type":"line_chart","data":{"kind":"chart","series":[{"name":"x","points":[0.2,0.4,0.6,0.8]}]}}""")!!
        val r = CardRegistry.resolveOrNull("line_chart") as CardRenderer<CardState>
        val size = r.measure(spec, r.createInitialState(), 360f)
        assertTrue("measure 应给出非零高度", size.height > 0f)
        assertEquals(360f, size.width)
    }

    @Test
    fun lineChart_render_drawsPathAndAxes() {
        val r = LineChartRenderer()
        val spec = parseCardSpec("""{"type":"line_chart","data":{"kind":"chart","series":[{"name":"x","color":"primary","points":[0.2,0.5,0.8]}]}}""")!!
        val state = r.createInitialState()
        val size = r.measure(spec, state, 360f)
        val layout = r.layout(spec, state, size)
        val b = RecordingBackend()
        r.render(b, spec, layout, state)
        assertTrue("折线图应绘制折线(polyline path)", b.paths >= 1)
        assertTrue("折线图应绘制坐标轴(line >= 2)", b.lines >= 2)
    }

    @Test
    fun buttonGroup_render_drawsRectsAndLabels_andHitTest() {
        val r = ButtonGroupRenderer()
        val spec = parseCardSpec("""{"type":"button_group","data":{"kind":"form","buttons":[{"label":"确定","action":{"type":"callback","name":"ok"}},{"label":"取消","action":{"type":"callback","name":"cancel"}}]}}""")!!
        val state = r.createInitialState()
        val size = r.measure(spec, state, 360f)
        val layout = r.layout(spec, state, size)
        val b = RecordingBackend()
        r.render(b, spec, layout, state)
        assertTrue("按钮组应绘制按钮矩形(>=2)", b.rects >= 2)
        assertTrue("按钮组应绘制按钮文字(>=2)", b.texts >= 2)
        // 点中第一个按钮中心应命中，且命中序号=0
        val box = layout.boxes.first()
        val hit = r.hitTest(Offset((box.left + box.right) / 2f, (box.top + box.bottom) / 2f), layout, state)
        assertNotNull(hit)
        assertEquals(0, hit!!.actionIndex)
    }

    @Test
    fun skeleton_render_drawsBars() {
        val r = SkeletonRenderer()
        val spec = parseCardSpec("""{"type":"skeleton","data":{"kind":"status","statusType":"skeleton"}}""")!!
        val state = r.createInitialState()
        val size = r.measure(spec, state, 360f)
        val layout = r.layout(spec, state, size)
        val b = RecordingBackend()
        r.render(b, spec, layout, state)
        assertTrue("骨架卡应绘制占位条(>=3)", b.rects >= 3)
    }
}
