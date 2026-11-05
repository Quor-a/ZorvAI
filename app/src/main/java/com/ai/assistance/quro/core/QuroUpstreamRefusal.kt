package com.ai.assistance.quro.core

/**
 * 上游「拒答回执」的归一化（工具与环境层的异常处理补漏）。
 *
 * ## 为什么要有它
 *
 * [QuroLlmMeta.filtered] 只认 `finish_reason == content_filter` / `safety` / `blocklist` 这类
 * **结构化**停止原因。但相当一部分网关（各类 OpenAI 兼容代理、部分国内中转）根本不走这个字段 ——
 * 它们把拒答**直接写进 `content`**，`finish_reason` 照常是 `stop`。
 *
 * 于是旧链路的结局是：用户看到气泡里一句**原生英文**
 * `The request was rejected because it was considered high risk`。
 * 这既看不懂，还会被当成「AI 的正常回复」存进历史、下一轮继续喂给模型，
 * 等于把一句异种语言的错误回执当正文在会话里传。
 *
 * ## 判定口径（误判比漏判更糟）
 *
 * - **高置信硬回执**（`PATTERNS_HIGH`）：上游网关专属签名（英文拒答短语 / 中文机器腔
 *   「request 被拒绝 / 内容被判定高风险 / 涉及敏感内容」）。这些在正常回答里几乎不会自然出现，
 *   命中即判拒答，**不受豁免影响**。
 * - **软拒答**（`PATTERNS_SOFT` + `HEAD_REFUSAL`）：中文「道歉 + 不能/无法」或英文开头道歉。
 *   **单独命中不算拒答**——只有「没给替代方案 + 篇幅不长」才判拒答。
 *   正常回答常写「抱歉，我不能直接操作，但我可以给你步骤」，绝不能误换成「高风险拒答」。
 *
 * 纯 Kotlin、不依赖 `org.json`，可被单测穷举覆盖。
 */
object QuroUpstreamRefusal {

    /** 归一化后的机器可读错误码（走 [ToolStatus] 之外的另一路：内容被拒不是工具失败）。 */
    const val CODE: String = "content_refused"

    /**
     * 高置信拒答签名：上游网关**硬回执**或**结构化拒答**特征。
     *
     * 这些短语在正常回答里几乎不可能自然出现（主语是机器/request，或是「内容被判定高风险」
     * 这类上游专属表述），命中即判拒答——不受下方「给方案豁免」影响。
     */
    private val PATTERNS_HIGH: List<Regex> = listOf(
        Regex("the\\s+request\\s+was\\s+rejected", RegexOption.IGNORE_CASE),
        Regex("considered\\s+high\\s+risk", RegexOption.IGNORE_CASE),
        Regex("high\\s+risk\\s+(content|request|prompt|input)", RegexOption.IGNORE_CASE),
        Regex("content\\s+policy\\s+violation", RegexOption.IGNORE_CASE),
        Regex("violat\\w*\\s+(the\\s+)?(content\\s+)?(policy|policies|guidelines|terms\\s+of\\s+service)", RegexOption.IGNORE_CASE),
        Regex("blocked\\s+because\\s+of\\s+((your|our|the|its|any)\\s+)?(safety|content|security)", RegexOption.IGNORE_CASE),
        Regex("(safety|content)\\s+(policy|filter|settings?)\\s+(has\\s+been\\s+)?(violated|triggered|blocked)", RegexOption.IGNORE_CASE),
        // 上游专属中文硬回执：机器腔「request 被拒绝 / 内容被判定高风险 / 涉及敏感内容」
        Regex("request\\s+被\\s*拒绝|内容被(判定|视为)?(高风险|敏感)|涉及(敏感|政治)内容"),
    )

