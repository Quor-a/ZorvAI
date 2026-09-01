package com.ai.assistance.quro.core.novaterm.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.text.BasicTextField
import com.ai.assistance.quro.core.novaterm.command.OutputStyle
import com.ai.assistance.quro.core.novaterm.executor.ProcessWatcher
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.style.*
import androidx.compose.ui.unit.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.input.key.*
import androidx.compose.ui.focus.*
import com.ai.assistance.quro.core.novaterm.ui.TerminalViewModel.*

/**
 * 终端主界面
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreen(viewModel: TerminalViewModel) {
    val theme by viewModel.theme.collectAsState()
    val entries by viewModel.entries.collectAsState()
    val input by viewModel.currentInput.collectAsState()
    val isExecuting by viewModel.isExecuting.collectAsState()
    val cursorBlink by viewModel.cursorBlink.collectAsState()
    val metrics by viewModel.metrics.collectAsState()
    val rootBackend by viewModel.rootBackendAvailable.collectAsState()

    val focusRequester = remember { FocusRequester() }
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    // 自动滚动到底部
    LaunchedEffect(entries.size) {
        if (entries.isNotEmpty()) {
            listState.animateScrollToItem(entries.size - 1)
        }
    }

    // 自动聚焦输入
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(theme.background)
            .clickable { focusRequester.requestFocus() }
    ) {
        Column(modifier = Modifier.fillMaxSize()) {

            // ===== 顶部工具栏 =====
            TerminalToolbar(
                theme = theme,
                onCycleTheme = { viewModel.cycleTheme() },
                onClear = { viewModel.clearAll() },
                metrics = metrics
            )

            // ===== ROOT 后端透明化横幅 =====
            RootNoticeBanner(theme = theme, state = rootBackend)

            // ===== 终端输出区域 =====
            TerminalOutput(
                theme = theme,
                entries = entries,
                listState = listState,
                modifier = Modifier.weight(1f)
            )

            // ===== 底部输入栏 =====
            TerminalInputBar(
                theme = theme,
                input = input,
                cursorVisible = cursorBlink,
                isExecuting = isExecuting,
                focusRequester = focusRequester,
                onInputChange = { viewModel.onInputChange(it) },
                onSubmit = { viewModel.onSubmit() },
                onHistoryUp = { viewModel.onHistoryUp() },
                onHistoryDown = { viewModel.onHistoryDown() }
            )
        }

        // 扫描线特效（Matrix/Cyberpunk 主题）
        if (theme.scanlineAlpha > 0) {
            ScanlineOverlay(alpha = theme.scanlineAlpha)
        }
    }
}

// ==================== 工具栏 ====================

@Composable
private fun TerminalToolbar(
    theme: TerminalTheme,
    onCycleTheme: () -> Unit,
    onClear: () -> Unit,
    metrics: ProcessWatcher.SystemMetrics
) {
    Surface(
        color = theme.toolbarBg,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 状态指示灯
                PulsingDot(color = if (metrics.memoryPct < 80) theme.outputSuccess else theme.outputWarning)

                Spacer(Modifier.width(8.dp))

                Text(
                    "ZorvAI 沙盒终端",
                    color = theme.toolbarFg,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )

                Spacer(Modifier.width(12.dp))

                Text(
                    "Mem: ${String.format("%.0f", metrics.memoryPct)}%",
                    color = theme.outputDim,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )
            }

            Row {
                // 主题切换按钮
                IconButton(onClick = onCycleTheme, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.Default.Palette,
                        contentDescription = "Theme",
                        tint = theme.toolbarFg,
                        modifier = Modifier.size(18.dp)
                    )
                }

                // 清除按钮
                IconButton(onClick = onClear, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.Default.DeleteSweep,
                        contentDescription = "Clear",
                        tint = theme.toolbarFg,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }

    // 分割线
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(theme.divider)
    )
}

/**
 * ROOT 后端透明化横幅：常驻展示 su/root 的真实执行能力，绝不误导用户。
 *
 * - true  ：已连接真实 ROOT 后端（Shizuku / su）
 * - false ：无真实 ROOT，su/root 输出为沙箱模拟（[模拟] 标注）
 * - null  ：探测中
 */
@Composable
private fun RootNoticeBanner(theme: TerminalTheme, state: Boolean?) {
    val (fg: Color, text: String) = when (state) {
        true -> theme.outputSuccess to
            "✓ 已连接真实 ROOT 后端（Shizuku / su）：su/root 命令将以真实 root 身份执行"
        false -> theme.outputWarning to
            "⚠ 本环境无 ROOT 权限：su/root 以下输出为沙箱模拟（[模拟] 标注），并非真实提权"
        null -> theme.outputDim to
            "… 正在检测 ROOT 后端（Shizuku / su）…"
    }
    Surface(color = theme.toolbarBg, modifier = Modifier.fillMaxWidth()) {
        Text(
            text = text,
            color = fg,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
        )
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(theme.divider)
    )
}

@Composable
private fun PulsingDot(color: Color) {
    val infiniteTransition = rememberInfiniteTransition()
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000),
            repeatMode = RepeatMode.Reverse
        )
    )
    Canvas(modifier = Modifier.size(8.dp)) {
        drawCircle(color = color.copy(alpha = alpha), radius = size.minDimension / 2)
    }
}

// ==================== 输出区域 ====================

