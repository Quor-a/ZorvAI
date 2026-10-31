package com.ai.assistance.quro.core.harness

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import com.ai.assistance.quro.service.QuroTerminalKeepAliveService

/**
 * DeepSeek Harness 引擎的**唤醒看门狗**。
 *
 * ## 为什么光有前台服务不够
 *
 * 上一轮把引擎自启挂进了 [QuroTerminalKeepAliveService] 的巡检环，但用户实测「根本不起作用」。
 * 原因不在循环本身，而在**没人保证那个循环还在跑**：
 *  - Android 把长时间不可见的应用冻结（App Freeze / cached 进程回收），协程循环一起停；
 *  - 部分 ROM 的「自启动管理」会拦掉 BOOT_COMPLETED，服务压根没被拉起；
 *  - 电量优化把进程降级后，`delay()` 的唤醒被推迟到几十分钟。
 *
 * ## 这里补的三重保障
 *
 *  1. **AlarmManager 定时唤醒**（本文件）：`setAndAllowWhileIdle` 在 Doze 下也能穿过，
 *     到点由系统拉起进程 → 静态广播 [AlarmReceiver] → 拉起保活服务 → 巡检环启动引擎。
 *     这是**唯一不依赖「进程还活着」**的手段。
 *  2. **电池优化白名单**：[isIgnoringBatteryOptimizations] / [requestIgnoreBattery]，
 *     没有它，前两条都会被系统降级（设置页里可一键查看与跳转）。
 *  3. **每次唤醒都重新排下一次**：AlarmManager 的重复闹钟在部分 ROM 上会被清，
 *     所以用「一次性闹钟 + 触发后再排」的方式，宁可多一次调度也不能断链。
 *
 * 关闭开关（[QuroDshKeepAlive.setAutoStartEnabled] = false）时这里会取消闹钟，
 * 不给用户留「关了还偷偷唤醒」的行为。
 */
object QuroDshWatchdog {

    private const val TAG = "QuroDshWatchdog"

    /** 闹钟唤醒间隔。Doze 下系统允许的最短周期就在 9–15 分钟量级，取 15 分钟。 */
    const val INTERVAL_MS = 15 * 60 * 1000L

    private const val ACTION_WAKE = "com.ai.assistance.quro.action.DSH_WATCHDOG"
    private const val REQ_CODE = 0x445348 // 'DSH'

    private fun pendingIntent(ctx: Context): PendingIntent {
        val i = Intent(ctx, AlarmReceiver::class.java).setAction(ACTION_WAKE)
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags = flags or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(ctx, REQ_CODE, i, flags)
    }

    /**
     * 排下一次唤醒。**幂等**：重复调用只是把时间往后推。
     *
     * 用 `setAndAllowWhileIdle` 而非 `setExactAndAllowWhileIdle`：
     * 后者在 Android 14+ 需要用户单独授予精确闹钟权限（默认拒绝），
     * 而看门狗对「±几分钟」完全不敏感，用一个不需要额外授权的接口反而更可靠。
     */
    fun schedule(ctx: Context) {
        if (!QuroDshKeepAlive.isAutoStartEnabled(ctx)) {
            cancel(ctx)
            return
        }
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val at = SystemClock.elapsedRealtime() + INTERVAL_MS
        try {
            am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pendingIntent(ctx))
            Log.i(TAG, "已排下次唤醒：${INTERVAL_MS / 60000} 分钟后")
        } catch (t: Throwable) {
            Log.w(TAG, "排唤醒失败，退回 set()", t)
            runCatching { am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pendingIntent(ctx)) }
        }
    }

    fun cancel(ctx: Context) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        runCatching { am.cancel(pendingIntent(ctx)) }
        Log.i(TAG, "已取消唤醒闹钟")
    }

    /** 是否已在电池优化白名单（不限制后台）。 */
    fun isIgnoringBatteryOptimizations(ctx: Context): Boolean = runCatching {
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        pm.isIgnoringBatteryOptimizations(ctx.packageName)
    }.getOrDefault(false)

    /**
     * 请求加入电池优化白名单。
     *
     * 直接用 `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` 弹系统授权框（本应用非商店发布，
     * 不受该 action 的商店政策限制）；个别 ROM 不认这个 action 时退回设置列表页。
     */
    fun requestIgnoreBattery(ctx: Context) {
        val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.parse("package:${ctx.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val ok = runCatching { ctx.startActivity(direct); true }.getOrDefault(false)
        if (ok) return
        runCatching {
            ctx.startActivity(
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.onFailure { Log.w(TAG, "打不开电池优化设置", it) }
    }

    /**
     * 闹钟到点：拉起保活服务（那里会启动引擎）。
     *
     * 静态注册在 manifest 里 —— 进程被杀后系统仍能实例化本接收器，
     * 这是「AlarmManager 能把死掉的进程叫醒」的关键。
     */
    class AlarmReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent?) {
            if (intent?.action != ACTION_WAKE) return
            // 先把下一次排上：万一后面抛异常，链路也不能断
            runCatching { schedule(context) }

            // 🔴 幂等第一道：引擎已经在服务？那只续闹钟，绝不重复拉服务/重复启动。
            // 用户担心「已经启动会一直循环启动」——这里就是那道闸。
            val alive = runCatching {
                val h = com.ai.assistance.quro.terminal.harness.DeepSeekHarness.get(context)
                if (!h.isInstalled()) return@runCatching null
                when {
                    h.isRunning() -> h.activePort()
                    else -> h.attachIfAlive()
                }
            }.getOrNull()
            if (alive != null) {
                Log.i(TAG, "引擎已在 :$alive 服务，本次唤醒只续闹钟，不重复启动")
                return
            }

            // 用户正开着 Harness 界面 → 同样不插手
            if (QuroDshKeepAlive.isAutoStartEnabled(context)) {
                Log.i(TAG, "引擎未运行，拉起保活服务（保活服务的巡检环会按幂等逻辑启动）")
                runCatching { QuroTerminalKeepAliveService.ensureStarted(context) }
                    .onFailure { Log.w(TAG, "拉起保活服务失败", it) }
            } else {
                Log.i(TAG, "自启开关已关闭，跳过")
            }
        }
    }
}
