package com.ai.assistance.quro.core.mika.cap.web.cdp

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** 协议事件。[sessionId] 非空表示来自扁平化附着的子会话(`Target.setAutoAttach` 且 `flatten=true`)。 */
data class CdpEvent(val method: String, val params: JsonObject, val sessionId: String? = null)

/** 对端对某个调用回了 error 对象。 */
class CdpException(val code: Int, val errorMessage: String, val method: String, val data: String? = null) :
    RuntimeException("$method failed ($code): $errorMessage" + (data?.let { " — $it" } ?: ""))

class CdpTimeoutException(val method: String, val timeoutMs: Long) : IOException("$method timed out after ${timeoutMs}ms")

/** 连接已断或已关闭;断开时还没回的调用都以它失败。 */
class CdpConnectionClosedException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * 一条 DevTools WebSocket 上的 JSON-RPC 会话。一个读协程负责收包:按 id 交还响应,其余作为事件发出;
 * 发送在 IO 线程上,写帧串行。同一条连接可以带多个扁平化子会话,用 sessionId 区分。
 *
 * 事件流不重放,没有订阅者时事件直接丢弃;缓冲 [eventBuffer] 条,订阅者跟不上时丢最旧的,
 * 读协程从不因事件阻塞(否则响应也会被卡住)。需要完整事件序列的订阅者要及时消费,
 * 并在发出触发事件的命令前就开始订阅。
 */
