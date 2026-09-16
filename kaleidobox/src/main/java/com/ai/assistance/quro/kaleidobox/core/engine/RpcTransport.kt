package com.ai.assistance.quro.kaleidobox.core.engine

import com.ai.assistance.quro.kaleidobox.core.KaleidoException
import com.ai.assistance.quro.kaleidobox.core.util.Json
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicLong

/**
 * JSON-RPC 2.0 传输层（双向、基于行分隔的 JSON）。
 *
 * 这一层是 Kaleido "生态兼容" 的技术支点：
 *   同一个 peer 实现，既能驱动 Node/Python 子进程，也能直接对话任何 MCP stdio server，
 *   因为它们本来就都是 JSON-RPC 2.0。不需要为 MCP 单独写一套协议栈。
 */

data class RpcRequest(
    val id: Long?,
    val method: String,
    val params: Map<String, Any?> = emptyMap(),
    val isNotification: Boolean = false,
)

data class RpcResponse(
    val id: Long,
    val result: Any? = null,
    val error: RpcError? = null,
)

data class RpcError(val code: Int, val message: String, val data: Any? = null) {
    companion object {
        const val PARSE_ERROR = -32700
        const val INVALID_REQUEST = -32600
        const val METHOD_NOT_FOUND = -32601
        const val INVALID_PARAMS = -32602
        const val INTERNAL_ERROR = -32603
        const val TIMEOUT = -32000
        const val PERMISSION = -32001
        const val QUOTA = -32002
    }
}

interface RpcTransport : AutoCloseable {
    fun send(line: String)
    fun readLine(): String?
    val isAlive: Boolean
}

/** 子进程 stdio 传输。用于 node / python / mcp-stdio。 */
class StdioTransport(private val process: Process) : RpcTransport {
    private val writer = BufferedWriter(OutputStreamWriter(process.outputStream, StandardCharsets.UTF_8))
    private val reader = BufferedReader(InputStreamReader(process.inputStream, StandardCharsets.UTF_8))
    override val isAlive: Boolean get() = process.isAlive

    @Synchronized
    override fun send(line: String) {
        writer.write(line)
        writer.write("\n")
        writer.flush()
    }

    override fun readLine(): String? = try {
        reader.readLine()
    } catch (_: Throwable) {
        null
    }

    override fun close() {
        runCatching { writer.close() }
        runCatching { reader.close() }
        runCatching { process.destroy() }
        runCatching {
            if (!process.waitFor(3, TimeUnit.SECONDS)) process.destroyForcibly()
        }
    }

    /** stderr 单独收集，出错时能给出真正的原因（很多脚本错误只写 stderr）。 */
    fun stderrTail(maxLines: Int = 40): String = try {
        val err = process.errorStream.bufferedReader()
        val lines = mutableListOf<String>()
        var l: String?
        while (err.readLine().also { l = it } != null) {
            lines += l!!
            if (lines.size > maxLines * 4) lines.removeAt(0)
        }
        lines.takeLast(maxLines).joinToString("\n")
    } catch (_: Throwable) {
        ""
    }
}

/** TCP 传输。用于远程沙箱 / 桌面端协同。 */
class SocketTransport(private val socket: Socket) : RpcTransport {
    private val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))
    private val reader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))
    override val isAlive: Boolean get() = !socket.isClosed && socket.isConnected

    @Synchronized
    override fun send(line: String) {
        writer.write(line); writer.write("\n"); writer.flush()
    }

    override fun readLine(): String? = try {
        reader.readLine()
    } catch (_: Throwable) {
        null
    }

    override fun close() {
        runCatching { socket.close() }
    }
}

/**
 * 双向 JSON-RPC peer。
 *
 * 两个方向：
 *   1) 我们发请求 → 等响应（带超时、带 id 匹配）
 *   2) 对端发请求（host.call / log / emit）→ 我们分发给 [handler] 并回响应
 * 第 2 点让子进程里的插件能主动回调宿主能力，和 in-process 引擎体验一致。
 */
class JsonRpcPeer(
    private val transport: RpcTransport,
    private val handler: (RpcRequest) -> Any?,
    private val onNotification: (RpcRequest) -> Unit = {},
    private val onFatal: (Throwable) -> Unit = {},
) : AutoCloseable {

    private val pending = ConcurrentHashMap<Long, CompletableFuture<RpcResponse>>()
    private val seq = AtomicLong(1)
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "kaleido-rpc-reader").apply { isDaemon = true }
    }
    @Volatile
    private var running = true

    init {
        executor.submit {
            while (running) {
                val line = transport.readLine() ?: break
                if (line.isBlank()) continue
                handleLine(line)
            }
            running = false
            pending.values.forEach { it.completeExceptionally(KaleidoException.Engine("对端连接已关闭")) }
            pending.clear()
        }
    }

    private fun handleLine(line: String) {
        val raw = runCatching { Json.parse(line) as? Map<*, *> }.getOrNull()
            ?: return send(Json.write(mapOf("jsonrpc" to "2.0", "id" to null,
                "error" to mapOf("code" to RpcError.PARSE_ERROR, "message" to "parse error"))))
        @Suppress("UNCHECKED_CAST")
        val m = raw as Map<String, Any?>
        val id = (m["id"] as? Number)?.toLong()
        val method = m["method"] as? String

        if (method != null && id != null) {
            val req = RpcRequest(id, method, m["params"] as? Map<String, Any?> ?: emptyMap())
            val result = try {
                handler(req)
            } catch (t: Throwable) {
                mapOf("__rpcError" to mapOf("code" to RpcError.INTERNAL_ERROR, "message" to (t.message ?: "internal")))
            }
            val errPart = (result as? Map<*, *>)?.get("__rpcError") as? Map<*, *>
            val resp = if (errPart != null)
                mapOf("jsonrpc" to "2.0", "id" to id, "error" to errPart)
            else
                mapOf("jsonrpc" to "2.0", "id" to id, "result" to result)
            send(Json.write(resp))
            return
        }

        if (method != null && id == null) {
            onNotification(RpcRequest(null, method, m["params"] as? Map<String, Any?> ?: emptyMap(), true))
            return
        }

        if (id != null) {
            val errRaw = m["error"] as? Map<*, *>
            val resp = if (errRaw != null) RpcResponse(
                id, null,
                RpcError((errRaw["code"] as? Number)?.toInt() ?: -1, errRaw["message"] as? String ?: "", errRaw["data"])
            ) else RpcResponse(id, m["result"])
            pending.remove(id)?.complete(resp)
        }
    }

    @Synchronized
    private fun send(line: String) = transport.send(line)

    fun request(method: String, params: Map<String, Any?> = emptyMap(), timeoutMs: Int = 30_000): RpcResponse {
        if (!running) throw KaleidoException.Engine("peer 已停止")
        val id = seq.getAndIncrement()
        val fut = CompletableFuture<RpcResponse>()
        pending[id] = fut
        send(Json.write(mapOf("jsonrpc" to "2.0", "id" to id, "method" to method, "params" to params)))
        return try {
            fut.get(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            pending.remove(id)
            throw KaleidoException.Timeout("rpc $method", timeoutMs)
        } catch (e: ExecutionException) {
            throw KaleidoException.Engine("rpc $method 失败: ${e.cause?.message}")
        }
    }

    fun notify(method: String, params: Map<String, Any?> = emptyMap()) {
        send(Json.write(mapOf("jsonrpc" to "2.0", "method" to method, "params" to params)))
    }

    override fun close() {
        running = false
        executor.shutdownNow()
        transport.close()
    }
}
