package com.ai.assistance.quro.genui.sdk.dsl

import kotlinx.serialization.Serializable

@Serializable
data class UIStyle(
    val width: Dimension? = null,
    val height: Dimension? = null,
    val padding: EdgeInsets = EdgeInsets(0f, 0f, 0f, 0f),
    val margin: EdgeInsets = EdgeInsets(0f, 0f, 0f, 0f),
    val backgroundColor: String? = null,
    val borderColor: String? = null,
    val borderWidth: Float = 0f,
    val cornerRadius: Float = 0f,
    val elevation: Float = 0f,
    val opacity: Float = 1f,
    val clip: Boolean = false,
    val alignment: String? = null,
    val crossAlignment: String? = null,
    val arrangement: String? = null,
    val gravity: String? = null,
    val textColor: String? = null,
    val textSize: Float? = null,
    val fontWeight: String? = null,
    val fontFamily: String? = null,
    val textAlign: String? = null,
    val letterSpacing: Float? = null,
    val lineHeight: Float? = null,
    val maxLines: Int? = null,
    val overflow: String? = null,
    val textStyle: String? = null,
    val shape: String? = null,
    // ===== 效果引擎 v2：全组件共享的正交视觉参数 =====
    val gradient: String? = null,        // 线性渐变 "#FF6B6B,#4ECDC4,#1A535C"（2-3 色）
    val gradientAngle: Float = 0f,       // 渐变角度（度）
    val glow: String? = null,            // 霓虹辉光颜色 "#22D3EE"
    val glowRadius: Float = 0f,          // 辉光半径 dp
    val pattern: String? = null,         // 纹理 dots|stripes|grid|checker
    val patternColor: String? = null,    // 纹理颜色
    val rotate: Float = 0f,              // 旋转角度（度，可为负）
    val contentDescription: String? = null,
    val semanticsRole: String? = null
)
