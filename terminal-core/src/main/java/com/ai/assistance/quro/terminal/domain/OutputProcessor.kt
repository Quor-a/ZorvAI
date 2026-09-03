package com.ai.assistance.quro.terminal.domain

import android.util.Log
import com.ai.assistance.quro.terminal.CommandExecutionEvent
import com.ai.assistance.quro.terminal.SessionDirectoryEvent
import com.ai.assistance.quro.terminal.data.CommandHistoryItem
import com.ai.assistance.quro.terminal.data.SessionInitState
import com.ai.assistance.quro.terminal.data.TerminalSessionData
import com.ai.assistance.quro.terminal.domain.ansi.AnsiUtils
import java.util.UUID

/**
 * 终端输出的会话处理状态
 * @property justHandledCarriageReturn 如果最近处理的行分隔符是回车符（CR），则为 true
 */
private data class SessionProcessingState(
    var justHandledCarriageReturn: Boolean = false
)

/**
 * 终端输出处理器
 * 负责处理和解析终端输出，更新会话状态
 */
class OutputProcessor(
    private val onCommandExecutionEvent: (CommandExecutionEvent) -> Unit = {},
    private val onDirectoryChangeEvent: (SessionDirectoryEvent) -> Unit = {},
    private val onCommandCompleted: (String) -> Unit = {}
) {

    private val sessionStates = mutableMapOf<String, SessionProcessingState>()

    companion object {
        private const val TAG = "OutputProcessor"
        private const val MAX_LINES_PER_HISTORY_ITEM = 10
    }

    /**
     * 处理终端输出
     */
    fun processOutput(
        sessionId: String,
        chunk: String,
        sessionManager: SessionManager
    ) {
        val session = sessionManager.getSession(sessionId) ?: return
        session.rawBuffer.append(chunk)

        Log.d(TAG, "Processing chunk for session $sessionId. New buffer size: ${session.rawBuffer.length}")

        // 始终检查全屏模式切换
        if (detectFullscreenMode(sessionId, session.rawBuffer, sessionManager)) {
            // 如果检测到模式切换，缓冲区可能已被修改，及早返回以处理下一个块
            return
        }

        // 只有在 READY 状态后才更新 ANSI 解析器（用于 Canvas 渲染）
        // 避免在初始化阶段显示登录信息和 TERMINAL_READY 标记
        if (session.initState == SessionInitState.READY) {
            session.ansiParser.parse(chunk)
        }
        
        // 如果在全屏模式下，跳过行解析逻辑（全屏应用自己管理屏幕）
        if (session.isFullscreen) {
            // 不需要再次解析，ansiParser 已经更新
            return
        }

        val state = sessionStates.getOrPut(sessionId) { SessionProcessingState() }

        // 从缓冲区中提取并处理行
        while (session.rawBuffer.isNotEmpty()) {
            val bufferContent = session.rawBuffer.toString()
            val newlineIndex = bufferContent.indexOf('\n')
            val carriageReturnIndex = bufferContent.indexOf('\r')

            if (carriageReturnIndex != -1 && (newlineIndex == -1 || carriageReturnIndex < newlineIndex)) {
                // We have a carriage return.
                val line = bufferContent.substring(0, carriageReturnIndex)

                val isCRLF = carriageReturnIndex + 1 < bufferContent.length && bufferContent[carriageReturnIndex + 1] == '\n'
                val consumedLength = if (isCRLF) carriageReturnIndex + 2 else carriageReturnIndex + 1

                session.rawBuffer.delete(0, consumedLength)

                if (isCRLF) {
                    // It's a CRLF, treat as a normal line. `processLine` will handle the
                    // case where this CRLF finalizes a progress-updated line.
                    Log.d(TAG, "Processing CRLF line: '$line'")
                    processLine(sessionId, line, sessionManager)
                } else {
                    // It's just CR, treat as a progress update.
                    Log.d(TAG, "Processing CR line: '$line'")
                    handleCarriageReturn(sessionId, line, sessionManager)
                }
            } else if (newlineIndex != -1) {
                // We have a newline without a preceding carriage return.
                val line = bufferContent.substring(0, newlineIndex)
                session.rawBuffer.delete(0, newlineIndex + 1)
                Log.d(TAG, "Processing LF line: '$line'")
                processLine(sessionId, line, sessionManager)
            } else {
                // No full line-terminator found in the buffer.
                
                // 首先检查是否是进度行（优先级最高，避免被误判为提示符）
                if (AnsiUtils.isProgressLine(bufferContent)) {
                    Log.d(TAG, "Detected progress line in buffer: '$bufferContent'")
                    val cleanContent = AnsiUtils.stripAnsi(bufferContent)
                    Log.d(TAG, "Stripped progress line: '$cleanContent'")
                    handleCarriageReturn(sessionId, bufferContent, sessionManager)
                    session.rawBuffer.clear()
                    continue // Re-check buffer in case more data came in
                }
                
                // 然后检查是否是提示符
                val cleanContent = AnsiUtils.stripAnsi(bufferContent)
                
                // 检查是否是普通 shell 提示符
                val isShellPrompt = isPrompt(cleanContent)
                
                // 使用 PTY 模式检测是否在等待输入
                val isWaitingInput = isInteractivePrompt(cleanContent, sessionId, sessionManager)
                
                if (isShellPrompt || isWaitingInput) {
                    Log.d(TAG, "Processing remaining buffer as interactive/shell prompt: '$bufferContent'")
                    // Since this is not a newline-terminated line, the justHandledCarriageReturn
                    // state from a previous CR is not relevant here. We reset it to ensure
                    // the prompt is processed correctly by handleReadyState.
                    state.justHandledCarriageReturn = false
                    
                    // 如果是交互式提示符（非普通 shell 提示符），进入交互模式
                    if (isWaitingInput && !isShellPrompt) {
                        handleInteractivePrompt(sessionId, cleanContent, sessionManager)
                    } else {
                        processLine(sessionId, bufferContent, sessionManager)
                    }
                    session.rawBuffer.clear()
                }
                break // Exit loop, wait for more data.
            }
        }
    }

    private fun handleCarriageReturn(sessionId: String, line: String, sessionManager: SessionManager) {
        val cleanLine = AnsiUtils.stripAnsi(line)
        val session = sessionManager.getSession(sessionId) ?: return
        if (session.initState != SessionInitState.READY) {
            processLine(sessionId, line, sessionManager)
            return
        }
        // 只有在清理后的内容非空时才处理为进度更新
        // 空内容（如 ANSI 控制序列）不应影响下一行的处理
        if (cleanLine.isNotEmpty()) {
            updateProgressOutput(sessionId, cleanLine, sessionManager)
            sessionStates[sessionId]?.justHandledCarriageReturn = true
        }
        // 如果是空内容（纯 ANSI 控制序列），不设置 justHandledCarriageReturn
        // 这样下一行会被正常处理，而不是被当作进度更新
    }

    private fun processLine(
        sessionId: String,
        line: String,
        sessionManager: SessionManager
    ) {
        val session = sessionManager.getSession(sessionId) ?: return

        when (session.initState) {
            SessionInitState.INITIALIZING -> {
                handleInitializingState(sessionId, line, sessionManager)
            }
            SessionInitState.LOGGED_IN -> {
                handleLoggedInState(sessionId, line, sessionManager)
            }
            SessionInitState.AWAITING_FIRST_PROMPT -> {
                handleAwaitingFirstPromptState(sessionId, line, sessionManager)
            }
            SessionInitState.READY -> {
                val state = sessionStates.getOrPut(sessionId) { SessionProcessingState() }
                if (state.justHandledCarriageReturn) {
                    // A newline is received after a carriage return. This finalizes the line that was being updated.
                    state.justHandledCarriageReturn = false // Reset state immediately

                    val cleanLine = AnsiUtils.stripAnsi(line)

                    if (cleanLine.isNotEmpty()) {
                        updateProgressOutput(sessionId, cleanLine, sessionManager)
                    }

                    // Always append a newline to finalize the line and move to the next.
                    val currentItem = session.currentExecutingCommand
                    if (currentItem != null) {
                        session.currentCommandOutput.append('\n')
                        currentItem.setOutput(session.currentCommandOutput.toString())
                    }
                } else {
                    handleReadyState(sessionId, line, sessionManager)
                }
            }
        }
    }

    private fun handleInitializingState(
        sessionId: String,
        line: String,
        sessionManager: SessionManager
    ) {
        if (line.contains("LOGIN_SUCCESSFUL")) {
            Log.d(TAG, "Login successful marker found.")
            sessionManager.getSession(sessionId)?.let { session ->
                session.currentCommandOutput.clear()
                sessionManager.updateSession(sessionId) {
                    it.copy(initState = SessionInitState.LOGGED_IN)
                }
            }
        }
    }

    private fun handleLoggedInState(
        sessionId: String,
        line: String,
        sessionManager: SessionManager
    ) {
        if (AnsiUtils.stripAnsi(line).trim() == "TERMINAL_READY") {
            Log.d(TAG, "TERMINAL_READY marker found.")
            sessionManager.updateSession(sessionId) { session ->
                session.copy(initState = SessionInitState.AWAITING_FIRST_PROMPT)
            }
        }
    }

    private fun handleAwaitingFirstPromptState(
        sessionId: String,
        line: String,
        sessionManager: SessionManager
    ) {
        val cleanLine = AnsiUtils.stripAnsi(line)
        if (handlePrompt(sessionId, cleanLine, sessionManager)) {
            Log.d(TAG, "First prompt detected. Session is now ready.")
            sessionManager.updateSession(sessionId) { session ->
                session.copy(initState = SessionInitState.READY)
            }
            
            // 发送欢迎语到 Canvas
            sendWelcomeMessage(sessionId, sessionManager)
        }
    }

    private fun handleReadyState(
        sessionId: String,
        line: String,
        sessionManager: SessionManager
    ) {
        val cleanLine = AnsiUtils.stripAnsi(line)
        Log.d(TAG, "Stripped line: '$cleanLine'")

        // 跳过TERMINAL_READY信号
        if (cleanLine.trim() == "TERMINAL_READY") {
            return
        }

        val session = sessionManager.getSession(sessionId) ?: return

        // 检测命令回显
        if (isCommandEcho(cleanLine, session)) {
            Log.d(TAG, "Ignoring command echo: '$cleanLine'")
            return
        }

        // 优先处理常规提示符，因为它表示命令结束
        if (handlePrompt(sessionId, cleanLine, sessionManager)) {
            return
        }

        // 注意：不在这里检测交互式提示符，因为这里处理的是以 CRLF 结束的完整行
        // 真正的交互式提示符通常不以换行结束，会在 buffer 末尾被检测到（第 118 行）

        // 处理普通输出
        updateCommandOutput(sessionId, cleanLine, sessionManager)
    }

    /**
     * 检测是否是提示符
     */
    fun isPrompt(line: String): Boolean {
        if (PromptDetector.findCwdPrompt(line) != null) {
            return true
        }
        return PromptDetector.isFallbackPrompt(line.trim())
    }

    /**
     * 处理提示符
     */
    private fun handlePrompt(
        sessionId: String,
        line: String,
        sessionManager: SessionManager
    ): Boolean {
        val session = sessionManager.getSession(sessionId) ?: return false

        val match = PromptDetector.findCwdPrompt(line)

        val isAPrompt = if (match != null) {
            val path = match.groups[1]?.value?.trim() ?: "~"
            sessionManager.updateSession(sessionId) { session ->
                session.copy(currentDirectory = "$path $")
            }
            
            // 发出目录变化事件
            onDirectoryChangeEvent(SessionDirectoryEvent(
                sessionId = sessionId,
                currentDirectory = "$path $"
            ))
            
            Log.d(TAG, "Matched CWD prompt. Path: $path")

            val outputBeforePrompt = line.substring(0, match.range.first)
            if (outputBeforePrompt.isNotBlank()) {
                session.currentCommandOutput.append(outputBeforePrompt)
            }
            true
        } else if (PromptDetector.isFallbackPrompt(line.trim())) {
            val cleanPrompt = PromptDetector.extractFallbackPath(line.trim())
            sessionManager.updateSession(sessionId) { session ->
                session.copy(currentDirectory = "${cleanPrompt} $")
            }
            
            // 发出目录变化事件
            onDirectoryChangeEvent(SessionDirectoryEvent(
                sessionId = sessionId,
                currentDirectory = "${cleanPrompt} $"
            ))
            
            Log.d(TAG, "Matched fallback prompt: $cleanPrompt")
            true
        } else {
            false
        }

        if (isAPrompt) {
            // 检测到常规提示符，表示我们回到了shell。
            // 确保退出任何持久的交互模式。
            if (session.isInteractiveMode) {
                sessionManager.updateSession(sessionId) {
                    it.copy(
                        isInteractiveMode = false,
                        interactivePrompt = ""
                    )
                }
            }
            finishCurrentCommand(sessionId, sessionManager)
            return true
        }
        return false
    }

    /**
     * 使用 PTY 模式检测是否正在等待交互式输入
     * 完全依赖 PTY 层面的状态，不使用任何文本模式匹配
     */
    fun isInteractivePrompt(line: String, sessionId: String, sessionManager: SessionManager): Boolean {
        val session = sessionManager.getSession(sessionId) ?: return false
        val pty = session.pty ?: return false
        
        // 只在有命令执行时才检测（避免误判普通 shell 提示符）
        if (session.currentExecutingCommand?.isExecuting != true) {
            return false
        }
        
        try {
            val ptyMode = pty.getPtyMode()
            val isWaiting = ptyMode.isWaitingForInput()
            
            if (!isWaiting) {
                return false
            }
            
            // 无论 canonical 还是 non-canonical mode，都完全依赖 PTY 状态判断
            // 不使用任何文本模式匹配或关键词判断
            val mode = if (ptyMode.isCanonicalMode) "canonical" else "non-canonical"
            Log.d(TAG, "Detected interactive prompt ($mode mode, available=${ptyMode.availableBytes}): $line")
            
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Error checking PTY mode", e)
            return false
        }
    }



    private fun handleInteractivePrompt(
        sessionId: String,
        cleanLine: String,
        sessionManager: SessionManager
    ) {
        Log.d(TAG, "Detected interactive prompt: $cleanLine")
        val session = sessionManager.getSession(sessionId) ?: return
        
        sessionManager.updateSession(sessionId) { session ->
            session.copy(
                isWaitingForInteractiveInput = true,
                lastInteractivePrompt = cleanLine,
                isInteractiveMode = true, // 统一标记为交互模式
                interactivePrompt = cleanLine
            )
        }

        // 将交互式提示添加到当前命令的输出中
        if (cleanLine.isNotBlank()) {
            updateCommandOutput(sessionId, cleanLine, sessionManager)
        }
    }



    private fun isCommandEcho(cleanLine: String, session: TerminalSessionData): Boolean {
        val lastExecutingItem = session.currentExecutingCommand
        if (lastExecutingItem != null && lastExecutingItem.isExecuting) {
            val commandToCheck = lastExecutingItem.command.trim()
            val lineToCheck = cleanLine.trim()
            val isMatch = lineToCheck == commandToCheck

            if (session.currentCommandOutput.isEmpty() && isMatch) {
                return true
            }
        }
        return false
    }

    private fun updateCommandOutput(
        sessionId: String,
        cleanLine: String,
        sessionManager: SessionManager
    ) {
        val session = sessionManager.getSession(sessionId) ?: return
        val currentItem = session.currentExecutingCommand

        if (currentItem != null && currentItem.isExecuting) {
            val builder = session.currentCommandOutput
            // 确保输出之间有换行符
            if (builder.isNotEmpty() && builder.last() != '\n') {
                builder.append('\n')
            }
            builder.append(cleanLine)

            // 实时更新当前输出块
            currentItem.setOutput(builder.toString())
            session.currentOutputLineCount++
            
            // 发出命令执行过程事件
            onCommandExecutionEvent(CommandExecutionEvent(
                commandId = currentItem.id,
                sessionId = sessionId,
                outputChunk = cleanLine,
                isCompleted = false
            ))

            if (session.currentOutputLineCount >= MAX_LINES_PER_HISTORY_ITEM) {
                // 当前页已满，将其添加到已完成的页面列表并开始新的一页
                currentItem.outputPages.add(currentItem.output)
                builder.clear()
                session.currentOutputLineCount = 0
                currentItem.setOutput("") // 为新页面清空实时输出
            }
        }
    }

    private fun updateProgressOutput(
        sessionId: String,
        cleanLine: String,
        sessionManager: SessionManager
    ) {
        val session = sessionManager.getSession(sessionId) ?: return
        val builder = session.currentCommandOutput
        val lastExecutingItem = session.currentExecutingCommand

        if (lastExecutingItem != null && lastExecutingItem.isExecuting) {
             // More efficient way to replace the last line
            val lastNewlineIndex = builder.lastIndexOf('\n')
            if (lastNewlineIndex != -1) {
                // Found a newline, replace everything after it
                builder.setLength(lastNewlineIndex + 1)
                builder.append(cleanLine)
            } else {
                // No newline, replace the whole buffer
                builder.clear()
                builder.append(cleanLine)
            }
            // Update history from the builder
            lastExecutingItem.setOutput(builder.toString().trimEnd())
        }
    }

    private fun finishCurrentCommand(sessionId: String, sessionManager: SessionManager) {
        sessionManager.updateSession(sessionId) { session ->
            session.copy(
                isWaitingForInteractiveInput = false,
                lastInteractivePrompt = "",
                isInteractiveMode = false,
                interactivePrompt = ""
            )
        }

        val session = sessionManager.getSession(sessionId) ?: return
        val lastExecutingItem = session.currentExecutingCommand

        if (lastExecutingItem != null && lastExecutingItem.isExecuting) {
            lastExecutingItem.setOutput(session.currentCommandOutput.toString().trim())
            lastExecutingItem.setExecuting(false)

            Log.i(TAG, "Finishing command ${lastExecutingItem.id} for session $sessionId")
            
            // 发出命令完成事件
            onCommandExecutionEvent(CommandExecutionEvent(
                commandId = lastExecutingItem.id,
                sessionId = sessionId,
                outputChunk = "",
                isCompleted = true
            ))

            // Clear the reference since command is no longer executing
            session.currentExecutingCommand = null
            session.currentCommandOutput.clear()
            
            // 通知命令已完成，可以处理下一个队列命令
            onCommandCompleted(sessionId)
        }
    }

    /**
     * 检测并处理全屏模式切换
     * @return 如果处理了全屏模式切换，则返回 true
     */
    private fun detectFullscreenMode(sessionId: String, buffer: StringBuilder, sessionManager: SessionManager): Boolean {
        // CSI ? 1049 h: 启用备用屏幕缓冲区（进入全屏模式）
        // CSI ? 1049 l: 禁用备用屏幕缓冲区（退出全屏模式）
        val enterFullscreen = "\u001B[?1049h"
        val exitFullscreen = "\u001B[?1049l"

        val bufferContent = buffer.toString()

        val enterIndex = bufferContent.indexOf(enterFullscreen)
        val exitIndex = bufferContent.indexOf(exitFullscreen)

        if (enterIndex != -1) {
            Log.d(TAG, "Entering fullscreen mode for session $sessionId")
            
            sessionManager.updateSession(sessionId) { session ->
                session.copy(isFullscreen = true)
            }
            
            // 清空缓冲区，ansiParser 已经包含所有内容
            buffer.clear()
            return true
        }

        if (exitIndex != -1) {
            Log.d(TAG, "Exiting fullscreen mode for session $sessionId")
            val outputBeforeExit = bufferContent.substring(0, exitIndex)

            // 更新最后一个命令的输出
            if (outputBeforeExit.isNotEmpty()) {
                updateCommandOutput(sessionId, outputBeforeExit, sessionManager)
            }

            sessionManager.updateSession(sessionId) { session ->
                session.copy(isFullscreen = false)
            }

            // 消耗包括退出代码在内的所有内容
            buffer.delete(0, exitIndex + exitFullscreen.length)

            // 退出全屏后，我们可能需要重新绘制提示符
            finishCurrentCommand(sessionId, sessionManager)
            return true
        }
        return false
    }

    /**
     * 发送欢迎消息到 Canvas
     */
    private fun sendWelcomeMessage(sessionId: String, sessionManager: SessionManager) {
        val session = sessionManager.getSession(sessionId) ?: return
        
        // 构建欢迎消息，包含 ANSI 控制序列
        // \u001B[2J - 清屏
        // \u001B[H - 移动光标到左上角
        // 使用 \r\n 确保正确换行（\r 回车到行首，\n 换到下一行）
        val welcomeMessage = "\u001B[2J\u001B[H" +
            "  #####  ###  ####  #   #  ###  #####\r\n" +
            "     #  #   # #   # #   # #   #   #\r\n" +
            "    #   #   # ####  #   # #####   #\r\n" +
            "   #    #   # #  #   # #  #   #   #\r\n" +
            "  #####  ###  #   #   #   #   # #####\r\n" +
            "\r\n" +
            "  ZorvAI Terminal v1.0 · Ubuntu (proot)\r\n" +
            "\r\n"
        
        // 直接发送到 ANSI 解析器（Canvas 渲染）
        session.ansiParser.parse(welcomeMessage)
        
        Log.d(TAG, "Welcome message sent to Canvas for session $sessionId")
    }

}