package com.ai.assistance.quro.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ClearAll
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.JavascriptInterface
import com.ai.assistance.quro.core.terminal.QuroLanguageRunner
import android.widget.Toast
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Web
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.assistance.quro.core.linux.QuroLinuxEnv
import com.ai.assistance.quro.core.linux.QuroDesktopInstaller
import com.ai.assistance.quro.core.terminal.QuroHistoryCursor
import com.ai.assistance.quro.util.QuroDiag
import com.ai.assistance.quro.core.terminal.QuroTerminalController
import com.ai.assistance.quro.core.terminal.QuroTerminalHistory
import com.ai.assistance.quro.core.terminal.ShellMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 一个特殊按键 / 快捷插入项（E-10）。
 *
 * @param label chip 上显示的文本
 * @param onTap 点击行为
 */
private data class KeyChip(val label: String, val onTap: () -> Unit)

/**
 * Zorv AI 终端界面。
 *
 * E-8/E-9/E-10 增补：
 *  - 顶栏显示上一条命令的**退出码**（哨兵解析得来，见 `QuroTerminalSentinel`）；
 *  - 运行中显示「■ 中断」按钮，走两段式中断（`QuroTerminalController.interrupt`）；
 *  - 输入框支持 `↑`/`↓` 浏览持久化命令历史（硬件方向键 + 屏幕按钮双通道）；
 *  - 特殊按键 chip 行（^C/^D/^Z/ESC/TAB + 常用符号）；
 *  - 日志一键导出到 `Documents/QuroDocs/terminal_<ts>.log`；
 *  - 非交互 shell 提示横幅，明确告诉用户 stdin 是管道不是 PTY。
 */
