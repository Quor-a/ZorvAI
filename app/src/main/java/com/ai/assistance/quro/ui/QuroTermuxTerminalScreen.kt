package com.ai.assistance.quro.ui

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.ClearAll
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.ai.assistance.quro.core.linux.QuroLinuxEnv
import com.ai.assistance.quro.core.linux.SourceManager
import com.ai.assistance.quro.core.linux.PackageManagerType
import com.ai.assistance.quro.core.termux.QuroTermuxTerminalController
import com.ai.assistance.quro.core.termux.QuroTermuxViewClient
import com.ai.assistance.quro.core.termux.terminal.TerminalSession
import com.ai.assistance.quro.core.termux.view.TerminalView
import com.ai.assistance.quro.core.terminal.QuroTerminalSessionManager
import com.ai.assistance.quro.core.terminal.QuroTerminalSessionManager.Backend
import com.ai.assistance.quro.core.terminal.QuroTerminalSessionManager.Kind
import com.ai.assistance.quro.core.terminal.QuroTerminalSessionManager.SessionInfo
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 去品牌化 Termux 终端界面（Zorv AI 版）。
 *
 * 用 [AndroidView] 承载 Termux 的纯 Java 渲染层 [TerminalView]，
 * 底层仍走 Zorv AI 自研的 proot/Ubuntu 管道（[QuroTermuxTerminalController]），
 * 从而 100% 复用 Termux 久经考验的 ANSI 渲染，又无需原生 PTY / NDK。
 *
 * 终端架构统一（本次新增）：
 *  - 打开界面时**跟随启动 zorvAI 终端环境**：[QuroTerminalSessionManager.ensureDefault]
 *    以 installIfMissing=true 拉起默认共享会话（AI / 使用者 / CMS 共用同一 proot/Ubuntu 后端）。
 *  - 打开时登记本 UI 终端会话（[QuroTerminalSessionManager.registerUiSession]），
 *    关闭时注销（[QuroTerminalSessionManager.unregisterUiSession]），纳入统一会话列表管理。
 *  - 顶栏「会话」面板可查看 / 切换默认 / 关闭 / 新建会话，使使用者也能管理所有会话与后端。
 */
