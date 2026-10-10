package com.ai.assistance.quro.core.cluster

import android.content.Context
import com.ai.assistance.quro.core.cluster.ClusterSkillStore.ClusterSkill
import com.ai.assistance.quro.core.rag.ClusterSkillRagIndex

/**
 * ★ 集群自己的技能引擎 ★
 *
 * 用户最新反馈：「集群 skills 要实现自己的 skills 架构、引擎、依赖，等等使用做出来自己的功能」。
 *
 * 在旧实现（[ClusterSkillRuntime.resolveSkills] 直接读库 → 按 RAG 注入）之上，
 * 本引擎补上「技能依赖」这一整层语义：
 *
 * - **依赖解析**：技能可以声明 `dependsOn`（依赖另一门技能）与 `requiresTools`
 *   （执行这门技能需要的宿主工具）。装配角色技能时，引擎先对角色绑定的技能做
 *   **传递闭包**解析 —— 依赖的技能即使没有显式绑定，也会一并装配进角色，
 *   保证「主技能」的规程可以引用「前置技能」的规矩。
 *
 * - **循环检测**：技能依赖成环（A 依赖 B、B 依赖 A）会让装配死循环。
 *   引擎在解析闭包时同步做环检测，命中环就把环上技能排除并如实报告，
 *   绝不静默卡死。
 *
 * - **工具需求校验**：每门技能声明它执行时要用哪些宿主工具。
 *   引擎把全部需求汇总，与角色**实际拿到**的工具面（[ClusterSkillRuntime.toolsFor]）
 *   求交；缺失的工具**显式报告**，让角色知道「规程要求用 X，但你手上没有 X」，
 *   由主持决定补工具还是换技能 —— 绝不假装技能可用。
 *
 * - **RAG 按需注入保持**：依赖解析只做「技能集合的完备化」，注入仍走
 *   [ClusterSkillRagIndex]（角色任务相关的能力词决定哪些正文进 system prompt，
 *   与 #217 保持一致）。
 *
 * ## 装配产物
 *
 * [assemble] 返回 [AssembledSkills]：
 * - `skills`：**装配后的完整技能集**（角色显式绑定 + 传递依赖解析出来的），
 *   每个技能都带依赖来源说明（`direct` / `via:<技能名>`）；
 * - `summaryLines`：供 UI / 工具展示的一行摘要（全部装配技能）；
 * - `bodies`：RAG 命中后注入提示词的正文（仍走预算收口）；
 * - `missingDependencies`：依赖声明了但库中不存在的技能 id/名（如实报告）；
 * - `cycles`：被检测出的依赖环（环上技能已从装配中剔除）；
 * - `missingTools`：技能声明需要、但角色实际没有的工具名。
 *
 * 所有字段都在 [ResolvedSkills] 之外独立返回，避免破坏既有调用方契约。
 */
object ClusterSkillEngine {

    /** 技能 id 或名称（依赖声明里两种都允许）。 */
    data class SkillRef(
        val id: String,
        val name: String,
    )

    /**
     * 装配产物。
     *
     * @param skills 装配后的技能（直接绑定 + 依赖闭包），带来源说明。
     * @param summaryLines 给 UI 的摘要行。
     * @param bodies 注入提示词的正文（RAG 命中 + 预算收口）。
     * @param missingDependencies 声明了但库里没有的依赖。
     * @param cycles 检测到的依赖环。
     * @param missingTools 技能需要但角色没有的工具。
     */
    data class AssembledSkills(
        val skills: List<ClusterSkill> = emptyList(),
        val summaryLines: List<String> = emptyList(),
        val bodies: List<String> = emptyList(),
        val missingDependencies: List<String> = emptyList(),
        val cycles: List<List<String>> = emptyList(),
        val missingTools: List<String> = emptyList(),
    )

    /** 一 个技能在装配结果里的来源说明。 */
    data class AssembledSkill(
        val skill: ClusterSkill,
        /** `direct` = 角色显式绑定；否则是依赖来源技能名。 */
        val via: String,
    )

    /**
     * 解析依赖声明。
     *
     * 依赖声明同时支持：
     * - 技能 id（`cluster_xxx`）；
     * - 技能名（`frontend-design-playbook`）。
     *
     * 解析顺序：先按 id 精确查，再按名字精确查；两种都查不到就加入
     * [AssembledSkills.missingDependencies]。
     */
    private fun resolveDep(
        libById: Map<String, ClusterSkill>,
        libByName: Map<String, ClusterSkill>,
        dep: String,
    ): ClusterSkill? {
        val id = dep.trim()
        if (id.isEmpty()) return null
        return libById[id] ?: libByName[id]
    }

