package com.ai.assistance.quro.core.mika.trigger

import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

@Serializable
data class TriggerDefinition(
    val id: String,
    val threadId: String,
    val goal: String,
    val nextAtMs: Long,
    /** null 是一次性；间隔触发会合并积压的时段，只补跑一次。 */
    val intervalMs: Long? = null,
    /** 先在沙箱/网页里检查；检查成立才运行 [goal]。 */
    val conditionGoal: String? = null,
    val enabled: Boolean = true,
    /** 到点后只在没有前台任务时执行；忙时看门狗稍后重查。 */
    val idleOnly: Boolean = false,
    /** 上一次有效检查是否满足条件；用于只在状态变化时执行动作。 */
    val lastConditionMet: Boolean? = null,
    /** 固定提醒原文；非空时到点直接送达，不启动模型。 */
    val reminderText: String? = null,
    /** 按指定时区的当地钟点重复；空表示使用固定毫秒间隔或只运行一次。 */
    val calendarTime: String? = null,
    val calendarZone: String? = null,
    val calendarDays: List<Int> = emptyList(),
) {
    init {
        require(id.isNotBlank() && threadId.isNotBlank() && goal.isNotBlank())
        require(nextAtMs >= 0 && (intervalMs == null || intervalMs > 0))
        require(calendarTime == null || (intervalMs == null && calendarZone != null && calendarDays.all { it in 1..7 }))
    }
}

@Serializable
enum class TriggerWindowState { Claimed, Dispatched, ActionDispatched, Delivered, Skipped, Failed, Unknown }

@Serializable
data class TriggerWindow(
    val triggerId: String,
    val dueAtMs: Long,
    val claimedAtMs: Long,
    val missedIntervals: Long = 0,
    val state: TriggerWindowState = TriggerWindowState.Claimed,
    val reason: String? = null,
) {
    val key get() = "$triggerId:$dueAtMs"
}

/** claimDue 必须把下次时间与待处理账本一起写入，返回后才可派发。 */
interface TriggerStore {
    fun upsert(trigger: TriggerDefinition)
    fun get(id: String): TriggerDefinition?
    fun all(): List<TriggerDefinition>
    fun windows(): List<TriggerWindow>
    fun claimDue(nowMs: Long, eligible: (TriggerDefinition) -> Boolean = { true }): List<TriggerWindow>
    /** 手动执行一次，不改变原来的下一次计划时间。 */
    fun claimNow(id: String, nowMs: Long): TriggerWindow?
    fun mark(key: String, state: TriggerWindowState, reason: String? = null): Boolean
    /** 一次性任务只在结果确定送达后删除。 */
    fun retireDeliveredOneShots()
    fun remove(id: String)
}

@Serializable data class TriggerSnapshot(val triggers: List<TriggerDefinition>, val windows: List<TriggerWindow>)

/** 纯内存实现，文件实现沿用相同的状态转换。 */
class MemoryTriggerStore(initial: TriggerSnapshot = TriggerSnapshot(emptyList(), emptyList())) : TriggerStore {
    private val triggers = linkedMapOf<String, TriggerDefinition>()
    private val ledger = linkedMapOf<String, TriggerWindow>()

    init {
        initial.triggers.forEach { triggers[it.id] = it }
        initial.windows.forEach { ledger[it.key] = it }
    }

    @Synchronized fun snapshot() = TriggerSnapshot(triggers.values.toList(), ledger.values.toList())

    @Synchronized override fun upsert(trigger: TriggerDefinition) { triggers[trigger.id] = trigger }
    @Synchronized override fun remove(id: String) {
        triggers.remove(id)
        ledger.entries.removeAll { it.value.triggerId == id }
    }
    @Synchronized override fun get(id: String) = triggers[id]
    @Synchronized override fun all() = triggers.values.toList()
    @Synchronized override fun windows() = ledger.values.toList()

    @Synchronized override fun claimDue(nowMs: Long, eligible: (TriggerDefinition) -> Boolean): List<TriggerWindow> {
        val active = ledger.values.filter { it.state == TriggerWindowState.Claimed ||
            it.state == TriggerWindowState.Dispatched || it.state == TriggerWindowState.ActionDispatched }
            .map { it.triggerId }.toSet()
        val due = triggers.values.filter { it.enabled && it.id !in active && it.nextAtMs <= nowMs &&
            ledger["${it.id}:${it.nextAtMs}"] == null && eligible(it) }
        return due.map { t ->
            val interval = t.intervalMs
            val missed = if (interval == null) 0 else (nowMs - t.nextAtMs) / interval
            val window = TriggerWindow(t.id, t.nextAtMs, nowMs, missed)
            ledger[window.key] = window
            triggers[t.id] = when {
                t.calendarTime != null -> t.copy(nextAtMs = nextCalendarAt(t.calendarTime, t.calendarZone!!,
                    t.calendarDays, nowMs))
                interval != null -> t.copy(nextAtMs = t.nextAtMs + (missed + 1) * interval)
                else -> t.copy(enabled = false)
            }
            window
        }
    }

