package com.ai.assistance.quro.terminal.navigation

object TerminalRoutes {
    const val SETUP_ROUTE = "setup"
    const val TERMINAL_HOME_ROUTE = "terminal_home"

    /**
     * 墨语言（Mo）管理页 —— 独立运行时，不依赖 Ubuntu/proot。
     */
    const val MOYU_ROUTE = "moyu"

    /**
     * 增强终端（vtlib 完整 VT 模拟器 + 额外按键栏）。
     */
    const val ENHANCED_TERMINAL_ROUTE = "enhanced_terminal"
}
