package com.ai.assistance.quro.terminal.navigation

object TerminalRoutes {
    const val SETUP_ROUTE = "setup"
    const val TERMINAL_HOME_ROUTE = "terminal_home"

    /**
     * 墨语言（Mo）管理页 —— 独立运行时，不依赖 Ubuntu/proot。
     * 取代原 LANXIU 入口位（LANXIU 已于 2026-10-23 整体移除，仅保留下列旧路由常量
     * 以免历史导航栈/深度链接崩溃，但 UI 上不再暴露入口）。
     */
    const val MOYU_ROUTE = "moyu"

    const val LANXIU_ROUTE = "lanxiu"
    const val LANXIU_DESKTOP_ROUTE = "lanxiu_desktop"
}