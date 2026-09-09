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

    fun get(url: String, forHtml: Boolean = false): String? = runCatching {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
            .header("Accept", if (forHtml) "text/html,application/xhtml+xml,*/*;q=0.8" else "*/*")
            .get()
            .build()
        val c = if (forHtml) fetchClient else client
        c.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            resp.body?.string()
        }
    }.getOrNull()

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
