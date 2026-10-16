package com.ai.assistance.quro.terminal.utils

import android.content.Context
import android.util.Log
import com.ai.assistance.quro.terminal.TerminalManager
import com.ai.assistance.quro.terminal.runtime.GuestEnvStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * 单个远程服务（SSH / VNC）的界面状态。
 *
 * 「运行中」不是猜的：只有 TCP 探针真的读到服务 banner（`SSH-2.0-` / `RFB 003.`）才算
 * [Running]；启动调用成功但端口没起来，同样判 [Failed]。
 */
sealed interface RemoteServiceState {
    /** 正在探测。 */
    object Probing : RemoteServiceState

    /** 服务确实在监听（[banner] 是服务端返回的真实 banner）。 */
    data class Running(val banner: String) : RemoteServiceState

    /** 未在监听。 */
    object Stopped : RemoteServiceState

    /** 正在执行安装/启动/停止。 */
    data class Working(
        val step: String,
        val startedAtMs: Long,
        val log: List<String>,
    ) : RemoteServiceState

    /** 执行失败（[reason] 是给用户看的一句话，[log] 是脚本输出尾巴）。 */
    data class Failed(val reason: String, val log: List<String>) : RemoteServiceState
}

/**
 * 远程连接（SSH / VNC）的运行时中心。
 *
 * ## 关键修正（相对旧实现）
 * 旧实现把 sshd / vncserver `apt` 装在 proot + Ubuntu 里，于是「没有连接环境」
 * （Ubuntu 未初始化）就完全连不上，且 [RemoteAccessCenter] 在 [GuestEnvStatus.ASSET_MISSING]
 * 时直接 return 阻断 —— 正是用户报「一直安装、连不上」的根因。
 *
 * 新版改为**真正内置**：SSH 始终用 Apache MINA SSHD（[BuiltinSshServer]，host 进程，
 * 环境无关）；VNC 则按 Ubuntu 就绪情况二选一 —— **rootfs 就绪时在 proot 内启动真正的
 * tigervnc 桌面**（满足「需要 Ubuntu 桌面 + 显示服务」），**未就绪时退化为 host 进程的自研
 * RFB 3.8 字符终端**([HostVncServer]，环境无关，仍能连)。两条 VNC 路都监听 127.0.0.1:5901，
 * 客户端连接信息不变。核心目的不变：本地连本地，且没有连接环境时也连得上。
 *
 * ## 状态与界面解耦
 * 动作跑在模块级协程里，状态放在 [MutableStateFlow]；弹窗关掉任务仍跑，重开即见进度。
 * 完成以 TCP 探针为准：启动后一定再做一次探针，避免「调用说成功、端口没起」的假成功。
 */
object RemoteAccessCenter {

    private const val TAG = "RemoteAccessCenter"

    /** 界面日志区最多保留的行数。 */
    private const val LOG_TAIL = 80

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _envStatus = MutableStateFlow<GuestEnvStatus?>(null)
    val envStatus: StateFlow<GuestEnvStatus?> = _envStatus.asStateFlow()

    private val _addresses = MutableStateFlow<List<RemoteAccessManager.NetAddr>>(emptyList())
    val addresses: StateFlow<List<RemoteAccessManager.NetAddr>> = _addresses.asStateFlow()

    /** 当前后端（Ubuntu proot / Host busybox），供界面展示。 */
    private val _backend = MutableStateFlow("—")
    val backend: StateFlow<String> = _backend.asStateFlow()

    private val _sshState = MutableStateFlow<RemoteServiceState>(RemoteServiceState.Probing)
    val sshState: StateFlow<RemoteServiceState> = _sshState.asStateFlow()

    private val _vncState = MutableStateFlow<RemoteServiceState>(RemoteServiceState.Probing)
    val vncState: StateFlow<RemoteServiceState> = _vncState.asStateFlow()

    private val jobs = ConcurrentHashMap<RemoteAccessManager.Kind, Job>()

    /** VNC 后端：HOST = 内置字符终端（环境无关）；UBUNTU = Ubuntu 内真实桌面。 */
    private enum class VncBackend { HOST, UBUNTU, NONE }
    @Volatile private var vncBackend: VncBackend = VncBackend.NONE

    private fun stateOf(kind: RemoteAccessManager.Kind): MutableStateFlow<RemoteServiceState> =
        if (kind == RemoteAccessManager.Kind.SSH) _sshState else _vncState

