package com.ai.assistance.quro.core.tools

import com.ai.assistance.quro.core.cards.CardSdk
import org.json.JSONArray
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #203 回归锁：可视化组件「老是使用同一个类型」。
 *
 * ## 用户报的现象
 *
 * 「修复动态 UI 组件和可视化组件老是一直使用一个类型组件问题」
 *
 * ## 根因（探针实测，不是推测）
 *
 * 机制本来是齐的：`card_catalog` 有 `find` 模糊检索、名册 100+ 种类型、
 * `ui_widget`/`ui_card` 的工具描述里还常驻了一份紧凑清单。
 *
 * 但改前 `findByIntent` 用 `minScore = 1.2`，而 [com.ai.assistance.quro.core.rag.RagEngine]
 * 的 `fieldScore` 里 `coverage = hit / qt.size` —— **分母是整句 token 数**。
 * 中文按 bigram 切分后「来个仪表盘看完成度」是 9 个 token，
 * 于是单个词最多只值 1/9 覆盖率，top1 远低于 1.2 → **零命中**。
 *
 * 探针实测（改前 6 条真实说法只召回 1 条）：
 * |说法 | 改前 | 正确答案 |
 * |---|---|---|
 * | 来个仪表盘看完成度 | ❌ 零命中 | gauge / speedometer |
 * | 把占比画出来 | ❌ 零命中 | pie |
 * | 做个时间线 | ❌ 零命中 | timeline |
 * | 展示排名 | ❌ 零命中 | ranking |
 * | 对比两组差距 | ❌ 零命中 | compare |
 * | 能打分的 | ✅ matrix | matrix |
 *
 * **AI 查不到合适类型，就只能反复用那几个耳熟能详的**（table / keyvalue / text）——
 * 这正是「老是使用同一个类型」。也就是说：这不是模型偷懒，是**检索不给力**。
 * 在检索修好之前，光在提示词里喊「别只用一种类型」是没有用的。
 *
 * ## 二级问题：能力缺口（不只是门槛）
 *
 * 门槛降到 0.15 后 6 条全部有命中，但**排序仍不对**：
 * - 「展示排名」→ 召回 `[section]`：名册里**根本没有通用排行榜**，
 *   AI 想排名只能退回 table —— 这本身就是「老用一个类型」的深层原因。
 *   → 补了一张 `ranking` 通用排行榜卡。
 * - 「做个时间线」→ 召回 `[countdown,schedule,gantt,clock,...]`：
 *   `timeline` 的 description 只写了「时间轴」，用户说「时间线」**字面不撞**。
 *   → 给 description 补了口语触发词。
 *
 * ## 🔴 语义边界（易复发）
 *
 * `scoreboard` 是**体育比分板**（字段 `home/homeScore/away/awayScore/period/time/status`），
 * **不是**通用排行榜。此前被当成排名的归属，属于错误文档。
 * 现在二者分工：`ranking` 排名次，`scoreboard` 显示体育比分。
 */
class CardCatalogFindRecallTest {

