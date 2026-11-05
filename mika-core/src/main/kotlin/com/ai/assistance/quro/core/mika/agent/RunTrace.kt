package com.ai.assistance.quro.core.mika.agent

import com.ai.assistance.quro.core.mika.model.ChatModel
import com.ai.assistance.quro.core.mika.model.Message
import com.ai.assistance.quro.core.mika.model.ModelReply
import com.ai.assistance.quro.core.mika.record.RunEvent
import com.ai.assistance.quro.core.mika.record.RunSink

/**
 * 运行轨迹接收方：事件总线订阅者 + 模型/工具调用埋点。
 *
 * 早期版本把轨迹经 OTLP 导出；当前本地版不接 OTLP，这里仅保留接口桩，默认不挂（构造 [AgentLoop] / [ModelCaller] 时传 null 即不订阅）。
 * 所有方法均为 no-op；接上真实落盘只需在此实现 [event] / [toolStarted] / [modelCall]。
 */
class RunTrace : RunSink {
    override fun event(e: RunEvent) {}

    fun toolStarted(step: Int, name: String, id: String?, now: Long) {}

    suspend fun modelCall(
        step: Int,
        using: ChatModel,
        verb: Any?,
        change: String?,
        messages: List<Message>,
        block: suspend () -> ModelReply,
    ): ModelReply = block()
}
