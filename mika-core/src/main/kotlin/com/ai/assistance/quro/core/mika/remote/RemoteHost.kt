package com.ai.assistance.quro.core.mika.remote

import com.ai.assistance.quro.core.mika.mcp.McpRpc
import com.ai.assistance.quro.core.mika.mcp.McpTaskTools

import com.ai.assistance.quro.core.mika.device.DeviceAccess
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
) {
    private val access = RemoteAccess(token, clock, loopbackToken = loopbackToken)
    private val clients = LinkedHashMap<String, Long>()
    private val _activity = MutableStateFlow(RemoteActivity())
    val activity: StateFlow<RemoteActivity> = _activity.asStateFlow()
    private val service = RemoteTaskService(tasks)
    private val api = RemoteTaskApi(service, serverVersion, device)
    private val rpc = McpRpc(McpTaskTools(service, device), serverVersion) { remote, name -> seen(remote, name) }
    private var server: MiniHttpServer? = null

    val boundPort: Int get() = server?.port ?: -1

    fun handle(req: HttpRequest): HttpResponse {
        when (val v = access.check(req)) {
            is RemoteAccess.Verdict.Reject -> return if (req.path == McpRpc.PATH)
                McpRpc.transportError(v.status, v.message, v.headers)
            else RemoteTaskApi.error(v.status, "access_denied", v.message, v.headers)
            RemoteAccess.Verdict.Pass -> Unit
        }
        if (req.path == McpRpc.PATH) return rpc.handle(req)
        if (req.path == RemoteTaskApi.ROOT || req.path.startsWith("${RemoteTaskApi.ROOT}/")) {
            seen(req.remote, req.header("x-quro-client")?.take(64))
            return api.handle(req)
        }
        return RemoteTaskApi.error(404, "not_found", "No such endpoint")
    }

    /** 同一客户端(地址 + 自报的名字)算一个;十分钟内来过的算「最近」。 */
    private fun seen(remote: InetAddress, name: String?) {
        val now = clock()
        synchronized(this) {
            val who = remote.hostAddress ?: "?"
            // 老一代客户端只在握手时报名字,之后的请求按地址归到已有的那一个
            val key = if (name != null) "$who|$name" else clients.keys.lastOrNull { it.startsWith("$who|") } ?: "$who|"
            clients.remove(key)
            clients[key] = now
            clients.entries.removeAll { now - it.value > RECENT_MS }
            _activity.value = RemoteActivity(clients.size, now)
        }
    }

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
