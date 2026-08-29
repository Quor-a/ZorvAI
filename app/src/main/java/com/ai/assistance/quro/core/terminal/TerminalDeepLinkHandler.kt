package com.ai.assistance.quro.core.terminal

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log

/**
 * 终端 Deep Link 处理器 - 处理 quro://terminal/... 格式的深度链接。
 *
 * 支持的 URI：
 * - quro://terminal/exec?cmd={command}&timeout={timeout}
 * - quro://terminal/sessions
 * - quro://terminal/sessions/{id}
 * - quro://terminal/status
 * - quro://terminal/create?name={name}&mode={mode}
 */
class TerminalDeepLinkHandler(private val context: Context) {

    companion object {
        private const val TAG = "TerminalDeepLink"
        const val SCHEME = "quro"
        const val HOST = "terminal"
    }

    fun handleIntent(intent: Intent): String {
        val uri = intent.data ?: return "错误：无效的 URI"
        if (uri.scheme != SCHEME || uri.host != HOST) {
            return "错误：无效的 Deep Link 格式"
        }
        return try {
            processUri(uri)
        } catch (e: Exception) {
            Log.e(TAG, "处理 Deep Link 失败: ${e.message}")
            "错误：${e.message}"
        }
    }

    private fun processUri(uri: Uri): String {
        val segments = uri.pathSegments
        if (segments.isEmpty()) return "错误：缺少操作路径"

        return when (segments[0]) {
            "exec" -> handleExec(uri)
            "sessions" -> if (segments.size == 1) listSessions() else getSession(segments[1])
            "status" -> handleStatus()
            "create" -> handleCreate(uri)
            else -> "错误：未知操作 ${segments[0]}"
        }
    }

    private fun handleExec(uri: Uri): String {
        val command = uri.getQueryParameter("cmd") ?: return "错误：缺少 cmd 参数"
        val timeout = uri.getQueryParameter("timeout")?.toLongOrNull() ?: 14L
        val result = QuroTerminalController.runCommand(command, timeout * 1000, context)
        return buildString {
            appendLine("Exit Code: ${result.exitCode}")
            if (result.output.isNotBlank()) { appendLine("Output:"); appendLine(result.output) }
            if (result.error?.isNotBlank() == true) { appendLine("Error:"); appendLine(result.error) }
            if (result.timedOut) appendLine("[TIMED OUT]")
        }
    }

    private fun listSessions(): String {
        val sessions = QuroTerminalSessionManager.listSessions()
        return buildString {
            appendLine("=== 终端会话列表 ===")
            for (s in sessions) {
                appendLine("${s.id} (${s.name}) [${if (s.alive) "存活" else "已退出"}]")
            }
        }
    }

    private fun getSession(sessionId: String): String {
        val sessions = QuroTerminalSessionManager.listSessions()
        val s = sessions.find { it.id == sessionId } ?: return "错误：会话不存在: $sessionId"
        return "ID: ${s.id}\nName: ${s.name}\nMode: ${s.backend.name}\nAlive: ${s.alive}\nDefault: ${s.isDefault}"
    }

    private fun handleStatus(): String {
        val sessions = QuroTerminalSessionManager.listSessions()
        return buildString {
            appendLine("会话数量: ${sessions.size}")
            for (s in sessions) { appendLine("- ${s.id} (${s.name}) [${if (s.alive) "存活" else "已退出"}]") }
        }
    }

    private fun handleCreate(uri: Uri): String {
        val name = uri.getQueryParameter("name") ?: "session-${System.currentTimeMillis()}"
        // createSession 是 suspend 函数，在非协程上下文不能直接调用
        return "创建请求已接收: $name（需通过协程上下文调用）"
    }
}
