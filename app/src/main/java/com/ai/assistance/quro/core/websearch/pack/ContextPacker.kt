package com.ai.assistance.quro.core.websearch.pack

import com.ai.assistance.quro.core.websearch.html.Markdownizer
import com.ai.assistance.quro.core.websearch.model.Article
import com.ai.assistance.quro.core.websearch.model.Citation
import com.ai.assistance.quro.core.websearch.model.SearchBundle
import com.ai.assistance.quro.core.websearch.model.SearchHit
import com.ai.assistance.quro.core.websearch.rank.DomainTrust
import com.ai.assistance.quro.core.websearch.rank.EvidenceReranker

/**
 * ContextPacker —— 把多篇正文压缩进有限 token 预算，并生成可引用编号。
 *
 * 这是整条链路的"成本控制闸门"：
 * 五篇全文轻松上万 token，直接塞进上下文会挤爆窗口、拖慢推理、还让模型抓不住重点。
 * 因此这里同时做三件事：按预算截断、按价值排序、生成 [n] 引用锚点。
 *
 * [n] 是刻意设计的：有了编号，模型才能"指着说"，答案里的每一句都能溯源到具体网页，
 * UI 也能把 citations 渲染成可点击卡片。这是"AI 联网"区别于"搜索结果列表"的关键体验。
 */
object ContextPacker {

    private val CJK = Regex("""[一-鿿぀-ヿ]""")

    /** 保守估算：CJK 按 1 token/字，其余按 4 字符/token */
    fun estimateTokens(text: String): Int {
        if (text.isEmpty()) return 0
        val cjk = CJK.findAll(text).count()
        val rest = text.length - cjk
        return cjk + (rest / 4)
    }

    /**
     * @param hits 重排后的候选（已按分数降序）
     * @param articles url -> 抓取抽取结果
     * @param tokenBudget 总预算（推荐 4000-6000）
     * @param perArticleCap 单篇上限（推荐 1200-1800）
     */
    fun pack(
        hits: List<SearchHit>,
        articles: Map<String, Article>,
        queries: List<String>,
        tokenBudget: Int = 5000,
        perArticleCap: Int = 1500,
        timings: Map<String, Long> = emptyMap()
    ): SearchBundle {
        val citations = ArrayList<Citation>()
        val sb = StringBuilder()
        var used = 0
        val dropped = ArrayList<SearchHit>()
        val votesList = ArrayList<Int>()
        var farmCount = 0
        var freshCount = 0

        for (h in hits) {
            if (used >= tokenBudget) { dropped.add(h); continue }

            val art = articles[h.url]
            // 抓失败的条目不占用预算，但仍可作为"仅标题+摘要"的弱引用保留
            val body = if (art != null && art.ok && art.markdown.isNotBlank()) {
                art.markdown
            } else {
                h.snippet
            }
            if (body.isBlank()) { dropped.add(h); continue }

            val room = minOf(perArticleCap, tokenBudget - used)
            if (room < 120) { dropped.add(h); continue }

            val (excerpt, truncated) = Markdownizer.truncate(body, charBudget(room))
            val idx = citations.size + 1
            val domain = domainOf(h.url)

            citations.add(
                Citation(
                    index = idx,
                    title = h.title,
                    url = h.url,
                    domain = domain,
                    publishedAt = h.publishedAt,
                    excerpt = excerpt,
                    truncated = truncated
                )
            )
            votesList.add(h.votes)
            if (DomainTrust.isContentFarm(domain)) farmCount++
            if (h.publishedAt > 0) freshCount++

            sb.append("[$idx] ").append(h.title.trim()).append('\n')
                .append("来源: ").append(domain)
            if (h.publishedAt > 0) sb.append(" | 发布: ").append(fmtDate(h.publishedAt))
            sb.append('\n')
            sb.append(excerpt).append("\n\n")

            used += estimateTokens(excerpt) + 20
        }

        if (citations.isEmpty()) {
            return SearchBundle(
                context = "",
                citations = emptyList(),
                queries = queries,
                dropped = hits,
                timings = timings,
                confidence = 0f,
                confidenceNote = "无可用来源"
            )
        }

        val (confidence, note) = computeConfidence(citations.size, votesList, farmCount, freshCount)

        val header = buildString {
            append("以下是联网检索到的实时资料，请基于这些资料回答；")
            append("引用处标注对应编号如 [1][2]。若资料不足以回答，请明确说明。\n\n")
        }

        return SearchBundle(
            context = header + sb.toString().trim(),
            citations = citations,
            queries = queries,
            dropped = dropped,
            timings = timings,
            confidence = confidence,
            confidenceNote = note
        )
    }

