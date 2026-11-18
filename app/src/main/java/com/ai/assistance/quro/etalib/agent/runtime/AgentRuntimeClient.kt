package com.ai.assistance.quro.etalib.agent.runtime

import android.content.Context
import com.ai.assistance.quro.core.assistant.QuroAgentBridgeProtocol
import com.ai.assistance.quro.core.xposed.QuroAgentBridgeClient
import com.ai.assistance.quro.etalib.config.Prefs
import com.ai.assistance.quro.etalib.core.AgentLogger
import com.ai.assistance.quro.etalib.core.safeLogType
import org.json.JSONObject
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * 厂商入口层 → ZorvAI Agent 的运行时客户端。
 *
 * Eta 在这里绑定一个独立的 Agent Runtime 服务进程（Messenger + Binder 图片传输）。
 * ZorvAI 不需要第二套运行时：它自己就有完整的 Agent 栈
 * `QuroAgentBridgeClient → QuroAgentBridgeProvider → QuroAgentTurnRunner → QuroAssistant`
 * （含工具注册表、会话存储、人格、技能、记忆、模型配置仓库）。
 * 因此本类把 Eta 的 RunRequest 投影为 ZorvAI Bridge 的一次 runTurn 调用，
 * 并把结果还原为 Eta hook 层期待的 RunResult / 事件流。
 *
 * 运行位置：system_server / 小布 / 超级小爱等被 Hook 的进程。
 * 所有失败都返回结构化失败结果，绝不把异常抛回宿主进程。
 */
