package com.ai.assistance.quro.core.mika.remote

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.selects.select
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.net.InetAddress
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.random.Random

enum class RelayState { Off, Connecting, Online, Offline, Unauthorized, Incompatible }

/**
 * 只从已配对的 TLS 网关接收MCP 和任务 API 请求。连接可以重建,请求从不重放。
 * 一个连接最多八个请求;取消/断线中断等待,但不声称撤销已经执行的动作。
 */
class RelayClient(private val scope: CoroutineScope, private val handle: (HttpRequest) -> HttpResponse,
    private val http: OkHttpClient = RelayGateway.http().newBuilder().callTimeout(0, TimeUnit.SECONDS)
        .pingInterval(25, TimeUnit.SECONDS).build(),
    private val allowLoopbackHttp: Boolean = false,
    private val reconnectDelay: suspend (Long) -> Unit = { delay(it) },
) {
    private val mutable = MutableStateFlow(RelayState.Off)
    val state = mutable.asStateFlow()
    private var running: Job? = null
    private var current: Session? = null
    private var identity: String? = null
    private val networkChanges = Channel<Unit>(Channel.CONFLATED)

    /** Android 网络切换时关闭旧通道,唤醒退避等待;仍不重放任何请求。 */
    @Synchronized fun networkChanged() {
        if (identity == null) return
        current?.dispose()
        networkChanges.trySend(Unit)
    }

    @Synchronized fun start(pairing: RelayPairing) {
        val key = pairing.gateway + "\n" + pairing.deviceToken
        if (running?.isActive == true && identity == key) return
        stop()
        val url = RelayGateway.parse(pairing.gateway, allowLoopbackHttp).newBuilder().encodedPath("/device").build()
        identity = key
        val request = Request.Builder().url(url).header("Authorization", "Bearer ${pairing.deviceToken}")
            .header("Sec-WebSocket-Protocol", PROTOCOL).build()
        running = scope.launch {
            var attempts = 0
            while (true) {
                val session = Session(this)
                synchronized(this@RelayClient) { if (identity != key) return@launch; current = session; mutable.value = RelayState.Connecting }
                session.socket = http.newWebSocket(request, session)
                val unauthorized = try { session.finished.await() } finally { session.dispose() }
                synchronized(this@RelayClient) {
                    if (identity != key) return@launch
                    current = null
                    mutable.value = if (session.incompatible) RelayState.Incompatible else if (unauthorized) RelayState.Unauthorized else RelayState.Offline
                }
                if (unauthorized) return@launch
                attempts = if (session.opened) 0 else (attempts + 1).coerceAtMost(6)
                coroutineScope {
                    val waiting = async { reconnectDelay((1_000L shl attempts).coerceAtMost(60_000) + Random.nextLong(500)) }
                    try { select { waiting.onAwait { }; networkChanges.onReceive { } } } finally { waiting.cancel() }
                }
            }
        }
    }

    @Synchronized fun stop() {
        identity = null
        running?.cancel(); running = null
        current?.dispose(); current = null
        mutable.value = RelayState.Off
        while (networkChanges.tryReceive().isSuccess) { /* 丢弃上次连接的网络事件。 */ }
    }

    private inner class Session(parent: CoroutineScope) : WebSocketListener() {
        val finished = CompletableDeferred<Boolean>()
        val workers = CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job]))
        var socket: WebSocket? = null
        @Volatile var opened = false
        @Volatile var incompatible = false
        private val jobs = LinkedHashMap<String, Job>()
        private val seen = HashSet<String>()

        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (response.header("Sec-WebSocket-Protocol") != PROTOCOL) { incompatible = true; webSocket.cancel(); finished.complete(true); return }
            synchronized(this@RelayClient) {
                if (current !== this) { webSocket.cancel(); return }
                opened = true; mutable.value = RelayState.Online
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (text.length > MAX_FRAME || text.toByteArray(Charsets.UTF_8).size > MAX_FRAME) { reject(webSocket); return }
            val f = runCatching { json.decodeFromString<RelayFrame>(text) }.getOrNull()
            if (f == null || !Regex("[A-Za-z0-9_-]{16,64}").matches(f.id)) { reject(webSocket); return }
            if (f.type == "cancel") { synchronized(jobs) { jobs[f.id]?.cancel() }; return }
            val body = runCatching { Base64.getDecoder().decode(f.body) }.getOrNull()
            if (f.type != "request" || f.method !in setOf("GET", "POST", "DELETE") || !relayPath(f.path) || f.status != 0 ||
                f.query.length > 8192 || f.query.any { it == '#' || it == '\r' || it == '\n' } || !CLIENT.matches(f.client) ||
                body == null || body.size > MAX_BODY || !validHeaders(f.headers)) { reject(webSocket); return }
            synchronized(jobs) {
                // Reusing an ID is a protocol violation, including after the original request completed.
                if (!seen.add(f.id)) { reject(webSocket); return }
                if (seen.size > 4096) { webSocket.close(1001, "Reconnect"); finished.complete(false); return }
                if (jobs.size >= 8) { reply(webSocket, f.id, HttpResponse.json(429, "{\"error\":\"device_busy\"}")); return }
                val job = workers.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
                    try {
                        // A synthetic unspecified address is not loopback and never passes through USB authentication.
                        // The gateway's view of the caller only keys the failed-token cooldown, apart from local addresses.
                        val req = HttpRequest(f.method, f.path, f.headers, body, InetAddress.getByAddress(byteArrayOf(0, 0, 0, 0)),
                            query = f.query, source = "relay|${f.client}")
                        val result = runInterruptible { handle(req) }
                        reply(webSocket, f.id, result)
                    } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                    catch (_: Exception) { reply(webSocket, f.id, HttpResponse.json(500, "{\"error\":\"request_failed\"}")) }
                    finally { synchronized(jobs) { jobs.remove(f.id) } }
                }
                jobs[f.id] = job
                job.start()
            }
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) { reject(webSocket) }
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, null); finished.complete(code == 1008) }
        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { finished.complete(code == 1008) }
        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            incompatible = response?.code == 426
            finished.complete(incompatible || response?.code in listOf(401, 403))
        }
        private fun reject(ws: WebSocket) { ws.close(1008, "Invalid relay message"); finished.complete(true) }

        private fun reply(ws: WebSocket, id: String, response: HttpResponse) {
            if (response.body.size > MAX_BODY) {
                reply(ws, id, RemoteTaskApi.error(502, "response_too_large", "Response exceeds relay limit"))
                return
            }
            val headers = response.headers.mapKeys { it.key.lowercase() }.filterKeys { it in RESPONSE_HEADERS }
            if (!ws.send(json.encodeToString(RelayFrame(type = "response", id = id, headers = headers,
                    body = Base64.getEncoder().encodeToString(response.body), status = response.status)))) {
                ws.cancel(); finished.complete(false)
            }
        }

        fun dispose() { workers.cancel(); socket?.cancel(); finished.complete(false) }
    }

    companion object {
        const val PROTOCOL = "quro-relay.v2"
        const val MAX_BODY = 1_048_576
        private const val MAX_FRAME = 2 * MAX_BODY
        private val json = Json { encodeDefaults = false }
        private val REQUEST_HEADERS = setOf("authorization", "origin", "x-quro-client", "content-type", "accept", "mcp-protocol-version", "mcp-method", "mcp-name", "mcp-session-id", "last-event-id")
        private val RESPONSE_HEADERS = setOf("content-type", "allow", "retry-after", "www-authenticate", "mcp-protocol-version")
        /** 网关看到的调用方:IPv4 地址或 IPv6 的 /64 前缀;网关没给时为空,这些调用方共用一份冷却。 */
        private val CLIENT = Regex("[0-9A-Fa-f.:/]{0,64}")
        private fun relayPath(path: String) = path == "/mcp" || path == "/api/v1" || path.startsWith("/api/v1/")
        private fun validHeaders(headers: Map<String, String>): Boolean = headers.size <= 16 &&
            headers.entries.sumOf { it.key.length + it.value.length } <= 8192 &&
            headers.all { (k, v) -> k in REQUEST_HEADERS && '\r' !in v && '\n' !in v }
    }
}

@Serializable internal data class RelayFrame(val type: String, val id: String, val method: String = "", val path: String = "",
    val headers: Map<String, String> = emptyMap(), val body: String = "", val status: Int = 0, val query: String = "",
    val client: String = "")
