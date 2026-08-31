package com.ai.assistance.quro.core.tools

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.AlarmClock
import com.ai.assistance.quro.permissions.QuroAlarmScheduler
import org.json.JSONArray
import org.json.JSONObject
import java.util.ArrayList
import java.util.Calendar

/**
 * 设置闹钟（参考 Eta：委托系统时钟 App 真正响铃）。
 *
 * 主路径用 [AlarmClock.ACTION_SET_ALARM] 把请求甩给系统时钟 App（如 AOSP DeskClock / ColorOS 时钟 /
 * 三星时钟）。系统时钟负责真正响铃，关了本应用也能响，且不依赖应用后台存活——这是「闹钟真的会响」的
 * 最可靠做法。无可用系统时钟 App（或系统拒绝该 Intent）时，才兜底用 [QuroAlarmScheduler] 在应用内排程、
 * 到点由 [com.ai.assistance.quro.permissions.WorkoutAlarmReceiver] 弹通知。支持一次性与按周重复。
 */
class SetAlarmTool : QuroTool {
    override val name = "set_alarm"
    override val description = "设置一条闹钟（优先走系统时钟 App 真正响铃，无系统时钟时退化为应用内提醒）。当用户要设闹钟/定时提醒时使用。参数 {\"hour\":7,\"minute\":30,\"label\":\"晨会\",\"days\":[2,4,6]}。hour 0-23、minute 0-59；days 为 1-7(周一至周日)，省略=仅响一次。"
    override val parametersJson = """{"type":"object","properties":{"hour":{"type":"integer","description":"小时0-23"},"minute":{"type":"integer","description":"分钟0-59"},"label":{"type":"string","description":"标签(可选)"},"days":{"type":"array","description":"重复日 1-7(可选)，省略则只响一次"}},"required":["hour","minute"]}"""

    override fun run(context: Context, arguments: String): String {
        val jo = JSONObject(arguments)
        val hour = jo.optInt("hour", -1)
        val minute = jo.optInt("minute", -1)
        if (hour !in 0..23 || minute !in 0..59) return "hour(0-23) 与 minute(0-59) 非法"

        val label = jo.optString("label", "")
        val days = if (jo.has("days")) {
            val a = jo.getJSONArray("days")
            (0 until a.length()).map { a.getInt(it) }.filter { it in 1..7 }
        } else emptyList()

        // —— 主路径：委托系统时钟 App（Eta 做法）——
        val clockIntent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hour)
            putExtra(AlarmClock.EXTRA_MINUTES, minute)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            putExtra(AlarmClock.EXTRA_VIBRATE, true)
            if (label.isNotBlank()) putExtra(AlarmClock.EXTRA_MESSAGE, label)
            if (days.isNotEmpty()) putExtra(AlarmClock.EXTRA_DAYS, ArrayList(days.map { it.toCalendarDay() }))
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val pm = context.packageManager
        val direct = when {
            clockIntent.resolveActivity(pm) != null -> clockIntent
            // 通用意图在某些 ROM 不挂默认时钟时，尝试常见系统时钟包名
            else -> KNOWN_CLOCK_PACKAGES.firstNotNullOfOrNull { pkg ->
                clockIntent.setPackage(pkg).takeIf { it.resolveActivity(pm) != null }
            }
        }
        if (direct != null && runCatching { context.startActivity(direct) }.isSuccess) {
            val whenStr = if (days.isEmpty()) {
                "将于下次 ${"%02d:%02d".format(hour, minute)} 响铃（由系统时钟负责）"
            } else {
                "每周 ${days.joinToString("/")} 的 ${"%02d:%02d".format(hour, minute)} 重复响铃（由系统时钟负责）"
            }
            return "✅ 已通过系统时钟设置闹钟：${if (label.isNotBlank()) "$label " else ""}(${"%02d:%02d".format(hour, minute)}) — $whenStr"
        }

