package com.ai.assistance.quro.terminal.utils

import android.content.Context
import android.util.Log
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket

/**
 * 终端远程访问（SSH / VNC）管理器。
 *
 * ## 定位
 * 在 proot + Ubuntu 环境内安装并启动 sshd / vncserver，把 Ubuntu 文件系统
 * 通过 SSH（端口 2222）与 VNC（显示 :1 → 端口 5901）暴露到局域网，
 * 供电脑端 `ssh` / VNC 客户端连接。
 *
 * ## 相对旧实现的三处关键修正
 * 1. **执行通道**：不再把命令塞进终端会话的「命令队列」（那条路要靠提示符正则
 *    匹配才会推进，匹配不上就永远卡在「安装中」），改为经 proot 一次性执行
 *    通道 + 文件回执，拿真实退出码。见 [com.ai.assistance.quro.terminal.TerminalManager.runInGuest]。
 * 2. **真的能登录**：sshd 显式 `UsePAM no`（proot 下 PAM 必失败）+ 设置 root 密码；
 *    VNC 补 `xstartup` 与最小桌面（旧实现只装 tigervnc，连上是全黑屏）。
 * 3. **状态不靠猜**：直接用 TCP 探针读服务 banner（`SSH-2.0-` / `RFB 003.`）判定
 *    「是否真的在监听」，而不是看进程名或提示符。
 *
 * ## 端口选择
 * 端口 >1024，避免 proot 伪 root（proot -0）下绑定特权端口需要 CAP_NET_BIND_SERVICE 的问题。
 */
object RemoteAccessManager {

    private const val TAG = "RemoteAccessManager"

    const val SSH_PORT = 2222
    const val VNC_DISPLAY = 1
    const val VNC_PORT = 5900 + VNC_DISPLAY

    /** SSH / VNC 统一登录密码（与界面展示保持一致）。 */
    const val LOGIN_PASSWORD = "ubuntu"

    /** 安装阶段超时：apt 首次拉取在慢网络下可能数分钟。 */
    const val PROVISION_TIMEOUT_MS = 15 * 60 * 1000L

    /** 停止阶段超时。 */
    const val STOP_TIMEOUT_MS = 90 * 1000L

    enum class Kind(val title: String) {
        SSH("SSH"), VNC("VNC")
    }

    /** 一个可用的局域网地址。 */
    data class NetAddr(val ip: String, val iface: String)

    // ---------------------------------------------------------------- 网络地址

