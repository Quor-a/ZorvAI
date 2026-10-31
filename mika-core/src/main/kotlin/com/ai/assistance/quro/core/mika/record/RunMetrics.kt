package com.ai.assistance.quro.core.mika.record

import kotlinx.serialization.Serializable

/**
 * 一次运行的小结,全部由运行记录里的事件算出(纯函数,见 [of]),落盘后不随事件清理而丢(见 [RunMetricsStore])。
 * 时间都从 [RunEvent.TaskStarted] 算起:之前的准备(载入知识、连手机)不计。
 *
 * - [totalMs]:开始到 [RunEvent.TaskEnded];没结束的([ended] 为 false)到最后一个事件。
 * - [firstFeedbackMs]:开始到用户第一次看到这次运行的实际进展——最早一条真执行了的模型回复到手([RunEvent.ModelReply.repliedAtMs],
 *   旧记录用它的 [RunEvent.atMs]):这时界面显示它的进度说明、要做的事或回答;或最早一条活动(回放不经模型时,第一步做完)。
 *   改派掉的轻量模型回复不算(用户看不到);回答逐段显示时第一段字会更早,没记录,不算。两样都没有为 null。
 * - [modelCalls]:模型回复事件数,即主循环的请求次数(含改派掉的轻量模型回复,[lightCalls] 是其中轻量模型的);
 *   [callsWithoutUsage] 是其中服务商没报用量的。token 是这些回复的用量之和([inputTokens] 含 [cachedTokens])。
 * - [allCalls] 等:运行期间经计量的全部请求([RunEvent.ModelCalls]:另含旁路调用和失败重试的每一次);旧记录为 null。
 * - [modelMs]:各次回复的请求耗时之和(最后一次请求发出到收完);之前失败的请求和退避另记在 [retryWaitMs]。
 * - [observeMs] / [actMs]:顶层工具调用(模型直接调的、回放的)的执行耗时,按执行入口给活动定的种类分:风险为只读的是读
 *   (读屏、读网页、列元素、截图、查资料……),其余是操作(点、输入、滑、打开 App……)。一批动作、页面脚本的外层调用里,
 *   子步中的读计入读、其余计入操作,不重复计;没有活动的控制类调用(等待、问用户、请确认、交给用户、说做不成)不算读也不算操作。
 *   [uiWaitMs] 是等界面的时间:单个工具里端口等界面稳定的部分(端口能报时才有,仍算在那次读或操作里),
 *   加上一批动作里各步做完后按条件等界面的时间——后者和单独调等待工具一样不算操作,落在 [otherMs] 里。
 * - [otherMs]:总时长减模型、读、操作,剩下的是等用户、重试退避、控制类调用、核查与其他开销。
 * - [steps]:主循环走到第几轮(真执行了的回复里最大的步号);[observeCalls] / [actCalls] 是读、操作各做了几次(含子步)。
 * - [outcome]:[RunEvent.TaskEnded.outcome],没结束为 unfinished;[failKind] 是失败的类别;[judged] 是交出回答后另判的结局。
 */
