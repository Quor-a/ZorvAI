package com.ai.assistance.quro.core.mika.run

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.selects.select
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

/**
 * 本次运行里「有用户插话还没交给模型」的信号([RunInbox] 给出),装在运行的协程上下文里。
 * 撤掉也什么都不丢的调用(纯等待、动作之前的停留与间隔、只问看图模型)经 [waitOrSteer] 包起来,插话一到就提前结束,
 * 让模型马上读到这句话;已经开始的动作不受它影响。
 */
class SteerSignal internal constructor(internal val pending: StateFlow<Boolean>) : AbstractCoroutineContextElement(SteerSignal) {
    companion object Key : CoroutineContext.Key<SteerSignal>
}

/** 撤掉的调用给模型的错误码:这次什么也没做(等待被截断、动作之前的停留没走完),先读用户的新话。 */
const val SKIPPED_FOR_NEW_MESSAGE = "skipped_for_new_message"

/**
 * 跑一段撤掉也什么都不丢的 [block]:用户插话已在等或在它进行中到达,就撤掉它、返回 null;否则返回它的结果。
 * 不在运行里(没有 [SteerSignal])时照常跑完。
 */
suspend fun <T : Any> waitOrSteer(block: suspend () -> T): T? {
    val pending = coroutineContext[SteerSignal]?.pending ?: return block()
    if (pending.value) return null
    return coroutineScope {
        val work = async { block() }
        val steer = async { pending.first { it } }
        select<T?> {
            work.onAwait { steer.cancel(); it }
            steer.onAwait { work.cancel(); null }
        }
    }
}
