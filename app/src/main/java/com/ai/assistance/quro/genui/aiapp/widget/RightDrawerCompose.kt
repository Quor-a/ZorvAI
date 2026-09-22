package com.ai.assistance.quro.genui.aiapp.widget

import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * 右侧抽屉（纯 Compose 四锁手势，移植自原生 RightDrawerLayout）
 *
 * ① 位置锁：收起只认右缘 edgeSize 内按下；展开只认抽屉本体右拉（点遮罩 tap 关）
 * ② 轴向锁：|dx|>slop 且 |dy|≤|dx|*0.6 且 |dx|>|dy| 才算横滑；判定后锁定（竖滑彻底退出）
 * ③ 方向锁：收起只左滑、展开只右拉
 * ④ 速度锁：松手 |vx|>fling → 甩向开/关；否则按位置吸附（>50% 开）
 *
 * 拖动期用普通 State 直写（不挂起）；松手动画用协程 launch。
 */
@Composable
fun RightDrawerCompose(
    drawerWidthDp: Int = 300,
    edgeSizeDp: Int = 40,
    onOpenStateChange: (Boolean) -> Unit = { },
    drawerCommand: () -> Float? = { null },
    drawer: @Composable BoxScope.() -> Unit,
    content: @Composable BoxScope.() -> Unit
) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    var slideOffset by remember { mutableFloatStateOf(0f) }     // 0..1
    var containerWidth by remember { mutableStateOf(0) }
    val drawerWidthPx = with(density) { drawerWidthDp.dp.toPx() }
    var animJob by remember { mutableStateOf<Job?>(null) }
    var containerW by remember { mutableStateOf(0) }

    LaunchedEffect(slideOffset) { onOpenStateChange(slideOffset > 0.5f) }
    // 外部指令（openSidePanel/closeSidePanel）
    LaunchedEffect(Unit) {
        while (true) {
            val t = drawerCommand()
            if (t != null) {
                animJob?.cancel()
                animJob = scope.launch {
                    val from = slideOffset
                    val delta = t - from
                    val start = System.nanoTime()
                    val durNs = 260_000_000L
                    while (true) {
                        val p = ((System.nanoTime() - start).toFloat() / durNs).coerceAtMost(1f)
                        val eased = 1f - (1f - p) * (1f - p)   // easeOut
                        slideOffset = from + delta * eased
                        if (p >= 1f) break
                        kotlinx.coroutines.delay(16)
                    }
                }
            }
            kotlinx.coroutines.delay(50)
        }
    }

    fun animateTo(target: Float) {
        animJob?.cancel()
        animJob = scope.launch {
            val from = slideOffset
            val delta = target - from
            val start = System.nanoTime()
            val durNs = 260_000_000L
            while (true) {
                val p = ((System.nanoTime() - start).toFloat() / durNs).coerceAtMost(1f)
                val eased = 1f - (1f - p) * (1f - p)
                slideOffset = from + delta * eased
                if (p >= 1f) break
                kotlinx.coroutines.delay(16)
            }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .onSizeChanged { containerW = it.width; containerWidth = it.width }
            .pointerInput(containerW, drawerWidthPx) {
                val slop = 24.dp.toPx()
                val edge = edgeSizeDp.dp.toPx()
                val fling = 1800f
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val drawerLeftPx = size.width - drawerWidthPx * slideOffset
                    val canStart = if (slideOffset <= 0.001f) down.position.x >= size.width - edge
                    else down.position.x >= drawerLeftPx
                    if (!canStart) {
                        // 展开态点内容区遮罩 → tap 关闭
                        if (slideOffset > 0f && down.position.x < drawerLeftPx) {
                            var movedFar = false
                            while (true) {
                                val ev = awaitPointerEvent()
                                val c = ev.changes.firstOrNull() ?: break
                                if (abs(c.position.x - down.position.x) + abs(c.position.y - down.position.y) > 12.dp.toPx()) {
                                    movedFar = true; break
                                }
                                if (!c.pressed) break
                            }
                            if (!movedFar) animateTo(0f)
                        }
                        return@awaitEachGesture
                    }
                    val downX = down.position.x
                    val downY = down.position.y
                    var lastX = downX
                    var axis = 0
                    var dragging = false
                    val vt = VelocityTracker()
                    vt.resetTracking()
                    while (true) {
                        val ev = awaitPointerEvent()
                        val c = ev.changes.firstOrNull() ?: break
                        val px = c.position.x
                        val py = c.position.y
                        if (axis == 0) {
                            val adx = abs(px - downX)
                            val ady = abs(py - downY)
                            if (adx >= slop || ady >= slop) {
                                axis = if (adx > ady && ady <= adx * 0.6f) 1 else 2
                            }
                        }
                        if (axis == 2) break
                        if (axis == 1) {
                            val dxTotal = px - downX
                            val dirOk = if (slideOffset >= 0.999f) dxTotal > 0
                            else if (slideOffset <= 0.001f) dxTotal < 0 else true
                            if (dirOk) {
                                val dx = px - lastX
                                lastX = px
                                vt.addPosition(c.uptimeMillis, c.position)
                                animJob?.cancel()
                                slideOffset = (slideOffset - dx / drawerWidthPx).coerceIn(0f, 1f)
                                c.consume()
                                dragging = true
                            }
                        }
                        if (!c.pressed) {
                            if (dragging) {
                                val vx = vt.calculateVelocity().x
                                val target = when {
                                    abs(vx) > fling -> if (vx < 0) 1f else 0f
                                    else -> if (slideOffset > 0.5f) 1f else 0f
                                }
                                animateTo(target)
                            }
                            break
                        }
                    }
                }
            }
    ) {
        content()
        Box(
            Modifier
                .offset { IntOffset((containerW - drawerWidthPx * slideOffset).toInt(), 0) }
                .width(with(density) { drawerWidthPx.toDp() })
                .fillMaxSize()
        ) {
            drawer()
        }
    }
}
