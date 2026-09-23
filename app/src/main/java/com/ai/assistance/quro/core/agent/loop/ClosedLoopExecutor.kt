package com.ai.assistance.quro.core.agent.loop

import android.content.Context

/**
 * 闭环执行器（感知-判断-执行-反馈-校验-修正 的统一主循环）。
 *
 * 它不关心"怎么跑工具"，只负责：
 * 1. 调用上层提供的 [execOnce]（真正的执行体，并负责把"是否真执行"分类成 [ExecResult]）；
 * 2. 用 [Verifier] 判断"结果是否符合预期"；
 * 3. 用 [ScenarioFailurePolicy] 按失败类型+轮次自动决策 重试/修正/回滚/升级；
 * 4. 全程经 [LoopTrace] 发射轨迹、终端失败时经 [ClosedLoopDiag] 落盘。
 *
 * 这是把 GenUI 的 verifyGenUI 闭环推广到「所有 Agent/Tool 执行层」的通用件。
 */
class ClosedLoopExecutor(
    private val trace: LoopTrace = LoopTrace,
    private val diag: ClosedLoopDiag = ClosedLoopDiag,
) {

    /**
     * 单工具调用的闭环运行。
     *
     * @param scenario 场景键（如 "tool"/"skill"/"plugin"/"network"/"file"），用于查 [FailurePolicyRegistry]。
     * @param name 工具名。
     * @param arguments 入参 JSON。
     * @param execOnce 真正执行体：跑一次并把结果分类为 [ExecResult]。
     * @param verifier 校验器，默认 [DefaultVerifier]。
     * @param onModelCorrect 当策略决定 CORRECT 时回调（把修正指令压回模型）。返回 true 表示已处理、可进入下一轮。
     * @return 最终 [ToolOutcome]。
     */
    suspend fun dispatch(
        context: Context?,
        scenario: String,
        name: String,
        arguments: String,
        execOnce: suspend (attempt: Int) -> ExecResult,
        verifier: Verifier = DefaultVerifier,
        onModelCorrect: (suspend (ToolOutcome.Failure) -> Boolean)? = null,
    ): ToolOutcome {
        val policy = FailurePolicyRegistry.policyFor(scenario)
        var attempt = 0
        val history = mutableListOf<String>()

        while (true) {
            attempt++
            if (attempt > policy.maxAttempts) {
                val f = ToolOutcome.Failure(
                    FailureType.UNKNOWN,
                    "超出最大尝试次数(${policy.maxAttempts})，停止自动处理",
                    attempt = attempt,
                )
                trace.status(name, "闭环终止·超预算", f.message)
                diag.dump(context, name, arguments, history + f.message, scenario)
                return f
            }

            trace.act(name, "执行 第${attempt}次", arguments.take(200))
            val r = execOnce(attempt)

            when (r) {
                is ExecResult.Completed -> {
                    trace.feedback(name, "执行完成", r.raw.take(300))
                    val issues = verifier.verify(name, arguments, r.raw, attempt)
                    val errors = issues.filter { it.severity == LoopIssue.Severity.ERROR }
                    if (errors.isEmpty()) {
                        val warns = issues.filter { it.severity == LoopIssue.Severity.WARN }.map { it.message }
                        trace.verify(name, "校验通过·闭环成功", warns.joinToString("；").ifBlank { "OK" })
                        return ToolOutcome.Success(r.raw, warns)
                    }
                    val msg = "校验未通过：" + errors.joinToString("；") { "${it.code}:${it.message}" }
                    trace.verify(name, "校验发现不符合预期", msg)
                    handleFailure(
                        context, scenario, name, arguments, policy, attempt, history,
                        ToolOutcome.Failure(FailureType.BUSINESS, msg, r.raw, attempt = attempt),
                        onModelCorrect,
                    )?.let { return it }
                }

                is ExecResult.Failed -> {
                    trace.judge(name, "执行失败·${r.type}", r.message)
                    handleFailure(
                        context, scenario, name, arguments, policy, attempt, history,
                        ToolOutcome.Failure(r.type, r.message, r.raw, r.cause, attempt),
                        onModelCorrect,
                    )?.let { return it }
                }

                is ExecResult.Terminal -> {
                    // 不可恢复（未知工具 / 权限缺失且无法弹窗）：直接终止，不重试。
                    trace.judge(name, "不可恢复·${r.type}", r.message)
                    val f = ToolOutcome.Failure(r.type, r.message, attempt = attempt)
                    diag.dump(context, name, arguments, history + "TERMINAL:${r.message}", scenario)
                    return f
                }
            }
        }
    }

    /**
     * 失败决策：根据 policy 决定下一步。
     * 返回非 null = 终态（直接返回调用方）；返回 null = 继续循环（重试/修正）。
     */
    private suspend fun handleFailure(
        context: Context?,
        scenario: String,
        name: String,
        arguments: String,
        policy: ScenarioFailurePolicy,
        attempt: Int,
        history: MutableList<String>,
        failure: ToolOutcome.Failure,
        onModelCorrect: (suspend (ToolOutcome.Failure) -> Boolean)?,
    ): ToolOutcome? {
        val action = policy.decide(failure.failureType, attempt)
        return when (action) {
            RecoveryAction.RETRY -> {
                trace.status(name, "决策·重试", "第${attempt}次失败，按策略重试")
                history.add("a$attempt ${failure.failureType}→重试")
                null
            }
            RecoveryAction.CORRECT -> {
                if (onModelCorrect != null && onModelCorrect(failure)) {
                    trace.correct(name, "决策·模型修正", "已压回模型修正指令")
                    history.add("a$attempt ${failure.failureType}→模型修正")
                    null
                } else {
                    // 没有模型修正通道（如纯工具批处理）：退化为升级。
                    trace.status(name, "决策·升级", "模型修正通道不可用，升级")
                    val f = failure.copy(message = failure.message + "（需模型修正但当前不可用，已升级）")
                    diag.dump(context, name, arguments, history + "a$attempt ${failure.failureType}→升级(无修正通道)", scenario)
                    f
                }
            }
            RecoveryAction.ROLLBACK -> {
                trace.status(name, "决策·回滚", "不应用本次结果")
                diag.dump(context, name, arguments, history + "a$attempt ${failure.failureType}→回滚", scenario)
                failure
            }
            RecoveryAction.ESCALATE -> {
                trace.status(name, "决策·升级", failure.message)
                diag.dump(context, name, arguments, history + "a$attempt ${failure.failureType}→升级", scenario)
                failure
            }
        }
    }
}

/**
 * 单次执行结果的分类（由 [ClosedLoopExecutor.dispatch] 的 execOnce 提供）。
 * 这是闭环"感知"阶段的产物：把"是否真正执行"明确表达出来。
 */
sealed interface ExecResult {
    /** 工具跑完且引擎判定为成功（具体是否符合预期由 Verifier 决定）。 */
    data class Completed(val raw: String) : ExecResult

    /** 执行出现故障，可按策略重试/升级。 */
    data class Failed(
        val type: FailureType,
        val message: String,
        val raw: String? = null,
        val cause: Throwable? = null,
    ) : ExecResult

    /** 不可恢复的终止态（未知工具 / 权限缺失且无法弹窗）：不应重试。 */
    data class Terminal(val type: FailureType, val message: String) : ExecResult
}
