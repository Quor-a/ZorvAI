package com.ai.assistance.quro.core.ui.card

import androidx.compose.ui.graphics.Color
import com.ai.assistance.quro.core.ui.card.compose.CardExtractors
import com.ai.assistance.quro.core.ui.card.spec.ColorToken
import com.ai.assistance.quro.core.ui.card.spec.LayoutNode
import com.ai.assistance.quro.core.ui.card.spec.parseCardSpec
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「图1 那种小卡片」的**渲染内容实测**。
 *
 * 用户拍板的正确形态：渐变底 + 左侧环形进度（86%）+ 右侧标题 / 大数字（9,527）/ 副标题，
 * 全部由 `type:"custom"` 的 layout 树自绘，不用 ui_widget 富卡片。
 *
 * 之前不管怎么改提示词都没用，所以先把「渲染层到底画不画得出来」这件事钉死：
 * 用纯 JVM 的 [CardExtractors] 断言渐变 / 环形节点 / 文字 / 高度都被正确提取——
 * 只要这些提取失败，说明问题在渲染层而不是提示词，改提示词再多也没意义。
 */
class CustomCardFanoutTest {

    private val gray: (ColorToken) -> Color = { _ -> Color.Gray }

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

    private fun spec(json: String) = parseCardSpec(json)!!

    private fun hasType(node: LayoutNode?, type: String): Boolean {
        if (node == null) return false
        if (node.type == type) return true
        return node.children.any { hasType(it, type) }
    }

    @Test
    fun 渐变底真的会提取() {
        val s = spec(fanoutJson)
        val grad = CardExtractors.extractGradientColors(s.layout!!.props["gradient"], gray)
        assertTrue(
            "没有提取出渐变——渐变底不会画。这正是「以前是渐变、现在没了」的渲染层原因",
            (grad?.size ?: 0) > 0,
        )
    }

    @Test
    fun 环形进度节点真的存在() {
        val s = spec(fanoutJson)
        assertTrue("layout 树必须含有 ring 节点，否则环形进度不会画", hasType(s.layout, "ring"))
    }

    @Test
    fun 大数字与三段文字都提取出来() {
        val s = spec(fanoutJson)
        val texts = CardExtractors.collectTexts(s.layout)
        val joined = texts.joinToString("|")
        assertTrue("缺少标题「热门指数」，实际提取=$joined", texts.any { it.contains("热门指数") })
        assertTrue("缺少大数字「9,527」，实际提取=$joined", texts.any { it.contains("9,527") })
        assertTrue("缺少环形中心「86%」，实际提取=$joined", texts.any { it.contains("86%") })
        assertTrue("缺少「条讨论中」，实际提取=$joined", texts.any { it.contains("条讨论中") })
    }

    @Test
    fun 高度足够容纳内容() {
        val s = spec(fanoutJson)
        // 有标题 + 一行（环形 92dp）+ padding，高度至少要能装下 92dp 的可视区
        val h = CardExtractors.estimateHeightDp(s.layout)
        assertTrue("估算高度 $h 太小，内容会被压扁 / 裁掉", h > 240f)
    }

    @Test
    fun 从围栏文本解析也走同一条路() {
        // CardFence 给的是 ```quro-card 里的裸 JSON，必须能直接进 parseCardSpec
        val json = """
        {"type":"custom","layout":{"type":"card","gradient":["#F98A8A","#F973C0"],"children":[
          {"type":"ring","value":86,"size":80,"text":"86%"}
        ]}}
        """.trimIndent()
        val s = spec(json)
        assertNotNull("围栏 JSON 必须能解析成 spec", s)
        assertNotNull("layout 必须解析出来，否则渲染成空盒子", s!!.layout)
        assertTrue("layout 应含 ring", hasType(s.layout, "ring"))
    }
}
