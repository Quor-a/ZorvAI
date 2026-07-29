package com.ai.assistance.quro.core.novaterm.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ai.assistance.quro.core.novaterm.command.*
import com.ai.assistance.quro.core.novaterm.core.*
import com.ai.assistance.quro.core.novaterm.executor.SandboxExecutor
import com.ai.assistance.quro.core.novaterm.executor.ProcessWatcher
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/**
 * 终端 ViewModel
 * 管理终端 UI 状态和交互逻辑
 */
class TerminalViewModel(app: Application) : AndroidViewModel(app) {

    // ===== 会话管理 =====
    private val sessionId: String = SessionManager.createSession("main")

    // ===== 主题 =====
    private val _theme = MutableStateFlow(Themes.Matrix)
    val theme: StateFlow<TerminalTheme> = _theme.asStateFlow()

    // ===== 终端行（输出历史） =====
    data class TerminalEntry(
        val id: Long,
        val type: EntryType,
        val text: String,
        val style: OutputStyle = OutputStyle.NORMAL,
        val timestamp: Long = System.currentTimeMillis()
    )

    enum class EntryType { PROMPT, INPUT, OUTPUT, ERROR, SYSTEM, SUCCESS, WARNING, INFO }

    private val _entries = MutableStateFlow<List<TerminalEntry>>(emptyList())
    val entries: StateFlow<List<TerminalEntry>> = _entries.asStateFlow()

    // ===== 输入状态 =====
    private val _currentInput = MutableStateFlow("")
    val currentInput: StateFlow<String> = _currentInput.asStateFlow()

    // ===== 命令历史（用于上下键导航） =====
    private val history = mutableListOf<String>()
    private var historyIndex = -1
    private var inputBeforeHistory = ""

    // ===== 执行器 =====
    private val executor = SandboxExecutor(sessionId)
    private val processWatcher = ProcessWatcher()

    // ===== 系统指标 =====
    val metrics: StateFlow<ProcessWatcher.SystemMetrics> = processWatcher.metrics

    // ===== 终端状态 =====
    private val _isExecuting = MutableStateFlow(false)
    val isExecuting: StateFlow<Boolean> = _isExecuting.asStateFlow()

    private val _cursorBlink = MutableStateFlow(true)
    val cursorBlink: StateFlow<Boolean> = _cursorBlink.asStateFlow()

    // ===== 启动横幅 =====
    private var entryId = 0L
    private fun nextId(): Long = ++entryId

    init {
        printBanner()
        printPrompt()
        processWatcher.start(2000)

        // 光标闪烁
        viewModelScope.launch {
            while (true) {
                kotlinx.coroutines.delay(530)
                _cursorBlink.value = !_cursorBlink.value
            }
        }
    }

    private fun printBanner() {
        val banner = listOf(
            "",
            "   ███╗   ██╗ ██████╗ ██╗   ██╗ █████╗ ████████╗███████╗██████╗ ███╗   ███╗",
            "   ████╗  ██║██╔═══██╗██║   ██║██╔══██╗╚══██╔══╝██╔════╝██╔══██╗████╗ ████║",
            "   ██╔██╗ ██║██║   ██║██║   ██║███████║   ██║   █████╗  ██████╔╝██╔████╔██║",
            "   ██║╚██╗██║██║   ██║╚██╗ ██╔╝██╔══██║   ██║   ██╔══╝  ██╔══██╗██║╚██╔╝██║",
            "   ██║ ╚████║╚██████╔╝ ╚████╔╝ ██║  ██║   ██║   ███████╗██║  ██║██║ ╚═╝ ██║",
            "   ╚═╝  ╚═══╝ ╚═════╝   ╚═══╝  ╚═╝  ╚═╝   ╚═╝   ╚══════╝╚═╝  ╚═╝╚═╝     ╚═╝",
            "",
            "   ┌──────────────────────────────────────────────────────────────┐",
            "   │  QuroTerm v1.0  │  Self-Contained Terminal System          │",
            "   │  Type 'help' for commands  │  Type 'theme' for themes      │",
            "   └──────────────────────────────────────────────────────────────┘",
            ""
        )
        banner.forEach { line ->
            addEntry(EntryType.SYSTEM, line, OutputStyle.INFO)
        }
    }

    private fun printPrompt() {
        val cwd = FileSystem.getCwd(sessionId).takeLast(30)
        val user = SessionManager.getEnv(sessionId, "USER") ?: "user"
        val perm = PermissionController.getLevel(sessionId).name.lowercase()
        val prompt = "$user@novaterm:$cwd($perm)$ "
        addEntry(EntryType.PROMPT, prompt, OutputStyle.NORMAL)
    }

    private fun addEntry(type: EntryType, text: String, style: OutputStyle = OutputStyle.NORMAL) {
        val entry = TerminalEntry(nextId(), type, text, style)
        _entries.value = _entries.value + entry
    }

    // ===== 公开 API =====

    fun onInputChange(text: String) {
        _currentInput.value = text
    }

    fun onSubmit() {
        val input = _currentInput.value.trim()
        if (input.isEmpty()) {
            addEntry(EntryType.INPUT, "")
            printPrompt()
            return
        }

        // 特殊 UI 命令处理
        when (input) {
            "clear" -> {
                _entries.value = emptyList()
                printPrompt()
                _currentInput.value = ""
                return
            }
        }

        // 显示输入的命令
        addEntry(EntryType.INPUT, input)

        // 加入历史
        history.add(input)
        historyIndex = history.size

        // 执行
        _isExecuting.value = true
        _currentInput.value = ""

        executor.execute(input, onResult = { result ->
            _isExecuting.value = false
            when (result) {
                is CommandResult.Text -> {
                    if (result.output.isNotEmpty()) {
                        val type = if (result.isError) EntryType.ERROR else EntryType.OUTPUT
                        val style = if (result.isError) OutputStyle.ERROR else OutputStyle.NORMAL
                        // 多行输出逐行添加
                        result.output.lines().forEach { line ->
                            addEntry(type, line, style)
                        }
                    }
                }
                is CommandResult.RichText -> {
                    result.lines.forEach { line ->
                        addEntry(EntryType.OUTPUT, line.text, line.style)
                    }
                }
                else -> {}
            }
            printPrompt()
        })
    }

    fun onHistoryUp() {
        if (history.isEmpty()) return
        if (historyIndex == history.size) {
            inputBeforeHistory = _currentInput.value
        }
        historyIndex = (historyIndex - 1).coerceAtLeast(0)
        _currentInput.value = history[historyIndex]
    }

    fun onHistoryDown() {
        if (history.isEmpty()) return
        historyIndex = (historyIndex + 1).coerceAtMost(history.size)
        _currentInput.value = if (historyIndex == history.size) inputBeforeHistory else history[historyIndex]
    }

    fun setTheme(name: String) {
        _theme.value = Themes.getByName(name)
        SessionManager.setEnv(sessionId, "THEME", name)
    }

    fun cycleTheme() {
        val current = _theme.value
        val idx = Themes.allThemes.indexOfFirst { it.name == current.name }
        val next = Themes.allThemes[(idx + 1) % Themes.allThemes.size]
        setTheme(next.name)
    }

    fun getCwd(): String = FileSystem.getCwd(sessionId)
    fun getPermissionLevel(): String = PermissionController.getLevel(sessionId).name

    override fun onCleared() {
        super.onCleared()
        executor.shutdown()
        processWatcher.shutdown()
        SessionManager.destroySession(sessionId)
    }
}