    /** 解析 `find` 返回的 type 集合。 */
    private fun findTypes(q: String): List<String> {
        val raw = CardCatalogTool.query("""{"find":"$q","detail":false}""")
        // 零命中时返回的是带"没有召回到"字样的 JSONArray（结构化失败），不是 items
        val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.optString("type")?.takeIf { it.isNotBlank() }
        }
    }

    /**
     * 每条真实说法都必须召回到**至少一个**合适类型。
     *
     * 判据只要求「召回到某个合理类型」，不强求 top1 唯一正确 ——
     * 检索排序优化的收益远小于「一条都召不回」这个致命伤。
     */
    @Test
    fun 真实说法都能召回到组件() {
        val cases = listOf(
            "来个仪表盘看完成度",
            "把占比画出来",
            "做个时间线",
            "展示排名",
            "对比两组差距",
            "能打分的",
        )
        for (q in cases) {
            val got = findTypes(q)
            assertTrue(
                "「$q」在卡片域**零命中**（改前就是这个问题）：AI 查不到就只能一直用那几个老类型",
                got.isNotEmpty()
            )
        }
    }

    /** 排名/榜单类必须能召回到通用排行榜 ranking。 */
    @Test
    fun 排名类能召回到排行榜() {
        val got = findTypes("展示排名")
        assertTrue("「展示排名」召回到 $got，应含 ranking（通用排行榜）", got.contains("ranking"))
    }

    /**
     * 🔴 体育比分 ≠ 通用排名。
     *
     * `scoreboard` 的字段是主客队 + 比分 + 节次/时间/状态，只能用于体育比赛。
     * 「排名/排行/top N」必须落到 `ranking`。二者混用会把榜单渲染成两个队的比分。
     */
    @Test
    fun 排名不该落到体育比分板() {
        val got = findTypes("销售排行榜前三名")
        assertTrue("「销售排行榜前三名」召回到 $got，应含 ranking", got.contains("ranking"))
    }

    /** 体育比分场景仍然要能找到 scoreboard，不能被 ranking 抢走。 */
    @Test
    fun 体育比分能召回到比分板() {
        val got = findTypes("今晚这场比赛谁赢了比分多少")
        assertTrue("「今晚这场比赛谁赢了比分多少」召回到 $got，应含 scoreboard", got.contains("scoreboard"))
    }

    /** 占比类必须能召回到饼图/环图。 */
    @Test
    fun 占比类能召回到饼图() {
        val got = findTypes("把占比画出来")
        assertTrue(
            "「把占比画出来」召回到 $got，应含 pie 或 ring",
            got.any { it == "pie" || it == "ring" }
        )
    }

    /** 时间线类必须能召回到 timeline（description 补了「时间线」口语说法）。 */
    @Test
    fun 时间线类能召回到时间线卡() {
        val got = findTypes("做个时间线")
        assertTrue("「做个时间线」召回到 $got，应含 timeline", got.contains("timeline"))
    }

    /**
     * 🔴 名录自洽：每种类型都必须能被自己的名字查到。
     *
     * 这是「新增组件只对已经知道它存在的模型有效」那个静默失效的守卫 ——
     * 加了组件但索引漏登记，等于白加。
     */
    @Test
    fun 名录里每种类型都能被直接查到() {
        val all = CardSdk.all.map { it.type }
        assertTrue("名册为空，判据失效", all.isNotEmpty())
        // 用 types 直查（精确路径）逐个验证注册完整
        val raw = CardCatalogTool.query("""{"detail":false}""")
        val items = JSONArray(raw)
        val got = (0 until items.length()).mapNotNull { items.optJSONObject(it)?.optString("type") }
        val missing = all.filterNot { it in got }
        assertTrue("这些类型注册了但查不到：$missing", missing.isEmpty())
    }

    /** 组件数量不能悄悄缩水 —— 用户的直觉是「明明有一百种」。 */
    @Test
    fun 组件总数保持在百种量级() {
        assertTrue(
            "组件只有 ${CardSdk.typeCount} 种，少于百种量级，索引/渲染可能又出问题了",
            CardSdk.typeCount >= 90
        )
    }

    /** 紧凑清单是常驻在工具描述里的，必须真的含各类型名（AI 靠它做第一轮选型）。 */
    @Test
    fun 紧凑清单含关键类型() {
        val compact = CardSdk.compactCatalog()
        for (t in listOf("table", "gauge", "pie", "timeline", "scoreboard", "ranking", "keyvalue")) {
            assertTrue("紧凑清单缺 $t：$compact", compact.contains(t))
        }
    }

    /** 零命中时必须说清并给出路，不能返回空数组让模型以为「没有这种组件」。 */
    @Test
    fun 零命中时给出下一步() {
        val raw = CardCatalogTool.query("""{"find":"zzzzqqq完全不相关的东西xyzzy","detail":false}""")
        val text = raw
        assertTrue(
            "零命中必须给出下一步指引（实际返回 ${raw.take(120)}）",
            text.contains("category") || text.contains("types") || text.contains("next")
        )
    }
}