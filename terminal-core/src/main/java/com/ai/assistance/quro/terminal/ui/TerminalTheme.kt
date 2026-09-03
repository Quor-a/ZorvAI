package com.ai.assistance.quro.terminal.ui

import androidx.compose.ui.graphics.Color

// ZorvAI 终端画布主题（终端模拟器专用）：深蓝黑背景，保持终端传统的深色画布观感。
// 与「设置 / 环境配置」页面的暖纸陶土主题分离，互不干扰。
object TerminalTheme {
    val primaryColor = Color(0xFF7C4DFF)        // 主色
    val primaryVariant = Color(0xFF651FFF)      // 深紫变体

    val accentColor = Color(0xFF2DD4BF)         // 终端 prompt / 光标 / 加载指示

    val backgroundColor = Color(0xFF0B0E14)     // 深蓝黑背景
    val terminalBackground = Color(0xFF0B0E14)  // 终端画布背景
    val surfaceColor = Color(0xFF151A23)        // 卡片 / 标签栏背景
    val surfaceVariant = Color(0xFF0F141C)      // 更深层（工具栏 / 虚拟键盘）
    val elevated = Color(0xFF2A3448)            // 选中标签 / 按钮
    val divider = Color(0xFF202838)             // 分隔线 / 未选中标签 / 按键

    val onSurfaceColor = Color(0xFFE6EDF3)      // 主要文字
    val onSurfaceVariant = Color(0xFF8B98A9)    // 次要文字

    val successColor = Color(0xFF34D399)        // 成功 / 已安装
    val warningColor = Color(0xFFF59E0B)        // 警示 / 必须标签
    val errorColor = Color(0xFFE53E3E)          // 错误/危险色
    val errorVariant = Color(0xFFD32F2F)        // 深红变体
}
