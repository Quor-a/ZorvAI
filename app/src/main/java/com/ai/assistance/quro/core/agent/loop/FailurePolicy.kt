package com.ai.assistance.quro.core.agent.loop

/**
 * 失败后的恢复动作（闭环"判断→修正"的输出）。
 */
enum class RecoveryAction {
    /** 用相同参数立即重试。 */
    RETRY,
    /** 让模型/上层修正参数或指令后重试（本引擎内若无修正通道则退化为升级）。 */
    CORRECT,
    /** 回滚到执行前状态，不应用本次结果。 */
    ROLLBACK,
    /** 升级：交给用户或上报，不再自动处理。 */
    ESCALATE,
}

/**
 * 按场景配置的失败决策表。
 *
 * `table[type]` 是一个"尝试轮次→动作"序列：第 N 次失败时取序列第 N 项（越界取末项，末项应为终态动作）。
 * 这样就能做到"按场景自动决策"：不同场景给出不同序列即可，无需固定单一动作。
 *
 * 例：默认 BUSINESS = [RETRY, RETRY, CORRECT, ROLLBACK, ESCALATE] 表示
 * 业务失败先重试两次，再让模型修正，仍不行就回滚，最后升级给用户。
 */
data class ScenarioFailurePolicy(
    val scenario: String,
    val table: Map<FailureType, List<RecoveryAction>> = DEFAULT_TABLE,
    /** 安全上限：任何场景都不允许无限循环。 */
    val maxAttempts: Int = 5,
) {
    fun decide(type: FailureType, attempt: Int): RecoveryAction {
        val seq = table[type] ?: DEFAULT_TABLE[FailureType.UNKNOWN]!!
        val idx = (attempt - 1).coerceIn(0, seq.size - 1)
        return seq[idx]
    }
}

/** 全局默认决策表（按失败类型给出最合理的尝试序列）。 */
val DEFAULT_TABLE: Map<FailureType, List<RecoveryAction>> = mapOf(
    FailureType.PERMISSION to listOf(RecoveryAction.ESCALATE),
    FailureType.TIMEOUT to listOf(RecoveryAction.RETRY, RecoveryAction.RETRY, RecoveryAction.ESCALATE),
    FailureType.TRANSPORT to listOf(RecoveryAction.RETRY, RecoveryAction.RETRY, RecoveryAction.ESCALATE),
    FailureType.PARSE to listOf(RecoveryAction.CORRECT, RecoveryAction.ESCALATE),
    FailureType.BUSINESS to listOf(
        RecoveryAction.RETRY,
        RecoveryAction.RETRY,
        RecoveryAction.CORRECT,
        RecoveryAction.ROLLBACK,
        RecoveryAction.ESCALATE,
    ),
    FailureType.UNKNOWN to listOf(RecoveryAction.RETRY, RecoveryAction.ESCALATE),
)

/**
 * 场景策略注册表：各 Agent/工具类可向此处注册专属策略，未注册的场景走默认。
 *
 * 例：网络类工具可注册 `network` 场景，把 TIMEOUT 改成 [RETRY, RETRY, RETRY, ESCALATE]（更耐抖）；
 * 文件写类工具可把 BUSINESS 改成以 ROLLBACK 收尾（写坏文件必须回滚）。
 */
object FailurePolicyRegistry {
    private val overrides = mutableMapOf<String, ScenarioFailurePolicy>()

    fun register(policy: ScenarioFailurePolicy) {
        overrides[policy.scenario] = policy
    }

    fun policyFor(scenario: String): ScenarioFailurePolicy =
        overrides[scenario] ?: ScenarioFailurePolicy(scenario)
}
