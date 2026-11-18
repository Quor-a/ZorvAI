package com.ai.assistance.quro.etalib.agent.runtime

import com.ai.assistance.quro.etalib.agent.model.AgentContextSnapshot
import com.ai.assistance.quro.etalib.agent.model.AgentModelClient

/**
 * 厂商入口层与 Agent 运行时之间的数据契约（纯内存对象）。
 *
 * Eta 原版这里是一整套跨进程 Messenger/Bundle 序列化协议，因为它的 Agent Runtime
 * 是独立服务进程。ZorvAI 的运行时走自己的 `QuroAgentBridgeProvider`（ContentProvider），
 * 由 [AgentRuntimeClient] 直接把请求投影成 ZorvAI Bridge 的 Bundle，
 * 所以这里只保留 hook 层需要的数据结构，不再做序列化。
 */
internal object AgentRuntimeWire {

    const val OP_CHAT = "chat"
    const val OP_COMPACT = "compact"
    const val OP_REWRITE_REPLY = "rewrite_reply"
    const val AGENT_UI_HANDOFF_SOURCE = "agent_ui"
    const val ETA_VOICE_HANDOFF_SOURCE = "eta_voice"

    data class RunRequest(
        val runId: String,
        val prompt: String,
        val config: AgentModelClient.ModelConfig,
        val images: List<AgentModelClient.ModelImage> = emptyList(),
        val history: List<AgentModelClient.ConversationMessage> = emptyList(),
        val handoff: EntryHandoff? = null,
        val modelSessionId: String = "",
        val operation: String = OP_CHAT,
        val rewriteTargetMessageId: String? = null,
        val assistantScreenContext: String = "",
    ) {
        /** 会话键：优先用显式 session，其次用入口 handoff 的 id，最后落到 runId。 */
        val effectiveModelSessionId: String
            get() = modelSessionId.ifBlank { handoff?.id?.takeIf { it.isNotBlank() } ?: runId }
    }

    data class RunResult(
        val runId: String,
        val ok: Boolean,
        val content: String,
        val error: String? = null,
        val reasoningContent: String = "",
        val transcript: List<AgentModelClient.ConversationMessage> = emptyList(),
        val contextSnapshot: AgentContextSnapshot? = null,
        val contextSnapshotRef: String = "",
        val operation: String = OP_CHAT,
        val rewriteTargetMessageId: String? = null,
    )

    data class EntryHandoff(
        val id: String,
        val source: String,
        val payload: String,
        val dismissEntrySurfaceOnForegroundOperation: Boolean = false,
    )

    data class CompletedRun(
        val handoff: EntryHandoff,
        val result: RunResult,
        val createdAt: Long,
    )

    /** 活跃回合快照；ZorvAI Bridge 同步执行，该结构只用于协议兼容。 */
    data class ActiveRunSnapshot(
        val runId: String,
        val handoff: EntryHandoff? = null,
        val createdAt: Long = 0L,
    )
}
