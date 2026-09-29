package com.ai.assistance.quro.ui
import androidx.compose.ui.res.stringResource
import com.ai.assistance.quro.R
import com.ai.assistance.quro.util.qstr

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.assistance.quro.core.linux.QuroLinuxEnv
import com.ai.assistance.quro.core.terminal.QuroTerminalController
import com.ai.assistance.quro.ui.theme.Line
import kotlinx.coroutines.*

/**
 * 开发环境管理界面 — 独立于 CMS 引擎，每个环境可单独部署。
 *
 * 显示 Java 17、Gradle、Rust/Cargo、Go 等开发环境的部署状态，
 * 点击部署按钮后在 proot/Ubuntu 终端中执行对应的安装脚本。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuroDevEnvScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()

    // 环境分类 - 简化版本，直接使用命令
    data class EnvSection(val title: String, val items: List<Pair<String, DevEnvInfo>>)

    val envSections = listOf(
        EnvSection(stringResource(R.string.qk_01702), listOf(
            "python" to DevEnvInfo(Icons.Filled.Code, "Python3", "Python 3 + pip + venv", Color(0xFF3776AB)),
        )),
        EnvSection(stringResource(R.string.qk_01703), listOf(
            "node" to DevEnvInfo(Icons.Filled.Javascript, "Node.js", stringResource(R.string.qk_01704), Color(0xFF339933)),
            "pnpm" to DevEnvInfo(Icons.Filled.Apps, "PNPM + TypeScript", stringResource(R.string.qk_01705), Color(0xFF339933)),
        )),
        EnvSection(stringResource(R.string.qk_01706), listOf(
            "ssh" to DevEnvInfo(Icons.Filled.VpnKey, stringResource(R.string.qk_01707), stringResource(R.string.qk_01708), Color(0xFF0055A5)),
        )),
        EnvSection(stringResource(R.string.qk_01709), listOf(
            "java" to DevEnvInfo(Icons.Filled.Coffee, "Java", "OpenJDK 17", Color(0xFFE6794A)),
            "gradle" to DevEnvInfo(Icons.Filled.Build, "Gradle", stringResource(R.string.qk_01710), Color(0xFFE6794A)),
        )),
        EnvSection(stringResource(R.string.qk_01711), listOf(
            "rust" to DevEnvInfo(Icons.Filled.Memory, "Rust / Cargo", stringResource(R.string.qk_01712), Color(0xFFE65100)),
        )),
        EnvSection(stringResource(R.string.qk_01713), listOf(
            "go" to DevEnvInfo(Icons.Filled.SmartToy, "Go", stringResource(R.string.qk_01714), Color(0xFF00ADD8)),
        )),
    )

    // 终端就绪状态
    var termReady by remember { mutableStateOf(false) }
    // 各环境就绪状态（使用 mutableStateOf 包装 Map 以触发重组）
    var envStates by remember { mutableStateOf<Map<String, Boolean>>(emptyMap()) }
    // 正在部署的环境
    var deploying by remember { mutableStateOf<String?>(null) }
    // 部署实时日志
    var deployLogs by remember { mutableStateOf<List<String>>(emptyList()) }
    // 部署进度
    var deployProgress by remember { mutableStateOf("") }

    // 进入时检查终端状态
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val st = QuroLinuxEnv.probe(ctx)
            termReady = st.available
        }
    }

    // 探测各环境真实安装状态：用 command -v 逐个检查二进制是否在 proot 内可达。
    // 修复：envStates 此前初始化为空且从未被赋值，导致 Python/SSH/Node 等所有环境
    // 永久显示「未安装」/「未注册」，即便实际已可用（用户看到「未注册（功能正常）」的根因）。
    suspend fun reprobeEnvStates() {
        if (!QuroLinuxEnv.probeLenient(ctx).available) return
        runCatching {
            val probeCmd = "for b in python3 node pnpm ssh java gradle rustc go; do command -v \$b >/dev/null 2>&1 && echo \"\$b:1\" || echo \"\$b:0\"; done"
            val (code, out) = QuroLinuxEnv.run(ctx, probeCmd, timeoutMs = 15000)
            if (code == 0) {
                val m = out.lines().mapNotNull { l ->
                    val p = l.indexOf(':')
                    if (p > 0) l.substring(0, p) to (l.substring(p + 1).trim() == "1") else null
                }.toMap()
                envStates = mapOf(
                    "python" to (m["python3"] == true),
                    "node" to (m["node"] == true),
                    "pnpm" to (m["pnpm"] == true),
                    "ssh" to (m["ssh"] == true),
                    "java" to (m["java"] == true),
                    "gradle" to (m["gradle"] == true),
                    "rust" to (m["rustc"] == true),
                    "go" to (m["go"] == true),
                )
            }
        }
    }

    // 进入即探测真实环境状态
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { reprobeEnvStates() }
    }

    // 简化的环境检查命令
    fun getCheckCommand(envName: String): String {
        return when (envName) {
            "python" -> qstr(R.string.qk_01715)
            "node" -> qstr(R.string.qk_01716)
            "pnpm" -> qstr(R.string.qk_01717)
            "ssh" -> qstr(R.string.qk_01718)
            "java" -> qstr(R.string.qk_01719)
            "gradle" -> qstr(R.string.qk_01720)
            "rust" -> qstr(R.string.qk_01721)
            "go" -> qstr(R.string.qk_01722)
            else -> qstr(R.string.qk_01723)
        }
    }

    // 简化的安装命令
    fun getInstallCommand(envName: String): String {
        return when (envName) {
            "python" -> "apt-get update && apt-get install -y python3 python3-pip python3-venv && python3 -m ensurepip --upgrade 2>&1 | tail -20"
            "node" -> "apt-get update && apt-get install -y nodejs npm 2>&1 | tail -20"
            "pnpm" -> "npm install -g pnpm typescript 2>&1 | tail -20"
            "ssh" -> "apt-get update && apt-get install -y openssh-client openssh-server sshpass 2>&1 | tail -20"
            "java" -> "apt-get update && apt-get install -y openjdk-17-jdk-headless 2>&1 | tail -20"
            "gradle" -> "apt-get update && apt-get install -y gradle 2>&1 | tail -20"
            "rust" -> "curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh -s -- -y 2>&1 | tail -20"
            "go" -> "apt-get update && apt-get install -y golang 2>&1 | tail -20"
            else -> qstr(R.string.qk_01723)
        }
    }

    // 简化的卸载命令
    fun getUninstallCommand(envName: String): String {
        return when (envName) {
            "python" -> "apt-get remove -y python3 python3-pip python3-venv && rm -rf /root/cms-venv 2>&1 | tail -20"
            "node" -> "apt-get remove -y nodejs npm && npm uninstall -g pnpm typescript 2>&1 | tail -20"
            "pnpm" -> "npm uninstall -g pnpm typescript 2>&1 | tail -20"
            "ssh" -> "apt-get remove -y openssh-client openssh-server sshpass 2>&1 | tail -20"
            "java" -> "apt-get remove -y openjdk-17-jdk-headless 2>&1 | tail -20"
            "gradle" -> "apt-get remove -y gradle 2>&1 | tail -20"
            "rust" -> "apt-get remove -y rustc cargo && rm -rf /root/.cargo /root/.rustup 2>&1 | tail -20"
            "go" -> "apt-get remove -y golang && rm -rf /usr/local/go 2>&1 | tail -20"
            else -> qstr(R.string.qk_01723)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.qk_01724), style = MaterialTheme.typography.titleLarge.copy(fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, null) } },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(12.dp)
        ) {
            // 终端状态提示
            if (!termReady) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.errorContainer)
                        .padding(16.dp)
                ) {
                    Text(stringResource(R.string.qk_01725), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onErrorContainer)
                    Spacer(Modifier.height(4.dp))
                    Text(stringResource(R.string.qk_01726), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
                }
            } else {
                Row(Modifier.padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.qk_01727), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = { scope.launch(Dispatchers.IO) { reprobeEnvStates() } }) {
                        Text(stringResource(R.string.qk_01728), fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }

            // 各开发环境分组
            envSections.forEach { section ->
                // 分组标题
                Text(
                    section.title,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    modifier = Modifier.padding(vertical = 8.dp)
                )
                
                // 该分组下的环境卡片
                section.items.forEach { (envName, info) ->
                    val isReady = envStates[envName] ?: false
                    val isDeploying = deploying == envName

                    DevEnvCard(
                        info = info,
                        installCommand = getInstallCommand(envName),
                        checkCommand = getCheckCommand(envName),
                        isReady = isReady,
                        isDeploying = isDeploying,
                        enabled = termReady && !isDeploying,
                        onDeploy = {
                            // 发送安装命令给终端会话
                            val command = getInstallCommand(envName)
                            scope.launch(Dispatchers.IO) {
                                try {
                                    // 确保终端会话存在
                                    val session = QuroTerminalController.createSession(ctx)
                                    withContext(Dispatchers.Main) {
                                        Toast.makeText(ctx, qstr(R.string.qk_01729), Toast.LENGTH_SHORT).show()
                                    }
                                    // 发送安装命令给终端
                                    QuroTerminalController.sendToShell(command)
                                    withContext(Dispatchers.Main) {
                                        deployLogs = deployLogs + qstr(R.string.qk_01730, (envName).toString())
                                    }
                                } catch (e: Exception) {
                                    withContext(Dispatchers.Main) {
                                        deployLogs = deployLogs + qstr(R.string.qk_01731, (e.message).toString())
                                    }
                                }
                            }
                        },
                        onDelete = {
                            // 删除已安装环境
                            scope.launch(Dispatchers.IO) {
                                try {
                                    val deleteCommand = getUninstallCommand(envName)
                                    withContext(Dispatchers.Main) {
                                        Toast.makeText(ctx, qstr(R.string.qk_01732), Toast.LENGTH_SHORT).show()
                                    }
                                    QuroTerminalController.sendToShell(deleteCommand)
                                    withContext(Dispatchers.Main) {
                                        deployLogs = deployLogs + qstr(R.string.qk_01733, (envName).toString())
                                    }
                                } catch (e: Exception) {
                                    withContext(Dispatchers.Main) {
                                        deployLogs = deployLogs + qstr(R.string.qk_01734, (e.message).toString())
                                    }
                                }
                            }
                        },
                        deployProgress = if (isDeploying) deployProgress else "",
                    )
                    Spacer(Modifier.height(6.dp))
                }
                
                Spacer(Modifier.height(4.dp))
            }

            // 一键全部部署
            if (termReady) {
                Spacer(Modifier.height(12.dp))
                var busyAll by remember { mutableStateOf(false) }
                Button(
                    onClick = {
                        busyAll = true
                        scope.launch(Dispatchers.IO) {
                            try {
                                // 确保终端会话存在
                                val session = QuroTerminalController.createSession(ctx)
                                withContext(Dispatchers.Main) {
                                    Toast.makeText(ctx, qstr(R.string.qk_01735), Toast.LENGTH_SHORT).show()
                                }
                                
                                // 发送所有安装命令给终端
                                envSections.forEach { section ->
                                    section.items.forEach { (envName, _) ->
                                        val command = getInstallCommand(envName)
                                        QuroTerminalController.sendToShell(command)
                                        withContext(Dispatchers.Main) {
                                            deployLogs = deployLogs + qstr(R.string.qk_01736, (envName).toString())
                                        }
                                    }
                                }
                                
                                withContext(Dispatchers.Main) {
                                    Toast.makeText(ctx, qstr(R.string.qk_01737), Toast.LENGTH_LONG).show()
                                    deployProgress = ""
                                }
                            } catch (e: Exception) {
                                withContext(Dispatchers.Main) {
                                    deployLogs = deployLogs + qstr(R.string.qk_01731, (e.message).toString())
                                }
                            } finally {
                                withContext(Dispatchers.Main) {
                                    deploying = null
                                    busyAll = false
                                    deployProgress = ""
                                }
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !busyAll && deploying == null,
                ) {
                    Text(if (busyAll) stringResource(R.string.qk_01738) else stringResource(R.string.qk_01739))
                }
            }

            // 部署进度
            if (deploying != null && deployProgress.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    deployProgress,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer)
                        .padding(12.dp),
                )
            }

            // 部署日志
            if (deployLogs.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(stringResource(R.string.qk_01740), style = MaterialTheme.typography.labelLarge)
                    TextButton(
                        onClick = {
                            val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val clip = ClipData.newPlainText(qstr(R.string.qk_01741), deployLogs.joinToString("\n"))
                            clipboard.setPrimaryClip(clip)
                            Toast.makeText(ctx, qstr(R.string.qk_01742), Toast.LENGTH_SHORT).show()
                        }
                    ) {
                        Icon(Icons.Filled.ContentCopy, contentDescription = stringResource(R.string.qk_00088), modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.qk_01743))
                    }
                }
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(12.dp)
                        .heightIn(max = 300.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    deployLogs.forEach { line ->
                        Text(
                            line,
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            color = when {
                                line.startsWith("✅") -> Color(0xFF34C759)
                                line.startsWith("❌") -> MaterialTheme.colorScheme.error
                                line.startsWith("⚠️") -> Color(0xFFFF9500)
                                line.startsWith("---") -> MaterialTheme.colorScheme.primary
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                }
            }
        }
    }
}

/** 开发环境信息。 */
data class DevEnvInfo(
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val name: String,
    val description: String,
    val color: Color,
)

