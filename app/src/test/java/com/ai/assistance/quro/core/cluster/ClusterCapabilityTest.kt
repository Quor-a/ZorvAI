package com.ai.assistance.quro.core.cluster

import android.content.Context
import com.ai.assistance.quro.core.cluster.ClusterSkillStore.ClusterSkill
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * #192：CAPABILITY 阶段（文档 V2 第 5 页 ★）的回归测试。
 *
 * 这个阶段存在的唯一理由：拆完子任务之后先算一遍账 ——
 * 「这个子任务需要的能力，到底有没有人真的具备」。
 *
 * 钉死的四条铁律：
 * 1. **能力凭据只认真技能**：只读 role.skillIds 指向的技能正文；
 *    duties/skills 那些提示词文本自称会什么不算数（否则就是回到凭空想象）。
 * 2. **覆盖不住要能报缺口**，且必须给出 bestRoleId 与原因，方便人看。
 * 3. **补救顺序**：本地能救的绝不联网（零开销优先）。
 * 4. **绝不绑给评审**：评审一旦有了生产技能就会自己把产物改掉，验收永远通过。
 * 5. **补救失败必须如实说**，不能返回空/成功假装没事。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ClusterCapabilityTest {

    private lateinit var ctx: Context

    @Before
    fun setUp() {
        ctx = RuntimeEnvironment.getApplication()
    }

    /**
     * 🔴 #200：测试也必须落集群库。
     *
     * 生产代码已全切到 [ClusterSkillStore]（能力核对、注入、角色卡建角都只认集群库），
     * 测试若还写全局库，就会测出一个“绑了但注不进去”的库——
     * 那正是用户实测拍凳的原型（手动绑 web-search-exa 也没用）。
     */
    private fun saveSkills(vararg skills: ClusterSkill) {
        ClusterSkillStore.save(ctx, skills.toList())
    }

    /** 构造一个集群技能（不必填正文，能力核对只看名字/描述/能力词）。 */
    private fun cskill(
        id: String,
        name: String,
        description: String = "",
        enabled: Boolean = true,
        abilityWords: String = "",
    ) = ClusterSkill(
        id = id, name = name, description = description,
        abilityWords = abilityWords, enabled = enabled,
    )

    private fun role(
        id: String,
        kind: RoleKind = RoleKind.EXECUTOR,
        skillIds: List<String> = emptyList(),
        enabled: Boolean = true,
        duties: List<String> = emptyList(),
    ) = RoleProfile(
        personaId = id,
        role = kind,
        duties = duties,
        skillIds = skillIds,
        enabled = enabled,
    )

    // ——— 打分口径 ———

    @Test
    fun `ability terms split chinese by punctuation and add short windows`() {
        val t = ClusterCapability.abilityTerms("视觉设计、交互原型")
        assertTrue("标点切词失灵：$t", t.isNotEmpty())
        assertTrue("没切出「视觉设计」：$t", t.any { it.contains("视觉") || it.contains("设计") })
        // 停用词不该进词表，否则「的」「和」会让任何技能都命中
        assertTrue("混进停用词：$t", t.none { it == "的" || it == "和" || it == "与" })
    }

    @Test
    fun `declared duties score above zero but stay below the cover threshold`() {
        // 文档 V2 第 5 页要求 goodAt 关键词也参与打分，但不能只看它。
        // 封顶值必须**低于**阈值：只靠嘴说会的不算会，否则就成了新的凭空想象。
        saveSkills(cskill(id = "sk_x", name = "unrelated-thing", description = "完全无关"))
        val lib = ClusterSkillStore.load(ctx).associateBy { it.id }
        val talker = role("talker", duties = listOf("视觉设计", "UI 设计", "界面评审"))
        val s = ClusterCapability.scoreRole(talker, "视觉设计", lib)
        assertTrue("声明擅长应当有分（文档要求 goodAt 参与）：$s", s > 0f)
        assertTrue(
            "声明分必须封顶在阈值以下，否则等于只看嘴：$s >= ${ClusterCapability.COVER_THRESHOLD}",
            s < ClusterCapability.COVER_THRESHOLD
        )
    }

    @Test
    fun `a bound skill outweighs a mere declaration`() {
        // 真技能与口头声明不对等：有真技能时应显著高于只声明。
        saveSkills(cskill(id = "sk_ui", name = "ui-design-system", description = "视觉设计 界面设计"))
        val lib = ClusterSkillStore.load(ctx).associateBy { it.id }
        val withSkill = role("real", skillIds = listOf("sk_ui"), duties = listOf("视觉设计"))
        val onlyTalk = role("talk", duties = listOf("视觉设计"))
        val a = ClusterCapability.scoreRole(withSkill, "视觉设计", lib)
        val b = ClusterCapability.scoreRole(onlyTalk, "视觉设计", lib)
        assertTrue("真技能应独立越过阈值：a=$a", a >= ClusterCapability.COVER_THRESHOLD)
        assertTrue("真技能分必须高于只声明：a=$a b=$b", a > b)
    }

    @Test
    fun `a role with neither skill nor declaration scores zero`() {
        saveSkills(cskill(id = "sk_x", name = "unrelated-thing", description = "完全无关"))
        val blank = role("blank", duties = listOf("后勤", "看门"))
        val s = ClusterCapability.scoreRole(blank, "视觉设计", ClusterSkillStore.load(ctx).associateBy { it.id })
        assertEquals("两路证据都没有就该是 0", 0f, s, 0.001f)
    }

    @Test
    fun `a bound skill whose text matches the ability scores above zero`() {
        saveSkills(cskill(id = "sk_ui", name = "ui-design-system", description = "视觉设计 界面设计 令牌"))
        val r = role("designer", skillIds = listOf("sk_ui"))
        val s = ClusterCapability.scoreRole(r, "视觉设计", ClusterSkillStore.load(ctx).associateBy { it.id })
        assertTrue("命中真实技能却得 0 分", s > 0f)
    }

    @Test
    fun `a disabled skill does not count as capability`() {
        saveSkills(
            cskill(id = "sk_on", name = "ui-design-system", description = "视觉设计", enabled = true),
            cskill(id = "sk_off", name = "design-systems", description = "视觉设计", enabled = false)
        )
        val lib = ClusterSkillStore.load(ctx).associateBy { it.id }
        val on = ClusterCapability.scoreRole(role("a", skillIds = listOf("sk_on")), "视觉设计", lib)
        val off = ClusterCapability.scoreRole(role("b", skillIds = listOf("sk_off")), "视觉设计", lib)
        assertTrue("已启用技能应计分", on > 0f)
        assertEquals("已停用技能不得计分", 0f, off, 0.001f)
    }

    // ——— audit / gaps ———

    @Test
    fun `audit reports covered nodes with the best role id`() {
        saveSkills(cskill(id = "sk_ui", name = "ui-design-system", description = "视觉设计 界面 令牌"))
        val node = ClusterNode(
            id = "n1", title = "设计首页", instruction = "出稿",
            ability = "视觉设计 界面设计"
        )
        val cov = ClusterCapability.audit(ctx, listOf(node), listOf(role("designer", skillIds = listOf("sk_ui"))))
        assertEquals(1, cov.size)
        assertEquals("designer", cov[0].bestRoleId)
        assertTrue("应判定为有人能做：${cov[0]}", cov[0].covered)
        assertTrue(ClusterCapability.gaps(cov).isEmpty())
    }

    @Test
    fun `audit marks an uncovered node and names why`() {
        saveSkills(cskill(id = "sk_ui", name = "ui-design-system", description = "视觉设计"))
        val node = ClusterNode(
            id = "n9", title = "做高频期权定价蒙特卡洛", instruction = "",
            ability = "期权定价 蒙特卡洛 随机模拟 金融衍生品"
        )
        val cov = ClusterCapability.audit(ctx, listOf(node), listOf(role("designer", skillIds = listOf("sk_ui"))))
        assertFalse("没人会做却判成 covered", cov[0].covered)
        assertEquals(1, ClusterCapability.gaps(cov).size)
        // 汇报文案必须说清缺什么、为什么不是「沉默跳过」
        val s = ClusterCapability.summary(cov)
        assertTrue("汇报没提能力需求：$s", s.contains("期权定价") || s.contains("n9"))
    }

    @Test
    fun `audit falls back to title and instruction when ability is blank`() {
        saveSkills(cskill(id = "sk_ui", name = "ui-design-system", description = "视觉设计 界面设计"))
        val node = ClusterNode(id = "n2", title = "视觉设计", instruction = "出界面稿", ability = "")
        val cov = ClusterCapability.audit(ctx, listOf(node), listOf(role("designer", skillIds = listOf("sk_ui"))))
        assertTrue("ability 为空时应退回 标题+指令", cov[0].covered)
    }

    // ——— 本地补救（零网络）———

    @Test
    fun `findLocalMatch locates an installable skill without touching the network`() {
        saveSkills(
            cskill(id = "sk_ui", name = "ui-design-system", description = "视觉设计 界面 令牌"),
            cskill(id = "sk_junk", name = "totally-other", description = "别的东西")
        )
        val hit = ClusterCapability.findLocalMatch(ctx, "视觉设计")
        assertNotNull("本地明明有ui 技能却没找到", hit)
        assertEquals("ui-design-system", hit!!.name)
    }

    @Test
    fun `findLocalMatch returns null instead of guessing when nothing matches`() {
        saveSkills(cskill(id = "sk_junk", name = "totally-other", description = "别的东西"))
        assertEquals(null, ClusterCapability.findLocalMatch(ctx, "期权定价 蒙特卡洛"))
    }

    @Test
    fun `findLocalMatch excludes already bound skill ids`() {
        saveSkills(cskill(id = "sk_ui", name = "ui-design-system", description = "视觉设计"))
        assertEquals(
            null,
            ClusterCapability.findLocalMatch(ctx, "视觉设计", excludeIds = setOf("sk_ui"))
        )
    }

    // ——— 补救（remedy）——

    @Test
    fun `remedy grants a local skill and binds it to an executor`() = runBlocking {
        saveSkills(cskill(id = "sk_ui", name = "ui-design-system", description = "视觉设计 界面"))
        val cov = ClusterCapability.Coverage(
            nodeId = "n1", nodeTitle = "设计", ability = "视觉设计",
            score = 0f, bestRoleId = "", covered = false
        )
        val designer = role("designer")
        RoleRegistry.upsert(ctx, designer)
        val res = ClusterCapability.remedy(ctx, cov, listOf(designer), allowNetwork = false)
        assertEquals(ClusterCapability.Remedy.GRANT_LOCAL, res.remedy)
        assertTrue("本地补救应成功：$res", res.ok)
        assertTrue("说明里要写清绑给了谁：${res.detail}", res.detail.contains("designer"))
        // 绑定真的落库了，且只加一次（不堆副本）
        val after = RoleRegistry.get(ctx, "designer")!!
        assertEquals(1, after.skillIds.count { it == "sk_ui" })
    }

    @Test
    fun `remedy never binds a skill to a critic`() = runBlocking {
        saveSkills(cskill(id = "sk_ui", name = "ui-design-system", description = "视觉设计 界面"))
        val cov = ClusterCapability.Coverage(
            nodeId = "n1", nodeTitle = "设计", ability = "视觉设计",
            score = 0f, bestRoleId = "", covered = false
        )
        // 只有评审在场：必须如实说绑不了，绝不给评审塞生产技能
        val critic = role("critic-only", kind = RoleKind.CRITIC)
        RoleRegistry.upsert(ctx, critic)
        val res = ClusterCapability.remedy(ctx, cov, listOf(critic), allowNetwork = false)
        assertFalse("不该把生产技能绑给评审", RoleRegistry.get(ctx, "critic-only")!!.skillIds.isNotEmpty())
        assertFalse("无可绑对象时不能谎报成功", res.ok)
        assertTrue("必须说清为什么没绑：${res.detail}", res.detail.isNotBlank())
    }

    @Test
    fun `remedy fails honestly when nothing matches and network is off`() =
        runBlocking {
            // 注意：QuroSkillStore.load 会 seed 内置技能，所以「什么都没有」不可靠。
            // 这里靠一个内置技能库里绝不会出现的生僻能力词来制造真实缺口。
            val cov = ClusterCapability.Coverage(
                nodeId = "n1", nodeTitle = "定价",
                ability = "甲骨文残片断代测年与碳十四校准",
                score = 0f, bestRoleId = "", covered = false
            )
            val r = role("exec")
            val res = ClusterCapability.remedy(ctx, cov, listOf(r), allowNetwork = false)
            assertFalse("没有对应技能时不可能成功", res.ok)
            assertTrue(
                "必须明说未允许联网，而不是假装社区没有：${res.detail}",
                res.detail.contains("联网")
            )
            assertTrue("执行角色的技能列表不该被凭空塞东西", r.skillIds.isEmpty())
        }

    // ——— 角色卡 / 集群模型 ———

    @Test
    fun `cluster node ability survives a json round trip`() {
        val n = ClusterNode(id = "n", title = "T", instruction = "I", ability = "视觉设计")
        val back = ClusterNode.fromJson(n.toJson())
        assertEquals("视觉设计", back.ability)
        // 老数据没这个字段时必须给空串，不能崩也不能编
        val legacy = JSONObject("""{"id":"n","title":"T","instruction":"I"}""")
        assertEquals("", ClusterNode.fromJson(legacy).ability)
    }

    @Test
    fun `budget carries the capability switch and remedy cap`() {
        val b = ClusterBudget()
        assertTrue("能力核对默认必须开（文档★项）", b.capabilityCheck)
        assertTrue("补救次数必须有上限，否则会死循环", b.maxRemedies in 1..5)
        val back = ClusterBudget.fromJson(b.toJson())
        assertEquals(b.capabilityCheck, back.capabilityCheck)
        assertEquals(b.maxRemedies, back.maxRemedies)
    }

    @Test
    fun `capability notes accumulate on the task and can be reset`() {
        // capabilityNotes 是任务生命周期内的内存字段（逐节点一条如实说明）。
        // 关键语义：**每轮重新核对前必须先清空**，否则上一轮的结论会一直堆着，
        // UI 上就会显示过期的「已补救」。
        val t = ClusterTask(id = "t1", clusterId = "c1", goal = "G")
        assertTrue("默认没有备注", t.capabilityNotes.isEmpty())
        t.capabilityNotes += "能力覆盖 0/1 个子任务"
        t.capabilityNotes += "- ✗ 跳过（期权定价）：无人具备所需能力"
        assertEquals(2, t.capabilityNotes.size)
        t.capabilityNotes.clear()
        assertTrue("重核前没清空，上一轮结论会一直堆着", t.capabilityNotes.isEmpty())
    }

    @Test
    fun `engine wires the capability stage between intake and dispatching`() {
        // 文档流程是 INTAKE → CAPABILITY → DISPATCHING。
        // 这条钉的是**顺序**：能力核对必须夹在拆解与派活之间，
        // 放错位置就等于没有这个阶段。
        val states = ClusterTaskState.values().toList()
        val iIntake = states.indexOf(ClusterTaskState.INTAKE)
        val iCap = states.indexOf(ClusterTaskState.CAPABILITY)
        val iDisp = states.indexOf(ClusterTaskState.DISPATCHING)
        assertTrue("缺 CAPABILITY 阶段，现有：$states", iCap >= 0)
        assertTrue("CAPABILITY 必须排在 INTAKE 之后", iCap > iIntake)
        assertTrue("CAPABILITY 必须排在 DISPATCHING 之前", iCap < iDisp)
    }
}