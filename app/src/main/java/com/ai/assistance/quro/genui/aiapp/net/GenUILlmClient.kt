package com.ai.assistance.quro.genui.aiapp.net

import android.util.Log
import com.ai.assistance.quro.genui.aiapp.core.GenUIChatMessage
import com.ai.assistance.quro.genui.aiapp.core.GenUILlmResult
import com.ai.assistance.quro.genui.aiapp.core.GenUIToolCall
import com.ai.assistance.quro.genui.aiapp.core.GenUIToolSpec

import com.ai.assistance.quro.core.network.QuroModelOutputBudget
import com.ai.assistance.quro.core.network.QuroReasoningControl
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
 * 提示词体积告警线（字符，含 system + 历史 + tools 描述）。
 *
 * GenUI 的 system prompt 天然偏大（GenUiRules 组件清单 + design-studio 技能 + 宿主工具集），
 * 实测单轮输入可轻易冲到 6 万字符以上。小上下文模型（32K/64K）会直接 400，
 * 大窗口模型则被挤掉输出预算导致「画不出界面」。此阈值只用于**告警与诊断**，
 * 不做任何裁剪——裁剪已随 N13–N15 回滚，真机反馈显示压缩反而让 AI 答非所问。
 */
private const val PROMPT_WARN_CHARS = 60_000

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
        /** 思考档位。GenUI 侧的「深度思考」由上层透传；AUTO = 不干预。 */
        reasoningLevel: QuroReasoningControl.ThinkingLevel =
            QuroReasoningControl.ThinkingLevel.AUTO,
        stream: Boolean = false,
        onToken: ((String) -> Unit)? = null,
        onThinking: ((String) -> Unit)? = null
    ): GenUILlmResult = withContext(Dispatchers.IO) {
        // 端点补全
        val url = completeEndpoint(baseUrl)

        // 🔴 思考控制：四家字段互斥，必须由编译层统一决定（复用主链路 QuroReasoningControl）。
        //
        // 旧实现只有 `Regex("(?i)^o[0-9]")` 一条窄规则，只认 o1/o3/o4 开头，漏掉：
        //   - gpt-5 / gpt-5.x（正则不匹配 → 仍下发 temperature + max_tokens → GPT-5 直接 400）
        //   - claude（不下发 thinking → 恒不思考；且 thinking 开启时必须省略 temperature）
        //   - qwen3（默认开着思考，用户根本关不掉）
        // 这正是用户说的「部分模型不能用」—— 换模型就报错/不思考。
        val plan = QuroReasoningControl.plan(
            provider = "",
            baseUrl = baseUrl,
            model = model,
            level = reasoningLevel,
            maxTokens = maxTokens,
        )
        if (plan.family != QuroReasoningControl.Family.NONE) {
            Log.i(TAG, ">>> REASONING ${plan.summary()}")
        }

        // 🔴 max_tokens 必须钳到**模型真实输出上限**（这是「写不完整 / 换个模型就报错」的真凶）。
        //   QuroModelConfig.maxTokens 默认 65536，但 gpt-4o 只有 16384、gpt-4-turbo 只有 4096、
        //   claude-3 只有 8192 → 上游收到超限值会 400 / 静默夹断（长报告被腰斩）/ 整个 500。
        val budget = QuroModelOutputBudget.resolve(model, maxTokens)
        val effectiveMaxTokens = budget.outputTokens.coerceAtMost(MAX_OUTPUT_TOKENS)
        Log.i(TAG, ">>> OUTPUT_BUDGET ${QuroModelOutputBudget.describe(budget)}")

        // 构建请求体
        val body = JSONObject().apply {
            put("model", model)
            // 🔴 字段名与 temperature 抑制全部由 plan 决定（不再用窄正则猜）。
            if (plan.useMaxCompletionTokens) {
                put("max_completion_tokens", effectiveMaxTokens)
            } else {
                put("max_tokens", effectiveMaxTokens)
            }
            // suppressTemperature：GPT-5 系与「开了 thinking 的 Claude」明确拒绝 temperature，
            // 带上就是 400 —— 这是「换个模型就直接报错」的第二个真凶。
            if (!plan.suppressTemperature) put("temperature", temperature)
            put("messages", JSONArray().also { arr ->
                normalizeToolCallMessages(messages).forEach { m ->
                    // 🔴 emitReasoning 由 plan 家族决定（旧实现是 `!isReasoningModel`，
                    //   而那条窄正则只认 o1/o3/o4 开头，等于「非 o 系一律回吐 reasoning_content」）：
                    //   · ALWAYS_ON（DeepSeek-R1 / QwQ / GLM-Z1）：**必须回吐**。它们恒思考且用
                    //     reasoning_content 承载思考，不回吐则多轮对话时模型看不到自己上轮思考 → 重复思考。
                    //   · 其他家族（OpenAI/GPT-5 用 reasoning、Claude 用 thinking、Qwen3 用
                    //     chat_template）：reasoning_content 是对方的私有字段，发过去就是 400，**一律不发**。
                    arr.put(
                        messageToJson(
                            m,
                            emitReasoning = plan.family == QuroReasoningControl.Family.ALWAYS_ON
                        )
                    )
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
            // 🔴 四家思考字段互斥：同一请求同时发两种 = 400。全部由 plan 决定，未识别家族一个都不发。
            plan.reasoningEffort?.let { put("reasoning_effort", it) }
            plan.topLevelEnableThinking?.let { put("enable_thinking", it) }
            plan.chatTemplateEnableThinking?.let {
                put("chat_template_kwargs", JSONObject().put("enable_thinking", it))
            }
            if (plan.thinkingType != null) {
                put(
                    "thinking",
                    JSONObject().put("type", plan.thinkingType).apply {
                        plan.thinkingBudgetTokens?.let { put("budget_tokens", it) }
                    }
                )
            }
            if (stream) put("stream", true)
        }

        val bodyStr = body.toString()
        // 诊断：真实字符数 / tools 体积一并打出。
        // 为什么加：此前只打 messages.size（消息**条数**），提示词超限时日志完全无痕，
        // 真机只能看到「界面一直空白」，无从判断是提示词过大还是模型不通。
        val promptChars = messages.sumOf { it.content.length }
        val toolsChars = tools?.sumOf { it.description.length + it.parametersJson.length } ?: 0
        Log.i(TAG, ">>> REQUEST model=$model url=$url messages=${messages.size} tools=${tools?.size ?: 0} maxTokens=$effectiveMaxTokens requested=$maxTokens")
        Log.i(TAG, ">>> SIZE promptChars=$promptChars toolsChars=$toolsChars totalChars=${promptChars + toolsChars} (≈${(promptChars + toolsChars) / 2}tok)")
        if (promptChars + toolsChars > PROMPT_WARN_CHARS) {
            Log.w(TAG, ">>> SIZE 提示词体积偏大（${promptChars + toolsChars} 字符），小上下文模型可能直接 400；"
                + "当前模型：$model")
        }

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
            // 🔴 记住上游停止原因：`length` = 被 max_tokens 腰斩。
            // 旧实现拿到就 break 且丢弃，上层无从判断是否需要续写。
            var lastFinishReason: String? = null

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
                                    // ⚠️ 这里**不能** continue。
                                    // 部分模型（混思考的推理型网关、以及 reasoning 结束时与正文
                                    // 交接的那一帧）会在**同一个 delta 里同时**给 reasoning_content 和
                                    // content。旧代码 `continue` 直接把该帧的 content 丢掉 →
                                    // 正文凭空少一截（常见是围栏起始 ``` 那一帧）→
                                    // detectChannel 认不到围栏 → 界面显示成代码块源码。
                                    // 所以下面继续正常处理 content / tool_calls。
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

                            if (finishReason == "stop" || finishReason == "length" ||
                                finishReason == "tool_calls" || finishReason == "content_filter"
                            ) {
                                lastFinishReason = finishReason
                                if (finishReason == "length") {
                                    Log.w(
                                        TAG,
                                        ">>> STREAM 被 max_tokens 截断 (finish_reason=length)，" +
                                            "已收 ${contentAcc.length} 字符 → 需续写"
                                    )
                                }
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

            return buildToolCallsOrText(toolAcc, contentAcc, reasoningAcc, lastFinishReason)
        }
    }

    private fun buildToolCallsOrText(
        toolAcc: StreamToolAcc,
        contentAcc: StringBuilder,
        reasoningAcc: StringBuilder,
        finishReason: String? = null,
    ): GenUILlmResult {
        val reasoning = reasoningAcc.toString().ifEmpty { null }
        val content = contentAcc.toString()

        if (toolAcc.hasValidCalls()) {
            val calls = toolAcc.buildCalls()
            return GenUILlmResult.ToolCalls(calls, reasoning, content.ifEmpty { null })
        }
        return GenUILlmResult.Text(content, reasoning, finishReason)
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
                GenUILlmResult.Text(
                    content, reasoning,
                    choice.optString("finish_reason", null)
                )
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
    /** 识别「上下文超限」类 400（中英文/各中转写法都覆盖）。 */
    private fun looksLikeContextOverflow(msg: String): Boolean {
        val m = msg.lowercase()
        return m.contains("maximum context length") ||
            m.contains("context_length_exceeded") ||
            m.contains("context length") && (m.contains("exceed") || m.contains("too long") || m.contains("exceeds")) ||
            m.contains("prompt is too long") ||
            m.contains("input is too long") ||
            m.contains("reduce the length") ||
            m.contains("上下文") && (m.contains("超") || m.contains("过长"))
    }

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
                // 上下文超限是 GenUI 最常见的 400，且错误文案（"maximum context length" /
                // "context_length_exceeded"）对普通用户毫无意义 —— 换成能照做的说法。
                if (looksLikeContextOverflow(msg)) {
                    "输入内容超出该模型的上下文窗口（$msg）。" +
                        "GenUI 每次生成界面的系统提示词较长，请换用上下文更大的模型，" +
                        "或到「模型配置」调大上下文窗口后重试。"
                } else {
                    "请求参数错误（400）：$msg"
                }
            }
            else -> {
                // 🔴 上游给了 HTTP 码却不给任何正文（或只给了一个空消息字段）时，
                //旧实现直接拼空串 → 气泡里只有「请求失败（HTTP 500）：」，
                // 用户看到的就是图2 —— 冒号后面全空，无从跟踪。
                // 现在补三层：① 取 JSON message；② 取原文首段；③ 仍空 → 给可操作建议。
                val msg = extractJsonErrorMessage(plain)
                    ?.takeIf { it.isNotBlank() }
                    ?: plain.take(200).takeIf { it.isNotBlank() }
                    ?: emptyBodyHint(code)
                "请求失败（HTTP $code）：$msg"
            }
        }
    }

    /**
     * 🔴 上游错误体为空时的可操作提示。
     *
     * 为什么不能继续返回空串：用户看到「请求失败（HTTP 500）：」时，
     * 无法判断是自己的配置错误还是上游故障 —— 而两者的处置完全不同。
     * 至少要告诉他下一步去哪看。
     */
    private fun emptyBodyHint(code: Int): String = when (code) {
        401, 403 -> "上游未返回错误详情（HTTP $code）。通常是 API Key 无效或超额，" +
            "请在「模型配置」检查 Key 与 Base URL。（详细原文见诊断日志）"
        404 -> "上游未返回错误详情（HTTP 404）。通常是模型名或端点不存在，" +
            "请刷新模型列表并重新选择。（详细原文见诊断日志）"
        429 -> "上游未返回错误详情（HTTP 429），多为频率限制。请稍后重试。"
        in 500..599 -> "上游服务器内部错误（HTTP $code）且未返回详情。" +
            "通常是中转上游转发失败或该模型暂不可用；" +
            "若持续出现，请刷新模型列表换一个模型试试。（详细原文见诊断日志）"
        else -> "上游未返回错误详情（HTTP $code）。" +
            "请查看诊断日志中的 llm_last_error.json 获取原始响应。"
    }

    /**
     * 从 JSON 错误体提取 message 字段
     */
    private fun extractJsonErrorMessage(plain: String): String? {
        return try {
            val obj = JSONObject(plain)
            val error = obj.optJSONObject("error")
            val raw = error?.optString("message", null) ?: obj.optString("message", null)
            // 🔴 上游给 `{"error":{"message":""}}` 时 optString 返回空串而非null，
            // 旧实现直接返回空串使 `?:` 兜底失效 → "HTTP 500：" 冒号后全空。
            // 必须当名字段为空当作没拿到。
            raw?.takeIf { it.isNotBlank() }
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
