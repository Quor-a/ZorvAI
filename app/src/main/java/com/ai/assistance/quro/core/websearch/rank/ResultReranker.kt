package com.ai.assistance.quro.core.websearch.rank

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

    /** 域名权威加分：官方/百科/权威媒体/学术源 */
    private val AUTHORITY = listOf(
        Regex("""(^|\.)wikipedia\.org$""") to 1.25,
        Regex("""(^|\.)(gov|gov\.cn|edu|edu\.cn|org\.cn)$""") to 1.2,
        Regex("""(^|\.)(nature\.com|sciencedirect\.com|arxiv\.org|ieee\.org|acm\.org)$""") to 1.2,
        Regex("""(^|\.)(xinhuanet\.com|people\.com\.cn|chinanews\.com\.cn|cctv\.com)$""") to 1.1,
        Regex("""(^|\.)(github\.com|stackoverflow\.com|developer\.android\.com|developer\.apple\.com)$""") to 1.15,
        Regex("""(^|\.)(zhihu\.com|jianshu\.com|csdn\.net|51cto\.com|juejin\.cn)$""") to 0.95,
        Regex("""(^|\.)(baidu\.com|so\.com|sogou\.com)$""") to 0.85
    )

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

            // 4. 权威度
            s += authorityBonus(domain)

            // 5. 新鲜度
            if (h.publishedAt > 0 && recencyBias > 0) {
                val days = (now - h.publishedAt) / 86_400_000.0
                val fresh = when {
                    days <= 1 -> 1.5
                    days <= 7 -> 1.0
                    days <= 30 -> 0.5
                    days <= 365 -> 0.1
                    else -> 0.0
                }
                s += fresh * recencyBias
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
        // 中英混排切分：英文按空格，中文按 2-gram 滑动（无分词器下的高性价比方案）
        val out = ArrayList<String>()
        val en = Regex("""[a-zA-Z0-9]{2,}""").findAll(q.lowercase()).map { it.value }.toList()
        out.addAll(en.filter { it !in STOP })
        val cn = Regex("""[一-鿿]+""").findAll(q).map { it.value }.toList()
        for (seg in cn) {
            if (seg.length <= 2) out.add(seg)
            else for (i in 0..seg.length - 2) out.add(seg.substring(i, i + 2))
        }
        return out.distinct().filter { it !in STOP }
    }

    private fun domainOf(url: String): String {
        val s = url.removePrefix("https://").removePrefix("http://")
        return s.substringBefore('/').substringBefore(':').removePrefix("www.")
    }

    private fun authorityBonus(domain: String): Double {
        for ((re, w) in AUTHORITY) if (re.containsMatchIn(domain)) return (w - 1.0) * 3.0
        return 0.0
    }
}
