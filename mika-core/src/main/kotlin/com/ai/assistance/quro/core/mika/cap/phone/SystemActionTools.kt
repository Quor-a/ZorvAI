package com.ai.assistance.quro.core.mika.cap.phone

import com.ai.assistance.quro.core.mika.observation.ObsKind
import com.ai.assistance.quro.core.mika.observation.Observation
import com.ai.assistance.quro.core.mika.prompt.Feedback
import com.ai.assistance.quro.core.mika.prompt.Prompts
import com.ai.assistance.quro.core.mika.tool.ArgError
import com.ai.assistance.quro.core.mika.tool.Args
import com.ai.assistance.quro.core.mika.tool.Entry
import com.ai.assistance.quro.core.mika.tool.EvidenceLog
import com.ai.assistance.quro.core.mika.tool.Risk
import com.ai.assistance.quro.core.mika.tool.ToolContext
import com.ai.assistance.quro.core.mika.tool.ToolDef
import com.ai.assistance.quro.core.mika.tool.ToolExecution
import com.ai.assistance.quro.core.mika.tool.Verb
import com.ai.assistance.quro.core.mika.tool.bool
import com.ai.assistance.quro.core.mika.tool.int
import com.ai.assistance.quro.core.mika.tool.str
import com.ai.assistance.quro.core.mika.tool.strArray
import com.ai.assistance.quro.core.mika.uistate.ActionResult
import com.ai.assistance.quro.core.mika.uistate.SystemAction
import com.ai.assistance.quro.core.mika.uistate.SystemActionResult
import com.ai.assistance.quro.core.mika.uistate.action
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/*
 * 闹钟、计时器、日程:经安卓的标准接口交给手机上的时钟、日历 App,不去一格一格操作它们的界面。
 * 没有 App 接时报 not_supported,模型退回去操作界面。只改手机上的东西、不花钱:风险同打开 App。
 */

/** 一周七天在参数里的写法 → java.util.Calendar 的星期(周日 = 1)。 */
private val WEEKDAYS = listOf("SUN", "MON", "TUE", "WED", "THU", "FRI", "SAT")

/** 结果里写给模型看的本地时间。 */
private val LOCAL_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

internal object SetAlarm : PhoneTool {
    override val verb = Verb.SetAlarm
    override val label = "设闹钟"
    override val risk = Risk.Write
    override val entry = Entry.Alone
    override val logged = true
    override val def = ToolDef("phone_set_alarm", Prompts.tool("phone_set_alarm"), mapOf(
        "hour" to int("Hour of day, 0-23 (24-hour clock).", 0, 23),
        "minute" to int("Minute, 0-59.", 0, 59),
        "label" to str("Optional label shown with the alarm."),
        "days" to strArray("Optional repeat days: MON, TUE, WED, THU, FRI, SAT, SUN. Omit for a one-time alarm.", 7),
        "vibrate" to bool("Optional: vibrate when it rings."),
    ), listOf("hour", "minute"))

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val hour = a.int("hour", 0, 23)!!
        val minute = a.int("minute", 0, 59)!!
        val days = a.strList("days", 1, 7)?.map { d ->
            WEEKDAYS.indexOf(d.trim().uppercase().take(3)).takeIf { it >= 0 }?.plus(1)
                ?: throw ArgError("days", "items must be MON, TUE, WED, THU, FRI, SAT or SUN")
        }?.distinct()?.sorted().orEmpty()
        val label = a.str("label", max = 100)
        val vibrate = a.o["vibrate"]?.let { a.bool("vibrate", false) }
        val res = ctx.phone.screen.systemAction(SystemAction.SetAlarm(hour, minute, label, days, vibrate))
        return systemResult(ctx, res) {
            put("requested", buildJsonObject {
                put("time", "%02d:%02d".format(hour, minute))
                label?.let { put("label", it) }
                if (days.isNotEmpty()) put("days", JsonArray(days.map { JsonPrimitive(WEEKDAYS[it - 1]) }))
            })
            val check = AlarmCheck.of(hour, minute, res)
            put("check", check.wire)
            res.nextAlarmAfter?.let { put("nextAlarm", local(it)) }
            hint(this, when (check) {
                AlarmCheck.Status.VERIFIED -> null
                AlarmCheck.Status.EARLIER_FIRST -> Feedback.format("alarm.earlier_first", "next" to local(res.nextAlarmAfter!!))
                AlarmCheck.Status.NOT_SEEN, AlarmCheck.Status.UNKNOWN -> Feedback["alarm.unconfirmed"]
            }, res, silent = true)
        }
    }

    override fun logTarget(o: JsonObject, r: ToolExecution, ctx: ToolContext): String? = time(r)

    private fun time(r: ToolExecution): String? =
        ((r.content["requested"] as? JsonObject)?.get("time") as? JsonPrimitive)?.contentOrNull

    override fun observations(o: JsonObject, r: ToolExecution, log: EvidenceLog): List<Observation> {
        if (r.content["ok"] != JsonPrimitive(true)) return emptyList()
        val time = time(r) ?: return emptyList()
        val check = (r.content["check"] as? JsonPrimitive)?.contentOrNull
        val by = (r.content["handledBy"] as? JsonPrimitive)?.contentOrNull
        val tail = if (check == AlarmCheck.Status.VERIFIED.wire) ",系统报的下一个闹钟就是它" else ",系统没报出它(未核实)"
        return listOf(Observation(ObsKind.Fact, "$name 把 $time 的闹钟交给了 ${by ?: "时钟 App"}$tail", source = PHONE))
    }
}

