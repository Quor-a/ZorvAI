package com.ai.assistance.quro.core.bot.adapters

import android.content.Context
import com.ai.assistance.quro.core.bot.QuroBotPlatform
import com.ai.assistance.quro.core.bot.QuroOutboundMessage
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import com.ai.assistance.quro.util.QuroDiag

/**
 * QQ 机器人 V2 适配器（直连官方网关，零公网端点）。
 *
 * 接入形态（已核实）：
 *  - 换 token：POST https://bots.qq.com/app/getAppAccessToken（appId + clientSecret）→ access_token
 *  - 拿 WS 网关：GET {baseUrl}/gateway（Authorization: QQBot {token} + X-Union-Appid: {appId}）→ wss 地址
 *  - 收消息：WebSocket 长连，op=0 DISPATCH 的 C2C_MESSAGE_CREATE / GROUP_AT_MESSAGE_CREATE 事件
 *  - 回消息：POST {baseUrl}/v2/users/{openid}/messages（被动回复，5 分钟内）
 *  - 心跳：HELLO 给 heartbeat_interval，客户端周期发 op=1 HEARTBEAT；服务端主动心跳(op=1)立即回包
 *  - Intent：(1<<30)|(1<<26)|(1<<25)|(1<<12)（群@ / 群消息 / 私聊 / 加机器人），遵循QQ频道协议规范
 *  - 握手顺序：onOpen 等待 op=10 HELLO → 再发 op=2 IDENTIFY（shard=[0,1]），收到 op=0 READY 才算连上
 *
 * 仅用 OkHttp（含 WebSocket）+ org.json，不引入官方 SDK。
 */
class QuroQqBotAdapter(context: Context) : QuroDirectBotAdapter(context) {
    override val platform = QuroBotPlatform.QQ

    private val appId get() = pref("qq_appid")
    private val appSecret get() = pref("qq_secret")
    private val useSandbox get() = pref("qq_sandbox").equals("true", ignoreCase = true)

    private var accessToken: String = ""
    private var ws: WebSocket? = null
    private val alive = AtomicBoolean(false)
    /** WS 真实连接状态（onOpen→true, onClosed/onFailure→false），供 UI 读取。 */
    val wsConnected = AtomicBoolean(false)
    private val lastSeq = AtomicLong(0)
    /** 被动回复去重序号（QQ 官方要求 msg_seq 必填，自增即可）。 */
    private val msgSeq = AtomicInteger(1)
    /** 上次发送时间戳（用于发送节流，规避服务端限流）。 */
    private val lastSendMs = AtomicLong(0)
    private var heartbeatJob: kotlinx.coroutines.Job? = null

    override fun isConfigured(): Boolean = appId.isNotBlank() && appSecret.isNotBlank()

    /** 覆盖基类：不立即标 connected=true，等 WS onOpen 后再标真实状态。 */
    override suspend fun start() {
        if (!isConfigured()) {
            Log_w("QQ 未配置，跳过 start")
            connected = false
            return
        }
        if (connJob?.isActive == true) return
        stopped.set(false)
        wsConnected.set(false)
        connJob = scope.launch {
            try {
                runConnection()
            } catch (e: Exception) {
                Log_e("连接循环异常退出: ${e.message}")
            } finally {
                connected = false
                wsConnected.set(false)
            }
        }
        Log_i("QQ 适配器已启动（等待 WS 连接...）")
    }

    override suspend fun runConnection() {
        var retries = 0
        while (!stopped.get()) {
            alive.set(true)
            try {
                val tokenJson = httpPostJson(
                    "https://bots.qq.com/app/getAppAccessToken",
                    json = JSONObject().apply {
                        put("appId", appId)
                        put("clientSecret", appSecret)
                    }.toString(),
                ) ?: run {
                    lastError = "获取 QQ access_token 失败（appid/secret 错误或网络不通）"
                    alive.set(false); backoff(retries++); continue
                }
                accessToken = tokenJson.optString("access_token").also {
                    if (it.isBlank()) {
                        lastError = "获取 QQ access_token 返回为空（appid/secret 无效）"
                        alive.set(false); backoff(retries++); continue
                    }
                }

                val baseUrl = if (useSandbox) "https://sandbox.api.sgroup.qq.com" else "https://api.sgroup.qq.com"
                val gw = httpGetString(
                    "$baseUrl/gateway",
                    headers = mapOf(
                        "Authorization" to "QQBot $accessToken",
                        "X-Union-Appid" to appId,
                    ),
                ) ?: run {
                    lastError = "获取 QQ WS 网关失败（token 无效 / 缺少 X-Union-Appid / 网络不通）"
                    alive.set(false); backoff(retries++); continue
                }
                val wsUrl = JSONObject(gw).optString("url").also {
                    if (it.isBlank()) {
                        lastError = "QQ 网关返回为空: ${gw.take(300)}"
                        alive.set(false); backoff(retries++); continue
                    }
                }

                retries = 0
                val listener = QqWsListener()
                val req = Request.Builder().url(wsUrl).build()
                ws = client.newWebSocket(req, listener)
                // 阻塞直到连接断开（listener 在关闭时置 alive=false）
                while (alive.get() && !stopped.get()) delay(1000)
            } catch (e: Exception) {
                Log_e("runConnection 异常: ${e.message}")
            } finally {
                heartbeatJob?.cancel()
                ws?.cancel()
                ws = null
                alive.set(false)
            }
            if (!stopped.get()) backoff(retries++)
        }
    }

