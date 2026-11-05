package com.ai.assistance.quro.core.adb

import android.content.Context
import android.util.Log
import com.ai.assistance.quro.core.adb.mika.AdbConnectPort
import com.ai.assistance.quro.core.adb.mika.AdbConnection
import com.ai.assistance.quro.core.adb.mika.AdbPairing
import com.ai.assistance.quro.core.adb.mika.AdbServiceTypes
import com.ai.assistance.quro.core.adb.mika.ConnectCandidates
import com.ai.assistance.quro.core.adb.mika.LocalPortScan
import com.ai.assistance.quro.core.adb.mika.NioPortScanner
import com.ai.assistance.quro.core.adb.mika.NsdAdbServiceFinder
import com.ai.assistance.quro.core.adb.mika.AndroidAdb
import com.ai.assistance.quro.core.adb.mika.PortCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/**
 * 无线调试（Android 11+）门面：**配对码配对 + TLS 连接 + 执行命令**。
 *
 * 实现使用移植自 Mika 的 `core/adb/mika` 包（纯 Kotlin，无第三方密码学依赖）：
 *  - [AdbPairing]：TLS 1.3 → 导出 64B keying material（label `adb-label\0`）→ 口令 = 配对码 + 导出密钥
 *    → SPAKE2 → AES-GCM 互换 PeerInfo
 *  - [AdbConnection]：TLS 通道上跑标准 ADB 协议
 *  - [NsdAdbServiceFinder]：用系统 mDNS 找 `_adb-tls-pairing._tcp` / `_adb-tls-connect._tcp` 的端口
 *  - [LocalPortScan]：**mDNS 找不到时的兜底**——扫本机临时端口，对每个开着的端口发 CNXN，
 *    只有回 STLS 的才算 adbd（见下文「为什么必须扫本机端口」）
 *
 * 与「USB / TCP 5555」的关系：那两条走 [QuroAdbManager]（明文/无配对）；
 * 这条是**加密 + 免数据线**，也是 Android 11+ 官方唯一支持的方式。
 *
 * ## 为什么必须扫本机端口（对齐 Mika 上游的核心一条）
 *
 * adbd 只在「打开无线调试」时向 mDNS **登记一次**服务。以下任一情况登记就没了，但 adbd
 * 进程和端口**都还在**：
 *  · mDNS 守护进程重启 / 系统回收；
 *  · 关过 Wi-Fi、切过数据卡、开关过飞行模式；
 *  · 部分路由器拦组播。
 *
 * 只靠 mDNS 的实现（本文件旧版就是）在这些场景下会一直报「没找到连接端口」，
 * 而实际上连接完全可用 —— 用户看到的是「昨天还能连，今天就废了」。
 * Mika 的做法是多路候选 + 最后直接扫本机端口，本文件现在按同样的顺序来：
 *   ① 记住的上次成功端口（失效时被拒得很快，排第一最划算）
 *   ② 系统属性里的端口（`service.adb.tls.port`，多数机型普通应用读不到 → 视为「没有」，不算失败）
 *   ③ mDNS 查到的**每一个**端口（可能有已失效的旧记录，逐个试）
 *   ④ 扫本机端口（前面都不行才扫；已经有端口明确拒过我们就不再扫）
 *   ⑤ 用户手填的端口
 */
object QuroAdbWireless {

    private const val TAG = "QuroAdbWireless"
    private const val LOOPBACK = "127.0.0.1"

    /**
     * mDNS 的等待上限。
     *
     * 旧实现是 8s（`findPairingPorts` / `findConnectPorts` 各 8s，[status] 还各调一次 = 最多 16s）。
     * 而在工具调用路径上这是**同步阻塞**——用户侧表现就是「问一句 ADB 状态，AI 卡十几秒」。
     * Mika 用 3s：系统 mDNS 通常 300ms 内就回报，等一下只是给「同时公布多条」留收齐的时间。
     */
    private const val MDNS_TIMEOUT_MS = 3_000L

    /** [status] 里的探测预算（比正式连接更短，状态查询不该让人等）。 */
    private const val STATUS_PROBE_MS = 2_000L

    /** 扫本机端口的总预算（含 TCP 连接与 CNXN/STLS 核对）。 */
    private const val SCAN_BUDGET_MS = 9_000L

