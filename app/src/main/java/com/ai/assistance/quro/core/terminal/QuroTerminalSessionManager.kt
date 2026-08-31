package com.ai.assistance.quro.core.terminal

import android.content.Context
import android.util.Log
import com.ai.assistance.quro.activity.QuroApplication
import com.ai.assistance.quro.core.linux.QuroLinuxEnv
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 统一终端会话管理器（终端架构统一 · 新增）。
 *
 * 解决「AI 工具层 / 终端界面 / CMS 开发环境」三方各自独立起 proot 进程、无共享会话的碎片化问题：
 * 三者现在共用由本管理器持有的**默认共享 shell 会话**（[defaultSession]，一个常驻 [QuroShellSession]，
 * 底层经 proot+Ubuntu 或回退设备 sh），并实现：
 *  - AI、使用者都能**使用/管理**所有会话与后端（list / create / switch / destroy）；
 *  - 能**创建新会话**；
 *  - 打开终端界面时**跟随启动 zorvAI 终端环境**（installIfMissing=true，必要时安装 proot/Ubuntu）；
 *  - **zorvAI 自启动**时由 [com.ai.assistance.quro.service.QuroTerminalKeepAliveService] 保活默认会话
 *    （installIfMissing=false，避免开机无网络时误下载 / 消耗流量）。
 *
 * 关于 CMS 开发环境：其 `cms` / `devenv` 控制（[QuroHostBridge]）与默认会话共用同一 proot/Ubuntu 后端
 * （[QuroLinuxEnv]），因此「统一默认终端会话和后端」在本架构下天然成立——本管理器只负责把
 * 「共享会话」这一层显式化并暴露管理能力。
 */
object QuroTerminalSessionManager {

    private const val TAG = "QuroTermSessionMgr"
    private const val STORE_FILE = "quro_terminal_sessions.json"
    private const val DEFAULT_NAME = "默认会话"
    private const val UI_ID = "__ui_termux__"

    enum class Backend { LINUX_PROOT, DEVICE_SH }
    enum class Kind { DEFAULT, EXTRA, UI_TERMUX }

    /** 对外的会话摘要（不含进程句柄，可安全序列化 / 跨层传递）。 */
    data class SessionInfo(
        val id: String,
        val name: String,
        val kind: Kind,
        val backend: Backend,
        val isDefault: Boolean,
        val alive: Boolean,
        val createdAt: Long,
    )

    /** 内部会话条目：id/元数据 + 可选的常驻 shell 进程。 */
    private class Entry(
        val id: String,
        val name: String,
        val kind: Kind,
        val backend: Backend,
        val createdAt: Long,
        var shell: QuroShellSession? = null,
    ) {
        val alive: Boolean get() = shell?.exited == false
        fun toInfo() = SessionInfo(
            id = id, name = name, kind = kind, backend = backend,
            isDefault = kind == Kind.DEFAULT, alive = alive, createdAt = createdAt,
        )
    }

    @Volatile
    private var defaultEntry: Entry? = null
    private val extras = ConcurrentHashMap<String, Entry>()
    @Volatile
    private var uiEntry: Entry? = null
    /** 跨进程重启存活的历史会话元数据（仅展示，进程已消亡，alive=false）。 */
    private val history = ConcurrentHashMap<String, SessionInfo>()
    private val mutex = Mutex()
    private val bgScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 默认共享 shell 会话（AI 工具 / CMS / 使用者共用的那一个）。 */
    val defaultSession: QuroShellSession?
        get() = defaultEntry?.shell

    // ───────────────────────── 生命周期 ─────────────────────────

