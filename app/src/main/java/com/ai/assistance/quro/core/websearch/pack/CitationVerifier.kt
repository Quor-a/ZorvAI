package com.ai.assistance.quro.core.websearch.pack

import com.ai.assistance.quro.core.websearch.rank.EvidenceReranker
import com.ai.assistance.quro.core.websearch.model.Citation

/**
 * CitationVerifier —— 校验答案中的引用是否真实、声明是否有证据支撑。
 *
 * 为什么必须加：
 * 仅有 [n] 样式无法区分"这个编号对应真实证据"和"模型随手编的编号"。
 * 更隐蔽的问题是数字漂移：模型看到多篇资料后，把 A 的日期和 B 的数值合成一句，
 * 格式上完全合规，事实却是错的。
 *
 * 因此这里做三层校验：
 *   1. 引用编号必须存在于证据集中（防编造编号）；
 *   2. 答案中的关键数字/日期必须能在被引用的证据原文里找到（防数字漂移）；
 *   3. 含事实断言却无引用的句子必须被标记（防无据断言）。
 */
object CitationVerifier {

    /** 答案可信度状态，UI 应据此给出不同呈现 */
    enum class AnswerStatus {
        /** 关键声明均有证据 */
        ANSWERED,
        /** 核心有证据，细节缺失 */
        PARTIAL,
        /** 来源冲突或证据不足 */
        UNSURE,
        /** 需要用户补充信息 */
        NEEDS_INPUT
    }

    data class Report(
        val status: AnswerStatus,
        /** 引用编号总数 */
        val totalCites: Int,
        /** 指向不存在证据的编号（编造） */
        val dangling: List<Int>,
        /** 被引用的证据数 */
        val usedEvidence: Int,
        /** 证据总数 */
        val totalEvidence: Int,
        /** 数字/日期无法在被引证据中核实的句子 */
        val unsupported: List<String>,
        /** 含断言但无引用的句子 */
        val uncited: List<String>,
        /** 给用户的提示语 */
        val note: String
    )

    private val CITE_RE = Regex("""\[(\d+)\]""")

    /**
     * 句子切分。关键：英文句点只有在**后面不紧跟数字**时才作为分隔符。
     * 否则 "功耗为3.2瓦[1]" 会被切成 "功耗为3." + "2瓦[1]"——
     * 前半句丢失引用标记，被误判为无据断言（实测中确实发生过）。
     * 小数点、版本号（iOS 17.2）、百分比都会踩这个坑。
     */
    private val SENT_SPLIT = Regex("""(?<=[。！？!?])\s*|(?<=[.])(?!\d)\s*""")
    /**
     * 断言标记：出现这些词却没引用，风险较高。
     * 除判断动词外，还必须包含评价型/比较级词——
     * "续航也很出色，表现突出"这类句子不含数字，若只看动词会漏判为无风险。
     */
    private val ASSERT = listOf(
        "是", "为", "达到", "超过", "发布于", "表示", "显示", "根据", "支持", "采用",
        "出色", "优秀", "领先", "最好", "最强", "顶尖", "卓越", "显著", "明显",
        "更快", "更慢", "更高", "更低", "优于", "强于", "超过", "不及"
    )

    fun verify(answer: String, citations: List<Citation>): Report {
        if (citations.isEmpty()) {
            return Report(
                AnswerStatus.UNSURE, 0, emptyList(), 0, 0,
                emptyList(), emptyList(),
                "未检索到可用资料，无法核实。已基于模型知识作答，请谨慎参考。"
            )
        }

        val validIds = citations.map { it.index }.toSet()
        val found = CITE_RE.findAll(answer).map { it.groupValues[1].toInt() }.distinct().toList()
        val dangling = found.filter { it !in validIds }

        val byId = citations.associateBy { it.index }
        val unsupported = ArrayList<String>()
        val uncited = ArrayList<String>()

        for (sent in SENT_SPLIT.split(answer)) {
            val s = sent.trim()
            if (s.length < 6) continue
            val cited = CITE_RE.findAll(s).mapNotNull { byId[it.groupValues[1].toInt()] }.toList()

            // 该句含数字或日期 → 必须能在被引证据原文中核实
            val nums = EvidenceReranker.extractNumerics(s)
            val dates = s.contains("年") || s.contains("月") || s.contains("日")
            if (nums.isNotEmpty() || dates) {
                if (cited.isEmpty()) {
                    if (nums.isNotEmpty()) uncited.add(s)
                } else {
                    val pool = cited.joinToString("\n") { it.excerpt }
                    val ok = nums.all { n -> pool.contains(n) }
                    if (!ok) unsupported.add(s)
                }
            }

            // 含断言词、不含引用 → 标记为无据断言。
            // 门槛取 12 而非更长：实测"此外续航也很出色，表现突出。"仅 14 字，
            // 若要求 >20 会整句漏判。宁可多标为 PARTIAL（仅提示），也不要漏掉无据断言。
            if (cited.isEmpty() && nums.isEmpty() && s.length > 12 &&
                ASSERT.any { s.contains(it) }
            ) {
                uncited.add(s)
            }
        }

        val status = when {
            dangling.isNotEmpty() && dangling.size == found.size -> AnswerStatus.UNSURE
            unsupported.isNotEmpty() -> AnswerStatus.UNSURE
            found.isEmpty() -> AnswerStatus.PARTIAL
            uncited.isNotEmpty() -> AnswerStatus.PARTIAL
            else -> AnswerStatus.ANSWERED
        }

        val note = when (status) {
            AnswerStatus.ANSWERED -> "基于 ${found.size} 个来源回答，关键声明均有引用支撑。"
            AnswerStatus.PARTIAL -> "部分内容缺少引用支撑，已标注的不确定项建议另行核实。"
            AnswerStatus.UNSURE -> "存在无法核实的声明，以下內容仅供参考，请核对原始来源。"
            AnswerStatus.NEEDS_INPUT -> "信息不足，需要补充说明后才能准确回答。"
        }

        return Report(
            status = status,
            totalCites = found.size,
            dangling = dangling,
            usedEvidence = found.count { it in validIds },
            totalEvidence = citations.size,
            unsupported = unsupported,
            uncited = uncited,
            note = note
        )
    }

    /** 生成供 UI 展示的来源卡 JSON（含可点击 URL） */
    fun sourceCards(citations: List<Citation>): String {
        val sb = StringBuilder("[")
        citations.forEachIndexed { i, c ->
            if (i > 0) sb.append(",")
            sb.append("""{"index":${c.index},"title":${q(c.title)},"url":${q(c.url)},"domain":${q(c.domain)}}""")
        }
        return sb.append("]").toString()
    }

    private fun q(s: String): String =
        "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ") + "\""
}
