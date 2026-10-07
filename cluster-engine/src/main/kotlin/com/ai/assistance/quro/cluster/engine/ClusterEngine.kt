package com.ai.assistance.quro.cluster.engine

import com.ai.assistance.quro.cluster.bridge.HostModelBridge
import com.ai.assistance.quro.cluster.bridge.HostModelInfo
import com.ai.assistance.quro.cluster.engine.capability.Tool
import com.ai.assistance.quro.cluster.engine.memory.KeywordSemanticMemory
import com.ai.assistance.quro.cluster.engine.memory.SemanticMemory
import com.ai.assistance.quro.cluster.engine.orchestration.HostAgent
import com.ai.assistance.quro.cluster.engine.orchestration.Router
import com.ai.assistance.quro.cluster.model.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * 集群引擎 —— 宿主 App 唯一需要打交道的门面。
 *
 * 典型用法（宿主侧 5 行接入）：
 * ```
 * val engine = ClusterEngine.install(context.filesDir, MyHostModelBridge())
 * val clusterId = engine.createCluster("写作天团")
 * engine.addAgent(clusterId, AgentConfig(...))
 * val taskId = engine.submit(clusterId, "写一篇关于…的深度文章")
 * engine.run(taskId)   // 主持接管，直到闭环
 * ```
 */
