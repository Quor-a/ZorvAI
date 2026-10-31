package com.ai.assistance.quro.core.mika.resource

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

/**
 * 一组同生共死的系统资源(虚拟屏、网页显示等):用 [acquire] 建,建好当场登记,[close] 时按登记的反序放掉。
 * 「建完交回调用方再记到字段」中间隔着一次挂起,任务恰在这时取消,建好的东西就没人记得放;登记在建的同一段里做,就没有这个空档。
 * 任意线程可用;每个资源只放一次,[close] 可重复调。
 */
class ResourceScope {
    private class Entry(val context: CoroutineContext, val release: () -> Unit)

    private val lock = Any()
    private val entries = ArrayList<Entry>()
    private var closed = false

    /**
     * 在 [context] 里调 [create],建好立即登记,收尾时同样在 [context] 里调 [release]。
     * 建好之后即使任务被取消(取消在交回时抛出),资源也已归作用域。作用域已关时建出来的立即放掉,并抛 [IllegalStateException]。
     */
    suspend fun <T> acquire(context: CoroutineContext, release: (T) -> Unit, create: suspend () -> T): T =
        withContext(context) {
            val made = create()
            val owned = synchronized(lock) { if (!closed) entries += Entry(context) { release(made) }; !closed }
            if (!owned) {
                release(made)
                throw IllegalStateException("resource scope already closed")
            }
            made
        }

    /** 按登记的反序放掉全部;一个放不掉不影响其余,放完后抛出第一个错误。不受取消打断。 */
    suspend fun close() {
        val todo = synchronized(lock) {
            closed = true
            entries.asReversed().toList().also { entries.clear() }
        }
        var failure: Throwable? = null
        for (e in todo) {
            try {
                withContext(e.context + NonCancellable) { e.release() }
            } catch (t: Throwable) {
                failure?.addSuppressed(t) ?: run { failure = t }
            }
        }
        failure?.let { throw it }
    }
}
