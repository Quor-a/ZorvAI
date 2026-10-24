package com.ai.assistance.quro.terminal.harness

import android.content.Context

/**
 * 让 harness 挂上宿主（ZorvAI）全部能力的注入点。
 *
 * ## 为什么要有这个钩子
 *
 * harness 的 agent 要能操控手机（读屏、点击、滑动、截图、启动应用、Shizuku 系统命令），
 * 这些能力只在 ZorvAI（app 模块）里实现。可 MCP 桥（把 QuroTool 经 MCP 暴露给引擎）
 * 也在 app 模块，而本模块（terminal-core）**不能反向依赖 app** —— 依赖方向是 app → terminal-core。
 *
 * 所以这里只留一个钩子：app 层在 Application 启动时把实现塞进来，terminal-core 在
 * harness 启动前调用它。钩子缺失时一切照旧（只是 harness 用不到宿主能力），不崩。
 */
object HarnessBridgeHook {
    /**
     * 启用宿主↔harness 的工具桥。返回一行人类可读的结果（供日志/诊断），失败时返回原因。
     *
     * 幂等：重复调用是安全的（会刷新工具快照）。
     */
    @Volatile
    var ensureBridge: ((Context) -> String?)? = null

    /** 宿主当前暴露给 harness 的工具数量；未知时返回 -1。 */
    @Volatile
    var exposedToolCount: Int = -1
}
