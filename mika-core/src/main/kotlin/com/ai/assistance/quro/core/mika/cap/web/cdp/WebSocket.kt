package com.ai.assistance.quro.core.mika.cap.web.cdp

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

class WebSocketException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * RFC 6455 客户端,跑在任意 [CdpStream] 上。不协商扩展和子协议。
 * [receive] 只给一个线程调用;写操作内部串行,任何线程都可以发。
 */
class WebSocketConnection private constructor(
    private val stream: CdpStream,
    input: InputStream,
    private val output: OutputStream,
    private val maxMessageBytes: Int,
) : Closeable {
    private val input = DataInputStream(input)
    private val writeLock = Any()
    @Volatile private var closeSent = false
    @Volatile private var aborted = false

    /** 对端关闭帧里的状态码和原因;没收到为 null。 */
    @Volatile var closeCode: Int? = null
        private set
    @Volatile var closeReason: String = ""
        private set

    fun sendText(text: String) {
        if (closeSent) throw WebSocketException("websocket closing")
        writeFrame(OP_TEXT, text.toByteArray(Charsets.UTF_8))
    }

    /**
     * 阻塞到下一条完整的数据消息。ping 自动回 pong,分片自动拼接。
     * 对端发关闭帧时回一个关闭帧并返回 null。
     */
    fun receive(): String? {
        var assembling: ByteArrayOutputStream? = null
        while (true) {
            val b0 = input.read()
            if (b0 < 0) throw WebSocketException("connection dropped")
            val b1 = input.readUnsignedByte()
            val fin = b0 and 0x80 != 0
            val opcode = b0 and 0x0f
            if (b0 and 0x70 != 0) protocolError("reserved bits set")
            if (b1 and 0x80 != 0) protocolError("server frame is masked")
            val len = when (val l = b1 and 0x7f) {
                126 -> input.readUnsignedShort().toLong()
                127 -> input.readLong().also { if (it < 0) protocolError("bad frame length") }
                else -> l.toLong()
            }
            if (opcode >= 0x8) {
                if (!fin || len > 125) protocolError("bad control frame")
                val payload = ByteArray(len.toInt()).also { input.readFully(it) }
                when (opcode) {
                    OP_PING -> runCatching { writeFrame(OP_PONG, payload) }
                    OP_PONG -> Unit
                    OP_CLOSE -> {
                        if (payload.size >= 2) {
                            closeCode = ((payload[0].toInt() and 0xff) shl 8) or (payload[1].toInt() and 0xff)
                            closeReason = payload.copyOfRange(2, payload.size).toString(Charsets.UTF_8)
                        } else {
                            closeCode = CLOSE_NO_STATUS
                        }
                        if (!closeSent) runCatching { sendClose(if (payload.size >= 2) closeCode!! else CLOSE_NORMAL) }
                        abort()
                        return null
                    }
                    else -> protocolError("unknown opcode $opcode")
                }
                continue
            }
            when (opcode) {
                OP_CONTINUATION -> if (assembling == null) protocolError("unexpected continuation")
                OP_TEXT, OP_BINARY -> if (assembling != null) protocolError("new message inside fragmented message")
                else -> protocolError("unknown opcode $opcode")
            }
            val total = (assembling?.size()?.toLong() ?: 0L) + len
            if (total > maxMessageBytes) {
                runCatching { sendClose(CLOSE_TOO_BIG) }
                abort()
                throw WebSocketException("message larger than $maxMessageBytes bytes")
            }
            val payload = ByteArray(len.toInt()).also { input.readFully(it) }
            if (fin && assembling == null) return payload.toString(Charsets.UTF_8)
            if (assembling == null) assembling = ByteArrayOutputStream(maxOf(payload.size * 2, 1024))
            assembling.write(payload)
            if (fin) return assembling.toString(Charsets.UTF_8)
        }
    }

    /** 发关闭帧;之后对端回的关闭帧由 [receive] 收走。只发一次。 */
    fun sendClose(code: Int = CLOSE_NORMAL, reason: String = "") {
        val r = reason.toByteArray(Charsets.UTF_8).let { if (it.size > 123) it.copyOf(123) else it }
        synchronized(writeLock) {
            if (closeSent) return
            closeSent = true
            writeFrameLocked(OP_CLOSE, byteArrayOf((code shr 8).toByte(), code.toByte()) + r)
        }
    }

    /** 直接断开底层流,阻塞中的 [receive] 会抛错。 */
    fun abort() {
        aborted = true
        runCatching { stream.close() }
    }

    override fun close() {
        if (!aborted) runCatching { sendClose() }
        abort()
    }

    private fun writeFrame(opcode: Int, payload: ByteArray) {
        synchronized(writeLock) { writeFrameLocked(opcode, payload) }
    }

    private fun writeFrameLocked(opcode: Int, payload: ByteArray) {
        val n = payload.size
        val header = ByteArray(14)
        var h = 0
        header[h++] = (0x80 or opcode).toByte()
        when {
            n <= 125 -> header[h++] = (0x80 or n).toByte()
            n <= 0xffff -> {
                header[h++] = (0x80 or 126).toByte()
                header[h++] = (n shr 8).toByte()
                header[h++] = n.toByte()
            }
            else -> {
                header[h++] = (0x80 or 127).toByte()
                for (shift in 56 downTo 0 step 8) header[h++] = (n.toLong() shr shift).toByte()
            }
        }
        val mask = ByteArray(4).also { random.nextBytes(it) }
        mask.copyInto(header, h)
        h += 4
        val masked = ByteArray(n)
        for (i in 0 until n) masked[i] = (payload[i].toInt() xor mask[i and 3].toInt()).toByte()
        output.write(header, 0, h)
        output.write(masked)
        output.flush()
    }

    private fun protocolError(what: String): Nothing {
        runCatching { sendClose(CLOSE_PROTOCOL_ERROR) }
        abort()
        throw WebSocketException("websocket protocol error: $what")
    }

    companion object {
        const val DEFAULT_MAX_MESSAGE_BYTES = 64 * 1024 * 1024
        const val CLOSE_NORMAL = 1000
        const val CLOSE_PROTOCOL_ERROR = 1002
        const val CLOSE_NO_STATUS = 1005
        const val CLOSE_TOO_BIG = 1009

        private const val OP_CONTINUATION = 0x0
        private const val OP_TEXT = 0x1
        private const val OP_BINARY = 0x2
        private const val OP_CLOSE = 0x8
        private const val OP_PING = 0x9
        private const val OP_PONG = 0xa
        private const val GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
        private val random = SecureRandom()

        fun acceptFor(key: String): String =
            Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1").digest((key + GUID).toByteArray(Charsets.US_ASCII)))

        /** 在 [stream] 上做升级握手;失败时关闭流并抛 [WebSocketException]。阻塞。 */
        fun connect(
            stream: CdpStream,
            path: String,
            host: String = CdpDiscovery.DEFAULT_HOST,
            maxMessageBytes: Int = DEFAULT_MAX_MESSAGE_BYTES,
        ): WebSocketConnection {
            try {
                val key = Base64.getEncoder().encodeToString(ByteArray(16).also { random.nextBytes(it) })
                val req = "GET $path HTTP/1.1\r\nHost: $host\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                    "Sec-WebSocket-Key: $key\r\nSec-WebSocket-Version: 13\r\n\r\n"
                stream.output.write(req.toByteArray(Charsets.US_ASCII))
                stream.output.flush()
                // 握手后的帧可能已经进了缓冲,之后都从这个缓冲流读
                val input = stream.input.buffered(64 * 1024)
                val status = Http.readLine(input) ?: throw WebSocketException("no handshake response")
                if (status.split(' ').getOrNull(1) != "101") throw WebSocketException("handshake rejected: $status")
                val headers = Http.readHeaders(input)
                if (!headers["upgrade"].equals("websocket", ignoreCase = true) ||
                    headers["connection"]?.split(',')?.none { it.trim().equals("upgrade", ignoreCase = true) } != false
                ) {
                    throw WebSocketException("handshake missing upgrade headers")
                }
                if (headers["sec-websocket-accept"] != acceptFor(key)) throw WebSocketException("bad Sec-WebSocket-Accept")
                if (headers["sec-websocket-extensions"] != null) throw WebSocketException("unexpected websocket extension")
                return WebSocketConnection(stream, input, stream.output, maxMessageBytes)
            } catch (e: IOException) {
                runCatching { stream.close() }
                throw e as? WebSocketException ?: WebSocketException("handshake failed: ${e.message}", e)
            }
        }
    }
}
