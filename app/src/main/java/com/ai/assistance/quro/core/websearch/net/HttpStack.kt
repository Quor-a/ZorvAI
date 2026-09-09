package com.ai.assistance.quro.core.websearch.net

import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * HttpStack —— 联网能力的唯一出口。
 *
 * 约束：
 * 1. 只用 INTERNET 权限，不申请任何敏感权限；
 * 2. 统一移动端 UA 与语言头，降低被 WAF 拦截概率；
 * 3. 强制超时上限，避免任一环节卡死拖垮 Agent 主流程；
 * 4. 自动跟随重定向，并限制跳转深度由 OkHttp 默认处理。
 */
object HttpStack {

    private const val UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(8, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .build()
    }

    /** 正文抓取专用：允许更长读取时间（大页面） */
    private val fetchClient: OkHttpClient by lazy {
        client.newBuilder().readTimeout(15, TimeUnit.SECONDS).build()
    }

    /**
     * 带状态诊断的响应。
     * 只返回 body 的话，403 / 超时 / 解析出 0 条这三类问题无法区分，
     * 排查时会完全抓瞎，因此这里保留状态码与错误原因。
     */
    data class RawResponse(
        val code: Int,
        val body: String?,
        val error: String?,
        val costMs: Long
    ) {
        val ok: Boolean get() = code in 200..299 && body != null
    }

    fun request(url: String, forHtml: Boolean = false, timeoutMs: Long = 0): RawResponse {
        val t0 = System.currentTimeMillis()
        return try {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", UA)
                .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                .header("Accept", if (forHtml) "text/html,application/xhtml+xml,*/*;q=0.8" else "*/*")
                .get()
                .build()
            val c = if (timeoutMs > 0) {
                client.newBuilder()
                    .callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                    .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                    .build()
            } else if (forHtml) fetchClient else client
            c.newCall(req).execute().use { resp ->
                RawResponse(resp.code, resp.body?.string(), null, System.currentTimeMillis() - t0)
            }
        } catch (e: Exception) {
            RawResponse(-1, null, e.javaClass.simpleName + ": " + e.message, System.currentTimeMillis() - t0)
        }
    }

    fun get(url: String, forHtml: Boolean = false): String? =
        request(url, forHtml).takeIf { it.ok }?.body

    fun postForm(url: String, params: Map<String, String>): String? = runCatching {
        val fb = FormBody.Builder()
        params.forEach { (k, v) -> fb.add(k, v) }
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
            .post(fb.build())
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            resp.body?.string()
        }
    }.getOrNull()
}