@Composable
fun QuroTerminalScreen(onClose: () -> Unit, showDesktopLauncher: Boolean = false) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var input by remember { mutableStateOf("") }

    // 语言运行器
    val languageRunner = remember { QuroLanguageRunner(context) }

    // WebView状态
    var showWebView by remember { mutableStateOf(false) }
    var webContent by remember { mutableStateOf("") }
    var currentLanguage by remember { mutableStateOf<QuroLanguageRunner.Language?>(null) }
    
    // 桌面启动器状态
    var showDesktopMode by remember { mutableStateOf(false) }
    var showVncView by remember { mutableStateOf(false) }
    var vncUrl by remember { mutableStateOf("") }
    val showDesktopInstallerMutable = remember { mutableStateOf(false) }
    var showDesktopInstaller by showDesktopInstallerMutable
    val desktopInstallerStateMutable = remember { mutableStateOf<QuroDesktopInstaller.DesktopState>(QuroDesktopInstaller.DesktopState.NotInstalled) }
    var desktopInstallerState by desktopInstallerStateMutable

    // 进入界面即创建（或复用）常驻会话；离开时不销毁，保留滚动历史，便于再次进入继续操作。
    var session by remember {
        mutableStateOf(QuroTerminalController.session ?: QuroTerminalController.createSession(context))
    }
    val sandboxState by QuroLinuxEnv.state.collectAsState()

    // Linux 环境就绪且当前仍是设备模式时，重建为 Linux 会话（获得 python3 / 完整写能力）。
    // 反过来，若环境实际已缺失/失败但会话仍挂在 Linux 模式，必须切回设备 sh，
    // 否则标题会显示「proot/Linux」而下方却提示安装/部署，造成「完全废了」的错觉。
    LaunchedEffect(sandboxState) {
        val modeBefore = session.mode
        when {
            sandboxState is QuroLinuxEnv.SandboxState.Ready && session.mode == ShellMode.DEVICE -> {
                QuroTerminalController.destroySession()
                session = QuroTerminalController.createSession(context)
                QuroDiag.log("Terminal", "env→Ready 且原会话为设备模式，重建为 Linux 会话 (modeBefore=$modeBefore)")
            }
            sandboxState !is QuroLinuxEnv.SandboxState.Ready && session.mode == ShellMode.LINUX -> {
                QuroTerminalController.destroySession()
                session = QuroTerminalController.createSession(context)
                QuroDiag.log(
                    "Terminal",
                    "env 非就绪(${sandboxState::class.simpleName}) 但原会话为 Linux 模式，重建为设备会话 " +
                        "(modeBefore=$modeBefore) —— 修复「标题显示 proot/Linux 却提示安装」的错位"
                )
            }
            else -> {
                QuroDiag.log(
                    "Terminal",
                    "sandboxState=${sandboxState::class.simpleName} session.mode=${session.mode} → 无需重建会话"
                )
            }
        }
    }

    // 进入终端即按真实文件系统重新探测一次 Linux 环境状态（见 QuroLinuxEnv.probe 说明）：
    // 若 rootfs 已被删掉而 _state 仍停在旧的 Ready，不重新探测就再也不会弹出「安装 Linux 环境」
    // 横幅，用户顶栏只剩「导出日志」按钮、无法重新部署。重新探测可让横幅/部署按钮回到正确状态。
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val st = QuroLinuxEnv.probe(context)
            QuroDiag.log(
                "Terminal",
                "进入终端 re-probe | available=${st.available} | mode=${QuroTerminalController.session?.mode} | " +
                    "reason=${st.reason}"
            )
        }
    }

    // ── E-10 命令历史 ──────────────────────────────────────────────
    // SharedPreferences 首次读取会命中磁盘，绝不能放在组合里同步做（会掉帧甚至 ANR），
    // 因此走 LaunchedEffect + IO 调度器异步加载，加载完再触发一次重组。
    var history by remember { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(Unit) {
        history = withContext(Dispatchers.IO) { QuroTerminalHistory.load(context) }
    }
    // history 变化（提交了新命令）时重建游标 → 自动回到草稿位，符合 bash 语义。
    val cursor = remember(history) { QuroHistoryCursor(history) }

    // ── 顶部临时提示（导出结果 / 中断结果）────────────────────────
    var notice by remember { mutableStateOf("") }
    LaunchedEffect(notice) {
        if (notice.isNotEmpty()) {
            delay(NOTICE_DURATION_MS)
            notice = ""
        }
    }

    // 非交互 shell 提示横幅：用户可关闭，仅本次进入界面内生效。
    var showPipeHint by remember { mutableStateOf(true) }

    val lines = session.lines
    val busy = session.busy
    val lastExit = session.lastExit
    val interrupted = session.lastInterrupted
    val modeLabel = if (session.mode == ShellMode.LINUX) "proot/Linux" else "设备 sh"

    val listState = rememberLazyListState()
    val atBottom by remember {
        derivedStateOf {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            lastVisible >= lines.size - 2
        }
    }
    LaunchedEffect(lines.size) {
        if (atBottom) listState.scrollToItem(lines.lastIndex.coerceAtLeast(0))
    }

    /** 提交当前输入框内容：执行 + 落历史 + 清空输入 + 复位游标。 */
    fun submit() {
        val cmd = input
        input = ""
        cursor.reset()
        
        // 检测是否为代码内容（包含多种语言）
        val language = languageRunner.detectLanguage(cmd)
        if (language != QuroLanguageRunner.Language.UNKNOWN) {
            // 运行代码并显示WebView
            currentLanguage = language
            scope.launch {
                val result = languageRunner.runCode(cmd, language)
                if (result.htmlOutput != null) {
                    webContent = result.htmlOutput
                    showWebView = true
                } else {
                    // 普通命令，发送到shell
                    QuroTerminalController.sendToShell(cmd)
                }
            }
        } else {
            // 普通命令，发送到shell
            QuroTerminalController.sendToShell(cmd)
        }
        
        if (cmd.isNotBlank()) {
            // 写盘同样放到 IO 线程：每敲一条命令都要写，主线程做没必要。
            scope.launch {
                history = withContext(Dispatchers.IO) { QuroTerminalHistory.add(context, cmd) }
            }
        }
    }

    /** ↑：往更旧的历史走。历史为空时不动输入框。 */
    fun historyUp() {
        cursor.up(input)?.let { input = it }
    }

    /** ↓：往更新的历史走，越过最新一条后还原草稿。 */
    fun historyDown() {
        cursor.down()?.let { input = it }
    }

    /** ■ 中断：两段式（软 ETX → 硬杀重建）。硬中断会换 session 实例，必须重新取。 */
    fun interruptNow() {
        scope.launch {
            // interrupt() 内部涉及杀进程 / 重建 shell 等阻塞操作，放 IO 线程。
            val msg = withContext(Dispatchers.IO) { QuroTerminalController.interrupt(context) }
            session = QuroTerminalController.session ?: session
            notice = msg
        }
    }

    /** 导出滚动缓冲区到 Documents/QuroDocs。 */
    fun exportLog() {
        scope.launch {
            val path = withContext(Dispatchers.IO) { session.exportLog() }
            notice = if (path != null) "日志已导出：$path" else "导出失败：Documents / Downloads 均不可写"
        }
    }

    Column(Modifier.fillMaxSize().background(Color(0xFF0C0C0C))) {
        // ═══════════ 顶栏 ═══════════
        Row(
            Modifier.fillMaxWidth().height(52.dp).background(Color(0xFF1B1B1B)).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onClose) {
                Icon(Icons.Filled.ArrowBack, "back", tint = Color.White)
            }
            Text("终端 · $modeLabel", color = Color.White, fontSize = 14.sp)
            Spacer(Modifier.weight(1f))
            StatusChip(busy = busy, lastExit = lastExit, interrupted = interrupted)
            // 顶栏常驻「部署 Linux 环境」按钮：环境未就绪（NotInstalled/Error）时直接可点，
            // 不再依赖下方横幅——避免「部署按钮消失、只剩导出日志」的困境。
            if (sandboxState is QuroLinuxEnv.SandboxState.NotInstalled
                || sandboxState is QuroLinuxEnv.SandboxState.Error
            ) {
                IconButton(onClick = { QuroLinuxEnv.setup(context) }) {
                    Icon(Icons.Filled.Download, "部署 Linux 环境", tint = Color(0xFF7BE0A0))
                }
            }
            if (true) {
                IconButton(onClick = {
                    Toast.makeText(context, "启动VNC服务器...", Toast.LENGTH_SHORT).show()
                    scope.launch {
                        val result = QuroDesktopInstaller.startDesktop(context)
                        delay(2000)
                        Toast.makeText(context, "VNC服务器已启动，正在打开桌面...", Toast.LENGTH_SHORT).show()
                        vncUrl = "http://localhost:6080/vnc.html"
                        showVncView = true
                    }
                }) {
                    Icon(Icons.Filled.Web, "启动VNC", tint = Color(0xFF6FA8FF))
                }
                IconButton(onClick = {
                    Toast.makeText(context, "正在部署Linux开发环境...", Toast.LENGTH_SHORT).show()
                    QuroLinuxEnv.setup(context)
                }) {
                    Icon(Icons.Filled.Download, "部署Linux环境", tint = Color(0xFF7BE0A0))
                }
            }
            IconButton(onClick = { exportLog() }) {
                Icon(Icons.Filled.SaveAlt, "export", tint = Color.White)
            }
            IconButton(onClick = { session.clear() }) {
                Icon(Icons.Filled.ClearAll, "clear", tint = Color.White)
            }
        }

        // ═══════════ 临时提示条（导出/中断结果）═══════════
        if (notice.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().background(Color(0xFF13233A)).padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(notice, color = Color(0xFF9CC7FF), fontSize = 12.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = { notice = "" }) {
                    Text("知道了", color = Color(0xFF6FA8FF), fontSize = 12.sp)
                }
            }
        }

        // ═══════════ 非交互 shell 提示（E-10）═══════════
        // 这不是「装饰性说明」：用户会拿 vi / top / ssh 来试，然后以为终端坏了。
        // 明确写清限制与替代做法，比事后解释便宜得多。
        if (showPipeHint) {
            Row(
                Modifier.fillMaxWidth().background(Color(0xFF241F33)).padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "非交互 shell：stdin 是管道不是 PTY，无 tty 回显与作业控制。" +
                        "vi / top / ssh 等全屏程序不可用，请改用 nano -Q / ps / 一次性命令；" +
                        "^C 走两段式中断（先送 ETX，无效则重启 shell）。",
                    color = Color(0xFFC5B8E8),
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { showPipeHint = false }) {
                    Text("不再提示", color = Color(0xFF9E86E0), fontSize = 11.sp)
                }
            }
        }

        // ═══════════ Linux 环境安装/进度横幅 ═══════════
        when (val st = sandboxState) {
            is QuroLinuxEnv.SandboxState.NotInstalled -> {
                SetupBanner(
                    text = "未安装 Linux 环境（proot + Alpine），点此安装后可运行 python3 / 完整 Linux 命令",
                    actionLabel = "安装 Linux 环境",
                    onClick = { QuroLinuxEnv.setup(context) },
                )
            }
            is QuroLinuxEnv.SandboxState.Error -> {
                SetupBanner(
                    text = "Linux 环境安装失败：${st.message}",
                    actionLabel = "重试",
                    onClick = { QuroLinuxEnv.setup(context) },
                )
            }
            is QuroLinuxEnv.SandboxState.Downloading -> {
                ProgressBanner("正在下载 Alpine rootfs … ${(st.progress * 100).toInt()}%", st.progress)
            }
            is QuroLinuxEnv.SandboxState.Extracting -> {
                ProgressBanner("正在解压 rootfs …", null)
            }
            is QuroLinuxEnv.SandboxState.Installing -> {
                ProgressBanner(st.detail.ifEmpty { "正在初始化…" }, null)
            }
            is QuroLinuxEnv.SandboxState.Ready -> { /* 正常终端，无需横幅 */ }
        }

        // ═══════════ 滚动输出区 ═══════════
        if (showWebView && webContent.isNotEmpty()) {
            // WebView显示代码运行结果
            Column(modifier = Modifier.fillMaxWidth().weight(1f)) {
                // WebView顶栏
                Row(
                    modifier = Modifier.fillMaxWidth().background(Color(0xFF1B1B1B)).padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${currentLanguage?.name ?: "代码"} 运行结果",
                        color = Color.White,
                        fontSize = 14.sp
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    TextButton(onClick = { showWebView = false }) {
                        Text("返回终端", color = Color(0xFF6FA8FF))
                    }
                }

                // WebView内容
                AndroidView(
                    factory = { context ->
                        WebView(context).apply {
                            webViewClient = WebViewClient()
                            webChromeClient = WebChromeClient()
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.allowFileAccessFromFileURLs = true
                            settings.allowUniversalAccessFromFileURLs = true
                            settings.defaultTextEncodingName = "UTF-8"
                        }
                    },
                    update = { webView ->
                        webView.loadDataWithBaseURL(
                            null,
                            webContent,
                            "text/html",
                            "UTF-8",
                            null
                        )
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }
        } else if (showVncView) {
            // VNC WebView
            AndroidView(
                factory = { context ->
                    WebView(context).apply {
                        webViewClient = WebViewClient()
                        webChromeClient = WebChromeClient()
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.allowFileAccessFromFileURLs = true
                        settings.allowUniversalAccessFromFileURLs = true
                        settings.defaultTextEncodingName = "UTF-8"
                    }
                },
                update = { webView ->
                    webView.loadUrl(vncUrl)
                },
                modifier = Modifier.fillMaxSize()
            )
        } else {
            // 普通终端输出
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                items(lines) { line ->
                    Text(
                        text = line,
                        color = Color(0xFFD6D6D6),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }

        // ═══════════ 特殊按键 chip 行（E-10）═══════════
        SpecialKeyRow(
            busy = busy,
            onInterrupt = { interruptNow() },
            onKey = { QuroTerminalController.sendKey(it) },
            onInsert = { input += it },
            onHistoryUp = { historyUp() },
            onHistoryDown = { historyDown() },
        )

        // ═══════════ 输入行 ═══════════
        Row(
            Modifier.fillMaxWidth().background(Color(0xFF1B1B1B)).padding(horizontal = 4.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BasicTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier
                    .weight(1f)
                    .background(Color(0xFF0C0C0C))
                    .padding(12.dp)
                    // 外接键盘 / 实体方向键：↑↓ 浏览历史，与 chip 行的 ↑↓ 按钮等价。
                    // 返回 true 表示事件已消费，避免焦点被方向键移走。
                    .onPreviewKeyEvent { e ->
                        if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (e.key) {
                            Key.DirectionUp -> { historyUp(); true }
                            Key.DirectionDown -> { historyDown(); true }
                            else -> false
                        }
                    },
                singleLine = false,
                textStyle = TextStyle(color = Color.White, fontSize = 14.sp, fontFamily = FontFamily.Monospace),
                decorationBox = { inner ->
                    if (input.isEmpty()) {
                        Text(
                            "输入命令后点 ▶ 执行（clear 清屏，↑↓ 翻历史 ${history.size} 条）",
                            color = Color.Gray,
                            fontSize = 13.sp,
                        )
                    }
                    inner()
                }
            )
            // 运行中才显示中断按钮：空闲时点它没有任何意义，只会让人以为终端卡住了。
            if (busy) {
                IconButton(onClick = { interruptNow() }) {
                    Icon(Icons.Filled.Stop, "interrupt", tint = Color(0xFFFF6B6B))
                }
            }
            IconButton(onClick = { submit() }) {
                Icon(Icons.Filled.Send, "send", tint = Color.White)
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            // 离开界面保留会话（session 仍挂在 controller 上，下次进入复用）。
        }
    }
}

/** 顶栏状态徽标：就绪 / 运行中 / 上条命令退出码 / 被中断。 */
@Composable
private fun StatusChip(busy: Boolean, lastExit: Int, interrupted: Boolean) {
    val (text, color) = when {
        busy -> "运行中…" to Color(0xFFF59E0B)
        interrupted -> "已中断" to Color(0xFFFF6B6B)
        lastExit == 0 -> "就绪" to Color(0xFF7BE0A0)
        else -> "exit $lastExit" to Color(0xFFFF6B6B)
    }
    Text(
        text = text,
        color = color,
        fontSize = 12.sp,
        fontFamily = FontFamily.Monospace,
        modifier = Modifier.padding(end = 4.dp),
    )
}

/**
 * 特殊按键 / 快捷符号 chip 行（E-10）。
 *
 * 分两组：
 *  1. **控制键**——直接写控制字符到 shell 的 stdin（`^C` 例外，走两段式中断）；
 *  2. **符号插入**——手机软键盘上 `|`、`~`、`$` 这些要翻两层才找得到，
 *     直接插进输入框比让用户去翻键盘快得多。
 */
@Composable
private fun SpecialKeyRow(
    busy: Boolean,
    onInterrupt: () -> Unit,
    onKey: (String) -> Unit,
    onInsert: (String) -> Unit,
    onHistoryUp: () -> Unit,
    onHistoryDown: () -> Unit,
) {
    val chips = remember(busy) {
        listOf(
            // ^C 不能只写 \u0003：管道 stdin 无法投递 SIGINT，必须走 controller 的两段式中断。
            KeyChip("^C", onInterrupt),
            KeyChip("^D") { onKey("\u0004") },   // EOF
            KeyChip("^Z") { onKey("\u001a") },   // SUSP（无作业控制时多数程序会直接退出）
            KeyChip("ESC") { onKey("\u001b") },
            KeyChip("TAB") { onKey("\t") },
            KeyChip("↑", onHistoryUp),
            KeyChip("↓", onHistoryDown),
            KeyChip("|") { onInsert("|") },
            KeyChip("~") { onInsert("~") },
            KeyChip("/") { onInsert("/") },
            KeyChip("-") { onInsert("-") },
            KeyChip("$") { onInsert("$") },
            KeyChip("*") { onInsert("*") },
            KeyChip(">") { onInsert(">") },
            KeyChip("&&") { onInsert(" && ") },
            KeyChip("../") { onInsert("../") },
        )
    }

    Row(
        Modifier
            .fillMaxWidth()
            .background(Color(0xFF141414))
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 6.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (chip in chips) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xFF262626))
                    .clickable { chip.onTap() }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    chip.label,
                    color = Color(0xFFBFBFBF),
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}

@Composable
private fun SetupBanner(text: String, actionLabel: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(Color(0xFF2A1F12)).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, color = Color(0xFFF5C77B), fontSize = 12.sp, modifier = Modifier.weight(1f))
        TextButton(onClick = onClick) {
            Text(actionLabel, color = Color(0xFFF59E0B))
        }
    }
}

