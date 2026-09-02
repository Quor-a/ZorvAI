package com.ai.assistance.quro.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 通用「化小窗」悬浮窗容器：可拖拽标题栏 + 右下角缩放手柄 + 还原/关闭按钮。
 *
 * 内容由调用方以 content 槽注入（如内置浏览器 WebView、对话精简列表）。
 * 设计为纯 Compose 层内联浮层（不依赖系统悬浮窗权限），可叠加在任意界面之上，
 * 用于「把全屏内容收成可拖动小窗，边看边用其它功能」。
 */
@Composable
fun FloatingMiniWindow(
    title: String,
    modifier: Modifier = Modifier,
    initialX: Dp = 28.dp,
    initialY: Dp = 140.dp,
    initialWidth: Dp = 300.dp,
    initialHeight: Dp = 380.dp,
    minWidth: Dp = 180.dp,
    minHeight: Dp = 180.dp,
    titleBarColor: Color? = null,
    onRestore: () -> Unit,
    onClose: () -> Unit,
    /** 系统级浮窗拖拽：宿主接管移动整个窗口（传入后不再用内部 offset）。 */
    onDrag: ((dxPx: Float, dyPx: Float) -> Unit)? = null,
    /** 系统级浮窗缩放：面板尺寸变化后通知宿主重新测量窗口。 */
    onResize: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val density = LocalDensity.current
    var offsetX by remember { mutableStateOf(initialX) }
    var offsetY by remember { mutableStateOf(initialY) }
    var width by remember { mutableStateOf(initialWidth) }
    var height by remember { mutableStateOf(initialHeight) }
    val cs = MaterialTheme.colorScheme

    Box(
        modifier
            .offset {
                IntOffset(
                    with(density) { offsetX.roundToPx() },
                    with(density) { offsetY.roundToPx() },
                )
            }
            .size(width, height)
            .clip(RoundedCornerShape(14.dp))
            .background(cs.surface)
            .border(1.dp, cs.outline, RoundedCornerShape(14.dp)),
    ) {
        Column(Modifier.fillMaxSize()) {
            // 标题栏：拖拽移动
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(titleBarColor ?: cs.primaryContainer)
                    .padding(horizontal = 6.dp, vertical = 4.dp)
                    .pointerInput(Unit) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            if (onDrag != null) {
                                // 系统级浮窗：拖拽移动整个窗口（由宿主 updateViewLayout）。
                                onDrag(dragAmount.x, dragAmount.y)
                            } else {
                                // 应用内浮层：面板内部偏移（父容器即满屏 Box）。
                                val dx = with(density) { dragAmount.x.toDp() }
                                val dy = with(density) { dragAmount.y.toDp() }
                                offsetX = (offsetX + dx).coerceAtLeast(0.dp)
                                offsetY = (offsetY + dy).coerceAtLeast(0.dp)
                            }
                        }
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    title,
                    Modifier
                        .weight(1f)
                        .padding(start = 4.dp),
                    color = cs.onPrimaryContainer,
                    fontSize = 13.sp,
                    maxLines = 1,
                )
                IconButton(onClick = onRestore, Modifier.size(30.dp)) {
                    Icon(
                        Icons.Filled.OpenInFull,
                        "还原",
                        tint = cs.onPrimaryContainer,
                        modifier = Modifier.size(16.dp),
                    )
                }
                IconButton(onClick = onClose, Modifier.size(30.dp)) {
                    Icon(
                        Icons.Filled.Close,
                        "关闭",
                        tint = cs.onPrimaryContainer,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
            // 内容区
            Box(Modifier.fillMaxSize().weight(1f)) {
                content()
            }
        }
        // 右下角缩放手柄
        Box(
            Modifier
                .align(Alignment.BottomEnd)
                .size(20.dp)
                .background(cs.outline.copy(alpha = 0.4f))
                .pointerInput(Unit) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        val dx = with(density) { dragAmount.x.toDp() }
                        val dy = with(density) { dragAmount.y.toDp() }
                        width = (width + dx).coerceAtLeast(minWidth)
                        height = (height + dy).coerceAtLeast(minHeight)
                        onResize?.invoke()
                    }
                },
        )
    }
}
