package com.ai.assistance.quro.core.cluster

import com.ai.assistance.quro.core.skill.QuroSkill
import com.ai.assistance.quro.core.skill.QuroSkillStore
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * #191：开源技能市场 + 角色卡（skills 聚合）的回归测试。
 *
 * 重点钉死四件事：
 * 1. 开源源目录里的路径/分支口径正确（写错分支整个功能就是 404）；
 * 2. **角色卡引用的技能名必须真实存在** —— 否则用户按卡建角色却没手艺，
 *    正是本轮要消灭的「凭空想象」；
 * 3. 角色卡本身必须真的带技能聚合 + 灵魂注入正文，不是光有个名字；
 * 4. 网络不可用时必须**如实报错**，不能返回空列表假装「社区没有」。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ClusterRoleCardTest {

    // 与 ClusterSkillRuntimeTest 同一套取 context 的写法（本仓用 Robolectric RuntimeEnvironment）
    private val ctx get() = RuntimeEnvironment.getApplication()

    /** Gradle test 的工作目录可能是 app/ 或工程根，向上找带settings.gradle.kts 的那层。 */
    private fun repoRoot(): File {
        val cwd = File(System.getProperty("user.dir"))
        var d: File? = cwd.absoluteFile
        repeat(6) {
            val cur = d ?: return cwd
            if (File(cur, "settings.gradle.kts").exists()) return cur
            d = cur.parentFile
        }
        return cwd
    }

    // ——————————————— 开源源口径 ———————————————

    @Test
    fun `open sources use real repos and paths`() {
        val srcs = ClusterOpenSkillHub.SOURCES
        assertEquals(4, srcs.size)
        assertTrue(srcs.all { it.owner.isNotBlank() && it.repo.isNotBlank() && it.ref.isNotBlank() })
        // id 必须唯一（工具参数按 id 选源）
        assertEquals(srcs.size, srcs.map { it.id }.toSet().size)
        // slug 全是小写规范名
        assertTrue(srcs.all { it.slug == it.owner + "/" + it.repo })
    }

    @Test
    fun `composio source uses master not main`() {
        // 实测该仓 default_branch = master；写 main 会 404，功能整体失效
        val composio = ClusterOpenSkillHub.SOURCES.filter { it.owner == "ComposioHQ" }
        assertTrue(composio.isNotEmpty())
        assertTrue(composio.all { it.ref == "master" })
    }

    @Test
    fun `empty repo root path does not produce double slash`() {
        val root = ClusterOpenSkillHub.SOURCES.first { it.path.isEmpty() }
        // 双斜杠会 302 到不带 ?ref= 的地址 → 分支被丢掉
        assertFalse(root.contentsApiPath().contains("contents//"))
        assertTrue(root.contentsApiPath().endsWith("?ref=${root.ref}"))
        val nested = ClusterOpenSkillHub.SOURCES.first { it.path.isNotEmpty() }
        assertFalse(nested.contentsApiPath().contains("//"))
        assertFalse(nested.skillMdApiPath("abc").contains("//"))
    }

    @Test
    fun `unknown source id is rejected with the real list`() {
        assertEquals(null, ClusterOpenSkillHub.sourceById("no-such-source"))
        // 现有源应能查到
        assertNotNull(ClusterOpenSkillHub.sourceById("anthropics-skills"))
    }

    // ——————————————— 角色卡是 skills 聚合 ———————————————

    @Test
    fun `every built in card is a real skills aggregate not just a name`() {
        assertTrue("内置角色卡不能为空", ClusterRoleCards.BUILT_IN.isNotEmpty())
        ClusterRoleCards.BUILT_IN.forEach { c ->
            assertTrue("卡 ${c.id} 没有技能聚合", c.skills.isNotEmpty())
            assertTrue("卡 ${c.id} 没有灵魂注入正文", c.persona.isBlank().not())
            assertTrue("卡 ${c.id} 没有职责", c.duties.isNotEmpty())
            assertTrue("卡 ${c.id} 没有禁忌", c.taboos.isNotEmpty())
            assertTrue("卡 ${c.id} 没有分工", c.kind != RoleKind.HOST) // 主持不可做卡
        }
    }

    @Test
    fun `card ids are unique and searchable`() {
        val cards = ClusterRoleCards.BUILT_IN
        assertEquals(cards.size, cards.map { it.id }.toSet().size)
        // 用户点名的场景必须都有卡
        listOf("ui-designer", "html-frontend-dev", "website-builder", "phone-operator",
            "planner", "content-strategist", "writer", "md-document", "critic")
            .forEach { id ->
                assertNotNull("缺少角色卡：$id", ClusterRoleCards.byId(id))
            }
        // 评审卡必须是 CRITIC，且不给出任何工具相关的承诺
        val critic = ClusterRoleCards.byId("critic")!!
        assertEquals(RoleKind.CRITIC, critic.kind)
        // 手机操作员必须是 EXECUTOR（真正动手的那个）
        assertEquals(RoleKind.EXECUTOR, ClusterRoleCards.byId("phone-operator")!!.kind)
        // 搜索按关键词
        assertTrue(ClusterRoleCards.search("ui").isNotEmpty())
    }

    /**
     * ## 为什么从磁盘读随包技能，而不是 `ctx.assets`
     * Robolectric 单元测试里 `AssetManager` 拿不到 AGP 合并后的 assets 目录（实测
     * `ctx.assets.list("skills/zorv")` 返回空，`names.size >= 60` 直接挂）。
     * 所以这里照 [BuiltinSkillAssetsTest] 的既有做法，直接从仓库磁盘读
     * `app/src/main/assets/skills/zorv`，再用与运行时**同一个**
     * `QuroSkillStore.parseSkillMd` 解析 front-matter —— 口径一致，
     * 也不依赖 `seedBuiltinZorvSkills` 的一次性播种守卫
     * （Robolectric 下多测试共享 prefs，会变成顺序依赖）。
     *
     * ## 这条断言在防什么
     * #191 开发中我按印象写了 10 个不存在的技能名（accessibility-review / write / pdf…），
     * 用户按卡建角色就会「说自己会但其实没装」—— 正是本轮要消灭的凭空想象。
     */
    @Test
    fun `every local skill referenced by a card really exists among builtin assets`() {
        val dir = File(repoRoot(), "app/src/main/assets/skills/zorv")
        assertTrue("找不到随包技能目录：${dir.absolutePath}", dir.isDirectory)
        val names = mutableSetOf<String>()
        dir.listFiles { f -> f.isFile && f.name.endsWith(".md") }?.forEach { f ->
            QuroSkillStore.parseSkillMd(f.readText()).forEach { sk -> names.add(sk.name) }
        }
        assertTrue("没读到随包技能（解析口径变了？）实际读到 ${names.size} 个", names.size >= 60)
        val missing = mutableListOf<String>()
        ClusterRoleCards.BUILT_IN.forEach { c ->
            c.skills.filter { !it.isOpenSource }.forEach { ref ->
                if (ref.localName !in names) missing += "${c.id} -> ${ref.localName}"
            }
        }
        assertTrue("角色卡引用了不存在的技能：$missing", missing.isEmpty())
    }

    @Test
    fun `open source refs point at real source ids`() {
        ClusterRoleCards.BUILT_IN.forEach { c ->
            c.skills.filter { it.isOpenSource }.forEach { ref ->
                val sid = ref.openSource.substringBefore('/')
                assertNotNull(
                    "卡 ${c.id} 引用了不存在的开源源：$sid",
                    ClusterOpenSkillHub.sourceById(sid)
                )
                assertTrue(
                    "卡 ${c.id} 的开源引用缺少目录名：${ref.openSource}",
                    ref.openSource.substringAfter('/', "").isNotBlank()
                )
            }
        }
    }

    // ——————————————— 工具行为 ———————————————

    @Test
    fun `rolecard list reports installed and missing skills honestly`() {
        // 不走 seedBuiltinZorvSkills（有一次性守卫，Robolectric 下会因测试顺序而空库）：
        // 直接造几个真技能进库，剩下的卡内技能自然落到 skillsNotInstalled，正好覆盖两态。
        QuroSkillStore.save(
            ctx,
            listOf(
                QuroSkill(id = "sk_a", name = "zorv-ui-craft", description = "UI 手艺"),
                QuroSkill(id = "sk_b", name = "zorv-design-systems", description = "设计系统"),
            )
        )
        val out = JSONObject(ClusterRoleCardTool().run(ctx, """{"mode":"list"}"""))
        assertTrue(out.optBoolean("ok"))
        assertTrue(out.optInt("total") >= 8)
        val card = out.getJSONArray("cards").getJSONObject(0)
        // 每个卡都必须同时报「已装」与「未装」两个数组（哪怕其中一个是空的），
        // 这样 UI 与主持都能看见真实状态而不是只看到一半。
        assertNotNull(card.getJSONArray("skillsReady"))
        assertNotNull(card.getJSONArray("skillsNotInstalled"))
        assertTrue(card.getJSONArray("openSkills") != null)
    }

    @Test
    fun `rolecard list rejects unknown mode with a real message`() {
        val out = JSONObject(ClusterRoleCardTool().run(ctx, """{"mode":"nope"}"""))
        assertFalse(out.optBoolean("ok"))
        assertTrue(out.optString("error").contains("list"))
    }

    @Test
    fun `rolecard create with unknown card id lists the real cards`() {
        val out = JSONObject(ClusterRoleCardTool().run(ctx, """{"mode":"create","cardId":"no-such"}"""))
        assertFalse(out.optBoolean("ok"))
        val err = out.optString("error")
        assertTrue(err.contains("ui-designer"))
    }

    @Test
    fun `rolecard create without card id tells you to list first`() {
        val out = JSONObject(ClusterRoleCardTool().run(ctx, """{"mode":"create"}"""))
        assertFalse(out.optBoolean("ok"))
        assertTrue(out.optString("error").contains("mode=list"))
    }

    @Test
    fun `open skill search rejects unknown source with the real list`() {
        val out = JSONObject(ClusterOpenSkillSearchTool().run(ctx, """{"sourceId":"no-such"}"""))
        assertFalse(out.optBoolean("ok"))
        assertTrue(out.optString("error").contains("anthropics-skills"))
    }

    @Test
    fun `open skill install validates key format before touching network`() {
        // key 格式错时必须**先**本地报错，不要白跑一次网络
        val bad = JSONObject(ClusterOpenSkillInstallTool().run(ctx, """{"key":"nope"}"""))
        assertFalse(bad.optBoolean("ok"))
        assertTrue(bad.optString("error").contains("源id/目录名"))
        val empty = JSONObject(ClusterOpenSkillInstallTool().run(ctx, "{}"))
        assertFalse(empty.optBoolean("ok"))
    }

    @Test
    fun `grant now accepts skill names not only ids`() {
        QuroSkillStore.save(
            ctx,
            listOf(QuroSkill(id = "sk_g", name = "grant-skill", description = "按名绑"))
        )
        val tool = ClusterSkillGrantTool()
        // 没给任何技能来源时必须报错并说清两种入参
        val bad = JSONObject(tool.run(ctx, """{"personaId":"x"}"""))
        assertFalse(bad.optBoolean("ok"))
        assertTrue(bad.optString("error").contains("skillNames"))
        // 主持仍然不可绑
        val hostOut = JSONObject(tool.run(ctx, """{"personaId":"${RoleRegistry.HOST_PERSONA_ID}","skillIds":["a"]}"""))
        assertFalse(hostOut.optBoolean("ok"))
    }

    @Test
    fun `market now advertises open sources`() {
        // 🔴 #200：市场读的是**集群**库，所以测试也得写集群库。
        ClusterTestSkills.seedClusterSkills(ctx)
        ClusterSkillStore.upsert(
            ctx,
            ClusterSkillStore.ClusterSkill(
                id = "cluster_m", name = "market-skill", description = "给市场看的"
            )
        )
        val out = JSONObject(ClusterSkillMarketTool().run(ctx, "{}"))
        assertTrue(out.optBoolean("ok"))
        val sources = out.getJSONArray("openSources")
        assertEquals(ClusterOpenSkillHub.SOURCES.size, sources.length())
        assertTrue(out.has("openInstalledCount"))
        // 提示文案必须指向新工具，否则主持不知道外面还有技能市场
        assertTrue(out.optString("hint").contains("cluster_skill_search"))
    }

    @Test
    fun `stable id is deterministic so reinstall overwrites instead of duplicating`() {
        val a = stableSuffix("anthropics-skills/frontend-design")
        val b = stableSuffix("anthropics-skills/frontend-design")
        assertEquals(a, b)
        assertEquals(16, a.length)
        assertTrue(a != stableSuffix("anthropics-skills/docx"))
    }

    // ——— #192：结构化 soul（信念 / 鄙视什么 / 可判据）———

    @Test
    fun `every card that has a soul writes three non-empty sections`() {
        val withSoul = ClusterRoleCards.BUILT_IN.filter { !it.soul.isEmpty }
        // #192 补到 10 张；文档要求 12 位专家，这里只钉「有 soul 的必须写全」
        assertTrue("有 soul 的卡太少：${withSoul.size}", withSoul.size >= 10)
        withSoul.forEach { c ->
            assertTrue("${c.id} 缺信念", c.soul.beliefs.isNotEmpty())
            assertTrue("${c.id} 缺鄙视项", c.soul.despises.isNotEmpty())
            assertTrue("${c.id} 缺可判据标准", c.soul.standards.isNotEmpty())
            c.soul.beliefs.forEach { assertTrue("${c.id} 信念是空串", it.isNotBlank()) }
            c.soul.despises.forEach { assertTrue("${c.id} 鄙视项是空串", it.isNotBlank()) }
            c.soul.standards.forEach { assertTrue("${c.id} 标准是空串", it.isNotBlank()) }
        }
    }

    @Test
    fun `soul standards must be checkable not adjectives`() {
        // 文档原话：standards 必须能逐条验收。写「要有美感」无法验收。
        // 这里钉住一条可机检的底线：每条标准都要含数字/标点/明确动词之一，
        // 且不能是「美观/高级/专业」这类纯形容词收尾。
        val vague = listOf("美观", "高级", "专业", "大气", "舒服", "好看")
        ClusterRoleCards.BUILT_IN.forEach { c ->
            if (c.soul.isEmpty) return@forEach
            c.soul.standards.forEach { s ->
                val tail = s.trim().trimEnd('。', '，', ',', '！', '!')
                vague.forEach { v ->
                    assertFalse(
                        "${c.id} 的标准不可验收：「$s」",
                        tail == v || tail.endsWith("要有$v") || tail.endsWith("足够$v")
                    )
                }
                assertTrue("${c.id} 的标准太短，等于没写：$s", s.length >= 10)
            }
        }
    }

    @Test
    fun `soul render puts the three sections in the prompt`() {
        val c = ClusterRoleCards.BUILT_IN.first { !it.soul.isEmpty }
        val text = c.soul.render()
        assertTrue(text.contains("信念"))
        assertTrue(text.contains("鄙视"))
        assertTrue(text.contains("什么叫做好"))
        c.soul.standards.forEach { assertTrue("render 漏了标准：$it", text.contains(it)) }
    }

    @Test
    fun `the twelve experts from the doc are all present`() {
        // 文档第 3 页的 12 位内置专家，id 口径对齐。少一个就是没补齐。
        val need = listOf(
            "frontend-dev", "ui-designer", "writer", "doc-engineer",
            "planner", "strategist", "reviewer", "code-reviewer",
            "researcher", "operator", "analyst", "generalist"
        )
        val ids = ClusterRoleCards.BUILT_IN.map { it.id }.toSet()
        val alias = mapOf(
            "frontend-dev" to "html-frontend-dev",
            "doc-engineer" to "md-document",
            "strategist" to "content-strategist",
            "reviewer" to "critic",
            "operator" to "phone-operator"
        )
        need.forEach { n ->
            val real = alias[n] ?: n
            assertTrue("文档要求的专家「$n」缺失（找的是 $real），现有：$ids", real in ids)
        }
    }

    @Test
    fun `critic cards must not be executable`() {
        // 评审独立性：CRITIC 卡不能可执行，否则会自己改自己审。
        ClusterRoleCards.BUILT_IN.forEach { c ->
            if (c.kind == RoleKind.CRITIC) {
                assertFalse("${c.id} 是 CRITIC 就不能可执行", c.kind.canExecute)
                assertTrue("${c.id} 应标记为只验收", c.kind.isVerifier)
            }
        }
    }

}