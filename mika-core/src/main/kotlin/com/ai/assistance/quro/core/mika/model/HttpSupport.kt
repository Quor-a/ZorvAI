package com.ai.assistance.quro.core.mika.model

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal data class RawResponse(val status: Int, val retryAfter: String?, val body: String)

/** 各适配器共用的 HTTP 执行(enqueue + 协程取消)与 Retry-After 解析。 */
internal object HttpSupport {
    suspend fun execute(http: OkHttpClient, req: Request): RawResponse = suspendCancellableCoroutine { cont ->
        val call = http.newCall(req)
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resumeWithException(ModelError.Network(e))
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    try {
                        val raw = RawResponse(it.code, it.header("retry-after"), it.body?.string().orEmpty())
                        if (cont.isActive) cont.resume(raw)
                    } catch (e: IOException) {
                        if (cont.isActive) cont.resumeWithException(ModelError.Network(e))
                    }
                }
            }
        })
    }

    /**
     * SSE 按事件交付；取消协程会同时取消 HTTP 请求。流读到头时由调用者按各自协议判断有没有正常收尾，
     * 没收尾算 [ModelError.StreamCut]。一个事件都没有、又不是事件流格式的成功响应(比如地址指到了网页)算 [ModelError.BadResponse]。
     */
    suspend fun stream(http: OkHttpClient, req: Request, onEvent: (String) -> Unit): RawResponse = suspendCancellableCoroutine { cont ->
        val call = http.newCall(req)
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resumeWithException(ModelError.Network(e))
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    try {
                        if (it.code !in 200..299) {
                            val error = RawResponse(it.code, it.header("retry-after"), it.body?.string().orEmpty())
                            if (cont.isActive) cont.resume(error)
                            return
                        }
                        if (it.header("Content-Type")?.contains("application/json", ignoreCase = true) == true) {
                            if (cont.isActive) cont.resume(RawResponse(it.code, it.header("retry-after"), it.body?.string().orEmpty()))
                            return
                        }
                        val source = it.body?.source() ?: throw ModelError.BadResponse("流式响应没有内容")
                        val data = mutableListOf<String>()
                        var events = 0
                        fun dispatch() {
                            if (data.isNotEmpty()) {
                                events++
                                onEvent(data.joinToString("\n"))
                                data.clear()
                            }
                        }
                        while (cont.isActive) {
                            val line = source.readUtf8Line() ?: break
                            when {
                                line.isEmpty() -> dispatch()
                                line.startsWith("data:") -> data += line.removePrefix("data:").removePrefix(" ")
                            }
                        }
                        if (cont.isActive) {
                            dispatch()
                            val eventStream = it.header("Content-Type")?.contains("event-stream", ignoreCase = true) == true
                            if (events == 0 && !eventStream) throw ModelError.BadResponse("返回的不是流式内容(Content-Type: ${it.header("Content-Type").orEmpty()})")
                            cont.resume(RawResponse(it.code, it.header("retry-after"), ""))
                        }
                    } catch (e: Exception) {
                        if (cont.isActive) cont.resumeWithException(if (e is IOException) ModelError.Network(e) else e)
                    }
                }
            }
        })
    }

    fun retryAfterMs(header: String?): Long? = header?.trim()?.toDoubleOrNull()?.let { (it * 1000).toLong() }
}
