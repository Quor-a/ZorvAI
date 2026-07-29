package com.ai.assistance.quro.core.bot.adapters

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.ai.assistance.quro.core.bot.QuroBotAdapter
import com.ai.assistance.quro.core.bot.QuroBotManager
import com.ai.assistance.quro.core.bot.QuroBotPlatform
import com.ai.assistance.quro.core.bot.QuroInboundMessage
import com.ai.assistance.quro.core.bot.QuroOutboundMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 直连型平台适配器基类（QQBot V2 / 飞书 / 微信 iLink 共用）。
 *
 * 与旧版「后端 Relay」脚手架（QuroRelayBotAdapter）的本质区别：
 * 旧版假设必须有一个公网后端中转，而元宝核实——**三家官方都支持「手机端零公网端点」的
 * 收消息方式**：QQBot / 飞书走官方 WebSocket 长连，微信 iLink 走 HTTP 长轮询（35s）。
 * 因此本 App 直接持密钥【出站】连官方网关即可，无需任何自备服务器 / Webhook。
 *
 * 本基类提供：凭据读取（SharedPreferences）、OkHttp 客户端（含 70s readTimeout 供长轮询）、
 * 协程生命周期（start 拉起连接循环，stop 取消）、HTTP/JSON 工具、以及统一的入站入口。
 *
 * 各子类只需实现：专属凭据校验、连接主循环（WS 或长轮询）、把回复投递回平台。
 */
