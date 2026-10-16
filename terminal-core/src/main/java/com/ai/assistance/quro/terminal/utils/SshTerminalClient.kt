package com.ai.assistance.quro.terminal.utils

import android.content.Context
import android.util.Log
import org.apache.sshd.client.SshClient
import org.apache.sshd.client.channel.ChannelShell
import org.apache.sshd.client.keyverifier.AcceptAllServerKeyVerifier
import org.apache.sshd.client.session.ClientSession
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * 内置 SSH 客户端：让「已连接」在 App 内**真的有界面**。
 *
 * ## 为什么需要它
 * 光有服务端 + 一行 `ssh root@127.0.0.1 -p 2222` 的连接串，用户在本机只能看到
 * 「已连接」三个字 —— 原话是「界面连接上界面都没有连接个屁」。
 * 本客户端连本机 2222，把服务端吐出的字节喂给已有的
 * [com.ai.assistance.quro.terminal.domain.ansi.AnsiTerminalEmulator]（与本地终端同一套
 * 解析/渲染），于是本机就能直接看到登录 shell，同时它也是 SSH 通路的**自检**。
 *
 * ## 要点
 * - 服务端 host key 是自签的：接受任意 host key（仅回环自连，无中间人面）。
 * - PTY：显式 `xterm` + 列/行数；窗口尺寸变化时发 `window-change`，避免布局错乱。
 * - 发往服务端的字节走 [QueueInputStream]（`setIn` 需要一个阻塞的 InputStream），
 *   键盘输入在任意线程写入都安全。
 * - UTF-8 跨读边界会被切开：这里做**残字节保留**，否则中文会变乱码。
 * - 全程只有一处 `catch (e: Throwable)` 出口 —— MINA 在 Android 上可能抛 `Error`。
 */
