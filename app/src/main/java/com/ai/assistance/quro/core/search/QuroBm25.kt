package com.ai.assistance.quro.core.search

import kotlin.math.ln

/**
 * BM25 检索引擎（参照 Teleclaw 记忆系统的 BM25 实现，去品牌化重写）。
 *
 * 为什么不用「关键词包含匹配」：
 *  - 包含匹配无法排序，长记忆永远排前面（词频高但相关性低）；
 *  - 缺词（"手机" 对 "电话号码"）与多词（"今天天气"）场景表现差；
 *  - BM25 同时考虑 **词频饱和度**（出现 10 次不比 3 次重要 3 倍）与
 *    **文档长度归一化**（长文档不被无脑加分），是信息检索的经典最优解。
 *
 * 中英文混合分词见 [QuroBm25Tokenizer]：中文走 bigram + 单字，英文走小写词，
 * **不需要内置词典**，因此对专有名词、新词、中英混排都天然可用。
 */

/** 检索命中结果。 */
data class QuroBm25Hit(
    /** 文档唯一标识（记忆 id / 文件名等）。 */
    val id: String,
    /** BM25 相关性得分，越大越相关（可能为负，负分表示整体低于平均相关性）。 */
    val score: Float,
    /** 命中片段（已做长度限制，便于直接展示）。 */
    val snippet: String,
    /** 命中词，用于高亮或调试。 */
    val matchedTerms: List<String>,
)

/**
 * 中英文混合分词器。
 *
 * 规则：
 *  - 连续 ASCII 字母/数字 → 一个英文词（转小写，如 `GPT-4o` → `gpt`, `4o`）；
 *  - 连续 CJK 字符 → 全部 bigram（"人工智能" → 人工/工智/智能）+ 全部单字（人/工/智/能）；
 *  - 单字 CJK 片段只输出该字，避免 bigram 切不出来导致漏召回。
 *
 * 单字与 bigram 双写会让索引变大，但记忆条目通常在数百量级，
 * 换来的是「单字查询也能命中」的鲁棒性，值得。
 */
object QuroBm25Tokenizer {

    private val CJK_RANGE = Regex("""[一-鿿㐀-䶿]""")

    fun tokenize(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        val out = ArrayList<String>(text.length)
        val sb = StringBuilder()

        fun flushLatin() {
            if (sb.isNotBlank()) {
                out.add(sb.toString().lowercase())
                sb.clear()
            }
        }

        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (isCjk(c)) {
                flushLatin()
                // 抓取一段连续 CJK
                val start = i
                while (i < text.length && isCjk(text[i])) i++
                val seg = text.substring(start, i)
                out.addAll(tokenizeCjk(seg))
            } else if (c.isLetterOrDigit() && c.code < 128) {
                sb.append(c)
                i++
            } else {
                // 标点/空白/其他语言字符：作为分隔符
                flushLatin()
                i++
            }
        }
        flushLatin()
        return out
    }

    /** 中文片段 → bigram + 单字。 */
    private fun tokenizeCjk(seg: String): List<String> {
        val chars = seg.toList()
        if (chars.size == 1) return listOf(chars[0].toString())
        val out = ArrayList<String>(chars.size * 2)
        // 单字：保证单字查询不漏召回
        for (ch in chars) out.add(ch.toString())
        // bigram：保证词组查询的语序相关性
        for (k in 0 until chars.size - 1) out.add("${chars[k]}${chars[k + 1]}")
        return out
    }

    private fun isCjk(c: Char): Boolean = CJK_RANGE.matches(c.toString())
}

/**
 * BM25 索引。
 *
 * 用法：
 * ```
 * val bm25 = QuroBm25()
 * memories.forEach { bm25.index(it.id, it.content) }
 * val hits = bm25.search("用户喜欢", topK = 5)
 * ```
 *
 * 非线程安全：索引构建与检索都在调用方线程完成，记忆量小，无需加锁；
 * 若将来改为后台常驻索引，在外层加读写锁即可。
 *
 * @param k1 词频饱和度参数（默认 1.5）。越大，词频对得分影响越强。
 * @param b 长度归一化参数（默认 0.75）。1=完全按长度归一化，0=不归一化。
 */
