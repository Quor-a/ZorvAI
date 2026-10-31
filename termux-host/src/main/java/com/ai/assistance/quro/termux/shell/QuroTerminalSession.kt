package com.ai.assistance.quro.termux.shell

import android.util.Log
import com.ai.assistance.quro.terminal.runtime.TerminalEnvironment
import com.ai.assistance.quro.vt.TerminalSession
import com.ai.assistance.quro.vt.TerminalSessionClient

/**
 * 增强终端会话包装（移植自 Termux `TermuxSession`，去品牌化简化版）。
 *
 * 官方 `TermuxSession` 持有 `ExecutionCommand` + `com.termux.terminal.TerminalSession`。
 * 本类直接包装 vtlib 的 [TerminalSession]，并复用 [TerminalEnvironment.buildProotLaunch]
 * 生成的 proot 启动参数（shellPath/args/env），无需 bootstrap 下载。
 *
 * @param sessionId 会话唯一 ID（宿主服务分配）
 * @param sessionName 初始会话名（通常是 hostname，如 ubuntu）
 * @param environment 终端环境（rootfs 准备 + proot 启动参数）
 * @param client 会话客户端（宿主服务实现，用于转发回调）
 */
class QuroTerminalSession(
    val sessionId: String,
    val sessionName: String,
    environment: TerminalEnvironment,
    client: TerminalSessionClient
) {
    companion object {
        private const val TAG = "QuroTerminalSession"
        const val DEFAULT_TRANSCRIPT_ROWS = 5000
    }

    /** vtlib 终端会话（真正执行 proot 的 PTY 会话）。 */
    val terminalSession: TerminalSession

    /** 会话是否已启动（isRunning）。 */
    val isRunning: Boolean get() = terminalSession.isRunning()

    /** 会话标题（由终端模拟器上报，如当前命令）。 */
    val title: String? get() = terminalSession.getTitle()

    /** 会话当前工作目录。 */
    val cwd: String? get() = terminalSession.getCwd()

    /** 进程 PID。 */
    val pid: Int get() = terminalSession.getPid()

    /** 会话名称（可重命名）。 */
    var name: String = sessionName

    init {
        // 1) 宿主侧确保 rootfs 解压 + usrmerge 链接就绪（幂等）
        if (!environment.prepareGuestRootfs()) {
            Log.e(TAG, "prepareGuestRootfs 失败，无法启动会话 $sessionId")
        }

        val spec = environment.buildProotLaunch()
        val session = TerminalSession(
            spec.shellPath,
            spec.hostCwd.absolutePath,
            spec.args,
            spec.env.map { "${it.key}=${it.value}" }.toTypedArray(),
            DEFAULT_TRANSCRIPT_ROWS,
            client
        )
        // 立即用默认尺寸初始化 PTY 子进程（否则进程永远不会启动）
        session.initializeEmulator(80, 24, 10, 18)
        terminalSession = session

        // 验证子进程存活：proot 参数错误 / rootfs 缺失时立即退出
        var alive = session.isRunning()
        if (alive) {
            Thread.sleep(300)
            alive = session.isRunning()
        }
        if (!alive) {
            Log.e(TAG, "proot 子进程未存活（isRunning=false），会话 $sessionId 启动失败")
        }
    }

    /** 写入数据到会话。 */
    fun write(data: ByteArray, offset: Int = 0, count: Int = data.size) {
        terminalSession.write(data, offset, count)
    }

    /** 写入码点。 */
    fun writeCodePoint(prependEscape: Boolean, codePoint: Int) {
        terminalSession.writeCodePoint(prependEscape, codePoint)
    }

    /** 强制结束会话。 */
    fun finishIfRunning() {
        terminalSession.finishIfRunning()
    }
}