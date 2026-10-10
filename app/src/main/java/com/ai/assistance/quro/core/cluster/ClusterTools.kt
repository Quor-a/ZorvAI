package com.ai.assistance.quro.core.cluster

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

/**
 * ★ 集群挂到 Zorv AI 现有工具体系上的入口 ★
 *
 * 这是"零侵入"的关键：Zorv AI 是 Tool-first 架构（一切都表达为 QuroTool，
 * 由 QuroToolRegistry 集中注册，LLM 只看注册表就能发现并调用）。
 * 所以集群不该外挂一套新框架，而应该**自己也变成一组 QuroTool**。
 *
 * 这样：
 *   - 主助手（QuroAssistant）在普通对话里就能说"建个集群跑这个任务"；
 *   - 不需要改 ChatScreen / ViewModel 的任何接线；
 *   - 工具规格天然是 OpenAI function-calling 格式，云端端侧都能调。
 *
 * ⚠ 注意：QuroTool.run() 是同步签名（fun run(context, arguments): String），
 *        集群调用是挂起的，这里用 runBlocking 包裹并加总超时。
 *        工具本身已在后台线程执行，不会阻塞 UI。
 */

/** 进程级单例，持有引擎与网关 */
object ClusterRuntime {

    @Volatile private var engine: ClusterEngine? = null
    @Volatile private var appContext: Context? = null
    @Volatile private var clusters = listOf<ClusterConfig>()

    /**
     * #213 病灶 C：sync=false 后台驱动作用域。
     *
     * 旧实现 cluster_start(sync=false) 只 submit 存任务就返回，**没有任何后台协程调
     * drive()**，于是主持永远不拾取，任务一直停在 IDLE（用户实测 progress 0/0）。
     * 这个作用域持有每个异步任务的驱动 Job，随应用进程存活。
     */
    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun init(context: Context, gateway: LlmGateway? = null) {
        appContext = context.applicationContext
        val src = DefaultClusterModelSource(context)
        engine = ClusterEngine(context, gateway ?: DefaultLlmGateway(context, src), src)
        RoleRegistry.ensureHost(context)
        // #217：集群技能 RAG 域预装 —— 让 rag_search(domain="cluster_skills") 立即可用。
        com.ai.assistance.quro.core.rag.ClusterSkillRagIndex.install(context)
        if (clusters.isEmpty()) {
            clusters = listOf(ClusterConfig(id = "default", name = "默认集群"))
        }
    }

    fun get(): ClusterEngine = engine ?: error("请先调用 ClusterRuntime.init(context)")
    fun models(): List<ModelProfile> = DefaultClusterModelSource(appContext!!).listModels()
    fun defaultCluster(): ClusterConfig = clusters.first()
    fun clusters(): List<ClusterConfig> = clusters
    fun addCluster(c: ClusterConfig) { clusters = clusters + c }
    /** 同 id 覆盖，避免配置工具反复追加导致列表膨胀 */
    fun updateCluster(c: ClusterConfig) {
        clusters = clusters.map { if (it.id == c.id) c else it }.ifEmpty { listOf(c) }
    }
    fun ctx(): Context = appContext ?: error("未初始化")

    /**
     * #213 病灶 C：提交一个后台任务并立即开始驱动。
     *
     * 由 [ClusterStartTool] 在 sync=false 时调用。任务在 [backgroundScope] 里推进，
     * 事件照常发到 [ClusterEngine.events]（ClusterChatBridge / ClusterTraceBridge
     * 会投影到对话框与 trace 面板），调用方立即拿到任务 ID 用于 cluster_status 查询。
     */
    fun launchBackground(cluster: ClusterConfig, task: ClusterTask) {
        val eng = engine ?: return
        backgroundScope.launch {
            runCatching { eng.drive(cluster, task) }
        }
    }
}

// ————————————————————————————————————————
// 工具实现
// ————————————————————————————————————————

/** cluster_start：提交一个目标，主持接管并推进到闭环 */
class ClusterStartTool : com.ai.assistance.quro.core.tools.QuroTool {
    override val name = "cluster_start"
    override val description =
        "启动 AI 集群处理一个复杂目标。集群由一个不可替换的「主持」驱动：它会定义验收标准、拆解子任务、" +
        "点名合适的角色、裁决方案、验收产物，直到任务闭环。适合需要多种专业能力协作、单轮对话搞不定的任务。" +
        "参数 goal 是用户目标；sync=true 时阻塞到任务结束再返回结果（可能耗时较久），false 时立即返回任务 ID。"
    override val parametersJson = """{
      "type":"object",
      "properties":{
        "goal":{"type":"string","description":"要达成的目标，尽量具体"},
        "acceptance":{"type":"array","items":{"type":"string"},"description":"可选的验收标准条目"},
        "sync":{"type":"boolean","description":"是否等待任务结束，默认 true"}
      },
      "required":["goal"]
    }"""
    override val readOnly = false

