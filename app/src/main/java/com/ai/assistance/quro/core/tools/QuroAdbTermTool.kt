package com.ai.assistance.quro.core.tools

import android.content.Context
import com.ai.assistance.quro.core.adb.QuroAdbDebug
import com.ai.assistance.quro.core.privilege.QuroRootGateway
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

/**
 * ADB 终端工具（#565）：把本机变成可被 ADB 控制的终端（无线调试中枢）。
 *
 * 复用既有 [QuroAdbDebug]（原创、无外部依赖）：
 * - action=shell：经特权通道(Shizuku/ROOT)以 root 执行命令，等价于「本机 ADB shell」；
 * - action=tcp_status / tcp_enable / tcp_disable：管理 TCP adbd（无线 ADB），给出连接信息供电脑 `adb connect`。
 *
 * 启停 TCP adbd 必须经 root 或 Shizuku（写系统属性 + 管 adbd 服务）；无提权通道时返回引导。
 */
class QuroAdbTermTool : QuroTool {
    override val name = "adb_term"
    override val description =
        "把本机变成可被 ADB 控制的终端（无线调试中枢）。" +
            "action=shell 经特权通道(Shizuku/ROOT)以 root 执行命令，等价于「本机 ADB shell」；" +
            "action=tcp_status 查看 TCP adbd 监听端口与局域网 IP；" +
            "action=tcp_enable 开启无线 ADB（port 默认 5555）；action=tcp_disable 关闭无线 ADB。" +
            "需 Shizuku 或 ROOT 才能静默启停 TCP adbd，否则返回引导。" +
            "参数 {\"action\":\"shell|tcp_status|tcp_enable|tcp_disable\",\"command\":\"shell 命令\",\"port\":5555,\"timeout_ms\":15000}。"
    override val parametersJson = """{
        "type":"object",
        "properties":{
            "action":{"type":"string","description":"shell=以 root 执行命令(本机 ADB shell) / tcp_status=查监听 / tcp_enable=开无线ADB / tcp_disable=关无线ADB"},
            "command":{"type":"string","description":"shell 时的命令"},
            "port":{"type":"integer","description":"tcp_enable 时的端口，默认 5555"},
            "timeout_ms":{"type":"integer","description":"shell 超时毫秒，默认 15000"}
        },
        "required":["action"]
    }"""

    override fun run(context: Context, arguments: String): String {
        val jo = runCatching { JSONObject(arguments) }.getOrElse { return "参数不是合法 JSON：$arguments" }
        val action = jo.optString("action", "").trim().lowercase()
        return when (action) {
            "shell" -> runCatching {
                val cmd = jo.optString("command", "").trim()
                if (cmd.isEmpty()) return "shell 缺少 command"
                val timeout = jo.optLong("timeout_ms", QuroRootGateway.DEFAULT_TIMEOUT_MS).coerceIn(1000, 60000)
                runBlocking(Dispatchers.IO) {
                    QuroAdbDebug.shell(context, cmd, timeout).render()
                }
            }.getOrElse { "adb_term shell 异常：${it.message}" }
            "tcp_status" -> runCatching {
                runBlocking(Dispatchers.IO) { tcpStatus(context) }
            }.getOrElse { "adb_term 状态查询异常：${it.message}" }
            "tcp_enable" -> runCatching {
                val port = jo.optInt("port", QuroAdbDebug.DEFAULT_PORT).coerceIn(1, 65535)
                runBlocking(Dispatchers.IO) {
                    val r = QuroAdbDebug.setTcpAdb(context, true, port)
                    val listening = QuroAdbDebug.isAdbdListening(context, port)
                    val ip = QuroAdbDebug.wifiIp(context) ?: "（无 WiFi）"
                    buildString {
                        append("启用结果：\n").append(r.render()).append('\n')
                        append("监听确认：${if (listening) "已在 :$port 监听" else "尚未确认监听（可稍后 tcp_status 复查）"}\n")
                        append("电脑端连接：adb connect $ip:$port")
                    }
                }
            }.getOrElse { "adb_term 开启无线ADB 异常：${it.message}" }
            "tcp_disable" -> runCatching {
                runBlocking(Dispatchers.IO) {
                    QuroAdbDebug.setTcpAdb(context, false, 0).render()
                }
            }.getOrElse { "adb_term 关闭无线ADB 异常：${it.message}" }
            else -> "未知 action: $action（支持 shell / tcp_status / tcp_enable / tcp_disable）"
        }
    }

    private fun tcpStatus(ctx: Context): String {
        val port = QuroAdbDebug.currentTcpPort(ctx)
        val ip = QuroAdbDebug.wifiIp(ctx)
        return buildString {
            append("TCP adbd：${if (port > 0) "监听 :$port" else "未启用"}\n")
            append("局域网 IP：${ip ?: "无 WiFi"}\n")
            append("特权通道：${if (QuroAdbDebug.hasPrivilegedChannel()) "可用（Shizuku/ROOT）" else "无（无法静默启停 TCP adbd）"}\n")
            append("USB 调试：${QuroAdbDebug.usbDebugEnabled(ctx) ?: "未知"}\n")
            if (port > 0 && ip != null) append("电脑端可：adb connect $ip:$port")
        }.trim()
    }
}
