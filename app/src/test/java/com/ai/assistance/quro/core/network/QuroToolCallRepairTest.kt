package com.ai.assistance.quro.core.network

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [QuroToolCallRepair] 的行为契约测试。
 *
 * 断言的每一条输入都是**真实端侧模型吐过的畸形形态**，任何一个失败都意味着
 * 对应那类模型会回到"工具调用被静默吞掉、气泡里只剩一坨标签"的旧状态。
 */
class QuroToolCallRepairTest {

    private val known = setOf(
        "get_current_time", "read_file", "write_file", "get_battery",
        "toggle_flashlight", "list_installed_apps",
    )

    private fun args(json: String): JSONObject = JSONObject(json)

    // ------------------------------------------------------------------ 标准形态

    @Test
    fun `标准 tool_call 标签解析`() {
        val r = QuroToolCallRepair.extract(
            """<tool_call>{"name":"get_current_time","arguments":{}}</tool_call>""",
            known,
        )
        assertEquals(1, r.calls.size)
        assertEquals("get_current_time", r.calls[0].name)
        assertTrue(r.recovered)
        assertNull(r.diagnostic)
    }

    @Test
    fun `OpenAI 信封解析`() {
        val r = QuroToolCallRepair.extract(
            """{"tool_calls":[{"id":"c1","type":"function","function":{"name":"get_battery","arguments":"{}"}}]}""",
            known,
        )
        assertEquals(1, r.calls.size)
        assertEquals("get_battery", r.calls[0].name)
        assertEquals("c1", r.calls[0].id)
    }

    // ------------------------------------------------------- 旧实现全部落空的形态

    @Test
    fun `裸键名与裸值能被救回`() {
        val r = QuroToolCallRepair.extract(
            """<tool_call>{name: get_current_time, arguments: {}}</tool_call>""",
            known,
        )
        assertEquals("裸键名/裸值没有被修复，正是用户遇到的报错形态", 1, r.calls.size)
        assertEquals("get_current_time", r.calls[0].name)
    }

    @Test
    fun `单引号能被救回`() {
        val r = QuroToolCallRepair.extract(
            """<tool_call>{'name':'read_file','arguments':{'path':'/sdcard/a.txt'}}</tool_call>""",
            known,
        )
        assertEquals(1, r.calls.size)
        assertEquals("read_file", r.calls[0].name)
        assertEquals("/sdcard/a.txt", args(r.calls[0].arguments).getString("path"))
    }

    @Test
    fun `尾随逗号能被救回`() {
        val r = QuroToolCallRepair.extract(
            """<tool_call>{"name":"read_file","arguments":{"path":"/a.txt",},}</tool_call>""",
            known,
        )
        assertEquals(1, r.calls.size)
        assertEquals("/a.txt", args(r.calls[0].arguments).getString("path"))
    }

    @Test
    fun `缺闭合括号能被补齐`() {
        // 用拼接而非原始字符串：结尾需要 4 个连续引号，原始字符串的终止规则在这里有歧义。
        val broken = "<tool_call>" + "{\"name\":\"read_file\",\"arguments\":{\"path\":\"/a.txt\""
        val r = QuroToolCallRepair.extract(broken, known)
        assertEquals(1, r.calls.size)
        assertEquals("read_file", r.calls[0].name)
        assertEquals("/a.txt", args(r.calls[0].arguments).getString("path"))
    }

    @Test
    fun `缺闭合标签的截断输出能被救回`() {
        val r = QuroToolCallRepair.extract(
            """好的，我来看看。<tool_call>{"name":"read_file","arguments":{"path":"/a.txt"}}""",
            known,
        )
        assertEquals(1, r.calls.size)
        assertEquals("read_file", r.calls[0].name)
    }

    @Test
    fun `全角标点能被归一`() {
        val r = QuroToolCallRepair.extract(
            "<tool_call>｛name：“read_file”，arguments：｛“path”：“/a.txt”｝｝</tool_call>",
            known,
        )
        assertEquals(1, r.calls.size)
        assertEquals("/a.txt", args(r.calls[0].arguments).getString("path"))
    }

    @Test
    fun `字符串内裸换行能被接受`() {
        val r = QuroToolCallRepair.extract(
            "<tool_call>{\"name\":\"write_file\",\"arguments\":{\"path\":\"/a.txt\",\"content\":\"第一行\n第二行\"}}</tool_call>",
            known,
        )
        assertEquals(1, r.calls.size)
        assertTrue(args(r.calls[0].arguments).getString("content").contains("第二行"))
    }