class CdpSession(
    private val ws: WebSocketConnection,
    eventBuffer: Int = DEFAULT_EVENT_BUFFER,
) : Closeable {
    private class Pending(val method: String, val result: CompletableDeferred<JsonObject>)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pending = ConcurrentHashMap<Int, Pending>()
    private val nextId = AtomicInteger(1)
    @Volatile private var closeError: CdpConnectionClosedException? = null

    private val _events = MutableSharedFlow<CdpEvent>(extraBufferCapacity = eventBuffer, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val events: SharedFlow<CdpEvent> = _events.asSharedFlow()

    /**
     * Input 域(鼠标、拖放)坐标与 CSS 像素之比:发出去的坐标 = CSS 像素 × 它。
     * 平时为 1;视口仿真带缩放(桌面宽度的页面缩进手机视图)时,Input 按缩放前的视图坐标解释,由设仿真的一方改掉。
     */
    @Volatile var inputScale: Double = 1.0

    /** 上一次鼠标事件落在哪(CSS 像素);还没发过为 null。下一次移动从这里沿轨迹过去。 */
    @Volatile var pointer: Pair<Double, Double>? = null

    private val _closed = MutableStateFlow(false)
    val closed: StateFlow<Boolean> = _closed.asStateFlow()

    private val reader = scope.launch { readLoop() }

    suspend fun send(
        method: String,
        params: JsonObject = EMPTY,
        sessionId: String? = null,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): JsonObject {
        closeError?.let { throw CdpConnectionClosedException(it.message ?: "closed", it.cause) }
        val id = nextId.getAndIncrement()
        val p = Pending(method, CompletableDeferred())
        pending[id] = p
        // 读协程先置 closeError 再清空 pending:这里放进去之后再查一次,就不会漏掉
        closeError?.let {
            pending.remove(id)
            throw CdpConnectionClosedException(it.message ?: "closed", it.cause)
        }
        val msg = buildJsonObject {
            put("id", JsonPrimitive(id))
            put("method", JsonPrimitive(method))
            put("params", params)
            if (sessionId != null) put("sessionId", JsonPrimitive(sessionId))
        }.toString()
        try {
            // 写也有上限:对端不读、缓冲写满时到点断开连接,不让调用方一直卡在写上
            withStreamDeadline(timeoutMs, method, open = { Closeable { ws.abort() } }, closeOnCancel = false) { ws.sendText(msg) }
        } catch (e: IOException) {
            pending.remove(id)
            ws.abort()
            throw CdpConnectionClosedException("CDP connection lost while sending $method", e)
        }
        try {
            return withTimeoutOrNull(timeoutMs) { p.result.await() } ?: throw CdpTimeoutException(method, timeoutMs)
        } finally {
            pending.remove(id)
        }
    }

    /** 发关闭帧并断开;未完成的调用立即失败。 */
    override fun close() {
        if (closeError != null) return
        shutdown(CdpConnectionClosedException("CDP session closed"))
        runCatching { ws.sendClose() }
        // 给对端一点时间回关闭帧,然后硬断
        scope.launch {
            withTimeoutOrNull(CLOSE_GRACE_MS) { reader.join() }
            ws.abort()
            scope.cancel()
        }
    }

    private fun readLoop() {
        var cause: Throwable? = null
        try {
            while (true) {
                val text = ws.receive() ?: break
                val obj = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: continue
                val id = (obj["id"] as? JsonPrimitive)?.intOrNull
                if (id != null) {
                    val p = pending[id] ?: continue
                    val error = obj["error"] as? JsonObject
                    if (error != null) {
                        p.result.completeExceptionally(
                            CdpException(
                                code = error["code"]?.jsonPrimitive?.intOrNull ?: 0,
                                errorMessage = error["message"]?.jsonPrimitive?.contentOrNull ?: "",
                                method = p.method,
                                data = error["data"]?.let { (it as? JsonPrimitive)?.contentOrNull ?: it.toString() },
                            ),
                        )
                    } else {
                        p.result.complete(obj["result"] as? JsonObject ?: EMPTY)
                    }
                    continue
                }
                val method = (obj["method"] as? JsonPrimitive)?.contentOrNull ?: continue
                _events.tryEmit(
                    CdpEvent(method, obj["params"] as? JsonObject ?: EMPTY, (obj["sessionId"] as? JsonPrimitive)?.contentOrNull),
                )
            }
        } catch (t: Throwable) {
            cause = t
        }
        val reason = when {
            closeError != null -> null
            ws.closeCode != null -> "CDP connection closed by peer (${ws.closeCode}${ws.closeReason.takeIf { it.isNotEmpty() }?.let { " $it" } ?: ""})"
            else -> "CDP connection lost: ${cause?.message ?: "end of stream"}"
        }
        if (reason != null) shutdown(CdpConnectionClosedException(reason, cause))
        ws.abort()
    }

    private fun shutdown(error: CdpConnectionClosedException) {
        synchronized(this) {
            if (closeError != null) return
            closeError = error
        }
        for (id in pending.keys.toList()) {
            pending.remove(id)?.result?.completeExceptionally(
                CdpConnectionClosedException(error.message ?: "closed", error.cause),
            )
        }
        _closed.value = true
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 30_000L
        const val DEFAULT_EVENT_BUFFER = 1024
        private const val CLOSE_GRACE_MS = 1_000L
        private val EMPTY = JsonObject(emptyMap())
        private val json = Json { ignoreUnknownKeys = true }

        /** 打开传输、做 WebSocket 握手,返回会话。[path] 取自目标的 webSocketDebuggerUrl。超时抛 [CdpTimeoutException]。 */
        suspend fun connect(
            transport: CdpTransport,
            endpoint: String,
            path: String,
            host: String = CdpDiscovery.DEFAULT_HOST,
            maxMessageBytes: Int = WebSocketConnection.DEFAULT_MAX_MESSAGE_BYTES,
            eventBuffer: Int = DEFAULT_EVENT_BUFFER,
            handshakeTimeoutMs: Long = CdpDiscovery.DEFAULT_TIMEOUT_MS,
        ): CdpSession {
            // 握手至多 [handshakeTimeoutMs]:到点关掉流,卡住的握手随之失败
            val ws = withStreamDeadline(handshakeTimeoutMs, "WebSocket handshake $path", open = { transport.open(endpoint) }) { s ->
                WebSocketConnection.connect(s, path, host, maxMessageBytes)
            }
            return CdpSession(ws, eventBuffer)
        }
    }
}
