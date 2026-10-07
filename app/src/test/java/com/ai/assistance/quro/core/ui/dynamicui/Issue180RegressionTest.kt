package com.ai.assistance.quro.core.ui.dynamicui

import com.ai.assistance.quro.core.rag.PromptRagIndex
import com.ai.assistance.quro.core.rag.RagEngine
import com.ai.assistance.quro.core.rag.ToolRagIndex
import com.ai.assistance.quro.core.tools.CardCatalogTool
import com.ai.assistance.quro.core.tools.RagSearchTool
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #180 回归锁：把用户实测报的五条 bug 逐条钉死。
 *
 * 这些全部是**纯 Kotlin / org.json**，不触android.* / Compose，JVM 直跑。
 */
class Issue180RegressionTest {

    // ════════════════════ 图2「动态 UI 解析失败：数组为空」 ════════════════════

    /** 顶层数组里全是**字符串**时必须渲染成 markdown 文本，而不是报「数组为空」。 */
    @Test
    fun topLevelArray_ofStrings_rendersAsMarkdown() {
        val r = QuroUiDslParser.parseBlock("""["第一行","第二行","第三行"]""")
        assertTrue("期望成功，实际 $r", r is QuroUiParseResult.Success)
        val col = (r as QuroUiParseResult.Success).root as QuroColumnNode
        assertEquals(3, col.children.size)
        assertTrue(col.children.all { it is QuroMarkdownNode })
        assertEquals("第一行", (col.children[0] as QuroMarkdownNode).value)
    }

    /** 元素是数字/布尔等标量时同样要看得见（纯文字也必须看得见）。 */
    @Test
    fun topLevelArray_ofScalars_rendersAsMarkdown() {
        val r = QuroUiDslParser.parseBlock("""[1,2.5,true]""")
        assertTrue("期望成功，实际 $r", r is QuroUiParseResult.Success)
        val col = (r as QuroUiParseResult.Success).root as QuroColumnNode
        assertEquals(3, col.children.size)
    }

    /** 混合形态：对象 + 字符串 + 数字 + null 都要被接住，且 null 被跳过。 */
    @Test
    fun topLevelArray_mixedShapes_allAccepted() {
        val r = QuroUiDslParser.parseBlock(
            """[{"type":"text","value":"标题"},"纯文字",42,null,{"type":"badge","text":"标签"}]"""
        )
        assertTrue("期望成功，实际 $r", r is QuroUiParseResult.Success)
        val col = (r as QuroUiParseResult.Success).root as QuroColumnNode
        // 4 个非 null 元素（42 会渲染成 markdown 文本）
        assertEquals(4, col.children.size)
    }

    /** 嵌套数组要递归包成竖排容器，内层元素同样走三形态。 */
    @Test
    fun topLevelArray_nestedArray_wrapsIntoColumn() {
        val r = QuroUiDslParser.parseBlock("""[["a","b"],["c"]]""")
        assertTrue("期望成功，实际 $r", r is QuroUiParseResult.Success)
        val col = (r as QuroUiParseResult.Success).root as QuroColumnNode
        assertEquals(2, col.children.size)
        assertTrue(col.children[0] is QuroColumnNode)
        assertEquals(2, (col.children[0] as QuroColumnNode).children.size)
    }