    @Test
    fun `Python 风格字面量能被归一`() {
        val r = QuroToolCallRepair.extract(
            """<tool_call>{'name': 'write_file', 'arguments': {'path': '/a.txt', 'overwrite': True, 'note': None}}</tool_call>""",
            known,
        )
        assertEquals(1, r.calls.size)
        val a = args(r.calls[0].arguments)
        assertTrue(a.getBoolean("overwrite"))
        assertTrue(a.isNull("note"))
    }

    // ------------------------------------------------------------------ 参数形态

    @Test
    fun `参数摊平在顶层时被聚合`() {
        val r = QuroToolCallRepair.extract(
            """<tool_call>{"name":"read_file","path":"/sdcard/a.txt"}</tool_call>""",
            known,
        )
        assertEquals("摊平参数被当成零参数调用，下游只会报'缺少必填参数'", 1, r.calls.size)
        assertEquals("/sdcard/a.txt", args(r.calls[0].arguments).getString("path"))
    }

    @Test
    fun `arguments 别名全覆盖`() {
        for (alias in listOf("arguments", "parameters", "args", "params", "input", "action_input")) {
            val r = QuroToolCallRepair.extract(
                """<tool_call>{"name":"read_file","$alias":{"path":"/x.txt"}}</tool_call>""",
                known,
            )
            assertEquals("别名 $alias 未被识别", 1, r.calls.size)
            assertEquals("/x.txt", args(r.calls[0].arguments).getString("path"))
        }
    }

    @Test
    fun `字符串化的 arguments 会被二次解析`() {
        val r = QuroToolCallRepair.extract(
            """<tool_call>{"name":"read_file","arguments":"{\"path\":\"/deep.txt\"}"}</tool_call>""",
            known,
        )
        assertEquals(1, r.calls.size)
        assertEquals("/deep.txt", args(r.calls[0].arguments).getString("path"))
    }

    @Test
    fun `嵌套参数不被花括号截断`() {
        val r = QuroToolCallRepair.extract(
            """<tool_call>{"name":"write_file","arguments":{"meta":{"a":{"b":[1,2,3]},"c":"}"}}}</tool_call>""",
            known,
        )
        assertEquals(1, r.calls.size)
        val meta = args(r.calls[0].arguments).getJSONObject("meta")
        assertEquals(3, meta.getJSONObject("a").getJSONArray("b").length())
        assertEquals("}", meta.getString("c"))
    }

    @Test
    fun `路径中的空格不被截断`() {
        val r = QuroToolCallRepair.extract(
            """<tool_call>{name: read_file, arguments: {path: /sdcard/My Documents/a.txt}}</tool_call>""",
            known,
        )
        assertEquals(1, r.calls.size)
        assertEquals("/sdcard/My Documents/a.txt", args(r.calls[0].arguments).getString("path"))
    }

    // ------------------------------------------------------------------ 标签族

    @Test
    fun `TOOL_CALLS 哨兵形态`() {
        val r = QuroToolCallRepair.extract(
            """[TOOL_CALLS] [{"name":"get_battery","arguments":{}}]""",
            known,
        )
        assertEquals(1, r.calls.size)
        assertEquals("get_battery", r.calls[0].name)
    }

    @Test
    fun `function_call 标签族`() {
        val r = QuroToolCallRepair.extract(
            """<function_call>{"name":"get_battery","arguments":{}}</function_call>""",
            known,
        )
        assertEquals(1, r.calls.size)
        assertEquals("get_battery", r.calls[0].name)
    }

    @Test
    fun `tool_calls wrapper 内的多个调用全部解析`() {
        val r = QuroToolCallRepair.extract(
            """<tool_calls>[{"name":"get_battery","arguments":{}},{"name":"get_current_time","arguments":{}}]</tool_calls>""",
            known,
        )
        assertEquals(2, r.calls.size)
        assertEquals(listOf("get_battery", "get_current_time"), r.calls.map { it.name })
    }

    @Test
    fun `多段 tool_call 标签全部解析`() {
        val r = QuroToolCallRepair.extract(
            """<tool_call>{"name":"get_battery","arguments":{}}</tool_call> 然后 <tool_call>{"name":"get_current_time","arguments":{}}</tool_call>""",
            known,
        )
        assertEquals(2, r.calls.size)
    }

