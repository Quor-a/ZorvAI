package com.ai.assistance.quro.llm

import com.ai.assistance.quro.core.QuroAttachment
import com.ai.assistance.quro.core.QuroChatMessage
import com.ai.assistance.quro.core.QuroLlmResult
import com.ai.assistance.quro.core.QuroToolCall
import com.ai.assistance.quro.core.model.QuroLocalModel
import com.ai.assistance.quro.core.model.QuroLocalModelType
import org.json.JSONArray
import org.json.JSONObject

/**
 * L1 · 跨进程载荷编解码。
 *
 * 存在原因：`IQuroLlmService.execLocal` 只接一个 String。为什么不给 AIDL 定义
 * Parcelable？两个理由：
 *   1. **契约演进成本**。本 App 的 AIDL 两侧永远同版本（同一个 APK 里的两个进程），
 *      用 JSON 可以随时加字段而不用担心 Parcel 读写顺序错位 —— 那种错位是
 *      「读到一个看似正常但完全错位的数据」，比解析失败难查得多。
 *   2. **字段可缺省**。QuroChatMessage / QuroLocalModel 有十几个带默认值的字段，
 *      Parcelable 要逐个 writeXxx/readXxx，漏一个就是默认值静默丢失。
 *      JSON 天然支持「没写 = 用默认值」。
 *
 * 保真原则：这里**只做序列化，不做任何业务变换**。
 *   QuroChatMessage 原样过去、原样回来。所有 prompt 组装（chat template）、
 *   思考段剥离、工具消息排序，都留在 `:llm` 侧的 `QuroLocalEngineNative` 里跑 ——
 *   那是它既有且已被真机验证的行为，两侧各做一遍必然漂移。
 *
 * ⚠️ 体积提醒：Binder 单次 transaction 上限约 1MB。本地对话的 messages 通常在
 *   几十 KB 以内（长上下文也就几百 KB），但附件路径数组在极端情况下可能逼近上限 ——
 *   调用方（QuroLocalEngineRemote）因此对编码结果做了长度预检。
 */
internal object QuroLlmWireCodec {

    // ─────────────────────────────────────────────────────────────────────
    // QuroLocalModel
    // ─────────────────────────────────────────────────────────────────────

    fun encodeModel(m: QuroLocalModel): JSONObject = JSONObject()
        .put("id", m.id)
        .put("type", m.type.name)
        .put("name", m.name)
        .put("path", m.path)
        .put("modelNames", JSONArray(m.modelNames as List<*>))
        .put("threads", m.threads)
        .put("contextSize", m.contextSize)
        .put("gpuLayers", m.gpuLayers)
        .put("useMmap", m.useMmap)
        .put("kvUnified", m.kvUnified)
        .put("backend", m.backend)
        .put("precision", m.precision)
        .put("memoryMode", m.memoryMode)

    fun decodeModel(o: JSONObject): QuroLocalModel = QuroLocalModel(
        id = o.optString("id", ""),
        // 未知类型退回 MNN：宁可走 MNN 路径失败并给出明确错误，
        // 也不要在 valueOf 抛异常时把整次请求打死（错误会更难定位）。
        type = runCatching { QuroLocalModelType.valueOf(o.optString("type", "MNN")) }
            .getOrDefault(QuroLocalModelType.MNN),
        name = o.optString("name", ""),
        path = o.optString("path", ""),
        modelNames = o.optJSONArray("modelNames")?.toStringList() ?: emptyList(),
        threads = o.optInt("threads", 0),
        contextSize = o.optInt("contextSize", 0),
        gpuLayers = o.optInt("gpuLayers", 0),
        useMmap = o.optBoolean("useMmap", false),
        kvUnified = o.optBoolean("kvUnified", true),
        backend = o.optString("backend", ""),
        precision = o.optString("precision", ""),
        memoryMode = o.optString("memoryMode", ""),
    )

    // ─────────────────────────────────────────────────────────────────────
    // QuroChatMessage
    // ─────────────────────────────────────────────────────────────────────

    fun encodeMessages(messages: List<QuroChatMessage>): JSONArray {
        val arr = JSONArray()
        for (m in messages) arr.put(encodeMessage(m))
        return arr
    }

    private fun encodeMessage(m: QuroChatMessage): JSONObject {
        val o = JSONObject()
            .put("role", m.role)
            .put("content", m.content)
        // 可空字段只在有值时写入：省体积，也让解码侧「字段不存在 = null」这条规则成立。
        m.toolCallId?.let { o.put("toolCallId", it) }
        m.toolName?.let { o.put("toolName", it) }
        m.reasoning?.let { o.put("reasoning", it) }
        m.toolCalls?.let { calls ->
            val ca = JSONArray()
            calls.forEach { c -> ca.put(encodeToolCall(c)) }
            o.put("toolCalls", ca)
        }
        m.attachments?.let { atts ->
            val aa = JSONArray()
            atts.forEach { a -> aa.put(encodeAttachment(a)) }
            o.put("attachments", aa)
        }
        return o
    }

