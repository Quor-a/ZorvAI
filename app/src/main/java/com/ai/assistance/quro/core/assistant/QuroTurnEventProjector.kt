package com.ai.assistance.quro.core.assistant

import com.ai.assistance.quro.core.QuroAssistantStreamEvent
import com.ai.assistance.quro.core.assistant.QuroAgentTurnEvents.putText

/**
 * 把 [QuroAssistant] 的回合内事件投影成跨进程协议事件。
 *
 * 关键换算：ZorvAI 的 `onToken` / `onThinking` 给的是**本轮累计文本**（每轮 LLM 请求
 * 从空串重新开始），而 Eta 的小布 / 小爱渲染器消费的是**增量 delta**。因此这里按轮
 * 维护游标，只在 [QuroAssistantStreamEvent.RoundStarted] 时重置，并把块开始/结束事件
 * 一并发出，让渲染器正确清空与切换卡片。
 *
 * 同时累计完整思考文本，回合结果里的 `reasoning_content` 就是它的全文——
 * 小布渲染器收尾时用 `drop(已流式字符数)` 补齐，即使中途丢了几个事件也不会缺字。
 */
internal class QuroTurnEventProjector(private val runId: String) {

    private var round = 0
    private var textEmitted = 0
    private var thinkingEmitted = 0
    private var textBlockOpen = false
    private var thinkingBlockOpen = false
    private val reasoning = StringBuilder()

    fun onEvent(event: QuroAssistantStreamEvent) {
        runCatching {
            when (event) {
                is QuroAssistantStreamEvent.RoundStarted -> onRoundStarted(event.round)
                is QuroAssistantStreamEvent.TextAccumulated -> onText(event.text)
                is QuroAssistantStreamEvent.ThinkingAccumulated -> onThinking(event.text)
                is QuroAssistantStreamEvent.ToolStarted -> emit(
                    QuroAgentBridgeProtocol.EVENT_TOOL_STARTED
                ) {
                    putText(QuroAgentBridgeProtocol.EVENT_NAME, event.name)
                    putText(QuroAgentBridgeProtocol.EVENT_CALL_ID, event.callId)
                    putText(QuroAgentBridgeProtocol.EVENT_ARGS, event.argsPreview)
                    put(QuroAgentBridgeProtocol.EVENT_ROUND, round)
                }
                is QuroAssistantStreamEvent.ToolFinished -> emit(
                    QuroAgentBridgeProtocol.EVENT_TOOL_FINISHED
                ) {
                    putText(QuroAgentBridgeProtocol.EVENT_NAME, event.name)
                    putText(QuroAgentBridgeProtocol.EVENT_CALL_ID, event.callId)
                    putText(QuroAgentBridgeProtocol.EVENT_SUMMARY, event.resultSummary)
                    event.success?.let { put(QuroAgentBridgeProtocol.EVENT_SUCCESS, it) }
                    put(QuroAgentBridgeProtocol.EVENT_ROUND, round)
                }
            }
        }
    }

    /** 必须在 `QuroAgentTurnEvents.start()` 之后调用：让厂商助手立刻进入「思考中」态。 */
    fun begin() {
        emit(QuroAgentBridgeProtocol.EVENT_RUN_STARTED)
    }

    private fun onRoundStarted(newRound: Int) {
        closeBlocks()
        round = newRound
        textEmitted = 0
        thinkingEmitted = 0
        emit(QuroAgentBridgeProtocol.EVENT_ROUND_STARTED) {
            put(QuroAgentBridgeProtocol.EVENT_ROUND, round)
        }
        emit(QuroAgentBridgeProtocol.EVENT_PROVIDER_REQUEST_STARTED) {
            put(QuroAgentBridgeProtocol.EVENT_ROUND, round)
        }
    }

