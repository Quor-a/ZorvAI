package com.ai.assistance.quro.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import kotlin.math.max

/**
 * 逐字打字机文字组件（原创，零第三方依赖）。
 *
 * 进度按 Unicode 码点计数，emoji 代理对不会被拆成两帧。完整透明文本永久占位，
 * 动画层在其上绘制，避免打字过程中宽度/高度/换行抖动。
 *
 * - [TypewriterCursor.BAR]：竖线光标
 * - [TypewriterCursor.ROUND_DOT]：圆点光标
 */
enum class TypewriterCursor(val glyph: String) {
    BAR("|"),
    ROUND_DOT("●"),
}

@Composable
fun TypewriterText(
    text: String,
    animationKey: Any,
    modifier: Modifier = Modifier,
    style: TextStyle = TextStyle.Default,
    fontWeight: FontWeight? = null,
    color: Color = Color.Unspecified,
    textAlign: TextAlign? = null,
    typeSpeedMs: Int = 40,
    initialDelayMs: Int = 0,
    animate: Boolean = true,
    cursor: TypewriterCursor = TypewriterCursor.BAR,
    onDone: () -> Unit = {},
) {
    val codePointCount = remember(text) { text.codePointCount(0, text.length) }
    val safeTypeSpeedMs = typeSpeedMs.coerceAtLeast(1)
    val safeInitialDelayMs = initialDelayMs.coerceAtLeast(0)
    val baseColor = when {
        color != Color.Unspecified -> color
        style.color != Color.Unspecified -> style.color
        else -> LocalContentColor.current
    }
    val latestOnDone by rememberUpdatedState(onDone)

    var visibleCodePoints by rememberSaveable(animationKey, text, animate) {
        mutableIntStateOf(if (animate) 0 else codePointCount)
    }
    var completed by rememberSaveable(animationKey, text, animate) {
        mutableStateOf(!animate)
    }
    var animationElapsedMs by remember(animationKey, text, animate) {
        mutableLongStateOf(0L)
    }

    LaunchedEffect(animationKey, text, animate, safeTypeSpeedMs, safeInitialDelayMs, cursor) {
        if (!animate || completed) return@LaunchedEffect
        if (codePointCount == 0) {
            visibleCodePoints = 0
            completed = true
            latestOnDone()
            return@LaunchedEffect
        }
        val resumedElapsedMs = if (visibleCodePoints == 0) {
            0L
        } else {
            safeInitialDelayMs.toLong() + visibleCodePoints.toLong() * safeTypeSpeedMs
        }
        val firstFrameNanos = withFrameNanos { it }
        val startNanos = firstFrameNanos - resumedElapsedMs * 1_000_000L
        val finishElapsedMs = safeInitialDelayMs.toLong() + codePointCount.toLong() * safeTypeSpeedMs

        while (!completed) {
            val frameNanos = withFrameNanos { it }
            val elapsedMs = ((frameNanos - startNanos).coerceAtLeast(0L) / 1_000_000L)
            animationElapsedMs = elapsedMs
            val revealElapsedMs = (elapsedMs - safeInitialDelayMs).coerceAtLeast(0L)
            val targetCount = (revealElapsedMs / safeTypeSpeedMs).toInt().coerceIn(0, codePointCount)
            visibleCodePoints = max(visibleCodePoints, targetCount)
            if (elapsedMs >= finishElapsedMs) {
                visibleCodePoints = codePointCount
                completed = true
                latestOnDone()
            }
        }
    }

    val animatedText = remember(text, visibleCodePoints, completed, animationElapsedMs, cursor, baseColor) {
        buildCursorTypingText(text, visibleCodePoints, completed, animationElapsedMs, cursor, baseColor)
    }

    Box(modifier = modifier) {
        Text(
            text = text,
            style = style,
            fontWeight = fontWeight,
            color = Color.Transparent,
            textAlign = textAlign,
            modifier = Modifier.clearAndSetSemantics { },
        )
        Text(
            text = animatedText,
            style = style,
            fontWeight = fontWeight,
            color = baseColor,
            textAlign = textAlign,
        )
    }
}

private fun buildCursorTypingText(
    text: String,
    visibleCodePoints: Int,
    completed: Boolean,
    elapsedMs: Long,
    cursor: TypewriterCursor,
    baseColor: Color,
): AnnotatedString = buildAnnotatedString {
    if (completed) {
        append(text)
        return@buildAnnotatedString
    }
    var charOffset = 0
    repeat(visibleCodePoints) {
        val start = charOffset
        charOffset += Character.charCount(text.codePointAt(charOffset))
        append(text, start, charOffset)
    }
    val cursorStart = length
    append(cursor.glyph)
    val cycleMs = 1_060L
    val halfCycleMs = cycleMs / 2L
    val phaseMs = elapsedMs.mod(cycleMs)
    val cursorAlpha = if (phaseMs <= halfCycleMs) {
        1f - phaseMs.toFloat() / halfCycleMs
    } else {
        (phaseMs - halfCycleMs).toFloat() / halfCycleMs
    }
    addStyle(
        SpanStyle(color = baseColor.copy(alpha = baseColor.alpha * cursorAlpha)),
        start = cursorStart,
        end = length,
    )
}
