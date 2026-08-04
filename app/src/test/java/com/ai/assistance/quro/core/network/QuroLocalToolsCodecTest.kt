package com.ai.assistance.quro.core.network

import com.ai.assistance.quro.core.QuroChatMessage
import com.ai.assistance.quro.core.QuroToolCall
import com.ai.assistance.quro.core.QuroToolSpec
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 锁死 [QuroLocalToolsCodec] 的工具调用编解码口径（B-2 修复的回归防线）。
 *
 * 病灶回顾：
 * - **编码侧**：旧版把消息压成 `{role, content}`，assistant 的 `tool_calls` 整个丢失、
 *   `role="tool"` 被降级成 `user`。多步工具编排必然断链——模型每轮失忆式重新决策，
 *   表现为反复调同一个工具或干脆放弃。
 * - **解码侧**：旧版用正则 `\{.*?\}` 抓 JSON，遇到嵌套 `arguments` 会在第一个 `}` 截断；
 *   而且解析失败一律静默返回空列表，用户只看到一段裸 JSON，不知道工具调用被吃掉了。
 */
class QuroLocalToolsCodecTest {

    // ---------------------------------------------------------------- encode

    /** tools 序列化必须是 OpenAI 兼容结构，且 parameters 是**对象**而非转义字符串。 */
    @Test
    fun `encodeTools emits openai compatible schema`() {
        val json = QuroLocalToolsCodec.encodeTools(
            listOf(
                QuroToolSpec(
                    name = "get_weather",
                    description = "查询天气",
                    parametersJson = """{"type":"object","properties":{"city":{"type":"string"}}}""",
                )
            )
        )

        val arr = JSONArray(json)
        assertEquals(1, arr.length())
        val item = arr.getJSONObject(0)
        assertEquals("function", item.getString("type"))
        val fn = item.getJSONObject("function")
        assertEquals("get_weather", fn.getString("name"))
        assertEquals("查询天气", fn.getString("description"))
        // 必须是对象：写成字符串会导致模板里 tool.function.parameters.properties 取不到。
        val params = fn.getJSONObject("parameters")
        assertEquals("object", params.getString("type"))
        assertTrue(params.getJSONObject("properties").has("city"))
    }

    /** parametersJson 非法时不得整体崩掉，降级成空 object schema。 */
    @Test
    fun `encodeTools tolerates malformed parameters`() {
        val json = QuroLocalToolsCodec.encodeTools(
            listOf(QuroToolSpec("t", "d", "not json at all"))
        )

        val params = JSONArray(json).getJSONObject(0).getJSONObject("function").getJSONObject("parameters")
        assertEquals("object", params.getString("type"))
    }

    /**
     * 核心回归：assistant 的 `tool_calls` 必须完整保留（旧版会丢），
     * 且 `arguments` 按 OpenAI 规范是 **JSON 字符串**而非对象。
     */
    @Test
    fun `encodeMessages preserves assistant tool_calls`() {
        val json = QuroLocalToolsCodec.encodeMessages(
            listOf(
                QuroChatMessage(
                    role = "assistant",
                    content = "",
                    toolCalls = listOf(
                        QuroToolCall(id = "call_1", name = "get_weather", arguments = """{"city":"北京"}""")
                    ),
                )
            )
        )

        val msg = JSONArray(json).getJSONObject(0)
        assertEquals("assistant", msg.getString("role"))
        val calls = msg.getJSONArray("tool_calls")
        assertEquals(1, calls.length())
        val call = calls.getJSONObject(0)
        assertEquals("call_1", call.getString("id"))
        assertEquals("function", call.getString("type"))
        val fn = call.getJSONObject("function")
        assertEquals("get_weather", fn.getString("name"))
        assertEquals("""{"city":"北京"}""", fn.getString("arguments"))
    }

    /** 核心回归：`role="tool"` 不得被降级成 user，且必须带 tool_call_id。 */
    @Test
    fun `encodeMessages keeps tool role and tool_call_id`() {
        val json = QuroLocalToolsCodec.encodeMessages(
            listOf(QuroChatMessage(role = "tool", content = "22℃", toolCallId = "call_1"))
        )

        val msg = JSONArray(json).getJSONObject(0)
        assertEquals("tool", msg.getString("role"))
        assertEquals("call_1", msg.getString("tool_call_id"))
        assertEquals("22℃", msg.getString("content"))
    }

