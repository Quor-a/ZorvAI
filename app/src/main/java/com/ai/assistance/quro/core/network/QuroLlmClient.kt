package com.ai.assistance.quro.core.network

import android.util.Log
import com.ai.assistance.quro.core.QuroAttachmentKit
import com.ai.assistance.quro.core.QuroChatMessage
import com.ai.assistance.quro.core.QuroLlmResult
import com.ai.assistance.quro.core.QuroToolCall
import com.ai.assistance.quro.core.QuroToolSpec
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

private const val TAG = "QuroLlm"

/**
 * 单次 HTTP 调用的硬超时护栏（毫秒）。
 * 作用：OkHttp 自带 connect/read 超时在「代理挂起 / 端点假死」时仍可能长时间不返回，
 * 导致整条对话协程卡在「思考中」、bot 永远不回复且无任何报错（用户感知为「完全没反应」）。
 * 这里用 withTimeout 在 NET_CALL_TIMEOUT_MS 后强制取消本次调用并转成明确错误气泡，
 * 杜绝「永久静默」——最坏情况用户也会看到「⚠️ 连接模型服务超时」而非无限转圈。
 * 设 90s，比 OkHttp 的 120s readTimeout 更早触发，确保本护栏是最终裁决者。
 */
private const val NET_CALL_TIMEOUT_MS = 90_000L

/**
 * Quro LLM 客户端（原创）：对接 OpenAI 兼容的 /chat/completions，
 * 支持 function/tool calling。仅用 OkHttp + org.json，无第三方序列化依赖。
 *
 * 设计取舍（对齐 Calw OS 稳定方案）：
 *  - 采用「同步一次性请求」：模型完整生成后一次性返回，UI 拿到完整回复再渲染。
 *    不自行实现 SSE 逐字写回——后者会高频触发 UI 重组，破坏对话框的
 *    思考气泡 / 工具块 / 卡片 / 复制 / 重生成等功能，且不同模型流式字段结构
 *    差异大、极易崩溃或串入脏数据（如 JSON null）。
 *  - 兼容性：只解析标准 OpenAI 响应（message.content / reasoning_content /
 *    reasoning / thinking / tool_calls），不假设任何单一模型特例。
 *  - 重试：网关类临时故障（5xx / 429）与网络异常自动重试，4xx 不重试。
 *
 * 调试：所有请求/响应关键信息通过 Logcat 输出（tag=QuroLlm），
 * 用 adb logcat -s QuroLlm:* 可实时查看工具调用链路是否正常。
 */
class QuroLlmClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build(),
) {
    companion object {
        /** 单次响应体最大 4MB；超过此限制的响应（如 MiMo 超长 reasoning）直接截断，
         * 作为内存护栏，避免超大响应直接 OOM。 */
        const val MAX_RESPONSE_BYTES = 4 * 1024 * 1024
    }

    suspend fun chat(
        baseUrl: String,
        apiKey: String,
        model: String,
        messages: List<QuroChatMessage>,
        temperature: Float,
        maxTokens: Int,
        tools: List<QuroToolSpec> = emptyList(),
    ): QuroLlmResult {
        val normalized = baseUrl.trim().trimEnd('/')
        val url =
            if (normalized.endsWith("/chat/completions")) {
                normalized
            } else {
                "$normalized/chat/completions"
            }
        val body = JSONObject().apply {
            put("model", model)
            put("temperature", temperature)
            put("max_tokens", maxTokens)
            put("messages", JSONArray().also { arr ->
                messages.forEach { m -> arr.put(messageToJson(m)) }
            })
            if (tools.isNotEmpty()) {
                put("tools", JSONArray().also { arr ->
                    tools.forEach { t ->
                        arr.put(
                            JSONObject().put("type", "function").put(
                                "function",
                                JSONObject()
                                    .put("name", t.name)
                                    .put("description", t.description)
                                    .put("parameters", JSONObject(t.parametersJson)),
                            ),
                        )
                    }
                })
                put("tool_choice", "auto")
            }
        }
        val bodyStr = body.toString()
        // ===== 调试日志：请求体概览（Logcat tag=QuroLlm）=====
        Log.i(TAG, ">>> REQUEST  model=$model url=$url messages=${messages.size} tools=${tools.size} maxTokens=$maxTokens body=${bodyStr.length}ch")
        if (tools.isNotEmpty()) {
            Log.d(TAG, "    tool_names=[${tools.joinToString(", ") { it.name }}]")
            if (tools.size > 25) Log.w(TAG, "    ⚠️ 工具数量 ${tools.size} 偏多（内置工具+技能）！部分 API 中转可能静默丢弃 tools 字段，导致模型无法调用工具。可考虑关闭部分技能的「常驻系统提示词」或在设置关闭「完整工具集」。")
        }
        val req = Request.Builder().url(url)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(bodyStr.toRequestBody("application/json".toMediaType()))
            .build()
        // 重试策略：网关类临时故障（5xx / 429）与网络异常（超时/连接失败）自动重试，
        // 避免 openresty 等反向代理偶发 502/503 直接把原始错误甩给用户。
        // 4xx（鉴权/参数错误）不重试——属于确定性失败。
        val maxRetries = 2
        val retryableCodes = setOf(429, 500, 502, 503, 504)
        var lastErr: String? = null
        for (attempt in 0..maxRetries) {
            if (attempt > 0) {
                val backoff = 800L * attempt
                Log.w(TAG, "<<< RETRY attempt=$attempt/${maxRetries} after ${backoff}ms (prev=${lastErr ?: "n/a"})")
                Thread.sleep(backoff)
            }
            try {
                val callResult = withTimeout(NET_CALL_TIMEOUT_MS) {
                    client.newCall(req).execute().use { resp ->
                        val rawBody = resp.body?.string().orEmpty()
                        // 🛡️ 响应体超限截断：MiMo 等推理模型可能返回数 MB 的 reasoning_content，
                        // org.json 递归解析时 StackOverflowError → "stack size 8188KB"。
                        // 截断到 MAX_RESPONSE_BYTES 后仍可解析出 choices[0]（尾部被裁的是 reasoning）。
                        val text = if (rawBody.length > MAX_RESPONSE_BYTES) {
                            Log.w(TAG, "⚠️ 响应体超限 ${rawBody.length}ch > ${MAX_RESPONSE_BYTES}ch，截断处理")
                            rawBody.take(MAX_RESPONSE_BYTES)
                        } else {
                            rawBody
                        }
                        // ===== 调试日志：响应概览 =====
                        val preview = text.take(300).replace("\n", "\\n")
                        Log.i(TAG, "<<< RESPONSE HTTP=${resp.code} body=${text.length}ch preview=$preview")
                        if (!resp.isSuccessful) {
                            lastErr = "HTTP ${resp.code}"
                            if (resp.code in retryableCodes && attempt < maxRetries) {
                                return@use null // 临时故障 → 进入下一次重试
                            }
                            return@use QuroLlmResult.Error(friendlyHttpError(resp.code, text))
                        }
                        return@use parse(text)
                    }
                }
                if (callResult != null) return callResult
                // callResult == null：临时故障（5xx/429），lastErr 已记录，进入下一轮重试
            } catch (e: TimeoutCancellationException) {
                // 硬超时：单次调用 90s 内无响应（端点假死 / 代理挂起）→ 转成明确报错气泡，
                // 杜绝「思考中」永久卡死、bot 完全没反应且无任何提示。
                return QuroLlmResult.Error("连接模型服务超时（${NET_CALL_TIMEOUT_MS / 1000} 秒无响应），请检查网络或模型服务地址后重试")
            } catch (e: Exception) {
                lastErr = e.message
                Log.e(TAG, "<<< NETWORK ERROR attempt=$attempt: ${e.message}", e)
                if (attempt < maxRetries) continue // 超时/连接失败等网络异常重试
                return QuroLlmResult.Error(friendlyNetError(e))
            }
        }
        return QuroLlmResult.Error(lastErr ?: "unknown error")
    }

    /** 把网关 HTML/JSON 错误体转成简洁中文提示，避免把 <html>502</html> 甩给用户。 */
    private fun friendlyHttpError(code: Int, raw: String): String {
        val plain = raw.replace(Regex("<[^>]+>"), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
        return when {
            plain.contains("502") || plain.contains("Bad Gateway", ignoreCase = true) ->
                "模型服务网关暂时不可用（502 Bad Gateway），请稍后重试"
            plain.contains("503") || plain.contains("Service Unavailable", ignoreCase = true) ->
                "模型服务暂时不可用（503），请稍后重试"
            plain.contains("504") || plain.contains("Gateway Timeout", ignoreCase = true) ->
                "模型服务响应超时（504），请稍后重试"
            plain.contains("429") || plain.contains("Too Many Requests", ignoreCase = true) ->
                "请求过于频繁（429），请稍后重试"
            else -> "请求失败（HTTP $code）：${plain.take(200)}"
        }
    }

    private fun friendlyNetError(e: Exception): String {
        val m = e.message ?: "network error"
        return when {
            m.contains("timed out", ignoreCase = true) -> "连接模型服务超时，请检查网络后重试"
            m.contains("Unable to resolve host", ignoreCase = true) ||
                m.contains("No address associated", ignoreCase = true) ->
                "无法连接模型服务，请检查网络/地址后重试"
            else -> "网络错误：$m"
        }
    }

    private fun messageToJson(m: QuroChatMessage): JSONObject {
        val o = JSONObject().put("role", m.role)
        val images = m.attachments?.filter { it.type == "image" } ?: emptyList()
        if (m.toolCallId != null) {
            o.put("tool_call_id", m.toolCallId)
            o.put("content", m.content)
        } else if (m.toolCalls != null) {
            o.put("content", m.content.ifBlank { " " })
            o.put("tool_calls", JSONArray().also { arr ->
                m.toolCalls.forEach { tc ->
                    arr.put(
                        JSONObject().put("id", tc.id).put("type", "function").put(
                            "function",
                            JSONObject().put("name", tc.name).put("arguments", tc.arguments),
                        ),
                    )
                }
            })
        } else if (images.isNotEmpty()) {
            // 多模态：文本段 + 图片段（base64 data URI），供视觉模型理解图片
            val arr = JSONArray()
            arr.put(JSONObject().put("type", "text").put("text", m.content))
            images.forEach { att ->
                val dataUri = QuroAttachmentKit.toVisionDataUri(att.uri)
                if (dataUri != null) {
                    arr.put(
                        JSONObject().put("type", "image_url").put(
                            "image_url",
                            JSONObject().put("url", dataUri),
                        ),
                    )
                }
            }
            o.put("content", arr)
        } else {
            o.put("content", m.content)
        }
        return o
    }

    private fun parse(json: String): QuroLlmResult = try {
        val root = JSONObject(json)
        val choice = root.getJSONArray("choices").getJSONObject(0)
        val msg = choice.getJSONObject("message")
        // 统一提取 reasoning（无论本轮是纯文本还是工具调用，MiMo 等 reasoning 模型
        // 都可能在 tool_calls 的同时返回 reasoning_content；必须保留并在回传时携带）。
        // 兼容多种字段名：reasoning_content / reasoning / thinking。
        val reasoning = safeString(msg, "reasoning_content")
            ?: safeString(msg, "reasoning")
            ?: safeString(msg, "thinking")
        if (msg.has("tool_calls") && !msg.isNull("tool_calls")) {
            val arr = msg.getJSONArray("tool_calls")
            val calls = mutableListOf<QuroToolCall>()
            for (i in 0 until arr.length()) {
                val tc = arr.getJSONObject(i)
                val fn = tc.getJSONObject("function")
                calls.add(
                    QuroToolCall(
                        id = tc.optString("id", "call_$i"),
                        name = fn.getString("name"),
                        arguments = fn.optString("arguments", "{}"),
                    ),
                )
            }
            Log.i(TAG, "<<< PARSE tool_calls=${calls.size} reasoningBlank=${reasoning.isNullOrBlank()} first=${calls.firstOrNull()?.name}")
            QuroLlmResult.ToolCalls(calls, reasoning)
        } else {
            // 小米 MiMo 等推理模型在 reason 模式下 content 可能为空、仅返回 reasoning_content。
            // ⚠️ 不再将 reasoning 兜底到 content！此前 content=reasoning 导致思考文本同时写入
            //   content 与 reasoning 两个字段 → ChatScreen 既渲染正文气泡（原始 HTML）又渲染
            //   ThinkBubble（同样原始 HTML），出现「思考内容错乱到其他地方」的症状。
            // 正确做法：content 为空时返回空字符串，由 QuroAssistant 决定是否展示占位符；
            //   reasoning 始终只走 reasoning 字段，仅在用户开启「深度思考」时展示。
            val rawContent = safeString(msg, "content")?.takeIf { it.isNotBlank() } ?: ""
            QuroLlmResult.Text(rawContent, reasoning)
        }
    } catch (e: Exception) {
        QuroLlmResult.Error(e.message ?: "parse error")
    }

    /**
     * 健壮取字符串：字段缺失 / JSON null / 字面量 "null" / 非字符串类型 一律返回 null。
     * 修复 Android org.json 的两大坑：
     *  - optString(key,"") 在值为 JSON null 时返回字面量 "null"
     *  - getString(key) 在值不是字符串类型时抛异常
     */
    private fun safeString(o: JSONObject, key: String): String? {
        if (!o.has(key)) return null
        if (o.isNull(key)) return null
        return try { o.getString(key) } catch (_: Exception) { null }?.takeIf { it != "null" }
    }
}
