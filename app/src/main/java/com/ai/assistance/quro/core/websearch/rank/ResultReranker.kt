package com.ai.assistance.quro.core.websearch.rank

import com.ai.assistance.quro.core.websearch.HanEntities
import com.ai.assistance.quro.core.websearch.model.SearchHit

/**
 * ResultReranker —— 自研重排，决定"哪几条值得精读"。
 *
 * 这一步直接决定最终答案质量：检索拿到的 20 条里往往只有 3-5 条真正有用，
 * 而每读一条都要花掉几百毫秒和上千 token，选错就是双重浪费。
 *
 * 打分由五个正交信号加权，全部为启发式，无需训练数据：
 * 相关度（词项命中）、共识度（多引擎投票）、权威度（域名）、新鲜度（发布时间）、位次（引擎排序）。
 */
object ResultReranker {

    /** 新鲜度半衰：以 30 天为半衰期做平滑指数衰减，替代原粗粒度分桶 */
    private const val FRESH_HALF_LIFE_DAYS = 30.0

    private val STOP = setOf(
        "的", "了", "吗", "呢", "是", "在", "有", "和", "与", "怎么", "如何", "什么", "为什么",
        "the", "a", "an", "is", "are", "of", "to", "in", "for", "how", "what", "why", "when"
    )

    /**
     * @param recencyBias 时间偏好：1.0 = 强烈偏好新内容（新闻/价格类），0 = 不区分
     * @param maxPerDomain 同一域名最多保留条数，避免结果被单一站点垄断
     */
    fun rerank(
        hits: List<SearchHit>,
        query: String,
        recencyBias: Double = 0.5,
        maxPerDomain: Int = 2
    ): List<SearchHit> {
        val terms = tokenize(query)
        val now = System.currentTimeMillis()

        val scored = hits.map { h ->
            val domain = domainOf(h.url)
            var s = 0.0

            // 1. 相关度：标题命中权重远高于摘要
            if (terms.isNotEmpty()) {
                val title = h.title.lowercase()
                val snip = h.snippet.lowercase()
                var hit = 0
                for (t in terms) {
                    if (title.contains(t)) hit += 3
                    else if (snip.contains(t)) hit += 1
                }
                s += (hit.toDouble() / terms.size) * 4.0
            }

            // 2. 共识度：多引擎同时命中，可信度显著提升
            s += (h.votes - 1) * 1.2

            // 3. 位次：引擎自身排序仍是最强信号之一
            s += when {
                h.position <= 0 -> 0.0
                h.position == 1 -> 1.5
                h.position <= 3 -> 1.0
                h.position <= 6 -> 0.5
                else -> 0.1
            }

            // 4. 权威度（统一由 DomainTrust 评定，含内容农场降权）
            s += DomainTrust.authorityBonus(domain)
            if (DomainTrust.isContentFarm(domain)) s -= 0.5

            // 5. 新鲜度（平滑半衰衰减，替代粗粒度分桶）
            if (h.publishedAt > 0 && recencyBias > 0) {
                val days = (now - h.publishedAt) / 86_400_000.0
                val fresh = Math.pow(0.5, days / FRESH_HALF_LIFE_DAYS)
                s += fresh * recencyBias * 1.5
            }

            // 摘要过短通常意味着内容稀薄
            if (h.snippet.length < 30) s -= 0.4

            h.copy(score = s)
        }

        // 域名打散：按分数排序后逐条挑选，超限跳过（保留后续高分条目的机会）
        val domainCount = HashMap<String, Int>()
        return scored.sortedByDescending { it.score }.filter { h ->
            val d = domainOf(h.url)
            val c = domainCount[d] ?: 0
            if (c >= maxPerDomain) false else { domainCount[d] = c + 1; true }
        }
    }

    fun tokenize(q: String): List<String> {
        // 委托 HanEntities 做端侧专有名词保护：命中实体（郑钦文 / C罗 / iPhone 17 Pro 等）
        // 作为整体 token 保留，不被 2-gram 拆断；剩余中文仍走 2-gram，保证召回不退化。
        return HanEntities.protectTokens(q).filter { it !in STOP }
    }

    private fun domainOf(url: String): String {
        val s = url.removePrefix("https://").removePrefix("http://")
        return s.substringBefore('/').substringBefore(':').removePrefix("www.")
    }

    private fun authorityBonus(domain: String): Double {
        return DomainTrust.authorityBonus(domain)
    }
}
