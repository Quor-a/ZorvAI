package com.ai.assistance.quro.core.network

import com.ai.assistance.quro.core.QuroChatMessage
import com.ai.assistance.quro.core.QuroToolCall
import com.ai.assistance.quro.core.QuroToolSpec
import org.json.JSONArray
import org.json.JSONObject

/**
 * 本地离线模型（llama.cpp / MNN）的工具调用编解码器。
 *
 * 职责：
 * 1. [encodeTools]：把 [QuroToolSpec] 列表序列化为 OpenAI 兼容的 tools JSON 数组，
 *    供原生层 `applyStructuredChatTemplate` / `generateStreamStructured` 使用。
 *    输出格式与云端 [QuroLlmClient] 下发的 tools 字段完全一致，保证本地/云端行为对齐。
 * 2. [encodeMessages]：把 [QuroChatMessage] 列表序列化为 `[{role, content}]` JSON 数组，
 *    供原生层结构化聊天模板使用。
 * 3. [parseDetailed] / [parseToolCalls]：把模型输出解析为 [QuroToolCall] 列表。支持：
 *    - llama.cpp `parseToolCallResponse` 返回的 OpenAI 兼容 JSON `{"tool_calls":[...]}`
 *    - MNN 路径的 raw 模型文本（`<tool_call>...</tool_call>`，含被截断的未闭合形态）
 *    - ```` ```json ```` 代码块包裹、以及整段就是裸 JSON 对象 / 数组的形态
 *
 * 设计取舍：
 * - 仅用 org.json，无额外依赖（与 QuroLlmClient 一致）。
 * - 解析全程不抛异常；但**失败不再静默**——[parseDetailed] 会回报
 *   [ParseResult.sawMarker]（看起来想调工具）与 [ParseResult.diagnostic]（哪一步没解析出来），
 *   由上层写进诊断日志并提示用户，避免"工具调用被吃掉"这种无声失败。
 */
object QuroLocalToolsCodec {

    /**
     * 把工具规格列表序列化为 OpenAI 兼容的 tools JSON 数组字符串。
     *
     * 输出格式：
     * ```json
     * [{"type":"function","function":{"name":"...","description":"...","parameters":{...}}}]
     * ```
     *
     * 与 [QuroLlmClient.chat] 中 tools 字段的序列化逻辑完全对齐（见 QuroLlmClient.kt:87-102），
     * 保证本地模型与云端模型收到的工具描述格式一致。
     */
    fun encodeTools(specs: List<QuroToolSpec>): String {
        val arr = JSONArray()
        for (spec in specs) {
            val function = JSONObject()
                .put("name", spec.name)
                .put("description", spec.description)
            // parametersJson 是 JSON Schema 对象字符串；解析后放入，避免双重转义。
            val params = runCatching { JSONObject(spec.parametersJson) }.getOrNull()
                ?: JSONObject().put("type", "object")
            function.put("parameters", params)
            arr.put(
                JSONObject()
                    .put("type", "function")
                    .put("function", function)
            )
        }
        return arr.toString()
    }

    /**
     * 用自然语言把工具定义写成一段 system 指令（Hermes / Qwen 约定格式）。
     *
     * ## 用途
     * 有些离线模型**确实具备**函数调用能力（预训练里见过 `<tool_call>`），
     * 但它的 `llm_config.json` 里那份 `chat_template` 压根不消费 `tools` 变量——
     * 于是工具定义在模板渲染阶段就被丢掉了，模型什么也没看见，
     * 表现就是"明明支持工具调用，开了却没反应"。
     *
     * 这时把工具定义降级成普通 system 文本塞进去，模型照样能触发调用。
     * 格式与原生 ChatML 兜底模板（mnnllmnative.cpp `buildToolsInstruction`）
     * 以及 [parseDetailed] 的解析口径保持一致。
     *
     * @param toolsJson [encodeTools] 产出的 OpenAI 兼容 tools JSON 数组字符串。
     * @return 可直接拼进 system 消息的指令文本；tools 为空或非法时返回空串。
     */
    fun buildToolInstruction(toolsJson: String): String {
        val arr = runCatching { JSONArray(toolsJson) }.getOrNull() ?: return ""
        if (arr.length() == 0) return ""
        val sb = StringBuilder()
        sb.append("\n\n# Tools\n\n")
        sb.append("You may call one or more functions to assist with the user query.\n\n")
        sb.append("You are provided with function signatures within <tools></tools> XML tags:\n<tools>\n")
        for (i in 0 until arr.length()) {
            val item = arr.opt(i) ?: continue
            sb.append(item.toString()).append('\n')
        }
        sb.append("</tools>\n\n")
        sb.append("For each function call, return a json object with function name and arguments ")
        sb.append("within <tool_call></tool_call> XML tags:\n")
        sb.append("<tool_call>\n{\"name\": <function-name>, \"arguments\": <args-json-object>}\n</tool_call>")
        return sb.toString()
    }

