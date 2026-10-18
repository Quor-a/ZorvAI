package com.ai.assistance.quro.terminal

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.ai.assistance.quro.terminal.data.TerminalSessionData
import com.ai.assistance.quro.terminal.runtime.Distro
import com.ai.assistance.quro.terminal.runtime.GuestEnvStatus
import kotlinx.coroutines.launch
import android.util.Log

/** LANXIU rootfs 下载状态（按需下载）。 */
enum class LanxiuDownloadStatus { Idle, Downloading, Success, Failed }

@Stable
class TerminalEnv(
    sessionsState: State<List<TerminalSessionData>>,
    currentSessionIdState: State<String?>,
    currentDirectoryState: State<String>,
    isFullscreenState: State<Boolean>,
    terminalEmulatorState: State<com.ai.assistance.quro.terminal.domain.ansi.AnsiTerminalEmulator>,
    private val terminalManager: TerminalManager,
    val forceShowSetup: Boolean = false
) {
    val sessions by sessionsState
    val currentSessionId by currentSessionIdState
    val currentDirectory by currentDirectoryState
    val isFullscreen by isFullscreenState
    val terminalEmulator by terminalEmulatorState

    var command by mutableStateOf("")

    /** 当前选中的发行版（双系统切换），初始值读自持久化偏好。 */
    var selectedDistro by mutableStateOf(terminalManager.getDistro())

    /** LANXIU rootfs 按需下载状态与进度（0f..1f）。 */
    var lanxiuDownloadStatus by mutableStateOf(LanxiuDownloadStatus.Idle)
    var lanxiuDownloadProgress by mutableStateOf(0f)

    fun onCommandChange(newCommand: String) {
        command = newCommand
    }

    /** 切换发行版：更新本地状态 + 持久化（不杀已有会话，影响下次启动/后端）。 */
    fun onSelectDistro(distro: Distro) {
        if (distro == selectedDistro) return
        selectedDistro = distro
        terminalManager.coroutineScope.launch {
            terminalManager.setDistro(distro)
        }
    }

    /** 首次使用按需下载 LANXIU rootfs（约 88MB）。 */
    fun onDownloadLanxiu() {
        if (lanxiuDownloadStatus == LanxiuDownloadStatus.Downloading) return
        lanxiuDownloadStatus = LanxiuDownloadStatus.Downloading
        lanxiuDownloadProgress = 0f
        terminalManager.coroutineScope.launch {
            val ok = terminalManager.downloadLanxiuRootfs { _, _, frac ->
                if (frac >= 0f) lanxiuDownloadProgress = frac
            }
            lanxiuDownloadStatus = if (ok) LanxiuDownloadStatus.Success else LanxiuDownloadStatus.Failed
            // 下载完成（内置 asset 或网络）即触发老栈可见终端重建，使其切到 LANXIU rootfs。
            if (ok) terminalManager.rebuildInteractiveTerminal()
        }
    }

    /** LANXIU rootfs 当前可用性（用于 UI 显示下载 / 初始化 / 就绪）。 */
    fun lanxiuEnvStatus(): GuestEnvStatus = terminalManager.guestEnvStatus(Distro.LANXIU)

    fun onSendInput(inputText: String, isCommand: Boolean) {
        // 允许空输入（用于交互式场景发送回车）
        if (isCommand) {
            // 命令模式：也允许空命令（用于 SSH 等交互场景）
            terminalManager.coroutineScope.launch {
                terminalManager.sendCommand(inputText)
            }
            if (inputText == command) {
                command = ""
            }
        } else {
            // 输入模式：允许空输入（例如 ssh-keygen 直接回车使用默认路径）
            terminalManager.sendInput(inputText)
        }
    }

    fun onSetup(commands: List<String>) {
        val fullCommand = commands.joinToString(separator = " && ")
        terminalManager.coroutineScope.launch {
            terminalManager.sendCommand(fullCommand)
        }
    }

    fun onInterrupt() = terminalManager.sendInterruptSignal()
    fun onNewSession() {
        // 在terminalManager的协程作用域中异步创建会话
        terminalManager.coroutineScope.launch {
            try {
                terminalManager.createNewSession()
                Log.d("TerminalEnv", "New session created successfully")
            } catch (e: Exception) {
                Log.e("TerminalEnv", "Failed to create new session", e)
            }
        }
    }
    fun onSwitchSession(sessionId: String) = terminalManager.switchToSession(sessionId)
    fun onCloseSession(sessionId: String) = terminalManager.closeSession(sessionId)
}

@Composable
fun rememberTerminalEnv(terminalManager: TerminalManager, forceShowSetup: Boolean = false): TerminalEnv {
    val sessionsState = terminalManager.sessions.collectAsState(initial = emptyList())
    val currentSessionIdState = terminalManager.currentSessionId.collectAsState(initial = null)
    val currentDirectoryState = terminalManager.currentDirectory.collectAsState(initial = "$ ")
    val isFullscreenState = terminalManager.isFullscreen.collectAsState(initial = false)
    val terminalEmulatorState = terminalManager.terminalEmulator.collectAsState(initial = com.ai.assistance.quro.terminal.domain.ansi.AnsiTerminalEmulator())

    return remember(terminalManager, forceShowSetup) {
        TerminalEnv(
            sessionsState = sessionsState,
            currentSessionIdState = currentSessionIdState,
            currentDirectoryState = currentDirectoryState,
            isFullscreenState = isFullscreenState,
            terminalEmulatorState = terminalEmulatorState,
            terminalManager = terminalManager,
            forceShowSetup = forceShowSetup
        )
    }
} 