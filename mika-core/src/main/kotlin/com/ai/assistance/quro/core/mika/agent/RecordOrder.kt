package com.ai.assistance.quro.core.mika.agent

import com.ai.assistance.quro.core.mika.record.RunEvent
import com.ai.assistance.quro.core.mika.record.RunSink

/**
 * 运行记录按因果先后写:一次调用里读到、记进证据账本的观察([observed])先存着,跟在这次调用的工具结果后面写出;
 * 没有工具结果的读取(交还时读的一屏、批做完后看的一眼)在下一条别的事件之前写出。模型回复与工具结果之间不插别的。
 */
internal class RecordOrder(private val sink: RunSink) : RunSink {
    private val pending = mutableListOf<RunEvent.Observed>()

    fun observed(e: RunEvent.Observed) {
        synchronized(pending) { pending += e }
    }

    override fun event(e: RunEvent) {
        if (e !is RunEvent.ModelReply && e !is RunEvent.ToolResult) flush()
        sink.event(e)
        if (e is RunEvent.ToolResult) flush()
    }

    override fun media(mediaId: Int, png: ByteArray) = sink.media(mediaId, png)

    private fun flush() {
        val out = synchronized(pending) { pending.toList().also { pending.clear() } }
        out.forEach(sink::event)
    }
}
