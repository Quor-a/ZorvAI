package com.ai.assistance.quro.core.tools

import com.ai.assistance.quro.core.cards.CardFence
import com.ai.assistance.quro.core.cards.CardSdk
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `card_catalog` 工具自检。
 *
 * 背景：组件名册扩到 92 种后，模型看不到清单（工具描述里是手写的旧清单，停在 v1068 的
 * 四十来种），只能靠围栏撞样例去猜。这里补两级暴露：常驻紧凑清单（进 ui_widget /
 * ui_card 描述）+ 本工具按需拉完整样例。
 *
 * 测的是 [CardCatalogTool.query] 这个纯函数入口而非 run —— 工程没引 Robolectric，
 * 而 run 的 context 形参非空，字节码插桩的 checkNotNullParameter 连 unchecked cast
 * 都拦得住，强行造 Context 反而测不到逻辑。
 */
class CardCatalogToolTest {

    @Test
    fun `parametersJson 是合法 JSON`() {
        val o = JSONObject(CardCatalogTool().parametersJson)
        assertEquals("schema 根必须是 object", "object", o.getString("type"))
        assertTrue("schema 必须有 properties", o.has("properties"))
        assertTrue("应声明 types 数组参数", o.getJSONObject("properties").has("types"))
        assertTrue("应声明 category 参数", o.getJSONObject("properties").has("category"))
        // 这条是回归：字符串拼 JSON 时 properties 多闭过一层花括号，
        // 编译与运行都不报，只有工具真正下发、上游解析 schema 时才炸
        assertTrue("properties 必须自洽闭合", o.getJSONObject("properties").has("category"))
    }

    @Test
    fun `description 含组件总数与拉取指引`() {
        val d = CardCatalogTool().description
        assertTrue("描述里应写明组件总数", d.contains(CardSdk.typeCount.toString()))
        assertTrue("描述里应指引模型用 card_catalog 拉样例", d.contains("card_catalog"))
    }

    @Test
    fun `工具名与只读标记正确`() {
        val t = CardCatalogTool()
        assertEquals("card_catalog", t.name)
        assertTrue("查询工具应标只读，才能进并发快路径", t.readOnly)
    }

    @Test
    fun `按类目查询返回该类全部样例`() {
        val arr = JSONArray(CardCatalogTool.query("""{"category":"aiwrite"}"""))
        assertEquals(CardSdk.byCategory["aiwrite"]!!.size, arr.length())
        for (i in 0 until arr.length()) {
            assertEquals("aiwrite", arr.getJSONObject(i).getString("category"))
        }
    }

    @Test
    fun `detail false 时不返回样例只回说明`() {
        val arr = JSONArray(CardCatalogTool.query("""{"category":"input","detail":false}"""))
        assertEquals(CardSdk.byCategory["input"]!!.size, arr.length())
        val first = arr.getJSONObject(0)
        assertTrue(first.has("description"))
        assertFalse("省 token 模式不该回完整样例", first.has("sample"))
    }

    @Test
    fun `detail true 时回完整可下发样例`() {
        val arr = JSONArray(CardCatalogTool.query("""{"types":["gantt"]}"""))
        assertEquals(1, arr.length())
        val sample = JSONObject(arr.getJSONObject(0).getString("sample"))
        // 样例必须真能构造出卡片，否则等于给了模型一份废说明书
        assertEquals("gantt", sample.optString("type"))
        assertTrue(CardSdk.parseObj(sample) != null)
    }

    @Test
    fun `normalize 顺带回别名归一化`() {
        val o = JSONObject(CardCatalogTool.query("""{"types":["GanttChart","AwesomeBox"],"normalize":true}"""))
        val norm = o.getJSONArray("normalized")
        assertEquals(2, norm.length())
        // 名册认识的归一化到真 type；不认识的落 custom，不伪造下游认不出的名
        assertEquals("gantt", norm.getJSONObject(0).getString("type"))
        assertTrue(norm.getJSONObject(0).getBoolean("known"))
        assertEquals("custom", norm.getJSONObject(1).getString("type"))
        assertFalse(norm.getJSONObject(1).getBoolean("known"))
    }

    @Test
    fun `normalize 归一化结果与围栏解析同源`() {
        // 同一份输入，card_catalog 说的 type 必须就是围栏解析真会用的 type
        listOf("GanttChart", "QRCode", "TextInput").forEach { raw ->
            val o = JSONObject(CardCatalogTool.query("""{"types":["$raw"],"normalize":true}"""))
            val said = o.getJSONArray("normalized").getJSONObject(0).getString("type")
            assertEquals("card_catalog 与 CardFence 对 $raw 的归一化不一致", CardFence.normalizeType(raw), said)
        }
    }

    @Test
    fun `空参数等价全量查询不抛异常`() {
        assertTrue("空参数应回全量样例", JSONArray(CardCatalogTool.query("{}")).length() > 0)
    }

    @Test
    fun `非法 JSON 参数不抛异常`() {
        val out = CardCatalogTool.query("这不是 JSON")
        assertTrue("应降级成可用输出而不是抛栈", out.isNotEmpty())
    }

    @Test
    fun `未知类目回可纠正的错误`() {
        val arr = JSONArray(CardCatalogTool.query("""{"category":"nosuch"}"""))
        assertEquals(1, arr.length())
        assertTrue(arr.getJSONObject(0).has("error"))
    }
}