package com.ai.assistance.quro.terminal.utils

import android.content.Context
import android.util.Log
import com.ai.assistance.quro.terminal.TerminalManager
import com.ai.assistance.quro.terminal.runtime.GuestEnvStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
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
 * [Running]；脚本报告成功但端口没起来，同样判 [Failed]。
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
 * ## 为什么要有这一层
 * 1. **状态与界面解耦**：apt 装一次可能几分钟，用户一定会把弹窗关掉。任务跑在
 *    模块级协程里，状态放在 [MutableStateFlow]；弹窗重开就能看到进度，不会丢。
 * 2. **单一执行通道**：所有动作都走 [TerminalManager.runInGuest]（proot 一次性执行 +
 *    文件回执），彻底绕开终端会话那条「靠提示符正则推进」的命令队列 —— 旧实现
 *    正是因为队列不推进，界面永远停在「安装中」。
 * 3. **完成以探针为准**：脚本退出码只是第一道闸门，最终一定会再做一次 TCP 探针，
 *    避免「脚本说成功、实际没监听」的假成功。
 */
object RemoteAccessCenter {

    private const val TAG = "RemoteAccessCenter"

    /** 界面日志区最多保留的行数。 */
    private const val LOG_TAIL = 80

    /** 内部缓冲上限，防止超长输出吃内存。 */
    private const val LOG_BUFFER = 400

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _envStatus = MutableStateFlow<GuestEnvStatus?>(null)
    val envStatus: StateFlow<GuestEnvStatus?> = _envStatus.asStateFlow()

    private val _addresses = MutableStateFlow<List<RemoteAccessManager.NetAddr>>(emptyList())
    val addresses: StateFlow<List<RemoteAccessManager.NetAddr>> = _addresses.asStateFlow()

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
            _addresses.value = withContext(Dispatchers.IO) { RemoteAccessManager.localIpv4All() }
            _envStatus.value = withContext(Dispatchers.IO) { manager.guestEnvStatus() }
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

    /** 安装并启动（幂等：已装则直接重启）。 */
    fun provision(context: Context, kind: RemoteAccessManager.Kind) {
        if (isBusy(kind)) return
        val app = context.applicationContext
        val flow = stateOf(kind)
        val job = scope.launch {
            try {
                runJob(
                    context = app,
                    kind = kind,
                    flow = flow,
                    script = RemoteAccessManager.provisionScript(app, kind),
                    timeoutMs = RemoteAccessManager.PROVISION_TIMEOUT_MS,
                    busyStep = { envNeedsInstall ->
                        if (envNeedsInstall) "正在初始化 Ubuntu 环境（首次较慢，请保持应用在前台）…"
                        else "正在安装并启动 ${kind.title}…"
                    },
                    expectRunning = true,
                )
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
                runJob(
                    context = app,
                    kind = kind,
                    flow = flow,
                    script = RemoteAccessManager.stopScript(app, kind),
                    timeoutMs = RemoteAccessManager.STOP_TIMEOUT_MS,
                    busyStep = { "正在停止 ${kind.title}…" },
                    expectRunning = false,
                )
            } finally {
                jobs.remove(kind)
            }
        }
        jobs[kind] = job
    }

    private suspend fun runJob(
        context: Context,
        kind: RemoteAccessManager.Kind,
        flow: MutableStateFlow<RemoteServiceState>,
        script: String,
        timeoutMs: Long,
        busyStep: (Boolean) -> String,
        expectRunning: Boolean,
    ) {
        val manager = TerminalManager.getInstance(context)
        val startedAt = System.currentTimeMillis()
        flow.value = RemoteServiceState.Working("准备执行通道…", startedAt, emptyList())

        val env = withContext(Dispatchers.IO) { manager.guestEnvStatus() }
        _envStatus.value = env
        if (env == GuestEnvStatus.ASSET_MISSING) {
            flow.value = RemoteServiceState.Failed(
                "缺少 Ubuntu 根文件系统，无法自动修复 —— 请到「环境配置」重新初始化",
                emptyList(),
            )
            return
        }
        flow.value = RemoteServiceState.Working(
            busyStep(env == GuestEnvStatus.NEEDS_INSTALL), startedAt, emptyList()
        )

        // 逐行日志：读取线程 trySend，主线程收集，避免跨线程写 Compose 状态
        val lines = Channel<String>(Channel.UNLIMITED)
        val buffer = ArrayList<String>(LOG_BUFFER)
        val collector = scope.launch {
            for (line in lines) {
                buffer.add(line)
                if (buffer.size > LOG_BUFFER) buffer.removeAt(0)
                val cur = flow.value
                if (cur is RemoteServiceState.Working) {
                    flow.value = cur.copy(log = buffer.takeLast(LOG_TAIL))
                }
            }
        }

        val result = try {
            withContext(Dispatchers.IO) {
                manager.runInGuest(script, timeoutMs) { line -> lines.trySend(line) }
            }
        } catch (e: Exception) {
            Log.e(TAG, "runJob 异常 kind=$kind", e)
            null
        } finally {
            lines.close()
        }
        collector.join()
        val tail = buffer.takeLast(LOG_TAIL)

        if (result == null) {
            flow.value = RemoteServiceState.Failed("执行通道异常中断", tail)
            return
        }
        when {
            result.timedOut -> flow.value = RemoteServiceState.Failed(
                "执行超时（${timeoutMs / 60000} 分钟）—— 网络过慢时可重试，已安装部分会复用",
                tail,
            )

            !result.completed -> flow.value = RemoteServiceState.Failed(
                result.error ?: "未拿到执行回执（proot 通道未能启动，可先到「环境配置」确认环境正常）",
                tail,
            )

            result.code != 0 -> flow.value = RemoteServiceState.Failed(
                "脚本以退出码 ${result.code} 结束（详见日志）", tail,
            )

            else -> {
                // 脚本说成功还不够，必须再验一次端口真实状态
                val banner = withContext(Dispatchers.IO) { RemoteAccessManager.probe(kind) }
                flow.value = when {
                    expectRunning && banner != null -> RemoteServiceState.Running(banner)
                    expectRunning -> RemoteServiceState.Failed(
                        "脚本报告成功，但 ${kind.title} 端口未在监听（详见日志）", tail,
                    )
                    banner == null -> RemoteServiceState.Stopped
                    else -> RemoteServiceState.Failed(
                        "脚本报告已停止，但 ${kind.title} 端口仍在监听（详见日志）", tail,
                    )
                }
            }
        }
    }
}