@Composable
fun QuroTermuxTerminalScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val sessionState = remember { mutableStateOf<TerminalSession?>(null) }

    val modeLabel by QuroTermuxTerminalController.modeLabel.collectAsState()
    val cwd by QuroTermuxTerminalController.cwd.collectAsState()
    val sandboxState by QuroLinuxEnv.state.collectAsState()
    var showReplaceDialog by remember { mutableStateOf(false) }
    var showDevEnvMenu by remember { mutableStateOf(false) }
    var devEnvStatus by remember { mutableStateOf("") }
    val sourceManager = remember { SourceManager(context) }

    // 统一会话管理状态
    val scope = rememberCoroutineScope()
    val sessionList = remember { mutableStateOf<List<SessionInfo>>(emptyList()) }
    var showSessionPanel by remember { mutableStateOf(false) }

    fun refreshSessions() {
        sessionList.value = QuroTerminalSessionManager.listSessions()
    }

    // 打开界面：跟随启动默认会话（必要时安装 Linux 环境）+ 登记 UI 会话 + 定时刷新列表
    LaunchedEffect(Unit) {
        QuroTerminalSessionManager.ensureDefault(context, installIfMissing = true)
        QuroTerminalSessionManager.registerUiSession(Backend.LINUX_PROOT)
        refreshSessions()
        while (true) {
            delay(3000)
            refreshSessions()
        }
    }
    // 关闭界面：注销 UI 会话登记（默认共享会话由保活服务继续存活）
    DisposableEffect(Unit) {
        onDispose { QuroTerminalSessionManager.unregisterUiSession() }
    }

    Box(Modifier.fillMaxSize().background(Color(0xFF0C0C0C))) {
        Column(Modifier.fillMaxSize()) {
            // ═══════════ 顶栏 ═══════════
            Row(
                Modifier.fillMaxWidth().height(52.dp).background(Color(0xFF1B1B1B)).padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onClose) {
                    Icon(Icons.Filled.ArrowBack, "back", tint = Color.White)
                }
                Text("终端 · $modeLabel", color = Color.White, fontSize = 14.sp)
                if (cwd.isNotBlank()) {
                    Text("  $cwd", color = Color(0xFF9CC7FF), fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                }
                Spacer(Modifier.weight(1f))
                // 会话管理面板入口：显示当前会话总数
                TextButton(onClick = { showSessionPanel = !showSessionPanel }) {
                    Text(
                        "会话 ${sessionList.value.size}",
                        color = Color(0xFF9CC7FF),
                        fontSize = 12.sp,
                    )
                }
                // 开发环境管理下拉菜单
                Box {
                    IconButton(onClick = { showDevEnvMenu = true }) {
                        Icon(Icons.Filled.Build, "开发环境", tint = Color(0xFFFFD700))
                    }
                    DropdownMenu(expanded = showDevEnvMenu, onDismissRequest = { showDevEnvMenu = false }) {
                        DropdownMenuItem(
                            text = { Text("📦 安装 Node.js") },
                            onClick = {
                                showDevEnvMenu = false
                                devEnvStatus = "正在发送 Node.js 安装命令..."
                                scope.launch(Dispatchers.IO) {
                                    // 使用 SourceManager 生成的命令
                                    val sourceConfig = sourceManager.generateAllSourceConfigCommands()
                                    val nodeCommand = """
                                        $sourceConfig
                                        # 安装 Node.js 24.x（使用 NodeSource）
                                        curl -fsSL https://deb.nodesource.com/setup_24.x | bash - 
                                        apt-get install -y nodejs
                                        echo "Node.js 安装完成"
                                        node -v && npm -v
                                    """.trimIndent()
                                    sessionState.value?.write("$nodeCommand 2>&1 | tail -20\n")
                                    withContext(Dispatchers.Main) {
                                        devEnvStatus = "Node.js 安装命令已发送（已配置镜像源），请查看终端输出"
                                    }
                                }
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("🐍 安装 Python3") },
                            onClick = {
                                showDevEnvMenu = false
                                devEnvStatus = "正在发送 Python3 安装命令..."
                                scope.launch(Dispatchers.IO) {
                                    // 使用 SourceManager 生成的命令
                                    val sourceConfig = sourceManager.generateAllSourceConfigCommands()
                                    val pythonCommand = """
                                        $sourceConfig
                                        # 安装 Python3 + pip
                                        apt-get update
                                        apt-get install -y python3 python3-pip
                                        echo "Python 安装完成"
                                        python3 --version && pip3 --version
                                    """.trimIndent()
                                    sessionState.value?.write("$pythonCommand 2>&1 | tail -20\n")
                                    withContext(Dispatchers.Main) {
                                        devEnvStatus = "Python3 安装命令已发送（已配置镜像源），请查看终端输出"
                                    }
                                }
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("☕ 安装 Java") },
                            onClick = {
                                showDevEnvMenu = false
                                devEnvStatus = "正在发送 Java 安装命令..."
                                scope.launch(Dispatchers.IO) {
                                    // 使用 SourceManager 生成的命令
                                    val sourceConfig = sourceManager.generateAllSourceConfigCommands()
                                    val javaCommand = """
                                        $sourceConfig
                                        # 安装 OpenJDK 17
                                        apt-get update
                                        apt-get install -y openjdk-17-jdk-headless
                                        echo "Java 安装完成"
                                        java -version
                                    """.trimIndent()
                                    sessionState.value?.write("$javaCommand 2>&1 | tail -20\n")
                                    withContext(Dispatchers.Main) {
                                        devEnvStatus = "Java 安装命令已发送（已配置镜像源），请查看终端输出"
                                    }
                                }
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("🦀 安装 Rust") },
                            onClick = {
                                showDevEnvMenu = false
                                devEnvStatus = "正在发送 Rust 安装命令..."
                                scope.launch(Dispatchers.IO) {
                                    // 使用 SourceManager 生成的命令
                                    val sourceConfig = sourceManager.generateAllSourceConfigCommands()
                                    val rustCommand = """
                                        $sourceConfig
                                        # 安装 Rust（使用镜像源）
                                        export RUSTUP_DIST_SERVER="${sourceManager.getSelectedSource(PackageManagerType.RUST).url}"
                                        export RUSTUP_UPDATE_ROOT="${sourceManager.getSelectedSource(PackageManagerType.RUST).url}/rustup"
                                        curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh -s -- -y
                                        source ~/.cargo/env
                                        echo "Rust 安装完成"
                                        rustc --version && cargo --version
                                    """.trimIndent()
                                    sessionState.value?.write("$rustCommand 2>&1 | tail -20\n")
                                    withContext(Dispatchers.Main) {
                                        devEnvStatus = "Rust 安装命令已发送（已配置镜像源），请查看终端输出"
                                    }
                                }
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("🔧 安装 Go") },
                            onClick = {
                                showDevEnvMenu = false
                                devEnvStatus = "正在发送 Go 安装命令..."
                                scope.launch(Dispatchers.IO) {
                                    // 使用 SourceManager 生成的命令
                                    val sourceConfig = sourceManager.generateAllSourceConfigCommands()
                                    val goCommand = """
                                        $sourceConfig
                                        # 安装 Go
                                        apt-get update
                                        apt-get install -y golang
                                        echo "Go 安装完成"
                                        go version
                                    """.trimIndent()
                                    sessionState.value?.write("$goCommand 2>&1 | tail -20\n")
                                    withContext(Dispatchers.Main) {
                                        devEnvStatus = "Go 安装命令已发送（已配置镜像源），请查看终端输出"
                                    }
                                }
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("🌐 安装 Git + Curl + Wget") },
                            onClick = {
                                showDevEnvMenu = false
                                devEnvStatus = "正在发送 Git 安装命令..."
                                scope.launch(Dispatchers.IO) {
                                    // 使用 SourceManager 生成的命令
                                    val sourceConfig = sourceManager.generateAllSourceConfigCommands()
                                    val gitCommand = """
                                        $sourceConfig
                                        # 安装 Git + Curl + Wget
                                        apt-get update
                                        apt-get install -y git curl wget
                                        echo "Git/Curl/Wget 安装完成"
                                        git --version && curl --version | head -1 && wget --version | head -1
                                    """.trimIndent()
                                    sessionState.value?.write("$gitCommand 2>&1 | tail -20\n")
                                    withContext(Dispatchers.Main) {
                                        devEnvStatus = "Git 安装命令已发送（已配置镜像源），请查看终端输出"
                                    }
                                }
                            }
                        )
                        Divider()
                        DropdownMenuItem(
                            text = { Text("🔍 检查已安装环境") },
                            onClick = {
                                showDevEnvMenu = false
                                devEnvStatus = "正在检查环境状态..."
                                scope.launch(Dispatchers.IO) {
                                    val checkCommand = """
                                        echo '=== 环境检查 ==='
                                        echo -n 'Node: ' && node -v 2>/dev/null || echo '未安装'
                                        echo -n 'Python: ' && python3 --version 2>/dev/null || echo '未安装'
                                        echo -n 'Java: ' && java -version 2>&1 | head -1 || echo '未安装'
                                        echo -n 'Rust: ' && rustc --version 2>/dev/null || echo '未安装'
                                        echo -n 'Go: ' && go version 2>/dev/null || echo '未安装'
                                        echo -n 'Git: ' && git --version 2>/dev/null || echo '未安装'
                                    """.trimIndent()
                                    sessionState.value?.write("$checkCommand\n")
                                    withContext(Dispatchers.Main) {
                                        devEnvStatus = "环境检查命令已发送，请查看终端输出"
                                    }
                                }
                            }
                        )
                        Divider()
                        DropdownMenuItem(
                            text = { Text("🗑️ 卸载 Node.js") },
                            onClick = {
                                showDevEnvMenu = false
                                devEnvStatus = "正在发送 Node.js 卸载命令..."
                                scope.launch(Dispatchers.IO) {
                                    val uninstallCommand = """
                                        # 卸载 Node.js
                                        apt-get remove -y nodejs npm
                                        echo "Node.js 卸载完成"
                                    """.trimIndent()
                                    sessionState.value?.write("$uninstallCommand 2>&1 | tail -20\n")
                                    withContext(Dispatchers.Main) {
                                        devEnvStatus = "Node.js 卸载命令已发送，请查看终端输出"
                                    }
                                }
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("🗑️ 卸载 Python3") },
                            onClick = {
                                showDevEnvMenu = false
                                devEnvStatus = "正在发送 Python3 卸载命令..."
                                scope.launch(Dispatchers.IO) {
                                    val uninstallCommand = """
                                        # 卸载 Python3
                                        apt-get remove -y python3 python3-pip
                                        echo "Python3 卸载完成"
                                    """.trimIndent()
                                    sessionState.value?.write("$uninstallCommand 2>&1 | tail -20\n")
                                    withContext(Dispatchers.Main) {
                                        devEnvStatus = "Python3 卸载命令已发送，请查看终端输出"
                                    }
                                }
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("🗑️ 卸载 Java") },
                            onClick = {
                                showDevEnvMenu = false
                                devEnvStatus = "正在发送 Java 卸载命令..."
                                scope.launch(Dispatchers.IO) {
                                    val uninstallCommand = """
                                        # 卸载 Java
                                        apt-get remove -y openjdk-17-jdk-headless
                                        echo "Java 卸载完成"
                                    """.trimIndent()
                                    sessionState.value?.write("$uninstallCommand 2>&1 | tail -20\n")
                                    withContext(Dispatchers.Main) {
                                        devEnvStatus = "Java 卸载命令已发送，请查看终端输出"
                                    }
                                }
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("🗑️ 卸载 Rust") },
                            onClick = {
                                showDevEnvMenu = false
                                devEnvStatus = "正在发送 Rust 卸载命令..."
                                scope.launch(Dispatchers.IO) {
                                    val uninstallCommand = """
                                        # 卸载 Rust
                                        rustup self uninstall -y 2>/dev/null || true
                                        rm -rf ~/.cargo ~/.rustup
                                        echo "Rust 卸载完成"
                                    """.trimIndent()
                                    sessionState.value?.write("$uninstallCommand 2>&1 | tail -20\n")
                                    withContext(Dispatchers.Main) {
                                        devEnvStatus = "Rust 卸载命令已发送，请查看终端输出"
                                    }
                                }
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("🗑️ 卸载 Go") },
                            onClick = {
                                showDevEnvMenu = false
                                devEnvStatus = "正在发送 Go 卸载命令..."
                                scope.launch(Dispatchers.IO) {
                                    val uninstallCommand = """
                                        # 卸载 Go
                                        apt-get remove -y golang
                                        rm -rf /usr/local/go
                                        echo "Go 卸载完成"
                                    """.trimIndent()
                                    sessionState.value?.write("$uninstallCommand 2>&1 | tail -20\n")
                                    withContext(Dispatchers.Main) {
                                        devEnvStatus = "Go 卸载命令已发送，请查看终端输出"
                                    }
                                }
                            }
                        )
                    }
                }
                // 始终显示检查更新按钮
                IconButton(onClick = { showReplaceDialog = true }) {
                    Icon(Icons.Filled.Download, "检查更新", tint = Color(0xFF7BE0A0))
                }
                // 中断（硬杀底层 proot 进程并重建）
                IconButton(onClick = { sessionState.value?.finishIfRunning() }) {
                    Icon(Icons.Filled.Stop, "中断", tint = Color(0xFFFF6B6B))
                }
                // 清屏
                IconButton(onClick = { sessionState.value?.write("clear\n") }) {
                    Icon(Icons.Filled.ClearAll, "清屏", tint = Color.White)
                }
            }

            // ═══════════ 开发环境状态显示 ═══════════
            if (devEnvStatus.isNotBlank()) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF1A2E1A))
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            devEnvStatus,
                            color = Color(0xFF7BE0A0),
                            fontSize = 11.sp,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(
                            onClick = { devEnvStatus = "" },
                            modifier = Modifier.padding(start = 8.dp)
                        ) {
                            Text("关闭", fontSize = 10.sp, color = Color(0xFF999999))
                        }
                    }
                }
            }

            // ═══════════ 检查更新确认对话框 ═══════════
            if (showReplaceDialog) {
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = { showReplaceDialog = false },
                    title = { Text("检查更新") },
                    text = {
                        val stateText = when (val st = sandboxState) {
                            is QuroLinuxEnv.SandboxState.NotInstalled -> "当前未安装 Linux 环境"
                            is QuroLinuxEnv.SandboxState.Ready -> "当前已安装 Ubuntu 24.04"
                            else -> "当前状态未知"
                        }
                        Text("$stateText\n\n点击「检查更新」将检查是否有新版本的 Ubuntu rootfs。如果有新版本，将下载并替换当前环境（约207MB）。\n\n已安装的 CMS 模块和开发环境可能需要重新部署。")
                    },
                    confirmButton = {
                        androidx.compose.material3.TextButton(
                            onClick = {
                                showReplaceDialog = false
                                QuroLinuxEnv.setup(context)
                            }
                        ) {
                            Text("检查更新")
                        }
                    },
                    dismissButton = {
                        androidx.compose.material3.TextButton(
                            onClick = { showReplaceDialog = false }
                        ) {
                            Text("取消")
                        }
                    },
                )
            }

            // ═══════════ Linux 环境安装/进度横幅 ═══════════
            when (val st = sandboxState) {
                is QuroLinuxEnv.SandboxState.NotInstalled -> {
                    QuroTermuxBanner(
                        "未安装 Linux 环境（proot + Ubuntu），点此安装后可运行 python3 / 完整 Linux 命令",
                        "安装 Linux 环境",
                    ) { QuroLinuxEnv.setup(context) }
                }
                is QuroLinuxEnv.SandboxState.Error -> {
                    QuroTermuxBanner("Linux 环境安装失败：${st.message}", "重试") { QuroLinuxEnv.setup(context) }
                }
                is QuroLinuxEnv.SandboxState.Downloading -> {
                    QuroTermuxProgressBanner("正在下载 Ubuntu rootfs … ${(st.progress * 100).toInt()}%", st.progress)
                }
                is QuroLinuxEnv.SandboxState.Extracting -> QuroTermuxProgressBanner("正在解压 rootfs …", null)
                is QuroLinuxEnv.SandboxState.Installing -> QuroTermuxProgressBanner(st.detail.ifEmpty { "正在初始化…" }, null)
                is QuroLinuxEnv.SandboxState.Ready -> { /* 正常终端，无需横幅 */ }
            }

            // ═══════════ Termux 渲染区 ═══════════
            Box(Modifier.fillMaxWidth().weight(1f)) {
                AndroidView(
                    factory = { ctx ->
                        val view = TerminalView(ctx, null).apply {
                            setTextSize(14)
                            isFocusable = true
                            isFocusableInTouchMode = true
                        }
                        val session = QuroTermuxTerminalController.start(ctx, view) { /* onExited */ }
                        sessionState.value = session
                        view.setTerminalViewClient(QuroTermuxViewClient(view))
                        view.attachSession(session)
                        view.requestFocus()
                        view
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }

            // ═══════════ 特殊按键 chip 行 ═══════════
            val chips = listOf(
                "C" to "\u0003",   // ^C
                "D" to "\u0004",   // ^D
                "ESC" to "\u001b",
                "TAB" to "\t",
                "|" to "|",
                "~" to "~",
                "/" to "/",
                "-" to "-",
                "\$" to "\$",
                "&&" to " && ",
            )
            LazyRow(
                Modifier.fillMaxWidth().background(Color(0xFF141414)).padding(horizontal = 6.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(chips) { (label, seq) ->
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0xFF262626))
                            .clickable { sessionState.value?.write(seq) }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(label, color = Color(0xFFBFBFBF), fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }

        // ═══════════ 会话管理面板（顶栏下拉） ═══════════
        if (showSessionPanel) {
            Box(
                Modifier.align(Alignment.TopEnd)
                    .padding(top = 56.dp, end = 8.dp)
                    .width(300.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF1B1B1B))
                    .padding(10.dp),
            ) {
                Column {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "终端会话 (${sessionList.value.size})",
                            color = Color.White,
                            fontSize = 13.sp,
                        )
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { showSessionPanel = false }) {
                            Text("关闭", fontSize = 11.sp, color = Color(0xFF999999))
                        }
                    }
                    sessionList.value.forEach { info ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                Modifier.size(8.dp)
                                    .clip(CircleShape)
                                    .background(if (info.alive) Color(0xFF7BE0A0) else Color(0xFF555555)),
                            )
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(info.name, color = Color.White, fontSize = 12.sp)
                                val sub = buildString {
                                    append(info.kind.name.lowercase())
                                    append(" · ")
                                    append(info.backend.name.lowercase())
                                    if (info.isDefault) append(" · 默认")
                                }
                                Text(sub, color = Color(0xFF999999), fontSize = 10.sp)
                            }
                            if (info.kind == Kind.DEFAULT || info.kind == Kind.EXTRA) {
                                if (!info.isDefault) {
                                    TextButton(
                                        onClick = {
                                            scope.launch {
                                                QuroTerminalSessionManager.switchDefault(info.id)
                                                refreshSessions()
                                            }
                                        },
                                    ) { Text("设默认", fontSize = 10.sp, color = Color(0xFF9CC7FF)) }
                                }
                                TextButton(
                                    onClick = {
                                        scope.launch {
                                            QuroTerminalSessionManager.destroySession(info.id)
                                            refreshSessions()
                                        }
                                    },
                                ) { Text("关闭", fontSize = 10.sp, color = Color(0xFFFF6B6B)) }
                            }
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    TextButton(
                        onClick = {
                            scope.launch {
                                QuroTerminalSessionManager.createSession(context, null, installIfMissing = true)
                                refreshSessions()
                            }
                        },
                        Modifier.align(Alignment.End),
                    ) {
                        Text("+ 新会话", fontSize = 12.sp, color = Color(0xFF7BE0A0))
                    }
                }
            }
        }
    }
}

@Composable
private fun QuroTermuxBanner(text: String, actionLabel: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(Color(0xFF2A1F12)).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, color = Color(0xFFF5C77B), fontSize = 12.sp, modifier = Modifier.weight(1f))
        androidx.compose.material3.TextButton(onClick = onClick) {
            Text(actionLabel, color = Color(0xFFF59E0B))
        }
    }
}

@Composable
private fun QuroTermuxProgressBanner(text: String, progress: Float?) {
    Column(Modifier.fillMaxWidth().background(Color(0xFF122A1A)).padding(horizontal = 12.dp, vertical = 8.dp)) {
        Text(text, color = Color(0xFF7BE0A0), fontSize = 12.sp)
        if (progress != null) {
            Spacer(Modifier.height(4.dp))
            androidx.compose.material3.LinearProgressIndicator(progress = progress, modifier = Modifier.fillMaxWidth())
        } else {
            Spacer(Modifier.height(4.dp))
            androidx.compose.material3.LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
    }
}
