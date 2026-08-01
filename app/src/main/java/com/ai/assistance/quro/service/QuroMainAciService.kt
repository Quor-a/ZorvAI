package com.ai.assistance.quro.service

import ai.aci.core.ACIError
import ai.aci.core.ACIRequest
import ai.aci.core.ACIResponse
import ai.aci.core.BaseACIService
import ai.aci.core.Capability
import android.os.Bundle
import android.util.Log
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPOutputStream
import java.io.ByteArrayOutputStream

/**
 * 主应用 ACI 受控端 Service（新增 http_request 能力）。
 *
 * 主应用 QuroAI 原本只是 ACI 控制方（QuroAciManager），本 Service 让主应用
 * 同时成为 ACI 受控端，对外暴露「HTTP 传输」能力：既能发出 HTTP 请求（传），
 * 也能接收响应（收）。AI 经 aci_call("com.ai.assistance.quro", "http_request", ...)
 * 即可让主应用代为发起任意 HTTP 请求。
 *
 * 设计要点（对齐浏览器受控端 QuroControlledAciService）：
 * 1. super.onCreate() 包 try-catch —— 基类内部调 onCreateCapabilities，任何异常都会炸掉 Service
 * 2. onCall 在 Binder 线程被调用；HTTP 网络请求 offload 到后台线程 + CountDownLatch 阻塞
 *    等待（硬上限 14s，< 控制器 15s 超时），避免 NetworkOnMainThread 与控制器超时
 * 3. 响应体 >15万字符触发截断 + gzip 回退（绕开 AIDL ~1MB 事务限制），与浏览器 readHtml 一致
 * 4. onCheckPermission 白名单：自身（主应用）+ 受控浏览器包名，反向放行便于跨 App 互通
 *
 * 注：主应用无 DiagBuffer（仅浏览器模块有），诊断改用 android.util.Log。
 */
class QuroMainAciService : BaseACIService() {

    companion object {
        private const val TAG = "QuroMainACI"
        /** 自身包名（控制方 QuroAI 同时作为受控端，允许自己调自己） */
        private const val SELF_PKG = "com.ai.assistance.quro"
        /** 允许的调用方：自身 + 受控浏览器（反向放行，便于浏览器经 ACI 反调主程序） */
        private const val BROWSER_PKG = "com.ai.assistance.quro.browser"
        /** 控制器 QuroAciManager.callTimeoutMs = 15_000L；handler 硬上限留 1s 余量 */
        private const val HARD_TIMEOUT_S = 14L
    }

    override fun onCreate() {
        Log.d(TAG, "onCreate")
        try {
            super.onCreate()
        } catch (e: Throwable) {
            Log.e(TAG, "super.onCreate 崩溃: ${e.message}")
        }
    }

    override fun onCreateCapabilities(caps: MutableList<Capability>) {
        caps.add(
            Capability.create(
                "http_request",
                "HTTP 传输：发起 HTTP 请求并取回响应（既能发出请求也能接收响应）。" +
                    "支持自定义方法（GET/POST/PUT/DELETE/PATCH/HEAD 等）、请求头与请求体，" +
                    "返回状态码、响应头与响应体。可用于调用 Web API、抓取网页、对接第三方服务。"
            )
                .addParam("url", "string", true, "目标 URL")
                .addParam("method", "string", false, "HTTP 方法，默认 GET")
                .addParam("headers", "string", false, "请求头 JSON 对象，如 {\"Authorization\":\"Bearer x\"}")
                .addParam("body", "string", false, "请求体（原样发送，字符串）")
                .addResult("status_code", "int", "HTTP 响应状态码")
                .addResult("response_headers", "string", "响应头 JSON 对象")
                .addResult("response_body", "string", "响应体（>15万字符截断，完整内容见 response_body_gz）")
                .addResult("truncated", "boolean", "响应体是否被截断")
                .addFlag(Capability.FLAG_BACKGROUND)
                .addFlag(Capability.FLAG_NO_UI)
        )
    }

    override fun onCheckPermission(req: ACIRequest?, callerPkg: String?): Boolean {
        val ok = callerPkg == SELF_PKG || callerPkg == BROWSER_PKG
        Log.d(TAG, "onCheckPermission: caller=$callerPkg → ${if (ok) "放行" else "拒绝"}")
        return ok
    }

    override fun onCall(req: ACIRequest?): ACIResponse {
        if (req == null) return ACIResponse.error(ACIError.REQUEST_NULL, "null")
        return try {
            when (req.capability) {
                "http_request" -> handleHttpRequest(req.params)
                else -> ACIResponse.error(ACIError.CAPABILITY_NOT_FOUND, "unknown: ${req.capability}")
            }
        } catch (e: Throwable) {
            Log.e(TAG, "onCall 异常: ${e.message}")
            ACIResponse.error(ACIError.INTERNAL_ERROR, e.message ?: "err")
        }
    }

