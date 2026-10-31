package com.ai.assistance.quro.core.mika.cap.web

import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.UnknownHostException

/**
 * 下载能取哪些地址:先过 [WebUrlPolicy.checkNavigable](与 web_navigate 同一条规则),再不许本机、局域网与保留地址。
 * 下载是 App 自己在后台发请求,页面借它就能摸到手机所在局域网里的设备,所以比打开网页更严。
 * 每一次跳转、每一次域名解析都按这条规则查([guardedDns] 挡住解析到内网的域名)。
 */
object DownloadAddressPolicy {
    /** 不能取的原因;能取为 null。 */
    fun refusal(url: String): String? {
        WebUrlPolicy.checkNavigable(url)?.let { return it }
        val host = url.toHttpUrlOrNull()?.host ?: return "not a web address"
        return if (blockedHost(host)) "local or private network address" else null
    }

    /** 本机或局域网的名字,或字面上就是内网/保留的 IP。普通域名要等解析后由 [guardedDns] 判。 */
    fun blockedHost(host: String): Boolean {
        val h = host.trim().trimEnd('.').lowercase().removePrefix("[").removeSuffix("]")
        if (h.isEmpty() || h == "localhost" || LOCAL_SUFFIXES.any { h.endsWith(it) }) return true
        if (!IPV4.matches(h) && ':' !in h) return false
        // 字面 IP:getByName 不查 DNS
        val a = runCatching { InetAddress.getByName(h) }.getOrNull() ?: return true
        return blockedAddress(a)
    }

    fun blockedAddress(a: InetAddress): Boolean {
        if (a.isAnyLocalAddress || a.isLoopbackAddress || a.isLinkLocalAddress || a.isSiteLocalAddress || a.isMulticastAddress) return true
        val b = a.address
        return when (a) {
            is Inet4Address -> blockedV4(b)
            is Inet6Address -> {
                val u0 = b[0].toInt() and 0xff
                // fc00::/7 唯一本地;::ffff:a.b.c.d 与 64:ff9b::/96 内嵌的 IPv4 按 IPv4 判
                (u0 and 0xfe) == 0xfc ||
                    (b.copyOfRange(0, 10).all { it == 0.toByte() } && b[10] == 0xff.toByte() && b[11] == 0xff.toByte() && blockedV4(b.copyOfRange(12, 16))) ||
                    (b.copyOfRange(0, 12).contentEquals(NAT64) && blockedV4(b.copyOfRange(12, 16)))
            }
            else -> true
        }
    }

    private fun blockedV4(b: ByteArray): Boolean {
        val o0 = b[0].toInt() and 0xff
        val o1 = b[1].toInt() and 0xff
        return o0 == 0 || o0 == 10 || o0 == 127 || o0 >= 224 ||
            (o0 == 100 && o1 in 64..127) || (o0 == 169 && o1 == 254) || (o0 == 172 && o1 in 16..31) ||
            (o0 == 192 && o1 == 168) || (o0 == 192 && o1 == 0 && (b[2].toInt() and 0xff) == 0) || (o0 == 198 && o1 in 18..19)
    }

    /** 解析到的地址里有一个是内网/保留地址就整个拒绝([BlockedAddress])。 */
    fun guardedDns(delegate: Dns = Dns.SYSTEM): Dns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            if (blockedHost(hostname)) throw BlockedAddress(hostname)
            val all = delegate.lookup(hostname)
            if (all.any(::blockedAddress)) throw BlockedAddress(hostname)
            return all
        }
    }

    class BlockedAddress(host: String) : UnknownHostException("$host resolves to a local or private network address")

    private val IPV4 = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")
    private val LOCAL_SUFFIXES = listOf(".localhost", ".local", ".lan", ".home.arpa", ".internal", ".intranet")
    private val NAT64 = byteArrayOf(0, 0x64, 0xff.toByte(), 0x9b.toByte(), 0, 0, 0, 0, 0, 0, 0, 0)
}
