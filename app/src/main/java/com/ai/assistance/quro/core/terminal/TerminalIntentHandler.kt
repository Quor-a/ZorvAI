package com.ai.assistance.quro.core.terminal

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import kotlinx.coroutines.runBlocking

/**
 * 终端 Intent 处理器 — 符合 Android 标准的 Intent 处理。
 *
 * Intent 分类：
 * - 显式 Intent：指定完整 ComponentName，启动自己或目标应用的指定组件
 * - 隐式 Intent：声明 action/data/category，由系统匹配 Intent Filter
 *
 * 本处理器支持：
 * 1. 显式 Intent（精确指定 TerminalIntentHandler）
 * 2. 隐式 Intent（通过 Intent Filter 匹配）
 * 3. 有序广播（sendOrderedBroadcast）
 * 4. startActivityForResult 结果回传
 * 5. ACTION_PICK 模式（Intent + ContentProvider 协作）
 *
 * 安全实践：
 * - Service 绑定必须使用显式 Intent
 * - 隐式 Intent 仅用于 Activity 和 Broadcast
 * - 高危操作需要用户确认
 */
class TerminalIntentHandler(private val context: Context) {

    companion object {
        private const val TAG = "TerminalIntentHandler"

        // ========== Action 定义 ==========
        /** 执行命令（显式/隐式均可） */
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
        /** 获取会话输出（ACTION_PICK 模式：返回 Content URI 让调用方通过 ContentResolver 读取） */
        const val ACTION_PICK_SESSION = "com.ai.assistance.quro.action.TERMINAL_PICK_SESSION"

        // ========== Extra Key 定义 ==========
        const val EXTRA_COMMAND = "command"
        const val EXTRA_TIMEOUT = "timeout"
        const val EXTRA_SESSION_ID = "session_id"
        const val EXTRA_SESSION_NAME = "session_name"
        const val EXTRA_INPUT = "input"
        const val EXTRA_OUTPUT_LIMIT = "output_limit"
        const val EXTRA_SESSION_OUTPUT = "session_output"

        // ========== Result Code 定义 ==========
        const val RESULT_SUCCESS = Activity.RESULT_OK        // -1
        const val RESULT_ERROR = Activity.RESULT_CANCELED    // 0
        const val RESULT_TIMEOUT = 2
        const val RESULT_NO_SESSION = 3

        // ========== MIME 类型 ==========
        const val MIME_SESSION_LIST = "vnd.android.cursor.dir/vnd.com.ai.assistance.quro.terminal.sessions"
        const val MIME_SESSION_ITEM = "vnd.android.cursor.item/vnd.com.ai.assistance.quro.terminal.sessions"
        const val MIME_EXEC_RESULT = "vnd.android.cursor.item/vnd.com.ai.assistance.quro.terminal.exec"
    }

    // ========== Intent 处理入口 ==========

