package com.ai.assistance.quro.core.runtime

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Agent 运行检查点（参照 Eta `agent/runtime` 的 checkpoint 机制，去品牌化重写）。
 *
 * 解决的问题：一次多轮 Agent 运行（用户提问 → 模型思考 → 连调 5 个工具 → 生成最终回答）
 * 动辄几十秒。中途进程被杀、切后台被回收、或某次工具调用抛异常，
 * 此前只能从头再来 —— 用户要重新描述问题，已消耗的 token 也全部浪费。
 *
 * 检查点把「已完成的工具轮次 + 已累积的部分输出」落盘，
 * 恢复时可从断点续跑，而不是从零开始。
 *
 * 存储：filesDir/quro_runs/<runId>.json（每个会话只保留最近一个活跃检查点，
 * 运行正常结束后主动清除，避免垃圾堆积）。
 */
class QuroRunCheckpoint(private val context: Context) {

    private val dir: File = File(context.filesDir, "quro_runs").apply { mkdirs() }

    /** 一次运行中已完成的工具轮次。 */
    data class ToolRound(
        val round: Int,
        val toolName: String,
        val arguments: String,
        val result: String,
        val success: Boolean,
        val elapsedMs: Long,
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("round", round)
            put("tool", toolName)
            put("args", arguments)
            put("result", result)
            put("ok", success)
            put("elapsed_ms", elapsedMs)
        }

        companion object {
            fun fromJson(o: JSONObject): ToolRound = ToolRound(
                round = o.optInt("round", 0),
                toolName = o.optString("tool", ""),
                arguments = o.optString("args", ""),
                result = o.optString("result", ""),
                success = o.optBoolean("ok", true),
                elapsedMs = o.optLong("elapsed_ms", 0L),
            )
        }
    }

    /** 检查点快照。 */
    data class Snapshot(
        val runId: String,
        val conversationId: String,
        val userMessage: String,
        val modelId: String,
        val rounds: List<ToolRound>,
        val partialOutput: String,
        val promptTokens: Int,
        val completionTokens: Int,
        val startedAt: Long,
        val updatedAt: Long,
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("run_id", runId)
            put("conversation_id", conversationId)
            put("user_message", userMessage)
            put("model_id", modelId)
            put("rounds", JSONArray().apply { rounds.forEach { put(it.toJson()) } })
            put("partial_output", partialOutput)
            put("prompt_tokens", promptTokens)
            put("completion_tokens", completionTokens)
            put("started_at", startedAt)
            put("updated_at", updatedAt)
        }

        companion object {
            fun fromJson(o: JSONObject): Snapshot = Snapshot(
                runId = o.optString("run_id", ""),
                conversationId = o.optString("conversation_id", ""),
                userMessage = o.optString("user_message", ""),
                modelId = o.optString("model_id", ""),
                rounds = o.optJSONArray("rounds")?.let { arr ->
                    (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let { ToolRound.fromJson(it) } }
                } ?: emptyList(),
                partialOutput = o.optString("partial_output", ""),
                promptTokens = o.optInt("prompt_tokens", 0),
                completionTokens = o.optInt("completion_tokens", 0),
                startedAt = o.optLong("started_at", 0L),
                updatedAt = o.optLong("updated_at", 0L),
            )
        }
    }

    private fun fileOf(runId: String): File = File(dir, "$runId.json")

    /**
     * 写入/更新检查点。每完成一个工具轮次调用一次即可。
     * 写失败（磁盘满、并发）一律静默吞掉——检查点是**优化**而非**功能**，
     * 绝不能因为存不下检查点就让正在进行的对话失败。
     */
    fun save(snapshot: Snapshot) {
        runCatching {
            fileOf(snapshot.runId).writeText(
                snapshot.copy(updatedAt = System.currentTimeMillis()).toJson().toString()
            )
        }
    }

    /** 读取检查点；不存在或已损坏返回 null。 */
    fun load(runId: String): Snapshot? = runCatching {
        val f = fileOf(runId)
        if (!f.exists()) return null
        Snapshot.fromJson(JSONObject(f.readText()))
    }.getOrNull()

    /** 运行正常结束后清除检查点。 */
    fun clear(runId: String) {
        runCatching { fileOf(runId).delete() }
    }

    /**
     * 找出所有「疑似中断」的运行：即存在检查点、且最后更新时间早于 [olderThanMs]。
     * 应用启动自检时用它提示用户「上次有个任务没跑完，要继续吗？」。
     */
    fun findInterrupted(olderThanMs: Long = 60_000L): List<Snapshot> {
        val now = System.currentTimeMillis()
        return runCatching {
            dir.listFiles()?.mapNotNull { f ->
                runCatching { Snapshot.fromJson(JSONObject(f.readText())) }.getOrNull()
                    ?.takeIf { now - it.updatedAt > olderThanMs }
            }?.sortedByDescending { it.updatedAt } ?: emptyList()
        }.getOrDefault(emptyList())
    }

    /**
     * 清理超过 [maxAgeMs] 的陈旧检查点，防止长期积累占空间。
     * 在应用启动或设置页「清理缓存」时调用。
     */
    fun purgeStale(maxAgeMs: Long = 7 * 24 * 3600_000L): Int {
        val now = System.currentTimeMillis()
        var removed = 0
        runCatching {
            dir.listFiles()?.forEach { f ->
                val snap = runCatching { Snapshot.fromJson(JSONObject(f.readText())) }.getOrNull()
                val age = if (snap != null) now - snap.updatedAt else now - f.lastModified()
                if (age > maxAgeMs && f.delete()) removed++
            }
        }
        return removed
    }

    /** 全部清除。 */
    fun clearAll(): Int {
        var n = 0
        runCatching {
            dir.listFiles()?.forEach { if (it.delete()) n++ }
        }
        return n
    }

    /** 当前检查点占用的条目数。 */
    fun size(): Int = runCatching { dir.listFiles()?.size ?: 0 }.getOrDefault(0)
}
