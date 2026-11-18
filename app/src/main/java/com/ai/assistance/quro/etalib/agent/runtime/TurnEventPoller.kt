package com.ai.assistance.quro.etalib.agent.runtime

import android.content.ContentResolver
import com.ai.assistance.quro.core.assistant.QuroAgentBridgeProtocol
import com.ai.assistance.quro.core.xposed.QuroAgentBridgeClient
import com.ai.assistance.quro.etalib.core.AgentLogger
import com.ai.assistance.quro.etalib.core.safeLogType
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 厂商助手入口的事件流拉取器（运行在被 Hook 的进程）。
 *
 * [AgentRuntimeClient.run] 会同步阻塞在 `METHOD_RUN` 上等最终答复；本类在独立线程
 * 按游标并行拉取同一个 requestId 的回合内事件，把它们实时翻译成 Eta 的 [AgentEvent]，
 * 交给小布思考态渲染器与超级小爱流式卡片——于是入口界面能显示：
 * 逐字流式正文、思考过程、工具调用名与结果、模型重试提示，与 ZorvAI 主对话框对等。
 *
 * 设计约束：
 *  · 只依赖 Android 框架 + 纯协议常量，绝不触碰 app 侧单例 / 资源 ID；
 *  · 任何解析或回调异常都吞掉，绝不把异常抛回宿主进程（system_server / 小布 / 小爱）；
 *  · 有硬 deadline 与 terminal 收敛，回合结束后不会留下野线程；
 *  · 事件流不可用时静默降级，[AgentRuntimeClient] 会补一次完整文本。
 */
