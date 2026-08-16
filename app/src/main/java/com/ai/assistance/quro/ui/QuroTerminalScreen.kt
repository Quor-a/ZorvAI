package com.ai.assistance.quro.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.assistance.quro.core.linux.QuroLinuxEnv
import com.ai.assistance.quro.core.terminal.QuroHistoryCursor
import com.ai.assistance.quro.util.QuroDiag
import com.ai.assistance.quro.core.terminal.QuroTerminalController
import com.ai.assistance.quro.core.terminal.QuroTerminalHistory
import com.ai.assistance.quro.core.terminal.ShellMode
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
fun QuroTerminalScreen(onClose: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var input by remember { mutableStateOf("") }

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
        QuroTerminalController.sendToShell(cmd)
        input = ""
        cursor.reset()
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
                    modifier = Modifier.fillMaxWidth()
                )
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
