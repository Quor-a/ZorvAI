package com.ai.assistance.quro.core.mika.trace

import com.ai.assistance.quro.core.mika.model.InferenceRequest
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * HTTP 调用的协程上下文埋点元素。
 *
 * 当前本地版不接 OTLP，这里仅保留接口桩：[sending] 恒返回 null，使调用方走「无埋点」分支
 * （等价于当前协程从不挂载本元素）。接上真实落盘只需让 [sending] 返回非空 [Attempt] 并在其内部记录。
 */
internal class TraceCall : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<TraceCall>

    fun sending(wire: InferenceRequest): Attempt? = null

    fun finish(status: String?, data: String?) {}

    data class Attempt(val wire: InferenceRequest) {
        fun packet(event: String) {}
        fun httpStatus(code: Int) {}
    }
}
