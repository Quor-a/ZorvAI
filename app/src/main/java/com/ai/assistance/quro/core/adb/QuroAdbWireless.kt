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

    // ═══════════════ 保活：端口记忆 + 自动重连 ═══════════════
    //
    // 🔴 为什么需要这一层（用户问「关了 wifi 怎么保活 ADB 调试」）：
    // 配对与连接都走 127.0.0.1 回环（见 LOOPBACK），所以**连接本身不依赖 wifi 的 IP**。
    // 真正会断的是 mDNS 发现：NsdAdbServiceFinder 靠系统 mDNS 找 `_adb-tls-connect._tcp`，
    // 而 mDNS 走网络接口 —— 关掉 wifi / 切数据 / 开关飞行模式后，mDNS 找不到端口了，
    // 但 adbd 进程往往还在、端口号也没变。
    // 于是「记住上次成功的端口，mDNS 失败时直接用它重连」就能把连接救回来。
    //
    // 仍然连不上的情形（adbd 随 wifi 一起停了）：只能用 Shizuku/root 把 adbd 重新拉起来
    // （QuroAdbDebug.enableTcpAdb 走 setprop service.adb.tcp.port + 重启 adbd），
    // 那条路把接口固定到所有地址含 localhost，关 wifi 依然通。

    private const val PREFS = "quro_adb_wireless"
    private const val KEY_PORT = "last_connect_port"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 记住成功的连接端口（Android 11+ 端口是随机的，但一次会话内基本稳定）。 */
    private fun rememberPort(ctx: Context, port: Int) {
        runCatching { prefs(ctx).edit().putInt(KEY_PORT, port).apply() }
    }

    /** 上次成功连接用过的端口；没有返回 -1。 */
    fun lastConnectPort(ctx: Context): Int = runCatching { prefs(ctx).getInt(KEY_PORT, -1) }.getOrDefault(-1)

    /** 清除端口记忆（配对被撤销 / 系统重置了无线调试时用）。 */
    fun forgetPort(ctx: Context) {
        runCatching { prefs(ctx).edit().remove(KEY_PORT).apply() }
    }

    /**
     * **保活入口**：需要跑命令/查状态时先调它，不要直接假设连接还活着。
     *
     * 顺序：已连直接用 → 记住的端口重连 → mDNS 重新发现 → 再连一次。
     * 返回 true 表示连接可用。
     */
    fun ensureConnected(context: Context): Boolean {
        if (isConnected) return true

        // ① 先试上次成功的端口：关 wifi 后 mDNS 没了，但端口通常没变，这是最常见能救回来的情况
        val remembered = lastConnectPort(context)
        if (remembered > 0) {
            val r = connect(context, remembered)
            if (isConnected) {
                Log.i(TAG, "ensureConnected：用记住的端口 $remembered 重连成功（无需 mDNS）")
                return true
            }
            Log.d(TAG, "ensureConnected：记住的端口 $remembered 已失效 → ${r.take(80)}")
        }

        // ② mDNS 重新发现端口（系统「无线调试」还开着时有效）
        val r = connect(context, null)
        if (isConnected) {
            Log.i(TAG, "ensureConnected：mDNS 重新发现成功")
            return true
        }
        Log.w(TAG, "ensureConnected 失败：${r.take(120)}")
        return false
    }

    /** 心跳：连接还在但可能已经半死（对端 adbd 挂了），发一条轻量命令探活。 */
    fun heartbeat(context: Context): Boolean {
        if (!isConnected) return ensureConnected(context)
        return runCatching {
            val out = exec("echo ok", timeoutMs = 3_000)
            out.contains("ok")
        }.getOrDefault(false).also { alive ->
            if (!alive) {
                Log.w(TAG, "heartbeat 失败 → 丢弃旧连接，等下次 ensureConnected 重连")
                runCatching { connection?.close() }
                connection = null
            }
        }
    }


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
            // 记住端口：关 wifi / 切网络后 mDNS 会失效，靠这个端口把连接救回来（见 ensureConnected）
            rememberPort(context, target)
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
