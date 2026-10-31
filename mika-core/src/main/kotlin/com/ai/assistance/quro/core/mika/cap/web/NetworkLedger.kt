package com.ai.assistance.quro.core.mika.cap.web

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import java.util.Base64

/**
 * 页面发出的一个 XHR/fetch 请求及其响应。[id] 是协议里的 requestId(子会话的加上 `会话:` 前缀);
 * [size] 为解码后的字节数(拿不到为 null);[body] 只在取过正文后才有,[bodyNote] 说明为什么没有正文。
 */
data class CapturedResponse(
    val id: String,
    val url: String,
    val method: String,
    val type: String,
    val status: Int?,
    val mime: String?,
    val size: Long?,
    val fromCache: Boolean,
    val state: State,
    val error: String?,
    val startedMs: Long,
    val body: String? = null,
    val bodyTruncated: Boolean = false,
    val bodyNote: String? = null,
) {
    enum class State(val wire: String) { PENDING("pending"), DONE("done"), FAILED("failed") }
}

/**
 * 一个标签的网络账本(纯逻辑,不碰协议连接):喂入 `Network.*` 事件,回答「还有没有在途请求」「安静了多久」,
 * 并留着最近 [maxCaptured] 个 XHR/fetch 响应。正文按需取(调用方用 `Network.getResponseBody` 取回后交给 [putBody]),
 * 只收 JSON/文本、单个不超过 [maxBodyBytes];全部正文合计超过 [maxTotalBodyBytes] 时先丢最早取的。
 *
 * 在途计数不算长连接:事件流、媒体、信标、预取,以及发出超过 [staleMs] 还没回的(长轮询)。
 * 不是线程安全的,调用方自己串行。
 */
