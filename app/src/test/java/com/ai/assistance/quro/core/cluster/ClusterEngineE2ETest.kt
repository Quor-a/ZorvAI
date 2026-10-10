package com.ai.assistance.quro.core.cluster

import com.ai.assistance.quro.core.QuroPersona
import com.ai.assistance.quro.core.QuroPersonaRepository
import com.ai.assistance.quro.core.tools.QuroToolRegistry
import java.util.concurrent.CopyOnWriteArrayList
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
 * 新集群（tool-first 重写）的端到端验证。
 *
 * 闭环是重写的核心目标：旧集群在真机上「对话框一个字都没有」，所以这里必须证明
 * 主持能完整跑完「定验收 → 拆解 → 点名 → 裁决 → 验收 → 收敛」并到达 GOAL_REACHED，
 * 同时角色发言（RoleUtterance）确实产生。
 *
 * 不联网、不耗 token：用 ScriptedLlmGateway 把任意 userPrompt 按阶段回固定的 JSON / 文本，
 * 并用 FakeModelSource 直接给一条可用模型，绕开真实模型配置（测试环境无 apiKey）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ClusterEngineE2ETest {

    /** 直接返回一条可用的云端模型，模型名交给 ScriptedLlmGateway 忽略。 */
    private class FakeModelSource : ClusterModelSource {
        private val m = ModelProfile(
            id = "cloud:current", displayName = "测试模型",
            kind = ModelKind.CLOUD, available = true
        )
        override fun listModels(): List<ModelProfile> = listOf(m)
        override fun get(id: String): ModelProfile? = if (id == m.id) m else null
    }

    /** 按阶段回包：主持的几个 JSON 调用 + 角色的纯文本调用。 */
    private fun scripted(prompt: String): String = when {
        "定义验收标准" in prompt ->
            """{"acceptance":["完成用户目标"],"nodes":[{"id":"n1","title":"子任务1","instruction":"做研究并输出结论","dependsOn":[]}]}"""
        "请裁决" in prompt ->
            """{"pass":true,"reason":"方案可行","steps":["执行第一步"],"chosen":"研究员"}"""
        // #200E2E：锚点用「只输出 JSON」——
        // #190 把验收 prompt 整段重写过，里面已经没有「请对照验收标准严格检查」这句，
        // 脚本还按老文案匹配 → 落到 else 分支返回纯文本 → 验收 JSON 解析失败
        // → 按不通过处理 → 节点 SKIPPED → NO_PROGRESS。
        // 判单链路是好的（能力覆盖 1/1），挂在验收闸门上。
        "只输出 JSON" in prompt && "待验收产物" in prompt ->
            """{"pass":true,"reason":"满足验收标准","checks":[{"index":1,"pass":true,"note":"已完成"}],"failed":[]}"""
        "全部子任务已完成" in prompt ->
            """{"summary":"调研完成，结论已产出"}"""
        else -> "角色输出：${prompt.take(20)}"
    }

    @Test
    fun `host drives single-node task to GOAL_REACHED and emits RoleUtterance`() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        RoleRegistry.ensureHost(context)
        // 🔴 #198：节点必须有真技能才会被执行。不绑技能的节点会被跳过，
        // 而一个没人做的任务不能被宣布成「完成」（NO_PROGRESS）。
        // 所以这里绑一门真手艺（web-research），不绑就是在测「跳过后不能算完成」。
        ClusterTestSkills.seedClusterSkills(context)
        val researchSkillId = ClusterTestSkills.skillIdByAbility(context, "联网", "调研", "搜索")

        // 登记一名专家角色，并绑定已配置模型
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
        assertTrue("测试前提：集群库里应能找到联网调研技能，实际=$researchSkillId",
            researchSkillId.isNotBlank())
        val role0 = RoleRegistry.get(context, "expert1")!!
        RoleRegistry.upsert(context, role0.copy(skillIds = listOf(researchSkillId)))

        val engine = ClusterEngine(context, ScriptedLlmGateway(::scripted), FakeModelSource())
        val cluster = ClusterConfig(id = "default", name = "默认集群")
        val task = engine.submit(cluster, "写一份折叠屏市场简报")


        val events = CopyOnWriteArrayList<ClusterEvent>()
        val job = launch { engine.events.collect { events.add(it) } }
        val reason = engine.drive(cluster, task)
        delay(200) // 让 SharedFlow 把事件投递给收集协程
        job.cancel()

        // #200E2E 护栏：能力描述「子任务1 做研究并输出结论」里，
        // 「子任务1」是纯编号标签必须被剔除，剩下的一段要靠近义词（研究↔调研）
        // 才能命中 web-research。这两条任一失效，本用例都会退回 NO_PROGRESS。
        val segs = ClusterCapability.abilitySegments("子任务1 做研究并输出结论")
        assertEquals("编号标签不该进能力分母：" + segs, listOf("做研究并输出结论"), segs)
        assertEquals("任务应闭环到 GOAL_REACHED", CloseReason.GOAL_REACHED, reason)
        assertTrue("应当产生 RoleUtterance 事件（角色确实发言）", events.any { it is ClusterEvent.RoleUtterance })
        assertTrue("应当产生 Closed 事件", events.any { it is ClusterEvent.Closed })
        assertEquals("子任务应全部完成", 1 to 1, task.progress())
    }

    @Test
    fun `cluster tools register all sixteen into registry`() {
        val reg = QuroToolRegistry()
        ClusterToolSet.registerAll(reg)
        val expected = listOf(
            "cluster_start", "cluster_status", "cluster_roles", "cluster_models",
            "cluster_bind_model", "cluster_enroll", "cluster_remove_role",
            "cluster_host_config", "cluster_abort",
            // #190 技能市场：主持可查技能、给角色配技能、从外部导入技能
            "cluster_skill_market", "cluster_skill_grant", "cluster_skill_import",
            // #191 开源技能 + 角色卡：搜开源社区、下载安装、用角色卡一键建角
            "cluster_skill_search", "cluster_skill_install", "cluster_rolecard",
            // #215 动态创造能力：缺能力时现场造一个新角色
            "cluster_forge_role"
        )
        expected.forEach { name ->
            assertTrue("缺少集群工具 $name", reg.get(name) != null)
        }
        assertEquals("应注册 16 个集群工具", 16, reg.all().size)
    }

    /**
     * 🔴 钉死「集群发言要进对话框」这条断链。
     *
     * 背景：用户报「集群进入对话框你老是另开」。真因不是另开窗口，
     * 而是集群事件只被转发进 QuroAgentTrace（思维链/诊断面板）——
     * 和消息流是两套东西，于是后台真跑模型、对话框里一个字没有。
     *
     * 旧集群靠已删的 ClusterChatProjector 做投影；tool-first 重写时漏了这条线。
     * 本测试直接验「ClusterChatBridge 绑上 store 后，角色发言会变成对话框气泡」。
     */
    @Test
    fun `cluster role utterance lands in the chat conversation store`() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        RoleRegistry.ensureHost(context)
        QuroPersonaRepository(context).upsert(
            QuroPersona(
                id = "expert1", name = "研究员",
                description = "测试专家", roleSetting = "你是研究员",
                chatSetting = "简洁", tags = listOf("内置")
            )
        )
        RoleRegistry.enroll(
            context, "expert1", modelProfileId = "cloud:current",
            role = RoleKind.EXPERT, duties = listOf("研究")
        )

        val engine = ClusterEngine(context, ScriptedLlmGateway(::scripted), FakeModelSource())
        // 模拟真实对话框：一个内存 store 作为消息流
        val store = com.ai.assistance.quro.core.QuroConversationStore()
        ClusterChatBridge.registerSink(store, engine)

        val cluster = ClusterConfig(id = "default", name = "默认集群")
        val task = engine.submit(cluster, "写一份简报")
        engine.drive(cluster, task)
        delay(300) // 等桥把事件写进 store

        ClusterChatBridge.unregisterSink()

        val msgs = store.all()
        assertTrue(
            "集群跑完后对话框里应当出现气泡，实际 0 条 —— 又变成思维链面板里的黑盒了",
            msgs.isNotEmpty()
        )
        assertTrue(
            "气泡里应当有集群角色的发言（senderName 带「集群」前缀）",
            msgs.any { it.senderName?.contains("集群") == true }
        )
        assertTrue(
            "集群发言必须 excludeFromLlm=true（用户看得见、LLM 看不见），" +
                "否则主 LLM 会以为已经有人答过而偷懒",
            msgs.filter { it.senderName?.contains("集群") == true }.all { it.excludeFromLlm }
        )
    }

    /** cluster_remove_role 的行为约束：主持不可移、不在集群的不能移、能移的要真移掉。 */
    @Test
    fun `cluster_remove_role refuses host and removes an enrolled expert`() {
        val context = RuntimeEnvironment.getApplication()
        RoleRegistry.ensureHost(context)
        QuroPersonaRepository(context).upsert(
            QuroPersona(
                id = "expert1", name = "研究员",
                description = "d", roleSetting = "你是研究员",
                chatSetting = "简洁", tags = listOf("内置")
            )
        )
        RoleRegistry.enroll(context, "expert1", modelProfileId = "cloud:current", role = RoleKind.EXPERT)

        val tool = ClusterRemoveRoleTool()

        val hostOut = tool.run(context, """{"personaId":"$HOST_ID"}""")
        assertTrue("主持不可被移出，实际返回：$hostOut", hostOut.contains("主持不可移出"))

        val missOut = tool.run(context, """{"personaId":"not_in_cluster"}""")
        assertTrue("移出不在集群的角色应报错，实际返回：$missOut", missOut.contains("不在集群"))

        val okOut = tool.run(context, """{"personaId":"expert1"}""")
        assertTrue("移出应成功，实际返回：$okOut", okOut.contains("已移出"))
        assertTrue(
            "移出后该角色不应再在集群里",
            !RoleRegistry.roles(context).any { it.personaId == "expert1" }
        )
    }

    private companion object {
        const val HOST_ID = RoleRegistry.HOST_PERSONA_ID
    }

    // ————————————————— #213 病灶 C：sync=false 必须有后台驱动入口 —————————————————

    /**
     * 🔴 用户实测：cluster_start(sync=false) 提交后任务一直停在 IDLE
     * （progress 0/0，主持从不拾取）。
     *
     * 根因：旧实现 sync=false 只 submit 存任务就返回，**没有任何后台协程调 drive()**。
     * 修复后 [ClusterRuntime.launchBackground] 会在后台作用域里立即驱动任务。
     *
     * 由于 [ClusterRuntime] 是进程级单例且依赖真实网关，这里钉住行为契约：
     * - sync=false 返回的 message 必须明说「后台推进」（不再是静默提交）；
     * - 返回体必须带 taskId（供 cluster_status 查询）；
     * - [ClusterRuntime.launchBackground] 在引擎未初始化时不崩溃（`engine ?: return`）。
     */
    @Test
    fun `cluster_start sync=false 返回后台推进提示且带 taskId`() {
        val context = RuntimeEnvironment.getApplication()
        RoleRegistry.ensureHost(context)
        // 初始化进程级单例（Robolectric 下只注册不联网）
        ClusterRuntime.init(context)
        val out = ClusterStartTool().run(context, """{"goal":"写 slogan","sync":false}""")
        val j = org.json.JSONObject(out)
        assertTrue("sync=false 应返回 ok，实际=$out", j.optBoolean("ok"))
        assertTrue("sync=false 必须带 taskId，实际=$out", j.has("taskId") && j.optString("taskId").isNotBlank())
        assertTrue(
            "返回信息必须明说后台推进（否则调用方以为只是静默存了任务），实际=${j.optString("message")}",
            j.optString("message").contains("后台"),
        )
        // 引擎已初始化，launchBackground 不应崩溃
        ClusterRuntime.launchBackground(
            ClusterConfig(id = "default", name = "默认集群"),
            ClusterTask(id = "probe", clusterId = "default", goal = "x"),
        )
    }

    // ————————————————— #213 病灶 B：开源技能缺 abilityWords 时兜底不空 —————————————————

    /**
     * 🔴 用户实测：通过 cluster_skill_grant 用 skillNames 绑定的开源技能，能力核对
     * 匹配不上（节点 SKIPPED）。
     *
     * 根因排查结论：内置集群技能 manifest 全部写了完整中文 `abilityWords`，但**开源
     * SKILL.md 的 front-matter 往往不写这个字段**。此时必须靠 [ClusterSkillStore
     * .effectiveAbilityWords] 的启发式兜底（手写表 → 自动切词），**绝不返回空列表**。
     *
     * 这条测试钉住：一个只带 name/description 的 SKILL.md（模拟开源技能），
     * 解析后 abilityWords 为空，但 effectiveAbilityWords 必须给出可匹配词。
     */
    @Test
    fun `开源技能缺 abilityWords 时兜底词表不空`() {
        val context = RuntimeEnvironment.getApplication()
        val md = """
            ---
            name: web-research-probe
            description: Research and summarize topics from the web with multiple sources
            ---
            正文规则：多源交叉、标注来源。
        """.trimIndent()
        val skill = ClusterSkillStore.parseMd(
            id = ClusterSkillStore.stableId("web-research-probe"),
            name = "web-research-probe",
            md = md,
        )
        assertEquals("开源技能 front-matter 没有 abilityWords 时字段应为空", "", skill.abilityWords)
        val words = ClusterSkillStore.effectiveAbilityWords(skill)
        assertTrue(
            "缺 abilityWords 的开源技能必须兜底出可匹配词（否则判单时该技能=不存在），实际=$words",
            words.isNotEmpty(),
        )
        // 自动切词必须包含技能名片段（research / web），否则中英匹配仍可能断
        assertTrue(
            "兜底词应含技能名切出的词（research/web），实际=$words",
            words.any { it.contains("research") || it.contains("web") },
        )
    }
}