        // —— 兜底：无可用系统时钟 App 时，应用内排程弹通知 ——
        val scheduler = QuroAlarmScheduler(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !scheduler.canScheduleExact()) {
            scheduler.openExactAlarmSettings()
            return "⚠️ 未找到系统时钟 App，且精确闹钟权限未授予（Android 12+ 需 SCHEDULE_EXACT_ALARM）。已打开系统设置页，请开启后重试。"
        }
        val id = "alarm_${System.currentTimeMillis()}"
        scheduler.add(
            QuroAlarmScheduler.ZorvAlarm(
                id = id, hour = hour, minute = minute, label = label,
                days = days, enabled = true, createdAt = System.currentTimeMillis()
            )
        )
        val whenStr = if (days.isEmpty()) {
            "将于下次 ${"%02d:%02d".format(hour, minute)} 响铃（应用内提醒）"
        } else {
            "每周 ${days.joinToString("/")} 的 ${"%02d:%02d".format(hour, minute)} 重复响铃（应用内提醒）"
        }
        return "✅ 已设置应用内闹钟（无系统时钟可用）：${if (label.isNotBlank()) "$label " else ""}(${"%02d:%02d".format(hour, minute)}) — $whenStr"
    }
}

/** 用户传入的 days 1-7（周一..周日）映射到 Calendar 的 DAY_OF_WEEK 常量（1=周日..7=周六）。 */
private fun Int.toCalendarDay(): Int = when (this) {
    1 -> Calendar.MONDAY
    2 -> Calendar.TUESDAY
    3 -> Calendar.WEDNESDAY
    4 -> Calendar.THURSDAY
    5 -> Calendar.FRIDAY
    6 -> Calendar.SATURDAY
    7 -> Calendar.SUNDAY
    else -> Calendar.MONDAY
}

/** 常见系统时钟 App 包名，用于通用 ACTION_SET_ALARM 未挂默认 handler 时兜底解析。 */
private val KNOWN_CLOCK_PACKAGES = listOf(
    "com.android.deskclock",
    "com.google.android.deskclock",
    "com.coloros.alarmclock",
    "com.sec.android.app.clockpackage",
    "com.htc.android.worldclock",
    "com.oneplus.deskclock",
)

/**
 * 取消指定应用内闹钟。参数 {\"id\":\"alarm_xxx\"}；id 来自 list_alarms 的返回。
 */
class CancelAlarmTool : QuroTool {
    override val name = "cancel_alarm"
    override val description = "取消一条应用内闹钟。参数 {\"id\":\"alarm_xxx\"}，id 由 list_alarms 返回。"
    override val parametersJson = """{"type":"object","properties":{"id":{"type":"string","description":"要取消的闹钟 id"}},"required":["id"]}"""
    override fun run(context: Context, arguments: String): String {
        val jo = JSONObject(arguments)
        val id = jo.optString("id", "")
        if (id.isEmpty()) return "缺少 id"
        val ok = QuroAlarmScheduler(context).remove(id)
        return if (ok) "✅ 已取消闹钟：$id" else "⚠️ 未找到闹钟：$id"
    }
}

/**
 * 列出当前所有应用内闹钟（含是否启用与下次触发时间）。
 */
class ListAlarmsTool : QuroTool {
    override val name = "list_alarms"
    override val description = "列出当前所有应用内闹钟（含 id、时间、重复日、是否启用、下次响铃时间）。无参数。"
    override val parametersJson = """{"type":"object","properties":{}}"""
    override fun run(context: Context, arguments: String): String {
        val alarms = QuroAlarmScheduler(context).list()
        if (alarms.isEmpty()) return "当前没有已设置的应用内闹钟。"
        val arr = JSONArray()
        alarms.forEach { a ->
            arr.put(
                JSONObject().apply {
                    put("id", a.id)
                    put("hour", a.hour)
                    put("minute", a.minute)
                    put("label", a.label)
                    put("days", JSONArray(a.days))
                    put("enabled", a.enabled)
                    put("nextTriggerAt", QuroAlarmScheduler(context).nextTrigger(a))
                }
            )
        }
        return "当前应用内闹钟（${alarms.size} 条）：\n${arr.toString(2)}"
    }
}
