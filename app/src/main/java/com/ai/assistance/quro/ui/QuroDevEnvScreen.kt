package com.ai.assistance.quro.ui

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
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ai.assistance.quro.core.cms.CmsEnvProvisioner
import com.ai.assistance.quro.core.cms.EnvProfile
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

    // 环境分类
    data class EnvSection(val title: String, val items: List<Pair<EnvProfile, DevEnvInfo>>)

    val envSections = listOf(
        EnvSection("Python 环境", listOf(
            EnvProfile.PYTHON to DevEnvInfo(Icons.Filled.Code, "Python 开发环境", "Python 3 + pip + venv + uv 全套", Color(0xFF3776AB)),
            EnvProfile.PYTHON_LINK to DevEnvInfo(Icons.Filled.Link, "Python 链接", "将 python 命令链接到 python3", Color(0xFF3776AB)),
            EnvProfile.PIP to DevEnvInfo(Icons.Filled.Inventory2, "Pip", "Python 包管理器", Color(0xFF3776AB)),
            EnvProfile.UV to DevEnvInfo(Icons.Filled.FlashOn, "UV", "用 Rust 编写的极速 Python 包安装器", Color(0xFFE65100)),
            EnvProfile.VENV to DevEnvInfo(Icons.Filled.FolderOpen, "虚拟环境", "Python 虚拟环境支持", Color(0xFF3776AB)),
        )),
        EnvSection("Node.js 环境", listOf(
            EnvProfile.NODEJS to DevEnvInfo(Icons.Filled.Javascript, "Node.js", "JavaScript 运行时", Color(0xFF339933)),
            EnvProfile.PNPM to DevEnvInfo(Icons.Filled.Apps, "PNPM + TypeScript", "快速的包管理器和 TypeScript", Color(0xFF339933)),
        )),
        EnvSection("SSH 工具", listOf(
            EnvProfile.SSH to DevEnvInfo(Icons.Filled.VpnKey, "SSH 完整工具链", "SSH 客户端 + sshpass + sshd 反向隧道", Color(0xFF0055A5)),
            EnvProfile.SSH_CLIENT to DevEnvInfo(Icons.Filled.Terminal, "SSH 客户端", "SSH 连接客户端", Color(0xFF0055A5)),
            EnvProfile.SSHPASS to DevEnvInfo(Icons.Filled.Password, "sshpass", "SSH 密码认证工具", Color(0xFF0055A5)),
            EnvProfile.SSH_SERVER to DevEnvInfo(Icons.Filled.Dns, "OpenSSH 服务器", "用于反向隧道挂载本地文件系统", Color(0xFF0055A5)),
        )),
        EnvSection("Java 环境", listOf(
            EnvProfile.JAVA to DevEnvInfo(Icons.Filled.Coffee, "Java 完整环境", "OpenJDK 17 + Gradle 构建工具", Color(0xFFE6794A)),
            EnvProfile.OPENJDK17 to DevEnvInfo(Icons.Filled.Coffee, "OpenJDK 17", "Java 17 开发环境", Color(0xFFE6794A)),
            EnvProfile.GRADLE to DevEnvInfo(Icons.Filled.Build, "Gradle", "现代化的构建自动化工具", Color(0xFFE6794A)),
        )),
        EnvSection("Rust 环境", listOf(
            EnvProfile.RUST to DevEnvInfo(Icons.Filled.Memory, "Rust / Cargo", "通过 rustup 安装 Rust 工具链和 Cargo 包管理器", Color(0xFFE65100)),
        )),
        EnvSection("Go 环境", listOf(
            EnvProfile.GO to DevEnvInfo(Icons.Filled.SmartToy, "Go", "Go 编程语言开发环境", Color(0xFF00ADD8)),
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

    // 进入时检查终端和环境状态（不重置正在部署的状态）
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val st = QuroLinuxEnv.probe(ctx)
            termReady = st.available
            if (termReady) {
                val states = mutableMapOf<String, Boolean>()
                envSections.forEach { section ->
                    section.items.forEach { (profile, _) ->
                        // 只检测不在部署中的环境
                        if (deploying != profile.name) {
                            states[profile.name] = CmsEnvProvisioner.isReady(ctx, profile)
                        } else {
                            // 部署中的环境保持当前状态
                            states[profile.name] = envStates[profile.name] ?: false
                        }
                    }
                }
                envStates = states
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("开发环境", style = MaterialTheme.typography.titleLarge.copy(fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold)) },
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
                    Text("⚠️ 终端环境未就绪", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onErrorContainer)
                    Spacer(Modifier.height(4.dp))
                    Text("请先在「终端」页面安装 Linux 环境，再部署开发环境。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
                }
            } else {
                Text("终端环境已就绪 ✓", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(bottom = 8.dp))
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
                section.items.forEach { (profile, info) ->
                    val isReady = envStates[profile.name] ?: false
                    val isDeploying = deploying == profile.name

                    DevEnvCard(
                        info = info,
                        installScript = EnvProfile.getInstallScript(profile),
                        isReady = isReady,
                        isDeploying = isDeploying,
                        enabled = termReady && !isDeploying,
                        onDeploy = {
                            // 发送安装命令给终端会话
                            val script = EnvProfile.getInstallScript(profile)
                            scope.launch(Dispatchers.IO) {
                                try {
                                    // 确保终端会话存在
                                    val session = QuroTerminalController.createSession(ctx)
                                    withContext(Dispatchers.Main) {
                                        Toast.makeText(ctx, "发送安装命令到终端", Toast.LENGTH_SHORT).show()
                                    }
                                    // 发送安装命令给终端
                                    QuroTerminalController.sendToShell(script)
                                    withContext(Dispatchers.Main) {
                                        deployLogs = deployLogs + "✅ 已发送安装命令到终端: ${profile.name}"
                                    }
                                } catch (e: Exception) {
                                    withContext(Dispatchers.Main) {
                                        deployLogs = deployLogs + "❌ 发送命令失败: ${e.message}"
                                    }
                                }
                            }
                        },
                        onDelete = {
                            // 删除已安装环境
                            scope.launch(Dispatchers.IO) {
                                try {
                                    val deleteScript = when (profile) {
                                        EnvProfile.PYTHON, EnvProfile.PYTHON_LINK, EnvProfile.PIP, EnvProfile.UV, EnvProfile.VENV ->
                                            "apt-get remove -y python3 python3-pip python3-venv 2>/dev/null; rm -rf /root/cms-venv /usr/local/bin/python"
                                        EnvProfile.NODEJS, EnvProfile.PNPM ->
                                            "apt-get remove -y nodejs npm 2>/dev/null; npm uninstall -g pnpm typescript 2>/dev/null; rm -rf /usr/local/lib/node_modules /usr/local/bin/pnpm /usr/local/bin/tsc"
                                        EnvProfile.SSH, EnvProfile.SSH_CLIENT, EnvProfile.SSHPASS, EnvProfile.SSH_SERVER ->
                                            "apt-get remove -y openssh-client openssh-server sshpass 2>/dev/null; rm -f /etc/ssh/ssh_host_*_key"
                                        EnvProfile.JAVA, EnvProfile.OPENJDK17, EnvProfile.GRADLE ->
                                            "apt-get remove -y openjdk-17-jdk-headless gradle 2>/dev/null"
                                        EnvProfile.RUST ->
                                            "apt-get remove -y rustc cargo 2>/dev/null; rm -rf /root/.cargo /root/.rustup"
                                        EnvProfile.GO ->
                                            "apt-get remove -y golang-go 2>/dev/null; rm -rf /usr/local/go"
                                        else -> "echo 'No delete script for ${profile.name}'"
                                    }
                                    withContext(Dispatchers.Main) {
                                        Toast.makeText(ctx, "发送删除命令到终端", Toast.LENGTH_SHORT).show()
                                    }
                                    QuroTerminalController.sendToShell(deleteScript)
                                    withContext(Dispatchers.Main) {
                                        deployLogs = deployLogs + "✅ 已发送删除命令: ${profile.name}"
                                    }
                                } catch (e: Exception) {
                                    withContext(Dispatchers.Main) {
                                        deployLogs = deployLogs + "❌ 删除命令发送失败: ${e.message}"
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
                                    Toast.makeText(ctx, "发送全部安装命令到终端", Toast.LENGTH_SHORT).show()
                                }
                                
                                // 发送所有安装命令给终端
                                envSections.forEach { section ->
                                    section.items.forEach { (profile, _) ->
                                        val script = EnvProfile.getInstallScript(profile)
                                        QuroTerminalController.sendToShell(script)
                                        withContext(Dispatchers.Main) {
                                            deployLogs = deployLogs + "✅ 已发送安装命令: ${profile.name}"
                                        }
                                    }
                                }
                                
                                withContext(Dispatchers.Main) {
                                    Toast.makeText(ctx, "全部安装命令已发送到终端", Toast.LENGTH_LONG).show()
                                    deployProgress = ""
                                }
                            } catch (e: Exception) {
                                withContext(Dispatchers.Main) {
                                    deployLogs = deployLogs + "❌ 发送命令失败: ${e.message}"
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
                    Text(if (busyAll) "发送中…" else "一键发送全部安装命令")
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
                    Text("部署日志：", style = MaterialTheme.typography.labelLarge)
                    TextButton(
                        onClick = {
                            val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val clip = ClipData.newPlainText("部署日志", deployLogs.joinToString("\n"))
                            clipboard.setPrimaryClip(clip)
                            Toast.makeText(ctx, "日志已复制到剪贴板", Toast.LENGTH_SHORT).show()
                        }
                    ) {
                        Icon(Icons.Filled.ContentCopy, contentDescription = "复制", modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("复制全部")
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
    installScript: String,
    isReady: Boolean,
    isDeploying: Boolean,
    enabled: Boolean,
    onDeploy: () -> Unit,
    onDelete: () -> Unit,
    deployProgress: String = "",
) {
    val ctx = LocalContext.current
    var showCommandBox by remember { mutableStateOf(false) }
    var editingCommand by remember { mutableStateOf("") }
    var isEditing by remember { mutableStateOf(false) }

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
                        isDeploying -> "发送中…"
                        isReady -> "重新安装"
                        else -> "发送到终端"
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
                    Text("删除", color = MaterialTheme.colorScheme.onErrorContainer)
                }
            }

            // 查看命令按钮
            IconButton(onClick = { showCommandBox = !showCommandBox }) {
                Icon(
                    if (showCommandBox) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = "查看命令",
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
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "安装命令",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row {
                        // 复制按钮
                        IconButton(
                            onClick = {
                                val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                val clip = ClipData.newPlainText("安装命令", installScript)
                                clipboard.setPrimaryClip(clip)
                                Toast.makeText(ctx, "命令已复制", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(Icons.Filled.ContentCopy, "复制", modifier = Modifier.size(16.dp))
                        }
                        // 编辑按钮
                        IconButton(
                            onClick = {
                                isEditing = !isEditing
                                editingCommand = installScript
                            },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                if (isEditing) Icons.Filled.Save else Icons.Filled.Edit,
                                if (isEditing) "保存" else "编辑",
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }

                if (isEditing) {
                    // 编辑模式 - 文本框
                    OutlinedTextField(
                        value = editingCommand,
                        onValueChange = { editingCommand = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 100.dp, max = 200.dp),
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    )
                } else {
                    // 只读模式
                    Text(
                        installScript,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 150.dp)
                            .verticalScroll(rememberScrollState())
                    )
                }
            }
        }

        Spacer(Modifier.height(4.dp))
    }
}
