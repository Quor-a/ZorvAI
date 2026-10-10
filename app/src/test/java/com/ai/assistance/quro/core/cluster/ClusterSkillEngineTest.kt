package com.ai.assistance.quro.core.cluster

import com.ai.assistance.quro.core.cluster.ClusterSkillStore.ClusterSkill
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * #218 回归锁：集群技能引擎 —— 依赖解析、循环检测、工具需求校验、装配产物。
 *
 * 用户最新反馈：「集群 skills 要实现自己的 skills 架构、引擎、依赖，等等使用做出来自己的功能」。
 *
 * 本测试逐条钉死四个契约：
 *  1. [ClusterSkillEngine] 对角色绑定的技能做**传递闭包**依赖解析，
 *     依赖的技能即使没显式绑定也会装配进来，且摘要行标注来源；
 *  2. 依赖成环会被检测并剔除，不静默卡死；
 *  3. 技能声明的工具需求与角色实际工具面求交，缺失工具**显式报告**；
 *  4. 装配产物（skills / summaryLines / bodies）完整可用，RAG 注入仍生效。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ClusterSkillEngineTest {

    private val ctx get() = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        ClusterTestSkills.seedClusterSkills(ctx)
    }

    private fun skill(
        id: String,
        name: String,
        dependsOn: List<String> = emptyList(),
        requiresTools: List<String> = emptyList(),
        prompt: String = "规程正文：$name",
        description: String = "技能 $name",
        abilityWords: String = "写代码 编码 $name",
    ) = ClusterSkill(
        id = id,
        name = name,
        description = description,
        prompt = prompt,
        abilityWords = abilityWords,
        dependsOn = dependsOn,
        requiresTools = requiresTools,
        enabled = true,
    )

    private fun role(
        kind: RoleKind = RoleKind.EXECUTOR,
        skillIds: List<String> = emptyList(),
        duties: List<String> = listOf("写代码"),
    ) = RoleProfile(
        personaId = "p_engine",
        modelProfileId = "cloud:current",
        role = kind,
        duties = duties,
        skills = duties,
        skillIds = skillIds,
    )

    // ——————————————— 1. 传递闭包依赖解析 ———————————————

    @Test
    fun `dependent skills are assembled even when not explicitly bound`() {
        // 主技能 A 依赖 B，B 依赖 C；角色只绑了 A。
        ClusterSkillStore.upsert(ctx, skill("sk_a", "main-skill", dependsOn = listOf("sk_b")))
        ClusterSkillStore.upsert(ctx, skill("sk_b", "dep-b", dependsOn = listOf("sk_c")))
        ClusterSkillStore.upsert(ctx, skill("sk_c", "dep-c"))

        val role = role(skillIds = listOf("sk_a"))
        val assembled = ClusterSkillEngine.assemble(ctx, role)

        val names = assembled.skills.map { it.name }
        assertTrue("装配必须包含主技能 main-skill，实际=$names", names.contains("main-skill"))
        assertTrue("装配必须包含传递依赖 dep-b，实际=$names", names.contains("dep-b"))
        assertTrue("装配必须包含二级依赖 dep-c，实际=$names", names.contains("dep-c"))
        assertTrue("装配不能有多余技能，实际=$names", names.size == 3)

        // 摘要行标注依赖来源
        val summary = assembled.summaryLines.joinToString("\n")
        assertTrue("依赖技能摘要应标注来源（依赖 main-skill）", summary.contains("依赖 main-skill"))
        assertTrue("依赖技能摘要应标注来源（依赖 dep-b）", summary.contains("依赖 dep-b"))
    }

    @Test
    fun `dependency by name resolves too`() {
        ClusterSkillStore.upsert(ctx, skill("sk_x", "named-dep"))
        ClusterSkillStore.upsert(ctx, skill("sk_main", "main-by-name", dependsOn = listOf("named-dep")))

        val assembled = ClusterSkillEngine.assemble(ctx, role(skillIds = listOf("sk_main")))
        assertTrue(
            "依赖声明用技能名也应能解析，实际=${assembled.skills.map { it.name }}",
            assembled.skills.any { it.name == "named-dep" },
        )
    }

    @Test
    fun `missing dependency is reported honestly`() {
        ClusterSkillStore.upsert(ctx, skill("sk_a", "main-with-missing", dependsOn = listOf("no-such-skill")))

        val assembled = ClusterSkillEngine.assemble(ctx, role(skillIds = listOf("sk_a")))
        assertTrue(
            "缺失依赖必须如实报告，实际=${assembled.missingDependencies}",
            "no-such-skill" in assembled.missingDependencies,
        )
    }

    // ——————————————— 2. 循环检测 ———————————————

    @Test
    fun `dependency cycle is detected and excluded`() {
        ClusterSkillStore.upsert(ctx, skill("sk_a", "cycle-a", dependsOn = listOf("sk_b")))
        ClusterSkillStore.upsert(ctx, skill("sk_b", "cycle-b", dependsOn = listOf("sk_a")))

        val assembled = ClusterSkillEngine.assemble(ctx, role(skillIds = listOf("sk_a")))
        assertTrue(
            "依赖环必须被检测到，实际=${assembled.cycles}",
            assembled.cycles.isNotEmpty(),
        )
        // 环上技能不参与装配（避免死循环）
        assertFalse(
            "环上技能不应装配进角色，实际=${assembled.skills.map { it.name }}",
            assembled.skills.any { it.name == "cycle-a" || it.name == "cycle-b" },
        )
    }

    @Test
    fun `self dependency cycle is detected`() {
        ClusterSkillStore.upsert(ctx, skill("sk_self", "self-cycle", dependsOn = listOf("sk_self")))

        val assembled = ClusterSkillEngine.assemble(ctx, role(skillIds = listOf("sk_self")))
        assertTrue("自依赖也成环，必须被检测到", assembled.cycles.isNotEmpty())
    }

    // ——————————————— 3. 工具需求校验 ———————————————

    @Test
    fun `missing required tools are reported`() {
        ClusterSkillStore.upsert(
            ctx, skill("sk_tool", "tool-needy", requiresTools = listOf("write_file", "shell", "no_tool"))
        )

        // 角色实际只有 write_file
        val assembled = ClusterSkillEngine.assemble(
            ctx, role(skillIds = listOf("sk_tool")),
            actualToolNames = setOf("write_file"),
        )
        assertTrue(
            "技能需要但角色没有的工具必须报告，实际=${assembled.missingTools}",
            assembled.missingTools.contains("shell") && assembled.missingTools.contains("no_tool"),
        )
        assertFalse(
            "角色已有的工具不应报缺失，实际=${assembled.missingTools}",
            assembled.missingTools.contains("write_file"),
        )
    }

    @Test
    fun `all required tools present means no missing`() {
        ClusterSkillStore.upsert(
            ctx, skill("sk_ok", "tool-ok", requiresTools = listOf("write_file", "shell"))
        )
        val assembled = ClusterSkillEngine.assemble(
            ctx, role(skillIds = listOf("sk_ok")),
            actualToolNames = setOf("write_file", "shell"),
        )
        assertTrue("工具齐全时不应报缺失，实际=${assembled.missingTools}", assembled.missingTools.isEmpty())
    }

    // ——————————————— 4. 装配产物完整性 ———————————————

    @Test
    fun `assemble produces full resolvable skills`() {
        ClusterSkillStore.upsert(ctx, skill("sk_full", "full-skill", prompt = "完整规程正文"))
        val assembled = ClusterSkillEngine.assemble(ctx, role(skillIds = listOf("sk_full")))

        assertTrue("装配后应至少有一个技能", assembled.skills.isNotEmpty())
        assertTrue("摘要行应包含技能名", assembled.summaryLines.any { it.contains("full-skill") })
        // 执行者（EXECUTOR）应注入正文
        assertTrue(
            "执行者应注入技能正文，实际=${assembled.bodies}",
            assembled.bodies.any { it.contains("完整规程正文") },
        )
    }

    @Test
    fun `planner gets summary not full body`() {
        ClusterSkillStore.upsert(ctx, skill("sk_plan", "plan-skill", prompt = "很长的规程正文"))
        val assembled = ClusterSkillEngine.assemble(
            ctx, role(kind = RoleKind.PLANNER, skillIds = listOf("sk_plan")),
        )
        assertTrue("规划师应拿到技能（摘要），实际=${assembled.skills.map { it.name }}",
            assembled.skills.any { it.name == "plan-skill" })
        // 规划师只拿摘要（含「此处只登记其存在」标记），不注入完整规程（预算）。
        val bodies = assembled.bodies.joinToString("\n")
        assertTrue(
            "规划师应拿到技能摘要（含登记标记），实际=$bodies",
            bodies.contains("plan-skill") && bodies.contains("此处只登记其存在"),
        )
    }

    @Test
    fun `empty skillIds returns empty assembly`() {
        val assembled = ClusterSkillEngine.assemble(ctx, role(skillIds = emptyList()))
        assertTrue(assembled.skills.isEmpty())
        assertTrue(assembled.summaryLines.isEmpty())
        assertTrue(assembled.bodies.isEmpty())
        assertTrue(assembled.missingDependencies.isEmpty())
        assertTrue(assembled.missingTools.isEmpty())
    }
}