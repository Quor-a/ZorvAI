package com.ai.assistance.quro.core.agent.orchestration

import android.content.Context
import com.ai.assistance.quro.core.agent.loop.ClosedLoopDiag
import com.ai.assistance.quro.core.agent.loop.ClosedLoopExecutor
import com.ai.assistance.quro.core.agent.loop.ToolOutcome

/**
 * 长程任务编排器：把"策划方案 → 规划方案 → 设计方案 → 执行 → 校验 → 修正 → 交付判定"
 * 串成任务级闭环。
 *
 * 与 [ClosedLoopExecutor]（单工具级闭环）的关系：本编排器是"任务级"闭环，
 * 其中"执行"阶段把每个步骤委托给 [ClosedLoopExecutor]（工具级闭环），
 * 并在最后加一道"交付闸门"——**不立即交付**，先判定产物是否真正可交付；
 * 不可交付则继续编排（重新规划/执行），直到可交付或超预算升级。
 *
 * 这正是"所有任务不是马上交，而是先检查是否可交付，不是才继续 Agent 编排执行"的机制落点。
 */
class LongHorizonOrchestrator(
    private val closedLoop: ClosedLoopExecutor = ClosedLoopExecutor(),
    private val trace: OrchestrationTrace = OrchestrationTrace,
    private val diag: ClosedLoopDiag = ClosedLoopDiag,
) {

    /**
     * 运行一个长程任务。
     *
     * @param taskBrief 任务简述（通常是最新用户指令）。
     * @param planner 策划/规划/设计实现（null 则跳过，直接执行）。
     * @param steps 兜底步骤列表（planner 未给出 steps 时使用）。
     * @param stepExecutor 单步骤执行体（通常委托 [ClosedLoopExecutor.dispatch]）。
     * @param judge 交付闸门（默认 [AlwaysDeliverable]，即保持旧行为）。
     * @param maxIterations 重新规划/执行的最大轮次（防无限循环）。
     */
    suspend fun runTask(
        context: Context?,
        taskBrief: String,
        planner: TaskPlanner? = null,
        steps: List<TaskStep> = emptyList(),
        stepExecutor: suspend (TaskStep, Int) -> ToolOutcome,
        judge: DeliverabilityJudge = AlwaysDeliverable,
        maxIterations: Int = 5,
    ): TaskResult {
        val history = mutableListOf<String>()
        var iteration = 0
        loop@ while (iteration < maxIterations) {
            iteration++
            // ① 策划方案 / 规划方案 / 设计方案
            val plan: TaskPlan? = if (planner != null) {
                trace.strategize("task", "第${iteration}轮 策划/规划/设计")
                val p = runCatching { planner.plan(taskBrief, context?.let { "ctx" } ?: "") }.getOrElse { TaskPlan() }
                trace.plan("task", p.plan.take(200))
                trace.design("task", p.design.take(200))
                history.add("i$iteration 策划：${p.strategy.take(80)}")
                p
            } else {
                trace.phase("task", TaskPhase.STRATEGIZE, "无 planner，跳过显式策划")
                null
            }

            // ② 执行 + 校验（委托工具级闭环）
            val effectiveSteps = plan?.steps?.takeIf { it.isNotEmpty() } ?: steps
            val stepOutcomes = mutableListOf<ToolOutcome>()
            for (step in effectiveSteps) {
                trace.execute("task", "执行步骤 ${step.id}：${step.description.take(60)}")
                val outcome = stepExecutor(step, iteration)
                stepOutcomes.add(outcome)
                when (outcome) {
                    is ToolOutcome.Success -> trace.verify("task", "步骤成功", outcome.raw.take(120))
                    is ToolOutcome.Failure -> trace.verify("task", "步骤失败·${outcome.failureType}", outcome.message)
                }
            }
            val summary = stepOutcomes.joinToString("\n") {
                when (it) {
                    is ToolOutcome.Success -> "OK: ${it.raw.take(200)}"
                    is ToolOutcome.Failure -> "FAIL(${it.failureType}): ${it.message}"
                }
            }

            // ③ 交付闸门：不立即交付，先判定产物是否真正可交付
            val gate = judge.judge(taskBrief, summary, context?.let { "ctx" } ?: "")
            trace.deliver(
                "task",
                if (gate is Deliverability.Deliverable) "可交付" else "不可交付",
                gate.summary,
            )
            return when (gate) {
                is Deliverability.Deliverable -> {
                    history.add("i$iteration 交付闸门通过")
                    TaskResult.Delivered(stepOutcomes, summary, iteration)
                }
                is Deliverability.NotDeliverable -> {
                    history.add("i$iteration 不可交付：${gate.reason}")
                    if (iteration >= maxIterations) {
                        // 超预算：升级（交回用户，并附原因）
                        trace.deliver("task", "超预算·升级", "已达最大迭代 $maxIterations")
                        diag.dump(context, taskBrief.take(40), summary, history, "orchestration")
                        TaskResult.Escalated(stepOutcomes, summary, iteration, gate.reason)
                    } else {
                        // 继续编排：下一轮重新规划/执行（修正）
                        trace.correct("task", "继续编排", gate.suggestion ?: "")
                        continue@loop
                    }
                }
            }
        }
        return TaskResult.Escalated(emptyList(), "", iteration, "未知终止")
    }
}

/** 长程任务最终产出。 */
sealed interface TaskResult {
    val outcomes: List<ToolOutcome>
    val finalSummary: String
    val iterations: Int

    /** 闸门通过，产物已可交付。 */
    data class Delivered(
        override val outcomes: List<ToolOutcome>,
        override val finalSummary: String,
        override val iterations: Int,
    ) : TaskResult

    /** 超出预算仍未可交付，升级给用户（附原因）。 */
    data class Escalated(
        override val outcomes: List<ToolOutcome>,
        override val finalSummary: String,
        override val iterations: Int,
        val reason: String,
    ) : TaskResult
}
