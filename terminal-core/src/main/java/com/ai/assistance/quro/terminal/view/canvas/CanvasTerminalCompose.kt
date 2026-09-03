package com.ai.assistance.quro.terminal.view.canvas

import android.view.MotionEvent
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.ai.assistance.quro.terminal.domain.ansi.AnsiTerminalEmulator

/**
 * Compose集成桥接
 * 将CanvasTerminalView包装为Compose组件
 */
@Composable
fun CanvasTerminalScreen(
    emulator: AnsiTerminalEmulator,
    modifier: Modifier = Modifier,
    config: RenderConfig = RenderConfig(),
    pty: com.ai.assistance.quro.terminal.Pty? = null,
    onInput: (String) -> Unit = {},
    onScaleChanged: (Float) -> Unit = {}
) {
    AndroidView(
        factory = { context ->
            CanvasTerminalView(context, config = config).apply {
                setEmulator(emulator)
                setPty(pty)
                setInputCallback(onInput)
                setScaleCallback(onScaleChanged)
                
                // 全屏模式下自动请求焦点
                post {
                    requestFocus()
                }
                
                // 请求父容器不要拦截触摸事件，让终端视图处理滚动和缩放手势
                setOnTouchListener { v, event ->
                    when (event.action) {
                        MotionEvent.ACTION_DOWN ->
                            v.parent?.requestDisallowInterceptTouchEvent(true)
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                            v.parent?.requestDisallowInterceptTouchEvent(false)
                    }
                    false // 返回 false 让 View 继续处理事件
                }
            }
        },
        update = { view ->
            view.setEmulator(emulator)
            view.setPty(pty)
            view.setInputCallback(onInput)
        },
        modifier = modifier
    )
}

/**
 * 带配置的Canvas终端视图
 */
@Composable
fun ConfigurableCanvasTerminal(
    emulator: AnsiTerminalEmulator,
    modifier: Modifier = Modifier,
    fontSize: Float = 14f,
    backgroundColor: Int = 0xFF0B0E14.toInt(),
    foregroundColor: Int = 0xFFE6EDF3.toInt(),
    cursorColor: Int = 0xFF2DD4BF.toInt(),
    onInput: (String) -> Unit = {}
) {
    val density = LocalDensity.current
    // sp → px：终端画布按物理像素布局，必须换算（否则不同密度手机字号/排版不一致）
    val fontSizePx = with(density) { fontSize.sp.toPx() }
    val config = remember(fontSizePx, backgroundColor, foregroundColor, cursorColor) {
        RenderConfig(
            fontSize = fontSizePx,
            backgroundColor = backgroundColor,
            foregroundColor = foregroundColor,
            cursorColor = cursorColor
        )
    }
    
    var currentScale by remember { mutableStateOf(1f) }
    
    CanvasTerminalScreen(
        emulator = emulator,
        modifier = modifier,
        config = config,
        onInput = onInput,
        onScaleChanged = { scale -> currentScale = scale }
    )
}

/**
 * 性能监控版本的Canvas终端
 */
@Composable
fun PerformanceMonitoredTerminal(
    emulator: AnsiTerminalEmulator,
    modifier: Modifier = Modifier,
    config: RenderConfig = RenderConfig(),
    onInput: (String) -> Unit = {},
    onFpsUpdate: (Float) -> Unit = {}
) {
    AndroidView(
        factory = { context ->
            CanvasTerminalView(context, config = config).apply {
                setEmulator(emulator)
                setInputCallback(onInput)
                setPerformanceCallback { fps: Float, frameTime: Long ->
                    onFpsUpdate(fps)
                }
                
                // 请求父容器不要拦截触摸事件，让终端视图处理滚动和缩放手势
                setOnTouchListener { v, event ->
                    when (event.action) {
                        MotionEvent.ACTION_DOWN ->
                            v.parent?.requestDisallowInterceptTouchEvent(true)
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                            v.parent?.requestDisallowInterceptTouchEvent(false)
                    }
                    false // 返回 false 让 View 继续处理事件
                }
            }
        },
        update = { view ->
            view.setEmulator(emulator)
        },
        modifier = modifier
    )
}

/**
 * 非全屏Canvas终端输出
 * 仅用于显示终端输出，不处理输入
 */
@Composable
fun CanvasTerminalOutput(
    emulator: AnsiTerminalEmulator,
    modifier: Modifier = Modifier,
    config: RenderConfig = RenderConfig(),
    pty: com.ai.assistance.quro.terminal.Pty? = null
) {
    AndroidView(
        factory = { context ->
            CanvasTerminalView(context, config = config).apply {
                setEmulator(emulator)
                setPty(pty)
                setFullscreenMode(false) // 关键：设置为非全屏模式
                
                // 请求父容器不要拦截触摸事件，让终端视图处理滚动手势
                setOnTouchListener { v, event ->
                    when (event.action) {
                        MotionEvent.ACTION_DOWN ->
                            v.parent?.requestDisallowInterceptTouchEvent(true)
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                            v.parent?.requestDisallowInterceptTouchEvent(false)
                    }
                    false // 返回 false 让 View 继续处理事件
                }
            }
        },
        update = { view ->
            view.setEmulator(emulator)
            view.setPty(pty)
        },
        modifier = modifier
    )
}

