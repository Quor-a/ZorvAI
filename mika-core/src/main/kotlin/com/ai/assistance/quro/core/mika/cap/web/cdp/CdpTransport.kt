package com.ai.assistance.quro.core.mika.cap.web.cdp

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean

/** 一条双向字节流。关闭后两个方向都结束,阻塞中的读要能因此返回或抛错。 */
interface CdpStream : Closeable {
    val input: InputStream
    val output: OutputStream
}

/**
 * 到 DevTools 端点的字节通道。端点是不透明的字符串(本地抽象套接字名、经 shell 中转的地址等),
 * 由实现自己解释;每次 [open] 都是一条新连接。调用方在 IO 线程上调用,可以阻塞。
 */
fun interface CdpTransport {
    fun open(endpoint: String): CdpStream
}

@Serializable
data class CdpTargetInfo(
    val id: String,
    val type: String,
    val url: String = "",
    val title: String = "",
    /** 已有别的客户端连着这个目标时 DevTools 不给这个字段。 */
    val webSocketDebuggerUrl: String? = null,
)

@Serializable
data class CdpBrowserVersion(
    @SerialName("Browser") val browser: String = "",
    @SerialName("Protocol-Version") val protocolVersion: String = "",
    @SerialName("User-Agent") val userAgent: String = "",
    val webSocketDebuggerUrl: String? = null,
)

/** DevTools 的 HTTP 发现接口:`/json/version`、`/json/list`。每次请求单开一条连接。 */
object CdpDiscovery {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun version(transport: CdpTransport, endpoint: String, host: String = DEFAULT_HOST, timeoutMs: Long = DEFAULT_TIMEOUT_MS): CdpBrowserVersion =
        parseVersion(get(transport, endpoint, "/json/version", host, timeoutMs))

    suspend fun list(transport: CdpTransport, endpoint: String, host: String = DEFAULT_HOST, timeoutMs: Long = DEFAULT_TIMEOUT_MS): List<CdpTargetInfo> =
        parseList(get(transport, endpoint, "/json/list", host, timeoutMs))

    fun parseVersion(body: String): CdpBrowserVersion = json.decodeFromString(body)

    fun parseList(body: String): List<CdpTargetInfo> = json.decodeFromString(body)

    /** `ws://localhost/devtools/page/ID` → `/devtools/page/ID`;传输层不管主机,只要路径。 */
    fun webSocketPath(webSocketUrl: String): String {
        val rest = webSocketUrl.substringAfter("://", webSocketUrl)
        val slash = rest.indexOf('/')
        return if (slash < 0) "/" else rest.substring(slash)
    }

