package com.ai.assistance.quro.core.mika.run

/** 运行状态的最小持久化记录。运行中断后只告知并允许重跑，不从屏幕中途续跑。 */
data class StoredRun(
    val id: RunId,
    val threadId: String,
    val goal: String,
    val phase: RunPhase,
    val reply: String? = null,
    val deliveryId: String? = null,
    val roundInProgress: Boolean = false,
    val triggerWindowKey: String? = null,
    val runSucceeded: Boolean? = null,
)

interface RunStore {
    fun save(record: StoredRun)
    fun get(id: RunId): StoredRun?
    fun all(): List<StoredRun>

    /** 重启时逐个标记中断，不能静默把工具调用再跑一遍。[live] 里是此刻还在跑的运行,不动它们。 */
    fun interruptUnfinished(live: Set<RunId> = emptySet()): List<StoredRun> {
        val interrupted = all().filter { it.phase != RunPhase.Ended && it.phase != RunPhase.Interrupted && it.id !in live }
        interrupted.forEach { save(it.copy(phase = RunPhase.Interrupted)) }
        return interrupted
    }
}

class MemoryRunStore : RunStore {
    private val records = linkedMapOf<RunId, StoredRun>()
    @Synchronized override fun save(record: StoredRun) { records[record.id] = record }
    @Synchronized override fun get(id: RunId): StoredRun? = records[id]
    @Synchronized override fun all(): List<StoredRun> = records.values.toList()
}
