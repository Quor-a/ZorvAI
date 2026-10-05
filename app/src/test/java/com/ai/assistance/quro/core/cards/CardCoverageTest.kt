package com.ai.assistance.quro.core.cards

import com.ai.assistance.quro.core.tools.CardCatalogTool
import com.ai.assistance.quro.core.tools.CardPatchTool
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「模型能不能写出全部可视化组件」的**端到端自检**。
 *
 * ## 为什么要有这个测试
 *
 * 名册扩到 101 种后，「AI 到底会不会用这 101 种」无法靠人眼核对工具描述确认 ——
 * 那段描述三千多字，任何一处与名册脱节都只表现为「AI 静默画不出来」，
 * 没有报错、没有日志、用户只看到「AI 好像不知道有这种卡片」。
 *
 * 这里把这件事拆成可断言的三段，每段都对应一个真实的失效点：
 *
 * 1. **清单完整性**：[compactCatalog] 必须列全名册全部 type。
 *    失效表现：名册加了组件但清单没同步 → 新组件只对「碰巧在别处见过样例」的模型有效。
 * 2. **样例可构造**：[samples] 里的每份样例都要能真的构造成卡片。
 *    失效表现：样例字段名写错 → 模型照抄 → 渲染成兜底卡（CustomCard）。
 * 3. **样例可 patch**：[card_patch] 的 describe 能在样例卡上列出路径。
 *    失效表现：卡片下发后改不动 → 增量更新能力对该组件无效。
 */
class CardCoverageTest {

    /** 1) 紧凑清单必须列全名册全部 type —— 与实现同源，缺一个就报出来。 */
    @Test
    fun `紧凑清单列全名册全部组件`() {
        val catalog = CardSdk.compactCatalog()
        val missing = CardSdk.types.filter { !catalog.contains(it) }
        assertTrue(
            "紧凑清单漏了 ${missing.size} 种组件（AI 将看不见它们）：${missing.take(20)}",
            missing.isEmpty()
        )
    }

    /** 清单里不该出现名册外的幽灵 type（否则模型会照着不存在的名字写）。 */
    @Test
    fun `紧凑清单无幽灵组件`() {
        val catalog = CardSdk.compactCatalog()
        val known = CardSdk.types.toSet()
        val listed = Regex("[a-z][a-z0-9_]*").findAll(catalog).map { it.value }.toSet()
        // 类目名本身（input/data/…）不是 type，用 byCategory 的键集排除
        val ghost = listed - known - CardSdk.byCategory.keys
        assertTrue("紧凑清单出现名册外的类型：$ghost", ghost.isEmpty())
    }

    /** 2) 每种组件的样例都要能构造成**它自己**（不是兜底卡）。 */
    @Test
    fun `全部组件样例都能构造且不退化成兜底卡`() {
        val bad = ArrayList<String>()
        CardSdk.all.forEach { spec ->
            val built = runCatching { spec.builder?.invoke(JSONObject(spec.sample)) }.getOrNull()
            if (built == null) {
                bad += "${spec.type}：样例无法构造"
                return@forEach
            }
            // parseObj 走的是「builder 失败就落兜底卡」的路，
            // 所以要验的是「builder 本身成功」，否则样例字段名错了也看不出来
            if (built is CustomCard) bad += "${spec.type}：样例退化成了兜底卡（字段名与 builder 不符）"
        }
        assertTrue("有 ${bad.size} 种组件的样例不能用：\n" + bad.joinToString("\n"), bad.isEmpty())
    }

    /** 3) 每种组件下发后都要能列出可 patch 的路径（否则增量更新对它无效）。 */
    @Test
    fun `全部组件都能被增量更新`() {
        val bad = ArrayList<String>()
        CardSdk.all.forEach { spec ->
            val c = spec.builder?.let { runCatching { it(JSONObject(spec.sample)) }.getOrNull() } ?: return@forEach
            val paths = runCatching { CardPatch.describe(c) }.getOrElse { emptyList() }
            if (paths.isEmpty()) bad += "${spec.type}：describe 列不出任何路径（补丁对它无效）"
        }
        assertTrue("有 ${bad.size} 种组件无法被 patch：\n" + bad.joinToString("\n"), bad.isEmpty())
    }

    /** 工具描述里必须真的带上了清单 —— 清单再好，没进描述等于不存在。 */
    @Test
    fun `两个下发工具的描述都带清单与补丁指引`() {
        val widget = com.ai.assistance.quro.core.tools.UiWidgetTool().description
        val card = com.ai.assistance.quro.core.tools.UiCardTool().description
        listOf("ui_widget" to widget, "ui_card" to card).forEach { (name, d) ->
            assertTrue("$name 描述里应有紧凑清单", d.contains("input(") || d.contains("共 ${CardSdk.typeCount} 种"))
            assertTrue("$name 描述里应提到 card_catalog（按需拉样例）", d.contains("card_catalog"))
            assertTrue("$name 描述里应提到 card_patch（增量更新）", d.contains("card_patch"))
        }
    }

    /**
     * card_catalog 的三种按需模式都要能取到东西，否则「按需」是空头承诺。
     *
     * 顶层形态随参数变（[CardCatalogTool.query] 的既有约定）：
     *  - `detail` + 不带 normalize → 顶层是**数组**（摊平后的 items）
     *  - `normalize=true` → 才包成 `{"items":…,"normalized":…}` 对象
     * 这里两种都断言，是为了让「形态变了」在测试里显形 ——
     * 模型是按 `items[i]` 取对象的，哪天多包一层数组它就会拿到 JSONArray 而静默画错。
     */
    @Test
    fun `card_catalog 各按需模式都能取到内容`() {
        val detailRaw = CardCatalogTool.query("""{"detail":true}""")
        val detailArr = JSONArray(detailRaw)
        assertTrue("detail 模式应返回摊平数组，实际长度 ${detailArr.length()}", detailArr.length() > 0)
        assertTrue(
            "detail 模式数组元素应是对象（模型按 items[i].type 取）",
            detailArr.opt(0) is JSONObject
        )

        val normObj = JSONObject(CardCatalogTool.query("""{"normalize":true,"types":["GanttChart"]}"""))
        assertTrue("normalize 模式应返回对象", normObj.has("items"))
        assertTrue(
            "normalize 模式应带回归一化结果",
            normObj.optJSONArray("normalized")?.length() == 1
        )

        assertTrue(CardCatalogTool.query("""{"fence":true}""").contains("围栏"))
        assertTrue(CardCatalogTool.query("""{"patch":true}""").contains("JSON Pointer"))
    }

    /** 工具层：没有宿主时不谎报成功（静默失效的经典形态）。 */
    @Test
    fun `card_patch 无宿主时不谎报成功`() {
        val out = CardPatchTool.handle("""{"cardId":"x","patches":[{"op":"set","path":"/a","value":1}]}""")
        assertTrue("应说明没有界面宿主：$out", out.contains("没有界面宿主"))
    }
}