    override fun run(context: Context, arguments: String): String = runBlocking {
        val a = JSONObject(arguments)
        val goal = a.optString("goal").ifBlank { return@runBlocking err("缺少 goal") }
        val acc = a.optJSONArray("acceptance")?.let { arr ->
            (0 until arr.length()).map { arr.optString(it) }
        } ?: emptyList()
        val sync = a.optBoolean("sync", true)

        val engine = ClusterRuntime.get()
        val cluster = ClusterRuntime.defaultCluster().copy(acceptance = acc)
        val task = engine.submit(cluster, goal)

        if (!sync) {
            // #213 病灶 C：sync=false 也要让主持真的开始推进。
            // 旧实现只 submit 就返回，没有后台协程 drive，任务永远停在 IDLE。
            // 现在提交后立即在后台作用域里驱动，调用方拿 taskId 用 cluster_status 查进度。
            ClusterRuntime.launchBackground(cluster, task)
            return@runBlocking JSONObject().apply {
                put("ok", true); put("taskId", task.id)
                put("message", "任务已提交，主持正在后台推进，可用 cluster_status 查询进度")
            }.toString()
        }
        val reason = withTimeoutOrNull(30 * 60 * 1000L) { engine.drive(cluster, task) }
            ?: CloseReason.CIRCUIT_BROKEN
        val (done, total) = task.progress()
        JSONObject().apply {
            put("ok", reason == CloseReason.GOAL_REACHED)
            put("taskId", task.id)
            put("reason", reason.name)
            put("progress", "$done/$total")
            put("summary", task.summary ?: "")
            put("turns", task.turnCount)
            put("tokens", task.tokenUsed)
        }.toString()
    }
}

/** cluster_status：查进度 */
class ClusterStatusTool : com.ai.assistance.quro.core.tools.QuroTool {
    override val name = "cluster_status"
    override val description = "查询集群任务进度：当前状态、子任务完成情况、各节点分配的模型、已消耗 token。"
    override val parametersJson = """{
      "type":"object",
      "properties":{"taskId":{"type":"string","description":"cluster_start 返回的任务 ID"}},
      "required":["taskId"]
    }"""
    override val readOnly = true

    override fun run(context: Context, arguments: String): String {
        val id = JSONObject(arguments).optString("taskId")
        val t = ClusterRuntime.get().task(id) ?: return err("任务不存在: $id")
        val (done, total) = t.progress()
        val nodes = JSONArray()
        t.nodes.forEach { n ->
            nodes.put(JSONObject().apply {
                put("id", n.id); put("title", n.title); put("state", n.state.name)
                put("assignee", n.assignee ?: ""); put("attempt", n.attempt)
                put("error", n.lastError ?: "")
            })
        }
        return JSONObject().apply {
            put("ok", true); put("state", t.state.name); put("progress", "$done/$total")
            put("turns", t.turnCount); put("tokens", t.tokenUsed); put("replan", t.replanCount)
            put("acceptance", JSONArray(t.acceptance)); put("nodes", nodes)
            put("summary", t.summary ?: "")
        }.toString()
    }
}

/** cluster_roles：列出角色（主持永远在第一位且不可删） */
class ClusterRolesTool : com.ai.assistance.quro.core.tools.QuroTool {
    override val name = "cluster_roles"
    override val description = "列出集群中的所有角色。第一个是主持（内置、不可删除、不可替换），其余是专家与评审。"
    override val parametersJson = """{"type":"object","properties":{}}"""
    override val readOnly = true

    override fun run(context: Context, arguments: String): String {
        val models = ClusterRuntime.models().associateBy { it.id }
        val arr = JSONArray()
        RoleRegistry.roles(context).forEach { r ->
            val p = runCatching { com.ai.assistance.quro.core.QuroPersonaRepository(context).get(r.personaId) }.getOrNull()
            arr.put(JSONObject().apply {
                put("personaId", r.personaId)
                put("name", p?.name ?: r.personaId)
                put("role", r.role.name)
                put("isHost", RoleRegistry.isHost(r.personaId))
                put("model", models[r.modelProfileId]?.displayName ?: r.modelProfileId.ifBlank { "（未绑定，运行时兜底）" })
                put("fallback", JSONArray(r.fallbackModelIds))
                put("duties", JSONArray(r.duties))
                put("tags", JSONArray(r.tags))
                put("enabled", r.enabled)
            })
        }
        return JSONObject().put("ok", true).put("roles", arr).toString()
    }
}

