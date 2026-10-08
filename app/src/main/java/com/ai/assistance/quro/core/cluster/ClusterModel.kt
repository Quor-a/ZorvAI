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

/**
 * 集群分工。
 *
 * #190：原来只有 HOST/EXPERT/CRITIC 三档，缺「谁执行」——所有活都落在 EXPERT 头上，
 * EXPERT 既出方案又动手实现，等于没人真正干活。现按**流水线阶段**拆开：
 * 规划 → 生产 → 验收，三段各归各的kind，选人打分与提示词都按 kind 走。
 */
enum class RoleKind {
    /** 主持：唯一、不可替换、不可删除 */
    HOST,
    /** 规划：拆解目标、定义验收标准、定方案，不动手产出 */
    PLANNER,
    /** 生产：真正动手执行（写代码/做设计/写文档/操作设备），持有工具权限 */
    EXECUTOR,
    /** 专家：提供领域判断与方案评审，可产出内容但不负责最终验收 */
    EXPERT,
    /** 评审：只对产物做验收，不参与生产 */
    CRITIC
    ;

    /** 该 kind 是否允许真正动手（调工具、产出文件） */
    val canExecute: Boolean get() = this == EXECUTOR || this == EXPERT

    /** 该 kind 是否只做验收 */
    val isVerifier: Boolean get() = this == CRITIC

    val label: String
        get() = when (this) {
            HOST -> "主持"
            PLANNER -> "规划"
            EXECUTOR -> "执行"
            EXPERT -> "专家"
            CRITIC -> "评审"
        }

    companion object {
        /** UI 下拉用的全部分工（不含主持，主持唯一不可选） */
        val selectable: List<RoleKind> = listOf(PLANNER, EXECUTOR, EXPERT, CRITIC)
    }
}

