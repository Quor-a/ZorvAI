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
        // 127.0.0.1 的 hex = 0100007F；宿主侧 /proc/net/tcp 可读，最可靠且不依赖 netstat/ss。
        val portHex = String.format("%04X", port)
        val cmd = "grep -qiE \"[ :]0100007F:${portHex}[ ]+.*[ ]+0A[ ]\" /proc/net/tcp /proc/net/tcp6 2>/dev/null && echo LISTENING || echo CLOSED"
        val r = runCatching { QuroRootGateway.exec(ctx, cmd, QuroRootGateway.PROBE_TIMEOUT_MS) }.getOrNull()
        val out = r?.output ?: return false
        return out.contains("LISTENING")
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
