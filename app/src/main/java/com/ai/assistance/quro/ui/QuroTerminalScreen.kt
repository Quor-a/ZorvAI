package com.ai.assistance.quro.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ClearAll
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.assistance.quro.core.linux.QuroLinuxEnv
import com.ai.assistance.quro.core.terminal.QuroShellSession
import com.ai.assistance.quro.core.terminal.QuroTerminalController
import com.ai.assistance.quro.core.terminal.ShellMode
import androidx.compose.runtime.collectAsState

@Composable
fun QuroTerminalScreen(onClose: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var input by remember { mutableStateOf("") }

    // 进入界面即创建（或复用）常驻会话；离开时不销毁，保留滚动历史，便于再次进入继续操作。
    var session by remember {
        mutableStateOf(QuroTerminalController.session ?: QuroTerminalController.createSession(context))
    }
    val sandboxState by QuroLinuxEnv.state.collectAsState()

    // Linux 环境就绪且当前仍是设备模式时，重建为 Linux 会话（获得 python3 / 完整写能力）。
    LaunchedEffect(sandboxState) {
        if (sandboxState is QuroLinuxEnv.SandboxState.Ready && session.mode == ShellMode.DEVICE) {
            QuroTerminalController.destroySession()
            session = QuroTerminalController.createSession(context)
        }
    }

    val lines = session.lines
    val busy = session.busy
    val modeLabel = if (session.mode == ShellMode.LINUX) "proot/Linux" else "设备 sh"

    val listState = rememberLazyListState()
    val atBottom by remember {
        derivedStateOf { listState.firstVisibleItemIndex >= lines.size - 2 }
    }
    LaunchedEffect(lines.size) {
        if (atBottom) listState.scrollToItem(lines.lastIndex.coerceAtLeast(0))
    }

    Column(Modifier.fillMaxSize().background(Color(0xFF0C0C0C))) {
        Row(
            Modifier.fillMaxWidth().height(52.dp).background(Color(0xFF1B1B1B)).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onClose) {
                Icon(Icons.Filled.ArrowBack, "back", tint = Color.White)
            }
            Text("终端 · $modeLabel", color = Color.White, modifier = Modifier.padding(start = 8.dp))
            Spacer(Modifier.weight(1f))
            Text(
                if (busy) "运行中…" else "就绪",
                color = if (busy) Color(0xFFF59E0B) else Color.Gray,
                fontSize = 12.sp,
                modifier = Modifier.padding(end = 8.dp)
            )
            IconButton(onClick = { session.clear() }) {
                Icon(Icons.Filled.ClearAll, "clear", tint = Color.White)
            }
        }

        // Linux 环境安装/进度横幅
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

        Row(
            Modifier.fillMaxWidth().background(Color(0xFF1B1B1B)).padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BasicTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f).background(Color(0xFF0C0C0C)).padding(12.dp),
                singleLine = false,
                textStyle = TextStyle(color = Color.White, fontSize = 14.sp, fontFamily = FontFamily.Monospace),
                decorationBox = { inner ->
                    if (input.isEmpty()) {
                        Text("输入命令后回车执行（clear 清屏，exit 结束会话）", color = Color.Gray, fontSize = 14.sp)
                    }
                    inner()
                }
            )
            IconButton(onClick = {
                val cmd = input
                if (cmd.isNotBlank()) {
                    QuroTerminalController.sendToShell(cmd)
                    input = ""
                } else {
                    QuroTerminalController.sendToShell("")
                }
            }) {
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