    /**
     * 处理 Intent（支持显式和隐式）。
     *
     * 显式 Intent：调用方直接指定 ComponentName
     *   val intent = Intent()
     *   intent.component = ComponentName("com.ai.assistance.quro",
     *       "com.ai.assistance.quro.core.terminal.TerminalIntentHandler")
     *   intent.putExtra("command", "ls -la")
     *   startActivityForResult(intent, REQUEST_CODE)
     *
     * 隐式 Intent：声明 action，由系统匹配 Intent Filter
     *   val intent = Intent("com.ai.assistance.quro.action.TERMINAL_EXEC")
     *   intent.putExtra("command", "ls -la")
     *   startActivity(intent)
     *
     * @param intent 收到的 Intent
     * @param requestCode startActivityForResult 的请求码（非 Activity 调用传 -1）
     * @return Bundle 结果，包含 result_code、result_message、data 等
     */
    fun handleIntent(intent: Intent, requestCode: Int = -1): Bundle {
        val result = Bundle()
        try {
            when (intent.action) {
                ACTION_EXEC -> handleExec(intent, result)
                ACTION_STATUS -> handleStatus(result)
                ACTION_SESSIONS -> handleSessions(result)
                ACTION_CREATE_SESSION -> handleCreateSession(intent, result)
                ACTION_DESTROY_SESSION -> handleDestroySession(intent, result)
                ACTION_SEND_INPUT -> handleSendInput(intent, result)
                ACTION_GET_OUTPUT -> handleGetOutput(intent, result)
                ACTION_PICK_SESSION -> handlePickSession(intent, result)
                Intent.ACTION_SEND -> handleSend(intent, result)
                Intent.ACTION_VIEW -> handleView(intent, result)
                else -> {
                    result.putInt("result_code", RESULT_ERROR)
                    result.putString("result_message", "未知 Action: ${intent.action}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "处理 Intent 失败: ${e.message}", e)
            result.putInt("result_code", RESULT_ERROR)
            result.putString("result_message", "错误：${e.message}")
        }
        return result
    }

    // ========== 各 Action 处理 ==========

    /**
     * 执行命令。
     *
     * 调用示例（显式 Intent）：
     *   val intent = Intent(context, TerminalIntentHandler::class.java)
     *   intent.action = ACTION_EXEC
     *   intent.putExtra(EXTRA_COMMAND, "python3 -c 'print(1+2)'")
     *   intent.putExtra(EXTRA_TIMEOUT, 14L)
     *   startActivityForResult(intent, 0)
     *
     * 调用示例（隐式 Intent）：
     *   val intent = Intent(ACTION_EXEC)
     *   intent.putExtra(EXTRA_COMMAND, "uname -a")
     *   sendBroadcast(intent)  // 或 startActivity(intent)
     */
    private fun handleExec(intent: Intent, result: Bundle) {
        val command = intent.getStringExtra(EXTRA_COMMAND)
        if (command.isNullOrBlank()) {
            result.putInt("result_code", RESULT_ERROR)
            result.putString("result_message", "错误：缺少 command 参数")
            return
        }

        val timeout = intent.getLongExtra(EXTRA_TIMEOUT, 14L)

        val execResult = try {
            QuroTerminalController.runCommand(command, timeout * 1000, context)
        } catch (e: Exception) {
            ShellResult(output = "", exitCode = -1, error = e.message ?: "未知错误")
        }

        result.putInt("result_code", if (execResult.exitCode == 0) RESULT_SUCCESS else RESULT_ERROR)
        result.putString("result_message", "Exit Code: ${execResult.exitCode}")
        result.putString("output", execResult.output)
        result.putString("error", execResult.error)
        result.putInt("exit_code", execResult.exitCode)
        result.putBoolean("timed_out", execResult.timedOut)
    }

    private fun handleStatus(result: Bundle) {
        val sessions = QuroTerminalSessionManager.listSessions()
        val sessionInfo = sessions.map { s ->
            Bundle().apply {
                putString("id", s.id)
                putString("name", s.name)
                putBoolean("alive", s.alive)
                putBoolean("is_default", s.isDefault)
            }
        }.toTypedArray()

        result.putInt("result_code", RESULT_SUCCESS)
        result.putString("result_message", "会话数量: ${sessions.size}")
        result.putParcelableArray("sessions", sessionInfo)
        result.putInt("session_count", sessions.size)
    }

    private fun handleSessions(result: Bundle) {
        handleStatus(result)
    }

    private fun handleCreateSession(intent: Intent, result: Bundle) {
        val name = intent.getStringExtra(EXTRA_SESSION_NAME)
            ?: "session_${System.currentTimeMillis()}"

        // 使用 runBlocking 调用 suspend 函数（Intent 处理不在协程中）
        val session = try {
            runBlocking {
                QuroTerminalSessionManager.createSession(context, name)
            }
        } catch (e: Exception) {
            Log.e(TAG, "创建会话失败: ${e.message}", e)
            null
        }

        if (session != null) {
            result.putInt("result_code", RESULT_SUCCESS)
            result.putString("result_message", "会话已创建")
            result.putString(EXTRA_SESSION_ID, session.id)
            result.putString(EXTRA_SESSION_NAME, session.name)
        } else {
            result.putInt("result_code", RESULT_ERROR)
            result.putString("result_message", "创建会话失败")
        }
    }

    private fun handleDestroySession(intent: Intent, result: Bundle) {
        val sessionId = intent.getStringExtra(EXTRA_SESSION_ID)
        if (sessionId.isNullOrBlank()) {
            result.putInt("result_code", RESULT_ERROR)
            result.putString("result_message", "错误：缺少 session_id 参数")
            return
        }

        // 使用 runBlocking 调用 suspend 函数
        val destroyed = try {
            runBlocking {
                QuroTerminalSessionManager.destroySession(sessionId)
            }
        } catch (e: Exception) {
            Log.e(TAG, "销毁会话失败: ${e.message}", e)
            false
        }

        result.putInt("result_code", if (destroyed) RESULT_SUCCESS else RESULT_ERROR)
        result.putString("result_message", if (destroyed) "会话已销毁" else "会话不存在或已销毁")
        result.putBoolean("destroyed", destroyed)
    }

    private fun handleSendInput(intent: Intent, result: Bundle) {
        val sessionId = intent.getStringExtra(EXTRA_SESSION_ID)
        val input = intent.getStringExtra(EXTRA_INPUT)
        if (sessionId.isNullOrBlank() || input.isNullOrBlank()) {
            result.putInt("result_code", RESULT_ERROR)
            result.putString("result_message", "错误：缺少 session_id 或 input 参数")
            return
        }

        val shell = QuroTerminalSessionManager.getShellSession(sessionId)
        if (shell == null) {
            result.putInt("result_code", RESULT_NO_SESSION)
            result.putString("result_message", "会话不存在: $sessionId")
            return
        }

        shell.sendRaw(input)
        result.putInt("result_code", RESULT_SUCCESS)
        result.putString("result_message", "输入已发送")
        result.putBoolean("sent", true)
    }

    private fun handleGetOutput(intent: Intent, result: Bundle) {
        val sessionId = intent.getStringExtra(EXTRA_SESSION_ID)
        val limit = intent.getIntExtra(EXTRA_OUTPUT_LIMIT, 100)

        if (sessionId.isNullOrBlank()) {
            result.putInt("result_code", RESULT_ERROR)
            result.putString("result_message", "错误：缺少 session_id 参数")
            return
        }

        val shell = QuroTerminalSessionManager.getShellSession(sessionId)
        if (shell == null) {
            result.putInt("result_code", RESULT_NO_SESSION)
            result.putString("result_message", "会话不存在: $sessionId")
            return
        }

        // 获取输出历史（取最后 limit 行）
        val allLines = shell.lines.toList()
        val output = if (allLines.size > limit) allLines.takeLast(limit) else allLines

        result.putInt("result_code", RESULT_SUCCESS)
        result.putString("result_message", "输出历史: ${output.size} 行")
        result.putStringArrayList("output_lines", ArrayList(output))
    }

    /**
     * ACTION_PICK 模式 — Intent + ContentProvider 协作。
     *
     * 这是 Android 官方推荐的跨应用数据访问模式：
     * 1. 调用方发送 ACTION_PICK Intent
     * 2. 终端应用启动 Picker Activity
     * 3. 用户选择会话
     * 4. 结果 Intent 携带 Content URI + FLAG_GRANT_READ_URI_PERMISSION
     * 5. 调用方通过 ContentResolver 读取数据
     *
     * 调用示例：
     *   val intent = Intent(ACTION_PICK_SESSION)
     *   intent.type = MIME_SESSION_LIST
     *   startActivityForResult(intent, REQUEST_PICK_SESSION)
     *
     * // 在 onActivityResult 中：
     * val sessionUri = data.data  // content://com.ai.assistance.quro.terminal/sessions/{id}
     * val cursor = contentResolver.query(sessionUri, null, null, null, null)
     */
    private fun handlePickSession(intent: Intent, result: Bundle) {
        // 返回终端会话列表的 Content URI
        val sessionsUri = Uri.parse("content://com.ai.assistance.quro.terminal/sessions")
        result.putInt("result_code", RESULT_SUCCESS)
        result.putString("result_message", "请通过 ContentResolver 查询会话列表")
        result.putParcelable("data", sessionsUri)
        result.putString("mime_type", MIME_SESSION_LIST)
    }

    /**
     * 处理 ACTION_SEND — 将文本发送到终端。
     *
     * 调用示例（隐式 Intent）：
     *   val intent = Intent(Intent.ACTION_SEND)
     *   intent.type = "text/plain"
     *   intent.putExtra(Intent.EXTRA_TEXT, "ls -la")
     *   intent.setPackage("com.ai.assistance.quro")
     *   startActivity(intent)
     */
    private fun handleSend(intent: Intent, result: Bundle) {
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)
        if (text.isNullOrBlank()) {
            result.putInt("result_code", RESULT_ERROR)
            result.putString("result_message", "错误：缺少文本内容")
            return
        }

        val shell = QuroTerminalSessionManager.defaultSession
        if (shell == null) {
            result.putInt("result_code", RESULT_NO_SESSION)
            result.putString("result_message", "没有可用的终端会话")
            return
        }

        shell.sendRaw(text)
        result.putInt("result_code", RESULT_SUCCESS)
        result.putString("result_message", "文本已发送: ${text.length} 字节")
        result.putBoolean("sent", true)
    }

    /**
     * 处理 ACTION_VIEW — 打开终端会话。
     *
     * 调用示例：
     *   val intent = Intent(Intent.ACTION_VIEW,
     *       Uri.parse("quro://terminal/exec?cmd=ls -la"))
     *   startActivity(intent)
     */
    private fun handleView(intent: Intent, result: Bundle) {
        val uri = intent.data
        if (uri == null) {
            result.putInt("result_code", RESULT_ERROR)
            result.putString("result_message", "错误：缺少 URI")
            return
        }

        when (uri.pathSegments.firstOrNull()) {
            "exec" -> {
                val cmd = uri.getQueryParameter("cmd")
                if (cmd != null) {
                    val execIntent = Intent(ACTION_EXEC)
                    execIntent.putExtra(EXTRA_COMMAND, cmd)
                    handleExec(execIntent, result)
                } else {
                    result.putInt("result_code", RESULT_ERROR)
                    result.putString("result_message", "错误：缺少 cmd 参数")
                }
            }
            "sessions" -> handleSessions(result)
            "status" -> handleStatus(result)
            else -> {
                result.putInt("result_code", RESULT_ERROR)
                result.putString("result_message", "未知路径: ${uri.pathSegments}")
            }
        }
    }

    // ========== Intent 构建工具方法 ==========

    /**
     * 创建显式 Intent — 启动终端执行命令。
     *
     * 显式 Intent 直接指定目标组件，安全可靠。
     * Android 5.0+ 要求 Service 绑定必须使用显式 Intent。
     */
    fun createExecIntent(command: String, timeout: Long = 14L): Intent {
        return Intent(context, TerminalIntentHandler::class.java).apply {
            action = ACTION_EXEC
            component = ComponentName(
                context.packageName,
                TerminalIntentHandler::class.java.name
            )
            putExtra(EXTRA_COMMAND, command)
            putExtra(EXTRA_TIMEOUT, timeout)
        }
    }

    /**
     * 创建隐式 Intent — 用于 sendBroadcast 或系统匹配。
     *
     * 隐式 Intent 不指定目标组件，由系统根据 Intent Filter 匹配。
     * 适用于 Activity 和 Broadcast，不适用于 Service 绑定。
     */
    fun createImplicitExecIntent(command: String, timeout: Long = 14L): Intent {
        return Intent(ACTION_EXEC).apply {
            putExtra(EXTRA_COMMAND, command)
            putExtra(EXTRA_TIMEOUT, timeout)
            // 可选：限制目标包名
            // `package` = "com.ai.assistance.quro"
        }
    }

    /**
     * 创建有序广播 Intent。
     *
     * 有序广播按优先级依次传递，每个接收器可以：
     * - 修改结果（setResultCode / setResultData）
     * - 中止传播（abortBroadcast）
     *
     * 调用示例：
     *   val intent = createOrderedExecIntent("ls -la")
     *   sendOrderedBroadcast(intent, "ai.aci.permission.CALL")
     */
    fun createOrderedExecIntent(command: String, timeout: Long = 14L): Intent {
        return Intent(ACTION_EXEC).apply {
            putExtra(EXTRA_COMMAND, command)
            putExtra(EXTRA_TIMEOUT, timeout)
        }
    }

    /**
     * 创建 ACTION_PICK Intent — 用于选择终端会话。
     *
     * ACTION_PICK + ContentProvider 是 Android 官方推荐的跨应用数据访问模式：
     * 1. 发送 ACTION_PICK Intent
     * 2. 系统启动终端的 Picker Activity
     * 3. 用户选择会话
     * 4. 结果 Intent 携带 Content URI + 临时读权限
     * 5. 调用方通过 ContentResolver 读取数据
     */
    fun createPickSessionIntent(): Intent {
        return Intent(ACTION_PICK_SESSION).apply {
            type = MIME_SESSION_LIST
            component = ComponentName(
                context.packageName,
                TerminalIntentHandler::class.java.name
            )
        }
    }

    fun createStatusIntent(): Intent {
        return Intent(context, TerminalIntentHandler::class.java).apply {
            action = ACTION_STATUS
        }
    }

    fun createSessionsIntent(): Intent {
        return Intent(context, TerminalIntentHandler::class.java).apply {
            action = ACTION_SESSIONS
        }
    }

    fun createSendIntent(text: String): Intent {
        return Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            `package` = context.packageName
        }
    }

    /**
     * 创建 ACTION_VIEW Intent — 通过 Deep Link 打开终端。
     *
     * 调用示例：
     *   val intent = createViewIntent("exec", "ls -la")
     *   startActivity(intent)
     */
    fun createViewIntent(path: String, query: String? = null): Intent {
        val uriBuilder = Uri.Builder()
            .scheme("quro")
            .authority("terminal")
            .appendPath(path)
        if (query != null) {
            uriBuilder.appendQueryParameter("cmd", query)
        }
        return Intent(Intent.ACTION_VIEW, uriBuilder.build())
    }
}
