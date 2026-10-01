package com.ai.assistance.quro.core

/**
 * 一次云端 LLM 调用的**结果元数据**：停止原因 + token 用量。
 *
 * ## 为什么必须有这个类型
 *
 * 改造前 `QuroLlmClient.parse()` 只从响应里取 `choices[0].message`，
 * `choices[0].finish_reason` 与顶层 `usage` **被逐字丢弃**（见缺口 C3）。
 * 后果（全部是用户可感知的故障，且无法归因）：
 *
 * 1. **回复被截断却假装正常**。`finish_reason == "length"` 表示模型撞到 `max_tokens` 上限、
 *    话没说完，但客户端返回的仍是「成功」，正文就断在半句 —— 用户只看到「AI 说话说一半」，
 *    既不知道为什么，也不知道可以「继续」。
 * 2. **内容被风控拦截无提示**。`finish_reason == "content_filter"` 时 `content` 为空，
 *    表现为「AI 不回复 / 空回复」，实际是上游安全策略拦截，本应明确报错。
 * 3. **思考开销完全不可见**。`usage.completion_tokens_details.reasoning_tokens` 是
 *    「开思考后回复变短、变慢」的直接解释（思考 token 挤占输出预算），却被丢弃，
 *    用户与开发者只能靠猜。
 * 4. **上下文腐化（context rot）无法预警**。`prompt_tokens` 持续逼近模型窗口是
 *    「越聊越笨」的前兆（缺口 C6），没有用量数据就做不了任何预警。
 *
 * ## 设计约束
 *
 * 纯 Kotlin、**不依赖 `org.json`** —— Android 单测里 `org.json` 是桩（调用即抛
 * "not mocked"），所以 JSON 抽取留在 [com.ai.assistance.quro.core.network.QuroLlmClient]，
 * 本类只做**归一化与判定**，从而可以被单测穷举覆盖。
 *
 * ## 容错口径
 *
 * - 各家 `finish_reason` 拼写不一致（Anthropic 用 `end_turn` / `max_tokens` / `tool_use`），
 *   统一由 [normalizeFinishReason] 归一到 4 个内部值。
 * - 用量字段用 `-1` 表示**未知**（不是 0）。网关不回 `usage` 是常态（尤其流式），
 *   把「未知」当「0」会让「思考占比」这类派生指标算出假数据。
 *
 * @param finishReason 已归一化的停止原因，见 [F_STOP] / [F_LENGTH] / [F_TOOL_CALLS] /
 *   [F_CONTENT_FILTER]；`null` = 上游未提供（非流式少见，流式在最后一帧）。
 * @param promptTokens 输入 token 数（OpenAI `prompt_tokens` / Anthropic `input_tokens`）。-1 = 未知。
 * @param completionTokens 输出 token 数（`completion_tokens` / `output_tokens`）。-1 = 未知。
 * @param reasoningTokens 输出里**属于思考**的 token 数（`completion_tokens_details.reasoning_tokens`）。-1 = 未知。
 * @param cachedTokens 命中提示缓存的输入 token 数（`prompt_tokens_details.cached_tokens`）。-1 = 未知。
 * @param totalTokens 总 token 数。未知时由 [withDerivedTotal] 用 prompt+completion 兜底补出。
 */
