package com.ai.assistance.quro.core.tools

import android.Manifest
import android.content.Context
import android.provider.CalendarContract
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
