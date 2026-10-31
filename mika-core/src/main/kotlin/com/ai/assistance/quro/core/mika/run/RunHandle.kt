package com.ai.assistance.quro.core.mika.run

import com.ai.assistance.quro.core.mika.agent.AgentLoop
import com.ai.assistance.quro.core.mika.agent.TaskState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** 一次已提交运行的控制面;取消可以直接发,用户文字经 Inbox 进入。 */
class RunHandle internal constructor(
    val spec: RunSpec,
    private val inbox: RunInbox,
    val events: RunEventBus,
    private val phaseState: MutableStateFlow<RunPhase>,
    private val completion: CompletableDeferred<TaskState>,
) {
    val id: RunId get() = spec.id
    val phase: StateFlow<RunPhase> get() = phaseState

    suspend fun send(input: RunInput): InputReceipt = inbox.send(input)
    fun cancel(): InputReceipt = inbox.cancel()
    suspend fun await(): TaskState = completion.await()
    fun nextTaskMessages(): List<RunInbox.UserSteer> = inbox.nextTaskMessages()
}

/** S1 的直接运行入口。S3 的 TaskCenter 将决定何时启动它。 */
fun AgentLoop.start(scope: CoroutineScope, spec: RunSpec): RunHandle {
    val inbox = RunInbox()
    val phase = MutableStateFlow<RunPhase>(RunPhase.Queued)
    val done = CompletableDeferred<TaskState>()
    val handle = RunHandle(spec, inbox, events, phase, done)
    scope.launch {
        phase.value = RunPhase.Preparing
        val watch: Job = launch {
            state.collect { current ->
                phase.value = when (current) {
                    TaskState.Idle -> RunPhase.Preparing
                    is TaskState.Running -> RunPhase.Running
                    is TaskState.WaitingInput, is TaskState.WaitingConfirmation, is TaskState.WaitingTakeover -> RunPhase.WaitingUser(activeAsk?.askId)
                    is TaskState.Held -> RunPhase.Paused(current.reason.code)
                    is TaskState.Succeeded, is TaskState.Failed, TaskState.Cancelled -> RunPhase.Finishing
                }
            }
        }
        try {
            phase.value = RunPhase.Running
            done.complete(run(spec, inbox))
            phase.value = RunPhase.Finishing
        } catch (e: CancellationException) {
            if (inbox.wasCancelled) {
                done.complete(TaskState.Cancelled)
                phase.value = RunPhase.Finishing
            } else {
                done.completeExceptionally(e)
                phase.value = RunPhase.Interrupted
            }
        } catch (e: Throwable) {
            done.completeExceptionally(e)
            phase.value = RunPhase.Interrupted
        } finally {
            watch.cancel()
            if (phase.value != RunPhase.Interrupted) phase.value = RunPhase.Ended
        }
    }
    return handle
}
