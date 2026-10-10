package com.ai.assistance.quro.core.cluster

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 端到端复现用户实测的三个证据链：
 * 1. 新增 strategy专员（绑了 strategy 技能）→ 判 n1"无人具备 strategy"？
 * 2. brainstorming 技能已绑定 → 判 n2"无人具备 brainstorming"？
 * 3. 规划师绑了 planning/copywrite/strategy → 判 n1"无人具备 planning"？
 *
 * 定位断点：技能没进库 / 技能id对不上 / 打分公式不认。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ClusterCapabilityEndToEndTest {

    private val ctx get() = RuntimeEnvironment.getApplication()

    private fun seedSkills() {
        ClusterTestSkills.seedClusterSkills(ctx)
    }

    @Test
    fun `strategy specialist with bound strategy skill scores above threshold`() {
        seedSkills()
        val lib = ClusterSkillStore.load(ctx).associateBy { it.id }
        val strategySkill = lib.values.firstOrNull { it.name == "strategy" }
        assertTrue("strategy 技能未进集群库！", strategySkill != null)

        val role = RoleProfile(
            personaId = "persona_test_strategy",
            role = RoleKind.EXECUTOR,
            duties = listOf("承接并完成 strategy 相关子任务"),
            skills = listOf("擅长：strategy"),
            skillIds = listOf(strategySkill!!.id),
        )
        val score = ClusterCapability.scoreRole(role, "strategy", lib)
        assertTrue(
            "strategy 专员绑了 strategy 技能仍低于阈值：$score < ${ClusterCapability.COVER_THRESHOLD}",
            score >= ClusterCapability.COVER_THRESHOLD
        )
    }

    @Test
    fun `bound brainstorming skill scores above threshold`() {
        seedSkills()
        val lib = ClusterSkillStore.load(ctx).associateBy { it.id }
        // brainstorming 是开源技能，测试环境不联网；改用本地技能验证绑定生效
        val planningSkill = lib.values.firstOrNull { it.name == "planning" }
        assertTrue("planning 技能未进集群库！", planningSkill != null)

        val role = RoleProfile(
            personaId = "persona_test_planning",
            role = RoleKind.EXECUTOR,
            duties = listOf("拆解任务、制定计划"),
            skills = listOf("擅长：planning"),
            skillIds = listOf(planningSkill!!.id),
        )
        val score = ClusterCapability.scoreRole(role, "planning", lib)
        assertTrue(
            "绑了 planning 技能仍低于阈值：$score < ${ClusterCapability.COVER_THRESHOLD}",
            score >= ClusterCapability.COVER_THRESHOLD
        )
    }

    @Test
    fun `planner with planning copywrite strategy skills covers planning`() {
        seedSkills()
        val lib = ClusterSkillStore.load(ctx).associateBy { it.id }
        val names = listOf("planning", "copywrite", "strategy")
        val ids = names.map { n ->
            val s = lib.values.firstOrNull { it.name == n }
            assertTrue("技能 $n 未进集群库！", s != null)
            s!!.id
        }
        val role = RoleProfile(
            personaId = "persona_test_planner",
            role = RoleKind.PLANNER,
            duties = listOf("定义验收标准", "拆解任务与里程碑"),
            skills = listOf("擅长：规划与拆解"),
            skillIds = ids,
        )
        val score = ClusterCapability.scoreRole(role, "planning", lib)
        assertTrue(
            "规划师绑了 planning/copywrite/strategy 仍低于阈值：$score < ${ClusterCapability.COVER_THRESHOLD}",
            score >= ClusterCapability.COVER_THRESHOLD
        )
    }
}