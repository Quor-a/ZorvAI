package com.ai.assistance.quro.core.cards

import com.ai.assistance.quro.core.cards.CardFence.FENCE_CARD
import com.ai.assistance.quro.core.cards.CardFence.FENCE_CARDS
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 第三批 9 种组件（AI 征询决策 + 可视化进阶）的自检。
 *
 * 这批的设计依据来自 A2UI / AG-UI 协议调研，测试重点因此不只是「能构造」，
 * 还要守住两件容易退化的事：
 *  1. **语义边界**：decision 与 quickreply、graph 与 tree、waterfall 与 bar 的区别
 *     一旦模糊，AI 就会选错卡（能渲染但表达不准，比报错更难发现）；
 *  2. **funnel 是扩展存量类而非新建** —— 老存档的 `{"steps":[{"name":..,"value":..}]}`
 *     必须仍能解析，否则升级即丢历史数据。
 */
class CardSdkEx3Test {

    /** 名册自检必须干净：漏接线、样例不自洽、type 重复都在这里被抓住。 */
    @Test
    fun `名册自检必须干净`() {
        val issues = CardSdk.lint()
        assertTrue("lint 应为空，实际报出：\n" + issues.joinToString("\n"), issues.isEmpty())
    }

    @Test
    fun `别名表指向的 type 必须都在名册里`() {
        val issues = CardFence.lintAliases()
        assertTrue("别名指向了名册里没有的 type：\n" + issues.joinToString("\n"), issues.isEmpty())
    }

    @Test
    fun `第三批 9 种都能从样例构造并往返`() {
        val types = listOf(
            "decision", "confirm", "sankey", "funnel", "waterfall",
            "quadrant", "matrix", "feed", "graph", "section",
        )
        types.forEach { t ->
            val spec = CardSdk.byType[t] ?: error("名册里没有 $t")
            val built = spec.builder?.invoke(JSONObject(spec.sample))
            assertNotNull("$t 的样例应能构造出卡片", built)
            val round = parseCard(serializeCard(built!!))
            assertNotNull("$t serialize → parse 应能读回", round)
            assertEquals("$t 往返后类型变了", built::class, round!!::class)
        }
    }

    @Test
    fun `第三批都能过整条围栏链路`() {
        listOf("decision", "confirm", "sankey", "waterfall", "quadrant", "matrix", "feed", "graph", "section")
            .forEach { t ->
                val spec = CardSdk.byType.getValue(t)
                val cards = CardFence.toCards(FENCE_CARD, spec.sample)
                assertEquals("$t 走 card 围栏应出 1 张卡", 1, cards.size)
                val arr = CardFence.toCards(FENCE_CARDS, "[${spec.sample}]")
                assertEquals("$t 走 cards 围栏也应出 1 张卡", 1, arr.size)
            }
    }

    // ── 语义边界（这批最该守住的东西）──

    @Test
    fun `decision 与 quickreply 是两类卡不能互相顶替`() {
        // quickreply 是「给用户可点的回应，推进对话」；
        // decision 是「AI 卡住等一个约束」。两者渲染形态相似，用途完全不同。
        val decision = CardSdk.byType.getValue("decision")
        assertTrue("decision 的说明必须点明与 quickreply 的区别", decision.description.contains("quickreply"))
        assertTrue("decision 必须允许自由输入出口", decision.description.contains("allowCustom"))

        val built = decision.builder!!.invoke(JSONObject(decision.sample))
        val d = built as DecisionCard
        assertTrue("allowCustom 缺省应为 true，否则用户被逼在选项里", d.allowCustom)
        assertEquals(2, d.options.size)
        assertTrue("样例应至少有一个推荐项", d.options.any { it.recommended })
    }

    @Test
    fun `confirm 把取消放在前且危险态可辨`() {
        val spec = CardSdk.byType.getValue("confirm")
        val c = spec.builder!!.invoke(JSONObject(spec.sample)) as ConfirmCard
        assertTrue("危险操作样例应标 danger", c.danger)
        assertTrue("必须有取消出口", c.cancelLabel.isNotBlank())
    }

    @Test
    fun `sankey 的连线端点必须在节点里否则渲染会画空气`() {
        val spec = CardSdk.byType.getValue("sankey")
        val s = spec.builder!!.invoke(JSONObject(spec.sample)) as SankeyCard
        val ids = s.nodes.map { it.id }.toSet()
        s.links.forEach { l ->
            assertTrue("连线起点 ${l.from} 不在节点里", l.from in ids)
            assertTrue("连线终点 ${l.to} 不在节点里", l.to in ids)
        }
    }

    @Test
    fun `graph 允许多父与成环这是它与 tree 的本质区别`() {
        val spec = CardSdk.byType.getValue("graph")
        val g = spec.builder!!.invoke(JSONObject(spec.sample)) as GraphCard
        // 同一节点被多条边指向（多父）不应报错
        val targets = g.edges.groupBy { it.to }.filterValues { it.size > 1 }
        assertTrue("graph 应能表达多父", targets.isNotEmpty() || g.edges.size >= 3)
        assertTrue("graph 的说明必须点明与 tree 的区别", spec.description.contains("tree"))
    }