    override fun onDisconnect() {
        alive.set(false)
        heartbeatJob?.cancel()
        ws?.cancel()
        ws = null
    }

    override suspend fun deliver(reply: QuroOutboundMessage) {
        // ---- 先处理图片/文件附件 ----
        if (reply.imageBytes != null && reply.imageBytes.isNotEmpty()) {
            if (accessToken.isBlank()) {
                val tj = httpPostJson(
                    "https://bots.qq.com/app/getAppAccessToken",
                    json = JSONObject().apply { put("appId", appId); put("clientSecret", appSecret) }.toString(),
                )
                accessToken = tj?.optString("access_token").orEmpty()
            }
            val fileName = reply.imageFileName ?: "quro_image.png"
            val isImage = fileName.endsWith(".png", ignoreCase = true) ||
                fileName.endsWith(".jpg", ignoreCase = true) || fileName.endsWith(".jpeg", ignoreCase = true) ||
                fileName.endsWith(".gif", ignoreCase = true) || fileName.endsWith(".webp", ignoreCase = true)
            val fileType = if (isImage) "1" else "4"  // 1=图片, 4=文件
            val msgType = if (isImage) 7 else 10       // 7=图片消息, 10=文件消息

            val fileInfo = uploadMedia(reply.userId, reply.imageBytes, fileName, fileType)
            if (fileInfo != null) {
                val ok = sendMediaMessage(reply.userId, fileInfo, msgType, reply.msgId, reply.groupId)
                if (ok) {
                    Log_i("deliver 附件成功 → QQ ${if (reply.groupId != null) "群" else "用户"} ${reply.userId}")
                    // 附件发完后如果有文字，继续发文字
                    if (reply.text.isNotBlank()) {
                        // fall through to text send below
                    } else return
                } else {
                    Log_w("deliver 附件发送失败，降级为纯文本")
                }
            } else {
                Log_w("deliver 附件上传失败，降级为纯文本")
            }
        }

        // ---- 发送文本消息 ----
        if (reply.text.isBlank()) return

        // ---- 构建请求体（对齐 QQ 官方 OpenAPI：content 裸文本、msg_type 数字、msg_seq 必填）----
        val seq = msgSeq.getAndIncrement()
        val endpoint: String
        val body = if (reply.groupId != null) {
            endpoint = "https://api.sgroup.qq.com/v2/groups/${reply.groupId}/messages"
            JSONObject().apply {
                put("msg_type", 0)
                put("content", reply.text)
                put("msg_seq", seq)
                reply.msgId?.let { put("msg_id", it) }
                reply.eventId?.let { put("event_id", it) }
            }.toString()
        } else {
            endpoint = "https://api.sgroup.qq.com/v2/users/${reply.userId}/messages"
            JSONObject().apply {
                put("msg_type", 0)
                put("content", reply.text)
                put("msg_seq", seq)
                reply.msgId?.let { put("msg_id", it) }
                reply.eventId?.let { put("event_id", it) }
            }.toString()
        }

        // ---- 发送辅助函数（闭包复用）----
        fun doSend(token: String): Triple<Int, String, JSONObject?> {
            return httpPostWithStatus(
                endpoint,
                headers = mapOf(
                    "Authorization" to "QQBot $token",
                    "X-Union-Appid" to appId,   // 官方实现固定携带，标识机器人 appid
                ),
                json = body,
            )
        }

        fun refreshToken(): Boolean {
            Log_w("deliver 刷新 token...")
            val tj = httpPostJson(
                "https://bots.qq.com/app/getAppAccessToken",
                json = JSONObject().apply {
                    put("appId", appId)
                    put("clientSecret", appSecret)
                }.toString(),
            )
            accessToken = tj?.optString("access_token").orEmpty()
            val ok = accessToken.isNotBlank()
            if (ok) Log_i("deliver token 刷新成功（长度=${accessToken.length}）")
            else Log_e("deliver token 刷新失败：${tj?.toString()?.take(200)}")
            return ok
        }

        // ---- 发送节流：两次回包至少间隔 250ms，规避 QQ 服务端限流（429）----
        val gap = 250L - (System.currentTimeMillis() - lastSendMs.get())
        if (gap > 0) kotlinx.coroutines.delay(gap)
        lastSendMs.set(System.currentTimeMillis())

        // ---- 第一次尝试：用当前 token 直接发 ----
        if (accessToken.isBlank()) refreshToken()
        var (code, respBody, json) = doSend(accessToken)

        // ---- 失败时判断是否需要刷新重试（401=过期/无效, 403=无权限, 429=限流等）----
        if (json == null && code in listOf(401, 403, 0)) {
            Log_w("deliver 首次失败 HTTP $code，尝试刷新 token 后重试...（响应: ${respBody.take(300)}）")
            if (refreshToken()) {
                val (code2, body2, json2) = doSend(accessToken)
                code = code2; respBody = body2; json = json2
            }
        }

        // ---- 429 限流：退避 2s 后重试一次 ----
        if (json == null && code == 429) {
            Log_w("deliver 收到 429 限流，退避 2s 后重试一次...（响应: ${respBody.take(200)}）")
            kotlinx.coroutines.delay(2000)
            val (code2, body2, json2) = doSend(accessToken)
            code = code2; respBody = body2; json = json2
        }

        // ---- 结果判定 ----
        if (json != null) {
            lastError = null
            Log_i("✅ deliver 成功 → QQ ${if (reply.groupId != null) "群 ${reply.groupId}" else "用户 ${reply.userId}"}")
        } else {
            lastError = "回复发送失败（HTTP=$code：${respBody.take(200)}）"
            Log_e("❌ deliver 最终失败 → $endpoint | HTTP=$code | 响应=${respBody.take(500)} | token长度=${accessToken.length}")
        }
    }

