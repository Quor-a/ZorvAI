package com.ai.assistance.quro.etalib.agent.runtime

import com.ai.assistance.quro.etalib.core.toSafeLogToken

/**
 * Eta hook 层消费的事件契约。
 *
 * ZorvAI 的 Agent Bridge 是「一次请求 → 一次完整答复」的内容提供者协议，
 * 不产生逐 token 的流式事件；桥接层只在回合开始/结束合成必要事件，
 * 让 Eta 的流式渲染器（小爱卡片、小布思考态）保持原有行为不变。
 */
internal sealed interface AgentEvent {
    fun toLogLine(): String

    enum class AssistantBlockKind {
        TEXT,
        THINKING,
        TOOL_CALL,
    }

    data class RunStarted(
        val initialImages: Int = 0,
        val initialImageBytes: Int = 0,
        val toolCount: Int = 0,
        val terminalTools: Boolean = false,
    ) : AgentEvent {
        override fun toLogLine(): String = "run_started images=$initialImages"
    }

    data class ModelRetryScheduled(
        val round: Int = 0,
        val attempt: Int = 0,
        val maxAttempts: Int = 0,
        val delayMs: Int = 0,
        val reasonCode: String = "",
        val displayMessage: String,
    ) : AgentEvent {
        override fun toLogLine(): String = "model_retry_scheduled round=$round"
    }

    data class ProviderRequestStarted(val round: Int = 0) : AgentEvent {
        override fun toLogLine(): String = "provider_request_started round=$round"
    }

    data class ProviderResponseStarted(val round: Int = 0, val httpCode: Int = 0) : AgentEvent {
        override fun toLogLine(): String = "provider_response_started round=$round"
    }

    data class AssistantBlockStart(
        val round: Int = 0,
        val kind: AssistantBlockKind,
        val index: Int = 0,
        val blockId: String? = null,
        val name: String? = null,
    ) : AgentEvent {
        override fun toLogLine(): String = "assistant_block_start kind=$kind"
    }

    data class AssistantBlockDelta(
        val round: Int = 0,
        val kind: AssistantBlockKind,
        val index: Int = 0,
        val deltaChars: Int = 0,
        val delta: String,
    ) : AgentEvent {
        override fun toLogLine(): String = "assistant_block_delta kind=$kind chars=$deltaChars"
    }

    data class AssistantBlockEnd(
        val round: Int = 0,
        val kind: AssistantBlockKind,
        val index: Int = 0,
        val blockId: String? = null,
        val name: String? = null,
        val contentChars: Int = 0,
        val replacementContent: String? = null,
    ) : AgentEvent {
        override fun toLogLine(): String = "assistant_block_end kind=$kind"
    }

    data class ToolStarted(
        val round: Int = 0,
        val toolCallId: String = "",
        val name: String,
        val argsPreview: String = "",
        val command: String? = null,
    ) : AgentEvent {
        override fun toLogLine(): String = "tool_started name=${name.toSafeLogToken()}"
    }

    data class ToolFinished(
        val round: Int = 0,
        val toolCallId: String = "",
        val name: String,
        val resultSummary: String = "",
        val imageCount: Int = 0,
        val imageBytes: Int = 0,
        val success: Boolean? = null,
        val command: String? = null,
    ) : AgentEvent {
        override fun toLogLine(): String = "tool_finished name=${name.toSafeLogToken()}"
    }
}

/** token 用量事件所需的数据结构（ZorvAI Bridge 不上报用量，保留类型以兼容协议）。 */
internal data class AgentTokenUsage(
    val contextTokens: Int = 0,
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    val reasoningTokens: Int = 0,
    val cachedTokens: Int = 0,
    val stepCount: Int = 0,
    val llmMs: Long = 0L,
    val toolMs: Long = 0L,
    val ttftMs: Long = 0L,
    val ttftSteps: Int = 0,
    val decodeMs: Long = 0L,
    val decodeTokens: Int = 0,
)

internal enum class GuiControlStatus(val wireValue: String) {
    IDLE("idle"),
    RUNNING("running"),
    WAITING_USER("waiting_user"),
    STOPPED("stopped"),
}
