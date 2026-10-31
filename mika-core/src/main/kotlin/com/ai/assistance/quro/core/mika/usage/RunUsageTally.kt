package com.ai.assistance.quro.core.mika.usage

import com.ai.assistance.quro.core.mika.model.Usage
import com.ai.assistance.quro.core.mika.record.RunEvent
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * 一次运行里经计量的模型请求合计。运行把它放进自己的协程上下文,[metered] 套上的模型每次请求结束顺手加一笔,
 * 所以主循环之外的旁路调用(意图判断、核查、看图、压缩……)和失败重试的每一次都算在这次运行名下;
 * 不在这个上下文里的调用(交出回答后的结局判定、记忆提取)不算,Token 用量页照样都记。只在内存里加,多线程安全。
 */
class RunUsageTally : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<RunUsageTally>

    private val calls = AtomicInteger()
    private val unreported = AtomicInteger()
    private val input = AtomicLong()
    private val output = AtomicLong()
    private val cached = AtomicLong()

    /** 记一次请求;[usage] 为 null 表示没报用量或调用失败。 */
    fun add(usage: Usage?) {
        calls.incrementAndGet()
        if (usage == null) {
            unreported.incrementAndGet()
            return
        }
        input.addAndGet(usage.inputTokens.toLong())
        output.addAndGet(usage.outputTokens.toLong())
        cached.addAndGet(usage.cachedInputTokens.toLong())
    }

    /** 此刻的合计,作为运行记录里的一条 [RunEvent.ModelCalls]。 */
    fun event(atMs: Long) = RunEvent.ModelCalls(atMs, calls.get(), unreported.get(), input.get(), output.get(), cached.get())
}