abstract class QuroDirectBotAdapter(
    protected val appContext: Context,
) : QuroBotAdapter {

    protected val prefs: SharedPreferences =
        appContext.getSharedPreferences(QuroBotManager.PREFS, Context.MODE_PRIVATE)

    /** readTimeout 给长轮询留足余量（iLink 35s + 余量）。 */
    protected val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(70, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    protected val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    protected var connJob: Job? = null

    /** 用户/管理器主动停止标志：置 true 后连接循环不再重连。 */
    protected val stopped = AtomicBoolean(false)

    var connected: Boolean = false
        protected set

    /** 最近一次连接/投递失败的可读原因，供 UI 直接展示（无需翻 logcat）。 */
    override var lastError: String? = null

    /** 实现接口公共访问器：UI 可读真实连接态。 */
    override val isConnected: Boolean get() = connected

    /** 子类偏好读取的凭据键（按 platform.name 小写前缀）。 */
    protected fun pref(key: String): String = prefs.getString(key, "") ?: ""

    // ---------------- 公共 HTTP 工具 ----------------

    protected fun httpPostJson(
        url: String,
        headers: Map<String, String> = emptyMap(),
        json: String,
    ): JSONObject? = try {
        val req = Request.Builder().url(url)
            .addHeader("Content-Type", "application/json")
            .also { headers.forEach { (k, v) -> it.addHeader(k, v) } }
            .post(json.toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(req).execute().use { resp ->
            val b = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                Log.w(TAG, "$platform POST $url -> HTTP ${resp.code}: ${b.take(500)}")
                null
            } else JSONObject(b)
        }
    } catch (e: Exception) {
        Log.e(TAG, "$platform POST $url 失败: ${e.message}")
        null
    }

    /**
     * 带完整状态信息的 POST（供 deliver 等需区分 401/403/429 等场景）。
     * 返回 Triple<statusCode, responseBody, json?>，永远不抛异常。
     */
    protected fun httpPostWithStatus(
        url: String,
        headers: Map<String, String> = emptyMap(),
        json: String,
    ): Triple<Int, String, JSONObject?> = try {
        val req = Request.Builder().url(url)
            .addHeader("Content-Type", "application/json")
            .also { h -> headers.forEach { (k, v) -> h.addHeader(k, v) } }
            .post(json.toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(req).execute().use { resp ->
            val b = resp.body?.string().orEmpty()
            val parsed = if (resp.isSuccessful && b.isNotBlank()) try { JSONObject(b) } catch (_: Exception) { null } else null
            Triple(resp.code, b, parsed)
        }
    } catch (e: Exception) {
        Log.e(TAG, "$platform POST $url 异常: ${e.message}")
        Triple(0, e.message ?: "exception", null)
    }

    protected fun httpGetWithStatus(
        url: String,
        headers: Map<String, String> = emptyMap(),
    ): Triple<Int, String, String?> = try {
        val req = Request.Builder().url(url)
            .also { h -> headers.forEach { (k, v) -> h.addHeader(k, v) } }
            .get().build()
        client.newCall(req).execute().use { resp ->
            val b = resp.body?.string().orEmpty()
            Triple(resp.code, b, if (resp.isSuccessful) b else null)
        }
    } catch (e: Exception) {
        Log.e(TAG, "$platform GET $url 异常: ${e.javaClass.simpleName}: ${e.message}")
        Triple(0, e.javaClass.simpleName + ": " + (e.message ?: "exception"), null)
    }

    protected fun httpGetString(
        url: String,
        headers: Map<String, String> = emptyMap(),
    ): String? = try {
        val req = Request.Builder().url(url)
            .also { headers.forEach { (k, v) -> it.addHeader(k, v) } }
            .get().build()
        client.newCall(req).execute().use { resp ->
            val b = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                Log.w(TAG, "$platform GET $url -> HTTP ${resp.code}: ${b.take(200)}")
                null
            } else b
        }
    } catch (e: Exception) {
        Log.e(TAG, "$platform GET $url 失败: ${e.message}")
        null
    }

    /** GET 请求并解析为 JSONObject（404/非JSON 返回 null）。 */
    protected fun httpGetJson(
        url: String,
        headers: Map<String, String> = emptyMap(),
    ): JSONObject? = try {
        val req = Request.Builder().url(url)
            .also { headers.forEach { (k, v) -> it.addHeader(k, v) } }
            .get().build()
        client.newCall(req).execute().use { resp ->
            val b = resp.body?.string().orEmpty()
            when {
                !resp.isSuccessful -> { Log.w(TAG, "$platform GET $url -> HTTP ${resp.code}"); null }
                b.isBlank() -> null
                else -> try { JSONObject(b) } catch (_: Exception) { null }
            }
        }
    } catch (e: Exception) {
        Log.e(TAG, "$platform GET $url 失败: ${e.message}")
        null
    }

    /**
     * 带文件的 multipart 上传（飞书图片消息等场景）。
     * 成功返回解析好的 JSONObject，HTTP 失败/解析异常返回 null（永不抛出）。
     */
    protected fun httpUploadMultipart(
        url: String,
        headers: Map<String, String> = emptyMap(),
        formFields: Map<String, String> = emptyMap(),
        fileField: String,
        fileName: String,
        bytes: ByteArray,
        mediaType: String = "application/octet-stream",
    ): JSONObject? = try {
        val mp = MultipartBody.Builder().setType(MultipartBody.FORM)
        formFields.forEach { (k, v) -> mp.addFormDataPart(k, v) }
        mp.addFormDataPart(fileField, fileName, bytes.toRequestBody(mediaType.toMediaType()))
        val req = Request.Builder().url(url)
            .also { h -> headers.forEach { (k, v) -> h.addHeader(k, v) } }
            .post(mp.build())
            .build()
        client.newCall(req).execute().use { resp ->
            val b = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                Log.w(TAG, "$platform UPLOAD $url -> HTTP ${resp.code}: ${b.take(500)}")
                null
            } else runCatching { JSONObject(b) }.getOrNull()
        }
    } catch (e: Exception) {
        Log.e(TAG, "$platform UPLOAD $url 失败: ${e.message}")
        null
    }

    // ---------------- 生命周期 ----------------

    override suspend fun start() {
        if (!isConfigured()) {
            Log.w(TAG, "$platform 未配置，跳过 start")
            connected = false
            return
        }
        if (connJob?.isActive == true) return
        stopped.set(false)
        connJob = scope.launch {
            try {
                runConnection()
            } catch (e: Exception) {
                Log.e(TAG, "$platform 连接循环异常退出: ${e.message}")
            } finally {
                connected = false
            }
        }
        connected = true
        Log.i(TAG, "$platform 直连适配器已启动")
    }

    override suspend fun stop() {
        stopped.set(true)
        connJob?.cancel()
        connJob = null
        connected = false
        onDisconnect()
        Log.i(TAG, "$platform 已停止")
    }

    /** 连接主循环（WS 长连 / HTTP 长轮询），由子类实现。 */
    protected abstract suspend fun runConnection()

    /** stop 时清理资源（关 WS 等），由子类按需实现。 */
    protected open fun onDisconnect() {}

    /** 子类在收到平台消息时调用：统一经 QuroBotManager 驱动回复引擎。 */
    protected fun onInbound(
        userId: String,
        userName: String,
        text: String,
        msgId: String? = null,
        eventId: String? = null,
        groupId: String? = null,
    ) {
        QuroBotManager.instance(appContext).handleInbound(
            QuroInboundMessage(
                platform, userId, userName, text,
                msgId = msgId, eventId = eventId, groupId = groupId,
            ),
        )
    }

    companion object {
        protected const val TAG = "QuroDirectBot"
    }
}

/** 简单退避：网络抖动后短暂停顿再重连。 */
internal suspend fun backoff(retries: Int) {
    val sec = (1000L * (1 shl retries.coerceAtMost(5))).coerceAtMost(30_000)
    delay(sec)
}