    @Test
    fun `工具名作键的形态`() {
        val r = QuroToolCallRepair.extract(
            """<tool_call>{"read_file":{"path":"/k.txt"}}</tool_call>""",
            known,
        )
        assertEquals(1, r.calls.size)
        assertEquals("read_file", r.calls[0].name)
        assertEquals("/k.txt", args(r.calls[0].arguments).getString("path"))
    }

    // ------------------------------------------------------------------ 函数式调用

    @Test
    fun `函数式调用带关键字参数`() {
        val r = QuroToolCallRepair.extract(
            """<tool_call>read_file(path="/sdcard/a.txt")</tool_call>""",
            known,
        )
        assertEquals(1, r.calls.size)
        assertEquals("read_file", r.calls[0].name)
        assertEquals("/sdcard/a.txt", args(r.calls[0].arguments).getString("path"))
    }

    @Test
    fun `函数式调用空参数`() {
        val r = QuroToolCallRepair.extract(
            """<tool_call>get_current_time()</tool_call>""",
            known,
        )
        assertEquals(1, r.calls.size)
        assertEquals("get_current_time", r.calls[0].name)
        assertEquals("{}", r.calls[0].arguments)
    }

    @Test
    fun `函数式调用传 JSON 对象`() {
        val r = QuroToolCallRepair.extract(
            """<tool_call>read_file({"path":"/j.txt"})</tool_call>""",
            known,
        )
        assertEquals(1, r.calls.size)
        assertEquals("/j.txt", args(r.calls[0].arguments).getString("path"))
    }

    // ------------------------------------------------------------------ 名称纠错

    @Test
    fun `大小写偏差被纠正`() {
        val r = QuroToolCallRepair.extract(
            """<tool_call>{"name":"GetCurrentTime","arguments":{}}</tool_call>""",
            known,
        )
        assertEquals("get_current_time", r.calls[0].name)
    }

    @Test
    fun `下划线缺失被纠正`() {
        val r = QuroToolCallRepair.extract(
            """<tool_call>{"name":"getcurrenttime","arguments":{}}</tool_call>""",
            known,
        )
        assertEquals("get_current_time", r.calls[0].name)
    }

    @Test
    fun `轻微拼写偏差被纠正`() {
        val r = QuroToolCallRepair.extract(
            """<tool_call>{"name":"get_current_times","arguments":{}}</tool_call>""",
            known,
        )
        assertEquals("get_current_time", r.calls[0].name)
    }

    @Test
    fun `毫不相干的工具名绝不被瞎猜`() {
        val r = QuroToolCallRepair.extract(
            """<tool_call>{"name":"send_email_to_boss","arguments":{}}</tool_call>""",
            known,
        )
        assertEquals(1, r.calls.size)
        assertEquals("send_email_to_boss", r.calls[0].name)
    }

    @Test
    fun `不传已知名单时保持模型原样`() {
        val r = QuroToolCallRepair.extract(
            """<tool_call>{"name":"GetCurrentTime","arguments":{}}</tool_call>""",
        )
        assertEquals("GetCurrentTime", r.calls[0].name)
    }

    @Test
    fun `functions 前缀被剥离`() {
        val r = QuroToolCallRepair.extract(
            """<tool_call>{"name":"functions.get_battery","arguments":{}}</tool_call>""",
            known,
        )
        assertEquals("get_battery", r.calls[0].name)
    }

    // ------------------------------------------------------------------ 正文夹带 / 误判防护

    @Test
    fun `正文夹带 JSON 也能解析`() {
        val r = QuroToolCallRepair.extract(
            """好的，我来查一下电量：{"name":"get_battery","arguments":{}}""",
            known,
        )
        assertEquals(1, r.calls.size)
        assertEquals("get_battery", r.calls[0].name)
    }

    @Test
    fun `正文里的普通 JSON 不被误判为工具调用`() {
        val r = QuroToolCallRepair.extract(
            """查询结果如下：{"temperature":25,"humidity":60,"city":"深圳"}""",
            known,
        )
        assertTrue("普通结构化数据被误判成工具调用会造成误触发", r.calls.isEmpty())
        assertFalse(r.sawMarker)
    }

    @Test
    fun `无任何标记时 sawMarker 为假`() {
        val r = QuroToolCallRepair.extract("今天天气不错，我们聊聊别的吧。", known)
        assertTrue(r.calls.isEmpty())
        assertFalse(r.sawMarker)
        assertNull(r.diagnostic)
    }

