package com.ai.assistance.quro.core.adb

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.provider.Settings
import com.ai.assistance.quro.core.privilege.QuroRootGateway
import com.ai.assistance.quro.core.shizuku.QuroShizuku
import java.net.Inet4Address

/**
 * USB / 无线调试（ADB）能力助手（原创，无外部依赖）。
 *
 * ## 能力边界（对应"完整 ADB"诉求）
 * - 控制代码：经本应用终端 / 工具箱执行 shell 与脚本（既有能力）。
 * - 控制手机：本机以 ADB 客户端身份对系统发指令（需 root/Shizuku 才能静默执行，
 *   否则经系统设置引导用户授权）。
 * - 被电脑控制：在 root/Shizuku 下把 `adbd` 拉成 TCP 监听（`setprop service.adb.tcp.port`
 *   + `stop/start adbd`），电脑端 `adb connect <ip>:<port>` 即可接管本机。
 * - 被手机控制：本机也可作为 ADB 客户端去连其它设备 / 控制自身（提供连接信息 + 终端入口）。
 *
 * ## 通道
 * 启停 TCP adbd 必须经 root 或 Shizuku（写系统属性 + 管 adbd 服务）。无提权通道时，
 * 退回打开系统「无线调试 / 开发者选项」让用户手动配对（Android 11+ 无线调试配对码流程）。
 *
 * ## 线程模型
 * [currentTcpPort] / [setTcpAdb] / [isAdbdListening] 均**阻塞**（内部 spawn `su`/Shizuku Binder），
 * 必须在 IO 线程调用；UI 用 [withContext(Dispatchers.IO)] 包一层。
 */
object QuroAdbDebug {

    /** 无线 ADB 默认端口（与 `adb tcpip 5555` 对齐）。 */
    const val DEFAULT_PORT = 5555

    /** 是否具备提权通道（Shizuku 就绪 或 root 可用），可启动/关闭 TCP adbd。 */
    fun hasPrivilegedChannel(): Boolean =
        runCatching { QuroShizuku.isReady }.getOrDefault(false) ||
            runCatching { QuroRootGateway.isRootAvailable() }.getOrDefault(false)

    /** 当前 adbd TCP 监听端口（`getprop service.adb.tcp.port`）。<=0 或空 => 未启用。需 IO 线程。 */
    fun currentTcpPort(ctx: Context): Int =
        runCatching {
            val r = QuroRootGateway.exec(ctx, "getprop service.adb.tcp.port", QuroRootGateway.PROBE_TIMEOUT_MS)
            val v = r.output.trim().toIntOrNull() ?: 0
            if (v <= 0) 0 else v
        }.getOrDefault(0)

    /** 设备 WiFi 局域网 IP（点分十进制，已去掉 scope id）。无 root 也能拿。 */
    fun wifiIp(ctx: Context): String? {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return null
        val net = cm.activeNetwork ?: return null
        val caps = cm.getNetworkCapabilities(net) ?: return null
        if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return null
        val link = cm.getLinkProperties(net) ?: return null
        for (la in link.linkAddresses) {
            val addr = la.address
            if (addr is Inet4Address && !addr.isLoopbackAddress) {
                // 去掉可能存在的 "%wlan0" scope id
                return addr.hostAddress?.substringBefore('%')
            }
        }
        return null
    }

    /**
     * 设置 TCP adbd 开关。需 IO 线程。
     * @param enabled true=监听 TCP；false=关闭（端口置 -1）。
     * @return 结构化结果（含通道 / 输出 / 退出码）。
     */
    fun setTcpAdb(ctx: Context, enabled: Boolean, port: Int): QuroRootGateway.RootResult {
        val p = if (enabled) port.coerceIn(1, 65535) else -1
        // setprop 改监听端口；stop/start adbd 让新端口生效（init 上下文，su -c 下可用）。
        val cmd = "setprop service.adb.tcp.port $p; stop adbd; sleep 1; start adbd"
        return QuroRootGateway.exec(ctx, cmd)
    }

