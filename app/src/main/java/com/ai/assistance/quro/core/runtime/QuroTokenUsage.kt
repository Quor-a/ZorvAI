package com.ai.assistance.quro.core.runtime

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Token 用量统计（参照 Eta `AgentTokenUsage`重写）。
 *
 * 为什么需要：模型账单与上下文预算都靠 token 说话，但用户看到的只有「钱变少了」。
 * 有了用量台账，才能回答三个关键问题：
 *  - **今天/本月用了多少**（控成本）
 *  - **哪个模型最烧 token**（选模型）
 *  - **哪次对话特别贵**（找异常，比如陷入工具死循环）
 *
 * 设计要点：
 *  - 三维度聚合：**按模型 / 按天 / 按会话**，调用时 O(1) 累加，查询时 O(维度基数) 汇总；
 *  - 只保留最近 [MAX_DAYS] 天与最近 [MAX_CONVERSATIONS] 个会话的明细，防止文件无限膨胀；
 *  - 所有写操作静默失败 —— 统计不准可以容忍，影响对话不行。
 */
class QuroTokenUsage(private val context: Context) {

    private val file: File = File(context.filesDir, "quro_token_usage.json")

    /** 单次调用用量明细。 */
    data class Record(
        val modelId: String,
        val conversationId: String,
        val promptTokens: Int,
        val completionTokens: Int,
        val timestamp: Long = System.currentTimeMillis(),
    ) {
        val total: Int get() = promptTokens + completionTokens
    }

    /** 聚合计数（prompt / completion / 调用次数）。 */
    data class Aggregate(
        var prompt: Long = 0,
        var completion: Long = 0,
        var calls: Int = 0,
    ) {
        val total: Long get() = prompt + completion
        fun add(p: Int, c: Int) {
            prompt += p
            completion += c
            calls++
        }
    }

    /** 统计台账的内存视图；读写通过 [load] / [persist]。 */
    private var byModel: MutableMap<String, Aggregate> = linkedMapOf()
    private var byDay: MutableMap<String, Aggregate> = linkedMapOf()
    private var byConversation: MutableMap<String, Aggregate> = linkedMapOf()
    private var loaded = false

