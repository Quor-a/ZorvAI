package com.ai.assistance.quro.core.mika.adb

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.SocketChannel
import java.util.TreeSet
import java.util.concurrent.ConcurrentHashMap

/** 连接端口的一个候选:本机的哪个地址、哪个端口。 */
data class AdbEndpoint(val host: String, val port: Int)

/** ZorvAI 自己在本机开着的监听端口(如登录回调):扫端口时跳过,免得连到自己。 */
object OwnListeningPorts {
    private val ports = ConcurrentHashMap.newKeySet<Int>()
    fun add(port: Int) { ports += port }
    fun remove(port: Int) { ports -= port }
    fun snapshot(): Set<Int> = ports.toSet()
}

/** 扫本机端口的底层动作;测试里换成假的。 */
interface LocalPortScanner {
    /** [host] 上 [ports] 里哪些能建立 TCP 连接(升序),跳过 [skip];[budgetMs] 用完就停,已找到的照给。 */
    suspend fun openPorts(host: String, ports: IntRange, skip: Set<Int>, budgetMs: Long): List<Int>

    /** 发 CNXN、对方回 STLS 才算无线调试的 adbd(别的服务、自己连自己都不会回 STLS)。 */
    fun speaksAdbTls(host: String, port: Int, timeoutMs: Int): Boolean
}

/** 一次本机扫描的结果:几个端口开着、几个像 adbd(诊断信息只记个数)。 */
data class ScanResult(val endpoints: List<AdbEndpoint>, val open: Int, val adb: Int)

/**
 * 找连接端口的最后一招:前面几个来源都没给出能连上的端口时,扫本机自己的地址。
 * 无线调试的 adbd 监听一个系统随手分配的端口,落在临时端口范围里([range],读不到就用 [FALLBACK_RANGE])。
 * 按 [hosts] 的顺序一个地址一个地址扫(先 127.0.0.1,再本机 Wi-Fi 地址;adbd 可能只绑其中一个),
 * 每个开着的端口发 CNXN 看回不回 STLS;一个地址上有像 adbd 的就不再扫下一个。只扫本机,不碰别的设备。
 * 扫描和核对合计不超过 [budgetMs]。
 */
class LocalPortScan(
    private val scanner: LocalPortScanner,
    private val hosts: () -> List<String>,
    private val range: () -> IntRange = { FALLBACK_RANGE },
    private val ownPorts: () -> Set<Int> = OwnListeningPorts::snapshot,
    private val budgetMs: Long = 10_000,
    private val probeTimeoutMs: Int = 1_000,
    private val now: () -> Long = System::currentTimeMillis,
) {
    /** 扫一遍;[skip] 是前面来源已经试过的端口。 */
    suspend fun scan(skip: Set<Int>): ScanResult {
        val deadline = now() + budgetMs
        val ports = range()
        val excluded = skip + ownPorts()
        val found = ArrayList<AdbEndpoint>()
        var open = 0
        for (host in hosts().distinct()) {
            val left = deadline - now()
            if (left <= 0) break
            val openPorts = scanner.openPorts(host, ports, excluded, left)
            open += openPorts.size
            for (port in openPorts) {
                if (now() >= deadline) break
                if (scanner.speaksAdbTls(host, port, probeTimeoutMs)) found += AdbEndpoint(host, port)
            }
            if (found.isNotEmpty()) break
        }
        return ScanResult(found, open, found.size)
    }

    companion object {
        /** 读不到系统的临时端口范围时扫这一段(覆盖各版本常见的设置)。 */
        val FALLBACK_RANGE = 30_000..65_535

        /**
         * 解析 `/proc/sys/net/ipv4/ip_local_port_range`(两个数,空白分隔)。读不到、格式不对或范围不合理时用 [FALLBACK_RANGE]。
         */
        fun parseRange(text: String?): IntRange {
            val nums = text?.trim()?.split(Regex("\\s+"))?.mapNotNull { it.toIntOrNull() } ?: return FALLBACK_RANGE
            if (nums.size != 2) return FALLBACK_RANGE
            val (lo, hi) = nums
            return if (lo in 1024..65_535 && hi in lo..65_535) lo..hi else FALLBACK_RANGE
        }
    }
}

