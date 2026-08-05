package com.ai.assistance.quro.core

/**
 * Zorv AI 核心契约。
 * 统一描述对话消息、工具调用、工具规格与 LLM 返回结果。
 */

/** 发送给 LLM 的一条消息。 */
data class QuroChatMessage(
    val role: String,                 // "system" | "user" | "assistant" | "tool"
    val content: String,
    val toolCalls: List<QuroToolCall>? = null, // 仅 assistant 且含工具调用时
    val toolCallId: String? = null,            // 仅 role="tool" 时
    val attachments: List<QuroAttachment>? = null, // 仅 user 且含附件时（图片送视觉模型）
    /** 仅 role="tool" 时携带工具名（function name）。标准 OpenAI 格式里工具名写在前面 assistant
     * 的 tool_calls[].function.name 上、tool 消息可省略；但 Kimi K3 等严格实现要求 tool 消息
     * 自身带 name（或靠顺序对齐），否则 400。这里在 toLlmMessages 收尾按 tool_call_id 反查补全。 */
    val toolName: String? = null,
)

/** 一次工具调用（LLM 产生；也可由引擎回填 [result] 供 UI 自包含展示）。 */
data class QuroToolCall(
    val id: String = "",
    val name: String,
    val arguments: String,            // 原始 JSON 字符串
    /** 工具执行结果（引擎执行完后回填进 assistant 消息的 toolCalls）。
     *  UI 直接从单条 assistant 消息读出「工具名 + 参数 + 结果」三件套，
     *  不再依赖跨消息 resultMap 匹配 role=tool 结果 → 彻底消除「工具调用展示缺失」。 */
    val result: String? = null,
    /** 工具本次执行耗时（毫秒），由 [QuroAssistant] 在 engine.execute 前后计时回填，UI 展示用。 */
    val durationMs: Long = 0,
)

/** 工具规格（下发给 LLM 的 function 描述）。 */
data class QuroToolSpec(
    val name: String,
    val description: String,
    val parametersJson: String,       // JSON Schema 对象字符串
)

/** LLM 返回结果。 */
sealed interface QuroLlmResult {
    data class Text(val content: String, val reasoning: String? = null) : QuroLlmResult
    /** 工具调用结果：[calls] 为本轮模型要求执行的工具列表；[reasoning] 为模型本轮的思考过程
     *  （MiMo 等 reasoning 模型会在 tool_calls 同时返回 reasoning_content，必须保留并在
     *   回传给模型时一并携带，否则模型每轮都在「失忆」状态下做下一步决策，
     *   无法链式编排多步工具调用）。 */
    data class ToolCalls(
        val calls: List<QuroToolCall>,
        val reasoning: String? = null,
        val content: String? = null,
    ) : QuroLlmResult
    data class Error(val message: String) : QuroLlmResult
}

/** 工具执行结果（引擎内部用）。 */
data class QuroToolResult(
    val name: String,
    val result: String,
)