class SshTerminalClient(
    context: Context,
    private val host: String,
    private val port: Int,
    private val user: String,
    private val password: String,
    private val listener: Listener,
) {

    interface Listener {
        /** 连接阶段进度。 */
        fun onStatus(text: String)

        /** 服务端输出（已按 UTF-8 解码），交给终端模拟器 parse。 */
        fun onOutput(text: String)

        /** 失败（一句话原因）。 */
        fun onFailure(message: String)

        /** 会话结束（正常退出或对端断开）。 */
        fun onClosed()
    }

    private val appContext = context.applicationContext

    @Volatile
    private var running = false

    @Volatile
    private var client: SshClient? = null

    @Volatile
    private var session: ClientSession? = null

    @Volatile
    private var channel: ChannelShell? = null

    private val stdin = QueueInputStream()

    @Volatile
    private var lastCols = 0

    @Volatile
    private var lastRows = 0

    private val pending = java.io.ByteArrayOutputStream()

    fun isRunning(): Boolean = running

    fun connect(cols: Int = DEFAULT_COLS, rows: Int = DEFAULT_ROWS) {
        if (running) return
        running = true
        Thread({
            try {
                runSession(cols, rows)
                if (running) listener.onClosed()
            } catch (e: Throwable) {
                if (running) {
                    val msg = e.message ?: e.javaClass.simpleName
                    Log.w(TAG, "SSH 会话结束：$msg", e)
                    listener.onFailure(describe(e))
                }
            } finally {
                running = false
                closeQuietly()
            }
        }, "ssh-terminal-client").apply { isDaemon = true; start() }
    }

    fun close() {
        running = false
        closeQuietly()
    }

    /** 键盘输入 → 服务端。 */
    fun send(data: String) {
        if (!running || data.isEmpty()) return
        stdin.offer(data.toByteArray(Charsets.UTF_8))
    }

    /** 终端尺寸变化（界面缩放/旋转时调用）。 */
    fun resize(cols: Int, rows: Int) {
        if (cols <= 0 || rows <= 0) return
        if (cols == lastCols && rows == lastRows) return
        lastCols = cols
        lastRows = rows
        val ch = channel ?: return
        runCatching { ch.sendWindowChange(cols, rows) }
    }

    // ------------------------------------------------------------------ 内部

    private fun runSession(cols: Int, rows: Int) {
        // 必须在任何 MINA 类加载前补 user.home（见 SshAndroidCompat 注释）
        SshAndroidCompat.ensure(appContext)

        listener.onStatus("正在连接 $user@$host:$port …")
        val ssh = SshClient.setUpDefaultClient()
        // 自签 host key + 仅回环自连：接受任意 host key
        ssh.setServerKeyVerifier(AcceptAllServerKeyVerifier.INSTANCE)
        ssh.start()
        client = ssh

        val connectFuture = ssh.connect(user, host, port, null, null)
        val sess = connectFuture.verify(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS).session
        session = sess

        listener.onStatus("正在认证 …")
        sess.addPasswordIdentity(password)
        val auth = sess.auth()
        auth.verify(AUTH_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        if (!auth.isSuccess) {
            throw IllegalStateException("认证失败（用户名/密码不对）")
        }

        listener.onStatus("正在打开终端 …")
        val shell = sess.createShellChannel()
        shell.setPtyType("xterm")
        shell.setPtyColumns(cols)
        shell.setPtyLines(rows)
        shell.setOut(outputStream())
        shell.setErr(outputStream())
        shell.setIn(stdin)
        shell.open().verify(OPEN_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        channel = shell
        lastCols = cols
        lastRows = rows

        listener.onStatus("已连接")

        // 阻塞直到通道关闭或 stop() 拉闸
        while (running && !shell.isClosed) {
            Thread.sleep(200)
        }
        if (running) {
            listener.onStatus("连接已被对端关闭")
        }
    }

    private fun outputStream(): OutputStream = object : OutputStream() {
        override fun write(b: Int) {
            feed(byteArrayOf(b.toByte()), 0, 1)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            feed(b, off, len)
        }
    }

    /**
     * 把服务端字节流按 UTF-8 解码后交给界面，**保留结尾不完整的多字节序列**，
     * 否则中文/emoji 会在读边界被切碎成乱码。
     */
    private fun feed(bytes: ByteArray, off: Int, len: Int) {
        syncPending {
            pending.write(bytes, off, len)
            val all = pending.toByteArray()
            val keep = incompleteTailLength(all)
            val usable = all.size - keep
            if (usable <= 0) return@syncPending
            val text = String(all, 0, usable, Charsets.UTF_8)
            pending.reset()
            if (keep > 0) pending.write(all, usable, keep)
            if (text.isNotEmpty()) listener.onOutput(text)
        }
    }

    private fun syncPending(block: () -> Unit) {
        synchronized(pending) { block() }
    }

    private fun closeQuietly() {
        runCatching { channel?.close(false) }
        runCatching { session?.close(false) }
        runCatching { client?.stop() }
        channel = null
        session = null
        client = null
        runCatching { stdin.closeInput() }
    }

    private fun describe(e: Throwable): String {
        var t: Throwable? = e
        val seen = mutableSetOf<Throwable>()
        while (t != null && seen.add(t)) {
            val msg = t.message
            if (!msg.isNullOrBlank()) return "${t.javaClass.simpleName}：$msg"
            t = t.cause
        }
        return e.javaClass.simpleName
    }

    /** `setIn` 需要一个「有数据才返回、没数据就阻塞」的 InputStream。 */
    private class QueueInputStream : InputStream() {

        private val queue = LinkedBlockingQueue<ByteArray>()

        @Volatile
        private var closed = false

        private var current: ByteArray? = null
        private var pos = 0

        fun offer(data: ByteArray) {
            if (!closed && data.isNotEmpty()) queue.offer(data)
        }

        fun closeInput() {
            if (closed) return
            closed = true
            queue.offer(EMPTY)
        }

        override fun read(): Int {
            val one = ByteArray(1)
            val n = read(one, 0, 1)
            return if (n <= 0) -1 else one[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            while (true) {
                val cur = current
                if (cur != null && pos < cur.size) {
                    val n = minOf(len, cur.size - pos)
                    System.arraycopy(cur, pos, b, off, n)
                    pos += n
                    if (pos >= cur.size) {
                        current = null
                        pos = 0
                    }
                    return n
                }
                if (closed && queue.isEmpty()) return -1
                val next = try {
                    queue.take()
                } catch (_: InterruptedException) {
                    return -1
                }
                if (next.isEmpty()) {
                    if (closed) return -1
                    continue
                }
                current = next
                pos = 0
            }
        }

        private companion object {
            private val EMPTY = ByteArray(0)
        }
    }

    private companion object {
        private const val TAG = "SshTerminalClient"

        private const val DEFAULT_COLS = 80
        private const val DEFAULT_ROWS = 24

        private const val CONNECT_TIMEOUT_MS = 10_000L
        private const val AUTH_TIMEOUT_MS = 10_000L
        private const val OPEN_TIMEOUT_MS = 10_000L

        /**
         * 结尾处「不完整的 UTF-8 序列」长度（0~3）。
         * 返回 >0 表示这几个字节要留到下一批再解码。
         */
        private fun incompleteTailLength(b: ByteArray): Int {
            if (b.isEmpty()) return 0
            var continua = 0
            var i = b.size - 1
            while (i >= 0 && (b[i].toInt() and 0xC0) == 0x80 && continua < 3) {
                continua++
                i--
            }
            if (i < 0) return 0
            val lead = b[i].toInt() and 0xFF
            val need = when {
                lead and 0x80 == 0x00 -> 1
                lead and 0xE0 == 0xC0 -> 2
                lead and 0xF0 == 0xE0 -> 3
                lead and 0xF8 == 0xF0 -> 4
                else -> 1
            }
            val have = continua + 1
            return if (have < need) have else 0
        }
    }
}
