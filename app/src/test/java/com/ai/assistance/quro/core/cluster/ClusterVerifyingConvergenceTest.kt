package com.ai.assistance.quro.core.cluster

import com.ai.assistance.quro.core.QuroPersona
import com.ai.assistance.quro.core.QuroPersonaRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * #207 回归：**VERIFYING 验收必须能收敛**。
 *
 * ## 用户实测（第 6 轮）
 *
 * 极简任务「20 字 slogan」（主持只拆 1 个节点）也在 **VERIFYING 阶段熔断**，
 * 烧掉 47k tokens。前 5 轮栽在派单/能力匹配，这一轮是**第三种故障模式**：
 * 产物「无法收敛」。
 *
 * ## 真因（读码确认）
 *
 * `verifying()` 的 FAIL 分支只做 `node.attempt++ / replanCount++ / state=FAILED`
 * 然后回 REPLANNING，**从不检查 [ClusterBudget.maxNodeAttempts]**。
 *
 * 而 `maxReplan = 3` 与 `maxNodeAttempts = 3` 相等，且
 * `replanCount > maxReplan → ESCALATED` 的判定发生在**进 REPLANNING 之前**，
 * 于是 REPLANNING 里那句「attempt >= maxNodeAttempts 就 SKIPPED」**永远走不到** ——
 * 是死代码。
 *
 * 结果：执行→验收→不过→重做 反复 4 轮后直接熔断。主观任务（slogan/文案）
 * 的验收方每次都能挑出新毛病，产物永远「无法收敛」。
 *
 * ## 修法
 *
 * 与 [ClusterVerdictProtocol] 的 UNPARSABLE 分支同一口径：
 * 连败到上限 → **降级放行 + 如实记账**（`ClusterNode.releaseUnverified`），
 * 而不是无限重做到熔断。
 *
 * ## 🔴 为什么「记账」这一步不能省
 *
 * 只放行不记账的话，[ClusterEngine.converging] 会无条件报 `GOAL_REACHED`，
 * 「验收从未通过」就被包装成「圆满达成」—— 那是**假闭环**，比熔断更糟。
 * 所以本测试同时钉住：放行后总结里必须点名「未通过验收」。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ClusterVerifyingConvergenceTest {

    private class FakeModelSource : ClusterModelSource {
        private val m = ModelProfile(
            id = "cloud:current", displayName = "测试模型",
            kind = ModelKind.CLOUD, available = true
        )
        override fun listModels(): List<ModelProfile> = listOf(m)
        override fun get(id: String): ModelProfile? = if (id == m.id) m else null
    }

    /** 验收**永远不通过**——模拟「slogan 每次都被挑出新毛病」的不可收敛场景。 */
    private fun neverPass(prompt: String): String = when {
        "定义验收标准" in prompt ->
            """{"acceptance":["朗朗上口且不超过20字"],"nodes":[{"id":"n1","title":"子任务1","instruction":"做研究并输出结论","dependsOn":[]}]}"""
        // 🔴 关键字跟着**当前**提示词：#211 之后裁决提示词里已无「请裁决」二字，
        // 这里若继续按旧关键字匹配，裁决输出会落进 else 被当成执行产物，
        // 裁决解析失败走兜底 —— 测试就测不到真正的裁决/验收路径了。
        "confidence" in prompt ->
            """{"summary":"方案","options":[{"name":"A","pros":"快","cons":"糙"}],"recommend":"A","steps":["执行第一步"],"risks":["无"],"confidence":0.8}"""
        "chosen" in prompt ->
            """{"pass":true,"reason":"方案可行","steps":["执行第一步"],"chosen":"研究员"}"""
        "待验收产物" in prompt ->
            """{"pass":false,"reason":"不够朗朗上口","checks":[{"index":1,"pass":false,"note":"不够朗朗上口"}],"failed":["换更押韵的写法"]}"""
        "请如实总结" in prompt || "全部子任务已完成" in prompt ->
            """{"summary":"slogan 已产出"}"""
        else -> "角色输出：${prompt.take(20)}"
    }

    /** 对照组：验收一次就过。 */
    private fun alwaysPass(prompt: String): String = when {
        "定义验收标准" in prompt ->
            """{"acceptance":["朗朗上口且不超过20字"],"nodes":[{"id":"n1","title":"子任务1","instruction":"做研究并输出结论","dependsOn":[]}]}"""
        // 🔴 关键字跟着**当前**提示词：#211 之后裁决提示词里已无「请裁决」二字，
        // 这里若继续按旧关键字匹配，裁决输出会落进 else 被当成执行产物，
        // 裁决解析失败走兜底 —— 测试就测不到真正的裁决/验收路径了。
        "confidence" in prompt ->
            """{"summary":"方案","options":[{"name":"A","pros":"快","cons":"糙"}],"recommend":"A","steps":["执行第一步"],"risks":["无"],"confidence":0.8}"""
        "chosen" in prompt ->
            """{"pass":true,"reason":"方案可行","steps":["执行第一步"],"chosen":"研究员"}"""
        "待验收产物" in prompt ->
            """{"pass":true,"reason":"满足验收标准","checks":[{"index":1,"pass":true,"note":"已完成"}],"failed":[]}"""
        "请如实总结" in prompt || "全部子任务已完成" in prompt ->
            """{"summary":"slogan 已产出"}"""
        else -> "角色输出：${prompt.take(20)}"
    }

    private fun buildContext(): android.content.Context {
        val context = RuntimeEnvironment.getApplication()
        RoleRegistry.ensureHost(context)
        ClusterTestSkills.seedClusterSkills(context)
        // 🔴 走 E2E 已验证可行的技能路径（web-research / 联网·调研·搜索）。
        // 本测试关注的是**验收能否收敛**，不是能力匹配；用一条一定能过的前置，
        // 否则节点会被 SKIPPED，测的就不是验收了（第一轮跑出来就是死在这）。
        val skillId = ClusterTestSkills.skillIdByAbility(context, "联网", "调研", "搜索")
        assertTrue("测试前提：应能找到联网调研技能，实际=$skillId", skillId.isNotBlank())
        QuroPersonaRepository(context).upsert(
            QuroPersona(
                id = "expert1", name = "研究员",
                description = "测试专家", roleSetting = "你是研究员",
                chatSetting = "简洁", tags = listOf("内置")
            )
        )
        RoleRegistry.enroll(
            context, "expert1", modelProfileId = "cloud:current",
            role = RoleKind.EXPERT, duties = listOf("研究", "调研")
        )
        val r = RoleRegistry.get(context, "expert1")!!
        RoleRegistry.upsert(context, r.copy(skillIds = listOf(skillId)))
        return context
    }

    /**
     * 🔴 核心判据：验收永远不通过时，**不许熔断**（改前就是 4 轮后 CIRCUIT_BROKEN），
     * 而应当在单节点重试上限处收敛。
     */
    @Test
    fun 验收永不通过时不再熔断而是收敛() = runBlocking {
        val context = buildContext()
        val engine = ClusterEngine(context, ScriptedLlmGateway(::neverPass), FakeModelSource())
        val cluster = ClusterConfig(id = "default", name = "默认集群")
        val task = engine.submit(cluster, "写一句 20 字 slogan")

        val job = launch { engine.events.collect {} }
        val reason = engine.drive(cluster, task)
        delay(200)
        job.cancel()

        assertFalse(
            "验收不通过不该一路空转到熔断（改前 4 轮后 CIRCUIT_BROKEN、47k tokens），实际=$reason",
            reason == CloseReason.CIRCUIT_BROKEN,
        )
        val node = task.nodes.first()
        assertTrue(
            "连败到上限后应降级放行并置 releaseUnverified，实际 attempt=${node.attempt} state=${node.state}",
            node.releaseUnverified,
        )
        assertTrue(
            "重做次数必须受 maxNodeAttempts 约束，实际 attempt=${node.attempt}",
            node.attempt <= cluster.budget.maxNodeAttempts,
        )
    }

    /**
     * 🔴 「放行」不等于「通过」：总结里必须点名未通过验收，
     * 否则 [ClusterEngine.converging] 的 GOAL_REACHED 会把没验收的产物
     * 包装成「圆满达成」—— 那是假闭环。
     */
    @Test
    fun 降级放行必须在总结里点名未通过验收() = runBlocking {
        val context = buildContext()
        val engine = ClusterEngine(context, ScriptedLlmGateway(::neverPass), FakeModelSource())
        val cluster = ClusterConfig(id = "default", name = "默认集群")
        val task = engine.submit(cluster, "写一句 20 字 slogan")

        val job = launch { engine.events.collect {} }
        engine.drive(cluster, task)
        delay(200)
        job.cancel()

        val summary = task.summary.orEmpty()
        assertTrue(
            "总结必须点名「未通过验收」，否则降级放行就变成了假闭环。实际总结=$summary",
            summary.contains("未通过验收"),
        )
    }

    /** 反向保护：验收一次就过时，走正常路径，不得被「降级放行」口径误伤。 */
    @Test
    fun 验收通过时不置位未验收标记() = runBlocking {
        val context = buildContext()
        val engine = ClusterEngine(context, ScriptedLlmGateway(::alwaysPass), FakeModelSource())
        val cluster = ClusterConfig(id = "default", name = "默认集群")
        val task = engine.submit(cluster, "写一句 20 字 slogan")

        val job = launch { engine.events.collect {} }
        val reason = engine.drive(cluster, task)
        delay(200)
        job.cancel()

        assertEquals("正常验收通过应闭环到 GOAL_REACHED，实际=$reason", CloseReason.GOAL_REACHED, reason)
        assertFalse(
            "真通过了就不该置 releaseUnverified",
            task.nodes.first().releaseUnverified,
        )
        assertFalse(
            "真通过了总结里不该出现「未通过验收」，实际=${task.summary}",
            task.summary.orEmpty().contains("未通过验收"),
        )
    }

    /**
     * 记账字段必须能往返序列化 —— 集群状态会落盘/回显，
     * 丢了就等于「放行过」这件事消失，UI 上又变成「圆满达成」。
     */
    @Test
    fun 未验收标记能往返序列化() {
        val n = ClusterNode(id = "n1", title = "t", releaseUnverified = true)
        val back = ClusterNode.fromJson(n.toJson())
        assertTrue("releaseUnverified 往返丢失", back.releaseUnverified)

        val n2 = ClusterNode(id = "n2", title = "t")
        assertFalse("默认不该是未验收", ClusterNode.fromJson(n2.toJson()).releaseUnverified)
    }
}