    // ------------------------------------------------------------------ 去重

    @Test
    fun `wrapper 与内部标签不产生重复调用`() {
        val r = QuroToolCallRepair.extract(
            """<tool_calls><tool_call>{"name":"get_battery","arguments":{}}</tool_call></tool_calls>""",
            known,
        )
        assertEquals("同一调用被执行两次对有副作用的工具是真实伤害", 1, r.calls.size)
    }

    @Test
    fun `同参数重复调用被去重`() {
        val r = QuroToolCallRepair.extract(
            """<tool_call>{"name":"get_battery","arguments":{}}</tool_call><tool_call>{"name":"get_battery","arguments":{}}</tool_call>""",
            known,
        )
        assertEquals(1, r.calls.size)
    }

    @Test
    fun `不同参数的同名调用都保留`() {
        val r = QuroToolCallRepair.extract(
            """<tool_call>{"name":"read_file","arguments":{"path":"/a"}}</tool_call><tool_call>{"name":"read_file","arguments":{"path":"/b"}}</tool_call>""",
            known,
        )
        assertEquals(2, r.calls.size)
    }

    // ------------------------------------------------------------------ 诊断

    @Test
    fun `解析不出时给出可读诊断而非空话`() {
        val r = QuroToolCallRepair.extract("<tool_call></tool_call>", known)
        assertTrue(r.calls.isEmpty())
        assertTrue(r.sawMarker)
        assertNotNull("必须给出为什么失败，而不是让用户猜", r.diagnostic)
        assertFalse(
            "诊断里必须带上实际片段，否则等于没诊断",
            r.diagnostic!!.isBlank(),
        )
    }

    @Test
    fun `修复动作被记录用于诊断`() {
        val r = QuroToolCallRepair.extract(
            """<tool_call>{name: get_current_time, arguments: {}}</tool_call>""",
            known,
        )
        assertTrue("应用过修复却不上报，出问题就无法归因", r.repairs.isNotEmpty())
    }

    @Test
    fun `规范 JSON 不产生任何修复记录`() {
        val r = QuroToolCallRepair.extract(
            """<tool_call>{"name":"get_current_time","arguments":{}}</tool_call>""",
            known,
        )
        assertTrue("正常输出被标记为'修复过'会污染诊断可信度", r.repairs.isEmpty())
    }

    @Test
    fun `空输入安全返回`() {
        val r = QuroToolCallRepair.extract("")
        assertTrue(r.calls.isEmpty())
        assertFalse(r.sawMarker)
    }

    @Test
    fun `超长垃圾输入不抛异常`() {
        val junk = "<tool_call>" + "x".repeat(5000)
        val r = QuroToolCallRepair.extract(junk, known)
        assertTrue(r.calls.isEmpty())
        assertTrue(r.sawMarker)
    }

    // ------------------------------------------------------------------ 正文清洗

    @Test
    fun `清洗剥掉工具标签但保留正文`() {
        val out = QuroToolCallRepair.sanitizeVisible(
            """好的，我帮你查。<tool_call>{"name":"get_battery","arguments":{}}</tool_call>""",
        )
        assertFalse("用户不该在气泡里看到 <tool_call> 标签", out.contains("<tool_call>"))
        assertFalse(out.contains("get_battery"))
        assertTrue(out.contains("我帮你查"))
    }

    @Test
    fun `清洗剥掉未闭合的截断标签`() {
        val out = QuroToolCallRepair.sanitizeVisible(
            """正在处理。<tool_call>{"name":"read_file","arguments":{"path":"/a""",
        )
        assertFalse(out.contains("<tool_call>"))
        assertEquals("正在处理。", out)
    }

    @Test
    fun `清洗不动普通 json 代码块`() {
        val src = "示例如下：\n```json\n{\"temperature\":25}\n```\n以上。"
        assertEquals(src, QuroToolCallRepair.sanitizeVisible(src))
    }

    @Test
    fun `无标记文本原样返回`() {
        val src = "这是一段普通回复，没有工具调用。"
        assertEquals(src, QuroToolCallRepair.sanitizeVisible(src))
    }

    @Test
    fun `清洗哨兵形态后正文为空`() {
        val out = QuroToolCallRepair.sanitizeVisible(
            """[TOOL_CALLS] [{"name":"get_battery","arguments":{}}]""",
        )
        assertEquals("", out)
    }
}