class ClusterEngine private constructor(
    val env: EngineEnv,
    private val scope: CoroutineScope
) {
    private val runner = AgentRunner(env)
    private val router = Router(env)
    private val memory: SemanticMemory = KeywordSemanticMemory()
    private val _allEvents = MutableSharedFlow<ClusterEvent>(extraBufferCapacity = 256)

    //🔴🔴🔴 #181「集群协作中一直转圈、对话框一个字都没有」的真凶。
    //
    // 此前 `_allEvents` **只有声明和读取，从不 emit**：宿主侧
    // `e.events().collect { projector.project(it) }` 订阅的就是这条流，
    // 于是它永远收不到任何事件 —— 投影器写得再对也是空转。
    // 而 [HostAgent.emit] 发去的是 `env.event`（install 时未注入 = 空实现）
    // 和 `HostAgent._events`（宿主根本没订阅）—— 两条路都接不到 UI。
    //
    // 修法：在 init 里把主持的事件流**转发**到本引擎的总线。
    // 为什么用转发而不是让 HostAgent 直接 emit：HostAgent 是引擎内部组件，
    // 不该知道对外总线的存在；`HostAgent.events` 是它已有的公开出口。
    private val host = HostAgent(env, runner, router)

    init {
        scope.launch {
            host.events.collect { _allEvents.emit(it) }
        }
    }

    val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    companion object {
        @Volatile private var instance: ClusterEngine? = null

        /**
         * 安装引擎（进程内单例）。
         * @param filesDir 宿主给的私有目录，用于放 Skill 与导出文件
         * @param bridge 宿主实现的模型桥
         * @param store 存储实现（默认由 :sdk:storage 提供 Room 实现）
         */
        fun install(
            filesDir: File,
            bridge: HostModelBridge,
            store: ClusterStore,
            approver: Approver = Approver { _, _ -> false },
            scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        ): ClusterEngine {
            val env = EngineEnv(bridge, store, filesDir, approver = approver)
            val engine = ClusterEngine(env, scope)
            bridge.setModelChangeListener { env.gateway.invalidate() }
            scope.launch { env.skills.loadFromDisk() }
            instance = engine
            return engine
        }

        fun get(): ClusterEngine = instance ?: error("请先调用 ClusterEngine.install()")
    }

    // ——————————— 模型（全部来自宿主配置） ———————————

    /** 给集群 UI 的模型下拉直接用：列出宿主配置界面里已有的模型 */
    suspend fun hostModels(forceRefresh: Boolean = false): List<HostModelInfo> =
        env.gateway.models(forceRefresh)

    fun invalidateModels() = env.gateway.invalidate()

    // ——————————— 集群与角色 ———————————

    suspend fun createCluster(name: String, description: String = ""): ClusterId {
        val c = Cluster(ClusterId(newId("cl")), name, description)
        env.store.saveCluster(c)
        return c.id
    }

    /**
     * 列出全部集群。
     *
     * 🔴 旧实现返回 `recentClusters`（内存里的 LinkedHashSet），只在本次进程里
     * 提交过任务的集群才在列 —— App 一重启集合就空，用户建过的集群全部消失，
     * 表现为「明明建过，列表却是空的」。现在直接查存储（DAO 本来就有这条查询）。
     *
     * 排序沿用存储层：最近更新的在前。
     */
    suspend fun clusters(): List<Cluster> = env.store.allClusters()

    /** 某个集群下的全部任务，供 UI 做任务历史与回放。 */
    suspend fun tasks(clusterId: ClusterId): List<Task> = env.store.tasks(clusterId)

    private val recentClusters = java.util.Collections.synchronizedSet(LinkedHashSet<ClusterId>())

    suspend fun cluster(id: ClusterId): Cluster? = env.store.cluster(id)

    suspend fun addAgent(clusterId: ClusterId, agent: AgentConfig): AgentId {
        val a = agent.copy(clusterId = clusterId)
        env.store.saveAgent(a)
        env.store.addAudit(
            AuditLog(newId("aud"), env.clock(), "user", AuditAction.AGENT_CREATE, a.id.value)
        )
        return a.id
    }

    suspend fun updateAgent(agent: AgentConfig) {
        val old = env.store.agent(agent.id)
        env.store.saveAgent(agent)
        env.store.addAudit(
            AuditLog(newId("aud"), env.clock(), "user", AuditAction.AGENT_UPDATE, agent.id.value,
                beforeJson = old?.let { json.encodeToString(it) }, afterJson = json.encodeToString(agent))
        )
    }

    /**
     * 删除角色。主持不可删 —— 这是硬约束，任何调用都会抛异常。
     */
    suspend fun removeAgent(agentId: AgentId) {
        require(agentId != HOST_AGENT_ID) { "主持不可删除" }
        env.store.deleteAgent(agentId)
        env.store.addAudit(AuditLog(newId("aud"), env.clock(), "user", AuditAction.AGENT_DELETE, agentId.value))
    }

    suspend fun agents(clusterId: ClusterId): List<AgentConfig> = env.store.agents(clusterId)

    /** 更新主持的能力（模型 / 技能 / 编排 / 终止策略）。身份不在此列，改不了。 */
    suspend fun updateHost(clusterId: ClusterId, mutate: (HostConfig) -> HostConfig) {
        val c = env.store.cluster(clusterId) ?: return
        val before = c.host
        val after = mutate(c.host)
        env.store.saveCluster(c.copy(host = after))
        env.store.addAudit(
            AuditLog(newId("aud"), env.clock(), "user", AuditAction.HOST_CONFIG_UPDATE, clusterId.value,
                beforeJson = json.encodeToString(before), afterJson = json.encodeToString(after))
        )
    }

    // ——————————— 工具 ———————————

    fun registerTool(tool: Tool) = env.tools.register(tool)
    fun grantTool(agentId: AgentId, toolId: ToolId) = env.tools.grant(agentId, setOf(toolId))

    /**
     * 撤销工具授权。
     *
     * 与 [grantTool] 成对：[ToolRegistry.revoke] 早就存在，但此前**没有任何调用方**
     * —— 因为角色编辑器里没有工具授权的UI，用户根本无法取消勾选。
     * 补上这个方法，编辑器才能做「勾选/取消」的双向操作。
     */
    fun revokeTool(agentId: AgentId, toolId: ToolId) = env.tools.revoke(agentId, toolId)

    /** 某角色当前已授权的工具 id（供编辑器渲染勾选状态）。 */
    fun grantedToolIds(agentId: AgentId): Set<ToolId> = env.tools.grantedIds(agentId)

    // ——————————— 任务 ———————————

    suspend fun submit(clusterId: ClusterId, goal: String): TaskId {
        val t = Task(TaskId(newId("task")), clusterId, goal, state = TaskState.IDLE)
        env.store.saveTask(t)
        recentClusters += clusterId
        env.activeClusterId = clusterId
        return t.id
    }

    /** 同步跑到闭环。宿主可在自己的协程/服务里调用。 */
    suspend fun run(taskId: TaskId): CloseReason {
        val t = env.store.task(taskId) ?: error("任务不存在: $taskId")
        val c = env.store.cluster(t.clusterId) ?: error("集群不存在")
        env.activeClusterId = t.clusterId
        return host.drive(c, t, memory)
    }

    /** 异步跑，宿主不阻塞 */
    fun runAsync(taskId: TaskId, onDone: (CloseReason) -> Unit = {}) {
        scope.launch {
            val r = runCatching { run(taskId) }.getOrElse { CloseReason.UNRECOVERABLE }
            onDone(r)
        }
    }

    fun command(cmd: UserCommand) = host.submitCommand(cmd)

    fun events(): Flow<ClusterEvent> = _allEvents.asSharedFlow()

    suspend fun task(id: TaskId): Task? = env.store.task(id)
    suspend fun messages(taskId: TaskId, limit: Int = 200) = env.store.messages(taskId, limit)
    suspend fun artifacts(taskId: TaskId) = env.store.artifacts(taskId)
    suspend fun blackboard(taskId: TaskId) = env.store.blackboard(taskId)
    suspend fun budget(taskId: TaskId) = env.store.budgetOf(taskId)
    suspend fun replay(taskId: TaskId): List<ClusterEvent> = env.store.events(taskId)

    // ——————————— AI 自演化（可审批、可回滚） ———————————

    /**
     * AI 自创角色：由某个角色提出"我缺一个 X 助手"，生成配置后等待用户确认。
     * @return 待审批的角色配置；用户确认后调 addAgent 落库
     */
    suspend fun forgeAgentDraft(
        clusterId: ClusterId, proposerId: AgentId, spec: String
    ): AgentConfig {
        val proposer = env.store.agent(proposerId) ?: error("提议者不存在")
        val out = runner.run(
            proposer,
            TurnContext(
                clusterId = clusterId,
                task = Task(TaskId("draft"), clusterId, spec),
                blackboard = com.ai.assistance.quro.cluster.engine.memory.Blackboard(TaskId("draft")),
                memory = memory,
                intent = TurnIntent.CHAT,
                jsonMode = true,
                instruction = """
请为新角色生成完整配置。只输出 JSON：
{"name":"…","persona":"…","duties":["…"],"taboos":["…"],"triggerKeywords":["…"],"systemPrompt":"…"}
新角色需求：$spec
不得申请 SYSTEM 权限或任何越权能力。
                """.trimIndent()
            )
        ).getOrThrow()

        val o = runCatching {
            json.parseToJsonElement(out.text.substring(out.text.indexOf('{'))).jsonObject
        }.getOrNull()

        return AgentConfig(
            id = AgentId(newId("ag")),
            clusterId = clusterId,
            identity = Identity(
                name = o?.get("name")?.jsonPrimitive?.content ?: "新角色",
                avatarEmoji = "✨"
            ),
            persona = o?.get("persona")?.jsonPrimitive?.content ?: "",
            duties = o?.get("duties")?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: listOf(spec),
            taboos = o?.get("taboos")?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList(),
            triggerKeywords = o?.get("triggerKeywords")?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }
                ?: emptyList(),
            systemPrompt = o?.get("systemPrompt")?.jsonPrimitive?.content ?: "",
            meta = Meta(provenance = Provenance.AI_CREATED, approval = ApprovalState.PENDING, createdBy = proposerId)
        )
    }

    /** AI 自创 Skill：静态扫描通过后待用户确认安装 */
    suspend fun forgeSkillDraft(skillId: SkillId, raw: String): Result<SkillManifest> {
        val (m, body) = com.ai.assistance.quro.cluster.engine.capability.SkillParser.parse(skillId, raw, "")
        val issues = com.ai.assistance.quro.cluster.engine.capability.SkillParser.scan(body, m.permissions)
        if (issues.any { it.contains("危险指令") }) return Result.failure(SecurityException(issues.joinToString("; ")))
        return Result.success(m)
    }

    suspend fun installSkill(skillId: SkillId, raw: String): Result<SkillManifest> =
        env.skills.install(skillId, raw)

    suspend fun skills() = env.skills.list()

    // ——————————— 导入导出 ———————————

    suspend fun exportCluster(clusterId: ClusterId): String {
        val c = env.store.cluster(clusterId) ?: return "{}"
        val agents = env.store.agents(clusterId)
        return json.encodeToString(
            ClusterSnapshot(c, agents)
        )
    }

    suspend fun importCluster(payload: String, newName: String? = null): ClusterId {
        val snap = json.decodeFromString<ClusterSnapshot>(payload)
        val nid = ClusterId(newId("cl"))
        val c = snap.cluster.copy(id = nid, name = newName ?: snap.cluster.name)
        env.store.saveCluster(c)
        snap.agents.forEach { a ->
            env.store.saveAgent(a.copy(id = AgentId(newId("ag")), clusterId = nid))
        }
        return nid
    }
}