    private fun startHeartbeat(intervalMs: Long) {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (alive.get() && isActive) {
                delay(intervalMs)
                try { ws?.send(JSONObject().put("op", 1).put("d", if (lastSeq.get() > 0) lastSeq.get() else JSONObject.NULL).toString()) }
                catch (e: Exception) { Log_e("heartbeat 失败: ${e.message}") }
            }
        }
    }

    private inner class QqWsListener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            Log_i("WS 已连接（真实握手成功）")
            lastError = null
            wsConnected.set(true)
            connected = true
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            runCatching {
                val msg = JSONObject(text)
                when (val op = msg.optInt("op", -1)) {
                    10 -> { // HELLO
                        val interval = msg.optJSONObject("d")?.optLong("heartbeat_interval", 30000) ?: 30000
                        // IDENTIFY
                        val identify = JSONObject().apply {
                            put("op", 2)
                            put("d", JSONObject().apply {
                                put("token", "QQBot $accessToken")
                                // 遵循QQ频道协议规范：私聊(1<<25) | 群@(1<<30) | 群消息(1<<26) | 加机器人(1<<12)
                                put("intents", (1 shl 30) or (1 shl 26) or (1 shl 25) or (1 shl 12))
                                put("shard", JSONArray().put(0).put(1))
                            })
                        }.toString()
                        webSocket.send(identify)
                        startHeartbeat(interval)
                        Log_i("IDENTIFY 已发，心跳间隔 ${interval}ms")
                    }
                    0 -> { // DISPATCH
                        val seq = msg.optLong("s", 0)
                        if (seq > 0) lastSeq.set(seq)
                        val t = msg.optString("t")
                        val d = msg.optJSONObject("d") ?: return@runCatching
                        if (t == "READY") {
                            val sess = d.optString("session_id").ifBlank { null }
                            val botName = d.optJSONObject("user")?.optString("username").orEmpty()
                            connected = true
                            Log_i("✅ 握手完成 READY session=${sess?.take(12)}... bot=$botName")
                            return@runCatching
                        }
                        if (t == "C2C_MESSAGE_CREATE") {
                            val openid = d.optJSONObject("author")?.optString("user_openid").orEmpty()
                            var content = d.optString("content", "").trim()
                            // C2C 内容可能带前导 "/" 指令 token，去掉
                            content = content.removePrefix("/").trim()
                            val msgId = d.optString("msg_id").ifBlank { null }
                            val eventId = d.optString("event_id").ifBlank { null }
                            if (openid.isNotBlank() && content.isNotBlank()) {
                                onInbound(openid, openid, content, msgId, eventId)
                            }
                        } else if (t == "GROUP_AT_MESSAGE_CREATE") {
                            // 群内 @机器人：content 形如「@ bot名称 <真正的消息>」，去掉 @ 提及前缀。
                            val groupOpenid = d.optString("group_openid").ifBlank { null }
                            val openid = d.optJSONObject("author")?.optString("user_openid").orEmpty()
                            var content = d.optString("content", "").trim()
                            // 去掉开头的 @ 提及（直到第一个空格）
                            content = content.replace(Regex("^@\\S+\\s*"), "").trim()
                            val msgId = d.optString("msg_id").ifBlank { null }
                            val eventId = d.optString("event_id").ifBlank { null }
                            if (groupOpenid != null && content.isNotBlank()) {
                                // userId 仍用发消息人 openid 便于会话绑定；groupId 标记群回复端点
                                onInbound(openid, openid, content, msgId, eventId, groupId = groupOpenid)
                            }
                        }
                    }
                    1 -> { // 服务端主动心跳：立即原样回 HEARTBEAT（op=1, d=seq），遵循协议规范
                        try {
                            val d = if (lastSeq.get() > 0) lastSeq.get() else JSONObject.NULL
                            webSocket.send(JSONObject().put("op", 1).put("d", d).toString())
                        } catch (e: Exception) { Log_e("心跳回包失败: ${e.message}") }
                    }
                    11 -> { /* HEARTBEAT ACK */ }
                    7, 12 -> { // INVALID SESSION / RECONNECT
                        Log_w("收到重连指令 op=$op，关闭重连")
                        alive.set(false)
                        webSocket.cancel()
                    }
                }
            }.onFailure { e -> Log_e("onMessage 解析失败: ${e.message}") }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            Log_w("WS closing $code $reason")
            wsConnected.set(false)
            alive.set(false)
            webSocket.cancel()
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Log_w("WS closed $code $reason")
            wsConnected.set(false)
            connected = false
            alive.set(false)
            if (code != 1000 && code != 1001) {
                lastError = "WS 已断开（code=$code ${reason.ifBlank { "无原因" }}）"
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            val httpInfo = response?.let { "HTTP ${it.code}" } ?: ""
            Log_e("WS failure: ${t.message} $httpInfo")
            lastError = "WS 连接失败：${t.message ?: "未知"} $httpInfo"
            wsConnected.set(false)
            connected = false
            alive.set(false)
        }
    }

    /**
     * 上传媒体文件到 QQ（分片上传流程）。
     * QQ Bot API v2 不支持 multipart 上传，需要分片上传：
     * 1. POST /v2/users/{openid}/upload_prepare → 获取 upload_id + 预签名 URL
     * 2. PUT 预签名 URL → 上传分片
     * 3. POST /v2/users/{openid}/upload_part_finish → 确认分片
     * 4. POST /v2/users/{openid}/files { upload_id } → 合并获取 file_info
     */
    private fun uploadMedia(userId: String, bytes: ByteArray, fileName: String, fileType: String = "2"): String? {
        // 确保 token 有效
        if (accessToken.isBlank()) {
            val tj = httpPostJson(
                "https://bots.qq.com/app/getAppAccessToken",
                json = JSONObject().apply { put("appId", appId); put("clientSecret", appSecret) }.toString(),
            )
            accessToken = tj?.optString("access_token").orEmpty()
            if (accessToken.isBlank()) {
                Log_e("uploadMedia 失败: token 刷新为空")
                return null
            }
        }

        val fileSize = bytes.size.toLong()
        val file_type = fileType.toIntOrNull() ?: 2  // 1=图片, 2=视频, 3=语音, 4=文件

        // 计算 MD5、SHA1、md5_10m
        val md5 = java.security.MessageDigest.getInstance("MD5").digest(bytes)
            .joinToString("") { "%02x".format(it) }
        val sha1 = java.security.MessageDigest.getInstance("SHA-1").digest(bytes)
            .joinToString("") { "%02x".format(it) }
        val md5_10m = if (bytes.size > 10002432) {
            java.security.MessageDigest.getInstance("MD5").digest(bytes.copyOf(10002432))
                .joinToString("") { "%02x".format(it) }
        } else md5

        Log_i("uploadMedia 开始分片上传: file=$fileName size=$fileSize type=$file_type md5=${md5.take(16)}...")

        // Step 1: 预上传
        val prepareBody = JSONObject().apply {
            put("file_type", file_type)
            put("file_size", fileSize.toString())
            put("file_name", fileName)
            put("md5", md5)
            put("sha1", sha1)
            put("md5_10m", md5_10m)
        }.toString()

        val prepareResp = httpPostJson(
            "https://api.sgroup.qq.com/v2/users/$userId/upload_prepare",
            headers = mapOf("Authorization" to "QQBot $accessToken", "X-Union-Appid" to appId),
            json = prepareBody,
        ) ?: run {
            Log_e("uploadMedia 预上传失败: 响应为空")
            return null
        }

        val uploadId = prepareResp.optString("upload_id").ifBlank {
            Log_e("uploadMedia 预上传失败: 无 upload_id, resp=${prepareResp.toString().take(300)}")
            return null
        }
        val blockSize = prepareResp.optString("block_size", "5242880").toLongOrNull() ?: 5242880L
        val parts = prepareResp.optJSONArray("parts") ?: run {
            Log_e("uploadMedia 预上传失败: 无 parts 数组")
            return null
        }

        Log_i("uploadMedia 预上传成功: upload_id=${uploadId.take(16)}... block_size=$blockSize parts=${parts.length()}")

        // Step 2: 逐片 PUT 到预签名 URL
        for (i in 0 until parts.length()) {
            val part = parts.optJSONObject(i) ?: continue
            val presignedUrl = part.optString("presigned_url")
            val partBlockSize = part.optString("block_size", blockSize.toString()).toLongOrNull() ?: blockSize
            val offset = (i * blockSize).toInt()
            val end = minOf(offset + blockSize.toInt(), bytes.size)
            val chunk = bytes.copyOfRange(offset, end)

            Log_i("uploadMedia 上传分片 $i/${parts.length()} size=${chunk.size}")

            val putReq = Request.Builder()
                .url(presignedUrl)
                .put(chunk.toRequestBody("application/octet-stream".toMediaType()))
                .build()

            val putResp = client.newCall(putReq).execute()
            if (!putResp.isSuccessful) {
                Log_e("uploadMedia 分片 $i 上传失败 HTTP ${putResp.code}")
                return null
            }
            putResp.close()
        }

        // Step 3: 确认分片（如果需要）
        // 注意：QQ Bot API 文档提到 upload_part_finish，但预上传响应中没有提供这个端点
        // 根据文档，分片 PUT 完成后直接调用合并接口

        // Step 4: 合并获取 file_info
        val mergeBody = JSONObject().apply {
            put("upload_id", uploadId)
        }.toString()

        val mergeResp = httpPostJson(
            "https://api.sgroup.qq.com/v2/users/$userId/files",
            headers = mapOf("Authorization" to "QQBot $accessToken", "X-Union-Appid" to appId),
            json = mergeBody,
        ) ?: run {
            Log_e("uploadMedia 合并失败: 响应为空")
            return null
        }

        val fileInfo = mergeResp.optString("file_info").ifBlank {
            Log_e("uploadMedia 合并失败: 无 file_info, resp=${mergeResp.toString().take(300)}")
            return null
        }

        Log_i("uploadMedia 分片上传成功: file_info 长度=${fileInfo.length}")
        return fileInfo
    }

    /**
     * 发送图片消息（APK 实现：msg_type=7 + file_info）。
     */
    private fun sendMediaMessage(userId: String, fileInfo: String, msgType: Int, msgId: String? = null, groupId: String? = null): Boolean {
        val seq = msgSeq.getAndIncrement()
        val endpoint = if (groupId != null) {
            "https://api.sgroup.qq.com/v2/groups/$groupId/messages"
        } else {
            "https://api.sgroup.qq.com/v2/users/$userId/messages"
        }
        val body = JSONObject().apply {
            put("msg_type", msgType)  // 7=图片, 8=视频, 9=语音, 10=文件
            put("media", JSONObject().apply { put("file_info", fileInfo) })
            put("msg_seq", seq)
            msgId?.let { put("msg_id", it) }
        }.toString()

        val json = httpPostJson(
            endpoint,
            headers = mapOf(
                "Authorization" to "QQBot $accessToken",
                "X-Union-Appid" to appId,
            ),
            json = body,
        )
        return json != null
    }

    private fun Log_i(s: String) { android.util.Log.i(TAG, "[QQ] $s"); QuroDiag.log("QQ", s) }
    private fun Log_w(s: String) { android.util.Log.w(TAG, "[QQ] $s"); QuroDiag.log("QQ", s) }
    private fun Log_e(s: String) { android.util.Log.e(TAG, "[QQ] $s"); QuroDiag.log("QQ", s) }
}