    private fun handleHttpRequest(params: Bundle?): ACIResponse {
        val url = params?.getString("url") ?: ""
        if (url.isEmpty()) return ACIResponse.error(ACIError.BAD_REQUEST, "no url")
        val method = (params?.getString("method") ?: "GET").uppercase()
        val headersStr = params?.getString("headers") ?: ""
        val body = params?.getString("body")  // 可能为 null

        val latch = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        var result: ACIResponse? = null
        executor.execute {
            try {
                result = doHttp(method, url, headersStr, body)
            } catch (e: Throwable) {
                result = ACIResponse.error(ACIError.INTERNAL_ERROR, "http_failed: ${e.message}")
            } finally {
                latch.countDown()
            }
        }
        val done = latch.await(HARD_TIMEOUT_S, TimeUnit.SECONDS)
        executor.shutdownNow()
        return if (done) {
            result ?: ACIResponse.error(ACIError.INTERNAL_ERROR, "no result")
        } else {
            ACIResponse.error(ACIError.INTERNAL_ERROR, "http timeout (>$HARD_TIMEOUT_S s, 控制器上限 15s)")
        }
    }

    private fun doHttp(method: String, url: String, headersStr: String, body: String?): ACIResponse {
        val client = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(14, TimeUnit.SECONDS)
            .writeTimeout(14, TimeUnit.SECONDS)
            .build()

        val reqBuilder = Request.Builder().url(url)
        if (headersStr.isNotEmpty()) {
            try {
                val h = JSONObject(headersStr)
                val it = h.keys()
                while (it.hasNext()) {
                    val k = it.next()
                    reqBuilder.addHeader(k, h.optString(k))
                }
            } catch (ignored: Throwable) {
                Log.w(TAG, "headers 解析失败，忽略: $headersStr")
            }
        }

        val mediaType = "application/octet-stream".toMediaTypeOrNull()
        val reqBody: RequestBody? = if (!body.isNullOrEmpty()) {
            body.toByteArray(Charsets.UTF_8).toRequestBody(mediaType)
        } else null

        val okReq: Request = try {
            val builtBuilder: Request.Builder = when (method) {
                "GET" -> reqBuilder.get()
                "HEAD" -> reqBuilder.head()
                "POST" -> reqBuilder.post(bodyOrEmpty(reqBody))
                "PUT" -> reqBuilder.put(bodyOrEmpty(reqBody))
                "PATCH" -> reqBuilder.patch(bodyOrEmpty(reqBody))
                "DELETE" -> reqBuilder.delete(reqBody)
                else -> reqBuilder.method(method, reqBody)
            }
            builtBuilder.build()
        } catch (e: Throwable) {
            return ACIResponse.error(ACIError.BAD_REQUEST, "bad method/body: ${e.message}")
        }

        val response = client.newCall(okReq).execute()
        try {
            val code = response.code
            val headers = JSONObject()
            for (i in 0 until response.headers.size) {
                headers.put(response.headers.name(i), response.headers.value(i))
            }

            // 大响应体保护：Content-Length > 2MB 不载入内存，直接标记截断
            val contentLength = response.header("Content-Length")?.toLongOrNull() ?: -1L
            if (contentLength > 2_000_000L) {
                return ACIResponse.success(Bundle())
                    .putResult("status_code", code)
                    .putResult("response_headers", headers.toString())
                    .putResult("response_body", "")
                    .putResult("truncated", true)
                    .putResult(
                        "truncated_reason",
                        "响应体超过 2MB，未载入内存（如需大文件请改用受控浏览器或文件下载能力）"
                    )
            }

            val raw = response.body?.string() ?: ""
            val truncated = raw.length > 150_000
            val safe = if (truncated) {
                raw.take(150_000) + "\n…[响应体已截断，完整内容见 response_body_gz，共 ${raw.length} 字符]"
            } else raw
            val r = ACIResponse.success(Bundle())
                .putResult("status_code", code)
                .putResult("response_headers", headers.toString())
                .putResult("response_body", safe)
                .putResult("truncated", truncated)
            if (truncated) {
                val gz = gzip(raw.toByteArray())
                if (gz.size <= 900_000) {
                    r.putResult("response_body_gz", gz)
                    r.putResult("response_body_len", raw.length)
                }
            }
            return r
        } finally {
            response.close()
        }
    }

    private fun bodyOrEmpty(b: RequestBody?): RequestBody =
        b ?: ByteArray(0).toRequestBody()

    private fun gzip(data: ByteArray): ByteArray {
        val bos = ByteArrayOutputStream()
        val gz = GZIPOutputStream(bos)
        gz.write(data)
        gz.finish()
        gz.close()
        return bos.toByteArray()
    }
}
