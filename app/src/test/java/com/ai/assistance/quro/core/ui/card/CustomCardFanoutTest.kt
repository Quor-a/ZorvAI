package com.ai.assistance.quro.core.ui.card

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import com.ai.assistance.quro.core.ui.card.render.RenderBackend
import com.ai.assistance.quro.core.ui.card.spec.ColorToken
import com.ai.assistance.quro.core.ui.card.spec.parseCardSpec
import com.ai.assistance.quro.core.ui.card.widgets.CustomCardRenderer
import com.ai.assistance.quro.core.ui.card.widgets.CustomCardState
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「图1 那种小卡片」的**渲染能力实测**。
 *
 * 用户拍板的正确形态：渐变底 + 左侧环形进度（86%）+ 右侧标题/大数字（9,527）/副标题，
 * 全部由 `type:"custom"` 的 layout 树自绘，不用 ui_widget 富卡片。
 *
 * 之前不管怎么改提示词都没用，所以先把「渲染器到底画不画得出来」这件事钉死：
 * 只要这里任何一条断言失败，说明问题在渲染层而不是提示词，改提示词再多也没意义。
 */
class CustomCardFanoutTest {

    private class Rec : RenderBackend {
        var gradientRects = 0
        var arcs = 0
        var rects = 0
        val texts = mutableListOf<String>()
        override fun drawRect(left: Float, top: Float, right: Float, bottom: Float, color: Color, radiusDp: Float) { rects++ }
        override fun drawText(text: String, x: Float, y: Float, color: Color, sizeSp: Float, weight: Int) { texts.add(text) }
        override fun drawLine(x1: Float, y1: Float, x2: Float, y2: Float, color: Color, widthDp: Float) {}
        override fun drawPath(path: Path, color: Color, widthDp: Float, fill: Boolean) {}
        override fun drawImage(bitmap: Any?, left: Float, top: Float, width: Float, height: Float) {}
        override fun saveLayer() {}
        override fun restore() {}
        override fun clip(left: Float, top: Float, right: Float, bottom: Float) {}
        override fun resolve(token: ColorToken): Color = Color.Gray
        override fun drawGradientRect(
            left: Float, top: Float, right: Float, bottom: Float,
            colors: List<Color>, angleDeg: Float, radiusDp: Float,
        ) { gradientRects++ }
        override fun drawArc(
            left: Float, top: Float, right: Float, bottom: Float,
            startDeg: Float, sweepDeg: Float, color: Color, strokeWidthDp: Float,
        ) { arcs++ }
    }

    /** 用户截图里那张卡的等价布局（AI 应该写成的样子）。 */
    private val fanoutJson = """
    {"type":"custom","layout":{"type":"card","gradient":["#F98A8A","#F973C0"],"angle":90,"radius":20,"padding":18,"gap":14,"children":[
      {"type":"text","text":"🔥 热门指数","fontSize":15,"color":"#FFFFFF","fontWeight":600},
      {"type":"row","gap":18,"children":[
        {"type":"ring","value":86,"size":92,"stroke":10,"text":"86%","textSize":20,"color":"#FFFFFF"},
        {"type":"column","gap":6,"weight":1,"children":[
          {"type":"text","text":"实时热度","fontSize":13,"color":"#FFE9E9"},
          {"type":"text","text":"9,527","fontSize":40,"color":"#FFFFFF","fontWeight":700},
          {"type":"text","text":"条讨论中","fontSize":13,"color":"#FFE9E9"}
        ]}
      ]}
    ]}}
    """.trimIndent()

    private fun render(json: String): Pair<Rec, Size> {
        val parsed = parseCardSpec(json)
        assertNotNull("spec 应解析成功", parsed)
        val spec = parsed!!
        val r = CustomCardRenderer()
        val st = r.createInitialState() as CustomCardState
        val size = r.measure(spec, st, 360f, 2.75f)
        val be = Rec()
        // 入场动画相位推到 1：环形的扫角与数字动画都依赖它，不推就是 0 度
        st.onFrame(1f)
        r.render(be, spec, r.layout(spec, st, size, 2.75f), st)
        return be to size
    }

    @Test
    fun 渐变底真的会画出来() {
        val (be, _) = render(fanoutJson)
        assertTrue(
            "没有调用 drawGradientRect —— 渐变底没画出来。这正是「以前是渐变、现在没了」的渲染层原因",
            be.gradientRects > 0,
        )
    }

    @Test
    fun 环形进度真的会画出来() {
        val (be, _) = render(fanoutJson)
        assertTrue("没有 drawArc —— 环形进度没画", be.arcs >= 1)
    }

    @Test
    fun 大数字与三段文字都画出来() {
        val (be, _) = render(fanoutJson)
        val joined = be.texts.joinToString("|")
        assertTrue("缺少标题「热门指数」，实际画出=$joined", be.texts.any { it.contains("热门指数") })
        assertTrue("缺少大数字「9,527」，实际画出=$joined", be.texts.any { it.contains("9,527") })
        assertTrue("缺少环形中心「86%」，实际画出=$joined", be.texts.any { it.contains("86%") })
        assertTrue("缺少「条讨论中」，实际画出=$joined", be.texts.any { it.contains("条讨论中") })
    }

    @Test
    fun 高度足够容纳内容() {
        val (_, size) = render(fanoutJson)
        // 有标题 + 一行（环形 92dp）+ padding，高度至少要能装下 92dp*2.75≈253px 的可视区
        assertTrue("测得高度 ${size.height} 太小，内容会被压扁/裁掉", size.height > 240f)
    }

    @Test
    fun 从围栏文本解析也走同一条路() {
        // CardFence 给的是 ```quro-card 里的裸 JSON，必须能直接进 parseCardSpec
        val json = """
        {"type":"custom","layout":{"type":"card","gradient":["#F98A8A","#F973C0"],"children":[
          {"type":"ring","value":86,"size":80,"text":"86%"}
        ]}}
        """.trimIndent()
        val spec = parseCardSpec(json)
        assertNotNull("围栏 JSON 必须能解析成 spec", spec)
        assertNotNull("layout 必须解析出来，否则渲染成空盒子", spec!!.layout)
    }
}
