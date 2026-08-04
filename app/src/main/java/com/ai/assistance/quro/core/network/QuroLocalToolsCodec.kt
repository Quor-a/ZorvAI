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
 * 3. [parseToolCalls]：把模型输出解析为 [QuroToolCall] 列表。支持两种输入：
 *    - llama.cpp `parseToolCallResponse` 返回的 OpenAI 兼容 JSON `{"tool_calls":[...]}`
 *    - MNN 路径的 raw 模型文本（含 `<tool_call>...</tool_call>` 标签）
 *
 * 设计取舍：
 * - 仅用 org.json，无额外依赖（与 QuroLlmClient 一致）。
 * - 所有解析均用 runCatching 包裹（调用方负责），失败时返回 emptyList → 走 Text 兜底。
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
     * 把对话消息列表序列化为 `[{role, content}]` JSON 数组字符串。
     *
     * 供原生层 `applyStructuredChatTemplate` / `generateStreamStructured` 使用。
     * 仅保留 role + content；tool_calls / tool_call_id 等字段由调用方按需扩展。
     *
     * 角色映射：与 [QuroLocalEngineNative.buildMnnHistory] 一致——
     * "tool" → "user"（MNN 无 tool 角色），其余原样保留。
     */
    fun encodeMessages(messages: List<QuroChatMessage>): String {
        val arr = JSONArray()
        for (m in messages) {
            if (m.content.isBlank()) continue
            val role = when (m.role.lowercase()) {
                "system" -> "system"
                "assistant" -> "assistant"
                "tool" -> "user"
                else -> "user"
            }
            arr.put(
                JSONObject()
                    .put("role", role)
                    .put("content", m.content)
            )
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
    fun parseToolCalls(rawOrJson: String): List<QuroToolCall> {
        if (rawOrJson.isBlank()) return emptyList()
        val calls = mutableListOf<QuroToolCall>()

        // 路径 1：OpenAI 兼容 JSON（llama.cpp parseToolCallResponse 返回值）
        val oaiResult = runCatching {
            val root = JSONObject(rawOrJson)
            if (root.has("tool_calls") && !root.isNull("tool_calls")) {
                val arr = root.getJSONArray("tool_calls")
                for (i in 0 until arr.length()) {
                    val tc = arr.getJSONObject(i)
                    val fn = tc.optJSONObject("function") ?: continue
                    calls.add(
                        QuroToolCall(
                            id = tc.optString("id", "call_local_$i"),
                            name = fn.optString("name", ""),
                            arguments = fn.optString("arguments", "{}"),
                        )
                    )
                }
            }
        }
        if (oaiResult.isSuccess && calls.isNotEmpty()) return calls

        // 路径 2：raw 模型文本中的 <tool_call>...</tool_call> 标签
        calls.clear()
        val tagRegex = Regex("""<tool_call>\s*(\{.*?\})\s*</tool_call>""", RegexOption.DOT_MATCHES_ALL)
        var idx = 0
        for (match in tagRegex.findAll(rawOrJson)) {
            val jsonStr = match.groupValues[1]
            val parsed = runCatching { JSONObject(jsonStr) }.getOrNull() ?: continue
            val name = parsed.optString("name", "")
            if (name.isBlank()) continue
            val arguments = parsed.opt("arguments")?.toString() ?: "{}"
            calls.add(
                QuroToolCall(
                    id = "call_local_$idx",
                    name = name,
                    arguments = arguments,
                )
            )
            idx++
        }
        return calls
    }
}
