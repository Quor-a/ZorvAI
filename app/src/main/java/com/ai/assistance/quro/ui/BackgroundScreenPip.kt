package com.ai.assistance.quro.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.assistance.quro.core.display.PipPhysics
import com.ai.assistance.quro.core.display.QuroVirtualScreen
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * 后台虚拟屏的**悬浮画中画**（移植自 Mika 的 AgentPip，去品牌化）。
 *
 * 定位：只显示「AI 正在后台做什么」的画面，不承载操作；要操作就点一下进管理页。
 * 为什么需要它：后台屏对用户不可见，没有这块小窗，AI 在里面忙什么完全是黑盒。
 *
 * 交互（Mika 的拖动/吸角 + Extendroid 的三样）：
 *  - 拖动 1:1 跟手；松手按速度投射落点，吸附到最近的角（临界阻尼弹簧，见 [PipPhysics]）
 *  - **右下角手柄拖动 → 自由改变窗口大小**（对应 Extendroid 的 resizer）
 *  - **点画面 → 直接操控虚拟屏**（坐标按比例换算成虚拟屏像素后注入；开关可切回「点击进管理页」）
 *  - **右上角「—」→ 收成一颗漂浮球**，点球展开（对应 Extendroid 的可收起悬浮窗）
 *  - 点 × → 本次会话内隐藏
 *
 * 外层 Box 不消费触摸（没有 pointerInput），事件会穿透到下层对话，不影响正常聊天。
 */
