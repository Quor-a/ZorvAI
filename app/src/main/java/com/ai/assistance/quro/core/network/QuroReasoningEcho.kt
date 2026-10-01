package com.ai.assistance.quro.core.network

/**
 * 「历史里 assistant 消息的 reasoning 要不要回传给上游」的策略（N6 / 缺口 C7）。
 *
 * ## 为什么需要单独一个策略对象
 *
 * 改造前这个判定写死在请求构造处：
 * ```kotlin
 * messageToJson(m, emitReasoning = !isReasoningModel)
 * ```
 * 而 `isReasoningModel` 的**真实语义**是 `QuroReasoningControl` 给出的
 * `useMaxCompletionTokens` —— 即「这个模型只认 `max_completion_tokens` 不认 `max_tokens`」。
 *
 * 这两件事**完全无关**：
 * - 「用哪个 token 上限字段」= 请求参数**格式**问题；
 * - 「能不能把 reasoning 回传」= 上游**是否认识这个字段**的问题。
 *
 * 它们碰巧在今天给出相同答案（o 系 / GPT-5 两者都是「是」），于是被写成了一个 `!`。
 * 但如果哪天 [QuroReasoningControl] 为另一个家族也打开 `useMaxCompletionTokens`
 * （比如某厂商同时要求新字段名、又**支持** reasoning 回传），
 * 这一行会**静默地**把该家族的 reasoning 回传关掉 —— 表现为「AI 突然开始失忆、
 * 多步工具编排断链」，而没有任何日志指向原因。
 *
 * 本对象把判定显式化、加注释、加单测，让「每个家族为什么是这个答案」有据可查，
 * 且新增家族时**必须**在这里做一个明确的决定。
 *
 * ## 各家族结论（含不确定性说明）
 *
 * | 家族 | 回传？ | 依据 |
 * |---|---|---|
 * | [QuroReasoningControl.Family.OPENAI_EFFORT]（o 系 / GPT-5 系） | **否** | 该家族不接受 `reasoning_content` 字段，回传会被上游拒收整轮。这是改造前就有的行为（经 `!isReasoningModel` 得到），此处只是把它写明。 |
 * | [QuroReasoningControl.Family.ANTHROPIC_THINKING]（Claude） | **是** | Claude 侧要求把思考块（带 `signature`）**原样回传**，否则开启思考后的多轮工具编排会断链；经 OpenAI 兼容网关时这些块通常映射为 `reasoning_content`。若网关不识别该字段，绝大多数实现会忽略未知字段而非报错。 |
 * | 其余（MiMo / DeepSeek / Qwen3 / DashScope / 未知） | **是** | MiMo 系明确要求 `requiresReasoningContentOnAssistantMessages`；其余为**保持改造前行为**（默认 `true`）。这条是有意保守：现网已验证可用，未经真机验证不擅自收紧。 |
 *
 * ## 一条刻意的「不做」
 *
 * 恒思考家族（`deepseek-r*` / `qwq` / `reasoner` 等）**不做**特殊处理：它们的思考是模型自身
 * 行为，与我们回传与否无关；而「不回传」对部分网关反而会导致
 * 「assistant 带 tool_calls 但缺 reasoning_content」的校验失败。
 *
 * 纯 Kotlin（复用 [QuroReasoningControl.detectFamily]，那套家族判定已有 20 例单测覆盖）。
 */
object QuroReasoningEcho {

    /**
     * 是否把 assistant 消息的 `reasoning_content` 一并回传给上游。
     *
     * 默认 `true`：这是改造前的行为，也是唯一不会引入回归的保守选择。
     * 只有**确知上游不认识该字段**的家族才返回 `false`。
     */
    fun shouldEcho(provider: String, baseUrl: String, model: String): Boolean =
        QuroReasoningControl.detectFamily(provider, baseUrl, model) !=
            QuroReasoningControl.Family.OPENAI_EFFORT

    /**
     * 一行诊断，用于 Logcat。
     *
     * 之所以要有它：这个判定错了不会报错、只会让模型「失忆」，
     * 所以必须在日志里能直接看到「本轮为什么回传/不回传」。
     */
    fun diagnosis(provider: String, baseUrl: String, model: String): String {
        val family = QuroReasoningControl.detectFamily(provider, baseUrl, model)
        val echo = family != QuroReasoningControl.Family.OPENAI_EFFORT
        return buildString {
            append("reasoningEcho=").append(echo)
            append(" family=").append(family)
            append(
                when (family) {
                    QuroReasoningControl.Family.OPENAI_EFFORT ->
                        "（该家族不认识 reasoning_content，回传会被整轮拒收）"
                    QuroReasoningControl.Family.ANTHROPIC_THINKING ->
                        "（Claude 要求思考块原样回传，否则多轮工具编排断链）"
                    QuroReasoningControl.Family.ALWAYS_ON ->
                        "（恒思考模型：回传与否由上游决定，保持默认回传）"
                    else -> "（保持改造前默认：回传，兼容 MiMo 等要求回传的模型）"
                },
            )
        }
    }
}
