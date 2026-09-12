package com.ai.assistance.quro.core.websearch.rank

/**
 * DomainTrust —— 域名权威度与内容农场识别（统一替换 ResultReranker / EvidenceReranker 内联权威表）。
 *
 * 两级信号：
 * 1. [authorityScore]：权威度评分，统一标度约 [0.3, 1.25]，未知域 0.5；
 *    官方/百科/学术源最高，UGC（知乎/CSDN 等）居中，搜索门户（百度/搜狗/360）偏低。
 * 2. [isContentFarm]：低质聚合 / 内容农场识别，命中则重排降权、置信度扣分。
 *
 * 采用统一权威分层，避免两处打分表各自为政导致行为漂移；新增权威源只需改这一张表。
 */
object DomainTrust {

    /** 权威分层（高 → 低） */
    private val TIERS = listOf(
        Regex("""(^|\.)wikipedia\.org$""") to 1.25,
        Regex("""(^|\.)(gov|gov\.cn|edu|edu\.cn|org\.cn)$""") to 1.2,
        Regex("""(^|\.)(nature\.com|sciencedirect\.com|arxiv\.org|ieee\.org|acm\.org|cell\.com|science\.org|nejm\.org)$""") to 1.2,
        Regex("""(^|\.)(github\.com|stackoverflow\.com|developer\.android\.com|developer\.apple\.com|docs\.google\.com)$""") to 1.15,
        Regex("""(^|\.)(xinhuanet\.com|people\.com\.cn|chinanews\.com\.cn|cctv\.com)$""") to 1.1,
        Regex("""(^|\.)(zhihu\.com|jianshu\.com|csdn\.net|51cto\.com|juejin\.cn)$""") to 0.95,
        Regex("""(^|\.)(baidu\.com|so\.com|sogou\.com)$""") to 0.85
    )

    /** 内容农场 / 低质聚合源：重排降权 + 置信度扣分 */
    private val CONTENT_FARMS = setOf(
        "baijiahao.baidu.com", "toutiao.com", "toutiaoapi.com",
        "kuaibao.qq.com", "360kuai.com"
    )

    /** 权威度评分（统一标度）。未知域返回 0.5；内容农场返回 0.3。 */
    fun authorityScore(domain: String): Double {
        if (isContentFarm(domain)) return 0.3
        for ((re, w) in TIERS) if (re.containsMatchIn(domain)) return w
        return 0.5
    }

    /** ResultReranker 兼容：以 (score-1)*3 的加权形式返回权威加分，与历史打分幅度一致。 */
    fun authorityBonus(domain: String): Double =
        (authorityScore(domain) - 1.0) * 3.0

    /** 内容农场 / 低质聚合源识别。 */
    fun isContentFarm(domain: String): Boolean = domain in CONTENT_FARMS
}
