package com.ai.assistance.quro.core.cluster

import android.content.Context
import com.ai.assistance.quro.core.QuroToolSpec
import com.ai.assistance.quro.core.skill.QuroSkillStore
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * #196~#199：集群**独立技能库**与**假闭环拦截**的回归测试。
 *
 * 这些全部来自真机实测踩出来的坑（用户实跑三轮后给出的结论）：
 *
 * 1. **假闭环**：5 个节点全 SKIPPED，集群却报 GOAL_REACHED，tokens 只花 384。
 *    根因是 [ClusterTask.finished] 把 SKIPPED 当完成 + [ClusterEngine.converging]
 *    不看产出就问主持要总结。→ 必须有 NO_PROGRESS 这一档。
 * 2. **一技能全角色可见**：集群复用全局 [QuroSkillStore]，81 个技能全开放，
 *    且一个技能绑给 N 个角色就有 N 份正文各自注入。→ 必须独立库。
 * 3. **中英断层导致误杀**：主持拆出中文能力标签（「HTML/CSS编码」）
 *    去 contains 英文技能名（`frontend-design`）恒为 0 → 节点全跳过。
 *    → 技能必须有中英双语 `abilityWords`。
 *
 * 4. **写入侧漏隔离**（#200）：#196 只切了读侧，市场/绑定/导入/开源安装
 *    四个入口仍写全局库 → 绑完的 id 注入时找不到，等于白绑。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ClusterIsolatedSkillTest {

    private lateinit var ctx: Context

    @Before
    fun setUp() {
        ctx = RuntimeEnvironment.getApplication()
        ClusterTestSkills.seedClusterSkills(ctx)
    }

    // ———————————————— 1. 假闭环拦截 ————————————————

    @Test
    fun `全部节点被跳过时不得判为达成`() {
        val t = task {
            node("a", NodeState.SKIPPED, "无人具备所需能力")
            node("b", NodeState.SKIPPED, "无人具备所需能力")
            node("c", NodeState.SKIPPED, "无人具备所需能力")
        }
        assertTrue("全跳过时 finished 应为 true（都到终态了）", t.finished())
        assertEquals("真实产出必须是 0", 0, t.productive())
        assertEquals("跳过数应等于总数", 3, t.skipped())
        assertTrue("零产出必须被识别为 noProgress", t.noProgress())
    }

    @Test
    fun `进度分子不再把跳过算成完成`() {
        val t = task {
            node("a", NodeState.DONE)
            node("b", NodeState.DONE)
            node("c", NodeState.SKIPPED)
            node("d", NodeState.SKIPPED)
        }
        // 旧版这里会是 (4, 4) → UI 显示「4/4 全完成」，实际只做了 2 个
        assertEquals("进度分子只算真产出", 2, t.progress().first)
        assertEquals("分母是总节点数", 4, t.progress().second)
        assertEquals("跳过的 2 个要能单独数出来", 2, t.skipped())
        assertFalse("有真产出就不算 noProgress", t.noProgress())
    }

    @Test
    fun `至少一个真产出即不算假闭环`() {
        val t = task {
            node("a", NodeState.DONE)
            node("b", NodeState.FAILED, "模型不可用")
            node("c", NodeState.SKIPPED)
        }
        assertTrue(t.finished())
        assertFalse("有 1 个 DONE 就不能报 NO_PROGRESS", t.noProgress())
    }

    @Test
    fun `全部失败也算零产出`() {
        val t = task {
            node("a", NodeState.FAILED, "超时")
            node("b", NodeState.FAILED, "超时")
        }
        assertTrue("全失败必须被识别为假闭环", t.noProgress())
    }

    @Test
    fun `非终态不算结束`() {
        val t = task {
            node("a", NodeState.DONE)
            node("b", NodeState.EXECUTING)
        }
        assertFalse("还有在跑的就不算 finished", t.finished())
        assertFalse("有在跑的就不能判 NO_PROGRESS", t.noProgress())
    }

    @Test
    fun `isTerminal 显式列出终态而不是取反`() {
        assertTrue(NodeState.DONE.isTerminal())
        assertTrue(NodeState.FAILED.isTerminal())
        assertTrue(NodeState.SKIPPED.isTerminal())
        assertFalse(NodeState.PENDING.isTerminal())
        assertFalse(NodeState.READY.isTerminal())
        assertFalse(NodeState.PROPOSING.isTerminal())
        assertFalse(NodeState.EXECUTING.isTerminal())
    }

    @Test
    fun `CloseReason 必须有 NO_PROGRESS 这一档`() {
        // 没有它，引擎就只能在「谎报达成」与「什么都不说」之间二选一
        val names = CloseReason.values().map { it.name }
        assertTrue("缺少 NO_PROGRESS（假闭环就无法被表达）：$names", names.contains("NO_PROGRESS"))
    }

    // ———————————————— 2. 集群技能库隔离 ————————————————

    @Test
    fun `集群技能库与全局库id 命名空间不重叠`() {
        val name = "web-research"
        val clusterId = ClusterSkillStore.stableId(name)
        assertTrue("集群 id 必须带 cluster_ 前缀：$clusterId", clusterId.startsWith(ClusterSkillStore.ID_PREFIX))
        assertFalse(
            "集群 id 不该用全局库的 zorv_ 前缀",
            clusterId.startsWith("zorv_")
        )
    }

    @Test
    fun `集群技能库 id 稳定可复现`() {
        assertEquals(ClusterSkillStore.stableId("ui-design"), ClusterSkillStore.stableId("ui-design"))
        assertFalse(
            "不同技能名必须算出不同 id",
            ClusterSkillStore.stableId("ui-design") == ClusterSkillStore.stableId("review")
        )
    }

    @Test
    fun `集群技能库存储文件独立于全局技能库`() {
        val f = File(ctx.filesDir, "cluster_skills.json")
        // 播种后再查文件真实存在
        ClusterSkillStore.load(ctx)
        assertTrue("集群技能库应有独立存储文件：${f.absolutePath}", f.exists())
        assertFalse("不该落到全局技能的文件名", f.name.contains("skill_prefs"))
    }

    @Test
    fun `播种出14 个集群技能且不含 callable 语义`() {
        val skills = ClusterSkillStore.load(ctx)
        assertTrue("应播种出 14 个集群技能，实际 ${skills.size}", skills.size == 14)
        // 集群技能一律是提示词规程，绝不注册成 function-calling 工具。
        // 这不是遗漏 —— ClusterSkill 数据类里根本没有 callable 字段。
        val s = skills.first()
        assertNotNull(s.prompt)
        assertTrue("正文不该为空", s.prompt.isNotBlank())
    }

    @Test
    fun `每个集群技能都有可匹配的能力词`() {
        val skills = ClusterSkillStore.load(ctx)
        val noWords = skills.filter { ClusterSkillStore.effectiveAbilityWords(it).isEmpty() }
        assertTrue(
            "这些技能没有能力词，会被判成无人具备能力：${noWords.map { it.name }}",
            noWords.isEmpty()
        )
    }

    // ———————————————— 3. 中英断层（假闭环的直接原因） ————————————————

    @Test
    fun `中文能力标签能匹配到英文命名的技能`() {
        val lib = ClusterSkillStore.load(ctx).associateBy { it.id }
        // 复现真机报错的那句能力标签
        val cases = mapOf(
            "HTML/CSS编码" to "html-dev",
            "联网搜索" to "web-research",
            "文案写作" to "copywrite",
            "视觉设计" to "ui-design",
            "代码评审" to "code-review",
            "任务拆解" to "planning",
        )
        for ((ability, expected) in cases) {
            val skill = ClusterSkillStore.load(ctx).firstOrNull { it.name == expected }
            assertNotNull("集群库里应有技能 $expected", skill)
            val role = RoleProfile(
                personaId = "r_$expected",
                role = RoleKind.EXECUTOR,
                skillIds = listOf(skill!!.id),
                duties = listOf("负责$expected 相关产出"),
            )
            val score = ClusterCapability.scoreRole(role, ability, lib)
            assertTrue(
                "中文能力「$ability」应能匹配英文技能「$expected」，实际分 $score",
                score > 0f
            )
        }
    }

    @Test
    fun `中文能力标签经由 audit 判定为已覆盖`() {
        val lib = ClusterSkillStore.load(ctx).associateBy { it.id }
        val htmlSkill = ClusterSkillStore.load(ctx).first { it.name == "html-dev" }
        val role = RoleProfile(
            personaId = "r_web",
            role = RoleKind.EXECUTOR,
            skillIds = listOf(htmlSkill.id),
            duties = listOf("实现界面与交互"),
        )
        val cov = ClusterCapability.audit(
            ctx,
            listOf(ClusterNode(id = "n1", title = "写页面", ability = "HTML/CSS编码")),
            listOf(role),
        ).single()
        assertTrue(
            "「HTML/CSS编码」应判为已覆盖，实际 score=${cov.score} declared=${cov.declared}",
            cov.covered
        )
    }

    @Test
    fun `英文能力标签也能匹配`() {
        val lib = ClusterSkillStore.load(ctx).associateBy { it.id }
        val skill = ClusterSkillStore.load(ctx).first { it.name == "code-review" }
        val role = RoleProfile(personaId = "r1", role = RoleKind.CRITIC, skillIds = listOf(skill.id))
        val score = ClusterCapability.scoreRole(role, "code review", lib)
        assertTrue("英文能力标签应匹配，实际 $score", score > 0f)
    }

    @Test
    fun `findLocalMatch 走集群库而不是全局库`() {
        val hit = ClusterCapability.findLocalMatch(ctx, "联网搜索 资讯")
        assertNotNull("集群库里应能匹配到联网检索技能", hit)
        assertTrue(
            "匹配到的应是集群技能（cluster_ 前缀）：${hit!!.id}",
            hit.id.startsWith(ClusterSkillStore.ID_PREFIX)
        )
    }

    @Test
    fun `角色声明用的是 taboos 也能算证据`() {
        val terms = ClusterCapability.abilityTerms("评审 验收")
        // 这条尚无任何职责与技能，能力证据全靠禁忌。
        // 禁忌写的是为什么：一个角色若无人能做评审，它就应该被认定为有手艺。
        val role = RoleProfile(
            personaId = "r1",
            role = RoleKind.CRITIC,
            duties = emptyList(),
            taboos = listOf("不接受没有验收标准的产物，评审不能只出主观感变"),
        )
        assertTrue(
            "禁忌也是能力证据，不该被漏掉",
            ClusterCapability.declaredScore(role, terms) > 0f
        )
    }

    @Test
    fun `不绑技能的角色不会被真技能分蒙混过关`() {
        val lib = ClusterSkillStore.load(ctx).associateBy { it.id }
        val role = RoleProfile(personaId = "empty", role = RoleKind.EXECUTOR, skillIds = emptyList())
        val score = ClusterCapability.scoreRole(role, "HTML/CSS编码", lib)
        assertEquals("没绑技能包只能拿声明分（封顶 0.30），不能越过阈值", 0f, score, 0.001f)
    }

    // ———————————————— 4. 上下文隔离 ————————————————

    @Test
    fun `单角色技能数被上限截断`() {
        val all = ClusterSkillStore.load(ctx)
        val many = all + all // 故意超量
        val r = ClusterSkillRuntime.buildSkills(many, RoleKind.EXECUTOR)
        assertTrue(
            "应截断到 MAX_SKILLS_PER_ROLE=${ClusterSkillRuntime.MAX_SKILLS_PER_ROLE}，实际 ${r.skills.size}",
            r.skills.size <= ClusterSkillRuntime.MAX_SKILLS_PER_ROLE
        )
    }

    @Test
    fun `执行者拿全文主持只拿摘要`() {
        val web = ClusterSkillStore.load(ctx).first { it.name == "web-research" }
        val exec = ClusterSkillRuntime.buildSkills(listOf(web), RoleKind.EXECUTOR)
        val host = ClusterSkillRuntime.buildSkills(listOf(web), RoleKind.HOST)
        val execBody = exec.bodies.joinToString("")
        val hostBody = host.bodies.joinToString("")
        assertTrue("执行者应拿到较完整的正文", execBody.length > hostBody.length)
        assertTrue(
            "主持注入的必须明显更短（上下文隔离）：exec=${execBody.length} host=${hostBody.length}",
            hostBody.length * 2< execBody.length
        )
    }

    @Test
    fun `被截断的技能正文必须写明被截断`() {
        // 主动造一个**超长**技能：内置包都未超过 MAX_CHARS_PER_SKILL，
        // 用它做用例就会因为"没截断"而假通过。
        val huge = ClusterSkillStore.ClusterSkill(
            id = "cluster_huge",
            name = "huge-probe",
            description = "超长正文探针",
            prompt = "x".repeat(ClusterSkillRuntime.MAX_CHARS_PER_SKILL * 3),
        )
        val r = ClusterSkillRuntime.buildSkills(listOf(huge), RoleKind.EXECUTOR)
        val body = r.bodies.joinToString("")
        assertTrue(
            "截断时必须告知，不能让角色以为那就是全部规矩，实际 body=${body.length}",
            body.contains("过长") || body.contains("摘要") || body.contains("已截断")
        )
    }

    @Test
    fun `注入正文总量不超预算`() {
        val all = ClusterSkillStore.load(ctx)
        val r = ClusterSkillRuntime.buildSkills(all, RoleKind.EXECUTOR)
        val total = r.bodies.joinToString("").length
        assertTrue(
            "注入总量 $total 应≤ ${ClusterSkillRuntime.MAX_SKILL_TEXT_CHARS}（加了截断说明会略超）",
            total <= ClusterSkillRuntime.MAX_SKILL_TEXT_CHARS + ClusterSkillRuntime.MAX_CHARS_PER_SKILL
        )
    }

    @Test
    fun `能力感知段不再宣称技能可激活`() {
        val web = ClusterSkillStore.load(ctx).first { it.name == "web-research" }
        val text = ClusterSkillRuntime.capabilityAwareness(ctx, role("r1"), emptyList(), listOf(web))
        assertTrue("应列出该角色的技能", text.contains("web-research"))
        assertFalse(
            "集群技能不是 function-calling 工具，不许说「可激活」",
            text.contains("可激活")
        )
        assertFalse("不许诱导调用 skill__xxx（集群没这个工具）", text.contains("skill__"))
    }

    // ———————————————— 5. 别名映射（否则 68 处引用全missing） ————————————————

    @Test
    fun `全局技能名有集群别名映射`() {
        // 这些是内置角色卡实际引用的名字，若无别名则建卡时全部落missing
        val referenced = listOf(
            "frontend-design", "frontend-dev", "frontend-spec", "ui-design-system",
            "zorv-ui-craft", "zorv-visual-art", "zorv-mobile-patterns",
            "humanizer-zh", "official-document-skill", "web-search-exa",
            "code-reviewer", "news-summary", "web-performance-audit",
        )
        val noAlias = referenced.filter { resolveAlias(it) == null }
        assertTrue("这些内置引用没有集群别名（建卡会丢手艺）：$noAlias", noAlias.isEmpty())
    }

    @Test
    fun `别名指向的技能确实存在于集群库`() {
        val names = ClusterSkillStore.load(ctx).map { it.name }.toSet()
        val pairs = mapOf(
            "frontend-design" to "frontend-design-playbook",
            "zorv-ui-craft" to "ui-design",
            "zorv-mobile-patterns" to "mobile-control",
            "humanizer-zh" to "copywrite",
            "official-document-skill" to "md-doc",
            "web-search-exa" to "web-research",
            "code-reviewer" to "code-review",
        )
        for ((legacy, expected) in pairs) {
            assertEquals("别名「$legacy」应指向 $expected", expected, resolveAlias(legacy))
            assertTrue("集群库应有 $expected（实际有：$names）", expected in names)
        }
    }

    @Test
    fun `未知技能名没有别名`() {
        assertNull("不存在的技能不该被映射", resolveAlias("totally-unknown-skill-xyz"))
    }

    @Test
    fun `集群库不再引用全局技能库做能力核对`() {
        // 静态检查：ClusterCapability 的 audit/findLocalMatch 必须读集群库。
        // 若哪天有人改回 QuroSkillStore，这里会红。
        val f = File(repoRoot(), "app/src/main/java/com/ai/assistance/quro/core/cluster/ClusterCapability.kt")
        assertTrue("找不到 ClusterCapability.kt", f.exists())
        val src = f.readText()
        assertTrue(
            "audit 应读 ClusterSkillStore",
            src.contains("ClusterSkillStore.load(context).associateBy")
        )
        assertFalse(
            "能力核对不该再读全局技能库（会与集群库得出两套结论）",
            src.contains("QuroSkillStore.load(context).associateBy")
        )
    }

    // ———————————————— #200：写入侧也必须隔离 ————————————————
    //
    // 上面那条只钉住了「读」。但 #196 漏了写入侧：市场 / 绑定 / 导入 / 开源安装
    // 四个入口仍写全局库，而运行时注入读集群库 —— 绑完照旧注不进去。
    // 这正是用户实测「手动把 web-search-exa 绑给专家角色也没用」的根因。
    // 下面五条把写入侧钉死。

    @Test
    fun `技能写入侧四处入口都不再写全局库`() {
        // 不得出现的是**写**全局库的调用：addOrUpdate / save / parseSkillMd / seed。
        // 读是允许的（且必要）：内置角色卡有 68 处 SkillRef 写的是全局技能名，
        // 全砍掉就是所有角色一门手艺都没有。读了之后必须镜像成 cluster_ id（见 mirrorIntoCluster）。
        val base = "app/src/main/java/com/ai/assistance/quro/core/cluster/"
        val files = listOf(
            "ClusterSkillMarketTools.kt",  // 市场 / 绑定 / 导入
            "ClusterOpenSkillHub.kt",      // 开源安装
            "ClusterOpenSkillTools.kt",    // 安装工具
            "ClusterRoleCard.kt",          // 角色卡建角
        )
        val writers = listOf(
            "QuroSkillStore.addOrUpdate",
            "QuroSkillStore.save",
            "QuroSkillStore.parseSkillMd",
            "seedBuiltinZorvSkills",
        )
        files.forEach { name ->
            val f = File(repoRoot(), base + name)
            assertTrue("找不到 $name", f.exists())
            val hits = f.readLines().filter { l ->
                writers.any { w -> l.contains(w) } && !l.trimStart().startsWith("*") &&
                    !l.trimStart().startsWith("//")
            }
            assertTrue(
                "$name 仍在写全局技能库：\n" + hits.joinToString("\n"),
                hits.isEmpty()
            )
        }
    }

    @Test
    fun `集群技能没有可激活工具所以市场不得再返回 activateTool`() {
        val out = JSONObject(
            ClusterSkillMarketTool().run(ctx, """{"limit":5}""")
        )
        val arr = out.optJSONArray("skills") ?: JSONArray()
        assertTrue("市场应有内置集群技能，实际 0", arr.length() > 0)
        for (i in 0 until arr.length()) {
            val sk = arr.getJSONObject(i)
            assertFalse(
                "技能「${sk.optString("name")}」不该有 activateTool：集群技能是规程正文，没有工具形态",
                sk.has("activateTool")
            )
            assertEquals("prompt-only", sk.optString("form"))
        }
    }

    @Test
    fun `导入的技能落集群库且不出现在全局库`() {
        val md = """
            ---
            name: isolated-import-probe
            description: 验证导入隔离
            ---
            这是正文第一条规则：金额一律两位小数。
        """.trimIndent()
        val out = JSONObject(
            ClusterSkillImportTool().run(ctx, JSONObject().put("markdown", md).toString())
        )
        assertTrue("导入应成功：${out.optString("error")}", out.optBoolean("ok"))
        val id = out.getString("id")
        assertTrue("id 必须在集群命名空间：$id", id.startsWith(ClusterSkillStore.ID_PREFIX))
        assertTrue("集群库应能查到", ClusterSkillStore.get(ctx, id) != null)
        assertTrue(
            "全局库绝不能出现集群技能（隔离）",
            QuroSkillStore.load(ctx).none { it.id == id }
        )
    }

    @Test
    fun `集群角色拿不到全局技能激活工具`() {
        val all = listOf(
            QuroToolSpec("write_file", "写文件", """{"type":"object","properties":{}}"""),
            QuroToolSpec("skill__web-search-exa", "激活全局技能", """{"type":"object","properties":{}}"""),
            QuroToolSpec("cluster_skill_market", "集群技能市场", """{"type":"object","properties":{}}"""),
        )
        val picked = ClusterSkillRuntime.toolsFor(
            ctx, role("r1").copy(context = RoleContextPolicy(toolWhitelist = setOf(
                "write_file", "skill__web-search-exa", "cluster_skill_market"
            ))), all
        )
        val names = picked.map { it.name }.toSet()
        assertFalse(
            "skill__ 工具会回灌全局库正文，集群角色拿到就等于绕过隔离：$names",
            names.any { it.startsWith("skill__") }
        )
        assertTrue("其余工具应照常下发：$names", names.contains("write_file"))
    }

    @Test
    fun `角色卡引用能全部落到集群包上不需要全局库兜底`() {
        // 🔴 #202：这条替换掉旧的「兜底镜像」用例 —— 镜像本身已被用户否决。
        //
        // 旧理由是「68 处引用全砍掉角色就没手艺了」，但那是**猜测**，从没实测。
        // 实测：34 个 localName 去重后全部能由 LEGACY_NAME_ALIAS 落到集群包，
        // 落 missing 0 —— 全局兜底零贡献，可以整条砍掉。
        val lib = ClusterSkillStore.load(ctx)
        assertTrue("集群库应有内置技能", lib.isNotEmpty())
        val clusterNames = lib.map { it.name }.toSet()

        val refs = ClusterRoleCards.BUILT_IN
            .flatMap { it.skills }
            .filter { !it.isOpenSource }
            .map { it.localName }
            .filter { it.isNotBlank() }
            .distinct()
        assertTrue("内置卡应至少有技能引用", refs.isNotEmpty())

        val unresolvable = refs.filter { name ->
            name !in clusterNames && resolveAlias(name)?.let { it in clusterNames } != true
        }
        assertTrue(
            "这些引用既不在集群库、别名也指不到 —— 砍掉全局兜底后会落 missing：" + unresolvable,
            unresolvable.isEmpty()
        )
    }

    @Test
    fun `集群代码不再读全局技能库`() {
        // 用户原话：「集群实现自己的技能 skills，要分开」。
        // 这次不靠 code review 保证，而是扫源码 —— 任何 cluster 包文件里
        // 出现 QuroSkillStore 或构造 QuroSkill 都算违规。
        //
        // 注意 ClusterSkillStore 是集群自己的库，名字里也含 SkillStore，不能误伤，
        // 所以只查带 Quro 前缀的那两个。
        val banned = listOf("QuroSkillStore", "core.skill.QuroSkill")
        val dir = File(repoRoot(), "app/src/main/java/com/ai/assistance/quro/core/cluster")
        val files = dir.listFiles { f: File -> f.name.endsWith(".kt") }.orEmpty()
        assertTrue("应至少扫到一些 cluster 源文件", files.isNotEmpty())

        val offenders = files.mapNotNull { f ->
            // 必须剥掉注释再扫：源码里有大量「这里不再用 QuroSkillStore 了」之类的
            // 历史说明文档，那是**说明隔离**的注释，不是耦合。裸扫全文会全误判。
            val code = stripComments(f.readText())
            val hit = banned.filter { code.contains(it) }
            if (hit.isEmpty()) null else f.name to hit
        }
        assertTrue(
            "集群包仍在引用全局技能库（用户要求完全分开）：$offenders",
            offenders.isEmpty()
        )
    }

    // ———————————————— helpers ————————————————

    /** 去掉 `//` 行注释与 `/* */`（含 KDoc）块注释，字符串字面量原样保留。 */
    private fun stripComments(src: String): String {
        val out = StringBuilder(src.length)
        var i = 0
        val n = src.length
        var inStr = false
        while (i < n) {
            val c = src[i]
            if (inStr) {
                out.append(c)
                if (c == '\\' && i + 1 < n) {
                    out.append(src[i + 1]); i += 2; continue
                }
                if (c == '"') inStr = false
                i++
                continue
            }
            when {
                c == '"' -> { inStr = true; out.append(c); i++ }
                c == '/' && i + 1 < n && src[i + 1] == '/' -> {
                    while (i < n && src[i] != '\n') i++
                }
                c == '/' && i + 1 < n && src[i + 1] == '*' -> {
                    i += 2
                    while (i + 1 < n && !(src[i] == '*' && src[i + 1] == '/')) i++
                    i = if (i + 1 < n) i + 2 else n
                }
                else -> { out.append(c); i++ }
            }
        }
        return out.toString()
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

    private fun role(id: String) = RoleProfile(personaId = id, role = RoleKind.EXECUTOR)

    private fun task(build: TaskBuilder.() -> Unit): ClusterTask {
        val b = TaskBuilder()
        b.build()
        return ClusterTask(id = "t1", clusterId = "c1", goal = "g", nodes = b.nodes)
    }

    private class TaskBuilder {
        val nodes = mutableListOf<ClusterNode>()
        fun node(
            id: String,
            state: NodeState,
            error: String? = null,
        ) {
            nodes += ClusterNode(id = id, title = "节点$id", state = state).also {
                it.lastError = error
            }
        }
    }
}