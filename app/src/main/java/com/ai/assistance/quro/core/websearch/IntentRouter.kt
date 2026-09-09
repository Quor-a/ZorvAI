package com.ai.assistance.quro.core.websearch

/**
 * IntentRouter —— 决定"该不该联网"，发生在任何网络请求之前。
 *
 * 为什么必须加这一层：
 * 默认对所有问题都检索，会带来三重代价——流量与延迟浪费、
 * 把模型的旧知识包装成"有引用的权威答案"（比直接承认不知道更危险）、
 * 以及在实体缺失时搜出一堆答非所问的内容。
 *
 * 实现上规则优先：正则与词表即可覆盖绝大多数情况，不依赖 LLM，
 * 因此没有额外延迟，且行为可预测。LLM 仅作为可选增强。
 */
object IntentRouter {

    enum class Action { SEARCH, SKIP, CLARIFY }

    data class Decision(
        val action: Action,
        /** 0-1，用于决定是否旁路规则（如置信度低则仍检索） */
        val confidence: Double,
        val reason: String,
        /** CLARIFY 时给出需要用户补齐的信息 */
        val missing: List<String> = emptyList(),
        /** 检索必要性：影响是否值得抓正文 */
        val freshness: Freshness = Freshness.ANY
    )

    enum class Freshness { DAY, WEEK, MONTH, ANY }

    // ---------------------------------------------------------------- 词表

    /** 强时效信号：出现即倾向检索 */
    private val TIME_SIGNALS = listOf(
        "今天", "明天", "昨天", "现在", "目前", "刚刚", "最新", "近日", "本周", "本月",
        "今年", "最近", "实时", "当前", "今日", "这周", "几个月", "几年",
        "latest", "today", "now", "current", "recent", "2026", "2025", "2027"
    )

    /** 事实/数值型：需要外部核实 */
    private val FACT_SIGNALS = listOf(
        "多少钱", "价格", "股价", "汇率", "市值", "销量", "票房", "评分", "版本",
        "什么时候", "哪一年", "多久", "多少", "几个", "排名第", "占比", "增速",
        "是多少", "叫什么", "在哪", "是谁", "怎么去", "多少度",
        "price", "how much", "when", "who is", "what is the"
    )

    /**
     * 明显不需要联网。
     * 注意不要放入"好的""明白""收到"这类极易成为其他词子串的词——
     * 实测中"最好的笔记本是哪个"因包含"好的"被误判为寒暄而跳过检索。
     * 这类续接词单独放在 RESUME_SIGNALS 中，并要求短句精确匹配。
     */
    private val SKIP_SIGNALS = listOf(
        "你好", "谢谢", "再见", "你是谁", "你能做什么", "讲个笑话", "哈哈",
        "hello", "hi ", "thanks", "thank you"
    )

    /** 多轮续接信号：只在整句很短且精确匹配时才生效 */
    private val RESUME_SIGNALS = setOf("好的", "明白", "收到", "继续", "再说", "ok", "okay")

    /** 纯知识/创作类：模型自身知识足够 */
    private val KNOWLEDGE_SIGNALS = listOf(
        "什么意思", "是什么原理", "解释一下", "帮我写", "翻译成", "总结一下这段代码",
        "这段代码", "语法", "怎么写", "举个例子说明", "定义是什么"
    )

    /** 需要澄清的宽泛表述 */
    private val VAGUE_SIGNALS = listOf(
        "最好的", "最便宜的", "哪个好", "推荐一款", "有什么建议", "最好的手机",
        "best", "cheapest", "which one"
    )

    private val ENTITY_HINT = Regex(
        """([A-Z][a-zA-Z]{1,15}\s?\d+)|([一-鿿]{2,10}(?:市|省|区|县|大学|公司|银行|医院|地铁))"""
    )

    // ---------------------------------------------------------------- 判定

    fun route(
        question: String,
        /** 上一轮是否已有可用证据，用于识别"追问续接" */
        hasPriorEvidence: Boolean = false
    ): Decision {
        val q = question.trim()
        if (q.isEmpty()) return Decision(Action.SKIP, 1.0, "空问题")

        val lower = q.lowercase()

        // 1. 宽泛表述：先于寒暄判定。
        // 顺序很关键——"最好的"包含"好的"，若先判寒暄会误跳过。
        val vague = VAGUE_SIGNALS.firstOrNull { lower.contains(it) }
        if (vague != null && !ENTITY_HINT.containsMatchIn(q)) {
            return Decision(
                Action.CLARIFY, 0.7, "表述过于宽泛，缺少约束条件",
                missing = listOf("具体型号或品牌", "预算区间", "使用场景")
            )
        }

        // 2. 寒暄/指令：直接跳过
        if (SKIP_SIGNALS.any { lower.contains(it) } && q.length <= 12) {
            return Decision(Action.SKIP, 0.9, "寒暄或指令，无事实诉求")
        }
        // 续接词：必须极短且精确匹配，避免子串误伤
        if (q.length <= 6 && lower.trim() in RESUME_SIGNALS) {
            return Decision(Action.SKIP, 0.85, "纯续接指令，无新信息诉求")
        }

        // 3. 时效信号：立即检索
        val hasTime = TIME_SIGNALS.any { lower.contains(it) }
        if (hasTime) {
            val f = when {
                listOf("今天", "现在", "刚刚", "实时", "今日", "today", "now").any { lower.contains(it) } -> Freshness.DAY
                listOf("本周", "这周", "近日", "最近", "recent").any { lower.contains(it) } -> Freshness.WEEK
                else -> Freshness.MONTH
            }
            return Decision(Action.SEARCH, 0.95, "含时效信号，必须联网核实", freshness = f)
        }

        // 3. 事实/数值：检索
        if (FACT_SIGNALS.any { lower.contains(it) }) {
            return Decision(Action.SEARCH, 0.9, "事实/数值型问题，需外部核实")
        }

        // 4. 知识/创作类：模型自身足够，且无新实体时跳过
        if (KNOWLEDGE_SIGNALS.any { lower.contains(it) } && !ENTITY_HINT.containsMatchIn(q)) {
            return Decision(Action.SKIP, 0.75, "概念解释或创作任务，无需联网")
        }

        // 6. 多轮续接：已有证据且无新实体 → 跳过
        if (hasPriorEvidence && q.length <= 10 && !ENTITY_HINT.containsMatchIn(q)) {
            return Decision(Action.SKIP, 0.7, "承接上文且无新实体，复用已有证据")
        }

        // 7. 含明确实体的问题：倾向检索
        if (ENTITY_HINT.containsMatchIn(q)) {
            return Decision(Action.SEARCH, 0.7, "含可核实实体，建议联网确认")
        }

        // 8. 兜底：不确定时检索（漏检代价高于多检一次）
        return Decision(Action.SEARCH, 0.5, "无法明确判定，默认检索以保准确", freshness = Freshness.ANY)
    }
}
