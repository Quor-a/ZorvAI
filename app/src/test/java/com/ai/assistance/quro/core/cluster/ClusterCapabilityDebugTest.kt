package com.ai.assistance.quro.core.cluster

import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 定位打分恒 0 的断点：技能没进库 / 技能id对不上 / 打分公式不认。
 * 打印每一步中间量。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ClusterCapabilityDebugTest {

    private val ctx get() = RuntimeEnvironment.getApplication()

    @Test
    fun `debug why bound skill scores zero`() {
        ClusterTestSkills.seedClusterSkills(ctx)
        val lib = ClusterSkillStore.load(ctx).associateBy { it.id }
        println("=== 技能库共 ${lib.size} 个 ===")
        lib.values.forEach { s ->
            println("  技能: id=${s.id} name=${s.name} abilityWords=${s.abilityWords} enabled=${s.enabled}")
        }

        val strategySkill = lib.values.firstOrNull { it.name == "strategy" }
        assertTrue("strategy 技能未进集群库！", strategySkill != null)
        println("\n=== strategy 技能详情 ===")
        println("  id=${strategySkill!!.id}")
        println("  abilityHaystack=${ClusterSkillStore.abilityHaystack(strategySkill)}")

        val role = RoleProfile(
            personaId = "persona_test_strategy",
            role = RoleKind.EXECUTOR,
            duties = listOf("承接并完成 strategy 相关子任务"),
            skills = listOf("擅长：strategy"),
            skillIds = listOf(strategySkill.id),
        )
        println("\n=== 打分过程 ===")
        val segments = ClusterCapability.abilitySegments("strategy")
        println("  abilitySegments(strategy) = $segments")
        val terms = ClusterCapability.abilityTerms("strategy")
        println("  abilityTerms(strategy) = $terms")
        val byId = role.skillIds.mapNotNull { lib[it] }
        println("  role.skillIds = ${role.skillIds}")
        println("  byId (技能库查到的) = ${byId.map { it.name }}")
        // 复刻 hitCount 逻辑（hitCount 是 private，这里用公开 API 打印中间量）
        val segments2 = ClusterCapability.abilitySegments("strategy")
        val labelled = ClusterSkillStore.effectiveAbilityWords(strategySkill)
        val full = ClusterSkillStore.abilityHaystack(strategySkill)
        println("  labelled(能力词) = $labelled")
        println("  full(干草堆) = $full")
        var hitCount = 0
        for (term in ClusterCapability.abilityTerms("strategy")) {
            val byLabel = labelled.any { h ->
                h.contains(term) || term.contains(h)
            }
            val byDesc = full.any { h ->
                h.split(Regex("[\\s,，。、；;：:（）()【】\\[\\]/+\\-—_·|]+")).filter { it.isNotBlank() }
                    .any { tok -> tok.length in 2..4 && (tok.contains(term) || term.contains(tok)) }
            }
            println("    term='$term' byLabel=$byLabel byDesc=$byDesc")
            if (byLabel || byDesc) hitCount++
        }
        println("  hitCount = $hitCount")
        val segHit = segments2.count { seg ->
            val t = ClusterCapability.abilityTerms(seg)
            var h = 0
            for (term in t) {
                val byLabel = labelled.any { hh -> hh.contains(term) || term.contains(hh) }
                val byDesc = full.any { hh ->
                    hh.split(Regex("[\\s,，。、；;：:（）()【】\\[\\]/+\\-—_·|]+")).filter { it.isNotBlank() }
                        .any { tok -> tok.length in 2..4 && (tok.contains(term) || term.contains(tok)) }
                }
                if (byLabel || byDesc) h++
            }
            h > 0
        }
        println("  segmentHitCount = $segHit")
        val score = ClusterCapability.scoreRole(role, "strategy", lib)
        println("  scoreRole = $score")
        assertTrue("分数仍为 0", score > 0f)
    }
}