    /**
     * 把工具指令并入消息列表的 system 消息（没有则新建一条置于开头）。
     *
     * 仅在"模型模板不消费 tools"时使用；模板本身支持 tools 的模型不要调用本方法，
     * 否则工具定义会重复出现两遍，白白吃掉上下文。
     *
     * @param messages 原始消息列表（不会被修改）。
     * @param toolsJson OpenAI 兼容的 tools JSON 数组字符串。
     * @return 注入工具指令后的新列表；指令为空时原样返回 [messages]。
     */
    fun withToolInstruction(
        messages: List<QuroChatMessage>,
        toolsJson: String,
    ): List<QuroChatMessage> {
        val instruction = buildToolInstruction(toolsJson)
        if (instruction.isEmpty()) return messages

        val systemIndex = messages.indexOfFirst { it.role.equals("system", ignoreCase = true) }
        if (systemIndex < 0) {
            return listOf(
                QuroChatMessage(role = "system", content = "You are a helpful assistant.$instruction")
            ) + messages
        }
        val out = messages.toMutableList()
        val original = out[systemIndex]
        out[systemIndex] = original.copy(content = original.content + instruction)
        return out
    }

    /**
     * 把对话消息列表序列化为 OpenAI 兼容的消息 JSON 数组字符串。
     *
     * 供原生层 `applyStructuredChatTemplate` / `generateStreamStructured` 使用。
     *
     * ## 与旧版的区别（B-2 工具调用透传修复）
     * 旧版把所有消息压成 `{role, content}`，并且把 `tool` 角色降级成 `user`，导致：
     * - 助手上一轮发起的 `tool_calls` **整个丢失**，模型下一轮看不到自己刚调过什么工具；
     * - 工具执行结果以裸文本混进 user 轮，模型分不清"这是工具返回"还是"用户又说了句话"。
     *
     * 多步工具编排因此必然断链——模型每轮都在失忆状态下重新决策，
     * 表现就是反复调用同一个工具或干脆放弃调用。
     *
     * 现在按 OpenAI 规范完整输出 `tool_calls` / `tool_call_id` / `role="tool"`：
     * - MNN 侧：原生 `applyStructuredChatTemplate` 对含这些字段的消息走 `"json"` 分支
     *   （MNN 文档 llm.md:636 的约定），交给 jinja 模板渲染；模板不支持时由内置 ChatML
     *   兜底渲染成 `<tool_response>` / `<tool_call>` 段。
     * - llama.cpp 侧：minja 模板原生支持这些字段。
     *
     * 注意：`role="tool"` 且 content 为空时仍会保留（工具可能返回空结果），
     * 只有既无 content 又无 tool_calls 的消息才会被丢弃。
     */
    fun encodeMessages(messages: List<QuroChatMessage>): String {
        val arr = JSONArray()
        for (m in messages) {
            val role = when (m.role.lowercase()) {
                "system" -> "system"
                "assistant" -> "assistant"
                "tool" -> "tool"
                else -> "user"
            }
            val hasToolCalls = !m.toolCalls.isNullOrEmpty()
            // 工具结果允许空内容；其余角色空内容没有任何信息量，直接跳过。
            if (m.content.isBlank() && !hasToolCalls && role != "tool") continue

            // 🔧 v1.0.49 兜底剥离思考块：assistant 的 stored content 若仍夹带 <think>…</think>
            // （含未闭合尾部），会被当成上一轮正文回放进新上下文、诱发本地模型乱恢复。
            val cleanContent = if (role == "assistant") {
                m.content.replace(Regex("<think>.*?(</think>|$)", RegexOption.DOT_MATCHES_ALL), "").trim()
            } else {
                m.content
            }
            val obj = JSONObject()
                .put("role", role)
                .put("content", cleanContent)

            if (role == "tool") {
                // tool_call_id 缺失时给一个稳定占位，避免模板取不到字段直接抛异常。
                obj.put("tool_call_id", m.toolCallId?.takeIf { it.isNotBlank() } ?: "call_local_0")
            }

            if (hasToolCalls) {
                val callsArr = JSONArray()
                m.toolCalls?.forEachIndexed { index, call ->
                    val fn = JSONObject()
                        .put("name", call.name)
                        // arguments 按 OpenAI 规范是「JSON 字符串」，不是对象，保持原样透传。
                        .put("arguments", call.arguments.ifBlank { "{}" })
                    callsArr.put(
                        JSONObject()
                            .put("id", call.id.ifBlank { "call_local_$index" })
                            .put("type", "function")
                            .put("function", fn)
                    )
                }
                obj.put("tool_calls", callsArr)
            }

            arr.put(obj)
        }
        return arr.toString()
    }

