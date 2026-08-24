package com.ai.assistance.quro.core.tools

import android.content.Context

/**
 * 注册流体云工具到 QuroToolRegistry。
 * 在 Application 或 ViewModel 初始化处调用一次：
 *   RegisterFluidCloudTool.register(applicationContext)
 */
object RegisterFluidCloudTool {
    fun register(context: Context) {
        QuroToolRegistry.active?.register(FluidCloudTool(context.applicationContext))
    }
}
