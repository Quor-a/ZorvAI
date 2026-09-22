package com.ai.assistance.quro.genui.app.llm

import com.ai.assistance.quro.genui.app.store.ModelProvider
import com.ai.assistance.quro.genui.app.store.Protocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * A2UI 生成任务的一次流式调用。
 * [onChunk] 每次返回增量文本（HTML 片段，可能截断在标签中间——由渲染器缓冲）。
 */
class LLMClient {

    data class StreamCallbacks(
        val onChunk: (String) -> Unit,
        val onDone: (String) -> Unit,
        val onError: (String) -> Unit
    )

    @Volatile var cancelled = false
        private set

    fun cancel() { cancelled = true }

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)     // 长回复流式读取
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun stream(
        provider: ModelProvider,
        system: String,
        user: String,
        onChunk: (String) -> Unit,
        onDone: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        cancelled = false
        withContext(Dispatchers.IO) {
            try {
                val call = when (provider.protocol) {
                    Protocol.OPENAI    -> openAIRequest(provider, system, user)
                    Protocol.ANTHROPIC -> anthropicRequest(provider, system, user)
                    Protocol.GEMINI    -> geminiRequest(provider, system, user)
                }
                http.newCall(call).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        onError("HTTP ${resp.code}: ${resp.body?.string()?.take(300)}")
                        return@use
                    }
                    val source = resp.body!!.source()
                    val full = StringBuilder()
                    while (!cancelled) {
                        val line = source.readUtf8Line() ?: break
                        if (line.startsWith("data:")) {
                            val payload = line.removePrefix("data:").trim()
                            if (payload == "[DONE]") break
                            val delta = when (provider.protocol) {
                                Protocol.OPENAI, Protocol.GEMINI -> parseOpenAILikeChunk(payload)
                                Protocol.ANTHROPIC               -> parseAnthropicChunk(payload)
                            }
                            if (delta != null) { full.append(delta); onChunk(delta) }
                        }
                    }
                    onDone(full.toString())
                }
            } catch (e: Exception) {
                if (!cancelled) onError(e.message ?: "网络错误")
            }
        }
    }

    /**
     * 连接测试的详细结果。
     * @param ok        是否连通
     * @param ms        往返耗时
     * @param model     服务端实际回显的模型名（有的网关会改写成别的，这个能让用户发现）
     * @param message   人可读的结论或错误
     * @param hint      下一步该做什么（当 ok=false 时给可操作建议）
     */
    data class TestResult(
        val ok: Boolean,
        val ms: Long,
        val model: String = "",
        val message: String = "",
        val hint: String = ""
    )

    /**
     * 连接测试：发一个 max_tokens=1 的最小请求。
     * 不止判断"通没通"——还回显服务端认到的模型名、给出失败的具体原因与可操作建议。
     * 手填 baseUrl 的典型错误（少 /v1、http 被拒、Key 错、模型名不存在）都会在这里被指出。
     */
    suspend fun testDetailed(provider: ModelProvider): TestResult = withContext(Dispatchers.IO) {
        val t0 = System.currentTimeMillis()

        // —— 先做静态自检，能不发请求就不发 ——
        if (provider.baseUrl.isBlank() || provider.baseUrl == "https://")
            return@withContext TestResult(false, 0, message = "端点地址为空", hint = "从预设里选一个供应商，或手动填入带 /v1 的地址")
        if (!provider.baseUrl.startsWith("http"))
            return@withContext TestResult(false, 0, message = "端点必须以 http:// 或 https:// 开头", hint = "补全协议头")
        if (provider.model.isBlank())
            return@withContext TestResult(false, 0, message = "模型名为空", hint = "填入模型 ID，如 deepseek-chat")
        if (provider.protocol != Protocol.OPENAI && provider.baseUrl.endsWith("/chat/completions"))
            return@withContext TestResult(false, 0, message = "端点写到了具体路径", hint = "只填根地址（到 /v1 为止），路径由端上拼接")

        try {
            val j = JSONObject()
                .put("model", provider.model)
                .put("max_tokens", 1)
                .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", "hi")))
            val call = when (provider.protocol) {
                Protocol.OPENAI -> Request.Builder()
                    .url(provider.baseUrl.trimEnd('/') + "/chat/completions")
                    .header("Authorization", "Bearer ${provider.apiKey}")
                    .post(j.toString().toRequestBody("application/json".toMediaType()))
                    .build()
                Protocol.ANTHROPIC -> Request.Builder()
                    .url(provider.baseUrl.trimEnd('/') + "/messages")
                    .header("x-api-key", provider.apiKey)
                    .header("anthropic-version", "2023-06-01")
                    .post(j.toString().toRequestBody("application/json".toMediaType()))
                    .build()
                Protocol.GEMINI -> Request.Builder()
                    .url("${provider.baseUrl.trimEnd('/')}/models/${provider.model}:generateContent?key=${provider.apiKey}")
                    .post(JSONObject().put("contents", JSONArray().put(JSONObject()
                        .put("parts", JSONArray().put(JSONObject().put("text", "hi")))))
                        .toString().toRequestBody("application/json".toMediaType()))
                    .build()
            }
            http.newCall(call).execute().use { resp ->
                val ms = System.currentTimeMillis() - t0
                val bodyText = resp.body?.string().orEmpty()
                if (resp.isSuccessful) {
                    val echoed = runCatching { JSONObject(bodyText).optString("model") }.getOrDefault("")
                    TestResult(true, ms, echoed.ifBlank { provider.model },
                        message = "连通 · $ms ms",
                        hint = if (echoed.isNotBlank() && echoed != provider.model)
                            "注意：服务端回显的模型是「$echoed」，与你填的不一致" else "")
                } else {
                    val (msg, hint) = diagnose(resp.code, bodyText)
                    TestResult(false, ms, message = msg, hint = hint)
                }
            }
        } catch (e: Exception) {
            val ms = System.currentTimeMillis() - t0
            val (msg, hint) = when (e) {
                is java.net.UnknownHostException -> "域名解析失败" to "检查端点域名是否拼错、手机是否联网"
                is java.net.SocketTimeoutException -> "连接超时" to "端点可能不可达；国内网络访问国际服务常需代理"
                is javax.net.ssl.SSLException -> "SSL 握手失败" to "该端点可能只支持 http（本地 Ollama 常见），或证书异常"
                is java.net.ConnectException -> "连接被拒绝" to "本地服务是否已启动？（如 ollama serve）"
                else -> (e.message ?: "连接失败") to "检查端点、网络与 Key"
            }
            TestResult(false, ms, message = msg, hint = hint)
        }
    }

    /** 把 HTTP 状态码 + 服务端错误体翻译成可操作建议 */
    private fun diagnose(code: Int, body: String): Pair<String, String> {
        val detail = runCatching {
            val o = JSONObject(body)
            o.optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() }
                ?: o.optString("message").takeIf { it.isNotBlank() }
        }.getOrNull()?.take(140).orEmpty()
        val msg = "HTTP $code" + if (detail.isNotBlank()) "：$detail" else ""
        val hint = when (code) {
            401, 403 -> "API Key 无效或无权限 —— 检查 Key 是否正确、是否有多余空格"
            404 -> "端点路径或模型名不存在 —— 检查 baseUrl 是否包含正确的版本号（如 /v1）"
            429 -> "触发限流或额度用尽"
            in 500..599 -> "服务端异常，稍后重试；若持续则可换其他供应商"
            else -> ""
        }
        return msg to hint
    }

    /** 旧接口：仅返回文本结论（保留兼容，内部走 testDetailed） */
    suspend fun test(provider: ModelProvider): String = testDetailed(provider).let {
        if (it.ok) "OK" else it.message
    }

    /**
     * 非流式单轮（Agent 决策轮专用）：返回 OpenAI 格式的 message 对象，
     * 可能含 tool_calls。仅 OPENAI 协议支持 tools；其余协议忽略。
     */
    suspend fun chatOnce(provider: ModelProvider, messages: JSONArray, tools: JSONArray?): JSONObject =
        withContext(Dispatchers.IO) {
            val body = JSONObject()
                .put("model", provider.model)
                .put("messages", messages)
            if (tools != null && tools.length() > 0) body.put("tools", tools)
            val call = Request.Builder()
                .url(provider.baseUrl.trimEnd('/') + "/chat/completions")
                .header("Authorization", "Bearer ${provider.apiKey}")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()
            http.newCall(call).execute().use { resp ->
                check(resp.isSuccessful) { "HTTP ${resp.code}: ${resp.body?.string()?.take(200)}" }
                val obj = JSONObject(resp.body!!.string())
                obj.getJSONArray("choices").getJSONObject(0).getJSONObject("message")
            }
        }

    /**
     * 流式（自定义 messages，渲染轮专用）。
     */
    suspend fun chatStream(
        provider: ModelProvider,
        system: String,
        messages: JSONArray,
        onChunk: (String) -> Unit,
        onDone: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        cancelled = false
        withContext(Dispatchers.IO) {
            try {
                val msgs = JSONArray().put(
                    JSONObject().put("role", "system").put("content", system)
                )
                for (i in 0 until messages.length()) {
                    val m = messages.getJSONObject(i)
                    if (m.optString("role") == "system") continue   // 外部 system 优先
                    msgs.put(m)
                }
                val body = JSONObject().put("model", provider.model).put("messages", msgs)
                    .put("stream", true)
                    .let { applySampling(it, provider) }
                val call = when (provider.protocol) {
                    Protocol.OPENAI -> Request.Builder()
                        .url(provider.baseUrl.trimEnd('/') + "/chat/completions")
                        .header("Authorization", "Bearer ${provider.apiKey}")
                        .post(body.toString().toRequestBody("application/json".toMediaType()))
                        .build()
                    else -> anthropicGeminiStream(provider, system, msgs, body)
                }
                http.newCall(call).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        onError("HTTP ${resp.code}: ${resp.body?.string()?.take(300)}")
                        return@use
                    }
                    val source = resp.body!!.source()
                    val full = StringBuilder()
                    while (!cancelled) {
                        val line = source.readUtf8Line() ?: break
                        if (line.startsWith("data:")) {
                            val payload = line.removePrefix("data:").trim()
                            if (payload == "[DONE]") break
                            val delta = when (provider.protocol) {
                                Protocol.OPENAI, Protocol.GEMINI -> parseOpenAILikeChunk(payload)
                                Protocol.ANTHROPIC -> parseAnthropicChunk(payload)
                            }
                            if (delta != null) { full.append(delta); onChunk(delta) }
                        }
                    }
                    onDone(full.toString())
                }
            } catch (e: Exception) {
                if (!cancelled) onError(e.message ?: "网络错误")
            }
        }
    }

    /** Anthropic / Gemini 的流式请求体构造（msgs 已含 system） */
    private fun anthropicGeminiStream(provider: ModelProvider, system: String, msgs: JSONArray, body: JSONObject): Request {
        return when (provider.protocol) {
            Protocol.ANTHROPIC -> {
                val b = JSONObject().put("model", provider.model).put("stream", true)
                    .let { if (provider.protocol == Protocol.ANTHROPIC) applySamplingAnthropic(it, provider) else applySampling(it, provider) }
                    .put("system", system)
                    .put("messages", JSONArray().apply {
                        for (i in 0 until msgs.length()) {
                            val m = msgs.getJSONObject(i)
                            if (m.optString("role") != "system") put(m)
                        }
                    })
                Request.Builder()
                    .url(provider.baseUrl.trimEnd('/') + "/messages")
                    .header("x-api-key", provider.apiKey)
                    .header("anthropic-version", "2023-06-01")
                    .post(b.toString().toRequestBody("application/json".toMediaType()))
                    .build()
            }
            else -> {
                val contents = JSONArray()
                for (i in 0 until msgs.length()) {
                    val m = msgs.getJSONObject(i)
                    if (m.optString("role") == "system") continue
                    contents.put(JSONObject()
                        .put("role", if (m.optString("role") == "assistant") "model" else "user")
                        .put("parts", JSONArray().put(JSONObject().put("text", m.optString("content")))))
                }
                val genCfg = JSONObject()
                    .put("temperature", provider.temperature.toDouble())
                    .apply { if (provider.topP < 1f) put("topP", provider.topP.toDouble()) }
                    .apply { if (provider.maxTokens > 0) put("maxOutputTokens", provider.maxTokens) }
                val b = JSONObject().put("contents", contents)
                    .put("generationConfig", genCfg)
                    .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
                Request.Builder()
                    .url("${provider.baseUrl.trimEnd('/')}/models/${provider.model}:streamGenerateContent?alt=sse&key=${provider.apiKey}")
                    .post(b.toString().toRequestBody("application/json".toMediaType()))
                    .build()
            }
        }
    }

    /**
     * 拉取供应商的可用模型列表（参考 ZorvAI 的 QuroModelConfigScreen 手动拉模型）。
     * 三种协议分别请求各自的列表端点，返回模型 ID 列表；失败抛异常（由调用方捕获展示）。
     */
    suspend fun fetchModels(provider: ModelProvider): List<String> = withContext(Dispatchers.IO) {
        val req = when (provider.protocol) {
            Protocol.OPENAI -> Request.Builder()                     // OpenAI 兼容 / DeepSeek / Moonshot / GLM / Ollama(/v1)
                .url(provider.baseUrl.trimEnd('/') + "/models")
                .header("Authorization", "Bearer ${provider.apiKey}")
                .build()
            Protocol.ANTHROPIC -> Request.Builder()
                .url(provider.baseUrl.trimEnd('/') + "/models?limit=100")
                .header("x-api-key", provider.apiKey)
                .header("anthropic-version", "2023-06-01")
                .build()
            Protocol.GEMINI -> Request.Builder()
                .url("${provider.baseUrl.trimEnd('/')}/models?key=${provider.apiKey}&pageSize=100")
                .build()
        }
        http.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            check(resp.isSuccessful) { "HTTP ${resp.code}: ${body.take(160)}" }
            val arr = when (provider.protocol) {
                Protocol.OPENAI, Protocol.ANTHROPIC ->
                    JSONObject(body).optJSONArray("data") ?: JSONArray()
                Protocol.GEMINI -> JSONObject(body).optJSONArray("models") ?: JSONArray()
            }
            val out = mutableListOf<String>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val id = when (provider.protocol) {
                    Protocol.OPENAI, Protocol.ANTHROPIC -> o.optString("id")
                    Protocol.GEMINI -> o.optString("name").removePrefix("models/")
                }
                if (id.isNotBlank()) out.add(id)
            }
            out.distinct()
        }
    }

    // ---------- 协议一：OpenAI 兼容（OpenAI/DeepSeek/Moonshot/GLM/Ollama/vLLM…） ----------

    private fun openAIRequest(p: ModelProvider, system: String, user: String): Request {
        val body = JSONObject()
            .put("model", p.model)
            .put("stream", true)
            .put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content", system))
                .put(JSONObject().put("role", "user").put("content", user)))
        return Request.Builder()
            .url(p.baseUrl.trimEnd('/') + "/chat/completions")
            .header("Authorization", "Bearer ${p.apiKey}")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
    }

    private fun parseOpenAILikeChunk(payload: String): String? = runCatching {
        val obj = JSONObject(payload)
        // OpenAI 兼容格式
        obj.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("delta")
            ?.optString("content")?.takeIf { it.isNotEmpty() }
            // Gemini SSE 格式兜底
            ?: obj.optJSONArray("candidates")?.optJSONObject(0)
                ?.optJSONObject("content")?.optJSONArray("parts")?.optJSONObject(0)
                ?.optString("text")?.takeIf { it.isNotEmpty() }
    }.getOrNull()

    // ---------- 协议二：Anthropic ----------

    /**
     * 把供应商的采样参数写进请求体，按协议适配字段名。
     *
     * 为什么单独抽：三种协议的参数名与取值范围略有差异（OpenAI 用 `max_tokens`、
     * Anthropic 必须给 `max_tokens` 且不能有 `top_p` 与 `temperature` 同时极端值、
     * Gemini 用 `generationConfig` 嵌套）。集中一处才能保证三个入口行为一致。
     */
    private fun applySampling(body: JSONObject, p: ModelProvider): JSONObject {
        // max_tokens：0 表示不限制；OpenAI 部分端点不接受该字段为 0，故跳过
        if (p.maxTokens > 0) body.put("max_tokens", p.maxTokens)
        body.put("temperature", p.temperature.toDouble())
        if (p.topP < 1f) body.put("top_p", p.topP.toDouble())
        return body
    }

    /**
     * Anthropic 专用：该协议 **强制要求** max_tokens 存在，且不接受 0。
     * 用户选"不限制"时必须兜一个足够大的值，否则请求直接被服务端拒绝。
     */
    private fun applySamplingAnthropic(body: JSONObject, p: ModelProvider): JSONObject {
        val mt = if (p.maxTokens > 0) p.maxTokens else 8192
        body.put("max_tokens", mt.coerceIn(256, 64_000))
        body.put("temperature", p.temperature.toDouble())
        if (p.topP < 1f) body.put("top_p", p.topP.toDouble())
        return body
    }

    private fun anthropicRequest(p: ModelProvider, system: String, user: String): Request {
        val body = JSONObject()
            .put("model", p.model)
            .put("stream", true)
            .let { applySamplingAnthropic(it, p) }
            .put("system", system)
            .put("messages", JSONArray()
                .put(JSONObject().put("role", "user").put("content", user)))
        return Request.Builder()
            .url(p.baseUrl.trimEnd('/') + "/messages")
            .header("x-api-key", p.apiKey)
            .header("anthropic-version", "2023-06-01")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
    }

    private fun parseAnthropicChunk(payload: String): String? = runCatching {
        val obj = JSONObject(payload)
        if (obj.optString("type") == "content_block_delta")
            obj.optJSONObject("delta")?.optString("text")?.takeIf { it.isNotEmpty() } else null
    }.getOrNull()

    // ---------- 协议三：Gemini ----------

    private fun geminiRequest(p: ModelProvider, system: String, user: String): Request {
        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
            .put("contents", JSONArray().put(JSONObject()
                .put("role", "user")
                .put("parts", JSONArray().put(JSONObject().put("text", user)))))
        return Request.Builder()
            .url("${p.baseUrl.trimEnd('/')}/models/${p.model}:streamGenerateContent?alt=sse&key=${p.apiKey}")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
    }
}
