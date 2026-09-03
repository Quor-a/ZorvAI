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

        /**
         * 计算字节数组尾部「不完整 UTF-8 序列」的长度（0..3）。
         * 多字节 UTF-8 的前导字节声明了序列总长（110→2 / 1110→3 / 11110→4），
         * 若尾部剩余字节不足该长度，则这部分需要留给下一个 read 块拼接。
         */
        /**
         * 检测字符串末尾是否存在被 read 边界截断的不完整 ANSI 转义序列。
         * 返回应留给下一块的字符长度；0 表示末尾是完整序列或普通文本。
         */
        private fun incompleteAnsiTailLength(text: String): Int {
            val esc = text.lastIndexOf('\u001B')
            if (esc < 0) return 0
            if (esc == text.length - 1) return 1 // ESC 单独在末尾
            val after = text[esc + 1]
            if (after == '[') {
                // CSI：完整需要命令字符 0x40-0x7E；参数/中间字符在 0x30-0x3F 与 0x20-0x2F
                for (i in esc + 2 until text.length) {
                    val c = text[i].code
                    if (c in 0x40..0x7E) return 0 // 完整
                    if (c in 0x30..0x3F || c in 0x20..0x2F) continue
                    // 遇到非法字符：当成已结束（让 scanner 去处理错误）
                    return 0
                }
                return text.length - esc
            }
            if (after == ']') {
                // OSC：以 BEL 或 ESC \ 结束
                for (i in esc + 2 until text.length) {
                    if (text[i] == '\u0007') return 0
                    if (text[i] == '\u001B' && i + 1 < text.length && text[i + 1] == '\\') return 0
                }
                return text.length - esc
            }
            if (after == 'P') {
                // DCS：以 ESC \ 结束
                for (i in esc + 2 until text.length) {
                    if (text[i] == '\u001B' && i + 1 < text.length && text[i + 1] == '\\') return 0
                }
                return text.length - esc
            }
            // ESC 7/8/c/D/E/H/M/Z 等单字符序列已完整；其余不处理
            return 0
        }

        private fun incompleteUtf8TailLength(bytes: ByteArray): Int {
            val n = bytes.size
            // 最多回看 4 字节（UTF-8 序列最长 4 字节）
            for (lookBack in 1..minOf(4, n)) {
                val idx = n - lookBack
                val b = bytes[idx].toInt() and 0xFF
                when {
                    // 前导字节：序列总长
                    (b and 0xF8) == 0xF0 -> {
                        val have = n - idx
                        return if (have < 4) lookBack else 0
                    }
                    (b and 0xF0) == 0xE0 -> {
                        val have = n - idx
                        return if (have < 3) lookBack else 0
                    }
                    (b and 0xE0) == 0xC0 -> {
                        val have = n - idx
                        return if (have < 2) lookBack else 0
                    }
                    // 续字节（10xxxxxx）：继续回看找前导
                    (b and 0xC0) == 0x80 -> { /* 继续往前找 */ }
                    // ASCII：尾部无残缺
                    else -> return 0
                }
            }
            // 回看 4 字节全是续字节（异常流），不保留，按替换字符解码
            return 0
        }
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
                            // UTF-8 安全分块：多字节序列跨 read 边界时先留尾部不完整字节，
                            // 与下一块拼接后再解码，避免中文/emoji 在 4096 字节边界被截成 U+FFFD 乱码。
                            var pending: ByteArray = ByteArray(0)
                            // ANSI 转义序列跨 read 边界时同样留尾（如 ESC[32 与下一块的 m 分属两次 read）
                            var pendingAnsi: String = ""
                            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                                val all = if (pending.isEmpty()) buffer.copyOf(bytesRead)
                                else pending + buffer.copyOf(bytesRead)
                                val keep = incompleteUtf8TailLength(all)
                                val decodeLen = all.size - keep
                                if (decodeLen > 0) {
                                    var chunk = String(all, 0, decodeLen, Charsets.UTF_8)
                                    // 拼接上一块遗留的 ANSI 尾部
                                    if (pendingAnsi.isNotEmpty()) {
                                        chunk = pendingAnsi + chunk
                                        pendingAnsi = ""
                                    }
                                    // 当前块末尾若存在不完整的 ANSI 序列，把尾部留到下一次
                                    val ansiKeep = incompleteAnsiTailLength(chunk)
                                    if (ansiKeep > 0) {
                                        if (ansiKeep < chunk.length) {
                                            pendingAnsi = chunk.substring(chunk.length - ansiKeep)
                                            chunk = chunk.substring(0, chunk.length - ansiKeep)
                                        } else {
                                            pendingAnsi = chunk
                                            chunk = ""
                                        }
                                    }
                                    if (chunk.isNotEmpty()) {
                                        Log.d(TAG, "Read chunk: '$chunk'")
                                        outputProcessor.processOutput(sessionId, chunk, sessionManager)
                                    }
                                }
                                pending = if (keep > 0) all.copyOfRange(decodeLen, all.size) else ByteArray(0)
                            }
                            // 流结束时仍残留的不完整字节按替换字符解码，并把遗留的 ANSI 尾部一并喂出去
                            if (pending.isNotEmpty() || pendingAnsi.isNotEmpty()) {
                                var chunk = String(pending, Charsets.UTF_8)
                                if (pendingAnsi.isNotEmpty()) {
                                    chunk = pendingAnsi + chunk
                                }
                                if (chunk.isNotEmpty()) {
                                    outputProcessor.processOutput(sessionId, chunk, sessionManager)
                                }
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