    private fun onText(accumulated: String) {
        if (accumulated.isEmpty()) return
        if (!textBlockOpen) {
            emit(QuroAgentBridgeProtocol.EVENT_BLOCK_START) {
                put(QuroAgentBridgeProtocol.EVENT_KIND, QuroAgentBridgeProtocol.BLOCK_TEXT)
                put(QuroAgentBridgeProtocol.EVENT_ROUND, round)
            }
            textBlockOpen = true
        }
        // 累计串被上游重置（异常重试）时，把整段作为新 delta，绝不发负数长度。
        val delta = if (accumulated.length >= textEmitted) {
            accumulated.substring(textEmitted)
        } else {
            textEmitted = 0
            accumulated
        }
        textEmitted = accumulated.length
        if (delta.isEmpty()) return
        emit(QuroAgentBridgeProtocol.EVENT_BLOCK_DELTA) {
            put(QuroAgentBridgeProtocol.EVENT_KIND, QuroAgentBridgeProtocol.BLOCK_TEXT)
            putText(QuroAgentBridgeProtocol.EVENT_DELTA, delta)
            put(QuroAgentBridgeProtocol.EVENT_CHARS, delta.length)
            put(QuroAgentBridgeProtocol.EVENT_ROUND, round)
        }
    }

    private fun onThinking(accumulated: String) {
        if (accumulated.isEmpty()) return
        if (!thinkingBlockOpen) {
            emit(QuroAgentBridgeProtocol.EVENT_BLOCK_START) {
                put(QuroAgentBridgeProtocol.EVENT_KIND, QuroAgentBridgeProtocol.BLOCK_THINKING)
                put(QuroAgentBridgeProtocol.EVENT_ROUND, round)
            }
            thinkingBlockOpen = true
        }
        val delta = if (accumulated.length >= thinkingEmitted) {
            accumulated.substring(thinkingEmitted)
        } else {
            thinkingEmitted = 0
            accumulated
        }
        thinkingEmitted = accumulated.length
        if (delta.isEmpty()) return
        reasoning.append(delta)
        emit(QuroAgentBridgeProtocol.EVENT_BLOCK_DELTA) {
            put(QuroAgentBridgeProtocol.EVENT_KIND, QuroAgentBridgeProtocol.BLOCK_THINKING)
            putText(QuroAgentBridgeProtocol.EVENT_DELTA, delta)
            put(QuroAgentBridgeProtocol.EVENT_CHARS, delta.length)
            put(QuroAgentBridgeProtocol.EVENT_ROUND, round)
        }
    }

    fun close() {
        runCatching {
            emit(QuroAgentBridgeProtocol.EVENT_PROVIDER_RESPONSE_STARTED) {
                put(QuroAgentBridgeProtocol.EVENT_ROUND, round)
            }
            closeBlocks()
        }
    }

    fun reasoning(): String = reasoning.toString()

    private fun closeBlocks() {
        if (textBlockOpen) {
            emit(QuroAgentBridgeProtocol.EVENT_BLOCK_END) {
                put(QuroAgentBridgeProtocol.EVENT_KIND, QuroAgentBridgeProtocol.BLOCK_TEXT)
                put(QuroAgentBridgeProtocol.EVENT_CHARS, textEmitted)
                put(QuroAgentBridgeProtocol.EVENT_ROUND, round)
            }
            textBlockOpen = false
        }
        if (thinkingBlockOpen) {
            emit(QuroAgentBridgeProtocol.EVENT_BLOCK_END) {
                put(QuroAgentBridgeProtocol.EVENT_KIND, QuroAgentBridgeProtocol.BLOCK_THINKING)
                put(QuroAgentBridgeProtocol.EVENT_CHARS, thinkingEmitted)
                put(QuroAgentBridgeProtocol.EVENT_ROUND, round)
            }
            thinkingBlockOpen = false
        }
    }

    private fun emit(type: String, payload: org.json.JSONObject.() -> Unit = {}) {
        QuroAgentTurnEvents.emit(runId, type, payload)
    }
}
