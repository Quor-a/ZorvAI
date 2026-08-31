package com.ai.assistance.quro.core.runtime

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Agent 运行归档（参照 Eta `AgentRunArchiveStore`，去品牌化重写）。
 *
 * 把一次完整运行的**事件流**以 JSONL（每行一个 JSON 事件）追加落盘。
 * 用途：
 *  - **问题复现**：用户报「AI 刚才干了个傻事」，逐条回放事件即可还原现场，
 *    不必靠用户复述（用户往往只记得结果，记不住中间调了哪些工具）；
 *  - **死循环定位**：连续 N 条同参数 TOOL_CALL 一眼可见；
 *  - **导出分享**：把归档文件发给开发者即可，无需截图或录屏。
 *
 * 为什么用 JSONL 而不是单个 JSON 数组：
 *  - 追加写 O(1)，不必「读整个文件 → 加一项 → 重写」，运行中频繁写入也不卡；
 *  - 进程被杀时已落盘的行仍是完整 JSON，不会因最后半个 `]` 缺失导致整份损坏。
 */
class QuroRunArchive(private val context: Context) {

    private val dir: File = File(context.filesDir, "quro_archives").apply { mkdirs() }

    /** 事件类型。 */
    enum class EventType {
        RUN_START, USER_MESSAGE, MODEL_REQUEST, MODEL_DELTA,
        TOOL_CALL, TOOL_RESULT, FINAL, ERROR, RUN_END,
    }

    /** 单条事件。 */
    data class Event(
        val seq: Int,
        val type: EventType,
        val timestamp: Long,
        val payload: String,
        val meta: Map<String, String> = emptyMap(),
    ) {
        fun toJsonLine(): String = JSONObject().apply {
            put("seq", seq)
            put("t", type.name)
            put("ts", timestamp)
            // payload 可能很长（整段 prompt），这里不做裁剪：
            // 归档的价值就在于「完整现场」，裁剪会让排查时缺关键线索。
            put("p", payload)
            if (meta.isNotEmpty()) {
                put("m", JSONObject().apply { meta.forEach { (k, v) -> put(k, v) } })
            }
        }.toString()

        companion object {
            fun fromJsonLine(line: String): Event? = runCatching {
                val o = JSONObject(line)
                val meta = mutableMapOf<String, String>()
                o.optJSONObject("m")?.let { m ->
                    m.keys().forEach { k -> meta[k] = m.optString(k) }
                }
                Event(
                    seq = o.optInt("seq", 0),
                    type = runCatching { EventType.valueOf(o.optString("t")) }
                        .getOrDefault(EventType.MODEL_DELTA),
                    timestamp = o.optLong("ts", 0L),
                    payload = o.optString("p", ""),
                    meta = meta,
                )
            }.getOrNull()
        }
    }

    private fun fileOf(runId: String): File = File(dir, "$runId.jsonl")

    /** 追加一条事件。seq 由当前行数推导，保证回放顺序严格递增。 */
    @Synchronized
    fun append(runId: String, type: EventType, payload: String, meta: Map<String, String> = emptyMap()) {
        runCatching {
            val f = fileOf(runId)
            val seq = if (f.exists()) f.useLines { it.count() } else 0
            f.appendText(Event(seq, type, System.currentTimeMillis(), payload, meta).toJsonLine() + "\n")
        }
    }

    /** 读取一次运行的完整事件流（按 seq 排序）。 */
    fun read(runId: String): List<Event> = runCatching {
        val f = fileOf(runId)
        if (!f.exists()) return emptyList()
        f.useLines { lines -> lines.mapNotNull { Event.fromJsonLine(it) }.toList() }
    }.getOrDefault(emptyList())

    /**
     * 导出为可读文本（用于分享或让 AI 自己分析）。
     * 长 payload 会被截断到 [maxPayload]，避免几万字符刷屏。
     */
    fun exportReadable(runId: String, maxPayload: Int = 600): String {
        val events = read(runId)
        if (events.isEmpty()) return "归档 $runId 为空或不存在。"
        return buildString {
            appendLine("===== 运行归档 $runId =====")
            events.forEach { e ->
                val ts = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
                    .format(java.util.Date(e.timestamp))
                val p = if (e.payload.length > maxPayload) {
                    e.payload.take(maxPayload) + "…(已截断，共 ${e.payload.length} 字符)"
                } else e.payload
                val metaText = if (e.meta.isNotEmpty()) " ${e.meta}" else ""
                appendLine("[$ts] #${e.seq} ${e.type}$metaText")
                appendLine(p)
            }
        }.trim()
    }

    /** 列出全部归档 runId（按最后修改时间降序）。 */
    fun listRuns(): List<String> = runCatching {
        dir.listFiles()?.sortedByDescending { it.lastModified() }
            ?.map { it.nameWithoutExtension } ?: emptyList()
    }.getOrDefault(emptyList())

    /** 删除单个归档。 */
    fun delete(runId: String): Boolean = runCatching { fileOf(runId).delete() }.getOrDefault(false)

    /**
     * 清理超过 [maxAgeMs] 的旧归档。归档体积最大，默认只留 3 天。
     */
    fun purgeStale(maxAgeMs: Long = 3 * 24 * 3600_000L): Int {
        val now = System.currentTimeMillis()
        var removed = 0
        runCatching {
            dir.listFiles()?.forEach { f ->
                if (now - f.lastModified() > maxAgeMs && f.delete()) removed++
            }
        }
        return removed
    }

    /** 全部清除。 */
    fun clearAll(): Int {
        var n = 0
        runCatching { dir.listFiles()?.forEach { if (it.delete()) n++ } }
        return n
    }

