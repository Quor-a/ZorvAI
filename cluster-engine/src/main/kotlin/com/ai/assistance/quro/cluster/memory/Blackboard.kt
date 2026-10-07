package com.ai.assistance.quro.cluster.engine.memory

import com.ai.assistance.quro.cluster.model.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 黑板 —— 角色之间唯一的共享真相。
 *
 * 三条硬规则：
 *  1. 每条写入必须带 sourceAgent + confidence，低置信不得进最终结论；
 *  2. 每次变更发事件，可驱动 TRIGGERED 调度；
 *  3. 版本自增，读取方可判断自己手上的数据是否过期。
 */
class Blackboard(
    private val taskId: TaskId,
    private val persist: suspend (BlackboardEntry) -> Unit = {}
) {
    private val mutex = Mutex()
    private val store = LinkedHashMap<String, BlackboardEntry>()
    private val _changes = MutableSharedFlow<BlackboardEntry>(extraBufferCapacity = 64)
    val changes: SharedFlow<BlackboardEntry> = _changes.asSharedFlow()

    suspend fun put(key: String, value: String, source: AgentId, confidence: Float = 1.0f) {
        require(confidence in 0f..1f) { "confidence 必须在 0..1" }
        val entry = mutex.withLock {
            val prev = store[key]
            val next = BlackboardEntry(
                key = key, valueJson = Json.encodeToString(value),
                sourceAgent = source, confidence = confidence,
                version = (prev?.version ?: 0) + 1
            )
            store[key] = next
            next
        }
        persist(entry)
        _changes.emit(entry)
    }

    suspend fun get(key: String): BlackboardEntry? = mutex.withLock { store[key] }

    suspend fun snapshot(minConfidence: Float = 0f, keys: List<String>? = null): List<BlackboardEntry> =
        mutex.withLock {
            store.values
                .filter { it.confidence >= minConfidence }
                .filter { keys == null || it.key in keys }
                .sortedBy { it.key }
        }

    /** 渲染成给模型看的紧凑文本；低置信条目会被显式标注为"待核实" */
    suspend fun render(minConfidence: Float = 0.5f, keys: List<String>? = null): String =
        snapshot(minConfidence, keys).joinToString("\n") { e ->
            val flag = if (e.confidence < 0.7f) "（待核实）" else ""
            "- ${e.key} = ${e.valueJson.trim('"')}$flag  [来源:${e.sourceAgent.value} 置信度:${"%.2f".format(e.confidence)} v${e.version}]"
        }.ifBlank { "（黑板为空）" }

    suspend fun clear() = mutex.withLock { store.clear() }

    /** 断点续跑：把库里已存的黑板条目恢复到内存（后者覆盖前者） */
    suspend fun restore(entries: List<BlackboardEntry>) = mutex.withLock {
        entries.forEach { store[it.key] = it }
    }
}

/** 语义记忆。宿主没提供 embedding 时用关键词退化实现。 */
interface SemanticMemory {
    suspend fun add(clusterId: ClusterId, text: String, source: String)
    suspend fun search(clusterId: ClusterId, query: String, topK: Int): List<String>
}

class KeywordSemanticMemory : SemanticMemory {
    private val data = ArrayList<Triple<ClusterId, String, String>>()

    override suspend fun add(clusterId: ClusterId, text: String, source: String) {
        if (text.length > 4_000) return
        data += Triple(clusterId, text, source)
        if (data.size > 5_000) data.removeAt(0)
    }

    override suspend fun search(clusterId: ClusterId, query: String, topK: Int): List<String> {
        val terms = query.split(Regex("[\\s,，。、；;]+")).filter { it.length >= 2 }.take(12)
        return data.asSequence()
            .filter { it.first == clusterId }
            .map { it.second to terms.count { t -> it.second.contains(t, true) } }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .take(topK)
            .map { it.first }
            .toList()
    }
}

/** 角色画像：沉淀擅长与失败模式，供 Router 打分与 PromptTuner 优化 */
data class AgentProfile(
    val agentId: AgentId,
    val successCount: Int = 0,
    val failCount: Int = 0,
    val avgLatencyMs: Long = 0,
    val failureModes: List<String> = emptyList()
) {
    val successRate: Float get() =
        (successCount + failCount).takeIf { it > 0 }?.let { successCount.toFloat() / it } ?: 0.5f
}
