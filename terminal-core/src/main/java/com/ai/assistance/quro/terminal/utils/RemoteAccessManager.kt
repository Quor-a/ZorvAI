package com.ai.assistance.quro.terminal.utils

import android.util.Log
import java.net.InetSocketAddress
import java.net.Socket

/**
 * 终端远程访问（SSH / VNC）管理器。
 *
 * ## 定位
 * SSH / VNC 服务端**真正内置在 App host 进程**里（见 [BuiltinSshServer] / [HostVncServer]），
 * 监听 127.0.0.1:2222（SSH）与 127.0.0.1:5901（VNC），供**本机**客户端连接
 * （本地连本地，回环地址 127.0.0.1，不需要任何 WiFi / 网卡，外部连接走内网穿透/公网）。
 *
 * 这是「没有连接环境也能连」的关键：服务端不依赖 proot / Ubuntu 是否就绪，
 * 后端 shell 由 [com.ai.assistance.quro.terminal.runtime.TerminalEnvironment.backendShellCommand]
 * 在 Ubuntu rootfs 就绪时桥接 proot 登录 shell、否则退化为 host 的 busybox sh。
 *
 * ## 本类的职责（仅限轻量工具）
 * - [connectAddresses] / [primaryIpv4]：回环地址（本地连本地，永远存在）。
 * - [probe] / [sshProbe] / [vncProbe]：TCP 探针读服务 banner（`SSH-2.0-` / `RFB 003.`），
 *   判定「是否真的在监听」—— 界面状态以此为准，而非看进程名或提示符。
 * - 端口 / 密码常量：[SSH_PORT] / [VNC_PORT] / [LOGIN_PASSWORD]。
 *
 * ## 端口选择
 * 端口 >1024，避免绑定特权端口需要的 CAP_NET_BIND_SERVICE（host 普通进程无此能力）。
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

    /** 一个可用的连接地址（本地回环，本地连本地无需任何网络）。 */
    data class NetAddr(val ip: String, val iface: String)

    // ---------------------------------------------------------------- 连接地址

    /**
     * 连接地址：本地连本地。
     *
     * 服务端（proot 里的 sshd / Xvnc）和客户端都在**同一台设备**上，根本不需要 WiFi /
     * 热点 / 任何网卡 —— 直接用回环地址 `127.0.0.1` 即可。回环地址永远存在，所以界面
     * 不会再出现「本机 IP 显示 —」的问题（那是旧实现强行去要一个局域网地址、本机没连网
     * 就空了，方向整个错了）。
     */
    fun connectAddresses(): List<NetAddr> = listOf(NetAddr("127.0.0.1", "localhost"))

    /** 首选连接地址：恒为回环地址。 */
    fun primaryIpv4(): NetAddr = connectAddresses().first()

    fun sshConnectionInfo(ip: String?): String =
        "ssh root@${ip ?: "127.0.0.1"} -p $SSH_PORT  （密码 $LOGIN_PASSWORD）"

    fun vncConnectionInfo(ip: String?): String =
        "${ip ?: "127.0.0.1"}:$VNC_PORT  （密码 $LOGIN_PASSWORD）"

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
}
