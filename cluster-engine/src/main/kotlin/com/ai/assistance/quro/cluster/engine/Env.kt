package com.ai.assistance.quro.cluster.engine

import com.ai.assistance.quro.cluster.bridge.HostModelBridge
import com.ai.assistance.quro.cluster.bridge.ModelGateway
import com.ai.assistance.quro.cluster.engine.capability.SkillLoader
import com.ai.assistance.quro.cluster.engine.capability.ToolRegistry
import com.ai.assistance.quro.cluster.model.*
import kotlinx.coroutines.flow.SharedFlow
import java.io.File

/** 事件出口：宿主可接自己的日志/埋点；SDK 内部会落库 */
fun interface EventSink {
    suspend fun emit(event: ClusterEvent)
}

/** 人工确认出口：宿主弹自己的对话框 */
fun interface Approver {
    suspend fun ask(prompt: String, risk: RiskLevel): Boolean
}

/** 持久化出口：由 :sdk:storage 实现，宿主也可替换成自己的存储 */
interface ClusterStore {
    suspend fun saveAgent(a: AgentConfig)
    suspend fun agent(id: AgentId): AgentConfig?
    suspend fun agents(clusterId: ClusterId): List<AgentConfig>
    suspend fun deleteAgent(id: AgentId)

    suspend fun saveCluster(c: Cluster)
    suspend fun cluster(id: ClusterId): Cluster?
    /**
     * 列出全部集群。
     *
     * 🔴 为什么是必需方法而不是让调用方自己维护 ID 列表：
     * 集群是**持久化实体**，App 重启后内存里的 ID 列表就没了。若宿主只能靠内存枚举，
     * 用户建的集群在重启后就再也找不回来 —— 表现为「明明建过，列表却是空的」。
     * 存储层本来就有 `SELECT * FROM clusters` 的查询，这里把它提到接口上。
     */
    suspend fun allClusters(): List<Cluster>

    suspend fun saveTask(t: Task)
    suspend fun task(id: TaskId): Task?
    /** 按集群列出任务（任务历史 / 回放入口）。 */
    suspend fun tasks(clusterId: ClusterId): List<Task>

    suspend fun appendMessage(m: Message)
    suspend fun messages(taskId: TaskId, limit: Int = 100): List<Message>
    suspend fun appendTurn(t: Turn)

    suspend fun saveArtifact(a: Artifact): Artifact
    suspend fun artifacts(taskId: TaskId): List<Artifact>

    suspend fun saveBlackboard(taskId: TaskId, e: BlackboardEntry)
    suspend fun blackboard(taskId: TaskId): List<BlackboardEntry>

    suspend fun addBudget(b: BudgetEntry)
    suspend fun budgetOf(taskId: TaskId): List<BudgetEntry>
    suspend fun addAudit(a: AuditLog)

    suspend fun appendEvent(e: ClusterEvent)
    suspend fun events(taskId: TaskId): List<ClusterEvent>
}

/** 引擎运行所需的全部外部依赖。宿主最少只需提供 bridge + filesDir。 */
class EngineEnv(
    val bridge: HostModelBridge,
    val store: ClusterStore,
    val filesDir: File,
    val event: EventSink = EventSink { },
    val approver: Approver = Approver { _, _ -> false },
    val clock: () -> Long = { System.currentTimeMillis() }
) {
    /** clusterId → 用于记账 */
    var activeClusterId: ClusterId = ClusterId("default")

    val gateway: ModelGateway by lazy {
        ModelGateway(
            bridge = bridge,
            spend = { agentId, taskId, modelId, tokens ->
                store.addBudget(
                    BudgetEntry(
                        id = newId("bud"), clusterId = activeClusterId, taskId = taskId,
                        agentId = agentId, hostModelId = modelId,
                        tokensIn = 0, tokensOut = tokens, ts = clock()
                    )
                )
            },
            onSwitch = { _, from, to, reason -> lastSwitch = Triple(from, to, reason) },
            clock = clock
        )
    }
    val skills: SkillLoader by lazy { SkillLoader(File(filesDir, "skills")) }
    val tools: ToolRegistry by lazy { ToolRegistry() }
    val prompts: PromptAssembler by lazy { PromptAssembler() }

    @Volatile var lastSwitch: Triple<String, String, String>? = null
}

/** 引擎对外暴露的实时状态流 */
interface ClusterObservable {
    fun events(taskId: TaskId): SharedFlow<ClusterEvent>
}
