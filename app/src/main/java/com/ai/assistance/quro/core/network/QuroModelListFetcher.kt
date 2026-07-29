package com.ai.assistance.quro.core.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Quro 模型列表抓取器（原创）：对接 OpenAI 兼容的 /models 接口，
 * 以及 Ollama 的 /api/tags 接口，拉取当前接入点下可用的模型名列表。
 * 仅用 OkHttp + org.json，无第三方序列化依赖。
 *
 * 设计取舍：
 * - 标准 OpenAI 兼容端点（含 LM Studio / vLLM / OpenRouter 等）：用 `{baseUrl}/models`
 * - Ollama 端点（通常为 http://localhost:11434）：自动检测并回退到 `{baseUrl}/api/tags`
 * - 本地回环服务通常免密钥：apiKey 为空时不带鉴权头
 */
sealed interface QuroModelListResult {
    data class Success(val models: List<String>) : QuroModelListResult
    data class Error(val message: String) : QuroModelListResult
}

class QuroModelListFetcher(
    connectTimeout: Long = 30,
    readTimeout: Long = 60,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(connectTimeout, TimeUnit.SECONDS)
        .readTimeout(readTimeout, TimeUnit.SECONDS)
        .build(),
) {
    /**
     * 判断给定 baseUrl 是否指向 Ollama 服务。
     * 启发式：端口 11434 或 URL 中包含 "ollama" 关键字。
     */
    private fun isLikelyOllama(baseUrl: String): Boolean {
        val lower = baseUrl.lowercase()
        return lower.contains(":11434") || lower.contains("ollama")
    }

    suspend fun fetch(baseUrl: String, apiKey: String): QuroModelListResult {
        return withContext(Dispatchers.IO) {
            try {
                val normalized = baseUrl.trim().trimEnd('/')
                // Ollama 使用 /api/tags 接口；其余使用标准 OpenAI /models 接口
                val url = if (isLikelyOllama(normalized)) "$normalized/api/tags" else "$normalized/models"
                val reqBuilder = Request.Builder().url(url)
                    .addHeader("Content-Type", "application/json")
                if (apiKey.isNotBlank()) {
                    reqBuilder.addHeader("Authorization", "Bearer $apiKey")
                }
                val req = reqBuilder.get().build()
                val resp = client.newCall(req).execute()
                val text = resp.body?.string().orEmpty()

                if (!resp.isSuccessful) {
                    // 如果标准 /models 失败且是回环地址，尝试 Ollama 格式作为回退
                    if (!isLikelyOllama(normalized) && isLoopback(normalized)) {
                        val fallbackUrl = "$normalized/api/tags"
                        val fallbackReq = Request.Builder().url(fallbackUrl)
                            .addHeader("Content-Type", "application/json")
                            .let { b -> if (apiKey.isNotBlank()) b.addHeader("Authorization", "Bearer $apiKey") else b }
                            .get().build()
                        val fallbackResp = client.newCall(fallbackReq).execute()
                        val fallbackText = fallbackResp.body?.string().orEmpty()
                        if (fallbackResp.isSuccessful) {
                            return@withContext QuroModelListResult.Success(parseOllamaTags(fallbackText))
                        }
                    }
                    return@withContext QuroModelListResult.Error("HTTP ${resp.code}: ${text.take(200)}")
                }

                // 根据响应格式选择解析器：Ollama 返回 {"models":[...]}，OpenAI 返回 {"data":[...]}
                val models = if (text.contains("\"models\"") && !text.contains("\"object\":\"list\"")) {
                    parseOllamaTags(text)
                } else {
                    parseModels(text)
                }
                QuroModelListResult.Success(models)
            } catch (e: Exception) {
                QuroModelListResult.Error(e.message ?: "拉取模型失败")
            }
        }
    }

    /**
     * 解析 OpenAI 格式模型列表 JSON：
     * `{"object":"list","data":[{"id":"gpt-4o","object":"model"}, ...]}`
     */
    internal fun parseModels(json: String): List<String> {
        val root = JSONObject(json)
        val arr = root.getJSONArray("data")
        val out = mutableListOf<String>()
        for (i in 0 until arr.length()) {
            val id = arr.getJSONObject(i).optString("id", "").trim()
            if (id.isNotEmpty()) out.add(id)
        }
        return out
    }

    /**
     * 解析 Ollama /api/tags 响应格式：
     * `{"models":[{"name":"llama3.2:latest","modified_at":"..."}, ...]}`
     */
    internal fun parseOllamaTags(json: String): List<String> {
        val root = JSONObject(json)
        // Ollama 可能返回顶层 "models" 数组
        return try {
            val arr = root.getJSONArray("models")
            val out = mutableListOf<String>()
            for (i in 0 until arr.length()) {
                val name = arr.getJSONObject(i).optString("name", "").trim()
                if (name.isNotEmpty()) out.add(name)
            }
            out
        } catch (_: Exception) {
            // 如果解析失败，回退尝试标准格式
            parseModels(json)
        }
    }

    /** 判断 URL 是否指向本地回环地址。 */
    private fun isLoopback(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("localhost") ||
               lower.contains("127.0.0.1") ||
               lower.contains("10.0.2.2") ||
               lower.contains("[::1]")
    }
}
