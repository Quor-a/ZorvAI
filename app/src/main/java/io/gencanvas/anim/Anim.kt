package io.gencanvas.anim

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.gencanvas.model.AnimSpec
import io.gencanvas.model.AnimValues
import kotlinx.coroutines.delay

/**
 * 动画层：声明式时间线 → 0..1 进度 → [AnimValues]。
 *
 * 只驱动 transform / alpha，交给 GPU 合成，不触发重新绘制。
 * 所有时长被 clamp 到 [0, 10_000]，防止 AI 生成永动动画耗尽资源。
 */

private const val MAX_DUR = 10_000

@Composable
fun rememberAnimValues(spec: AnimSpec?, pressed: Boolean, watchKey: Any? = null): AnimValues {
    val from = remember(spec) { AnimValues.from(spec?.from ?: emptyMap()) }
    val to = remember(spec) { AnimValues.from(spec?.to ?: emptyMap(), from) }
    val progress = remember { Animatable(0f) }

    val dur = (spec?.dur ?: 300).coerceIn(0, MAX_DUR)
    val delayMs = (spec?.delay ?: 0).coerceIn(0, MAX_DUR)

    LaunchedEffect(spec, pressed, watchKey) {
        if (spec == null) { progress.snapTo(1f); return@LaunchedEffect }
        val target = when (spec.trigger) {
            "press" -> if (pressed) 1f else 0f
            "state" -> 1f
            "loop" -> 1f
            else -> 1f     // appear
        }
        delay(delayMs.toLong())
        val spec0 = buildSpec(spec)
        if (spec.repeat == "infinite") {
            progress.animateTo(target, infiniteRepeatable(spec0 as androidx.compose.animation.core.DurationBasedAnimationSpec<Float>, RepeatMode.Restart))
        } else {
            progress.snapTo(if (target == 1f) 0f else 1f)
            progress.animateTo(target, spec0)
        }
    }

    val p by progress.asState()
    return lerp(from, to, p)
}

private fun buildSpec(spec: AnimSpec): androidx.compose.animation.core.AnimationSpec<Float> {
    val dur = (spec.dur).coerceIn(0, MAX_DUR)
    return when (spec.easing) {
        "linear" -> tween(dur, 0, LinearEasing)
        "decelerate" -> tween(dur, 0, LinearOutSlowInEasing)
        "accelerate" -> tween(dur, 0, CubicBezierEasing(0.4f, 0f, 1f, 1f))
        "bounce" -> tween(dur, 0, CubicBezierEasing(0.68f, -0.55f, 0.27f, 1.55f))
        "spring" -> spring(visibilityThreshold = 0.001f, stiffness = Spring.StiffnessMedium)
        else -> tween(dur, 0, FastOutSlowInEasing)
    }
}

private fun lerp(a: AnimValues, b: AnimValues, t: Float) = AnimValues(
    alpha = a.alpha + (b.alpha - a.alpha) * t,
    scaleX = a.scaleX + (b.scaleX - a.scaleX) * t,
    scaleY = a.scaleY + (b.scaleY - a.scaleY) * t,
    rotate = a.rotate + (b.rotate - a.rotate) * t,
    tx = a.tx + (b.tx - a.tx) * t,
    ty = a.ty + (b.ty - a.ty) * t,
)

/** 按压态：pressAnim 的轻量版本，无需 AnimSpec 也可工作。 */
@Composable
fun rememberPressScale(scale: Float?, dur: Int, pressed: Boolean): Float {
    if (scale == null) return 1f
    val a = remember { Animatable(1f) }
    LaunchedEffect(pressed) {
        a.animateTo(if (pressed) scale else 1f, tween(dur.coerceIn(0, MAX_DUR)))
    }
    val v by a.asState()
    return v
}
