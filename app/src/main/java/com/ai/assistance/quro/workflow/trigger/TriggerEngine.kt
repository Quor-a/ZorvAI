package com.ai.assistance.quro.workflow.trigger

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.ai.assistance.quro.workflow.data.NotesRepository
import com.ai.assistance.quro.workflow.data.RunStore
import com.ai.assistance.quro.workflow.data.WorkflowRepository
import com.ai.assistance.quro.workflow.data.model.Workflow
import com.ai.assistance.quro.workflow.executor.WorkflowEngine
import com.ai.assistance.quro.workflow.platform.Device
import java.util.Calendar

/**
 * 触发引擎：把「定时 / 事件」真正接到系统。
 *
 *  - time  触发器：用 AlarmManager 精确闹钟（daily / interval / cron），
 *           每次触发后自动重新武装（recurring）。
 *  - event 触发器：由 WorkflowTriggerReceiver 在系统事件（开机 / 亮屏 / 网络变化…）时回调 onSystemEvent。
 *  - manual 触发器：仅由用户或 AI 手动 wf_trigger，不在此武装。
 *
 * 全部基于 Android 框架，无新依赖。
 */
object TriggerEngine {

    private const val TAG = "TriggerEngine"

    private lateinit var appCtx: Context
    private var initialized = false

    /** 系统事件名 → 对应 Intent Action。 */
    private val EVENT_ACTIONS = mapOf(
        "boot" to Intent.ACTION_BOOT_COMPLETED,
        "user_present" to Intent.ACTION_USER_PRESENT,
        "screen_on" to Intent.ACTION_SCREEN_ON,
        "screen_off" to Intent.ACTION_SCREEN_OFF,
        "connectivity" to "android.net.conn.CONNECTIVITY_CHANGE"
    )

    fun init(ctx: Context) {
        appCtx = ctx.applicationContext
    }

    /** 接收器可能在 App 未初始化时被系统唤起（如开机），在此补齐初始化。 */
    fun ensureInit(ctx: Context) {
        if (!initialized) {
            synchronized(this) {
                if (!initialized) {
                    val c = ctx.applicationContext
                    WorkflowRepository.init(c)
                    NotesRepository.init(c)
                    RunStore.init(c)
                    WorkflowEngine.init(c)
                    Device.ensureChannel(c)
                    appCtx = c
                    initialized = true
                }
            }
        } else {
            appCtx = ctx.applicationContext
        }
    }

    fun armAll() {
        if (!initialized) return
        WorkflowRepository.getAll().filter { it.enabled }.forEach { arm(it) }
    }

    fun arm(wf: Workflow) {
        if (wf.trigger == "time") scheduleAlarm(wf)
    }

    fun disarm(wfId: String) {
        cancelAlarm(wfId)
    }

    /** 闹钟触发后：重新武装（time 触发器按 schedule 计算下一次）。 */
    fun armAfterFire(wfId: String) {
        val wf = WorkflowRepository.get(wfId) ?: return
        if (wf.trigger == "time") scheduleAlarm(wf)
    }

    /** 系统事件回调（来自 WorkflowTriggerReceiver）。 */
    fun onSystemEvent(action: String) {
        if (!initialized) return
        val eventName = EVENT_ACTIONS.entries.firstOrNull { it.value == action }?.key ?: return
        if (eventName == "boot") {
            // 重启后闹钟丢失，重新武装所有 time 触发器
            armAll()
        }
        WorkflowRepository.getAll()
            .filter { it.enabled && it.trigger == "event" && it.schedule == eventName }
            .forEach { WorkflowEngine.run(it.id) }
    }

    // ── 闹钟 ──
    private fun alarmIntent(wfId: String): PendingIntent {
        val intent = Intent(appCtx, WorkflowAlarmReceiver::class.java).putExtra("wf_id", wfId)
        return PendingIntent.getBroadcast(
            appCtx,
            wfId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun scheduleAlarm(wf: Workflow) {
        val am = appCtx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val fire = computeNextFireTime(wf.schedule, System.currentTimeMillis())
        val pi = alarmIntent(wf.id)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms()) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, fire, pi)
            } else {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, fire, pi)
            }
            Log.i(TAG, "已武装定时触发 ${wf.name} @ ${java.text.DateFormat.getTimeInstance().format(fire)}")
        } catch (e: Exception) {
            Log.e(TAG, "武装定时触发失败: ${e.message}")
        }
    }

    private fun cancelAlarm(wfId: String) {
        val am = appCtx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        runCatching { am.cancel(alarmIntent(wfId)) }
    }

    // ── schedule 解析 ──
    private fun computeNextFireTime(schedule: String, now: Long): Long {
        val cal = Calendar.getInstance()
        cal.timeInMillis = now
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return when {
            schedule.startsWith("daily:") -> {
                val parts = schedule.substring(6).split(":")
                val hh = parts.getOrNull(0)?.toIntOrNull() ?: 9
                val mm = parts.getOrNull(1)?.toIntOrNull() ?: 0
                cal.set(Calendar.HOUR_OF_DAY, hh)
                cal.set(Calendar.MINUTE, mm)
                if (cal.timeInMillis <= now) cal.add(Calendar.DAY_OF_MONTH, 1)
                cal.timeInMillis
            }
            schedule.startsWith("interval:") -> {
                val n = schedule.substring(8).removeSuffix("m").toIntOrNull() ?: 15
                now + n * 60_000L
            }
            schedule.startsWith("cron:") -> cronNext(schedule.substring(5), now)
            else -> now + 60_000L
        }
    }

    private fun cronNext(expr: String, now: Long): Long {
        val f = expr.split(Regex("\\s+"))
        if (f.size != 5) return now + 60_000L
        val cal = Calendar.getInstance()
        cal.timeInMillis = now
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        cal.add(Calendar.MINUTE, 1) // 从下一分钟开始，避免回到过去
        repeat(367 * 24 * 60) {
            if (cronMatch(cal, f[0], f[1], f[2], f[3], f[4])) return cal.timeInMillis
            cal.add(Calendar.MINUTE, 1)
        }
        return now + 60_000L
    }

    private fun cronMatch(
        cal: Calendar,
        min: String, hour: String, dom: String, mon: String, dow: String
    ): Boolean {
        val calDow0 = (cal.get(Calendar.DAY_OF_WEEK) + 6) % 7 // 0=周日
        return matchField(cal.get(Calendar.MINUTE), min, 0..59) &&
                matchField(cal.get(Calendar.HOUR_OF_DAY), hour, 0..23) &&
                matchField(cal.get(Calendar.DAY_OF_MONTH), dom, 1..31) &&
                matchField(cal.get(Calendar.MONTH) + 1, mon, 1..12) &&
                matchField(calDow0, dow, 0..6)
    }

    private fun matchField(v: Int, spec: String, range: IntRange): Boolean {
        if (spec == "*" || spec == "?") return true
        for (part in spec.split(",")) {
            if ("/" in part) {
                val (base, stepStr) = part.split("/")
                val step = stepStr.toIntOrNull() ?: 1
                val start = if (base == "*" || base == "?") range.first else base.toIntOrNull() ?: range.first
                if (v >= start && (v - start) % step == 0) return true
            } else if ("-" in part) {
                val (a, b) = part.split("-")
                val lo = a.toIntOrNull() ?: continue
                val hi = b.toIntOrNull() ?: continue
                if (v in lo..hi) return true
            } else {
                if (v == part.toIntOrNull()) return true
            }
        }
        return false
    }
}