    /** 该服务当前是否有一个动作在跑。 */
    fun isBusy(kind: RemoteAccessManager.Kind): Boolean = jobs[kind]?.isActive == true

    /** 面板打开时调用：刷新环境、IP，并重新探测两个服务。 */
    fun refresh(context: Context) {
        val app = context.applicationContext
        scope.launch {
            val manager = TerminalManager.getInstance(app)
            _addresses.value = withContext(Dispatchers.IO) { RemoteAccessManager.connectAddresses() }
            _envStatus.value = withContext(Dispatchers.IO) { manager.guestEnvStatus() }
            // 后端随已运行的服务刷新
            updateBackend()
            probe(app, RemoteAccessManager.Kind.SSH)
            probe(app, RemoteAccessManager.Kind.VNC)
        }
    }

    /** 探测单个服务端口，更新状态。 */
    fun probe(context: Context, kind: RemoteAccessManager.Kind) {
        val app = context.applicationContext
        val flow = stateOf(kind)
        if (isBusy(kind)) return
        scope.launch {
            flow.value = RemoteServiceState.Probing
            val banner = withContext(Dispatchers.IO) { RemoteAccessManager.probe(kind) }
            flow.value =
                if (banner != null) RemoteServiceState.Running(banner) else RemoteServiceState.Stopped
        }
    }

    /** 安装并启动服务（幂等：已起则直接复核）。 */
    fun provision(context: Context, kind: RemoteAccessManager.Kind) {
        if (isBusy(kind)) return
        val app = context.applicationContext
        val flow = stateOf(kind)
        val job = scope.launch {
            try {
                if (kind == RemoteAccessManager.Kind.SSH) runSsh(app, flow, start = true)
                else runVnc(app, flow, start = true)
            } finally {
                jobs.remove(kind)
            }
        }
        jobs[kind] = job
    }

    /** 停止服务。 */
    fun stop(context: Context, kind: RemoteAccessManager.Kind) {
        if (isBusy(kind)) return
        val app = context.applicationContext
        val flow = stateOf(kind)
        val job = scope.launch {
            try {
                if (kind == RemoteAccessManager.Kind.SSH) runSsh(app, flow, start = false)
                else runVnc(app, flow, start = false)
            } finally {
                jobs.remove(kind)
            }
        }
        jobs[kind] = job
    }

    /** 汇总当前后端展示文案（SSH host/Ubuntu + VNC 桌面/终端）。 */
    private fun updateBackend() {
        val parts = mutableListOf<String>()
        if (BuiltinSshServer.isRunning()) parts += BuiltinSshServer.backendLabel
        if (vncBackend == VncBackend.UBUNTU) parts += "Ubuntu 桌面"
        if (HostVncServer.isRunning()) parts += "Host 终端"
        _backend.value = if (parts.isEmpty()) "—" else parts.joinToString(" / ")
    }

    /** SSH：始终用 host 级 Apache MINA SSHD（环境无关，桥接到 proot/Ubuntu 或 host busybox）。 */
    private suspend fun runSsh(
        context: Context,
        flow: MutableStateFlow<RemoteServiceState>,
        start: Boolean,
    ) {
        flow.value = RemoteServiceState.Working(
            if (start) "正在启动内置 SSH 服务（host 进程，环境无关）…"
            else "正在停止 SSH 服务…",
            System.currentTimeMillis(),
            emptyList(),
        )
        try {
            val ok = withContext(Dispatchers.IO) {
                if (start) BuiltinSshServer.start(context)
                else { BuiltinSshServer.stop(); true }
            }
            if (!ok) {
                flow.value = RemoteServiceState.Failed(
                    if (start) "SSH 服务启动失败：宿主运行环境未就绪（native 库缺失）"
                    else "SSH 服务停止失败",
                    emptyList(),
                )
                return
            }
            updateBackend()
            delay(400)
            val banner = withContext(Dispatchers.IO) { RemoteAccessManager.probe(RemoteAccessManager.Kind.SSH) }
            flow.value = when {
                start && banner != null -> RemoteServiceState.Running(banner)
                start -> RemoteServiceState.Failed("SSH 服务已启动，但端口未响应（可能端口被占用）", emptyList())
                banner == null -> RemoteServiceState.Stopped
                else -> RemoteServiceState.Failed("SSH 服务已停止，但端口仍在监听", emptyList())
            }
        } catch (e: Exception) {
            Log.e(TAG, "runSsh 异常", e)
            flow.value = RemoteServiceState.Failed("执行异常：${e.message ?: e.javaClass.simpleName}", emptyList())
        }
    }