    /** adbd 是否真的在监听该端口（解析 /proc/net/tcp 的 LISTEN 状态）。需 IO 线程。 */
    fun isAdbdListening(ctx: Context, port: Int): Boolean {
        // Bug7 修复：原实现依赖 `ss -ltn` / `netstat -ltn`，但 Android 宿主 root shell 可能无 netstat/ss
        // （toybox/busybox 差异），且 proot 容器内 /proc/net/tcp 受 hidepid=invisible 限制不可读。
        // 改为直接解析 /proc/net/tcp：local_address 为 `IP:PORT`（hex），st 字段 0A=LISTEN。
        //
        // Bug 二次修复（监听状态误判为「未监听」）：原正则只匹配本地回环 0100007F(127.0.0.1)，
        // 而 adbd 在 TCP 模式下实际监听 0.0.0.0（00000000）以供局域网连接 —— 表现为
        //「无线 ADB 已开、电脑 adb connect 也连得上」，本应用却判为未监听。
        // 现按列锚定（sl / local_address / rem_address / st），不再关心具体绑定地址，IPv4+IPv6 通用。
        val portHex = String.format("%04X", port.coerceIn(0, 65535))
        val cmd = "grep -qiE \"^ *[0-9]+: [0-9A-F]+:${portHex} [0-9A-F]+:[0-9A-F]+ 0A \"" +
            " /proc/net/tcp /proc/net/tcp6 2>/dev/null && echo LISTENING || echo CLOSED"
        val r = runCatching { QuroRootGateway.exec(ctx, cmd, QuroRootGateway.PROBE_TIMEOUT_MS) }.getOrNull()
        val out = r?.output ?: return false
        return out.contains("LISTENING")
    }

    /**
     * 当前已连上本机 ADB 端口的控制端地址（解析 /proc/net/tcp 的 ESTABLISHED=01 连接）。
     * 用于回答「谁正在控制本机」——这是「被电脑/手机控制」闭环里此前缺失的一环。需 IO 线程。
     *
     * @return `ip:port` 列表（IPv6 带方括号）；无法读取 / 无连接时为空列表。
     */
    fun connectedClients(ctx: Context, port: Int): List<String> {
        if (port <= 0) return emptyList()
        val portHex = String.format("%04X", port.coerceIn(0, 65535))
        val cmd = "grep -iE \"^ *[0-9]+: [0-9A-F]+:${portHex} [0-9A-F]+:[0-9A-F]+ 01 \"" +
            " /proc/net/tcp /proc/net/tcp6 2>/dev/null || true"
        val r = runCatching { QuroRootGateway.exec(ctx, cmd, QuroRootGateway.PROBE_TIMEOUT_MS) }.getOrNull()
            ?: return emptyList()
        return r.output.lineSequence().mapNotNull { parseRemoteEndpoint(it) }.distinct().toList()
    }

    /**
     * USB 数据线是否实际连接（区别于「USB 调试开关是否打开」）。需 IO 线程。
     * 优先读 /sys/class/android_usb/android0/state（CONFIGURED / CONNECTED / DISCONNECTED），
     * 该文件不可读时退回 `getprop sys.usb.state`（含 adb/mtp 等即视为已连并启用了对应 USB 功能）。
     * 无法判定返回 null。
     */
    fun usbCableState(ctx: Context): String? {
        val r = runCatching {
            QuroRootGateway.exec(
                ctx,
                "cat /sys/class/android_usb/android0/state 2>/dev/null; echo '---'; getprop sys.usb.state",
                QuroRootGateway.PROBE_TIMEOUT_MS,
            )
        }.getOrNull() ?: return null
        val sysState = r.output.substringBefore("---").trim().lowercase()
        val prop = r.output.substringAfter("---", "").trim().lowercase()
        return when {
            sysState.contains("configured") -> "已连接（已配置）"
            sysState.contains("connected") -> "已连接"
            sysState.contains("disconnected") -> "未连接"
            prop.isNotBlank() && prop != "disconnected" && prop != "none" ->
                if (prop.contains("adb")) "已连接（ADB 功能已启用）" else "已连接（$prop）"
            else -> null
        }
    }

