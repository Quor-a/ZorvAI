package com.ai.assistance.mnn

/**
 * 思考模型（Qwen3 / DeepSeek-R1 系）输出里的 `<think>...</think>` 段落切分器。
 *
 * ## 为什么需要
 * 思考模型会把推理过程原样吐在正文里：
 * ```
 * <think>用户问时间，我应该调用 get_current_time 工具……</think>
 * <tool_call>{"name":"get_current_time","arguments":{}}</tool_call>
 * ```
 * 如果不切分，会有三个直接后果：
 * 1. 用户看到一大段自言自语，以为模型"跑飞了"——这正是"有思考但是不能用"的观感来源；
 * 2. 思考段里常常出现**示例性质**的 `<tool_call>` 片段，会被工具解析器误当成真实调用；
 * 3. 思考段计入复读检测，容易误判退化。
 *
 * ## 切分规则
 * - 成对 `<think>...</think>`：内部为思考内容，其余拼接为正文。支持多段。
 * - 只有开标签没有闭标签（生成被 max_tokens 截断）：开标签之后全部算思考内容。
 * - 没有 think 标签：原样返回，[Split.reasoning] 为空。
 * - 切分后正文为空（模型只输出了思考）：正文回退为思考内容本身，
 *   避免界面上出现空气泡；此时 [Split.answerFromReasoning] 为 true，供上层提示。
 */
object MnnThinkContent {

    private const val OPEN_TAG = "<think>"
    private const val CLOSE_TAG = "</think>"

    /**
     * 切分结果。
     *
     * @property answer 去掉思考段后的正文（可直接展示 / 交给工具解析器）。
     * @property reasoning 抽出的思考内容；无思考段时为空串。
     * @property answerFromReasoning 正文是否是"因为原正文为空而回退的思考内容"。
     */
    data class Split(
        val answer: String,
        val reasoning: String,
        val answerFromReasoning: Boolean = false,
    ) {
        /** 是否检测到思考段。 */
        val hasReasoning: Boolean get() = reasoning.isNotEmpty()
    }

    /**
     * 切分模型原始输出。
     *
     * @param raw 模型输出的完整文本。
     * @return 切分结果；[raw] 为空时 answer / reasoning 均为空串。
     */
    fun split(raw: String): Split {
        if (raw.isEmpty() || !raw.contains(OPEN_TAG)) {
            return Split(answer = raw, reasoning = "")
        }

        val answerBuilder = StringBuilder(raw.length)
        val reasoningBuilder = StringBuilder()
        var cursor = 0

        while (cursor < raw.length) {
            val open = raw.indexOf(OPEN_TAG, cursor)
            if (open < 0) {
                answerBuilder.append(raw, cursor, raw.length)
                break
            }
            // 开标签之前的内容属于正文。
            answerBuilder.append(raw, cursor, open)

            val contentStart = open + OPEN_TAG.length
            val close = raw.indexOf(CLOSE_TAG, contentStart)
            if (close < 0) {
                // 未闭合：剩下全是思考内容（生成被截断的典型形态）。
                appendReasoning(reasoningBuilder, raw.substring(contentStart))
                cursor = raw.length
                break
            }
            appendReasoning(reasoningBuilder, raw.substring(contentStart, close))
            cursor = close + CLOSE_TAG.length
        }

        val reasoning = reasoningBuilder.toString().trim()
        val answer = answerBuilder.toString().trim()

        if (answer.isEmpty() && reasoning.isNotEmpty()) {
            // 模型只吐了思考段（常见于 max_tokens 太小）。
            // ⚠️ 回归修复（症状 1）：**不再**把 reasoning 当作 answer 回退展示——
            // 否则思考文本会"泄漏"进正文（UI 把 answer 当最终可见答案），即"思考泄漏"。
            // 上层（QuroLocalEngineNative）会在 answer 为空时显示"思考完毕"占位，
            // 因此空 answer 是安全可接受的；这里只留 reasoning 在 reasoning 字段。
            return Split(answer = "", reasoning = reasoning, answerFromReasoning = false)
        }
        return Split(answer = answer, reasoning = reasoning)
    }

    /**
     * 追加一段思考内容，多段之间用空行分隔。
     *
     * @param builder 累积缓冲。
     * @param segment 本段思考内容（未裁剪）。
     */
    private fun appendReasoning(builder: StringBuilder, segment: String) {
        val trimmed = segment.trim()
        if (trimmed.isEmpty()) return
        if (builder.isNotEmpty()) builder.append("\n\n")
        builder.append(trimmed)
    }
}
