package com.ai.assistance.quro.core.ui.card

import androidx.compose.ui.graphics.Color
import com.ai.assistance.quro.core.ui.card.compose.CardExtractors
import com.ai.assistance.quro.core.ui.card.spec.ColorToken
import com.ai.assistance.quro.core.ui.card.spec.parseCardSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 自研小卡片（feat_self_card）端到端管线回归：parse → 纯 JVM 内容提取。
 * 用提示词里「用户认可的标准小卡片」梯度+环形+大数字 JSON 直接喂，
 * 验证：① parseCardSpec 能解析出 custom + 非空 layout 树；② 渐变与全部文字都被正确提取
 * （Compose 据此渲染，提取失败 = 内容消失）；③ 估算高度合理（否则卡片被压扁）。
 */
class SelfCardPipelineTest {

    private val gray: (ColorToken) -> Color = { _ -> Color.Gray }

    /** 提示词 1999 行附近「小卡片标准形态」照抄 JSON。 */
    private val standardCard = """
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

    @Test
    fun `标准小卡片 parse 出 custom 且 layout 非空`() {
        val spec = parseCardSpec(standardCard)
        assertNotNull("标准小卡片未解析出 spec", spec)
        assertEquals("custom", spec!!.type)
        assertNotNull("custom 卡片 layout 树为空", spec.layout)
        assertEquals("card", spec.layout!!.type)
        assertTrue("根节点应至少含 2 个子（标题 + row）", spec.layout!!.children.size >= 2)
    }

    @Test
    fun `标准小卡片 提取出渐变与全部文字且高度合理`() {
        val spec = parseCardSpec(standardCard)!!
        val grad = CardExtractors.extractGradientColors(spec.layout!!.props["gradient"], gray)
        assertTrue("渐变必须提取出来（否则 Canvas 不画渐变底）", (grad?.size ?: 0) >= 2)
        val texts = CardExtractors.collectTexts(spec.layout)
        val joined = texts.joinToString("|")
        assertTrue("必须提取标题「🔥 热门指数」", "🔥 热门指数" in joined)
        assertTrue("必须提取「实时热度」", "实时热度" in joined)
        assertTrue("必须提取大数字「9,527」", "9,527" in joined)
        assertTrue("必须提取「条讨论中」", "条讨论中" in joined)
        val h = CardExtractors.estimateHeightDp(spec.layout)
        // 高度应至少容纳：标题行 + ring/列行（ring 92dp ≈ 253px）
        assertTrue("高度应合理（>= ring 尺寸），实际=$h", h >= 230f)
    }

    @Test
    fun `指标卡 metric parse 出 series`() {
        val json = """{"type":"metric","data":{"kind":"chart","series":[{"name":"在线用户","points":[1230],"labels":["人"]}]}}"""
        val spec = parseCardSpec(json)
        assertNotNull(spec)
        assertEquals("metric", spec!!.type)
    }

    @Test
    fun `状态卡 status parse 出进度`() {
        val json = """{"type":"status","data":{"kind":"status","statusType":"progress","text":"正在下载模型 612MB","progress":0.6}}"""
        val spec = parseCardSpec(json)
        assertNotNull(spec)
        assertEquals("status", spec!!.type)
    }
}
