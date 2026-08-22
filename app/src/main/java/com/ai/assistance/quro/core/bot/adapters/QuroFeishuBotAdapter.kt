package com.ai.assistance.quro.core.bot.adapters

import android.content.Context
import com.ai.assistance.quro.core.bot.QuroBotPlatform
import com.ai.assistance.quro.core.bot.QuroOutboundMessage
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import com.ai.assistance.quro.util.QuroDiag
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 飞书（Lark）自建应用机器人适配器（直连官方 WS 网关，零公网端点）。
 *
 * 接入形态（已核实）：
 *  - 换 token：POST https://open.feishu.cn/open-apis/auth/v3/tenant_access_token/internal（app_id + app_secret）→ tenant_access_token
 *  - 收消息：先 POST https://open.feishu.cn/callback/ws/endpoint（AppID+AppSecret）拿到 data.URL，
 *    再连该签名 WS 地址（带 session）；后台「事件订阅」选「使用长连接接收事件」后无需填回调 URL。
 *    ⚠️ 不能把 token 拼到 wss://open.feishu.cn/open-apis/ws/v1/?access_token=... —— 飞书不认，握手必失败。
 *  - 心跳：服务端发 {"type":"ping","sn":N} → 客户端回 {"type":"pong","sn":N}（控制帧才有顶层 type）
 *  - 事件：schema 2.0 信封 {"schema":"2.0","header":{"event_type":"im.message.receive_v1",...},"event":{...}}（无顶层 type！）
 *  - 回消息：POST https://open.feishu.cn/open-apis/im/v1/messages/{message_id}/reply（Bearer token，官方回声机器人做法）
 */
class QuroFeishuBotAdapter(context: Context) : QuroDirectBotAdapter(context) {
    override val platform = QuroBotPlatform.FEISHU

    private val appId get() = pref("feishu_appid")
    private val appSecret get() = pref("feishu_secret")

    private var tenantToken: String = ""
    private var ws: WebSocket? = null
    private val alive = AtomicBoolean(false)
    /** WS 真实连接状态（onOpen→true, onClosed/onFailure→false），供 UI 读取。 */
    val wsConnected = AtomicBoolean(false)

    /** 已处理消息去重（飞书重推/自身回环可能重复投递同一 message_id，避免重复回复）。线程安全。 */
    private val seenMsgIds = ConcurrentHashMap.newKeySet<String>()

    override fun isConfigured(): Boolean = appId.isNotBlank() && appSecret.isNotBlank()

    /** 覆盖基类：不立即标 connected=true，等 WS onOpen 后再标真实状态。 */
    override suspend fun start() {
        if (!isConfigured()) {
            Log_w("飞书 未配置，跳过 start")
            connected = false
            return
        }
        if (connJob?.isActive == true) return
        stopped.set(false)
        wsConnected.set(false)
        connJob = scope.launch {
            try { runConnection() } catch (e: Exception) { Log_e("连接循环异常退出: ${e.message}") }
            finally { connected = false; wsConnected.set(false) }
        }
        Log_i("飞书 适配器已启动（等待 WS 连接...）")
    }

