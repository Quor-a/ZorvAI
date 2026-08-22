package com.ai.assistance.quro.core.tools

import android.content.Context
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * HTTP 请求工具（http_request / multipart_request）。
 * 引擎无关；使用项目已引入的 OkHttp（Square, Apache-2.0）发起请求。
 */
class HttpRequestTool : QuroTool {
    override val name = "http_request"
    override val description = "发起 HTTP 请求。参数 {\"url\":\"https://... 或相对路径\",\"method\":\"GET|POST|PUT|DELETE\",\"headers\":\"可选 JSON 对象字符串\",\"body\":\"可选请求体(字符串)\",\"timeout\":10(秒),\"service\":\"可选 第三方授权服务别名\"}。" +
        "传入 service（先用 auth_service_add 保存）后，自动以该服务的 baseUrl 补全相对 url 并注入鉴权头（Bearer/X-API-Key/Basic），显式 headers 可覆盖。" +
        "返回状态码 + 响应体（截断到 8KB）。"
    override val parametersJson = """{
        "type":"object",
        "properties":{
            "url":{"type":"string","description":"目标 URL，或相对路径（配合 service 的 baseUrl 补全）"},
            "method":{"type":"string","description":"HTTP 方法，默认 GET"},
            "headers":{"type":"string","description":"可选，JSON 对象字符串，如 {\"X-Custom\":\"v\"}，会覆盖服务注入的同名头"},
            "body":{"type":"string","description":"可选，请求体（字符串）"},
            "timeout":{"type":"integer","description":"超时秒数，默认 10，最大 30"},
            "service":{"type":"string","description":"可选，第三方授权服务别名（先用 auth_service_add 保存），自动带鉴权头与 baseUrl"}
        },
        "required":["url"]
    }"""

    override fun run(context: Context, arguments: String): String {
        val jo = JSONObject(arguments)
        val rawUrl = jo.optString("url", "").trim()
        if (rawUrl.isEmpty()) return "缺少 url 参数"

        // 第三方服务授权：补全 baseUrl + 注入鉴权头
        val serviceName = jo.optString("service", "").trim()
        var url = rawUrl
        val authHeaders = mutableMapOf<String, String>()
        if (serviceName.isNotEmpty()) {
            val svc = QuroAuthStore.get(context, serviceName)
                ?: return "未找到授权服务「$serviceName」，请先用 auth_service_add 添加。"
            if (svc.baseUrl.isNotEmpty() && !rawUrl.startsWith("http://") && !rawUrl.startsWith("https://")) {
                val base = svc.baseUrl.trimEnd('/')
                url = if (rawUrl.startsWith("/")) "$base$rawUrl" else "$base/$rawUrl"
            }
            svc.resolveHeaders().forEach { (k, v) -> authHeaders[k] = v }
        }

        if (!url.startsWith("http://") && !url.startsWith("https://")) return "url 必须以 http(s):// 开头"
        val method = jo.optString("method", "GET").uppercase()
        val timeout = jo.optInt("timeout", 10).coerceIn(1, 30)
        val body = jo.optString("body", "")
        val explicit = runCatching {
            jo.optString("headers", "").let { if (it.isBlank()) JSONObject() else JSONObject(it) }
        }.getOrElse { return "headers 不是合法 JSON 对象" }

        // 合并：服务鉴权头为底，显式头覆盖
        val merged = authHeaders.toMutableMap()
        explicit.keys().forEach { k -> merged[k] = explicit.optString(k) }

        return try {
            val builder = Request.Builder().url(url)
            merged.forEach { (k, v) -> builder.addHeader(k, v) }
            val reqBody = if (body.isNotEmpty() && method != "GET" && method != "HEAD") {
                body.toRequestBody("application/json; charset=utf-8".toMediaType())
            } else null
            val request = builder.method(method, reqBody).build()
            val c = client.newBuilder()
                .connectTimeout(timeout.toLong(), TimeUnit.SECONDS)
                .readTimeout(timeout.toLong(), TimeUnit.SECONDS)
                .build()
            c.newCall(request).execute().use { resp ->
                val respBody = resp.body?.string() ?: ""
                val truncated = if (respBody.length > 8000) respBody.take(8000) + "\n...[截断]" else respBody
                "HTTP ${resp.code} ${resp.message}\n$truncated"
            }
        } catch (e: Exception) {
            "HTTP 请求失败: ${e.message}"
        }
    }

    private companion object {
        val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }
}
