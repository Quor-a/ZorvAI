package com.ai.assistance.quro.core.mika.phone.a11y

import com.ai.assistance.quro.core.mika.phone.display.FrameMath

/**
 * 动作之后等界面稳定再判断有没有变化。只看事件安静不够:反应慢的页面安静时可能还没开始画,
 * 会被误判为没变,模型就会重复点。
 *
 * - 无障碍事件安静 [QUIET_MS] 且控件树变了:立即判为变了,不等画面停(视频、直播的画面永远在动)。
 * - 只有画面变了(自绘界面):还要前后两帧相近,才判为变了,免得把画到一半的过渡当结果。
 * - 判「没变」要事件安静且画面稳定,在此之前一直等到 [CAP_MS]。
 * - 到 [CAP_MS] 时不管事件是否还在来、画面是否还在动,都按当时的状态下结论:
 *   控件树或画面与动作前不同就是变了。事件一直不停的页面(进度条、计时器)也不会让动作卡住超过上限。
 *
 * 只差事件安静时按剩余的安静时间睡,普通页面在最后一个事件之后约 [QUIET_MS] 就能返回。
 * 时钟、等待、取帧都由调用方注入,便于在 JVM 上测。
 */
internal object ChangeWait {
    const val POLL_MS = 150L
    const val QUIET_MS = 400L
    const val CAP_MS = 2_500L
    private const val MIN_SLEEP_MS = 10L

    /**
     * @param frame 当前预览帧的亮度格子,没有画面时为 null(此时只靠控件树和事件安静)
     * @param quietForMs 距最近一次界面事件过了多久
     * @param treeChanged 控件树指纹是否与动作前不同
     * @param beforeFrame 动作前的亮度格子
     */
    suspend fun awaitStableChange(
        now: () -> Long,
        sleep: suspend (Long) -> Unit,
        frame: () -> IntArray?,
        quietForMs: () -> Long,
        treeChanged: () -> Boolean,
        beforeFrame: IntArray?,
    ): Boolean {
        val deadline = now() + CAP_MS
        var prev = frame()
        var wait = POLL_MS
        while (true) {
            sleep(minOf(wait, (deadline - now()).coerceAtLeast(0)))
            val cur = frame()
            val quietFor = quietForMs()
            val quiet = quietFor >= QUIET_MS
            if (quiet && treeChanged()) return true
            val stable = if (prev == null || cur == null) prev == null && cur == null else !FrameMath.differs(prev, cur)
            val frameChanged = FrameMath.differs(beforeFrame, cur)
            if (quiet && stable && frameChanged) return true
            if (now() >= deadline) return frameChanged || treeChanged()
            wait = if (quiet) POLL_MS else minOf(POLL_MS, QUIET_MS - quietFor).coerceAtLeast(MIN_SLEEP_MS)
            prev = cur
        }
    }
}
