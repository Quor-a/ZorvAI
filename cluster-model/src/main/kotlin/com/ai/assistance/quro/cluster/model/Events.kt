package com.ai.assistance.quro.cluster.model

import kotlinx.serialization.Serializable

/**
 * 集群事件 —— 事件溯源的统一写入口。
 * 一切状态变化先落这里：UI 是事件投影，崩溃后从最后一条事件续跑，审计也读它。
 */
@Serializable
sealed interface ClusterEvent {
    val taskId: TaskId
    val ts: Long

    @Serializable data class UserGoal(override val taskId: TaskId, val goal: String, override val ts: Long = now()) : ClusterEvent
    @Serializable data class AcceptanceDefined(override val taskId: TaskId, val desc: String, val checks: List<String>, override val ts: Long = now()) : ClusterEvent
    @Serializable data class PlanProposed(override val taskId: TaskId, val nodeCount: Int, override val ts: Long = now()) : ClusterEvent
    @Serializable data class SpeakerSelected(override val taskId: TaskId, val agentId: AgentId, val nodeId: NodeId?, val reason: String, override val ts: Long = now()) : ClusterEvent
    @Serializable data class ProposalSubmitted(override val taskId: TaskId, val agentId: AgentId, val nodeId: NodeId, val summary: String, override val ts: Long = now()) : ClusterEvent
    @Serializable data class VerdictIssued(override val taskId: TaskId, val nodeId: NodeId, val pass: Boolean, val reason: String, override val ts: Long = now()) : ClusterEvent
    @Serializable data class ArtifactProduced(override val taskId: TaskId, val artifactId: ArtifactId, val producer: AgentId, val title: String, override val ts: Long = now()) : ClusterEvent
    /**
     * 🔴🔴 工具调用轨迹（#181 补全参数与结果）。
     *
     * 原实现只有 `toolId` + `status`，于是对话框里只能显示
     * 「调用工具 `web_search`（SUCCESS）」—— 用户看到的是一句**元叙述**，
     * 既不知道传了什么参数、也不知道查回了什么结果，等于没有信息。
     *
     * 这三个字段在 [AgentRunner] 里本来就拿得到（`call.argsJson` / `out.output` / `out.error`），
     * 过去只是没往事件里放。补上后 UI 侧可以像主 AI 的工具卡那样完整展开。
     *
     * @param argsJson 调用参数原文（截断在发射处，长参数不该撑爆事件流）。
     * @param output 工具返回原文（同样在发射处截断）。
     * @param error 失败原因；成功时为空串。
     */
    @Serializable data class ToolInvoked(
        override val taskId: TaskId,
        val toolId: ToolId,
        val agentId: AgentId,
        val status: String,
        val argsJson: String = "",
        val output: String = "",
        val error: String = "",
        override val ts: Long = now(),
    ) : ClusterEvent