    override suspend fun runConnection() {
        var retries = 0
        while (!stopped.get()) {
            alive.set(true)
            try {
                // ===== Step 0: 网络连通性预检（排除 DNS/防火墙/运营商拦截）=====
                val reachResult = httpGetWithStatus("https://open.feishu.cn/open-apis/ping")
                Log_i("网络预检: open.feishu.cn -> HTTP ${reachResult.first} (${reachResult.second.take(100)})")

                // ===== Step 1: 获取 tenant_access_token（带详细诊断）=====
                Log_i("正在获取 token: app_id=${appId.take(8)}... secret=${appSecret.take(4)}...")
                val tkn = httpPostWithStatus(
                    "https://open.feishu.cn/open-apis/auth/v3/tenant_access_token/internal",
                    json = JSONObject().apply {
                        put("app_id", appId)
                        put("app_secret", appSecret)
                    }.toString(),
                )
                if (tkn.third == null) {
                    val msg = "Token 获取失败: HTTP ${tkn.first} body=${tkn.second.take(200)}"
                    Log_e(msg)
                    lastError = msg
                    alive.set(false); backoff(retries++); continue
                }
                val resp = tkn.third!!
                tenantToken = resp.optString("tenant_access_token").also {
                    val expiry = resp.optInt("expire", 0)
                    Log_i("Token 获取成功: len=${it.length} expiry=${expiry}s response_code=${tkn.first}")
                    if (it.isBlank()) {
                        val detail = "Token 为空! full_resp=${resp.toString().take(300)} (app_id/secret 可能无效)"
                        Log_e(detail)
                        lastError = detail
                        alive.set(false); backoff(retries++); return@also
                    }
                }

                // ===== Step 2: 获取签名 WS 长连接端点（飞书官方协议：先取 endpoint，再连返回的 URL）=====
                // 旧代码错误地把 token 拼到 wss://.../ws/v1/?access_token=... 直接连，飞书不认、握手必失败。
                // 正确做法：POST /callback/ws/endpoint（AppID+AppSecret）→ 返回 data.URL（带 session 的临时签名地址），连它。
                val ep = httpPostJson(
                    "https://open.feishu.cn/callback/ws/endpoint",
                    headers = mapOf("Locale" to "zh"),
                    json = JSONObject().apply {
                        put("AppID", appId)
                        put("AppSecret", appSecret)
                    }.toString(),
                )
                val wsUrl = ep?.optJSONObject("data")?.optString("URL").orEmpty()
                val epCode = ep?.optInt("code", -1) ?: -1
                if (wsUrl.isBlank()) {
                    val detail = "WS 端点获取失败: code=$epCode body=${(ep?.toString() ?: "null").take(300)}（应用未启用长连接/未发布，或 AppID/Secret 无效）"
                    Log_e(detail)
                    lastError = detail
                    alive.set(false); backoff(retries++); continue
                }
                Log_i("WS 端点已获取: ${wsUrl.take(64)}...（code=$epCode）")
                val req = Request.Builder().url(wsUrl).build()
                ws = client.newWebSocket(req, FeishuWsListener())

                // 等待握手（最多 8 秒）
                var waited = 0
                while (!wsConnected.get() && !stopped.get() && waited < 8) {
                    delay(1000)
                    waited++
                }
                if (!wsConnected.get()) {
                    Log_w("WS 8s 内未握手成功，清理并重连...")
                    ws?.cancel(); ws = null
                    alive.set(false); backoff(retries++); continue
                }

                // WS 已连接，保持存活循环
                while (alive.get() && !stopped.get()) delay(1000)
            } catch (e: Exception) {
                Log_e("runConnection 异常: ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                ws?.cancel(); ws = null; alive.set(false)
            }
            if (!stopped.get()) backoff(retries++)
        }
    }

    override fun onDisconnect() {
        alive.set(false)
        ws?.cancel()
        ws = null
    }

    override suspend fun deliver(reply: QuroOutboundMessage) {
        if (!ensureToken(reply.userId)) return

        // 优先发送图片（飞书支持）；图片成功后再把文字作为附言补发
        if (reply.imageBytes != null && reply.imageBytes.isNotEmpty()) {
            val ok = sendImage(reply.userId, reply.imageBytes, reply.imageFileName ?: "quro_image.png", reply.msgId)
            if (ok) {
                if (reply.text.isNotBlank()) sendTextMessage(reply.userId, reply.text, reply.msgId)
                lastError = null
                Log_i("deliver 已发往飞书会话 ${reply.userId}（图片）")
                return
            }
            Log_w("图片发送失败，降级为纯文本 chat=${reply.userId}")
        }

        if (reply.text.isNotBlank()) {
            if (sendTextMessage(reply.userId, reply.text, reply.msgId)) {
                lastError = null
                Log_i("deliver 已发往飞书会话 ${reply.userId}")
            }
        } else {
            Log_w("deliver 内容为空（无文本且无图片），跳过 chat=${reply.userId}")
        }
    }

    /** 确保 tenantToken 有效；为空则刷新一次。返回是否可用。 */
    private fun ensureToken(chatId: String = ""): Boolean {
        if (tenantToken.isNotBlank()) return true
        Log_w("deliver 时 tenantToken 为空，尝试刷新...")
        val tkn = httpPostJson(
            "https://open.feishu.cn/open-apis/auth/v3/tenant_access_token/internal",
            json = JSONObject().apply { put("app_id", appId); put("app_secret", appSecret) }.toString(),
        )
        tenantToken = tkn?.optString("tenant_access_token").orEmpty()
        if (tenantToken.isBlank()) {
            lastError = "回复发送失败：飞书 token 刷新为空（app_id/secret 无效）"
            Log_e("deliver 失败：token 刷新也为空，无法发送回复" + if (chatId.isNotBlank()) " chat=$chatId" else "")
            return false
        }
        return true
    }

    /** 发送纯文本消息。优先用官方 reply 端点（按 message_id 回投，入站透传），msgId 缺失时回退 chat_id 直发。成功返回 true。 */
    private fun sendTextMessage(chatId: String, text: String, msgId: String? = null): Boolean {
        val content = JSONObject().put("text", text).toString()
        val body = JSONObject().apply {
            put("msg_type", "text")
            put("content", content)
        }.toString()
        val json = if (!msgId.isNullOrBlank()) {
            Log_i("sendTextMessage 走 reply 端点 msgId=$msgId")
            httpPostJson(
                "https://open.feishu.cn/open-apis/im/v1/messages/${msgId}/reply",
                headers = mapOf("Authorization" to "Bearer $tenantToken"),
                json = body,
            )
        } else {
            Log_w("sendTextMessage 无 msgId，回退 chat_id 直发 chat=$chatId")
            val direct = JSONObject().apply {
                put("receive_id", chatId)
                put("msg_type", "text")
                put("content", content)
            }.toString()
            httpPostJson(
                "https://open.feishu.cn/open-apis/im/v1/messages?receive_id_type=chat_id",
                headers = mapOf("Authorization" to "Bearer $tenantToken"),
                json = direct,
            )
        }
        return if (json == null) {
            lastError = "回复发送失败 chat=$chatId（HTTP 错误或网络异常）"
            Log_e("sendTextMessage 失败 chat=$chatId（HTTP 错误或网络异常）")
            false
        } else {
            true
        }
    }

    /**
     * 上传图片字节到飞书并发送图片消息（移植自 Andclaw 的 uploadImage + sendImageMessage，
     * 按飞书官方契约校正：文件 part 名为 image、附加 image_type=message 表单字段）。
     * 成功返回 true。
     */
    private fun sendImage(chatId: String, bytes: ByteArray, fileName: String, msgId: String? = null): Boolean {
        val mediaType = when {
            fileName.endsWith(".png", ignoreCase = true) -> "image/png"
            fileName.endsWith(".jpg", ignoreCase = true) || fileName.endsWith(".jpeg", ignoreCase = true) -> "image/jpeg"
            fileName.endsWith(".gif", ignoreCase = true) -> "image/gif"
            fileName.endsWith(".webp", ignoreCase = true) -> "image/webp"
            else -> "application/octet-stream"
        }
        // Step 1: 上传图片拿到 image_key
        val up = httpUploadMultipart(
            "https://open.feishu.cn/open-apis/im/v1/images",
            headers = mapOf("Authorization" to "Bearer $tenantToken"),
            formFields = mapOf("image_type" to "message"),
            fileField = "image",
            fileName = fileName,
            bytes = bytes,
            mediaType = mediaType,
        )
        val imageKey = up?.optJSONObject("data")?.optString("image_key").orEmpty()
        if (imageKey.isBlank()) {
            lastError = "图片上传失败 chat=$chatId（未返回 image_key）resp=${(up?.toString() ?: "null").take(200)}"
            Log_e("图片上传失败 chat=$chatId resp=${(up?.toString() ?: "null").take(200)}")
            return false
        }
        // Step 2: 发送图片消息（优先 reply 端点，回退 chat_id 直发）
        val content = JSONObject().put("image_key", imageKey).toString()
        val json = if (!msgId.isNullOrBlank()) {
            val body = JSONObject().apply {
                put("msg_type", "image")
                put("content", content)
            }.toString()
            httpPostJson(
                "https://open.feishu.cn/open-apis/im/v1/messages/${msgId}/reply",
                headers = mapOf("Authorization" to "Bearer $tenantToken"),
                json = body,
            )
        } else {
            val body = JSONObject().apply {
                put("receive_id", chatId)
                put("msg_type", "image")
                put("content", content)
            }.toString()
            httpPostJson(
                "https://open.feishu.cn/open-apis/im/v1/messages?receive_id_type=chat_id",
                headers = mapOf("Authorization" to "Bearer $tenantToken"),
                json = body,
            )
        }
        return if (json == null) {
            lastError = "图片消息发送失败 chat=$chatId（HTTP 错误或网络异常）"
            Log_e("sendImage 消息发送失败 chat=$chatId")
            false
        } else {
            true
        }
    }

    private inner class FeishuWsListener() : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            Log_i("WS 已连接（握手成功 HTTP ${response.code}）")
            lastError = null
            wsConnected.set(true)
            connected = true
        }

