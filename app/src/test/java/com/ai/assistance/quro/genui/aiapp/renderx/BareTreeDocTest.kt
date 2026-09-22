package com.ai.assistance.quro.genui.aiapp.renderx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 裸嵌套节点树解析回归测试（FlatDoc 路径 ③）。
 *
 * 这是一次真实线上问题的回归锁：用户截图里 A2UI 通道把整篇 JSON 当**源码文本**画了出来，
 * 根因是模型输出的形状是「顶层直接一个 {type, children:[…]} 节点」——既没有 `root`+`components`
 * （本家扁平邻接表），也没有 `createSurface`/`updateComponents` 信封（官方协议），
 * 于是 ①② 两条路径都取不到，通道退化成原文画布。
 *
 * 本测试锁死三件事：能认这种形状、文本能取到（`value` 等价 `text`）、顶层标量属性不丢。
 */
class BareTreeDocTest {

    /** 截图同构的最小复现：顶层节点 + 内联 children + 文本写在 value + 样式写在顶层。 */
    private val screenshotLike = """
        {"type":"column","spacing":14,"padding":16,"children":[
          {"type":"card","title":"Zorv AI · 端侧智能助手","padding":20,"corner_radius":20,
           "backgroundColor":"#6C5CE7","children":[
             {"type":"text","value":"常驻你手机里的智能伙伴","style":"body"},
             {"type":"text","value":"我不是远端的问答机器人","style":"caption"}
          ]},
          {"type":"list","items":["日常生活管家：查天气、设闹钟","亲手操作手机：看屏幕、点按钮"]},
          {"type":"card","padding":14,"corner_radius":14,"children":[
             {"type":"text","value":"Z · 零延迟","style":"label","bold":true}
          ]}
        ]}
    """.trimIndent()

    @Test
    fun bareTreeParses() {
        val doc = FlatDoc.parse(screenshotLike, isYaml = false)
        assertNotNull("裸嵌套节点树必须解析成功（否则通道退化成原文画布）", doc)
        doc!!
        assertEquals("root", doc.root)
        assertEquals("column", doc.nodes["root"]!!.type)
        assertEquals(3, doc.nodes["root"]!!.kids.size)
    }

    @Test
    fun valueIsTreatedAsText() {
        val doc = FlatDoc.parse(screenshotLike, isYaml = false)!!
        val card = doc.nodes["root_0"]!!
        assertEquals("card", card.type)
        val body = doc.nodes[card.kids[0]]!!
        assertEquals("text", body.type)
        assertEquals("常驻你手机里的智能伙伴", body.text)
    }

    @Test
    fun topLevelTitleAndStylePropsPreserved() {
        val doc = FlatDoc.parse(screenshotLike, isYaml = false)!!
        val card = doc.nodes["root_0"]!!
        // title / corner_radius / backgroundColor 都是顶层标量，只读 props 会把它们全丢掉
        assertEquals("Zorv AI · 端侧智能助手", card.text)
        assertEquals("Zorv AI · 端侧智能助手", card.props["title"])
        assertEquals("20", card.props["corner_radius"])
        assertEquals("#6C5CE7", card.props["backgroundColor"])
        // 结构键不能混进 props，否则会被当成样式解析
        assertTrue("type 不应出现在 props", card.props["type"] == null)
        assertTrue("children 不应出现在 props", card.props["children"] == null)
    }

    @Test
    fun itemsStringArrayBecomesTextChildren() {
        val doc = FlatDoc.parse(screenshotLike, isYaml = false)!!
        val list = doc.nodes["root_1"]!!
        assertEquals("items 裸字符串数组应合成 text 子节点", 2, list.kids.size)
        assertEquals("日常生活管家：查天气、设闹钟", doc.nodes[list.kids[0]]!!.text)
        assertEquals("亲手操作手机：看屏幕、点按钮", doc.nodes[list.kids[1]]!!.text)
    }

    @Test
    fun topLevelArrayWrappedAsColumn() {
        val doc = FlatDoc.parse("""[{"type":"text","value":"A"},{"type":"text","value":"B"}]""", isYaml = false)
        assertNotNull(doc)
        assertEquals("column", doc!!.nodes["root"]!!.type)
        assertEquals(2, doc.nodes["root"]!!.kids.size)
        assertEquals("A", doc.nodes[doc.nodes["root"]!!.kids[0]]!!.text)
    }

    @Test
    fun officialEnvelopeNotHijacked() {
        // 路径 ① 优先：官方 createSurface 报文不能被路径 ③ 抢走
        val doc = FlatDoc.parse(
            """
            {"version":"v0.9","createSurface":{"surfaceId":"main"}}
            {"version":"v0.9","updateComponents":{"surfaceId":"main","components":[
              {"id":"root","component":"Column","children":["a"]},
              {"id":"a","component":"Text","text":"官方协议"}]}}
            """.trimIndent(),
            isYaml = false
        )
        assertNotNull(doc)
        assertEquals("官方协议", doc!!.nodes["a"]!!.text)
    }

    @Test
    fun flatAdjacencyNotHijacked() {
        // 路径 ② 优先：本家扁平邻接表不能被路径 ③ 抢走
        val doc = FlatDoc.parse(
            """{"root":"r","components":[{"id":"r","type":"column","children":["a"]},{"id":"a","type":"text","text":"邻接表"}]}""",
            isYaml = false
        )
        assertNotNull(doc)
        assertEquals("邻接表", doc!!.nodes["a"]!!.text)
    }

    @Test
    fun nonNodeJsonReturnsNull() {
        // 既不是节点树、也不是任何一种已知结构 → 必须返回 null，交给上层的「原文画布」兜底
        assertNull(FlatDoc.parse("""{"messages":[{"role":"user","content":"hi"}]}""", isYaml = false))
        assertNull(FlatDoc.parse("""{"foo":"bar"}""", isYaml = false))
        assertNull(FlatDoc.parse("not json at all", isYaml = false))
    }
}
