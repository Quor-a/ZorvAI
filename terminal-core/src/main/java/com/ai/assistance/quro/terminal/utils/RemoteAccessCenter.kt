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
 * 新版改为**真正内置**：SSH 用 Apache MINA SSHD、VNC 用自研 RFB 3.8 服务端，
 * 都跑在 **App host 进程**里（[BuiltinSshServer] / [HostVncServer]），监听 127.0.0.1:
 * 端口，不依赖 proot / Ubuntu。后端 shell 由 [TerminalEnvironment.backendShellCommand] 决定：
 * Ubuntu rootfs 就绪时桥接 proot 登录 shell，否则退化为 Android host 的 busybox sh ——
 * 满足「没有连接环境也能连、连接环境就绪则接 Ubuntu」两边都要接的诉求。
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
            _backend.value = when {
                BuiltinSshServer.isRunning() -> BuiltinSshServer.backendLabel
                HostVncServer.isRunning() -> HostVncServer.backend()
                else -> "—"
            }
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

    /** 安装并启动内置服务（幂等：已起则直接复核）。 */
    fun provision(context: Context, kind: RemoteAccessManager.Kind) {
        if (isBusy(kind)) return
        val app = context.applicationContext
        val flow = stateOf(kind)
        val job = scope.launch {
            try {
                runHostServer(app, kind, flow, start = true)
            } finally {
                jobs.remove(kind)
            }
        }
        jobs[kind] = job
    }

    /** 停止内置服务。 */
    fun stop(context: Context, kind: RemoteAccessManager.Kind) {
        if (isBusy(kind)) return
        val app = context.applicationContext
        val flow = stateOf(kind)
        val job = scope.launch {
            try {
                runHostServer(app, kind, flow, start = false)
            } finally {
                jobs.remove(kind)
            }
        }
        jobs[kind] = job
    }

    private suspend fun runHostServer(
        context: Context,
        kind: RemoteAccessManager.Kind,
        flow: MutableStateFlow<RemoteServiceState>,
        start: Boolean,
    ) {
        flow.value = RemoteServiceState.Working(
            if (start) "正在启动内置 ${kind.title} 服务（host 进程，环境无关）…"
            else "正在停止 ${kind.title} 服务…",
            System.currentTimeMillis(),
            emptyList(),
        )
        try {
            val ok = withContext(Dispatchers.IO) {
                if (start) {
                    when (kind) {
                        RemoteAccessManager.Kind.SSH -> BuiltinSshServer.start(context)
                        RemoteAccessManager.Kind.VNC -> HostVncServer.start(context)
                    }
                } else {
                    when (kind) {
                        RemoteAccessManager.Kind.SSH -> {
                            BuiltinSshServer.stop()
                            true
                        }
                        RemoteAccessManager.Kind.VNC -> {
                            HostVncServer.stop()
                            true
                        }
                    }
                }
            }
            if (!ok) {
                flow.value = RemoteServiceState.Failed(
                    if (start) "${kind.title} 服务启动失败：宿主运行环境未就绪（native 库缺失）"
                    else "${kind.title} 服务停止失败",
                    emptyList(),
                )
                return
            }
            // 刷新后端标签（Ubuntu proot / Host busybox）
            _backend.value = when {
                BuiltinSshServer.isRunning() -> BuiltinSshServer.backendLabel
                HostVncServer.isRunning() -> HostVncServer.backend()
                else -> "—"
            }
            // 复核端口真实状态：调用成功不够，必须真能连上
            delay(400)
            val banner = withContext(Dispatchers.IO) { RemoteAccessManager.probe(kind) }
            flow.value = when {
                start && banner != null -> RemoteServiceState.Running(banner)
                start -> RemoteServiceState.Failed(
                    "${kind.title} 服务已启动，但端口未响应（可能端口被占用）", emptyList(),
                )
                banner == null -> RemoteServiceState.Stopped
                else -> RemoteServiceState.Failed(
                    "${kind.title} 服务已停止，但端口仍在监听", emptyList(),
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "runHostServer 异常 kind=$kind", e)
            flow.value = RemoteServiceState.Failed(
                "执行异常：${e.message ?: e.javaClass.simpleName}", emptyList(),
            )
        }
    }
}
