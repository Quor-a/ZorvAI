package com.ai.assistance.quro.core.cluster

import org.json.JSONArray
import org.json.JSONObject

/**
 * 集群领域模型。
 *
 * 设计前提：**不引入新的存储体系、不引入新的依赖**。
 * 角色复用已有的 QuroPersona（人格卡），只在其上补一层 RoleProfile，
 * 用与 QuroPersonaRepository 相同的 JSON 文件风格持久化。
 */

// ————————————————— 模型 —————————————————

enum class ModelKind { CLOUD, LOCAL }

/**
 * 一条"宿主已配置好的模型"。
 *
 * id 的构成规则（稳定、可反解）：
 *   云端当前配置 → "cloud:current"
 *   自定义厂商   → "cloud:<providerId>"
 *   离线模型     → "local:<modelId>"
 */
data class ModelProfile(
    val id: String,
    val displayName: String,
    val kind: ModelKind,
    /** 云端：provider 名；端侧：留空 */
    val provider: String = "",

    /**
     * 该模型专属的 API 基址；空 = 沿用全局「当前」配置（兼容旧行为）。
     *
     * 为什么必须有：集群允许多个角色绑**不同厂商的模型**。若没有这一层，
     * gateway 只能拿到全局那一个 baseUrl/apiKey，于是「角色绑的模型名」会被
     * 发到别的厂商的端点上 —— 表现为一用多厂商就 404 / 模型不存在。
     */
    val baseUrl: String = "",
    /** 该模型专属的 apiKey；空 = 沿用全局配置 */
    val apiKey: String = "",    /** 端侧模型文件标识，传给 QuroLocalEngine */
    val localModelId: String = "",
    val supportsTools: Boolean = true,
    val supportsJsonMode: Boolean = true,
    /** 端侧填 1，云端可填 2~3；决定并发闸门 */
    val maxConcurrency: Int = 2,
    val contextWindow: Int = 0,
    val available: Boolean = true
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("displayName", displayName); put("kind", kind.name)
        put("provider", provider); put("localModelId", localModelId)
        put("supportsTools", supportsTools); put("supportsJsonMode", supportsJsonMode)
        put("maxConcurrency", maxConcurrency); put("contextWindow", contextWindow)
        put("available", available)
    }

    companion object {
        fun fromJson(o: JSONObject): ModelProfile = ModelProfile(
            id = o.optString("id"),
            displayName = o.optString("displayName"),
            kind = runCatching { ModelKind.valueOf(o.optString("kind", "CLOUD")) }.getOrDefault(ModelKind.CLOUD),
            provider = o.optString("provider"),
            localModelId = o.optString("localModelId"),
            supportsTools = o.optBoolean("supportsTools", true),
            supportsJsonMode = o.optBoolean("supportsJsonMode", true),
            maxConcurrency = o.optInt("maxConcurrency", 2),
            contextWindow = o.optInt("contextWindow", 0),
            available = o.optBoolean("available", true)
        )
    }
}

// ————————————————— 角色 —————————————————

enum class RoleKind {
    /** 主持：唯一、不可替换、不可删除 */
    HOST,
    /** 专家：可被主持点名 */
    EXPERT,
    /** 评审：只对产物做验收，不参与生产 */
    CRITIC
}

/** 该角色能"看到"什么 —— 请求隔离在上下文层面的体现 */
data class RoleContextPolicy(
    /** 历史轮次窗口 */
    val historyRounds: Int = 8,
    /** 可见工具白名单，为空=全部（建议主持用 coreSpecs，专家按需裁剪） */
    val toolWhitelist: Set<String> = emptySet(),
    /** 是否可见其他角色的发言（提案阶段应关掉，避免从众） */
    val seeOtherRoles: Boolean = true,
    /** 是否可见集群共享记忆 */
    val seeSharedMemory: Boolean = true,
    val maxTokens: Int = 4096,
    val temperature: Float = 0.7f
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("historyRounds", historyRounds)
        put("toolWhitelist", JSONArray(toolWhitelist.toList()))
        put("seeOtherRoles", seeOtherRoles)
        put("seeSharedMemory", seeSharedMemory)
        put("maxTokens", maxTokens)
        put("temperature", temperature.toDouble())
    }

    companion object {
        fun fromJson(o: JSONObject): RoleContextPolicy = RoleContextPolicy(
            historyRounds = o.optInt("historyRounds", 8),
            toolWhitelist = o.optJSONArray("toolWhitelist")?.let { a ->
                (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() }.toSet()
            } ?: emptySet(),
            seeOtherRoles = o.optBoolean("seeOtherRoles", true),
            seeSharedMemory = o.optBoolean("seeSharedMemory", true),
            maxTokens = o.optInt("maxTokens", 4096),
            temperature = o.optDouble("temperature", 0.7).toFloat()
        )
    }
}

