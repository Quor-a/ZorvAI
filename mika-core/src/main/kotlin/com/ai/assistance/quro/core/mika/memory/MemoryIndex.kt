package com.ai.assistance.quro.core.mika.memory

import kotlin.math.ln
import kotlin.math.max

/**
 * 本机词法切词:汉字取相邻两字(单个汉字成词时保留单字),英文和数字按单词、小写。
 * [withUnigrams] 另外给出每个汉字的单字(查询用,让「辣」这类单字检索词能对上)。
 */
object Tokenize {
    private val WORD = Regex("[A-Za-z0-9]+")

    fun terms(text: String, withUnigrams: Boolean = false): List<String> {
        val out = mutableListOf<String>()
        val han = StringBuilder()
        fun flushHan() {
            if (han.length == 1) out += han.toString()
            else {
                for (i in 0 until han.length - 1) out += han.substring(i, i + 2)
                if (withUnigrams) han.forEach { out += it.toString() }
            }
            han.clear()
        }
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (isHan(c)) {
                han.append(c); i++; continue
            }
            flushHan()
            val m = WORD.matchAt(text, i)
            if (m != null) { out += m.value.lowercase(); i = m.range.last + 1 } else i++
        }
        flushHan()
        return out
    }

    internal fun isHan(c: Char) = Character.UnicodeScript.of(c.code) == Character.UnicodeScript.HAN

    internal fun isUnigram(term: String) = term.length == 1 && isHan(term[0])
}

/**
 * content + keywords 建倒排,BM25 打分(k1=1.2,b=0.75;检索词 tf 计两倍)。
 * 记忆条数少时 IDF 按至少 [MIN_CORPUS] 条平滑,否则一两条记忆时什么都过不了阈值。
 * 只返回「相关」的条目:检索词对上了,或至少两个不同的查询词(两字/单词)命中,
 * 或唯一命中的词只有这一条有、且是它的检索词。
 * 单字只参与打分,不算进这三条判断。
 */
class MemoryIndex(private val items: List<MemoryItem>) {
    private val docs: List<Map<String, Int>> = items.map { item ->
        val counts = HashMap<String, Int>()
        Tokenize.terms(item.content).forEach { counts.merge(it, 1, Int::plus) }
        // 单字检索词(「辣」)本身就切成单字;多字检索词不另拆单字,免得单字噪声盖过两字命中
        item.keywords.flatMap { Tokenize.terms(it) }.forEach { counts.merge(it, KEYWORD_TF, Int::plus) }
        counts
    }
    private val lengths = docs.map { d -> d.values.sum() }
    private val keywordTerms: List<Set<String>> = items.map { it.keywords.flatMap { k -> Tokenize.terms(k.lowercase()) }.toSet() }

    /** 按分数从高到低。 */
    fun search(query: String, limit: Int, includePending: Boolean = false): List<Pair<MemoryItem, Double>> {
        val eligible = items.indices.filter { includePending || items[it].status == MemoryStatus.active }
        if (eligible.isEmpty() || limit <= 0) return emptyList()
        val corpus = max(eligible.size, MIN_CORPUS).toDouble()
        val avgLen = eligible.sumOf { lengths[it] }.toDouble() / eligible.size
        val q = query.lowercase()
        val terms = Tokenize.terms(q, withUnigrams = true).distinct().filter { it !in STOP_TERMS }
        if (terms.isEmpty()) return emptyList()
        val termSet = terms.toSet()
        val df = terms.associateWith { t -> eligible.count { t in docs[it] } }
        val idf = df.mapValues { (_, n) -> ln(1 + (corpus - n + 0.5) / (n + 0.5)) }

        return eligible.mapNotNull { i ->
            val doc = docs[i]
            val matched = terms.filter { it in doc }
            if (matched.isEmpty()) return@mapNotNull null
            val multi = matched.filterNot(Tokenize::isUnigram)
            val keywordHit = items[i].keywords.any { kw ->
                val k = kw.lowercase()
                val kt = Tokenize.terms(k)
                q.contains(k) || (kt.isNotEmpty() && kt.all { it in termSet })
            }
            // 只撞上一个词:这个词要只此一条有,而且是这条的检索词(「以后」这类常用词撞上不算)
            val relevant = keywordHit || multi.size >= 2 ||
                (multi.size == 1 && df.getValue(multi.single()) == 1 && multi.single() in keywordTerms[i])
            if (!relevant) return@mapNotNull null
            val norm = K1 * (1 - B + B * lengths[i] / avgLen.coerceAtLeast(1.0))
            val score = matched.sumOf { t ->
                val tf = doc.getValue(t)
                idf.getValue(t) * tf * (K1 + 1) / (tf + norm)
            }
            items[i] to score
        }.sortedByDescending { it.second }.take(limit)
    }

    private companion object {
        const val K1 = 1.2
        const val B = 0.75
        const val KEYWORD_TF = 2
        const val MIN_CORPUS = 50
        // 只作用于查询词:口头的虚词两字组合,几乎每句话都有,不能单凭它们算相关
        val STOP_TERMS = setOf(
            "帮我", "给我", "一下", "我的", "不要", "这个", "那个", "可以", "一个", "用户",
            "今天", "这次", "现在", "什么", "怎么", "我们", "一些", "看看",
        )
    }
}
