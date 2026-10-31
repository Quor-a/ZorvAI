package com.ai.assistance.quro.core.adb

import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * ADB 协议常量与 24 字节报文编解码。
 *
 * 依据（一手资料）：
 *  - AOSP `packages/modules/adb/protocol.txt`：报文格式与命令语义
 *  - AOSP `packages/modules/adb/adb.h`：命令常量实际取值
 *
 * 所有多字节字段均为**小端**。`magic` 必须等于 `command xor 0xFFFFFFFF`，
 * 校验不过说明流已错位，必须断开重连（协议依赖共享状态，不能跳过继续读）。
 */
object AdbProtocol {

    // ── 命令 ──
    const val CMD_CNXN = 0x4e584e43   // "CNXN"
    const val CMD_AUTH = 0x48545541   // "AUTH"
    const val CMD_OPEN = 0x4e45504f   // "OPEN"
    const val CMD_OKAY = 0x59414b4f   // "OKAY"
    const val CMD_WRTE = 0x45545257   // "WRTE"
    const val CMD_CLSE = 0x45534c43   // "CLSE"
    const val CMD_STLS = 0x534c5453   // "STLS"（TLS 升级，无线调试用）

    // ── AUTH 子类型 ──
    const val AUTH_TOKEN = 1
    const val AUTH_SIGNATURE = 2
    const val AUTH_RSAPUBLICKEY = 3

    /** 协议版本：0x01000000 兼容面最广（0x01000001+ 会忽略 checksum）。 */
    const val VERSION = 0x01000000

    /** 我们愿意接收的最大 payload。老设备会给 256KiB，最终取双方最小值。 */
    const val MAX_PAYLOAD = 1024 * 1024

    /** 旧版不带 features 的 banner；带 features 更好，但空 banner 兼容性最稳。 */
    const val DEFAULT_BANNER = "host::"

    fun magicOf(command: Int): Int = command xor -1

    fun nameOf(command: Int): String = when (command) {
        CMD_CNXN -> "CNXN"
        CMD_AUTH -> "AUTH"
        CMD_OPEN -> "OPEN"
        CMD_OKAY -> "OKAY"
        CMD_WRTE -> "WRTE"
        CMD_CLSE -> "CLSE"
        CMD_STLS -> "STLS"
        else -> "0x${Integer.toHexString(command)}"
    }
}

/**
 * 一条 ADB 消息。
 *
 * [arg0]/[arg1] 的含义随 [command] 变化，且 **local/remote id 永远相对发送方**：
 * 收到 OKAY(localId=X, remoteId=Y) 时，X 是**对方**的流 id，Y 是**我们的**流 id。
 */
data class AdbMessage(
    val command: Int,
    val arg0: Int,
    val arg1: Int,
    val payload: ByteArray = EMPTY,
) {
    val name: String get() = AdbProtocol.nameOf(command)

    /** OPEN 的 payload 以 NUL 结尾，转服务名时去掉。 */
    val serviceName: String
        get() = String(payload, Charsets.UTF_8).trimEnd('\u0000')

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AdbMessage) return false
        return command == other.command && arg0 == other.arg0 && arg1 == other.arg1 &&
            payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int {
        var result = command
        result = 31 * result + arg0
        result = 31 * result + arg1
        result = 31 * result + payload.contentHashCode()
        return result
    }

    companion object {
        val EMPTY = ByteArray(0)
    }
}

/** 报文读取器。线程安全性由调用方保证（AdbConnection 只有一个读线程）。 */
class AdbMessageReader(private val input: InputStream) {

    /**
     * 读一条完整消息。
     *
     * @throws EOFException 对端正常关闭
     * @throws AdbProtocolException magic 校验失败（流已错位，必须重连）
     */
    fun read(): AdbMessage {
        val header = ByteArray(24)
        readFully(header)

        val buf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        val command = buf.int
        val arg0 = buf.int
        val arg1 = buf.int
        val dataLength = buf.int
        @Suppress("UNUSED_VARIABLE") val checksum = buf.int
        val magic = buf.int

        if (magic != AdbProtocol.magicOf(command)) {
            throw AdbProtocolException(
                "报文 magic 校验失败：${AdbProtocol.nameOf(command)} magic=0x${Integer.toHexString(magic)}"
            )
        }
        if (dataLength < 0 || dataLength > AdbProtocol.MAX_PAYLOAD) {
            throw AdbProtocolException("payload 长度异常：$dataLength")
        }

        val payload = if (dataLength == 0) AdbMessage.EMPTY else ByteArray(dataLength).also { readFully(it) }
        return AdbMessage(command, arg0, arg1, payload)
    }

    private fun readFully(dst: ByteArray) {
        var off = 0
        while (off < dst.size) {
            val n = input.read(dst, off, dst.size - off)
            if (n < 0) throw EOFException("ADB 连接已关闭（已读 $off/${dst.size} 字节）")
            off += n
        }
    }
}

/** 报文写入器。内部加锁，允许多线程写（WRTE 的 OKAY 节流在 AdbStream 层做）。 */
class AdbMessageWriter(private val output: OutputStream) {

    private val lock = Any()

    fun write(command: Int, arg0: Int, arg1: Int, payload: ByteArray = AdbMessage.EMPTY) {
        val header = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
        header.putInt(command)
        header.putInt(arg0)
        header.putInt(arg1)
        header.putInt(payload.size)
        // data_check 填 0：adbd 对 0 不做校验，兼容所有版本（dadb 同样处理）
        header.putInt(0)
        header.putInt(AdbProtocol.magicOf(command))

        synchronized(lock) {
            output.write(header.array())
            if (payload.isNotEmpty()) output.write(payload)
            output.flush()
        }
    }

    fun connect(banner: String = AdbProtocol.DEFAULT_BANNER, maxPayload: Int = AdbProtocol.MAX_PAYLOAD) {
        val payload = (banner + "\u0000").toByteArray(Charsets.UTF_8)
        write(AdbProtocol.CMD_CNXN, AdbProtocol.VERSION, maxPayload, payload)
    }

    fun auth(type: Int, authPayload: ByteArray) {
        write(AdbProtocol.CMD_AUTH, type, 0, authPayload)
    }

    fun open(localId: Int, destination: String) {
        val body = destination.toByteArray(Charsets.UTF_8)
        val payload = ByteArray(body.size + 1)
        System.arraycopy(body, 0, payload, 0, body.size)
        write(AdbProtocol.CMD_OPEN, localId, 0, payload)
    }

    fun okay(localId: Int, remoteId: Int) {
        write(AdbProtocol.CMD_OKAY, localId, remoteId)
    }

    fun writeStream(localId: Int, remoteId: Int, data: ByteArray) {
        write(AdbProtocol.CMD_WRTE, localId, remoteId, data)
    }

    fun close(localId: Int, remoteId: Int) {
        write(AdbProtocol.CMD_CLSE, localId, remoteId)
    }

    fun stls(version: Int = 1) {
        write(AdbProtocol.CMD_STLS, version, 0)
    }
}

/** 协议层错误（错位、非法命令等），一律不可继续使用该连接。 */
class AdbProtocolException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** 连接/握手失败。 */
class AdbConnectException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** 认证失败：通常是设备上没点「允许 USB 调试」，或公钥未被信任。 */
class AdbAuthException(message: String, cause: Throwable? = null) : Exception(message, cause)
