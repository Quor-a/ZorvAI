package com.ai.assistance.quro.core.canvas

import com.ai.assistance.quro.core.canvas.Aip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 复现用户报告：「排版引擎已降级为 Markdown显示」——AI 在正文里写 ```aip 围栏（信封 JSON 合法）
 * 但对话框仍显示降级横幅 + 原始 JSON。
 *
 * 用与 ChatScreen.parseBlocks 完全一致的围栏提取（RE_FENCE 非贪婪）+ Aip.parse 链路验证：
 * 若提取的 code 或 parse 失败，即为根因。
 */
class AipFenceRenderReproTest {

    // 与 ChatScreen.kt RE_FENCE 修复后一致：行首锚定，避免被正文内联代码 ` ```aip ` 干扰
    private val reFence = Regex("(?m)^```([\\w+#-]*)\\n?([\\s\\S]*?)```")

    private val userMessage = """
好的！要让 AIP 文档直接渲染在对话框里，我用 ` ```aip ` 围栏把信封写进正文，客户端排版引擎会直接把它渲染成原生排版卡片：
```aip
{"v":1,"kind":"doc","meta":{"title":"AIP 文档测试","subtitle":"Zorv AI 排版引擎能力验证","author":"Zorv AI"},"theme":{"name":"aurora","accent":"#2E6BE6"},"blocks":[{"id":"b1","type":"heading","data":{"level":1,"text":"第一章 测试概述"}},{"id":"b2","type":"paragraph","data":{"text":"这是一段用于验证 AIP 文档流渲染能力的测试文本。"}},{"id":"b3","type":"list","data":{"ordered":false,"items":["标题层级渲染","段落排版","列表与有序列表","表格与图表","提示块 callout"]}},{"id":"b4","type":"table","data":{"headers":["测试项","状态","说明"],"rows":[["doc 文档流","待验证","是否渲染成原生排版卡片"],["deck 横滑PPT","待验证","幻灯片式排版"],["mindmap 导图","待验证","思维导图渲染"],["export 导出","已验证 ✅","docx 真实落盘"]]}},{"id":"b5","type":"callout","data":{"tone":"info","title":"测试目的","text":"确认 AIP 排版引擎在对话框内的渲染表现。"}},{"id":"b6","type":"steps","data":{"items":["生成信封","围栏下发","渲染验证","导出确认"]}}]}
```
📌 说明：如果上面这条围栏在你的对话框里显示成了排版精美的文档卡片，说明 AIP 渲染层正常工作 ✅
    """.trimIndent()

    @Test
    fun `fence extracts complete aip envelope`() {
        val matches = reFence.findAll(userMessage).toList()
        assertTrue("必须匹配到 aip 围栏，实际 ${matches.size} 个", matches.isNotEmpty())
        val m = matches.first { it.groupValues[1].trim().lowercase() == "aip" }
        val code = m.groupValues[2].removeSuffix("\n")
        assertTrue("围栏 code 必须以 { 开头", code.trimStart().startsWith("{"))
        assertTrue("围栏 code 必须含 blocks 数组", code.contains("\"blocks\""))
        // code 应包含最后一个块 b6（steps），证明未被非贪婪截断
        assertTrue("code 必须完整到 b6 steps 块（未被提前截断）: len=${code.length}", code.contains("\"b6\""))
    }

    @Test
    fun `extracted envelope parses without channel down`() {
        val m = reFence.findAll(userMessage).first { it.groupValues[1].trim().lowercase() == "aip" }
        val code = m.groupValues[2].removeSuffix("\n")
        val r = Aip.parse(code)
        assertNotNull("信封必须解析成功，degradation=${r.degradation}", r.envelope)
        val env = r.envelope!!
        assertEquals("doc", env.kind)
        assertEquals("AIP 文档测试", env.title)
        // 6 个块都应在
        assertEquals(6, env.blocks.size)
        assertEquals("heading", env.blocks[0].type)
        assertEquals("steps", env.blocks[5].type)
    }

    @Test
    fun `sanitize does not break this valid envelope`() {
        val m = reFence.findAll(userMessage).first { it.groupValues[1].trim().lowercase() == "aip" }
        val code = m.groupValues[2].removeSuffix("\n")
        val sanitized = Aip.sanitizeJson(code)
        // 合法 JSON 经 sanitize 后应能解析
        val r = Aip.parse(sanitized)
        assertNotNull(r.envelope)
    }

    @Test
    fun `inline code mentioning fence does not hijack the real fence`() {
        // 回归用例：正文里用反引号内联代码「` ```aip `」引用围栏语法（AI 常见写法），
        // 真正的围栏在行首。旧 RE_FENCE 会从内联代码的 ``` 开始吞掉真正围栏起始标记 →
        // 信封不被识别 → 降级为 Markdown。修复（行首锚定）后必须只匹配真正的行首围栏。
        val inlineMention = "我用 ` ```aip ` 围栏写文档：\n```aip\n" +
            "{\"v\":1,\"kind\":\"doc\",\"meta\":{\"title\":\"T\"},\"blocks\":[{\"id\":\"b1\",\"type\":\"paragraph\",\"data\":{\"text\":\"hi\"}}]}\n```\n"
        val m = reFence.findAll(inlineMention).toList()
        assertEquals("必须只匹配 1 个围栏（真正的行首围栏），实际 ${m.size}", 1, m.size)
        val code = m[0].groupValues[2].removeSuffix("\n")
        assertTrue("code 必须是完整信封（以 { 开头）", code.trimStart().startsWith("{"))
        val r = Aip.parse(code)
        assertNotNull("行首围栏信封必须解析成功", r.envelope)
        assertEquals("T", r.envelope!!.title)
    }
}
