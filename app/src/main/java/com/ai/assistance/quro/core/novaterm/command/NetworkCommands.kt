package com.ai.assistance.quro.core.novaterm.command

import com.ai.assistance.quro.core.novaterm.core.*
import java.net.InetAddress
import java.net.URL
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * 网络命令集
 */
object PingCommand : BuiltinCommand {
    override val name = "ping"
    override fun execute(sessionId: String, cmd: Command): CommandResult {
        val host = cmd.getArg(0)
        if (host.isEmpty()) return CommandResult.err("ping: missing host")

        val count = cmd.getOption("-c", "4").toIntOrNull() ?: 4

        return try {
            // 优先调用系统 ping（真实 ICMP + 设备 DNS 解析）
            val pb = Runtime.getRuntime().exec(
                arrayOf("/system/bin/ping", "-c", count.toString(), host)
            )
            val out = pb.inputStream.bufferedReader().readText()
            val err = pb.errorStream.bufferedReader().readText()
            val code = pb.waitFor()
            if (code == 0) {
                CommandResult.ok(out.trim())
            } else {
                // 多数非 root 应用无 CAP_NET_RAW，系统 ping 会失败 → 回落 TCP 连通性探测
                tcpProbe(host, count)
            }
        } catch (e: Exception) {
            tcpProbe(host, count)
        }
    }

    /**
     * TCP 连通性探测：连目标 443（失败再试 80），只要有 INTERNET 权限即稳定可用。
     * 给出可达性 + 近似 RTT，作为 ICMP ping 不可用时的最小可用替代。
     */
    private fun tcpProbe(host: String, count: Int): CommandResult {
        return try {
            val addr = InetAddress.getByName(host)
            val lines = mutableListOf<String>()
            lines.add("PING $host (${addr.hostAddress}): 56 data bytes (TCP probe)")
            var success = 0
            var total = 0L
            for (i in 1..count) {
                val start = System.nanoTime()
                val ok = try {
                    java.net.Socket().use { s -> s.connect(java.net.InetSocketAddress(addr, 443), 3000); true }
                } catch (_: Exception) {
                    try {
                        java.net.Socket().use { s -> s.connect(java.net.InetSocketAddress(addr, 80), 3000); true }
                    } catch (_: Exception) { false }
                }
                val elapsed = (System.nanoTime() - start) / 1000000
                if (ok) {
                    success++; total += elapsed
                    lines.add("${addr.hostAddress} seq=$i time=${elapsed}ms")
                } else {
                    lines.add("${addr.hostAddress} seq=$i timeout")
                }
            }
            lines.add("")
            lines.add("--- $host ping statistics ---")
            lines.add("$count packets transmitted, $success received, ${100 - (success * 100 / count)}% packet loss")
            if (success > 0) lines.add("approx avg time: ${total / success}ms (TCP/443·80)")
            CommandResult.ok(lines.joinToString("\n"))
        } catch (e: Exception) {
            CommandResult.err("ping: ${e.message}")
        }
    }
    override fun help() = "ping [-c <count>] <host>  - 测试网络连通性（系统 ping，回落 TCP 探测）"
}

object CurlCommand : BuiltinCommand {
    override val name = "curl"
    override fun execute(sessionId: String, cmd: Command): CommandResult {
        val url = cmd.getArg(0)
        if (url.isEmpty()) return CommandResult.err("curl: missing URL")

        val method = cmd.getOption("-X", "GET")
        val headers = cmd.hasFlag("-i") || cmd.hasFlag("--headers")

        return try {
            val conn = URL(url).openConnection() as java.net.HttpURLConnection
            conn.requestMethod = method
            conn.connectTimeout = 10000
            conn.readTimeout = 15000

            val code = conn.responseCode
            val body = conn.inputStream.bufferedReader().readText()

            if (headers) {
                val headerLines = conn.headerFields.entries.joinToString("\n") { "${it.key}: ${it.value.joinToString(", ")}" }
                CommandResult.ok("HTTP/1.1 $code\n$headerLines\n\n$body")
            } else {
                CommandResult.ok(body)
            }
        } catch (e: Exception) {
            CommandResult.err("curl: ${e.message}")
        }
    }
    override fun help() = "curl [-X <method>] [-i] <url>  - 发起 HTTP 请求"
}

object DnsCommand : BuiltinCommand {
    override val name = "dns"
    override fun execute(sessionId: String, cmd: Command): CommandResult {
        val host = cmd.getArg(0)
        if (host.isEmpty()) return CommandResult.err("dns: missing host")

        return try {
            val addr = InetAddress.getAllByName(host)
            val lines = addr.mapIndexed { i, a ->
                "${i + 1}. ${a.hostName} -> ${a.hostAddress}  (${if (a is InetAddress) "IPv4" else "IPv6"})"
            }
            CommandResult.ok(lines.joinToString("\n"))
        } catch (e: Exception) {
            CommandResult.err("dns: ${e.message}")
        }
    }
    override fun help() = "dns <host>  - DNS 解析"
}
