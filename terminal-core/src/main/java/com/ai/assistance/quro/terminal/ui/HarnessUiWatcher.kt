package com.ai.assistance.quro.terminal.ui

/**
 * Harness 界面进出通知（可注入回调）。
 *
 * ## 为什么要有这个中间层
 *
 * 定时看门狗 [com.ai.assistance.quro.core.harness.QuroDshWatchdog] 在后台每 15 分钟唤醒一次，
 * 目的是「引擎被系统回收后把它拉回来」。但用户可能此刻**正在界面里手动启动/调试引擎**——
 * 这时看门狗再插一脚就是「强行启动」，是实打实的打扰。
 *
 * 引擎所在的 `terminal-core` 模块**不依赖** `:app`（依赖方向是 app → terminal-core），
 * 所以这里用一个可注入的回调：`:app` 在 `QuroApplication` 启动时把
 * `QuroDshKeepAlive::setUiActive` 塞进来，界面在这里通知进出。
 *
 * 默认实现是空操作 —— 没注入时行为与以前完全一致，不会因为少接一条线就崩。
 */
object HarnessUiWatcher {

    /** 进入 Harness 界面（用户开始手动操作）。 */
    var onEnter: () -> Unit = {}

    /** 离开 Harness 界面（看门狗可以恢复自动守护）。 */
    var onExit: () -> Unit = {}
}
