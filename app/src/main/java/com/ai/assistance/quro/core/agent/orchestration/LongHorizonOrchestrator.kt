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
 * 其中"执行"阶段把每个步骤委托给执行体（端上通常是 [ClosedLoopExecutor]，也可直接是
 * `QuroAssistant` 的一趟 ReAct 工具循环），并在最后加一道"交付闸门"——
 * **不立即交付**，先判定产物是否真正可交付；不可交付则继续编排（重新规划/执行），
 * 直到可交付或超预算升级。
 *
 * 这正是"所有任务不是马上交，而是先检查是否可交付，不是才继续 Agent 编排执行"的机制落点。
 *
 * ## 🔴 重规划必须带记忆（本类存在的核心理由之一）
 * 此前这里写的是 `planner.plan(taskBrief, context?.let { "ctx" } ?: "")` ——
 * 第二个参数恒为字面量 `"ctx"`（一个真·无意义的占位符），于是**每一轮重规划都是盲的**：
 * 模型不知道上一轮为什么被判不可交付、不知道哪些工具刚失败，
 * 只会换个说法再答一遍同样的错。
 * 现在 `lastGate`（打回原因 + 修正建议）与 `lastFailures`（上轮失败步骤摘要）
 * 由本类自己累积并拼进 [buildPlanContext]，第 2 轮起 `plan()` 拿到的是**事实**。
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
     * @param stepExecutor 单步骤执行体（通常是 `QuroAssistant` 的一趟 ReAct 工具循环）。
     * @param judge 交付闸门（默认 [AlwaysDeliverable]；端上主循环注入常驻的启发式闸门）。
     * @param maxIterations 重新规划/执行的最大轮次（防无限循环）。
     * @param extraPlanContext 追加给策划的**外部**记忆（如「最近失败的工具调用摘要」）。
     *   本类自己掌握闸门打回原因与步骤失败，但工具层的失败明细只有调用方看得到
     *   （执行体把整趟 ReAct 压成一步，工具失败不会体现在 [ToolOutcome] 上），故留此出口。
     *   返回 null/空白表示无追加。
     */
    suspend fun runTask(
        context: Context?,
        taskBrief: String,
        planner: TaskPlanner? = null,
        steps: List<TaskStep> = emptyList(),
        stepExecutor: suspend (TaskStep, Int) -> ToolOutcome,
        judge: DeliverabilityJudge = AlwaysDeliverable,
        maxIterations: Int = 5,
        extraPlanContext: (suspend () -> String?)? = null,
    ): TaskResult {
        val history = mutableListOf<String>()
        // 🔴 重规划记忆：这两个变量此前根本不存在，是「重规划不带记忆」的根因。
        var lastGate: Deliverability.NotDeliverable? = null
        var lastFailures: String? = null
        var iteration = 0
        loop@ while (iteration < maxIterations) {
            iteration++
            // 首轮两者皆null → buildPlanContext 返回空串 → 策划阶段零噪声；
            // 第 2 轮起带上「为什么被打回」与「哪几步失败了」。
            val externalCtx = extraPlanContext?.let { runCatching { it() }.getOrNull() }
            val planCtx = buildPlanContext(lastGate, lastFailures, externalCtx)

            // ① 策划方案 / 规划方案 / 设计方案
            val plan: TaskPlan? = if (planner != null) {
                trace.strategize("task", "第${iteration}轮 策划/规划/设计")
                val p = runCatching { planner.plan(taskBrief, planCtx) }.getOrElse { TaskPlan() }
                trace.plan("task", p.plan.take(200))
                trace.design("task", p.design.take(200))
                history.add("i$iteration 策划：${p.strategy.take(80)}")
                p
            } else {
                trace.phase("task", TaskPhase.STRATEGIZE, "无 planner，跳过显式策划")
                null
            }

            // ② 执行 + 校验（委托执行体）
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

            // 🔴 失败步骤摘要：喂给**下一轮**策划，否则重规划不知道该避开什么。
            //   只取失败、每条截断，避免把整段工具输出灌进策划上下文。
            lastFailures = stepOutcomes
                .filterIsInstance<ToolOutcome.Failure>()
                .joinToString(" | ") { "${it.failureType}: ${it.message.take(120)}" }
                .takeIf { it.isNotBlank() }

            // ③ 交付闸门：不立即交付，先判定产物是否真正可交付。
            //   🔴 判定输入必须是**产物原文**，不能是上面那个带 "OK: " 前缀且 take(200)
            //   截断过的 summary —— 闸门判的是「这段答复本身是不是失败回执」，
            //   加了前缀就永远 startsWith 不上，等于闸门彻底失明。
            val candidate = stepOutcomes.lastOrNull()?.let {
                when (it) {
                    is ToolOutcome.Success -> it.raw
                    is ToolOutcome.Failure -> it.message
                }
            } ?: ""
            val gate = judge.judge(taskBrief, candidate, planCtx)
            trace.deliver(
                "task",
                if (gate is Deliverability.Deliverable) "可交付" else "不可交付",
                gate.summary,
            )
            return when (gate) {
                is Deliverability.Deliverable -> {
                    history.add("i$iteration 交付闸门通过")
                    TaskResult.Delivered(stepOutcomes, candidate, iteration)
                }
                is Deliverability.NotDeliverable -> {
                    history.add("i$iteration 不可交付：${gate.reason}")
                    // 记下打回原因：下一轮 plan() 会读到它（见类注释「重规划必须带记忆」）。
                    lastGate = gate
                    if (iteration >= maxIterations) {
                        // 超预算：升级（交回用户，并附原因）
                        trace.deliver("task", "超预算·升级", "已达最大迭代 $maxIterations")
                        diag.dump(context, taskBrief.take(40), summary, history, "orchestration")
                        TaskResult.Escalated(stepOutcomes, candidate, iteration, gate.reason)
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

    companion object {
        /**
         * 把「上一轮为什么被打回」+「最近哪些步骤失败」+「调用方追加的外部记忆」拼成策划上下文。
         *
         * 纯函数（不碰 store / Context），可直接单测。
         *
         * @param gate 上一轮交付闸门的打回结果；null = 尚未被打回过。
         * @param failures 最近失败步骤摘要（已由调用方截断）；null/空白 = 无。
         * @param external 调用方追加的外部记忆（如工具层失败明细）；null/空白 = 无。
         * @return 策划上下文；首轮（三者皆空）返回**空串** —— 否则每次普通问答
         *         的策划提示里都会塞一段无用前缀。返回串以换行收尾，planner 追加自己的内容时
         *         不会粘在最后一行。
         */
        fun buildPlanContext(
            gate: Deliverability.NotDeliverable?,
            failures: String?,
            external: String? = null,
        ): String {
            if (gate == null && failures.isNullOrBlank() && external.isNullOrBlank()) return ""
            val sb = StringBuilder()
            if (gate != null) {
                sb.append("上一轮答复被交付闸门判为不可交付：")
                sb.append(gate.reason)
                gate.suggestion?.takeIf { it.isNotBlank() }?.let { sb.append("。建议：").append(it) }
                sb.append('\n')
            }
            if (!failures.isNullOrBlank()) {
                sb.append("最近工具失败：")
                sb.append(failures)
                sb.append('\n')
            }
            if (!external.isNullOrBlank()) {
                sb.append(external)
                if (!external.endsWith("\n")) sb.append('\n')
            }
            return sb.toString()
        }
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