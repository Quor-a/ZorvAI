package com.ai.assistance.quro.core.mika.net

import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI

/**
 * 一个网络代理地址,形如 `协议://[用户:密码@]主机:端口`:协议是 http、https、socks5、socks5h 之一,后面不带路径。
 * [toString] 不带账号密码,写进日志、显示在界面上都不会泄露。
 */
class ProxyAddress private constructor(
    val scheme: String,
    /** 主机名或 IP;IPv6 不带方括号。 */
    val host: String,
    val port: Int,
    val user: String,
    val password: String,
) {
    val socks: Boolean get() = scheme == "socks5" || scheme == "socks5h"
    val hasCredentials: Boolean get() = user.isNotEmpty()

    /**
     * ZorvAI 自己的连接(调模型、下更新、内置浏览器)能不能走它。https 代理要先和代理本身握 TLS,
     * 这些连接用的网络库不支持,只有容器里的命令(curl 等)用得上。
     */
    val forApp: Boolean get() = scheme != "https"

    fun javaProxy(): Proxy = Proxy(if (socks) Proxy.Type.SOCKS else Proxy.Type.HTTP, InetSocketAddress.createUnresolved(host, port))

    /** 给人看的写法:协议、主机、端口,不带账号密码。 */
    val display: String get() = "$scheme://${if (host.contains(':')) "[$host]" else host}:$port"

    override fun toString(): String = display

    companion object {
        val SCHEMES = setOf("http", "https", "socks5", "socks5h")

        /** 解析代理地址;空串或写法不对为 null。 */
        fun parse(text: String): ProxyAddress? {
            val v = text.trim()
            if (v.isEmpty() || v.any { it.isWhitespace() }) return null
            val u = runCatching { URI(v) }.getOrNull() ?: return null
            val scheme = u.scheme?.lowercase() ?: return null
            if (scheme !in SCHEMES) return null
            val host = u.host?.removeSurrounding("[", "]")?.takeIf { it.isNotEmpty() } ?: return null
            if (u.port !in 1..65535) return null
            if (!(u.rawPath.isNullOrEmpty() || u.rawPath == "/") || u.rawQuery != null || u.rawFragment != null) return null
            val info = u.userInfo.orEmpty()
            val user = info.substringBefore(':')
            val password = if (info.contains(':')) info.substringAfter(':') else ""
            return ProxyAddress(scheme, host, u.port, user, password)
        }

        /** 空串(不用代理),或能解析的代理地址。 */
        fun valid(text: String): Boolean = text.isBlank() || parse(text) != null
    }
}
