package com.ai.assistance.quro.lanui

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * 前端 ↔ 后端 HTTP 客户端（OkHttp + 协程）。
 * baseUrl 可切换：同设备默认 http://127.0.0.1:PORT；跨设备填局域网内其他设备的后端地址。
 */
class LanUiClient(private val baseUrl: String) {

    private val http = sharedClient
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    /** 拉取当前 UI 快照（后端JSON → 前端模型）。 */
    suspend fun fetchUi(): LanScreen = withContext(Dispatchers.IO) {
        val req = Request.Builder().url("$baseUrl/lan/ui").get().build()
        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            LanUiModel.parse(JSONObject(text))
        }
    }

    /** 回传 action（可选附带 payload，如输入框值）。 */
    suspend fun postAction(action: String, payload: Map<String, String> = emptyMap()): Boolean =
        withContext(Dispatchers.IO) {
            val bodyJson = JSONObject().apply {
                put("action", action)
                payload.forEach { (k, v) -> put(k, v) }
            }
            val req = Request.Builder()
                .url("$baseUrl/lan/action")
                .post(bodyJson.toString().toRequestBody(jsonMedia))
                .build()
            runCatching { http.newCall(req).execute().use { it.isSuccessful } }.getOrDefault(false)
        }

    companion object {
        /** 复用同一 OkHttpClient，避免每次轮询新建连接池。 */
        private val sharedClient = OkHttpClient.Builder().build()

        fun defaultLocalUrl(port: Int) = "http://127.0.0.1:$port"
    }
}