internal class AgentRuntimeClient(
    private val context: Context,
    private val logger: AgentLogger,
) {
    /** 与 Eta 协议兼容的查询结果类型，供桥接层内部使用。 */
    sealed interface AttachOutcome {
        data class Completed(val result: AgentRuntimeWire.RunResult) : AttachOutcome
        data object NotActive : AttachOutcome
        data object Unavailable : AttachOutcome
    }

    sealed interface ActiveRunQuery {
        data class Known(val runId: String?) : ActiveRunQuery
        data object Unavailable : ActiveRunQuery
    }

    sealed interface ActiveRunsQuery {
        data class Known(val runs: List<AgentRuntimeWire.ActiveRunSnapshot>) : ActiveRunsQuery
        data object Unavailable : ActiveRunsQuery
    }

    sealed interface CompletedRunsQuery {
        data class Known(val runs: List<AgentRuntimeWire.CompletedRun>) : CompletedRunsQuery
        data object Unavailable : CompletedRunsQuery
    }

    /**
     * 跑一个完整 Agent 回合。
     *
     * 同步语义与 Eta 一致：调用线程阻塞直到 ZorvAI Agent 给出最终答复或超时。
     * 与此同时，本方法在独立线程按 requestId 轮询 ZorvAI Bridge 的事件流，
     * 把主聊同款的逐字正文、思考流、工具调用、重试实时转成 Eta 的 [AgentEvent]，
     * 交给小布 / 超级小爱渲染器——界面能力与 ZorvAI 对话框对等，而不是一次性蹦出全文。
     *
     * 事件流不可用时（旧版 app / provider 未启动 / binder 异常）自动降级为一次性全文，
     * 保证已经可用的路径不受影响。
     */
    fun run(
        request: AgentRuntimeWire.RunRequest,
        onEvent: (AgentEvent) -> Unit,
    ): AgentRuntimeWire.RunResult {
        val source = request.handoff?.source?.let(::sourceOf) ?: QuroAgentBridgeProtocol.SOURCE_SYSTEM
        val hiddenContext = buildHiddenContext(request)
        val imageRefs = request.images.map { it.reference }.filter { it.isNotBlank() }
        val lite = !Prefs.isEnabled(Prefs.Keys.AGENT_CUSTOM_MODEL)

        val poller = TurnEventPoller(
            resolver = context.contentResolver,
            runId = request.runId,
            logger = logger,
            onEvent = onEvent,
        )
        poller.start()

        val json: JSONObject = runCatching {
            QuroAgentBridgeClient.runTurn(
                resolver = context.contentResolver,
                source = source,
                text = request.prompt,
                hiddenContext = hiddenContext,
                personaId = "",
                requestId = request.runId,
                timeoutMs = QuroAgentBridgeProtocol.DEFAULT_TIMEOUT_MS,
                lite = lite,
                imageRefs = imageRefs,
                streamEvents = request.runId.isNotBlank(),
            )
        }.getOrElse { throwable ->
            logger.warn("ZorvAI Agent Bridge 调用失败: ${throwable.safeLogType()}")
            JSONObject().apply {
                put(QuroAgentBridgeProtocol.RESULT_OK, false)
                put(QuroAgentBridgeProtocol.RESULT_TEXT, "")
                put(QuroAgentBridgeProtocol.RESULT_ERROR, "Agent Bridge 不可用（${throwable.safeLogType()}）")
            }
        }

        // 回合已结束：把剩余事件全部 drain 完再收尾，避免最后一段正文 / 思考丢失。
        val streamedText = poller.awaitTerminal()

        val ok = json.optBoolean(QuroAgentBridgeProtocol.RESULT_OK, false)
        val content = json.optString(QuroAgentBridgeProtocol.RESULT_TEXT, "")
        val error = json.optString(QuroAgentBridgeProtocol.RESULT_ERROR, "").takeIf { it.isNotBlank() }

        // 把结果写回 ZorvAI 自己的收件箱，保持「可观测」语义（失败也留下原因）。
        runCatching {
            QuroAgentBridgeClient.deliver(context.contentResolver, source, request.prompt, json)
        }.onFailure { logger.warn("ZorvAI Agent 结果回执失败: ${it.safeLogType()}") }

        // 降级：事件流完全没送达正文时，才补一次完整文本，避免界面空白。
        if (!streamedText && ok && content.isNotBlank()) {
            runCatching {
                onEvent(
                    AgentEvent.AssistantBlockDelta(
                        kind = AgentEvent.AssistantBlockKind.TEXT,
                        delta = content,
                        deltaChars = content.length,
                    )
                )
            }.onFailure { logger.warn("入口流式渲染器降级增量事件失败: ${it.safeLogType()}") }
        }

        val result = AgentRuntimeWire.RunResult(
            runId = request.runId,
            ok = ok,
            content = content,
            error = error,
            reasoningContent = if (ok) {
                json.optString(QuroAgentBridgeProtocol.RESULT_REASONING, "")
            } else "",
            operation = request.operation,
            rewriteTargetMessageId = request.rewriteTargetMessageId,
        )

        // 留下已完成回合，供入口层后台补给（进程重启后由 ZorvAI 收件箱恢复，这里是同进程快照）。
        request.handoff?.let { handoff ->
            completedRuns[request.runId] = AgentRuntimeWire.CompletedRun(
                handoff = handoff,
                result = result,
                createdAt = System.currentTimeMillis(),
            )
            while (completedRuns.size > MAX_COMPLETED_RUNS) {
                val oldest = completedRuns.entries.minByOrNull { it.value.createdAt }?.key ?: break
                completedRuns.remove(oldest)
            }
        }
        return result
    }

    /**
     * 历史 + 入口交接上下文 + 屏幕观察，作为「内部上下文」随请求一起交给 ZorvAI Agent。
     * 与 ZorvAI Bridge 的约定一致：这部分只给模型判断，不原样复述给用户。
     */
    private fun buildHiddenContext(request: AgentRuntimeWire.RunRequest): String = buildString {
        if (request.history.isNotEmpty()) {
            appendLine("[厂商入口历史对话]")
            request.history.takeLast(MAX_HISTORY_MESSAGES).forEach { message ->
                val text = message.content.ifBlank { message.contentJson }
                if (text.isBlank()) return@forEach
                append(message.role.take(16)).append(": ").append(text.take(MAX_HISTORY_CHARS))
                appendLine()
            }
            appendLine()
        }
        request.assistantScreenContext.takeIf { it.isNotBlank() }?.let {
            appendLine("[屏幕观察]").appendLine(it.take(MAX_SCREEN_CONTEXT_CHARS))
        }
        request.handoff?.payload?.takeIf { it.isNotBlank() }?.let {
            appendLine("[入口交接元数据]").appendLine(it.take(MAX_HANDOFF_CHARS))
        }
    }.toString().take(MAX_HIDDEN_CONTEXT_CHARS)

    private fun sourceOf(handoffSource: String): String = when {
        handoffSource.contains("breeno", ignoreCase = true) -> QuroAgentBridgeProtocol.SOURCE_BREENO
        handoffSource.contains("xiaoai", ignoreCase = true) ||
            handoffSource.contains("mi", ignoreCase = true) -> QuroAgentBridgeProtocol.SOURCE_XIAOAI
        else -> QuroAgentBridgeProtocol.normalizeSource(handoffSource)
    }

    /** 确认结果已消费：ZorvAI Bridge 同步交付，确认即移除本地快照，幂等返回 true。 */
    fun ackResult(runId: String): Boolean {
        if (runId.isBlank()) return false
        completedRuns.remove(runId)
        return true
    }

    fun cancelRun(runId: String) {
        if (runId.isBlank()) return
        cancelled.add(runId)
        completedRuns.remove(runId)
    }

    /** 拉取未交付的后台结果（同进程快照；跨进程由 ZorvAI 收件箱承接）。 */
    fun drainCompletedRuns(): List<AgentRuntimeWire.CompletedRun> = runCatching {
        completedRuns.values
            .filter { it.result.runId !in cancelled }
            .sortedBy { it.createdAt }
            .toList()
    }.getOrDefault(emptyList())

    fun queryCompletedRuns(): CompletedRunsQuery =
        CompletedRunsQuery.Known(drainCompletedRuns())

    fun queryActiveRun(): ActiveRunQuery = ActiveRunQuery.Known(null)

    fun queryActiveRuns(): ActiveRunsQuery = ActiveRunsQuery.Known(emptyList())

    private companion object {
        const val MAX_COMPLETED_RUNS = 64
        const val MAX_HISTORY_MESSAGES = 24
        const val MAX_HISTORY_CHARS = 4_000
        const val MAX_SCREEN_CONTEXT_CHARS = 4_000
        const val MAX_HANDOFF_CHARS = 4_000
        const val MAX_HIDDEN_CONTEXT_CHARS = 16_000

        /** 进程内共享：多个入口 client 实例看到同一批回合结果。 */
        val completedRuns = ConcurrentHashMap<String, AgentRuntimeWire.CompletedRun>()
        val cancelled: MutableSet<String> = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
    }
}