    /**
     * 把模型输出解析为工具调用列表。
     *
     * 支持两种输入格式：
     *
     * 1. **llama.cpp `parseToolCallResponse` 返回值**（OpenAI 兼容）：
     *    ```json
     *    {"tool_calls":[{"id":"...","type":"function","function":{"name":"...","arguments":"..."}}]}
     *    ```
     *    直接按 `tool_calls` 数组解析。
     *
     * 2. **MNN 路径 raw 模型文本**（含 `<tool_call>` 标签）：
     *    ```
     *    <tool_call>
     *    {"name":"get_current_time","arguments":{}}
     *    </tool_call>
     *    ```
     *    按 `<tool_call>...</tool_call>` 正则提取内部 JSON，解析 name + arguments。
     *
     * @return 解析出的工具调用列表；空列表表示无工具调用（调用方应走 Text 兜底）。
     */
    fun parseToolCalls(rawOrJson: String): List<QuroToolCall> = parseDetailed(rawOrJson).calls

    /**
     * 工具调用解析结果（含诊断信息）。
     *
     * 存在原因（B-2）：旧版解析失败一律静默返回空列表，模型明明想调工具、
     * 只是格式差一点（少个闭合标签、多包了一层 ```json、参数里有嵌套对象），
     * 用户侧只看到一段裸 JSON 文本，完全不知道"工具调用被吃掉了"。
     * 现在把"看起来想调工具但没解析成功"这件事显式暴露出来。
     *
     * @property calls 解析出的工具调用；空列表表示本轮没有工具调用。
     * @property sawMarker 文本里是否出现过工具调用特征（`<tool_call>` / `"tool_calls"` /
     *   `{"name":..,"arguments":..}`）。
     * @property diagnostic 解析异常的可读说明；无异常为 null。
     */
    data class ParseResult(
        val calls: List<QuroToolCall>,
        val sawMarker: Boolean,
        val diagnostic: String?,
    )

    /** 工具调用起始标签。 */
    private const val TOOL_CALL_OPEN = "<tool_call>"

    /** 工具调用结束标签。 */
    private const val TOOL_CALL_CLOSE = "</tool_call>"

    /**
     * 解析模型输出中的工具调用，并给出诊断信息。
     *
     * 覆盖的输入形态（按优先级尝试）：
     * 1. OpenAI 兼容 JSON：`{"tool_calls":[...]}`
     * 2. `<tool_call>{...}</tool_call>`（标准形态，可多段）
     * 3. `<tool_call>{...}`（生成被 max_tokens 截断，缺闭合标签）
     * 4. ` ```json {...} ``` ` 代码块包裹
     * 5. 整段输出就是一个裸 `{"name":..,"arguments":..}` 对象或对象数组
     *
     * 括号匹配采用**字符串感知的花括号配对扫描**，而不是正则 `\{.*?\}`——
     * 后者遇到 `{"name":"x","arguments":{"a":1}}` 这种嵌套参数会在第一个 `}` 处截断。
     *
     * @param rawOrJson 模型原始输出，或 llama.cpp `parseToolCallResponse` 的返回值。
     * @return 解析结果，绝不抛异常。
     */
    fun parseDetailed(rawOrJson: String): ParseResult {
        if (rawOrJson.isBlank()) {
            return ParseResult(emptyList(), sawMarker = false, diagnostic = null)
        }

        // 只在**强特征**下判定"模型想调工具"，避免把正文里恰好出现的 JSON 误报成失败的工具调用
        // （误报会给用户凭空多出一段警告文字，比漏报更糟）。
        val trimmed = rawOrJson.trim()
        val sawMarker = rawOrJson.contains(TOOL_CALL_OPEN) ||
            rawOrJson.contains("\"tool_calls\"") ||
            ((trimmed.startsWith("{") || trimmed.startsWith("[")) &&
                trimmed.contains("\"name\"") && trimmed.contains("\"arguments\""))

        val failures = mutableListOf<String>()

        // 路径 1：OpenAI 兼容 JSON（llama.cpp parseToolCallResponse 返回值）
        parseOpenAiEnvelope(rawOrJson.trim(), failures)?.let { calls ->
            if (calls.isNotEmpty()) return ParseResult(calls, sawMarker, null)
        }

        // 路径 2/3：<tool_call> 标签（含未闭合的截断形态）
        val tagged = parseTaggedCalls(rawOrJson, failures)
        if (tagged.isNotEmpty()) return ParseResult(tagged, sawMarker, diagnosticOf(failures))

        // 路径 4/5：代码块 / 裸 JSON 对象或数组
        val bare = parseBareJson(rawOrJson, failures)
        if (bare.isNotEmpty()) return ParseResult(bare, sawMarker, diagnosticOf(failures))

        if (sawMarker && failures.isEmpty()) {
            failures.add("文本中出现工具调用特征，但没有找到可解析的 JSON 对象")
        }
        return ParseResult(emptyList(), sawMarker, diagnosticOf(failures))
    }

