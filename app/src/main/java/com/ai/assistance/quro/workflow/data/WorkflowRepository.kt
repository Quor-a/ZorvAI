package com.ai.assistance.quro.workflow.data

import android.content.Context
import com.ai.assistance.quro.workflow.data.model.Workflow
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * 工作流单一数据源。
 *
 * - 本地 UI 与 ACI 能力层（SchedulerCapability）都读写本仓库 →
 *   用户创建/导入的工作流与 AI 创建的工作流共用同一存储。
 * - 持久化在 filesDir/workflows.json（替代原 SharedPreferences）。
 * - changeSignal 在每次写后自增，供 Compose 通过 collectAsState 实时刷新。
 */
object WorkflowRepository {

    private lateinit var file: File
    private val list = mutableListOf<Workflow>()
    private val lock = Any()

    val changeSignal = MutableStateFlow(0L)

    fun init(ctx: Context) {
        file = File(ctx.filesDir, "workflows.json")
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
                    list.add(Workflow.fromJson(arr.getJSONObject(i)))
                }
            }.onFailure { list.clear() }
        }
    }

    private fun save() = synchronized(lock) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        file.writeText(arr.toString())
        changeSignal.value += 1
    }

    fun getAll(): List<Workflow> = synchronized(lock) { list.toList() }

    fun get(id: String): Workflow? = synchronized(lock) {
        list.firstOrNull { it.id == id }
    }

    fun upsert(wf: Workflow) = synchronized(lock) {
        val idx = list.indexOfFirst { it.id == wf.id }
        if (idx >= 0) list[idx] = wf else list.add(wf)
        save()
    }

    fun delete(id: String): Boolean = synchronized(lock) {
        val before = list.size
        list.removeAll { it.id == id }
        val ok = list.size < before
        if (ok) save()
        ok
    }

    /** 执行器回写运行状态（含最近一次 runId，供历史回看关联） */
    fun updateRun(id: String, status: String, log: String, runId: String = "") = synchronized(lock) {
        val idx = list.indexOfFirst { it.id == id }
        if (idx >= 0) {
            list[idx] = list[idx].copy(
                lastRun = System.currentTimeMillis(),
                lastStatus = status,
                lastLog = log,
                lastRunId = runId
            )
            save()
        }
    }

    /**
     * 导入工作流 JSON（单条对象或数组），按 id 合并（已存在则覆盖）。
     * @return 成功导入条数；出错时返回 (0, 错误信息)
     */
    fun importJson(text: String): Pair<Int, String?> {
        return try {
            val trimmed = text.trim().ifBlank { "[]" }
            val arr = if (trimmed.startsWith("[")) {
                JSONArray(trimmed)
            } else {
                JSONArray().apply { put(JSONObject(trimmed)) }
            }
            var n = 0
            synchronized(lock) {
                for (i in 0 until arr.length()) {
                    val wf = Workflow.fromJson(arr.getJSONObject(i))
                    val w = if (wf.id.isBlank()) wf.copy(id = UUID.randomUUID().toString()) else wf
                    val idx = list.indexOfFirst { it.id == w.id }
                    if (idx >= 0) list[idx] = w else list.add(w)
                    n++
                }
                save()
            }
            n to null
        } catch (e: Exception) {
            0 to (e.message ?: "解析失败")
        }
    }

    fun exportAll(): String = synchronized(lock) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        arr.toString(2)
    }

    fun exportOne(id: String): String? =
        get(id)?.toJson()?.toString(2)
}