/** cluster_models：列出宿主已配置的所有模型 —— 这就是"拉取软件已配置模型" */
class ClusterModelsTool : com.ai.assistance.quro.core.tools.QuroTool {
    override val name = "cluster_models"
    override val description =
        "列出 Zorv AI 中已配置好的所有可用模型（含云端厂商与本地离线模型），可用来给集群角色分配模型。" +
        "返回的 id 可直接用于 cluster_bind_model。" +
        "支持 action=fetch：从当前接入点联网拉取该厂商可用的模型列表（参考功能模型配置的『拉取模型』），" +
        "拉到的模型 id 同样可用于 cluster_bind_model。"
    override val parametersJson = """{
      "type":"object",
      "properties":{
        "action":{"type":"string","description":"list=列出已配置模型（默认）；fetch=从当前接入点联网拉取可用模型列表"}
      }
    }"""
    override val readOnly = true

    override fun run(context: Context, arguments: String): String {
        val action = JSONObject(arguments).optString("action", "list").ifBlank { "list" }
        return if (action == "fetch") fetchModels(context) else listConfiguredModels()
    }

    private fun listConfiguredModels(): String {
        val arr = JSONArray()
        ClusterRuntime.models().forEach { m ->
            arr.put(JSONObject().apply {
                put("id", m.id); put("displayName", m.displayName)
                put("kind", m.kind.name); put("available", m.available)
                put("supportsTools", m.supportsTools)
                put("maxConcurrency", m.maxConcurrency)
            })
        }
        return JSONObject().put("ok", true).put("models", arr).toString()
    }

    /** #215：联网拉取当前接入点可用模型列表（复用功能模型配置的 QuroModelListFetcher）。 */
    private fun fetchModels(context: Context): String {
        val cfg = runCatching {
            com.ai.assistance.quro.core.model.QuroModelConfigRepository(context).load()
        }.getOrNull()
        if (cfg == null || cfg.baseUrl.isBlank()) {
            return err("当前没有可用的云端接入点（baseUrl 为空），无法拉取模型列表")
        }
        val baseUrl = cfg.baseUrl
        val apiKey = cfg.apiKey
        return kotlinx.coroutines.runBlocking {
            val r = com.ai.assistance.quro.core.network.QuroModelListFetcher(
                connectTimeout = 8, readTimeout = 15
            ).fetch(baseUrl, apiKey)
            when (r) {
                is com.ai.assistance.quro.core.network.QuroModelListResult.Success -> {
                    val arr = JSONArray()
                    r.models.forEach { m ->
                        arr.put(JSONObject().apply {
                            put("id", m.id)
                            put("displayName", m.id)
                            put("contextLength", m.contextLength)
                            put("kind", "CLOUD")
                            put("available", true)
                            put("supportsTools", true)
                        })
                    }
                    JSONObject().apply {
                        put("ok", true)
                        put("models", arr)
                        put("source", baseUrl)
                        put("hint", "这些是接入点可用的模型，用 cluster_bind_model(personaId, modelId) 绑定给角色")
                    }.toString()
                }
                is com.ai.assistance.quro.core.network.QuroModelListResult.Error -> {
                    err("拉取模型列表失败：${r.message}")
                }
            }
        }
    }
}

/**
 * cluster_remove_role：把一个角色移出集群。
 *
 * 为什么必须有：集群重写成tool-first 时，角色注册有 `cluster_enroll`、换模有
 * `cluster_bind_model`，但**移除角色没有对等工具**—— 用户在对话框里请AI「把某人撤掉」
 * 时 AI 只能干瞪眼（设置页能移出，但对话框不能 = 能力不对称）。
 */
class ClusterRemoveRoleTool : com.ai.assistance.quro.core.tools.QuroTool {
    override val name = "cluster_remove_role"
    override val description =
        "把一个角色移出集群（只移出集群，人格卡本身不会被删除）。主持不可被移出——主持是集群的固定身份。"
    override val parametersJson = """{
      "type":"object",
      "properties":{
        "personaId":{"type":"string","description":"要移出集群的人格卡 ID，可用 cluster_roles 查看"}
      },
      "required":["personaId"]
    }"""
    override val readOnly = false

