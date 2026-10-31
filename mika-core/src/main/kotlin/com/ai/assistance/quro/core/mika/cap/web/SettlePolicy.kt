package com.ai.assistance.quro.core.mika.cap.web

import kotlinx.coroutines.delay

/**
 * 网页动作之后等多久。等页面安静和以前一样([domQuiet] 带旧上限 [domCapMs],页面一直在变就等满这个上限,不报);
 * 网络是另一个条件:只有本次动作发出的请求还在途时([networkBusy])才往后等,自动作开始合计至多 [netCapMs],
 * 请求结束后在剩余时间里再看一眼页面安静。网络本来就空闲时,和没有网络记录时等得一样久。
 * 返回网络是否在上限内静下来(没等网络时为 true)。
 */
object SettlePolicy {
    suspend fun settle(
        clock: () -> Long,
        domQuiet: suspend (capMs: Long) -> Boolean,
        networkBusy: () -> Boolean,
        domCapMs: Long,
        netCapMs: Long,
        pollMs: Long,
    ): Boolean {
        val deadline = clock() + netCapMs
        domQuiet(domCapMs)
        if (!networkBusy()) return true
        while (networkBusy()) {
            if (clock() >= deadline) return false
            delay(pollMs)
        }
        val left = deadline - clock()
        if (left > 0) domQuiet(minOf(left, domCapMs))
        return true
    }

    /**
     * 读页之前等正文稳定(不只是页面安静:懒加载、分批渲染时 DOM 可能一会儿静一会儿动)。
     * 每隔 [intervalMs] 取一次正文的(长度, 摘要),连续 [stableIntervals] 次都和上一次一样算稳定;
     * 还一个字都没有时要连续 [emptyIntervals] 次(页面多半还在加载)。到 [capMs] 还不稳定返回 false;取不到样本也返回 false。
     */
    suspend fun contentStable(
        clock: () -> Long,
        sample: suspend () -> Pair<Int, Int>?,
        intervalMs: Long,
        stableIntervals: Int,
        emptyIntervals: Int,
        capMs: Long,
    ): Boolean {
        val deadline = clock() + capMs
        var last = sample() ?: return false
        var same = 0
        while (true) {
            if (clock() + intervalMs > deadline) return false
            delay(intervalMs)
            val now = sample() ?: return false
            same = if (now == last) same + 1 else 0
            last = now
            if (same >= if (now.first == 0) emptyIntervals else stableIntervals) return true
        }
    }
}
