package com.ai.assistance.quro.cluster.model

import kotlinx.serialization.Serializable

enum class MessageRole { SYSTEM, USER, ASSISTANT, TOOL }

enum class TurnIntent {
    INTAKE, PLAN, PROPOSE, ARBITRATE, EXECUTE, REVIEW, VERIFY,
    REPLAN, CONVERGE, CLOSE, CHAT, USER_PIN
}

enum class ToolCallStatus { PENDING, RUNNING, SUCCESS, FAILED, DENIED, TIMEOUT }

@Serializable
data class Message(
    val id: MessageId,
    val turnId: TurnId,
    /** 冗余存储，便于按任务直接查消息流（turn 表只在调试时用） */
    val taskId: TaskId? = null,
    val agentId: AgentId,
    val role: MessageRole,
    val content: String,
    val ts: Long = System.currentTimeMillis(),
    val tokensIn: Int = 0,
    val tokensOut: Int = 0,
    /** 实际使用的宿主模型 ID，便于事后归因 */
    val hostModelId: String? = null,
    val latencyMs: Long = 0,
    /** 外部来源内容标记：装进提示词时会加隔离边界防注入 */
    val untrusted: Boolean = false
)

@Serializable
data class Turn(
    val id: TurnId,
    val taskId: TaskId,
    val seq: Int,
    val speaker: AgentId,
    val intent: TurnIntent,
    val triggeredBy: AgentId? = null,
    val nodeId: NodeId? = null,
    val ts: Long = System.currentTimeMillis()
)

@Serializable
data class ToolCall(
    val id: String,
    val messageId: MessageId,
    val agentId: AgentId,
    val toolId: ToolId,
    val argsJson: String,
    var resultJson: String? = null,
    var status: ToolCallStatus = ToolCallStatus.PENDING,
    val risk: RiskLevel = RiskLevel.LOW,
    /** 重放时不重复产生副作用 */
    val idempotencyKey: String = id,
    val durationMs: Long = 0,
    val ts: Long = System.currentTimeMillis()
)

enum class ArtifactKind { TEXT, MARKDOWN, CODE, JSON, IMAGE, FILE, PLAN, VERDICT }

@Serializable
data class Artifact(
    val id: ArtifactId,
    val taskId: TaskId,
    val nodeId: NodeId?,
    val producer: AgentId,
    val kind: ArtifactKind,
    val title: String,
    val uri: String? = null,
    val content: String? = null,
    val version: Int = 1,
    val ts: Long = System.currentTimeMillis()
)

/** 黑板条目必须带来源与置信度 —— 防止某个角色的幻觉被下游当成事实 */
@Serializable
data class BlackboardEntry(
    val key: String,
    val valueJson: String,
    val sourceAgent: AgentId,
    val confidence: Float = 1.0f,
    val version: Int = 1,
    val scope: MemoryScope = MemoryScope.SHARED,
    val ts: Long = System.currentTimeMillis()
)

/** token 账本：按集群/任务/角色/模型多维统计，用来定位"到底谁在烧钱" */
@Serializable
data class BudgetEntry(
    val id: String,
    val clusterId: ClusterId,
    val taskId: TaskId?,
    val agentId: AgentId,
    val hostModelId: String,
    val tokensIn: Int,
    val tokensOut: Int,
    val ts: Long = System.currentTimeMillis()
)

enum class AuditAction {
    AGENT_CREATE, AGENT_UPDATE, AGENT_DELETE,
    SKILL_INSTALL, SKILL_UPDATE, SKILL_UNINSTALL, SKILL_CREATE,
    TASK_SUBMIT, TASK_CLOSE, HOST_CONFIG_UPDATE, PERMISSION_GRANT
}

@Serializable
data class AuditLog(
    val id: String,
    val ts: Long,
    /** "user" 或 agentId.value */
    val actor: String,
    val action: AuditAction,
    val target: String,
    val beforeJson: String? = null,
    val afterJson: String? = null,
    val risk: RiskLevel = RiskLevel.LOW
)
