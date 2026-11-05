package com.ai.assistance.quro.core.mika.remote

import com.ai.assistance.quro.core.mika.agent.TaskState
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** 电脑交来的一个任务:[state] 为空表示已经交给任务服务、还没登记上(马上就会有)。 */
data class RemoteTask(
    val id: String,
    val conversation: String,
    val goal: String,
    val createdAtMs: Long,
    val state: TaskState?,
    val steers: List<RemoteSteer> = emptyList(),
)

/** 只报告电脑追加的消息;joined 表示进入当前任务上下文,不是执行成功。 */
data class RemoteSteer(val messageId: String, val state: String)

sealed interface StartOutcome {
    data class Started(val task: RemoteTask) : StartOutcome
    data class Refused(val message: String) : StartOutcome
}

/**
 * App 一侧给电脑连接的任务出入口:任务照旧经任务服务开始、在对话里留记录,状态读任务自己的,这里不另存一份。
 * 只认电脑交来的任务(按 [RemoteTask.id]),手机上发起的任务看不到也动不了。
 */
interface RemoteTasks {
    fun start(goal: String, conversation: String?): StartOutcome
    fun get(id: String): RemoteTask?
    /** 新的在前。 */
    fun list(limit: Int): List<RemoteTask>
    /** 只在任务正等着回答追问时调用;交给任务时返回 true。 */
    fun answer(id: String, text: String): Boolean
    /** 原子确认消息进入当前任务的收件箱;不能转成新任务。返回消息编号,拒绝为 null。 */
    fun steer(id: String, text: String): String?
    fun cancel(id: String): Boolean
}

/** 任务状态对电脑的说法;只读任务自己的状态,不另记。 */
object RemoteStatus {
    const val STARTING = "starting"
    const val RUNNING = "running"
    const val QUEUED = "queued"
    const val PAUSED = "paused"
    const val WAITING_FOR_ANSWER = "waiting_for_answer"
    const val WAITING_ON_PHONE = "waiting_on_phone"
    const val DONE = "done"
    const val FAILED = "failed"
    const val CANCELLED = "cancelled"

    const val ON_PHONE_NOTE = "This step needs the user on the phone and cannot be answered remotely. " +
        "ZorvAI has shown its usual notification there; keep waiting or cancel the task."

    fun of(state: TaskState?): String = when (state) {
        null, TaskState.Idle -> STARTING
        is TaskState.Running -> RUNNING
        is TaskState.Held -> when {
            state.reason.needsYou -> WAITING_ON_PHONE
            state.reason.queued -> QUEUED
            else -> PAUSED
        }
        is TaskState.WaitingInput -> WAITING_FOR_ANSWER
        is TaskState.WaitingConfirmation, is TaskState.WaitingTakeover -> WAITING_ON_PHONE
        is TaskState.Succeeded -> DONE
        is TaskState.Failed -> FAILED
        TaskState.Cancelled -> CANCELLED
    }

    fun finished(status: String) = status == DONE || status == FAILED || status == CANCELLED

    /** 一个任务此刻的样子:状态、最近的进度说明、等什么、结果。 */
    fun describe(t: RemoteTask): JsonObject = buildJsonObject {
        val s = t.state
        val status = of(s)
        put("task_id", t.id)
        put("status", status)
        put("conversation", t.conversation)
        put("goal", t.goal)
        put("started_at_ms", t.createdAtMs)
        when (s) {
            is TaskState.Running -> {
                put("step", s.step)
                (s.narration ?: s.reason ?: s.note).takeIf { it.isNotBlank() }?.let { put("progress", it) }
            }
            is TaskState.Held -> {
                put("waiting_for", s.reason.code)
                s.reason.why.takeIf { it.isNotBlank() }?.let { put("progress", it) }
                if (s.reason.needsYou) put("note", ON_PHONE_NOTE)
            }
            is TaskState.WaitingInput -> {
                put("question", s.prompt)
                s.options?.takeIf { it.isNotEmpty() }?.let { o -> putJsonArray("options") { o.forEach { add(it) } } }
                put("note", "ZorvAI is asking a question. Answer this question remotely or on the phone.")
            }
            is TaskState.WaitingConfirmation -> {
                put("waiting_for", "confirmation")
                put("app", s.request.app)
                put("note", ON_PHONE_NOTE)
            }
            is TaskState.WaitingTakeover -> {
                put("waiting_for", "hand_to_user")
                put("reason", s.reason)
                put("message", s.message)
                put("note", ON_PHONE_NOTE)
            }
            is TaskState.Succeeded -> {
                put("answer", s.answer)
                if (s.reviewed) put("verified", s.verified)
                s.note?.let { put("verification_note", it) }
                if (s.unconfirmed.isNotEmpty()) putJsonArray("unconfirmed") { s.unconfirmed.forEach { add(it) } }
            }
            is TaskState.Failed -> {
                put("reason", s.reason)
                put("failure_kind", s.kind.name.lowercase())
            }
            null, TaskState.Idle, TaskState.Cancelled -> Unit
        }
        put("answerable", s is TaskState.WaitingInput)
        if (t.steers.isNotEmpty()) putJsonArray("steers") {
            t.steers.forEach { add(buildJsonObject { put("message_id", it.messageId); put("state", it.state) }) }
        }
    }
}
