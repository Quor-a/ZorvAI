package com.ai.assistance.quro.etalib.agent.model

import android.content.Context
import com.ai.assistance.quro.core.model.QuroModelConfig
import com.ai.assistance.quro.core.model.QuroModelConfigRepository
import com.ai.assistance.quro.etalib.agent.runtime.AgentAppContext
import com.ai.assistance.quro.etalib.config.Prefs
import com.ai.assistance.quro.etalib.data.model.ReasoningEffort

/**
 * Eta hook 层所需的模型协议数据契约。
 *
 * 这里**不执行任何模型调用**：真正的 Agent 回合由 ZorvAI 自己的
 * `QuroAgentBridgeProvider → QuroAgentTurnRunner → QuroAssistant → QuroLlmClient` 完成，
 * 使用 ZorvAI 自己的 `QuroModelConfigRepository`（用户配置的模型 / 服务商 / 密钥 / 工具集）。
 * Hook 侧保留这些类型，只为让厂商入口协议层原样传递上下文、历史与图片。
 */
internal object AgentModelClient {

    /** ZorvAI 当前模型配置的协议快照（实际推理参数由 app 侧决定，这里只作元数据）。 */
    data class ModelConfig(
        val providerId: String = "",
        val providerName: String = "",
        val providerType: String = "openai_compatible",
        val baseUrl: String = "",
        val apiKey: String = "",
        val model: String = "",
        val modelDisplayName: String = "",
        val systemPrompt: String = "",
        val contextWindow: Int? = null,
        val terminalTools: Boolean = true,
        val browserTools: Boolean = true,
        val deviceDirectTools: Boolean = true,
        val thinkingEnabled: Boolean = false,
        val reasoningEffort: ReasoningEffort? = null,
        val subagentTools: Boolean = true,
        val autoCompactionEnabled: Boolean = true,
    ) {
        val effectiveReasoningEffort: ReasoningEffort
            get() = reasoningEffort ?: ReasoningEffort.fromLegacy(thinkingEnabled)

        val knownContextWindow: Int?
            get() = contextWindow?.takeIf { it > 0 }
    }

    data class ConversationMessage(
        val role: String,
        val content: String = "",
        val contentJson: String = "",
        val toolCallId: String = "",
        val reasoningContent: String = "",
        val toolCallsJson: String = "",
        val contextSummary: Boolean = false,
        val compactedUserTurns: Int = 0,
        val summaryThroughUserTurn: Int = 0,
        val messageId: String = "",
    )

    data class ModelImage(
        val reference: String,
        val mimeType: String,
        val bytes: Int,
        val width: Int? = null,
        val height: Int? = null,
        val source: String = "unknown",
        val preserveOriginal: Boolean = false,
    )

    sealed interface ModelResponse {
        data class Text(
            val content: String,
            val reasoningContent: String = "",
            val transcript: List<ConversationMessage> = emptyList(),
            val contextSnapshot: AgentContextSnapshot? = null,
        ) : ModelResponse
    }

    /**
     * 读取 ZorvAI 自己的模型配置仓库并投影为协议快照。
     *
     * Hook 运行在 system_server / 小布 / 超级小爱等别的 UID 进程，读不到本应用私有
     * SharedPreferences，此时退化为默认快照；**实际模型选择始终发生在 app 进程的
     * QuroAgentTurnRunner**，那里读的是同一份 QuroModelConfigRepository，配置一致。
     */
    fun loadConfig(): ModelConfig {
        val ctx: Context? = runCatching { AgentAppContext.resolve() }.getOrNull()
        val cfg: QuroModelConfig = ctx?.let {
            runCatching { QuroModelConfigRepository(it).load() }.getOrNull()
        } ?: QuroModelConfig()
        return ModelConfig(
            providerId = cfg.provider,
            providerName = cfg.customProviderName.ifBlank { cfg.provider },
            baseUrl = cfg.baseUrl,
            apiKey = cfg.apiKey,
            model = cfg.model,
            contextWindow = cfg.contextWindow.takeIf { it > 0 },
            terminalTools = cfg.enableTools,
            browserTools = cfg.enableTools,
            deviceDirectTools = cfg.enableTools,
            thinkingEnabled = Prefs.isEnabled(Prefs.Keys.AGENT_THINKING_ENABLED),
            subagentTools = cfg.enableTools && cfg.maxToolRounds != 1,
            autoCompactionEnabled = cfg.contextCompressThreshold in 1..100,
        )
    }
}

internal class AgentModelExecutionException(
    cause: Throwable,
    val reasoningContent: String,
    val transcript: List<AgentModelClient.ConversationMessage>,
) : RuntimeException(cause)
