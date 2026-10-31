package com.ai.assistance.quro.core.adb.mika

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetAddress
import java.net.NetworkInterface

/**
 * 用系统 mDNS(NsdManager)找本机 adbd 公布的端口。只认地址属于本机网卡的结果,
 * 同一局域网里别的手机也会公布同名服务。拿到端口后由调用方连 127.0.0.1。
 * 查到第一个后再多等 [SETTLE_MS] 收齐同时公布的几条(旧的失效记录和新的可能一起回来),逐个解析(旧版系统一次只解析一个)。
 */
class NsdAdbServiceFinder(context: Context) : AdbServiceFinder {
    private val nsd = context.applicationContext.getSystemService(NsdManager::class.java)

    override suspend fun findPorts(serviceType: String, timeoutMs: Long): List<Int> {
        val services = Channel<NsdServiceInfo>(Channel.UNLIMITED)
        val listener = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(info: NsdServiceInfo) { services.trySend(info) }
            override fun onServiceLost(info: NsdServiceInfo) {}
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { services.close() }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        }
        nsd.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener)
        val ports = LinkedHashSet<Int>()
        try {
            withTimeoutOrNull(timeoutMs) {
                var settleUntil = Long.MAX_VALUE
                while (System.currentTimeMillis() < settleUntil) {
                    val info = withTimeoutOrNull((settleUntil - System.currentTimeMillis()).coerceAtMost(timeoutMs)) {
                        services.receiveCatching().getOrNull()
                    } ?: break
                    resolve(info)?.let {
                        ports += it
                        if (settleUntil == Long.MAX_VALUE) settleUntil = System.currentTimeMillis() + SETTLE_MS
                    }
                }
            }
        } finally {
            runCatching { nsd.stopServiceDiscovery(listener) }
        }
        return ports.toList()
    }

    /** 解析一条服务,是本机的就给出端口;解析失败或不是本机为 null。 */
    private suspend fun resolve(info: NsdServiceInfo): Int? {
        val done = Channel<Int?>(1)
        @Suppress("DEPRECATION")
        nsd.resolveService(info, object : NsdManager.ResolveListener {
            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) { done.trySend(null) }
            override fun onServiceResolved(info: NsdServiceInfo) {
                @Suppress("DEPRECATION")
                done.trySend(info.port.takeIf { isLocal(info.host) })
            }
        })
        return withTimeoutOrNull(RESOLVE_MS) { done.receive() }
    }

    private fun isLocal(host: InetAddress?): Boolean {
        if (host == null) return false
        if (host.isLoopbackAddress) return true
        return runCatching {
            NetworkInterface.getNetworkInterfaces().asSequence().any { nic -> nic.inetAddresses.asSequence().any { it == host } }
        }.getOrDefault(false)
    }

    private companion object {
        const val SETTLE_MS = 400L
        const val RESOLVE_MS = 1_500L
    }
}