    /**
     * 把失败原因列表压成一行诊断串。
     *
     * @param failures 失败原因；为空时返回 null。
     */
    private fun diagnosticOf(failures: List<String>): String? =
        if (failures.isEmpty()) null else failures.distinct().joinToString("；")

    /**
     * 解析 `{"tool_calls":[...]}` 信封。
     *
     * @param text 已 trim 的候选文本。
     * @param failures 失败原因收集器。
     * @return 解析出的调用列表；输入不是该形态时返回 null。
     */
    private fun parseOpenAiEnvelope(text: String, failures: MutableList<String>): List<QuroToolCall>? {
        if (!text.startsWith("{") || !text.contains("\"tool_calls\"")) return null
        val root = runCatching { JSONObject(text) }.getOrElse {
            failures.add("tool_calls 信封 JSON 解析失败：${it.message}")
            return null
        }
        if (!root.has("tool_calls") || root.isNull("tool_calls")) return null
        val arr = root.optJSONArray("tool_calls") ?: run {
            failures.add("tool_calls 字段不是数组")
            return null
        }
        val calls = mutableListOf<QuroToolCall>()
        for (i in 0 until arr.length()) {
            val tc = arr.optJSONObject(i) ?: continue
            val fn = tc.optJSONObject("function") ?: tc
            val name = fn.optString("name", "")
            if (name.isBlank()) {
                failures.add("第 ${i + 1} 个 tool_call 缺少 name 字段")
                continue
            }
            calls.add(
                QuroToolCall(
                    id = tc.optString("id", "").ifBlank { "call_local_$i" },
                    name = name,
                    arguments = normalizeArguments(fn.opt("arguments")),
                )
            )
        }
        return calls
    }

    /**
     * 解析 `<tool_call>` 标签包裹的调用（兼容缺失闭合标签的截断输出）。
     *
     * @param raw 模型原始输出。
     * @param failures 失败原因收集器。
     */
    private fun parseTaggedCalls(raw: String, failures: MutableList<String>): List<QuroToolCall> {
        val calls = mutableListOf<QuroToolCall>()
        var cursor = 0
        var index = 0
        while (true) {
            val open = raw.indexOf(TOOL_CALL_OPEN, cursor)
            if (open < 0) break
            val contentStart = open + TOOL_CALL_OPEN.length
            val close = raw.indexOf(TOOL_CALL_CLOSE, contentStart)
            val segment = if (close >= 0) {
                raw.substring(contentStart, close)
            } else {
                // 未闭合：生成被截断。仍然尝试从剩余文本里抠出完整 JSON 对象。
                failures.add("检测到未闭合的 <tool_call>（生成可能被最大长度截断），已尽力解析")
                raw.substring(contentStart)
            }
            cursor = if (close >= 0) close + TOOL_CALL_CLOSE.length else raw.length

            for (json in extractJsonObjects(segment)) {
                appendCall(calls, json, index, failures)?.let { index = it }
            }
            if (close < 0) break
        }
        return calls
    }