    /** 进程启动期载入持久化的会话元数据（不重建进程，进程在重启后已消亡）。 */
    fun load(context: Context) {
        try {
            val file = File(context.filesDir, STORE_FILE)
            if (!file.exists()) return
            val arr = JSONArray(file.readText())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val info = SessionInfo(
                    id = o.getString("id"),
                    name = o.getString("name"),
                    kind = Kind.valueOf(o.getString("kind")),
                    backend = Backend.valueOf(o.getString("backend")),
                    isDefault = o.optBoolean("isDefault", false),
                    alive = false,
                    createdAt = o.optLong("createdAt", System.currentTimeMillis()),
                )
                history[info.id] = info
            }
            Log.i(TAG, "已载入 ${history.size} 条历史会话元数据")
        } catch (e: Exception) {
            Log.w(TAG, "载入会话元数据失败（忽略）: ${e.message}")
        }
    }

    /**
     * 确保默认共享会话存在（若不存在则跟随创建）。
     *
     * @param installIfMissing 后端（proot/Ubuntu）未就绪时是否触发安装/下载。
     *         - 打开终端界面 / 用户主动操作：true（跟随启动 zorvAI 终端环境）。
     *         - 应用自启动 / 开机保活：false（避免无网络时误下载、或开机即消耗流量）。
     */
    suspend fun ensureDefault(context: Context, installIfMissing: Boolean = true): QuroShellSession? {
        if (defaultEntry?.alive == true) return defaultEntry!!.shell
        return mutex.withLock {
            if (defaultEntry?.alive == true) return@withLock defaultEntry!!.shell
            // ⚠ ANR 修复：以下全是重操作（文件 I/O、可能下载并解压 rootfs、启动 proot 进程），
            // 必须切到 IO 线程。本函数虽是 suspend，但调用方（如 UI 的 rememberCoroutineScope，
            // 默认 Main 调度器）并不一定会切线程——不在这里 withContext 就会把重活全压到主线程。
            // 真机实测 ANR：Input dispatching timed out，而应用进程 CPU 仅 3.7%（典型阻塞态）。
            withContext(Dispatchers.IO) {
                if (installIfMissing) {
                    val st = QuroLinuxEnv.probeLenient(context)
                    if (!st.available) {
                        Log.i(TAG, "ensureDefault: 后端未就绪，跟随安装 Linux 环境…")
                        QuroLinuxEnv.ensureInstalledBlocking(context)
                    }
                }
                val shell = QuroShellSession.create(context)
                val backend = if (shell.mode == ShellMode.LINUX) Backend.LINUX_PROOT else Backend.DEVICE_SH
                val entry = Entry("default", DEFAULT_NAME, Kind.DEFAULT, backend, System.currentTimeMillis(), shell)
                shell.onExit = { code -> Log.i(TAG, "默认会话退出(exit=$code)，保活服务将重建") }
                defaultEntry = entry
                persist()
                Log.i(TAG, "✅ 默认共享会话已创建: backend=$backend")
                shell
            }
        }
    }

    /** 非挂起包装：用于 Application / 保活服务的 fire-and-forget 调用。 */
    fun ensureDefaultAsync(context: Context, installIfMissing: Boolean = true) {
        bgScope.launch { ensureDefault(context, installIfMissing) }
    }

    /** 列出所有会话（默认 / 额外 / UI 终端 / 历史），供 AI 工具与界面会话面板使用。 */
    fun listSessions(): List<SessionInfo> {
        val out = mutableListOf<SessionInfo>()
        defaultEntry?.let { out.add(it.toInfo()) }
        extras.values.forEach { out.add(it.toInfo()) }
        uiEntry?.let { out.add(it.toInfo()) }
        // 合并历史中未存活的会话（仅展示，供用户决定是否重建）
        history.values.forEach { h ->
            if (out.none { it.id == h.id }) out.add(h.copy(alive = false))
        }
        return out.sortedBy { it.createdAt }
    }

    /**
     * 根据 ID 获取会话信息。
     * @param sessionId 会话 ID
     * @return 会话信息，如果不存在返回 null
     */
    fun getSession(sessionId: String): SessionInfo? {
        // 检查默认会话
        defaultEntry?.let { entry ->
            if (entry.id == sessionId) return entry.toInfo()
        }
        
        // 检查额外会话
        extras[sessionId]?.let { entry ->
            return entry.toInfo()
        }
        
        // 检查 UI 会话
        uiEntry?.let { entry ->
            if (entry.id == sessionId) return entry.toInfo()
        }
        
        // 检查历史会话
        history[sessionId]?.let { h ->
            return h.copy(alive = false)
        }
        
        return null
    }

    /**
     * 根据 ID 获取实际的 QuroShellSession 对象。
     * @param sessionId 会话 ID
     * @return QuroShellSession 对象，如果不存在或已退出返回 null
     */
    fun getShellSession(sessionId: String): QuroShellSession? {
        // 检查默认会话
        defaultEntry?.let { entry ->
            if (entry.id == sessionId) return entry.shell
        }
        
        // 检查额外会话
        extras[sessionId]?.let { entry ->
            return entry.shell
        }
        
        // 检查 UI 会话
        uiEntry?.let { entry ->
            if (entry.id == sessionId) return entry.shell
        }
        
        return null
    }

    /**
     * 创建一个新的额外 shell 会话（满足「创建新会话」需求）。
     * 新会话不会自动成为默认；如需切换默认请调用 [switchDefault]。
     */
    suspend fun createSession(context: Context, name: String?, installIfMissing: Boolean = true): SessionInfo {
        // ⚠ ANR 修复：同上，会话创建含文件 I/O / 可能安装 rootfs / 启动 proot 进程，
        // 必须切 IO 线程。UI「+ 新会话」按钮用的是 rememberCoroutineScope（Main 调度器），
        // 不切线程会直接 5 秒 Input dispatching 超时 ANR。
        return withContext(Dispatchers.IO) {
            if (installIfMissing) {
                val st = QuroLinuxEnv.probeLenient(context)
                if (!st.available) QuroLinuxEnv.ensureInstalledBlocking(context)
            }
            val shell = QuroShellSession.create(context)
            val backend = if (shell.mode == ShellMode.LINUX) Backend.LINUX_PROOT else Backend.DEVICE_SH
            val id = UUID.randomUUID().toString().take(8)
            val entry = Entry(
                id = id,
                name = name?.takeIf { it.isNotBlank() } ?: "会话 $id",
                kind = Kind.EXTRA,
                backend = backend,
                createdAt = System.currentTimeMillis(),
                shell = shell,
            )
            extras[id] = entry
            persist()
            Log.i(TAG, "✅ 新会话已创建: id=$id name=${entry.name} backend=$backend")
            entry.toInfo()
        }
    }

    /** 把某个已存在的会话提升为默认（AI/使用者切换默认会话）。原默认降级为额外会话并保留进程。 */
    suspend fun switchDefault(id: String): Boolean {
        if (id == "default") return defaultEntry?.alive == true
        val target = extras[id] ?: return false
        return mutex.withLock {
            // 保留原始会话的 ID，而不是使用固定的 "default"
            val newDefault = Entry(id, target.name, Kind.DEFAULT, target.backend, target.createdAt, target.shell)
            val old = defaultEntry
            defaultEntry = newDefault
            extras.remove(id)
            // 旧默认（若有进程）降级为额外会话，避免进程丢失
            if (old?.shell != null) {
                // 旧默认会话使用随机生成的 ID，避免与新默认会话冲突
                val oldId = if (old.id == "default") UUID.randomUUID().toString().take(8) else old.id
                extras[oldId] = Entry(oldId, old.name, Kind.EXTRA, old.backend, old.createdAt, old.shell)
            }
            persist()
            Log.i(TAG, "默认会话已切换为原 id=$id")
            true
        }
    }

    /** 销毁指定会话（默认会话被销毁后会回到「需重建」状态，下次 ensureDefault 重新创建）。 */
    suspend fun destroySession(id: String): Boolean = mutex.withLock {
        when {
            id == "default" || defaultEntry?.id == id -> {
                defaultEntry?.shell?.destroy()
                defaultEntry = null
                persist()
                Log.i(TAG, "默认会话已销毁")
                true
            }
            extras.containsKey(id) -> {
                extras[id]?.shell?.destroy()
                extras.remove(id)
                persist()
                Log.i(TAG, "额外会话已销毁: id=$id")
                true
            }
            else -> false
        }
    }

    /** 销毁默认会话（terminal_kill 用）。 */
    suspend fun killDefault(): Boolean = destroySession("default")

    // ───────────────────────── UI 终端登记 ─────────────────────────

    /** 终端界面打开时登记其 Termux PTY 会话（仅用于会话列表展示与统一管理，不参与默认 shell）。 */
    fun registerUiSession(backend: Backend) {
        uiEntry = Entry(UI_ID, "终端界面 (Termux PTY)", Kind.UI_TERMUX, backend, System.currentTimeMillis(), null)
        Log.i(TAG, "UI 终端会话已登记: backend=$backend")
    }

    fun unregisterUiSession() {
        uiEntry = null
        Log.i(TAG, "UI 终端会话已注销")
    }

    fun isUiRegistered(): Boolean = uiEntry != null

    // ───────────────────────── 内部 ─────────────────────────

    private fun persist() {
        val ctx = QuroApplication.appCtx ?: return
        try {
            val arr = JSONArray()
            defaultEntry?.let { arr.put(descriptor(it)) }
            extras.values.forEach { arr.put(descriptor(it)) }
            File(ctx.filesDir, STORE_FILE).writeText(arr.toString())
        } catch (e: Exception) {
            Log.w(TAG, "持久化会话失败（忽略）: ${e.message}")
        }
    }

    private fun descriptor(e: Entry) = JSONObject().apply {
        put("id", e.id)
        put("name", e.name)
        put("kind", e.kind.name)
        put("backend", e.backend.name)
        put("isDefault", e.kind == Kind.DEFAULT)
        put("createdAt", e.createdAt)
    }
}
