package com.ai.assistance.quro.core.cluster

import com.ai.assistance.quro.core.QuroPersona
import com.ai.assistance.quro.core.QuroPersonaRepository
import com.ai.assistance.quro.core.QuroToolSpec
import com.ai.assistance.quro.core.cluster.ClusterSkillStore.ClusterSkill
import com.ai.assistance.quro.core.tools.ToolCapabilityDirectory
import org.json.JSONArray
import org.json.JSONObject
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
 * #190「角色 = 人格 × 技能聚合 × 工具权限」的验证。
 *
 * 这次修的根因是：**宿主早就有一套完整技能体系**，集群却从没用过。
 * 🔴 #200：用户实测后要求「集群另设技能系统」，所以这里一刀切到 [ClusterSkillStore]，
 * 不再读全局 QuroSkillStore（也不再写入它）。
 * 角色的 `skills` 只是提示词里的几行文字标签，于是模型只能凭空发挥。
 * 所以这里必须钉死四件事：
 *  1. 技能正文真的进了角色的 system prompt（不是标签）；
 *  2. 角色知道自己能调哪些工具（能力自我感知），没有工具时明确说「没有」；
 *  3. 工具按分工裁剪，且评审拿不到会改变世界的工具；
 *  4. 技能市场工具能把技能绑到角色上。
 *
 * 不联网、不耗 token。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ClusterSkillRuntimeTest {

    private val ctx get() = RuntimeEnvironment.getApplication()

    // 🔴 本项目 Robolectric 下 context.assets 读不到 app 自带 assets，
    // 生产的 ClusterSkillStore.seed 因此播种出 0 个。详见 ClusterTestSkills。
    @Before
    fun setUp() {
        ClusterTestSkills.seedClusterSkills(ctx)
    }

    private fun role(
        kind: RoleKind = RoleKind.EXECUTOR,
        skillIds: List<String> = emptyList(),
        whitelist: Set<String> = emptySet(),
    ) = RoleProfile(
        personaId = "p1",
        modelProfileId = "cloud:current",
        role = kind,
        duties = listOf("写代码"),
        skillIds = skillIds,
        context = RoleContextPolicy(toolWhitelist = whitelist),
    )

    private fun spec(name: String, desc: String = "工具 $name") =
        QuroToolSpec(name, desc, """{"type":"object","properties":{}}""")

    // ——————————————————————————— 1. 技能正文真的注入 ———————————————————————————

    @Test
    fun `skill prompt body reaches the role system prompt`() {
        val skill = ClusterSkill(
            id = "cluster_test", name = "unit-test-skill",
            description = "测试技能",
            prompt = "第一行规则：所有金额一律用两位小数表示。第二行规则：写完必须自测。",
            // 集群技能没有 callable 字段：一律是注入系统提示词的规程正文。
            enabled = true,
        )
        val resolved = ClusterSkillRuntime.buildSkills(listOf(skill))

        assertTrue("应解析出 1 个技能", resolved.skills.size == 1)
        assertTrue(
            "技能正文必须被注入（这是「不凭空想象」的关键）",
            resolved.bodies.isNotEmpty() && resolved.bodies.first().contains("两位小数")
        )
        assertTrue(
            "正文应带技能名，便于模型建立索引",
            resolved.bodies.first().contains("unit-test-skill")
        )
    }

    @Test
    fun `disabled skills are not injected`() {
        val off = ClusterSkill(
            id = "sk_off", name = "off-skill", description = "关闭的",
            prompt = "不该被注入的正文", enabled = false,
        )
        val resolved = ClusterSkillRuntime.buildSkills(listOf(off))
        assertTrue("禁用的技能不应注入", resolved.bodies.isEmpty())
    }

    @Test
    fun `skill body is budgeted and never silently half a skill`() {
        // 三个各 5000 字��技能，总预算 6000 → 只注入第一个完整的
        val big = (1..3).map {
            ClusterSkill(
                id = "sk_$it", name = "big-$it", description = "大技能$it",
                prompt = "x".repeat(5000), enabled = true,
            )
        }
        val resolved = ClusterSkillRuntime.buildSkills(big)
        val body = resolved.bodies.firstOrNull().orEmpty()
        assertTrue("总注入量必须受预算约束（<= ${ClusterSkillRuntime.MAX_SKILL_TEXT_CHARS}）",
            body.length <= ClusterSkillRuntime.MAX_SKILL_TEXT_CHARS + 200)
    }

    // ——————————————————————————— 2. 能力自我感知 ———————————————————————————

    @Test
    fun `role knows which tools it actually has`() {
        val tools = listOf(spec("write_file", "写文件"), spec("screen_tap", "点击屏幕"))
        val text = ClusterSkillRuntime.capabilityAwareness(
            ctx, role(), tools, emptyList()
        )
        assertTrue("必须列出实际可用工具 write_file", text.contains("write_file"))
        assertTrue("必须列出实际可用工具 screen_tap", text.contains("screen_tap"))
        assertTrue("必须说明这是真实能力清单", text.contains("真实可用"))
    }

    @Test
    fun `role with no tool is told it has none instead of guessing`() {
        val text = ClusterSkillRuntime.capabilityAwareness(ctx, role(), emptyList(), emptyList())
        assertTrue("没工具时必须明说没有", text.contains("没有可调用的工具"))
        assertTrue(
            "没工具时必须要求它如实说缺什么，不许假装做过",
            text.contains("不要假装") || text.contains("如实说明")
        )
    }

    // ——————————————————————————— 3. 工具按分工裁剪 ———————————————————————————

    @Test
    fun `explicit whitelist wins over auto inference`() {
        val all = listOf(spec("write_file"), spec("screen_tap"), spec("run_shell"))
        val picked = ClusterSkillRuntime.toolsFor(
            ctx, role(whitelist = setOf("screen_tap")), all
        )
        assertEquals("显式白名单应唯一生效", 1, picked.size)
        assertEquals("screen_tap", picked.first().name)
    }

    @Test
    fun `critic never gets mutating tools`() {
        val all = listOf(
            spec("write_file", "写文件"),
            spec("run_shell", "跑命令"),
            spec("screen_tap", "点屏幕"),
            spec("memory_search", "检索记忆"),
        )
        val picked = ClusterSkillRuntime.toolsFor(ctx, role(kind = RoleKind.CRITIC), all)
        val names = picked.map { it.name }.toSet()
        assertFalse("评审不能拿到写文件", names.contains("write_file"))
        assertFalse("评审不能拿到跑命令", names.contains("run_shell"))
        assertFalse("评审不能拿到操作设备", names.contains("screen_tap"))
    }

    @Test
    fun `tool count is capped per role when tool set explodes`() {
        // #215：工具总数 < TOOL_FULL_OPEN_LIMIT 时默认全开（对话框所有功能给集群兼容）。
        // 只有工具多到会挤爆上下文（> TOOL_FULL_OPEN_LIMIT）才走裁剪分支，此时才封顶。
        val small = (1..ClusterSkillRuntime.TOOL_FULL_OPEN_LIMIT).map { spec("tool_$it") }
        val allOpen = ClusterSkillRuntime.toolsFor(ctx, role(), small)
        assertEquals(
            "工具未超上限时应全开（对话框所有功能给集群兼容），实际=${allOpen.size}",
            small.size, allOpen.size
        )

        val big = (1..(ClusterSkillRuntime.TOOL_FULL_OPEN_LIMIT + 100)).map { spec("tool_$it") }
        val capped = ClusterSkillRuntime.toolsFor(ctx, role(), big)
        assertTrue(
            "工具多到失控时才按需裁剪并封顶（实际 ${capped.size}），否则上下文会被撑爆",
            capped.size <= ClusterSkillRuntime.MAX_TOOLS_PER_ROLE
        )
    }

    @Test
    fun `executor can actually get execution tools`() {
        val all = listOf(
            spec("write_file", "写文件到磁盘"),
            spec("run_shell", "执行终端命令"),
            spec("http_get", "发起 HTTP 请求"),
            spec("screen_tap", "点击屏幕"),
        )
        val picked = ClusterSkillRuntime.toolsFor(ctx, role(kind = RoleKind.EXECUTOR), all)
        val names = picked.map { it.name }.toSet()
        assertTrue("执行者必须能拿到写文件（关键词命中），实际=$names", names.contains("write_file"))
        assertTrue("执行者必须能拿到跑命令，实际=$names", names.contains("run_shell"))
    }

    // ——————————————————————————— 4. 技能市场 ———————————————————————————

    @Test
    fun `skill grant binds real skills to a role`() {
        RoleRegistry.ensureHost(ctx)
        QuroPersonaRepository(ctx).upsert(
            QuroPersona(
                id = "ex2", name = "设计师",
                description = "d", roleSetting = "你是设计师", chatSetting = "",
                tags = listOf("内置")
            )
        )
        RoleRegistry.enroll(
            ctx, "ex2", modelProfileId = "cloud:current",
            role = RoleKind.EXECUTOR,
            duties = listOf("设计", "视觉")
        )

        val skill = ClusterSkill(
            id = "sk_design_1", name = "design-pro", description = "专业设计能力",
            prompt = "设计规则正文", enabled = true,
        )
        ClusterSkillStore.upsert(ctx, skill)

        val out = ClusterSkillGrantTool().run(
            ctx, """{"personaId":"ex2","skillIds":["sk_design_1"]}"""
        )
        assertTrue("绑定应成功，实际返回：$out", out.contains("\"ok\":true"))

        val role = RoleRegistry.get(ctx, "ex2")!!
        assertTrue("角色应真的持有该技能 id", role.skillIds.contains("sk_design_1"))

        // 关键：绑定后技能正文能解析出来（否则绑定只是存了个字符串）
        val resolved = ClusterSkillRuntime.resolveSkills(ctx, role)
        assertTrue("绑定后应能解析出该技能", resolved.skills.any { it.id == "sk_design_1" })
        assertTrue("绑定后技能正文应可注入", resolved.bodies.isNotEmpty())
    }

    @Test
    fun `skill grant refuses host and unknown ids`() {
        RoleRegistry.ensureHost(ctx)
        val hostOut = ClusterSkillGrantTool().run(
            ctx, """{"personaId":"${RoleRegistry.HOST_PERSONA_ID}","skillIds":["x"]}"""
        )
        assertTrue("主持不该能绑技能", hostOut.contains("主持不可绑定技能"))

        QuroPersonaRepository(ctx).upsert(
            QuroPersona(id = "ex3", name = "执行", description = "d", roleSetting = "s", chatSetting = "", tags = listOf("内置"))
        )
        RoleRegistry.enroll(ctx, "ex3", modelProfileId = "cloud:current", role = RoleKind.EXECUTOR)
        val badOut = ClusterSkillGrantTool().run(ctx, """{"personaId":"ex3","skillIds":["not_exist"]}""")
        assertTrue("不存在的技能 id 必须报错而不是静默成功", badOut.contains("都不存在"))
    }

    @Test
    fun `skill market lists cluster skills without a fake activate tool`() {
        ClusterSkillStore.upsert(
            ctx, ClusterSkill(id = "cluster_m1", name = "market-skill", description = "市场技能", prompt = "正文")
        )
        val out = JSONObject(ClusterSkillMarketTool().run(ctx, """{"query":"market-skill"}"""))
        assertTrue("市场应能查到该技能", out.getInt("matched") >= 1)
        val arr = out.getJSONArray("skills")
        val first = arr.getJSONObject(0)
        // 🔴 #200：集群技能是提示词规程，没有 function-calling 形态。
        // 市场还给 activateTool，就是向模型提供一个指向不存在工具的名字。
        assertFalse("集群技能没有可激活工具，不能再返回 activateTool", first.has("activateTool"))
        assertEquals("prompt-only", first.optString("form"))
        assertTrue("应返回每个技能的 id（绑定时要用的）", first.optString("id").isNotBlank())
    }

    @Test
    fun `skill market search hits chinese ability words not only english names`() {
        // 🔴 #200：用户实测“中文关键词搜不到、换英文才行”。
        // 市场搜索必须能用集群技能的中英双语能力词。
        val out = JSONObject(
            ClusterSkillMarketTool().run(ctx, """{"query":"写网页"}""")
        )
        val arr = out.optJSONArray("skills") ?: JSONArray()
        assertTrue(
            "中文能力词搜索应有命中，实际 matched=${out.optInt("matched")}",
            arr.length() > 0
        )
    }

    @Test
    fun `skill import creates a real skill from markdown`() {
        val md = """
            ---
            name: imported-skill
            description: 从 md 导入的技能
            ---
            这是导入的技能正文，规定具体的做事方式。
        """.trimIndent()
        val out = JSONObject(
            ClusterSkillImportTool().run(ctx, JSONObject().put("markdown", md).toString())
        )
        assertTrue("导入应成功，实际=${out.optString("error")}", out.optBoolean("ok"))
        val id = out.getString("id")
        // 🔴 #200：导入必须落集群库。落全局库就是无效工作 ——
        // 运行时注入只认集群库，导入全局库的技能绑了也注不进去。
        assertTrue("导入的技能 id 必须在集群命名空间，实际=$id", id.startsWith(ClusterSkillStore.ID_PREFIX))
        val back = ClusterSkillStore.get(ctx, id)
        assertTrue("导入的技能应真的落到集群技能库", back != null)
        assertTrue("导入的技能应带正文", back!!.prompt.contains("具体的做事方式"))
        assertTrue(
            "导入的技能绝不能落进全局库（隔离）",
            com.ai.assistance.quro.core.skill.QuroSkillStore.load(ctx).none { it.id == id }
        )
    }

    @Test
    fun `skill import refuses markdown without frontmatter name`() {
        val out = JSONObject(
            ClusterSkillImportTool().run(ctx, """{"markdown":"就是一段没有 front-matter 的正文"}""")
        )
        assertFalse("没有 name 的 md 必须报错", out.optBoolean("ok"))
        assertTrue("错误信息要指明格式要求", out.optString("error").contains("front-matter"))
    }

    // ——————————————————————————— 5. 分工语义 ———————————————————————————

    @Test
    fun `role kinds cover plan execute verify and no longer lack an executor`() {
        // 用户原话：「只有专家和评审，谁执行」—— 必须有执行者
        val kinds = RoleKind.selectable.map { it.name }.toSet()
        assertTrue("必须有 EXECUTOR（谁执行）", kinds.contains("EXECUTOR"))
        assertTrue("必须有 PLANNER（谁规划）", kinds.contains("PLANNER"))
        assertTrue("必须有 CRITIC（谁验收）", kinds.contains("CRITIC"))
        assertTrue("必须有 EXPERT（领域判断）", kinds.contains("EXPERT"))
        assertTrue("执行者应可执行", RoleKind.EXECUTOR.canExecute)
        assertTrue("评审不应可执行生产", !RoleKind.CRITIC.canExecute)
        assertTrue("评审身份标记", RoleKind.CRITIC.isVerifier)
    }

    @Test
    fun `role profile round-trips skill ids through json`() {
        val original = role(skillIds = listOf("a", "b"), kind = RoleKind.EXECUTOR)
        val back = RoleProfile.fromJson(JSONObject(original.toJson().toString()))
        assertEquals("技能绑定必须能持久化，否则重启后全丢", listOf("a", "b"), back.skillIds)
        assertEquals(RoleKind.EXECUTOR, back.role)
    }
}
