package com.ai.assistance.quro.core.mika.uistate

import com.ai.assistance.quro.core.mika.access.AppAccessDenied

/** 读屏的尽力而为兜底不能把权限拒绝降级成没有内容。 */
internal fun Exception.rethrowAppAccess() {
    if (this is AppAccessDenied) throw this
}

/** 普通采集失败可降级;权限拒绝和取消必须回到调用方。 */
internal suspend inline fun <T> screenAttempt(body: () -> T): T? = try {
    body()
} catch (e: kotlinx.coroutines.CancellationException) {
    throw e
} catch (e: Exception) {
    e.rethrowAppAccess()
    null
}
