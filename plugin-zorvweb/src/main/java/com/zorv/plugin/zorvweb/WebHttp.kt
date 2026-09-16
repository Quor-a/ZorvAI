package com.zorv.plugin.zorvweb

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * HTTP 传输（原 ZorvBrowser 的 `http_request` ACI 能力）。
 *
 * 两处「受控端包袱」被丢掉：
 *  1. 原实现用 okhttp，是为了与主程序依赖版本对称；插件改用 JDK `HttpURLConnection`，**零额外依赖**。
 *  2. 原实现响应体 >15 万字符要 gzip 塞进 `response_body_gz` —— 那是为了绕开 **Binder 1MB 事务上限**
 *     （能力要跨进程回传给控制端）。插件与宿主同进程，返回值就是内存里一个 String，
 *     没有 Binder，没有 1MB 限制，因此 gzip 那套整段删掉，只保留按需截断。
 *
 * 明文 HTTP：是否放行由**宿主**的 `network_security_config.xml` 决定（插件清单管不着），
 * 宿主已把 `base-config cleartextTrafficPermitted` 设为 true，所以同网段 LAN 明文可直接访问。
 */
internal object WebHttp {

    private const val DEFAULT_MAX = 200_000

    suspend fun request(
        rawUrl: String,
        method: String,
        headersJson: String,
        body: String,
        maxChars: Int,
    ): String = withContext(Dispatchers.IO) {
        val url = rawUrl.trim()
        if (url.isEmpty()) return@withContext err("url 不能为空")
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return@withContext err("只支持 http/https 地址")
        }
        val limit = if (maxChars > 0) maxChars else DEFAULT_MAX

        var conn: HttpURLConnection? = null
        try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = method.ifBlank { "GET" }.uppercase()
                connectTimeout = 15_000
                readTimeout = 30_000
                instanceFollowRedirects = true
                useCaches = false
            }
            // 自定义请求头
            if (headersJson.isNotBlank()) {
                runCatching {
                    val o = JSONObject(headersJson)
                    o.keys().forEach { k -> conn.setRequestProperty(k, o.optString(k)) }
                }
            }
            if (body.isNotEmpty() && conn.requestMethod !in listOf("GET", "HEAD")) {
                conn.doOutput = true
                // 未显式指定 Content-Type 时给个默认，避免服务端拒收
                if (conn.getRequestProperty("Content-Type") == null) {
                    conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                }
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }

            val t0 = System.currentTimeMillis()
            val status = conn.responseCode
            val stream = if (status in 200..299) conn.inputStream else conn.errorStream
            val bytes = runCatching { stream?.readBytes() ?: ByteArray(0) }.getOrDefault(ByteArray(0))
            val ms = System.currentTimeMillis() - t0

            val full = String(bytes, Charsets.UTF_8)
            val truncated = full.length > limit
            val text = if (truncated) full.substring(0, limit) else full

            val hdrs = JSONObject()
            runCatching {
                conn.headerFields.entries
                    .filter { it.key != null }
                    .forEach { (k, v) -> hdrs.put(k, v.joinToString(", ")) }
            }

            JSONObject().apply {
                put("ok", status in 200..399)
                put("status", status)
                put("url", conn.url?.toString() ?: url)
                put("method", conn.requestMethod)
                put("content_type", conn.contentType ?: "")
                put("size", bytes.size)
                put("chars", full.length)
                put("truncated", truncated)
                put("time_ms", ms)
                put("headers", hdrs)
                put("body", text)
            }.toString()
        } catch (t: Throwable) {
            err("请求失败：${t.message ?: t.javaClass.simpleName}")
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    private fun err(msg: String) = JSONObject().put("ok", false).put("error", msg).toString()
}