internal class TurnEventPoller(
    private val resolver: ContentResolver?,
    private val runId: String,
    private val logger: AgentLogger,
    private val onEvent: (AgentEvent) -> Unit,
) {
    private var afterSeq = 0L
    private val stopped = AtomicBoolean(false)
    private val terminal = AtomicBoolean(false)
    private val textDelivered = AtomicBoolean(false)
    private val doneSeen = AtomicBoolean(false)
    private val deadline = System.currentTimeMillis() + MAX_LIFETIME_MS

    @Volatile
    private var thread: Thread? = null

    fun start() {
        if (runId.isBlank() || resolver == null) return
        val t = Thread({
            try {
                loop()
            } catch (throwable: Throwable) {
                logger.warn("入口事件流轮询异常: ${throwable.safeLogType()}")
            }
        }, "quro-turn-events").apply {
            isDaemon = true
        }
        thread = t
        runCatching { t.start() }.onFailure {
            logger.warn("入口事件流线程启动失败: ${it.safeLogType()}")
        }
    }

    /**
     * 回合结束后调用：请求停止，并给轮询线程一小段时间把最后一批事件 drain 完。
     * @return 是否已经通过事件流送出过正文 delta；false 时调用方需降级补全文。
     */
    fun awaitTerminal(): Boolean {
        terminal.set(true)
        val t = thread
        if (t != null) runCatching { t.join(TERMINAL_DRAIN_MS) }
        stopped.set(true)
        // drain 只由轮询线程执行，afterSeq / 渲染器状态保持单线程语义，不会重复投递正文。
        return textDelivered.get()
    }

    private fun loop() {
        while (!stopped.get()) {
            val now = System.currentTimeMillis()
            if (now > deadline) return
            val json = QuroAgentBridgeClient.pollEvents(resolver, runId, afterSeq)
            if (!json.optBoolean(QuroAgentBridgeProtocol.RESULT_OK, false)) {
                // 流还没建好 / provider 不可用：短暂退避后重试，不做任何界面动作。
                if (terminal.get()) return
                if (!sleep(POLL_INTERVAL_MS)) return
                continue
            }
            val raw = json.optString(QuroAgentBridgeProtocol.EXTRA_EVENTS_JSON, "")
            val events = runCatching { JSONArray(raw) }.getOrElse { JSONArray() }
            for (i in 0 until events.length()) {
                val event = events.optJSONObject(i) ?: continue
                val seq = event.optLong(QuroAgentBridgeProtocol.EVENT_SEQ, -1L)
                if (seq <= afterSeq) continue
                afterSeq = seq
                dispatch(event)
            }
            if (json.optBoolean(QuroAgentBridgeProtocol.EXTRA_EVENTS_DONE, false)) {
                doneSeen.set(true)
                // done 已到达，但事件数组可能刚被截断；再拉一次确保尾部不丢。
                drainOnce()
                return
            }
            if (terminal.get()) {
                drainOnce()
                return
            }
            if (!sleep(POLL_INTERVAL_MS)) return
        }
        // loop 因 stopped 退出时（join 超时），补一次尾部 drain。
        if (!doneSeen.get()) {
            drainOnce()
        }
    }

    private fun drainOnce() {
        val json = QuroAgentBridgeClient.pollEvents(resolver, runId, afterSeq)
        if (!json.optBoolean(QuroAgentBridgeProtocol.RESULT_OK, false)) return
        val events = runCatching {
            JSONArray(json.optString(QuroAgentBridgeProtocol.EXTRA_EVENTS_JSON, ""))
        }.getOrElse { JSONArray() }
        for (i in 0 until events.length()) {
            val event = events.optJSONObject(i) ?: continue
            val seq = event.optLong(QuroAgentBridgeProtocol.EVENT_SEQ, -1L)
            if (seq > afterSeq) {
                afterSeq = seq
                dispatch(event)
            }
        }
    }

    private fun dispatch(event: JSONObject) {
        runCatching {
            val type = event.optString(QuroAgentBridgeProtocol.EVENT_TYPE)
            val round = event.optInt(QuroAgentBridgeProtocol.EVENT_ROUND, 0)
            val kind = kindOf(event.optString(QuroAgentBridgeProtocol.EVENT_KIND))
            when (type) {
                QuroAgentBridgeProtocol.EVENT_RUN_STARTED ->
                    fire(AgentEvent.RunStarted())

                QuroAgentBridgeProtocol.EVENT_PROVIDER_REQUEST_STARTED ->
                    fire(AgentEvent.ProviderRequestStarted(round = round))

                QuroAgentBridgeProtocol.EVENT_PROVIDER_RESPONSE_STARTED ->
                    fire(AgentEvent.ProviderResponseStarted(round = round))

                QuroAgentBridgeProtocol.EVENT_ROUND_STARTED ->
                    fire(AgentEvent.ProviderRequestStarted(round = round))

                QuroAgentBridgeProtocol.EVENT_BLOCK_START ->
                    fire(
                        AgentEvent.AssistantBlockStart(
                            round = round,
                            kind = kind,
                            index = 0,
                        )
                    )

                QuroAgentBridgeProtocol.EVENT_BLOCK_DELTA -> {
                    val delta = event.optString(QuroAgentBridgeProtocol.EVENT_DELTA)
                    if (delta.isNotEmpty()) {
                        fire(
                            AgentEvent.AssistantBlockDelta(
                                round = round,
                                kind = kind,
                                delta = delta,
                                deltaChars = event.optInt(
                                    QuroAgentBridgeProtocol.EVENT_CHARS, delta.length
                                ),
                            )
                        )
                        if (kind == AgentEvent.AssistantBlockKind.TEXT) textDelivered.set(true)
                    }
                }

                QuroAgentBridgeProtocol.EVENT_BLOCK_END ->
                    fire(
                        AgentEvent.AssistantBlockEnd(
                            round = round,
                            kind = kind,
                            contentChars = event.optInt(QuroAgentBridgeProtocol.EVENT_CHARS, 0),
                        )
                    )

                QuroAgentBridgeProtocol.EVENT_TOOL_STARTED ->
                    fire(
                        AgentEvent.ToolStarted(
                            round = round,
                            toolCallId = event.optString(QuroAgentBridgeProtocol.EVENT_CALL_ID),
                            name = event.optString(QuroAgentBridgeProtocol.EVENT_NAME),
                            argsPreview = event.optString(QuroAgentBridgeProtocol.EVENT_ARGS),
                        )
                    )

                QuroAgentBridgeProtocol.EVENT_TOOL_FINISHED ->
                    fire(
                        AgentEvent.ToolFinished(
                            round = round,
                            toolCallId = event.optString(QuroAgentBridgeProtocol.EVENT_CALL_ID),
                            name = event.optString(QuroAgentBridgeProtocol.EVENT_NAME),
                            resultSummary = event.optString(QuroAgentBridgeProtocol.EVENT_SUMMARY),
                            success = if (event.has(QuroAgentBridgeProtocol.EVENT_SUCCESS)) {
                                event.optBoolean(QuroAgentBridgeProtocol.EVENT_SUCCESS)
                            } else null,
                        )
                    )

                QuroAgentBridgeProtocol.EVENT_MODEL_RETRY ->
                    fire(
                        AgentEvent.ModelRetryScheduled(
                            round = round,
                            attempt = event.optInt(QuroAgentBridgeProtocol.EVENT_ATTEMPT, 0),
                            maxAttempts = event.optInt(QuroAgentBridgeProtocol.EVENT_MAX_ATTEMPTS, 0),
                            delayMs = event.optInt(QuroAgentBridgeProtocol.EVENT_DELAY_MS, 0),
                            displayMessage = event.optString(QuroAgentBridgeProtocol.EVENT_MESSAGE)
                                .ifBlank { "模型请求暂时中断，正在重试…" },
                        )
                    )

                else -> Unit
            }
        }.onFailure {
            logger.warn("入口事件转换失败: ${it.safeLogType()}")
        }
    }

    private fun fire(event: AgentEvent) {
        runCatching { onEvent(event) }
            .onFailure { logger.warn("入口渲染器事件回调失败: ${it.safeLogType()}") }
    }

    private fun kindOf(raw: String): AgentEvent.AssistantBlockKind = when (raw) {
        QuroAgentBridgeProtocol.BLOCK_THINKING -> AgentEvent.AssistantBlockKind.THINKING
        QuroAgentBridgeProtocol.BLOCK_TOOL_CALL -> AgentEvent.AssistantBlockKind.TOOL_CALL
        else -> AgentEvent.AssistantBlockKind.TEXT
    }

    private fun sleep(ms: Long): Boolean = runCatching {
        Thread.sleep(ms)
        true
    }.getOrDefault(false)

    private companion object {
        const val POLL_INTERVAL_MS = 60L
        const val TERMINAL_DRAIN_MS = 1_500L
        const val MAX_LIFETIME_MS = 260_000L
    }
}
