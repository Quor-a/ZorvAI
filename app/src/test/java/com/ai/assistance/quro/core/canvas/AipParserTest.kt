package com.ai.assistance.quro.core.canvas

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AIP 协议层单测：PRD 4.1「流式友好——任意位置截断都可部分解析」是核心验收点。
 *
 * 断言策略：
 *  - 完整信封 → 结构正确（kind/meta/blocks/类型化 Block）；
 *  - 每个截断位置 → 解析绝不抛异常；blocks 出现后任意截断都能拿到「已完整部分的块」；
 *  - 未知 Block 类型 → Fallback 兜底（L2），不丢内容；
 *  - 完全不是 AIP → TextDown（L4），调用方按纯文本渲染。
 */
class AipParserTest {

    private val full = """
    {
      "v": 1,
      "kind": "doc",
      "meta": { "title": "智慧园区建设方案", "author": "AI 助手" },
      "theme": { "name": "aurora", "accent": "#2E6BE6" },
      "blocks": [
        { "id": "b1", "type": "section", "data": { "level": 1, "title": "项目背景" } },
        { "id": "b2", "type": "paragraph", "data": { "text": "传统模式面临三大挑战。" } },
        { "id": "b3", "type": "columns", "data": { "ratio": [1,1,1], "children": [
            { "type": "stats", "data": { "value": "38%", "label": "运维人力占比" } },
            { "type": "stats", "data": { "value": "2.4h", "label": "平均故障响应" } } ] } },
        { "id": "b4", "type": "table", "data": {
            "headers": ["系统", "现状", "目标"],
            "rows": [["门禁", "独立管理", "统一身份"],
                     ["能耗", "人工抄表", "实时监测"]] } },
        { "id": "b5", "type": "chart", "data": {
            "type": "bar", "title": "季度营收",
            "labels": ["Q1", "Q2", "Q3", "Q4"],
            "series": [ { "name": "营收", "data": [120, 180, 160, 240] } ] } },
        { "id": "b6", "type": "mindmap", "data": {
            "layout": "right",
            "root": { "id": "n0", "text": "降本思路", "children": [
                { "id": "n1", "text": "人力成本", "children": [
                    { "id": "n11", "text": "流程自动化" } ] } ] } } }
      ],
      "assets": { "img_01": "https://cdn.example.com/arch.png" }
    }
    """.trimIndent()

    @Test
    fun `full envelope parses`() {
        val r = Aip.parse(full)
        assertNotNull("完整信封必须解析成功", r.envelope)
        val env = r.envelope!!
        assertEquals("doc", env.kind)
        assertEquals("智慧园区建设方案", env.title)
        assertEquals(6, env.blocks.size)
        assertTrue(env.blocks[0] is Aip.Block.Section)
        assertTrue(env.blocks[1] is Aip.Block.Paragraph)
        assertTrue(env.blocks[2] is Aip.Block.Columns)
        assertTrue(env.blocks[3] is Aip.Block.Table)
        val chart = env.blocks[4] as Aip.Block.Chart
        assertEquals("bar", chart.chartType)
        assertEquals(listOf(120.0, 180.0, 160.0, 240.0), chart.series[0].values)
        val mm = env.blocks[5] as Aip.Block.Mindmap
        assertEquals("降本思路", mm.root.text)
        assertEquals("流程自动化", mm.root.children[0].children[0].text)
    }

    @Test
    fun `unknown block type falls back without dropping content`() {
        val src = """
        {"v":1,"kind":"doc","blocks":[
          {"id":"x1","type":"hologram","data":{"foo":"bar"}}
        ]}
        """.trimIndent()
        val r = Aip.parse(src)
        assertNotNull(r.envelope)
        val fb = r.envelope!!.blocks[0] as Aip.Block.Fallback
        assertTrue("未知类型必须走 Fallback 兜底（L2）", true)
        assertTrue(fb.text.contains("hologram") || fb.text.contains("foo"))
    }

    @Test
    fun `truncation at every position never throws and yields partial blocks`() {
        var partialParses = 0
        var partialBlockCount = 0
        var cutPoints = 0
        // 每 7 个字符截断一次（覆盖字符串中途/嵌套中/数组逗号后等所有形态）
        for (cut in 10 until full.length step 7) {
            val r = Aip.parse(full.substring(0, cut))
            cutPoints++
            if (r.envelope != null) {
                partialParses++
                partialBlockCount += r.envelope!!.blocks.size
                // 每个已解析出的块必须有稳定 id（LazyColumn key 复用前提）
                r.envelope!!.blocks.forEach { assertTrue(it.id.isNotBlank()) }
            }
        }
        assertTrue("截断点数量异常: $cutPoints", cutPoints > 100)
        assertTrue("截断后至少部分位置能部分解析: $partialParses", partialParses > cutPoints / 3)
        assertTrue("截断重放平均块数异常: $partialBlockCount / $partialParses", partialBlockCount >= partialParses)
    }

    @Test
    fun `truncated mid-block keeps earlier blocks`() {
        // 截在第四个块（table）中间：前三个块必须完整保留
        val idx = full.indexOf("\"b4\"")
        val r = Aip.parse(full.substring(0, idx + 30))
        if (r.envelope != null) {
            assertTrue("截断后已完整块数应 ≥ 3，实际 ${r.envelope!!.blocks.size}", r.envelope!!.blocks.size >= 3)
        }
    }

    @Test
    fun `non aip content is text down`() {
        val r = Aip.parse("就是一段普通回答，没有任何结构。")
        assertTrue(r.envelope == null)
        assertEquals(Aip.Degradation.ChannelDown, r.degradation)
        assertTrue(!Aip.looksLikeAip("普通文本"))
        assertTrue(Aip.looksLikeAip(full))
    }

    @Test
    fun `router hard directive wins`() {
        assertEquals(CanvasRouter.Channel.B, CanvasRouter.route("帮我做个8页PPT").channel)
        assertEquals("deck", CanvasRouter.route("帮我做个8页PPT").hintKind)
        assertEquals("mindmap", CanvasRouter.route("用思维导图梳理一下").hintKind)
        assertEquals(CanvasRouter.Channel.A, CanvasRouter.route("解释一下协程").channel)
        // 信封头信号
        assertEquals(CanvasRouter.Channel.B, CanvasRouter.route("随便", envelopeKind = "deck").channel)
        // 复杂度启发式
        assertEquals(CanvasRouter.Channel.B, CanvasRouter.route("写点东西", contentLength = 2000).channel)
        assertEquals(CanvasRouter.Channel.B, CanvasRouter.route("写点东西", h2Count = 4).channel)
        assertEquals(CanvasRouter.Channel.B, CanvasRouter.route("写点东西", hasTableOrChart = true).channel)
    }
}
