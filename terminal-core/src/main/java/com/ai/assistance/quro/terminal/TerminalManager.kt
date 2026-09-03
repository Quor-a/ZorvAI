package com.ai.assistance.quro.terminal

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import com.ai.assistance.quro.terminal.command.CommandDispatcher
import com.ai.assistance.quro.terminal.data.SessionInitState
import com.ai.assistance.quro.terminal.data.TerminalState
import com.ai.assistance.quro.terminal.domain.OutputProcessor
import com.ai.assistance.quro.terminal.domain.SessionManager
import com.ai.assistance.quro.terminal.domain.ansi.AnsiTerminalEmulator
import com.ai.assistance.quro.terminal.runtime.TerminalEnvironment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * 终端门面。
 *
 * 作为终端能力的统一入口，将环境引导（[TerminalEnvironment]）、命令派发
 * （[CommandDispatcher]）、会话状态（[SessionManager]）与输出解析
 * （[OutputProcessor]）编排在一起，并对外暴露响应式状态流。
 */
@RequiresApi(Build.VERSION_CODES.O)
class TerminalManager private constructor(
    private val context: Context
) {
    internal val coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // 事件流（先声明，供各组件回调使用）
    private val _commandExecutionEvents = MutableSharedFlow<CommandExecutionEvent>()
    val commandExecutionEvents: SharedFlow<CommandExecutionEvent> = _commandExecutionEvents.asSharedFlow()

    private val _directoryChangeEvents = MutableSharedFlow<SessionDirectoryEvent>()
    val directoryChangeEvents: SharedFlow<SessionDirectoryEvent> = _directoryChangeEvents.asSharedFlow()

    // 核心组件
    private val environment = TerminalEnvironment(context)
    private val sessionManager = SessionManager(this)
    private val commandDispatcher = CommandDispatcher(
        sessionManager = sessionManager,
        scope = coroutineScope,
        emitCommandEvent = { event ->
            coroutineScope.launch { _commandExecutionEvents.emit(event) }
        }
    )
    private val outputProcessor = OutputProcessor(
        onCommandExecutionEvent = { event ->
            coroutineScope.launch { _commandExecutionEvents.emit(event) }
        },
        onDirectoryChangeEvent = { event ->
            coroutineScope.launch { _directoryChangeEvents.emit(event) }
        },
        onCommandCompleted = { sessionId ->
            coroutineScope.launch { commandDispatcher.processNextQueuedCommand(sessionId) }
        }
    )

    // 状态流
    val terminalState: StateFlow<TerminalState> = sessionManager.state
    val sessions = terminalState.map { it.sessions }
    val currentSessionId = terminalState.map { it.currentSessionId }
    val currentDirectory = terminalState.map { it.currentSession?.currentDirectory ?: "$ " }
    val isInteractiveMode = terminalState.map { it.currentSession?.isInteractiveMode ?: false }
    val interactivePrompt = terminalState.map { it.currentSession?.interactivePrompt ?: "" }
    val isFullscreen = terminalState.map { it.currentSession?.isFullscreen ?: false }
    val terminalEmulator = terminalState.map { it.currentSession?.ansiParser ?: AnsiTerminalEmulator() }

    companion object {
        @Volatile
        private var INSTANCE: TerminalManager? = null

        fun getInstance(context: Context): TerminalManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: TerminalManager(context.applicationContext).also { INSTANCE = it }
            }
        }

        private const val TAG = "TerminalManager"
    }

    init {
        coroutineScope.launch {
            try {
                Log.d(TAG, "Creating default session...")
                createNewSession("default")
                Log.d(TAG, "Default session created successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create default session", e)
            }
        }
    }

    /**
     * 创建新会话 - 同步等待初始化完成。
     */
    suspend fun createNewSession(title: String? = null): com.ai.assistance.quro.terminal.data.TerminalSessionData {
        val newSession = sessionManager.createNewSession(title)

        coroutineScope.launch {
            initializeSession(newSession.id)
        }

        val success = withTimeoutOrNull(30000) {
            terminalState.first { state ->
                val session = state.sessions.find { it.id == newSession.id }
                session?.initState == SessionInitState.READY
            }
        }

        if (success == null) {
            Log.e(TAG, "Session initialization timeout for session: ${newSession.id}")
            sessionManager.closeSession(newSession.id)
            throw Exception("Session initialization timeout")
        }

        Log.d(TAG, "Session ${newSession.id} initialized successfully")
        return sessionManager.getSession(newSession.id) ?: newSession
    }

    fun switchToSession(sessionId: String) {
        sessionManager.switchToSession(sessionId)
    }

    fun closeSession(sessionId: String) {
        sessionManager.closeSession(sessionId)
    }

    suspend fun sendCommand(command: String, commandId: String? = null): String {
        return commandDispatcher.sendCommand(command, commandId)
    }

    suspend fun sendCommandToSession(sessionId: String, command: String, commandId: String? = null): String {
        return commandDispatcher.sendCommandToSession(sessionId, command, commandId)
    }

    fun sendInput(input: String) {
        commandDispatcher.sendInput(input)
    }

    fun sendInterruptSignal() {
        commandDispatcher.sendInterruptSignal()
    }

    suspend fun initializeEnvironment(): Boolean {
        return environment.initializeEnvironment()
    }

    fun startTerminalSession(sessionId: String): Pair<TerminalSession, Pty> {
        return environment.startTerminalSession(sessionId)
    }

    fun closeTerminalSession(sessionId: String) {
        environment.closeTerminalSession(sessionId)
    }

    private fun initializeSession(sessionId: String) {
        coroutineScope.launch {
            val success = environment.initializeEnvironment()
            if (success) {
                startSession(sessionId)
            }
        }
    }

    private fun startSession(sessionId: String) {
        coroutineScope.launch(Dispatchers.IO) {
            try {
                val (terminalSession, pty) = environment.startTerminalSession(sessionId)
                val sessionWriter = terminalSession.stdin.writer()

                sessionWriter.write("echo 'TERMINAL_READY'\n")
                sessionWriter.flush()

                val readJob = launch {
                    try {
                        terminalSession.stdout.use { inputStream ->
                            val buffer = ByteArray(4096)
                            var bytesRead: Int
                            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                                val chunk = String(buffer, 0, bytesRead)
                                Log.d(TAG, "Read chunk: '$chunk'")
                                outputProcessor.processOutput(sessionId, chunk, sessionManager)
                            }
                        }
                    } catch (e: java.io.InterruptedIOException) {
                        Log.i(TAG, "Read job interrupted for session $sessionId.")
                    } catch (e: Exception) {
                        Log.e(TAG, "Error in read job for session $sessionId", e)
                    }
                }

                sessionManager.updateSession(sessionId) { session ->
                    session.copy(
                        terminalSession = terminalSession,
                        pty = pty,
                        sessionWriter = sessionWriter,
                        readJob = readJob
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error starting session", e)
            }
        }
    }

    fun cleanup() {
        environment.closeAllSessions()
        sessionManager.cleanup()
        coroutineScope.cancel()
        Log.d(TAG, "All active sessions cleaned up.")
    }
}
