package com.ai.assistance.quro.genui.sdk.animation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExitTransition
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.ai.assistance.quro.genui.sdk.dsl.UIAnimation

/**
 * 入场动画包装组件
 * 根据 UIAnimation 配置应用对应的进入动画
 * 动画延迟和缓动曲线由 GenUIAnimations.enterTransition 内部处理
 */
@Composable
fun AnimatedEntrance(
    animation: UIAnimation,
    content: @Composable () -> Unit
) {
    var visible by remember { mutableStateOf(false) }

    LaunchedEffect(animation.type, animation.durationMs, animation.delayMs, animation.easing) {
        visible = true
    }

    AnimatedVisibility(
        visible = visible,
        enter = GenUIAnimations.enterTransition(animation),
        exit = ExitTransition.None
    ) {
        content()
    }
}
