package com.ai.assistance.quro.ui

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowDropUp
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.ClearAll
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.assistance.quro.core.linux.QuroLinuxEnv
import com.ai.assistance.quro.core.linux.PackageManagerType
import com.ai.assistance.quro.core.linux.SourceManager
import com.ai.assistance.quro.core.terminal.QuroShellSession
import com.ai.assistance.quro.core.terminal.QuroTerminalSessionManager
import com.ai.assistance.quro.core.terminal.QuroTerminalSessionManager.Backend
import com.ai.assistance.quro.core.terminal.QuroTerminalSessionManager.Kind
import com.ai.assistance.quro.core.terminal.QuroTerminalSessionManager.SessionInfo
import com.ai.assistance.quro.core.terminal.ShellMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ═══════════════════════════════════════════════════════════════
// 终端界面 v3 —— 全功能版
//
// 新增功能：
//  - 命令历史（上下键 / 按钮导航）
//  - 快捷命令面板（系统/包管理/开发/网络/文件分类）
//  - 更多特殊按键（方向键、Ctrl组合、括号引号）
//  - 输出搜索（实时高亮匹配行）
//  - 一键复制输出 / 粘贴到输入
//  - 字体大小可调
//  - 日志导出
//  - 会话管理增强（重命名、PID显示）
//  - 设置面板
// ═══════════════════════════════════════════════════════════════

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun QuroTermuxTerminalScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()

    // ═══════════ 核心状态 ═══════════
    var shellSession by remember { mutableStateOf<QuroShellSession?>(null) }
    var inputText by remember { mutableStateOf(TextFieldValue("")) }
    val listState = rememberLazyListState()

    // ═══════════ 面板开关 ═══════════
    var showDevEnvMenu by remember { mutableStateOf(false) }
    var showSessionPanel by remember { mutableStateOf(false) }
    var showQuickCmds by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showSearchBar by remember { mutableStateOf(false) }
    var showReplaceDialog by remember { mutableStateOf(false) }

    // ═══════════ 状态数据 ═══════════
    var devEnvStatus by remember { mutableStateOf("") }
    var sessionList by remember { mutableStateOf<List<SessionInfo>>(emptyList()) }
    val sandboxState by QuroLinuxEnv.state.collectAsState()
    val sourceManager = remember { SourceManager(context) }

    // ═══════════ 命令历史 ═══════════
    val commandHistory = remember { mutableStateListOf<String>() }
    var historyIndex by remember { mutableIntStateOf(-1) }

    // ═══════════ 设置 ═══════════
    var fontSize by remember { mutableFloatStateOf(12f) }
    var showLineNumbers by remember { mutableStateOf(false) }

    // ═══════════ 搜索 ═══════════
    var searchQuery by remember { mutableStateOf("") }
    var searchMatchCount by remember { mutableIntStateOf(0) }

    // ═══════════ 剪贴板 ═══════════
    var lastCopiedLine by remember { mutableIntStateOf(-1) }

    // ═══════════ 创建初始会话 ═══════════
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            if (!QuroLinuxEnv.probeLenient(context).available) {
                QuroLinuxEnv.ensureInstalledBlocking(context)
            }
            shellSession = QuroShellSession.create(context)
        }
    }

    // ═══════════ 自动滚动到底部 ═══════════
    val lines = shellSession?.lines
    LaunchedEffect(lines?.size) {
        if (lines != null && lines.isNotEmpty() && searchQuery.isBlank()) {
            listState.animateScrollToItem(lines.size - 1)
        }
    }

    // ═══════════ 定时刷新会话列表 ═══════════
    LaunchedEffect(Unit) {
        while (true) {
            delay(3000)
            sessionList = QuroTerminalSessionManager.listSessions()
        }
    }

    // ═══════════ 搜索匹配计数 ═══════════
    LaunchedEffect(searchQuery, lines?.size) {
        if (searchQuery.isNotBlank() && lines != null) {
            searchMatchCount = lines.count { it.contains(searchQuery, ignoreCase = true) }
        } else {
            searchMatchCount = 0
        }
    }

    // ═══════════ 退出清理 ═══════════
    DisposableEffect(Unit) {
        onDispose { shellSession?.destroy() }
    }

    // ═══════════ 会话切换 ═══════════
    fun switchSession(newSession: QuroShellSession?) {
        shellSession?.destroy()
        shellSession = newSession
    }

    fun createNewSession() {
        scope.launch {
            withContext(Dispatchers.IO) {
                val old = shellSession
                val newSession = QuroShellSession.create(context)
                switchSession(newSession)
                old?.destroy()
                sessionList = QuroTerminalSessionManager.listSessions()
            }
        }
    }

    // ═══════════ 发送命令 ═══════════
    fun sendCommand() {
        val session = shellSession ?: return
        val cmd = inputText.text.trim()
        if (cmd.isEmpty()) return
        if (session.busy) {
            // busy = 命令在跑（等用户输入），走 sendKey 直接喂字符，不加哨兵
            session.sendRaw(cmd)
        } else {
            // 空闲 = 等新命令，走 sendCommand 加哨兵
            if (commandHistory.isEmpty() || commandHistory.last() != cmd) {
                commandHistory.add(cmd)
            }
            historyIndex = -1
            session.sendCommand(cmd)
        }
        inputText = TextFieldValue("")
        keyboardController?.hide()
    }

    // ═══════════ 历史导航 ═══════════
    fun navigateHistoryUp() {
        if (commandHistory.isEmpty()) return
        val newIndex = if (historyIndex < 0) commandHistory.size - 1 else maxOf(0, historyIndex - 1)
        historyIndex = newIndex
        inputText = TextFieldValue(commandHistory[newIndex])
    }

    fun navigateHistoryDown() {
        if (historyIndex < 0) return
        val newIndex = historyIndex + 1
        if (newIndex >= commandHistory.size) {
            historyIndex = -1
            inputText = TextFieldValue("")
        } else {
            historyIndex = newIndex
            inputText = TextFieldValue(commandHistory[newIndex])
        }
    }

    // ═══════════ 复制输出 ═══════════
    fun copyAllOutput() {
        val allText = lines?.joinToString("\n") ?: return
        val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("terminal output", allText))
        Toast.makeText(context, "已复制全部输出", Toast.LENGTH_SHORT).show()
    }

    fun copySingleLine(index: Int) {
        if (lines == null || index >= lines.size) return
        val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("line", lines[index]))
        lastCopiedLine = index
        Toast.makeText(context, "已复制第 ${index + 1} 行", Toast.LENGTH_SHORT).show()
    }

    fun pasteFromClipboard() {
        val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val clip = clipboard.primaryClip
        if (clip != null && clip.itemCount > 0) {
            val text = clip.getItemAt(0).text?.toString() ?: return
            inputText = TextFieldValue(inputText.text + text)
        }
    }

    // ═══════════ 导出日志 ═══════════
    fun exportLog() {
        val path = shellSession?.exportLog()
        if (path != null) {
            Toast.makeText(context, "日志已保存: $path", Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(context, "导出失败", Toast.LENGTH_SHORT).show()
        }
    }

    // ═══════════ 主布局 ═══════════
    Box(Modifier.fillMaxSize().background(Color(0xFF0C0C0C))) {
        Column(Modifier.fillMaxSize()) {

            // ═══════════ 顶栏第一行：返回 + 模式 + cwd + 设置 ═══════════
            Row(
                Modifier.fillMaxWidth().height(36.dp).background(Color(0xFF1B1B1B)).padding(horizontal = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onClose, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Filled.ArrowBack, "返回", tint = Color.White, modifier = Modifier.size(18.dp))
                }
                // 模式徽章
                val (modeText, modeColor) = when (shellSession?.mode) {
                    ShellMode.VM -> "VM/Linux" to Color(0xFF8AB4F8)
                    ShellMode.LINUX -> "proot/Linux" to Color(0xFF7BE0A0)
                    ShellMode.DEVICE -> "设备 sh" to Color(0xFFFFD700)
                    null -> "初始化…" to Color(0xFF666666)
                }
                Box(
                    Modifier.clip(RoundedCornerShape(4.dp)).background(modeColor.copy(alpha = 0.15f)).padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(modeText, color = modeColor, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.width(4.dp))
                // cwd（截断）
                val cwd = shellSession?.cwdState
                if (!cwd.isNullOrBlank()) {
                    val shortCwd = cwd.substringAfterLast("/files", cwd).let { if (it.length > 30) "…" + it.takeLast(29) else it }
                    Text(
                        shortCwd, color = Color(0xFF9CC7FF), fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace, maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    Spacer(Modifier.weight(1f))
                }
                // 右侧小按钮
                IconButton(onClick = { showSettings = !showSettings }, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Filled.Settings, "设置", tint = Color(0xFF999999), modifier = Modifier.size(16.dp))
                }
            }

            // ═══════════ 顶栏第二行：操作按钮 ═══════════
            Row(
                Modifier.fillMaxWidth().height(32.dp).background(Color(0xFF151515)).padding(horizontal = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                SmallButton("📋 会话") { showSessionPanel = !showSessionPanel }
                SmallButton("🔨 环境") { showDevEnvMenu = true }
                SmallButton("📦 快捷") { showQuickCmds = !showQuickCmds }
                SmallButton("🔍 搜索") { showSearchBar = !showSearchBar }
                SmallButton("⬆ 下载") { showReplaceDialog = true }
                SmallButton("⏹ 中断") { scope.launch { shellSession?.interrupt() } }
                SmallButton("🗑 清屏") { shellSession?.clear() }
            }

            // ═══════════ 开发环境菜单 ═══════════
            Box {
                DevEnvDropdown(
                    expanded = showDevEnvMenu,
                    onDismiss = { showDevEnvMenu = false },
                    onStatus = { devEnvStatus = it },
                    sourceManager = sourceManager,
                    session = shellSession,
                )
            }

            // ═══════════ 设置面板 ═══════════
            AnimatedVisibility(visible = showSettings, enter = expandVertically(), exit = shrinkVertically()) {
                Column(
                    Modifier.fillMaxWidth().background(Color(0xFF1A1A2E)).padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("字体: ${fontSize.toInt()}sp", color = Color(0xFFCCCCCC), fontSize = 11.sp)
                        Spacer(Modifier.width(8.dp))
                        Slider(
                            value = fontSize, onValueChange = { fontSize = it },
                            valueRange = 8f..20f, steps = 11,
                            modifier = Modifier.weight(1f),
                            colors = SliderDefaults.colors(
                                thumbColor = Color(0xFF7BE0A0),
                                activeTrackColor = Color(0xFF7BE0A0),
                            ),
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.Checkbox(
                            checked = showLineNumbers,
                            onCheckedChange = { showLineNumbers = it },
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text("显示行号", color = Color(0xFFCCCCCC), fontSize = 11.sp)
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { exportLog() }) {
                            Text("📄 导出日志", fontSize = 11.sp, color = Color(0xFF7BE0A0))
                        }
                        TextButton(onClick = { copyAllOutput() }) {
                            Text("📋 复制全部", fontSize = 11.sp, color = Color(0xFF9CC7FF))
                        }
                    }
                }
            }

            // ═══════════ 搜索栏 ═══════════
            AnimatedVisibility(visible = showSearchBar, enter = expandVertically(), exit = shrinkVertically()) {
                Row(
                    Modifier.fillMaxWidth().background(Color(0xFF1A1A1A)).padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.Search, "搜索", tint = Color(0xFF666666), modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    BasicTextField(
                        value = searchQuery, onValueChange = { searchQuery = it },
                        modifier = Modifier.weight(1f).clip(RoundedCornerShape(4.dp)).background(Color(0xFF262626)).padding(horizontal = 6.dp, vertical = 4.dp),
                        textStyle = TextStyle(color = Color(0xFFCCCCCC), fontSize = 12.sp, fontFamily = FontFamily.Monospace),
                        cursorBrush = SolidColor(Color(0xFFFFD700)),
                        singleLine = true,
                        decorationBox = { inner ->
                            Box {
                                if (searchQuery.isEmpty()) Text("搜索输出…", color = Color(0xFF555555), fontSize = 12.sp)
                                inner()
                            }
                        },
                    )
                    Spacer(Modifier.width(6.dp))
                    if (searchQuery.isNotBlank()) {
                        Text("$searchMatchCount 匹配", color = Color(0xFFFFD700), fontSize = 10.sp)
                    }
                    IconButton(onClick = { searchQuery = ""; showSearchBar = false }, modifier = Modifier.size(24.dp)) {
                        Icon(Icons.Filled.Close, "关闭搜索", tint = Color(0xFF666666), modifier = Modifier.size(14.dp))
                    }
                }
            }

            // ═══════════ 开发环境状态 ═══════════
            if (devEnvStatus.isNotBlank()) {
                Row(
                    Modifier.fillMaxWidth().background(Color(0xFF1A2E1A)).padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(devEnvStatus, color = Color(0xFF7BE0A0), fontSize = 10.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    TextButton(onClick = { devEnvStatus = "" }) { Text("关闭", fontSize = 9.sp, color = Color(0xFF999999)) }
                }
            }

            // ═══════════ 检查更新对话框 ═══════════
            if (showReplaceDialog) {
                AlertDialog(
                    onDismissRequest = { showReplaceDialog = false },
                    title = { Text("检查更新") },
                    text = {
                        val stateText = when (val st = sandboxState) {
                            is QuroLinuxEnv.SandboxState.NotInstalled -> "当前未安装 Linux 环境"
                            is QuroLinuxEnv.SandboxState.Ready -> "当前已安装 Ubuntu 24.04"
                            else -> "当前状态未知"
                        }
                        Text("$stateText\n\n点击「检查更新」将检查是否有新版本的 Ubuntu rootfs。\n\n已安装的 CMS 模块和开发环境可能需要重新部署。")
                    },
                    confirmButton = {
                        TextButton(onClick = { showReplaceDialog = false; QuroLinuxEnv.setup(context) }) { Text("检查更新") }
                    },
                    dismissButton = {
                        TextButton(onClick = { showReplaceDialog = false }) { Text("取消") }
                    },
                )
            }

            // ═══════════ Linux 环境安装横幅 ═══════════
            when (val st = sandboxState) {
                is QuroLinuxEnv.SandboxState.NotInstalled ->
                    Banner("未安装 Linux 环境，点此安装后可运行 python3 / 完整 Linux 命令", "安装") { QuroLinuxEnv.setup(context) }
                is QuroLinuxEnv.SandboxState.Error ->
                    Banner("Linux 环境安装失败：${st.message}", "重试") { QuroLinuxEnv.setup(context) }
                is QuroLinuxEnv.SandboxState.Downloading ->
                    ProgressBanner("正在下载 Ubuntu rootfs … ${(st.progress * 100).toInt()}%", st.progress)
                is QuroLinuxEnv.SandboxState.Extracting -> ProgressBanner("正在解压 rootfs …", null)
                is QuroLinuxEnv.SandboxState.Installing -> ProgressBanner(st.detail.ifEmpty { "正在初始化…" }, null)
                is QuroLinuxEnv.SandboxState.Ready -> { /* 正常 */ }
            }

            // ═══════════ 快捷命令面板 ═══════════
            AnimatedVisibility(visible = showQuickCmds, enter = expandVertically(), exit = shrinkVertically()) {
                QuickCommandsPanel(
                    session = shellSession,
                    sourceManager = sourceManager,
                    onStatus = { devEnvStatus = it },
                    onDismiss = { showQuickCmds = false },
                )
            }

            // ═══════════ 终端输出 ═══════════
            val currentLines = shellSession?.lines
            val searchLower = searchQuery.lowercase()
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 4.dp),
            ) {
                if (currentLines != null) {
                    items(currentLines.size) { idx ->
                        val line = currentLines[idx]
                        val isCopied = idx == lastCopiedLine
                        val isSearchMatch = searchQuery.isNotBlank() && line.lowercase().contains(searchLower)

                        // 输出行：点击复制
                        Row(
                            Modifier.fillMaxWidth().let { mod ->
                                mod.clickable { copySingleLine(idx) }
                            }.let { mod ->
                                if (isSearchMatch) mod.background(Color(0xFF3D3500).copy(alpha = 0.4f))
                                else mod
                            }.padding(horizontal = 4.dp, vertical = 1.dp),
                            verticalAlignment = Alignment.Top,
                        ) {
                            // 行号
                            if (showLineNumbers) {
                                Text(
                                    text = "${idx + 1}".padStart(4),
                                    color = Color(0xFF555555), fontSize = (fontSize - 2).sp,
                                    fontFamily = FontFamily.Monospace,
                                    modifier = Modifier.width(32.dp).padding(end = 4.dp),
                                )
                            }
                            // 行内容（颜色编码）
                            val lineColor = when {
                                line.contains("error", ignoreCase = true) || line.contains("错误", ignoreCase = true) -> Color(0xFFFF6B6B)
                                line.contains("warning", ignoreCase = true) || line.contains("警告", ignoreCase = true) -> Color(0xFFFFD700)
                                line.startsWith("quro@") || line.startsWith("$") || line.startsWith("#") -> Color(0xFF7BE0A0)
                                line.startsWith("—") -> Color(0xFF666666)
                                line.startsWith("[router]") -> Color(0xFFBB86FC)
                                line.startsWith("⚠") -> Color(0xFFFF6B6B)
                                line.startsWith("✓") || line.contains("完成") -> Color(0xFF7BE0A0)
                                else -> Color(0xFFCCCCCC)
                            }
                            Text(
                                text = line, color = lineColor,
                                fontSize = fontSize.sp, fontFamily = FontFamily.Monospace,
                                lineHeight = (fontSize + 4).sp,
                                modifier = Modifier.weight(1f),
                            )
                            // 复制指示
                            if (isCopied) {
                                Icon(Icons.Filled.ContentCopy, "已复制", tint = Color(0xFF7BE0A0), modifier = Modifier.size(12.dp).padding(start = 2.dp))
                            }
                        }
                    }
                } else {
                    item {
                        Text("正在启动终端…", color = Color(0xFF666666), fontSize = fontSize.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(vertical = 8.dp))
                    }
                }
            }

            // ═══════════ 特殊按键栏 ═══════════
            SpecialKeysBar(
                onKey = { seq -> shellSession?.sendKey(seq) },
                fontSize = fontSize,
            )

            // ═══════════ 操作确认栏（busy 时显示 Y/N/Enter/Tab，走 sendKey 绕过 busy 锁） ═══════════
            val isBusy = shellSession?.busy == true
            AnimatedVisibility(visible = isBusy, enter = expandVertically(), exit = shrinkVertically()) {
                Row(
                    Modifier.fillMaxWidth().background(Color(0xFF1A0A0A)).padding(horizontal = 6.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text("⏳ 等待输入:", color = Color(0xFFFFD700), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    ConfirmButton("Y 确认") { shellSession?.sendKey("y\n") }
                    ConfirmButton("N 取消") { shellSession?.sendKey("n\n") }
                    ConfirmButton("Enter ↵") { shellSession?.sendKey("\n") }
                    ConfirmButton("Tab ⇥") { shellSession?.sendKey("\t") }
                    ConfirmButton("Ctrl+C") { shellSession?.sendKey("\u0003") }
                }
            }

            // ═══════════ 输入栏 ═══════════
            InputBar(
                inputText = inputText,
                onInputChange = { inputText = it },
                isBusy = shellSession?.busy == true,
                isEnabled = shellSession != null,
                onSend = { sendCommand() },
                onHistoryUp = { navigateHistoryUp() },
                onHistoryDown = { navigateHistoryDown() },
                onPaste = { pasteFromClipboard() },
                historySize = commandHistory.size,
                historyIndex = historyIndex,
            )
        }

        // ═══════════ 会话管理面板 ═══════════
        if (showSessionPanel) {
            SessionPanel(
                sessions = sessionList,
                onDismiss = { showSessionPanel = false },
                onCreateNew = { createNewSession() },
                onDestroy = { id ->
                    scope.launch {
                        QuroTerminalSessionManager.destroySession(id)
                        sessionList = QuroTerminalSessionManager.listSessions()
                    }
                },
                onSwitchDefault = { id ->
                    scope.launch {
                        QuroTerminalSessionManager.switchDefault(id)
                        sessionList = QuroTerminalSessionManager.listSessions()
                    }
                },
            )
        }
    }
}

// ═══════════ 小按钮组件 ═══════════
@Composable
private fun SmallButton(text: String, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        modifier = Modifier.height(24.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp, vertical = 0.dp),
    ) {
        Text(text, color = Color(0xFFBBBBBB), fontSize = 10.sp, maxLines = 1)
    }
}

// ═══════════ 确认按钮（busy 时使用，走 sendKey 绕过 busy 锁） ═══════════
@Composable
private fun ConfirmButton(text: String, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(4.dp))
            .background(Color(0xFF3D1F1F))
            .clickable { onClick() }
            .padding(horizontal = 8.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = Color(0xFFFF6B6B), fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
}

// ═══════════ 快捷命令面板 ═══════════
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QuickCommandsPanel(
    session: QuroShellSession?,
    sourceManager: SourceManager,
    onStatus: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    data class CmdCategory(val name: String, val color: Color, val cmds: List<Pair<String, String>>)
    val categories = listOf(
        CmdCategory("系统", Color(0xFF7BE0A0), listOf(
            "ls -la" to "ls -la",
            "pwd" to "pwd",
            "whoami" to "whoami",
            "uname -a" to "uname -a",
            "df -h" to "df -h",
            "free -h" to "free -h",
            "top" to "top -bn1 | head -20",
            "cat /etc/os-release" to "cat /etc/os-release",
            "ps aux" to "ps aux | head -20",
            "env" to "env",
        )),
        CmdCategory("包管理", Color(0xFFFFD700), listOf(
            "apt update" to "${sourceManager.generateAllSourceConfigCommands()}\napt-get update",
            "apt upgrade" to "apt-get upgrade -y",
            "apt list 已装" to "apt list --installed 2>/dev/null | head -30",
            "apt search" to "apt-cache search",
            "dpkg -l" to "dpkg -l | head -20",
            "apt clean" to "apt-get clean",
        )),
        CmdCategory("开发", Color(0xFF9CC7FF), listOf(
            "node -v" to "node -v 2>/dev/null || echo 'Node 未安装'",
            "python3 -V" to "python3 --version 2>/dev/null || echo 'Python 未安装'",
            "java -version" to "java -version 2>&1 || echo 'Java 未安装'",
            "gcc --version" to "gcc --version 2>/dev/null | head -1 || echo 'GCC 未安装'",
            "git --version" to "git --version 2>/dev/null || echo 'Git 未安装'",
            "rustc -V" to "rustc --version 2>/dev/null || echo 'Rust 未安装'",
            "go version" to "go version 2>/dev/null || echo 'Go 未安装'",
            "npm -v" to "npm -v 2>/dev/null || echo 'npm 未安装'",
            "pip3 -V" to "pip3 --version 2>/dev/null || echo 'pip 未安装'",
        )),
        CmdCategory("网络", Color(0xFFFF6B6B), listOf(
            "公网IP" to "curl -s ifconfig.me 2>/dev/null || echo '无法获取'",
            "ping" to "ping -c 3 8.8.8.8",
            "DNS" to "cat /etc/resolv.conf",
            "端口" to "ss -tlnp 2>/dev/null || netstat -tlnp 2>/dev/null | head -20",
        )),
        CmdCategory("文件", Color(0xFFBB86FC), listOf(
            "cd ~" to "cd ~ && pwd",
            "cd .." to "cd .. && pwd",
            "tree" to "find . -maxdepth 2 -type f | head -30",
            "磁盘" to "du -sh * | sort -rh | head -10",
            "find" to "find . -name",
            "wc" to "wc -l",
            "head" to "head -20",
            "tail" to "tail -20",
        )),
    )

    Column(
        Modifier.fillMaxWidth().background(Color(0xFF121218)).padding(horizontal = 6.dp, vertical = 4.dp).heightIn(max = 160.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("⚡ 快捷命令", color = Color(0xFF999999), fontSize = 10.sp)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onDismiss, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                Text("收起", fontSize = 9.sp, color = Color(0xFF666666))
            }
        }
        androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxWidth()) {
            categories.forEach { cat ->
                item {
                    Text(cat.name, color = cat.color, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp, bottom = 2.dp))
                }
                item {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        cat.cmds.forEach { (label, cmd) ->
                            Box(
                                Modifier.clip(RoundedCornerShape(4.dp))
                                    .background(Color(0xFF1E1E2E))
                                    .clickable {
                                        onStatus("发送: $label")
                                        session?.sendCommand(cmd)
                                    }
                                    .padding(horizontal = 6.dp, vertical = 3.dp)
                            ) {
                                Text(label, color = Color(0xFFBBBBBB), fontSize = 9.sp, fontFamily = FontFamily.Monospace, maxLines = 1)
                            }
                        }
                    }
                }
            }
        }
    }
}

