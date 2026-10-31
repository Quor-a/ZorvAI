package com.ai.assistance.quro.core.mika.run

import com.ai.assistance.quro.core.mika.record.RunEvent
import com.ai.assistance.quro.core.mika.record.RunSink
import kotlinx.coroutines.CancellationException
import java.util.concurrent.CopyOnWriteArrayList

/** 一次 Run 的事件分发器。记录、界面与送达可各订一份,单个接收方故障不影响其他接收方。 */
class RunEventBus : RunSink {
    private val listeners = CopyOnWriteArrayList<RunSink>()

    fun subscribe(listener: RunSink): AutoCloseable {
        listeners += listener
        return AutoCloseable { listeners -= listener }
    }

    override fun event(e: RunEvent) {
        listeners.forEach { listener ->
            try { listener.event(e) }
            catch (e: CancellationException) { throw e }
            catch (_: Throwable) { /* 运行不能被订阅者的故障打断 */ }
        }
    }

    override fun media(mediaId: Int, png: ByteArray) {
        listeners.forEach { listener ->
            try { listener.media(mediaId, png) }
            catch (e: CancellationException) { throw e }
            catch (_: Throwable) { /* 同上 */ }
        }
    }
}
