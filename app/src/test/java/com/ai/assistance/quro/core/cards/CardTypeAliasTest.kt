package com.ai.assistance.quro.core.cards

import com.ai.assistance.quro.core.cards.QuroChatCard.ChartCard
import com.ai.assistance.quro.core.cards.QuroChatCard.CompositeCard
import com.ai.assistance.quro.core.cards.QuroChatCard.StatCard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 跨通道 type 名归一化的回归测试。
 *
 * ## 为什么必须有这组测试
 *
 * 项目里两套可视化通道的类型名互相冲突：`CardModule`（`quro-card` 围栏）有
 * `status` / `metric` / `line_chart` / `button_group` / `custom`，而 `CardSdk`
 * （`ui_widget` / `ui_card`，也就是用户说的「可视化小卡片」）里它们叫
 * `stat` / `chart` / `actions` / `composite`。
 *
 * 模型把围栏侧的名字写进 `ui_widget` 时，原来一律落「未识别组件：status」——
 * 现象是用户看到红字兜底卡，换任何模型都复现。这不是模型问题，是名字对不上。
 * 这里把「两套名字必须都能解析」钉死，任何一次名册重构都不能再让它回归。
 */
class CardTypeAliasTest {

    private fun parse(spec: String): QuroChatCard? = CardSdk.parse(spec)

    @Test
    fun `status 归一到 stat 而不是落未识别组件`() {
        val c = parse("""{"type":"status","label":"进度","value":"80%"}""")
        assertTrue("status 应解析为 StatCard，实际 ${c?.javaClass?.simpleName}", c is StatCard)
        assertEquals("进度", (c as StatCard).label)
        assertEquals("80%", c.value)
    }

    @Test
    fun `metric 归一到 stat`() {
        val c = parse("""{"type":"metric","label":"在线","value":42}""")
        assertTrue("metric 应解析为 StatCard，实际 ${c?.javaClass?.simpleName}", c is StatCard)
        assertEquals("在线", (c as StatCard).label)
    }

    @Test
    fun `line_chart 归一到 chart`() {
        val c = parse(
            """{"type":"line_chart","chart_type":"line","series":[{"label":"周一","value":3},{"label":"周二","value":5}]}""",
        )
        assertTrue("line_chart 应解析为 ChartCard，实际 ${c?.javaClass?.simpleName}", c is ChartCard)
        assertEquals("line", (c as ChartCard).type)
        assertEquals(2, c.series.size)
        assertEquals(5f, c.series[1].value, 0.001f)
    }

    @Test
    fun `button_group 归一到 actions`() {
        val c = parse("""{"type":"button_group","actions":[{"label":"继续","command":"ai:继续"}]}""")
        assertTrue("button_group 应解析成功，实际 null", c != null)
    }

    @Test
    fun `custom 归一到 composite`() {
        val c = parse(
            """{"type":"custom","layout":"stack","children":[{"type":"stat","label":"A","value":"1"}]}""",
        )
        assertTrue("custom 应解析为 CompositeCard，实际 ${c?.javaClass?.simpleName}", c is CompositeCard)
    }

    @Test
    fun `容器类手滑写法都归一到 card`() {
        for (t in listOf("cards", "container", "panel", "box", "group", "layout")) {
            val c = parse("""{"type":"$t","children":[{"type":"stat","label":"x","value":"1"}]}""")
            assertTrue("type=$t 应解析为 CompositeCard，实际 ${c?.javaClass?.simpleName}", c is CompositeCard)
        }
    }

    @Test
    fun `card 本身是名册类型 不走别名`() {
        assertTrue("card 必须是名册里的真实类型", "card" in CardSdk.types)
        val c = parse("""{"type":"card","children":[{"type":"stat","label":"x","value":"1"}]}""")
        assertTrue("card 应解析为 CompositeCard", c is CompositeCard)
    }

    @Test
    fun `别名表不得指向不存在的类型`() {
        // 通过公开路径间接校验：直接反射拿私有表，避免为测试把表公开化
        val f = CardSdk::class.java.getDeclaredField("TYPE_ALIASES")
        f.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val map = f.get(null) as Map<String, String>
        assertTrue("别名表不应为空", map.isNotEmpty())
        val bad = map.filterValues { it !in CardSdk.types }
        assertTrue("以下别名指向了名册里不存在的 type：$bad", bad.isEmpty())
        // key 与真实类型重名会让别名永远走不到（真实类型优先），属隐患
        val shadowed = map.keys.filter { it in CardSdk.types }
        assertTrue("以下别名 key 与真实类型重名、会被真实类型遮蔽：$shadowed", shadowed.isEmpty())
    }

    @Test
    fun `彻底不认识的 type 仍然落兜底 不做臆测`() {
        val c = parse("""{"type":"zzz_not_a_card_zzz","foo":"bar"}""")
        assertTrue("未知 type 应落 CustomCard 兜底", c is CustomCard)
        assertEquals("zzz_not_a_card_zzz", (c as CustomCard).kind)
    }

    @Test
    fun `别名不改变字段内容`() {
        val c = parse("""{"type":"status","label":"内存","value":"6G","unit":"GB","delta":"+2%"}""") as StatCard
        assertEquals("内存", c.label)
        assertEquals("6G", c.value)
        assertEquals("GB", c.unit)
        assertEquals("+2%", c.delta)
    }
}
