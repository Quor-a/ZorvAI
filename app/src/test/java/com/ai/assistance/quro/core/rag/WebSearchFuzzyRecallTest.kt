package com.ai.assistance.quro.core.rag

import com.ai.assistance.quro.core.tools.QuroToolRouter
import com.ai.assistance.quro.core.tools.buildQuroRegistry
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 任务 #177回归：**web_search 支持模糊搜索**。
 *
 * ## 用户诉求
 *
 * 「web_search升级可以模糊搜索」。
 *
 * ## 缺口的性质
 *
 * 改`RagConcept.CAPACITIES` 之前，web_search 的触发词**全是手段型**：
 * 联网/上网/网上/查资料/找资料/搜一下/查一查/搜一搜/百度/google/新闻/资讯/八卦/热点/调研。
 *
 * 这批词有个共同前提：用户得**说出「上网查」这个手段**。但真实口语里大量查询
 * **只说效果、不说手段**——「这玩意现在多少钱」「外面是怎么说的」「帮我打听一下」
 * 「最近有什么新说法」。这些句子字面上与「搜索」零重叠，触发词表命中不了。
 *
 * ## 为什么用「进 top3」而不是「排第一」当判据
 *
 * 这些是**效果型**查询，本身不含工具名字面量，同域内必然有别的工具也沾边
 * （如「多少钱」会沾到 `http_request`）。要求排第一过严，会逼着人调分数权重，
 * 那正是RAG 反复出问题的地方。判据取「期望工具进 top3」——
 * 与本仓既有的 [AgentRagRecallTest.模糊查询能命中正确工具] 完全同一口径。
 */
class WebSearchFuzzyRecallTest {

    private lateinit var engine: RagEngine

    @Before
    fun setUp() {
        engine = RagEngine()
        // 🔴 关键：必须装真实工具索引，否则 web_search 这类动态工具不在索引里，
        // search 搜不到它 → 下面所有断言会假失败（不是引擎的锅，是我把索引漏了）。
        // 纪律与 AgentRagRecallTest 一致：用真实注册表，不用假数据。
        ToolRagIndex.install(engine, QuroToolRouter(buildQuroRegistry(null).specs()).allSpecsSnapshot())
    }

    /**
     * 效果型口语探针。
     *
     * 🔴 期望值 `web_search` 取自**真实工具注册表**（与既有AgentRagRecallTest 同源纪律），
     * 不是凭印象编的。
     */
    private val effectStyleProbes = listOf(
        "这玩意现在多少钱" to "只说效果：问价，不说「搜」",
        "帮我打听一下这个牌子" to "只说效果：打听，不说「搜」",
        "外面是怎么说的" to "只说效果：口碑，不说「搜」",
        "最近有什么新说法" to "只说效果：追新，不说「搜」",
        "有没有这回事是真的吗" to "只说效果：查证，不说「搜」",
        "求推荐一款" to "只说效果：求推荐，不说「搜」",
    )

    @Test
    fun 效果型口语能召回web_search() {
        val failures = ArrayList<String>()
        for ((query, why) in effectStyleProbes) {
            val hits = engine.search(query, ToolRagIndex.DOMAIN, 10)
            if (hits.isEmpty()) {
                failures += "[$why]「$query」→ 零命中"
                continue
            }
            val top = hits.take(3).map { it.id }
            if (top.none { it == "web_search" }) {
                failures += "[$why] 「$query」→ 期望 web_search 进 top3，实际 top3=$top"
            }
        }
        assertTrue(
            "效果型口语未召回 web_search：\n" + failures.joinToString("\n"),
            failures.isEmpty()
        )
    }

    /**
     * 手段型口语**不能被改坏**。
     *
     * 加词表是「加」不是「换」——原来能命中的必须还能命中。
     * 这条是防止后来者为了压缩词表把手段型词删掉。
     */
    @Test
    fun 手段型口语仍然能召回web_search() {
        val probes = listOf("上网查一下最近的资料", "帮我搜一下这个主题", "查资料", "百度一下")
        val failures = ArrayList<String>()
        for (q in probes) {
            val hits = engine.search(q, ToolRagIndex.DOMAIN, 10)
            val top = hits.take(3).map { it.id }
            if (top.none { it == "web_search" }) {
                failures += "「$q」→ 期望 web_search 进 top3，实际 top3=$top"
            }
        }
        assertTrue(
            "手段型口语回归（加词表不能破坏原有召回）：\n" + failures.joinToString("\n"),
            failures.isEmpty()
        )
    }

    /**
     * 效果型词表不能把**别的工具**挤掉。
     *
     * 🔴 这是本次改动的真实风险：`价格`/`多少钱` 这类词泛化面很宽，
     * 若权重没控住，会把 `build_apk`（打包）、`http_request`（接口）等
     * 跟价格毫无关系的工具拉进结果。判据：效果型查询的 top1 必须仍是 web_search
     * 或至少 top3 里不能出现明显的离类工具。
     */
    @Test
    fun 效果型词不会挤掉不相关工具() {
        // 「打包」「出apk」是 build_apk 的强触发词，效果型词若权重失控会污染它
        val hits = engine.search("帮我打包一个 apk", ToolRagIndex.DOMAIN, 5)
        assertTrue("打包类查询不应零命中（本仓既有决策：零命中绝不返回空）", hits.isNotEmpty())
        val top = hits.take(3).map { it.id }
        assertTrue(
            "打包类查询的 top3 被联网类词污染了：$top",
            top.none { it == "web_search" },
        )
    }

    /** 名册自检：web_search 这个工具名必须真实存在，否则上面所有断言都是自欺欺人。 */
    @Test
    fun 期望的工具名真实存在() {
        val specs = QuroToolRouter(buildQuroRegistry(null).specs()).allSpecsSnapshot()
        val names = specs.map { it.name }
        assertTrue(
            "注册表里没有 web_search，探针期望值是编的：$names",
            names.contains("web_search"),
        )
    }
}