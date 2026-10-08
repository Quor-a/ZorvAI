package com.ai.assistance.quro.core.cluster

import com.ai.assistance.quro.core.QuroPersona
import com.ai.assistance.quro.core.QuroPersonaRepository
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * #202：派单区分度回归测试。
 *
 * 背景：用户第 5 轮真机实测「双十一策划案」跑了 14 轮 / 20 万 tokens 熔断，
 * 表现为**策划任务派给了前端开发角色**。前四轮失败都在「能力匹配失效」，
 * 这一轮不同 —— 链路是通的（14 轮真在跑），病在**派错人**。
 *
 * 探针实测（`TmpDispatchProbe`，已删）拿到三处硬证据：
 *
 * 1. **整句当term**：`abilityTerms` 有一句 `if (raw.length in 3..24) out += raw`，
 *    把「产出可验收的方案片段」整串拿去撞干草堆 → 只要技能含「方案」二字
 *    （strategy）就算**整段命中**，含「验收」二字（review）也算整段命中。
 *    结果 8 个技能**全部 0.5 分**，与真正该接单的 planning 完全同分。
 *
 * 2. **只切前 4 个 2 字窗口**：「承接功能目标定义子任务」只切出
 *    [承接, 接功, 功能, 能目] —— 全是跨词碎片，真正的词「任务」「定义」被漏掉，
 *    planning 的「任务分解」「拆解」永远撞不上，第一段得 0 分。
 *
 * 3. **权重把 30% 押在与任务无关的信号上**：③ 历史成功率 20% + ④ 预算余量 10%。
 *    ① 能力覆盖被上面两个缺陷稀释后区分度极低，于是这 30% 成了决定名次的主力，
 *    而「谁历史成功率高」「谁预算剩得多」跟「这单该谁干」毫无关系。
 *
 * 修法：整串 term 删除 / 2 字窗口切全串 + 补同义组 / 权重改为
 * 能力覆盖 70% + 技能命中 20% + 历史 6% + 预算 4%。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ClusterDispatchRankingTest {

    private val ctx get() = RuntimeEnvironment.getApplication()

    private fun seed(): Map<String, ClusterSkillStore.ClusterSkill> {
        RoleRegistry.ensureHost(ctx)
        ClusterTestSkills.seedClusterSkills(ctx)
        return ClusterSkillStore.load(ctx).associateBy { it.id }
    }

    private fun role(id: String, name: String, duties: List<String>) {
        QuroPersonaRepository(ctx).upsert(
            QuroPersona(
                id = id, name = name, description = "test",
                roleSetting = "你是$name", chatSetting = "简洁", tags = listOf("内置")
            )
        )
        RoleRegistry.enroll(
            ctx, id, modelProfileId = "cloud:current",
            role = RoleKind.EXPERT, duties = duties
        )
    }

    // ——————————————— 缺陷 1：整句不得当term ———————————————

    @Test
    fun `整句不再作为可匹配项`() {
        // 旧码 `if (raw.length in 3..24) out += raw` 会把整句塞进 terms，
        // 导致「含某个 2 字词」就整段命中。这里钉死它不许回来。
        val terms = ClusterCapability.abilityTerms("产出可验收的方案片段")
        assertTrue(
            "整句不该作为 term 参与匹配，否则 strategy/review 会凭「方案」「验收」二字整段命中：" + terms,
            "产出可验收的方案片段" !in terms
        )
    }

    // ——————————————— 缺陷 2：2 字窗口必须切全串 ———————————————

    @Test
    fun `二字窗口切全串不只切前四个字`() {
        // 旧码 `until minOf(4, ...)` 只切前 4 个窗口 →
        // 「承接功能目标定义子任务」只出[承接, 接功, 功能, 能目]，
        // 真词「任务」被漏掉 → planning 永远拿不到这一段的分。
        val terms = ClusterCapability.abilityTerms("承接功能目标定义子任务")
        listOf("任务", "目标", "定义").forEach { w ->
            assertTrue("「$w」在句中出现过却被切词漏掉，terms=$terms", w in terms)
        }
    }

    // ——————————————— 缺陷 3：策划单必须派给 planning ———————————————

    @Test
    fun `策划子任务由planning 技能以明显优势胜出`() {
        val lib = seed()
        role("fe", "前端开发", listOf("写 HTML 页面", "做前端界面", "CSS 布局"))
        role("plan", "策划", listOf("承接功能目标定义子任务", "产出可验收的方案片段", "写策划案"))

        val ability = "承接功能目标定义子任务，产出可验收的方案片段"
        val planning = lib.values.first { it.name == "planning" }
        val htmlDev = lib.values.first { it.name == "html-dev" }

        val feRole = RoleRegistry.get(ctx, "fe")!!
        val fePlanning = ClusterCapability.scoreRole(feRole.copy(skillIds = listOf(planning.id)), ability, lib)
        val feHtml = ClusterCapability.scoreRole(feRole.copy(skillIds = listOf(htmlDev.id)), ability, lib)

        val planRole = RoleRegistry.get(ctx, "plan")!!
        val planPlanning = ClusterCapability.scoreRole(planRole.copy(skillIds = listOf(planning.id)), ability, lib)
        val planHtml = ClusterCapability.scoreRole(planRole.copy(skillIds = listOf(htmlDev.id)), ability, lib)

        println("DISPATCH fe[planning]=$fePlanning fe[html]=$feHtml plan[planning]=$planPlanning plan[html]=$planHtml")

        assertEquals(
            "planning 应完全覆盖策划能力描述的两段（修复前只有 0.5，且与 strategy/review 同分）",
            1.0f, planPlanning, 0.001f
        )
        assertTrue(
            "html-dev 与策划无关，不该拿到比 planning 更高的分（html=$planHtml planning=$planPlanning）",
            planHtml < planPlanning
        )
        assertTrue(
            "无论谁去接，planning 技能都必须显著优于 html-dev（fe: planning=$fePlanning html=$feHtml）",
            fePlanning > feHtml
        )
    }

    @Test
    fun `无关技能不再集体虚高到同一分数`() {
        // 修复前：html-dev / planning / strategy / review / code-review / ui-design /
        // role-forge / frontend-design-playbook **八个技能全部 0.5 分**，
        // 派单层完全没有区分度。这条钉死「不能再出现大面积同分」。
        val lib = seed()
        val ability = "承接功能目标定义子任务，产出可验收的方案片段"
        val feRole = RoleRegistry.get(ctx, "fe")?.copy(skillIds = emptyList())
            ?: RoleProfile(personaId = "x", role = RoleKind.EXECUTOR)

        val scores = lib.values.map { s ->
            s.name to ClusterCapability.scoreRole(feRole.copy(skillIds = listOf(s.id)), ability, lib)
        }
        println("DISPATCH scores=" + scores.sortedByDescending { it.second })
        val perfect = scores.count { it.second >= 0.99f }
        assertEquals(
            "只允许 planning 一个技能满分，前实测 8 个技能同分：" + scores,
            1, perfect
        )
        val zeroish = scores.count { it.second <= 0.31f }
        assertTrue(
            "与策划无关的技能应回落到声明分底线附近（<=0.31），实测 " +
                scores.filter { it.second <= 0.31f }.size + "/" + scores.size,
            zeroish >= lib.size - 4
        )
    }

    // ——————————————— 缺陷 4：权重不得被与任务无关的信号主导 ———————————————

    @Test
    fun `派单权重以任务相关性为主`() {
        val src = File(repoRoot(), "app/src/main/java/com/ai/assistance/quro/core/cluster/ClusterEngine.kt")
            .readText()
        // ③ 历史成功率 20% + ④ 预算余量 10% = 30% 与任务无关，
        // 在能力分区分度不足时会主导名次 → 策划单派给前端。
        assertTrue(
            "能力覆盖权重应升到 70%，让任务相关性成为决定性因素",
            src.contains("s += cap * 0.70f")
        )
        assertTrue("技能命中权重应为 20%", src.contains("s += 0.20f"))
        assertTrue("历史成功率应压到 6%", src.contains("* 0.06f"))
        assertTrue("预算余量应压到 4%", src.contains("* 0.04f"))
        assertTrue(
            "旧权重 0.45/0.25/0.2/0.1 不得残留",
            !src.contains("s += cap * 0.45f") && !src.contains("s += 0.25f")
        )
    }

    private fun repoRoot(): File {
        var d: File? = File(System.getProperty("user.dir")).absoluteFile
        repeat(6) {
            val cur = d ?: return File(System.getProperty("user.dir"))
            if (File(cur, "settings.gradle.kts").exists()) return cur
            d = cur.parentFile
        }
        return File(System.getProperty("user.dir"))
    }
}
