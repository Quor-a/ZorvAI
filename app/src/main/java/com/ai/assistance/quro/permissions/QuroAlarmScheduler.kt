package com.ai.assistance.quro.permissions

import android.app.AlarmManager
import android.content.Context
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/**
 * 应用内闹钟调度器（不依赖系统时钟 App，真正会响）。
 *
 * 之前 [com.ai.assistance.quro.core.tools.SetAlarmTool] 只通过 [android.provider.AlarmClock.ACTION_SET_ALARM]
 * 把请求甩给系统时钟 App —— 设备没预装时钟 App 时静默失败，闹钟根本设不上。本调度器改为在应用内
 * 用 [AlarmManager.setExactAndAllowWhileIdle] 真正排程，到点由 [WorkoutAlarmReceiver] 弹通知。
 *
 * - 闹钟数据持久化到 SharedPreferences（进程被杀 / 重启 / 应用更新后仍可恢复）。
 * - 重复闹钟（days 非空）每次触发后自动续排下一次；一次性闹钟触发后自动失效。
 * - 开机 / 应用更新后由 [com.ai.assistance.quro.receiver.QuroBootReceiver] 调用 [scheduleAll] 重建。
 */
class QuroAlarmScheduler(private val context: Context) {

    private val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    private val prefs = context.getSharedPreferences("zorv_alarms_v1", Context.MODE_PRIVATE)

    data class ZorvAlarm(
        val id: String,
        val hour: Int,
        val minute: Int,
        val label: String,
        val days: List<Int>, // 1-7（周一..周日）；空=仅一次
        val enabled: Boolean,
        val createdAt: Long
    )

    /** Android 12+ 是否已授予精确闹钟权限。 */
    fun canScheduleExact(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()

    /** 跳转到「精确闹钟」授权设置页（无权限时引导用户手动开启）。 */
    fun openExactAlarmSettings() {
        AlarmPermissionHelper(context).openExactAlarmSettings()
    }

    fun list(): List<ZorvAlarm> {
        val arr = JSONArray(prefs.getString("alarms", "[]") ?: "[]")
        val out = mutableListOf<ZorvAlarm>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val d = o.optJSONArray("days")
            val days = if (d != null) (0 until d.length()).map { d.getInt(it) } else emptyList()
            out.add(
                ZorvAlarm(
                    id = o.getString("id"),
                    hour = o.getInt("hour"),
                    minute = o.getInt("minute"),
                    label = o.optString("label", ""),
                    days = days,
                    enabled = o.optBoolean("enabled", true),
                    createdAt = o.optLong("createdAt", 0L)
                )
            )
        }
        return out
    }

    private fun save(list: List<ZorvAlarm>) {
        val arr = JSONArray()
        list.forEach { a ->
            arr.put(
                JSONObject().apply {
                    put("id", a.id)
                    put("hour", a.hour)
                    put("minute", a.minute)
                    put("label", a.label)
                    val d = JSONArray()
                    a.days.forEach { d.put(it) }
                    put("days", d)
                    put("enabled", a.enabled)
                    put("createdAt", a.createdAt)
                }
            )
        }
        prefs.edit().putString("alarms", arr.toString()).apply()
    }

    fun add(alarm: ZorvAlarm): ZorvAlarm {
        val cur = list().toMutableList()
        cur.removeAll { it.id == alarm.id }
        cur.add(alarm)
        save(cur)
        schedule(alarm)
        return alarm
    }

    fun remove(id: String): Boolean {
        val cur = list().toMutableList()
        val existed = cur.removeAll { it.id == id }
        save(cur)
        cancelPi(id)
        return existed
    }

    fun get(id: String): ZorvAlarm? = list().firstOrNull { it.id == id }

    /** 计算下一次触发时间（毫秒）。 */
    fun nextTrigger(alarm: ZorvAlarm): Long {
        val now = System.currentTimeMillis()
        if (alarm.days.isEmpty()) {
            val cal = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, alarm.hour)
                set(Calendar.MINUTE, alarm.minute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            if (cal.timeInMillis <= now) cal.add(Calendar.DAY_OF_MONTH, 1)
            return cal.timeInMillis
        }
        // 用户 days：1=周一..7=周日 → Calendar：1=周日..7=周六
        val mapped = alarm.days.map { if (it == 7) 1 else it + 1 }.toSet()
        for (i in 0..7) {
            val c = Calendar.getInstance().apply {
                timeInMillis = now
                add(Calendar.DAY_OF_MONTH, i)
                set(Calendar.HOUR_OF_DAY, alarm.hour)
                set(Calendar.MINUTE, alarm.minute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            if (i == 0 && c.timeInMillis <= now) continue
            if (mapped.contains(c.get(Calendar.DAY_OF_WEEK))) return c.timeInMillis
        }
        return now + 60_000L
    }

    /** 为单个闹钟排程下一次触发（仅 enabled 时）。 */
    fun schedule(alarm: ZorvAlarm) {
        if (!alarm.enabled) {
            cancelPi(alarm.id)
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms()) return
        val pi = WorkoutAlarmReceiver.buildIntent(context, alarm.id, alarm.label, buildContent(alarm))
        am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, nextTrigger(alarm), pi)
    }

    /** 全部重新排程（开机 / 应用更新后调用）。 */
    fun scheduleAll() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms()) return
        list().forEach { if (it.enabled) schedule(it) }
    }

    /** 闹钟触发后回调：一次性闹钟置失效；重复闹钟续排下一次。 */
    fun onFired(id: String) {
        val a = get(id) ?: return
        if (a.days.isEmpty()) {
            val cur = list().toMutableList()
            val idx = cur.indexOfFirst { it.id == id }
            if (idx >= 0) {
                cur[idx] = cur[idx].copy(enabled = false)
                save(cur)
            }
            cancelPi(id)
        } else {
            schedule(a)
        }
    }

    private fun cancelPi(id: String) {
        am.cancel(WorkoutAlarmReceiver.buildIntent(context, id, "", ""))
    }

    private fun buildContent(alarm: ZorvAlarm): String =
        if (alarm.days.isEmpty()) {
            "⏰ ${"%02d:%02d".format(alarm.hour, alarm.minute)} ${alarm.label}".trim()
        } else {
            "⏰ 每周重复 ${"%02d:%02d".format(alarm.hour, alarm.minute)} ${alarm.label}".trim()
        }

    companion object {
        const val EXTRA_ALARM_ID = "extra_alarm_id"
    }
}