    @Synchronized override fun claimNow(id: String, nowMs: Long): TriggerWindow? {
        if (triggers[id] == null || ledger.values.any { it.triggerId == id && it.state in setOf(
                TriggerWindowState.Claimed, TriggerWindowState.Dispatched, TriggerWindowState.ActionDispatched) }) return null
        val at = generateSequence(nowMs) { it + 1 }.first { "$id:$it" !in ledger }
        return TriggerWindow(id, at, nowMs).also { ledger[it.key] = it }
    }

    @Synchronized override fun mark(key: String, state: TriggerWindowState, reason: String?): Boolean {
        val old = ledger[key] ?: return false
        if (old.state == TriggerWindowState.Delivered || old.state == TriggerWindowState.Skipped || old.state == TriggerWindowState.Failed) return false
        ledger[key] = old.copy(state = state, reason = reason)
        if (state == TriggerWindowState.Delivered || state == TriggerWindowState.Skipped ||
            state == TriggerWindowState.Failed || state == TriggerWindowState.Unknown)
            pruneCompleted(old.triggerId)
        return true
    }

    private fun pruneCompleted(id: String) {
        ledger.values.filter { it.triggerId == id && it.state in setOf(
            TriggerWindowState.Delivered, TriggerWindowState.Skipped, TriggerWindowState.Failed,
            TriggerWindowState.Unknown) }
            .sortedByDescending { it.dueAtMs }.drop(100).forEach { ledger.remove(it.key) }
    }

    @Synchronized override fun retireDeliveredOneShots() {
        val retired = triggers.values.filter { it.intervalMs == null && it.calendarTime == null && ledger.values.any { w ->
            w.triggerId == it.id && w.dueAtMs == it.nextAtMs && w.state == TriggerWindowState.Delivered } }
            .map { it.id }
        retired.forEach { id -> triggers.remove(id); ledger.entries.removeAll { it.value.triggerId == id } }
    }
}

/** 以当地日历递进，跨夏令时仍保持同一钟点；不存在的钟点使用系统的向前调整规则。 */
fun nextCalendarAt(time: String, zone: String, days: List<Int>, afterMs: Long): Long {
    val localTime = LocalTime.parse(time)
    val zoneId = ZoneId.of(zone)
    var date = Instant.ofEpochMilli(afterMs).atZone(zoneId).toLocalDate()
    repeat(9) {
        val candidate = date.atTime(localTime).atZone(zoneId).toInstant().toEpochMilli()
        if (candidate > afterMs && (days.isEmpty() || date.dayOfWeek.value in days)) return candidate
        date = date.plusDays(1)
    }
    error("calendar schedule has no matching day")
}

/** 排期由宿主实现；核心负责先记账再派发，异常重启时不会重复执行已认领的时段。 */
class TriggerEngine(private val store: TriggerStore, private val scheduleAlarm: (Long?) -> Unit,
    private val isIdle: () -> Boolean = { true }, private val idleRetryMs: Long = 60_000L,
    private val dispatch: (TriggerDefinition, TriggerWindow) -> Unit) {
    fun rearm(nowMs: Long) {
        store.windows().filter { it.state == TriggerWindowState.Claimed }
            .forEach { store.mark(it.key, TriggerWindowState.Unknown, "process restarted before dispatch confirmation") }
        val idle = isIdle()
        val active = store.windows().filter { it.state == TriggerWindowState.Claimed ||
            it.state == TriggerWindowState.Dispatched || it.state == TriggerWindowState.ActionDispatched }
            .map { it.triggerId }.toSet()
        scheduleAlarm(store.all().filter { it.enabled }.minOfOrNull {
            if (it.id in active) nowMs + idleRetryMs
            else maxOf(if (it.idleOnly && !idle) nowMs + idleRetryMs else nowMs, it.nextAtMs)
        })
    }

    fun fire(nowMs: Long) {
        val idle = isIdle()
        store.claimDue(nowMs) { !it.idleOnly || idle }.forEach { window ->
            val trigger = store.get(window.triggerId) ?: return@forEach
            try {
                dispatch(trigger, window)
                store.mark(window.key, TriggerWindowState.Dispatched)
            } catch (e: Exception) {
                store.mark(window.key, TriggerWindowState.Unknown, e::class.simpleName)
            }
        }
        rearm(nowMs)
    }
}
