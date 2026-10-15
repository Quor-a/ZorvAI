package com.ai.assistance.quro.core.websearch
import com.ai.assistance.quro.R
import com.ai.assistance.quro.util.qstr

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
// ---------------------------------------------------------------- 意图理解层（2026 升级）
// web_search 不再"一刀切"盲搜，而是先判定意图类型，再决定走哪条检索/抓取编排。
// 规则优先、零延迟、可预测，与本项目"IntentRouter 规则优先"的哲学一致。

/** 意图类型：决定后续 Agent 编排走哪条路径 */
enum class IntentType { GENERAL, HOT_NEWS, ENTITY_SELF }

/**
 * 已知实体的权威信息。
 * 例：问"zorvAI 怎么获取 zorvAI 的网络内容"时，不再用通用搜索瞎猜，
 * 而是直接抓取该实体的官方/权威页面（GitHub 仓库、Releases、爱发电），准确且零歧义。
 */
data class EntityInfo(
    val name: String,
    val aliases: List<String>,
    val urls: List<String>,
    val domains: List<String>
)

/** 实体名册：按需追加。命中 aliases 即路由到 ENTITY_SELF。 */
object EntityRegistry {
    private val ZORVAI = EntityInfo(
        name = "ZorvAI",
        aliases = listOf("zorvai", "zorv", "zorv ai", "quor-a", "quroai", "quro ai"),
        urls = listOf(
            "https://github.com/Quor-a/ZorvAI",
            "https://github.com/Quor-a/ZorvAI/releases",
            "https://afdian.com/a/zorvAI"
        ),
        domains = listOf("github.com/Quor-a", "afdian.com/a/zorvAI")
    )
    val all: List<EntityInfo> = listOf(ZORVAI)

    fun match(q: String): EntityInfo? {
        val lower = q.lowercase()
        return all.firstOrNull { e -> e.aliases.any { lower.contains(it) } }
    }
}

/** 时效粒度：决定检索是否带时间约束、Agent 是否偏好近期源 */
enum class Freshness { DAY, WEEK, MONTH, ANY }

/** 意图判定结果，携带后续编排所需的全部信息 */
data class IntentInfo(
    val type: IntentType,
    val entity: EntityInfo? = null,
    val freshness: Freshness = Freshness.ANY,
    val reason: String = ""
)

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

    // ---------------------------------------------------------------- 词表

    /** 强时效信号：出现即倾向检索 */
    private val TIME_SIGNALS = listOf(
        "今天", "明天", "昨天", "现在", "目前", "刚刚", "最新", "近日", "本周", "本月",
        "今年", "最近", "实时", "当前", "今日", "这周", "几个月", "几年",
        "latest", "today", "now", "current", "recent", "2026", "2025", "2027"
    )

    /** 事实/数值型：需要外部核实 */
    private val FACT_SIGNALS = listOf(
        "多少钱", "价格", "股价", "汇率", "市值", "销量", "票房", qstr(R.string.qk_01667), qstr(R.string.qk_01612),
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

    private val HOT_NEWS_SIGNALS = listOf(
        "热点新闻", "热搜", "热榜", "今日热点", "今日热搜", "今天的新闻", "今天有什么新闻",
        "今天发生", "热门话题", "新闻头条", "实时热点", "最新消息", "突发", "什么新闻",
        "trending", "hot news", "today's news", "what's happening", "what happened today",
        "top news", "breaking news"
    )

    /**
     * 意图分类（规则优先，零延迟）。
     * 顺序：已知实体 → 热点新闻 → 通用检索。
     * 实体命中优先于热点——"zorvAI 上热搜了吗"这类问题，获取其官方/权威内容比泛搜更准确。
     */
    fun classify(question: String): IntentInfo {
        val q = question.trim()
        val lower = q.lowercase()

        val ent = EntityRegistry.match(q)
        if (ent != null) {
            return IntentInfo(IntentType.ENTITY_SELF, ent, Freshness.ANY, "命中已知实体，获取其官方/权威内容")
        }

        if (HOT_NEWS_SIGNALS.any { lower.contains(it) }) {
            val f = if (listOf("今天", "今日", "现在", "实时", "刚刚", "today", "now")
                    .any { lower.contains(it) }) Freshness.DAY else Freshness.WEEK
            return IntentInfo(IntentType.HOT_NEWS, null, f, "含热点/时效新闻信号")
        }

        val f = when {
            listOf("今天", "今日", "现在", "实时", "刚刚", "today", "now").any { lower.contains(it) } -> Freshness.DAY
            listOf("本周", "这周", "近日", "最近", "recent").any { lower.contains(it) } -> Freshness.WEEK
            listOf("本月", "今年", "month", "year").any { lower.contains(it) } -> Freshness.MONTH
            else -> Freshness.ANY
        }
        return IntentInfo(IntentType.GENERAL, null, f, "通用检索")
    }

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