package com.ai.assistance.quro.core.mika.trigger

/** 本机用户的计划管理；宿主负责持久化和重新布置闹钟。 */
interface ScheduleAdmin {
    fun list(threadId: String): List<TriggerDefinition>
    fun setEnabled(threadId: String, id: String, enabled: Boolean): Boolean
    fun remove(threadId: String, id: String): Boolean
    fun reschedule(threadId: String, id: String, atMs: Long): Boolean
    fun runNow(threadId: String, id: String): Boolean
}

/**
 * 存下一个定时任务之后的情况:[onTime] 为 false 表示系统没准许准时闹钟(到点可能不准时或起不来);
 * 这时 [userNotified] 说这次有没有真的给用户发出去开启的提示。
 */
data class Scheduled(val onTime: Boolean, val userNotified: Boolean = false)