@Serializable
data class RunMetrics(
    val runId: String = "",
    val goal: String = "",
    val startedAtMs: Long = 0,
    val totalMs: Long = 0,
    val ended: Boolean = false,
    val firstFeedbackMs: Long? = null,
    val modelCalls: Int = 0,
    val lightCalls: Int = 0,
    val callsWithoutUsage: Int = 0,
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val cachedTokens: Long = 0,
    val allCalls: Int? = null,
    val allInputTokens: Long? = null,
    val allOutputTokens: Long? = null,
    val modelMs: Long = 0,
    val retryWaitMs: Long = 0,
    val observeMs: Long = 0,
    val actMs: Long = 0,
    val uiWaitMs: Long = 0,
    val observeCalls: Int = 0,
    val actCalls: Int = 0,
    val steps: Int = 0,
    val outcome: String = UNFINISHED,
    val failKind: String? = null,
    val judged: String? = null,
) {
    val otherMs: Long get() = (totalMs - modelMs - observeMs - actMs).coerceAtLeast(0)

    /** 输入加输出(缓存命中含在输入里)。 */
    val tokens: Long get() = inputTokens + outputTokens

    /** 办成了:报了成功(含没能核实的),交出回答后也没判成只办了一部分或办不到。 */
    val succeeded: Boolean get() = outcome in SUCCESS && judged !in NOT_DONE

    /**
     * 按事件顺序累计;[build] 随时可调。运行记录的写线程用它边写边算,不必在收尾时重读整份记录。
     * 只留每个工具结果的几项数字,不留原文。
     */
    class Builder(private val runId: String = "") {
        private class Tool(val step: Int, val source: String, val callId: String?, val durationMs: Long, val waitMs: Long)

        private var goal = ""
        private var started: Long? = null
        private var last = 0L
        private var ended: RunEvent.TaskEnded? = null
        private var firstFeedback: Long? = null
        private var calls = 0
        private var light = 0
        private var noUsage = 0
        private var input = 0L
        private var output = 0L
        private var cached = 0L
        private var all: RunEvent.ModelCalls? = null
        private var modelMs = 0L
        private var retryMs = 0L
        private var steps = 0
        private var judged: String? = null
        private val tools = mutableListOf<Tool>()
        /** (来源, 步, 调用 id) → 活动种类(observe / act)。 */
        private val kinds = HashMap<Triple<String, Int, String?>, String>()

        fun add(e: RunEvent) {
            last = maxOf(last, e.atMs)
            when (e) {
                is RunEvent.TaskStarted -> if (started == null) { started = e.atMs; goal = e.goal.take(GOAL_MAX) }
                is RunEvent.ModelReply -> {
                    calls++
                    if (e.role == ROLE_LIGHT || !e.acted) light++
                    if (e.inputTokens == null && e.outputTokens == null) noUsage++
                    input += e.inputTokens ?: 0
                    output += e.outputTokens ?: 0
                    cached += e.cachedTokens ?: 0
                    modelMs += e.latencyMs.coerceAtLeast(0)
                    retryMs += e.retryWaitMs.coerceAtLeast(0)
                    if (e.acted) {
                        steps = maxOf(steps, e.step)
                        feedback(e.repliedAtMs ?: e.atMs)
                    }
                }
                is RunEvent.ToolResult -> tools += Tool(e.step, e.source, e.callId, e.durationMs.coerceAtLeast(0), e.waitMs.coerceAtLeast(0))
                is RunEvent.Activity -> {
                    kinds[Triple(e.source, e.step, e.callId)] = e.kind
                    feedback(e.atMs)
                }
                is RunEvent.TaskEnded -> if (ended == null) ended = e
                is RunEvent.ModelCalls -> all = e
                is RunEvent.OutcomeJudged -> judged = e.outcome
                else -> Unit
            }
        }

        private fun feedback(at: Long) {
            if (firstFeedback == null || at < firstFeedback!!) firstFeedback = at
        }

        /** 没有开始事件(记录开了却没跑起来)时为 null。 */
        fun build(): RunMetrics? {
            val start = started ?: return null
            val end = ended
            val kindOf = { t: Tool -> kinds[Triple(t.source, t.step, t.callId)] }
            val (top, nested) = tools.partition { it.source !in NESTED }
            var observe = 0L
            var act = 0L
            for (t in top) {
                val kind = kindOf(t)
                val inner = nested.filter { it.step == t.step }
                // 外层调用的耗时已含子步:子步里的读计入读,余下的算操作
                val innerObserve = inner.filter { kindOf(it) == OBSERVE }.sumOf { it.durationMs }.coerceAtMost(t.durationMs)
                when {
                    kind == OBSERVE -> observe += t.durationMs
                    // 外层调用记的 waitMs 是子步之后按条件等界面的时间,不在子步里,也不算操作
                    inner.isNotEmpty() -> { observe += innerObserve; act += (t.durationMs - innerObserve - t.waitMs).coerceAtLeast(0) }
                    // 为节奏补的停留(waitMs)算等待,不算操作
                    kind != null -> act += (t.durationMs - t.waitMs).coerceAtLeast(0)
                    else -> Unit
                }
            }
            val counted = kinds.values
            return RunMetrics(
                runId = runId,
                goal = goal,
                startedAtMs = start,
                totalMs = ((end?.atMs ?: last) - start).coerceAtLeast(0),
                ended = end != null,
                firstFeedbackMs = firstFeedback?.let { (it - start).coerceAtLeast(0) },
                modelCalls = calls,
                lightCalls = light,
                callsWithoutUsage = noUsage,
                inputTokens = input,
                outputTokens = output,
                cachedTokens = cached,
                allCalls = all?.calls,
                allInputTokens = all?.inputTokens,
                allOutputTokens = all?.outputTokens,
                modelMs = modelMs,
                retryWaitMs = retryMs,
                observeMs = observe,
                actMs = act,
                uiWaitMs = top.sumOf { it.waitMs },
                observeCalls = counted.count { it == OBSERVE },
                actCalls = counted.count { it == ACT },
                steps = steps,
                outcome = end?.outcome ?: UNFINISHED,
                failKind = end?.kind,
                judged = judged,
            )
        }
    }

    companion object {
        const val UNFINISHED = "unfinished"
        const val GOAL_MAX = 80
        private const val OBSERVE = "observe"
        private const val ACT = "act"
        private const val ROLE_LIGHT = "light"
        /** 包在外层调用里的子步:一批动作里的一步、页面脚本里的一个动作。 */
        private val NESTED = setOf("batch", "script")
        private val SUCCESS = setOf("succeeded", "unverified")
        private val NOT_DONE = setOf("partial", "not_possible")

        /** 一次运行的小结;没有开始事件时为 null。 */
        fun of(events: List<RunEvent>, runId: String = ""): RunMetrics? = Builder(runId).apply { events.forEach(::add) }.build()
    }
}