/**
 * 角色配置 = 已有的人格卡 + 集群元信息。
 * personaId 指向 QuroPersona.id，身份/头像/系统提示词全部复用现成的。
 */
data class RoleProfile(
    val personaId: String,
    /** 宿主已配置模型的 id（见 ModelProfile.id） */
    val modelProfileId: String = "",
    /** 降级链，同样是 ModelProfile.id */
    val fallbackModelIds: List<String> = emptyList(),
    val role: RoleKind = RoleKind.EXPERT,
    val duties: List<String> = emptyList(),
    val taboos: List<String> = emptyList(),
    val skills: List<String> = emptyList(),
    val context: RoleContextPolicy = RoleContextPolicy(),
    val enabled: Boolean = true,
    val version: Int = 1
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("personaId", personaId); put("modelProfileId", modelProfileId)
        put("fallbackModelIds", JSONArray(fallbackModelIds))
        put("role", role.name)
        put("duties", JSONArray(duties)); put("taboos", JSONArray(taboos)); put("skills", JSONArray(skills))
        put("context", context.toJson())
        put("enabled", enabled); put("version", version)
    }

    companion object {
        fun fromJson(o: JSONObject): RoleProfile = RoleProfile(
            personaId = o.optString("personaId"),
            modelProfileId = o.optString("modelProfileId"),
            fallbackModelIds = o.optJSONArray("fallbackModelIds")?.let { a ->
                (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() }
            } ?: emptyList(),
            role = runCatching { RoleKind.valueOf(o.optString("role", "EXPERT")) }.getOrDefault(RoleKind.EXPERT),
            duties = o.optJSONArray("duties")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList(),
            taboos = o.optJSONArray("taboos")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList(),
            skills = o.optJSONArray("skills")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList(),
            context = o.optJSONObject("context")?.let { RoleContextPolicy.fromJson(it) } ?: RoleContextPolicy(),
            enabled = o.optBoolean("enabled", true),
            version = o.optInt("version", 1)
        )
    }
}

// ————————————————— 集群 —————————————————

enum class ClusterMode {
    /** 主持中枢（默认） */
    SUPERVISOR,
    /** 多角色并行提案 + 主持裁决 */
    COUNCIL,
    /** 线性流水线 */
    PIPELINE
}

/** 防死循环的熔断参数 */
data class ClusterBudget(
    val maxTurns: Int = 40,
    val maxReplan: Int = 3,
    val maxTokensPerRole: Int = 120_000,
    /** 连续 N 轮无新产物视为停滞 */
    val noProgressWindow: Int = 3,
    /** 单节点连败次数上限，超过即跳过 */
    val maxNodeAttempts: Int = 3,
    /** 参与提案的角色数上限 */
    val proposeFanout: Int = 3,
    /** 并行提案：关掉会出现从众幻觉，默认必须开 */
    val parallelPropose: Boolean = true
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("maxTurns", maxTurns); put("maxReplan", maxReplan)
        put("maxTokensPerRole", maxTokensPerRole); put("noProgressWindow", noProgressWindow)
        put("maxNodeAttempts", maxNodeAttempts); put("proposeFanout", proposeFanout)
        put("parallelPropose", parallelPropose)
    }

    companion object {
        fun fromJson(o: JSONObject): ClusterBudget = ClusterBudget(
            maxTurns = o.optInt("maxTurns", 40),
            maxReplan = o.optInt("maxReplan", 3),
            maxTokensPerRole = o.optInt("maxTokensPerRole", 120_000),
            noProgressWindow = o.optInt("noProgressWindow", 3),
            maxNodeAttempts = o.optInt("maxNodeAttempts", 3),
            proposeFanout = o.optInt("proposeFanout", 3),
            parallelPropose = o.optBoolean("parallelPropose", true)
        )
    }
}

data class ClusterConfig(
    val id: String,
    val name: String,
    val mode: ClusterMode = ClusterMode.SUPERVISOR,
    val budget: ClusterBudget = ClusterBudget(),
    /** 验收标准：没有它主持无法判断"做完没有" */
    val acceptance: List<String> = emptyList()
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("name", name); put("mode", mode.name)
        put("budget", budget.toJson()); put("acceptance", JSONArray(acceptance))
    }

    companion object {
        fun fromJson(o: JSONObject): ClusterConfig = ClusterConfig(
            id = o.optString("id"),
            name = o.optString("name"),
            mode = runCatching { ClusterMode.valueOf(o.optString("mode", "SUPERVISOR")) }.getOrDefault(ClusterMode.SUPERVISOR),
            budget = o.optJSONObject("budget")?.let { ClusterBudget.fromJson(it) } ?: ClusterBudget(),
            acceptance = o.optJSONArray("acceptance")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList()
        )
    }
}

