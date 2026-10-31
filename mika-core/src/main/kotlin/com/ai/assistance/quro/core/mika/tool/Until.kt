package com.ai.assistance.quro.core.mika.tool

/**
 * 一批动作里某一步做完之后,接着做下一步之前要等到的样子;几项都给了要同时成立。代码只做字面比较,不理解意思:
 * [appears] 屏上某个元素的名字含这段字;[gone] 屏上已没有元素含这段字;[place] 前台的 App 包名(手机)或页面地址、站点(网页)含这段字;
 * [stable] 界面停下不再变化。最多等 [timeoutMs]。
 */
data class Until(
    val appears: String? = null,
    val gone: String? = null,
    val place: String? = null,
    val stable: Boolean = false,
    val timeoutMs: Long = DEFAULT_MS,
) {
    companion object {
        const val DEFAULT_MS = 8_000L
        const val MAX_MS = 20_000L
    }
}

/**
 * 等的结局:[met] 条件成立;没成立时 [stopped] 说明为什么不再等——[TIMEOUT] 到了时限,或提前看出的意外:
 * [POPUP] 同一 App 多了一个窗口(弹框)或页面弹了框,[SYSTEM_DIALOG] 别的包的窗口盖了上来(权限、系统对话框),
 * [APP_SWITCHED] 前台换成了别的 App,[NEW_MESSAGE] 用户插了话。[waitedMs] 是等了多久。
 */
data class UntilOutcome(val met: Boolean, val stopped: String?, val waitedMs: Long) {
    companion object {
        const val TIMEOUT = "timeout"
        const val POPUP = "popup"
        const val SYSTEM_DIALOG = "system_dialog"
        const val APP_SWITCHED = "app_switched"
        const val NEW_MESSAGE = "new_message"
    }
}
