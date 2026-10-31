package com.ai.assistance.quro.core.harness

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.ai.assistance.quro.terminal.harness.DeepSeekHarness

/**
 * DeepSeek Harness 引擎的**自启与保活**。
 *
 * ## 为什么需要这个文件
 *
 * DSH 引擎是一个 Node 子进程（`dsh --profile web`），过去**只有用户打开 Harness 界面**
 * 才会被 `start()`。于是出现两个现象，用户都反馈过：
 *
 *  1. **「ZorvAI 有自启动权限，但 DSH 根本不会自己起环境」** —— 开机自启接收器只拉起
 *     `QuroTerminalKeepAliveService`（保 shell 会话），DSH 不在其中，没人管它。
 *  2. **「返回后长时间还要重新启动」** —— 引擎跑在 Application 进程里，界面退出后一旦
 *     进程被系统回收，Node 子进程随之消失；再回来就要重新 spawn，
 *     而一次 spawn 要装配 profile / bundle / 运行时补丁，耗时以十秒计。
 *
 * 现在把它挂进保活前台服务的巡检环：[ensureRunning] 每轮调用，
 * 已装则保证「有一台在服务」，已死则重启，未装则**不动**（绝不因为自启去偷偷下载 800MB）。
 *
 * ## 与 [DeepSeekHarness.attachIfAlive] 的配合
 *
 * 引擎可能由保活服务进程 spawn，也可能由界面进程 spawn，两者 pid 不同。
 * 认领文件让 `start()` 具备「接管已有引擎」能力，因此这里只需无脑 `start()`：
 * 已有实例会被接管并秒返回，不会重复 spawn，也不会把活着的引擎当残留杀掉。
 */
object QuroDshKeepAlive {

    private const val TAG = "QuroDshKeepAlive"

    private const val PREFS = "quro_dsh_keepalive"

    /** 是否随应用自启（默认开：用户装了 ZorvAI 就该有可用的引擎）。 */
    private const val K_AUTOSTART = "dsh_autostart"

    /** 上次自启尝试时刻，用于失败冷却。 */
    private const val K_LAST_TRY = "dsh_last_try"

    /** 连续失败次数。 */
    private const val K_FAILS = "dsh_fails"

    /** 失败冷却：连续失败后拉长重试间隔，避免开机瞬间反复 spawn 拖垮设备。 */
    private const val COOLDOWN_MS = 90_000L

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * 「用户正开着 Harness 界面」标记。
     *
     * 为什么需要它：定时看门狗（[QuroDshWatchdog]）会在后台定时唤醒。
     * 如果用户此刻正在界面里手动启动/调试引擎，看门狗再插一脚就是「强行启动」——
     * 用户的原话是「手动启动进去了不是会把强行启动」。所以界面在前台时，
     * 引擎**没跑就让他手动跑**，看门狗只负责续闹钟，不负责启动。
     */
    @Volatile
    private var uiActive: Boolean = false

    /** 界面进入/退出时调用。 */
    fun setUiActive(active: Boolean) {
        uiActive = active
        Log.i(TAG, "界面活跃=$active（看门狗将${if (active) "跳过自动启动" else "恢复自动启动"}）")
    }

    /** 是否随应用自启。默认 **true**。 */
    fun isAutoStartEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(K_AUTOSTART, true)

    fun setAutoStartEnabled(ctx: Context, enabled: Boolean) {
        prefs(ctx).edit().putBoolean(K_AUTOSTART, enabled).apply()
        // 开关与唤醒闹钟同生共死：关了就不许再偷偷唤醒
        runCatching { if (enabled) QuroDshWatchdog.schedule(ctx) else QuroDshWatchdog.cancel(ctx) }
        Log.i(TAG, "DSH 自启已${if (enabled) "开启" else "关闭"}")
    }

    /** 给设置界面显示的一行状态文案。 */
    fun statusText(ctx: Context): String {
        val h = DeepSeekHarness.get(ctx)
        if (!h.isInstalled()) return "运行时未安装（首次打开 Harness 时安装）"
        val port = h.attachIfAlive() ?: h.activePort().takeIf { h.isRunning() }
        return when {
            port != null -> "引擎运行中 · 127.0.0.1:$port"
            !isAutoStartEnabled(ctx) -> "已关闭自启，引擎未运行"
            else -> "引擎未运行（保活服务会在下一轮巡检时拉起）"
        }
    }

    /**
     * 幂等确保引擎在跑。**必须在协程/工作线程调用**（会阻塞等就绪）。
     *
     * @return 端口；未安装、已关闭自启、冷却中或启动失败返回 null
     */
    suspend fun ensureRunning(ctx: Context, ignoreCooldown: Boolean = false): Int? {
        if (!isAutoStartEnabled(ctx)) return null
        val h = try {
            DeepSeekHarness.get(ctx)
        } catch (t: Throwable) {
            Log.w(TAG, "取 harness 失败", t)
            return null
        }
        // 未安装就什么都不做：自启不能演变成后台偷偷下载大资产
        if (!h.isInstalled()) return null

        // 已在跑 / 已有引擎可接管 → 直接返回端口（这是「回到前台秒开」的关键路径）
        // 🔴 幂等保证：这一步在 start() 之前，所以「已经启动」时**绝不会再 spawn 一次**。
        if (h.isRunning()) return h.activePort()
        h.attachIfAlive()?.let { return it }

        // 用户正在界面里自己操作（可能刚点停止、正在手动启动）→ 不替他做主
        if (uiActive) {
            Log.i(TAG, "界面活跃且引擎未运行：交给用户手动处理，看门狗不介入")
            return null
        }

        val p = prefs(ctx)
        val now = System.currentTimeMillis()
        if (!ignoreCooldown) {
            val last = p.getLong(K_LAST_TRY, 0L)
            val fails = p.getInt(K_FAILS, 0)
            // 失败次数越多，冷却越久（90s / 180s / 270s，上限 6 分钟）
            val cooldown = COOLDOWN_MS * (fails.coerceIn(0, 3) + 1)
            if (fails > 0 && now - last < cooldown) return null
        }

        p.edit().putLong(K_LAST_TRY, now).apply()
        val r = h.start()
        return r.fold(
            onSuccess = { port ->
                p.edit().putInt(K_FAILS, 0).apply()
                Log.i(TAG, "DSH 引擎已自启，port=$port")
                port
            },
            onFailure = { t ->
                val fails = p.getInt(K_FAILS, 0) + 1
                p.edit().putInt(K_FAILS, fails).apply()
                Log.w(TAG, "DSH 自启失败（连续第 $fails 次）: ${t.message}")
                null
            },
        )
    }

    /** 停止引擎（设置里「关闭并停止」用）。 */
    fun stop(ctx: Context) {
        runCatching { DeepSeekHarness.get(ctx).stop() }
        prefs(ctx).edit().putInt(K_FAILS, 0).apply()
    }
}
