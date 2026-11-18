package com.ai.assistance.quro.etalib.hook.hyperos

import android.content.Context
import com.ai.assistance.quro.etalib.config.Prefs
import com.ai.assistance.quro.etalib.core.safeLogType
import com.ai.assistance.quro.etalib.core.ModuleLogger
import com.ai.assistance.quro.etalib.hook.system.CircleToSearchInvoker

internal object HyperOsSearchTrigger {
    fun isEnabled(): Boolean = Prefs.isEnabled(Prefs.Keys.GESTURE_BAR_CIRCLE_TO_SEARCH)

    fun trigger(context: Context?, logger: ModuleLogger): Boolean {
        if (!isEnabled()) return false
        if (context == null) {
            logger.warnThrottled("hyperos_search_context_missing") { "HyperOS: 搜索入口缺少 Context，保留系统行为" }
            return false
        }
        return try {
            CircleToSearchInvoker.isAvailable(context, logger, "HyperOS", "保留系统行为") &&
                CircleToSearchInvoker.trigger(logger, "HyperOS", entryPoint = 1)
        } catch (exception: Exception) {
            logger.warnThrottled("hyperos_search_failed") {
                "HyperOS: 搜索触发失败，type=${exception.safeLogType()}"
            }
            false
        }
    }
}