@Composable
private fun ProgressBanner(text: String, progress: Float?) {
    Column(Modifier.fillMaxWidth().background(Color(0xFF122A1A)).padding(horizontal = 12.dp, vertical = 8.dp)) {
        Text(text, color = Color(0xFF7BE0A0), fontSize = 12.sp)
        if (progress != null) {
            Spacer(Modifier.height(4.dp))
            LinearProgressIndicator(progress = progress, modifier = Modifier.fillMaxWidth())
        } else {
            Spacer(Modifier.height(4.dp))
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
    }
}

/** 顶部临时提示条的显示时长。 */
private const val NOTICE_DURATION_MS: Long = 5000L

/**
 * 终端桌面启动器模式 - 图形界面
 *
 * 在终端内部显示图形化桌面启动器，可以启动各种应用
 */
@Composable
fun DesktopLauncherMode(
    onRunCode: (String, QuroLanguageRunner.Language) -> Unit,
    onOpenTerminal: () -> Unit,
    scope: CoroutineScope,
    showDesktopInstaller: MutableState<Boolean>,
    desktopInstallerState: MutableState<QuroDesktopInstaller.DesktopState>
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val languageRunner = remember { QuroLanguageRunner(context) }
    
    // VNC视图状态
    var showVncView by remember { mutableStateOf(false) }
    var vncUrl by remember { mutableStateOf("") }
    var isDesktopInstalled by remember { mutableStateOf(false) }
    
    // 检测桌面环境是否已安装
    LaunchedEffect(Unit) {
        isDesktopInstalled = withContext(Dispatchers.IO) {
            QuroDesktopInstaller.probe(context)
        }
    }

    // 应用列表
    val apps = listOf(
        Triple("javascript", Icons.Default.Code, "JavaScript"),
        Triple("python", Icons.Default.Phone, "Python"),
        Triple("html", Icons.Default.Web, "HTML"),
        Triple("json", Icons.Default.Folder, "JSON"),
        Triple("css", Icons.Default.Star, "CSS"),
        Triple("xml", Icons.Default.Settings, "XML"),
        Triple("c", Icons.Default.Terminal, "C/C++"),
        Triple("java", Icons.Default.Apps, "Java")
    )

    // 代码模板
    val codeTemplates = mapOf(
        "javascript" to """
            console.log("Hello from JavaScript!");
            document.body.innerHTML = "<h1 style='color: white;'>JavaScript 运行中</h1>";
        """.trimIndent(),
        "python" to """
            print("Hello from Python!")
            import sys
            print(f"Python version: {sys.version}")
        """.trimIndent(),
        "html" to """
            <!DOCTYPE html>
            <html>
            <head><title>HTML Preview</title></head>
            <body style="background: #1e1e1e; color: white; font-family: Arial;">
                <h1>HTML 预览</h1>
                <p>这是一个HTML预览页面</p>
                <button onclick="alert('Hello!')">点击我</button>
            </body>
            </html>
        """.trimIndent(),
        "json" to """
            {
                "name": "ZorvAI",
                "version": "1.0",
                "features": ["terminal", "code", "web"]
            }
        """.trimIndent(),
        "css" to """
            body {
                background: #1e1e1e;
                color: white;
                font-family: Arial;
                padding: 20px;
            }
            h1 { color: #7BE0A0; }
            button {
                background: #3d3d5c;
                color: white;
                border: none;
                padding: 10px 20px;
                border-radius: 5px;
                cursor: pointer;
            }
        """.trimIndent(),
        "xml" to """
            <?xml version="1.0" encoding="UTF-8"?>
            <config>
                <app name="ZorvAI">
                    <version>1.0</version>
                    <features>
                        <feature>terminal</feature>
                        <feature>code</feature>
                    </features>
                </app>
            </config>
        """.trimIndent(),
        "c" to """
            #include <stdio.h>

            int main() {
                printf("Hello from C!\\n");
                return 0;
            }
        """.trimIndent(),
        "java" to """
            public class Main {
                public static void main(String[] args) {
                    System.out.println("Hello from Java!");
                }
            }
        """.trimIndent()
    )

    // 图形界面背景
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF1a1a2e))
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            // 顶部状态栏
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(40.dp)
                    .background(Color(0xFF2d2d44))
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "ZorvAI 终端桌面",
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.weight(1f))
                TextButton(onClick = onOpenTerminal) {
                    Text("终端", color = Color(0xFF6FA8FF))
                }
                TextButton(onClick = { showDesktopInstaller.value = !showDesktopInstaller.value }) {
                    Text(if (isDesktopInstalled) "重新安装" else "安装桌面环境", color = Color(0xFF7BE0A0))
                }
                if (isDesktopInstalled) {
                    TextButton(onClick = {
                        scope.launch {
                            val result = QuroDesktopInstaller.startDesktop(context)
                            delay(2000)
                            vncUrl = "http://localhost:6080/vnc.html"
                            showVncView = true
                        }
                    }) {
                        Text("启动VNC", color = Color(0xFF6FA8FF))
                    }
                }
            }

            // 主内容区域
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(16.dp)
            ) {
                // 左侧应用面板
                Column(
                    modifier = Modifier
                        .width(200.dp)
                        .fillMaxHeight()
                        .background(Color(0xFF2d2d44))
                        .padding(16.dp)
                ) {
                    Text(
                        text = "应用列表",
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    // 应用列表
                    apps.forEach { (id, icon, label) ->
                        DesktopLauncherAppItem(
                            icon = icon,
                            label = label,
                            onClick = {
                                val code = codeTemplates[id] ?: ""
                                val language = when (id) {
                                    "javascript" -> QuroLanguageRunner.Language.JAVASCRIPT
                                    "python" -> QuroLanguageRunner.Language.PYTHON
                                    "html" -> QuroLanguageRunner.Language.HTML
                                    "json" -> QuroLanguageRunner.Language.JSON
                                    "css" -> QuroLanguageRunner.Language.CSS
                                    "xml" -> QuroLanguageRunner.Language.XML
                                    "c" -> QuroLanguageRunner.Language.C
                                    "java" -> QuroLanguageRunner.Language.JAVA
                                    else -> QuroLanguageRunner.Language.UNKNOWN
                                }
                                onRunCode(code, language)
                            }
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }

                Spacer(modifier = Modifier.width(16.dp))

                // 右侧内容区域
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .background(Color(0xFF0C0C0C))
                        .padding(16.dp)
                ) {
                    // 代码编辑区域标题
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "代码编辑器",
                            color = Color.White,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.weight(1f))
                        Text(
                            text = "选择左侧应用运行代码",
                            color = Color.Gray,
                            fontSize = 12.sp
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // 代码显示区域
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .background(Color(0xFF1e1e2e))
                            .padding(16.dp)
                    ) {
                        if (showVncView) {
                            // VNC WebView
                            AndroidView(
                                factory = { context ->
                                    WebView(context).apply {
                                        webViewClient = WebViewClient()
                                        webChromeClient = WebChromeClient()
                                        settings.javaScriptEnabled = true
                                        settings.domStorageEnabled = true
                                        settings.allowFileAccessFromFileURLs = true
                                        settings.allowUniversalAccessFromFileURLs = true
                                        settings.defaultTextEncodingName = "UTF-8"
                                    }
                                },
                                update = { webView ->
                                    webView.loadUrl(vncUrl)
                                },
                                modifier = Modifier.fillMaxSize()
                            )
                        } else if (showDesktopInstaller.value) {
                            // 桌面环境安装界面
                            Column {
                                Text(
                                    text = "Linux桌面环境安装",
                                    color = Color.White,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.height(16.dp))
                                Text(
                                    text = "安装XFCE桌面环境和VNC服务器，提供完整的图形界面",
                                    color = Color.Gray,
                                    fontSize = 14.sp
                                )
                                Spacer(modifier = Modifier.height(16.dp))
                                Text(
                                    text = "安装内容：\n• XFCE桌面环境\n• VNC服务器（TigerVNC）\n• 额外工具（dbus, xorg等）",
                                    color = Color(0xFFD6D6D6),
                                    fontSize = 14.sp
                                )
                                Spacer(modifier = Modifier.height(16.dp))
                                Row {
                                    Button(
                                        onClick = {
                                            scope.launch {
                                                desktopInstallerState.value = QuroDesktopInstaller.DesktopState.Installing("安装中...")
                                                QuroDesktopInstaller.install(context)
                                                desktopInstallerState.value = QuroDesktopInstaller.DesktopState.Ready
                                            }
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7BE0A0))
                                    ) {
                                        Text("安装桌面环境", color = Color.Black)
                                    }
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Button(
                                        onClick = {
                                            scope.launch {
                                                val result = QuroDesktopInstaller.startDesktop(context)
                                                // 等待服务启动
                                                delay(2000)
                                                vncUrl = "http://localhost:6080/vnc.html"
                                                showVncView = true
                                            }
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6FA8FF))
                                    ) {
                                        Text("启动VNC", color = Color.White)
                                    }
                                }
                            }
                        } else {
                            // 代码显示区域
                            Text(
                                text = "点击左侧应用图标运行示例代码\n\n支持的语言：\n• JavaScript\n• Python\n• HTML\n• JSON\n• CSS\n• XML\n• C/C++\n• Java\n\n代码运行结果显示在WebView中",
                                color = Color(0xFFD6D6D6),
                                fontSize = 14.sp,
                                lineHeight = 20.sp
                            )
                        }
                    }
                }
            }

            // 底部任务栏
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .background(Color(0xFF2d2d44))
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 开始按钮
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color(0xFF3d3d5c))
                        .clickable { },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Apps,
                        contentDescription = "开始",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Spacer(modifier = Modifier.width(16.dp))

                // 快捷方式
                DesktopLauncherQuickButton(
                    icon = Icons.Default.Terminal,
                    label = "终端",
                    onClick = onOpenTerminal
                )

                DesktopLauncherQuickButton(
                    icon = Icons.Default.Code,
                    label = "代码",
                    onClick = { }
                )

                DesktopLauncherQuickButton(
                    icon = Icons.Default.Web,
                    label = "浏览器",
                    onClick = { }
                )

                Spacer(modifier = Modifier.weight(1f))

                // 系统托盘
                Text(
                    text = "ZorvAI Desktop",
                    color = Color.White,
                    fontSize = 12.sp
                )
            }
        }
    }
}

/**
 * 桌面启动器应用项目
 */
@Composable
fun DesktopLauncherAppItem(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(40.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(Color(0xFF3d3d5c))
            .clickable { onClick() }
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = Color.White,
            modifier = Modifier.size(20.dp)
        )

        Spacer(modifier = Modifier.width(8.dp))

        Text(
            text = label,
            color = Color.White,
            fontSize = 14.sp
        )
    }
}

/**
 * 桌面启动器快捷按钮
 */
@Composable
fun DesktopLauncherQuickButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(Color(0xFF3d3d5c))
            .clickable { onClick() }
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = Color.White,
            modifier = Modifier.size(16.dp)
        )

        Spacer(modifier = Modifier.width(4.dp))

        Text(
            text = label,
            color = Color.White,
            fontSize = 12.sp
        )
    }
}
