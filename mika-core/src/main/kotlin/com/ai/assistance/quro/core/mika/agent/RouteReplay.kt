package com.ai.assistance.quro.core.mika.agent

import com.ai.assistance.quro.core.mika.observation.Place
import com.ai.assistance.quro.core.mika.observation.tag
import com.ai.assistance.quro.core.mika.action.ActionExecutor
import com.ai.assistance.quro.core.mika.model.Author
import com.ai.assistance.quro.core.mika.model.Message
import com.ai.assistance.quro.core.mika.model.Part
import com.ai.assistance.quro.core.mika.model.Role
import com.ai.assistance.quro.core.mika.prompt.Feedback
import com.ai.assistance.quro.core.mika.record.RunEvent
import com.ai.assistance.quro.core.mika.record.RunSink
import com.ai.assistance.quro.core.mika.replay.ReplayOutcome
import com.ai.assistance.quro.core.mika.replay.Replayer
import com.ai.assistance.quro.core.mika.replay.Route
import com.ai.assistance.quro.core.mika.replay.RouteMatcher
import com.ai.assistance.quro.core.mika.tool.ToolDispatcher
import com.ai.assistance.quro.core.mika.tool.Verb
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

private val REPLAY_SOURCE: String get() = Feedback["replay_source"]
private val REPLAY_SCREEN_HEADER: String get() = Feedback["replay_screen_header"]
private val REPLAY_NO_SCREEN: String get() = Feedback["replay_no_screen"]

/**
 * 经验回放:第一次模型调用之前,有合适的路线就先照着做,做完或对不上都交还模型;任务结束后按结局更新路线的计数。
 * 路线出了问题(匹配、读写路线)都不影响任务。每个任务开始时 [reset]。
 */