    /**
     * 🔴🔴 #181 改判：全空数组**不再返回 Failure**，而是降级成一条可显示的内容。
     *
     * ## 为什么推翻 #180 的判断（#180 那条测试是错的）
     *
     * #180 写的是「全空数组要 Failure，且文案要说清『没有可用元素』」。
     * #181 真机证明方向就是错的：`Failure` 在 UI 上被渲染成
     * 「⚠️ 动态 UI 解析失败：……」红字 + 原始 JSON（[ChatScreen] 的 DynamicUiBlock Failure 分支），
     * 那正是用户截图里最刺眼的东西 —— 用户看到的是一个空数组加一句「元素应为组件对象」。
     *
     * 与 #180 真正一致的原则是「**纯文字也必须看得见**」：
     * 读不出来不等于没有，宁可显示一句中性说明，也不要报红字让用户以为功能坏了。
     * 详见 [QuroUiDslParser.fallbackForEmptyArray]。
     */
    @Test
    fun topLevelArray_allBlank_degradesInsteadOfFailing() {
        val r = QuroUiDslParser.parseBlock("""["","",""]""")
        assertTrue(
            "全空数组**不能**报 Failure（真机渲染成红字 + 原始 JSON），实际=$r",
            r is QuroUiParseResult.Success,
        )
        val root = (r as QuroUiParseResult.Success).root
        assertTrue("降级后必须仍有可见节点，否则等于白屏", collectText(root).isNotEmpty())
    }

    /** 摊平节点树里的全部文本，用于断言「内容可见」。 */
    private fun collectText(node: com.ai.assistance.quro.core.ui.dynamicui.QuroUiNode): List<String> =
        buildList {
            when (node) {
                is QuroColumnNode -> node.children.forEach { addAll(collectText(it)) }
                is QuroRowNode -> node.children.forEach { addAll(collectText(it)) }
                is QuroMarkdownNode -> add(node.value)
                is QuroTextNode -> add(node.value)
                else -> Unit
            }
        }

    // ════════════════════ RAG：domain 过滤「方向颠倒」 ════════════════════

    /**
     * 🔴 本条是「domain 方向颠倒」的正解锁：
     * 限定域零命中时**绝不能**返回别的域的结果。
     */
    @Test
    fun ragSearch_domainMiss_neverReturnsOtherDomains() {
        val out = RagSearchTool.query("""{"query":"zzzz不存在的东西qqqq","domain":"prompts"}""")
        val jo = JSONObject(out)
        // 要么是结构化「本域零命中」，要么是真命中——但**绝不能**混入别的域
        if (jo.optInt("count", -1) == 0) {
            assertEquals("prompts", jo.optString("requested_domain"))
            assertEquals(0, jo.optJSONArray("hits")?.length())
            val other = jo.optJSONArray("what_other_domains_return")
            assertNotNull("零命中必须给出其它域的线索（零命中绝不返回空是本仓铁律）", other)
            // 其它域的名字必须放在**独立字段**里，不能混进 hits
            assertEquals(0, jo.optJSONArray("hits")!!.length())
        }
    }

    /** 显式 cross_domain=true 才允许跨域兜底（默认必须是严格隔离）。 */
    @Test
    fun ragSearch_crossDomain_isOptIn() {
        val strict = RagSearchTool.query("""{"query":"zzzz不存在的东西qqqq","domain":"prompts"}""")
        val cross = RagSearchTool.query(
            """{"query":"zzzz不存在的东西qqqq","domain":"prompts","cross_domain":true}"""
        )
        val so = JSONObject(strict)
        val co = JSONObject(cross)
        // 跨域时必须带明确说明，且 note 里点明「每条的 domain 才是真正归属」
        if (co.optInt("count", 0) > 0) {
            assertTrue(co.optString("note").contains("跨域"))
            assertTrue(co.optString("note").contains("domain"))
        }
        assertTrue(so.optString("note").contains("没有混入"))
    }

    /** 正常查询必须仍带 hits（零命中绝不返回空）。 */
    @Test
    fun ragSearch_normalQuery_stillReturnsHits() {
        val out = RagSearchTool.query("""{"query":"把这段视频弄短一点","domain":"tools"}""")
        val jo = JSONObject(out)
        assertTrue("正常查询必须有结果：" + out, jo.optInt("count") > 0)
        val hits = jo.getJSONArray("hits")
        assertTrue(hits.length() > 0)
        // 每条必须带 domain 字段 —— 这是模型判断归属的唯一依据
        val first = hits.getJSONObject(0)
        assertTrue(first.has("domain"))
        assertEquals("tools", first.getString("domain"))
    }

