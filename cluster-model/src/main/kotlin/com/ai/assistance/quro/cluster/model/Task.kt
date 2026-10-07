package com.ai.assistance.quro.cluster.model

import kotlinx.serialization.Serializable

enum class TaskState {
    IDLE, INTAKE, PLANNING, DISPATCHING, PROPOSING, REVIEWING,
    EXECUTING, VERIFYING, CONVERGING, REPLANNING, CLOSING,
    ARCHIVED, ESCALATED
}

/** 任务关闭原因 —— 主持合法的"下班方式"只有这五种 */
enum class CloseReason {
    GOAL_REACHED, CIRCUIT_BROKEN, USER_ABORTED, UNRECOVERABLE, SUPERSEDED
}

enum class NodeState { PENDING, READY, RUNNING, PROPOSING, EXECUTING, DONE, FAILED, SKIPPED }

enum class NodeType { RESEARCH, WRITE, CODE, REVIEW, VERIFY, TOOL, SYNTHESIS }

@Serializable
data class AcceptanceCriteria(
    val description: String,
    val checkList: List<String> = emptyList()
)

@Serializable
data class TaskNode(
    val id: NodeId,
    val title: String,
    val type: NodeType = NodeType.WRITE,
    val instruction: String = "",
    var assignee: AgentId? = null,          // 空 = 由 Router 选人；运行时会被主持重新指派
    val dependsOn: List<NodeId> = emptyList(),
    val acceptance: AcceptanceCriteria? = null,
    var state: NodeState = NodeState.PENDING,
    var attempt: Int = 0,
    var lastError: String? = null,
    var resultArtifactId: ArtifactId? = null
)

@Serializable
data class TaskGraph(val nodes: List<TaskNode> = emptyList()) {
    fun byId(id: NodeId) = nodes.firstOrNull { it.id == id }
    fun ready(): List<TaskNode> = nodes.filter { n ->
        n.state == NodeState.PENDING && n.dependsOn.all { byId(it)?.state == NodeState.DONE }
    }
    fun finished(): Boolean = nodes.all { it.state == NodeState.DONE || it.state == NodeState.SKIPPED }
    fun progress(): Pair<Int, Int> =
        nodes.count { it.state == NodeState.DONE || it.state == NodeState.SKIPPED } to nodes.size
}

@Serializable
data class Task(
    val id: TaskId,
    val clusterId: ClusterId,
    val goal: String,
    var state: TaskState = TaskState.IDLE,
    var graph: TaskGraph = TaskGraph(),
    /** 全局验收标准：任务开始时必须先定下来，否则主持无法判断"做完了没有" */
    var acceptance: AcceptanceCriteria? = null,
    var termination: TerminationPolicy = TerminationPolicy(),
    val createdAt: Long = System.currentTimeMillis(),
    var closedAt: Long? = null,
    var closeReason: CloseReason? = null,
    var summary: String? = null,
    var turnCount: Int = 0,
    var tokenUsed: Long = 0L,
    var replanCount: Int = 0,
    /** 连续无进展计数，供停滞检测 */
    var idleStreak: Int = 0
) {
    fun isOpen() = state != TaskState.ARCHIVED && state != TaskState.ESCALATED
}

/** 任务进行中用户可注入的指令 */
sealed interface UserCommand {
    data class Pause(val taskId: TaskId) : UserCommand
    data class Resume(val taskId: TaskId) : UserCommand
    data class Reassign(val taskId: TaskId, val nodeId: NodeId, val agentId: AgentId) : UserCommand
    data class Inject(val taskId: TaskId, val text: String) : UserCommand
    data class ForceSpeaker(val taskId: TaskId, val agentId: AgentId, val text: String) : UserCommand
    data class Terminate(val taskId: TaskId, val reason: String) : UserCommand
}