class NetworkLedger(
    val quietMs: Long = 500,
    val staleMs: Long = 10_000,
    val maxCaptured: Int = 200,
    val maxBodyBytes: Int = 256 * 1024,
    val maxTotalBodyBytes: Int = 2 * 1024 * 1024,
) {
    data class BodyFetch(val requestId: String, val sessionId: String?)

    private class Flight(val startedMs: Long, var longLived: Boolean, val actionSeq: Int)

    private class Entry(
        val id: String,
        val requestId: String,
        val sessionId: String?,
        var url: String,
        var method: String,
        val type: String,
        val startedMs: Long,
        val actionSeq: Int,
    ) {
        var status: Int? = null
        var mime: String? = null
        var decoded = 0L
        var encoded: Long? = null
        var fromCache = false
        var state = CapturedResponse.State.PENDING
        var error: String? = null
        var body: String? = null
        var bodyBytes = 0
        var bodyTruncated = false
    }

    private val flights = HashMap<String, Flight>()
    private val captured = LinkedHashMap<String, Entry>()
    // 取到正文的先后,用来按最早的先丢
    private val bodyOrder = ArrayDeque<String>()
    private var bodyTotal = 0
    private var lastBusyMs = Long.MIN_VALUE / 2
    // 只算本次动作以来发出的请求
    private var lastActionBusyMs = Long.MIN_VALUE / 2
    private var actionSeq = 0

    fun onEvent(method: String, params: JsonObject, now: Long, sessionId: String? = null) {
        val requestId = params.str("requestId") ?: return
        val key = if (sessionId == null) requestId else "$sessionId:$requestId"
        when (method) {
            "Network.requestWillBeSent" -> {
                val request = params["request"] as? JsonObject ?: return
                val url = request.str("url") ?: return
                val type = params.str("type") ?: "Other"
                val m = request.str("method") ?: "GET"
                val f = flights[key]
                if (f == null) {
                    pruneStale(now)
                    flights[key] = Flight(now, type in LONG_LIVED_TYPES, actionSeq)
                }
                val flight = flights.getValue(key)
                if (!flight.longLived) {
                    lastBusyMs = now
                    if (flight.actionSeq == actionSeq) lastActionBusyMs = now
                }
                val e = captured[key]
                if (e != null) {
                    // 重定向:同一个 requestId 带着上一跳的响应再发一次
                    e.url = url
                    e.method = m
                } else if (type in CAPTURED_TYPES) {
                    captured[key] = Entry(key, requestId, sessionId, url, m, type, now, actionSeq)
                    while (captured.size > maxCaptured) drop(captured.keys.first())
                }
            }
            "Network.requestServedFromCache" -> captured[key]?.fromCache = true
            "Network.responseReceived" -> {
                val r = params["response"] as? JsonObject ?: return
                val mime = r.str("mimeType")
                if (mime != null && mime.lowercase().startsWith(EVENT_STREAM)) flights[key]?.longLived = true
                captured[key]?.apply {
                    status = (r["status"] as? JsonPrimitive)?.doubleOrNull?.toInt()
                    this.mime = mime
                    if (r.bool("fromDiskCache") || r.bool("fromPrefetchCache") || r.bool("fromServiceWorker")) fromCache = true
                }
            }
            "Network.dataReceived" -> captured[key]?.let { it.decoded += params.long("dataLength") ?: 0 }
            "Network.loadingFinished" -> {
                end(key, now)
                captured[key]?.apply {
                    state = CapturedResponse.State.DONE
                    encoded = params.long("encodedDataLength")
                }
            }
            "Network.loadingFailed" -> {
                end(key, now)
                captured[key]?.apply {
                    state = CapturedResponse.State.FAILED
                    error = params.str("errorText")?.takeIf { it.isNotBlank() }
                        ?: if (params.bool("canceled")) "canceled" else params.str("blockedReason") ?: "failed"
                }
            }
        }
    }

    /** 现在还在途、且会拖住「网络空闲」的请求数。 */
    fun inFlight(now: Long): Int = flights.values.count { counts(it, now) }

    /** 没有在途请求,且最近一次请求开始或结束已过 [quietMs]。 */
    fun isIdle(now: Long): Boolean = inFlight(now) == 0 && now - lastBusyMs >= quietMs

    /** 动作开始时调:之后发出的请求算「上次动作以来」的。 */
    /**
     * 本次动作([markAction] 以来)发出的请求还在途,或最后一个结束还不到 [quietMs]:动作后的等待据此延长。
     * 动作之前就在途的、长连接、长轮询都不算,页面本身一直在变也不算。
     */
    fun actionBusy(now: Long): Boolean =
        flights.values.any { it.actionSeq == actionSeq && counts(it, now) } || now - lastActionBusyMs < quietMs

    fun markAction() {
        lastActionBusyMs = Long.MIN_VALUE / 2
        actionSeq++
    }

    fun list(urlContains: String? = null, method: String? = null, sinceLastAction: Boolean = false): List<CapturedResponse> =
        captured.values.filter { e ->
            (urlContains == null || e.url.contains(urlContains, ignoreCase = true)) &&
                (method == null || e.method.equals(method, ignoreCase = true)) &&
                (!sinceLastAction || e.actionSeq == actionSeq)
        }.map { view(it) }

    fun get(id: String): CapturedResponse? = captured[id]?.let { view(it) }

    /** 该去取正文时给出协议参数;已取过、不该取(非文本、太大、没完成)时为 null,原因见 [CapturedResponse.bodyNote]。 */
    fun bodyToFetch(id: String): BodyFetch? {
        val e = captured[id] ?: return null
        if (e.body != null || noBodyReason(e) != null) return null
        return BodyFetch(e.requestId, e.sessionId)
    }

    fun putBody(id: String, body: String, base64Encoded: Boolean) {
        val e = captured[id] ?: return
        forgetBody(e)
        val text = if (base64Encoded) runCatching { String(Base64.getDecoder().decode(body), Charsets.UTF_8) }.getOrNull() ?: return else body
        var kept = text
        var bytes = kept.toByteArray(Charsets.UTF_8).size
        if (bytes > maxBodyBytes) {
            kept = cutToBytes(kept, maxBodyBytes)
            bytes = kept.toByteArray(Charsets.UTF_8).size
            e.bodyTruncated = true
        }
        e.body = kept
        e.bodyBytes = bytes
        bodyOrder.addLast(id)
        bodyTotal += bytes
        while (bodyTotal > maxTotalBodyBytes && bodyOrder.size > 1) {
            captured[bodyOrder.removeFirst()]?.let(::forgetBody)
        }
    }

    fun totalBodyBytes(): Int = bodyTotal

    private fun counts(f: Flight, now: Long) = !f.longLived && now - f.startedMs < staleMs

    private fun end(key: String, now: Long) {
        val f = flights.remove(key) ?: return
        if (counts(f, now)) {
            lastBusyMs = now
            if (f.actionSeq == actionSeq) lastActionBusyMs = now
        }
    }

    private fun pruneStale(now: Long) {
        if (flights.size < MAX_FLIGHTS) return
        flights.entries.removeAll { now - it.value.startedMs >= staleMs }
    }

    private fun drop(key: String) {
        captured.remove(key)?.let(::forgetBody)
    }

    private fun forgetBody(e: Entry) {
        if (e.body == null) return
        bodyTotal -= e.bodyBytes
        bodyOrder.remove(e.id)
        e.body = null
        e.bodyBytes = 0
        e.bodyTruncated = false
    }

    private fun size(e: Entry): Long? = e.decoded.takeIf { it > 0 } ?: e.encoded?.takeIf { it > 0 }

    private fun noBodyReason(e: Entry): String? = when {
        e.state == CapturedResponse.State.FAILED -> "failed"
        e.state != CapturedResponse.State.DONE -> "not_finished"
        !isText(e.mime) -> "not_text"
        (size(e) ?: 0) > maxBodyBytes -> "too_large"
        else -> null
    }

    private fun view(e: Entry) = CapturedResponse(
        id = e.id, url = e.url, method = e.method, type = e.type, status = e.status, mime = e.mime, size = size(e),
        fromCache = e.fromCache, state = e.state, error = e.error, startedMs = e.startedMs,
        body = e.body, bodyTruncated = e.bodyTruncated, bodyNote = if (e.body == null) noBodyReason(e) else null,
    )

    companion object {
        /** 只收页面脚本发的请求。 */
        private val CAPTURED_TYPES = setOf("XHR", "Fetch")
        /** 天生长时间不结束、或页面不等它的请求:不拖住「网络空闲」。 */
        private val LONG_LIVED_TYPES = setOf("EventSource", "WebSocket", "Media", "Ping", "Prefetch", "CSPViolationReport")
        private const val EVENT_STREAM = "text/event-stream"
        private const val MAX_FLIGHTS = 512

        fun isText(mime: String?): Boolean {
            val m = mime?.lowercase()?.substringBefore(';')?.trim() ?: return false
            return m.startsWith("text/") || m.endsWith("json") || m.endsWith("+json") || m.endsWith("xml") || m == "application/javascript"
        }

        private fun cutToBytes(s: String, max: Int): String {
            var bytes = 0
            var i = 0
            while (i < s.length) {
                val cp = s.codePointAt(i)
                val n = when {
                    cp < 0x80 -> 1
                    cp < 0x800 -> 2
                    cp < 0x10000 -> 3
                    else -> 4
                }
                if (bytes + n > max) break
                bytes += n
                i += Character.charCount(cp)
            }
            return s.substring(0, i)
        }

        private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        private fun JsonObject.bool(k: String) = (this[k] as? JsonPrimitive)?.booleanOrNull == true
        private fun JsonObject.long(k: String) = (this[k] as? JsonPrimitive)?.doubleOrNull?.toLong()
    }
}
