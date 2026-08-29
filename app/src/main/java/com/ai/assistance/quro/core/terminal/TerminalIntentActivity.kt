package com.ai.assistance.quro.core.terminal

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log

/**
 * 终端 Intent Activity — 透明 Activity，作为外部应用调用终端的标准 Android 入口。
 *
 * Android 四大组件之 Activity：画界面，跟用户交互。
 * 本 Activity 设为透明（theme Translucent），无可见 UI，
 * 仅作为 Intent 处理器，处理完毕后立即 finish()。
 *
 * 支持的 Intent 调用方式：
 * 1. 显式 Intent：指定 ComponentName 启动
 * 2. 隐式 Intent：通过 Intent Filter 匹配 action
 * 3. startActivityForResult：结果通过 onActivityResult 回传
 * 4. Deep Link：quro://terminal/... URI
 *
 * Manifest 配置（Intent Filter）：
 * - com.ai.assistance.quro.action.TERMINAL_EXEC     → 执行命令
 * - com.ai.assistance.quro.action.TERMINAL_STATUS    → 获取状态
 * - com.ai.assistance.quro.action.TERMINAL_SESSIONS  → 列出会话
 * - com.ai.assistance.quro.action.TERMINAL_CREATE_SESSION → 创建会话
 * - com.ai.assistance.quro.action.TERMINAL_DESTROY_SESSION → 销毁会话
 * - com.ai.assistance.quro.action.TERMINAL_SEND_INPUT     → 发送输入
 * - com.ai.assistance.quro.action.TERMINAL_GET_OUTPUT     → 获取输出
 * - com.ai.assistance.quro.action.TERMINAL_PICK_SESSION   → ACTION_PICK 模式
 * - Intent.ACTION_SEND (text/plain)  → 分享文本到终端
 * - Intent.ACTION_VIEW (quro://terminal/...) → Deep Link
 *
 * 外部应用调用示例（显式 Intent）：
 *   val intent = Intent()
 *   intent.component = ComponentName("com.ai.assistance.quro",
 *       "com.ai.assistance.quro.core.terminal.TerminalIntentActivity")
 *   intent.action = "com.ai.assistance.quro.action.TERMINAL_EXEC"
 *   intent.putExtra("command", "ls -la")
 *   intent.putExtra("timeout", 14L)
 *   startActivityForResult(intent, 0)
 *
 * 外部应用调用示例（隐式 Intent）：
 *   val intent = Intent("com.ai.assistance.quro.action.TERMINAL_EXEC")
 *   intent.putExtra("command", "uname -a")
 *   startActivity(intent)
 *
 * 外部应用调用示例（ACTION_SEND）：
 *   val intent = Intent(Intent.ACTION_SEND)
 *   intent.type = "text/plain"
 *   intent.putExtra(Intent.EXTRA_TEXT, "echo hello")
 *   intent.setPackage("com.ai.assistance.quro")
 *   startActivity(intent)
 *
 * 外部应用调用示例（Deep Link）：
 *   val intent = Intent(Intent.ACTION_VIEW,
 *       Uri.parse("quro://terminal/exec?cmd=ls -la"))
 *   startActivity(intent)
 */
class TerminalIntentActivity : Activity() {

    companion object {
        private const val TAG = "TerminalIntentActivity"
        private const val RESULT_ERROR = RESULT_FIRST_USER + 1

        // ========== Action 定义（与 TerminalIntentHandler 保持一致）==========
        /** 执行命令 */
        const val ACTION_EXEC = "com.ai.assistance.quro.action.TERMINAL_EXEC"
        /** 获取终端状态 */
        const val ACTION_STATUS = "com.ai.assistance.quro.action.TERMINAL_STATUS"
        /** 列出所有会话 */
        const val ACTION_SESSIONS = "com.ai.assistance.quro.action.TERMINAL_SESSIONS"
        /** 创建新会话 */
        const val ACTION_CREATE_SESSION = "com.ai.assistance.quro.action.TERMINAL_CREATE_SESSION"
        /** 销毁会话 */
        const val ACTION_DESTROY_SESSION = "com.ai.assistance.quro.action.TERMINAL_DESTROY_SESSION"
        /** 向会话发送输入 */
        const val ACTION_SEND_INPUT = "com.ai.assistance.quro.action.TERMINAL_SEND_INPUT"
        /** 获取会话输出历史 */
        const val ACTION_GET_OUTPUT = "com.ai.assistance.quro.action.TERMINAL_GET_OUTPUT"
        /** ACTION_PICK 模式 */
        const val ACTION_PICK_SESSION = "com.ai.assistance.quro.action.TERMINAL_PICK_SESSION"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate: action=${intent?.action}, pid=${android.os.Process.myPid()}")

        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        Log.d(TAG, "onNewIntent: action=${intent?.action}")
        handleIntent(intent)
    }

