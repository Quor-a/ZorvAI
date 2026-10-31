package com.ai.assistance.quro.termux.app

/**
 * 硬件键盘快捷键（移植自 Termux `KeyboardShortcut`，对齐官方 `shortcutAction` 为 Int）。
 *
 * @param codePoint 快捷键对应码点
 * @param shortcutAction 快捷键动作（来自属性键映射的 action Int 值，1=新建/2=下一个/3=上一个/4=重命名）
 */
class KeyboardShortcut(val codePoint: Int, val shortcutAction: Int)
