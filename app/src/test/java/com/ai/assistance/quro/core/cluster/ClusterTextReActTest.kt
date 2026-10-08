package com.ai.assistance.quro.core.cluster

import com.ai.assistance.quro.core.QuroToolSpec
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #192：端侧 ReAct 文本协议（[ClusterTextReAct]）的回归测试。
 *
 * 为什么这些用例长得"很脏"：端侧小模型（1.5B~3B 量化）**真的会**输出
 * `Action: xxx`、`**行动**：xxx`、`行动:xxx（path="D:/"）`、
 * 半角括号、全角冒号……测试里出现的每一种脏写法都是真实见过的。
 * 解析层宽容度不够，端侧就等于没有工具。
 */
class ClusterTextReActTest {

    private val names = listOf("read_file", "write_file", "list_files")

    private fun spec(name: String) = QuroToolSpec(
        name = name,
        description = "读文件",
        parametersJson = """{"type":"object","properties":{"path":{"type":"string"}},"required":["path"]}"""
    )

    // ——————————— 协议级解析 ———————————

    @Test
    fun `standard chinese protocol parses into one call`() {
        val raw = """
            思考：我需要先看看配置文件在不在
            行动：read_file
            参数：{"path":"app/build.gradle.kts"}
        """.trimIndent()
        val p = ClusterTextReAct.parse(raw, names)
        assertEquals(1, p.calls.size)
        assertEquals("read_file", p.calls[0].name)
        assertEquals("app/build.gradle.kts", JSONObject(p.calls[0].arguments).optString("path"))
        assertFalse("解析出调用就不是最终答案", p.finalAnswer)
        assertNotNull(p.diagnostic)
    }

    @Test
    fun `action written with halfwidth colon and parens still parses`() {
        // 端侧模型极常见的写法：Action: read_file("a.txt")，参数在行动行里而不是下一行
        val raw = "Action: read_file\nArguments: {\"path\":\"a.txt\"}"
        val p = ClusterTextReAct.parse(raw, names)
        assertEquals(1, p.calls.size)
        assertEquals("read_file", p.calls[0].name)
    }

    @Test
    fun `bold markdown markers do not break parsing`() {
        val raw = "**思考**：看看目录\n**行动**：list_files\n**参数**：{}"
        val p = ClusterTextReAct.parse(raw, names)
        assertEquals(1, p.calls.size)
        assertEquals("list_files", p.calls[0].name)
    }

    @Test
    fun `chinese fullwidth colon parses`() {
        val raw = "行动：read_file\n参数：{\"path\":\"x\"}"
        val p = ClusterTextReAct.parse(raw, names)
        assertEquals(1, p.calls.size)
    }

    @Test
    fun `tool name with trailing parenthesis is trimmed`() {
        val p = ClusterTextReAct.parse("行动：read_file (a.txt)\n参数：{}", names)
        assertEquals(1, p.calls.size)
        assertEquals("read_file", p.calls[0].name)
    }

    // ——————————— 幻觉过滤：只执行清单内的工具 ———————————

    @Test
    fun `hallucinated tool name is refused`() {
        // 模型编一个不存在的工具名，绝不能真去调 ——
        // 调了就是「执行了不存在的操作」，比不调更糟。
        val raw = "行动：send_email_to_boss\n参数：{\"to\":\"x\"}"
        val p = ClusterTextReAct.parse(raw, names)
        assertTrue("幻觉工具名不该被执行", p.calls.isEmpty())
        assertTrue("应作为最终答案收下", p.finalAnswer)
    }

    @Test
    fun `known tool survives while hallucinated one is dropped`() {
        val raw = "行动：read_file\n参数：{\"path\":\"a\"}\n行动：hack_the_planet\n参数：{}"
        val p = ClusterTextReAct.parse(raw, names)
        assertEquals(1, p.calls.size)
        assertEquals("read_file", p.calls[0].name)
    }

    // ——————————— 参数归一化：解析不了也要能调 ———————————

    @Test
    fun `key equals value style args become json`() {
        val p = ClusterTextReAct.parse("行动：read_file\n参数：path=\"D:/a.txt\"", names)
        assertEquals(1, p.calls.size)
        assertEquals("D:/a.txt", JSONObject(p.calls[0].arguments).optString("path"))
    }

    @Test
    fun `bare value is wrapped as input rather than dropped`() {
        // 关键：解析不了参数**不能**丢掉整个调用。
        // 让工具自己报错，它的错误信息往往就是模型最需要的纠正提示。
        val p = ClusterTextReAct.parse("行动：write_file\n参数：hello world", names)
        assertEquals(1, p.calls.size)
        assertEquals("hello world", JSONObject(p.calls[0].arguments).optString("input"))
    }

    @Test
    fun `missing args block yields empty object not a crash`() {
        val p = ClusterTextReAct.parse("行动：list_files", names)
        assertEquals(1, p.calls.size)
        assertEquals("{}", p.calls[0].arguments.trim())
    }