    /** 工具可能合法地返回空结果，这条消息仍要保留，否则模板里 tool_call 找不到配对结果。 */
    @Test
    fun `encodeMessages keeps empty tool result but drops empty user`() {
        val json = QuroLocalToolsCodec.encodeMessages(
            listOf(
                QuroChatMessage(role = "tool", content = "", toolCallId = "call_1"),
                QuroChatMessage(role = "user", content = "   "),
            )
        )

        val arr = JSONArray(json)
        assertEquals(1, arr.length())
        assertEquals("tool", arr.getJSONObject(0).getString("role"))
    }

    /** tool_call_id 缺失时补稳定占位，避免 jinja 取不到字段直接抛异常导致 prompt 为空。 */
    @Test
    fun `encodeMessages backfills missing tool_call_id`() {
        val json = QuroLocalToolsCodec.encodeMessages(
            listOf(QuroChatMessage(role = "tool", content = "ok", toolCallId = null))
        )

        val id = JSONArray(json).getJSONObject(0).getString("tool_call_id")
        assertTrue(id.isNotBlank())
    }

    /** 未知角色统一归一为 user，防止模板遇到没见过的 role 抛异常。 */
    @Test
    fun `encodeMessages normalizes unknown role to user`() {
        val json = QuroLocalToolsCodec.encodeMessages(
            listOf(QuroChatMessage(role = "function", content = "x"))
        )

        assertEquals("user", JSONArray(json).getJSONObject(0).getString("role"))
    }

    // ---------------------------------------------------------------- decode

    /** llama.cpp `parseToolCallResponse` 的 OpenAI 信封形态。 */
    @Test
    fun `parses openai tool_calls envelope`() {
        val raw = """
            {"tool_calls":[{"id":"call_9","type":"function",
            "function":{"name":"get_weather","arguments":"{\"city\":\"北京\"}"}}]}
        """.trimIndent()

        val result = QuroLocalToolsCodec.parseDetailed(raw)

        assertEquals(1, result.calls.size)
        assertEquals("call_9", result.calls[0].id)
        assertEquals("get_weather", result.calls[0].name)
        assertEquals("""{"city":"北京"}""", result.calls[0].arguments)
        assertTrue(result.sawMarker)
    }

    /** MNN 主路径：标准 `<tool_call>` 标签。 */
    @Test
    fun `parses standard tool_call tag`() {
        val raw = "好的，我来查。\n<tool_call>\n{\"name\":\"get_time\",\"arguments\":{}}\n</tool_call>"

        val result = QuroLocalToolsCodec.parseDetailed(raw)

        assertEquals(1, result.calls.size)
        assertEquals("get_time", result.calls[0].name)
        assertEquals("{}", result.calls[0].arguments)
        assertNull(result.diagnostic)
    }

    /**
     * 关键回归：嵌套 `arguments` 不得被截断。
     * 旧正则 `\{.*?\}` 会在 `{"a":1}` 的第一个 `}` 处收尾，产出残缺 JSON。
     */
    @Test
    fun `nested arguments are not truncated`() {
        val raw = """<tool_call>{"name":"run","arguments":{"cmd":{"bin":"ls","args":["-l"]},"timeout":5}}</tool_call>"""

        val result = QuroLocalToolsCodec.parseDetailed(raw)

        assertEquals(1, result.calls.size)
        val args = JSONObject(result.calls[0].arguments)
        assertEquals("ls", args.getJSONObject("cmd").getString("bin"))
        assertEquals(5, args.getInt("timeout"))
    }

    /** 参数字符串里出现 `}` 或转义引号时，花括号配对扫描必须忽略字符串内部字符。 */
    @Test
    fun `braces inside string values do not break scanning`() {
        val raw = """<tool_call>{"name":"echo","arguments":{"text":"a } b \" c {"}}</tool_call>"""

        val result = QuroLocalToolsCodec.parseDetailed(raw)

        assertEquals(1, result.calls.size)
        assertEquals("""a } b " c {""", JSONObject(result.calls[0].arguments).getString("text"))
    }

    /** 多个工具调用要按顺序全部解析出来（并行工具调用形态）。 */
    @Test
    fun `parses multiple tool_call tags in order`() {
        val raw = """
            <tool_call>{"name":"first","arguments":{}}</tool_call>
            <tool_call>{"name":"second","arguments":{}}</tool_call>
        """.trimIndent()

        val result = QuroLocalToolsCodec.parseDetailed(raw)

        assertEquals(listOf("first", "second"), result.calls.map { it.name })
        assertEquals("call_local_0", result.calls[0].id)
        assertEquals("call_local_1", result.calls[1].id)
    }