    /**
     * 软拒答特征：中文「道歉 + 不能/无法/不予」这类**可由正常回答凑出来**的句式。
     *
     * 单独命中**不算**拒答——必须再经过 [HELPFUL_MARKERS] 豁免与篇幅检查（见 [detect]）。
     * 正常回答常写「抱歉，我不能直接操作，但我可以给你步骤」，绝不能误判成「高风险拒答」。
     */
    private val PATTERNS_SOFT: List<Regex> = listOf(
        Regex("(抱歉|对不起|很遗憾)[^\\n]{0,30}(无法|不能|不能提供|不能回答|不予)"),
        Regex("(我|我们)(无法|不能)[^\\n]{0,30}(提供|回答|协助|帮助|生成|输出)"),
        Regex("(安全|内容|审核)\\s*(策略|机制|规则)[^\\n]{0,20}(禁止|拦截|不予|不允许)"),
    )

    /**
     * 开头就道歉 + 明确拒绝（英文为主；中文道歉句一般没有结尾的 assist/help 动词，由 PATTERNS_SOFT 兜）。
     * 命中后同样要走 [HELPFUL_MARKERS] 豁免。
     */
    private val HEAD_REFUSAL: Regex =
        Regex("^\\s*(i'm\\s+sorry|i\\s+am\\s+sorry|sorry|抱歉|对不起)[^\\n]{0,80}?\\b(can'?t|cannot|can\\s?not|won'?t|不会|不能)\\b[^\\n]{0,80}?\\b(assist|help|fulfil|fulfill|provide|respond|answer)\\b",
            RegexOption.IGNORE_CASE)

    /**
     * 「给方案 / 续作」豁免标记：命中任一即说明模型**真的在帮忙**，不是纯拒答。
     *
     * 仅用「但 / but」太弱（"I'm sorry, but I can't assist" 是纯拒答），必须是有实质替代/引导的标记。
     */
    private val HELPFUL_MARKERS: List<String> = listOf(
        "我可以", "你可以", "可以试", "可以帮", "可以看", "下面是", "以下",
        "建议你", "建议", "步骤", "方案", "替代", "或者", "换个", "其实",
        "不过我", "但是我可以", "不过你可以", "我给你", "我帮你",
        "but i can ", "but you can ", "but here", "here's", "here is",
        "instead", "alternatively", "i can help", "i can show", "let me ",
        "for example", "you could", "you can ",
    )

    /** 文本是不是一句上游拒答回执。 */
    fun detect(text: String): Boolean {
        if (text.isBlank()) return false
        val t = text.trim()

        // 1) 高置信硬回执：直接判拒答。
        if (PATTERNS_HIGH.any { it.containsMatchIn(t) }) return true

        // 2) 软拒答 / 开头拒答：只有「没给方案 + 篇幅不长」才算拒答，否则放行真答案。
        val softHit = PATTERNS_SOFT.any { it.containsMatchIn(t) } ||
            HEAD_REFUSAL.containsMatchIn(t)
        if (softHit) {
            val givesAlternative = HELPFUL_MARKERS.any { mk ->
                t.contains(mk, ignoreCase = true)
            }
            // 给了替代方案/引导 → 不是纯拒答；或篇幅过长（像在详细作答）→ 不判。
            if (givesAlternative || t.length > 120) return false
            return true
        }
        return false
    }

    /** 拒答原因码；不是拒答返回 `null`（调用方不该瞎填）。 */
    fun code(text: String): String? = if (detect(text)) CODE else null

    /**
     * 归一化成**用户看得懂、能据此行动**的中文提示。
     *
     * 原回执（英文、且主语是「request」这种机器腔）一概不保留：
     * 它既不像人话，又会在下一轮被当正文继续喂给模型。
     */
    fun normalize(text: String): String =
        "⚠️ 这次提问没被模型受理：上游安全策略判定该内容属于高风险，未产出任何有效内容。\n" +
            "· 换个说法、换个切入点再问一次，通常就能过；\n" +
            "· 如果「问什么都这样」，到「设置 → 模型配置」换一个上游模型再试。"

    /** 顺带帮工具/编排层判断：这一轮是不是拿回来的东西根本不能用。 */
    fun unusable(text: String): Boolean = detect(text)
}
