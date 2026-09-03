package com.ai.assistance.quro.terminal.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.assistance.quro.terminal.TerminalEnv
import com.ai.assistance.quro.terminal.data.TerminalSessionData
import com.ai.assistance.quro.terminal.utils.CommandSanitizer
import com.ai.assistance.quro.terminal.view.canvas.CanvasTerminalOutput
import com.ai.assistance.quro.terminal.view.canvas.CanvasTerminalScreen
import com.ai.assistance.quro.terminal.view.canvas.RenderConfig

@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun TerminalHome(
    env: TerminalEnv,
    onNavigateToSetup: () -> Unit
) {
    val fontSize = 14.sp
    val padding = 8.dp

    // 终端画布配置：字号按 sp→px 换算（Canvas 物理像素布局，不同密度手机排版一致）
    val density = LocalDensity.current
    val terminalConfig = remember {
        RenderConfig(
            fontSize = with(density) { 14.sp.toPx() },
            backgroundColor = TerminalTheme.terminalBackground.toArgb(),
            foregroundColor = TerminalTheme.onSurfaceColor.toArgb(),
            cursorColor = TerminalTheme.accentColor.toArgb()
        )
    }

    // 删除确认弹窗状态
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }
    var sessionToDelete by remember { mutableStateOf<String?>(null) }

    // 权限 / 远程连接面板状态
    var showPrivilegePanel by remember { mutableStateOf(false) }
    var showRemotePanel by remember { mutableStateOf(false) }

    // 获取当前 session 的 PTY
    val currentPty = remember(env.currentSessionId, env.sessions) {
        env.sessions.find { it.id == env.currentSessionId }?.pty
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(TerminalTheme.terminalBackground)
    ) {
        // 顶部栏（全屏沉浸模式时隐藏）
        if (!env.isFullscreen) {
            TopBar(
                sessions = env.sessions,
                currentSessionId = env.currentSessionId,
                onSwitchSession = env::onSwitchSession,
                onNewSession = env::onNewSession,
                onRequestDelete = { sessionId ->
                    sessionToDelete = sessionId
                    showDeleteConfirmDialog = true
                },
                onNavigateToSetup = onNavigateToSetup,
                onOpenPrivilege = { showPrivilegePanel = true },
                onOpenRemote = { showRemotePanel = true }
            )
        }

        if (env.isFullscreen) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .imePadding()
            ) {
                // 全屏终端输出
                CanvasTerminalScreen(
                    emulator = env.terminalEmulator,
                    modifier = Modifier.weight(1f),
                    config = terminalConfig,
                    pty = currentPty,
                    onInput = { env.onSendInput(it, false) }
                )

                // 虚拟键盘
                VirtualKeyboard(
                    onKeyPress = { key -> env.onSendInput(key, false) },
                    fontSize = fontSize * 0.7f,
                    padding = padding * 0.5f
                )
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .imePadding()
            ) {
                // 终端输出区域
                CanvasTerminalOutput(
                    emulator = env.terminalEmulator,
                    modifier = Modifier.weight(1f),
                    config = terminalConfig,
                    pty = currentPty
                )

                // 底部输入卡（中断 + 输入框 + 发送）
                InputBar(
                    command = env.command,
                    onCommandChange = env::onCommandChange,
                    onSend = { env.onSendInput(env.command, true) },
                    onInterrupt = env::onInterrupt,
                    fontSize = fontSize,
                    padding = padding
                )

                // 虚拟快捷键键盘（非全屏模式同样可用：ESC/TAB/方向键/Ctrl 组合键）
                VirtualKeyboard(
                    onKeyPress = { key -> env.onSendInput(key, false) },
                    fontSize = fontSize * 0.7f,
                    padding = padding * 0.5f
                )
            }
        }
    }

    // 删除确认弹窗
    if (showDeleteConfirmDialog && sessionToDelete != null) {
        val context = LocalContext.current
        val sessionTitle = env.sessions.find { it.id == sessionToDelete }?.title
            ?: context.getString(com.ai.assistance.quro.terminal.R.string.unknown_session)

        AlertDialog(
            onDismissRequest = {
                showDeleteConfirmDialog = false
                sessionToDelete = null
            },
            title = {
                Text(
                    text = context.getString(com.ai.assistance.quro.terminal.R.string.confirm_delete_session),
                    color = TerminalTheme.onSurfaceColor
                )
            },
            text = {
                Text(
                    text = context.getString(com.ai.assistance.quro.terminal.R.string.delete_session_message, sessionTitle),
                    color = TerminalTheme.onSurfaceVariant
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        sessionToDelete?.let { sessionId ->
                            env.onCloseSession(sessionId)
                        }
                        showDeleteConfirmDialog = false
                        sessionToDelete = null
                    }
                ) {
                    Text(
                        text = context.getString(com.ai.assistance.quro.terminal.R.string.delete),
                        color = TerminalTheme.errorColor
                    )
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirmDialog = false
                        sessionToDelete = null
                    }
                ) {
                    Text(
                        text = context.getString(com.ai.assistance.quro.terminal.R.string.cancel),
                        color = TerminalTheme.onSurfaceColor
                    )
                }
            },
            containerColor = TerminalTheme.surfaceColor,
            titleContentColor = TerminalTheme.onSurfaceColor,
            textContentColor = TerminalTheme.onSurfaceVariant
        )
    }

    // 权限面板
    if (showPrivilegePanel) {
        TerminalPrivilegePanel(onDismiss = { showPrivilegePanel = false })
    }

    // 远程连接面板（SSH / VNC）
    if (showRemotePanel) {
        TerminalRemotePanel(onDismiss = { showRemotePanel = false })
    }
}

