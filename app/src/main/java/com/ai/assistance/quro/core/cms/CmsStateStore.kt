package com.ai.assistance.quro.core.cms

import android.content.Context
import android.content.SharedPreferences
import com.ai.assistance.quro.util.QuroDiag
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * CMS v2 状态系统（语义能力运行时 · 状态层）。
 *
 * 解决用户反馈的三处核心痛点：
 * 1) AI 执行拿不到结构化结果 → 每次执行/部署/启动都落 [TaskRecord]（明确终态 + stdout/stderr/exitCode/耗时），
 *    AI 经 cms_status / cms_logs / cms_result 查询（反馈环），而非只能拿到一句文本。
 * 2) 一键部署/安装不知成功否 → [ModuleStateRecord.deployStatus] + 任务终态明确（success/failed + message），
 *    模块部署态跨重启持久化，UI 进入即 re-query，不再「返回重进不知道成功没」。
 * 3) 装包无实时 UI 更新 → 部署/启动过程向 [LogBuffer] 写进度行，UI 订阅 [snapshot] StateFlow 实时刷新。
 */
object CmsStateStore {

    /** 模块级状态：部署态、是否运行中、最近部署时间、最后错误。 */
    data class ModuleStateRecord(
        val moduleId: String,
        val deployStatus: String = "idle", // idle | deploying | deployed | failed
        val running: Boolean = false,
        val lastDeployAt: Long = 0,
        val lastError: String = "",
    )

    /** 任务记录：一次执行/部署/启动/停止的明确终态（AI 可随时回查）。 */
    data class TaskRecord(
        val taskId: String,
        val kind: String,            // deploy | call | start | stop | run_dag
        val target: String,          // moduleId 或 capabilityId
        val status: String,          // pending | running | success | failed
        val progressPct: Int = 0,
        val message: String = "",
        val startedAt: Long = 0,
        val finishedAt: Long = 0,
        val exitCode: Int? = null,
        val stdout: String = "",
        val stderr: String = "",
        val durationMs: Long = 0,
    )

    /** 进程记录（moduleId -> 进程信息）。 */
    data class ProcessRecord(
        val moduleId: String,
        val pid: Int,
        val startedAt: Long,
        val alive: Boolean = false,
    )

    data class Snapshot(
        val modules: Map<String, ModuleStateRecord>,
        val tasks: Map<String, TaskRecord>,
        val processes: Map<String, ProcessRecord>,
        val logs: Map<String, List<String>>,
    )

    private const val RING = 300
    private const val PREFS = "cms_state_v2"

    private val modules = ConcurrentHashMap<String, ModuleStateRecord>()
    private val tasks = ConcurrentHashMap<String, TaskRecord>()
    private val processes = ConcurrentHashMap<String, ProcessRecord>()
    private val logs = ConcurrentHashMap<String, MutableList<String>>()

    private val _snapshot = MutableStateFlow(computeSnapshot())
    val snapshot: StateFlow<Snapshot> = _snapshot.asStateFlow()

    @Volatile private var prefs: SharedPreferences? = null

    /** 幂等初始化（首次持 context 调用即加载持久化模块态）。 */
    fun init(context: Context) {
        if (prefs != null) return
        synchronized(this) {
            if (prefs == null) {
                prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                loadModules()
            }
        }
    }

    private fun computeSnapshot(): Snapshot = Snapshot(
        modules = HashMap(modules),
        tasks = HashMap(tasks),
        processes = HashMap(processes),
        logs = logs.mapValues { ArrayList(it.value) },
    )

    private fun emit() { _snapshot.value = computeSnapshot() }

    // ---------- 模块态 ----------

    fun getModule(moduleId: String): ModuleStateRecord? = modules[moduleId]

    fun markDeployStart(moduleId: String, message: String = "开始部署…") {
        modules[moduleId] = (modules[moduleId] ?: ModuleStateRecord(moduleId)).copy(
            deployStatus = "deploying", running = false, lastError = "",
        )
        appendLog(moduleId, "▶ $message")
        upsertTask(TaskRecord("deploy:$moduleId", "deploy", moduleId, "running", 5, message, startedAt = now()))
        persistModules(); emit()
    }

    fun markDeployStep(moduleId: String, message: String, pct: Int = -1) {
        appendLog(moduleId, "• $message")
        tasks["deploy:$moduleId"]?.let { t ->
            tasks["deploy:$moduleId"] = t.copy(
                message = message,
                progressPct = if (pct >= 0) pct else t.progressPct,
            )
            emit()
        }
    }

    fun markDeployEnd(moduleId: String, ok: Boolean, message: String) {
        modules[moduleId] = (modules[moduleId] ?: ModuleStateRecord(moduleId)).copy(
            deployStatus = if (ok) "deployed" else "failed",
            running = false,
            lastDeployAt = now(),
            lastError = if (ok) "" else message,
        )
        appendLog(moduleId, if (ok) "✅ $message" else "⛔ $message")
        tasks["deploy:$moduleId"]?.let { t ->
            tasks["deploy:$moduleId"] = t.copy(
                status = if (ok) "success" else "failed",
                progressPct = if (ok) 100 else t.progressPct,
                message = message, finishedAt = now(),
            )
        }
        persistModules(); emit()
    }