        // ===== 飞书 WS 长连接真实协议：二进制 PB 帧（非 JSON 文本）=====
        // 官方 Go SDK(ws/client.go) 仅处理 ws.BinaryMessage，文本帧直接 continue 丢弃；
        // 事件/心跳均为 protobuf(Frame) 二进制帧。旧实现只覆写 onMessage(text) 导致事件帧永远进不来
        // → 表现为"连接已建立但收不到任何消息"。这是飞书适配器独有链路问题（其他平台不发二进制帧）。
        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            val buf = bytes.toByteArray()
            val hex = buf.take(160).joinToString("") { "%02x".format(it) }
            runCatching {
                val frame = decodeFrame(buf)
                Log_i("WS二进制帧 method=${frame.method} headers=${frame.headers} payloadLen=${frame.payload.size} rawHex=${hex}")
                // 不依赖 method 字段分流（proto 字段号可能有出入），统一看 header.type 判断是否为数据帧
                val type = frame.headers.firstOrNull { it.first.equals("type", true) }?.second ?: ""
                val isData = type.equals("event", true) || type.equals("card", true) || frame.method == 1
                // 飞书硬性要求：收到帧须 3s 内 ACK（二进制回显帧，原样回带 headers，payload={"code":200}），否则重推
                val ack = encodeFrame(frame.copy(payload = "{\"code\":200}".toByteArray(Charsets.UTF_8)))
                runCatching { webSocket.send(ByteString.of(*ack)) }.onFailure { Log_w("ACK帧发送失败: ${it.message}") }
                if (isData) {
                    val payloadStr = runCatching { String(frame.payload, Charsets.UTF_8) }.getOrDefault("")
                    if (payloadStr.isNotBlank()) handleEnvelopeAny(payloadStr, webSocket)
                    else Log_w("数据帧 payload 为空，无法解析")
                } else {
                    Log_i("控制帧 type=$type（已ACK）")
                    // 兜底：部分版本控制帧也可能携带 JSON payload，尝试解析避免漏消息
                    val payloadStr = runCatching { String(frame.payload, Charsets.UTF_8) }.getOrDefault("")
                    if (payloadStr.isNotBlank() && payloadStr.trimStart().startsWith("{")) handleEnvelopeAny(payloadStr, webSocket)
                }
            }.onFailure { e ->
                // decodeFrame 失败 → 兜底：把整帧当 UTF-8 JSON 文本解析（万一飞书实际发文本帧，或 proto 字段号猜错）
                Log_e("onMessage(bytes) 解码失败: ${e.message}；整帧当JSON兜底 rawHex=${hex}")
                val asText = runCatching { String(buf, Charsets.UTF_8) }.getOrDefault("")
                if (asText.isNotBlank()) { Log_i("整帧当文本兜底: ${asText.take(300)}"); handleEnvelopeAny(asText, webSocket) }
            }
        }

        // 文本帧：防御性兜底（飞书真实协议应为二进制，但若实际发文本帧，这里照常解析并回文本 ACK）
        override fun onMessage(webSocket: WebSocket, text: String) {
            Log_i("WS文本帧（飞书应为二进制，疑似协议差异）: ${text.take(300)}")
            runCatching {
                val m = JSONObject(text)
                val mid = m.optString("message_id", "").ifBlank {
                    m.optJSONObject("event")?.optJSONObject("message")?.optString("message_id").orEmpty()
                        .ifBlank { m.optJSONObject("header")?.optString("message_id").orEmpty() }
                }
                if (mid.isNotBlank()) webSocket.send(JSONObject().put("type", "ack").put("message_id", mid).toString())
            }.onFailure { Log_w("文本ACK失败: ${it.message}") }
            handleEnvelopeAny(text, webSocket)
        }

        /**
         * 统一事件解析：兼容三种 JSON 形态
         *  A) 裸 schema 2.0 信封 {"schema":"2.0","header":{"event_type":...},"event":{"message":{...}}}
         *  B) 包裹帧 {"type":"message","message_id":"...","data":"<envelope JSON 字符串>"}
         *  C) 旧形态 {"type":"message","event":{"message":{...}}}
         * ACK 已在帧层完成，此处只解析+去重+派发。
         */
        private fun handleEnvelopeAny(jsonText: String, webSocket: WebSocket?) {
            runCatching {
                val raw = JSONObject(jsonText)
                val dataStr = raw.optString("data", "")
                val env = if (dataStr.isNotBlank()) runCatching { JSONObject(dataStr) }.getOrDefault(raw) else raw
                val outerType = raw.optString("type", "")
                val header = env.optJSONObject("header")
                val eventType = header?.optString("event_type")
                    ?: if (outerType == "message") "im.message.receive_v1"
                    else if (env.optString("type", "") == "message") "im.message.receive_v1"
                    else null
                if (eventType != "im.message.receive_v1") { Log_i("非消息事件 type=$eventType，忽略"); return@runCatching }
                val event = env.optJSONObject("event") ?: env
                val message = event.optJSONObject("message") ?: env.optJSONObject("message")
                if (message == null) { Log_w("找不到 message 对象，忽略"); return@runCatching }
                val sender = event.optJSONObject("sender") ?: env.optJSONObject("sender")
                if (sender?.optString("sender_type", "") == "app") { Log_i("忽略机器人自身消息，避免回环"); return@runCatching }
                val messageId = message.optString("message_id", "")
                val contentStr = message.optString("content", "")
                val textBody = if (contentStr.isNotBlank()) runCatching { JSONObject(contentStr).optString("text", "") }.getOrDefault("") else ""
                val chatId = message.optString("chat_id", "")
                val openId = sender?.optJSONObject("sender_id")?.optString("open_id").orEmpty()
                if (chatId.isNotBlank() && textBody.isNotBlank() && messageId.isNotBlank()) {
                    if (!seenMsgIds.add(messageId)) { Log_w("重复 message_id=$messageId 跳过"); return@runCatching }
                    Log_i("解析到消息 chatId=$chatId msgId=$messageId → 派发（text=${textBody.take(40)}）")
                    scope.launch { onInbound(chatId, openId.ifBlank { chatId }, textBody, msgId = messageId) }
                } else {
                    Log_w("消息字段缺失被忽略 chatId=$chatId msgId=$messageId textLen=${textBody.length}")
                }
            }.onFailure { e -> Log_e("handleEnvelopeAny 解析失败: ${e.message} text=${jsonText.take(200)}") }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            Log_w("WS closing $code $reason")
            wsConnected.set(false)
            webSocket.cancel()
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Log_w("WS closed $code $reason")
            wsConnected.set(false)
            connected = false
            if (code != 1000 && code != 1001) {
                lastError = "WS 已断开（code=$code ${reason.ifBlank { "无原因" }}）"
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            val httpInfo = response?.let { "HTTP ${it.code} ${it.message}" } ?: ""
            val respBody = response?.body?.string().orEmpty().take(300)
            Log_e("WS failure: ${t.javaClass.simpleName}: ${t.message} $httpInfo body=$respBody")
            lastError = when (response?.code) {
                404 -> "WS 404: 应用未启用长连接/未发布/或事件订阅配置有误"
                401, 403 -> "WS ${response.code}: token 无效或应用无权限"
                else -> "WS 失败: ${t.message ?: "未知"} $httpInfo"
            }
            wsConnected.set(false)
            connected = false
        }
    }

    // ===== 飞书 WS 二进制 PB 帧编解码（schema 取自飞书官方 Go SDK ws/pbbp2.pb.go）=====
    // Frame: 1 SeqID(varint) 2 LogID(varint) 3 Service(varint) 4 Method(varint)
    //        5 Headers(repeated Header) 6 PayloadEncoding(str) 7 PayloadType(str) 8 Payload(bytes) 9 LogIDNew(str)
    // Header: 1 Key(str) 2 Value(str)；wire type: varint=0, length-delimited=2
    private data class FeishuFrame(
        var seqId: Long = 0,
        var logId: Long = 0,
        var service: Int = 0,
        var method: Int = 0,
        var headers: MutableList<Pair<String, String>> = mutableListOf(),
        var payloadEncoding: String = "",
        var payloadType: String = "",
        var payload: ByteArray = ByteArray(0),
        var logIdNew: String = "",
    )

    private fun decodeFrame(buf: ByteArray): FeishuFrame {
        val f = FeishuFrame()
        var i = 0
        while (i < buf.size) {
            val (tag, ni) = readVarint(buf, i); i = ni
            val fieldNum = tag.toInt() shr 3
            val wireType = tag.toInt() and 7
            when (fieldNum) {
                1 -> { val (v, ni2) = readVarint(buf, i); f.seqId = v; i = ni2 }
                2 -> { val (v, ni2) = readVarint(buf, i); f.logId = v; i = ni2 }
                3 -> { val (v, ni2) = readVarint(buf, i); f.service = v.toInt(); i = ni2 }
                4 -> { val (v, ni2) = readVarint(buf, i); f.method = v.toInt(); i = ni2 }
                5 -> {
                    val (len, ni2) = readVarint(buf, i); i = ni2
                    val sub = buf.copyOfRange(i, i + len.toInt()); i += len.toInt()
                    var hk = ""; var hv = ""
                    var j = 0
                    while (j < sub.size) {
                        val (ht, nj) = readVarint(sub, j); j = nj
                        val hfn = ht.toInt() shr 3; val hwt = ht.toInt() and 7
                        when (hfn) {
                            1 -> { val (l, nj2) = readVarint(sub, j); j = nj2
                                hk = String(sub.copyOfRange(j, j + l.toInt()), Charsets.UTF_8); j += l.toInt() }
                            2 -> { val (l, nj2) = readVarint(sub, j); j = nj2
                                hv = String(sub.copyOfRange(j, j + l.toInt()), Charsets.UTF_8); j += l.toInt() }
                            else -> { j = skipField(sub, j, hwt) }
                        }
                    }
                    f.headers.add(hk to hv)
                }
                6 -> { val (l, ni2) = readVarint(buf, i); i = ni2
                    f.payloadEncoding = String(buf.copyOfRange(i, i + l.toInt()), Charsets.UTF_8); i += l.toInt() }
                7 -> { val (l, ni2) = readVarint(buf, i); i = ni2
                    f.payloadType = String(buf.copyOfRange(i, i + l.toInt()), Charsets.UTF_8); i += l.toInt() }
                8 -> { val (l, ni2) = readVarint(buf, i); i = ni2
                    f.payload = buf.copyOfRange(i, i + l.toInt()); i += l.toInt() }
                9 -> { val (l, ni2) = readVarint(buf, i); i = ni2
                    f.logIdNew = String(buf.copyOfRange(i, i + l.toInt()), Charsets.UTF_8); i += l.toInt() }
                else -> { i = skipField(buf, i, wireType) }
            }
        }
        return f
    }

    private fun encodeFrame(f: FeishuFrame): ByteArray {
        val out = ByteArrayOutputStream()
        if (f.seqId != 0L) { writeTag(out, 1, 0); writeVarint(out, f.seqId) }
        if (f.logId != 0L) { writeTag(out, 2, 0); writeVarint(out, f.logId) }
        if (f.service != 0) { writeTag(out, 3, 0); writeVarint(out, f.service.toLong()) }
        if (f.method != 0) { writeTag(out, 4, 0); writeVarint(out, f.method.toLong()) }
        for ((k, v) in f.headers) {
            val hb = ByteArrayOutputStream()
            writeTag(hb, 1, 2); writeVarint(hb, k.length.toLong()); hb.write(k.toByteArray(Charsets.UTF_8))
            writeTag(hb, 2, 2); writeVarint(hb, v.length.toLong()); hb.write(v.toByteArray(Charsets.UTF_8))
            writeTag(out, 5, 2); writeVarint(out, hb.size().toLong()); out.write(hb.toByteArray())
        }
        if (f.payloadEncoding.isNotBlank()) writeBytesField(out, 6, f.payloadEncoding.toByteArray(Charsets.UTF_8))
        if (f.payloadType.isNotBlank()) writeBytesField(out, 7, f.payloadType.toByteArray(Charsets.UTF_8))
        if (f.payload.isNotEmpty()) writeBytesField(out, 8, f.payload)
        if (f.logIdNew.isNotBlank()) writeBytesField(out, 9, f.logIdNew.toByteArray(Charsets.UTF_8))
        return out.toByteArray()
    }

    private fun readVarint(buf: ByteArray, offset: Int): Pair<Long, Int> {
        var result = 0L; var shift = 0; var i = offset
        while (i < buf.size) {
            val b = buf[i].toLong() and 0xFF; i++
            result = result or ((b and 0x7F) shl shift)
            if (b and 0x80L == 0L) break
            shift += 7
        }
        return result to i
    }

    private fun skipField(buf: ByteArray, offset: Int, wireType: Int): Int {
        var i = offset
        return when (wireType) {
            0 -> readVarint(buf, i).second
            1 -> i + 8
            2 -> { val (len, ni) = readVarint(buf, i); ni + len.toInt() }
            5 -> i + 4
            else -> i
        }
    }

    private fun writeVarint(out: ByteArrayOutputStream, value: Long) {
        var v = value
        do {
            var b = (v and 0x7F).toByte()
            v = v ushr 7
            if (v != 0L) b = (b.toInt() or 0x80).toByte()
            out.write(b.toInt())
        } while (v != 0L)
    }

    private fun writeTag(out: ByteArrayOutputStream, fieldNum: Int, wireType: Int) {
        writeVarint(out, ((fieldNum shl 3) or wireType).toLong())
    }

    private fun writeBytesField(out: ByteArrayOutputStream, fieldNum: Int, bytes: ByteArray) {
        writeTag(out, fieldNum, 2)
        writeVarint(out, bytes.size.toLong())
        out.write(bytes)
    }

    private fun Log_i(s: String) { android.util.Log.i(TAG, "[Feishu] $s"); QuroDiag.log("Feishu", s) }
    private fun Log_w(s: String) { android.util.Log.w(TAG, "[Feishu] $s"); QuroDiag.log("Feishu", s) }
    private fun Log_e(s: String) { android.util.Log.e(TAG, "[Feishu] $s"); QuroDiag.log("Feishu", s) }
}
