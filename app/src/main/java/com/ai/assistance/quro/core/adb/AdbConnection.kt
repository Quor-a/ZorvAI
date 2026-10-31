package com.ai.assistance.quro.core.adb

import android.util.Log
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 一条 ADB 连接（对应 adbd 的一个 transport）。
 *
 * 职责：
 *  - 握手：CNXN → AUTH（RSA）→ CNXN
 *  - 多流复用：一条连接上并发多条 stream（shell / sync / tcp）
 *  - 消息分发：OKAY / WRTE / CLSE 路由到对应 stream
 *
 * **协议节流硬约束**：WRTE 之后必须等到 OKAY 才能发下一个 WRTE。违反会被 adbd 直接 CLSE。
 * 这里用每条 stream 一个 [Semaphore] 实现，写操作天然串行。
 */
class AdbConnection(
    private val transport: AdbTransport,
    private val keyPair: AdbKeyPair?,
) : Closeable {

    private val reader = AdbMessageReader(transport.input)
    private val writer = AdbMessageWriter(transport.output)

    private val streams = HashMap<Int, AdbStream>()
    private val streamsLock = Any()
    private val nextLocalId = AtomicInteger(1)

    @Volatile
    private var closed = false

    private var readThread: Thread? = null

    /** 设备声明的 features（决定能不能用 shell v2、stat_v2 等）。 */
    var deviceFeatures: Set<String> = emptySet()
        private set

    /** 协商后的最大 payload。 */
    var maxPayload: Int = 4096
        private set

    val transportName: String get() = transport.name

    val isClosed: Boolean get() = closed

    /**
     * 完成握手与认证，然后启动读线程。
     *
     * @throws AdbAuthException 设备要求认证但我们没有密钥，或用户没在设备上点「允许」
     */
    fun connect() {
        writer.connect()

        var done = false
        var authRounds = 0
        while (!done) {
            val msg = try {
                reader.read()
            } catch (t: Throwable) {
                throw AdbConnectException("握手失败（读报文出错）：${t.message}", t)
            }

            when (msg.command) {
                AdbProtocol.CMD_CNXN -> {
                    maxPayload = minOf(msg.arg1.takeIf { it > 0 } ?: 4096, AdbProtocol.MAX_PAYLOAD)
                    deviceFeatures = parseFeatures(msg.payload)
                    Log.i(TAG, "已连接 ${transport.name} maxPayload=$maxPayload features=$deviceFeatures")
                    done = true
                }

                AdbProtocol.CMD_AUTH -> {
                    authRounds++
                    if (authRounds > 3) {
                        throw AdbAuthException(
                            "设备反复要求认证，已放弃。请在手机上确认已勾选「始终允许来自这台计算机的调试」后再试。"
                        )
                    }
                    authRounds = performAuth(msg, authRounds)
                    // performAuth 内部读完后续报文；若认证通过会返回一个「已结束」信号
                    if (authRounds < 0) done = true
                }

                else -> throw AdbConnectException("握手收到意外报文：${msg.name}")
            }
        }

        startReadLoop()
    }

    /**
     * 处理一轮 AUTH，返回下一轮计数；若已认证通过返回 -1。
     *
     * 流程（与 AOSP adb_auth_host.cpp 一致）：
     * 1. 收到 AUTH(TOKEN) → 用私钥签 token，回 AUTH(SIGNATURE)
     * 2. 设备还不认识我们 → 再收 AUTH(TOKEN) → 回 AUTH(RSAPUBLICKEY)，设备弹「允许调试」
     * 3. 用户点允许 → 收 CNXN；用户拒绝 → 再收 AUTH
     */
    private fun performAuth(tokenMsg: AdbMessage, round: Int): Int {
        val key = keyPair ?: throw AdbAuthException(
            "设备要求认证，但本地没有 ADB 密钥"
        )
        if (tokenMsg.arg0 != AdbProtocol.AUTH_TOKEN) {
            throw AdbProtocolException("不支持的 AUTH 类型：${tokenMsg.arg0}")
        }

        val signature = key.signToken(tokenMsg.payload)
        writer.auth(AdbProtocol.AUTH_SIGNATURE, signature)

        var msg = reader.read()
        if (msg.command == AdbProtocol.CMD_CNXN) {
            maxPayload = minOf(msg.arg1.takeIf { it > 0 } ?: 4096, AdbProtocol.MAX_PAYLOAD)
            deviceFeatures = parseFeatures(msg.payload)
            return -1
        }

        if (msg.command != AdbProtocol.CMD_AUTH) {
            throw AdbConnectException("认证过程中收到意外报文：${msg.name}")
        }

        // 设备不认识这把钥匙，上报公钥，等用户在设备上点「允许」
        writer.auth(AdbProtocol.AUTH_RSAPUBLICKEY, key.publicKeyPayload)
        Log.i(TAG, "已上报公钥，等待设备端确认（请在手机上点「允许」）")

        msg = reader.read()
        if (msg.command == AdbProtocol.CMD_CNXN) {
            maxPayload = minOf(msg.arg1.takeIf { it > 0 } ?: 4096, AdbProtocol.MAX_PAYLOAD)
            deviceFeatures = parseFeatures(msg.payload)
            return -1
        }

        if (msg.command == AdbProtocol.CMD_AUTH) {
            throw AdbAuthException(
                "设备拒绝了这台主机的公钥。请在手机「开发者选项 → 撤销 USB 调试授权」后重插数据线重试，" +
                    "或确认弹出的「允许 USB 调试」对话框已点「允许」。"
            )
        }

        throw AdbConnectException("认证后收到意外报文：${msg.name}")
    }

    private fun parseFeatures(payload: ByteArray): Set<String> {
        // 形如 device::ro.product.name=xxx;features=shell_v2,cmd,stat_v2
        val text = String(payload, Charsets.UTF_8).trimEnd('\u0000')
        val features = text.substringAfter("features=", "")
        if (features.isBlank()) return emptySet()
        return features.substringBefore(';').split(',').filter { it.isNotBlank() }.toSet()
    }

    private fun startReadLoop() {
        val t = Thread({
            try {
                while (!closed) {
                    val msg = reader.read()
                    dispatch(msg)
                }
            } catch (t: Throwable) {
                if (!closed) Log.w(TAG, "读线程结束：${t.message}")
            } finally {
                failAllStreams()
                close()
            }
        }, "adb-read-${transport.name}")
        t.isDaemon = true
        t.start()
        readThread = t
    }

    private fun dispatch(msg: AdbMessage) {
        when (msg.command) {
            AdbProtocol.CMD_OKAY -> {
                // 对方 OKAY(local=对方id, remote=我们id)
                findStream(msg.arg1)?.onOkay(msg.arg0)
            }

            AdbProtocol.CMD_WRTE -> {
                val stream = findStream(msg.arg1)
                stream?.onData(msg.payload)
                // 无论 stream 是否还在，都要回 OKAY，否则对方会卡住
                writer.okay(msg.arg1, msg.arg0)
            }

            AdbProtocol.CMD_CLSE -> {
                findStream(msg.arg1)?.onRemoteClose(msg.arg0)
                synchronized(streamsLock) { streams.remove(msg.arg1) }
            }

            else -> Log.d(TAG, "忽略报文：${msg.name}")
        }
    }

    private fun findStream(localId: Int): AdbStream? = synchronized(streamsLock) { streams[localId] }

    /**
     * 打开一条到指定服务的流（如 `shell:ls`、`sync:`、`tcp:8080`）。
     *
     * @throws AdbConnectException 服务打不开
     */
    fun open(destination: String, timeoutMs: Long = 10_000): AdbStream {
        if (closed) throw AdbConnectException("连接已关闭")
        val localId = nextLocalId.getAndIncrement()
        val stream = AdbStream(localId, this, destination)
        synchronized(streamsLock) { streams[localId] = stream }

        writer.open(localId, destination)

        if (!stream.awaitOpen(timeoutMs)) {
            synchronized(streamsLock) { streams.remove(localId) }
            throw AdbConnectException("打开服务失败（超时）：$destination")
        }
        if (!stream.isOpen) {
            synchronized(streamsLock) { streams.remove(localId) }
            throw AdbConnectException("设备拒绝打开服务：$destination")
        }
        return stream
    }

    internal fun sendWrite(localId: Int, remoteId: Int, data: ByteArray) {
        writer.writeStream(localId, remoteId, data)
    }

    internal fun sendClose(localId: Int, remoteId: Int) {
        runCatching { writer.close(localId, remoteId) }
    }

    internal fun removeStream(localId: Int) {
        synchronized(streamsLock) { streams.remove(localId) }
    }

    private fun failAllStreams() {
        val all = synchronized(streamsLock) {
            val copy = streams.values.toList()
            streams.clear()
            copy
        }
        all.forEach { runCatching { it.onRemoteClose(0) } }
    }

    override fun close() {
        if (closed) return
        closed = true
        failAllStreams()
        runCatching { transport.close() }
        readThread?.interrupt()
    }

    private companion object {
        const val TAG = "AdbConnection"
    }
}