    fun decodeMessages(arr: JSONArray): List<QuroChatMessage> =
        (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.let { decodeMessage(it) }
        }

    private fun decodeMessage(o: JSONObject): QuroChatMessage = QuroChatMessage(
        role = o.optString("role", "user"),
        content = o.optString("content", ""),
        toolCalls = o.optJSONArray("toolCalls")?.let { ca ->
            (0 until ca.length()).mapNotNull { i ->
                ca.optJSONObject(i)?.let { decodeToolCall(it) }
            }
        },
        toolCallId = o.optStringOrNull("toolCallId"),
        attachments = o.optJSONArray("attachments")?.let { aa ->
            (0 until aa.length()).mapNotNull { i ->
                aa.optJSONObject(i)?.let { decodeAttachment(it) }
            }
        },
        toolName = o.optStringOrNull("toolName"),
        reasoning = o.optStringOrNull("reasoning"),
    )

    private fun encodeToolCall(c: QuroToolCall): JSONObject = JSONObject()
        .put("id", c.id)
        .put("name", c.name)
        .put("arguments", c.arguments)
        .apply {
            // result / durationMs 是「执行后回填」字段，本地引擎产出的调用里通常为空。
            // 仍然带上，保证 assistant 历史里的工具结果跨进程不丢（回放上下文要用）。
            c.result?.let { put("result", it) }
            if (c.durationMs != 0L) put("durationMs", c.durationMs)
        }

    private fun decodeToolCall(o: JSONObject): QuroToolCall = QuroToolCall(
        id = o.optString("id", ""),
        name = o.optString("name", ""),
        arguments = o.optString("arguments", "{}"),
        result = o.optStringOrNull("result"),
        durationMs = o.optLong("durationMs", 0L),
    )

    private fun encodeAttachment(a: QuroAttachment): JSONObject = JSONObject()
        .put("id", a.id)
        .put("type", a.type)
        .put("uri", a.uri)
        .put("name", a.name)
        .put("mime", a.mime)
        .put("size", a.size)

    private fun decodeAttachment(o: JSONObject): QuroAttachment = QuroAttachment(
        id = o.optString("id", ""),
        type = o.optString("type", "file"),
        uri = o.optString("uri", ""),
        name = o.optString("name", ""),
        mime = o.optString("mime", ""),
        size = o.optLong("size", 0L),
    )

    // ─────────────────────────────────────────────────────────────────────
    // QuroLlmResult（终态，装在 onDone 的 statsJson 字段里）
    // ─────────────────────────────────────────────────────────────────────
    // 注意 QuroLlmResult.Error **不是**传输错误，而是引擎级业务失败
    // （典型：聊天门禁「模型未加载」）。它必须经 onDone 回到主进程并原样
    // 变成 QuroLlmResult.Error，才能保持与进程内路径完全一致的 UI 表现。

    fun encodeResult(r: QuroLlmResult): JSONObject = when (r) {
        is QuroLlmResult.Text -> JSONObject()
            .put("kind", "text")
            .put("content", r.content)
            .apply { r.reasoning?.let { put("reasoning", it) } }

        is QuroLlmResult.ToolCalls -> JSONObject()
            .put("kind", "tool_calls")
            .put("calls", JSONArray().apply { r.calls.forEach { put(encodeToolCall(it)) } })
            .apply {
                r.reasoning?.let { put("reasoning", it) }
                r.content?.let { put("content", it) }
            }

        is QuroLlmResult.Error -> JSONObject()
            .put("kind", "error")
            .put("message", r.message)
    }

    fun decodeResult(o: JSONObject): QuroLlmResult = when (o.optString("kind", "text")) {
        "error" -> QuroLlmResult.Error(o.optString("message", "本地推理失败（服务端未给出原因）"))

        "tool_calls" -> QuroLlmResult.ToolCalls(
            calls = o.optJSONArray("calls")?.let { ca ->
                (0 until ca.length()).mapNotNull { i ->
                    ca.optJSONObject(i)?.let { decodeToolCall(it) }
                }
            } ?: emptyList(),
            reasoning = o.optStringOrNull("reasoning"),
            content = o.optStringOrNull("content"),
        )

        else -> QuroLlmResult.Text(
            content = o.optString("content", ""),
            reasoning = o.optStringOrNull("reasoning"),
        )
    }

    // ─────────────────────────────────────────────────────────────────────

    /**
     * 真正的「字段不存在或为 JSON null → null」。
     *
     * `optString` 不能直接用：它对 JSON null 返回字符串 `"null"`，对不存在的键返回
     * 调用方给的默认值 —— 两者都无法区分「没这个字段」。而 QuroChatMessage 的
     * toolCallId / toolName / reasoning 里，**null 与空串是不同语义**
     * （null = 这条消息不是工具结果；空串 = 是工具结果但 id 缺失），必须分开。
     */
    private fun JSONObject.optStringOrNull(key: String): String? =
        if (!has(key) || isNull(key)) null else optString(key, "")

    private fun JSONArray.toStringList(): List<String> =
        (0 until length()).mapNotNull { i -> optString(i, "").takeIf { it.isNotEmpty() } }
}