@Composable
fun BackgroundScreenPip(
    onOpenManage: () -> Unit,
    topInsetPx: Float = 0f,
    bottomInsetPx: Float = 0f,
) {
    val state by QuroVirtualScreen.state.collectAsState()
    val frame by QuroVirtualScreen.frames.collectAsState()

    // 只有屏真的建好了、且用户没手动收起，才显示
    if (!state.ready || state.dismissed) return

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val areaW = constraints.maxWidth.toFloat()
        val areaH = constraints.maxHeight.toFloat()

        val ctx = androidx.compose.ui.platform.LocalContext.current
        val screenAspect = remember(ctx) {
            val m = ctx.resources.displayMetrics
            if (m.widthPixels > 0 && m.heightPixels > 0) m.heightPixels.toFloat() / m.widthPixels else 2.0f
        }
        val aspect = frame?.let { it.height.toFloat() / it.width } ?: screenAspect

        // ── Extendroid 三样：自由缩放 / 收起成球 / 直控虚拟屏 ──
        var scale by rememberSaveable { mutableStateOf(1f) }
        var collapsed by rememberSaveable { mutableStateOf(false) }
        // 直控开关：默认开着（用户要的就是「直接操控虚拟屏」），关掉则点击进管理页
        var directControl by rememberSaveable { mutableStateOf(true) }

        val marginPx = with(density) { 12.dp.toPx() }
        val baseW = with(density) { PIP_WIDTH.toPx() }
        val maxH = (areaH - topInsetPx - bottomInsetPx - marginPx).coerceAtLeast(1f)
        val wPx = minOf(baseW, areaW - marginPx * 2, maxH / aspect).coerceAtLeast(1f)
        val hPx = wPx * aspect

        // 缩放后的实际尺寸：scale ∈ [0.6, 2.2]，同时不超出可用区域
        val scaledWpx = (wPx * scale).coerceIn(wPx * 0.6f, minOf(wPx * 2.2f, areaW - marginPx * 2))
        val scaledHpx = scaledWpx * aspect

        val wDp = with(density) { scaledWpx.toDp() }
        val hDp = with(density) { scaledHpx.toDp() }

        val bounds = PipPhysics.Bounds(areaW, areaH, scaledWpx, scaledHpx, marginPx, topInsetPx, bottomInsetPx)
        val currentBounds by rememberUpdatedState(bounds)

        var corner by rememberSaveable { mutableStateOf(PipPhysics.Corner.BottomEnd) }
        var free by remember { mutableStateOf<Offset?>(null) }

        val scope = rememberCoroutineScope()
        var settle by remember { mutableStateOf<Job?>(null) }

        fun springTo(c: PipPhysics.Corner, start: Offset, vx: Float, vy: Float) {
            settle?.cancel()
            corner = c
            settle = scope.launch {
                val spring = PipPhysics.Spring()
                var mx = PipPhysics.Motion(start.x, vx)
                var my = PipPhysics.Motion(start.y, vy)
                var last = withFrameNanos { it }
                while (true) {
                    val now = withFrameNanos { it }
                    val dt = (now - last) / 1e9f
                    last = now
                    val (tx, ty) = currentBounds.position(c)
                    mx = spring.step(mx, tx, dt)
                    my = spring.step(my, ty, dt)
                    free = Offset(mx.position, my.position)
                    if (spring.settled(mx, tx) && spring.settled(my, ty)) break
                }
                free = null
            }
        }

        val pos = free ?: bounds.position(corner).let { (x, y) -> Offset(x, y) }
        val outer = RoundedCornerShape(16.dp)
        val inner = RoundedCornerShape(13.dp)

        // 画面出现时轻微放大，提示"有内容了"
        val widthAnim by animateDpAsState(wDp, tween(260), label = "pipW")

        // ── 收起形态：一颗漂浮球，点它展开 ──
        if (collapsed) {
            Box(
                Modifier.offset { IntOffset(pos.x.roundToInt(), pos.y.roundToInt()) }
                    .size(BALL_SIZE)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.92f))
                    .clickable { collapsed = false },
                contentAlignment = Alignment.Center,
            ) {
                Text("AI", color = MaterialTheme.colorScheme.onPrimary, fontSize = 11.sp)
            }
            return@BoxWithConstraints
        }

        Box(
            Modifier.offset { IntOffset(pos.x.roundToInt(), pos.y.roundToInt()) }
                .size(widthAnim, hDp)
                .clip(outer)
                .background(MaterialTheme.colorScheme.surface)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, outer)
                .pointerInput(Unit) {
                    val tracker = VelocityTracker()
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val origin = free ?: currentBounds.position(corner).let { (x, y) -> Offset(x, y) }
                        var total = Offset.Zero
                        var dragging = false
                        tracker.resetTracking()
                        tracker.addPosition(down.uptimeMillis, origin + down.position)
                        while (true) {
                            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            total += change.positionChange()
                            if (!dragging && total.getDistance() > viewConfiguration.touchSlop) {
                                dragging = true
                                settle?.cancel()
                            }
                            if (dragging) {
                                change.consume()
                                free = origin + total
                                tracker.addPosition(change.uptimeMillis, origin + down.position + total)
                            }
                        }
                        if (dragging) {
                            val start = free ?: return@awaitEachGesture
                            val v = tracker.calculateVelocity()
                            springTo(PipPhysics.snapCorner(start.x, start.y, v.x, v.y, currentBounds), start, v.x, v.y)
                        }
                    }
                }
                .pointerInput(directControl, scaledWpx, scaledHpx, frame) {
                    detectTapGestures(
                        onTap = { offset ->
                            if (!directControl) {
                                onOpenManage()
                                return@detectTapGestures
                            }
                            // 把点击位置换算成虚拟屏像素：小窗显示的是整块屏的等比缩略
                            val sysW = frame?.width ?: ctx.resources.displayMetrics.widthPixels
                            val sysH = frame?.height ?: ctx.resources.displayMetrics.heightPixels
                            if (sysW <= 0 || sysH <= 0) return@detectTapGestures
                            val vx = (offset.x / scaledWpx * sysW).toInt().coerceIn(0, sysW - 1)
                            val vy = (offset.y / scaledHpx * sysH).toInt().coerceIn(0, sysH - 1)
                            QuroVirtualScreen.tapAt(ctx, vx, vy)
                        },
                        // 长按进管理页（直控模式下也要有办法进管理页）
                        onLongPress = { onOpenManage() },
                    )
                },
        ) {
            if (frame != null) {
                Image(
                    frame!!.asImageBitmap(),
                    contentDescription = "后台屏画面",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("后台屏", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            // 右上角按钮组：收起成球 / 直控开关 / 关闭
            androidx.compose.foundation.layout.Row(
                Modifier.align(Alignment.TopEnd).padding(4.dp),
            ) {
                PipChip("—") { collapsed = true }
                PipChip(if (directControl) "✋" else "↗") {
                    directControl = !directControl
                }
                PipChip("×") { QuroVirtualScreen.dismissUi() }
            }

            // 右下角缩放手柄：拖动改窗口大小（对应 Extendroid 的 resizer）
            Box(
                Modifier.align(Alignment.BottomEnd).size(22.dp)
                    .pointerInput(Unit) {
                        detectDragGestures { change, drag ->
                            change.consume()
                            // 按像素增量换算成比例：往右/下拖变大
                            val delta = (drag.x + drag.y) / wPx
                            scale = (scale + delta).coerceIn(0.6f, 2.2f)
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier.size(10.dp).clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)),
                )
            }
        }
    }
}

/** 小窗右上角的圆形小按钮。 */
@Composable
private fun PipChip(text: String, onClick: () -> Unit) {
    Box(
        Modifier.padding(start = 3.dp).size(20.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurface)
    }
}

private val PIP_WIDTH = 96.dp

/** 收起后的漂浮球直径。 */
private val BALL_SIZE = 42.dp
