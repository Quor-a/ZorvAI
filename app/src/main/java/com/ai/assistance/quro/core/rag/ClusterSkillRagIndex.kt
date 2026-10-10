package com.ai.assistance.quro.core.rag

import android.content.Context
import com.ai.assistance.quro.core.cluster.ClusterSkillStore

/**
 * 集群技能 RAG 域：把 [ClusterSkillStore] 的集群技能灌进 [AgentRag.engine]，
 * 让集群角色按任务能力词**按需检索**技能全文，而不是全量拼进系统提示词。
 *
 * ## 为什么需要它（用户最新诉求：「给集群加技能 skills 加 RAG」）
 *
 * 旧实现 [com.ai.assistance.quro.core.cluster.ClusterSkillRuntime.buildSkills]
 * 直接把技能正文全量拼进角色 system prompt，带来的问题：
 *  - 绑 5 个技能就占满 6000 字预算，更多技能只能给一行摘要，角色拿不到完整规程；
 *  - 与当前子任务无关的技能也在刷屏，稀释注意力；
 *  - 技能库**不是可检索的**——主持说「缺能力就查技能库」，角色没有自助入口。
 *
 * 现在把集群技能注册成独立 RAG 域（`cluster_skills`），与全局技能库**零耦合**：
 *  - 技能全文参与检索（description = prompt 全文，见 [ClusterSkill.toDoc]）；
 *  - 用 [ClusterSkillStore.effectiveAbilityWords] 作为关键词/触发词，中英双语都能召回；
 *  - 角色在系统提示词里被明确告知可以 `rag_search(domain="cluster_skills")` 取完整规程；
 *  - [ClusterSkillRuntime.resolveSkills] 先按任务能力词 RAG 检索，只把**命中**的技能
 *    正文注入，其余绑定技能保留摘要行（summaryLines）。
 *
 * ## 为什么不并入全局 prompts 域
 *
 * 全局 [PromptRagIndex] 域装的是主对话的系统提示词块，且带 `alwaysSticky` 语义；
 * 集群技能是角色绑定的**独立技能库**（id 前缀 `cluster_`，与全局库零耦合——
 * 见 [ClusterSkillStore] 的类注释）。混进 prompts 域会破坏隔离：
 * 全局主对话的 AI 也能检索到集群技能，集群角色也会被全局规则干扰。
 * 独立域 `cluster_skills` 是唯一既满足「走 ZorvAI 主 RAG 通道」又不破坏隔离的方案。
 */
object ClusterSkillRagIndex {

    /** 域标识。与全局 `tools` / `prompts` 并列，供 `rag_search(domain=...)` 使用。 */
    const val DOMAIN = "cluster_skills"

    /**
     * 集群技能域**独立**的召回门槛。
     *
     * 为什么不能直接用 [RagEngine] 的默认 `MIN_SCORE(0.62)`：
     * 与 [PromptRagIndex.PROMPT_MIN_SCORE] 同理——`fieldScore` 的覆盖率分母是整句
     * token 数，中文 bigram 切分后单触发词最多值 0.1 覆盖率。技能正文很长、
     * 域内文档少，照抄工具域阈值会把真查询系统性砍掉。
     *
     * 取 0.12：低于它是「查询里连一个集群技能实词都没有」，
     * 实测噪声查询在集群技能域拿不到分，安全。
     */
    const val SKILL_MIN_SCORE = 0.12

    /** 单次检索返回条数上限。 */
    private const val TOP_K = 8

