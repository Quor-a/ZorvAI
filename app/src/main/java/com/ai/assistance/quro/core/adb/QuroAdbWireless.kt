package com.ai.assistance.quro.core.adb

import android.content.Context
import android.util.Log
import com.ai.assistance.quro.core.adb.mika.AdbConnection
import com.ai.assistance.quro.core.adb.mika.AdbPairing
import com.ai.assistance.quro.core.adb.mika.AdbServiceTypes
import com.ai.assistance.quro.core.adb.mika.AndroidAdb
import com.ai.assistance.quro.core.adb.mika.NsdAdbServiceFinder
import kotlinx.coroutines.runBlocking

/**
 * 无线调试（Android 11+）门面：**配对码配对 + TLS 连接 + 执行命令**。
 *
 * 实现直接使用移植自 Mika 的 `core/adb/mika` 包（纯 Kotlin，无第三方密码学依赖）：
 *  - [AdbPairing]：TLS 1.3 → 导出 64B keying material（label `adb-label\0`）→ 口令 = 配对码 + 导出密钥
 *    → SPAKE2 → AES-GCM 互换 PeerInfo
 *  - [AdbConnection]：TLS 通道上跑标准 ADB 协议
 *  - [NsdAdbServiceFinder]：用系统 mDNS 找 `_adb-tls-pairing._tcp` / `_adb-tls-connect._tcp` 的端口
 *
 * 与「USB / TCP 5555」的关系：那两条走的是 [QuroAdbManager]（明文/无配对）；
 * 这条是**加密 + 免数据线**，也是 Android 11+ 官方唯一支持的方式。
 *
 * 注意：配对端口开的时间很短（用户关掉对话框就没了），且必须用**本机自己**的无线调试页面上的码。
 */
object QuroAdbWireless {

    private const val TAG = "QuroAdbWireless"
    private const val LOOPBACK = "127.0.0.1"

    @Volatile
    private var connection: AdbConnection? = null

    @Volatile
    private var paired: Boolean = false

    val isConnected: Boolean get() = connection?.isClosed == false
    val isPaired: Boolean get() = paired

    private fun identity(context: Context) = AndroidAdb.identity(context)

    private fun tls(context: Context) = AndroidAdb.tls(identity(context))

    /** 找配对服务端口（`_adb-tls-pairing._tcp`）。配对对话框必须开着。 */
    fun findPairingPorts(context: Context, timeoutMs: Long = 8_000): List<Int> = runBlocking {
        runCatching {
            NsdAdbServiceFinder(context).findPorts(AdbServiceTypes.PAIRING, timeoutMs)
        }.getOrElse {
            Log.w(TAG, "findPairingPorts 失败: ${it.message}")
            emptyList()
        }
    }

    /** 找连接服务端口（`_adb-tls-connect._tcp`）。无线调试开着就有。 */
    fun findConnectPorts(context: Context, timeoutMs: Long = 8_000): List<Int> = runBlocking {
        runCatching {
            NsdAdbServiceFinder(context).findPorts(AdbServiceTypes.CONNECT, timeoutMs)
        }.getOrElse {
            Log.w(TAG, "findConnectPorts 失败: ${it.message}")
            emptyList()
        }
    }

    /**
     * 用配对码配对。
     *
     * @param port 配对端口；传 null 则自动 mDNS 发现
     * @param code 设备「无线调试 → 使用配对码配对设备」弹窗里的 6 位数字
     */
    fun pair(context: Context, port: Int?, code: String, timeoutMs: Int = 15_000): String {
        val cleanCode = code.trim()
        if (cleanCode.isEmpty()) return "缺少配对码（6 位数字）"

        val target = port ?: findPairingPorts(context).firstOrNull()
            ?: return "没找到配对服务端口。请确认：\n" +
                "1) 已在「开发者选项 → 无线调试」里打开「使用配对码配对设备」弹窗（它会很快关闭）；\n" +
                "2) 手机与目标在同一网络（本机就是目标，通常是本机自己）。\n" +
                "→ 也可以手动填配对端口再试。"

        return runCatching {
            val peer = AdbPairing(tls(context)).pair(LOOPBACK, target, cleanCode, timeoutMs)
            paired = true
            "✅ 配对成功（端口 $target）；设备回报 type=${peer.type} data=${peer.data.take(40)}"
        }.getOrElse { t ->
            paired = false
            "❌ 配对失败（端口 $target）：${t.message}\n" +
                "常见原因：配对码输错/已过期；配对弹窗已关闭；端口不是配对的端口。\n" +
                "→ 重新打开「使用配对码配对设备」，拿新码再试一次。"
        }
    }

    /**
     * 建立无线调试连接（TLS）。
     *
     * @param port 连接端口；传 null 则自动 mDNS 发现
     */
    fun connect(context: Context, port: Int? = null, timeoutMs: Int = 10_000): String {
        if (isConnected) return "已连接（${connection?.banner?.take(60)}）"

        val target = port ?: findConnectPorts(context).firstOrNull()
            ?: return "没找到无线调试连接端口。请确认「开发者选项 → 无线调试」已打开，" +
                "且已用「使用配对码配对设备」完成过一次配对。\n→ 也可以手动填端口再试。"

        return runCatching {
            val conn = AdbConnection.open(LOOPBACK, target, tls(context), timeoutMs)
            connection = conn
            "✅ 已连接无线调试（端口 $target）\n设备：${conn.banner.take(120)}"
        }.getOrElse { t ->
            "❌ 连接失败（端口 $target）：${t.message}\n" +
                "如果是认证失败：设备不认这把钥匙 → 重新配对一次（系统设置里「撤销 USB 调试授权」也会清掉无线配对）。"
        }
    }

    /** 在已建立的无线调试连接上执行一条命令。 */
    fun exec(command: String, timeoutMs: Int = 60_000): String {
        if (command.isBlank()) return "缺少 command"
        val conn = connection ?: return "还没建立无线调试连接（先 pair，再 connect）"
        return runCatching {
            val stream = conn.exec(command)
            stream.use { it.readText() }
        }.getOrElse { "执行失败：${it.message}" }
    }

    fun disconnect() {
        runCatching { connection?.close() }
        connection = null
    }

    /** 解除配对：删掉本地密钥（设备侧的授权记录要在系统设置里撤销）。 */
    fun forget(context: Context) {
        disconnect()
        runCatching { AndroidAdb.forget(context) }
        paired = false
        Log.i(TAG, "已清除本地配对身份")
    }

    /** 状态摘要（给工具/UI 用）。 */
    fun status(context: Context): String = buildString {
        appendLine("无线调试连接：${if (isConnected) "✅ 已连接" else "❌ 未连接"}")
        appendLine("配对状态：${if (paired) "✅ 已配对（本次进程内）" else "未配对或未在本进程配对过"}")
        val connectPorts = findConnectPorts(context, 3_000)
        appendLine("连接服务端口：${if (connectPorts.isEmpty()) "未发现（无线调试可能没开）" else connectPorts.joinToString()} ")
        val pairPorts = findPairingPorts(context, 3_000)
        appendLine("配对服务端口：${if (pairPorts.isEmpty()) "未发现（配对弹窗没开）" else pairPorts.joinToString()} ")
        append("密钥身份：${runCatching { identity(context).name }.getOrElse { "读取失败" }}")
    }.trim()
}
