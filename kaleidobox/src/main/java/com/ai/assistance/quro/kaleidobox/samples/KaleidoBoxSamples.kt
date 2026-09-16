package com.ai.assistance.quro.kaleidobox.samples

import com.ai.assistance.quro.kaleidobox.core.KaleidoRuntime

/**
 * 内置示例包的安装器。
 *
 * 单一事实源是 [KaleidoCatalog]（内置实用 APP 级工具包：AI 助手 / 终端 / 设备信息 /
 * 剪贴板 / 联网工具）。本对象只是入口委托，避免两份清单不一致。
 *
 * 在 [com.ai.assistance.quro.kaleidobox.android.KaleidoBoxHost.init] 末尾调用 [installBuiltins]，
 * 保证用户首次打开 KaleidoBox 面板就有一组真正能用的工具（装完即见 UI）。
 */
object KaleidoBoxSamples {

    /** 安装内置包（幂等：已安装则跳过）。委托给 [KaleidoCatalog]（单一事实源）。 */
    fun installBuiltins(runtime: KaleidoRuntime): Map<String, String> =
        KaleidoCatalog.installBuiltins(runtime)
}