    fun setRunning(moduleId: String, running: Boolean) {
        val cur = modules[moduleId] ?: ModuleStateRecord(moduleId)
        modules[moduleId] = cur.copy(running = running)
        emit()
    }

    fun setProcess(moduleId: String, pid: Int, alive: Boolean) {
        processes[moduleId] = ProcessRecord(moduleId, pid, now(), alive)
        emit()
    }

    fun clearProcess(moduleId: String) {
        processes.remove(moduleId)
        emit()
    }

    // ---------- 任务 ----------

    fun newTask(kind: String, target: String, message: String = ""): String {
        val id = "${kind}_${UUID.randomUUID().toString().take(8)}"
        tasks[id] = TaskRecord(id, kind, target, "running", 0, message, startedAt = now())
        emit()
        return id
    }

    fun updateTask(id: String, progressPct: Int = -1, message: String? = null) {
        val t = tasks[id] ?: return
        tasks[id] = t.copy(
            progressPct = if (progressPct >= 0) progressPct else t.progressPct,
            message = message ?: t.message,
        )
        emit()
    }

    fun finishTask(
        id: String,
        ok: Boolean,
        message: String,
        exitCode: Int? = null,
        stdout: String = "",
        stderr: String = "",
        durationMs: Long = 0,
    ) {
        val t = tasks[id] ?: return
        tasks[id] = t.copy(
            status = if (ok) "success" else "failed",
            progressPct = if (ok) 100 else t.progressPct,
            message = message, finishedAt = now(),
            exitCode = exitCode, stdout = stdout, stderr = stderr, durationMs = durationMs,
        )
        emit()
    }

    fun getTask(id: String): TaskRecord? = tasks[id]

    /** 插入或覆盖一条任务记录（按 taskId）。部署任务用固定 id（"deploy:<moduleId>"）跨 start/step/end 复用，故需 upsert。 */
    fun upsertTask(t: TaskRecord) {
        tasks[t.taskId] = t
        emit()
    }

    // ---------- 日志 ----------

    fun appendLog(moduleId: String, line: String) {
        val list = logs.getOrPut(moduleId) { mutableListOf() }
        synchronized(list) {
            list.add("[${ts()}] $line")
            while (list.size > RING) list.removeAt(0)
        }
        // 🔎 诊断闭环：原 appendLog 只写内存环缓冲，模块部署/启动失败从不进
        // Download/QuroAI_logs。同步落 QuroDiag，设备侧直接取真机日志。
        QuroDiag.log("CMS", "[$moduleId] $line")
        emit()
    }

    fun getLogs(moduleId: String): String =
        logs[moduleId]?.joinToString("\n") ?: "(无日志)"

    // ---------- 汇总（供 AI 工具） ----------

    fun summary(): String = buildString {
        append("CMS v2 状态摘要：\n")
        if (modules.isEmpty()) append("- 暂无模块状态记录\n")
        modules.forEach { (id, m) ->
            append("- [$id] 部署=${m.deployStatus}${if (m.running) " 运行中" else ""}")
            if (m.lastDeployAt > 0) append(" 最近=${fmt(m.lastDeployAt)}")
            if (m.lastError.isNotBlank()) append(" 错误=${m.lastError}")
            append("\n")
        }
        val recent = tasks.values.sortedByDescending { it.startedAt }.take(10)
        if (recent.isNotEmpty()) {
            append("\n最近任务：\n")
            recent.forEach { t ->
                append("- ${t.taskId} [${t.kind}] ${t.target} → ${t.status}")
                if (t.message.isNotBlank()) append(" (${t.message})")
                append("\n")
            }
        }
    }

    // ---------- 持久化 ----------
    // 仅持久化模块态（任务/日志为运行时，重启后清空亦可；模块部署态需跨重启保留以满足「重进知成功」）

    private fun persistModules() {
        prefs?.edit()?.apply {
            val arr = JSONArray()
            modules.forEach { (_, m) ->
                arr.put(JSONObject().apply {
                    put("moduleId", m.moduleId)
                    put("deployStatus", m.deployStatus)
                    put("running", m.running)
                    put("lastDeployAt", m.lastDeployAt)
                    put("lastError", m.lastError)
                })
            }
            putString("modules", arr.toString())
            apply()
        }
    }

    private fun loadModules() {
        val s = prefs?.getString("modules", null) ?: return
        runCatching {
            val arr = JSONArray(s)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                modules[o.getString("moduleId")] = ModuleStateRecord(
                    moduleId = o.getString("moduleId"),
                    deployStatus = o.optString("deployStatus", "idle"),
                    running = o.optBoolean("running", false),
                    lastDeployAt = o.optLong("lastDeployAt", 0),
                    lastError = o.optString("lastError", ""),
                )
            }
        }
    }

    private fun now() = System.currentTimeMillis()
    private fun fmt(ts: Long): String =
        SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(java.util.Date(ts))
    private fun ts(): String =
        SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(java.util.Date(now()))
}
