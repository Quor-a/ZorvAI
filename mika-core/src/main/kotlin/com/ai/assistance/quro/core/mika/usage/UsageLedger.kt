package com.ai.assistance.quro.core.mika.usage

import com.ai.assistance.quro.core.mika.model.Usage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonArray
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/** 一格的归属:哪个服务商、哪个模型、哪个用途。 */
data class UsageKey(val providerId: String, val model: String, val purpose: UsagePurpose)

/** 一格里累计的数:输入(含缓存命中)、输出、缓存命中的 token,调用次数,其中没报用量的次数。 */
data class UsageCounts(
    val input: Long = 0,
    val output: Long = 0,
    val cached: Long = 0,
    val calls: Long = 0,
    val callsWithoutUsage: Long = 0,
) {
    operator fun plus(o: UsageCounts) =
        UsageCounts(input + o.input, output + o.output, cached + o.cached, calls + o.calls, callsWithoutUsage + o.callsWithoutUsage)

    companion object {
        fun of(usage: Usage?): UsageCounts =
            if (usage == null) UsageCounts(calls = 1, callsWithoutUsage = 1)
            else UsageCounts(usage.inputTokens.toLong(), usage.outputTokens.toLong(), usage.cachedInputTokens.toLong(), calls = 1)
    }
}

/** [hour] 是 UTC 整点桶:从 1970-01-01T00:00Z 起的第几个小时。 */
data class UsageRow(val hour: Long, val key: UsageKey, val counts: UsageCounts)

/** 账本此刻的全部内容;[providerNames] 是每个服务商最近一次记账时的名字(服务商删了也留着)。 */
data class UsageSnapshot(val rows: List<UsageRow>, val providerNames: Map<String, String>) {
    companion object {
        val EMPTY = UsageSnapshot(emptyList(), emptyMap())
    }
}

/**
 * 本机的 token 用量账本:按 UTC 整点 × 服务商 × 模型 × 用途累计,只存在这台手机上([file],JSON)。
 *
 * 记账([record])只在内存里加一笔,多个线程同时记也安全;改动后等 [flushDelayMs] 由一个后台线程整份写出
 * (先写临时文件再改名,写到一半断电不会坏掉原文件),进程被杀时最多丢掉最后这几秒。
 * 只留最近 [keepDays] 天,更早的在读入和写出时丢掉。文件读不出(损坏)时当作空账本重新开始。
 */
class UsageLedger(
    private val file: File,
    private val now: () -> Long = System::currentTimeMillis,
    private val flushDelayMs: Long = 2_000,
    private val keepDays: Int = KEEP_DAYS,
) : UsageSink {
    private data class Cell(val hour: Long, val key: UsageKey)

    private val lock = Any()
    private val writeLock = Any()
    private val cells = HashMap<Cell, UsageCounts>()
    private val names = HashMap<String, String>()
    private var dirty = false
    private var flushQueued = false

    private val writer: ScheduledExecutorService by lazy {
        Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "usage-ledger").apply { isDaemon = true } }
    }

    private val _version = MutableStateFlow(0L)
    /** 每记一笔加一;界面跟着它重新统计。 */
    val version: StateFlow<Long> = _version.asStateFlow()

    init {
        load()
    }

    override fun record(source: UsageSource, usage: Usage?) {
        val cell = Cell(Math.floorDiv(now(), HOUR_MS), UsageKey(source.providerId, source.model, source.purpose))
        val queue = synchronized(lock) {
            cells[cell] = (cells[cell] ?: UsageCounts()) + UsageCounts.of(usage)
            if (source.providerName.isNotEmpty()) names[source.providerId] = source.providerName
            dirty = true
            !flushQueued.also { flushQueued = true }
        }
        _version.update { it + 1 }
        if (queue) writer.schedule({ runCatching { flush() } }, flushDelayMs, TimeUnit.MILLISECONDS)
    }

    fun snapshot(): UsageSnapshot = synchronized(lock) {
        UsageSnapshot(cells.map { (c, n) -> UsageRow(c.hour, c.key, n) }, HashMap(names))
    }

    /** 有没写出的改动就当场写出(后台线程定时调;测试和退出前也可直接调)。写失败时留着改动,下次再写。 */
    fun flush() {
        synchronized(writeLock) {
            val text = synchronized(lock) {
                flushQueued = false
                if (!dirty) return
                dirty = false
                prune()
                serialize()
            }
            try {
                writeAtomically(text)
            } catch (e: Exception) {
                synchronized(lock) { dirty = true }
                throw e
            }
        }
    }

    private fun cutoffHour(): Long = Math.floorDiv(now(), HOUR_MS) - keepDays * 24L

    private fun prune() {
        val cutoff = cutoffHour()
        cells.keys.removeAll { it.hour < cutoff }
    }

    private fun serialize(): String {
        val keys = cells.keys.map { it.key }.distinct()
        val index = keys.withIndex().associate { (i, k) -> k to i }
        return buildJsonObject {
            put("version", FORMAT)
            putJsonObject("names") { names.forEach { (id, n) -> put(id, n) } }
            putJsonArray("keys") {
                keys.forEach { k -> addJsonObject { put("provider", k.providerId); put("model", k.model); put("purpose", k.purpose.key) } }
            }
            putJsonArray("rows") {
                cells.entries.sortedBy { it.key.hour }.forEach { (c, n) ->
                    addJsonArray {
                        add(JsonPrimitive(c.hour)); add(JsonPrimitive(index.getValue(c.key)))
                        add(JsonPrimitive(n.input)); add(JsonPrimitive(n.output)); add(JsonPrimitive(n.cached))
                        add(JsonPrimitive(n.calls)); add(JsonPrimitive(n.callsWithoutUsage))
                    }
                }
            }
        }.toString()
    }

    private fun load() {
        if (!file.isFile) return
        runCatching {
            val root = Json.parseToJsonElement(file.readText()).jsonObject
            root["names"]?.jsonObject?.forEach { (id, n) -> names[id] = n.jsonPrimitive.content }
            val keys = root["keys"]?.jsonArray.orEmpty().map { e ->
                val o = e.jsonObject
                UsageKey(
                    o.getValue("provider").jsonPrimitive.content, o.getValue("model").jsonPrimitive.content,
                    UsagePurpose.of(o.getValue("purpose").jsonPrimitive.content),
                )
            }
            val cutoff = cutoffHour()
            root["rows"]?.jsonArray.orEmpty().forEach { e ->
                val r = (e as JsonArray).map { it.jsonPrimitive }
                val hour = r[0].long
                if (hour < cutoff) return@forEach
                val cell = Cell(hour, keys[r[1].int])
                val n = UsageCounts(r[2].long, r[3].long, r[4].long, r[5].long, r[6].long)
                cells[cell] = (cells[cell] ?: UsageCounts()) + n
            }
        }.onFailure {
            cells.clear()
            names.clear()
        }
    }

    private fun writeAtomically(text: String) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(file)) {
            file.delete()
            check(tmp.renameTo(file)) { "cannot write ${file.name}" }
        }
    }

    companion object {
        const val KEEP_DAYS = 400
        const val HOUR_MS = 3_600_000L
        private const val FORMAT = 1
    }
}
