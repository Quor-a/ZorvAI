package com.ai.assistance.quro.core.assistant

import org.json.JSONArray
import org.json.JSONObject
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * 厂商助手入口的**回合内事件总线**（只活在 ZorvAI app 主进程）。
 *
 * 为什么需要它：`ContentProvider.call` 是同步一次性调用，[QuroAgentBridgeProvider.runTurn]
 * 会阻塞到整个 Agent 回合结束才有返回值。而 Eta hook 层的小布 / 超级小爱渲染器需要
 * 在回合进行中持续收到逐字正文、思考流、工具调用与重试事件，否则界面只能「一次性蹦出全文」。
 *
 * 因此这里按 requestId 维护一条带自增序号的事件流：
 *  · app 侧 [QuroAgentTurnRunner] 通过 [QuroAssistant] 的流式出口持续 [emit]；
 *  · Hook 侧（system_server / 小布 / 小爱进程）用 `METHOD_EVENTS` 按游标增量拉取，
 *    转换成 Eta 的 `AgentEvent` 交给渲染器；
 *  · 全程复用已有的 ContentProvider 协议，不引入 AIDL / 长连接 / Binder 回调，
 *    也不改变 [QuroAgentBridgeProtocol.METHOD_RUN] 的同步语义。
 *
 * 安全边界：单流事件数与总流数都有硬上限，完成后的流保留一小段时间供最后一次 drain，
 * 再按 TTL 回收，避免 Hook 进程挂掉时 app 侧内存被无限占用。
 */
object QuroAgentTurnEvents {

    private const val MAX_EVENTS_PER_RUN = 4_000
    private const val MAX_RUNS = 32
    private const val FINISHED_TTL_MS = 90_000L
    private const val STALE_TTL_MS = 5 * 60_000L
    private const val MAX_TEXT_CHARS = 8_000

    private class RunBuffer {
        val seq = AtomicLong(0)
        val events: MutableList<JSONObject> = Collections.synchronizedList(ArrayList())
        @Volatile var done = false
        @Volatile var finishedAt = 0L
        var createdAt = System.currentTimeMillis()
    }

    private val runs = ConcurrentHashMap<String, RunBuffer>()

    /** 开流；同一 requestId 重复调用会清空旧事件（Hook 侧重试时不会读到上一回合残留）。 */
    fun start(runId: String) {
        if (runId.isBlank()) return
        sweep()
        runs[runId] = RunBuffer()
        if (runs.size > MAX_RUNS) {
            val oldest = runs.entries
                .filter { !it.key.equals(runId, ignoreCase = true) }
                .minByOrNull { it.value.createdAt }
            oldest?.let { runs.remove(it.key, it.value) }
        }
    }

    /** 追加一条事件；返回其序号。事件已在 TurnRunner 侧做过差分，这里只负责传输。 */
    fun emit(runId: String, type: String, payload: JSONObject.() -> Unit = {}): Long {
        if (runId.isBlank()) return -1L
        val buffer = runs[runId] ?: return -1L
        if (buffer.events.size >= MAX_EVENTS_PER_RUN) return -1L
        val seq = buffer.seq.incrementAndGet()
        val json = JSONObject()
        json.put(QuroAgentBridgeProtocol.EVENT_SEQ, seq)
        json.put(QuroAgentBridgeProtocol.EVENT_TYPE, type)
        json.put(QuroAgentBridgeProtocol.EVENT_TS, System.currentTimeMillis())
        runCatching { json.payload() }
        buffer.events.add(json)
        return seq
    }

    /** 标记回合结束；保留 [FINISHED_TTL_MS] 让 Hook 侧拿到 `done` 之前的全部事件。 */
    fun finish(runId: String, ok: Boolean) {
        val buffer = runs[runId] ?: return
        emit(runId, QuroAgentBridgeProtocol.EVENT_DONE) {
            put(QuroAgentBridgeProtocol.EVENT_OK, ok)
        }
        buffer.done = true
        buffer.finishedAt = System.currentTimeMillis()
    }

    /**
     * 拉取 `afterSeq` 之后的增量事件。
     * 返回 JSON：{ ok, events: [...], last_seq, done }；流不存在时 `ok=false`，
     * Hook 侧据此降级为「一次性全文」，绝不让厂商助手界面空白。
     */
    fun poll(runId: String, afterSeq: Long): JSONObject {
        val result = JSONObject()
        val buffer = runs[runId]
        if (buffer == null) {
            result.put(QuroAgentBridgeProtocol.RESULT_OK, false)
            result.put(QuroAgentBridgeProtocol.RESULT_ERROR, "event stream not found")
            return result
        }
        val snapshot: List<JSONObject> = synchronized(buffer.events) {
            buffer.events.filter { it.optLong(QuroAgentBridgeProtocol.EVENT_SEQ, -1) > afterSeq }
        }
        val array = JSONArray()
        snapshot.forEach { array.put(it) }
        result.put(QuroAgentBridgeProtocol.RESULT_OK, true)
        result.put(QuroAgentBridgeProtocol.EXTRA_EVENTS_JSON, array.toString())
        result.put(QuroAgentBridgeProtocol.EXTRA_EVENTS_LAST_SEQ, buffer.seq.get())
        result.put(QuroAgentBridgeProtocol.EXTRA_EVENTS_DONE, buffer.done)
        return result
    }

    private fun sweep() {
        val now = System.currentTimeMillis()
        // ConcurrentHashMap 的 entrySet 不支持批量 removeIf，逐项原子移除。
        runs.forEach { (runId, buffer) ->
            val ttl = if (buffer.done) FINISHED_TTL_MS else STALE_TTL_MS
            val anchor = if (buffer.done) buffer.finishedAt else buffer.createdAt
            if (now - anchor > ttl) runs.remove(runId, buffer)
        }
    }

    /** 事件字段写入的安全封装：长文本一律截断，避免单次 binder 事务过大。 */
    fun JSONObject.putText(key: String, value: String) {
        put(key, if (value.length > MAX_TEXT_CHARS) value.take(MAX_TEXT_CHARS) else value)
    }
}