    @Volatile
    private var connection: AdbConnection? = null

    @Volatile
    private var paired: Boolean = false

    /** 最近一次找端口的各来源候选数（诊断用，[status] 会打出来）。 */
    @Volatile
    private var lastCandidates: ConnectCandidates? = null

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
    // 于是「记住上次成功的端口 → 直接用它重连」+「扫本机端口」这两条就能把连接救回来。
    //
    // 仍然连不上的情形（adbd 随 wifi 一起停了）：只能用 Shizuku/root 把 adbd 重新拉起来
    // （QuroAdbDebug.enableTcpAdb 走 setprop service.adb.tcp.port + 重启 adbd）。

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

    // ═══════════════ 候选来源 ═══════════════

    /**
     * 系统属性里的 adbd 端口。
     *
     * 走反射是因为 `android.os.SystemProperties` 不在 SDK 里。**读不到是常态**
     * （普通应用没有读 `service.*` 的 SELinux 权限），所以失败一律返回 null ——
     * 这只是「多一条候选」，绝不能因为它失败就判定连接失败。
     */
    private fun propertyPort(): Int? = runCatching {
        val cls = Class.forName("android.os.SystemProperties")
        val getString = cls.getMethod("get", String::class.java)
        for (key in PROPERTY_KEYS) {
            val raw = (getString.invoke(null, key) as? String)?.trim().orEmpty()
            val port = raw.toIntOrNull() ?: continue
            if (port in 1..65535) return@runCatching port
        }
        null
    }.getOrNull()

    private fun portCache(ctx: Context): PortCache = object : PortCache {
        override fun load(): Int? = lastConnectPort(ctx).takeIf { it > 0 }
        override fun save(port: Int) = rememberPort(ctx, port)
    }

    /**
     * 读本机临时端口范围（`/proc/sys/net/ipv4/ip_local_port_range`）。
     * 读不到就用 [LocalPortScan.FALLBACK_RANGE]（30000..65535）。
     */
    private fun localPortRange(): IntRange = runCatching {
        val text = java.io.File("/proc/sys/net/ipv4/ip_local_port_range")
            .takeIf { it.canRead() }?.readText()
        LocalPortScan.parseRange(text)
    }.getOrDefault(LocalPortScan.FALLBACK_RANGE)

    /**
     * 组装一次「找端口 + 连接」的编排器（Mika 的 [AdbConnectPort]）。
     *
     * @param manualPort 用户这次手填的端口；非 null 时不再扫本机（既然扫不到才让他填的，直接试它）
     */
    private fun connector(ctx: Context, manualPort: Int? = null): AdbConnectPort = AdbConnectPort(
        property = { propertyPort() },
        finder = NsdAdbServiceFinder(ctx),
        cache = portCache(ctx),
        mdnsTimeoutMs = MDNS_TIMEOUT_MS,
        report = { c ->
            lastCandidates = c
            Log.i(
                TAG,
                "找端口结果：缓存=${c.cache} 属性=${c.property} mDNS=${c.mdns} " +
                    "扫描=${if (c.scanned) "${c.scanOpen} 开/${c.scanAdb} 像 adbd" else "未扫"} " +
                    "手填=${c.manual} → ${if (c.connected) "已连上" else "未连上"}",
            )
        },
        scan = LocalPortScan(
            scanner = NioPortScanner(),
            // 只扫本机：adbd 可能只绑 loopback，也可能绑 Wi-Fi 地址，loopback 优先且足够
            hosts = { listOf(LOOPBACK) },
            range = { localPortRange() },
            budgetMs = SCAN_BUDGET_MS,
        ),
        manual = { manualPort },
    )

    // ═══════════════ 连接 / 保活 ═══════════════