    /** 整个请求至多 [timeoutMs]:到点关掉连接,阻塞中的读写随之返回,以 [CdpTimeoutException] 失败。 */
    suspend fun get(transport: CdpTransport, endpoint: String, path: String, host: String = DEFAULT_HOST, timeoutMs: Long = DEFAULT_TIMEOUT_MS): String =
        withStreamDeadline(timeoutMs, "GET $path", open = { transport.open(endpoint) }) { s ->
            s.use {
                s.output.write("GET $path HTTP/1.1\r\nHost: $host\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
                s.output.flush()
                val input = s.input.buffered()
                val status = Http.readLine(input) ?: throw IOException("no HTTP response for $path")
                val code = status.split(' ').getOrNull(1)?.toIntOrNull()
                val headers = Http.readHeaders(input)
                val body = when {
                    headers["transfer-encoding"]?.contains("chunked", ignoreCase = true) == true -> Http.readChunked(input)
                    headers["content-length"] != null -> {
                        val n = headers.getValue("content-length").trim().toInt()
                        Http.readUpTo(input, n).also { if (it.size < n) throw IOException("HTTP body truncated") }
                    }
                    else -> input.readBytes()
                }
                if (code != 200) throw IOException("GET $path: $status")
                body.toString(Charsets.UTF_8)
            }
        }

    /** DevTools 只接受主机名为 localhost 或 IP 的请求(防 DNS 重绑定)。 */
    const val DEFAULT_HOST = "localhost"
    const val DEFAULT_TIMEOUT_MS = 5_000L
}

private val deadlines = CoroutineScope(SupervisorJob() + Dispatchers.IO)

/**
 * 在 IO 线程上打开流并做一段会阻塞的读写,至多 [timeoutMs]。到点(或调用方被取消,见 closeOnCancel)就关掉流,
 * 阻塞的读写随之抛错返回,不会把调用方和它持有的锁一直卡住。超时以 [CdpTimeoutException] 失败。
 * 阻塞的那段在独立的协程里跑,调用方不用等它结束就能返回。
 */
internal suspend fun <S : Closeable, T> withStreamDeadline(
    timeoutMs: Long,
    what: String,
    open: () -> S,
    /** 调用方被取消时立刻关流;为 false 时让这段读写自己做完(仍受 [timeoutMs] 限制)。 */
    closeOnCancel: Boolean = true,
    block: (S) -> T,
): T {
    val fired = AtomicBoolean(false)
    val opened = java.util.concurrent.atomic.AtomicReference<Closeable?>(null)
    val work = deadlines.async {
        val s = open()
        opened.set(s)
        if (fired.get()) {
            runCatching { s.close() }
            throw CdpTimeoutException(what, timeoutMs)
        }
        block(s)
    }
    val watchdog = deadlines.launch {
        delay(timeoutMs)
        fired.set(true)
        opened.get()?.let { runCatching { it.close() } }
    }
    // 看门狗跟着这段读写走,不跟调用方:调用方先走了,读写仍有上限
    work.invokeOnCompletion { watchdog.cancel() }
    try {
        return work.await()
    } catch (e: CancellationException) {
        if (closeOnCancel) opened.get()?.let { runCatching { it.close() } }
        throw e
    } catch (e: IOException) {
        if (fired.get() && e !is CdpTimeoutException) throw CdpTimeoutException(what, timeoutMs)
        throw e
    }
}

internal object Http {
    private const val MAX_LINE = 16 * 1024

    /** 读一行(去掉 CRLF);流在行首就结束返回 null。 */
    fun readLine(input: InputStream): String? {
        val buf = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) {
                if (buf.size() == 0) return null
                throw IOException("unexpected end of stream in header")
            }
            if (b == '\n'.code) break
            buf.write(b)
            if (buf.size() > MAX_LINE) throw IOException("header line too long")
        }
        val s = String(buf.toByteArray(), Charsets.ISO_8859_1)
        return s.removeSuffix("\r")
    }

    /** 头部名一律转小写。 */
    fun readHeaders(input: InputStream): Map<String, String> {
        val headers = LinkedHashMap<String, String>()
        while (true) {
            val line = readLine(input) ?: throw IOException("unexpected end of stream in headers")
            if (line.isEmpty()) return headers
            val colon = line.indexOf(':')
            if (colon <= 0) throw IOException("bad header line: $line")
            headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
        }
    }

    /**
     * 读满 [n] 字节,流先结束就返回已读到的部分。不用 InputStream.readNBytes:安卓 13 才有,
     * 同样不用 ByteArrayOutputStream.toString(Charset)。
     */
    fun readUpTo(input: InputStream, n: Int): ByteArray {
        val out = ByteArray(n)
        var got = 0
        while (got < n) {
            val r = input.read(out, got, n - got)
            if (r < 0) break
            got += r
        }
        return if (got == n) out else out.copyOf(got)
    }

    fun readChunked(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        while (true) {
            val size = readLine(input)?.substringBefore(';')?.trim()?.toIntOrNull(16) ?: throw IOException("bad chunk size")
            if (size == 0) {
                while (!readLine(input).isNullOrEmpty()) Unit
                return out.toByteArray()
            }
            val chunk = readUpTo(input, size)
            if (chunk.size < size) throw IOException("HTTP chunk truncated")
            out.write(chunk)
            readLine(input)
        }
    }
}