    /**
     * L2 打包：直接消费片段级证据，而不是整篇文档。
     *
     * 与 pack() 的区别：pack() 以"文档"为单位、每篇截断到固定长度；
     * 这里以"证据块"为单位，按相关性取 Top-K，因此关键结论不会因为排在文末而被截掉。
     * 同一篇文档的多个块会合并到同一个引用编号下，避免编号膨胀。
     */
    fun packEvidence(
        evidence: List<EvidenceReranker.Evidence>,
        queries: List<String>,
        tokenBudget: Int = 5000,
        perDocCap: Int = 1500,
        timings: Map<String, Long> = emptyMap()
    ): SearchBundle {
        val citations = ArrayList<Citation>()
        val sb = StringBuilder()
        var used = 0
        val perDocUsed = HashMap<String, Int>()
        val votesList = ArrayList<Int>()
        var farmCount = 0
        var freshCount = 0

        for (e in evidence) {
            if (used >= tokenBudget) break
            val docKey = e.hit.url
            val docUsed = perDocUsed[docKey] ?: 0
            if (docUsed >= perDocCap) continue

            val room = minOf(perDocCap - docUsed, tokenBudget - used)
            if (room < 80) continue

            val (excerpt, truncated) = Markdownizer.truncate(e.chunk.text, charBudget(room))
            val domain = domainOf(e.hit.url)

            // 同一文档的多个块复用同一编号
            val existing = citations.firstOrNull { it.url == e.hit.url }
            val idx: Int
            if (existing != null) {
                idx = existing.index
                citations[citations.indexOf(existing)] = existing.copy(
                    excerpt = existing.excerpt + "\n" + excerpt,
                    truncated = existing.truncated || truncated
                )
                votesList.add(e.hit.votes)
                if (DomainTrust.isContentFarm(domain)) farmCount++
                if (e.hit.publishedAt > 0) freshCount++
            } else {
                idx = citations.size + 1
                citations.add(
                    Citation(
                        index = idx,
                        title = e.hit.title,
                        url = e.hit.url,
                        domain = domain,
                        publishedAt = e.hit.publishedAt,
                        excerpt = excerpt,
                        truncated = truncated
                    )
                )
                votesList.add(e.hit.votes)
                if (DomainTrust.isContentFarm(domain)) farmCount++
                if (e.hit.publishedAt > 0) freshCount++
            }

            // 带上标题路径，让模型知道这段出自文档的什么位置
            val path = e.chunk.headingPath
            sb.append("[$idx] ").append(e.hit.title.trim())
            if (path.isNotEmpty()) sb.append(" › ").append(path.joinToString(" › "))
            sb.append('\n')
            sb.append(excerpt).append("\n\n")

            val cost = estimateTokens(excerpt) + 20
            used += cost
            perDocUsed[docKey] = docUsed + cost
        }

        if (citations.isEmpty()) {
            return SearchBundle("", emptyList(), queries, emptyList(), timings, 0f, "无可用来源")
        }

        val (confidence, note) = computeConfidence(citations.size, votesList, farmCount, freshCount)

        val header = buildString {
            append("以下是联网检索到的实时资料，请基于这些资料回答；")
            append("引用处标注对应编号如 [1][2]。若资料不足以回答，请明确说明。\n\n")
        }
        return SearchBundle(
            header + sb.toString().trim(), citations, queries, emptyList(), timings,
            confidence, note
        )
    }

    /** token 预算换算为字符预算（按中英混合的经验系数） */
    private fun charBudget(tokens: Int): Int = (tokens * 1.6).toInt()

    private fun domainOf(url: String): String =
        url.removePrefix("https://").removePrefix("http://")
            .substringBefore('/').substringBefore(':').removePrefix("www.")

    private fun fmtDate(ms: Long): String =
        java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.CHINA).format(java.util.Date(ms))

    /**
     * 估算检索置信度 [0,1] 并给出可读说明。
     * 综合四个正交维度：
     * - 覆盖度（covScore）：来源条数是否充足（>=3 视为充分）；
     * - 跨引擎共识（voteScore）：平均被多少引擎同时命中，1 个→0.45，3+→1.0；
     * - 时效覆盖（freshScore）：带发布时间的来源占比；
     * - 内容农场占比（farmScore）：内容农场来源越多，置信度越低。
     */
    private fun computeConfidence(
        n: Int,
        votes: List<Int>,
        farmCount: Int,
        freshCount: Int
    ): Pair<Float, String> {
        if (n == 0) return 0f to "无可用来源"
        val avgVotes = if (votes.isNotEmpty()) votes.average() else 1.0
        val voteScore = minOf(1.0, (avgVotes - 1.0) / 2.0 + 0.45)
        val farmRatio = farmCount.toDouble() / n
        val farmScore = 1.0 - farmRatio * 0.6
        val freshScore = if (freshCount > 0) minOf(1.0, freshCount.toDouble() / n + 0.15) else 0.7
        val covScore = minOf(1.0, n / 3.0)
        val conf = (0.35 * covScore + 0.30 * voteScore + 0.20 * freshScore + 0.15 * farmScore)
            .coerceIn(0.0, 1.0)
        val note = "来源 $n 条 · 跨引擎共识 ${"%.1f".format(avgVotes)} · " +
            "内容农场占比 ${(farmRatio * 100).toInt()}% · 时效覆盖 ${(freshCount * 100 / n)}%"
        return conf.toFloat() to note
    }
}
