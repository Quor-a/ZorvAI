package com.ai.assistance.quro.core.mika.agent

/**
 * 一个模型的压缩记账:本任务是否已改成狠压缩(模型报过超长),上一次发出时折叠了几步、用的什么力度。
 * 每个模型各一份,轻量模型的压缩不连累主模型。
 */
internal data class CompactionState(val aggressive: Boolean = false, val lastFolded: Int = 0, val lastAggressive: Boolean = false)

/** 压缩时机:什么时候记一条压缩事件、上下文超长时还要不要压得更狠再发。纯函数。 */
internal object CompactionPolicy {
    /** 这次发出时折叠了 [folded] 步。返回之后的记账,以及要不要记一条压缩事件(折叠的步数或力度变了才记)。 */
    fun sent(s: CompactionState, folded: Int): Pair<CompactionState, Boolean> {
        val report = folded > 0 && (folded != s.lastFolded || s.aggressive != s.lastAggressive)
        return s.copy(lastFolded = folded, lastAggressive = s.aggressive) to report
    }

    /**
     * 模型报上下文超长。每次超长只重发一次:[retried] 为真(这次调用已因超长重发过)就不再发;
     * 否则 [shrinks] 从完整历史重新狠压一次,压得比刚发出的小才重发(上次狠压之后历史又长了也算)。之后一律狠压缩。
     */
    fun overflow(s: CompactionState, retried: Boolean, shrinks: () -> Boolean): Pair<CompactionState, Boolean> =
        s.copy(aggressive = true) to (!retried && shrinks())
}
