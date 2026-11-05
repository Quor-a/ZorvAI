package com.ai.assistance.quro.core.mika.remote

import com.ai.assistance.quro.core.mika.mcp.McpRpc
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.security.MessageDigest

/**
 * 电脑连接的门口:每个请求先过这里,过了才交给协议层。顺序固定:
 * 浏览器来的(带 Origin)一律 403;同一地址认证失败太多次先 429;访问令牌不对 401。路由和错误序列化交给入口适配器。
 * 令牌按常量时间比较;失败次数按请求的来源([HttpRequest.source])记,[clock] 可换,测试里拨时间。
 * 冷却期间正确令牌也不放行:放行就等于告诉猜的人「这回猜对了」,冷却就挡不住猜令牌。
 * 经网关转发的请求按网关看到的调用方分开记,一个匿名调用方猜错只冷却它自己。
 * [token] 从哪里来的连接都认;[loopbackToken](经 USB 的电脑连接用的那个)只认本机回环来的连接。
 */
class RemoteAccess(
    private val token: () -> String?,
    private val clock: () -> Long = System::currentTimeMillis,
    private val maxFailures: Int = 5,
    private val windowMs: Long = 60_000,
    private val lockMs: Long = 60_000,
    private val loopbackToken: () -> String? = { null },
) {
    sealed interface Verdict {
        data object Pass : Verdict
        data class Reject(val status: Int, val message: String, val headers: Map<String, String> = emptyMap()) : Verdict
    }

    private class Failures(var count: Int, var since: Long, var lockedUntil: Long)
    private val failures = HashMap<String, Failures>()

    fun check(req: HttpRequest): Verdict {
        // 浏览器的请求总带 Origin;电脑上的 MCP 客户端不带。网页不该能碰到这个端口(防 DNS 重绑定)
        if (req.header("origin") != null) return Verdict.Reject(403, "Browser requests are not allowed")
        val who = req.source
        val now = clock()
        synchronized(failures) {
            val f = failures[who]
            if (f != null && f.lockedUntil > now) {
                val wait = ((f.lockedUntil - now + 999) / 1000).coerceAtLeast(1)
                return Verdict.Reject(429, "Too many failed attempts", mapOf("Retry-After" to wait.toString()))
            }
        }
        val given = req.header("authorization")?.let { h ->
            if (h.length > 7 && h.substring(0, 7).equals("Bearer ", ignoreCase = true)) h.substring(7).trim() else null
        }
        val loopback = if (req.remote.isLoopbackAddress) loopbackToken() else null
        // 两个都比,不因对上了第一个就少比一次。
        if (given == null || !(matches(given, token()) or matches(given, loopback))) {
            fail(who, now)
            return Verdict.Reject(401, "Missing or wrong access token", mapOf("WWW-Authenticate" to "Bearer"))
        }
        synchronized(failures) { failures.remove(who) }

        return Verdict.Pass
    }

    /** 本地监听与远程转发共用鉴权和协议错误;只有通过后才进入任务层。 */
    fun handle(req: HttpRequest, next: (HttpRequest) -> HttpResponse): HttpResponse = when (val verdict = check(req)) {
        is Verdict.Reject -> if (req.path == McpRpc.PATH)
            McpRpc.transportError(verdict.status, verdict.message, verdict.headers)
        else RemoteTaskApi.error(verdict.status, "access_denied", verdict.message, verdict.headers)
        Verdict.Pass -> next(req)
    }

    private fun matches(given: String, expected: String?) = !expected.isNullOrEmpty() && same(given, expected)

    private fun fail(who: String, now: Long) = synchronized(failures) {
        val f = failures.getOrPut(who) { Failures(0, now, 0) }
        if (now - f.since > windowMs) { f.count = 0; f.since = now }
        f.count++
        if (f.count >= maxFailures) { f.lockedUntil = now + lockMs; f.count = 0; f.since = now }
        // 地址多了只留最近的,不让表无限长
        if (failures.size > 256) failures.entries.minByOrNull { it.value.since }?.let { failures.remove(it.key) }
    }

    companion object {
        /** 常量时间比较:先各自摘要成等长,再逐字节比,耗时不随前面对上了几个字符变。 */
        fun same(a: String, b: String): Boolean {
            val d = MessageDigest.getInstance("SHA-256")
            val x = d.digest(a.toByteArray(Charsets.UTF_8))
            val y = d.digest(b.toByteArray(Charsets.UTF_8))
            return MessageDigest.isEqual(x, y)
        }

        /**
         * 收不收这个地址的连接:本机回环、局域网私有段(10/8、172.16/12、192.168/16)、
         * 私有组网常用的运营商级 NAT 段(100.64/10)、IPv4 链路本地,以及 IPv6 的唯一本地(fc00::/7)和链路本地(fe80::/10)。
         * 公网地址(比如走移动数据时)一律不收。
         */
        fun privateAddress(a: InetAddress): Boolean {
            val v4 = when (a) {
                is Inet4Address -> a.address
                is Inet6Address -> a.address.let { b ->
                    // IPv4 映射成 IPv6 的写法(::ffff:a.b.c.d)按 IPv4 看
                    if ((0 until 10).all { b[it] == 0.toByte() } && b[10] == 0xff.toByte() && b[11] == 0xff.toByte()) b.copyOfRange(12, 16) else null
                }
                else -> null
            }
            if (v4 != null) {
                val o0 = v4[0].toInt() and 0xff
                val o1 = v4[1].toInt() and 0xff
                return o0 == 127 || o0 == 10 || (o0 == 172 && o1 in 16..31) || (o0 == 192 && o1 == 168) ||
                    (o0 == 100 && o1 in 64..127) || (o0 == 169 && o1 == 254)
            }
            if (a.isLoopbackAddress) return true
            val b0 = a.address[0].toInt() and 0xff
            val b1 = a.address[1].toInt() and 0xff
            return (b0 and 0xfe) == 0xfc || (b0 == 0xfe && (b1 and 0xc0) == 0x80)
        }
    }
}