class QuroBm25(
    private val k1: Float = 1.5f,
    private val b: Float = 0.75f,
) {
    /** 文档 id → 原始文本（用于生成片段）。 */
    private val documents = LinkedHashMap<String, String>()

    /** 文档 id → 词频表。 */
    private val termFreqs = LinkedHashMap<String, HashMap<String, Int>>()

    /** 文档 id → 文档长度（词数）。 */
    private val docLengths = LinkedHashMap<String, Int>()

    /** 词 → 出现该词的文档数（df），增量维护。 */
    private val docFreqs = HashMap<String, Int>()

    /** 平均文档长度，惰性计算，由 [avgDocLength] 读取。 */
    private var avgDocLengthCache: Float = -1f

    val documentCount: Int get() = documents.size

    /**
     * 索引（或更新）一篇文档。相同 id 重复调用会先移除旧索引，避免统计漂移。
     */
    fun index(id: String, text: String) {
        if (id.isBlank()) return
        // 覆盖写：先抹掉旧版贡献
        if (documents.containsKey(id)) unindex(id)

        val tokens = QuroBm25Tokenizer.tokenize(text)
        val tf = HashMap<String, Int>()
        for (t in tokens) tf[t] = (tf[t] ?: 0) + 1

        documents[id] = text
        termFreqs[id] = tf
        docLengths[id] = tokens.size
        for (term in tf.keys) docFreqs[term] = (docFreqs[term] ?: 0) + 1
        avgDocLengthCache = -1f
    }

    /** 批量索引。 */
    fun indexAll(items: List<Pair<String, String>>) {
        items.forEach { (id, text) -> index(id, text) }
    }

    /** 移除文档。 */
    fun unindex(id: String) {
        val tf = termFreqs.remove(id) ?: run {
            documents.remove(id)
            docLengths.remove(id)
            return
        }
        for (term in tf.keys) {
            val n = (docFreqs[term] ?: 0) - 1
            if (n <= 0) docFreqs.remove(term) else docFreqs[term] = n
        }
        documents.remove(id)
        docLengths.remove(id)
        avgDocLengthCache = -1f
    }

    fun clear() {
        documents.clear()
        termFreqs.clear()
        docLengths.clear()
        docFreqs.clear()
        avgDocLengthCache = -1f
    }

    private val avgDocLength: Float
        get() {
            if (avgDocLengthCache < 0f) {
                avgDocLengthCache = if (docLengths.isEmpty()) 0f
                else docLengths.values.sum().toFloat() / docLengths.size
            }
            return avgDocLengthCache
        }

    /**
     * 检索。返回按 BM25 得分降序的前 [topK] 条。
     *
     * @param query 查询串（支持中英混排、多词）
     * @param topK 返回条数上限
     * @param minScore 得分下限，用于过滤「勉强有词重叠但其实不相关」的结果
     */
    fun search(query: String, topK: Int = 10, minScore: Float = 0f): List<QuroBm25Hit> {
        val queryTerms = QuroBm25Tokenizer.tokenize(query).distinct()
        if (queryTerms.isEmpty() || documents.isEmpty()) return emptyList()

        val n = documents.size.toFloat()
        val avgdl = avgDocLength.coerceAtLeast(1f)

        val hits = ArrayList<QuroBm25Hit>()
        for ((id, tf) in termFreqs) {
            val dl = (docLengths[id] ?: 0).toFloat()
            var score = 0f
            val matched = ArrayList<String>()

            for (term in queryTerms) {
                val f = tf[term] ?: continue
                val df = docFreqs[term] ?: 0
                // IDF：df 越大（词越常见）权重越低；+0.5 平滑避免除零与负值
                val idf = ln(1f + (n - df + 0.5f) / (df + 0.5f))
                // 词频饱和 + 长度归一化
                val denom = f + k1 * (1f - b + b * (dl / avgdl))
                score += idf * (f * (k1 + 1f)) / denom
                matched.add(term)
            }

            if (score > minScore && matched.isNotEmpty()) {
                hits.add(
                    QuroBm25Hit(
                        id = id,
                        score = score,
                        snippet = buildSnippet(documents[id].orEmpty(), matched),
                        matchedTerms = matched.distinct(),
                    )
                )
            }
        }

        return hits.sortedByDescending { it.score }.take(topK)
    }

    /**
     * 生成命中片段：优先截取包含首个命中词的窗口，避免只展示文档开头
     * （长记忆的开头往往与查询无关）。
     */
    private fun buildSnippet(text: String, matched: List<String>, window: Int = 120): String {
        if (text.length <= window) return text.replace('\n', ' ')

        // 找到第一个命中词在原文中的位置
        var bestIdx = -1
        for (term in matched) {
            val idx = text.indexOf(term, ignoreCase = true)
            if (idx >= 0 && (bestIdx < 0 || idx < bestIdx)) bestIdx = idx
        }

        return if (bestIdx < 0) {
            text.take(window).replace('\n', ' ')
        } else {
            val start = (bestIdx - window / 3).coerceAtLeast(0)
            val end = (start + window).coerceAtMost(text.length)
            val prefix = if (start > 0) "…" else ""
            val suffix = if (end < text.length) "…" else ""
            prefix + text.substring(start, end).replace('\n', ' ') + suffix
        }
    }
}

/**
 * 便捷入口：给定文档列表直接检索，无需手动维护索引实例。
 * 适合「一次性的小批量检索」（如每次记忆搜索时全量重建，数百条耗时可忽略）。
 */
fun bm25Search(
    documents: List<Pair<String, String>>,
    query: String,
    topK: Int = 10,
    minScore: Float = 0f,
): List<QuroBm25Hit> {
    val engine = QuroBm25()
    engine.indexAll(documents)
    return engine.search(query, topK, minScore)
}
