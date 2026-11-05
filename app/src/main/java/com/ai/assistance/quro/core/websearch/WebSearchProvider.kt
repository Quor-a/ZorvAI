package com.ai.assistance.quro.core.websearch

import com.ai.assistance.quro.core.websearch.cache.MemoryCacheStore
import com.ai.assistance.quro.core.websearch.net.BaiduEngine
import com.ai.assistance.quro.core.websearch.net.BingEngine
import com.ai.assistance.quro.core.websearch.net.BotHubEngine
import com.ai.assistance.quro.core.websearch.net.BraveEngine
import com.ai.assistance.quro.core.websearch.net.DdgHtmlEngine
import com.ai.assistance.quro.core.websearch.net.GoogleNewsRssEngine
import com.ai.assistance.quro.core.websearch.net.KagiEngine
import com.ai.assistance.quro.core.websearch.net.SearXngEngine
import com.ai.assistance.quro.core.websearch.net.SerperEngine
import com.ai.assistance.quro.core.websearch.net.SearchEngine
import com.ai.assistance.quro.core.websearch.net.TavilyEngine
import com.ai.assistance.quro.core.websearch.net.WebSearchKeys

/**
 * 联网能力编排单例：组装多引擎 + 缓存 + 配置，供 [WebSearchTool] 取用。
 *
 * 检索层采用多引擎并发 + 投票去重（任一引擎不可达都不影响整体）：
 * - BingEngine（主力，RSS 优先 / HTML 兜底双通道，可用性最高）
 * - BaiduEngine（中文原生索引，无需 key、默认开启，解单点 Bing 依赖）
 * - BraveEngine（独立商业索引，API key 驱动；未配置 key 时自动跳过，数据不出设备）
 * - KagiEngine / SerperEngine / TavilyEngine（可选商业搜索 API，配 key 后生效）
 * - Google News RSS（时效类查询最佳源，境内不可达时自动熔断跳过）
 * - DuckDuckGo HTML（兜底，境内不可达时自动熔断跳过）
 * - SearXNG（自建元搜索，可选；baseUrl 为空则自动跳过）
 *
 * 不可达引擎由 EngineHealth 熔断（连续失败 3 次进入 10 分钟冷却），避免每次请求被其超时拖慢。
 *
 * 配置由 [WebSearchKeys] 提供（联网总开关 / 各 API key / SearXNG 地址 / 结果条数），
 * 在「设置 → 搜索服务」页可改。未配置 key 的引擎自动跳过，不影响免 key 的引擎。
 *
 * 查询改写（LlmCompleter）由 WebSearchTool 在每次调用时注入（需要宿主 Context 取模型配置），
 * 未注入时 Orchestrator 自动退化为规则式改写，联网能力依然可用。
 */
object WebSearchProvider {

    private fun engines(): List<SearchEngine> =
        listOf(
            BingEngine(),
            BaiduEngine(),
            BraveEngine(),
            KagiEngine(),
            SerperEngine(),
            TavilyEngine(),
            GoogleNewsRssEngine(),
            DdgHtmlEngine(),
            SearXngEngine(baseUrl = WebSearchKeys.searxngUrl),
            BotHubEngine(),
        )

    private val cache = MemoryCacheStore()

    fun build(completer: LlmCompleter?): WebSearchOrchestrator =
        WebSearchOrchestrator(
            engines = engines(),
            cache = cache,
            completer = completer,
            config = WebSearchOrchestrator.Config(
                readTopK = WebSearchKeys.resultCount,
                tokenBudget = 5000,
                perArticleCap = 1500,
                maxQueries = 3,
                searchTimeoutMs = 9_000,
            ),
        )

    /** 是否启用联网（供调用方快速判断；关闭时 WebSearchTool 直接返回未启用提示）。 */
    fun enabled(): Boolean = WebSearchKeys.webSearchEnabled
}
