package com.ai.assistance.quro.core.network

import android.content.ContentValues
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.ai.assistance.quro.activity.QuroApplication
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
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext

private const val TAG = "QuroLlm"

/**
 * 单次响应 max_tokens 的硬性上限（token）。取已知最大模型（MiMo-v2.5 输出上限 128K=131072）作为天花板，
 * 防止配置里误填超大值（如误存的 131000 或更大）被严格网关/上游按「超模型输出上限」直接 500。
 * 正常配置（≤131072）不受影响；仅当超出时才钳制并打日志提示。
 */
private const val MAX_OUTPUT_TOKENS = 131_072

/**
 * 单次 HTTP 调用的硬超时护栏（毫秒）。
 * 作用：OkHttp 自带 connect/read 超时在「代理挂起 / 端点假死」时仍可能长时间不返回，
 * 导致整条对话协程卡在「思考中」、bot 永远不回复且无任何报错（用户感知为「完全没反应」）。
 * 这里用独立计时器在 NET_CALL_TIMEOUT_MS 后 call.cancel() 强制中止本次调用并转成明确错误气泡，
 * 杜绝「永久静默」——最坏情况用户也会看到「⚠️ 连接模型服务超时」而非无限转圈。
 * 设 90s，比 OkHttp 的 120s readTimeout 更早触发，确保本护栏是最终裁决者。
 * （v455 起不再用 withTimeout：阻塞式 execute() 无法被协程超时及时取消，改为计时器 + call.cancel()。）
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
        // 🔧 云端端点兼容：用户常把裸 host
        // （如 https://api.openai.com、https://api.deepseek.com）当成 baseUrl 直接粘贴，
        // 旧逻辑会拼成 …/chat/completions → 404/401 → 模型「永远不回复」被误判为兼容问题。
        // 现按规则补全：裸 host → /v1/chat/completions；以 /v1 结尾 → /chat/completions；
        // 已带完整路径则原样；末尾加 '#' 可关闭自动补全（直达原始 URL）。
        val url = completeEndpoint(baseUrl)
        // 🔧 OpenAI 官方 reasoning 模型（o1 / o3 / o4 系列）硬伤修复：
        //  这类模型**不支持 max_tokens**，必须发 max_completion_tokens，否则直接 400
        //  「max_tokens is not supported with this model」→ 整轮失败（表现为「部分模型直接不回复」，
        //  o 系列全挂，gpt-4o 系列正常）。同时 reasoning 模型**不接收 temperature**
        //  （o1 固定为 1，传非 1 也 400），统一省略由服务端取默认。
        //  通过 model 名前缀 o+数字（o1 / o3-mini / o4-mini …）识别；gpt-4o / 4.1 等普通模型不受影响。
        val isReasoningModel = Regex("(?i)^o[0-9]").containsMatchIn(model.trim())
        // 🔧 toolfix8：max_tokens 硬性上限护栏。避免误配超大值被上游按「超模型输出上限」500。
        val effectiveMaxTokens = maxTokens.coerceAtMost(MAX_OUTPUT_TOKENS)
        if (effectiveMaxTokens != maxTokens) {
            Log.w(TAG, ">>> max_tokens 被钳到 $effectiveMaxTokens（原 $maxTokens 超模型输出上限 $MAX_OUTPUT_TOKENS）")
        }
        val body = JSONObject().apply {
            put("model", model)
            if (isReasoningModel) {
                Log.i(TAG, ">>> reasoning model 分支：用 max_completion_tokens，省略 temperature (model=$model)")
                put("max_completion_tokens", effectiveMaxTokens)
            } else {
                put("temperature", temperature)
                put("max_tokens", effectiveMaxTokens)
            }
            put("messages", JSONArray().also { arr ->
                messages.forEach { m -> arr.put(messageToJson(m, emitReasoning = !isReasoningModel)) }
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
        Log.i(TAG, ">>> REQUEST  model=$model url=$url messages=${messages.size} tools=${tools.size} maxTokens=$effectiveMaxTokens body=${bodyStr.length}ch")
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
            return streamChat(req, bodyStr, onToken, onThinking)
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
            // 🔧 Bug修复「取消被当成错误展示」：OkHttp execute() 是阻塞调用，协程取消本身打不断它。
            //   这里注册两个钩子：
            //   ① cancelHook：协程被取消（用户停止/切会话）→ 立即 call.cancel() 中止阻塞读写；
            //   ② timer：90s 硬超时（替代原 withTimeout——阻塞调用无法被 withTimeout 及时取消），
            //      超时置 timedOut 并中止调用。
            //   随后在 catch 里按「超时 / 用户取消 / 真实网络错误」三分支区分，绝不再把
            //   CancellationException 或 OkHttp 的 abort(IOException("Canceled")) 包成网络错误气泡。
            val call = client.newCall(req)
            // 3a 加固：计时器协程写、执行线程读，普通 Boolean 存在可见性竞态 → AtomicBoolean
            // （局部被捕获变量无法标注 @Volatile，AtomicBoolean 是等价且更明确的修法）。
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
                        // 🔧 把真实发出的请求体 + 上游响应双写到 Download/QuroAI_logs/，
                        // 用户用文件管理器即可取到（无需 adb），用于定位到底哪条消息/哪个字段非法。
                        dumpLlmErrorPayload(resp.code, text, bodyStr, url)
                        return@use QuroLlmResult.Error(friendlyHttpError(resp.code, text))
                    }
                    return@use parse(text)
                }
                if (callResult != null) return callResult
                // callResult == null：临时故障（5xx/429），lastErr 已记录，进入下一轮重试
            } catch (e: Exception) {
                when {
                    // 硬超时：timer 中止了调用 → 转成明确报错气泡，杜绝「思考中」永久卡死。
                    timedOut.get() ->
                        return QuroLlmResult.Error("连接模型服务超时（${NET_CALL_TIMEOUT_MS / 1000} 秒无响应），请检查网络或模型服务地址后重试")
                    // 用户主动取消：原样向上抛，由上层走「⏹ 已停止生成」，绝不进错误分支。
                    e is CancellationException -> throw e
                    // 协程已取消时 OkHttp 抛 IOException("Canceled"/"Socket closed")：视为干净取消。
                    coroutineContext[Job]?.isActive == false ->
                        throw CancellationException("request canceled by caller", e)
                    else -> {
                        lastErr = e.message
                        Log.e(TAG, "<<< NETWORK ERROR attempt=$attempt: ${e.message}", e)
                        if (attempt < maxRetries) continue // 超时/连接失败等网络异常重试
                        dumpLlmErrorPayload(-1, "网络/解析异常: ${e.message}", bodyStr, url)
                        return QuroLlmResult.Error(friendlyNetError(e))
                    }
                }
            } finally {
                timeoutJob.cancel()
                cancelHook?.dispose()
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
            // 🔧 上游模型服务 500：中转网关（tokenrouter 等）把真实上游模型的 500
            //   透传为 "Upstream Response Error" / "Internal Server Error"。这是服务端故障，
            //   不是本应用请求构造问题，给出明确引导而非把原始 JSON 甩给用户。
            plain.contains("Upstream Response Error", ignoreCase = true) ||
                plain.contains("Internal Server Error", ignoreCase = true) ->
                "模型上游服务返回 500（Internal Server Error）。通常是模型服务端临时故障或该模型/中转不可用，" +
                    "请稍后重试；若持续出现，请到「模型配置」检查模型名与中转地址是否有效（或换一个模型试试）。" +
                    "（已生成诊断文件 Download/QuroAI_logs/llm_last_error.json，可在手机文件管理器取到后发我，用于精准定位非法字段）"
            else -> {
                // 其它未归类错误：尽量抽 {"error":{"message":"..."}} 的 message，避免整段原始 JSON 进气泡。
                val msg = extractJsonErrorMessage(plain) ?: plain.take(200)
                "请求失败（HTTP $code）：$msg"
            }
        }
    }

    /** 从网关错误体里尽量抽出 {"error":{"message":"..."}} 的 message，避免把整段 JSON 甩给用户。 */
    /**
     * 🔧 请求失败（尤其是 500）时，把真实发出的请求体 + 上游响应双写到手机
     * Download/QuroAI_logs/llm_last_error.json，用户用文件管理器即可取到（无需 adb/logcat），
     * 用于定位到底哪条消息/哪个字段非法导致上游拒收。
     * - 图片 data URI 等超大字段脱敏，避免文件膨胀、聚焦 JSON 结构。
     * - 写入走 MediaStore（Android 11+ 文件管理器可见的「下载」目录）；
     *   旧方案直接用 File 写公共 Download 子目录在作用域存储下会被系统拒绝（静默失败），
     *   这是上一版「没有诊断文件」的真因，已改为 MediaStore + RELATIVE_PATH=Download/QuroAI_logs。
     * - 兜底：app 私有外部存储 getExternalFilesDir（adb / Android/data 可取）。
     */
    private fun dumpLlmErrorPayload(code: Int, responseText: String, requestBody: String, url: String = "") {
        val redacted = requestBody
            .replace(Regex("\"url\"\\s*:\\s*\"data:[^\"]*\""), "\"url\":\"[image base64 omitted]\"")
            .replace(Regex("\"image_url\"\\s*:\\s*\\{[^}]*\\}"), "\"image_url\":{\"url\":\"[omitted]\"}")
        // 🔧 toolfix8：诊断写出【完整】请求体（不再截断前 200000 字）。此前截断导致 500 致因的
        //  最新消息（user / tool 尾部）被切掉、无法定位到底是哪条消息/字段非法。图片 data URI 已脱敏，
        //  其余（system / 历史 / tools）全量保留，便于精准比对上游拒收点。
        val reqModel = runCatching { JSONObject(redacted).optString("model", "") }.getOrDefault("")
        val content = buildString {
            appendLine("ZorvAI LLM 请求失败自诊断")
            appendLine("时间: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
            appendLine("HTTP 状态码: $code")
            appendLine("请求 URL: ${url.ifBlank { "(未知)" }}")
            appendLine("请求 model: ${reqModel.ifBlank { "(未知)" }}")
            appendLine("请求体字节数: ${redacted.length}")
            appendLine()
            appendLine("=== 上游响应（前 2000 字）===")
            appendLine(responseText.take(2000))
            appendLine()
            appendLine("=== 发出的请求体（图片 data URI 已脱敏，完整未截断）===")
            appendLine(redacted)
            appendLine()
            appendLine("=== 说明 ===")
            appendLine("若 HTTP=500 且响应含 Upstream Response Error，通常是上游/中转服务端临时故障（5xx/限流），")
            appendLine("并非本应用请求构造问题——已对「首 token 前」的 5xx/429 自动重试，多数能恢复。")
            appendLine("若仍持续 500：① 到「模型配置」确认中转地址/模型名有效；② 换一个模型或中转试试；")
            appendLine("③ 本文件为完整请求体（共 ${redacted.length} 字节），可确认消息结构是否干净。")
        }
        val fileName = "llm_last_error.json"
        val ctx = QuroApplication.appCtx
        if (ctx == null) {
            Log.e(TAG, "⚠️ dumpLlmErrorPayload: QuroApplication.appCtx 为空，无法落盘")
            return
        }
        // 1) MediaStore 写公共 Download/QuroAI_logs（Android 11+ 文件管理器可见）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching {
                val resolver = ctx.contentResolver
                val coll = MediaStore.Downloads.EXTERNAL_CONTENT_URI
                runCatching {
                    resolver.delete(coll, "${MediaStore.Downloads.DISPLAY_NAME} = ?", arrayOf(fileName))
                }
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, "application/json")
                    put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/QuroAI_logs")
                }
                val uri = resolver.insert(coll, values) ?: return@runCatching
                resolver.openOutputStream(uri)?.use { it.write(content.toByteArray()) }
                Log.i(TAG, "✅ LLM 错误诊断已写入 Download/QuroAI_logs/$fileName（MediaStore）")
            }.onFailure { t ->
                Log.e(TAG, "⚠️ MediaStore 写 LLM 诊断失败: ${t.message}")
            }
        } else {
            // Android 10-：直接写公共 Download 子目录（无作用域存储限制）
            runCatching {
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "QuroAI_logs")
                dir.mkdirs()
                File(dir, fileName).writeText(content)
            }.onFailure { t ->
                Log.e(TAG, "⚠️ 旧版直接写 LLM 诊断失败: ${t.message}")
            }
        }
        // 2) 兜底：app 私有外部存储（adb / Android/data/com.ai.assistance.quro/files/QuroAI_logs）
        runCatching {
            val fb = ctx.getExternalFilesDir("QuroAI_logs")?.apply { mkdirs() }
            fb?.let { File(it, fileName).writeText(content) }
        }.onFailure { t ->
            Log.e(TAG, "⚠️ 兜底写 LLM 诊断失败: ${t.message}")
        }
    }

    private fun extractJsonErrorMessage(plain: String): String? = runCatching {
        JSONObject(plain).optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() }
    }.getOrNull()

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

    /**
     * 云端端点 URL 自动补全。
     * 规则：
     *  - 末尾 '#' → 关闭补全，原样返回（适合已带完整路径或非常规路径的中转）。
     *  - 路径为空（裸 host，如 https://api.openai.com）→ 补全 /v1/chat/completions。
     *  - 路径以 /v1 结尾（如 https://x/custom/v1）→ 仅补 /chat/completions。
     *  - 已以 /chat/completions 结尾 → 原样。
     *  - 其余（带任意子路径）→ 兜底追加 /chat/completions（与旧逻辑一致）。
     */
    private fun completeEndpoint(endpoint: String): String {
        val trimmed = endpoint.trim()
        if (trimmed.endsWith("#")) return trimmed.removeSuffix("#")
        val withoutSlash = trimmed.removeSuffix("/")
        return try {
            val path = java.net.URL(withoutSlash).path.removeSuffix("/")
            when {
                path.isEmpty() -> "$withoutSlash/v1/chat/completions"
                path.endsWith("/v1", ignoreCase = true) -> "$withoutSlash/chat/completions"
                path.endsWith("/chat/completions", ignoreCase = true) -> withoutSlash
                else -> "$withoutSlash/chat/completions"
            }
        } catch (_: Exception) {
            // 非标准 URL（无法解析）→ 兜底追加，绝不因解析失败而让请求裸奔。
            "$withoutSlash/chat/completions"
        }
    }

    /**
     * 清洗发送给模型的文本，剔除会让严格上游 JSON 解析失败（→ 500）的非法字符：
     *  - 孤立代理项（lone surrogate，UTF-16 截断产生）→ 替换为 U+FFFD；
     *  - C0 控制字符（除 \n \r \t）与 C1 控制字符（0x7F–0x9F，含 ANSI 转义起始 0x1B）→ 剔除。
     * 保留正常可见文本、emoji（合法代理对）、换行与缩进。终端/命令工具输出常含此类脏字符，
     * 原样发出会被严格上游（DeepSeek/中转）解析失败 → 500 "Upstream Response Error"。
     */
    private fun sanitizeForLlm(s: String): String {
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c.isHighSurrogate() -> {
                    if (i + 1 < s.length && s[i + 1].isLowSurrogate()) {
                        sb.append(c); sb.append(s[i + 1]); i += 2
                    } else { sb.append('\uFFFD'); i += 1 }
                }
                c.isLowSurrogate() -> { sb.append('\uFFFD'); i += 1 }
                c < '\u0020' -> {
                    if (c == '\n' || c == '\r' || c == '\t') sb.append(c)
                    i += 1
                }
                c in '\u007F'..'\u009F' -> { i += 1 }
                else -> { sb.append(c); i += 1 }
            }
        }
        return sb.toString()
    }

    private fun messageToJson(m: QuroChatMessage, emitReasoning: Boolean = true): JSONObject {
        val o = JSONObject().put("role", m.role)
        // 🔧 toolfix8：推理模型的思考过程（reasoning_content）随 assistant 消息一并回传。
        //  mimo-v2.5 等严格上游要求带 tool_calls 的 assistant 历史必须带 reasoning_content，
        //  缺失会被拒收 → 500。o 系列推理模型（isReasoningModel）服务端不接受输入侧的
        //  reasoning_content，故对其不发送（避免 400）。其余模型 reasoning 本就为空，不会发送。
        if (emitReasoning && m.reasoning != null) {
            o.put("reasoning_content", m.reasoning)
        }
        // 🔧 清洗非法字符：发送前统一清洗，保证请求体 JSON 合法且喂给模型的文本干净。
        val safeContent = sanitizeForLlm(m.content)
        val images = m.attachments?.filter { it.type == "image" } ?: emptyList()
        val videos = m.attachments?.filter { it.type == "video" } ?: emptyList()
        val files = m.attachments?.filter { it.type == "file" } ?: emptyList()
        if (m.toolCallId != null) {
            o.put("tool_call_id", m.toolCallId)
            m.toolName?.let { o.put("name", it) }
            // 🔧 toolfix8：严格上游（部分推理模型网关）拒绝空的 tool 消息 content（""）→ 500。
            //  工具无文本输出时给占位串，避免空 content 触发上游拒收。
            o.put("content", if (safeContent.isBlank()) "(工具无输出)" else safeContent)
        } else if (m.toolCalls != null) {
            // 🔧 修复：assistant 带 tool_calls 但无文本时，content 必须发 JSON null（OpenAI/DeepSeek 规范），
            //    发 " " 空串会被严格上游判定为非法消息结构 → 500。
            o.put("content", if (safeContent.isBlank()) JSONObject.NULL else safeContent)
            o.put("tool_calls", JSONArray().also { arr ->
                m.toolCalls.forEach { tc ->
                    // 🔧 关键修复：历史回放时 arguments 也必须清洗——旧版本存储的对话里可能残留
                    //    模型产出的非法 JSON arguments，原样回传会被严格上游解析失败 → 500。
                    //    先 sanitizeToolArguments 规整 JSON，再 sanitizeForLlm 清掉字符串值内的孤立代理项/控制字符。
                    arr.put(
                        JSONObject().put("id", tc.id).put("type", "function").put(
                            "function",
                            JSONObject().put("name", tc.name)
                                .put("arguments", sanitizeForLlm(sanitizeToolArguments(tc.arguments))),
                        ),
                    )
                }
            })
        } else if (images.isNotEmpty() || videos.isNotEmpty() || files.isNotEmpty()) {
            // 多模态：文本段 + 图片段 + 视频/文件描述
            val arr = JSONArray()
            // 构建增强文本：包含附件描述信息
            val enhancedText = buildString {
                append(safeContent)
                if (videos.isNotEmpty()) {
                    append("\n\n[附件信息] 用户发送了以下视频文件：")
                    videos.forEach { att ->
                        val sizeMB = att.size / (1024 * 1024)
                        append("\n- 文件名: ${att.name}")
                        append("\n  路径: ${att.uri}")
                        append("\n  大小: ${sizeMB}MB")
                        append("\n  类型: ${att.mime}")
                        append("\n  提示: 如需分析视频内容，请使用 video_understanding 工具，参数为 {\"video_path\":\"${att.uri}\"}")
                    }
                }
                if (files.isNotEmpty()) {
                    append("\n\n[附件信息] 用户发送了以下文件：")
                    files.forEach { att ->
                        val sizeMB = att.size / (1024 * 1024)
                        append("\n- 文件名: ${att.name}")
                        append("\n  路径: ${att.uri}")
                        append("\n  大小: ${sizeMB}MB")
                        append("\n  类型: ${att.mime}")
                        // 对于文档类型，提示AI可以使用 aiwps_read 工具读取
                        if (att.mime.contains("pdf") || att.mime.contains("document") ||
                            att.mime.contains("text") || att.name.endsWith(".txt") ||
                            att.name.endsWith(".md") || att.name.endsWith(".csv")) {
                            append("\n  💡 你可以使用 aiwps_read 工具读取此文件内容")
                        }
                    }
                }
            }
            arr.put(JSONObject().put("type", "text").put("text", enhancedText))
            // 添加图片
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
            o.put("content", safeContent)
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
                        // 🔧 修复模型快速并发发出多个 tool_call 时 arguments 常为非法 JSON
                        // （未引号 key / 单引号 / 尾逗号 / 截断）。原样回传会被严格上游
                        // （DeepSeek/国内厂商）解析失败 → 500 "Upstream Response Error"，
                        // 并触发 500 重试在 1 秒内连发数次失败请求。落地即修复为合法 JSON，
                        // 既避免上游 500，也让工具拿到可解析参数。
                        arguments = sanitizeToolArguments(fn.optString("arguments", "{}")),
                    ),
                )
            }
            Log.i(TAG, "<<< PARSE tool_calls=${calls.size} reasoningBlank=${reasoning.isNullOrBlank()} first=${calls.firstOrNull()?.name}")
            QuroLlmResult.ToolCalls(calls, reasoning, safeString(msg, "content")?.takeIf { it.isNotBlank() })
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
     * 修复模型返回的非法 JSON 工具参数（arguments）。
     *
     * 真实 BUG 根因：模型（尤其快速并发发出多个 tool_call 时）常返回不规范的 arguments：
     *  - 键未加引号（{key: "v"}）
     *  - 使用单引号（{'key': 'v'}）
     *  - 尾随逗号（{"a":1,}）
     *  - 被截断（网络分包 / 生成中断，如 {"a": 12）
     * 这些字符串在「下一轮请求」被原样回传给严格上游（DeepSeek / 国内厂商）时会被服务端
     * 严格解析失败 → 返回 500 "Upstream Response Error"，并触发 500 重试在 1 秒内连发数次失败请求
     * （与用户反馈的「1 秒执行了好几个 / 非法传入」现象完全吻合）。
     *
     * 修复策略：先尝试直接解析（合法则规整化返回）；否则做启发式修复（单引号→双引号、
     * 去尾逗号、补引号键、闭合未结束的括号与字符串）；修复后仍非法则回退 "{}"（空参数），
     * 绝不把脏数据甩给上游，也绝不因此让整轮对话 500。
     */
    private fun sanitizeToolArguments(json: String): String {
        val trimmed = json.trim()
        if (trimmed.isEmpty()) return "{}"
        // 已是合法 JSON 对象 → 规整化（统一 key 引号）后原样返回。
        runCatching { JSONObject(trimmed) }.onSuccess { return it.toString() }
        // 已是合法 JSON 数组 / 标量 → 直接返回（参数理论上应是对象，但容错）。
        runCatching { JSONArray(trimmed) }.onSuccess { return it.toString() }
        // 否则进入启发式修复；修复后仍非法 → 回退空对象。
        val repaired = repairToolArguments(trimmed)
        return if (runCatching { JSONObject(repaired) }.isSuccess) {
            repaired
        } else {
            Log.w(TAG, "⚠️ tool arguments 修复后仍非法，回退空对象。原始=${trimmed.take(200)}")
            "{}"
        }
    }

    /** 启发式修复非法 JSON 对象字符串（单引号 / 尾逗号 / 未引号键 / 截断括号）。 */
    private fun repairToolArguments(s: String): String {
        var t = s
        // 1) 单引号 → 双引号：模型常以单引号作 JSON 定界符（{'k':'v'} → {"k":"v"}）。
        //    注：这是最佳努力修复，若字符串值内本身含裸单引号（罕见）可能受损；
        //    此时后续解析会失败并回退 "{}"，不会把脏数据甩给上游。
        t = t.replace('\'', '"')
        // 2) 去尾随逗号：{,} / {, ] / ,} 等处的多余逗号。
        t = t.replace(Regex(""",\s*([\]}])"""), "$1")
        // 3) 给未加引号的键补双引号：{ key: 或 , key: → 补引号（key 为合法标识符）。
        //    已正确加引号的键（前导字符是 "）不会命中，故不会重复加引号。
        t = t.replace(Regex("""([{,]\s*)([A-Za-z_][A-Za-z0-9_]*)\s*(\s*:)"""), "$1\"$2\"$3")
        // 4) 截断修复：闭合未结束的字符串与未闭合的 {。
        t = closeBrackets(t)
        return t
    }

    /** 闭合未结束的字符串与未闭合的 {（参数通常是纯对象，简化只补 }）。 */
    private fun closeBrackets(s: String): String {
        var depth = 0
        var inStr = false
        var esc = false
        for (ch in s) {
            if (esc) { esc = false; continue }
            when (ch) {
                '\\' -> esc = true
                '"' -> inStr = !inStr
                '{' -> if (!inStr) depth++
                '}' -> if (!inStr) depth--
            }
        }
        var r = s
        if (inStr) r += "\"" // 闭合未结束的字符串
        if (depth > 0) r += "}".repeat(depth) // 闭合未闭合的对象
        return r
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
    private suspend fun streamChat(req: Request, requestBody: String, onToken: (String) -> Unit, onThinking: ((String) -> Unit)? = null): QuroLlmResult {
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
        val retryableCodes = setOf(429, 500, 502, 503, 504)
        var lastErr: Exception? = null
        for (attempt in 0..maxRetries) {
            // 🔧 toolfix9：首 token 前可重试的 HTTP 状态码（5xx/429）命中时置此，循环外进入下一轮重试。
            //   在循环内声明 → 每轮重置，避免上一轮置位污染本轮成功结果导致误重试。
            var retryableHttp: Pair<Int, String>? = null
            if (attempt > 0) {
                // 已吐出内容 → 不再重试，按已有内容兜底（下方统一处理）。
                if (contentAcc.isNotEmpty() || toolAcc.isNotEmpty()) break
                Log.w(TAG, "<<< STREAM RETRY attempt=$attempt/$maxRetries (prev=${lastErr?.message})")
                delay(800L * attempt)
            }
            try {
                // 🔧 Bug修复「取消被当成错误展示」：execute()/readUtf8Line() 均为阻塞调用，
                //   协程取消打不断它们（长思考无 SSE 行时，取消最长要等 readTimeout=120s 才生效，
                //   且 abort 异常还会被当成「断流/网络错误」）。注册取消钩子：协程一被取消立即
                //   call.cancel() 中止阻塞读，再在 catch 里把「已取消」统一转抛 CancellationException。
                val call = client.newCall(req)
                val cancelHook = coroutineContext[Job]?.invokeOnCompletion { cause ->
                    if (cause is CancellationException) runCatching { call.cancel() }
                }
                val result = try {
                    call.execute().use { resp ->
                    if (!resp.isSuccessful) {
                        val respText = resp.body?.string().orEmpty()
                        val code = resp.code
                        // 🔧 toolfix9：流式首 token 前遇到可重试状态码（5xx/429）→ 重试，
                        //   对齐非流式 chat() 路径。此前流式 500 直接判死甩给用户，
                        //   而上游（token-plan 中转）常间歇性 500，重试大多能恢复。
                        //   仅当「尚未吐出任何内容」时才重试，避免对已生成内容重复计费/错乱。
                        if (code in retryableCodes && attempt < maxRetries && contentAcc.isEmpty() && toolAcc.isEmpty()) {
                            retryableHttp = code to respText
                            return@use null
                        }
                        // 🔧 流式路径的 500 也必须落盘诊断（旧版漏了 → 没有文件）
                        dumpLlmErrorPayload(code, respText, requestBody, req.url.toString())
                        return@use QuroLlmResult.Error(friendlyHttpError(code, respText))
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
                } finally {
                    cancelHook?.dispose()
                }
                // HTTP 4xx（鉴权/参数错）属确定性失败，直接返回不重试（避免重复生成/计费）。
                if (result is QuroLlmResult.Error) return result
                // 🔧 toolfix9：首 token 前可重试状态码已在上方置 retryableHttp → 进入下一轮重试。
                if (retryableHttp != null) {
                    lastErr = Exception("HTTP ${retryableHttp.first}")
                    Log.w(TAG, "<<< STREAM RETRY (http ${retryableHttp.first}) attempt=$attempt/$maxRetries")
                    continue
                }
                // result 仅在成功路径（Text/ToolCalls）或非流式 Error 时非 null；此处兜底防御。
                return result ?: QuroLlmResult.Error("流式响应为空（未知原因）")
            } catch (e: Exception) {
                // 🔧 取消信号必须原样向上抛：否则会被当成"断流截断"兜底成成功文本，
                // 导致「停止生成/切换会话」不出现"⏹ 已停止生成"提示。
                if (e is kotlinx.coroutines.CancellationException) throw e
                // 🔧 协程已取消（用户停止/切会话）时，OkHttp 被 call.cancel() 中止而抛出
                // IOException("Canceled"/"Socket closed")：视为干净取消而非网络错误/断流，
                // 转抛 CancellationException 让上层走「⏹ 已停止生成」，绝不落错误气泡。
                if (coroutineContext[Job]?.isActive == false) {
                    throw kotlinx.coroutines.CancellationException("stream canceled by caller", e)
                }
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
        dumpLlmErrorPayload(-1, "流式连接/解析异常: ${lastErr?.message}", requestBody, req.url.toString())
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
                // 🔧 同样修复流式分片拼接出的非法 JSON arguments（见 sanitizeToolArguments 说明）。
                QuroToolCall(id = t.id ?: "call_$i", name = t.name, arguments = sanitizeToolArguments(t.arguments.ifBlank { "{}" }))
            }
            Log.i(TAG, "<<< STREAM tool_calls=${calls.size} reasoningBlank=${reasoning.isNullOrBlank()} first=${calls.firstOrNull()?.name}")
            QuroLlmResult.ToolCalls(calls, reasoning, contentAcc.toString().takeIf { it.isNotBlank() })
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
