package com.ai.assistance.quro.core.mika.cap.web.cdp

import com.ai.assistance.quro.core.mika.uistate.DOUBLE_TAP_GAP_MS
import kotlinx.coroutines.delay

/**
 * 双击:同一点上两次按下抬起,第二次的 clickCount 是 2,页面收到 click、click、dblclick(和真人双击一样)。
 * 两次之间隔 [gapMs],在浏览器认双击的时限内。
 */
suspend fun InputTrack.mouseDoubleClick(x: Double, y: Double, gapMs: Long = DOUBLE_TAP_GAP_MS) {
    mouse("mouseMoved", x, y, "none", 0, 0)
    mouse("mousePressed", x, y, "left", 1, 1)
    mouse("mouseReleased", x, y, "left", 0, 1)
    delay(gapMs)
    mouse("mousePressed", x, y, "left", 1, 2)
    mouse("mouseReleased", x, y, "left", 0, 2)
}
