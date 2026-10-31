package com.ai.assistance.quro.core.adb

import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * ADB 客户端：在一条 [AdbConnection] 上提供 shell / 文件传输 / 安装等能力。
 *
 * shell 用 v1（`shell:<cmd>`），兼容性最好：所有 Android 版本都支持。
 * v2 能拿到**退出码**，但需要解析带 stream-id 的分包，且老设备不支持；
 * 这里改用「命令后追加哨兵回显」的方式拿退出码，简单且跨版本稳定。
 */
class QuroAdbClient(private val connection: AdbConnection) : Closeable {

    val transportName: String get() = connection.transportName

    /**
     * 执行 shell 命令，返回合并后的输出（stdout + stderr）。
     *
     * @param withExitCode 为 true 时在末尾追加 `[exit=N]`（靠哨兵实现，不改协议）
     */
    fun shell(command: String, timeoutMs: Long = 60_000, withExitCode: Boolean = true): String {
        val sentinel = "__QURO_EXIT_${System.nanoTime()}__"
        val actual = if (withExitCode) "$command; echo $sentinel\$?" else command
        val stream = connection.open("shell:$actual")
        val raw = try {
            stream.readAll(timeoutMs)
        } finally {
            runCatching { stream.close() }
        }
        val text = String(raw, Charsets.UTF_8)
        if (!withExitCode) return text

        // 把哨兵行摘出来，还原成 [exit=N]
        val idx = text.lastIndexOf(sentinel)
        if (idx < 0) return text
        val body = text.substring(0, idx).trimEnd('\n', '\r')
        val code = text.substring(idx + sentinel.length).trim().takeWhile { it.isDigit() || it == '-' }
        return if (body.isEmpty()) "[exit=$code]" else "$body\n[exit=$code]"
    }

    /** 只取退出码。 */
    fun shellExitCode(command: String, timeoutMs: Long = 60_000): Int {
        val out = shell(command, timeoutMs, withExitCode = true)
        val m = Regex("\\[exit=(-?\\d+)]").find(out) ?: return -1
        return m.groupValues[1].toIntOrNull() ?: -1
    }

    // ───────────────────────── sync（push / pull）─────────────────────────

    /** 把本地文件推到设备。 */
    fun push(localFile: File, remotePath: String, timeoutMs: Long = 300_000) {
        require(localFile.isFile) { "不是文件：${localFile.absolutePath}" }
        val stream = connection.open("sync:")
        try {
            val reader = SyncReader(stream, timeoutMs)
            val spec = "$remotePath,${if (localFile.canExecute()) 0x1ED else 0x1A4}"
            writeSyncRequest(stream, "SEND", spec.toByteArray(Charsets.UTF_8).size)
            stream.write(spec.toByteArray(Charsets.UTF_8))

            localFile.inputStream().use { input ->
                val buf = ByteArray(SYNC_CHUNK)
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    writeSyncRequest(stream, "DATA", n)
                    stream.write(buf.copyOf(n))
                }
            }
            writeSyncRequest(stream, "DONE", 0)
            reader.expectOkay(remotePath)
        } finally {
            runCatching { stream.close() }
        }
    }

    /** 从设备拉取文件到本地。 */
    fun pull(remotePath: String, localFile: File, timeoutMs: Long = 300_000) {
        val stream = connection.open("sync:")
        try {
            val reader = SyncReader(stream, timeoutMs)
            writeSyncRequest(stream, "RECV", remotePath.toByteArray(Charsets.UTF_8).size)
            stream.write(remotePath.toByteArray(Charsets.UTF_8))

            localFile.parentFile?.mkdirs()
            localFile.outputStream().use { out ->
                while (true) {
                    val (id, length) = reader.readHeader()
                    when (id) {
                        "DATA" -> out.write(reader.readExactly(length))
                        "DONE" -> return
                        "FAIL" -> {
                            val reason = String(reader.readExactly(length), Charsets.UTF_8)
                            throw AdbConnectException("拉取失败：$remotePath（$reason）")
                        }
                        else -> throw AdbProtocolException("sync 未知响应：$id")
                    }
                }
            }
        } finally {
            runCatching { stream.close() }
        }
    }

    /** 列目录（用 shell 实现，比 sync LIST 兼容性更好）。 */
    fun list(remotePath: String): String =
        shell("ls -al '$remotePath'", withExitCode = false)

    /** 安装 APK（`pm install`，走 shell，避免依赖设备端 pm 的包安装服务）。 */
    fun install(localApk: File, reinstall: Boolean = true, timeoutMs: Long = 300_000): String {
        val remote = "/data/local/tmp/quro_install_${System.currentTimeMillis()}.apk"
        push(localApk, remote, timeoutMs)
        val flags = if (reinstall) "-r" else ""
        val out = shell("pm install $flags '$remote'; rm -f '$remote'", timeoutMs)
        Log.i(TAG, "install 结果：${out.take(200)}")
        return out
    }

    override fun close() {
        connection.close()
    }

    // ───────────────────────── 内部 ─────────────────────────

    private fun writeSyncRequest(stream: AdbStream, id: String, length: Int) {
        val buf = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(id.toByteArray(Charsets.US_ASCII))
        buf.putInt(length)
        stream.write(buf.array())
    }

    /**
     * sync 流的定长读取器。
     *
     * AdbStream 给的是 WRTE 的 payload 块，边界与 sync 帧边界**不对齐**
     * （一个块里可能含「头 + 数据 + 下一个头」），所以必须自己攒字节，
     * 且把用剩的尾巴留给下一次读，否则会丢数据或错位。
     */
    private class SyncReader(private val stream: AdbStream, private val timeoutMs: Long) {
        private var pending: ByteArray = EMPTY
        private var pendingOffset = 0

        fun readExactly(n: Int): ByteArray {
            val out = ByteArray(n)
            var off = 0
            while (off < n) {
                if (pendingOffset >= pending.size) {
                    val chunk = stream.read(timeoutMs)
                        ?: throw AdbConnectException("sync 流提前关闭（需要 $n 字节，只拿到 $off）")
                    if (chunk.isEmpty()) continue
                    pending = chunk
                    pendingOffset = 0
                }
                val take = minOf(pending.size - pendingOffset, n - off)
                System.arraycopy(pending, pendingOffset, out, off, take)
                pendingOffset += take
                off += take
            }
            return out
        }

        /** 读 8 字节头：4 字节 ASCII id + 4 字节小端长度。 */
        fun readHeader(): Pair<String, Int> {
            val head = readExactly(8)
            val id = String(head, 0, 4, Charsets.US_ASCII)
            val len = ByteBuffer.wrap(head, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
            return id to len
        }

        fun expectOkay(path: String) {
            val (id, len) = readHeader()
            when (id) {
                "OKAY" -> return
                "FAIL" -> {
                    val reason = String(readExactly(len), Charsets.UTF_8)
                    throw AdbConnectException("推送失败：$path（$reason）")
                }
                else -> throw AdbProtocolException("sync 期望 OKAY，实际收到 $id")
            }
        }

        private companion object {
            val EMPTY = ByteArray(0)
        }
    }

    private companion object {
        const val TAG = "QuroAdbClient"
        const val SYNC_CHUNK = 64 * 1024
    }
}
