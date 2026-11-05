package com.ai.assistance.quro.core.mika.record

/**
 * 读已经存下的运行记录(只读):概要(run.json)和事件(events.jsonl)。记录落盘时已脱敏、截短,读出来原样给。
 * 记录已被清掉、编号不认识或读不出时返回 null / 空表,不抛异常。由 app 一侧按落盘的记录实现。
 */
interface RunRecords {
    suspend fun summary(runId: String): RunSummary?
    suspend fun events(runId: String): List<RunEvent>
}
