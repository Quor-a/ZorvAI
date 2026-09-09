package com.ai.assistance.quro.core.websearch.pack

import com.ai.assistance.quro.core.websearch.html.Markdownizer
import com.ai.assistance.quro.core.websearch.model.Article
import com.ai.assistance.quro.core.websearch.model.Citation
import com.ai.assistance.quro.core.websearch.model.SearchBundle
import com.ai.assistance.quro.core.websearch.model.SearchHit

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
                timings = timings
            )
        }

        val header = buildString {
            append("以下是联网检索到的实时资料，请基于这些资料回答；")
            append("引用处标注对应编号如 [1][2]。若资料不足以回答，请明确说明。\n\n")
        }

        return SearchBundle(
            context = header + sb.toString().trim(),
            citations = citations,
            queries = queries,
            dropped = dropped,
            timings = timings
        )
    }

    /** token 预算换算为字符预算（按中英混合的经验系数） */
    private fun charBudget(tokens: Int): Int = (tokens * 1.6).toInt()

    private fun domainOf(url: String): String =
        url.removePrefix("https://").removePrefix("http://")
            .substringBefore('/').substringBefore(':').removePrefix("www.")

    private fun fmtDate(ms: Long): String =
        java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.CHINA).format(java.util.Date(ms))
}
