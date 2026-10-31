package com.ai.assistance.quro.core.mika.record

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 每次运行一份小结([RunMetrics]),一行一份存在 [file](JSON Lines),只留最近 [keep] 份;运行记录只留最近 30 次,
 * 小结不跟着清,统计看得到更多次。运行收尾时记一份,交出回答后判了结局再补上([update])。
 * 读不出的行跳过,整个文件坏了按空的算;写是整份写到临时文件再换上,写不成只丢这一次。
 * 同一个文件可能有几个实例(每个页面、每次运行各开一个),读写用进程内同一把锁。
 */
class RunMetricsStore(private val file: File, private val keep: Int = DEFAULT_KEEP) {
    init { require(keep > 0) { "keep must be positive" } }

    /** 旧的在前。 */
    fun all(): List<RunMetrics> = synchronized(LOCK) { read() }

    /** 最近 [n] 份(旧的在前);[n] 为 null 时全部。 */
    fun recent(n: Int?): List<RunMetrics> = all().let { if (n == null) it else it.takeLast(n) }

    /** 记一份;同一次运行已有的换掉。 */
    fun append(m: RunMetrics) = synchronized(LOCK) {
        write(read().filter { m.runId.isEmpty() || it.runId != m.runId } + m)
    }

    /** 改一次运行的那一份;没有就不动。 */
    fun update(runId: String, change: (RunMetrics) -> RunMetrics) = synchronized(LOCK) {
        val list = read()
        if (list.none { it.runId == runId }) return@synchronized
        write(list.map { if (it.runId == runId) change(it) else it })
    }

    private fun read(): List<RunMetrics> {
        if (!file.isFile) return emptyList()
        val lines = runCatching { file.readLines() }.getOrDefault(emptyList())
        return lines.filter { it.isNotBlank() }.mapNotNull { runCatching { JSON.decodeFromString(RunMetrics.serializer(), it) }.getOrNull() }
    }

    private fun write(list: List<RunMetrics>) {
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.path + ".tmp")
            tmp.writeText(list.takeLast(keep).joinToString("") { JSON.encodeToString(RunMetrics.serializer(), it) + "\n" })
            if (!tmp.renameTo(file)) error("rename failed")
        }
    }

    companion object {
        const val DEFAULT_KEEP = 500
        const val FILE_NAME = "run-metrics.jsonl"
        private val LOCK = Any()
        val JSON = Json { ignoreUnknownKeys = true }

        /** 导出给人分析用:一个 JSON 数组,旧的在前。 */
        fun export(list: List<RunMetrics>): String = EXPORT.encodeToString(ListSerializer(RunMetrics.serializer()), list)

        private val EXPORT = Json { encodeDefaults = true }
    }
}