// ————————————————— 任务与事件 —————————————————

enum class NodeState { PENDING, READY, PROPOSING, EXECUTING, DONE, FAILED, SKIPPED }

data class ClusterNode(
    val id: String,
    val title: String,
    val instruction: String = "",
    var assignee: String? = null,
    val dependsOn: List<String> = emptyList(),
    var state: NodeState = NodeState.PENDING,
    var attempt: Int = 0,
    var lastError: String? = null,
    var artifact: String? = null
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("title", title); put("instruction", instruction)
        put("assignee", assignee ?: ""); put("dependsOn", JSONArray(dependsOn))
        put("state", state.name); put("attempt", attempt)
        put("lastError", lastError ?: ""); put("artifact", artifact ?: "")
    }

    companion object {
        fun fromJson(o: JSONObject): ClusterNode = ClusterNode(
            id = o.optString("id"), title = o.optString("title"), instruction = o.optString("instruction"),
            assignee = o.optString("assignee").takeIf { it.isNotBlank() },
            dependsOn = o.optJSONArray("dependsOn")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList(),
            state = runCatching { NodeState.valueOf(o.optString("state", "PENDING")) }.getOrDefault(NodeState.PENDING),
            attempt = o.optInt("attempt", 0),
            lastError = o.optString("lastError").takeIf { it.isNotBlank() },
            artifact = o.optString("artifact").takeIf { it.isNotBlank() }
        )
    }
}

enum class ClusterTaskState {
    IDLE, INTAKE, DISPATCHING, PROPOSING, ARBITRATING,
    EXECUTING, VERIFYING, REPLANNING, CONVERGING, CLOSED, ESCALATED
}

/** 主持合法的"下班方式"只有这四种 */
enum class CloseReason { GOAL_REACHED, CIRCUIT_BROKEN, USER_ABORTED, UNRECOVERABLE }

data class ClusterTask(
    val id: String,
    val clusterId: String,
    val goal: String,
    var state: ClusterTaskState = ClusterTaskState.IDLE,
    var acceptance: List<String> = emptyList(),
    val nodes: MutableList<ClusterNode> = mutableListOf(),
    var turnCount: Int = 0,
    var tokenUsed: Int = 0,
    var replanCount: Int = 0,
    var idleStreak: Int = 0,
    var closeReason: CloseReason? = null,
    var summary: String? = null,
    val createdAt: Long = System.currentTimeMillis()
) {
    fun finished(): Boolean = nodes.all { it.state == NodeState.DONE || it.state == NodeState.SKIPPED }
    fun ready(): List<ClusterNode> = nodes.filter { n ->
        n.state == NodeState.PENDING && n.dependsOn.all { d -> nodes.firstOrNull { it.id == d }?.state == NodeState.DONE }
    }
    fun progress(): Pair<Int, Int> =
        nodes.count { it.state == NodeState.DONE || it.state == NodeState.SKIPPED } to nodes.size
}

/** 事件：复用 QuroAgentTrace 之外再发一份 Flow，供 UI 多轨道渲染 */
sealed interface ClusterEvent {
    val taskId: String
    val ts: Long

    data class Started(override val taskId: String, val goal: String, override val ts: Long = now()) : ClusterEvent
    data class AcceptanceDefined(override val taskId: String, val items: List<String>, override val ts: Long = now()) : ClusterEvent
    data class Planned(override val taskId: String, val count: Int, override val ts: Long = now()) : ClusterEvent
    data class SpeakerSelected(override val taskId: String, val personaId: String, val reason: String, override val ts: Long = now()) : ClusterEvent
    data class RoleUtterance(override val taskId: String, val personaId: String, val text: String, val streaming: Boolean = false, override val ts: Long = now()) : ClusterEvent
    data class ArtifactProduced(override val taskId: String, val nodeId: String, val personaId: String, val title: String, override val ts: Long = now()) : ClusterEvent
    data class Verdict(override val taskId: String, val nodeId: String, val pass: Boolean, val reason: String, override val ts: Long = now()) : ClusterEvent
    data class ModelSwitched(override val taskId: String, val personaId: String, val from: String, val to: String, val reason: String, override val ts: Long = now()) : ClusterEvent
    data class Error(override val taskId: String, val personaId: String?, val message: String, override val ts: Long = now()) : ClusterEvent
    data class Replanned(override val taskId: String, val round: Int, val reason: String, override val ts: Long = now()) : ClusterEvent
    data class Closed(override val taskId: String, val reason: CloseReason, val summary: String?, override val ts: Long = now()) : ClusterEvent

    companion object { fun now(): Long = System.currentTimeMillis() }
}
