package com.ai.assistance.quro.cluster.model

import kotlinx.serialization.Serializable

enum class RiskLevel { NONE, LOW, MEDIUM, HIGH, CRITICAL }

enum class MemoryScope { PRIVATE, SHARED, CLUSTER, GLOBAL }

enum class SpeakingRule {
    ON_POINTED,     // 被主持点名才说（默认，最省 token）
    CAN_INTERJECT,  // 轮次间隙可申请发言（需主持批准）
    TRIGGERED_ONLY  // 仅当黑板/事件命中其 trigger 时激活
}

enum class Provenance { TEMPLATE, USER_CREATED, AI_CREATED }

enum class ApprovalState { APPROVED, PENDING, REJECTED }

@Serializable
data class Meta(
    val provenance: Provenance = Provenance.USER_CREATED,
    val approval: ApprovalState = ApprovalState.APPROVED,
    val createdBy: AgentId? = null,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    val version: Int = 1,
    /** 锁定后 AI 不得自动修改此对象，必须用户解锁 */
    val locked: Boolean = false
)

@Serializable
data class Identity(
    val name: String,
    val avatarEmoji: String = "🤖",
    val avatarUri: String? = null,
    val colorArgb: Long = 0xFF90A4AE,
    val voiceId: String? = null,
    val signature: String = "",
    /** 自称方式，影响语气："我" / "本官" / "本研究员" */
    val selfReference: String = "我"
)

@Serializable
data class AgentPolicy(
    val maxTurnsPerTask: Int = 12,
    val maxTokensPerTask: Long = 200_000L,
    val maxToolCallsPerTurn: Int = 8,
    val allowToolCalls: Boolean = true,
    val allowModifySelfPrompt: Boolean = false,
    val allowCreateAgent: Boolean = false,
    val allowInstallSkill: Boolean = false,
    val requireApprovalOn: Set<RiskLevel> = setOf(RiskLevel.HIGH, RiskLevel.CRITICAL)
)

/**
 * 角色配置。除 id 外全部可变。
 * 注意 modelBinding 里存的是**宿主模型配置 ID**，不是 SDK 自己的 provider。
 * SDK 不持有任何 API Key、不实现任何网络协议，全部走宿主已有的模型层。
 */
@Serializable
data class AgentConfig(
    val id: AgentId,
    val clusterId: ClusterId,
    val identity: Identity,

    var persona: String = "",
    var systemPrompt: String = "",
    var duties: List<String> = emptyList(),
    var taboos: List<String> = emptyList(),
    var triggerKeywords: List<String> = emptyList(),

    var modelBinding: ModelBinding = ModelBinding(),
    var skillIds: Set<SkillId> = emptySet(),
    var toolIds: Set<ToolId> = emptySet(),

    var memoryScope: MemoryScope = MemoryScope.SHARED,
    var speakingRule: SpeakingRule = SpeakingRule.ON_POINTED,
    var policy: AgentPolicy = AgentPolicy(),

    var meta: Meta = Meta(),
    var enabled: Boolean = true
) {
    fun routingText(): String =
        (duties + triggerKeywords).joinToString(" ") + " " + persona.take(200)
}
