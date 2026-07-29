package com.ai.assistance.quro.core.novaterm.executor

import com.ai.assistance.quro.core.novaterm.command.CommandResult
import com.ai.assistance.quro.core.novaterm.command.CommandDispatcher
import com.ai.assistance.quro.core.novaterm.core.SessionManager
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * 沙盒执行器
 * 在独立协程中执行命令，支持超时、取消、并发限制
 */
class SandboxExecutor(
    private val sessionId: String,
    private val maxConcurrent: Int = 4,
    private val defaultTimeoutMs: Long = 30000
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val runningJobs = ConcurrentLinkedQueue<Job>()
    private val outputBuffer = StringBuilder()

    /**
     * 异步执行命令
     */
    fun execute(
        input: String,
        onResult: (CommandResult) -> Unit,
        onUpdate: ((String) -> Unit)? = null
    ): Job {
        val job = scope.launch {
            try {
                // 记录历史
                SessionManager.addHistory(sessionId, input)

                // 别名展开
                val expanded = SessionManager.resolveAlias(sessionId, input)

                // 执行（在 IO 线程）
                val result = withContext(Dispatchers.IO) {
                    CommandDispatcher.execute(sessionId, expanded)
                }

                // 处理输出
                when (result) {
                    is CommandResult.Text -> {
                        outputBuffer.append(result.output)
                        if (result.isError) {
                            onResult(CommandResult.err(result.output))
                        } else {
                            onResult(result)
                        }
                    }
                    else -> onResult(result)
                }
            } catch (e: TimeoutCancellationException) {
                onResult(CommandResult.err("Command timed out after ${defaultTimeoutMs}ms"))
            } catch (e: CancellationException) {
                onResult(CommandResult.err("Command cancelled"))
            } catch (e: Exception) {
                onResult(CommandResult.err("Execution error: ${e.message}"))
            }
        }

        runningJobs.add(job)
        job.invokeOnCompletion { runningJobs.remove(job) }
        return job
    }

    /**
     * 执行并等待结果（同步风格）。带超时，避免命令挂死（如 ping 无计数 / 死循环）
     * 永久阻塞调用方协程（QuroTermTool 在 ask() 的 IO 线程上 runBlocking 等待本方法，
     * 一旦无超时，整条对话循环卡死 → 用户感知「Zorv AI 没有响应」）。
     */
    suspend fun executeBlocking(input: String): CommandResult = withContext(Dispatchers.IO) {
        try {
            withTimeout(defaultTimeoutMs) {
                SessionManager.addHistory(sessionId, input)
                val expanded = SessionManager.resolveAlias(sessionId, input)
                CommandDispatcher.execute(sessionId, expanded)
            }
        } catch (e: TimeoutCancellationException) {
            CommandResult.err("命令执行超时（>${defaultTimeoutMs}ms），已被强制终止：${e.message}")
        }
    }

    fun cancelAll() {
        runningJobs.forEach { it.cancel() }
        runningJobs.clear()
    }

    fun getBufferedOutput(): String = outputBuffer.toString()

    fun clearBuffer() {
        outputBuffer.clear()
    }

    fun shutdown() {
        cancelAll()
        scope.cancel()
    }
}
