package com.ai.assistance.quro.terminal.privilege

/**
 * 终端「权限」面板展示的单条权限项。
 *
 * @param key 唯一标识（root / shizuku / adb / lsposed / storage），供 [TerminalPrivilegeBridge.request] 使用。
 * @param title 展示名。
 * @param status 状态文本（如「Root 访问可用」「未安装 Shizuku」）。
 * @param available 当前是否可用（已授权 / 已就绪）。
 * @param detail 补充说明（通道 / 引导文案）。
 */
data class TerminalPrivilegeEntry(
    val key: String,
    val title: String,
    val status: String,
    val available: Boolean,
    val detail: String = "",
)