    /**
     * 枚举本机全部可用的局域网 IPv4。
     *
     * 旧实现只看 `ConnectivityManager.activeNetwork` 且要求 WIFI/ETHERNET transport，
     * 于是在热点、USB 共享、部分 ROM 上直接返回 null → 界面显示「—」，用户以为功能坏了。
     * 这里改为直接枚举网卡：热点（ap/swlan）、WiFi（wlan）、以太网、USB 共享都能拿到。
     */
    fun localIpv4All(): List<NetAddr> {
        val result = ArrayList<NetAddr>()
        try {
            val nifs = NetworkInterface.getNetworkInterfaces() ?: return emptyList()
            for (nif in nifs) {
                if (!nif.isUp || nif.isLoopback) continue
                val name = nif.name ?: continue
                val lower = name.lowercase()
                // 蜂窝 / 隧道 / 点对点：拿到的地址电脑端也访问不到，直接排除
                if (lower.startsWith("rmnet") || lower.startsWith("ccmni") ||
                    lower.startsWith("tun") || lower.startsWith("ppp") ||
                    lower.startsWith("p2p") || lower.startsWith("dummy")
                ) {
                    continue
                }
                val addrs = runCatching { nif.interfaceAddresses }.getOrNull() ?: continue
                for (ia in addrs) {
                    val addr = ia.address ?: continue
                    if (addr !is Inet4Address || addr.isLoopbackAddress) continue
                    val raw = addr.hostAddress ?: continue
                    val ip = raw.substringBefore('%')
                    if (ip.startsWith("169.254.")) continue // link-local，不可路由
                    result.add(NetAddr(ip, name))
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "枚举网卡失败", e)
        }
        return result.sortedBy { preference(it.iface) }.distinctBy { it.ip }
    }

    /** 网卡优先级：越小越优先（局域网可能性越大）。 */
    private fun preference(iface: String): Int {
        val n = iface.lowercase()
        return when {
            n.startsWith("wlan") -> 0
            n.startsWith("ap") || n.startsWith("swlan") -> 1
            n.startsWith("eth") -> 2
            n.startsWith("rndis") || n.startsWith("usb") -> 3
            else -> 9
        }
    }

    /** 首选局域网地址；没有可用地址时返回 null。 */
    fun primaryIpv4(): NetAddr? = localIpv4All().firstOrNull()

    fun sshConnectionInfo(ip: String?): String =
        "ssh root@${ip ?: "<本机IP>"} -p $SSH_PORT  （密码 $LOGIN_PASSWORD）"

    fun vncConnectionInfo(ip: String?): String =
        "${ip ?: "<本机IP>"}:$VNC_PORT  （密码 $LOGIN_PASSWORD）"

    // ---------------------------------------------------------------- 服务探针

    /**
     * TCP 探针：连上去读服务 banner。
     *
     * 服务端会先开口（sshd 发 `SSH-2.0-...`、Xvnc 发 `RFB 003.008`），
     * 所以「能读到预期前缀」= 服务确实在监听且是我们要的那个。
     */
    fun probeBanner(port: Int, expectPrefix: String, timeoutMs: Int = 1500): String? {
        var socket: Socket? = null
        return try {
            socket = Socket()
            socket.connect(InetSocketAddress("127.0.0.1", port), timeoutMs)
            socket.soTimeout = timeoutMs
            val input = socket.getInputStream()
            val buf = ByteArray(64)
            val read = input.read(buf)
            if (read <= 0) return null
            val text = String(buf, 0, read, Charsets.US_ASCII).trim()
            if (text.startsWith(expectPrefix)) text else null
        } catch (_: Throwable) {
            null
        } finally {
            runCatching { socket?.close() }
        }
    }

    /** SSH 是否已在监听（返回 sshd 的真实 banner）。 */
    fun sshProbe(): String? = probeBanner(SSH_PORT, "SSH-")

    /** VNC 是否已在监听（返回 Xvnc 的真实 banner）。 */
    fun vncProbe(): String? = probeBanner(VNC_PORT, "RFB ")

    fun probe(kind: Kind): String? = when (kind) {
        Kind.SSH -> sshProbe()
        Kind.VNC -> vncProbe()
    }

    // ---------------------------------------------------------------- guest 脚本

    fun provisionScript(context: Context, kind: Kind): String = when (kind) {
        Kind.SSH -> loadScript(context, "ssh_provision.sh")
        Kind.VNC -> loadScript(context, "vnc_provision.sh")
    }

    fun stopScript(context: Context, kind: Kind): String = when (kind) {
        Kind.SSH -> loadScript(context, "ssh_stop.sh")
        Kind.VNC -> loadScript(context, "vnc_stop.sh")
    }

    /**
     * 读取并填参 guest 侧脚本。
     *
     * 脚本以 assets 形式存放（如 assets/zxremote/ssh_provision.sh），避免在 Kotlin 字符串里
     * 与 shell 的 `$`、反引号、反斜杠互相打架；端口/密码等常量统一在此注入，
     * 保证 Kotlin 常量是唯一真源。
     */
    private fun loadScript(context: Context, name: String): String {
        val raw = try {
            context.assets.open("zxremote/$name").use { it.readBytes().toString(Charsets.UTF_8) }
        } catch (e: Throwable) {
            Log.e(TAG, "读取脚本失败: $name", e)
            "echo \"[remote] 缺少内置脚本 $name\"; exit 127\n"
        }
        return raw
            .replace("@SSH_PORT@", SSH_PORT.toString())
            .replace("@VNC_DISPLAY@", VNC_DISPLAY.toString())
            .replace("@VNC_PORT@", VNC_PORT.toString())
            .replace("@PASSWORD@", LOGIN_PASSWORD)
    }
}