    /** 生成被 max_tokens 截断（缺闭合标签）时仍要尽力解析，并给出诊断说明。 */
    @Test
    fun `unclosed tool_call is parsed with diagnostic`() {
        val raw = """<tool_call>{"name":"get_time","arguments":{}}"""

        val result = QuroLocalToolsCodec.parseDetailed(raw)

        assertEquals(1, result.calls.size)
        assertEquals("get_time", result.calls[0].name)
        assertNotNull("截断形态应留下诊断", result.diagnostic)
        assertTrue(result.diagnostic!!.contains("未闭合"))
    }

    /** ```` ```json ```` 代码块包裹形态（模型把工具调用当成代码输出）。 */
    @Test
    fun `parses json fenced code block`() {
        val raw = "我需要调用工具：\n```json\n{\"name\":\"get_time\",\"arguments\":{\"tz\":\"CST\"}}\n```"

        val result = QuroLocalToolsCodec.parseDetailed(raw)

        assertEquals(1, result.calls.size)
        assertEquals("get_time", result.calls[0].name)
        assertEquals("CST", JSONObject(result.calls[0].arguments).getString("tz"))
    }

    /** 整段就是裸 JSON 对象的形态。 */
    @Test
    fun `parses bare json object`() {
        val result = QuroLocalToolsCodec.parseDetailed("""{"name":"get_time","arguments":{}}""")

        assertEquals(1, result.calls.size)
        assertEquals("get_time", result.calls[0].name)
        assertTrue(result.sawMarker)
    }

    /** `{"function":{...}}` 包一层的写法也要认。 */
    @Test
    fun `parses function wrapped object`() {
        val raw = """<tool_call>{"function":{"name":"get_time","arguments":{"tz":"UTC"}}}</tool_call>"""

        val result = QuroLocalToolsCodec.parseDetailed(raw)

        assertEquals(1, result.calls.size)
        assertEquals("get_time", result.calls[0].name)
        assertEquals("UTC", JSONObject(result.calls[0].arguments).getString("tz"))
    }

    /** arguments 缺省 / 为 null 时归一为 `{}`，不能让下游拿到空串去 JSONObject 解析。 */
    @Test
    fun `missing arguments normalize to empty object`() {
        val result = QuroLocalToolsCodec.parseDetailed("""<tool_call>{"name":"noop"}</tool_call>""")

        assertEquals(1, result.calls.size)
        assertEquals("{}", result.calls[0].arguments)
    }

    /** 普通聊天文本不得误报 sawMarker——误报会给用户凭空多出一段警告。 */
    @Test
    fun `plain answer reports no marker and no calls`() {
        val result = QuroLocalToolsCodec.parseDetailed("北京今天晴，气温 22℃。")

        assertTrue(result.calls.isEmpty())
        assertFalse(result.sawMarker)
        assertNull(result.diagnostic)
    }

    /** 正文里恰好带个普通 JSON（不含 name+arguments 强特征）也不能误判。 */
    @Test
    fun `incidental json in prose is not a tool call`() {
        val result = QuroLocalToolsCodec.parseDetailed("""返回结构是 {"code":0,"data":[]}，你参考下。""")

        assertTrue(result.calls.isEmpty())
        assertFalse(result.sawMarker)
    }

    /**
     * 关键回归：模型"想调工具但格式坏了"必须被显式暴露（sawMarker=true + diagnostic 非空），
     * 上层据此给用户可见提示，而不是无声吞掉。
     */
    @Test
    fun `malformed tool call surfaces marker and diagnostic`() {
        val result = QuroLocalToolsCodec.parseDetailed("<tool_call>name: get_time, args: none</tool_call>")

        assertTrue(result.calls.isEmpty())
        assertTrue("必须识别出模型想调工具", result.sawMarker)
        assertNotNull("必须留下诊断信息", result.diagnostic)
    }

    /** 缺 name 字段的对象要被拒绝，并记录原因。 */
    @Test
    fun `tool call without name is rejected with diagnostic`() {
        val result = QuroLocalToolsCodec.parseDetailed("""<tool_call>{"arguments":{"a":1}}</tool_call>""")

        assertTrue(result.calls.isEmpty())
        assertNotNull(result.diagnostic)
        assertTrue(result.diagnostic!!.contains("name"))
    }

    /** 空输入是常见路径（模型只吐了思考段），不得抛异常也不得误报。 */
    @Test
    fun `blank input is safe`() {
        val result = QuroLocalToolsCodec.parseDetailed("   ")

        assertTrue(result.calls.isEmpty())
        assertFalse(result.sawMarker)
        assertNull(result.diagnostic)
    }

