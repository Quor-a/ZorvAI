package com.ai.assistance.quro.core.cards

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.json.JSONObject

/** 可视化组件 SDK 名册一致性。 */
class CardSdkTest {

    /** 这一批 v1400 新增的 34 种增强组件，必须全部登记进名册。 */
    private val NEW_TYPES = listOf(
        "keyvalue", "ring", "stackedbar", "scatter", "funnel", "candlestick",
        "boxplot", "speedometer", "sparkline", "searchbox", "poll", "checklist",
        "accordion", "groupedlist", "tree", "quote", "diff", "flow", "hierarchy",
        "contact", "product", "schedule", "filecard", "achievement", "weather",
        "map", "qrcode", "gallery", "terminal", "linklist", "pagination",
        "divider", "spacer",
    )

    @Test
    fun `lint 自检应为空`() {
        val issues = CardSdk.lint()
        if (issues.isNotEmpty()) {
            fail("名册自检不通过（共 ${issues.size} 条）：\n" + issues.joinToString("\n"))
        }
    }

    @Test
    fun `34 种增强组件全部登记`() {
        val missing = NEW_TYPES.filter { it !in CardSdk.types }
        assertEquals("缺登记：${missing.joinToString()}", emptyList<String>(), missing)
    }

    @Test
    fun `未知 type 落兜底卡而不是丢卡`() {
        val s = JSONObject("""{"type":"totally_unknown","title":"X","foo":1}""")
        val c = CardSdk.parseObj(s)
        assertNotNull(c)
        assertTrue(c is CustomCard)
        assertEquals("totally_unknown", (c as CustomCard).kind)
        assertTrue(c.payload.contains("totally_unknown"))
    }

    @Test
    fun `缺 type 返回 null`() {
        assertNull(CardSdk.parseObj(JSONObject("""{"title":"没有 type"}""")))
    }

    @Test
    fun `样例里必须带 type 键`() {
        // 名册条目漏写 "type":"xxx" 会让 lint 永远绿 —— 这里单独钉死
        val bad = CardSdk.catalog().filter { it.sampleJson.indexOf("\"type\"") < 0 }
        if (bad.isNotEmpty()) fail("样例缺 type：${bad.map { it.type }.joinToString()}")
        assertEquals(CardSdk.catalog().size, CardSdk.typeCount)
    }

    @Test
    fun `keyvalue 往返保持类型`() {
        val c = CardSdk.parse(
            """{"type":"keyvalue","title":"详情","items":[{"k":"版本","v":"1.1.3"},{"k":"大小","v":"385 MB","command":"run:ls -lh"}]}"""
        )
        assertTrue(c is KeyValueCard)
        val back = parseCard(serializeCard(c!!))
        assertTrue(back is KeyValueCard)
        assertEquals(2, (back as KeyValueCard).rows.size)
        assertEquals("1.1.3", back.rows[0].v)
        // command 能穿过编解码
        assertEquals("run:ls -lh", back.rows[1].command)
    }

    @Test
    fun `树形组件往返保持结构`() {
        val c = CardSdk.parse(
            """{"type":"tree","title":"依赖","expandedDepth":1,"nodes":[{"label":"root","value":"v1","children":[{"label":"child"}]}]}"""
        )
        assertNotNull(c)
        val back = parseCard(serializeCard(c!!))
        assertTrue(back is TreeCard)
        val t = back as TreeCard
        assertEquals(1, t.nodes.size)
        assertEquals("root", t.nodes[0].label)
        assertEquals(1, t.nodes[0].children.size)
        assertEquals("child", t.nodes[0].children[0].label)
    }

    @Test
    fun `未知组件往返也能保住`() {
        val c = CardSdk.parse("""{"type":"weird","title":"T","payload":"{}"}""")
        assertNotNull(c)
        val back = parseCard(serializeCard(c!!))
        assertNotNull(back)
        assertEquals(c::class, back!!::class)
    }
}