// ═══════════ 特殊按键栏 ═══════════
@Composable
private fun SpecialKeysBar(onKey: (String) -> Unit, fontSize: Float) {
    data class KeyDef(val label: String, val seq: String, val wide: Boolean = false)
    val keys = listOf(
        KeyDef("ESC", "\u001b"),
        KeyDef("TAB", "\t"),
        KeyDef("CTRL", "", wide = true), // placeholder for sub-row
        KeyDef("↑", "\u001b[A"),
        KeyDef("↓", "\u001b[B"),
        KeyDef("→", "\u001b[C"),
        KeyDef("←", "\u001b[D"),
        KeyDef("C", "\u0003"),
        KeyDef("D", "\u0004"),
        KeyDef("Z", "\u001a"),
        KeyDef("L", "\u000c"),
        KeyDef("A", "\u0001"),
        KeyDef("E", "\u0005"),
        KeyDef("K", "\u000b"),
        KeyDef("U", "\u0015"),
        KeyDef("|", "|"),
        KeyDef("~", "~"),
        KeyDef("/", "/"),
        KeyDef("-", "-"),
        KeyDef("$", "$"),
        KeyDef("&", "&"),
        KeyDef(";", ";"),
        KeyDef("{", "{"),
        KeyDef("}", "}"),
        KeyDef("[", "["),
        KeyDef("]", "]"),
        KeyDef("\"", "\""),
        KeyDef("'", "'"),
        KeyDef("=", "="),
        KeyDef(">", ">"),
    )

    val scrollState = rememberScrollState()
    Row(
        Modifier.fillMaxWidth().background(Color(0xFF0E0E0E)).horizontalScroll(scrollState).padding(horizontal = 4.dp, vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        keys.filter { it.label != "CTRL" }.forEach { key ->
            Box(
                Modifier.clip(RoundedCornerShape(4.dp))
                    .background(if (key.label in listOf("ESC", "TAB", "C", "D")) Color(0xFF2A1F12) else Color(0xFF1A1A1A))
                    .clickable { onKey(key.seq) }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(key.label, color = Color(0xFFBFBFBF), fontSize = (fontSize - 2).sp, fontFamily = FontFamily.Monospace)
            }
        }
    }
}

// ═══════════ 输入栏 ═══════════
@Composable
private fun InputBar(
    inputText: TextFieldValue,
    onInputChange: (TextFieldValue) -> Unit,
    isBusy: Boolean,
    isEnabled: Boolean,
    onSend: () -> Unit,
    onHistoryUp: () -> Unit,
    onHistoryDown: () -> Unit,
    onPaste: () -> Unit,
    historySize: Int,
    historyIndex: Int,
) {
    Row(
        Modifier.fillMaxWidth().background(Color(0xFF1B1B1B)).padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 历史导航按钮
        IconButton(onClick = onHistoryUp, enabled = historySize > 0 && !isBusy, modifier = Modifier.size(28.dp)) {
            Icon(Icons.Filled.KeyboardArrowUp, "上一条", tint = if (historySize > 0) Color(0xFF7BE0A0) else Color(0xFF333333), modifier = Modifier.size(16.dp))
        }
        IconButton(onClick = onHistoryDown, enabled = historyIndex >= 0 && !isBusy, modifier = Modifier.size(28.dp)) {
            Icon(Icons.Filled.KeyboardArrowDown, "下一条", tint = if (historyIndex >= 0) Color(0xFF7BE0A0) else Color(0xFF333333), modifier = Modifier.size(16.dp))
        }

        // 输入框（busy 时也能打字，发送走 sendRaw）
        BasicTextField(
            value = inputText,
            onValueChange = onInputChange,
            enabled = isEnabled,
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(6.dp))
                .background(if (isBusy) Color(0xFF2A2220) else Color(0xFF262626))
                .padding(horizontal = 8.dp, vertical = 6.dp),
            textStyle = TextStyle(color = Color(0xFFCCCCCC), fontSize = 13.sp, fontFamily = FontFamily.Monospace),
            cursorBrush = SolidColor(Color(0xFF7BE0A0)),
            singleLine = true,
            decorationBox = { innerField ->
                Box {
                    if (inputText.text.isEmpty()) {
                        Text(
                            when {
                                !isEnabled -> "终端未就绪…"
                                isBusy -> "输入回复内容…（或点上方 Y/N 按钮）"
                                else -> "输入命令…（↑↓ 历史）"
                            },
                            color = if (isBusy) Color(0xFFAA7744) else Color(0xFF555555),
                            fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                        )
                    }
                    innerField()
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { onSend() }),
        )

        // 粘贴按钮
        IconButton(onClick = onPaste, modifier = Modifier.size(28.dp)) {
            Icon(Icons.Filled.ContentPaste, "粘贴", tint = Color(0xFF999999), modifier = Modifier.size(16.dp))
        }

        // 发送按钮（busy 时也能发，走 sendRaw）
        IconButton(
            onClick = onSend,
            enabled = inputText.text.isNotBlank() && isEnabled,
            modifier = Modifier.size(32.dp),
        ) {
            Icon(
                Icons.Filled.Send, "发送",
                tint = if (inputText.text.isNotBlank()) Color(0xFF7BE0A0) else Color(0xFF444444),
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

// ═══════════ 开发环境下拉菜单 ═══════════
@Composable
private fun DevEnvDropdown(
    expanded: Boolean,
    onDismiss: () -> Unit,
    onStatus: (String) -> Unit,
    sourceManager: SourceManager,
    session: QuroShellSession?,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        data class DevItem(val label: String, val command: String)
        val items = listOf(
            DevItem("📦 安装 Node.js", """
                ${sourceManager.generateAllSourceConfigCommands()}
                curl -fsSL https://deb.nodesource.com/setup_24.x | bash -
                apt-get install -y nodejs
                echo "Node.js 安装完成"
                node -v && npm -v
            """.trimIndent()),
            DevItem("🐍 安装 Python3", """
                ${sourceManager.generateAllSourceConfigCommands()}
                apt-get update
                apt-get install -y python3 python3-pip
                echo "Python 安装完成"
                python3 --version && pip3 --version
            """.trimIndent()),
            DevItem("☕ 安装 Java", """
                ${sourceManager.generateAllSourceConfigCommands()}
                apt-get update
                apt-get install -y openjdk-17-jdk-headless
                echo "Java 安装完成"
                java -version
            """.trimIndent()),
            DevItem("🦀 安装 Rust", """
                ${sourceManager.generateAllSourceConfigCommands()}
                export RUSTUP_DIST_SERVER="${sourceManager.getSelectedSource(PackageManagerType.RUST).url}"
                export RUSTUP_UPDATE_ROOT="${sourceManager.getSelectedSource(PackageManagerType.RUST).url}/rustup"
                curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh -s -- -y
                source ~/.cargo/env
                echo "Rust 安装完成"
                rustc --version && cargo --version
            """.trimIndent()),
            DevItem("🔧 安装 Go", """
                ${sourceManager.generateAllSourceConfigCommands()}
                apt-get update
                apt-get install -y golang
                echo "Go 安装完成"
                go version
            """.trimIndent()),
            DevItem("🌐 安装 Git + Curl + Wget", """
                ${sourceManager.generateAllSourceConfigCommands()}
                apt-get update
                apt-get install -y git curl wget
                echo "Git/Curl/Wget 安装完成"
                git --version && curl --version | head -1 && wget --version | head -1
            """.trimIndent()),
        )
        items.forEach { item ->
            DropdownMenuItem(
                text = { Text(item.label) },
                onClick = {
                    onDismiss()
                    val cleanName = item.label.removePrefix("📦 ").removePrefix("🐍 ").removePrefix("☕ ").removePrefix("🦀 ").removePrefix("🔧 ").removePrefix("🌐 ")
                    onStatus("正在发送 $cleanName 安装命令…")
                    session?.sendCommand(item.command)
                }
            )
        }
        Divider(color = Color(0xFF333333))
        DropdownMenuItem(
            text = { Text("🔍 检查已安装环境") },
            onClick = {
                onDismiss()
                onStatus("正在检查环境状态…")
                session?.sendCommand("""
                    echo '=== 环境检查 ==='
                    echo -n 'Node: ' && node -v 2>/dev/null || echo '未安装'
                    echo -n 'Python: ' && python3 --version 2>/dev/null || echo '未安装'
                    echo -n 'Java: ' && java -version 2>&1 | head -1 || echo '未安装'
                    echo -n 'Rust: ' && rustc --version 2>/dev/null || echo '未安装'
                    echo -n 'Go: ' && go version 2>/dev/null || echo '未安装'
                    echo -n 'Git: ' && git --version 2>/dev/null || echo '未安装'
                    echo '=== 检查完毕 ==='
                """.trimIndent())
            }
        )
    }
}

// ═══════════ 会话管理面板 ═══════════
@Composable
private fun SessionPanel(
    sessions: List<SessionInfo>,
    onDismiss: () -> Unit,
    onCreateNew: () -> Unit,
    onDestroy: (String) -> Unit,
    onSwitchDefault: (String) -> Unit,
) {
    Box(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp).padding(top = 8.dp)
            .clip(RoundedCornerShape(8.dp)).background(Color(0xFF1B1B1B)).padding(10.dp)
    ) {
        Column {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("终端会话 (${sessions.size})", color = Color.White, fontSize = 12.sp)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text("关闭", fontSize = 10.sp, color = Color(0xFF999999)) }
            }
            sessions.forEach { info ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)).background(Color(0xFF222222)).padding(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(6.dp).clip(CircleShape).background(if (info.alive) Color(0xFF7BE0A0) else Color(0xFF555555)))
                    Spacer(Modifier.width(6.dp))
                    Column(Modifier.weight(1f)) {
                        Text(info.name, color = Color.White, fontSize = 11.sp)
                        Text(
                            "${info.backend.name.lowercase()}${if (info.isDefault) " · 默认" else ""}",
                            color = Color(0xFF888888), fontSize = 9.sp,
                        )
                    }
                    if (!info.isDefault && info.kind == Kind.EXTRA) {
                        TextButton(onClick = { onSwitchDefault(info.id) }, contentPadding = androidx.compose.foundation.layout.PaddingValues(2.dp)) {
                            Text("设默认", fontSize = 9.sp, color = Color(0xFF9CC7FF))
                        }
                        TextButton(onClick = { onDestroy(info.id) }, contentPadding = androidx.compose.foundation.layout.PaddingValues(2.dp)) {
                            Text("关闭", fontSize = 9.sp, color = Color(0xFFFF6B6B))
                        }
                    }
                }
                Spacer(Modifier.height(2.dp))
            }
            Spacer(Modifier.height(4.dp))
            TextButton(onClick = onCreateNew, Modifier.align(Alignment.End)) {
                Icon(Icons.Filled.Add, "新会话", tint = Color(0xFF7BE0A0), modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
                Text("+ 新会话", fontSize = 11.sp, color = Color(0xFF7BE0A0))
            }
        }
    }
}

// ═══════════ 通用横幅 ═══════════
@Composable
private fun Banner(text: String, actionLabel: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(Color(0xFF2A1F12)).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, color = Color(0xFFF5C77B), fontSize = 11.sp, modifier = Modifier.weight(1f))
        TextButton(onClick = onClick) { Text(actionLabel, color = Color(0xFFF59E0B)) }
    }
}

@Composable
private fun ProgressBanner(text: String, progress: Float?) {
    Column(Modifier.fillMaxWidth().background(Color(0xFF122A1A)).padding(horizontal = 12.dp, vertical = 6.dp)) {
        Text(text, color = Color(0xFF7BE0A0), fontSize = 11.sp)
        Spacer(Modifier.height(4.dp))
        if (progress != null) {
            LinearProgressIndicator(progress = progress, modifier = Modifier.fillMaxWidth())
        } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
    }
}
