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
 * 3. [parseDetailed] / [parseToolCalls]：把模型输出解析为 [QuroToolCall] 列表。
 *    **解析本体已迁到 [QuroToolCallRepair]**（宽松 JSON 解析 + 多标签族 +
 *    参数形态归一 + 工具名纠错），本对象只保留兼容包装与 tools/messages 编解码职责。
 *    端侧模型吐单引号、裸键名、缺闭合括号、函数式调用等畸形形态时，
 *    [QuroToolCallRepair] 负责抢救；本层不再自己解析。
 * 4. [sanitizeForDisplay]：剥离正文里的工具调用标记，保证 `<tool_call>` 原文绝不上屏。
 * 5. [toolNamesOf]：从 tools JSON 里取出工具名，供解析纠错使用。
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

        // 📌 少样本格式示例（N11b）。
        //
        // 只给 `<function-name>` / `<args-json-object>` 这类占位符时，端侧小模型的典型失败是
        // **原样照抄占位符**（真的吐出 "<function-name>"）、漏引号、或把参数写成 `name=value`。
        // 给一个用**真实工具名**拼出的具体示例，格式合法率显著上升——这是端侧 Agent 的通行做法
        // （PokeClaw 把"harness 问题"与"模型能力问题"分开治理，格式示例属前者）。
        //
        // ⚠️ 必须显式声明"仅示范写法"：否则模型会把示例里的工具当成默认动作，对着任何问题都调它。
        sampleCallFor(arr)?.let { sample ->
            sb.append("\n\nExample (format only — do NOT copy the function name or the argument values):\n")
            sb.append("<tool_call>\n").append(sample).append("\n</tool_call>")
        }
        return sb.toString()
    }

    /**
     * 用 tools 列表里第一个工具拼一个具体的调用示例。
     *
     * `arguments` 按该工具 JSON Schema 里声明的 `required` 字段生成骨架，
     * 这样示例的参数形状与真实 schema 对齐（模型能顺带学到"必填字段长什么样"），
     * 而不是给一个 `{}` 让人以为参数可以随便省。
     *
     * @param arr OpenAI 兼容的 tools 数组。
     * @return 形如 `{"name":"x","arguments":{...}}` 的示例串；列表里没有合法工具时返回 null。
     */
    private fun sampleCallFor(arr: JSONArray): String? {
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            val fn = item.optJSONObject("function") ?: item
            val name = fn.optString("name", "")
            if (name.isBlank()) continue

            val params = fn.optJSONObject("parameters")
            val props = params?.optJSONObject("properties")
            val required = params?.optJSONArray("required")
            val args = JSONObject()
            if (props != null && required != null) {
                for (j in 0 until required.length()) {
                    val key = required.optString(j, "")
                    if (key.isBlank() || !props.has(key)) continue
                    args.put(key, placeholderFor(props.optJSONObject(key)?.optString("type", "string")))
                }
            }
            // 手工拼串而不整体 JSONObject.toString()：真实 org.json 用 HashMap，
            // key 顺序不稳定；示例串里 name 必须稳定出现在 arguments 之前。
            return "{\"name\": \"" + name + "\", \"arguments\": " + args.toString() + "}"
        }
        return null
    }

    /** 按 JSON Schema 的 `type` 给一个占位值，让示例的参数形状与真实 schema 一致。 */
    private fun placeholderFor(type: String?): Any = when (type?.lowercase()) {
        "number", "integer" -> 0
        "boolean" -> false
        "array" -> JSONArray()
        "object" -> JSONObject()
        else -> ""
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
        // 🔧 toolfix-deepseek（本地对齐）：剔除工具调用组之间插队的「⏳ 正在执行」等占位气泡，
        // 这些可见气泡随 toLlmMessages 进入上下文，落在 assistant[tool_calls] 与 tool 结果之间，
        // 同样违反本地模板的工具调用顺序约束。剔除后 tool 结果贴回 assistant 之后、序列合法。
        val ordered = filterToolCallOrdering(messages)
        val arr = JSONArray()
        for (m in ordered) {
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
     * 与 [QuroLlmClient.normalizeToolCallMessages] 同语义、供本地模板用的工具调用顺序过滤。
     *
     * 任何带 tool_calls 的 assistant 消息之后必须紧跟覆盖每个 tool_call_id 的 role=tool 消息；
     * 若中间插入了非 tool 消息（典型即「⏳ 正在执行」UI 占位气泡），剔除该插队消息，
     * 让 tool 结果能正确贴回 assistant 之后。孤儿 tool 结果（tool_call_id 无对应开放调用）一并丢弃。
     */
    private fun filterToolCallOrdering(input: List<QuroChatMessage>): List<QuroChatMessage> {
        val out = ArrayList<QuroChatMessage>(input.size)
        var open = linkedSetOf<String>()
        for (m in input) {
            when {
                m.toolCalls != null && m.toolCalls.isNotEmpty() -> {
                    open = m.toolCalls.map { it.id }.toCollection(linkedSetOf())
                    out.add(m)
                }

                m.role == "tool" -> {
                    if (m.toolCallId != null && m.toolCallId in open) {
                        out.add(m)
                        open.remove(m.toolCallId)
                    }
                }

                else -> {
                    if (open.isEmpty()) out.add(m)
                }
            }
        }
        return out
    }

    /**
     * 把模型输出解析为工具调用列表。
     *
     * 本函数是 [QuroToolCallRepair.extract] 的兼容包装——解析逻辑已全部迁到
     * [QuroToolCallRepair]（宽松 JSON 解析 + 多标签族 + 参数形态归一 + 工具名纠错），
     * 这里只维持既有调用方的返回类型不变。
     */
    fun parseToolCalls(rawOrJson: String): List<QuroToolCall> = parseDetailed(rawOrJson).calls

    /**
     * 解析模型输出中的工具调用，并给出诊断信息。
     *
     * @param rawOrJson 模型原始输出，或 llama.cpp `parseToolCallResponse` 的返回值。
     * @return 解析结果，绝不抛异常。
     */
    fun parseDetailed(rawOrJson: String): ParseResult = parseDetailed(rawOrJson, emptySet())

    /**
     * 带**已知工具名**的解析重载：启用工具名纠错（大小写 / 下划线 / 编辑距离最近匹配）。
     *
     * 端侧模型对工具名几乎无法逐字复制，一个字符的偏差在下游就是 `NOT_FOUND`，
     * 整条任务链断掉。而候选集是本轮**实际下发**的封闭集合，最近邻纠正是有依据的。
     *
     * @param rawOrJson 模型原始输出。
     * @param knownNames 本轮下发的工具名集合；传空集合则不纠正（保持与旧行为可对齐）。
     */
    fun parseDetailed(rawOrJson: String, knownNames: Collection<String>): ParseResult {
        val outcome = QuroToolCallRepair.extract(rawOrJson, knownNames)
        return ParseResult(outcome.calls, outcome.sawMarker, outcome.diagnostic)
    }

    /**
     * 从 [encodeTools] 产出的 tools JSON 里取出全部工具名，供 [parseDetailed] 纠错使用。
     *
     * @param toolsJson OpenAI 兼容的 tools JSON 数组字符串。
     * @return 工具名列表；解析失败返回空列表。
     */
    fun toolNamesOf(toolsJson: String?): List<String> {
        if (toolsJson.isNullOrBlank()) return emptyList()
        val arr = runCatching { JSONArray(toolsJson) }.getOrNull() ?: return emptyList()
        val out = ArrayList<String>(arr.length())
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            val fn = item.optJSONObject("function") ?: item
            val name = fn.optString("name", "")
            if (name.isNotBlank()) out.add(name)
        }
        return out
    }

    /**
     * 面向 UI 的正文清洗：剥离全部工具调用标记。
     *
     * 无论解析成功与否，可见气泡里**绝不允许**出现 `<tool_call>` 原文。
     * 旧实现在解析失败时把原文连同警告一起推给用户，观感就是「工具调用显示成了标签」。
     *
     * @param text 候选正文。
     * @return 剥离后的正文；无可剥离内容时原样返回。
     */
    fun sanitizeForDisplay(text: String): String = QuroToolCallRepair.sanitizeVisible(text)

    /**
     * 工具调用解析结果（含诊断信息）。
     *
     * 存在原因（B-2）：旧版解析失败一律静默返回空列表，模型明明想调工具、
     * 只是格式差一点，用户侧只看到一段裸 JSON 文本，完全不知道"工具调用被吃掉了"。
     * 现在把"看起来想调工具但没解析成功"显式暴露出来。
     *
     * @property calls 解析出的工具调用；空列表表示本轮没有工具调用。
     * @property sawMarker 文本里是否出现过工具调用特征（`<tool_call>` / `"tool_calls"` /
     *   `{"name":..,"arguments":..}`）。
     * @property diagnostic 解析过程中值得记录的情况：成功时是**修复动作**摘要
     *   （如"检测到未闭合的 &lt;tool_call&gt;"），失败时是**失败原因**；
     *   完全干净的一次解析为 null。
     */
    data class ParseResult(
        val calls: List<QuroToolCall>,
        val sawMarker: Boolean,
        val diagnostic: String?,
    )
}
