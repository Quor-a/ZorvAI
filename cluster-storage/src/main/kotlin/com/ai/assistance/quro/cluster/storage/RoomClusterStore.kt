package com.ai.assistance.quro.cluster.storage

import com.ai.assistance.quro.cluster.engine.ClusterStore
import com.ai.assistance.quro.cluster.model.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass

private val JSON = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    serializersModule = SerializersModule {
        polymorphic(ClusterEvent::class) {
            subclass(ClusterEvent.UserGoal::class)
            subclass(ClusterEvent.AcceptanceDefined::class)
            subclass(ClusterEvent.PlanProposed::class)
            subclass(ClusterEvent.SpeakerSelected::class)
            subclass(ClusterEvent.ProposalSubmitted::class)
            subclass(ClusterEvent.VerdictIssued::class)
            subclass(ClusterEvent.ArtifactProduced::class)
            subclass(ClusterEvent.ToolInvoked::class)
            subclass(ClusterEvent.BlackboardUpdated::class)
            subclass(ClusterEvent.SkillLoaded::class)
            subclass(ClusterEvent.ErrorRaised::class)
            subclass(ClusterEvent.Replanned::class)
            subclass(ClusterEvent.BudgetExceeded::class)
            subclass(ClusterEvent.ModelSwitched::class)
            subclass(ClusterEvent.Escalated::class)
            subclass(ClusterEvent.TaskClosed::class)
        }
    }
}

/** ClusterStore 的 Room 实现 */
class RoomClusterStore(private val db: ClusterDatabase) : ClusterStore {

    private val cdao get() = db.clusterDao()
    private val tdao get() = db.taskDao()

    override suspend fun saveCluster(c: Cluster) =
        cdao.putCluster(ClusterEntity(c.id.value, c.name, JSON.encodeToString(c), System.currentTimeMillis()))

    override suspend fun cluster(id: ClusterId): Cluster? =
        cdao.cluster(id.value)?.let { JSON.decodeFromString<Cluster>(it.dataJson) }

    /** 存储层本来就有 `ORDER BY updatedAt DESC` 的查询，提到接口上供 UI 枚举集群。 */
    override suspend fun allClusters(): List<Cluster> =
        cdao.clusters().mapNotNull { runCatching { JSON.decodeFromString<Cluster>(it.dataJson) }.getOrNull() }

    override suspend fun saveAgent(a: AgentConfig) =
        cdao.putAgent(AgentEntity(a.id.value, a.clusterId.value, a.enabled, JSON.encodeToString(a), System.currentTimeMillis()))

    override suspend fun agent(id: AgentId): AgentConfig? =
        cdao.agent(id.value)?.let { JSON.decodeFromString<AgentConfig>(it.dataJson) }

    override suspend fun agents(clusterId: ClusterId): List<AgentConfig> =
        cdao.agents(clusterId.value).map { JSON.decodeFromString<AgentConfig>(it.dataJson) }

    override suspend fun deleteAgent(id: AgentId) = cdao.deleteAgent(id.value)

    override suspend fun saveTask(t: Task) = tdao.putTask(
        TaskEntity(t.id.value, t.clusterId.value, t.state.name, JSON.encodeToString(t), t.createdAt)
    )

    override suspend fun task(id: TaskId): Task? =
        tdao.task(id.value)?.let { JSON.decodeFromString<Task>(it.dataJson) }

    override suspend fun tasks(clusterId: ClusterId): List<Task> =
        tdao.tasks(clusterId.value).mapNotNull { runCatching { JSON.decodeFromString<Task>(it.dataJson) }.getOrNull() }

    override suspend fun appendMessage(m: Message) =
        tdao.putMessage(MessageEntity(m.id.value, m.taskId?.value ?: "", JSON.encodeToString(m), m.ts))

    override suspend fun messages(taskId: TaskId, limit: Int): List<Message> =
        tdao.messages(taskId.value, limit).map { JSON.decodeFromString<Message>(it.dataJson) }.asReversed()

    override suspend fun appendTurn(t: Turn) =
        tdao.putTurn(TurnEntity(t.id.value, t.taskId.value, JSON.encodeToString(t), t.ts))

    override suspend fun saveArtifact(a: Artifact): Artifact {
        tdao.putArtifact(ArtifactEntity(a.id.value, a.taskId.value, JSON.encodeToString(a), a.ts))
        return a
    }

    override suspend fun artifacts(taskId: TaskId): List<Artifact> =
        tdao.artifacts(taskId.value).map { JSON.decodeFromString<Artifact>(it.dataJson) }

    override suspend fun saveBlackboard(taskId: TaskId, e: BlackboardEntry) =
        tdao.putBlackboard(BlackboardEntity(taskId.value, e.key, JSON.encodeToString(e), e.ts))

    override suspend fun blackboard(taskId: TaskId): List<BlackboardEntry> =
        tdao.blackboard(taskId.value).map { JSON.decodeFromString<BlackboardEntry>(it.dataJson) }

    override suspend fun addBudget(b: BudgetEntry) =
        tdao.putBudget(BudgetEntity(b.id, b.taskId?.value, JSON.encodeToString(b), b.ts))

    override suspend fun budgetOf(taskId: TaskId): List<BudgetEntry> =
        tdao.budget(taskId.value).map { JSON.decodeFromString<BudgetEntry>(it.dataJson) }

    override suspend fun addAudit(a: AuditLog) =
        tdao.putAudit(AuditEntity(a.id, JSON.encodeToString(a), a.ts))

    override suspend fun appendEvent(e: ClusterEvent) {
        tdao.putEvent(
            EventEntity(
                taskId = e.taskId.value,
                type = e::class.simpleName ?: "Unknown",
                dataJson = JSON.encodeToString(e),
                ts = e.ts
            )
        )
        if ((System.currentTimeMillis() and 0x3F) == 0L) tdao.trimEvents(e.taskId.value, 2_000)
    }

    override suspend fun events(taskId: TaskId): List<ClusterEvent> =
        tdao.events(taskId.value).mapNotNull {
            runCatching { JSON.decodeFromString<ClusterEvent>(it.dataJson) }.getOrNull()
        }
}