    override fun run(context: Context, arguments: String): String {
        val pid = JSONObject(arguments).optString("personaId").ifBlank { return err("缺少 personaId") }
        if (RoleRegistry.isHost(pid)) return err("主持不可移出集群：它是集群的固定身份")
        // 确认这人确实在集群里，避免把「本来就不在」当成成功
        val hit = runCatching { RoleRegistry.roles(context).any { it.personaId == pid } }
            .getOrDefault(false)
        if (!hit) return err("该角色不在集群中: $pid")
        runCatching { RoleRegistry.delete(context, pid) }
            .onFailure { return err("移出失败: ${it.message}") }
        return ok("已移出集群: $pid（人格卡仍保留在人格库）")
    }
}

/** cluster_bind_model：给角色绑模型 —— 每个角色可以用不同模型 */
class ClusterBindModelTool : com.ai.assistance.quro.core.tools.QuroTool {
    override val name = "cluster_bind_model"
    override val description =
        "给集群角色绑定它专属的模型（来自 cluster_models 的 id），并可指定降级链。" +
        "主持也可以换模型（建议用便宜快模型做调度）。不同角色可以绑定完全不同的模型。"
    override val parametersJson = """{
      "type":"object",
      "properties":{
        "personaId":{"type":"string","description":"角色的人格 ID，主持固定为 persona_cluster_host"},
        "modelId":{"type":"string","description":"cluster_models 返回的模型 id"},
        "fallback":{"type":"array","items":{"type":"string"},"description":"降级链，模型 id 列表"},
        "temperature":{"type":"number"}
      },
      "required":["personaId","modelId"]
    }"""
    override val readOnly = false

    override fun run(context: Context, arguments: String): String {
        val a = JSONObject(arguments)
        val pid = a.optString("personaId")
        val mid = a.optString("modelId")
        val role = RoleRegistry.get(context, pid) ?: return err("角色不存在: $pid")
        val fb = a.optJSONArray("fallback")?.let { arr -> (0 until arr.length()).map { arr.optString(it) } }
            ?: role.fallbackModelIds
        RoleRegistry.upsert(context, role.copy(
            modelProfileId = mid,
            fallbackModelIds = fb,
            context = role.context.copy(
                temperature = if (a.has("temperature")) a.optDouble("temperature").toFloat() else role.context.temperature
            ),
            version = role.version + 1
        ))
        return ok("已为 ${pid} 绑定模型 $mid")
    }
}

/** cluster_enroll：把已有的人格卡纳为集群角色 */
class ClusterEnrollTool : com.ai.assistance.quro.core.tools.QuroTool {
    override val name = "cluster_enroll"
    override val description =
        "把一张已有人格卡登记为集群角色（EXPERT 专家 / CRITIC 评审），并指定职责、禁忌与技能。" +
        "主持不可通过此工具登记或修改。"
    override val parametersJson = """{
      "type":"object",
      "properties":{
        "personaId":{"type":"string","description":"人格卡 ID"},
        "modelId":{"type":"string"},
        "role":{"type":"string","description":"EXPERT 或 CRITIC"},
        "duties":{"type":"array","items":{"type":"string"}},
        "taboos":{"type":"array","items":{"type":"string"}},
        "skills":{"type":"array","items":{"type":"string"}}
      },
      "required":["personaId"]
    }"""
    override val readOnly = false

    override fun run(context: Context, arguments: String): String {
        val a = JSONObject(arguments)
        val pid = a.optString("personaId")
        if (RoleRegistry.isHost(pid)) return err("主持不可通过此工具登记")
        val kind = runCatching { RoleKind.valueOf(a.optString("role", "EXPERT")) }.getOrDefault(RoleKind.EXPERT)
        if (kind == RoleKind.HOST) return err("不能登记为主持")
        RoleRegistry.enroll(
            context, pid,
            modelProfileId = a.optString("modelId"),
            role = kind,
            duties = a.optJSONArray("duties")?.let { arr -> (0 until arr.length()).map { arr.optString(it) } } ?: emptyList(),
            taboos = a.optJSONArray("taboos")?.let { arr -> (0 until arr.length()).map { arr.optString(it) } } ?: emptyList(),
            skills = a.optJSONArray("skills")?.let { arr -> (0 until arr.length()).map { arr.optString(it) } } ?: emptyList()
        )
        return ok("已登记角色 $pid")
    }
}