    @Test
    fun `waterfall 与 bar 的区别写在说明里`() {
        val desc = CardSdk.byType.getValue("waterfall").description
        assertTrue("必须点明是累加推导而非并列比较", desc.contains("累加") || desc.contains("归因"))
        assertTrue("必须点名 bar 以便 AI 区分", desc.contains("bar"))
    }

    @Test
    fun `quadrant 两轴语义与坐标范围自洽`() {
        val spec = CardSdk.byType.getValue("quadrant")
        val q = spec.builder!!.invoke(JSONObject(spec.sample)) as QuadrantCard
        assertTrue("axisMax 必须为正", q.axisMax > 0)
        q.items.forEach {
            assertTrue("${it.label} 的 x 越界", it.x in 0.0..q.axisMax)
            assertTrue("${it.label} 的 y 越界", it.y in 0.0..q.axisMax)
        }
        assertEquals("象限名应给全 4 个，否则渲染层无法标注", 4, q.quadrants.size)
    }

    @Test
    fun `matrix 的 better 决定优劣着色方向`() {
        val spec = CardSdk.byType.getValue("matrix")
        val m = spec.builder!!.invoke(JSONObject(spec.sample)) as MatrixCard
        assertTrue("应有一行 better=null（不参与判定，如发布时间）", m.rows.any { it.better == null })
        assertTrue("应同时有 high 与 low 两种方向", m.rows.any { it.better == "high" } && m.rows.any { it.better == "low" })
    }

    @Test
    fun `feed 的 level 只能是约定四档否则圆点无色可依`() {
        val spec = CardSdk.byType.getValue("feed")
        val f = spec.builder!!.invoke(JSONObject(spec.sample)) as FeedCard
        val ok = setOf("info", "success", "warning", "error")
        f.items.forEach { assertTrue("level ${it.level} 非法", it.level in ok) }
    }

    // ── funnel 是扩展存量类：老数据必须仍能解析 ──

    @Test
    fun `funnel 老存档格式仍能解析不丢历史数据`() {
        // 这是「扩展存量类而非新建同名类」的根本理由
        val old = """{"type":"funnel","steps":[{"name":"曝光","value":1000},{"name":"点击","value":300}]}"""
        val f = CardSdk.parse(old) as FunnelCard
        assertEquals(2, f.steps.size)
        assertEquals("曝光", f.steps[0].name)
        assertEquals(1000.0, f.steps[0].value, 0.001)
        assertTrue("新增字段走默认值", f.steps[0].hint.isEmpty())
        assertEquals(300.0, f.steps[1].value, 0.001)
    }

    @Test
    fun `funnel 兼容 label 写法`() {
        // 模型很可能写 label 而不是存量字段 name，两种都吃才不至于静默丢名
        val f = CardSdk.parse("""{"type":"funnel","steps":[{"label":"访问","value":10}]}""") as FunnelCard
        assertEquals("访问", f.steps[0].name)
    }

    @Test
    fun `funnel 新字段可解析且往返不丢`() {
        val f = CardSdk.parse(
            """{"type":"funnel","unit":"人","showRate":false,"steps":[{"name":"访问","value":100,"hint":"入口"},{"name":"注册","value":32}]}"""
        ) as FunnelCard
        assertEquals("人", f.unit)
        assertEquals(false, f.showRate)
        assertEquals("入口", f.steps[0].hint)
        val round = parseCard(serializeCard(f)) as FunnelCard
        assertEquals("人", round.unit)
        assertEquals("入口", round.steps[0].hint)
    }

    @Test
    fun `funnel 名册只有一条不重复登记`() {
        // 加「增强版」时最容易犯的错：新建同名类 + 再登记一条名册，两者都静默失效
        assertEquals(1, CardSdk.all.count { it.type == "funnel" })
    }

    @Test
    fun `section 的 rows 双形态都能吃`() {
        val spec = CardSdk.byType.getValue("section")
        val s = spec.builder!!.invoke(JSONObject(spec.sample)) as SectionCard
        assertTrue("样例应是数组形态", s.sections.isNotEmpty())
        // 对象形态
        val objForm = """{"type":"section","sections":[{"title":"A","rows":[{"k":"x","v":"1"}]}]}"""
        val s2 = CardSdk.parse(objForm) as SectionCard
        assertEquals("x" to "1", s2.sections[0].rows[0])
    }

    @Test
    fun `紧凑清单已含第三批`() {
        val compact = CardSdk.compactCatalog()
        listOf("decision", "confirm", "sankey", "waterfall", "quadrant", "matrix", "feed", "graph", "section")
            .forEach { t -> assertTrue("紧凑清单漏了 $t", compact.contains(t)) }
    }
}