    /**
     * 核心 Intent 分发逻辑。
     */
    private fun handleIntent(intent: Intent?) {
        if (intent == null) {
            val errBundle = Bundle().apply {
                putInt("result_code", RESULT_ERROR)
                putString("result_message", "无效的 Intent")
            }
            setResult(RESULT_CANCELED, Intent().apply { putExtras(errBundle) })
            finish()
            return
        }

        val resultBundle = Bundle()
        try {
            when (intent.action) {
                ACTION_EXEC -> handleExec(intent, resultBundle)
                ACTION_STATUS -> handleStatus(resultBundle)
                ACTION_SESSIONS -> handleSessions(resultBundle)
                ACTION_CREATE_SESSION -> handleCreateSession(intent, resultBundle)
                ACTION_DESTROY_SESSION -> handleDestroySession(intent, resultBundle)
                ACTION_SEND_INPUT -> handleSendInput(intent, resultBundle)
                ACTION_GET_OUTPUT -> handleGetOutput(intent, resultBundle)
                ACTION_PICK_SESSION -> handlePickSession(intent, resultBundle)
                Intent.ACTION_SEND -> handleSend(intent, resultBundle)
                Intent.ACTION_VIEW -> handleView(intent, resultBundle)
                else -> {
                    putResult(resultBundle, RESULT_ERROR, "未知 Action: ${intent.action}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "处理 Intent 失败: ${e.message}", e)
            putResult(resultBundle, RESULT_ERROR, "错误：${e.message}")
        }

        // 回传结果（startActivityForResult 模式）
        val resultCode = resultBundle.getInt("result_code", RESULT_CANCELED)
        val resultIntent = Intent().apply { putExtras(resultBundle) }
        setResult(resultCode, resultIntent)
        finish()
    }

    // ========== Action Handlers ==========

    private fun handleExec(intent: Intent, result: Bundle) {
        val command = intent.getStringExtra("command")
        if (command.isNullOrBlank()) {
            putResult(result, RESULT_ERROR, "错误：缺少 command 参数")
            return
        }
        val timeout = intent.getLongExtra("timeout", 14L)

        val execResult = try {
            QuroTerminalController.runCommand(command, timeout * 1000, this)
        } catch (e: Exception) {
            ShellResult(output = "", exitCode = -1, error = e.message ?: "未知错误")
        }

        val code = if (execResult.exitCode == 0) RESULT_OK else RESULT_FIRST_USER
        putResult(result, code, "Exit Code: ${execResult.exitCode}")
        result.putInt("exit_code", execResult.exitCode)
        result.putString("output", execResult.output)
        result.putString("error", execResult.error)
        result.putBoolean("timed_out", execResult.timedOut)
    }

    private fun handleStatus(result: Bundle) {
        val sessions = QuroTerminalSessionManager.listSessions()
        putResult(result, RESULT_OK, "会话数量: ${sessions.size}")
        result.putInt("session_count", sessions.size)

        val sessionArray = sessions.map { s ->
            Bundle().apply {
                putString("id", s.id)
                putString("name", s.name)
                putBoolean("alive", s.alive)
                putBoolean("is_default", s.isDefault)
            }
        }.toTypedArray()
        result.putParcelableArray("sessions", sessionArray)
    }

    private fun handleSessions(result: Bundle) {
        handleStatus(result)
    }

    private fun handleCreateSession(intent: Intent, result: Bundle) {
        val name = intent.getStringExtra("session_name")
            ?: "session_${System.currentTimeMillis()}"

        val session = try {
            kotlinx.coroutines.runBlocking {
                QuroTerminalSessionManager.createSession(this@TerminalIntentActivity, name)
            }
        } catch (e: Exception) {
            Log.e(TAG, "创建会话失败: ${e.message}", e)
            null
        }

        if (session != null) {
            putResult(result, RESULT_OK, "会话已创建")
            result.putString("session_id", session.id)
            result.putString("session_name", session.name)
        } else {
            putResult(result, RESULT_FIRST_USER, "创建会话失败")
        }
    }

    private fun handleDestroySession(intent: Intent, result: Bundle) {
        val sessionId = intent.getStringExtra("session_id")
        if (sessionId.isNullOrBlank()) {
            putResult(result, RESULT_ERROR, "错误：缺少 session_id 参数")
            return
        }

        val destroyed = try {
            kotlinx.coroutines.runBlocking {
                QuroTerminalSessionManager.destroySession(sessionId)
            }
        } catch (e: Exception) {
            Log.e(TAG, "销毁会话失败: ${e.message}", e)
            false
        }

        putResult(result, if (destroyed) RESULT_OK else RESULT_FIRST_USER,
            if (destroyed) "会话已销毁" else "会话不存在或已销毁")
        result.putBoolean("destroyed", destroyed)
    }

    private fun handleSendInput(intent: Intent, result: Bundle) {
        val sessionId = intent.getStringExtra("session_id")
        val input = intent.getStringExtra("input")
        if (sessionId.isNullOrBlank() || input.isNullOrBlank()) {
            putResult(result, RESULT_ERROR, "错误：缺少 session_id 或 input 参数")
            return
        }

        val shell = QuroTerminalSessionManager.getShellSession(sessionId)
        if (shell == null) {
            putResult(result, RESULT_FIRST_USER, "会话不存在: $sessionId")
            return
        }

        shell.sendRaw(input)
        putResult(result, RESULT_OK, "输入已发送")
        result.putBoolean("sent", true)
    }

    private fun handleGetOutput(intent: Intent, result: Bundle) {
        val sessionId = intent.getStringExtra("session_id")
        val limit = intent.getIntExtra("output_limit", 100)

        if (sessionId.isNullOrBlank()) {
            putResult(result, RESULT_ERROR, "错误：缺少 session_id 参数")
            return
        }

        val shell = QuroTerminalSessionManager.getShellSession(sessionId)
        if (shell == null) {
            putResult(result, RESULT_FIRST_USER, "会话不存在: $sessionId")
            return
        }

        val allLines = shell.lines.toList()
        val output = if (allLines.size > limit) allLines.takeLast(limit) else allLines

        putResult(result, RESULT_OK, "输出历史: ${output.size} 行")
        result.putStringArrayList("output_lines", ArrayList(output))
    }

    /**
     * ACTION_PICK 模式 — Intent + ContentProvider 协作。
     *
     * 返回终端会话列表的 Content URI，调用方通过 ContentResolver 读取数据。
     * 结果 Intent 携带 URI + FLAG_GRANT_READ_URI_PERMISSION。
     */
    private fun handlePickSession(intent: Intent, result: Bundle) {
        val sessionsUri = Uri.parse("content://com.ai.assistance.quro.terminal/sessions")
        putResult(result, RESULT_OK, "请通过 ContentResolver 查询会话列表")
        result.putParcelable("data", sessionsUri)
        result.putString("mime_type", "vnd.android.cursor.dir/vnd.com.ai.assistance.quro.terminal.sessions")

        // 设置 URI 权限标志，让调用方获得临时读权限
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    /**
     * 处理 ACTION_SEND — 将文本发送到终端。
     */
    private fun handleSend(intent: Intent, result: Bundle) {
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)
        if (text.isNullOrBlank()) {
            putResult(result, RESULT_ERROR, "错误：缺少文本内容")
            return
        }

        val shell = QuroTerminalSessionManager.defaultSession
        if (shell == null) {
            putResult(result, RESULT_FIRST_USER, "没有可用的终端会话")
            return
        }

        shell.sendRaw(text)
        putResult(result, RESULT_OK, "文本已发送: ${text.length} 字节")
        result.putBoolean("sent", true)
    }

    /**
     * 处理 ACTION_VIEW — 通过 Deep Link 打开终端。
     */
    private fun handleView(intent: Intent, result: Bundle) {
        val uri = intent.data
        if (uri == null) {
            putResult(result, RESULT_ERROR, "错误：缺少 URI")
            return
        }

        when (uri.pathSegments.firstOrNull()) {
            "exec" -> {
                val cmd = uri.getQueryParameter("cmd")
                if (cmd != null) {
                    val timeout = uri.getQueryParameter("timeout")?.toLongOrNull() ?: 14L
                    val execResult = try {
                        QuroTerminalController.runCommand(cmd, timeout * 1000, this)
                    } catch (e: Exception) {
                        ShellResult(output = "", exitCode = -1, error = e.message ?: "未知错误")
                    }
                    val code = if (execResult.exitCode == 0) RESULT_OK else RESULT_FIRST_USER
                    putResult(result, code, "Exit Code: ${execResult.exitCode}")
                    result.putInt("exit_code", execResult.exitCode)
                    result.putString("output", execResult.output)
                    result.putString("error", execResult.error)
                } else {
                    putResult(result, RESULT_ERROR, "错误：缺少 cmd 参数")
                }
            }
            "sessions" -> handleSessions(result)
            "status" -> handleStatus(result)
            else -> putResult(result, RESULT_ERROR, "未知路径: ${uri.pathSegments}")
        }
    }

    // ========== 工具方法 ==========

    private fun putResult(result: Bundle, code: Int, message: String) {
        result.putInt("result_code", code)
        result.putString("result_message", message)
    }
}