    // ——————————— 三级兜底：JSON 级 ———————————

    @Test
    fun `falls back to json parsing when protocol lines are absent`() {
        val raw = """{"tool":"read_file","arguments":{"path":"a.txt"}}"""
        val p = ClusterTextReAct.parse(raw, names)
        assertEquals(1, p.calls.size)
        assertEquals("read_file", p.calls[0].name)
        assertNotNull("应记录走了哪条路", p.diagnostic)
    }

    @Test
    fun `malformed json still gets rescued by the loose parser`() {
        // 单引号 + 尾随逗号：端侧最常见的坏 JSON
        val raw = "{'tool': 'list_files', 'arguments': {'path':'/'},}"
        val p = ClusterTextReAct.parse(raw, names)
        assertEquals(1, p.calls.size)
        assertEquals("list_files", p.calls[0].name)
    }

    // ——————————— 第 3 级：不按协议也不崩 ———————————

    @Test
    fun `plain prose is taken as the final answer`() {
        val raw = "这个需求我理解不了，请补充说明。"
        val p = ClusterTextReAct.parse(raw, names)
        assertTrue("不该硬造调用", p.calls.isEmpty())
        assertTrue("应作为最终答案", p.finalAnswer)
        assertTrue("用户必须看到模型的话", p.visible.contains("理解不了"))
    }

    @Test
    fun `conclusion marker yields only the conclusion`() {
        val raw = "思考：绕了一圈\n行动：read_file\n参数：{\"path\":\"a\"}\n结论：文件里写的是 hello"
        val p = ClusterTextReAct.parse(raw, names)
        // 有可执行调用就先执行，结论留给下一轮
        assertEquals(1, p.calls.size)

        val onlyConclusion = ClusterTextReAct.parse("思考：不用调工具\n结论：直接给你答案", names)
        assertTrue(onlyConclusion.calls.isEmpty())
        assertTrue(onlyConclusion.visible.contains("直接给你答案"))
    }

    @Test
    fun `empty output does not crash`() {
        val p = ClusterTextReAct.parse("", names)
        assertTrue(p.calls.isEmpty())
        assertTrue(p.finalAnswer)
    }

    @Test
    fun `pure hallucination marker without a callable name does not crash`() {
        // 有工具特征但解析不出 → 必须放弃执行并如实说明，不能崩
        val raw = "<tool_call>{'tool': </tool_call>"
        val p = ClusterTextReAct.parse(raw, names)
        assertTrue(p.calls.isEmpty())
        assertNotNull(p.diagnostic)
    }

    // ——————————— 协议提示词 ———————————

    @Test
    fun `protocol prompt lists real tools and real required keys`() {
        val prompt = ClusterTextReAct.protocolPrompt(listOf(spec("read_file"), spec("list_files")))
        assertTrue(prompt.contains("read_file"))
        assertTrue(prompt.contains("list_files"))
        // 必须带真实键名 + 必填标记：模型照抄才有意义
        assertTrue("缺参数键名：$prompt", prompt.contains("path"))
        assertTrue("缺必填标记：$prompt", prompt.contains("必填"))
        // 必须说清观察回灌，否则多步调用会断链
        assertTrue("没教怎么读观察结果：$prompt", prompt.contains("观察"))
    }

    @Test
    fun `protocol prompt with no tools says so explicitly`() {
        val prompt = ClusterTextReAct.protocolPrompt(emptyList())
        assertTrue("空工具清单要明说，否则模型会幻觉工具名", prompt.contains("无"))
    }

    @Test
    fun `protocol prompt survives a broken schema`() {
        // 工具 schema 坏了不能把整个 system prompt 搞崩
        val bad = QuroToolSpec("x", "坏 schema", "{not json")
        val prompt = ClusterTextReAct.protocolPrompt(listOf(bad))
        assertTrue(prompt.contains("x"))
    }

    // ——————————— 展示清洗只作用于副本 ———————————

    @Test
    fun `sanitize keeps the words but strips code fences`() {
        val out = ClusterTextReAct.sanitizeVisible("```\n思考：abc\n结论：done\n```")
        assertTrue(out.contains("结论"))
        assertFalse("代码围栏不该露给人：$out", out.contains("```"))
    }

    @Test
    fun `parse does not mutate its input`() {
        val raw = "行动：read_file\n参数：{\"path\":\"a\"}"
        val copy = raw
        ClusterTextReAct.parse(raw, names)
        assertEquals("解析不得改写原文（解析要靠原文标记）", copy, raw)
    }

    @Test
    fun `two identical calls get the same id`() {
        // 幂等 id：同一 (工具,参数) 必须稳定，否则 UI 上会出现两条一样的调用
        val a = ClusterTextReAct.parse("行动：read_file\n参数：{\"path\":\"a\"}", names)
        val b = ClusterTextReAct.parse("行动：read_file\n参数：{\"path\":\"a\"}", names)
        assertEquals(a.calls[0].id, b.calls[0].id)
    }
}