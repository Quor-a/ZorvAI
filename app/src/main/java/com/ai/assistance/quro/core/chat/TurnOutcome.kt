package com.ai.assistance.quro.core.chat

import com.ai.assistance.quro.core.QuroLlmMeta

/**
 * 一轮回复**结束时**的真实状态。
 *
 * ## 为什么需要它
 *
 * 改造前，一轮生成的所有异常收尾都塌缩成同一句话：「⚠️ 回复生成失败：<异常消息>」。
 * 用户看到的现象与真实原因之间隔着一层：
 *
 * | 用户看到 | 真实原因 | 用户以为 |
 * |---|---|---|
 * | 「生成失败」 | `finish_reason=length`，模型话没说完 | 网络坏了 |
 * | 「生成失败」 | 模型只输出了 reasoning、正文为空 | 模型不会回答 |
 * | 「生成失败」 | 工具全跑完了，模型没接话 | 工具坏了 |
 * | 「生成失败」 | 模型连续两次吐出畸形 tool_calls | 应用有 bug |
 *
 * 这四类的**处理动作完全不同**（继续 / 换模型 / 换工具 / 换输入），
 * 但塌缩成一句话之后，用户只能反复重试。
 *
 * ## 设计约束
 *
 * - 纯 Kotlin、无 Android 依赖，可被 JVM 单测穷举。
 * - [resolve] 是**纯函数**：输入快照、输出结论，不做 I/O、不读全局状态。
 * - 判定顺序即优先级，见 [resolve] 内的注释 —— 顺序本身是设计：
 *   用户主动停止永远压过一切；上游风控与长度截断压过「空回复」。
 */
enum class TurnOutcome {
    /** 正常拿到正文。 */
    OK,

    /** 用户点了停止。 */
    STOPPED_BY_USER,

    /** 同一会话里又发了一条，本轮被新轮顶掉（barge-in）。 */
    SUPERSEDED,

    /** 上游链路异常（网络 / 鉴权 / 5xx），原样保留异常文案。 */
    FAILED,

    /** 上游内容安全策略拦截（`finish_reason=content_filter` / `refusal`）。 */
    CONTENT_FILTERED,

    /** 撞到输出长度上限，正文是半句话。 */
    OUTPUT_TRUNCATED,

    /** 模型只给了思考过程，正文一个字都没有。 */
    REASONING_ONLY,

    /** 工具都执行完了，模型没有再说话。 */
    EMPTY_AFTER_TOOLS,

    /** 模型连续多次发出无法解析的 tool_calls。 */
    MALFORMED_TOOL_CALL,

    /** 工具名或参数在本地执行不了（未注册 / 参数校验失败 / 权限不足）。 */
    UNEXECUTABLE_TOOL_CALL,

    /** 模型什么都没给（无正文、无思考、无工具）。 */
    EMPTY_RESPONSE,
    ;

    /** 是否属于「用户读不到有效答复」的失败终态（决定是否显示状态条）。 */
    val isFailure: Boolean
        get() = this != OK
}

/**
 * 一轮生成的可观测快照 —— [TurnOutcomeResolver] 的唯一输入。
 *
 * 全部字段都由 `QuroChatViewModel` / `QuroAssistant` 在收尾时从**已有数据**里取，
 * 不额外发请求、不额外计时。
 *
 * @param stoppedByUser 用户是否点了停止（cancellation 来自用户手势）。
 * @param superseded 是否被同会话的新一轮取代（barge-in），与主动停止要分开归因。
 * @param visibleChars 本轮**给用户看的正文**字符数（不含思考、不含工具结果）。
 * @param reasoningChars 本轮思考过程字符数。
 * @param toolCalls 本轮实际执行的工具调用条数。
 * @param toolFailures 其中失败的条数。
 * @param malformedToolCalls 模型发出、但无法解析/无法映射的 tool_calls 次数。
 * @param finishReason 归一化后的停止原因（见 `QuroLlmMeta.F_*`）；`null` = 上游未给。
 * @param errorMessage 链路异常文案；`null` = 没有抛异常。
 */
