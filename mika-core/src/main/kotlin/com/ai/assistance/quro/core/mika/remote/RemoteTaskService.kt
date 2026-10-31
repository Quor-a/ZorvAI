package com.ai.assistance.quro.core.mika.remote

import kotlinx.coroutines.delay
import java.util.UUID

/**
 * 远程入口共用的业务错误;HTTP 状态和 MCP isError 由各自适配器决定。
 * 要按入口换说法的几种情形各是一个子类,适配器按类型换,不去比对 message 的文字。
 */
sealed class RemoteTaskError(val code: Code, message: String) : Exception(message) {
    enum class Code { INVALID_ARGUMENT, NOT_FOUND, CONFLICT, UNAVAILABLE }

    /** 其余错误:message 原样给调用方。 */
    class General(code: Code, message: String) : RemoteTaskError(code, message)
    class NoSuchTask(val id: String) : RemoteTaskError(Code.NOT_FOUND, "No remote task with id $id")
    class BadConversation : RemoteTaskError(Code.INVALID_ARGUMENT,
        "conversation must be one returned by an earlier remote task (looks like mcp-<uuid>)")
    /** 这一步要用户在手机上处理,不能远程回答。 */
    class NeedsPhone : RemoteTaskError(Code.CONFLICT, RemoteStatus.ON_PHONE_NOTE)
    /** 查到时还在问,交回答时问题已经没了。 */
    class QuestionClosed : RemoteTaskError(Code.CONFLICT, "The question is no longer open; check the task's status.")
}

data class RemoteWait(val task: RemoteTask, val changed: Boolean)
data class RemoteCancellation(val taskId: String, val accepted: Boolean, val status: String? = null)

/**
 * CLI API 与 MCP 共用的任务用例。没有 HTTP、JSON-RPC、工具名或命令行参数。
 * 只经 [RemoteTasks] 接入手机上原有的任务服务,不创建另一份执行器或任务状态。
 */
class RemoteTaskService(private val tasks: RemoteTasks, private val pollMs: Long = 250) {
    init { require(pollMs > 0) }

    fun start(goal: String, conversation: String? = null): RemoteTask {
        val clean = required(goal, "goal", MAX_GOAL)
        val thread = conversation?.trim()?.takeIf { it.isNotEmpty() }
        if (thread != null && !isConversation(thread)) throw RemoteTaskError.BadConversation()
        return when (val r = tasks.start(clean, thread)) {
            is StartOutcome.Started -> r.task
            is StartOutcome.Refused -> throw RemoteTaskError.General(RemoteTaskError.Code.UNAVAILABLE, r.message)
        }
    }

    fun get(id: String): RemoteTask {
        val clean = id.trim()
        if (clean.isEmpty()) invalid("task_id is required and must be a string")
        return tasks.get(clean) ?: throw RemoteTaskError.NoSuchTask(clean)
    }

    fun list(limit: Int = DEFAULT_LIST): List<RemoteTask> {
        if (limit !in 1..MAX_LIST) invalid("limit must be between 1 and $MAX_LIST")
        return tasks.list(limit)
    }

    /** 一次有界等待,状态变了或终止就返回;等待超时不取消手机任务。等待中任务查不到了,就返回最后看到的状态。 */
    suspend fun wait(id: String, timeoutS: Int = DEFAULT_WAIT_S): RemoteWait {
        val first = get(id)
        if (timeoutS !in 1..MAX_WAIT_S) invalid("timeout_s must be between 1 and $MAX_WAIT_S")
        val from = RemoteStatus.of(first.state)
        var now = first
        var waited = 0L
        while (!RemoteStatus.finished(RemoteStatus.of(now.state)) && RemoteStatus.of(now.state) == from && waited < timeoutS * 1000L) {
            val interval = minOf(pollMs, timeoutS * 1000L - waited)
            delay(interval)
            waited += interval
            now = tasks.get(first.id) ?: break
        }
        return RemoteWait(now, RemoteStatus.of(now.state) != from)
    }

    /** 只能回答追问,不能把付款确认或手机接管当作普通回答。 */
    fun answer(id: String, text: String): String {
        val task = get(id)
        val clean = required(text, "text", MAX_ANSWER)
        when (RemoteStatus.of(task.state)) {
            RemoteStatus.WAITING_FOR_ANSWER -> Unit
            RemoteStatus.WAITING_ON_PHONE -> throw RemoteTaskError.NeedsPhone()
            else -> conflict("Task ${task.id} is not waiting for an answer (status ${RemoteStatus.of(task.state)}).")
        }
        if (!tasks.answer(task.id, clean)) throw RemoteTaskError.QuestionClosed()
        return task.id
    }

    /** accepted 只表示已交给任务服务,是否到 cancelled 终态仍以状态查询为准。 */
    fun cancel(id: String): RemoteCancellation {
        val task = get(id)
        val status = RemoteStatus.of(task.state)
        return if (RemoteStatus.finished(status)) RemoteCancellation(task.id, false, status)
        else RemoteCancellation(task.id, tasks.cancel(task.id))
    }

    private fun required(value: String, name: String, max: Int): String {
        val clean = value.trim()
        if (clean.isEmpty()) invalid("$name is required and must be a non-empty string")
        if (clean.length > max) invalid("$name is too long (at most $max characters)")
        return clean
    }

    private fun invalid(message: String): Nothing = throw RemoteTaskError.General(RemoteTaskError.Code.INVALID_ARGUMENT, message)
    private fun conflict(message: String): Nothing = throw RemoteTaskError.General(RemoteTaskError.Code.CONFLICT, message)

    companion object {
        const val DEFAULT_WAIT_S = 30
        const val MAX_WAIT_S = 50
        const val DEFAULT_LIST = 10
        const val MAX_LIST = 50
        const val MAX_GOAL = 4_000
        const val MAX_ANSWER = 2_000
        // 保留已发出的对话编号,让新 CLI 与原 MCP 可以继续同一个远程对话。
        private const val CONVERSATION_PREFIX = "mcp-"
        fun newConversation(): String = CONVERSATION_PREFIX + UUID.randomUUID()
        fun isConversation(id: String): Boolean = id.startsWith(CONVERSATION_PREFIX) &&
            id.removePrefix(CONVERSATION_PREFIX).let { raw -> runCatching { UUID.fromString(raw).toString() == raw }.getOrDefault(false) }
    }
}
