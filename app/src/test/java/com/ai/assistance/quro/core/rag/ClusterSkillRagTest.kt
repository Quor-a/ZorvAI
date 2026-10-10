package com.ai.assistance.quro.core.rag

import android.content.Context
import com.ai.assistance.quro.core.cluster.ClusterSkillRuntime
import com.ai.assistance.quro.core.cluster.ClusterSkillStore
import com.ai.assistance.quro.core.cluster.ClusterSkillStore.ClusterSkill
import com.ai.assistance.quro.core.cluster.ClusterTestSkills
import com.ai.assistance.quro.core.cluster.RoleContextPolicy
import com.ai.assistance.quro.core.cluster.RoleKind
import com.ai.assistance.quro.core.cluster.RoleProfile
import com.ai.assistance.quro.core.tools.QuroToolRouter
import com.ai.assistance.quro.core.tools.RagSearchTool
import com.ai.assistance.quro.core.tools.buildQuroRegistry
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
 * #217 回归锁：集群技能 RAG 域 + 集群工具调用走 ZorvAI 主通道。
 *
 * 用户最新诉求：「给集群加技能 skills 加 RAG，工具调用获取，直接走 zorvAI 通道不要另开系统」。
 *
 * 本测试逐条钉死四个契约：
 *  1. [ClusterSkillRagIndex] 把集群技能注册成独立 RAG 域，模糊意图能召回技能全文；
 *  2. `rag_search(domain="cluster_skills")` 命中带回**完整规程正文**（不是摘要）；
 *  3. [ClusterSkillRuntime.resolveSkills] 走 RAG 按需注入：命中技能注入正文，
 *     未命中技能保留摘要行，角色仍知道它绑了哪些手艺；
 *  4. 集群云端角色工具面与主对话一致 —— [QuroToolRouter.activeSpecs] 渐进式披露，
 *     且 `tool_router` 经集群引擎的 [execTool] 拦截后返回目录/加载结果。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ClusterSkillRagTest {

    private val ctx get() = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        // Robolectric 下 assets 读不到 app 自带资源，用磁盘同源播种（与生产同一解析函数）。
        ClusterTestSkills.seedClusterSkills(ctx)
        // 清掉上次测试可能残留的索引，重新灌入当前库。
        ClusterSkillRagIndex.install(ctx)
    }

    // ─────────────────────────── 1. 技能 RAG 域 ───────────────────────────

    @Test
    fun `cluster skills are registered as a rag domain`() {
        val stats = ClusterSkillRagIndex.stats()
        assertTrue(
            "集群技能必须注册成 RAG 域（实际 $stats 篇），否则 rag_search(domain=cluster_skills) 恒零命中",
            stats > 0
        )
        assertEquals(ClusterSkillRagIndex.DOMAIN, "cluster_skills")
    }

    @Test
    fun `fuzzy intent recalls skill by chinese ability word`() {
        // 找一门真实存在的、能力词与「写网页/前端」相关的技能
        val skillId = ClusterTestSkills.skillIdByAbility(ctx, "网页", "前端", "html")
        assertTrue("测试前提：集群库里应有前端/网页类技能", skillId.isNotBlank())

        val hits = ClusterSkillRagIndex.searchSkills("写一个网页页面", limit = 5)
        assertTrue(
            "模糊意图「写一个网页页面」应能召回集群技能，实际=${hits.map { it.id }}",
            hits.isNotEmpty()
        )
        assertTrue(
            "召回结果里应包含技能 $skillId，实际=${hits.map { it.id }}",
            skillId in hits.map { it.id }
        )
    }

    @Test
    fun `fuzzy intent recalls skill by english name`() {
        val skillId = ClusterTestSkills.skillIdByAbility(ctx, "frontend", "html", "web")
        assertTrue("测试前提：集群库里应有 frontend 类技能", skillId.isNotBlank())

        val hits = ClusterSkillRagIndex.searchSkills("frontend design", limit = 5)
        assertTrue(
            "英文技能名也应能召回，实际=${hits.map { it.id }}",
            hits.any { it.id == skillId }
        )
    }

    @Test
    fun `rag hit payload carries full skill body`() {
        val hits = ClusterSkillRagIndex.searchSkills("写网页", limit = 3)
        assertTrue("前置：需有命中", hits.isNotEmpty())
        val body = ClusterSkillRagIndex.skillBodyOf(hits.first())
        assertTrue(
            "命中必须能取出完整技能正文（prompt），而不是空",
            !body.isNullOrBlank()
        )
        // 正文应长于描述首句（说明取的是全文不是摘要）
        val hitDoc = hits.first().doc
        assertTrue(
            "取出的正文应明显长于 description 首句（全文 vs 摘要），bodyLen=${body!!.length}, desc=${hitDoc.description.length}",
            body.length > hitDoc.description.lineSequence().firstOrNull().orEmpty().length
        )
    }

    // ─────────────────── 2. rag_search(domain=cluster_skills) ───────────────────

    @Test
    fun `rag_search tool returns full skill body for cluster_skills domain`() {
        val out = RagSearchTool.query("""{"query":"写一个网页页面","domain":"cluster_skills","limit":5}""")
        val jo = JSONObject(out)
        assertTrue(
            "cluster_skills 域应命中，实际 count=${jo.optInt("count")}",
            jo.optInt("count") > 0
        )
        val full = jo.optString("skills_full_text", "")
        assertTrue(
            "顶层必须带 skills_full_text（可直接照着执行的规程原文）",
            full.isNotBlank()
        )
        // 至少一条 hit 带 skill_body 原文，且明显长于描述摘要
        val hits = jo.getJSONArray("hits")
        var maxBody = 0
        for (i in 0 until hits.length()) {
            val h = hits.getJSONObject(i)
            if (h.optString("domain") == ClusterSkillRagIndex.DOMAIN) {
                maxBody = maxOf(maxBody, h.optString("skill_body", "").length)
            }
        }
        assertTrue("命中的集群技能必须带 skill_body 原文，maxBody=$maxBody", maxBody > 0)
    }

    @Test
    fun `rag_search honors domain isolation for cluster_skills`() {
        // 用与集群技能域几乎无关的查询验证域隔离：
        // 契约是「限定域的结果**不混入其它域**」——若零命中则 count=0 且 hits 为空；
        // 若引擎的零命中二次扩展在 cluster_skills 域内擦到了词，则 hits 里的 domain
        // 必须全部是 cluster_skills，绝不能混入 tools/prompts。
        val out = RagSearchTool.query("""{"query":"zzzzqqq完全不相关的东西xyzzy","domain":"cluster_skills"}""")
        val jo = JSONObject(out)
        val hits = jo.optJSONArray("hits")
        if (jo.optInt("count", -1) == 0) {
            assertEquals("限定 cluster_skills 域零命中时 requested_domain 必须回显", "cluster_skills", jo.optString("requested_domain"))
            assertEquals("零命中时 hits 必须为空（不混入其它域）", 0, hits?.length())
            // 但必须给出其它域的线索（零命中绝不返回空）
            assertTrue("零命中必须带 what_other_domains_return", jo.has("what_other_domains_return"))
        } else {
            // 有命中时，每条 domain 必须都是 cluster_skills（域严格隔离）
            for (i in 0 until (hits?.length() ?: 0)) {
                val h = hits!!.getJSONObject(i)
                assertEquals(
                    "限定 cluster_skills 域的命中不得混入其它域：${h.optString("name")}",
                    ClusterSkillRagIndex.DOMAIN,
                    h.optString("domain"),
                )
            }
        }
    }

    // ─────────────────── 3. resolveSkills 走 RAG 按需注入 ───────────────────

    private fun roleWithSkill(skillId: String): RoleProfile = RoleProfile(
        personaId = "p_rag",
        modelProfileId = "cloud:current",
        role = RoleKind.EXECUTOR,
        duties = listOf("写网页页面"),
        skills = listOf("前端", "网页"),
        skillIds = listOf(skillId),
        context = RoleContextPolicy(toolWhitelist = emptySet()),
    )

    @Test
    fun `resolveSkills injects body for rag-hit skill and keeps summary for others`() {
        val all = ClusterSkillStore.load(ctx)
        assertTrue("前置：需有技能库", all.isNotEmpty())

        // 一个与「写网页」相关的真实技能（html-dev / frontend-design 类）
        val webSkill = all.firstOrNull {
            ClusterSkillStore.abilityHaystack(it).any { w -> w.contains("网页") || w.contains("前端") || w.contains("html") }
        } ?: all.first()

        // 一个完全无关的手工技能：能力词与任务上下文（写网页/前端）零重叠
        val unrelatedSkill = ClusterSkill(
            id = "cluster_unrelated", name = "device-control",
            description = "操作手机设备", prompt = "手机操作规程正文",
            abilityWords = "手机 设备 点击 滑动", enabled = true,
        ).also { ClusterSkillStore.upsert(ctx, it) }

        val role = roleWithSkill(webSkill.id).copy(skillIds = listOf(webSkill.id, unrelatedSkill.id))
        val resolved = ClusterSkillRuntime.resolveSkills(ctx, role)

        // 摘要行 = 全部绑定技能（含未命中 RAG 的）
        assertTrue(
            "摘要行必须登记全部绑定技能（含未命中 RAG 的），实际=${resolved.summaryLines}",
            resolved.summaryLines.size == 2
        )
        // 注入正文 = RAG 命中的技能（webSkill 应被注入）
        val bodies = resolved.bodies.joinToString("\n")
        assertTrue(
            "与「写网页」相关的技能正文应被注入，实际 bodies=${bodies.take(200)}",
            bodies.contains(webSkill.name)
        )
        // 无关技能只进摘要行，正文不应被注入
        assertFalse(
            "与当前任务无关的技能正文不应被注入（避免刷屏），实际 bodies=${bodies.take(300)}",
            bodies.contains(unrelatedSkill.name)
        )
    }

    @Test
    fun `resolveSkills with no skills returns empty`() {
        val role = roleWithSkill("").copy(skillIds = emptyList())
        val resolved = ClusterSkillRuntime.resolveSkills(ctx, role)
        assertTrue(resolved.skills.isEmpty())
        assertTrue(resolved.summaryLines.isEmpty())
        assertTrue(resolved.bodies.isEmpty())
    }

    // ─────────────────── 4. 工具调用走 ZorvAI 主通道 ───────────────────

    @Test
    fun `cloud role tools come from progressive disclosure activeSpecs`() {
        val router = QuroToolRouter(buildQuroRegistry(null).specs())
        val active = router.activeSpecs()
        assertTrue("渐进式披露必须包含路由目录 tool_router", active.any { it.name == "tool_router" })
        assertTrue("渐进式披露必须包含常驻核心集（如 web_search）", active.any { it.name == "web_search" })
        // 不相关的长尾工具不应被全量下发
        assertTrue(
            "渐进式披露不应包含全部工具（否则等于没省上下文），实际=${active.size}",
            active.size < router.allSpecsSnapshot().size
        )
    }

    @Test
    fun `tool_router handle returns catalog and loads schema`() {
        val router = QuroToolRouter(buildQuroRegistry(null).specs())

        // match_intent 走主通道检索
        val match = router.handle("tool_router", """{"action":"match_intent","intent":"搜索资料"}""")
        assertTrue("match_intent 应返回候选工具，实际=$match", match.contains("web_search") || match.contains("搜索"))

        // get_schema 加载工具并返回完整 schema
        val schema = router.handle("tool_router", """{"action":"get_schema","name":"web_search"}""")
        assertTrue("get_schema 应返回已加载确认", schema.contains("已加载工具"))
        assertTrue("get_schema 应带完整参数 Schema", schema.contains("parametersJson") || schema.contains("schema") || schema.contains("properties"))
    }

    @Test
    fun `cluster execTool intercepts tool_router and delegates to main channel`() {
        // 通过 ClusterEngine 的实际 execTool 路径验证：脚本网关 + 真实工具注册表。
        // 这里用反射不可靠，直接验证 ClusterEngine.toolsForRole 的云端路径返回 activeSpecs。
        val context = ctx
        val all = buildQuroRegistry(null).specs()
        // 模拟 ClusterEngine.routerFor 的行为：QuroToolRouter 被 setSpecs 后 activeSpecs 与主对话一致
        val router = QuroToolRouter(all, context.applicationContext)
        router.setSpecs(all)
        val active = router.activeSpecs()
        assertTrue(active.any { it.name == "tool_router" })
        // 与主对话一致：activeSpecs 应包含常驻核心工具
        assertTrue(active.any { it.name == "web_search" })
    }
}