    /**
     * `adb devices -l` 输出（本机作为 ADB 客户端时列出已连接/已配对设备）。
     * 宿主不含 adb 二进制时返回 null——此时 UI 应引导用系统无线调试配对。需 IO 线程。
     */
    fun adbDevices(ctx: Context): String? {
        val r = runCatching {
            QuroRootGateway.exec(
                ctx,
                "command -v adb >/dev/null 2>&1 && adb devices -l || echo __NO_ADB__",
                QuroRootGateway.PROBE_TIMEOUT_MS,
            )
        }.getOrNull() ?: return null
        val out = r.output.trim()
        if (out.isBlank() || out.contains("__NO_ADB__")) return null
        return out
    }

    /** 解析 /proc/net/tcp(6) 的一行，返回 `远端IP:端口`；非数据行返回 null。 */
    private fun parseRemoteEndpoint(line: String): String? {
        val parts = line.trim().split(Regex("\\s+"))
        if (parts.size < 3) return null
        val rem = parts[2]
        val hexIp = rem.substringBefore(':')
        val hexPort = rem.substringAfter(':', "")
        if (hexIp.isBlank() || hexPort.isBlank()) return null
        val ip = hexToIp(hexIp) ?: return null
        val p = runCatching { hexPort.toInt(16) }.getOrNull() ?: return null
        return if (ip.contains(':')) "[$ip]:$p" else "$ip:$p"
    }

    /** /proc/net/tcp(6) 的十六进制地址（小端）转点分十进制 / IPv6 文本。 */
    private fun hexToIp(hex: String): String? = when (hex.length) {
        8 -> runCatching {
            (hex.length - 2 downTo 0 step 2)
                .joinToString(".") { hex.substring(it, it + 2).toInt(16).toString() }
        }.getOrNull()
        32 -> runCatching { decodeIpv6(hex) }.getOrNull()
        else -> null
    }

    private fun decodeIpv6(hex: String): String {
        // IPv4-mapped（::ffff:a.b.c.d）：前缀固定，后 32 位即 IPv4（同样按小端打印）
        if (hex.regionMatches(0, "0000000000000000FFFF0000", 0, 24, ignoreCase = true)) {
            val last8 = hex.takeLast(8)
            return (6 downTo 0 step 2).joinToString(".") { last8.substring(it, it + 2).toInt(16).toString() }
        }
        // 其余：128 位分 8 组，每组 4 字节小端
        return (0 until 8).joinToString(":") { i ->
            val g = hex.substring(i * 8, i * 8 + 8)
            (6 downTo 0 step 2).joinToString("") { g.substring(it, it + 2) }
        }
    }

    /** USB 调试是否已开启（Settings.Global ADB_ENABLED）。部分 ROM 受限返回 null。 */
    fun usbDebugEnabled(ctx: Context): Boolean? = runCatching {
        Settings.Global.getInt(ctx.contentResolver, Settings.Global.ADB_ENABLED) == 1
    }.getOrNull()

    /** 打开系统「开发者选项」页。 */
    fun openDeveloperOptions(ctx: Context) {
        runCatching {
            ctx.startActivity(
                Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    /** 打开系统「无线调试」页（Android 11+）；旧版本退回开发者选项。 */
    fun openWirelessDebugging(ctx: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching {
                ctx.startActivity(
                    Intent("android.settings.WIRELESS_DEBUGGING_SETTINGS")
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }.onFailure { openDeveloperOptions(ctx) }
        } else {
            openDeveloperOptions(ctx)
        }
    }

    /**
     * 经特权通道（Shizuku/ROOT）以 root 执行一条命令，等价于「本机 ADB shell」——即用户诉求里的
     * "控制代码 / 控制手机"：本应用自身作为 ADB 客户端对系统发指令（重启服务、改设置、读写系统分区等）。
     *
     * 走 [QuroRootGateway] 统一降级链（Shizuku-root → su），**阻塞**调用，必须在 IO 线程调用。
     *
     * @return [QuroRootGateway.RootResult]，含 exitCode / 合并后的 stdout+stderr / 实际通道
     */
    fun shell(
        ctx: Context,
        command: String,
        timeoutMs: Long = QuroRootGateway.DEFAULT_TIMEOUT_MS,
    ): QuroRootGateway.RootResult = QuroRootGateway.exec(ctx, command, timeoutMs, "capos.adb.shell")
}
