package com.ai.assistance.quro.terminal.command

import android.util.Log
import com.ai.assistance.quro.terminal.CommandExecutionEvent
import com.ai.assistance.quro.terminal.data.CommandHistoryItem
import com.ai.assistance.quro.terminal.data.QueuedCommand
import com.ai.assistance.quro.terminal.data.TerminalSessionData
import com.ai.assistance.quro.terminal.domain.SessionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/**
 * 命令派发器。
 *
 * 职责单一：负责把用户命令写入 PTY，管理每个会话的命令队列（串行执行、
 * 排队等待），并发出命令执行开始事件。输出解析、会话状态维护不在此层。
 */
class CommandDispatcher(
    private val sessionManager: SessionManager,
    private val scope: CoroutineScope,
    private val emitCommandEvent: (CommandExecutionEvent) -> Unit
) {
    companion object {
        private const val TAG = "CommandDispatcher"
    }

    /**
     * 向当前会话发送命令。
     */
    suspend fun sendCommand(command: String, commandId: String? = null): String {
        val actualCommandId = commandId ?: UUID.randomUUID().toString()
        val session = sessionManager.getCurrentSession() ?: return actualCommandId

        if (session.isInteractiveMode) {
            Log.d(TAG, "Session in interactive mode, sending as input: $command")
            sendInput(command + "\n")
            return actualCommandId
        }

        session.commandMutex.withLock {
            if (session.currentExecutingCommand?.isExecuting == true) {
                session.commandQueue.add(QueuedCommand(actualCommandId, command))
                Log.d(TAG, "Command queued: $command (id: $actualCommandId). Queue size: ${session.commandQueue.size}")
            } else {
                executeCommandInternal(command, session, actualCommandId)
            }
        }
        return actualCommandId
    }

    /**
     * 向指定会话发送命令（不切换当前会话）。
     */
    suspend fun sendCommandToSession(sessionId: String, command: String, commandId: String? = null): String {
        val actualCommandId = commandId ?: UUID.randomUUID().toString()
        val session = sessionManager.getSession(sessionId) ?: return actualCommandId

        if (session.isInteractiveMode) {
            Log.d(TAG, "Session $sessionId in interactive mode, sending as input: $command")
            try {
                session.sessionWriter?.write(command + "\n")
                session.sessionWriter?.flush()
            } catch (e: Exception) {
                Log.e(TAG, "Error sending input to session $sessionId", e)
            }
            return actualCommandId
        }

        session.commandMutex.withLock {
            if (session.currentExecutingCommand?.isExecuting == true) {
                session.commandQueue.add(QueuedCommand(actualCommandId, command))
                Log.d(TAG, "Command queued for session $sessionId: $command (id: $actualCommandId). Queue size: ${session.commandQueue.size}")
            } else {
                executeCommandInternal(command, session, actualCommandId)
            }
        }
        return actualCommandId
    }

    /**
     * 处理队列中的下一个命令（由输出处理器在命令完成时触发）。
     */
    internal suspend fun processNextQueuedCommand(sessionId: String) {
        val session = sessionManager.getSession(sessionId) ?: return

        session.commandMutex.withLock {
            if (session.currentExecutingCommand?.isExecuting == true) {
                Log.w(TAG, "processNextQueuedCommand called, but a command is still executing. This should not happen.")
                return@withLock
            }

            if (session.commandQueue.isNotEmpty()) {
                val nextCommand = session.commandQueue.removeAt(0)
                Log.d(TAG, "Processing next queued command: ${nextCommand.command} (id: ${nextCommand.id}). Queue size: ${session.commandQueue.size}")
                executeCommandInternal(nextCommand.command, session, nextCommand.id)
            }
        }
    }

    /**
     * 内部执行命令，必须在 commandMutex 锁内部调用。
     */
    private suspend fun executeCommandInternal(command: String, session: TerminalSessionData, commandId: String) {
        if (command.trim() == "clear") {
            try {
                session.sessionWriter?.write("clear\n")
                session.sessionWriter?.flush()
            } catch (e: Exception) {
                Log.e(TAG, "Error sending 'clear' command", e)
            }
        } else {
            handleRegularCommand(command, session, commandId)
            try {
                val fullInput = "$command\n"
                session.sessionWriter?.write(fullInput)
                session.sessionWriter?.flush()
                Log.d(TAG, "Sent command to PTY: $command")
            } catch (e: Exception) {
                Log.e(TAG, "Error sending command", e)
            }
        }
    }

    /**
     * 发送输入（用于交互模式）。
     */
    fun sendInput(input: String) {
        scope.launch(Dispatchers.IO) {
            val session = sessionManager.getCurrentSession() ?: return@launch

            try {
                session.sessionWriter?.write(input)
                session.sessionWriter?.flush()
                Log.d(TAG, "Sent input: '$input'")

                if (session.isWaitingForInteractiveInput) {
                    sessionManager.updateSession(session.id) {
                        it.copy(isWaitingForInteractiveInput = false)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error sending input", e)
            }
        }
    }

    /**
     * 发送中断信号（Ctrl+C）。
     */
    fun sendInterruptSignal() {
        scope.launch(Dispatchers.IO) {
            try {
                val currentSession = sessionManager.getCurrentSession()
                currentSession?.sessionWriter?.apply {
                    write(3) // ETX character (Ctrl+C)
                    flush()
                    Log.d(TAG, "Sent interrupt signal (Ctrl+C) to session ${currentSession.id}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error sending interrupt signal", e)
            }
        }
    }

    private fun handleRegularCommand(command: String, session: TerminalSessionData, commandId: String) {
        session.currentCommandOutput.clear()
        session.currentOutputLineCount = 0

        val newCommandItem = CommandHistoryItem(
            id = commandId,
            prompt = session.currentDirectory,
            command = command,
            output = "",
            isExecuting = true
        )

        session.currentExecutingCommand = newCommandItem

        emitCommandEvent(CommandExecutionEvent(
            commandId = newCommandItem.id,
            sessionId = session.id,
            outputChunk = "",
            isCompleted = false
        ))
    }
}
