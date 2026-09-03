package com.ai.assistance.quro.terminal.ui

import androidx.compose.ui.graphics.Color

// ZorvAI 设置 / 环境配置专属主题：暖纸 + 陶土（与 App 全局 QuroTheme 一致的原创风格）。
// 纸感底色 + 陶土强调 + 墨色文字 + 鼠尾草绿。
object SettingsTheme {
    // 品牌主色 - 陶土
    val primaryColor = Color(0xFFC25A38)        // 主色
    val primaryVariant = Color(0xFFA8482B)      // 按下态

    // 强调色 - 鼠尾草绿（成功 / 已安装 / 思考）
    val accentColor = Color(0xFF6E7C62)

    // 背景与表面
    val backgroundColor = Color(0xFFF4F1EA)     // 纸底
    val terminalBackground = Color(0xFFF4F1EA)  // 纸底（设置页无终端画布，保持一致）
    val surfaceColor = Color(0xFFFFFFFF)         // 白卡片
    val surfaceVariant = Color(0xFFECE7DC)       // 次纸底
    val elevated = Color(0xFFF4E4DB)             // 浅陶土（选中 / 浅底按钮）
    val divider = Color(0xFFE3DDD0)              // 描边

    // 文字
    val onSurfaceColor = Color(0xFF211E1A)       // 主文字（墨）
    val onSurfaceVariant = Color(0xFF544D44)     // 次文字（次墨）
    val mutedColor = Color(0xFF938A7E)           // 弱文字 / 占位

    // 状态色
    val successColor = Color(0xFF6E7C62)         // 成功 / 已安装
    val warningColor = Color(0xFFB8902F)         // 警示 / 必须标签
    val errorColor = Color(0xFFB23A2E)           // 暖红错误 / 危险
    val errorVariant = Color(0xFF7A2417)         // 深红变体
}