/**
 * 多次运行的汇总([of]):办成率、用时与第一次反馈的中位数、平均来回次数与 token,以及时间的去向(各项之和占总时长之和)。
 * 第一次反馈只在有这一项的运行里取中位数;一次都没有为 null。
 */
data class RunStats(
    val runs: Int,
    val succeeded: Int,
    val medianTotalMs: Long,
    val medianFirstFeedbackMs: Long?,
    val avgModelCalls: Double,
    val avgTokens: Double,
    val totalMs: Long,
    val modelMs: Long,
    val observeMs: Long,
    val actMs: Long,
    val otherMs: Long,
) {
    val successRate: Double get() = if (runs == 0) 0.0 else succeeded.toDouble() / runs

    companion object {
        fun of(runs: List<RunMetrics>): RunStats = RunStats(
            runs = runs.size,
            succeeded = runs.count { it.succeeded },
            medianTotalMs = median(runs.map { it.totalMs }) ?: 0,
            medianFirstFeedbackMs = median(runs.mapNotNull { it.firstFeedbackMs }),
            avgModelCalls = if (runs.isEmpty()) 0.0 else runs.sumOf { it.modelCalls }.toDouble() / runs.size,
            avgTokens = if (runs.isEmpty()) 0.0 else runs.sumOf { it.tokens }.toDouble() / runs.size,
            totalMs = runs.sumOf { it.totalMs },
            modelMs = runs.sumOf { it.modelMs },
            observeMs = runs.sumOf { it.observeMs },
            actMs = runs.sumOf { it.actMs },
            otherMs = runs.sumOf { it.otherMs },
        )

        /** 中位数;偶数个取中间两个的平均(向下取整)。空表为 null。 */
        fun median(xs: List<Long>): Long? {
            if (xs.isEmpty()) return null
            val s = xs.sorted()
            val m = s.size / 2
            return if (s.size % 2 == 1) s[m] else (s[m - 1] + s[m]) / 2
        }
    }
}
