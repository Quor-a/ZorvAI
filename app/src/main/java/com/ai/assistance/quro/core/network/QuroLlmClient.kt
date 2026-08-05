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
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.coroutineContext

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
 * 设计取舍（对齐 Zorv AI 稳定方案）：
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
        // 连接超时 15s：健康端点通常 <1s 建连，15s 足够；端点不可达时快速失败，
        // 避免用户在「等等」状态干等 30s 才看到报错（原 30s 偏长）。读取超时保持 120s，
        // 因为长生成（含 reasoning）可能持续数分钟，不应被误杀。
        .connectTimeout(15, TimeUnit.SECONDS)
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
        stream: Boolean = false,
        onToken: ((String) -> Unit)? = null,
        onThinking: ((String) -> Unit)? = null,
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
            if (stream) put("stream", true)
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
        // 流式路径：逐字回调，不走重试（避免半截 token 后重试造成内容错乱）。
        if (stream && onToken != null) {
            return streamChat(req, onToken, onThinking)
        }
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
                delay(backoff)
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
        val raw = e.message ?: "network error"
        val m = raw.lowercase()
        // 与「超时」区分：建连失败（TCP 连不上 / 被拒 / DNS 失败 / TLS 失败）属于不同根因，
        // 给出对应提示，且绝不把服务端公网 IP、本机内网 IP 等地址信息泄露到聊天气泡。
        return when {
            m.contains("failed to connect") || m.contains("connection refused") ->
                "无法连接到模型服务（连接被拒绝或超时），请检查网络或模型服务地址后重试"
            m.contains("connection reset") || m.contains("connection closed") ||
                m.contains("broken pipe") || m.contains("stream was reset") ->
                "与模型服务的连接中断，请稍后重试"
            m.contains("timed out") || m.contains("timeout") ->
                "连接模型服务超时，请检查网络后重试"
            m.contains("unable to resolve host") || m.contains("no address associated") ||
                m.contains("unknown host") || m.contains("dns") ->
                "无法解析模型服务地址（DNS 失败），请检查 baseUrl 是否正确"
            m.contains("certificate") || m.contains("ssl") || m.contains("tls") ||
                m.contains("handshake") ->
                "模型服务 TLS/证书校验失败，请检查地址是否为 https 且证书有效"
            else -> {
                // 兜底：先脱敏（去掉可能泄露的服务端/本机 IP），再展示精简后的原始信息，
                // 避免把 api.tokenrouter.com/13.214.26.65 (port 443) from /10.153.56.86 这类
                // 内部地址串直接甩给用户。
                val sanitized = stripHostAndIp(raw)
                "网络错误：${sanitized.take(160)}"
            }
        }
    }

    /** 脱敏：去掉 IP（含端口）、host/ip 片段，避免把服务端/本机地址泄露进聊天气泡。 */
    private fun stripHostAndIp(s: String): String = s
        .replace(Regex("""\d{1,3}(\.\d{1,3}){3}(:\d+)?"""), "***")
        .replace(Regex("""from /[\d./]+"""), "from local")
        .replace(Regex("""to [A-Za-z0-9.\-]+/\d{1,3}(\.\d{1,3}){3}"""), "to host")

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

    /**
     * 流式对话：解析 OpenAI 兼容的 SSE（server-sent events），逐块回调已累计的文本内容，
     * 让上层 UI 实时刷新 AI 回复气泡（修复「发出消息后很久才看到回复」的体感问题）。
     *
     * 设计取舍：
     *  - onToken 回传的是「截至当前的完整累计文本」，上层直接 store.update(content=累计) 即可，
     *    无需在上层再做增量拼接，避免重复累加。
     *  - 不套 withTimeout：依赖 OkHttp 的 readTimeout（每次收到字节都会重置），只有「连接假死」才会超时，
     *    长但正常的生成不会被误杀。
     *  - 🔧 连接/早期失败重试：在建连或首字节到达前若抛网络异常（连接超时 / 被拒 / DNS 失败），
     *    且尚未吐出任何 token / 工具调用 → 安全重试（重新 DNS + 建连），最多 2 次。
     *    这与非流式 chat() 的重试策略对齐，也对齐「其他客户端连得上」的体感——
     *    单次建连因路由/解析抖动失败不该直接判死，给一次重连机会；已吐出内容则不重试（避免错乱）。
     */
    private suspend fun streamChat(req: Request, onToken: (String) -> Unit, onThinking: ((String) -> Unit)? = null): QuroLlmResult {
        val contentAcc = StringBuilder()
        val reasoningAcc = StringBuilder()
        // 🔧 v291 修复：流式响应里模型返回的 tool_calls 也以 delta 形式下发，必须按 index 累计
        // （function.name / function.arguments 常分片到达）。否则工具调用被当成「空文本」→
        // AI 不执行工具、空回复、工具卡消失（用户报「AI 挂了 / 不执行 / 不回复 / 空回复」的根因）。
        val toolAcc = mutableListOf<StreamToolAcc>()
        fun ensureSlot(idx: Int) {
            while (toolAcc.size <= idx) toolAcc.add(StreamToolAcc())
        }
        // 建连/早期失败重试：对齐非流式路径与主流客户端。仅在尚未吐出任何内容时重连。
        val maxRetries = 2
        var lastErr: Exception? = null
        for (attempt in 0..maxRetries) {
            if (attempt > 0) {
                // 已吐出内容 → 不再重试，按已有内容兜底（下方统一处理）。
                if (contentAcc.isNotEmpty() || toolAcc.isNotEmpty()) break
                Log.w(TAG, "<<< STREAM RETRY attempt=$attempt/$maxRetries (prev=${lastErr?.message})")
                delay(800L * attempt)
            }
            try {
                val result = client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        return@use QuroLlmResult.Error(friendlyHttpError(resp.code, resp.body?.string().orEmpty()))
                    }
                    val source = resp.body?.source()
                        ?: return@use QuroLlmResult.Error("模型返回了空响应体")
                    while (true) {
                        val line = source.readUtf8Line() ?: break
                        // 🔧 取消点：用户「停止生成」或切换会话时 sendJob 被取消，此处每行读完后立即抛取消，
                        // 避免阻塞在 readUtf8Line 上把旧会话的生成一直"流"到结束（切对话停不掉的真正根因）。
                        coroutineContext.ensureActive()
                        val trimmed = line.trim()
                        if (trimmed.isEmpty() || !trimmed.startsWith("data:")) continue
                        val data = trimmed.removePrefix("data:").trim()
                        if (data.isEmpty() || data == "[DONE]") {
                            if (data == "[DONE]") break
                            continue
                        }
                        runCatching {
                            val root = JSONObject(data)
                            val delta = root.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("delta")
                            if (delta != null) {
                                val c = safeString(delta, "content")
                                if (!c.isNullOrEmpty()) {
                                    contentAcc.append(c)
                                    onToken(contentAcc.toString())
                                }
                                val r = safeString(delta, "reasoning_content")
                                    ?: safeString(delta, "reasoning")
                                    ?: safeString(delta, "thinking")
                                if (!r.isNullOrEmpty()) {
                                    reasoningAcc.append(r)
                                    // 🧠 流式思考实时上屏：与本地路径（onThinking）对称。
                                    // 此前云端只在终态 result 里带 reasoning，思考段生成期间 UI 完全空白，
                                    // 体感等同「卡住/不回复」。现在每片 reasoning 增量即回调，UI 边想边显示。
                                    onThinking?.invoke(reasoningAcc.toString())
                                }
                                // 🔧 累计流式 tool_calls（index 槽位 + name/arguments 拼接）
                                val tcs = delta.optJSONArray("tool_calls")
                                if (tcs != null) {
                                    for (j in 0 until tcs.length()) {
                                        val tc = tcs.getJSONObject(j)
                                        val idx = if (tc.has("index")) tc.optInt("index", toolAcc.size) else toolAcc.size
                                        ensureSlot(idx)
                                        val slot = toolAcc[idx]
                                        if (tc.has("id") && !tc.isNull("id")) slot.id = tc.optString("id")
                                        val fn = tc.optJSONObject("function")
                                        if (fn != null) {
                                            if (fn.has("name") && !fn.isNull("name")) slot.name += fn.getString("name")
                                            if (fn.has("arguments") && !fn.isNull("arguments")) slot.arguments += fn.optString("arguments", "")
                                        }
                                    }
                                }
                            }
                        }
                    }
                    // 与 parse() 一致：tool_calls 优先于 content
                    buildToolCallsOrText(toolAcc, contentAcc, reasoningAcc)
                }
                // HTTP 非成功（如 502/503）属确定性失败，直接返回不重试（避免重复生成/计费）。
                if (result is QuroLlmResult.Error) return result
                return result
            } catch (e: Exception) {
                // 🔧 取消信号必须原样向上抛：否则会被当成"断流截断"兜底成成功文本，
                // 导致「停止生成/切换会话」不出现"⏹ 已停止生成"提示。
                if (e is kotlinx.coroutines.CancellationException) throw e
                lastErr = e
                Log.e(TAG, "<<< STREAM ERROR attempt=$attempt: ${e.message}", e)
                // 已吐出部分内容 → 截断兜底为成功（不重试，避免错乱）。
                if (contentAcc.isNotEmpty()) {
                    return QuroLlmResult.Text(contentAcc.toString(), reasoningAcc.toString().takeIf { it.isNotBlank() })
                }
                if (toolAcc.isNotEmpty()) {
                    return buildToolCallsOrText(toolAcc, contentAcc, reasoningAcc)
                }
                // 连接/早期失败且无内容 → 进入下一轮重试（若还有次数）。
            }
        }
        return QuroLlmResult.Error(friendlyNetError(lastErr ?: Exception("stream connection failed")))
    }

    /** 流式累计结束后，按是否含工具调用产出 ToolCalls 或 Text（与 parse() 同语义）。 */
    private fun buildToolCallsOrText(
        toolAcc: List<StreamToolAcc>,
        contentAcc: StringBuilder,
        reasoningAcc: StringBuilder,
    ): QuroLlmResult {
        val reasoning = reasoningAcc.toString().takeIf { it.isNotBlank() }
        return if (toolAcc.isNotEmpty()) {
            val calls = toolAcc.mapIndexed { i, t ->
                QuroToolCall(id = t.id ?: "call_$i", name = t.name, arguments = t.arguments.ifBlank { "{}" })
            }
            Log.i(TAG, "<<< STREAM tool_calls=${calls.size} reasoningBlank=${reasoning.isNullOrBlank()} first=${calls.firstOrNull()?.name}")
            QuroLlmResult.ToolCalls(calls, reasoning)
        } else {
            QuroLlmResult.Text(contentAcc.toString(), reasoning)
        }
    }

    /** 流式 tool_calls 累计槽（name / arguments 跨 delta 分片拼接）。 */
    private class StreamToolAcc {
        var id: String? = null
        var name: String = ""
        var arguments: String = ""
    }
}
