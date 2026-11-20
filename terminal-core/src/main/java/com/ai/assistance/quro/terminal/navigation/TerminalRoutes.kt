package com.ai.assistance.quro.terminal.navigation

object TerminalRoutes {
    /** 启动引导页：首次进入自动检测/初始化 Ubuntu 环境，就绪后进终端（对齐 DSH 打开即启动）。 */
    const val BOOT_ROUTE = "boot"
    const val SETUP_ROUTE = "setup"
    const val TERMINAL_HOME_ROUTE = "terminal_home"

    /**
     * 墨语言（Mo）管理页 —— 独立运行时，不依赖 Ubuntu/proot。
     */
    const val MOYU_ROUTE = "moyu"

    /**
     * Termux（vtlib 完整 VT 模拟器 + 额外按键栏）。
     */
    const val ENHANCED_TERMINAL_ROUTE = "enhanced_terminal"
}
