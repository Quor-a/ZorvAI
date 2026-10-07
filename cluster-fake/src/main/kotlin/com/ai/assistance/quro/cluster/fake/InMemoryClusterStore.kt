package com.ai.assistance.quro.cluster.fake

import com.ai.assistance.quro.cluster.engine.ClusterStore
import com.ai.assistance.quro.cluster.model.*

/** 内存版存储：单元测试与演示用，不依赖 Android */
class InMemoryClusterStore : ClusterStore {
    private val agents = LinkedHashMap<AgentId, AgentConfig>()
    private val clusters = LinkedHashMap<ClusterId, Cluster>()
    private val tasks = LinkedHashMap<TaskId, Task>()
    private val msgs = ArrayList<Message>()
    private val turns = ArrayList<Turn>()
    private val arts = ArrayList<Artifact>()
    private val bb = HashMap<TaskId, MutableList<BlackboardEntry>>()
    private val budgets = ArrayList<BudgetEntry>()
    private val audits = ArrayList<AuditLog>()
    private val evts = ArrayList<ClusterEvent>()

    override suspend fun saveAgent(a: AgentConfig) { agents[a.id] = a }
    override suspend fun agent(id: AgentId) = agents[id]
    override suspend fun agents(clusterId: ClusterId) = agents.values.filter { it.clusterId == clusterId }
    override suspend fun deleteAgent(id: AgentId) { agents.remove(id) }
    override suspend fun saveCluster(c: Cluster) { clusters[c.id] = c }
    override suspend fun cluster(id: ClusterId) = clusters[id]
    override suspend fun allClusters(): List<Cluster> = clusters.values.toList()
    override suspend fun saveTask(t: Task) { tasks[t.id] = t }
    override suspend fun task(id: TaskId) = tasks[id]
    override suspend fun tasks(clusterId: ClusterId) = tasks.values.filter { it.clusterId == clusterId }
    override suspend fun appendMessage(m: Message) { msgs += m }
    override suspend fun messages(taskId: TaskId, limit: Int) =
        msgs.filter { it.taskId == taskId }.takeLast(limit)
    override suspend fun appendTurn(t: Turn) { turns += t }
    override suspend fun saveArtifact(a: Artifact): Artifact { arts += a; return a }
    override suspend fun artifacts(taskId: TaskId) = arts.filter { it.taskId == taskId }
    override suspend fun saveBlackboard(taskId: TaskId, e: BlackboardEntry) {
        bb.getOrPut(taskId) { ArrayList() }.removeAll { it.key == e.key }
        bb.getOrPut(taskId) { ArrayList() }.add(e)
    }
    override suspend fun blackboard(taskId: TaskId): List<BlackboardEntry> = bb[taskId] ?: emptyList()
    override suspend fun addBudget(b: BudgetEntry) { budgets += b }
    override suspend fun budgetOf(taskId: TaskId) = budgets.filter { it.taskId == taskId }
    override suspend fun addAudit(a: AuditLog) { audits += a }
    override suspend fun appendEvent(e: ClusterEvent) { evts += e }
    override suspend fun events(taskId: TaskId) = evts.filter { it.taskId == taskId }
}