    /** [QuroLocalToolsCodec.parseToolCalls] 是 parseDetailed 的薄封装，行为必须一致。 */
    @Test
    fun `parseToolCalls delegates to parseDetailed`() {
        val raw = """<tool_call>{"name":"get_time","arguments":{}}</tool_call>"""

        assertEquals(
            QuroLocalToolsCodec.parseDetailed(raw).calls,
            QuroLocalToolsCodec.parseToolCalls(raw),
        )
    }

    // ------------------------------------------------- tool instruction 降级

    /** 模板不消费 tools 时的降级注入：指令要含 `<tools>` 定义与 `<tool_call>` 输出约定。 */
    @Test
    fun `buildToolInstruction describes tools and output format`() {
        val toolsJson = QuroLocalToolsCodec.encodeTools(
            listOf(QuroToolSpec("get_weather", "查询天气", """{"type":"object"}"""))
        )

        val instruction = QuroLocalToolsCodec.buildToolInstruction(toolsJson)

        assertTrue(instruction.contains("<tools>"))
        assertTrue(instruction.contains("</tools>"))
        assertTrue(instruction.contains("get_weather"))
        assertTrue(instruction.contains("<tool_call>"))
    }

    /** 空 / 非法 tools 不得注入噪声。 */
    @Test
    fun `buildToolInstruction returns empty for empty or invalid tools`() {
        assertEquals("", QuroLocalToolsCodec.buildToolInstruction("[]"))
        assertEquals("", QuroLocalToolsCodec.buildToolInstruction("garbage"))
    }

    /** 已有 system 消息时就地追加，不得新增一条（否则某些模板只认第一条 system）。 */
    @Test
    fun `withToolInstruction appends to existing system message`() {
        val toolsJson = QuroLocalToolsCodec.encodeTools(
            listOf(QuroToolSpec("get_time", "查询时间", """{"type":"object"}"""))
        )
        val messages = listOf(
            QuroChatMessage(role = "system", content = "你是助手。"),
            QuroChatMessage(role = "user", content = "几点了"),
        )

        val out = QuroLocalToolsCodec.withToolInstruction(messages, toolsJson)

        assertEquals(2, out.size)
        assertEquals("system", out[0].role)
        assertTrue(out[0].content.startsWith("你是助手。"))
        assertTrue(out[0].content.contains("get_time"))
        assertEquals(messages[1], out[1])
    }

    /** 没有 system 消息时新建一条并置于开头。 */
    @Test
    fun `withToolInstruction prepends system message when absent`() {
        val toolsJson = QuroLocalToolsCodec.encodeTools(
            listOf(QuroToolSpec("get_time", "查询时间", """{"type":"object"}"""))
        )
        val messages = listOf(QuroChatMessage(role = "user", content = "几点了"))

        val out = QuroLocalToolsCodec.withToolInstruction(messages, toolsJson)

        assertEquals(2, out.size)
        assertEquals("system", out[0].role)
        assertTrue(out[0].content.contains("get_time"))
        assertEquals("user", out[1].role)
    }

    /** 工具为空时原样返回，避免白白吃上下文。 */
    @Test
    fun `withToolInstruction is a no-op for empty tools`() {
        val messages = listOf(QuroChatMessage(role = "user", content = "hi"))

        assertEquals(messages, QuroLocalToolsCodec.withToolInstruction(messages, "[]"))
    }

    /**
     * 端到端往返：降级注入的指令 → 模型按格式输出 → 解析器能还原调用。
     * 两侧格式约定必须自洽，否则"注入了却解析不出来"依旧是不能用。
     */
    @Test
    fun `instruction format round trips through parser`() {
        val toolsJson = QuroLocalToolsCodec.encodeTools(
            listOf(QuroToolSpec("get_weather", "查询天气", """{"type":"object"}"""))
        )
        val instruction = QuroLocalToolsCodec.buildToolInstruction(toolsJson)
        // 取指令里承诺的输出格式，替换成真实调用，模拟模型照做。
        assertTrue(instruction.contains("""{"name": <function-name>, "arguments": <args-json-object>}"""))

        val modelOutput = "<tool_call>\n{\"name\": \"get_weather\", \"arguments\": {\"city\": \"上海\"}}\n</tool_call>"
        val result = QuroLocalToolsCodec.parseDetailed(modelOutput)

        assertEquals(1, result.calls.size)
        assertEquals("get_weather", result.calls[0].name)
        assertEquals("上海", JSONObject(result.calls[0].arguments).getString("city"))
    }
}