    /**
     * 🔴🔴 角色**这一轮真正说的话**（#181「集群黑盒」的数据层正解）。
     *
     * ## 为什么必须新增这个事件
     *
     * 在此之前，角色调模型拿到的正文 [AgentRunner] 只写进了 `store.appendMessage`，
     * **从不发事件** —— 于是 UI 侧拿到的只有 [ProposalSubmitted] 的一句 `summary`、
     * [ToolInvoked] 的一个 `status`。投影器再怎么写，也变不出角色到底说了什么。
     * 这就是用户报的「他们讨论了什么？干了什么？不知道，交付没有，直接黑盒结束」。
     *
     *## 它与既有事件的关系（不是替换，是补齐）
     *
     * - [ProposalSubmitted] 等**结构化事件继续照发**：它们是引擎状态机的推进信号，
     *   HostAgent 的编排逻辑靠它们，不能动。
     * - 本事件是**旁路的可观测出口**：只为「让用户在对话框里看见角色在说话」。
     *   同一轮可能先发 [AgentSpoke]（角色说话）→ 再发 [ToolInvoked]（它去调工具）。
     *
     * ## 字段口径
     *
     * @param text 角色模型返回的**原始正文**，一个字节都不改（含 JSON —— 由 UI 侧决定怎么渲染）。
     *   🔴 绝不在引擎里剥 JSON：PROPOSE/PLAN 等 intent 下正文就是结构化 JSON，
     *   剥了 UI 就没法渲染成结构化卡片；原样透传才可能做「像主 AI 一样」的排版。
     * @param intent 本轮意图（PLAN/PROPOSE/VERIFY/EXECUTE…），UI 用来决定气泡上显示什么标签。
     * @param modelId 实际用的模型（角色可能中途被换模，见 [ModelSwitched]）。
     * @param nodeId 关联的子任务节点，用于把同一子任务的发言归到一组。
     */
    @Serializable data class AgentSpoke(
        override val taskId: TaskId,
        val agentId: AgentId,
        val nodeId: NodeId?,
        val text: String,
        val intent: String,
        val modelId: String,
        override val ts: Long = now(),
    ) : ClusterEvent
    @Serializable data class BlackboardUpdated(override val taskId: TaskId, val key: String, val sourceAgent: AgentId, override val ts: Long = now()) : ClusterEvent
    @Serializable data class SkillLoaded(override val taskId: TaskId, val agentId: AgentId, val skillId: SkillId, val level: String, override val ts: Long = now()) : ClusterEvent
    @Serializable data class ErrorRaised(override val taskId: TaskId, val agentId: AgentId?, val message: String, override val ts: Long = now()) : ClusterEvent
    @Serializable data class Replanned(override val taskId: TaskId, val round: Int, val reason: String, override val ts: Long = now()) : ClusterEvent
    @Serializable data class BudgetExceeded(override val taskId: TaskId, val scope: String, override val ts: Long = now()) : ClusterEvent
    @Serializable data class ModelSwitched(override val taskId: TaskId, val agentId: AgentId, val from: String, val to: String, val reason: String, override val ts: Long = now()) : ClusterEvent
    @Serializable data class Escalated(override val taskId: TaskId, val reason: String, override val ts: Long = now()) : ClusterEvent
    @Serializable data class TaskClosed(override val taskId: TaskId, val reason: CloseReason, val summary: String?, override val ts: Long = now()) : ClusterEvent

    companion object { fun now(): Long = System.currentTimeMillis() }
}

/**
 * 角色运行时错误 —— 异常驱动重规划的**唯一输入**。
 *
 * 实现 [Exception]：ModelGateway 用 `Result.failure(AgentError...)` 承载它，
 * AgentRunner 捕获后转成 REPLANNING。上游把这里写成纯 sealed interface，
 * 导致无法作为 Throwable 传进 Result.failure（编译不过），故继承 Exception。
 */
sealed class AgentError(message: String) : Exception(message) {
    data class Timeout(val ms: Long) : AgentError("角色超时 ${ms}ms")
    data class ModelFailure(val hostModelId: String, val reason: String) :
        AgentError("模型 $hostModelId 调用失败：$reason")
    data class ModelUnavailable(val hostModelId: String) :
        AgentError("模型 $hostModelId 当前不可用")
    data class BudgetExceeded(val scope: String) :
        AgentError("预算超限：$scope")
    data class ToolDenied(val toolId: ToolId) :
        AgentError("工具 ${toolId.value} 被拒绝")
    data class InvalidOutput(val expected: String, val got: String) :
        AgentError("输出不符合预期：期望 $expected，实得 $got")
    data class Unknown(val reason: String) : AgentError(reason)
}

/** 角色给出的执行方案 */
data class Proposal(
    val agentId: AgentId,
    val nodeId: NodeId,
    val summary: String,
    val steps: List<String>,
    val risks: List<String> = emptyList(),
    val selfConfidence: Float = 0.7f
)

/** 主持对多份方案的裁决 */
data class Verdict(
    val pass: Boolean,
    val reason: String,
    val chosenAgent: AgentId? = null,
    val mergedSteps: List<String> = emptyList(),
    val confidence: Float = 0f
)

/** 验收结论 */
data class ReviewResult(
    val pass: Boolean,
    val reason: String,
    val failedChecks: List<String> = emptyList()
)
