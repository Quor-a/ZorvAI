package com.ai.assistance.quro.core.websearch

import com.ai.assistance.quro.core.websearch.cache.CacheStore
import com.ai.assistance.quro.core.websearch.cache.MemoryCacheStore
import com.ai.assistance.quro.core.websearch.html.DensityExtractor
import com.ai.assistance.quro.core.websearch.html.Markdownizer
import com.ai.assistance.quro.core.websearch.model.Article
import com.ai.assistance.quro.core.websearch.model.SearchBundle
import com.ai.assistance.quro.core.websearch.model.SearchHit
import com.ai.assistance.quro.core.websearch.net.EngineRouter
import com.ai.assistance.quro.core.websearch.net.HttpStack
import com.ai.assistance.quro.core.websearch.net.SearchEngine
import com.ai.assistance.quro.core.websearch.pack.ContextPacker
import com.ai.assistance.quro.core.websearch.rank.ResultReranker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * WebSearchOrchestrator —— 联网能力的总装。
 *
 * 完整链路（也是"AI 联网"的骨架）：
 *   1. 改写   用户问题 → 1-3 个搜索查询词 + 时效偏好
 *   2. 检索   多引擎并发召回 → 合并去重 + 投票
 *   3. 重排   五信号打分 → 挑出最值得精读的 topK
 *   4. 阅读   并发抓正文 → 密度算法抽取 → markdown 化
 *   5. 打包   token 预算裁剪 → 生成 [n] 引用块 + 引用元数据
 *
 * 全程只有结构化文本在流动，不存在"页面""渲染""点击"这些浏览器概念。
 */
class WebSearchOrchestrator(
    private val engines: List<SearchEngine>,
    private val cache: CacheStore = MemoryCacheStore(),
    private val completer: LlmCompleter? = null,
    private val config: Config = Config()
) {

    data class Config(
        /** 单个查询词从每个引擎最多取回多少条 */
        val perQueryLimit: Int = 8,
        /** 最多并行使用几个查询词（越多越全、越慢） */
        val maxQueries: Int = 2,
        /** 最终精读几篇正文 */
        val readTopK: Int = 5,
        /** 上下文 token 总预算 */
        val tokenBudget: Int = 5000,
        /** 单篇正文 token 上限 */
        val perArticleCap: Int = 1500,
        /** 检索阶段超时 */
        val searchTimeoutMs: Long = 9_000
    )

    /** 主入口：传入用户原始问题，返回可直接喂给 LLM 的上下文包 */
    suspend fun search(question: String): SearchBundle {
        val t0 = System.currentTimeMillis()
        val timings = HashMap<String, Long>()

        // 1. 改写
        val now = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date())
        val rw = QueryRewriter.rewrite(completer, question, now)
        val queries = rw.queries.take(config.maxQueries)
        val t1 = System.currentTimeMillis()
        timings["rewrite"] = t1 - t0

        // 2. 检索（多查询词 + 多引擎）
        val merged = LinkedHashMap<String, SearchHit>()
        coroutineScope {
            val jobs = queries.map { q ->
                async {
                    cache.getHits(q) ?: EngineRouter.search(
                        engines, q, config.perQueryLimit, config.searchTimeoutMs
                    ).also { if (it.isNotEmpty()) cache.putHits(q, it) }
                }
            }
            for (j in jobs) {
                for (h in j.await()) {
                    val key = EngineRouter.normalizeUrl(h.url)
                    val old = merged[key]
                    merged[key] = if (old == null) h else old.copy(
                        votes = old.votes + 1,
                        snippet = old.snippet.ifBlank { h.snippet }
                    )
                }
            }
        }
        val t2 = System.currentTimeMillis()
        timings["retrieve"] = t2 - t1

        if (merged.isEmpty()) {
            return SearchBundle(
                context = "",
                citations = emptyList(),
                queries = queries,
                dropped = emptyList(),
                timings = timings
            )
        }

        // 3. 重排
        val bias = when (rw.recency) {
            "day" -> 1.0
            "week" -> 0.8
            "month" -> 0.5
            else -> 0.3
        }
        val ranked = ResultReranker.rerank(
            merged.values.toList(),
            queries.first(),
            recencyBias = bias,
            maxPerDomain = 2
        ).take(config.readTopK)

        val t3 = System.currentTimeMillis()
        timings["rerank"] = t3 - t2

        // 4. 阅读（并发）
        val articles = HashMap<String, Article>(ranked.size)
        coroutineScope {
            val jobs = ranked.map { h -> async { h.url to fetchArticle(h) } }
            for (j in jobs) {
                val (url, a) = j.await()
                articles[url] = a
            }
        }
        timings["read"] = System.currentTimeMillis() - t3

        // 5. 打包
        val bundle = ContextPacker.pack(
            hits = ranked,
            articles = articles,
            queries = queries,
            tokenBudget = config.tokenBudget,
            perArticleCap = config.perArticleCap,
            timings = timings
        )
        return bundle
    }

    /** 单篇抓取：供 read_url 工具独立使用 */
    suspend fun readUrl(url: String, titleHint: String = ""): Article =
        fetchArticle(SearchHit(titleHint, url, "", "direct", 0))

    private suspend fun fetchArticle(hit: SearchHit): Article {
        cache.getArticle(hit.url)?.let { return it }
        val domain = domainOf(hit.url)
        val html = withContext(Dispatchers.IO) { HttpStack.get(hit.url, forHtml = true) }
        if (html.isNullOrBlank()) {
            return Article(hit.url, hit.title, "", 0, 0, false, domain)
        }
        val ex = withContext(Dispatchers.Default) { DensityExtractor.extract(html) }
        val md = Markdownizer.toMarkdown(ex.blocks)
        val art = Article(
            url = hit.url,
            title = ex.title.ifBlank { hit.title },
            markdown = md,
            rawSize = html.length,
            textSize = md.length,
            ok = ex.ok && md.isNotBlank(),
            sourceDomain = domain
        )
        if (art.ok) cache.putArticle(art)
        return art
    }

    private fun domainOf(url: String): String =
        url.removePrefix("https://").removePrefix("http://")
            .substringBefore('/').substringBefore(':').removePrefix("www.")
}