    /**
     * 解析代码块包裹或裸 JSON 形态的调用。
     *
     * @param raw 模型原始输出。
     * @param failures 失败原因收集器。
     */
    private fun parseBareJson(raw: String, failures: MutableList<String>): List<QuroToolCall> {
        // 去掉 ```json / ``` 围栏，只留内容。
        val unfenced = raw.replace(Regex("```[a-zA-Z]*"), " ").replace("```", " ")
        val trimmed = unfenced.trim()
        // 🛡️ 收紧（离线工具误触发修复）：只有当文本「本身就是 JSON」时才把它当工具调用解析，
        // 否则模型正文里偶发的 {"name":...,"arguments":...}（如返回结构化数据）会被误判成工具调用。
        // <tool_call> 包裹的形态已由 parseTaggedCalls 在前面优先处理，这里只兜底「裸 JSON」与「代码块 JSON」。
        // 判定标准：去围栏后整体首尾分别是 { [ 与 } ]（文本主体即那段 JSON），
        // 或仅含单个顶层 JSON 对象且前后夹带的解释性文字极少（≤ 32 字符）。
        val looksStandalone = (trimmed.startsWith("{") && trimmed.endsWith("}")) ||
            (trimmed.startsWith("[") && trimmed.endsWith("]"))
        val objects = extractJsonObjects(unfenced)
        val singleObj = objects.size == 1
        val leadingTrailing = if (singleObj) {
            val firstObj = objects.first()
            val startIdx = unfenced.indexOf(firstObj)
            val endIdx = startIdx + firstObj.length
            startIdx + (unfenced.length - endIdx)
        } else {
            Int.MAX_VALUE
        }
        val acceptAsTool = looksStandalone || (singleObj && leadingTrailing <= 32)
        if (!acceptAsTool) return emptyList()

        val calls = mutableListOf<QuroToolCall>()
        var index = 0
        for (json in objects) {
            // 只认带 name 的对象，避免把模型正文里的普通 JSON 误判成工具调用。
            if (!json.contains("\"name\"")) continue
            appendCall(calls, json, index, failures)?.let { index = it }
        }
        return calls
    }

    /**
     * 把一段 JSON 对象文本解析成 [QuroToolCall] 并追加。
     *
     * @param sink 输出列表。
     * @param json JSON 对象字符串。
     * @param index 当前序号（用于生成默认 id）。
     * @param failures 失败原因收集器。
     * @return 追加成功时返回下一个序号；未追加返回 null。
     */
    private fun appendCall(
        sink: MutableList<QuroToolCall>,
        json: String,
        index: Int,
        failures: MutableList<String>,
    ): Int? {
        val obj = runCatching { JSONObject(json) }.getOrElse {
            failures.add("工具调用 JSON 解析失败：${it.message}")
            return null
        }
        // 兼容 {"function":{"name":..,"arguments":..}} 与 {"name":..,"arguments":..} 两种写法。
        val fn = obj.optJSONObject("function") ?: obj
        val name = fn.optString("name", "")
        if (name.isBlank()) {
            failures.add("工具调用缺少 name 字段")
            return null
        }
        sink.add(
            QuroToolCall(
                id = obj.optString("id", "").ifBlank { "call_local_$index" },
                name = name,
                arguments = normalizeArguments(fn.opt("arguments") ?: fn.opt("parameters")),
            )
        )
        return index + 1
    }

    /**
     * 把 arguments 字段归一化为 JSON 对象字符串。
     *
     * 模型可能给出对象（`{"a":1}`）、字符串化对象（`"{\"a\":1}"`）或干脆缺省。
     *
     * @param value 原始 arguments 值。
     * @return 始终是可被 `JSONObject` 解析的字符串；无法识别时返回 `"{}"`。
     */
    private fun normalizeArguments(value: Any?): String {
        if (value == null || value == JSONObject.NULL) return "{}"
        if (value is String) {
            val trimmed = value.trim()
            if (trimmed.isEmpty()) return "{}"
            // 已经是 JSON 文本就直接用；否则包成 {"input": "..."} 之类不合适，退回空对象更安全。
            return if (trimmed.startsWith("{")) trimmed else "{}"
        }
        return value.toString()
    }

    /**
     * 从任意文本中扫描出所有**完整**的顶层 JSON 对象。
     *
     * 使用字符串感知的花括号配对：忽略双引号内的花括号，正确处理 `\"` 转义。
     * 这是替换旧版正则 `\{.*?\}` 的关键——后者对嵌套 arguments 会截断。
     *
     * @param text 待扫描文本。
     * @return 顶层 JSON 对象字符串列表（按出现顺序）。
     */
    private fun extractJsonObjects(text: String): List<String> {
        val out = mutableListOf<String>()
        var depth = 0
        var start = -1
        var inString = false
        var escaped = false
        for (i in text.indices) {
            val c = text[i]
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                continue
            }
            when (c) {
                '"' -> inString = true
                '{' -> {
                    if (depth == 0) start = i
                    depth++
                }
                '}' -> {
                    if (depth > 0) {
                        depth--
                        if (depth == 0 && start >= 0) {
                            out.add(text.substring(start, i + 1))
                            start = -1
                        }
                    }
                }
            }
        }
        return out
    }
}
