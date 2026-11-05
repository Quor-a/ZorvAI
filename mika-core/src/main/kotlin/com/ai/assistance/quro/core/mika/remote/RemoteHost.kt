package com.ai.assistance.quro.core.mika.remote

import com.ai.assistance.quro.core.mika.device.DeviceAccess
import java.net.InetAddress
import java.net.InetSocketAddress

/** 设置页上显示的一点点动静:最近连过的客户端有几个、最后一次请求是什么时候。 */
data class RemoteActivity(val recentClients: Int = 0, val lastRequestAtMs: Long? = null)

/**
 * 电脑连接的组合入口:共用 HTTP 监听与鉴权,普通任务 API 和 MCP 并列接入同一份 [RemoteTaskService]。
 * 只监听 [port];[allowAllInterfaces] 为 false 时只绑本机回环 127.0.0.1(经 USB 的电脑连接、测试),为 true 时绑所有网卡,
 * 但只收两端都是私有地址的连接(局域网、私有组网),公网来的当场断开。
 * [token] 哪里来的连接都认;[loopbackToken] 只认本机回环来的(电脑经 `adb forward` 进来就是回环)。
 */
class RemoteHost(
    tasks: RemoteTasks,
    private val token: () -> String?,
    private val port: Int,
    private val allowAllInterfaces: Boolean,
    serverVersion: String,
    private val clock: () -> Long = System::currentTimeMillis,
    private val onError: (Throwable) -> Unit = {},
    loopbackToken: () -> String? = { null },
    device: DeviceAccess? = null,
    private val endpoints: RemoteEndpoints = RemoteEndpoints(tasks, serverVersion, clock, device),
) {
    private val access = RemoteAccess(token, clock, loopbackToken = loopbackToken)
    val activity get() = endpoints.activity
    private var server: MiniHttpServer? = null

    val boundPort: Int get() = server?.port ?: -1

    fun handle(req: HttpRequest): HttpResponse = access.handle(req, endpoints::handle)

    /** 开始监听;端口被占等绑定失败时抛出异常。 */
    fun start() {
        val s = MiniHttpServer(bindAddress(allowAllInterfaces, port), ::handle, accept = { local, remote ->
            RemoteAccess.privateAddress(local) && RemoteAccess.privateAddress(remote)
        }, onError = onError)
        s.start()
        server = s
    }

    fun stop() {
        server?.stop()
        server = null
    }

    companion object {
        const val RECENT_MS = 10 * 60_000L

        /**
         * 监听的地址:所有网卡,或只有 IPv4 回环 127.0.0.1。回环写死成 IPv4:安卓上 `InetAddress.getLoopbackAddress()` 可能给 ::1,
         * 而 `adb forward` 连的是 127.0.0.1。
         */
        fun bindAddress(allowAllInterfaces: Boolean, port: Int): InetSocketAddress =
            if (allowAllInterfaces) InetSocketAddress(port) else InetSocketAddress(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), port)
    }
}
