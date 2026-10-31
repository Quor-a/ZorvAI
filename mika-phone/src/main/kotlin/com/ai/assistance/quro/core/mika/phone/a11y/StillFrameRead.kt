package com.ai.assistance.quro.core.mika.phone.a11y

import com.ai.assistance.quro.core.mika.phone.display.FrameMath

/**
 * 认屏上文字(OCR)前后对画面的要求。自绘界面几乎不发无障碍事件,「事件安静」不等于画完:
 * 刚进页面、键盘在升起时认,认到的是过渡帧,或者认完画面已经换了、只好丢掉。
 *
 * - 认之前等画面停稳:前后两帧相近([FrameMath.differs] 为假),最多等 [STILL_CAP_MS];一直有局部在动(动图头像、光标)也到时就认。
 * - 认完整页变了([FrameMath.pageChanged]):字的位置已经对不上,按新的一页再认一次,最多 [ATTEMPTS] 次;局部变化不影响字的位置,照用。
 *
 * 时钟、等待、取帧都由调用方注入,便于在 JVM 上测。
 */
internal object StillFrameRead {
    const val POLL_MS = 120L
    const val STILL_CAP_MS = 1_200L
    const val ATTEMPTS = 2

    /** [recognize] 认当前一帧;它给 null(没有画面)时直接返回 null。每次都整页变了也返回 null。 */
    suspend fun <T : Any> read(now: () -> Long, sleep: suspend (Long) -> Unit, frame: () -> IntArray?, recognize: suspend () -> T?): T? {
        repeat(ATTEMPTS) {
            awaitStill(now, sleep, frame)
            val before = frame()
            val result = recognize() ?: return null
            if (!FrameMath.pageChanged(before, frame())) return result
        }
        return null
    }

    /** 等到前后两帧相近;到时限还在动返回 false。没有画面时不等。 */
    suspend fun awaitStill(now: () -> Long, sleep: suspend (Long) -> Unit, frame: () -> IntArray?): Boolean {
        val deadline = now() + STILL_CAP_MS
        var prev = frame() ?: return true
        while (true) {
            sleep(minOf(POLL_MS, (deadline - now()).coerceAtLeast(0)))
            val cur = frame() ?: return true
            if (!FrameMath.differs(prev, cur)) return true
            if (now() >= deadline) return false
            prev = cur
        }
    }
}