    /**
     * **保活入口**：需要跑命令/查状态时先调它，不要直接假设连接还活着。
     *
     * 返回 true 表示连接可用。内部按 Mika 的候选顺序走（缓存 → 属性 → mDNS → 扫本机）。
     */
    fun ensureConnected(context: Context): Boolean {
        if (isConnected) return true
        return runCatching {
            val conn = runBlocking(Dispatchers.IO) {
                connector(context).connect { host, port -> AdbConnection.open(host, port, tls(context)) }
            }
            connection = conn
            Log.i(TAG, "ensureConnected：已连上（${conn.banner.take(60)}）")
            true
        }.getOrElse {
            Log.w(TAG, "ensureConnected 失败：${it.message}")
            false
        }
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
    fun findPairingPorts(context: Context, timeoutMs: Long = MDNS_TIMEOUT_MS): List<Int> = runBlocking {
        runCatching {
            NsdAdbServiceFinder(context).findPorts(AdbServiceTypes.PAIRING, timeoutMs)
        }.getOrElse {
            Log.w(TAG, "findPairingPorts 失败: ${it.message}")
            emptyList()
        }
    }

    /** 找连接服务端口（`_adb-tls-connect._tcp`）。无线调试开着就有。 */
    fun findConnectPorts(context: Context, timeoutMs: Long = MDNS_TIMEOUT_MS): List<Int> = runBlocking {
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
     * @param port 连接端口；传 null 则按 [connector] 的多路候选自动找
     */
    fun connect(context: Context, port: Int? = null, timeoutMs: Int = 10_000): String {
        if (isConnected) return "已连接（${connection?.banner?.take(60)}）"

        return runCatching {
            val conn = runBlocking(Dispatchers.IO) {
                connector(context, manualPort = port).connect { host, p ->
                    AdbConnection.open(host, p, tls(context), timeoutMs)
                }
            }
            connection = conn
            // 记住端口：关 wifi / 切网络后 mDNS 会失效，靠这个端口把连接救回来（见 ensureConnected）
            "✅ 已连接无线调试（${conn.banner.take(120)}）"
        }.getOrElse { t ->
            val rejected = t is com.ai.assistance.quro.core.adb.mika.AdbConnectionException &&
                t.reason == com.ai.assistance.quro.core.adb.mika.AdbConnectionException.Reason.UNAUTHORIZED
            if (rejected) {
                "❌ 连接被设备拒绝（钥匙不被认）：${t.message}\n" +
                    "→ 重新配对一次（系统设置里「撤销 USB 调试授权」也会清掉无线配对）。\n" +
                    "→ 若刚配对成功就报这个：可能是别的服务抢了第一次 TLS 握手，已自动重试过一次；\n" +
                    "   仍不行就关掉再打开「无线调试」重配。"
            } else {
                "❌ 连接失败：${t.message}\n" +
                    "已按「上次端口 → 系统属性 → mDNS → 扫本机端口」全部试过。\n" +
                    "→ 确认「开发者选项 → 无线调试」还开着；若端口变了，重新配对一次。"
            }
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
        forgetPort(context)
        paired = false
        Log.i(TAG, "已清除本地配对身份")
    }

    /** 状态摘要（给工具/UI 用）。 */
    fun status(context: Context): String = buildString {
        appendLine("无线调试连接：${if (isConnected) "✅ 已连接" else "❌ 未连接"}")
        appendLine("配对状态：${if (paired) "✅ 已配对（本次进程内）" else "未配对或未在本进程配对过"}")
        // 🔴 只探一次连接服务端口。旧实现这里**还额外探一次配对端口**（+3s）——
        // 配对服务只在「使用配对码配对设备」弹窗开着的那几十秒里存在，
        // 平时查它必然为空，纯属让用户多等 3 秒。
        val connectPorts = findConnectPorts(context, STATUS_PROBE_MS)
        appendLine(
            "连接服务端口：" +
                if (connectPorts.isEmpty()) "未发现（无线调试可能没开；连接时会自动改扫本机端口）"
                else connectPorts.joinToString(),
        )
        lastConnectPort(context).takeIf { it > 0 }?.let { appendLine("上次成功端口：$it") }
        lastCandidates?.let { c ->
            appendLine(
                "上次找端口：缓存=${c.cache} 属性=${c.property} mDNS=${c.mdns} " +
                    "扫描=${if (c.scanned) "${c.scanOpen}开/${c.scanAdb}adbd" else "未扫"}",
            )
        }
        propertyPort()?.let { appendLine("系统属性端口：$it") }
        append("密钥身份：${runCatching { identity(context).name }.getOrElse { "读取失败" }}")
    }.trim()

    /** 系统属性里可能存放 adbd 端口的键（按可能性排序）。 */
    private val PROPERTY_KEYS = listOf(
        "service.adb.tls.port",
        "persist.adb.tls.port",
        "service.adb.tcp.port",
    )
}