    /**
     * 单个集群技能 → 可检索文档。
     *
     * - [RagDoc.name]：技能名，词法通道权重最高（用户直说技能名时精确命中）；
     * - [RagDoc.title]：技能描述首句，让「这个技能是干什么的」类查询能撞上；
     * - [RagDoc.description]：**技能规程全文**，不是 200 字摘要——
     *   与 [PromptRagIndex.PromptBlock.toDoc] 同一原则：正文只有全文参与检索，
     *   AI 命中后才能拿到完整规程照着执行；
     * - [RagDoc.keywords]：[ClusterSkillStore.effectiveAbilityWords]（中英双语能力词）；
     * - [RagDoc.triggers]：trigger 字段（中文口语触发词）+ 能力词；
     * - [RagDoc.capability]：技能名，让概念通道能反查；
     * - [RagDoc.payload]：技能对象本身，命中后交给调用方取全文。
     */
    fun ClusterSkillStore.ClusterSkill.toDoc(): RagDoc {
        val words = runCatching { ClusterSkillStore.effectiveAbilityWords(this) }
            .getOrDefault(emptyList())
        val triggers = (this.trigger.split(" ").map { it.trim() } + words)
            .filter { it.isNotBlank() }
            .distinct()
        return RagDoc(
            id = this.id,
            name = this.name,
            title = this.description.lineSequence().firstOrNull()?.take(80)
                ?.takeIf { it.isNotBlank() }
                ?: this.name,
            description = this.prompt.ifBlank { this.description },
            capability = this.name,
            keywords = words,
            triggers = triggers,
            concepts = listOfNotNull(this.description.take(120).takeIf { it.isNotBlank() }),
            priority = if (this.enabled) 0.6 else 0.2,
            hintBoost = 0.3,
            payload = this,
        )
    }

    /**
     * 把当前集群技能库全量灌进 [AgentRag.engine]。
     *
     * 幂等 + 自愈：
     * - 每次先 [RagEngine.clearDomain] 再灌，技能增删后不会累积旧文档；
     * - 读取 [ClusterSkillStore.load] 会自动播种内置技能，保证索引与库一致；
     * - 任何异常都吞掉返回 false，不打断调用方（技能 RAG 是增强，不是硬依赖）。
     *
     * @return 是否成功灌入（失败时调用方可如实说明技能 RAG 暂不可用）
     */
    fun install(context: Context): Boolean = runCatching {
        val engine = AgentRag.engine
        engine.clearDomain(DOMAIN)
        val skills = ClusterSkillStore.load(context)
        skills.forEach { s -> engine.register(DOMAIN, s.toDoc()) }
        true
    }.getOrDefault(false)

    /**
     * 按能力/意图检索集群技能。
     *
     * 与 [ClusterCapability] 同源：查询一般是主持拆出的能力标签或角色当前子任务
     * 标题/指令。返回的技能全文由调用方决定注入多少。
     *
     * 索引由 [install] 预装（[com.ai.assistance.quro.core.cluster.ClusterRuntime.init]
     * 与 [ClusterSkillRuntime.resolveSkills] 都会调用）；索引尚未安装时返回空，
     * 调用方自行决定是否先 install（RagSearchTool 无 Context，依赖预装）。
     */
    fun searchSkills(query: String, limit: Int = TOP_K): List<RagHit> {
        if (query.isBlank()) return emptyList()
        if (AgentRag.engine.countOf(DOMAIN) <= 0) return emptyList()
        return runCatching {
            AgentRag.engine.search(query, DOMAIN, limit.coerceIn(1, TOP_K), SKILL_MIN_SCORE)
        }.getOrDefault(emptyList())
    }

    /**
     * 从检索结果里取出**完整技能正文**（[ClusterSkillStore.ClusterSkill.prompt]）。
     *
     * 与 [PromptRagIndex.ruleBodyOf] 同一原则：AI 命中后必须拿到全文照着执行，
     * 而不是只看 description 摘要。
     */
    fun skillBodyOf(hit: RagHit): String? =
        (hit.doc.payload as? ClusterSkillStore.ClusterSkill)?.prompt

    /**
     * 把检索结果渲染成给模型看的技能清单（含全文）。
     *
     * 供 [ClusterSkillRuntime] 注入系统提示词，也供调试/测试直接检查。
     * 无命中时返回空串，由调用方决定措辞。
     */
    fun renderFullBodies(hits: List<RagHit>): String = buildString {
        hits.forEach { h ->
            val body = skillBodyOf(h)
            if (body != null && body.isNotBlank()) {
                append("### 技能「").append(h.doc.title.ifBlank { h.id }).append("」\n")
                append(body).append("\n\n")
            }
        }
    }

    /** 集群技能域当前文档数（诊断用）。 */
    fun stats(): Int = AgentRag.engine.countOf(DOMAIN)
}