/** 该角色能"看到"什么 —— 请求隔离在上下文层面的体现 */
data class RoleContextPolicy(
    /** 历史轮次窗口 */
    val historyRounds: Int = 8,
    /**
     * 可见工具白名单，为空=全部（建议主持用 coreSpecs，专家按需裁剪）。
     *
     * #190：这个字段此前**存了但没人读** —— 角色照样拿不到工具。
     * 现由 [com.ai.assistance.quro.core.cluster.ClusterSkillRuntime] 在组装角色请求时真实过滤，
     * 非空=只下发这些，角色连看都看不到别的工具（不给它幻觉的机会）。
     */
    val toolWhitelist: Set<String> = emptySet(),
    /** 是否可见其他角色的发言（提案阶段应关掉，避免从众） */
    val seeOtherRoles: Boolean = true,
    /** 是否可见集群共享记忆 */
    val seeSharedMemory: Boolean = true,
    /**
     * 🔴 #209：**已停用**，0 = 跟随模型配置（[com.ai.assistance.quro.core.model.QuroModelConfig.maxTokens]）。
     *
     * 以前这里是角色级输出上限（默认 4096，UI 保存时 coerceIn 到 <=32768），
     * 而模型配置明明是 65536 —— 等于同一件事配两遍、且第二遍只会更小。
     * 保留字段只为 JSON 向后兼容，不再参与任何限制。
     */
    val maxTokens: Int = 0,
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
            maxTokens = o.optInt("maxTokens", 0),
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
    /**
     * #190：绑定的技能 id 集合。
     *
     * 🔴 #202：这里的 id 全部来自**集群自己的**技能库
     * （[com.ai.assistance.quro.core.cluster.ClusterSkillStore]，前缀 `cluster_`），
     * **不再**对应宿主全局技能库。用户明确要求「集群实现自己的技能 skills，要分开」，
     * 全局 `QuroSkillStore` 与集群零耦合。
     *
     * 与 [skills] 的区别是**语义**：[skills] 只是提示词里的几行说明文字，
     * 模型据此凭空发挥；本字段是**真技能** —— 技能正文会被完整注入该角色的 system prompt。
     *
     * 集群技能**没有** function-calling 形态（无 `callable`、不生成 `skill__xxx` 工具），
     * 所以这里只注入正文，不存在「激活」这一说。
     *
     * 存 id 而不存名字：id 稳定，改了技能显示名不会丢绑定。
     */
    val skillIds: List<String> = emptyList(),
    val context: RoleContextPolicy = RoleContextPolicy(),
    val enabled: Boolean = true,
    val version: Int = 1
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("personaId", personaId); put("modelProfileId", modelProfileId)
        put("fallbackModelIds", JSONArray(fallbackModelIds))
        put("role", role.name)
        put("duties", JSONArray(duties)); put("taboos", JSONArray(taboos)); put("skills", JSONArray(skills))
        put("skillIds", JSONArray(skillIds))
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
            skillIds = o.optJSONArray("skillIds")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList(),
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
    val parallelPropose: Boolean = true,
    /**
     * #190：单个角色单次发言允许的 ReAct 轮数（工具调用往返次数）。
     *
     * 1=纯文本（等同旧行为）；6 足够「查资料 → 写文件 → 读回校验」这类真实链路。
     * 上限保护：模型可能反复调同一个工具，无上限会烧光 token 预算。
     */
    val reactRounds: Int = 6,

    /**
     * #192：开始前是否做能力覆盖检查（文档 V2 建议默认开）。
     *
     * 关掉它，集群会回到旧行为：派活时只看职责文本相关度，
     * 没人具备的能力也让角色硬上 → 模型凭空想象。
     * 保留这个开关是为了在**离线且角色已配好**的场景下省掉一轮核对开销。
     */
    val capabilityCheck: Boolean = true,

    /**
     * #192：单任务最多补救几个能力缺口。
     *
     * 补救会联网装技能，每个都要真实 HTTP 请求。不设上限的话，
     * 一个拆出 8 个节点的任务可能触发十几次网络往返。
     * 超限的缺口不会静默放过 —— 会在 [ClusterTask.capabilityNotes] 里如实记为未覆盖。
     */
    val maxRemedies: Int = 3
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("maxTurns", maxTurns); put("maxReplan", maxReplan)
        put("maxTokensPerRole", maxTokensPerRole); put("noProgressWindow", noProgressWindow)
        put("maxNodeAttempts", maxNodeAttempts); put("proposeFanout", proposeFanout)
        put("parallelPropose", parallelPropose)
        put("reactRounds", reactRounds)
        put("capabilityCheck", capabilityCheck)
        put("maxRemedies", maxRemedies)
    }

    companion object {
        fun fromJson(o: JSONObject): ClusterBudget = ClusterBudget(
            maxTurns = o.optInt("maxTurns", 40),
            maxReplan = o.optInt("maxReplan", 3),
            maxTokensPerRole = o.optInt("maxTokensPerRole", 120_000),
            noProgressWindow = o.optInt("noProgressWindow", 3),
            maxNodeAttempts = o.optInt("maxNodeAttempts", 3),
            proposeFanout = o.optInt("proposeFanout", 3),
            parallelPropose = o.optBoolean("parallelPropose", true),
            reactRounds = o.optInt("reactRounds", 6),
            capabilityCheck = o.optBoolean("capabilityCheck", true),
            maxRemedies = o.optInt("maxRemedies", 3)
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

enum class NodeState {
    PENDING, READY, PROPOSING, EXECUTING, DONE, FAILED, SKIPPED;

    /**
     * 是否已落到终态（不会再被调度）。
     *
     * 🔴 #198：必须显式列出终态，不能用「不是 PENDING 就是终态」那种取反写法 ——
     * 取反会把新加的中间态也当终态，任务会提前收敛。
     */
    fun isTerminal(): Boolean = this == DONE || this == FAILED || this == SKIPPED
}

data class ClusterNode(
    val id: String,
    val title: String,
    val instruction: String = "",
    /**
     * #192：这一节点需要什么能力（如「视觉设计」「数据统计」）。
     *
     * 为什么必须有这个字段：没有它，集群只能拿标题做关键词匹配去猜谁合适，
     * 猜错了就是「没人具备这个能力」的角色硬上 → 模型只能凭空想象。
     * 有了它，[ClusterCapability] 才能在派活前**如实核对**：
     * 到底有没有人真具备这项能力，没有就先去补，补不到就明确告知。
     *
     * 主持在 INTAKE 阶段负责声明；为空时退回「标题+指令」做兜底匹配。
     */
    val ability: String = "",
    var assignee: String? = null,
    val dependsOn: List<String> = emptyList(),
    var state: NodeState = NodeState.PENDING,
    var attempt: Int = 0,
    var lastError: String? = null,
    var artifact: String? = null,
    /**
     * #207：产物**未经有效验收**就被放行了。
     *
     * 两种情况会置位：验收方输出连续解析不出结论、或连续判不通过达到重试上限。
     * 置位后 [ClusterEngine.converging] 必须在总结里点名，
     * 否则「降级放行」会被无条件报成 GOAL_REACHED —— 那只是把熔断换成假闭环。
     */
    var releaseUnverified: Boolean = false
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("title", title); put("instruction", instruction)
        put("ability", ability)
        put("assignee", assignee ?: ""); put("dependsOn", JSONArray(dependsOn))
        put("state", state.name); put("attempt", attempt)
        put("lastError", lastError ?: ""); put("artifact", artifact ?: "")
        put("releaseUnverified", releaseUnverified)
    }

    companion object {
        fun fromJson(o: JSONObject): ClusterNode = ClusterNode(
            id = o.optString("id"), title = o.optString("title"), instruction = o.optString("instruction"),
            ability = o.optString("ability"),
            assignee = o.optString("assignee").takeIf { it.isNotBlank() },
            dependsOn = o.optJSONArray("dependsOn")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList(),
            state = runCatching { NodeState.valueOf(o.optString("state", "PENDING")) }.getOrDefault(NodeState.PENDING),
            attempt = o.optInt("attempt", 0),
            lastError = o.optString("lastError").takeIf { it.isNotBlank() },
            artifact = o.optString("artifact").takeIf { it.isNotBlank() },
            releaseUnverified = o.optBoolean("releaseUnverified", false)
        )
    }
}

enum class ClusterTaskState {
    IDLE, INTAKE,

    /**
     * #192：能力覆盖检查（文档 V2 标星的新增阶段）。
     *
     * 它插在 INTAKE 与 DISPATCHING 之间，解决用户那句
     * 「没有相关的 skills 能力怎么执行，凭空想象」：
     * **派活之前先把每个子任务需要的能力核对一遍**，
     * 没有角色具备就去补（本地技能 → 开源社区 → 造角色），
     * 补不到就明确标记并告知，绝不装作"有人能做"。
     *
     * 为什么必须是独立阶段而不是派活时顺手看看：
     * 补救动作会改角色档案（绑技能/建角色），属于**有副作用的写操作**，
     * 混在派活逻辑里会让派活变成不可预期的副作用源。
     */
    CAPABILITY,

    DISPATCHING, PROPOSING, ARBITRATING,
    EXECUTING, VERIFYING, REPLANNING, CONVERGING, CLOSED, ESCALATED
}

/** 主持合法的"下班方式"只有这四种 */
enum class CloseReason {
    GOAL_REACHED,

    /** 熔断：轮次/预算/idle 超限。 */
    CIRCUIT_BROKEN,
    USER_ABORTED,
    UNRECOVERABLE,

    /**
     * #198：节点都「结束」了，但**没有一个真的产出东西** ——
     * 全部被跳过，或全部失败，或全被判定无人具备能力。
     *
     * 🔴 为什么必须与 GOAL_REACHED 分开：
     * 旧代码 [ClusterTask.finished] 把 SKIPPED 当完成，于是
     * 「主持把所有节点以『无人具备能力』跳过 → 宣布 GOAL_REACHED」=
     * **假闭环**。用户看到「圆满达成」，实际 tokens 只花了 384、什么都没发生。
     * 这比直接报错恶劣得多：它让用户以为系统能用，直到某次真的需要它干活才发现全是空转。
     */
    NO_PROGRESS,
}

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
    /**
     * #192：CAPABILITY 阶段的核对结论，逐节点一条（给 UI 展示"为什么这个角色能干这活"）。
     * 空 = 未做能力核对（capabilityCheck 关掉，或还没走到那一步）。
     */
    val capabilityNotes: MutableList<String> = mutableListOf(),
    val createdAt: Long = System.currentTimeMillis()
) {
    /** 节点是否全部落到终态（做完 / 跳过 / 失败都算结束）。 */
    fun finished(): Boolean = nodes.all { it.state.isTerminal() }

    /**
     * #198：真正**产出**了东西的节点数（只有 DONE 算）。
     *
     * [progress] 把 SKIPPED 计入分子是「假闭环」的第二个源头 ——
     * UI 上「5/5 全完成」全是跳过时，用户完全看不出这是个空结果。
     */
    fun productive(): Int = nodes.count { it.state == NodeState.DONE }

    /** 被跳过的节点数（UI 要单独显示，否则用户会被「完成」误导）。 */
    fun skipped(): Int = nodes.count { it.state == NodeState.SKIPPED }

    /**
     * #198：**没有任何产出** —— 判定「假闭环」的唯一依据。
     *
     * 条件：至少有一个节点，且零个 DONE。
     * 此时绝不许报 GOAL_REACHED，必须报 [CloseReason.NO_PROGRESS] 并如实说明。
     */
    fun noProgress(): Boolean = nodes.isNotEmpty() && productive() == 0
    fun ready(): List<ClusterNode> = nodes.filter { n ->
        n.state == NodeState.PENDING && n.dependsOn.all { d -> nodes.firstOrNull { it.id == d }?.state == NodeState.DONE }
    }
    /**
     * 进度 = 真正做完 / 总数。
     *
     * 🔴 #198：分子**不再**计入 SKIPPED。
     * 旧版把跳过算完成，于是「5 个节点全被跳过」显示成「5/5 全完成」——
     * 这是用户看到假闭环的直接来源。要看「结束了几个」请用 [finished]/节点状态本身。
     */
    fun progress(): Pair<Int, Int> = productive() to nodes.size
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
    /**
     * 某个节点产出了成果。
     *
     * @param body **产物正文**（2026-10-08 新增）。
     *   旧事件只带 title，于是对话框投影出来只有「📎 产出：标题」——
     *   集群真跑了 14 轮、用户看到的是十几条没有内容的空气泡。
     *   默认空串以兼容既有构造。
     */
    data class ArtifactProduced(
        override val taskId: String,
        val nodeId: String,
        val personaId: String,
        val title: String,
        val body: String = "",
        override val ts: Long = now(),
    ) : ClusterEvent
    /**
     * 裁决 / 验收结论。
     *
     * @param verifier **谁给出的结论**（2026-10-08 新增）。
     *   用户直接问过「关键是谁验收？」——旧事件里只有 pass + reason，
     *   看到「验收未通过」根本不知道是评审角色判的、还是主持代验的。
     *   默认空串以兼容既有序列化（trace/UI 都按可空读）。
     */
    data class Verdict(
        override val taskId: String,
        val nodeId: String,
        val pass: Boolean,
        val reason: String,
        val verifier: String = "",
        override val ts: Long = now(),
    ) : ClusterEvent
    /**
     * #190：角色真的调用了一个工具。
     *
     * 为什么必须有这个事件：ReAct 之后「模型说自己做了什么」和「真做了」在最终文本里
     * 长得一模一样。少了它，用户无法判断集群是在执行还是在编，排查时也毫无线索。
     */
    data class ToolInvoked(override val taskId: String, val personaId: String, val toolName: String, val summary: String, override val ts: Long = now()) : ClusterEvent
    data class ModelSwitched(override val taskId: String, val personaId: String, val from: String, val to: String, val reason: String, override val ts: Long = now()) : ClusterEvent
    data class Error(override val taskId: String, val personaId: String?, val message: String, override val ts: Long = now()) : ClusterEvent
    data class Replanned(override val taskId: String, val round: Int, val reason: String, override val ts: Long = now()) : ClusterEvent

    /**
     * #192：能力核对完成。
     *
     * @param covered 有角色真具备能力的节点数
     * @param total 节点总数
     */
    data class CapabilityAudited(
        override val taskId: String, val covered: Int, val total: Int,
        override val ts: Long = now()
    ) : ClusterEvent

    /**
     * #192：某个能力缺口已补救（或补救失败）。
     *
     * 失败也会发 —— **静默放过等于回到凭空想象**，比报错更糟。
     */
    data class CapabilityRemedied(
        override val taskId: String, val ability: String,
        val remedy: String, val ok: Boolean, val detail: String,
        override val ts: Long = now()
    ) : ClusterEvent
    data class Closed(override val taskId: String, val reason: CloseReason, val summary: String?, override val ts: Long = now()) : ClusterEvent

    companion object { fun now(): Long = System.currentTimeMillis() }
}
