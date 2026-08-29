package com.ai.assistance.quro.core.terminal

import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 终端 Intent 处理器 - 处理标准 Android Intent 调用。
 */
class TerminalIntentHandler(private val context: Context) {

    companion object {
        private const val TAG = "TerminalIntentHandler"
        const val ACTION_EXEC = "com.ai.assistance.quro.action.TERMINAL_EXEC"
        const val ACTION_STATUS = "com.ai.assistance.quro.action.TERMINAL_STATUS"
        const val EXTRA_COMMAND = "command"
        const val EXTRA_TIMEOUT = "timeout"
    }

    fun handleIntent(intent: Intent): String {
        return try {
            when (intent.action) {
                ACTION_EXEC -> handleExec(intent)
                ACTION_STATUS -> handleStatus()
                Intent.ACTION_SEND -> handleSend(intent)
                else -> "错误：未知的 Action: ${intent.action}"
            }
        } catch (e: Exception) {
            Log.e(TAG, "处理 Intent 失败: ${e.message}")
            "错误：${e.message}"
        }
    }

    private fun handleExec(intent: Intent): String {
        val command = intent.getStringExtra(EXTRA_COMMAND) ?: return "错误：缺少 command 参数"
        val timeout = intent.getLongExtra(EXTRA_TIMEOUT, 14L)
        val result = QuroTerminalController.runCommand(command, timeout * 1000, context)
        return buildString {
            appendLine("Exit Code: ${result.exitCode}")
            if (result.output.isNotBlank()) { appendLine("Output:"); appendLine(result.output) }
            if (result.error?.isNotBlank() == true) { appendLine("Error:"); appendLine(result.error) }
            if (result.timedOut) appendLine("[TIMED OUT]")
        }
    }

    private fun handleStatus(): String {
        val sessions = QuroTerminalSessionManager.listSessions()
        return buildString {
            appendLine("会话数量: ${sessions.size}")
            for (s in sessions) { appendLine("- ${s.id} (${s.name}) [${if (s.alive) "存活" else "已退出"}]") }
        }
    }

    private fun handleSend(intent: Intent): String {
        val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return "错误：缺少文本内容"
        val session = QuroTerminalSessionManager.defaultSession ?: return "错误：没有可用的终端会话"
        return try {
            session.sendCommand(text)
            "文本已发送: ${text.length} 字节"
        } catch (e: Exception) {
            "错误：发送文本失败: ${e.message}"
        }
    }

    fun createExecIntent(command: String, timeout: Long = 14L): Intent {
        return Intent(ACTION_EXEC).apply {
            putExtra(EXTRA_COMMAND, command)
            putExtra(EXTRA_TIMEOUT, timeout)
        }
    }

    fun createStatusIntent(): Intent = Intent(ACTION_STATUS)

    fun createSendIntent(text: String): Intent {
        return Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
    }
}
