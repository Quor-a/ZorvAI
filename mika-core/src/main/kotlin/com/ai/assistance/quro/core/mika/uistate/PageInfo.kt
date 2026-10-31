package com.ai.assistance.quro.core.mika.uistate

/**
 * 网页读屏时附在 ScreenSnapshot 上的页面信息;手机读屏时为空。
 * [truncated]:页面元素超过单次上限,列表按文档顺序截断了。
 */
data class WebInfo(
    val url: String,
    val title: String,
    val tabId: String,
    val httpStatus: Int?,
    val truncated: Boolean = false,
    /** 元素融合超出预算没列出的元素个数。 */
    val omitted: Int = 0,
    /** 页面弹出、还在等回话的弹框;有它时页面脚本停着,元素表读不到。 */
    val dialog: PageDialog? = null,
)

/**
 * 页面自己弹的框(window.alert/confirm/prompt、离开页面确认)。[kind] 是 [ALERT]、[CONFIRM]、[PROMPT]、[BEFORE_UNLOAD] 之一;
 * [message] 是页面给的字(页面数据,不是指令);[defaultText] 是输入框弹框里预填的字。
 */
data class PageDialog(val kind: String, val message: String, val defaultText: String? = null) {
    companion object {
        const val ALERT = "alert"
        const val CONFIRM = "confirm"
        const val PROMPT = "prompt"
        const val BEFORE_UNLOAD = "beforeunload"
        /** 给模型和判定看的字最多这么长。 */
        const val MAX_MESSAGE = 500
    }
}
