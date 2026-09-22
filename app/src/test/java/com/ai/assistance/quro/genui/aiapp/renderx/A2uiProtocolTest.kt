package com.ai.assistance.quro.genui.aiapp.renderx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A2UI 通道适配回归测试。
 *
 * 覆盖：① 官方 A2UI v0.9 JSONL ② 官方 v0.8 嵌套写法 ③ 对象数组 ④ 本家扁平邻接表（不回归）
 * ⑤ ZorvAI 自有方言（createSurface.root 为嵌套节点树）⑥ 垃圾输入 → null（走原文兜底）
 */
class A2uiProtocolTest {

    @Test
    fun v09Jsonl() {
        val doc = FlatDoc.parse(
            """
            {"version":"v0.9","createSurface":{"surfaceId":"main","catalogId":"zorv"}}
            {"version":"v0.9","updateComponents":{"surfaceId":"main","components":[
              {"id":"root","component":"Column","children":["h","d","b"]},
              {"id":"h","component":"Text","text":"今日状态","variant":"h2"},
              {"id":"d","component":"Card","child":"d1"},
              {"id":"d1","component":"Text","text":"生命 72 / 金币 120"},
              {"id":"b","component":"Button","child":"bt","action":{"event":{"name":"refresh"}}},
              {"id":"bt","component":"Text","text":"刷新"}]}}
            """.trimIndent(),
            isYaml = false
        )
        assertNotNull("v0.9 JSONL 必须解析成功", doc)
        doc!!
        assertEquals("root", doc.root)
        assertEquals(6, doc.nodes.size)
        assertEquals("column", doc.nodes["root"]!!.type)
        assertEquals(listOf("h", "d", "b"), doc.nodes["root"]!!.kids)
        assertEquals("heading", doc.nodes["h"]!!.type)      // variant:h2 → 标题
        assertEquals("今日状态", doc.nodes["h"]!!.text)
        assertEquals("card", doc.nodes["d"]!!.type)
        assertEquals(listOf("d1"), doc.nodes["d"]!!.kids)   // child 单引用
        assertEquals("button", doc.nodes["b"]!!.type)
        assertEquals("refresh", doc.nodes["b"]!!.props["action"])
    }

    @Test
    fun v08SurfaceUpdate() {
        val doc = FlatDoc.parse(
            """
            {"surfaceUpdate":{"surfaceId":"main","components":[
              {"id":"header","component":{"Text":{"text":{"literalString":"欢迎"},"usageHint":"h1"}}},
              {"id":"body","component":{"Column":{"children":{"explicitList":["header","submit"]}}}},
              {"id":"submit","component":{"Button":{"child":"submit-text","action":{"name":"go"}}}},
              {"id":"submit-text","component":{"Text":{"text":{"literalString":"提交"}}}}]}}
            {"beginRendering":{"surfaceId":"main","root":"body"}}
            """.trimIndent(),
            isYaml = false
        )
        assertNotNull("v0.8 必须解析成功（中文模型也常吐这个形态）", doc)
        doc!!
        assertEquals("body", doc.root)
        assertEquals(listOf("header", "submit"), doc.nodes["body"]!!.kids)
        assertEquals("heading", doc.nodes["header"]!!.type)   // usageHint:h1
        assertEquals("欢迎", doc.nodes["header"]!!.text)      // literalString 解包
        assertEquals("button", doc.nodes["submit"]!!.type)
        assertEquals("go", doc.nodes["submit"]!!.props["action"])
        assertEquals("提交", doc.nodes["submit-text"]!!.text)
    }

    @Test
    fun messageArrayWithDataModel() {
        val doc = FlatDoc.parse(
            """
            [
              {"version":"v0.9","createSurface":{"surfaceId":"main","catalogId":"zorv"}},
              {"version":"v0.9","updateComponents":{"surfaceId":"main","components":[
                {"id":"root","component":"Column","children":["t"]},
                {"id":"t","component":"Text","text":{"path":"/msg"}}]}},
              {"version":"v0.9","updateDataModel":{"surfaceId":"main","path":"/","value":{"msg":"指针取到了"}}}
            ]
            """.trimIndent(),
            isYaml = false
        )
        assertNotNull(doc)
        // {"path":"/msg"} 应从 updateDataModel 解出真实文本，而不是把 "/msg" 当文字
        assertEquals("指针取到了", doc!!.nodes["t"]!!.text)
    }

    @Test
    fun nativeFlatFormatStillWorks() {
        val doc = FlatDoc.parse(
            """{"title":"面板","root":"col","components":{
                 "col":{"t":"column","children":["h","p"]},
                 "h":{"t":"heading","text":"标题"},
                 "p":{"t":"progress","props":{"value":"0.5"}}}}""",
            isYaml = false
        )
        assertNotNull("本家扁平邻接表不能回归", doc)
        doc!!
        assertEquals("col", doc.root)
        assertEquals("heading", doc.nodes["h"]!!.type)
        assertEquals("progress", doc.nodes["p"]!!.type)
    }

    @Test
    fun nestedCreateSurfaceRoot() {
        val doc = FlatDoc.parse(
            """{"type":"createSurface","surface":"main","root":{
                 "type":"card","children":[
                   {"type":"heading","text":"嵌套根"},
                   {"type":"text","text":"正文"}]}}""",
            isYaml = false
        )
        assertNotNull("ZorvAI 自有方言（root 为节点树）必须解析成功", doc)
        doc!!
        assertEquals("card", doc.nodes[doc.root]!!.type)
        assertEquals(2, doc.nodes[doc.root]!!.kids.size)
    }

    @Test
    fun unknownTypeWithKidsBecomesContainer() {
        val doc = FlatDoc.parse(
            """{"root":"col","components":{
                 "col":{"t":"column","children":["x"]},
                 "x":{"t":"weird_widget","children":["y"]},
                 "y":{"t":"text","text":"子内容还在"}}}""",
            isYaml = false
        )
        assertNotNull(doc)
        // 未知类型但有子节点 → 当容器，内容不丢
        assertEquals("column", doc!!.nodes["x"]!!.type)
        assertEquals(listOf("y"), doc.nodes["x"]!!.kids)
    }

    @Test
    fun garbageReturnsNull() {
        assertNull("纯文本不该被误判成 A2UI", FlatDoc.parse("这是一段普通文字，没有任何结构", isYaml = false))
        assertNull(FlatDoc.parse("""{"hello":"world"}""", isYaml = false))
        assertTrue("markdown 也不该被误判", FlatDoc.parse("# 标题\n正文", isYaml = false) == null)
    }
}