/**
 * 一条服务流。
 *
 * 数据流模型：读线程把 WRTE 的 payload 塞进 [queue]，调用方从 queue 取；
 * 写侧用 [writePermit] 做 OKAY 节流。
 */
class AdbStream internal constructor(
    val localId: Int,
    private val connection: AdbConnection,
    val destination: String,
) {

    private val openLatch = CountDownLatch(1)
    private val queue = LinkedBlockingQueue<ByteArray>()
    private val writePermit = Semaphore(1, true)

    /** 对方的流 id，收到第一个 OKAY 后才有值。 */
    @Volatile
    var remoteId: Int = 0
        private set

    @Volatile
    var isOpen: Boolean = false
        private set

    @Volatile
    private var remoteClosed = false

    private var openFailed = false

    internal fun onOkay(remote: Int) {
        if (remoteId == 0) remoteId = remote
        if (!isOpen) {
            isOpen = true
            openLatch.countDown()
        }
        runCatching { writePermit.release() }
    }

    internal fun onData(data: ByteArray) {
        if (data.isNotEmpty()) queue.offer(data)
    }

    internal fun onRemoteClose(remote: Int) {
        if (remote > 0 && remoteId == 0) remoteId = remote
        remoteClosed = true
        openFailed = !isOpen
        openLatch.countDown()
        runCatching { writePermit.release() }
    }

    internal fun awaitOpen(timeoutMs: Long): Boolean {
        val ok = openLatch.await(timeoutMs, TimeUnit.MILLISECONDS)
        return ok && isOpen
    }

    /** 阻塞读一块数据；对端关闭且无剩余数据时返回 null。 */
    fun read(timeoutMs: Long = 30_000): ByteArray? {
        if (remoteClosed && queue.isEmpty()) return null
        val chunk = queue.poll(timeoutMs, TimeUnit.MILLISECONDS)
        if (chunk != null) return chunk
        return if (remoteClosed) null else ByteArray(0)
    }

    /** 读到对端关闭为止，把全部数据拼起来（带总超时）。 */
    fun readAll(timeoutMs: Long = 60_000): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val remaining = (deadline - System.currentTimeMillis()).coerceAtLeast(1)
            val chunk = try {
                read(remaining)
            } catch (_: InterruptedException) {
                break
            } ?: break
            if (chunk.isNotEmpty()) out.write(chunk)
            if (remoteClosed && queue.isEmpty()) break
        }
        return out.toByteArray()
    }

    /** 写数据（自动按 maxPayload 分块，并在每块后等 OKAY）。 */
    fun write(data: ByteArray) {
        if (!isOpen) throw IOException("流未打开：$destination")
        val chunkSize = connection.maxPayload.coerceAtLeast(1024)
        var offset = 0
        while (offset < data.size) {
            if (remoteClosed) throw IOException("对端已关闭流：$destination")
            val size = minOf(chunkSize, data.size - offset)
            val chunk = data.copyOfRange(offset, offset + size)
            // 节流：等上一次写被 OKAY
            if (!writePermit.tryAcquire(10, TimeUnit.SECONDS)) {
                throw IOException("等待 OKAY 超时：$destination")
            }
            connection.sendWrite(localId, remoteId, chunk)
            offset += size
        }
    }

    fun close() {
        if (remoteClosed) return
        remoteClosed = true
        connection.sendClose(localId, remoteId)
        connection.removeStream(localId)
    }
}
