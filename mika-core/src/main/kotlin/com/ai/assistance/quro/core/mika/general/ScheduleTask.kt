package com.ai.assistance.quro.core.mika.general

import com.ai.assistance.quro.core.mika.action.ActionRisk
import com.ai.assistance.quro.core.mika.action.Risk as ApprovalRisk
import com.ai.assistance.quro.core.mika.prompt.Prompts
import com.ai.assistance.quro.core.mika.tool.AgentTool
import com.ai.assistance.quro.core.mika.tool.Args
import com.ai.assistance.quro.core.mika.tool.Risk
import com.ai.assistance.quro.core.mika.tool.ToolContext
import com.ai.assistance.quro.core.mika.tool.ToolDef
import com.ai.assistance.quro.core.mika.tool.ToolExecution
import com.ai.assistance.quro.core.mika.tool.Verb
import com.ai.assistance.quro.core.mika.tool.int
import com.ai.assistance.quro.core.mika.tool.bool
import com.ai.assistance.quro.core.mika.tool.okObj
import com.ai.assistance.quro.core.mika.tool.str
import com.ai.assistance.quro.core.mika.tool.strArray
import com.ai.assistance.quro.core.mika.trigger.TriggerDefinition
import com.ai.assistance.quro.core.mika.trigger.nextCalendarAt
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal object ScheduleTask : AgentTool {
    override val verb = Verb.ScheduleTask
    override val risk = Risk.External
    override val approvalRisk = ApprovalRisk(ActionRisk.External)
    override val offScreen = true
    override val logged = true
    override val def = ToolDef("schedule_task", Prompts.tool("schedule_task"), mapOf(
        "op" to str("create (default), list, pause, resume, delete, run_now, or reschedule.",
            listOf("create", "list", "pause", "resume", "delete", "run_now", "reschedule")),
        "triggerId" to str("Plan ID returned by list or create; required for pause/resume/delete/run_now/reschedule."),
        "goal" to str("What to do when the time arrives."),
        "at" to str("Optional first time as ISO 8601 with timezone. Required unless repeatAt is given."),
        "intervalMinutes" to int("Optional repeat interval in minutes; omit for one time.", 1, 525600),
        "repeatAt" to str("Optional daily local clock time HH:mm, e.g. 09:00. Use for 'every day at 9' rather than a 1440-minute interval."),
        "repeatZone" to str("IANA time zone for repeatAt, e.g. Asia/Shanghai; defaults to this device's zone."),
        "weekdays" to strArray("Optional days for repeatAt: MON,TUE,WED,THU,FRI,SAT,SUN. Omit for every day.", 7),
        "condition" to str("Optional read only condition to check using web or sandbox before running the goal."),
        "reminder" to str("Optional fixed reminder text. If provided, deliver it directly at the scheduled time without running the model."),
        "whenIdle" to bool("If true, run only while no foreground task is active; the watchdog retries while busy."),
    ), emptyList())

    override fun available(ctx: ToolContext) = ctx.config.scheduleTrigger != null || ctx.config.scheduleAdmin != null

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val thread = ctx.config.conversationId ?: return ToolExecution(okObj { put("scheduled", false); put("reason", "no_thread") })
        val op = a.str("op") ?: "create"
        if (op != "create") {
            val admin = ctx.config.scheduleAdmin ?: return ToolExecution(okObj { put("ok", false); put("reason", "unavailable") })
            if (op == "list") return ToolExecution(okObj {
                put("plans", buildJsonArray { admin.list(thread).forEach { plan ->
                    add(buildJsonObject {
                        put("id", plan.id); put("threadId", plan.threadId); put("goal", plan.goal); put("enabled", plan.enabled)
                        put("nextAtMs", plan.nextAtMs); put("nextAt", Instant.ofEpochMilli(plan.nextAtMs).toString())
                        plan.reminderText?.let { put("reminder", it) }
                        plan.intervalMs?.let { put("intervalMinutes", it / 60_000) }
                        plan.calendarTime?.let { put("repeatAt", it) }
                        plan.calendarZone?.let { put("repeatZone", it) }
                        plan.conditionGoal?.let { put("condition", it) }
                    })
                } })
            })
            val id = a.str("triggerId") ?: return ToolExecution(okObj { put("changed", false); put("reason", "missing_trigger_id") })
            val changed = when (op) {
                "pause" -> admin.setEnabled(thread, id, false)
                "resume" -> admin.setEnabled(thread, id, true)
                "delete" -> admin.remove(thread, id)
                "run_now" -> admin.runNow(thread, id)
                "reschedule" -> {
                    val at = runCatching { Instant.parse(a.str("at") ?: error("missing time")).toEpochMilli() }.getOrNull()
                        ?: return ToolExecution(okObj { put("changed", false); put("reason", "invalid_time") })
                    if (at <= System.currentTimeMillis()) return ToolExecution(okObj { put("changed", false); put("reason", "time_not_future") })
                    admin.reschedule(thread, id, at)
                }
                else -> return ToolExecution(okObj { put("changed", false); put("reason", "invalid_op") })
            }
            return ToolExecution(okObj { put("changed", changed); if (!changed) put("reason", "plan_not_found") })
        }
        val goal = a.str("goal", max = 4000) ?: return ToolExecution(okObj { put("scheduled", false); put("reason", "missing_goal") })
        val first = a.str("at")
        val repeatAt = a.str("repeatAt")
        val interval = a.int("intervalMinutes", 1, 525600)?.toLong()?.times(60_000)
        if (repeatAt != null && interval != null) return ToolExecution(okObj {
            put("scheduled", false); put("reason", "repeat_and_interval_conflict")
        })
        val zone = a.str("repeatZone") ?: ZoneId.systemDefault().id
        val days = a.strList("weekdays", 1, 7)?.map { day ->
            listOf("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN").indexOf(day.uppercase()) + 1
        }.orEmpty()
        if (days.any { it == 0 } || (repeatAt == null && days.isNotEmpty())) return ToolExecution(okObj {
            put("scheduled", false); put("reason", "invalid_weekdays")
        })
        val at = runCatching {
            val firstMs = first?.let { Instant.parse(it).toEpochMilli() }
            if (repeatAt == null) firstMs ?: error("missing time")
            else {
                LocalTime.parse(repeatAt)
                ZoneId.of(zone)
                nextCalendarAt(repeatAt, zone, days, maxOf(System.currentTimeMillis(), (firstMs ?: 0) - 1))
            }
        }.getOrElse { return ToolExecution(okObj { put("scheduled", false); put("reason", "invalid_time") }) }
        val condition = a.str("condition", max = 2000)
        val reminder = a.str("reminder", max = 2000)
        if (reminder != null && condition != null) return ToolExecution(okObj {
            put("scheduled", false); put("reason", "reminder_and_condition_conflict")
        })
        val trigger = TriggerDefinition(UUID.randomUUID().toString(), thread, goal, at, interval, condition,
            idleOnly = a.bool("whenIdle", false), reminderText = reminder,
            calendarTime = repeatAt, calendarZone = if (repeatAt != null) zone else null, calendarDays = days)
        val create = ctx.config.scheduleTrigger ?: return ToolExecution(okObj {
            put("scheduled", false); put("reason", "unavailable")
        })
        val saved = create(trigger)
        return ToolExecution(okObj {
            put("scheduled", true); put("triggerId", trigger.id); put("atMs", at)
            if (!saved.onTime) {
                put("exactAlarm", false)
                put("userNotified", saved.userNotified)
                put("message", "Saved, but the phone has not granted ZorvAI the alarms & reminders permission, so this task may run late or not start at all. " +
                    (if (saved.userNotified) "ZorvAI has just shown the user a notification that opens the setting. "
                    else "The user has not been shown a prompt for it this time. ") +
                    "Tell the user plainly that scheduled tasks need this permission and that it can be turned on from ZorvAI's scheduled tasks page.")
            }
        })
    }
}