    /**
     * VNC：后端随 Ubuntu 就绪情况切换。
     * - rootfs 就绪 → 在 proot 内启动**真正的** tigervnc 桌面（满足「Ubuntu 桌面 + 显示服务」）；
     * - 未就绪 → 退化为 host 级 [HostVncServer] 字符终端（环境无关，仍能连）。
     * 两条路都监听同一个 127.0.0.1:5901，客户端连接信息不变。
     */
    private suspend fun runVnc(
        context: Context,
        flow: MutableStateFlow<RemoteServiceState>,
        start: Boolean,
    ) {
        val manager = TerminalManager.getInstance(context.applicationContext)
        val ubuntuReady = withContext(Dispatchers.IO) { manager.guestEnvStatus() == GuestEnvStatus.READY }
        val target = if (ubuntuReady) VncBackend.UBUNTU else VncBackend.HOST
        flow.value = RemoteServiceState.Working(
            if (start) {
                if (target == VncBackend.UBUNTU) {
                    "正在 Ubuntu 内启动桌面 VNC（tigervnc + 窗口管理器，首次需 apt 安装，请保持前台）…"
                } else {
                    "正在启动内置 VNC 服务（host 终端，环境无关）…"
                }
            } else "正在停止 VNC 服务…",
            System.currentTimeMillis(),
            emptyList(),
        )
        try {
            val ok = withContext(Dispatchers.IO) {
                // 切换后端前先停掉另一个后端，避免 5901 端口冲突
                if (start && target != vncBackend) {
                    if (vncBackend == VncBackend.UBUNTU) stopGuestVnc(context)
                    if (vncBackend == VncBackend.HOST) HostVncServer.stop()
                }
                if (!start) {
                    if (vncBackend == VncBackend.UBUNTU) stopGuestVnc(context)
                    else HostVncServer.stop()
                    true
                } else {
                    when (target) {
                        VncBackend.UBUNTU -> {
                            val script = RemoteAccessManager.vncProvisionScript(context)
                            val res = manager.runInGuest(
                                script,
                                RemoteAccessManager.VNC_DESKTOP_TIMEOUT_MS,
                            ) { line ->
                                val cur = flow.value
                                if (cur is RemoteServiceState.Working) {
                                    flow.value = cur.copy(log = (cur.log + line).takeLast(LOG_TAIL))
                                }
                            }
                            if (!res.ok) {
                                flow.value = RemoteServiceState.Failed(
                                    if (res.timedOut) "Ubuntu 桌面 VNC 安装超时（apt 太慢或网络不通）"
                                    else res.error ?: "Ubuntu 桌面 VNC 启动失败",
                                    res.tailLines(LOG_TAIL),
                                )
                                return@withContext false
                            }
                            true
                        }
                        VncBackend.HOST -> HostVncServer.start(context)
                        VncBackend.NONE -> true
                    }
                }
            }
            if (!ok) {
                if (flow.value !is RemoteServiceState.Failed) {
                    flow.value = RemoteServiceState.Failed("VNC 服务启动失败", emptyList())
                }
                return
            }
            vncBackend = if (start) target else VncBackend.NONE
            updateBackend()
            delay(400)
            val banner = withContext(Dispatchers.IO) { RemoteAccessManager.probe(RemoteAccessManager.Kind.VNC) }
            flow.value = when {
                start && banner != null -> RemoteServiceState.Running(banner)
                start -> RemoteServiceState.Failed("VNC 服务已启动，但端口未响应（可能端口被占用）", emptyList())
                banner == null -> RemoteServiceState.Stopped
                else -> RemoteServiceState.Failed("VNC 服务已停止，但端口仍在监听", emptyList())
            }
        } catch (e: Exception) {
            Log.e(TAG, "runVnc 异常", e)
            flow.value = RemoteServiceState.Failed("执行异常：${e.message ?: e.javaClass.simpleName}", emptyList())
        }
    }

    /** 在 proot guest 内停止 Ubuntu 桌面 VNC（幂等）。 */
    private suspend fun stopGuestVnc(context: Context) {
        val manager = TerminalManager.getInstance(context.applicationContext)
        val script = RemoteAccessManager.vncStopScript(context)
        runCatching { manager.runInGuest(script, RemoteAccessManager.STOP_TIMEOUT_MS, null) }
    }
}
