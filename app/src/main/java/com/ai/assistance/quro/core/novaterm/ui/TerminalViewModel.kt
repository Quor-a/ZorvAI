package com.ai.assistance.quro.core.novaterm.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ai.assistance.quro.core.novaterm.command.*
import com.ai.assistance.quro.core.novaterm.core.*
import com.ai.assistance.quro.core.novaterm.executor.RootExecutor
import com.ai.assistance.quro.core.novaterm.executor.SandboxExecutor
import com.ai.assistance.quro.core.novaterm.executor.ProcessWatcher
import kotlinx.coroutines.Dispatchers
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

    // ===== ROOT 后端能力（透明化）=====
    // null = 探测中；true = 真实特权后端可用（Shizuku/su）；false = 无真实 ROOT
    private val _rootBackendAvailable = MutableStateFlow<Boolean?>(null)
    val rootBackendAvailable: StateFlow<Boolean?> = _rootBackendAvailable.asStateFlow()

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

        // 探测真实 ROOT 后端（Shizuku/su），结果驱动终端横幅与 su/root 行为
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { _rootBackendAvailable.value = RootExecutor.probeRealBackend() }
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

        // 透明化：su / root 前缀命令单独路由（真实提权 or 明确标注的模拟）
        val (isPrivileged, privilegedRest) = parsePrivilegedCommand(input)
        if (isPrivileged) {
            handlePrivilegedCommand(input, privilegedRest)
            return
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
            appendResult(result)
            printPrompt()
        })
    }

    /**
     * 解析 su / root 前缀命令。
     * @return Pair(是否为特权命令, 前缀之后的原始命令文本)
     *
     * 仅当第一个空白分隔词严格等于 "su" 或 "root" 时判定为特权命令，
     * 避免误伤如 `run root.nv`、`issue` 等普通命令。
     */
    private fun parsePrivilegedCommand(input: String): Pair<Boolean, String> {
        val trimmed = input.trim()
        val firstSpace = trimmed.indexOf(' ')
        val cmd = if (firstSpace < 0) trimmed else trimmed.substring(0, firstSpace)
        return if (cmd == "su" || cmd == "root") {
            val rest = if (firstSpace < 0) "" else trimmed.substring(firstSpace + 1).trim()
            true to rest
        } else {
            false to ""
        }
    }

    /**
     * su/root 后跟的是沙箱等级关键字（root/dev/user/guest）而非要执行的命令。
     * 这些仅切换终端内部沙箱权限等级（演示概念），不构成真实提权。
     */
    private fun isSandboxLevelKeyword(s: String): Boolean {
        val w = s.trim().lowercase()
        return w in setOf("su", "root", "dev", "developer", "user", "guest")
    }

    /**
     * 处理 su / root 前缀命令（透明化核心）。
     *
     * - 真实后端可用：把后续命令经 [RootExecutor] 以真实 root 执行；
     * - 无真实后端：明确标注为沙箱模拟（[模拟] 前缀 + 警示样式 + 提示行），绝不谎称已提权。
     */
    private fun handlePrivilegedCommand(rawInput: String, rest: String) {
        addEntry(EntryType.INPUT, rawInput)
        history.add(rawInput)
        historyIndex = history.size

        _isExecuting.value = true
        _currentInput.value = ""

        viewModelScope.launch(Dispatchers.IO) {
            // 若尚未探测完成，则在此阻塞实测一次并回写（避免首条命令被谎报）
            val real = _rootBackendAvailable.value
                ?: runCatching { RootExecutor.probeRealBackend() }
                    .getOrDefault(false)
                    .also { _rootBackendAvailable.value = it }
            val simulated = !real

            val result: CommandResult = runCatching {
                if (real) executePrivilegedReal(rest) else executePrivilegedSimulated(rest)
            }.getOrElse { e -> CommandResult.err("privileged exec error: ${e.message}") }

            _isExecuting.value = false
            if (simulated) {
                addEntry(
                    EntryType.WARNING,
                    "⚠ [模拟] 本环境无真实 ROOT 权限，以下为沙箱模拟输出（演示用途，非真实提权）",
                )
            }
            appendResult(result, simulated = simulated)
            printPrompt()
        }
    }

    /** 真实后端：rest 为空或仅为等级关键字 → 提示；否则以 root 执行 rest。 */
    private fun executePrivilegedReal(rest: String): CommandResult {
        if (rest.isBlank() || isSandboxLevelKeyword(rest)) {
            val lvl = rest.ifBlank { "root" }
            return CommandResult.ok(
                "已连接真实 ROOT 后端（Shizuku / su），沙箱权限等级切换为 $lvl（不影响真实提权）；" +
                    "可直接以 root 执行命令，例如：su ls -la /data",
            )
        }
        // 经既有、已审计的特权通道真实执行（Shizuku → su 降级）
        return RootExecutor.execute(rest)
    }

    /** 无真实后端：rest 为空或仅为等级关键字 → 模拟提示；否则普通沙箱执行并标注 [模拟]。 */
    private suspend fun executePrivilegedSimulated(rest: String): CommandResult {
        if (rest.isBlank() || isSandboxLevelKeyword(rest)) {
            val lvl = rest.ifBlank { "root" }
            return CommandResult.ok("[模拟] 沙箱权限等级切换为 $lvl（演示用途，无真实 ROOT）")
        }
        // 在普通（非提权）沙箱中执行该命令；输出将由 appendResult 标注 [模拟]
        return executor.executeBlocking(rest)
    }

    /**
     * 把命令结果写入终端历史。
     * @param simulated 为 true 时，每条输出行加 [模拟] 前缀并以警示样式着色，
     *                  明确告知用户该输出并非真实 root 执行。
     */
    private fun appendResult(result: CommandResult, simulated: Boolean = false) {
        when (result) {
            is CommandResult.Text -> {
                if (result.output.isNotEmpty()) {
                    val isErr = result.isError
                    result.output.lines().forEach { line ->
                        val text = if (simulated) "[模拟] $line" else line
                        val type = if (isErr) EntryType.ERROR else EntryType.OUTPUT
                        val style = if (simulated) {
                            OutputStyle.WARNING
                        } else if (isErr) {
                            OutputStyle.ERROR
                        } else {
                            OutputStyle.NORMAL
                        }
                        addEntry(type, text, style)
                    }
                }
            }
            is CommandResult.RichText -> {
                result.lines.forEach { line ->
                    val text = if (simulated) "[模拟] ${line.text}" else line.text
                    val style = if (simulated) OutputStyle.WARNING else line.style
                    addEntry(EntryType.OUTPUT, text, style)
                }
            }
            else -> {}
        }
    }

    /** 工具栏「清屏」按钮：清空全部终端输出并重新打印提示符。 */
    fun clearAll() {
        _entries.value = emptyList()
        printPrompt()
        _currentInput.value = ""
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