data class TurnSnapshot(
    val stoppedByUser: Boolean = false,
    val superseded: Boolean = false,
    val visibleChars: Int = 0,
    val reasoningChars: Int = 0,
    val toolCalls: Int = 0,
    val toolFailures: Int = 0,
    val malformedToolCalls: Int = 0,
    val finishReason: String? = null,
    val errorMessage: String? = null,
)

/**
 * 把快照归结成一个 [TurnOutcome]。
 *
 * 判定顺序（**从上到下，命中即返回**）：
 *
 * 1. 用户停止 —— 这是用户的意图，不是故障，必须最先判。
 * 2. 被新轮顶掉 —— 同上，属于交互结果而非错误。
 * 3. 链路异常 —— 有异常就当失败，不再猜别的原因。
 * 4. 上游风控 —— `content_filter` / `refusal`：模型确实回应了，但被拦了。
 * 5. 长度截断 —— `length` 且正文非空：话没说完，可以「继续」。
 * 6. 畸形工具调用 —— 模型的问题，不是应用的问题。
 * 7. 只有思考 —— 正文空、思考非空：思考预算吃光了输出。
 * 8. 工具后无回复 —— 工具跑了但模型没接话。
 * 9. 工具执行失败 —— 全失败且没正文：环境/权限问题。
 * 10. 彻底空回复。
 * 11. 其余一律正常。
 *
 * 注意第 5 与第 9 的边界：正文非空时**永远**优先判 OK 或 TRUNCATED，
 * 不因为「有工具失败」就把一次成功的回答判成失败 —— 用户读到的内容才是结论。
 */
object TurnOutcomeResolver {

    /**
     * 助手在「模型没给正文」时塞进 content 的占位文案 —— 判定时**视同空正文**。
     *
     * 这些串是渲染兜底（避免气泡空白），不是模型说的话。若拿它们算「有正文」，
     * 就会把「只思考没回答」误判成正常回复，归因彻底失效。
     */
    val NO_CONTENT_PLACEHOLDERS: Set<String> = setOf(
        "(已思考完毕)",
    )

    /** 正文短于此长度且 `finish_reason=length` 时，视为「截断到没有内容」。 */
    private const val TRUNCATED_MIN_CHARS = 220

    /** 连续畸形工具调用的判定阈值。 */
    private const val MALFORMED_THRESHOLD = 2

    fun resolve(s: TurnSnapshot): TurnOutcome {
        if (s.stoppedByUser) return TurnOutcome.STOPPED_BY_USER
        if (s.superseded) return TurnOutcome.SUPERSEDED
        if (!s.errorMessage.isNullOrBlank()) return TurnOutcome.FAILED

        val fr = s.finishReason
        if (fr == QuroLlmMeta.F_CONTENT_FILTER || fr == QuroLlmMeta.F_REFUSAL) {
            return TurnOutcome.CONTENT_FILTERED
        }

        if (fr == QuroLlmMeta.F_LENGTH) {
            return if (s.visibleChars < TRUNCATED_MIN_CHARS) TurnOutcome.OUTPUT_TRUNCATED
            else TurnOutcome.OK // 有实质内容，只是被截在半句：仍算「答了」，由正文尾部提示续写
        }

        if (s.malformedToolCalls >= MALFORMED_THRESHOLD) return TurnOutcome.MALFORMED_TOOL_CALL

        if (s.visibleChars > 0) return TurnOutcome.OK

        if (s.reasoningChars > 0) return TurnOutcome.REASONING_ONLY
        if (s.toolCalls > 0 && s.toolFailures >= s.toolCalls) return TurnOutcome.UNEXECUTABLE_TOOL_CALL
        if (s.toolCalls > 0) return TurnOutcome.EMPTY_AFTER_TOOLS
        return TurnOutcome.EMPTY_RESPONSE
    }
}