/**
 * 顶部栏：会话下拉选择器 + 新建 / 权限 / 远程 / 环境配置
 */
@Composable
private fun TopBar(
    sessions: List<TerminalSessionData>,
    currentSessionId: String?,
    onSwitchSession: (String) -> Unit,
    onNewSession: () -> Unit,
    onRequestDelete: (String) -> Unit,
    onNavigateToSetup: () -> Unit,
    onOpenPrivilege: () -> Unit,
    onOpenRemote: () -> Unit
) {
    val context = LocalContext.current
    var menuExpanded by remember { mutableStateOf(false) }
    val currentTitle = sessions.find { it.id == currentSessionId }?.title ?: "Terminal"

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = TerminalTheme.surfaceColor,
        shadowElevation = 4.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 会话下拉选择器
            Box {
                Surface(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { menuExpanded = true },
                    color = TerminalTheme.elevated,
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        // 活跃会话指示点
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .clip(RoundedCornerShape(50))
                                .background(TerminalTheme.accentColor)
                        )
                        Text(
                            text = currentTitle,
                            color = TerminalTheme.onSurfaceColor,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Icon(
                            imageVector = Icons.Default.KeyboardArrowDown,
                            contentDescription = null,
                            tint = TerminalTheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                    containerColor = TerminalTheme.surfaceColor
                ) {
                    sessions.forEach { session ->
                        val isActive = session.id == currentSessionId
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onSwitchSession(session.id)
                                    menuExpanded = false
                                }
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = session.title,
                                modifier = Modifier.weight(1f),
                                color = if (isActive) TerminalTheme.primaryColor else TerminalTheme.onSurfaceColor,
                                fontSize = 14.sp,
                                fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (sessions.size > 1) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = context.getString(com.ai.assistance.quro.terminal.R.string.close_session),
                                    tint = TerminalTheme.onSurfaceVariant,
                                    modifier = Modifier
                                        .size(18.dp)
                                        .clip(RoundedCornerShape(4.dp))
                                        .clickable {
                                            onRequestDelete(session.id)
                                            menuExpanded = false
                                        }
                                        .padding(2.dp)
                                )
                            }
                        }
                    }

                    // 分隔线
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(TerminalTheme.divider)
                    )

                    // 环境配置入口
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onNavigateToSetup()
                                menuExpanded = false
                            }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Build,
                            contentDescription = null,
                            tint = TerminalTheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = context.getString(com.ai.assistance.quro.terminal.R.string.environment_setup),
                            color = TerminalTheme.onSurfaceColor,
                            fontSize = 14.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            // 权限
            IconButton(onClick = onOpenPrivilege) {
                Icon(
                    imageVector = Icons.Default.Security,
                    contentDescription = context.getString(com.ai.assistance.quro.terminal.R.string.terminal_permission),
                    tint = TerminalTheme.accentColor
                )
            }

            // 远程连接
            IconButton(onClick = onOpenRemote) {
                Icon(
                    imageVector = Icons.Default.Wifi,
                    contentDescription = context.getString(com.ai.assistance.quro.terminal.R.string.terminal_remote),
                    tint = TerminalTheme.onSurfaceColor
                )
            }

            // 新建会话
            IconButton(onClick = onNewSession) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = context.getString(com.ai.assistance.quro.terminal.R.string.new_session),
                    tint = TerminalTheme.onSurfaceColor
                )
            }
        }
    }
}

