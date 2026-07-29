package com.ai.assistance.quro.core.cms2

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/**
 * CMS v2 · 状态系统（P0 骨干）。
 *
 * 设计定位（与 v1 解耦、纯加法、零回归）：
 * - [Cms2StateStore] 是 CMS v2 的**唯一可信状态源**（single source of truth），所有模块的运行态、
 *   部署态、实时日志、工具执行结果都集中在此，供 UI（部署器 / 实时日志面板）订阅刷新。
 * - 与 v1 的 [com.ai.assistance.quro.core.cms.QuroCmsRepository] 互不干扰：v2 走独立文件
 *   `cms2_state.json`，不读写 v1 的 `cms_modules.json`。
 * - 线程安全：状态写用 synchronized；监听器用 CopyOnWriteArrayList；日志用定长环形缓冲（默认 500 条）。
 * - 持久化：每次关键状态变更落盘（轻量 JSON），进程重启可恢复最近运行态（非强一致，够用）。
 *
 * 后续阶段（P1 执行引擎 / P2 工具面 / P3 部署器）都建立在它的状态读写之上。
 */

/** 模块生命周期状态机。 */
enum class Cms2ModuleState(val code: String, val label: String) {
    DRAFT("draft", "草稿"),
    BUILT("built", "已构建"),
    DEPLOYED("deployed", "已部署"),
    RUNNING("running", "运行中"),
    STOPPED("stopped", "已停止"),
    FAILED("failed", "失败"),
    UNKNOWN("unknown", "未知");

    companion object {
        fun fromCode(c: String?): Cms2ModuleState =
            entries.firstOrNull { it.code == c } ?: UNKNOWN
    }
}

/** 日志级别。 */
enum class Cms2LogLevel(val code: String) {
    DEBUG("debug"), INFO("info"), WARN("warn"), ERROR("error")
}

/** 一条实时日志。 */
data class Cms2LogEntry(
    val id: Long,
    val ts: Long,
    val level: Cms2LogLevel,
    val source: String,   // 来源：模块 id / 引擎 / 部署器
    val message: String,
)

/** 单模块运行态快照。 */
data class Cms2RuntimeStatus(
    val moduleId: String,
    val state: Cms2ModuleState,
    val pid: Long = 0,
    val startedAt: Long = 0,
    val lastError: String = "",
    val endpoint: String = "",   // 运行/部署后的访问端点（HTTP / 终端）
)

/** 工具执行结果缓存。 */
data class Cms2Result(
    val tool: String,
    val ok: Boolean,
    val output: String,
    val ts: Long,
)

/**
 * CMS v2 集中状态存储（单例，按 Context 懒构造）。
 */
class Cms2StateStore private constructor(private val ctx: Context) {

    private val file = File(ctx.filesDir, "cms2_state.json")
    private val seq = AtomicLong(System.currentTimeMillis())

    // ── 内存态 ──
    private val states = LinkedHashMap<String, Cms2RuntimeStatus>()
    private val results = LinkedHashMap<String, Cms2Result>()   // key = moduleId
    private val logs = CopyOnWriteArrayList<Cms2LogEntry>()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val logCap = 500

    init { load() }

    // ── 订阅 ──
    fun subscribe(fn: () -> Unit): () -> Unit {
        listeners.add(fn)
        return { listeners.remove(fn) }
    }
    private fun emit() { listeners.forEach { runCatching { it() } } }

    // ── 状态读写 ──
    fun getStatus(moduleId: String): Cms2RuntimeStatus =
        synchronized(states) { states[moduleId] ?: Cms2RuntimeStatus(moduleId, Cms2ModuleState.UNKNOWN) }

    fun getAllStatuses(): List<Cms2RuntimeStatus> = synchronized(states) { states.values.toList() }

    fun setState(moduleId: String, state: Cms2ModuleState, pid: Long = 0, endpoint: String = "", lastError: String = "") {
        synchronized(states) {
            val prev = states[moduleId] ?: Cms2RuntimeStatus(moduleId, Cms2ModuleState.UNKNOWN)
            states[moduleId] = prev.copy(
                state = state,
                pid = if (pid != 0L) pid else prev.pid,
                endpoint = endpoint.ifBlank { prev.endpoint },
                lastError = lastError,
                startedAt = if (state == Cms2ModuleState.RUNNING && prev.state != Cms2ModuleState.RUNNING) System.currentTimeMillis() else prev.startedAt,
            )
        }
        log(if (lastError.isBlank()) Cms2LogLevel.INFO else Cms2LogLevel.WARN, moduleId, "state→${state.label}${if (lastError.isBlank()) "" else " err=$lastError"}")
        persist(); emit()
    }

    // ── 结果 ──
    fun setResult(moduleId: String, result: Cms2Result) {
        synchronized(results) { results[moduleId] = result }
        log(if (result.ok) Cms2LogLevel.INFO else Cms2LogLevel.ERROR, moduleId, "tool=${result.tool} ok=${result.ok}")
        persist(); emit()
    }
    fun getResult(moduleId: String): Cms2Result? = synchronized(results) { results[moduleId] }

    // ── 日志 ──
    fun log(level: Cms2LogLevel, source: String, message: String) {
        val entry = Cms2LogEntry(seq.incrementAndGet(), System.currentTimeMillis(), level, source, message)
        logs.add(entry)
        while (logs.size > logCap) logs.removeAt(0)
        // 日志高频，不每次落盘（仅状态变更落盘），emit 仅通知 UI 自取
        emit()
    }
    fun getLogs(limit: Int = logCap): List<Cms2LogEntry> = logs.takeLast(limit)
    fun getLogsOf(source: String, limit: Int = logCap): List<Cms2LogEntry> =
        logs.filter { it.source == source }.takeLast(limit)

    // ── 持久化 ──
    private fun persist() {
        runCatching {
            val arr = JSONArray()
            synchronized(states) {
                states.values.forEach { s ->
                    arr.put(JSONObject().apply {
                        put("moduleId", s.moduleId); put("state", s.state.code)
                        put("pid", s.pid); put("startedAt", s.startedAt)
                        put("lastError", s.lastError); put("endpoint", s.endpoint)
                    })
                }
            }
            file.writeText(arr.toString())
        }
    }
    private fun load() {
        runCatching {
            if (!file.exists()) return
            val arr = JSONArray(file.readText())
            synchronized(states) {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    states[o.getString("moduleId")] = Cms2RuntimeStatus(
                        moduleId = o.getString("moduleId"),
                        state = Cms2ModuleState.fromCode(o.optString("state")),
                        pid = o.optLong("pid", 0),
                        startedAt = o.optLong("startedAt", 0),
                        lastError = o.optString("lastError", ""),
                        endpoint = o.optString("endpoint", ""),
                    )
                }
            }
        }
    }

    // ── 单例 ──
    companion object {
        @Volatile private var INSTANCE: Cms2StateStore? = null
        fun get(ctx: Context): Cms2StateStore =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Cms2StateStore(ctx.applicationContext).also { INSTANCE = it }
            }
    }
}