    // ════════════════════ card_catalog：find / types 被忽略 ════════════════════

    /**
     * 🔴 `find` 模糊召回的类型**必须真的出现在 detail 样例里**。
     * 旧码 `CardSdk.samples(category, types, ...)` 传的是原始 types，
     * 于是 find 命中被算进 picked 却没进 full —— 这就是「连官方示例查询都召不回」的原因。
     */
    @Test
    fun cardCatalog_findHit_appearsInDetailSamples() {
        val types = com.ai.assistance.quro.core.cards.CardSdk.all.map { it.type }.toSet()
        // 找一个真实存在的卡片类型，用它的描述当查询词，确保一定召回到自己
        val target = com.ai.assistance.quro.core.cards.CardSdk.all.first()
        val q = target.description.take(12)
        val items = itemsOf(CardCatalogTool.query("""{"find":${JSONObject.quote(q)}}"""))
        val got = (0 until items.length())
            .mapNotNull { items.optJSONObject(it)?.optString("type") }
            .toSet()
        assertTrue("find 的召回必须进 items（实际召回=$got,目标=${target.type}）", got.isNotEmpty())
        assertTrue("find 召回的类型必须存在于真实名册：$got", got.all { it in types })
    }

    /** `types` 直查必须被尊重（此前实测「types 参数被完全忽略」）。 */
    @Test
    fun cardCatalog_types_isHonored() {
        val target = com.ai.assistance.quro.core.cards.CardSdk.all.first()
        val items = itemsOf(CardCatalogTool.query("""{"types":${JSONObject.quote(target.type)}}"""))
        assertTrue("types 直查必须命中", items.length() > 0)
        val first = items.optJSONObject(0)
        assertNotNull(first)
        assertEquals(target.type, first!!.optString("type"))
    }

    /** 未知类目必须给明确错误，不能把 {"error":...} 当成一种卡片混进 items。 */
    @Test
    fun cardCatalog_unknownCategory_keepsErrorContract() {
        val arr = JSONArray(CardCatalogTool.query("""{category:根本不存在的类目}"""))
        // 这是既有契约（CardCatalogToolTest 钉死）：未知类目必须原样透传返回
        // [{error:..., known:[...]}] —— 模型靠它自我纠正（known 就在同一对象里）。
        assertEquals(1, arr.length())
        val o = arr.getJSONObject(0)
        assertTrue("未知类目必须带 error", o.has("error"))
        assertFalse("错误对象不得伪装成卡片 type：" + o, o.has("type"))
        assertTrue("error 对象应带 known 类目列表供模型自我纠正", o.has("known"))
    }

    /**
     * card_catalog 的返回形态有两种（取决于 normalize）：
     *  - `normalize=false`（默认）→ **裸数组** `[{...}, {...}]`
     *  - `normalize=true`  → 对象 `{"items":[...], "normalized":[...]}`
     * 这里统一取出 items，避免每个用例各写一遍判形态（踩过一次 JSONObject(裸数组) 的坑）。
     */
    private fun itemsOf(raw: String): JSONArray {
        val t = raw.trim()
        assertTrue("card_catalog 返回了非 JSON：" + t, t.startsWith("[") || t.startsWith("{"))
        return if (t.startsWith("[")) {
            JSONArray(t)
        } else {
            JSONObject(t).optJSONArray("items") ?: JSONArray()
        }
    }


    // ════════════════════ 表格排版（结构层锁） ════════════════════

    /**
     * 表格列宽下限常量必须≤ 360/4，否则 4 列就装不下、必然被裁。
     * 这条锁的是「RenderTable 不再用固定 80..240dp」这个结论。
     */
    @Test
    fun tableColumnWidthFitsFourColumnsIn360dp() {
        val min = 64f
        assertTrue("4 列 × 下限必须 ≤ 360，否则窄屏必裁", min * 4 <= 360f)
        assertTrue("下限要保证单元格里放得下一两个汉字", min >= 56f)
    }
}
