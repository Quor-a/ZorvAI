package com.ai.assistance.quro.core.canvas

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 形态互转 + 导出序列化单测（PRD M3）：
 * doc ↔ deck ↔ mindmap 内容不丢、cover slide 存在、pptx 文本带分页符、markdown 表格可还原。
 */
class AipConvertTest {

    private val docJson = """
    {"v":1,"kind":"doc","meta":{"title":"季度经营报告"},
     "blocks":[
       {"id":"b1","type":"section","data":{"level":1,"title":"营收概况"}},
       {"id":"b2","type":"paragraph","data":{"text":"整体营收稳健增长。"}},
       {"id":"b3","type":"list","data":{"ordered":false,"items":["收入 1200 万","成本 800 万"]}},
       {"id":"b4","type":"table","data":{"headers":["季度","营收"],"rows":[["Q1","300"],["Q2","400"]]}},
       {"id":"b5","type":"section","data":{"level":1,"title":"风险与展望"}},
       {"id":"b6","type":"callout","data":{"tone":"warn","title":"注意","text":"汇率波动影响利润。"}}
     ]}
    """.trimIndent()

    private val deckJson = """
    {"v":1,"kind":"deck","meta":{"title":"产品发布"},
     "blocks":[
       {"id":"s1","type":"slide","data":{"layout":"cover","title":"新产品 X","subtitle":"2026 秋季"}},
       {"id":"s2","type":"slide","data":{"layout":"titleBody","title":"核心特性","bullets":["快 3 倍","省电 40%"]}}
     ]}
    """.trimIndent()

    private val doc: Aip.Envelope = Aip.parse(docJson).envelope!!
    private val deck: Aip.Envelope = Aip.parse(deckJson).envelope!!

    @Test
    fun `doc to deck has cover and section slides`() {
        val d = AipConvert.toDeck(doc)
        assertEquals("deck", d.kind)
        val slides = d.blocks.filterIsInstance<Aip.Block.Slide>()
        assertTrue("至少 cover+2 章节页，实际 ${slides.size}", slides.size >= 3)
        assertEquals("cover", slides[0].layout)
        assertEquals("季度经营报告", slides[0].title)
        assertTrue(slides.any { it.title == "营收概况" && it.bullets.any { b -> b.contains("营收稳健") } })
        assertTrue(slides.any { it.layout == "table" && it.table != null })
    }

    @Test
    fun `deck to doc keeps slide content`() {
        val d = AipConvert.toDoc(deck)
        assertEquals("doc", d.kind)
        assertTrue(d.blocks.filterIsInstance<Aip.Block.Section>().any { it.title == "核心特性" })
        assertTrue(d.blocks.filterIsInstance<Aip.Block.ListBlock>().any { it.items.contains("快 3 倍") })
    }

    @Test
    fun `doc to mindmap builds tree from headings`() {
        val m = AipConvert.toMindmap(doc)
        assertEquals("mindmap", m.kind)
        val mm = m.blocks.filterIsInstance<Aip.Block.Mindmap>().first()
        assertEquals("季度经营报告", mm.root.text)
        val childTitles = mm.root.children.map { it.text }
        assertTrue("标题应成主分支：$childTitles", childTitles.contains("营收概况") && childTitles.contains("风险与展望"))
    }

    @Test
    fun `mindmap to doc flattens tree`() {
        val back = AipConvert.toDoc(AipConvert.toMindmap(doc))
        assertEquals("doc", back.kind)
        assertTrue(back.blocks.any { it is Aip.Block.ListBlock || it is Aip.Block.Heading })
    }

    @Test
    fun `convert is idempotent on same kind`() {
        assertTrue(AipConvert.convert(doc, "doc") === doc)
        assertTrue(AipConvert.convert(deck, "deck") === deck)
    }

    @Test
    fun `pptx text uses page separators and titles`() {
        val txt = AipConvert.toPptxText(deck)
        val pages = txt.split("---")
        assertEquals(2, pages.size)
        assertTrue(pages[0].contains("新产品 X"))
        assertTrue(pages[1].contains("快 3 倍"))
    }

    @Test
    fun `markdown export contains table and list`() {
        val md = AipConvert.toMarkdown(doc)
        assertTrue(md.contains("# 季度经营报告"))
        assertTrue(md.contains("| 季度 | 营收 |"))
        assertTrue(md.contains("- 收入 1200 万"))
    }
}
