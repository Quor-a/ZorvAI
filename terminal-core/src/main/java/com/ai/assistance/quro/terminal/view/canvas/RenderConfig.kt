package com.ai.assistance.quro.terminal.view.canvas

import android.graphics.Typeface

/**
 * 渲染配置
 */
data class RenderConfig(
    val fontSize: Float = 42f, // 默认字体大小（像素）
    val textSize: Float = fontSize, // 别名，保持兼容性
    val fontFamily: Typeface = Typeface.MONOSPACE,
    val backgroundColor: Int = 0xFF0B0E14.toInt(),   // 深蓝黑画布背景
    val foregroundColor: Int = 0xFFE6EDF3.toInt(),   // 主要前景色
    val defaultForegroundColor: Int = foregroundColor,
    val cursorColor: Int = 0xFF2DD4BF.toInt(),       // 青绿光标
    val cursorBlinkRate: Long = 500L, // 光标闪烁频率（毫秒）
    val lineSpacing: Float = 0.1f, // 行间距（相对于字符高度的比例）
    val charSpacing: Float = 0f, // 字符间距（像素）
    val targetFps: Int = 60, // 目标帧率
    val enableCharCache: Boolean = true, // 启用字符缓存
    val enableDirtyTracking: Boolean = true, // 启用脏区域追踪
    val enableFrameRateAdaptation: Boolean = true, // 启用帧率自适应
    val paddingLeft: Float = 16f,
    val paddingTop: Float = 16f,
    val paddingRight: Float = 16f,
    val paddingBottom: Float = 16f
) {
    fun withTextSize(newSize: Float): RenderConfig {
        return copy(fontSize = newSize, textSize = newSize)
    }
    
    fun getFrameDelay(): Long {
        return 1000L / targetFps
    }
}

