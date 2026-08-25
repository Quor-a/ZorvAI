package com.ai.assistance.quro.workflow.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 运行态持久化（runs.json）。
 *
 * 每次运行产生一条 RunRecord，记录 runId / 状态 / 起止时间 / 日志。
 * 进程死亡后记录仍在，详情页可回看历史运行。保留最近 200 条。
 */
data class RunRecord(
    val id: String,
    val wfId: String,
    val wfName: String,
    val status: String,            // running | success | failed
    val startedAt: Long,
    val finishedAt: Long,
    val log: String,
    val inputs: Map<String, String> = emptyMap()
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("wfId", wfId)
        put("wfName", wfName)
        put("status", status)
        put("startedAt", startedAt)
        put("finishedAt", finishedAt)
        put("log", log)
        val ins = JSONObject()
        inputs.forEach { (k, v) -> ins.put(k, v) }
        put("inputs", ins)
    }

    companion object {
        fun fromJson(o: JSONObject): RunRecord {
            val inputs = mutableMapOf<String, String>()
            o.optJSONObject("inputs")?.let { ins ->
                ins.keys().forEach { k -> inputs[k] = ins.optString(k) }
            }
            return RunRecord(
                id = o.optString("id"),
                wfId = o.optString("wfId"),
                wfName = o.optString("wfName"),
                status = o.optString("status", "running"),
                startedAt = o.optLong("startedAt", 0L),
                finishedAt = o.optLong("finishedAt", 0L),
                log = o.optString("log"),
                inputs = inputs
            )
        }
    }
}

object RunStore {

    private const val MAX_KEEP = 200

    private lateinit var file: File
    private val list = mutableListOf<RunRecord>()
    private val lock = Any()

    val changeSignal = MutableStateFlow(0L)

    fun init(ctx: Context) {
        file = File(ctx.filesDir, "runs.json")
        load()
    }

    private fun load() {
        synchronized(lock) {
            if (!file.exists()) {
                list.clear()
                return
            }
            runCatching {
                val arr = JSONArray(file.readText())
                list.clear()
                for (i in 0 until arr.length()) {
                    list.add(RunRecord.fromJson(arr.getJSONObject(i)))
                }
            }.onFailure { list.clear() }
        }
    }

    private fun save() = synchronized(lock) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        runCatching { file.writeText(arr.toString()) }
        changeSignal.value += 1
    }

    fun start(id: String, wfId: String, wfName: String, inputs: Map<String, String>) {
        synchronized(lock) {
            list.add(
                0,
                RunRecord(id, wfId, wfName, "running", System.currentTimeMillis(), 0L, "", inputs)
            )
            trim()
            save()
        }
    }

    fun finish(id: String, status: String, log: String, finishedAt: Long) {
        synchronized(lock) {
            val idx = list.indexOfFirst { it.id == id }
            if (idx >= 0) {
                list[idx] = list[idx].copy(status = status, log = log, finishedAt = finishedAt)
            }
            save()
        }
    }

    fun get(id: String): RunRecord? = synchronized(lock) {
        list.firstOrNull { it.id == id }
    }

    fun listForWf(wfId: String): List<RunRecord> = synchronized(lock) {
        list.filter { it.wfId == wfId }
    }

    fun listRecent(limit: Int = 50): List<RunRecord> = synchronized(lock) {
        list.take(limit)
    }

    private fun trim() {
        if (list.size > MAX_KEEP) {
            list.subList(MAX_KEEP, list.size).clear()
        }
    }
}
