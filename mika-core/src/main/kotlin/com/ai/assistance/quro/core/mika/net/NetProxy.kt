package com.ai.assistance.quro.core.mika.net

import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import java.io.IOException
import java.lang.ref.WeakReference
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.PasswordAuthentication
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.SocketTimeoutException
import java.net.URI
import java.net.UnknownHostException
import java.util.concurrent.CopyOnWriteArrayList
import java.net.Authenticator as JavaAuthenticator
import okhttp3.Authenticator as OkAuthenticator

/** 走网络代理时出的错,和服务商那边的错分开说。 */
enum class ProxyFailure {
    /** 连不上代理,或代理没有转发这个请求。 */
    Unreachable,
    /** 代理要账号密码,给的不对(或没给)。 */
    AuthFailed,
}

/**
 * ZorvAI 自己的网络连接(调模型、列模型、账号登录换令牌、下更新、内置浏览器下载)用的网络代理。
 * [source] 每次给出此刻存着的代理地址(空串不用);按原文缓存解析结果,改了设置后调 [changed]。
 *
 * - 经 [client] 建的 OkHttp 客户端每个请求都问 [selector]:本机、局域网地址([LocalHosts.bypassesProxy])直连,别的走代理;
 *   没设代理或代理不适用([ProxyAddress.forApp] 为假)时直连。流式回复照旧逐段读,不经过缓冲。
 * - HTTP 代理的账号密码在代理要求时(以及建隧道前)带上 `Proxy-Authorization`;同一请求带过还被拒就不再重试。
 * - SOCKS 代理的账号密码只能经全进程一个的 [java.net.Authenticator] 给:[installSocksAuth] 装上的那个
 *   只回答发给当前 SOCKS 代理(主机、端口都对上)的询问,别的一律不答。
 */
class NetProxy(private val source: () -> String) {
    private class Parsed(val raw: String, val address: ProxyAddress?)

    @Volatile private var parsed: Parsed? = null
    private val pools = CopyOnWriteArrayList<WeakReference<okhttp3.ConnectionPool>>()

    /** 此刻 ZorvAI 自己的连接要走的代理;没设、写法不对或不适用时为 null。 */
    fun current(): ProxyAddress? {
        val raw = runCatching(source).getOrDefault("")
        parsed?.takeIf { it.raw == raw }?.let { return it.address }
        val address = ProxyAddress.parse(raw)?.takeIf { it.forApp }
        parsed = Parsed(raw, address)
        return address
    }

    /** 连这个主机要走的代理;本机、局域网地址直连(为 null)。 */
    fun proxyFor(host: String): ProxyAddress? = current()?.takeUnless { LocalHosts.bypassesProxy(host) }

    val selector: ProxySelector = Selector()

    val authenticator: OkAuthenticator = OkAuthenticator { route, response -> authenticate(route, response) }

    private fun authenticate(route: Route?, response: Response): Request? {
        // 带过账号密码还被拒:不再重试,交给调用方报「代理的账号或密码不对」
        if (response.request.header(PROXY_AUTHORIZATION) != null) return null
        val proxy = current() ?: return null
        if (proxy.socks || !proxy.hasCredentials) return null
        if (route != null && route.proxy.type() != Proxy.Type.HTTP) return null
        return response.request.newBuilder().header(PROXY_AUTHORIZATION, Credentials.basic(proxy.user, proxy.password)).build()
    }

    /** 按 [builder] 建一个走这里代理设置的客户端;它的连接池会在设置改了时清掉空闲连接。 */
    fun client(builder: OkHttpClient.Builder = OkHttpClient.Builder()): OkHttpClient =
        builder.proxySelector(selector).proxyAuthenticator(authenticator).build().also { pools += WeakReference(it.connectionPool) }

    /** 设置改了:重新解析,空闲的连接全部断开,下一个请求按新的设置连(正在进行的请求照旧走完)。 */
    fun changed() {
        parsed = null
        pools.removeAll { it.get() == null }
        pools.forEach { it.get()?.evictAll() }
    }

    /** SOCKS 代理的账号密码:只回答发给当前 SOCKS 代理的询问。 */
    val socksAuthenticator: JavaAuthenticator = object : JavaAuthenticator() {
        override fun getPasswordAuthentication(): PasswordAuthentication? =
            socksCredentials(requestingProtocol, requestingHost, requestingPort)
    }

    internal fun socksCredentials(protocol: String?, host: String?, port: Int): PasswordAuthentication? {
        val proxy = current() ?: return null
        if (!proxy.socks || !proxy.hasCredentials) return null
        if (protocol?.startsWith("SOCKS", ignoreCase = true) != true) return null
        if (host == null || !host.removeSurrounding("[", "]").equals(proxy.host, ignoreCase = true) || port != proxy.port) return null
        return PasswordAuthentication(proxy.user, proxy.password.toCharArray())
    }

    /** 装上 [socksAuthenticator](全进程一个);App 启动时调一次。 */
    fun installSocksAuth() = JavaAuthenticator.setDefault(socksAuthenticator)

    private inner class Selector : ProxySelector() {
        val owner: NetProxy get() = this@NetProxy

        override fun select(uri: URI?): List<Proxy> {
            val host = uri?.host ?: return listOf(Proxy.NO_PROXY)
            return listOf(proxyFor(host)?.javaProxy() ?: Proxy.NO_PROXY)
        }

        override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) = Unit
    }

    companion object {
        private const val PROXY_AUTHORIZATION = "Proxy-Authorization"

        /** 产品里的那一份:App 启动时把 [sharedSource] 接到存着的设置上。 */
        @Volatile var sharedSource: () -> String = { "" }
        val shared: NetProxy = NetProxy { sharedSource() }

        /** 经 [client] 建的客户端对应的那一份;别的客户端为 null。 */
        fun of(http: OkHttpClient): NetProxy? = (http.proxySelector as? Selector)?.owner

        /** 这次失败是不是代理的问题:[http] 连 [url] 时走了代理,且错在连代理、过代理那一段。 */
        fun failure(http: OkHttpClient, url: HttpUrl, e: IOException): ProxyFailure? {
            of(http)?.proxyFor(url.host) ?: return null
            return classify(e)
        }

        /**
         * 走代理时网络错误的归类:代理拒了账号密码算 [ProxyFailure.AuthFailed];连不上代理(拒绝连接、找不到主机、连接超时)、
         * 代理建隧道失败、SOCKS 握手出错算 [ProxyFailure.Unreachable];读回复时断了之类的不算代理的问题(为 null)。
         */
        fun classify(e: IOException): ProxyFailure? {
            val chain = generateSequence(e as Throwable) { it.cause }.take(6).toList()
            val messages = chain.map { it.message.orEmpty() }
            if (messages.any { it.contains("authenticate with proxy", true) || (it.contains("SOCKS", true) && it.contains("auth", true)) }) {
                return ProxyFailure.AuthFailed
            }
            if (messages.any { it.contains("for CONNECT", true) || it.contains("SOCKS", true) }) return ProxyFailure.Unreachable
            val connecting = chain.any {
                it is ConnectException || it is NoRouteToHostException || it is UnknownHostException ||
                    (it is SocketTimeoutException && it.message.orEmpty().contains("connect", true))
            }
            return if (connecting) ProxyFailure.Unreachable else null
        }
    }
}
