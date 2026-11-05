package com.ai.assistance.quro.core.ui.card

import androidx.compose.ui.graphics.Color
import com.ai.assistance.quro.core.ui.card.compose.CardExtractors
import com.ai.assistance.quro.core.ui.card.spec.ColorToken
import com.ai.assistance.quro.core.ui.card.spec.parseCardSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 自研 custom 卡片（用户认可的「标准小卡片」：渐变底 + 环形 + 大数字）渲染内容回归。
 *
 * 新版架构把「画什么」交给 Compose 原语、把「提取什么内容」抽成纯 JVM 的 [CardExtractors]，
 * 单测不再需要录制型假 backend 回放像素——直接断言提取器产出的渐变/文字集合，
 * 因为 Compose 的 Text / Box(background=Brush) 是确定性地从这些集合渲染的。
 * 只要提取器返回空文字或提取不出渐变，Compose 就没东西可画（正是过去「内容消失 / 灰盒子」的根因）。
 */
class CustomCardRenderTest {

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

    private val gray: (ColorToken) -> Color = { _ -> Color.Gray }

    @Test
    fun `custom 卡片渐变与文字都被提取`() {
        val spec = parseCardSpec(standardCard)!!
        val grad = CardExtractors.extractGradientColors(spec.layout!!.props["gradient"], gray)
        assertTrue("custom 卡片必须提取出渐变（截图实证渐变消失）", grad != null)
        assertTrue("渐变应至少 2 色", (grad?.size ?: 0) >= 2)

        val texts = CardExtractors.collectTexts(spec.layout)
        val joined = texts.joinToString("|")
        assertTrue("应提取标题「🔥 热门指数」", "🔥 热门指数" in joined)
        assertTrue("应提取「实时热度」", "实时热度" in joined)
        assertTrue("应提取大数字「9,527」", "9,527" in joined)
        assertTrue("应提取「条讨论中」", "条讨论中" in joined)
    }

    @Test
    fun `gradient 写成逗号分隔字符串也必须提取出渐变`() {
        // 真机实证：模型高频把 gradient 写成 "#F98A8A,#F973C0" 字符串而非数组，
        // 旧逻辑只认 List 会导致渐变消失、卡片变灰盒子。
        val json = """
            {"type":"custom","layout":{"type":"card","gradient":"#F98A8A,#F973C0","angle":90,"radius":20,"padding":18,"children":[
              {"type":"text","text":"测试","fontSize":15,"color":"#FFFFFF"}
            ]}}
        """.trimIndent()
        val spec = parseCardSpec(json)!!
        assertEquals("custom", spec.type)
        val grad = CardExtractors.extractGradientColors(spec.layout!!.props["gradient"], gray)
        assertTrue("字符串形式渐变也必须提取出渐变背景", grad != null)
        assertTrue("渐变应至少 2 色", (grad?.size ?: 0) >= 2)
    }
}
