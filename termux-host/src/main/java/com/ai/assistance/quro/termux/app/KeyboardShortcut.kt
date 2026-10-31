package com.ai.assistance.quro.termux.app

/**
 * 硬件键盘快捷键（移植自 Termux `KeyboardShortcut`）。
 *
 * @param codePoint 快捷键对应码点
 * @param shortcutAction 快捷键动作（来自属性键映射的 action 字符串，如 "create-session"）
 */
class KeyboardShortcut(val codePoint: Int, val shortcutAction: String)