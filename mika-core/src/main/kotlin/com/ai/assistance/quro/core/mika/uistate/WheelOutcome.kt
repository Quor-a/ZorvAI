package com.ai.assistance.quro.core.mika.uistate

/**
 * 把滚轮转到一个值的结果。[reached] 到了;[value] 此刻显示的值(读不出为 null),[from] 开始时显示的;[attempts] 做了几次动作
 * (输入、滚一下、拖一下各算一次);[method] 最后用的做法:text(直接输入)、actions(无障碍滚动)、drag(拖动);
 * [error] 没到时的原因(ref_expired、not_a_wheel、unknown_value、at_end、not_moving、not_reached……),[hint] 端口的补充说明。
 */
data class WheelOutcome(
    val reached: Boolean,
    val value: String?,
    val from: String?,
    val attempts: Int,
    val method: String,
    val error: String? = null,
    val hint: String? = null,
)
