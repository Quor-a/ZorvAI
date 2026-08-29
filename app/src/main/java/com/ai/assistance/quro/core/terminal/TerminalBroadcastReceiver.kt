package com.ai.assistance.quro.core.terminal

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 终端广播接收器 - 通过广播执行终端命令、获取终端状态。
 *
 * 支持的广播 Action：
 * - com.ai.assistance.quro.action.TERMINAL_EXEC  → 执行命令
 * - com.ai.assistance.quro.action.TERMINAL_STATUS → 获取状态
 *
 * Extras: command (String), timeout (Long)
 */
class TerminalBroadcastReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "TerminalBcastReceiver"
        const val ACTION_EXEC = "com.ai.assistance.quro.action.TERMINAL_EXEC"
        const val ACTION_STATUS = "com.ai.assistance.quro.action.TERMINAL_STATUS"
        const val EXTRA_COMMAND = "command"
        const val EXTRA_TIMEOUT = "timeout"
        const val ACTION_RESULT = "com.ai.assistance.quro.action.TERMINAL_RESULT"
    }

    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "收到广播: ${intent.action}")
        val pendingResult = goAsync()
        Thread {
            try {
                val result = when (intent.action) {
                    ACTION_EXEC -> handleExec(context, intent)
                    ACTION_STATUS -> handleStatus()
                    else -> "未知 Action: ${intent.action}"
                }
                sendResult(context, intent, result)
            } catch (e: Exception) {
                Log.e(TAG, "处理广播失败: ${e.message}")
                sendResult(context, intent, "错误：${e.message}")
            } finally {
                pendingResult.finish()
            }
        }.start()
    }

    private fun handleExec(context: Context, intent: Intent): String {
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

    private fun sendResult(context: Context, originalIntent: Intent, result: String) {
        val resultIntent = Intent(ACTION_RESULT).apply {
            `package` = originalIntent.`package`
            putExtra("result_output", result)
        }
        context.sendBroadcast(resultIntent)
    }
}
