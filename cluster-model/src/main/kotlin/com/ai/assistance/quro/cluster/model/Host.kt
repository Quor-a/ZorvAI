package com.ai.assistance.quro.cluster.model

import kotlinx.serialization.Serializable

/**
 * 主持身份 —— 系统内置，不可修改。
 *
 * 三重不可变保证：
 * 1. 构造函数私有，全局只有 HostIdentity.INSTANCE 一个实例；
 * 2. 字段全为 val，无 copy、无 setter、无 data class；
 * 3. init 中校验 id 与 name，被改直接抛异常。
 */
@Serializable
class HostIdentity private constructor(
    val id: AgentId,
    val name: String,
    val avatarEmoji: String,
    val oath: String
) {
    init {
        require(id == HOST_AGENT_ID) { "主持 ID 不可变更" }
        require(name == HOST_NAME) { "主持名称不可变更" }
    }

    companion object {
        const val HOST_NAME = "主持"
        val INSTANCE = HostIdentity(
            id = HOST_AGENT_ID,
            name = HOST_NAME,
            avatarEmoji = "🎯",
            oath = """
你是本集群的主持，不是参与者。你的唯一职责是把当前任务推到闭环。
你不得放弃任务：只有「目标达成 / 熔断触发 / 用户中止 / 不可恢复错误」四种情况可以结束。
你不得代替角色做具体工作，你只负责：理解目标、定义验收标准、拆解、选人、裁决、验收、收敛。
你点名谁，谁才说话。无人可用时，你必须自己推进流程而不是空转等待。
你每次点名都要说明理由；每次验收都要对照验收标准逐条给结论。
            """.trimIndent()
        )
    }
}

/**
 * 主持的可变部分：模型、技能、编排策略、终止策略、风格微调。
 * 身份与职责不在其中 —— 那部分在 HostIdentity，物理上改不了。
 */
@Serializable
data class HostConfig(
    var modelBinding: ModelBinding = ModelBinding(),   // 同样指向宿主模型配置 ID
    var skillIds: Set<SkillId> = emptySet(),
    var toolIds: Set<ToolId> = emptySet(),
    var orchestration: OrchestrationPolicy = OrchestrationPolicy(),
    var termination: TerminationPolicy = TerminationPolicy(),
    /** 只调风格，不调身份与职责 */
    var personaLite: String = "简洁、果断，直接点名并说明理由，不给废话。",
    var meta: Meta = Meta(provenance = Provenance.TEMPLATE)
)

enum class OrchestrationMode {
    SUPERVISOR, PIPELINE, DAG, BLACKBOARD, COUNCIL, DEBATE, REFLECTION
}

enum class SpeakerPolicy {
    HOST_ARBITRARY, RELEVANCE_ROUTER, ROUND_ROBIN, BID, TRIGGERED, USER_PIN
}

@Serializable
data class OrchestrationPolicy(
    var mode: OrchestrationMode = OrchestrationMode.SUPERVISOR,
    var speakerPolicy: SpeakerPolicy = SpeakerPolicy.RELEVANCE_ROUTER,
    /** 一次点名几个角色提方案 */
    var proposeFanout: Int = 3,
    /** 提案是否并行且互相隔离（关掉会出现从众幻觉，默认必须开） */
    var parallelPropose: Boolean = true,
    var enableCritic: Boolean = true,
    var maxParallelNodes: Int = 3,
    var replanOnError: Boolean = true,
    var verifyBeforeClose: Boolean = true
)

enum class StagnationAction { REPLAN, ESCALATE_TO_USER, DOWNGRADE_MODEL, DROP_NODE }

/**
 * 终止策略 —— 主持"什么时候能下班"的唯一法律依据。
 * 没有它，主持会无限循环，这是多智能体系统最常见的翻车点。
 */
@Serializable
data class TerminationPolicy(
    var maxTurns: Int = 60,
    var maxTokens: Long = 2_000_000L,
    var maxWallClockMs: Long = 30 * 60 * 1000L,
    var maxReplan: Int = 5,
    /** 连续 N 轮无新产物/无状态变化 → 判定停滞 */
    var noProgressWindow: Int = 3,
    var stagnationAction: StagnationAction = StagnationAction.ESCALATE_TO_USER,
    var requireHumanOn: Set<RiskLevel> = setOf(RiskLevel.HIGH, RiskLevel.CRITICAL)
)

@Serializable
data class Cluster(
    val id: ClusterId,
    val name: String,
    val description: String = "",
    val host: HostConfig = HostConfig(),
    val createdAt: Long = System.currentTimeMillis(),
    var archived: Boolean = false
)