@Composable
private fun TerminalOutput(
    theme: TerminalTheme,
    entries: List<TerminalEntry>,
    listState: LazyListState,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        state = listState,
        modifier = modifier
            .fillMaxWidth()
            .background(theme.background)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        contentPadding = PaddingValues(vertical = 4.dp)
    ) {
        items(entries, key = { it.id }) { entry ->
            TerminalLine(theme = theme, entry = entry)
        }

        // 执行中指示器
        if (entries.isNotEmpty() && entries.last().type == TerminalViewModel.EntryType.PROMPT) {
            item {
                // 提示符后面有输入框，不需要额外内容
            }
        }
    }
}

@Composable
private fun TerminalLine(theme: TerminalTheme, entry: TerminalEntry) {
    val color = when (entry.type) {
        TerminalViewModel.EntryType.PROMPT -> theme.promptColor
        TerminalViewModel.EntryType.INPUT -> theme.outputCyan
        TerminalViewModel.EntryType.ERROR -> theme.outputError
        TerminalViewModel.EntryType.SUCCESS -> theme.outputSuccess
        TerminalViewModel.EntryType.WARNING -> theme.outputWarning
        TerminalViewModel.EntryType.INFO -> theme.outputInfo
        TerminalViewModel.EntryType.SYSTEM -> theme.outputDim
        else -> getStyleColor(theme, entry.style)
    }

    val fontWeight = when {
        entry.style == OutputStyle.BOLD -> FontWeight.Bold
        entry.type == TerminalViewModel.EntryType.PROMPT -> FontWeight.SemiBold
        else -> FontWeight.Normal
    }

    val fontSize = when {
        entry.type == TerminalViewModel.EntryType.SYSTEM -> 10.sp
        else -> 13.sp
    }

    // 简单语法高亮：检测关键字
    val displayText = entry.text

    Text(
        text = buildAnnotatedString {
            append(displayText)
        },
        color = color,
        fontSize = fontSize,
        fontFamily = FontFamily.Monospace,
        fontWeight = fontWeight,
        lineHeight = 16.sp,
        modifier = Modifier.padding(vertical = 0.5.dp)
    )
}

private fun getStyleColor(theme: TerminalTheme, style: OutputStyle): Color {
    return when (style) {
        OutputStyle.CYAN -> theme.outputCyan
        OutputStyle.MAGENTA -> theme.outputMagenta
        OutputStyle.YELLOW -> theme.outputYellow
        OutputStyle.GREEN -> theme.outputGreen
        OutputStyle.RED -> theme.outputRed
        OutputStyle.BLUE -> theme.outputBlue
        OutputStyle.ERROR -> theme.outputError
        OutputStyle.WARNING -> theme.outputWarning
        OutputStyle.SUCCESS -> theme.outputSuccess
        OutputStyle.INFO -> theme.outputInfo
        OutputStyle.DEBUG -> theme.outputDebug
        OutputStyle.DIM -> theme.outputDim
        OutputStyle.BOLD -> theme.outputBold
        else -> theme.outputNormal
    }
}

// ==================== 输入栏 ====================

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun TerminalInputBar(
    theme: TerminalTheme,
    input: String,
    cursorVisible: Boolean,
    isExecuting: Boolean,
    focusRequester: FocusRequester,
    onInputChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onHistoryUp: () -> Unit,
    onHistoryDown: () -> Unit
) {
    Surface(
        color = theme.inputBg,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 提示符
            Text(
                "$ ",
                color = theme.promptColor,
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold
            )

            // 输入框
            BasicTextField(
                value = input,
                onValueChange = onInputChange,
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focusRequester)
                    .onKeyEvent { event ->
                        if (event.type == KeyEventType.KeyUp) {
                            when (event.key) {
                                Key.Enter -> {
                                    onSubmit()
                                    true
                                }
                                Key.DirectionUp -> {
                                    onHistoryUp()
                                    true
                                }
                                Key.DirectionDown -> {
                                    onHistoryDown()
                                    true
                                }
                                Key.Tab -> {
                                    // TODO: 自动补全
                                    true
                                }
                                else -> false
                            }
                        } else false
                    },
                textStyle = TextStyle(
                    color = theme.inputFg,
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace
                ),
                cursorBrush = SolidColor(theme.cursorColor),
                singleLine = true,
                decorationBox = { innerTextField ->
                    Box {
                        if (input.isEmpty()) {
                            Text(
                                if (isExecuting) "executing..." else "type a command...",
                                color = theme.outputDim,
                                fontSize = 13.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                        innerTextField()
                        // 自定义光标闪烁
                        if (cursorVisible && input.isNotEmpty()) {
                            // BasicTextField 自带光标，这里不需要额外绘制
                        }
                    }
                }
            )

            // 执行状态指示
            if (isExecuting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp,
                    color = theme.promptColor
                )
            }
        }
    }

    // 底部分割线
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(theme.divider)
    )
}

// ==================== 扫描线特效 ====================

@Composable
private fun ScanlineOverlay(alpha: Float) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val lineSpacing = 3.dp.toPx()
        val lineHeight = 1.dp.toPx()
        val totalLines = (size.height / lineSpacing).toInt()

        for (i in 0 until totalLines) {
            drawRect(
                color = Color.Black.copy(alpha = alpha),
                topLeft = Offset(0f, i * lineSpacing),
                size = Size(size.width, lineHeight)
            )
        }
    }
}
