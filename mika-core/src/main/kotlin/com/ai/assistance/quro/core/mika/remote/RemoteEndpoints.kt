package com.ai.assistance.quro.core.mika.remote

import com.ai.assistance.quro.core.mika.device.DeviceAccess
import com.ai.assistance.quro.core.mika.mcp.McpRpc
import com.ai.assistance.quro.core.mika.mcp.McpTaskTools
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.InetAddress

/**
 * 已认证连接共用的协议入口。调用方先完成各自的认证:局域网由 RemoteAccess,出站隧道由已配对的 HTTPS 网关。
 * 不监听、不发网络请求、不判定来源可信度;两种传输共用任务用例与近期活动。
 * 手机屏幕能力(读锁屏状态、唤醒、解锁)由调用方逐个请求决定给不给:不给时这些工具和端点根本不出现,
 * 而不是出现了再拒绝。经网关来的请求默认不给,见 docs/dev/remote-mcp.md。
 */
class RemoteEndpoints(tasks: RemoteTasks, private val serverVersion: String, private val clock: () -> Long = System::currentTimeMillis,
    device: DeviceAccess? = null) {
    private val service = RemoteTaskService(tasks)
    private val clients = LinkedHashMap<String, Long>()
    private val mutable = MutableStateFlow(RemoteActivity())
    val activity = mutable.asStateFlow()

    /** 一套协议入口:任务 API 加 MCP。两套只差带不带手机屏幕能力,任务服务和近期活动共用。 */
    private inner class Surface(device: DeviceAccess?) {
        val api = RemoteTaskApi(service, serverVersion, device)
        val rpc = McpRpc(McpTaskTools(service, device), serverVersion) { remote, name -> seen(remote, name) }
    }
    private val withDevice = Surface(device)
    private val tasksOnly = if (device == null) withDevice else Surface(null)

    /** [deviceAccess] 为 false 时这次请求看不到唤醒、解锁和锁屏状态。 */
    fun handle(req: HttpRequest, deviceAccess: Boolean = true): HttpResponse {
        val surface = if (deviceAccess) withDevice else tasksOnly
        if (req.path == McpRpc.PATH) return surface.rpc.handle(req)
        if (req.path == RemoteTaskApi.ROOT || req.path.startsWith("${RemoteTaskApi.ROOT}/")) {
            seen(req.remote, req.header("x-quro-client")?.take(64))
            return surface.api.handle(req)
        }
        return RemoteTaskApi.error(404, "not_found", "No such endpoint")
    }

    private fun seen(remote: InetAddress, name: String?) {
        val now = clock()
        synchronized(this) {
            val who = remote.hostAddress ?: "?"
            val key = if (name != null) "$who|$name" else clients.keys.lastOrNull { it.startsWith("$who|") } ?: "$who|"
            clients.remove(key); clients[key] = now
            clients.entries.removeAll { now - it.value > RemoteHost.RECENT_MS }
            mutable.value = RemoteActivity(clients.size, now)
        }
    }
}
