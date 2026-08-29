package com.ai.assistance.quro.core.terminal

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Bundle
import android.util.Log
import kotlinx.coroutines.runBlocking

/**
 * 终端广播接收器 — 符合 Android 标准的广播处理。
 *
 * 广播是 Android 四大组件之一，用于：
 * 1. 一对多通知：一个发送者可以同时通知多个接收者
 * 2. 异步通信：发送后不等待接收者处理完成
 * 3. 系统事件通知：开机完成、电量变化等
 *
 * 广播类型：
 * - 普通广播（sendBroadcast）：异步发送，所有接收器同时收到，无顺序
 * - 有序广播（sendOrderedBroadcast）：按优先级依次传递，接收器可修改结果、中止传播
 * - 本地广播（LocalBroadcastManager）：仅应用内传播，更安全高效
 *
 * 权限控制：
 * - 发送方权限：发送广播时指定接收方必须拥有的权限
 * - 接收方权限：接收广播时检查发送方是否拥有指定权限
 *
 * 支持的广播 Action：
 * - TERMINAL_EXEC     → 执行命令（有序广播，支持结果传播）
 * - TERMINAL_STATUS   → 获取状态
 * - TERMINAL_SESSIONS → 列出会话
 * - TERMINAL_CREATE_SESSION → 创建会话
 * - TERMINAL_DESTROY_SESSION → 销毁会话
 * - TERMINAL_SEND_INPUT      → 发送输入
 * - TERMINAL_GET_OUTPUT      → 获取输出历史
 * - TERMINAL_RESULT          → 结果回调（由接收器发送）
 *
 * 调用示例（普通广播）：
 *   val intent = Intent("com.ai.assistance.quro.action.TERMINAL_EXEC")
 *   intent.putExtra("command", "ls -la")
 *   sendBroadcast(intent)
 *
 * 调用示例（有序广播）：
 *   val intent = Intent("com.ai.assistance.quro.action.TERMINAL_EXEC")
 *   intent.putExtra("command", "uname -a")
 *   sendOrderedBroadcast(intent, "ai.aci.permission.CALL")
 *
 * 调用示例（带权限的广播）：
 *   val intent = Intent("com.ai.assistance.quro.action.TERMINAL_EXEC")
 *   intent.putExtra("command", "whoami")
 *   sendBroadcast(intent, "ai.aci.permission.CALL")  // 仅拥有此权限的接收器能收到
 */
class TerminalBroadcastReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "TerminalBcastReceiver"

        // ========== Action 定义 ==========
        const val ACTION_EXEC = "com.ai.assistance.quro.action.TERMINAL_EXEC"
        const val ACTION_STATUS = "com.ai.assistance.quro.action.TERMINAL_STATUS"
        const val ACTION_SESSIONS = "com.ai.assistance.quro.action.TERMINAL_SESSIONS"
        const val ACTION_CREATE_SESSION = "com.ai.assistance.quro.action.TERMINAL_CREATE_SESSION"
        const val ACTION_DESTROY_SESSION = "com.ai.assistance.quro.action.TERMINAL_DESTROY_SESSION"
        const val ACTION_SEND_INPUT = "com.ai.assistance.quro.action.TERMINAL_SEND_INPUT"
        const val ACTION_GET_OUTPUT = "com.ai.assistance.quro.action.TERMINAL_GET_OUTPUT"

        // ========== Result Action ==========
        const val ACTION_RESULT = "com.ai.assistance.quro.action.TERMINAL_RESULT"

        // ========== Extra Key ==========
        const val EXTRA_COMMAND = "command"
        const val EXTRA_TIMEOUT = "timeout"
        const val EXTRA_SESSION_ID = "session_id"
        const val EXTRA_SESSION_NAME = "session_name"
        const val EXTRA_INPUT = "input"
        const val EXTRA_OUTPUT_LIMIT = "output_limit"

        // ========== Result Key ==========
        const val RESULT_CODE = "result_code"
        const val RESULT_OUTPUT = "result_output"
        const val RESULT_ERROR = "result_error"
        const val RESULT_EXIT_CODE = "exit_code"
        const val RESULT_TIMED_OUT = "timed_out"

        // ========== Permission ==========
        const val PERMISSION_SEND = "ai.aci.permission.SEND_TERMINAL_BROADCAST"
        const val PERMISSION_RECEIVE = "ai.aci.permission.RECEIVE_TERMINAL_BROADCAST"
    }

    /**
     * 接收广播。
     *
     * 使用 goAsync() 延长处理时间（最多 10 秒），
     * 避免主线程阻塞。
     */
    override fun onReceive(context: Context, intent: Intent) {
        val callingUid = Binder.getCallingUid()
        Log.d(TAG, "收到广播: ${intent.action} (from uid=$callingUid)")

        // 发送方权限检查（简化：应用内调用均允许）
        // 实际生产环境应检查 callingUid 对应的包名权限

        val pendingResult = goAsync()

        Thread {
            try {
                val resultBundle = Bundle()

                when (intent.action) {
                    ACTION_EXEC -> handleExec(context, intent, resultBundle)
                    ACTION_STATUS -> handleStatus(resultBundle)
                    ACTION_SESSIONS -> handleSessions(resultBundle)
                    ACTION_CREATE_SESSION -> handleCreateSession(context, intent, resultBundle)
                    ACTION_DESTROY_SESSION -> handleDestroySession(intent, resultBundle)
                    ACTION_SEND_INPUT -> handleSendInput(intent, resultBundle)
                    ACTION_GET_OUTPUT -> handleGetOutput(intent, resultBundle)
                    else -> {
                        resultBundle.putInt(RESULT_CODE, -1)
                        resultBundle.putString(RESULT_ERROR, "未知 Action: ${intent.action}")
                    }
                }

                // 发送结果
                sendResult(context, intent, resultBundle)

                // 有序广播：设置结果供下一个接收器使用
                if (isOrderedBroadcast) {
                    resultCode = resultBundle.getInt(RESULT_CODE, -1)
                    resultData = resultBundle.getString(RESULT_OUTPUT, "")
                    val resultExtras = Bundle(resultBundle).apply {
                        remove(RESULT_CODE)
                        remove(RESULT_OUTPUT)
                        remove(RESULT_ERROR)
                    }
                    setResultExtras(resultExtras)
                }

            } catch (e: Exception) {
                Log.e(TAG, "处理广播失败: ${e.message}", e)
                val errorBundle = Bundle().apply {
                    putInt(RESULT_CODE, -1)
                    putString(RESULT_ERROR, "错误：${e.message}")
                }
                sendResult(context, intent, errorBundle)

                if (isOrderedBroadcast) {
                    resultCode = -1
                    resultData = "错误：${e.message}"
                }
            } finally {
                pendingResult.finish()
            }
        }.start()
    }

    // ========== 各 Action 处理 ==========

    private fun handleExec(context: Context, intent: Intent, result: Bundle) {
        val command = intent.getStringExtra(EXTRA_COMMAND)
        if (command.isNullOrBlank()) {
            result.putInt(RESULT_CODE, -1)
            result.putString(RESULT_ERROR, "错误：缺少 command 参数")
            return
        }

        val timeout = intent.getLongExtra(EXTRA_TIMEOUT, 14L)
        val execResult = try {
            QuroTerminalController.runCommand(command, timeout * 1000, context)
        } catch (e: Exception) {
            ShellResult(output = "", exitCode = -1, error = e.message ?: "未知错误")
        }

        result.putInt(RESULT_CODE, execResult.exitCode)
        result.putString(RESULT_OUTPUT, execResult.output)
        result.putString(RESULT_ERROR, execResult.error)
        result.putInt(RESULT_EXIT_CODE, execResult.exitCode)
        result.putBoolean(RESULT_TIMED_OUT, execResult.timedOut)
    }

    private fun handleStatus(result: Bundle) {
        val sessions = QuroTerminalSessionManager.listSessions()
        val sessionInfo = sessions.joinToString("\n") { s ->
            "- ${s.id} (${s.name}) [${if (s.alive) "存活" else "已退出"}]"
        }

        result.putInt(RESULT_CODE, 0)
        result.putString(RESULT_OUTPUT, "会话数量: ${sessions.size}\n$sessionInfo")
        result.putInt("session_count", sessions.size)
    }

    private fun handleSessions(result: Bundle) {
        handleStatus(result)
    }

    private fun handleCreateSession(context: Context, intent: Intent, result: Bundle) {
        val name = intent.getStringExtra(EXTRA_SESSION_NAME)
            ?: "session_${System.currentTimeMillis()}"

        // 使用 runBlocking 调用 suspend 函数（BroadcastReceiver 不在协程中）
        val session = try {
            runBlocking {
                QuroTerminalSessionManager.createSession(context, name)
            }
        } catch (e: Exception) {
            Log.e(TAG, "创建会话失败: ${e.message}", e)
            null
        }

        if (session != null) {
            result.putInt(RESULT_CODE, 0)
            result.putString(RESULT_OUTPUT, "会话已创建: ${session.id}")
            result.putString(EXTRA_SESSION_ID, session.id)
        } else {
            result.putInt(RESULT_CODE, -1)
            result.putString(RESULT_ERROR, "创建会话失败")
        }
    }

    private fun handleDestroySession(intent: Intent, result: Bundle) {
        val sessionId = intent.getStringExtra(EXTRA_SESSION_ID)
        if (sessionId.isNullOrBlank()) {
            result.putInt(RESULT_CODE, -1)
            result.putString(RESULT_ERROR, "错误：缺少 session_id 参数")
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

        result.putInt(RESULT_CODE, if (destroyed) 0 else -1)
        result.putString(RESULT_OUTPUT, if (destroyed) "会话已销毁" else "会话不存在")
        result.putBoolean("destroyed", destroyed)
    }

    private fun handleSendInput(intent: Intent, result: Bundle) {
        val sessionId = intent.getStringExtra(EXTRA_SESSION_ID)
        val input = intent.getStringExtra(EXTRA_INPUT)
        if (sessionId.isNullOrBlank() || input.isNullOrBlank()) {
            result.putInt(RESULT_CODE, -1)
            result.putString(RESULT_ERROR, "错误：缺少 session_id 或 input 参数")
            return
        }

        val shell = QuroTerminalSessionManager.getShellSession(sessionId)
        if (shell == null) {
            result.putInt(RESULT_CODE, -1)
            result.putString(RESULT_ERROR, "会话不存在: $sessionId")
            return
        }

        shell.sendRaw(input)
        result.putInt(RESULT_CODE, 0)
        result.putString(RESULT_OUTPUT, "输入已发送")
        result.putBoolean("sent", true)
    }

    private fun handleGetOutput(intent: Intent, result: Bundle) {
        val sessionId = intent.getStringExtra(EXTRA_SESSION_ID)
        val limit = intent.getIntExtra(EXTRA_OUTPUT_LIMIT, 100)

        if (sessionId.isNullOrBlank()) {
            result.putInt(RESULT_CODE, -1)
            result.putString(RESULT_ERROR, "错误：缺少 session_id 参数")
            return
        }

        val shell = QuroTerminalSessionManager.getShellSession(sessionId)
        if (shell == null) {
            result.putInt(RESULT_CODE, -1)
            result.putString(RESULT_ERROR, "会话不存在: $sessionId")
            return
        }

        // 获取输出历史（取最后 limit 行）
        val allLines = shell.lines.toList()
        val output = if (allLines.size > limit) allLines.takeLast(limit) else allLines

        result.putInt(RESULT_CODE, 0)
        result.putString(RESULT_OUTPUT, output.joinToString("\n"))
        result.putStringArrayList("output_lines", ArrayList(output))
    }

    /**
     * 发送结果广播。
     *
     * 结果广播包含：
     * - result_code: 操作结果码（0 = 成功，-1 = 失败）
     * - result_output: 操作输出
     * - result_error: 错误信息
     * - exit_code: 命令退出码（仅 exec 操作）
     * - timed_out: 是否超时（仅 exec 操作）
     */
    private fun sendResult(context: Context, originalIntent: Intent, result: Bundle) {
        val resultIntent = Intent(ACTION_RESULT).apply {
            // 显式指定结果接收方（如果原 Intent 指定了 package）
            if (originalIntent.`package` != null) {
                `package` = originalIntent.`package`
            } else {
                `package` = context.packageName
            }
            // 附加结果数据
            putExtras(result)
        }

        // 发送结果广播（带权限控制）
        context.sendBroadcast(resultIntent, PERMISSION_RECEIVE)
    }

    /**
     * 结果广播接收器 — 接收操作结果。
     *
     * 调用方可以通过注册此接收器来获取终端操作的结果：
     *   val receiver = TerminalBroadcastReceiver.ResultReceiver { resultCode, data ->
     *       val output = data?.getString("result_output")
     *       val exitCode = data?.getInt("exit_code", -1)
     *       // 处理结果
     *   }
     *   registerReceiver(receiver, IntentFilter(TerminalBroadcastReceiver.ACTION_RESULT))
     */
    class ResultReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == ACTION_RESULT) {
                val resultCode = resultCode
                val output = intent.getStringExtra(RESULT_OUTPUT)
                val error = intent.getStringExtra(RESULT_ERROR)
                val exitCode = intent.getIntExtra(RESULT_EXIT_CODE, -1)
                val timedOut = intent.getBooleanExtra(RESULT_TIMED_OUT, false)

                Log.d(TAG, "收到结果: code=$resultCode, exit=$exitCode, timedOut=$timedOut")
                Log.d(TAG, "output: ${output?.take(200)}")
                if (!error.isNullOrBlank()) {
                    Log.w(TAG, "error: $error")
                }
            }
        }
    }
}
