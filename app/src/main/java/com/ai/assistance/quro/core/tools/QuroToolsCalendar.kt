package com.ai.assistance.quro.core.tools

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.provider.CalendarContract
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** 读取近期日历事件（READ_CALENDAR 运行时权限）。 */
class ReadCalendarTool : QuroTool {
    override val name = "read_calendar"
    override val description = "读取未来 N 天内的日历事件(标题+时间+地点)，参数为 {\"days\":7} (默认7)。"
    override val parametersJson = """{"type":"object","properties":{"days":{"type":"integer","description":"向前看的天数，默认7"}}}"""
    override val requiredPermissions = listOf(Manifest.permission.READ_CALENDAR)
    override fun run(context: Context, arguments: String): String {
        needsPermission(context, Manifest.permission.READ_CALENDAR)?.let { return it }
        val days = JSONObject(arguments).optInt("days", 7).coerceIn(1, 365)
        val now = System.currentTimeMillis()
        val end = now + days * 24L * 3600 * 1000
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        return try {
            val proj = arrayOf(
                CalendarContract.Events.TITLE,
                CalendarContract.Events.DTSTART,
                CalendarContract.Events.EVENT_LOCATION,
            )
            val sel = "${CalendarContract.Events.DTSTART} >= ? AND ${CalendarContract.Events.DTSTART} <= ?"
            context.contentResolver.query(
                CalendarContract.Events.CONTENT_URI, proj, sel,
                arrayOf(now.toString(), end.toString()),
                "${CalendarContract.Events.DTSTART} ASC",
            )?.use { c ->
                val out = mutableListOf<String>()
                while (c.moveToNext()) {
                    val title = c.getString(0) ?: "（无标题）"
                    val start = c.getLong(1)
                    val loc = c.getString(2) ?: ""
                    out.add("${fmt.format(Date(start))} | $title${if (loc.isNotEmpty()) " @ $loc" else ""}")
                }
                if (out.isEmpty()) "（未来 $days 天无事件）" else out.joinToString("\n")
            } ?: "（无日历数据）"
        } catch (e: Exception) {
            "读取日历失败: ${e.message}"
        }
    }
}

/**
 * 写入系统日历事件（WRITE_CALENDAR 运行时权限）。
 * 以 Quro 自身身份通过 contentResolver.insert 写入，避免走 shell/proot UID 被 provider 拒写。
 */
class WriteCalendarTool : QuroTool {
    override val name = "write_calendar"
    override val description =
        "在系统日历中创建事件(标题+开始时间，可选结束时间/地点/描述/全天)。参数为 " +
            "{\"title\":\"会议\",\"start\":\"2026-07-25 15:00\" 或毫秒时间戳,\"end\":\"2026-07-25 16:00\"(可选,默认+1h)," +
            "\"location\":\"(可选)\",\"description\":\"(可选)\",\"all_day\":false}。需 WRITE_CALENDAR 权限。"
    override val parametersJson = """{"type":"object","properties":{
        "title":{"type":"string","description":"事件标题（必填）"},
        "start":{"type":"string","description":"开始时间：'yyyy-MM-dd HH:mm' 或 毫秒时间戳（必填）"},
        "end":{"type":"string","description":"结束时间：同上格式；缺省默认开始+1小时"},
        "location":{"type":"string","description":"地点（可选）"},
        "description":{"type":"string","description":"描述/备注（可选）"},
        "all_day":{"type":"boolean","description":"是否全天事件，默认 false"}
    },"required":["title","start"]}"""
    override val requiredPermissions = listOf(Manifest.permission.WRITE_CALENDAR)

    override fun run(context: Context, arguments: String): String {
        needsPermission(context, Manifest.permission.WRITE_CALENDAR)?.let { return it }
        val a = try {
            JSONObject(arguments)
        } catch (e: Exception) {
            return "参数不是合法 JSON: ${e.message}"
        }
        val title = a.optString("title", "").trim()
        if (title.isEmpty()) return "缺少必填参数 title"
        val startMs = parseTime(a.optString("start", ""))
            ?: return "无法解析 start（支持 'yyyy-MM-dd HH:mm' 或毫秒时间戳）"
        val endMs = a.optString("end", "").let { if (it.isBlank()) startMs + 3600_000L else parseTime(it) }
            ?: return "无法解析 end（支持 'yyyy-MM-dd HH:mm' 或毫秒时间戳）"
        if (endMs < startMs) return "end 不能早于 start"
        val location = a.optString("location", "").trim()
        val desc = a.optString("description", "").trim()
        val allDay = a.optBoolean("all_day", false)

        val calId = resolveWritableCalendar(context)
            ?: return "设备上没有可写入的日历账户，请先在系统日历创建一个本地日历后再试。"

        val tz = if (allDay) "UTC" else TimeZone.getDefault().id
        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calId)
            put(CalendarContract.Events.TITLE, title)
            put(CalendarContract.Events.DTSTART, startMs)
            put(CalendarContract.Events.DTEND, endMs)
            put(CalendarContract.Events.EVENT_TIMEZONE, tz)
            if (location.isNotEmpty()) put(CalendarContract.Events.EVENT_LOCATION, location)
            if (desc.isNotEmpty()) put(CalendarContract.Events.DESCRIPTION, desc)
            put(CalendarContract.Events.ALL_DAY, if (allDay) 1 else 0)
        }
        return try {
            val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
            if (uri == null) {
                "写入日历失败：insert 返回 null（可能被日历应用拒绝，或账户不可写）"
            } else {
                val eventId = uri.lastPathSegment ?: "?"
                "✅ 已写入系统日历：[$title] ${fmt(startMs)} → ${fmt(endMs)}（事件ID=$eventId）"
            }
        } catch (e: Exception) {
            "写入日历失败: ${e.message}"
        }
    }

    /** 选一个可见且可同步的日历写入；优先本地日历，否则取第一个可见日历。 */
    private fun resolveWritableCalendar(context: Context): Long? {
        val uri = CalendarContract.Calendars.CONTENT_URI
        val proj = arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.ACCOUNT_TYPE)
        val sel = "${CalendarContract.Calendars.VISIBLE} = 1 AND ${CalendarContract.Calendars.SYNC_EVENTS} = 1"
        context.contentResolver.query(uri, proj, sel, null, null)?.use { c ->
            var local: Long? = null
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val type = c.getString(1) ?: ""
                if (type.equals("LOCAL", true) || type.contains("local", true)) {
                    local = id
                    break
                }
                if (local == null) local = id
            }
            return local
        }
        return null
    }

    private fun parseTime(s: String): Long? {
        s.toLongOrNull()?.let { return it }
        for (f in arrayOf("yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd HH:mm", "yyyy-MM-dd")) {
            try {
                return SimpleDateFormat(f, Locale.getDefault()).parse(s)?.time
            } catch (_: Exception) {
            }
        }
        return null
    }

    private fun fmt(ms: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(ms))
}
