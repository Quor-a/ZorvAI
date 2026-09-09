package com.ai.assistance.quro.core.websearch.rank

import com.ai.assistance.quro.core.websearch.model.SearchHit

/**
 * 多源结果融合：从"投票计数"升级为 RRF（Reciprocal Rank Fusion）。
 *
 * 为什么换掉投票：
 * 投票隐含的假设是"被多个引擎收录 = 更相关"，但这只能说明该 URL 在多个索引里都存在，
 * 并不能证明它回答了当前问题——热门首页、导航站、百科词条最容易拿到高票。
 * RRF 只用排名、不用各引擎的内部分数，天然回避了"Bing 分数和 DDG 分数不可比"的问题。
 *
 * 标准形式：score(d) = Σ_r 1 / (k + rank_r(d))，k 典型值 60。
 *
 * k 的作用：k 越大，排名差异被压缩得越平（第 1 名和第 10 名差距变小）；
 * k 越小，头部差异越明显。查询数少、结果长尾时 k 取 10-30 更合适。
 */
object Fusion {

    /**
     * @param lists 每个子列表代表"一个检索结果序列"（可以是不同引擎，也可以是不同查询词）
     * @param k 平滑常数
     * @param voteWeight 票数权重，仅作 tie-breaker，不应成为主排序依据
     */
    fun rrf(
        lists: List<List<SearchHit>>,
        k: Int = 60,
        voteWeight: Double = 0.08
    ): List<SearchHit> {
        if (lists.isEmpty()) return emptyList()

        data class Acc(var doc: SearchHit, var rrfScore: Double = 0.0)

        val acc = LinkedHashMap<String, Acc>()
        val listCount = HashSet<Int>()

        lists.forEachIndexed { li, list ->
            if (list.isEmpty()) return@forEachIndexed
            listCount.add(li)
            list.forEachIndexed { idx, hit ->
                val key = hit.url
                val a = acc.getOrPut(key) { Acc(hit) }
                // rank 从 1 开始
                a.rrfScore += 1.0 / (k + idx + 1)
                // 保留信息最完整的那条（摘要/时间可能来自不同源）
                a.doc = mergePrefer(a.doc, hit)
            }
        }

        val maxLists = listCount.size.coerceAtLeast(1)
        return acc.values.map { a ->
            // 票数归一化后作为次要信号；主排序仍是 RRF
            val voteRatio = (a.doc.votes - 1).toDouble() / maxLists
            a.doc.copy(score = a.rrfScore + voteRatio * voteWeight)
        }.sortedByDescending { it.score }
    }

    /**
     * 多查询融合：先各查询内部排名，再跨查询融合。
     * 不同查询词角色不同（宽泛/精确/日期限定），可给不同权重。
     */
    fun rrfMultiQuery(
        perQuery: List<Pair<List<SearchHit>, Double>>,
        k: Int = 60
    ): List<SearchHit> {
        val acc = LinkedHashMap<String, Double>()
        val docMap = HashMap<String, SearchHit>()

        for ((list, weight) in perQuery) {
            list.forEachIndexed { idx, hit ->
                val key = hit.url
                acc[key] = (acc[key] ?: 0.0) + weight * (1.0 / (k + idx + 1))
                val old = docMap[key]
                docMap[key] = if (old == null) hit else mergePrefer(old, hit)
            }
        }

        return acc.entries
            .sortedByDescending { it.value }
            .mapNotNull { (key, score) ->
                docMap[key]?.copy(score = score)
            }
    }

    /** 合并两条同 URL 记录：保留信息更全的一方 */
    private fun mergePrefer(a: SearchHit, b: SearchHit): SearchHit {
        // 位次取更靠前的（越小越好）
        val pos = minOf(a.position, b.position).let {
            if (it <= 0) maxOf(a.position, b.position) else it
        }
        return a.copy(
            title = a.title.ifBlank { b.title },
            snippet = if (a.snippet.length >= b.snippet.length) a.snippet else b.snippet,
            position = pos,
            publishedAt = if (a.publishedAt > 0) a.publishedAt else b.publishedAt,
            votes = maxOf(a.votes, b.votes)
        )
    }
}
