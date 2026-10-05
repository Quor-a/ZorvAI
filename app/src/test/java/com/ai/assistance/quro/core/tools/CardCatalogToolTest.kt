package com.ai.assistance.quro.core.tools

import com.ai.assistance.quro.core.cards.CardFence
import com.ai.assistance.quro.core.cards.CardPatch
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

    // ── fence=true：围栏语法说明（属性是组级语义，不会出现在组件目录里）──

    @Test
    fun `fence 模式回 4 种围栏头与属性说明`() {
        val o = JSONObject(CardCatalogTool.query("""{"fence":true}"""))
        val fences = o.getJSONArray("fences")
        assertEquals(4, fences.length())
        CardFence.ALL_FENCES.forEach { f ->
            assertTrue("围栏头 $f 应在说明里", fences.toString().contains(f))
        }
        assertTrue("应有开关说明", o.getJSONArray("switches").toString().contains("compact"))
        val va = o.getJSONArray("valueAttrs").toString()
        assertTrue("应有 title 属性", va.contains("title"))
        assertTrue("应有 theme 属性", va.contains("theme"))
    }

    @Test
    fun `fence 模式的主题白名单与实现同源`() {
        // 白名单写死就会出现「工具说的档位和解析器认的不是一套」，
        // 而这种不一致只在模型真按错误文案下发时才暴露
        val o = JSONObject(CardCatalogTool.query("""{"fence":true}"""))
        val allowed = o.getJSONArray("valueAttrs").toString()
        CardFence.THEME_PRESETS.forEach { t -> assertTrue("主题档位 $t 应在白名单里", allowed.contains(t)) }
    }

    @Test
    fun `fence 模式不混入组件目录`() {
        // 混进 items 会让模型在一堆组件里找围栏语法
        val raw = CardCatalogTool.query("""{"fence":true}""")
        assertFalse("不应回 items 数组", JSONObject(raw).has("items"))
        assertFalse("不该出现组件样例", raw.contains("\"chart_type\""))
    }

    // ═══════════════ patch 模式 ═══════════════

    @Test
    fun `patch 模式返回补丁语法`() {
        val o = JSONObject(CardCatalogTool.query("""{"patch":true}"""))
        val ops = o.getJSONArray("ops")
        assertEquals(CardPatch.OPS.size, ops.length())
        // 与实现同源：名册/操作表改了这里自动跟着变，不会说一套做一套
        CardPatch.OPS.forEach { assertTrue("操作 $it 应在返回里", ops.toString().contains("\"$it\"")) }
    }

    @Test
    fun `patch 模式说明保护键与原子性`() {
        val raw = CardCatalogTool.query("""{"patch":true}""")
        assertTrue("必须说明 id 不可改：$raw", raw.contains("id"))
        assertTrue("必须说明原子性：$raw", raw.contains("原子") || raw.contains("全成功"))
    }

    @Test
    fun `patch 模式不混入组件目录`() {
        val raw = CardCatalogTool.query("""{"patch":true}""")
        assertFalse("不应回 items 数组", JSONObject(raw).has("items"))
        assertFalse("不该出现组件样例", raw.contains("\"chart_type\""))
    }

    @Test
    fun `patch 模式与 fence 模式互不干扰`() {
        val a = CardCatalogTool.query("""{"patch":true}""")
        val b = CardCatalogTool.query("""{"fence":true}""")
        assertTrue("patch 应讲补丁", a.contains("JSON Pointer"))
        assertTrue("fence 应讲围栏", b.contains("围栏"))
        assertFalse("两者不该串味：patch=$a", a.contains("围栏头"))
    }
}