/**
 * 真实的扫描:java.nio 非阻塞连接,同时最多 [concurrency] 个在连,每个最多等 [connectTimeoutMs]。
 * 本机地址连不上的端口会马上被拒,整段扫下来几秒钟。在 IO 线程上跑,协程取消时停下。
 */
class NioPortScanner(
    private val concurrency: Int = 200,
    private val connectTimeoutMs: Long = 150,
) : LocalPortScanner {
    override suspend fun openPorts(host: String, ports: IntRange, skip: Set<Int>, budgetMs: Long): List<Int> = withContext(Dispatchers.IO) {
        val ctx = currentCoroutineContext()
        sweep(InetAddress.getByName(host), ports, skip, System.currentTimeMillis() + budgetMs) { ctx.isActive }
    }

    private fun sweep(addr: InetAddress, ports: IntRange, skip: Set<Int>, deadline: Long, active: () -> Boolean): List<Int> {
        val open = TreeSet<Int>()
        val next = ports.iterator()
        Selector.open().use { selector ->
            val startedAt = HashMap<SelectionKey, Long>()
            try {
                while ((next.hasNext() || startedAt.isNotEmpty()) && System.currentTimeMillis() < deadline && active()) {
                    while (next.hasNext() && startedAt.size < concurrency) {
                        val port = next.nextInt()
                        if (port in skip) continue
                        val ch = SocketChannel.open()
                        try {
                            ch.configureBlocking(false)
                            if (ch.connect(InetSocketAddress(addr, port))) {
                                if (!selfConnected(ch, port)) open += port
                                ch.close()
                            } else {
                                startedAt[ch.register(selector, SelectionKey.OP_CONNECT, port)] = System.currentTimeMillis()
                            }
                        } catch (e: IOException) {
                            ch.close()
                        }
                    }
                    selector.select(SELECT_MS)
                    for (key in selector.selectedKeys()) {
                        val ch = key.channel() as SocketChannel
                        val ok = try { ch.finishConnect() } catch (e: IOException) { false }
                        if (ok && !selfConnected(ch, key.attachment() as Int)) open += key.attachment() as Int
                        startedAt.remove(key)
                        key.cancel()
                        ch.close()
                    }
                    selector.selectedKeys().clear()
                    val t = System.currentTimeMillis()
                    val late = startedAt.filterValues { t - it > connectTimeoutMs }.keys
                    for (key in late) {
                        startedAt.remove(key)
                        key.cancel()
                        key.channel().close()
                    }
                }
            } finally {
                for (key in startedAt.keys) runCatching { key.channel().close() }
            }
        }
        return open.toList()
    }

    /** 扫临时端口范围时,系统可能恰好给这条连接分到同一个本地端口,于是自己连上了自己。 */
    private fun selfConnected(ch: SocketChannel, port: Int): Boolean = runCatching {
        val s = ch.socket()
        s.localPort == port && s.localAddress == s.inetAddress
    }.getOrDefault(false)

    override fun speaksAdbTls(host: String, port: Int, timeoutMs: Int): Boolean = try {
        Socket().use { s ->
            s.connect(InetSocketAddress(host, port), timeoutMs)
            if (s.localPort == port && s.localAddress == s.inetAddress) return false
            s.soTimeout = timeoutMs
            s.getOutputStream().apply {
                write(AdbMessage(AdbMessage.CNXN, AdbMessage.VERSION, AdbMessage.MAX_PAYLOAD, HOST_BANNER).encode())
                flush()
            }
            AdbMessage.read(DataInputStream(s.getInputStream()), maxData = 4096)?.command == AdbMessage.STLS
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        false
    }

    private companion object {
        const val SELECT_MS = 20L
        val HOST_BANNER = "host::\u0000".toByteArray(Charsets.US_ASCII)
    }
}
