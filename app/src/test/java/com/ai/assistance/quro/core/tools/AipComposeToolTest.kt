package com.ai.assistance.quro.core.tools

import com.ai.assistance.quro.core.canvas.Aip
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * aip_compose 工具容错：AI 调用时 blocks 数组常缺失/为空（大文档被模型省略），
 * 必须能从 sections/content/markdown/text 等参数自动合成块，不报「缺少非空 blocks 数组」、不白屏。
 */
class AipComposeToolTest {

    private val tool = AipComposeTool()

    @Test
    fun `empty blocks but content synthesizes paragraphs`() {
        val args = JSONObject().apply {
            put("kind", "doc")
            put("title", "季度总结")
            put("content", "本季度营收稳步增长。\n\n主要得益于新产品线。")
        }.toString()
        val blocks = tool.synthesizeBlocks(JSONObject(args))
        assertEquals("空 blocks 时 content 必须切成 2 段", 2, blocks.length())
    }

    @Test
    fun `empty blocks but markdown synthesizes heading and paragraphs`() {
        val args = JSONObject().apply {
            put("kind", "doc")
            put("title", "方案")
            put("markdown", "# 背景\n项目背景介绍。\n# 目标\n完成交付。")
        }.toString()
        val blocks = tool.synthesizeBlocks(JSONObject(args))
        assertEquals(4, blocks.length())
        assertEquals("heading", blocks.getJSONObject(0).optString("type"))
        assertEquals("背景", blocks.getJSONObject(0).getJSONObject("data").optString("text"))
    }

    @Test
    fun `empty blocks but sections synthesize section plus paragraphs`() {
        val args = JSONObject().apply {
            put("kind", "doc")
            put("title", "建设方案")
            put("sections", org.json.JSONArray().apply {
                put(JSONObject().apply {
                    put("title", "项目背景")
                    put("body", "现状描述。\n面临三大挑战。")
                })
            })
        }.toString()
        val blocks = tool.synthesizeBlocks(JSONObject(args))
        assertEquals("section + 2 段", 3, blocks.length())
        assertEquals("section", blocks.getJSONObject(0).optString("type"))
    }

    @Test
    fun `empty blocks and no content yields placeholder without crash`() {
        val args = JSONObject().apply { put("kind", "doc"); put("title", "空文档") }.toString()
        val blocks = tool.synthesizeBlocks(JSONObject(args))
        assertTrue("无内容也必须给占位块，不白屏", blocks.length() >= 1)
    }

    @Test
    fun `synthesized envelope roundtrips through Aip parse`() {
        // 模拟 run() 里缺 blocks 时的完整流程：synthesize → 组信封 → Aip.parse 成功
        val args = JSONObject().apply {
            put("kind", "doc")
            put("title", "周报")
            put("content", "本周完成 AIP 排版。\n\n修复降级问题。")
        }.toString()
        val jo = JSONObject(args)
        val blocks = tool.synthesizeBlocks(jo)
        val env = JSONObject().apply {
            put("v", 1)
            put("kind", "doc")
            put("meta", JSONObject().apply { put("title", jo.optString("title", "")) })
            put("theme", JSONObject().apply { put("name", "aurora"); put("accent", "") })
            put("blocks", blocks)
        }
        val r = Aip.parse(env.toString())
        assertNotNull("合成信封必须能被 Aip.parse 解析", r.envelope)
        assertEquals(2, r.envelope!!.blocks.size)
        assertEquals("周报", r.envelope!!.title)
    }
}