/**
 * 底部输入卡：中断 + 命令输入框 + 发送
 */
@Composable
private fun InputBar(
    command: String,
    onCommandChange: (String) -> Unit,
    onSend: () -> Unit,
    onInterrupt: () -> Unit,
    fontSize: androidx.compose.ui.unit.TextUnit,
    padding: androidx.compose.ui.unit.Dp
) {
    val context = LocalContext.current

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        color = TerminalTheme.surfaceColor,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, TerminalTheme.divider),
        shadowElevation = 2.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 中断按钮（Ctrl+C）
            Surface(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { onInterrupt() },
                color = TerminalTheme.errorColor.copy(alpha = 0.16f),
                shape = RoundedCornerShape(10.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Stop,
                        contentDescription = null,
                        tint = TerminalTheme.errorColor,
                        modifier = Modifier.size(15.dp)
                    )
                    Text(
                        text = context.getString(com.ai.assistance.quro.terminal.R.string.interrupt),
                        color = TerminalTheme.errorColor,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            // 粘贴按钮（净化剪贴板中的命令：去说明文字/提示符/围栏）
            Surface(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .clickable {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        val clip = cm?.primaryClip
                        if (clip != null && clip.itemCount > 0) {
                            val raw = clip.getItemAt(0).coerceToText(context).toString()
                            onCommandChange(CommandSanitizer.sanitizeSingleLine(raw))
                        }
                    },
                color = TerminalTheme.elevated,
                shape = RoundedCornerShape(10.dp)
            ) {
                Text(
                    text = context.getString(com.ai.assistance.quro.terminal.R.string.paste),
                    color = TerminalTheme.onSurfaceColor,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
                )
            }

            Spacer(modifier = Modifier.width(10.dp))

            // 命令输入框
            Box(modifier = Modifier.weight(1f)) {
                BasicTextField(
                    value = command,
                    onValueChange = onCommandChange,
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = TextStyle(
                        color = TerminalTheme.onSurfaceColor,
                        fontFamily = FontFamily.Monospace,
                        fontSize = fontSize
                    ),
                    cursorBrush = SolidColor(TerminalTheme.accentColor),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { onSend() }),
                    decorationBox = { innerTextField ->
                        Box {
                            if (command.isEmpty()) {
                                Text(
                                    text = "输入命令…",
                                    color = TerminalTheme.onSurfaceVariant,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = fontSize
                                )
                            }
                            innerTextField()
                        }
                    }
                )
            }

            Spacer(modifier = Modifier.width(10.dp))

            // 发送按钮
            Surface(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { onSend() },
                color = TerminalTheme.primaryColor,
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Send,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                        .size(18.dp)
                )
            }
        }
    }
}

