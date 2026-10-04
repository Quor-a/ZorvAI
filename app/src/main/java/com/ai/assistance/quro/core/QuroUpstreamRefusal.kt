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
 * ## 判定口径
 *
 * 只认**高置信**特征短语（`request was rejected` / `considered high risk` /
 * `content policy violation` / `violates ... policy` / `blocked because of ... safety` 等），
 * 不做宽泛的「含 risk / 含 sorry 就判拒答」—— 正常回答里出现 risk、policy 很常见，
 * 误判会把真正的答案换成一句「被拦了」，比原问题更糟。
 *
 * 纯 Kotlin、不依赖 `org.json`，可被单测穷举覆盖。
 */
object QuroUpstreamRefusal {

    /** 归一化后的机器可读错误码（走 [ToolStatus] 之外的另一路：内容被拒不是工具失败）。 */
    const val CODE: String = "content_refused"

    private val PATTERNS: List<Regex> = listOf(
        Regex("the\\s+request\\s+was\\s+rejected", RegexOption.IGNORE_CASE),
        Regex("considered\\s+high\\s+risk", RegexOption.IGNORE_CASE),
        Regex("high\\s+risk\\s+(content|request|prompt|input)", RegexOption.IGNORE_CASE),
        Regex("content\\s+policy\\s+violation", RegexOption.IGNORE_CASE),
        Regex("violat\\w*\\s+(the\\s+)?(content\\s+)?(policy|policies|guidelines|terms\\s+of\\s+service)", RegexOption.IGNORE_CASE),
        Regex("blocked\\s+because\\s+of\\s+((your|our|the|its|any)\\s+)?(safety|content|security)", RegexOption.IGNORE_CASE),
        Regex("(safety|content)\\s+(policy|filter|settings?)\\s+(has\\s+been\\s+)?(violated|triggered|blocked)", RegexOption.IGNORE_CASE),
        Regex("request\\s+被\\s*拒绝|内容被(判定|视为)?(高风险|敏感)|涉及(敏感|政治)内容"),
        Regex("(抱歉|对不起|很遗憾)[^\\n]{0,30}(无法|不能|不能提供|不能回答|不予)"),
        Regex("(我|我们)(无法|不能)[^\\n]{0,30}(提供|回答|协助|帮助|生成|输出)"),
        Regex("(安全|内容|审核)\\s*(策略|机制|规则)[^\\n]{0,20}(禁止|拦截|不予|不允许)"),
    )

    /** 开头就道歉 + 明确拒绝（正文里提到 risk/sorry 但同时在真正帮忙的不算）。 */
    private val HEAD_REFUSAL: Regex =
        Regex("^\\s*(i'm\\s+sorry|i\\s+am\\s+sorry|sorry|抱歉|对不起)[^\\n]{0,80}?\\b(can'?t|cannot|can\\s?not|won'?t|不会|不能)\\b[^\\n]{0,80}?\\b(assist|help|fulfil|fulfill|provide|respond|answer|fulfil)\\b",
            RegexOption.IGNORE_CASE)

    /** 文本是不是一句上游拒答回执。 */
    fun detect(text: String): Boolean {
        if (text.isBlank()) return false
        return HEAD_REFUSAL.containsMatchIn(text) || PATTERNS.any { it.containsMatchIn(text) }
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
