package com.ai.assistance.quro.core.cluster

import com.ai.assistance.quro.core.QuroPersona
import com.ai.assistance.quro.core.QuroPersonaRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * #210 回归：两处都是**用户第 7 轮实测**点名的靶子。
 *
 * ## 靶子一：能力标签匹配失效
 *
 * 实测：节点 n3「定时刷新逻辑」SKIPPED，报「无人具备所需能力 JavaScript 编程」，
 * 而角色绑的就是前端技能。
 *
 * 真因（读码 + 实测确认）：**不是打分公式错，是根本没有可供匹配的词**。
 * - 技能名/描述是英文（`frontend-dev` / `html-dev` / "Full-stack frontend development…"）；
 * - 主持拆的能力标签是中英混写（「JavaScript 编程」）；
 * - `contains` 跨语言恒 false，而旧的近义组里**没有一条中英桥梁**，
 *   也没有「编程/编码/开发」这一组 —— 于是 `编程` 与 `编码` 互相撞不上。
 * 结果：`hitCount` 两词全 0 → 0/2 < 0.34 → 判「无人具备」→ SKIPPED。
 *
 * 修法：近义组改成**中英混排**（同一组里同时放中文与英文说法），
 * 并给没写 abilityWords 的技能加自动切词兜底（空列表 = 这个技能等于不存在）。
 *
 * ## 靶子二：验收没有「给意见 → 修改 → 再验收」的闭环
 *
 * 实测：n1 正确执行 3 次，每次都被驳回，最后熔断在 VERIFYING。
 *
 * 真因：`executing()` 的提示词里**只有任务说明与验收标准，没有上一版的驳回意见**，
 * 而且执行计划 `artifact` 早被上一版产物覆盖了 —— 角色既不知道差在哪，
 * 也没有底稿可改，只能从零重新想象一份，产出同质产物 → 再次被驳回。
 * 更糟的是每驳回一次都要回 REPLANNING 重走「派单→提方案→裁决→执行」四步。
 *
 * 修法：执行计划与产物分开存（`plan` / `lastArtifact`），
 * 首次不通过直接回 EXECUTING，把「驳回意见 + 上一版产物」一起交回执行角色。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ClusterCapabilityAndReviseTest {

    private class FakeModelSource : ClusterModelSource {
        private val m = ModelProfile(
            id = "cloud:current", displayName = "测试模型",
            kind = ModelKind.CLOUD, available = true
        )
        override fun listModels(): List<ModelProfile> = listOf(m)
        override fun get(id: String): ModelProfile? = if (id == m.id) m else null
    }

    private fun skill(
        name: String,
        words: String = "",
        desc: String = "",
    ): ClusterSkillStore.ClusterSkill = ClusterSkillStore.ClusterSkill(
        id = name, name = name, description = desc,
        prompt = "", trigger = "", abilityWords = words, enabled = true,
    )

    private fun expertWith(vararg skillIds: String): RoleProfile = RoleProfile(
        personaId = "expert1", modelProfileId = "cloud:current",
        role = RoleKind.EXPERT, duties = listOf("实现界面与交互"),
        skillIds = skillIds.toList(),
    )

    // ————————————————— 靶子一：能力匹配 —————————————————

    /**
     * 🔴 用户实测的原话场景：能力标签「JavaScript 编程」匹配不上前端技能。
     *
     * 改前两段词全部命中 0（`javascript` 撞不上 `html`，`编程` 撞不上 `编码`），
     * score=0 < 0.34 → 节点被判「无人具备」→ SKIPPED。
     */
    @Test
    fun JavaScript编程能匹配上前端技能() {
        val lib = mapOf("html-dev" to skill("html-dev"))
        val score = ClusterCapability.scoreRole(expertWith("html-dev"), "JavaScript 编程", lib)
        assertTrue(
            "「JavaScript 编程」应能匹配上 html-dev（改前恒 0 分 → 节点 SKIPPED），实际 score=$score",
            score >= ClusterCapability.COVER_THRESHOLD,
        )
    }

    /** 中文能力标签也要能撞上**纯英文**技能（#197 中英断层的真正修法）。 */
    @Test
    fun 纯英文技能也能被中文能力标签命中() {
        val s = skill("frontend-dev", desc = "Full-stack frontend development with javascript")
        val lib = mapOf("frontend-dev" to s)
        val score = ClusterCapability.scoreRole(expertWith("frontend-dev"), "JavaScript 编程", lib)
        assertTrue(
            "纯英文技能必须能被中文能力标签命中，实际 score=$score",
            score >= ClusterCapability.COVER_THRESHOLD,
        )
    }

    /**
     * 🔴 黑户兜底：随包 81 个技能里只有 20 个手写了能力词，
     * 其余一旦返回**空列表**，这个技能对任何能力都是 0 分 ——
     * 绑着它的角色一律被判「不具备能力」。空列表等于技能不存在。
     */
    @Test
    fun 没写能力词的技能不得返回空词表() {
        val s = skill("frontend-dev", desc = "Full-stack frontend development")
        val words = ClusterSkillStore.effectiveAbilityWords(s)
        assertTrue(
            "没写 abilityWords 的技能必须靠自动切词兜底，实际=$words",
            words.isNotEmpty(),
        )
        assertTrue(
            "技能名切出的词必须进词表（它最能代表这技能是什么），实际=$words",
            words.any { it.contains("frontend") },
        )
    }

    // ————————————————— 靶子二：返工闭环 —————————————————

    private fun buildContext(): android.content.Context {
        val context = RuntimeEnvironment.getApplication()
        RoleRegistry.ensureHost(context)
        ClusterTestSkills.seedClusterSkills(context)
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

    private fun intakeJson(): String =
        """{"acceptance":["朗朗上口"],"nodes":[{"id":"n1","title":"子任务1","instruction":"写 slogan","ability":"联网调研","dependsOn":[]}]}"""

    /**
     * 🔴 核心判据：第一次验收不通过后，角色**必须**收到
     * ① 驳回意见 ② 上一版产物 —— 两者缺一个，「修改」就退化成「重新想象一份」。
     */
    @Test
    fun 验收不通过后重做必须带上驳回意见与上一版产物() = runBlocking {
        val seen = java.util.Collections.synchronizedList(mutableListOf<String>())
        var execCount = 0
        var verifyCount = 0
        fun script(prompt: String): String {
            seen += prompt
            return when {
                "定义验收标准" in prompt -> intakeJson()
                // 🔴 关键字必须跟着**当前**提示词走。#211 把「请裁决」改写成了
                // 「按顺序输出」，这里若还按旧关键字匹配，裁决输出会落进 else，
                // 被当成执行产物 → 裁决解析失败 → 走兜底分支，测的就不是裁决了。
                "confidence" in prompt ->
                    """{"summary":"方案","options":[{"name":"A","pros":"快","cons":"糙"}],"recommend":"A","steps":["写一版"],"risks":["无"],"confidence":0.8}"""
                "chosen" in prompt ->
                    """{"pass":true,"reason":"可行","steps":["写一版"],"chosen":"研究员"}"""
                "待验收产物" in prompt -> {
                    verifyCount++
                    if (verifyCount == 1) {
                        """{"pass":false,"reason":"不够押韵","checks":[{"index":1,"pass":false,"note":"不够押韵"}],"issues":[{"index":1,"problem":"结尾不押韵","why":"读起来不顺口","fix":"把结尾改成 ang 韵并保留原意","severity":"major"}],"failed":["改成押韵"]}"""
                    } else {
                        """{"pass":true,"reason":"达标","checks":[{"index":1,"pass":true,"note":"ok"}],"issues":[],"failed":[]}"""
                    }
                }
                "请如实总结" in prompt || "全部子任务已完成" in prompt -> """{"summary":"slogan 已产出"}"""
                else -> {
                    execCount++
                    "产物V$execCount"
                }
            }
        }

        val context = buildContext()
        val engine = ClusterEngine(context, ScriptedLlmGateway(::script), FakeModelSource())
        val cluster = ClusterConfig(id = "default", name = "默认集群")
        val task = engine.submit(cluster, "写一句 slogan")

        val job = launch { engine.events.collect {} }
        val reason = engine.drive(cluster, task)
        delay(200)
        job.cancel()

        assertEquals(
            "改一次就达标的话应当正常闭环，实际=$reason",
            CloseReason.GOAL_REACHED, reason,
        )
        assertEquals("应当只返工一次，实际执行 $execCount 次", 2, execCount)

        // 🔴 关键字必须精确：验收提示词里也有「不会拿它去驱动返工」这句说明，
        // 只匹配「返工」二字会先命中验收 prompt，断言读到的根本不是执行提示词。
        val revise = seen.firstOrNull { "⚠ 这是" in it }
        assertTrue(
            "第二次执行的提示词里必须标明这是返工，否则角色不知道要改什么",
            revise != null,
        )
        assertTrue(
            "返工提示词必须带上驳回意见「不够押韵」，实际=\n$revise",
            revise!!.contains("不够押韵"),
        )
        assertTrue(
            "返工提示词必须带上一版产物当修改底稿，实际=\n$revise",
            revise.contains("产物V1"),
        )
        assertTrue(
            "返工提示词必须带**怎么改**（不能只说不合格），实际=\n$revise",
            revise.contains("怎么改"),
        )
    }

    /**
     * 🔴 用户原话：评审「只驳回」，不说出了什么问题、怎么改 —— **盲目驳回**。
     *
     * 判据：一条「怎么改」都没有的驳回**不得**拿去驱动返工。
     * 它只能被退回要求补齐；补不出来就如实记账放行，绝不假装验收过。
     */
    @Test
    fun 只给不合格却不给改法的驳回不得驱动返工() = runBlocking {
        val seen = java.util.Collections.synchronizedList(mutableListOf<String>())
        var execCount = 0
        var verifyCount = 0
        fun script(prompt: String): String {
            seen += prompt
            return when {
                "定义验收标准" in prompt -> intakeJson()
                // 🔴 关键字必须跟着**当前**提示词走。#211 把「请裁决」改写成了
                // 「按顺序输出」，这里若还按旧关键字匹配，裁决输出会落进 else，
                // 被当成执行产物 → 裁决解析失败 → 走兜底分支，测的就不是裁决了。
                "confidence" in prompt ->
                    """{"summary":"方案","options":[{"name":"A","pros":"快","cons":"糙"}],"recommend":"A","steps":["写一版"],"risks":["无"],"confidence":0.8}"""
                "chosen" in prompt ->
                    """{"pass":true,"reason":"可行","steps":["写一版"],"chosen":"研究员"}"""
                "待验收产物" in prompt -> {
                    verifyCount++
                    // 永远「不合格」，但一条 fix 都不给
                    """{"pass":false,"reason":"不够好","checks":[{"index":1,"pass":false,"note":"不够好"}],"issues":[],"failed":[]}"""
                }
                "请如实总结" in prompt || "全部子任务已完成" in prompt -> """{"summary":"slogan 已产出"}"""
                else -> {
                    execCount++
                    "产物V$execCount"
                }
            }
        }

        val context = buildContext()
        val engine = ClusterEngine(context, ScriptedLlmGateway(::script), FakeModelSource())
        val cluster = ClusterConfig(id = "default", name = "默认集群")
        val task = engine.submit(cluster, "写一句 slogan")

        val job = launch { engine.events.collect {} }
        engine.drive(cluster, task)
        delay(200)
        job.cancel()

        assertEquals(
            "无效驳回不该驱动返工（执行角色无从下手），实际执行 $execCount 次",
            1, execCount,
        )
        assertTrue(
            "意见无效时应退回要求补齐，实际验收只调用了 $verifyCount 次",
            verifyCount > 1,
        )
        assertTrue(
            "补不出改法就应当如实记账放行，而不是假装验收通过",
            task.nodes.first().releaseUnverified,
        )
        assertTrue(
            "放行时总结里必须点名未通过验收，实际=${task.summary}",
            task.summary.orEmpty().contains("未通过验收"),
        )
    }

    /** 反向保护：一次就验收通过时，不得出现返工段，也不得多跑一次执行。 */
    @Test
    fun 验收一次通过时不触发返工() = runBlocking {
        val seen = java.util.Collections.synchronizedList(mutableListOf<String>())
        var execCount = 0
        fun script(prompt: String): String {
            seen += prompt
            return when {
                "定义验收标准" in prompt -> intakeJson()
                // 🔴 关键字必须跟着**当前**提示词走。#211 把「请裁决」改写成了
                // 「按顺序输出」，这里若还按旧关键字匹配，裁决输出会落进 else，
                // 被当成执行产物 → 裁决解析失败 → 走兜底分支，测的就不是裁决了。
                "confidence" in prompt ->
                    """{"summary":"方案","options":[{"name":"A","pros":"快","cons":"糙"}],"recommend":"A","steps":["写一版"],"risks":["无"],"confidence":0.8}"""
                "chosen" in prompt ->
                    """{"pass":true,"reason":"可行","steps":["写一版"],"chosen":"研究员"}"""
                "待验收产物" in prompt ->
                    """{"pass":true,"reason":"达标","checks":[{"index":1,"pass":true,"note":"ok"}],"failed":[]}"""
                "请如实总结" in prompt || "全部子任务已完成" in prompt -> """{"summary":"slogan 已产出"}"""
                else -> {
                    execCount++
                    "产物V$execCount"
                }
            }
        }

        val context = buildContext()
        val engine = ClusterEngine(context, ScriptedLlmGateway(::script), FakeModelSource())
        val cluster = ClusterConfig(id = "default", name = "默认集群")
        val task = engine.submit(cluster, "写一句 slogan")

        val job = launch { engine.events.collect {} }
        val reason = engine.drive(cluster, task)
        delay(200)
        job.cancel()

        assertEquals(CloseReason.GOAL_REACHED, reason)
        assertEquals(
            "一次就过不该多跑一次执行，实际 $execCount 次；全部 prompt：\n" +
                seen.mapIndexed { i, s ->
                    "$i: " + s.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().take(70)
                }.joinToString("\n"),
            1, execCount,
        )
        assertTrue(
            "一次就过不该出现返工提示段",
            seen.none { "⚠ 这是" in it },
        )
    }

    // ————————————————— 靶子三：绑定真实技能后 audit 必须识别（用户实测） —————————————————

    /**
     * 🔴 用户实测：规划师**绑定了 copywrite**（6 门技能之一），
     * 但 n2 需要「文案写作」时 host 却判「无人具备」→ 节点 SKIPPED。
     *
     * 这条测试用**真实 manifest 播种**的集群库 + 真实技能 id，
     * 复现「角色 skillIds 里有 copywrite，audit 对『文案写作』必须判覆盖」。
     */
    @Test
    fun 绑定真实copywrite技能后audit必须识别文案写作() {
        val context = RuntimeEnvironment.getApplication()
        ClusterTestSkills.seedClusterSkills(context)
        val lib = ClusterSkillStore.load(context).associateBy { it.id }

        // 找 copywrite 的真实 id（manifest 播种出来的）
        val copy = lib.values.firstOrNull { it.name == "copywrite" }
        assertTrue("测试前提：集群库应有 copywrite 技能", copy != null)

        val role = RoleProfile(
            personaId = "planner1",
            modelProfileId = "cloud:current",
            role = RoleKind.PLANNER,
            duties = listOf("文案写作", "内容策划"),
            skillIds = listOf(copy!!.id),
        )
        val covs = ClusterCapability.audit(
            context,
            listOf(ClusterNode(id = "n2", title = "子任务2", instruction = "写宣传文案", ability = "文案写作")),
            listOf(role),
        )
        val cov = covs.first()
        assertTrue(
            "角色绑定了真实 copywrite 技能，audit 对『文案写作』必须判覆盖。" +
                "实际 score=${cov.score}（阈值 ${ClusterCapability.COVER_THRESHOLD}），" +
                "bestRole=${cov.bestRoleId}，declared=${cov.declared}",
            cov.covered,
        )
    }

    /**
     * 🔴 配套：绑定 strategy 后 audit 对「策略制定」必须识别。
     * 用户实测 n1 需要 strategy，补绑后仍失败。
     */
    @Test
    fun 绑定真实strategy技能后audit必须识别策略() {
        val context = RuntimeEnvironment.getApplication()
        ClusterTestSkills.seedClusterSkills(context)
        val lib = ClusterSkillStore.load(context).associateBy { it.id }

        val strat = lib.values.firstOrNull { it.name == "strategy" }
        assertTrue("测试前提：集群库应有 strategy 技能", strat != null)

        val role = RoleProfile(
            personaId = "planner2",
            modelProfileId = "cloud:current",
            role = RoleKind.PLANNER,
            duties = listOf("策略制定", "方案取舍"),
            skillIds = listOf(strat!!.id),
        )
        val covs = ClusterCapability.audit(
            context,
            listOf(ClusterNode(id = "n1", title = "子任务1", instruction = "制定策略", ability = "策略制定")),
            listOf(role),
        )
        val cov = covs.first()
        assertTrue(
            "角色绑定了真实 strategy 技能，audit 对『策略制定』必须判覆盖。" +
                "实际 score=${cov.score}（阈值 ${ClusterCapability.COVER_THRESHOLD}）",
            cov.covered,
        )
    }
}
