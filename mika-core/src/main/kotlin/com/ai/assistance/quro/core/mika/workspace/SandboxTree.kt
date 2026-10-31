package com.ai.assistance.quro.core.mika.workspace

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * `files/sandbox/` 这棵树的主人:谁要往树里写,都先在这里拿到许可。进程里一份,由 App 给出,经构造参数交给要写的地方。
 *
 * - [exclusive]:改树的结构、或删整片的(安装/解包/换根文件系统、重置、清空工作区)。拿到时没有别人在写。
 * - [shared]:往已有的树里添东西的(技能同步、跑命令、网页下载、附件导入)。可以同时有很多个,
 *   但和 [exclusive] 互斥:重置删到一半时,不会有人又在里面建出目录(那样最后的 rmdir 会 ENOTEMPTY)。
 *
 * 有人在等 [exclusive] 时,新来的 [shared] 也排在它后面,重置不会被源源不断的命令饿死。
 * 同一条协程链里已经拿着(任一种)时再拿直接放行,嵌套调用不会自己锁死自己。
 */
class SandboxTree {
    /** 拿到它才能开始:[shared] 只短暂持有,[exclusive] 全程持有,所以写者排队时新读者也等着。 */
    private val entry = Mutex()
    /** 有 [shared] 在用时锁着。 */
    private val noShared = Mutex()
    private val count = Mutex()
    private var holders = 0

    /** 这条协程链此刻拿着哪几棵树的许可。 */
    private class Held(val trees: Set<SandboxTree>) : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<Held>
    }

    private suspend fun heldTrees() = currentCoroutineContext()[Held]?.trees.orEmpty()

    suspend fun <T> exclusive(block: suspend () -> T): T {
        val held = heldTrees()
        if (this in held) return block()
        return entry.withLock { noShared.withLock { withContext(Held(held + this)) { block() } } }
    }

    suspend fun <T> shared(block: suspend () -> T): T {
        val held = heldTrees()
        if (this in held) return block()
        entry.withLock { count.withLock { if (holders++ == 0) noShared.lock() } }
        try {
            return withContext(Held(held + this)) { block() }
        } finally {
            withContext(NonCancellable) { count.withLock { if (--holders == 0) noShared.unlock() } }
        }
    }
}
