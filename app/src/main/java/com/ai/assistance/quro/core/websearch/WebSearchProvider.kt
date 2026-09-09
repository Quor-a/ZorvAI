package com.ai.assistance.quro.core.websearch

import com.ai.assistance.quro.core.websearch.cache.MemoryCacheStore
import com.ai.assistance.quro.core.websearch.net.BingRssEngine
import com.ai.assistance.quro.core.websearch.net.DdgHtmlEngine
import com.ai.assistance.quro.core.websearch.net.GoogleNewsRssEngine
import com.ai.assistance.quro.core.websearch.net.SearXngEngine
import com.ai.assistance.quro.core.websearch.net.SearchEngine

/**
 * 联网能力编排单例：组装多引擎 + 缓存 + 配置，供 [WebSearchTool] 取用。
 *
 * 检索层采用多引擎并发 + 投票去重（任一引擎不可达都不影响整体）：
 * - Bing RSS（主力，天然结构化，无需解析 HTML）
 * - Google News RSS（时效类查询最佳源）
 * - DuckDuckGo HTML（兜底）
 * - SearXNG（自建元搜索，可选；baseUrl 为空则自动跳过）
 *
 * 查询改写（LlmCompleter）由 WebSearchTool 在每次调用时注入（需要宿主 Context 取模型配置），
 * 未注入时 Orchestrator 自动退化为规则式改写，联网能力依然可用。
 */
object WebSearchProvider {

    private val engines: List<SearchEngine> by lazy {
        listOf(
            BingRssEngine(),
            GoogleNewsRssEngine(),
            DdgHtmlEngine(),
            SearXngEngine(baseUrl = searxngUrl()),
        )
    }

    private val cache = MemoryCacheStore()

    fun build(completer: LlmCompleter?): WebSearchOrchestrator =
        WebSearchOrchestrator(
            engines = engines,
            cache = cache,
            completer = completer,
            config = WebSearchOrchestrator.Config(
                readTopK = 5,
                tokenBudget = 5000,
                perArticleCap = 1500,
                maxQueries = 2,
                searchTimeoutMs = 9_000,
            ),
        )

    /** 自建 SearXNG 实例地址（可选）：当前留空 → 该引擎自动跳过，不影响其余三引擎。 */
    private fun searxngUrl(): String = ""
}
