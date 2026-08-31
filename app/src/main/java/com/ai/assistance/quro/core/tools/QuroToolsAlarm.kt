package com.ai.assistance.quro.core.tools

import android.content.Context
import android.os.Build
import com.ai.assistance.quro.permissions.QuroAlarmScheduler
import org.json.JSONArray
import org.json.JSONObject

/**
 * 设置应用内闹钟（真正会响的提醒，不依赖系统时钟 App）。
 *
 * 旧实现只通过 [android.provider.AlarmClock.ACTION_SET_ALARM] 甩给系统时钟 App，设备无预装时钟 App 时静默失败。
 * 现改为用 [QuroAlarmScheduler] 在应用内排程，到点由 [com.ai.assistance.quro.permissions.WorkoutAlarmReceiver]
 * 弹通知；支持一次性与按周重复。
 */
class SetAlarmTool : QuroTool {
    override val name = "set_alarm"
    override val description = "设置一条应用内闹钟（真正会响的提醒）。当用户要设闹钟/定时提醒时使用。参数 {\"hour\":7,\"minute\":30,\"label\":\"晨会\",\"days\":[2,4,6]}。hour 0-23、minute 0-59；days 为 1-7(周一至周日)，省略=仅响一次。"
    override val parametersJson = """{"type":"object","properties":{"hour":{"type":"integer","description":"小时0-23"},"minute":{"type":"integer","description":"分钟0-59"},"label":{"type":"string","description":"标签(可选)"},"days":{"type":"array","description":"重复日 1-7(可选)，省略则只响一次"}},"required":["hour","minute"]}"""
    override fun run(context: Context, arguments: String): String {
        val jo = JSONObject(arguments)
        val hour = jo.optInt("hour", -1)
        val minute = jo.optInt("minute", -1)
        if (hour !in 0..23 || minute !in 0..59) return "hour(0-23) 与 minute(0-59) 非法"

        val scheduler = QuroAlarmScheduler(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !scheduler.canScheduleExact()) {
            scheduler.openExactAlarmSettings()
            return "⚠️ 精确闹钟权限未授予（Android 12+ 需要 SCHEDULE_EXACT_ALARM）。已打开系统设置页，请开启「允许设置精确闹钟」后重试。"
        }

        val days = if (jo.has("days")) {
            val a = jo.getJSONArray("days")
            (0 until a.length()).map { a.getInt(it) }.filter { it in 1..7 }
        } else emptyList()

        val label = jo.optString("label", "")
        val id = "alarm_${System.currentTimeMillis()}"
        val alarm = QuroAlarmScheduler.ZorvAlarm(
            id = id, hour = hour, minute = minute, label = label,
            days = days, enabled = true, createdAt = System.currentTimeMillis()
        )
        scheduler.add(alarm)

        val whenStr = if (days.isEmpty()) {
            "将于下次 ${"%02d:%02d".format(hour, minute)} 响铃"
        } else {
            "每周 ${days.joinToString("/")} 的 ${"%02d:%02d".format(hour, minute)} 重复响铃"
        }
        return "✅ 已设置闹钟：${if (label.isNotEmpty()) "$label " else ""}(${"%02d:%02d".format(hour, minute)}) — $whenStr"
    }
}

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
