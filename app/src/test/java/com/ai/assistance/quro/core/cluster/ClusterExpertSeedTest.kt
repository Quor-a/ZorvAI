package com.ai.assistance.quro.core.cluster

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * #215：五专家默认播种测试。
 *
 * 验证老用户升级后，集群角色列表里**默认出现**用户点名的五张专家卡，
 * 而不是只有主持 —— 这是「打开设置页感觉什么都没变」的直接回归防线。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ClusterExpertSeedTest {

    private val ctx get() = RuntimeEnvironment.getApplication()

    private val seededIds = listOf(
        "logic-thinker",
        "prompt-engineer",
        "ai-software-dev",
        "bug-detector",
        "completeness-auditor",
    )

    @Test
    fun `five expert cards exist in builtin catalog with tags`() {
        seededIds.forEach { id ->
            val card = ClusterRoleCards.byId(id)
            assertNotNull("缺少专家角色卡：$id", card)
            assertTrue("卡 $id 缺少角色类型标签", card!!.tags.isNotEmpty())
        }
    }

    @Test
    fun `seedBuiltinExperts registers all five experts into role registry`() {
        // 准备集群技能库（测试环境 assets 读不到，走磁盘）
        val seeded = ClusterTestSkills.seedClusterSkills(ctx)
        assertTrue("集群技能库应播种出技能，实际 $seeded", seeded > 0)

        // 先确保只有主持
        val before = RoleRegistry.roles(ctx)
        RoleRegistry.upsert(ctx, RoleProfile(personaId = "dummy_probe", role = RoleKind.EXPERT))
        RoleRegistry.delete(ctx, "dummy_probe")

        // 执行播种
        RoleRegistry.seedBuiltinExpertsForTest(ctx)

        val roles = RoleRegistry.roles(ctx)
        val experts = roles.filter { it.role != RoleKind.HOST }.map { it.personaId }.toSet()

        seededIds.forEach { id ->
            val personaId = "persona_cluster_${id}_seed"
            assertTrue("五专家未默认注册：$personaId", experts.contains(personaId))
        }

        // 主持仍在
        assertTrue(roles.any { it.personaId == RoleRegistry.HOST_PERSONA_ID })

        // 幂等：再种一次不重复
        RoleRegistry.seedBuiltinExpertsForTest(ctx)
        val after = RoleRegistry.roles(ctx).filter { it.role != RoleKind.HOST }
        assertEquals(seededIds.size, after.size)
    }
}