internal object SetTimer : PhoneTool {
    override val verb = Verb.SetTimer
    override val label = "设计时器"
    override val risk = Risk.Write
    override val entry = Entry.Alone
    override val logged = true
    override val def = ToolDef("phone_set_timer", Prompts.tool("phone_set_timer"), mapOf(
        "seconds" to int("Length of the timer in seconds, 1-86400.", 1, 86400),
        "label" to str("Optional label shown with the timer."),
    ), listOf("seconds"))

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val seconds = a.int("seconds", 1, 86400)!!
        val label = a.str("label", max = 100)
        val res = ctx.phone.screen.systemAction(SystemAction.SetTimer(seconds, label))
        return systemResult(ctx, res) {
            put("requested", buildJsonObject { put("seconds", seconds); label?.let { put("label", it) } })
            hint(this, null, res, silent = true)
        }
    }

    override fun logTarget(o: JsonObject, r: ToolExecution, ctx: ToolContext): String? = seconds(o)

    private fun seconds(o: JsonObject): String? = (o["seconds"] as? JsonPrimitive)?.contentOrNull?.let { "$it s" }

    override fun observations(o: JsonObject, r: ToolExecution, log: EvidenceLog): List<Observation> {
        if (r.content["ok"] != JsonPrimitive(true)) return emptyList()
        val by = (r.content["handledBy"] as? JsonPrimitive)?.contentOrNull
        return listOf(Observation(ObsKind.Fact, "$name 把 ${seconds(o)} 的计时器交给了 ${by ?: "时钟 App"}", source = PHONE))
    }
}

/** 在 ZorvAI 的屏上打开时钟的闹钟列表:核对、删除、改闹钟都在那一页上操作。 */
internal object ShowAlarms : PhoneTool {
    override val verb = Verb.ShowAlarms
    override val label = "查看闹钟"
    override val risk = Risk.Write
    override val showsScreenAfter = true
    override val logged = true
    override val def = ToolDef("phone_show_alarms", Prompts.tool("phone_show_alarms"), emptyMap(), emptyList())

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        ctx.refusedPay = null
        val res = ctx.phone.screen.systemAction(SystemAction.ShowAlarms)
        if (res.result is ActionResult.Ok) ctx.appOpened = true
        return systemResult(ctx, res) { hint(this, Feedback["alarms.opened"], res, silent = false) }
    }

    override fun logTarget(o: JsonObject, r: ToolExecution, ctx: ToolContext): String? = (r.content["handledBy"] as? JsonPrimitive)?.contentOrNull
}

/** 打开日历预填好的新日程页:保存由模型在那一页上点,这里不存任何东西;不要日历权限。 */
internal object AddCalendarEvent : PhoneTool {
    override val verb = Verb.AddCalendarEvent
    override val label = "添加日程"
    override val risk = Risk.Write
    override val entry = Entry.Opens
    override val showsScreenAfter = true
    override val logged = true
    override val def = ToolDef("phone_add_calendar_event", Prompts.tool("phone_add_calendar_event"), mapOf(
        "title" to str("Event title."),
        "start" to str("Start as local date-time yyyy-MM-ddTHH:mm (e.g. 2026-11-02T14:30), or a date yyyy-MM-dd with allDay=true."),
        "end" to str("Optional end, same format as start. Defaults to one hour after start (one day for all-day events)."),
        "allDay" to bool("All-day event (default false)."),
        "location" to str("Optional location."),
        "description" to str("Optional notes."),
    ), listOf("title", "start"))

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val title = a.str("title", max = 200)!!
        val allDay = a.bool("allDay", false)
        val zone = ZoneId.systemDefault()
        val begin = CalendarTime.parse(a.str("start")!!, allDay, zone) ?: throw ArgError("start", "must look like 2026-11-02T14:30 (or 2026-11-02 with allDay=true)")
        val end = a.str("end")?.let { CalendarTime.parse(it, allDay, zone) ?: throw ArgError("end", "must use the same format as start") }
        if (end != null && end < begin) throw ArgError("end", "must not be before start")
        ctx.refusedPay = null
        val res = ctx.phone.screen.systemAction(SystemAction.AddCalendarEvent(
            title, begin, end ?: CalendarTime.defaultEnd(begin, allDay), allDay, a.str("location", max = 200), a.str("description", max = 2000),
        ))
        if (res.result is ActionResult.Ok) ctx.appOpened = true
        return systemResult(ctx, res) {
            put("requested", buildJsonObject {
                put("title", title); put("start", local(begin)); end?.let { put("end", local(it)) }
                if (allDay) put("allDay", true)
            })
            hint(this, Feedback["calendar.opened"], res, silent = false)
        }
    }

    override fun logTarget(o: JsonObject, r: ToolExecution, ctx: ToolContext): String? = (o["title"] as? JsonPrimitive)?.contentOrNull

    override fun observations(o: JsonObject, r: ToolExecution, log: EvidenceLog): List<Observation> {
        if (r.content["ok"] != JsonPrimitive(true)) return emptyList()
        val by = (r.content["handledBy"] as? JsonPrimitive)?.contentOrNull ?: return emptyList()
        return listOf(Observation(ObsKind.Fact, "$name 打开了 $by 的新日程页(还没保存)", source = PHONE))
    }
}