@Composable
private fun VirtualKeyboard(
    onKeyPress: (String) -> Unit,
    fontSize: androidx.compose.ui.unit.TextUnit,
    padding: androidx.compose.ui.unit.Dp
) {
    val context = LocalContext.current

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = TerminalTheme.surfaceVariant,
        shadowElevation = 4.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = padding, vertical = padding * 0.5f),
            verticalArrangement = Arrangement.spacedBy(padding * 0.5f)
        ) {
            // 第一行：ESC, TAB, ↑, HOME, END, PGUP, PGDN
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(padding * 0.5f)
            ) {
                KeyButton("ESC", "\u001b", fontSize, padding, onKeyPress, modifier = Modifier.weight(1f))
                KeyButton("TAB", "\t", fontSize, padding, onKeyPress, modifier = Modifier.weight(1f))
                KeyButton("↑", "\u001b[A", fontSize, padding, onKeyPress, modifier = Modifier.weight(1f))
                KeyButton("HOME", "\u001b[H", fontSize, padding, onKeyPress, modifier = Modifier.weight(1f))
                KeyButton("END", "\u001b[F", fontSize, padding, onKeyPress, modifier = Modifier.weight(1f))
                KeyButton("PGUP", "\u001b[5~", fontSize, padding, onKeyPress, modifier = Modifier.weight(1f))
                KeyButton("PGDN", "\u001b[6~", fontSize, padding, onKeyPress, modifier = Modifier.weight(1f))
            }

            // 第二行：←, ↓, →, /, —, ALT
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(padding * 0.5f)
            ) {
                KeyButton("←", "\u001b[D", fontSize, padding, onKeyPress, modifier = Modifier.weight(1f))
                KeyButton("↓", "\u001b[B", fontSize, padding, onKeyPress, modifier = Modifier.weight(1f))
                KeyButton("→", "\u001b[C", fontSize, padding, onKeyPress, modifier = Modifier.weight(1f))
                KeyButton("/", "/", fontSize, padding, onKeyPress, modifier = Modifier.weight(1f))
                KeyButton("—", "-", fontSize, padding, onKeyPress, modifier = Modifier.weight(1f))
                KeyButton("ALT", "\u001b", fontSize, padding, onKeyPress, modifier = Modifier.weight(1f))
            }

            // 第三行：CTRL 组合键（^C/^D/^U/^L）+ 粘贴
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(padding * 0.5f)
            ) {
                KeyButton("^C", "\u0003", fontSize, padding, onKeyPress, modifier = Modifier.weight(1f))
                KeyButton("^D", "\u0004", fontSize, padding, onKeyPress, modifier = Modifier.weight(1f))
                KeyButton("^U", "\u0015", fontSize, padding, onKeyPress, modifier = Modifier.weight(1f))
                KeyButton("^L", "\u000c", fontSize, padding, onKeyPress, modifier = Modifier.weight(1f))

                // 粘贴按钮（净化剪贴板命令后逐行发送进 PTY）
                Surface(
                    modifier = Modifier
                        .weight(2f)
                        .clickable {
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                            val clip = cm?.primaryClip
                            if (clip != null && clip.itemCount > 0) {
                                val raw = clip.getItemAt(0).coerceToText(context).toString()
                                val cmds = CommandSanitizer.sanitizeToCommands(raw)
                                if (cmds.isNotEmpty()) {
                                    cmds.forEach { cmd -> onKeyPress(cmd + "\n") }
                                }
                            }
                        },
                    color = TerminalTheme.primaryColor,
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .padding(horizontal = padding * 0.5f, vertical = padding * 0.8f),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = context.getString(com.ai.assistance.quro.terminal.R.string.paste),
                            color = Color.White,
                            fontFamily = FontFamily.Monospace,
                            fontSize = fontSize,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1
                        )
                    }
                }
            }

            // 第四行：readline 常用快捷键（挂起/行首/行尾/删词/删到行尾/搜历史）
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(padding * 0.5f)
            ) {
                KeyButton("^Z", "\u001a", fontSize, padding, onKeyPress, modifier = Modifier.weight(1f))
                KeyButton("^A", "\u0001", fontSize, padding, onKeyPress, modifier = Modifier.weight(1f))
                KeyButton("^E", "\u0005", fontSize, padding, onKeyPress, modifier = Modifier.weight(1f))
                KeyButton("^W", "\u0017", fontSize, padding, onKeyPress, modifier = Modifier.weight(1f))
                KeyButton("^K", "\u000b", fontSize, padding, onKeyPress, modifier = Modifier.weight(1f))
                KeyButton("^R", "\u0012", fontSize, padding, onKeyPress, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun KeyButton(
    label: String,
    key: String,
    fontSize: androidx.compose.ui.unit.TextUnit,
    padding: androidx.compose.ui.unit.Dp,
    onKeyPress: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .clickable { onKeyPress(key) },
        color = TerminalTheme.divider,
        shape = RoundedCornerShape(8.dp)
    ) {
        Box(
            modifier = Modifier
                .padding(horizontal = padding * 0.5f, vertical = padding * 0.8f),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = label,
                color = TerminalTheme.onSurfaceColor,
                fontFamily = FontFamily.Monospace,
                fontSize = fontSize,
                fontWeight = FontWeight.Medium,
                maxLines = 1
            )
        }
    }
}