/** cluster_host_config：改主持的可变能力（身份永远改不了） */
class ClusterHostConfigTool : com.ai.assistance.quro.core.tools.QuroTool {
    override val name = "cluster_host_config"
    override val description =
        "调整主持的编排与熔断策略、以及它使用的模型。注意：主持的身份与职责不可修改，只能改这些能力项。"
    override val parametersJson = """{
      "type":"object",
      "properties":{
        "modelId":{"type":"string","description":"主持使用的模型 id，建议选便宜快模型"},
        "maxTurns":{"type":"integer"},
        "maxReplan":{"type":"integer"},
        "proposeFanout":{"type":"integer","description":"一次点名几个角色提方案"},
        "parallelPropose":{"type":"boolean","description":"提案是否并行且互相隔离，默认 true，关掉会产生从众幻觉"},
        "noProgressWindow":{"type":"integer","description":"连续多少轮无进展判定为停滞"}
      }
    }"""
    override val readOnly = false

    override fun run(context: Context, arguments: String): String = runBlocking {
        val a = JSONObject(arguments)
        val host = RoleRegistry.host(context)
        val updated = host.copy(
            modelProfileId = a.optString("modelId").takeIf { it.isNotBlank() } ?: host.modelProfileId,
            context = host.context.copy(
                temperature = if (a.has("temperature")) a.optDouble("temperature").toFloat() else host.context.temperature
            )
        )
        RoleRegistry.upsert(context, updated)

        val cur = ClusterRuntime.defaultCluster()
        val budget = cur.budget.copy(
            maxTurns = a.optInt("maxTurns", cur.budget.maxTurns),
            maxReplan = a.optInt("maxReplan", cur.budget.maxReplan),
            proposeFanout = a.optInt("proposeFanout", cur.budget.proposeFanout),
            parallelPropose = a.optBoolean("parallelPropose", cur.budget.parallelPropose),
            noProgressWindow = a.optInt("noProgressWindow", cur.budget.noProgressWindow)
        )
        ClusterRuntime.updateCluster(
            ClusterConfig(cur.id, cur.name, cur.mode, budget, cur.acceptance)
        )
        ok("主持配置已更新（身份不可变）")
    }
}

/** cluster_abort：中止当前任务 */
class ClusterAbortTool : com.ai.assistance.quro.core.tools.QuroTool {
    override val name = "cluster_abort"
    override val description = "中止正在进行的集群任务。主持会立即收尾并转为 ESCALATED。"
    override val parametersJson = """{"type":"object","properties":{}}"""
    override val readOnly = false

    override fun run(context: Context, arguments: String): String {
        ClusterRuntime.get().abort()
        return ok("已请求中止，主持将在下一个检查点收尾")
    }
}

// ————————————————————————————————————————
// 注册入口
// ————————————————————————————————————————

/**
 * 在 QuroBuiltInTools 的注册处加一行即可：
 *   ClusterToolSet.registerAll(QuroToolRegistry.active!!)
 */
object ClusterToolSet {
    fun registerAll(registry: com.ai.assistance.quro.core.tools.QuroToolRegistry) {
        registry.register(ClusterStartTool())
        registry.register(ClusterStatusTool())
        registry.register(ClusterRolesTool())
        registry.register(ClusterModelsTool())
        registry.register(ClusterBindModelTool())
        registry.register(ClusterEnrollTool())
        registry.register(ClusterRemoveRoleTool())
        registry.register(ClusterHostConfigTool())
        registry.register(ClusterAbortTool())
        // #190 技能市场：主持可自行查技能、给角色配技能、从外部导入技能
        registry.register(ClusterSkillMarketTool())
        registry.register(ClusterSkillGrantTool())
        registry.register(ClusterSkillImportTool())
        // #191 开源技能 + 角色卡：去开源社区找技能、下载安装、用角色卡（skills 聚合）一键建角
        registry.register(ClusterOpenSkillSearchTool())
        registry.register(ClusterOpenSkillInstallTool())
        registry.register(ClusterRoleCardTool())
        // #215 动态创造能力：缺能力/缺成员时，主持现场造一个新角色
        registry.register(ClusterForgeRoleTool())
    }
}

private fun ok(msg: String) = JSONObject().put("ok", true).put("message", msg).toString()
private fun err(msg: String) = JSONObject().put("ok", false).put("error", msg).toString()