/** 结果:端口的动作结果照常转(失败按错误码给说明),成功时加上接手的 App 和 [more]。 */
private fun systemResult(ctx: ToolContext, res: SystemActionResult, more: JsonObjectBuilder.() -> Unit): ToolExecution {
    val r = ctx.action(res.result)
    if (r.content["ok"] != JsonPrimitive(true)) return r
    val extra = buildJsonObject {
        res.handledBy?.let { put("handledBy", it) }
        if (res.showing) put("screenOpened", true)
        more()
    }
    // 端口给的提示(被切走、开到主屏上……)排在前面,这里的说明接在后面
    val hints = listOfNotNull((r.content["hint"] as? JsonPrimitive)?.content, (extra["hint"] as? JsonPrimitive)?.content)
    val merged = r.content + extra + (if (hints.isEmpty()) emptyMap() else mapOf("hint" to JsonPrimitive(hints.joinToString(" "))))
    return ToolExecution(JsonObject(merged), changed = r.changed)
}

/** 这一类结果的说明:本该悄悄设好的([silent])却开了界面时说这个,否则用 [normal]。 */
private fun hint(b: JsonObjectBuilder, normal: String?, res: SystemActionResult, silent: Boolean) {
    (if (silent && res.showing) Feedback["system_action.screen_opened"] else normal)?.let { b.put("hint", it) }
}

private fun local(ms: Long): String = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(LOCAL_TIME)

/** 系统报的下一个闹钟和要设的对不对得上。 */
internal object AlarmCheck {
    enum class Status(val wire: String) {
        /** 系统报的下一个闹钟就是这个时刻。 */
        VERIFIED("verified"),
        /** 下一个是更早的另一个闹钟,这个看不到。 */
        EARLIER_FIRST("earlier_alarm_first"),
        /** 系统没报出它(没有下一个闹钟,或者下一个比它晚)。 */
        NOT_SEEN("not_seen"),
        /** 读不到系统的下一个闹钟。 */
        UNKNOWN("unknown"),
    }

    fun of(hour: Int, minute: Int, res: SystemActionResult, zone: ZoneId = ZoneId.systemDefault(), now: Long = System.currentTimeMillis()): Status {
        if (!res.alarmReadable) return Status.UNKNOWN
        val next = res.nextAlarmAfter ?: return Status.NOT_SEEN
        val at = Instant.ofEpochMilli(next).atZone(zone)
        if (at.hour == hour && at.minute == minute) return Status.VERIFIED
        // 这个时刻下一次是什么时候:下一个闹钟比它早,就是被更早的挡住了
        val mine = nextOccurrence(hour, minute, Instant.ofEpochMilli(now).atZone(zone))
        return if (next < mine) Status.EARLIER_FIRST else Status.NOT_SEEN
    }

    private fun nextOccurrence(hour: Int, minute: Int, now: ZonedDateTime): Long {
        var t = now.withHour(hour).withMinute(minute).withSecond(0).withNano(0)
        if (!t.isAfter(now)) t = t.plusDays(1)
        return t.toInstant().toEpochMilli()
    }
}

/** 日程的时间:本地日期时间(或只有日期的全天),带时区偏移的也认。 */
internal object CalendarTime {
    fun parse(s: String, allDay: Boolean, zone: ZoneId): Long? {
        val t = s.trim()
        runCatching { return OffsetDateTime.parse(t).toInstant().toEpochMilli() }
        runCatching { return LocalDateTime.parse(t).atZone(zone).toInstant().toEpochMilli() }
        runCatching { return LocalDateTime.parse("${t}:00").atZone(zone).toInstant().toEpochMilli() }
        if (allDay) runCatching { return LocalDate.parse(t).atStartOfDay(zone).toInstant().toEpochMilli() }
        return null
    }

    fun defaultEnd(begin: Long, allDay: Boolean): Long = begin + if (allDay) 24 * 3_600_000L else 3_600_000L
}
