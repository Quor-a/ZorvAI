package com.ai.assistance.quro.core.agent.loop

/**
 * 闭环执行框架的统一结果类型。
 *
 * 旧的 [com.ai.assistance.quro.core.QuroToolResult] 用 `name="success"/"error"` + 自然语言文本
 * 三处并存描述成败，调用方只能靠猜。闭环框架先把"设备是否真正执行"和"结果是否符合预期"
 * 收敛成明确的密封类型，下游的"判断/决策"层才能据此自动决定 重试/修正/回滚/升级。
 */
sealed interface ToolOutcome {

    /** 工具成功执行，且通过了校验器的"是否符合预期"检查。 */
    data class Success(
        val raw: String,
        val warnings: List<String> = emptyList(),
    ) : ToolOutcome

    /**
     * 执行或校验失败。
     * @param failureType 失败分类，驱动下游决策（见 [ScenarioFailurePolicy]）。
     * @param message 人类可读原因，会原样回传给调用方（即旧的 `QuroToolResult.error` 文案）。
     * @param raw 工具原始返回（若有），便于诊断与回滚判定。
     * @param cause 异常（若有）。
     * @param attempt 进入本次失败时已尝试的轮次（含本次）。
     */
    data class Failure(
        val failureType: FailureType,
        val message: String,
        val raw: String? = null,
        val cause: Throwable? = null,
        val attempt: Int = 1,
    ) : ToolOutcome
}

/**
 * 失败类型枚举 —— 闭环"判断"层的输入。
 * 同一失败类型在不同场景下的恢复策略可能不同（见 [ScenarioFailurePolicy]）。
 */
enum class FailureType {
    /** 权限未授予：重试无意义，需用户介入或回滚。 */
    PERMISSION,
    /** 执行超时：通常是临时拥塞，可重试。 */
    TIMEOUT,
    /** 传输/连接/IO 故障：临时故障，可重试。 */
    TRANSPORT,
    /** 参数或返回解析失败：应让模型修正后重试。 */
    PARSE,
    /** 工具跑完了但业务失败（isSuccess=false）：视场景重试/修正/回滚。 */
    BUSINESS,
    /** 兜底：无法归类，直接升级。 */
    UNKNOWN,
}
