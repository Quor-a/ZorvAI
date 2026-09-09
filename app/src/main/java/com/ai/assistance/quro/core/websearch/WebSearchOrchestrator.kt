package com.ai.assistance.quro.core.websearch

import com.ai.assistance.quro.core.websearch.cache.CacheStore
import com.ai.assistance.quro.core.websearch.cache.MemoryCacheStore
import com.ai.assistance.quro.core.websearch.html.DensityExtractor
import com.ai.assistance.quro.core.websearch.html.Markdownizer
import com.ai.assistance.quro.core.websearch.html.SemanticChunker
import com.ai.assistance.quro.core.websearch.model.Article
import com.ai.assistance.quro.core.websearch.model.SearchBundle
import com.ai.assistance.quro.core.websearch.model.SearchHit
import com.ai.assistance.quro.core.websearch.net.EngineRouter
import com.ai.assistance.quro.core.websearch.net.HttpStack
import com.ai.assistance.quro.core.websearch.net.SearchEngine
import com.ai.assistance.quro.core.websearch.pack.ContextPacker
import com.ai.assistance.quro.core.websearch.rank.EvidenceReranker
import com.ai.assistance.quro.core.websearch.rank.Fusion
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
        /**
         * 超额抓取篇数。
         * 正文抓取的失败率远高于检索（反爬、403、超时、JS 渲染页），
         * 只抓 readTopK 篇的话，一旦挂掉两三篇，上下文质量会直接塌方。
         * 多抓几篇再按"成功优先"筛选，可以用少量带宽换稳定性。
         */
        val oversample: Int = 3,
        /** 上下文 token 总预算 */
        val tokenBudget: Int = 5000,
        /** 单篇正文 token 上限 */
        val perArticleCap: Int = 1500,
        /** 检索阶段超时 */
        val searchTimeoutMs: Long = 9_000,
        // ---- L2 新增 ----
        /** 语义块字符上限（约 512 token） */
        val chunkMaxChars: Int = 800,
        /** 相邻块重叠字符数 */
        val chunkOverlap: Int = 80,
        /** 单篇文档最多取几个块 */
        val maxChunksPerDoc: Int = 3,
        /** 最终进入上下文的证据块总数 */
        val evidenceTopK: Int = 12
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
        val rankedFull = ResultReranker.rerank(
            merged.values.toList(),
            queries.first(),
            recencyBias = bias,
            maxPerDomain = 2
        )

        val t3 = System.currentTimeMillis()
        timings["rerank"] = t3 - t2

        // 4. 阅读：超额并发抓取
        val candidates = rankedFull.take(config.readTopK + config.oversample)
        val fetched = LinkedHashMap<String, Article>(candidates.size)
        coroutineScope {
            val jobs = candidates.map { h -> async { h.url to fetchArticle(h) } }
            for (j in jobs) {
                val (url, a) = j.await()
                fetched[url] = a
            }
        }

        // 成功优先、保持原重排相对顺序，凑够 readTopK 篇
        val okList = candidates.filter { fetched[it.url]?.ok == true }
        val restList = candidates.filter { fetched[it.url]?.ok != true }
        val ranked = (okList + restList).take(config.readTopK)

        timings["read"] = System.currentTimeMillis() - t3
        timings["readOk"] = okList.size.toLong()

        // 5. 打包
        val bundle = ContextPacker.pack(
            hits = ranked,
            articles = fetched,
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

    // ------------------------------------------------------------------
    // L2 链路：意图路由 → RRF 融合 → 语义切块 → 片段级证据排序 → 打包
    // 相比上面的 L1 链路，三处关键升级：
    //   1. 检索前先判断该不该搜，省掉无谓请求；
    //   2. 投票计数换成 RRF，保留尾部高相关结果；
    //   3. 整篇截断换成语义切块 + 片段级排序，避免关键结论被截掉。
    // ------------------------------------------------------------------

    /** L2 检索结果 */
    data class L2Result(
        val decision: IntentRouter.Decision,
        val bundle: SearchBundle?,
        /** 选中的证据块，供引用校验对照 */
        val evidence: List<EvidenceReranker.Evidence>,
        val timings: Map<String, Long>
    )

    /**
     * @param question 用户原始问题
     * @param hasPriorEvidence 多轮场景下上文是否已有可用证据
     */
    suspend fun searchL2(
        question: String,
        hasPriorEvidence: Boolean = false
    ): L2Result {
        val t0 = System.currentTimeMillis()
        val timings = HashMap<String, Long>()

        // 1. 意图路由：不该搜就别搜
        val decision = IntentRouter.route(question, hasPriorEvidence)
        timings["intent"] = System.currentTimeMillis() - t0
        if (decision.action != IntentRouter.Action.SEARCH) {
            return L2Result(decision, null, emptyList(), timings)
        }

        val t1 = System.currentTimeMillis()
        val now = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date())
        val rw = QueryRewriter.rewrite(completer, question, now)
        val queries = rw.queries.take(config.maxQueries)
        timings["rewrite"] = System.currentTimeMillis() - t1

        // 2. 多查询并发检索
        val t2 = System.currentTimeMillis()
        val perQuery = ArrayList<Pair<List<SearchHit>, Double>>()
        coroutineScope {
            val jobs = queries.mapIndexed { i, q ->
                // 首个查询（通常最贴近原问句）权重更高
                async {
                    val hits = cache.getHits(q) ?: EngineRouter.search(
                        engines, q, config.perQueryLimit, config.searchTimeoutMs
                    ).also { if (it.isNotEmpty()) cache.putHits(q, it) }
                    hits to if (i == 0) 1.0 else 0.7
                }
            }
            for (j in jobs) perQuery.add(j.await())
        }
        timings["retrieve"] = System.currentTimeMillis() - t2

        // 3. RRF 融合（替代投票计数）
        val t3 = System.currentTimeMillis()
        val fused = Fusion.rrfMultiQuery(perQuery)
        timings["fuse"] = System.currentTimeMillis() - t3
        if (fused.isEmpty()) {
            return L2Result(decision, null, emptyList(), timings)
        }

        // 4. 结果级预排序后择优抓取
        val ranked = ResultReranker.rerank(
            fused, queries.first(),
            recencyBias = when (rw.recency) {
                "day" -> 1.0; "week" -> 0.8; "month" -> 0.5; else -> 0.3
            },
            maxPerDomain = 2
        )
        val candidates = ranked.take(config.readTopK + config.oversample)

        val t4 = System.currentTimeMillis()
        val fetched = LinkedHashMap<String, Article>(candidates.size)
        coroutineScope {
            val jobs = candidates.map { h -> async { h.url to fetchArticle(h) } }
            for (j in jobs) { val (u, a) = j.await(); fetched[u] = a }
        }
        timings["read"] = System.currentTimeMillis() - t4

        // 5. 语义切块 + 片段级证据排序
        val t5 = System.currentTimeMillis()
        val pairs = ArrayList<Pair<SemanticChunker.Chunk, SearchHit>>()
        for (h in candidates) {
            val art = fetched[h.url] ?: continue
            if (!art.ok || art.markdown.isBlank()) continue
            val blocks = toBlocks(art.markdown)
            val chunks = SemanticChunker.chunk(
                docId = EngineRouter.normalizeUrl(h.url),
                blocks = blocks,
                maxChars = config.chunkMaxChars,
                overlapChars = config.chunkOverlap
            )
            for (c in chunks) pairs.add(c to h)
        }
        val evidence = EvidenceReranker.rank(
            chunks = pairs,
            query = queries.first(),
            recencyBias = if (rw.recency == "day") 0.8 else 0.4,
            maxPerDoc = config.maxChunksPerDoc
        ).take(config.evidenceTopK)
        timings["evidence"] = System.currentTimeMillis() - t5

        // 6. 按证据打包（不再整篇截断）
        val t6 = System.currentTimeMillis()
        val bundle = ContextPacker.packEvidence(
            evidence = evidence,
            queries = queries,
            tokenBudget = config.tokenBudget,
            timings = timings
        )
        timings["pack"] = System.currentTimeMillis() - t6

        return L2Result(decision, bundle, evidence, timings)
    }

    /** 把 markdown 文本还原为块序列（供切块器消费） */
    private fun toBlocks(md: String): List<SemanticChunker.Block> {
        val out = ArrayList<SemanticChunker.Block>()
        var offset = 0
        for (line in md.split('\n')) {
            val trimmed = line.trimEnd()
            if (trimmed.isBlank()) { offset += line.length + 1; continue }
            val block = when {
                trimmed.startsWith("|") -> SemanticChunker.Block(trimmed, SemanticChunker.Kind.TABLE, null, offset)
                trimmed.startsWith("```") -> SemanticChunker.Block(trimmed, SemanticChunker.Kind.CODE, null, offset)
                trimmed.startsWith("#") -> SemanticChunker.Block(trimmed, SemanticChunker.Kind.HEADING, trimmed.takeWhile { it == '#' }, offset)
                trimmed.startsWith("- ") || trimmed.startsWith("> ") -> SemanticChunker.Block(trimmed, SemanticChunker.Kind.LIST, null, offset)
                else -> SemanticChunker.Block(trimmed, SemanticChunker.Kind.TEXT, null, offset)
            }
            out.add(block)
            offset += line.length + 1
        }
        return out
    }

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