    /**
     * 对一组种子技能做传递闭包依赖解析。
     *
     * @param seeds 角色显式绑定的技能。
     * @param libById 技能库 id 索引。
     * @param libByName 技能库名字索引。
     * @return (装配技能含来源, 缺失依赖, 依赖环)
     */
    private fun resolveClosure(
        seeds: List<ClusterSkill>,
        libById: Map<String, ClusterSkill>,
        libByName: Map<String, ClusterSkill>,
    ): Triple<List<AssembledSkill>, List<String>, List<List<String>>> {
        // 已解析的技能：id -> 来源
        val resolved = LinkedHashMap<String, AssembledSkill>()
        // 当前 DFS 路径（只含「根 → 当前节点」的链，用于精确环检测）
        val path = mutableListOf<String>()
        val missing = LinkedHashSet<String>()
        val cycles = mutableListOf<List<String>>()

        fun visit(skill: ClusterSkill, via: String) {
            val key = skill.id
            // 已在当前路径中 = 成环。环 = 路径中 key 的位置到末尾，再闭合回 key。
            val idx = path.indexOf(key)
            if (idx >= 0) {
                cycles += path.subList(idx, path.size) + key
                return
            }
            // 已完整解析过（不在路径，说明依赖都已处理）→ 直接复用。
            if (resolved.containsKey(key)) return
            path.add(key)
            resolved[key] = AssembledSkill(skill, via)
            for (dep in skill.dependsOn) {
                val d = resolveDep(libById, libByName, dep)
                if (d == null) {
                    missing += dep
                    continue
                }
                visit(d, via = skill.name)
            }
            path.removeAt(path.size - 1)
        }

        for (s in seeds) visit(s, via = "direct")
        return Triple(resolved.values.toList(), missing.toList(), cycles.distinct())
    }

    /**
     * 汇总装配后全部技能声明的工具需求，与角色实际拥有的工具求交。
     *
     * @param assembled 装配后的技能。
     * @param actualToolNames 角色实际拿到的工具名（[ClusterSkillRuntime.toolsFor] 的 name 集合）。
     * @return 缺失的工具名（去重、保序）。
     */
    internal fun missingTools(
        assembled: List<ClusterSkill>,
        actualToolNames: Set<String>,
    ): List<String> {
        val required = assembled.flatMap { it.requiresTools }
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
        return required.filter { it !in actualToolNames }
    }

    /**
     * 装配角色技能。
     *
     * @param context 上下文（读集群技能库）。
     * @param role 角色档案（读 skillIds）。
     * @param actualToolNames 角色实际拿到的工具名（用于工具需求校验；可为空，空 = 不做校验）。
     * @return 装配产物。
     */
    fun assemble(
        context: Context,
        role: RoleProfile,
        actualToolNames: Set<String> = emptySet(),
    ): AssembledSkills {
        if (role.skillIds.isEmpty()) {
            return AssembledSkills()
        }
        val all = runCatching { ClusterSkillStore.load(context) }.getOrElse { emptyList() }
        val byId = all.associateBy { it.id }
        val byName = all.associateBy { it.name }
        val seeds = role.skillIds.mapNotNull { byId[it] }

        val (assembled, missingDeps, cycles) = resolveClosure(seeds, byId, byName)
        // 把环上技能从装配结果中剔除（环上的技能不能可靠地作为前置）
        val cycleIds = cycles.flatten().toSet()
        val usableAssembled = assembled.filter { it.skill.id !in cycleIds }
        val usableSkills = usableAssembled.map { it.skill }

        // 工具需求校验
        val missingToolList = missingTools(usableSkills, actualToolNames)

        // RAG 按需注入（与 #217 保持一致，query 只用任务上下文，不拼技能描述）
        val ragInstalled = ClusterSkillRagIndex.install(context)
        val query = buildString {
            (role.duties + role.skills)
                .filter { it.isNotBlank() }
                .forEach { append(it).append(' ') }
        }.trim()
        val ragHits = if (ragInstalled && query.isNotBlank()) {
            ClusterSkillRagIndex.searchSkills(query, limit = ClusterSkillRuntime.MAX_SKILLS_PER_ROLE)
        } else {
            emptyList()
        }
        val ragHitIds = ragHits.map { it.id }.toSet()
        // RAG 有命中 → 只注入命中的技能（#217 按需注入）。
        // RAG 零命中（索引未装/查询为空/门槛太严）→ 降级注入**全部装配技能**，
        // 保证「绑了就有正文」—— RAG 是增强，不是硬依赖。
        val relevant = if (ragHits.isNotEmpty()) {
            usableSkills.filter { it.id in ragHitIds }
        } else {
            usableSkills
        }

        val fullText = when (role.role) {
            RoleKind.EXECUTOR, RoleKind.CRITIC -> true
            RoleKind.HOST, RoleKind.PLANNER, RoleKind.EXPERT -> false
        }
        val bodies = if (fullText) {
            ClusterSkillRuntime.buildBodies(relevant)
        } else {
            ClusterSkillRuntime.buildSummaries(relevant)
        }
        val bodyTexts = if (bodies.isBlank()) emptyList() else listOf(bodies)

        val summaryLines = usableSkills.map {
            val via = usableAssembled.first { a -> a.skill.id == it.id }.via
            val depNote = if (via == "direct") "" else "（依赖 ${via}）"
            "- 「${it.name}」${it.description.ifBlank { "（无说明）" }.take(80)}$depNote"
        }

        return AssembledSkills(
            skills = usableSkills,
            summaryLines = summaryLines,
            bodies = bodyTexts,
            missingDependencies = missingDeps,
            cycles = cycles,
            missingTools = missingToolList,
        )
    }
}