    private val dayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        runCatching {
            if (!file.exists()) return
            val o = JSONObject(file.readText())
            byModel = parseAggMap(o.optJSONObject("by_model"))
            byDay = parseAggMap(o.optJSONObject("by_day"))
            byConversation = parseAggMap(o.optJSONObject("by_conversation"))
        }
    }

    private fun parseAggMap(o: JSONObject?): MutableMap<String, Aggregate> {
        val map = linkedMapOf<String, Aggregate>()
        if (o == null) return map
        o.keys().forEach { key ->
            val v = o.optJSONObject(key) ?: return@forEach
            map[key] = Aggregate(
                prompt = v.optLong("p", 0L),
                completion = v.optLong("c", 0L),
                calls = v.optInt("n", 0),
            )
        }
        return map
    }

    private fun serializeAggMap(map: Map<String, Aggregate>): JSONObject = JSONObject().apply {
        map.forEach { (k, v) ->
            put(k, JSONObject().apply {
                put("p", v.prompt)
                put("c", v.completion)
                put("n", v.calls)
            })
        }
    }

    /**
     * 记录一次调用。
     * 所有 token 数按 0 处理负数（部分中转不返回 usage 字段，会传 -1 之类的哨兵值）。
     */
    fun record(rec: Record) {
        val p = rec.promptTokens.coerceAtLeast(0)
        val c = rec.completionTokens.coerceAtLeast(0)
        if (p == 0 && c == 0) return

        ensureLoaded()
        val modelKey = rec.modelId.ifBlank { "unknown" }
        val dayKey = dayFmt.format(Date(rec.timestamp))
        val convKey = rec.conversationId.ifBlank { "-" }

        byModel.getOrPut(modelKey) { Aggregate() }.add(p, c)
        byDay.getOrPut(dayKey) { Aggregate() }.add(p, c)
        byConversation.getOrPut(convKey) { Aggregate() }.add(p, c)

        trim()
        persist()
    }

    /** 便捷重载。 */
    fun record(modelId: String, conversationId: String, promptTokens: Int, completionTokens: Int) =
        record(Record(modelId, conversationId, promptTokens, completionTokens))

    /** 裁剪：只保留最近 N 天与最近 M 个会话，防止台账无限增长。 */
    private fun trim() {
        if (byDay.size > MAX_DAYS) {
            val keep = byDay.keys.sortedDescending().take(MAX_DAYS).toSet()
            byDay = byDay.filterKeys { it in keep }.toMutableMap()
        }
        if (byConversation.size > MAX_CONVERSATIONS) {
            // LinkedHashMap 保序，直接丢最旧的
            val drop = byConversation.size - MAX_CONVERSATIONS
            byConversation = LinkedHashMap(
                byConversation.entries.drop(drop).associate { it.key to it.value }
            )
        }
    }

    private fun persist() {
        runCatching {
            file.writeText(JSONObject().apply {
                put("by_model", serializeAggMap(byModel))
                put("by_day", serializeAggMap(byDay))
                put("by_conversation", serializeAggMap(byConversation))
                put("updated_at", System.currentTimeMillis())
            }.toString())
        }
    }

    // =========================================================================================
    // 查询
    // =========================================================================================

    /** 今日用量。 */
    fun today(): Aggregate {
        ensureLoaded()
        return byDay[dayFmt.format(Date())] ?: Aggregate()
    }

    /** 最近 [days] 天用量（含今天）。 */
    fun recentDays(days: Int): List<Pair<String, Aggregate>> {
        ensureLoaded()
        return byDay.entries.sortedByDescending { it.key }
            .take(days)
            .map { it.key to it.value }
    }

    /** 按模型汇总，按总 token 降序。 */
    fun byModel(): List<Pair<String, Aggregate>> {
        ensureLoaded()
        return byModel.entries.sortedByDescending { it.value.total }.map { it.key to it.value }
    }

    /** 单次会话用量；未记录返回 null。 */
    fun ofConversation(conversationId: String): Aggregate? {
        ensureLoaded()
        return byConversation[conversationId]
    }

    /** 全局累计。 */
    fun total(): Aggregate {
        ensureLoaded()
        val agg = Aggregate()
        byModel.values.forEach {
            agg.prompt += it.prompt
            agg.completion += it.completion
            agg.calls += it.calls
        }
        return agg
    }

    /**
     * 生成人类可读的用量报告，供设置页展示或 AI 工具返回。
     * 同时给出省钱提示（哪个模型最贵）。
     */
    fun report(): String {
        val t = total()
        if (t.calls == 0) return "暂无用量记录。"
        val todayAgg = today()
        return buildString {
            appendLine("累计：${t.total} tokens（输入 ${t.prompt} / 输出 ${t.completion}），共 ${t.calls} 次调用")
            appendLine("今日：${todayAgg.total} tokens，${todayAgg.calls} 次调用")
            val models = byModel()
            if (models.isNotEmpty()) {
                appendLine("按模型：")
                models.take(5).forEach { (name, a) ->
                    appendLine("  - $name：${a.total} tokens（${a.calls} 次）")
                }
                val top = models.first()
                if (top.second.total > t.total * 0.6) {
                    appendLine("提示：${top.first} 占用了超过 60% 的用量，可考虑在高强度任务上换更省的模型。")
                }
            }
        }.trim()
    }

    /** 清空台账。 */
    fun reset() {
        byModel.clear(); byDay.clear(); byConversation.clear()
        runCatching { file.delete() }
        loaded = true
    }

    companion object {
        private const val MAX_DAYS = 90
        private const val MAX_CONVERSATIONS = 200
    }
}
