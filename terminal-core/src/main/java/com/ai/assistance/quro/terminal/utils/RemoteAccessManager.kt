package com.ai.assistance.quro.terminal.utils

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.Inet4Address

/**
 * 终端远程访问（SSH / VNC）管理器。
 *
 * ## 定位
 * 在 proot + Ubuntu 环境内安装并启动 sshd / vncserver，把 Ubuntu 文件系统
 * 通过 SSH（端口 2222）与 VNC（显示 :1 → 端口 5901）暴露到局域网，
 * 供电脑端 `ssh` / VNC 客户端连接。命令统一注入到终端 PTY 内执行，不赌协议。
 *
 * ## 端口选择
 * 端口 >1024，避免 proot 伪 root（proot -0）下绑定特权端口需要 CAP_NET_BIND_SERVICE 的问题。
 */
object RemoteAccessManager {

    const val SSH_PORT = 2222
    const val VNC_DISPLAY = 1
    const val VNC_PORT = 5900 + VNC_DISPLAY

    /** 本机局域网 IPv4（非回环、去 scope id）。无 WiFi 时返回 null。 */
    fun localIpv4(context: Context): String? {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return null
        val net = cm.activeNetwork ?: return null
        val caps = cm.getNetworkCapabilities(net) ?: return null
        if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
            !caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        ) return null
        val link = cm.getLinkProperties(net) ?: return null
        for (la in link.linkAddresses) {
            val addr = la.address
            if (addr is Inet4Address && !addr.isLoopbackAddress) {
                return addr.hostAddress?.substringBefore('%')
            }
        }
        return null
    }

    /** 安装 OpenSSH 服务器。 */
    fun sshInstallCommand(): String =
        "apt-get update && apt-get install -y openssh-server"

    /** 配置并启动 sshd（在 proot Ubuntu 内监听 2222）。 */
    fun sshStartCommand(): String = run {
        val sb = StringBuilder()
        sb.append("mkdir -p /run/sshd && ")
        sb.append("ssh-keygen -A >/dev/null 2>&1; ")
        sb.append("grep -q '^PermitRootLogin yes' /etc/ssh/sshd_config || echo 'PermitRootLogin yes' >> /etc/ssh/sshd_config; ")
        sb.append("sed -i 's/^#\\?Port .*/Port $SSH_PORT/' /etc/ssh/sshd_config; ")
        sb.append("pkill sshd 2>/dev/null; sleep 1; ")
        sb.append("/usr/sbin/sshd -p $SSH_PORT >/dev/null 2>&1 &")
        sb.toString()
    }

    fun sshConnectionInfo(ip: String?): String {
        val host = ip ?: "本机IP"
        return "ssh root@$host -p $SSH_PORT"
    }

    /** 安装 TigerVNC（standalone server）。 */
    fun vncInstallCommand(): String =
        "apt-get update && apt-get install -y tigervnc-standalone-server"

    /** 配置并启动 vncserver（显示 :1 → 端口 5901，密码 ubuntu）。 */
    fun vncStartCommand(): String = run {
        val sb = StringBuilder()
        sb.append("mkdir -p ~/.vnc && ")
        sb.append("printf 'ubuntu\\nubuntu\\n' | vncpasswd -f > ~/.vnc/passwd 2>/dev/null; ")
        sb.append("chmod 600 ~/.vnc/passwd; ")
        sb.append("vncserver -kill :$VNC_DISPLAY >/dev/null 2>&1; sleep 1; ")
        sb.append("vncserver :$VNC_DISPLAY -geometry 1280x800 -depth 24 -localhost no")
        sb.toString()
    }

    fun vncConnectionInfo(ip: String?): String {
        val host = ip ?: "本机IP"
        return "$host:$VNC_PORT （密码 ubuntu）"
    }
}