/** 单个开发环境卡片。 */
@Composable
private fun DevEnvCard(
    info: DevEnvInfo,
    installCommand: String,
    checkCommand: String,
    isReady: Boolean,
    isDeploying: Boolean,
    enabled: Boolean,
    onDeploy: () -> Unit,
    onDelete: () -> Unit,
    deployProgress: String = "",
) {
    val ctx = LocalContext.current
    var showCommandBox by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, Line, RoundedCornerShape(12.dp))
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = info.icon,
                contentDescription = info.name,
                tint = info.color,
                modifier = Modifier.size(32.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(info.name, style = MaterialTheme.typography.bodyLarge)
                Text(info.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(4.dp))
                val (statusText, statusColor) = when {
                    isDeploying -> "● 部署中…" to MaterialTheme.colorScheme.primary
                    isReady -> "● 已安装" to Color(0xFF34C759)
                    else -> "○ 未安装" to MaterialTheme.colorScheme.onSurfaceVariant
                }
                Text(statusText, style = MaterialTheme.typography.labelSmall, color = statusColor)
                if (isDeploying && deployProgress.isNotBlank()) {
                    Text(deployProgress, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        // 操作按钮行
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // 发送/安装按钮
            Button(
                onClick = onDeploy,
                modifier = Modifier.weight(1f),
                enabled = enabled,
                colors = if (isReady) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondaryContainer) else ButtonDefaults.buttonColors(),
            ) {
                Text(
                    when {
                        isDeploying -> stringResource(R.string.qk_01738)
                        isReady -> stringResource(R.string.qk_01746)
                        else -> stringResource(R.string.qk_01747)
                    },
                    color = if (isReady) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }

            // 删除按钮
            if (isReady) {
                Button(
                    onClick = onDelete,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                ) {
                    Text(stringResource(R.string.qk_00091), color = MaterialTheme.colorScheme.onErrorContainer)
                }
            }

            // 查看命令按钮
            IconButton(onClick = { showCommandBox = !showCommandBox }) {
                Icon(
                    if (showCommandBox) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = stringResource(R.string.qk_01748),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }

        // 命令框
        if (showCommandBox) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(8.dp)
            ) {
                Text(stringResource(R.string.qk_01749),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    installCommand,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                )
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.qk_01750),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    checkCommand,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                )
            }
        }

        Spacer(Modifier.height(4.dp))
    }
}