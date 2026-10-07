package com.codecanvas.llm

import com.codecanvas.core.extract.CodeBlockExtractor
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 大模型流式接入（OpenAI 兼容协议 /v1/chat/completions）。
 *
 * 两个必须遵守的点：
 * 1. **readTimeout(0)** —— SSE 是长连接，任何有限读超时都会在生成中途掐断
 * 2. **API Key 不放端上** —— 请让 [baseUrl] 指向你自己的后端代理，
 *    由后端持有密钥并做鉴权、限流与审计
 */
class LlmStreamClient(
    private val baseUrl: String,
    private val apiKey: String = "",
    private val model: String = "gpt-4o-mini",
    private val systemPrompt: String = DEFAULT_SYSTEM_PROMPT,
) {

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)   // 关键：SSE 不能设读超时
        .build()

    /** 逐 token 流式返回 */
    fun stream(prompt: String, history: List<ChatMessage> = emptyList()): Flow<String> = callbackFlow {
        val body = buildBody(prompt, history, stream = true)
        val request = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/v1/chat/completions")
            .apply { if (apiKey.isNotBlank()) addHeader("Authorization", "Bearer $apiKey") }
            .post(body)
            .build()

        val call = client.newCall(request)
        call.enqueue(object : Callback {
            override fun onResponse(call: Call, response: Response) {
                if (!response.isSuccessful) {
                    close(IOException("HTTP ${response.code}: ${response.message}"))
                    return
                }
                response.body?.source()?.use { src ->
                    try {
                        while (!src.exhausted()) {
                            val line = src.readUtf8Line() ?: break
                            if (!line.startsWith("data: ")) continue
                            val payload = line.removePrefix("data: ")
                            if (payload == "[DONE]") { close(); return }
                            val delta = runCatching { parseDelta(payload) }.getOrNull()
                            if (!delta.isNullOrEmpty()) trySend(delta)
                        }
                        close()
                    } catch (t: Throwable) {
                        close(t as? IOException ?: IOException(t))
                    }
                } ?: close()
            }

            override fun onFailure(call: Call, e: IOException) {
                close(e)
            }
        })

        awaitClose { call.cancel() }
    }

    /** 一次性拿到完整回复（内部攒流） */
    suspend fun complete(prompt: String, history: List<ChatMessage> = emptyList()): String {
        val sb = StringBuilder()
        stream(prompt, history).collect { sb.append(it) }
        return sb.toString()
    }

    /**
     * 生成代码并抽取代码块。
     * 注意：只有 Markdown 完整闭合后才抽取，避免拿到半截 ``` 导致高亮错乱。
     */
    suspend fun generateCode(
        requirement: String,
        language: String,
        history: List<ChatMessage> = emptyList(),
    ): CodeGenResult {
        val prompt = buildString {
            append("请用 $language 实现以下需求，只输出一个代码块，不要多余解释。\n\n")
            append(requirement)
        }
        val md = complete(prompt, history)
        val blocks = CodeBlockExtractor.extract(md)
        return CodeGenResult(
            markdown = md,
            blocks = blocks,
            complete = CodeBlockExtractor.isMdComplete(md),
        )
    }

    private fun buildBody(prompt: String, history: List<ChatMessage>, stream: Boolean) =
        JSONObject().apply {
            put("model", model)
            put("stream", stream)
            put("messages", JSONArray().apply {
                put(JSONObject().put("role", "system").put("content", systemPrompt))
                history.forEach { put(JSONObject().put("role", it.role).put("content", it.content)) }
                put(JSONObject().put("role", "user").put("content", prompt))
            })
        }.toString().toRequestBody("application/json".toMediaType())

    private fun parseDelta(payload: String): String? = runCatching {
        JSONObject(payload)
            .getJSONArray("choices")
            .getJSONObject(0)
            .getJSONObject("delta")
            .optString("content", "")
    }.getOrNull()

    data class ChatMessage(val role: String, val content: String)

    data class CodeGenResult(
        val markdown: String,
        val blocks: List<CodeBlockExtractor.Block>,
        /** false 表示流式被截断，不要渲染 */
        val complete: Boolean,
    ) {
        val code: String? get() = blocks.firstOrNull()?.code
        val language: String? get() = blocks.firstOrNull()?.language
    }

    companion object {
        private const val DEFAULT_SYSTEM_PROMPT =
            "你是一个代码生成助手。只输出代码本身，使用 Markdown 代码块包裹，不要输出任何解释性文字。"
    }
}
