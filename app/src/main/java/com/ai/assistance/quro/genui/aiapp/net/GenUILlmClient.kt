package com.ai.assistance.quro.genui.aiapp.net

import android.util.Log
import com.ai.assistance.quro.genui.aiapp.core.GenUIChatMessage
import com.ai.assistance.quro.genui.aiapp.core.GenUILlmResult
import com.ai.assistance.quro.genui.aiapp.core.GenUIToolCall
import com.ai.assistance.quro.genui.aiapp.core.GenUIToolSpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext

private const val TAG = "GenUILlm"

/** max_tokens 硬性上限 — 防止误配超大值被上游 500 */
private const val MAX_OUTPUT_TOKENS = 131_072

/** HTTP 调用硬超时护栏（毫秒）— 防止阻塞式 execute() 永久卡死 */
private const val NET_CALL_TIMEOUT_MS = 90_000L

/** 响应体最大 4MB — 内存护栏 */
private const val MAX_RESPONSE_BYTES = 4 * 1024 * 1024

/**
 * LLM 客户端（稳定方案）
 *
 * 设计取舍：
 * - 采用同步一次性请求：模型完整生成后一次性返回，UI 拿到完整回复再渲染
 * - 兼容性：只解析标准 OpenAI 响应
 * - 重试：网关类临时故障（5xx / 429）自动重试，4xx 不重试
 * - 推理模型适配：o1/o3/o4 系列用 max_completion_tokens，省略 temperature
 * - 端点补全：裸 host 自动补 /v1/chat/completions
 */
class GenUILlmClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()
) {
    companion object {
        const val COMPANION_MAX_RESPONSE_BYTES = MAX_RESPONSE_BYTES
    }

    /**
     * 发送聊天请求
     */
    suspend fun chat(
        baseUrl: String,
        apiKey: String,
        model: String,
        messages: List<GenUIChatMessage>,
        temperature: Float = 0.7f,
        maxTokens: Int = MAX_OUTPUT_TOKENS,
        tools: List<GenUIToolSpec>? = null,
        stream: Boolean = false,
        onToken: ((String) -> Unit)? = null,
        onThinking: ((String) -> Unit)? = null
    ): GenUILlmResult = withContext(Dispatchers.IO) {
        // 端点补全
        val url = completeEndpoint(baseUrl)

        // 推理模型检测（o1/o3/o4 系列）
        val isReasoningModel = Regex("(?i)^o[0-9]").containsMatchIn(model.trim())

        // max_tokens 硬性上限护栏
        val effectiveMaxTokens = maxTokens.coerceAtMost(MAX_OUTPUT_TOKENS)
        if (effectiveMaxTokens != maxTokens) {
            Log.w(TAG, ">>> max_tokens 钳到 $effectiveMaxTokens（原 $maxTokens 超限）")
        }

        // 构建请求体
        val body = JSONObject().apply {
            put("model", model)
            if (isReasoningModel) {
                Log.i(TAG, ">>> reasoning model: max_completion_tokens, 省略 temperature (model=$model)")
                put("max_completion_tokens", effectiveMaxTokens)
            } else {
                put("temperature", temperature)
                put("max_tokens", effectiveMaxTokens)
            }
            put("messages", JSONArray().also { arr ->
                normalizeToolCallMessages(messages).forEach { m ->
                    arr.put(messageToJson(m, emitReasoning = !isReasoningModel))
                }
            })
            if (!tools.isNullOrEmpty()) {
                put("tools", JSONArray().also { arr ->
                    tools.forEach { t ->
                        arr.put(
                            JSONObject().put("type", "function").put(
                                "function",
                                JSONObject()
                                    .put("name", t.name)
                                    .put("description", t.description)
                                    .put("parameters", try {
                                        JSONObject(t.parametersJson)
                                    } catch (_: Exception) {
                                        t.parametersJson
                                    })
                            )
                        )
                    }
                })
                put("tool_choice", "auto")
            }
            if (stream) put("stream", true)
        }

        val bodyStr = body.toString()
        Log.i(TAG, ">>> REQUEST model=$model url=$url messages=${messages.size} tools=${tools?.size ?: 0} maxTokens=$effectiveMaxTokens")

        val req = Request.Builder().url(url)
            .apply {
                if (apiKey.isNotBlank()) {
                    addHeader("Authorization", "Bearer $apiKey")
                }
                addHeader("Content-Type", "application/json")
                addHeader("Accept", "text/event-stream")
            }
            .post(bodyStr.toRequestBody("application/json".toMediaType()))
            .build()

        // 流式路径
        if (stream && onToken != null) {
            return@withContext streamChat(req, bodyStr, onToken, onThinking)
        }

        // 非流式路径 — 带重试
        val maxRetries = 2
        val retryableCodes = setOf(429, 500, 502, 503, 504)
        var lastErr: String? = null

        for (attempt in 0..maxRetries) {
            if (attempt > 0) {
                val backoff = 800L * attempt
                Log.w(TAG, "<<< RETRY attempt=$attempt/$maxRetries after ${backoff}ms")
                delay(backoff)
            }

            val call = client.newCall(req)
            val timedOut = AtomicBoolean(false)
            val cancelHook = coroutineContext[Job]?.invokeOnCompletion { cause ->
                if (cause is CancellationException) runCatching { call.cancel() }
            }
            val timeoutJob = kotlinx.coroutines.CoroutineScope(coroutineContext).launch {
                delay(NET_CALL_TIMEOUT_MS)
                timedOut.set(true)
                runCatching { call.cancel() }
            }

            try {
                val callResult = call.execute().use { resp ->
                    val rawBody = resp.body?.string().orEmpty()
                    val text = if (rawBody.length > MAX_RESPONSE_BYTES) {
                        Log.w(TAG, "响应体超限 ${rawBody.length}ch, 截断处理")
                        rawBody.take(MAX_RESPONSE_BYTES)
                    } else rawBody

                    Log.i(TAG, "<<< RESPONSE HTTP=${resp.code} body=${text.length}ch")

                    if (!resp.isSuccessful) {
                        lastErr = "HTTP ${resp.code}"
                        if (resp.code in retryableCodes && attempt < maxRetries) {
                            return@use null // 重试
                        }
                        return@use GenUILlmResult.Error(friendlyHttpError(resp.code, text))
                    }
                    return@use parse(text)
                }
                if (callResult != null) {
                    return@withContext callResult
                }
            } catch (e: Exception) {
                when {
                    timedOut.get() ->
                        return@withContext GenUILlmResult.Error(
                            "连接模型服务超时（${NET_CALL_TIMEOUT_MS / 1000} 秒无响应），请检查网络或模型服务地址后重试"
                        )
                    e is CancellationException -> throw e
                    coroutineContext[Job]?.isActive == false ->
                        throw CancellationException("request canceled by caller", e)
                    else -> {
                        lastErr = e.message
                        Log.e(TAG, "<<< NETWORK ERROR attempt=$attempt: ${e.message}", e)
                        if (attempt < maxRetries) continue
                        return@withContext GenUILlmResult.Error(friendlyNetError(e))
                    }
                }
            } finally {
                timeoutJob.cancel()
                cancelHook?.dispose()
            }
        }
        GenUILlmResult.Error(lastErr ?: "unknown error")
    }

    /**
     * 测试连接
     */
    suspend fun testConnection(baseUrl: String, apiKey: String, model: String): String? =
        withContext(Dispatchers.IO) {
            try {
                val result = chat(
                    baseUrl = baseUrl,
                    apiKey = apiKey,
                    model = model,
                    messages = listOf(GenUIChatMessage("user", "Hi", reasoning = null)),
                    temperature = 0.0f,
                    maxTokens = 10,
                    tools = null,
                    stream = false
                )
                when (result) {
                    is GenUILlmResult.Error -> result.message
                    else -> null
                }
            } catch (e: Exception) {
                friendlyNetError(e)
            }
        }

    // ==================== 端点补全 ====================

    /**
     * 补全 API 端点 URL
     * - 裸 host → /v1/chat/completions
     * - 以 /v1 结尾 → /chat/completions
     * - 已带完整路径 → 原样
     * - 末尾加 '#' → 关闭自动补全
     */
    fun completeEndpoint(baseUrl: String): String {
        var url = baseUrl.trim()
        // '#' 后缀：用户显式关闭自动补全
        if (url.endsWith("#")) return url.dropLast(1)

        // 已经包含 /chat/completions → 不补
        if (url.contains("/chat/completions")) return url

        // 以 /v1 或 /vN 结尾 → 补 /chat/completions
        if (Regex("""/v\d+/?$""").containsMatchIn(url)) {
            return url.trimEnd('/') + "/chat/completions"
        }

        // 裸 host（无路径或只有 /）→ 补 /v1/chat/completions
        val pathPart = try {
            java.net.URI(url).path ?: ""
        } catch (_: Exception) {
            ""
        }
        if (pathPart.isBlank() || pathPart == "/") {
            return url.trimEnd('/') + "/v1/chat/completions"
        }

        // 有路径但不是 /vN → 原样返回（可能是自定义路径）
        return url
    }

    // ==================== 流式聊天（SSE）====================

    private fun streamChat(
        request: Request,
        bodyStr: String,
        onToken: ((String) -> Unit)?,
        onThinking: ((String) -> Unit)?
    ): GenUILlmResult {
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val body = response.body?.string() ?: ""
                return GenUILlmResult.Error(friendlyHttpError(response.code, body))
            }

            val body = response.body ?: return GenUILlmResult.Error("响应体为空")
            val source = body.source()

            val contentAcc = StringBuilder()
            val reasoningAcc = StringBuilder()
            val toolAcc = StreamToolAcc()
            var isReasoning = false
            var totalBytes = 0L

            try {
                while (!source.exhausted()) {
                    val line = source.readUtf8Line() ?: break
                    totalBytes += line.length + 1
                    if (totalBytes > MAX_RESPONSE_BYTES) {
                        return GenUILlmResult.Error("响应过大，超过 ${MAX_RESPONSE_BYTES / 1024 / 1024}MB 限制")
                    }

                    if (line.startsWith("data:")) {
                        val data = line.substring(5).trim()
                        if (data == "[DONE]") break
                        if (data.isEmpty()) continue

                        try {
                            val obj = JSONObject(data)
                            val choices = obj.optJSONArray("choices")
                            if (choices == null || choices.length() == 0) continue

                            val choice = choices.getJSONObject(0)
                            val finishReason = choice.optString("finish_reason", null)
                            val delta = choice.optJSONObject("delta")

                            if (delta != null) {
                                // reasoning content
                                val reasoningContent = delta.optString("reasoning_content", null)
                                    ?: delta.optString("reasoning", null)
                                    ?: delta.optString("thinking", null)
                                if (reasoningContent != null && reasoningContent.isNotEmpty()) {
                                    if (!isReasoning) isReasoning = true
                                    reasoningAcc.append(reasoningContent)
                                    onThinking?.invoke(reasoningAcc.toString())
                                    continue
                                }

                                val content = delta.optString("content", null)
                                if (content != null && content.isNotEmpty()) {
                                    if (isReasoning) isReasoning = false
                                    contentAcc.append(content)
                                    onToken?.invoke(content)
                                }

                                // tool calls
                                val toolCalls = delta.optJSONArray("tool_calls")
                                if (toolCalls != null && toolCalls.length() > 0) {
                                    for (i in 0 until toolCalls.length()) {
                                        val tc = toolCalls.getJSONObject(i)
                                        val index = tc.optInt("index", 0)
                                        toolAcc.ensureIndex(index)

                                        val id = tc.optString("id", null)
                                        if (id != null && id.isNotEmpty()) {
                                            toolAcc.ids[index] = id
                                        }
                                        val func = tc.optJSONObject("function")
                                        if (func != null) {
                                            val name = func.optString("name", null)
                                            if (name != null && name.isNotEmpty()) {
                                                toolAcc.names[index] = name
                                            }
                                            val args = func.optString("arguments", null)
                                            if (args != null) {
                                                toolAcc.arguments[index].append(args)
                                            }
                                        }
                                    }
                                }
                            }

                            if (finishReason == "stop" || finishReason == "length" || finishReason == "tool_calls") {
                                break
                            }
                        } catch (_: Exception) {
                            // 忽略单行解析错误
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Stream read error: ${e.message}")
            }

            return buildToolCallsOrText(toolAcc, contentAcc, reasoningAcc)
        }
    }

    private fun buildToolCallsOrText(
        toolAcc: StreamToolAcc,
        contentAcc: StringBuilder,
        reasoningAcc: StringBuilder
    ): GenUILlmResult {
        val reasoning = reasoningAcc.toString().ifEmpty { null }
        val content = contentAcc.toString()

        if (toolAcc.hasValidCalls()) {
            val calls = toolAcc.buildCalls()
            return GenUILlmResult.ToolCalls(calls, reasoning, content.ifEmpty { null })
        }
        return GenUILlmResult.Text(content, reasoning)
    }

    private class StreamToolAcc {
        val ids = mutableListOf<String>()
        val names = mutableListOf<String>()
        val arguments = mutableListOf<StringBuilder>()

        fun ensureIndex(index: Int) {
            while (ids.size <= index) {
                ids.add("")
                names.add("")
                arguments.add(StringBuilder())
            }
        }

        fun hasValidCalls(): Boolean = names.any { it.isNotEmpty() }

        fun buildCalls(): List<GenUIToolCall> {
            val result = mutableListOf<GenUIToolCall>()
            for (i in names.indices) {
                val name = names[i]
                if (name.isEmpty()) continue
                val args = sanitizeToolArguments(arguments[i].toString())
                result.add(
                    GenUIToolCall(
                        id = ids[i].ifEmpty { "call_${System.currentTimeMillis()}_$i" },
                        name = name,
                        arguments = args
                    )
                )
            }
            return result
        }
    }

    // ==================== 响应解析 ====================

    private fun parse(json: String): GenUILlmResult {
        return try {
            val obj = JSONObject(json)
            val choices = obj.optJSONArray("choices")
            if (choices == null || choices.length() == 0) {
                return GenUILlmResult.Error("响应中没有 choices")
            }
            val choice = choices.getJSONObject(0)
            val message = choice.optJSONObject("message")
            if (message == null) {
                return GenUILlmResult.Error("响应中没有 message")
            }

            val content = message.optString("content", "") ?: ""
            val reasoning = message.optString("reasoning_content", null)
                ?: message.optString("reasoning", null)
                ?: message.optString("thinking", null)

            val toolCallsJson = message.optJSONArray("tool_calls")
            if (toolCallsJson != null && toolCallsJson.length() > 0) {
                val calls = mutableListOf<GenUIToolCall>()
                for (i in 0 until toolCallsJson.length()) {
                    val tc = toolCallsJson.getJSONObject(i)
                    val func = tc.optJSONObject("function")
                    calls.add(
                        GenUIToolCall(
                            id = tc.optString("id", "call_${System.currentTimeMillis()}_$i"),
                            name = func?.optString("name", "") ?: "",
                            arguments = sanitizeToolArguments(func?.optString("arguments", "{}") ?: "{}")
                        )
                    )
                }
                GenUILlmResult.ToolCalls(calls, reasoning, content.ifEmpty { null })
            } else {
                GenUILlmResult.Text(content, reasoning)
            }
        } catch (e: Exception) {
            GenUILlmResult.Error("解析响应失败: ${e.message}")
        }
    }

    // ==================== 请求构建 ====================

    private fun messageToJson(msg: GenUIChatMessage, emitReasoning: Boolean = true): JSONObject {
        val obj = JSONObject()
        obj.put("role", msg.role)

        if (msg.role == "assistant" && !msg.toolCalls.isNullOrEmpty()) {
            val content = msg.content
            obj.put("content", content)
            val toolCallsArray = JSONArray()
            for (tc in msg.toolCalls) {
                val tcObj = JSONObject()
                tcObj.put("id", tc.id)
                tcObj.put("type", "function")
                val funcObj = JSONObject()
                funcObj.put("name", tc.name)
                funcObj.put("arguments", tc.arguments)
                tcObj.put("function", funcObj)
                toolCallsArray.put(tcObj)
            }
            obj.put("tool_calls", toolCallsArray)
            // reasoning 模型不发送 reasoning
            if (emitReasoning && msg.reasoning != null) {
                obj.put("reasoning_content", msg.reasoning)
            }
        } else if (msg.role == "tool") {
            obj.put("tool_call_id", msg.toolCallId ?: "")
            obj.put("content", msg.content)
        } else {
            obj.put("content", msg.content)
            if (emitReasoning && msg.reasoning != null) {
                obj.put("reasoning_content", msg.reasoning)
            }
        }

        return obj
    }

    // ==================== 消息规范化 ====================

    /**
     * 规范化工具调用消息
     * 剔除 UI 进度占位气泡（role=assistant、无 toolCalls 的空消息）
     * 这类消息在 assistant[tool_calls] 与 tool 结果之间会触发 DeepSeek 400
     */
    private fun normalizeToolCallMessages(input: List<GenUIChatMessage>): List<GenUIChatMessage> {
        val result = mutableListOf<GenUIChatMessage>()
        for (msg in input) {
            // 剔除空 assistant 占位（仅 role=assistant 且 content 为空且无 tool_calls）
            if (msg.role == "assistant" && msg.content.isBlank() && msg.toolCalls.isNullOrEmpty()) {
                continue
            }
            result.add(msg)
        }
        return result
    }

    // ==================== 工具参数清理 ====================

    /**
     * 清理工具调用参数 JSON
     * 确保是合法的 JSON 对象
     */
    private fun sanitizeToolArguments(args: String): String {
        val trimmed = args.trim()
        if (trimmed.isEmpty()) return "{}"
        // 尝试解析为 JSON，如果失败则包装
        return try {
            val parsed = JSONObject(trimmed)
            parsed.toString()
        } catch (_: Exception) {
            // 可能是流式拼接的不完整 JSON — 尝试修复
            try {
                // 移除可能的尾部不完整部分
                val fixed = trimmed.trimEnd()
                if (fixed.endsWith("}") || fixed.endsWith("\"")) {
                    JSONObject(fixed).toString()
                } else {
                    // 尝试补全
                    val attempt = if (fixed.endsWith("\"")) "$fixed}" else "$fixed\"}"
                    JSONObject(attempt).toString()
                }
            } catch (_: Exception) {
                trimmed
            }
        }
    }

    // ==================== 错误处理 ====================

    /**
     * HTTP 错误转友好提示
     */
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
            plain.contains("Upstream Response Error", ignoreCase = true) ||
                plain.contains("Internal Server Error", ignoreCase = true) ->
                "模型上游服务返回 500（Internal Server Error）。通常是模型服务端临时故障或该模型/中转不可用，" +
                "请稍后重试；若持续出现，请到「模型配置」检查模型名与中转地址。"
            plain.contains("401") || plain.contains("Unauthorized", ignoreCase = true) ->
                "API 密钥无效或已过期（401），请在模型配置中检查 API Key"
            plain.contains("404") || plain.contains("Not Found", ignoreCase = true) ->
                "请求的模型或端点不存在（404），请检查模型名和 Base URL 是否正确"
            plain.contains("400") || plain.contains("Bad Request", ignoreCase = true) -> {
                val msg = extractJsonErrorMessage(plain) ?: plain.take(200)
                "请求参数错误（400）：$msg"
            }
            else -> {
                val msg = extractJsonErrorMessage(plain) ?: plain.take(200)
                "请求失败（HTTP $code）：$msg"
            }
        }
    }

    /**
     * 从 JSON 错误体提取 message 字段
     */
    private fun extractJsonErrorMessage(plain: String): String? {
        return try {
            val obj = JSONObject(plain)
            val error = obj.optJSONObject("error")
            error?.optString("message", null) ?: obj.optString("message", null)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 网络异常转友好提示
     */
    private fun friendlyNetError(e: Exception): String {
        val message = e.message ?: ""
        val lower = message.lowercase(Locale.ROOT)
        val simpleName = e.javaClass.simpleName

        return when {
            "failed to connect" in lower || "connection refused" in lower ->
                "无法连接到模型服务（连接被拒绝或超时），请检查网络或模型服务地址后重试"
            "timed out" in lower || "timeout" in lower ->
                "连接模型服务超时，请检查网络后重试"
            "unable to resolve host" in lower || "no address associated" in lower ||
                "unknown host" in lower || "dns" in lower ->
                "无法解析模型服务地址（DNS 失败），请检查 baseUrl 是否正确"
            "certificate" in lower || "ssl" in lower || "tls" in lower || "handshake" in lower ->
                "模型服务 TLS/证书校验失败，请检查地址是否为 https 且证书有效"
            "redirect" in lower ->
                "请求被重定向过多，请检查 baseUrl 是否正确"
            message.isBlank() -> {
                Log.e(TAG, "<<< friendlyNetError: 异常 message 为空, 类名=$simpleName", e)
                "网络错误（$simpleName）：未知网络故障，请检查 baseUrl 是否正确、网络是否可达"
            }
            else -> "网络错误（$simpleName）：$message"
        }
    }
}