    /** 归档占用的条目数。 */
    fun size(): Int = runCatching { dir.listFiles()?.size ?: 0 }.getOrDefault(0)
}

/**
 * Agent 运行时策略（参照 Eta `AgentRuntimePolicy`）。
 *
 * 作用是给「AI 自主行动」划红线。没有策略时最典型的翻车是：
 * 模型陷入「调工具 → 结果不满意 → 再调」的死循环，烧掉几十万 token 还在原地打转。
 *
 * 每项策略都可通过设置调整；默认值偏向保守（宁可提前刹车，也不无限烧钱）。
 */
data class QuroRuntimePolicy(
    /** 单次运行最多允许的工具调用轮数。超过即强制收尾并提示模型。 */
    val maxToolRounds: Int = 12,
    /** 单次运行总墙钟超时（毫秒）。 */
    val maxRunWallClockMs: Long = 180_000L,
    /** 单个工具执行超时（毫秒）。 */
    val toolTimeoutMs: Long = 60_000L,
    /**
     * 相同「工具名 + 参数」连续重复调用的上限。
     * 达到上限说明模型在绕圈，直接中断该工具的再次调用。
     */
    val maxIdenticalToolRepeats: Int = 3,
    /** 累计 token 预算（0 = 不限制）。超出后强制结束运行。 */
    val tokenBudget: Int = 0,
    /**
     * 需要二次确认的高危工具（删除文件、发短信、支付等）。
     * 命中时先停下来问用户，不直接执行。
     */
    val confirmRequiredTools: Set<String> = setOf(
        "delete_file", "send_sms", "make_call", "execute_shell", "write_file",
    ),
    /** 本次运行完全禁用的工具。 */
    val deniedTools: Set<String> = emptySet(),
) {
    companion object {
        /** 宽松策略：适合本地模型、长任务。 */
        val RELAXED = QuroRuntimePolicy(
            maxToolRounds = 30,
            maxRunWallClockMs = 600_000L,
            tokenBudget = 0,
            confirmRequiredTools = setOf("delete_file", "send_sms", "make_call"),
        )

        /** 严格策略：适合按量付费的强模型、或无人值守的定时任务。 */
        val STRICT = QuroRuntimePolicy(
            maxToolRounds = 6,
            maxRunWallClockMs = 90_000L,
            tokenBudget = 60_000,
            confirmRequiredTools = setOf(
                "delete_file", "send_sms", "make_call", "execute_shell",
                "write_file", "execute_intent", "send_broadcast",
            ),
        )
    }
}

/**
 * 运行时守卫：在一次运行过程中持续判断是否该「踩刹车」。
 *
 * 用法：每次工具调用前调 [checkToolCall]，由返回值决定放行 / 需确认 / 中断。
 */
class QuroRunGuard(private val policy: QuroRuntimePolicy) {

    private val startedAt = System.currentTimeMillis()
    private var toolRounds = 0
    private var tokensUsed = 0
    private val callSignatures = LinkedHashMap<String, Int>()

    /** 工具调用的裁决结果。 */
    sealed class Verdict {
        object Allow : Verdict()
        /** 需要先向用户确认（高危工具）。 */
        data class NeedConfirm(val reason: String) : Verdict()
        /** 必须中断，并告知模型原因（模型可据此改写策略或向用户说明）。 */
        data class Abort(val reason: String) : Verdict()
    }

    /** 在真正执行工具前调用。 */
    fun checkToolCall(toolName: String, arguments: String): Verdict {
        if (toolName in policy.deniedTools) {
            return Verdict.Abort("工具 $toolName 已被本次运行的策略禁用。")
        }
        if (toolRounds >= policy.maxToolRounds) {
            return Verdict.Abort(
                "已达到本轮最大工具调用次数（${policy.maxToolRounds}）。" +
                    "请基于已有信息直接给出结论，不要再调用工具。"
            )
        }
        val elapsed = System.currentTimeMillis() - startedAt
        if (elapsed > policy.maxRunWallClockMs) {
            return Verdict.Abort(
                "本次运行已超时（${elapsed / 1000}s / 上限 ${policy.maxRunWallClockMs / 1000}s）。" +
                    "请立即用现有信息作答。"
            )
        }
        if (policy.tokenBudget > 0 && tokensUsed > policy.tokenBudget) {
            return Verdict.Abort(
                "已超出本次运行的 token 预算（${tokensUsed} / ${policy.tokenBudget}）。" +
                    "请停止调用工具并总结。"
            )
        }

        // 死循环检测：相同「工具+参数」重复次数
        val sig = "$toolName|$arguments"
        val repeats = (callSignatures[sig] ?: 0) + 1
        callSignatures[sig] = repeats
        if (repeats > policy.maxIdenticalToolRepeats) {
            return Verdict.Abort(
                "工具 $toolName 以相同参数重复调用 ${repeats} 次，判定为无效重试。" +
                    "请换一种参数或改用其他工具，不要原样重试。"
            )
        }

        if (toolName in policy.confirmRequiredTools) {
            return Verdict.NeedConfirm("工具 $toolName 属于高危操作，需要用户确认后执行。")
        }
        return Verdict.Allow
    }

    /** 工具成功执行后登记，用于计数与 token 累加。 */
    fun onToolExecuted(tokens: Int = 0) {
        toolRounds++
        tokensUsed += tokens
    }

    /** 累计 token（含模型输入输出）。 */
    fun addTokens(n: Int) {
        tokensUsed += n
    }

    /** 当前已用轮次 / 已耗时，供 UI 显示进度。 */
    fun progress(): Pair<Int, Long> = toolRounds to (System.currentTimeMillis() - startedAt)
}
