package com.ai.assistance.quro.core.mika.phone.display

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 用到时才建的虚拟屏:任务第一次打开 App 时由 [get] 建好,之后一直是同一块;纯对话的任务从头到尾不建。
 * [width]、[height] 是将要建的屏的尺寸,屏还没建时读屏用它们报尺寸。
 * 泛型只为让单元测试能换成假对象,产品里是 [LazyAgentDisplay]。
 */
class LazyDisplay<T : Any>(val width: Int, val height: Int, private val create: suspend () -> T) {
    private val lock = Mutex()
    @Volatile private var made: T? = null

    /** 已经建好的屏;还没建时为 null。不会去建。 */
    val current: T? get() = made

    /** 取屏,第一次调用时建;建失败抛出,下次再调会重试。 */
    suspend fun get(): T = made ?: lock.withLock { made ?: create().also { made = it } }
}

typealias LazyAgentDisplay = LazyDisplay<AgentDisplay>
