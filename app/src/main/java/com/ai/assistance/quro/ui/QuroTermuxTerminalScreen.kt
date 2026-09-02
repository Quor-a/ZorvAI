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
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.SoftwareKeyboardController
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
// 终端界面 v4 —— 双终端版（融合 VM + 本地终端）
//
// 设计：
//  - 双窗格终端：左窗格「VM/Linux 融合」(VM/pKVM/AVF/QEMU 优先，失败回退 proot)，
//    右窗格「本地终端」（强制 proot 本地）。
//  - 顶栏「⇆ 单/双」切换单窗格 / 双窗格；点任一窗格徽章将其设为活动窗格。
//  - 两窗格完全独立：各自输出、输入、命令历史、特殊键、复制。
//  - 顶栏操作按钮（环境/快捷/搜索/中断/清屏）作用于「活动窗格」。
//  - VM 资产（qemu/kernel/rootfs/initramfs）到位即左窗格自动跑真内核；
//    缺失则左窗格也回退 proot，终端始终可用。
// ═══════════════════════════════════════════════════════════════

/** 单个窗格的可变状态集合（与 Compose 重组解耦，便于双窗格复用）。 */
private class PaneState(
    val session: MutableState<QuroShellSession?>,
    val input: MutableState<TextFieldValue>,
    val listState: androidx.compose.foundation.lazy.LazyListState,
    val history: SnapshotStateList<String>,
    val histIdx: MutableState<Int>,
    val lastCopiedLine: MutableState<Int>,
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun rememberPaneState(
    context: android.content.Context,
    scope: kotlinx.coroutines.CoroutineScope,
    vmFirst: Boolean,
): PaneState {
    val session = remember { mutableStateOf<QuroShellSession?>(null) }
    val input = remember { mutableStateOf(TextFieldValue("")) }
    val listState = rememberLazyListState()
    val history = remember { mutableStateListOf<String>() }
    val histIdx = remember { mutableStateOf(-1) }
    val lastCopiedLine = remember { mutableStateOf(-1) }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            // 立即建立会话：env 已就绪走 proot/Linux，否则设备 sh 兜底（create 已保证永不抛、永不为 null）。
            // 关键修复：移除 UI 启动路径里的阻塞式 ensureInstalledBlocking——
            // 它在 env 未就绪时会触发 rootfs 下载（无超时、无网络/被墙时永久卡死），
            // 导致 session.value 永远赋不上 → 终端永久停在「正在启动终端…」。
            // env 未就绪改由下方 DEVICE 分支触发非阻塞 setup，安装完成由 sandboxState LaunchedEffect 自动重建为 proot。
            session.value = if (vmFirst) QuroShellSession.create(context) else QuroShellSession.createLocal(context)
            // 自动修复：若回退到设备 shell（proot 启动失败 / 环境损坏 / rootfs 缺失），
            // 后台重装 Linux 环境，待 Ready 后由屏幕级 LaunchedEffect 自动重建为 proot/Linux 会话，
            // 用户无需手动点「安装」按钮。
            if (session.value?.mode == ShellMode.DEVICE) {
                QuroLinuxEnv.setup(context)
            }
        }
    }
    DisposableEffect(Unit) { onDispose { session.value?.destroy() } }
    return PaneState(session, input, listState, history, histIdx, lastCopiedLine)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun QuroTermuxTerminalScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()

    // ═══════════ 单窗格状态（移除双终端，默认单终端） ═══════════
    val pane = rememberPaneState(context, scope, vmFirst = true)    // proot/Linux 终端
    fun active(): PaneState = pane

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

    // ═══════════ 设置 ═══════════
    var fontSize by remember { mutableFloatStateOf(12f) }
    var showLineNumbers by remember { mutableStateOf(false) }

    // ═══════════ 搜索 ═══════════
    var searchQuery by remember { mutableStateOf("") }

    // ═══════════ 定时刷新会话列表 ═══════════
    LaunchedEffect(Unit) {
        while (true) {
            delay(3000)
            sessionList = QuroTerminalSessionManager.listSessions()
        }
    }

    // 自动修复：Linux 环境安装/重装就绪后，若当前会话仍是设备 sh（proot 启动失败/环境损坏），
    // 自动重建为 proot/Linux 会话，无需用户手动点「安装」按钮。
    LaunchedEffect(sandboxState) {
        if (sandboxState is QuroLinuxEnv.SandboxState.Ready && pane.session.value?.mode == ShellMode.DEVICE) {
            scope.launch {
                withContext(Dispatchers.IO) {
                    val old = pane.session.value
                    pane.session.value = QuroShellSession.create(context)
                    old?.destroy()
                }
            }
        }
    }

    // ═══════════ 重建活动窗格会话（顶栏「新会话」） ═══════════
    fun recreateActivePane() {
        scope.launch {
            withContext(Dispatchers.IO) {
                val p = pane
                val old = p.session.value
                val newS = QuroShellSession.create(context)
                p.session.value = newS
                p.history.clear()
                p.histIdx.value = -1
                p.input.value = TextFieldValue("")
                old?.destroy()
                sessionList = QuroTerminalSessionManager.listSessions()
            }
        }
    }

    // ═══════════ 主布局 ═══════════
    Box(Modifier.fillMaxSize().background(Color(0xFF0C0C0C))) {
        Column(Modifier.fillMaxSize()) {

            // ═══════════ 顶栏第一行：返回 + 终端标签 + 设置 ═══════════
            Row(
                Modifier.fillMaxWidth().height(36.dp).background(Color(0xFF1B1B1B)).padding(horizontal = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onClose, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Filled.ArrowBack, "返回", tint = Color.White, modifier = Modifier.size(18.dp))
                }
                Text(
                    "Linux 终端", color = Color(0xFF7BE0A0), fontSize = 11.sp,
                    fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 4.dp),
                )
                Spacer(Modifier.width(4.dp))
                // 活动窗格 cwd（截断）
                val cwd = active().session.value?.cwdState
                if (!cwd.isNullOrBlank()) {
                    val shortCwd = cwd.substringAfterLast("/files", cwd).let { if (it.length > 30) "…" + it.takeLast(29) else it }
                    Text(
                        shortCwd, color = Color(0xFF9CC7FF), fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                    )
                } else {
                    Spacer(Modifier.weight(1f))
                }
                IconButton(onClick = { showSettings = !showSettings }, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Filled.Settings, "设置", tint = Color(0xFF999999), modifier = Modifier.size(16.dp))
                }
            }

            // ═══════════ 顶栏第二行：操作按钮（作用于活动窗格） ═══════════
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
                SmallButton("⏹ 中断") { scope.launch { active().session.value?.interrupt() } }
                SmallButton("🗑 清屏") { active().session.value?.clear() }
                SmallButton("➕ 新") { recreateActivePane() }
            }

            // ═══════════ 开发环境菜单 ═══════════
            Box {
                DevEnvDropdown(
                    expanded = showDevEnvMenu,
                    onDismiss = { showDevEnvMenu = false },
                    onStatus = { devEnvStatus = it },
                    sourceManager = sourceManager,
                    session = active().session.value,
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
                        TextButton(onClick = { active().session.value?.exportLog()?.let { p ->
                            Toast.makeText(context, "日志已保存: $p", Toast.LENGTH_LONG).show()
                        } ?: Toast.makeText(context, "导出失败", Toast.LENGTH_SHORT).show() }) {
                            Text("📄 导出日志", fontSize = 11.sp, color = Color(0xFF7BE0A0))
                        }
                        TextButton(onClick = {
                            val all = active().session.value?.lines?.joinToString("\n") ?: return@TextButton
                            val cb = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                            cb.setPrimaryClip(android.content.ClipData.newPlainText("terminal output", all))
                            Toast.makeText(context, "已复制全部输出", Toast.LENGTH_SHORT).show()
                        }) {
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
                                if (searchQuery.isEmpty()) Text("搜索活动窗格输出…", color = Color(0xFF555555), fontSize = 12.sp)
                                inner()
                            }
                        },
                    )
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
                    session = active().session.value,
                    sourceManager = sourceManager,
                    onStatus = { devEnvStatus = it },
                    onDismiss = { showQuickCmds = false },
                )
            }

            // ═══════════ 单窗格终端区 ═══════════
            TerminalPane(
                pane = pane, role = "Linux 终端",
                isActive = true, onFocus = { },
                fontSize = fontSize, showLineNumbers = showLineNumbers, searchQuery = searchQuery,
                keyboardController = keyboardController, scope = scope, context = context, sourceManager = sourceManager,
                modifier = Modifier.fillMaxWidth().weight(1f),
            )
        }

        // ═══════════ 会话管理面板 ═══════════
        if (showSessionPanel) {
            SessionPanel(
                sessions = sessionList,
                onDismiss = { showSessionPanel = false },
                onCreateNew = { recreateActivePane() },
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

// ═══════════ 窗格徽章（单终端后已移除） ═══════════


// ═══════════ 单窗格终端（输出 + 特殊键 + 输入，全部自包含） ═══════════
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TerminalPane(
    pane: PaneState,
    role: String,
    isActive: Boolean,
    onFocus: () -> Unit,
    fontSize: Float,
    showLineNumbers: Boolean,
    searchQuery: String,
    keyboardController: SoftwareKeyboardController?,
    scope: kotlinx.coroutines.CoroutineScope,
    context: android.content.Context,
    sourceManager: SourceManager,
    modifier: Modifier = Modifier,
) {
    val session = pane.session.value
    val lines = session?.lines
    val listState = pane.listState
    val searchLower = searchQuery.lowercase()

    // 自动滚动到底部
    LaunchedEffect(lines?.size) {
        if (lines != null && lines.isNotEmpty() && searchQuery.isBlank()) {
            listState.animateScrollToItem(lines.size - 1)
        }
    }

    fun sendCommand() {
        val s = pane.session.value ?: return
        val cmd = pane.input.value.text.trim()
        if (cmd.isEmpty()) return
        if (s.busy) {
            s.sendRaw(cmd)
        } else {
            if (pane.history.isEmpty() || pane.history.last() != cmd) pane.history.add(cmd)
            pane.histIdx.value = -1
            s.sendCommand(cmd)
        }
        pane.input.value = TextFieldValue("")
        keyboardController?.hide()
    }
    fun navigateHistoryUp() {
        if (pane.history.isEmpty()) return
        val ni = if (pane.histIdx.value < 0) pane.history.size - 1 else maxOf(0, pane.histIdx.value - 1)
        pane.histIdx.value = ni
        pane.input.value = TextFieldValue(pane.history[ni])
    }
    fun navigateHistoryDown() {
        if (pane.histIdx.value < 0) return
        val ni = pane.histIdx.value + 1
        if (ni >= pane.history.size) {
            pane.histIdx.value = -1
            pane.input.value = TextFieldValue("")
        } else {
            pane.histIdx.value = ni
            pane.input.value = TextFieldValue(pane.history[ni])
        }
    }
    fun copySingleLine(idx: Int) {
        if (lines == null || idx >= lines.size) return
        val cb = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cb.setPrimaryClip(android.content.ClipData.newPlainText("line", lines[idx]))
        pane.lastCopiedLine.value = idx
        Toast.makeText(context, "已复制第 ${idx + 1} 行", Toast.LENGTH_SHORT).show()
    }
    fun pasteFromClipboard() {
        val cb = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val clip = cb.primaryClip
        if (clip != null && clip.itemCount > 0) {
            val t = clip.getItemAt(0).text?.toString() ?: return
            pane.input.value = TextFieldValue(pane.input.value.text + t)
        }
    }

    Column(
        modifier
            .fillMaxSize()
            .background(if (isActive) Color(0xFF0C0C0C) else Color(0xFF080808))
            .clickable { onFocus() }
    ) {
        // 窗格角色条
        Row(
            Modifier.fillMaxWidth().height(22.dp)
                .background(if (isActive) Color(0xFF1B2B1B) else Color(0xFF141414))
                .padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(role, color = if (isActive) Color(0xFFBFE9C8) else Color(0xFF777777), fontSize = 9.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            if (isActive) Text("● 活动", color = Color(0xFF7BE0A0), fontSize = 8.sp)
        }

        // 输出：真·VT 终端，带 ANSI 颜色 / 光标 / 加粗 / 清屏。
        // 左窗格 = VM 融合终端，右窗格 = 本地 proot/Linux 终端，均走同一 VT 渲染管线。
        val outSession = session
        Box(
            Modifier.fillMaxWidth().weight(1f),
        ) {
            if (outSession != null) {
                // 只要会话已建立就挂载真·VT 面板；VT 引擎由面板在首次布局(onSizeChanged)时创建并赋给 session.vt。
                // 注意：不能再以 outSession.vt != null 作为挂载条件，否则面板永不挂载、vt 永不创建，
                // 终端会永久卡在"正在启动终端…"（鸡生蛋死锁）。
                val termListState = rememberLazyListState()
                LaunchedEffect(outSession.lines.size) {
                    if (outSession.lines.isNotEmpty()) {
                        termListState.animateScrollToItem(outSession.lines.size - 1)
                    }
                }
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 4.dp),
                    state = termListState,
                ) {
                    items(outSession.lines.size) { i ->
                        Text(
                            outSession.lines[i],
                            color = Color(0xFFCCCCCC),
                            fontSize = fontSize.sp,
                            fontFamily = FontFamily.Monospace,
                            lineHeight = (fontSize * 1.25f).sp,
                        )
                    }
                }
            } else {
                Column(Modifier.fillMaxSize().padding(16.dp)) {
                    Text("正在启动终端…", color = Color(0xFF7BE0A0), fontSize = 14.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Text("首次进入会下载 / 解压 rootfs + 启动 proot，安静等待几秒到一两分钟。", color = Color(0xFF9CC7FF), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    val recent = outSession?.lines.orEmpty()
                    if (recent.isNotEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        Text("最近输出（${outSession?.mode ?: "—"}）：", color = Color(0xFFBFE9C8), fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                        recent.takeLast(8).forEach { l ->
                            Text(l, color = Color(0xFFCCCCCC), fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            }
        }

        // busy 确认栏
        val isBusy = session?.busy == true
        AnimatedVisibility(visible = isBusy, enter = expandVertically(), exit = shrinkVertically()) {
            Row(
                Modifier.fillMaxWidth().background(Color(0xFF1A0A0A)).padding(horizontal = 6.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text("⏳ 等待输入:", color = Color(0xFFFFD700), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                ConfirmButton("Y 确认") { session?.sendKey("y\n") }
                ConfirmButton("N 取消") { session?.sendKey("n\n") }
                ConfirmButton("Enter ↵") { session?.sendKey("\n") }
                ConfirmButton("Tab ⇥") { session?.sendKey("\t") }
                ConfirmButton("Ctrl+C") { session?.sendKey("\u0003") }
            }
        }

        // 特殊键栏
        SpecialKeysBar(onKey = { seq -> session?.sendKey(seq) }, fontSize = fontSize)

        // 输入栏
        InputBar(
            inputText = pane.input.value,
            onInputChange = { pane.input.value = it },
            isBusy = isBusy,
            isEnabled = session != null,
            onSend = { sendCommand() },
            onHistoryUp = { navigateHistoryUp() },
            onHistoryDown = { navigateHistoryDown() },
            onPaste = { pasteFromClipboard() },
            historySize = pane.history.size,
            historyIndex = pane.histIdx.value,
        )
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
            "ps" to "ps -e | head -20",
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
            "端口" to "for p in 22 80 443 3000 5000 8000 8080 9000; do python3 -c \"import socket; s=socket.socket(); print('port', \$p, 'OPEN' if s.connect_ex(('127.0.0.1', \$p))==0 else 'closed')\" 2>/dev/null; done",
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
    data class KeyDef(val label: String, val seq: String)
    val keys = listOf(
        KeyDef("ESC", "\u001b"),
        KeyDef("TAB", "\t"),
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
        keys.forEach { key ->
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
        IconButton(onClick = onHistoryUp, enabled = historySize > 0 && !isBusy, modifier = Modifier.size(28.dp)) {
            Icon(Icons.Filled.KeyboardArrowUp, "上一条", tint = if (historySize > 0) Color(0xFF7BE0A0) else Color(0xFF333333), modifier = Modifier.size(16.dp))
        }
        IconButton(onClick = onHistoryDown, enabled = historyIndex >= 0 && !isBusy, modifier = Modifier.size(28.dp)) {
            Icon(Icons.Filled.KeyboardArrowDown, "下一条", tint = if (historyIndex >= 0) Color(0xFF7BE0A0) else Color(0xFF333333), modifier = Modifier.size(16.dp))
        }

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

        IconButton(onClick = onPaste, modifier = Modifier.size(28.dp)) {
            Icon(Icons.Filled.ContentPaste, "粘贴", tint = Color(0xFF999999), modifier = Modifier.size(16.dp))
        }

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