internal class RouteReplay(
    private val routes: RouteMatcher,
    private val tools: ToolDispatcher,
    private val gate: suspend () -> Unit,
    /** 对外显示回放进度(也是关卡等完之后恢复的状态)。 */
    private val show: (TaskState.Running) -> Unit,
    private val emit: (RunEvent) -> Unit,
    private val clock: () -> Long,
) {
    /** 本次任务回放过的路线与结果。 */
    private var replayed: Pair<Route, ReplayOutcome>? = null

    fun reset() { replayed = null }

    /** 交还时读屏用最近操作的那块屏的读元素工具(回放的路线落在哪块屏就读哪块)。 */
    private val dumpTool: String get() = tools.index.nameOf(Verb.ListElements, tools.surface)!!

    /**
     * 找路线并回放;回放过就把经过(做了哪几步、在哪对不上)和一份当前全量读屏交给模型,返回读屏里的前台 App。
     */
    suspend fun run(task: String, history: MutableList<Message>, executor: ActionExecutor, ctx: ContextBuilder, track: StepTrack): Place? {
        val choice = try {
            withContext(Dispatchers.IO) { routes.choose(task) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } ?: return null
        val total = choice.route.steps.size
        var running = TaskState.Running(0, REPLAY_NOTE, progress = RunProgress.Replay(1, total))
        show(running)
        val replayer = Replayer(
            tools, executor, gate = gate,
            sink = object : RunSink {
                override fun event(e: RunEvent) {
                    emit(e)
                    // 一步对上了就显示下一步
                    if (e is RunEvent.Replay && e.matched && e.step < e.total) {
                        running = running.copy(progress = RunProgress.Replay(e.step + 1, e.total))
                        show(running)
                    }
                }
                override fun media(mediaId: Int, png: ByteArray) = Unit
            },
            clock = clock,
        )
        val out = replayer.run(choice)
        replayed = choice.route to out
        update(choice.route.id) { r ->
            when (out) {
                is ReplayOutcome.Diverged -> {
                    val misses = if (r.missStep == out.done) r.consecutiveMisses + 1 else 1
                    r.copy(consecutiveMisses = misses, missStep = out.done, disabled = r.disabled || misses >= ROUTE_MISSES_TO_DISABLE, lastUsedMs = clock())
                }
                else -> r.copy(lastUsedMs = clock())
            }
        }
        val steps = choice.route.steps
        val doneLines = steps.take(out.done).mapIndexed { i, st -> "${i + 1}. ${Replayer.describe(st, choice.slots)}" }.joinToString("\n")
        val head = when (out) {
            is ReplayOutcome.Completed -> Feedback.format("replay.completed", "total" to steps.size, "steps" to doneLines)
            is ReplayOutcome.Diverged -> {
                val error = out.error?.let { Feedback.format("replay.error_code", "code" to it) }.orEmpty()
                val next = if (out.executed) Feedback.format("replay.diverged_executed", "step" to (out.done + 1), "expected" to out.expected, "seen" to out.seen, "error" to error)
                else Feedback.format("replay.diverged_skipped", "step" to (out.done + 1), "expected" to out.expected, "seen" to out.seen, "error" to error)
                Feedback.format("replay.progress", "done" to out.done, "total" to steps.size, "steps" to doneLines.ifEmpty { Feedback["replay.no_steps"] }, "next" to next)
            }
            is ReplayOutcome.StoppedForConfirmation -> Feedback.format("replay.progress", "done" to out.done, "total" to steps.size, "steps" to doneLines.ifEmpty { Feedback["replay.no_steps"] },
                "next" to Feedback.format("replay.stopped", "step" to (out.done + 1), "action" to Replayer.describe(steps[out.done], choice.slots)))
        }
        // 当前全量读屏:模型从这里拿编号接着做;读不到就只交说明,任务照常继续
        val looked = try {
            tools.executeRead(Part.ToolCall("replay_handback", dumpTool, "{}"), 0).takeIf { it.content["ok"] == JsonPrimitive(true) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        val read = looked?.content
        if (read != null) {
            // 读屏做成一对工具调用与结果:裁剪和「模型看得到哪几屏」按读屏工具认它,各家接口对调用和结果的配对要求也都满足
            val id = "replay_handback"
            history += Message(Role.Assistant, listOf(Part.Text(Feedback.format("replay.handback", "head" to head, "note" to REPLAY_SCREEN_HEADER)), Part.ToolCall(id, dumpTool, "{}")), Author.ZorvAI)
            history += Message(Role.Tool, listOf(Part.ToolResult(id, read.toString(), isError = false, looked?.observations.orEmpty().map { it.tag })), Author.ZorvAI)
            // 能看图的主模型:交还时和动作之后一样看到此刻的画面
            tools.imageNow()?.let { (png, shotId) -> history += Message(Role.User, listOf(Part.Text(Feedback["action_frame_note"]), tools.modelImage(png, shotId)), Author.ZorvAI) }
        } else {
            history += ctx.notice(REPLAY_SOURCE, Feedback.format("replay.handback", "head" to head, "note" to REPLAY_NO_SCREEN))
        }
        // 交还之后的一步按恢复步算(大小模型分工时交给主模型)
        track.markRecovery()
        return looked?.place
    }

    /** 回放走完且这次运行算办成了([LearnGate],[outcome] 是交出回答后判的结局):成功计数 +1、清掉连续失误。 */
    suspend fun noteResult(end: TaskState, outcome: RunOutcome) {
        val (route, out) = replayed ?: return
        if (out !is ReplayOutcome.Completed || !LearnGate.done(end, outcome)) return
        update(route.id) { it.copy(successes = it.successes + 1, consecutiveMisses = 0, missStep = null) }
    }

    private suspend fun update(id: String, change: (Route) -> Route) {
        val store = routes.store
        try {
            // 读改写一次完成:并行的另一个任务同时改这条路线时计数不会丢
            withContext(Dispatchers.IO) { store.update(id, change) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 路线计数写不进去不影响任务
        }
    }

    companion object {
        /** 回放期间界面上这一步的说明。 */
        const val REPLAY_NOTE = "按以往的做法操作"
        /** 同一步连续对不上这么多次就停用路线。 */
        const val ROUTE_MISSES_TO_DISABLE = 2
    }
}