data class QuroLlmMeta(
    val finishReason: String? = null,
    val promptTokens: Int = UNKNOWN,
    val completionTokens: Int = UNKNOWN,
    val reasoningTokens: Int = UNKNOWN,
    val cachedTokens: Int = UNKNOWN,
    val totalTokens: Int = UNKNOWN,
) {

    /** 是否拿到了至少一个用量字段（用于诊断日志「上游没回 usage」）。 */
    val hasUsage: Boolean
        get() = promptTokens >= 0 || completionTokens >= 0 || totalTokens >= 0

    /**
     * 输出是否被**截断**（撞到 `max_tokens` / `max_output_tokens` 上限）。
     *
     * 这是 [QuroAssistant] 必须处理的信号：截断时正文是半句话，用户需要知道
     * 「可以回复『继续』」，而不是默默收到残缺答复。
     */
    val truncated: Boolean
        get() = finishReason == F_LENGTH

    /** 是否被上游内容安全策略拦截（`content_filter` / `recitation` / `blocklist`）。 */
    val filtered: Boolean
        get() = finishReason == F_CONTENT_FILTER

    /** 是否因模型拒绝回答而结束（OpenAI GPT-5 系新增 `refusal`）。 */
    val refused: Boolean
        get() = finishReason == F_REFUSAL

    /**
     * 是否属于「正常结束」。未知的停止原因**一律视为非正常**，便于上层显式判断，
     * 避免把没见过的 `finish_reason` 静默当成成功。
     */
    val normal: Boolean
        get() = finishReason == null || finishReason == F_STOP || finishReason == F_TOOL_CALLS

    /**
     * 思考 token 占输出的比例（百分比，0–100）。
     *
     * 用于解释「开了深度思考之后回复又短又慢」：占比高说明输出预算大半花在思考上。
     * 数据不足（未回 usage / 输出为 0）时返回 `-1`，**不返回 0** —— 0 会被误读成
     * 「思考没花钱」，而 -1 明确表示「测不出」。
     */
    val reasoningSharePercent: Int
        get() {
            if (completionTokens <= 0 || reasoningTokens < 0) return -1
            return (reasoningTokens * 100 / completionTokens).coerceIn(0, 100)
        }

    /** 未知用量时，用 prompt+completion 补一个 total；已有 total 则原样返回。 */
    fun withDerivedTotal(): QuroLlmMeta =
        if (totalTokens >= 0 || !hasUsage) this
        else copy(totalTokens = promptTokens.coerceAtLeast(0) + completionTokens.coerceAtLeast(0))

    /**
     * 单行诊断摘要，直接写进 Logcat（tag=QuroLlm）。
     * 例：`finish=stop prompt=1832 completion=418 reasoning=311(74%思考) cached=1024`
     */
    fun summary(): String = buildString {
        append("finish=").append(finishReason ?: "?")
        if (promptTokens >= 0) append(" prompt=").append(promptTokens)
        if (completionTokens >= 0) append(" completion=").append(completionTokens)
        if (reasoningTokens >= 0) {
            append(" reasoning=").append(reasoningTokens)
            val share = reasoningSharePercent
            if (share >= 0) append("(").append(share).append("%思考)")
        }
        if (cachedTokens >= 0) append(" cached=").append(cachedTokens)
        if (totalTokens >= 0) append(" total=").append(totalTokens)
        if (!hasUsage) append(" [上游未回 usage]")
        if (truncated) append(" [已截断]")
        if (filtered) append(" [被风控拦截]")
        if (refused) append(" [模型拒答]")
    }

    /**
     * 给用户的截断提示；非截断时返回 `null`。
     *
     * 只说三件用户能采取行动的事：**为什么断了**、**怎么接着要**、**怎么根治**。
     * 不暴露 `max_tokens` 数值等内部细节（用户配的是「最大输出长度」，不是 token 数）。
     */
    fun truncationHint(): String? = if (!truncated) null else
        "⚠️ 本次回复达到了单次输出上限被截断（不是网络中断）。回复「继续」即可接着写；" +
            "若经常发生，到「设置 → 模型配置」调大「最大输出长度」。"

    /** 给用户的拦截提示；非拦截时返回 `null`。 */
    fun filterHint(): String? = if (!filtered) null else
        "⚠️ 本次请求被上游内容安全策略拦截（finish_reason=content_filter），模型未产出任何内容。" +
            "请调整提问措辞后重试。"

    companion object {
        /** 用量未知的哨兵值。用 -1 而非 0：0 是「真的是 0」，-1 是「上游没给」。 */
        const val UNKNOWN: Int = -1

        /** 正常说完 / 正常给出工具调用之外的收尾。 */
        const val F_STOP: String = "stop"

        /** 撞到输出上限被截断 —— N3 的核心监控信号。 */
        const val F_LENGTH: String = "length"

        /** 正常结束，等待客户端执行工具调用。 */
        const val F_TOOL_CALLS: String = "tool_calls"

        /** 被上游安全策略拦截。 */
        const val F_CONTENT_FILTER: String = "content_filter"

        /** 模型拒答（GPT-5 系）。 */
        const val F_REFUSAL: String = "refusal"

        /** 空元数据（上游什么都没给）。 */
        val EMPTY: QuroLlmMeta = QuroLlmMeta()

        /**
         * 把各家的 `finish_reason` 归一成 4 个内部值 + 2 个特殊值。
         *
         * 归一表（**大小写无关**，前后空白自动去除）：
         * - `stop` / `end_turn`（Anthropic）/ `stop_sequence`（Anthropic）/ `eos` → [F_STOP]
         * - `length` / `max_tokens`（Anthropic & 多家网关）/ `max_output_tokens`（Responses API）
         *   / `max_completion_tokens` → [F_LENGTH]
         * - `tool_calls` / `function_call`（旧版 OpenAI）/ `tool_use`（Anthropic） → [F_TOOL_CALLS]
         * - `content_filter` / `safety`（Gemini）/ `blocklist`（Anthropic）/ `prohibited_content`
         *   / `recitation`（Anthropic 版权） → [F_CONTENT_FILTER]
         * - `refusal` → [F_REFUSAL]
         * - 其余原样返回小写（**不吞掉**未知值：宁可让日志出现没见过的值，也不要静默当成 stop）
         *
         * 字面量 `"null"` 与空串都返回 `null` —— Android `JSONObject.optString` 在值为
         * JSON null 时会返回**字符串** `"null"`，这是本仓已踩过的坑（见 `safeString`）。
         */
        fun normalizeFinishReason(raw: String?): String? {
            val r = raw?.trim()?.lowercase() ?: return null
            if (r.isEmpty() || r == "null") return null
            return when (r) {
                "stop", "end_turn", "stop_sequence", "eos", "eos_token" -> F_STOP
                "length", "max_tokens", "max_output_tokens", "max_completion_tokens",
                "model_length" -> F_LENGTH
                "tool_calls", "function_call", "tool_use" -> F_TOOL_CALLS
                "content_filter", "safety", "blocklist", "prohibited_content", "recitation" -> F_CONTENT_FILTER
                "refusal" -> F_REFUSAL
                else -> r
            }
        }

        /** 便捷构造：只给归一化前的原始 `finish_reason`。 */
        fun ofFinishReason(raw: String?): QuroLlmMeta =
            QuroLlmMeta(finishReason = normalizeFinishReason(raw))
    }
}
