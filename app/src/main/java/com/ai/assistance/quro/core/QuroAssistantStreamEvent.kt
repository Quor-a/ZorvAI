package com.ai.assistance.quro.core

/**
 * [QuroAssistant.ask] 在回合执行过程中向外暴露的实时事件（纯内存对象，零 Android 依赖）。
 *
 * 主对话框通过 `QuroConversationStore` 的占位气泡 + [QuroAgentTrace] 消费同一批信息；
 * 厂商助手入口（小布 / 超级小爱）需要跨进程把这些信息送到 Eta hook 渲染器，
 * 因此在 [QuroAssistant] 内部同一批插桩点上额外发一份结构化事件。
 *
 * 语义约定：[TextAccumulated] / [ThinkingAccumulated] 携带的是**本轮累计文本**
 * （与 [QuroLlmClient] 的 onToken / onThinking 回调口径一致，每轮 LLM 请求从空串重新开始），
 * 由消费方做差分成增量 delta；这样插桩点不需要维护任何跨轮状态。
 *
 * 该回调默认为 null：主聊、语音球、IM、桌宠等既有调用方行为完全不变。
 */
sealed interface QuroAssistantStreamEvent {

    /** 可见正文的累计文本（每轮 LLM 请求独立累计）。 */
    data class TextAccumulated(val text: String) : QuroAssistantStreamEvent

    /** 思考过程 reasoning_content 的累计文本（每轮 LLM 请求独立累计）。 */
    data class ThinkingAccumulated(val text: String) : QuroAssistantStreamEvent

    /** 模型决定调用工具。 */
    data class ToolStarted(
        val callId: String,
        val name: String,
        val argsPreview: String,
    ) : QuroAssistantStreamEvent

    /** 工具执行完毕。 */
    data class ToolFinished(
        val callId: String,
        val name: String,
        val resultSummary: String,
        val success: Boolean?,
    ) : QuroAssistantStreamEvent

    /** 进入新一轮 ReAct / LLM 请求。 */
    data class RoundStarted(val round: Int) : QuroAssistantStreamEvent
}
