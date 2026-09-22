package com.ai.assistance.quro.genui.sdk.animation

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.ui.Alignment
import com.ai.assistance.quro.genui.sdk.dsl.UIAnimation
import kotlin.math.roundToInt

/**
 * GenUI 动画工具对象
 * 提供缓动曲线解析和入场动画构建
 */
object GenUIAnimations {

    /**
     * 根据名称解析缓动曲线
     * 支持: linear, fast_out, linear_out
     * 默认: FastOutSlowInEasing
     */
    fun easing(name: String?): Easing {
        return when (name?.lowercase()) {
            "linear" -> LinearEasing
            "fast_out" -> FastOutSlowInEasing
            "linear_out" -> LinearOutSlowInEasing
            else -> FastOutSlowInEasing
        }
    }

    /**
     * 根据 UIAnimation 配置构建入场动画过渡
     * 支持类型: fade, slide, slide_horizontal, scale, expand
     * 所有动画均叠加淡入效果
     */
    fun enterTransition(animation: UIAnimation): EnterTransition {
        val duration = animation.durationMs.coerceAtLeast(1)
        val delay = animation.delayMs.coerceAtLeast(0)
        val easing = easing(animation.easing)

        return when (animation.type.lowercase()) {
            "slide_horizontal" -> slideInHorizontally(
                animationSpec = tween(durationMillis = duration, delayMillis = delay, easing = easing),
                initialOffsetX = { -it / 3 }
            ) + fadeIn(animationSpec = tween(durationMillis = duration, delayMillis = delay, easing = easing))

            "expand" -> expandVertically(
                animationSpec = tween(durationMillis = duration, delayMillis = delay, easing = easing),
                expandFrom = Alignment.Top
            ) + fadeIn(animationSpec = tween(durationMillis = duration, delayMillis = delay, easing = easing))

            "scale" -> scaleIn(
                animationSpec = tween(durationMillis = duration, delayMillis = delay, easing = easing),
                initialScale = 0.85f
            ) + fadeIn(animationSpec = tween(durationMillis = duration, delayMillis = delay, easing = easing))

            "slide" -> slideInVertically(
                animationSpec = tween(durationMillis = duration, delayMillis = delay, easing = easing),
                initialOffsetY = { it / 3 }
            ) + fadeIn(animationSpec = tween(durationMillis = duration, delayMillis = delay, easing = easing))

            else -> fadeIn(animationSpec = tween(durationMillis = duration, delayMillis = delay, easing = easing))
        }
    }
}
