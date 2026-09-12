package com.ai.assistance.quro.core.websearch.rank

import com.ai.assistance.quro.core.websearch.HanEntities
import com.ai.assistance.quro.core.websearch.html.SemanticChunker
import com.ai.assistance.quro.core.websearch.model.SearchHit
import kotlin.math.exp

/**
 * EvidenceReranker —— 片段级证据排序。
 *
 * 与结果级重排（ResultReranker）的分工：
 * - ResultReranker 回答"这篇文档值不值得抓"，发生在抓取之前，只能用标题和摘要；
 * - EvidenceReranker 回答"这个段落能不能支持这个说法"，发生在抓取之后，能看到正文。
 *
 * 为什么必须补这一层：
 * 候选文档可能整体相关，但真正进入答案的往往只是其中若干段落。
 * 只做文档级排序，等于默认"整篇都有用"，结果是把导航、引言、相关阅读一起塞进上下文，
 * 真正的关键句反而被稀释。
 *
 * 打分采用报告建议的加权组合，全部为词法/结构特征，无需 embedding、不依赖 GPU：
 *   score = w_q*qcov + w_e*ent + w_t*title + w_f*fresh + w_a*auth
 *         + w_d*diversity - w_l*lenPenalty
 */
object EvidenceReranker {

    /** 权重，初值来自工程经验，应通过人工标注数据逐步调优 */
    data class Weights(
        val wQueryCoverage: Double = 0.25,
        val wEntity: Double = 0.20,
        val wTitle: Double = 0.10,
        val wFresh: Double = 0.15,
        val wAuthority: Double = 0.15,
        val wDiversity: Double = 0.10,
        val wLenPenalty: Double = 0.05
    )

    /** 一条证据：块 + 来源信息 */
    data class Evidence(
        val chunk: SemanticChunker.Chunk,
        val hit: SearchHit,
        var score: Double = 0.0,
        /** 命中了哪些查询词，供引用校验与调试 */
        val matchedTerms: MutableSet<String> = HashSet()
    )

    private val ENTITY = Regex("""([A-Z][a-zA-Z0-9]{2,}|[一-鿿]{2,8}(?:模型|系统|协议|框架|版本|芯片|手机|接口|标准|文档))""")
    private val NUMERIC = Regex("""\d+(?:\.\d+)?%?|\d{4}年|\d+月\d+日""")

    /** 时间衰减系数 λ：时效要求越高，衰减越快 */
    private fun lambdaFor(recencyBias: Double): Double =
        when {
            recencyBias >= 0.8 -> 0.05   // DAY
            recencyBias >= 0.5 -> 0.01   // WEEK/MONTH
            else -> 0.003
        }

    fun rank(
        chunks: List<Pair<SemanticChunker.Chunk, SearchHit>>,
        query: String,
        recencyBias: Double = 0.5,
        weights: Weights = Weights(),
        maxPerDoc: Int = 3
    ): List<Evidence> {
        if (chunks.isEmpty()) return emptyList()

        val terms = ResultReranker.tokenize(query)
        val entities = extractEntities(query).toSet()
        val now = System.currentTimeMillis()
        val lambda = lambdaFor(recencyBias)

        val scored = chunks.map { (c, hit) ->
            val lower = c.text.lowercase()
            var s = 0.0
            val matched = HashSet<String>()

            // 1. 查询词覆盖率：块内命中了多少比例的查询词
            val qcov = if (terms.isEmpty()) 0.0 else {
                var hitCount = 0
                for (t in terms) if (lower.contains(t)) { hitCount++; matched.add(t) }
                hitCount.toDouble() / terms.size
            }
            s += weights.wQueryCoverage * qcov

            // 2. 实体命中
            val ent = if (entities.isEmpty()) 0.0 else {
                var e = 0
                for (en in entities) if (c.text.contains(en)) { e++; matched.add(en) }
                e.toDouble() / entities.size
            }
            s += weights.wEntity * ent

            // 3. 标题路径命中（heading_path 也是上下文线索）
            val titleText = (hit.title + " " + c.headingPath.joinToString(" ")).lowercase()
            val tHit = if (terms.isEmpty()) 0.0 else {
                terms.count { titleText.contains(it) }.toDouble() / terms.size
            }
            s += weights.wTitle * tHit

            // 4. 新鲜度：指数衰减
            if (hit.publishedAt > 0 && recencyBias > 0) {
                val hours = (now - hit.publishedAt) / 3_600_000.0
                s += weights.wFresh * exp(-lambda * hours) * recencyBias
            }

            // 5. 来源权威
            s += weights.wAuthority * authorityScore(hit.url)

            // 6. 块类型：表格和代码信息密度高，但加成必须按相关性缩放。
            // 否则会出现"无关表格仅凭类型加成爬到第一"（实测中确实发生过）：
            // 表格查询词覆盖率只有 0.14，却靠 +0.12 反超了覆盖率 0.57 的正文段落。
            val typeBonus = when (c.kind) {
                SemanticChunker.Kind.TABLE -> 0.12
                SemanticChunker.Kind.CODE -> 0.08
                else -> 0.0
            }
            s += typeBonus * (0.3 + 0.7 * qcov)

            // 7. 长度惩罚：过短信息量不足，过长稀释注意力
            val len = c.text.length
            val penalty = when {
                len < 40 -> 1.0
                len > 1200 -> 0.6
                else -> 0.0
            }
            s -= weights.wLenPenalty * penalty

            Evidence(c, hit, s, matched)
        }

        // 多样性：每篇文档最多取 maxPerDoc 块，避免单一来源垄断上下文
        val perDoc = HashMap<String, Int>()
        return scored.sortedByDescending { it.score }.filter { e ->
            val d = e.hit.url
            val c = perDoc[d] ?: 0
            if (c >= maxPerDoc) false else { perDoc[d] = c + 1; true }
        }
    }

    fun extractEntities(q: String): List<String> {
        // 结构正则（XX模型/XX系统/拉丁词）与 HanEntities 词典/型号检测合并，覆盖更多专有名词
        val fromRegex = ENTITY.findAll(q).map { it.value }
        val fromHan = HanEntities.detect(q)
        return (fromRegex + fromHan).distinct().toList()
    }

    /** 提取数值型声明，供引用校验检查"数字是否真的来自证据" */
    fun extractNumerics(text: String): List<String> =
        NUMERIC.findAll(text).map { it.value }.distinct().toList()

    private fun authorityScore(url: String): Double {
        val d = url.removePrefix("https://").removePrefix("http://")
            .substringBefore('/').substringBefore(':').removePrefix("www.")
        return DomainTrust.authorityScore(d)
    }
}
