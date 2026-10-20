package com.ai.assistance.quro.terminal.ui

import android.content.Context
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.assistance.quro.terminal.LanxiuDownloadStatus
import com.ai.assistance.quro.terminal.TerminalEnv
import com.ai.assistance.quro.terminal.desktop.LanxiuDesktop
import com.ai.assistance.quro.terminal.runtime.Distro
import com.ai.assistance.quro.terminal.runtime.GuestEnvStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * LANXIU（澜岫）独立设置界面。
 *
 * 这是 ZorvAI 里 LANXIU 的专属入口，包含：
 * - 状态 / 概览（发行版、rootfs、桌面框架）
 * - 预装工具链清单（OpenSSH / Go / Rust / Node / Python / Weston 桌面 / qystore）
 * - 双系统切换（LANXIU ⇄ Ubuntu）
 * - 图形桌面卡片：启动 / 停止 +「进入桌面」（原生 VNC 渲染）
 */
@Composable
fun LanxiuScreen(
    env: TerminalEnv,
    onBack: () -> Unit,
    onOpenDesktop: () -> Unit
) {
    val context = LocalContext.current.applicationContext
    val scroll = rememberScrollState()

    var deskMsg by remember { mutableStateOf("") }
    var deskRunning by remember { mutableStateOf(false) }
    var deskBusy by remember { mutableStateOf(false) }
    var deskMode by remember { mutableStateOf(LanxiuDesktop.getOutputMode(context)) }

    // 安装时间：versionCode 被锁定（1.1.10/1001010 铁律不改），光看版本号无法区分新旧包；
    // 但 lastUpdateTime 每次覆盖安装都会变 —— 这是判断「设备上到底装没装这个新包」唯一可靠的
    // 宿主侧证据（2026-10-20 因桌面上三个同尺寸包装错，白跑两轮）。
    val installedAt = remember {
        runCatching {
            val pi = context.packageManager.getPackageInfo(context.packageName, 0)
            java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.US)
                .format(java.util.Date(pi.lastUpdateTime))
        }.getOrDefault("未知")
    }

    val lanxiuReady = env.lanxiuEnvStatus() == GuestEnvStatus.READY
    val isSelected = env.selectedDistro == Distro.LANXIU

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(TerminalTheme.backgroundColor)
    ) {
        // 顶栏
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(TerminalTheme.surfaceColor)
                .padding(horizontal = 8.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.Default.ArrowBack,
                    contentDescription = "返回",
                    tint = TerminalTheme.onSurfaceColor
                )
            }
            Text(
                "LANXIU 设置",
                color = TerminalTheme.onSurfaceColor,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.weight(1f))
            Icon(
                Icons.Default.Computer,
                contentDescription = null,
                tint = if (lanxiuReady) TerminalTheme.accentColor else TerminalTheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(end = 12.dp)
                    .width(20.dp)
            )
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(scroll)
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // 状态卡
            val statusText = when {
                !isSelected -> "未选中（当前：${env.selectedDistro.displayName}）"
                env.lanxiuDownloadStatus == LanxiuDownloadStatus.Downloading ->
                    "下载中 ${(env.lanxiuDownloadProgress * 100).toInt()}%"
                lanxiuReady -> "就绪"
                else -> "未就绪（请先下载/初始化）"
            }
            InfoCard(
                title = "LANXIU（澜岫）",
                icon = Icons.Default.Computer,
                lines = listOf(
                    "状态：$statusText",
                    "架构：aarch64 · glibc 2.39",
                    "桌面框架：Weston 14（Wayland）+ qystore 应用商店",
                    "包管理：qypkg（非 apt）"
                )
            )

            if (!isSelected) {
                ActionRow(
                    label = "切换到 LANXIU",
                    onClick = { env.onSelectDistro(Distro.LANXIU) }
                )
            } else if (env.lanxiuDownloadStatus == LanxiuDownloadStatus.Downloading) {
                LinearProgressIndicator(
                    progress = env.lanxiuDownloadProgress,
                    modifier = Modifier.fillMaxWidth(),
                    color = TerminalTheme.accentColor
                )
            } else if (!lanxiuReady) {
                ActionRow(
                    label = "下载并初始化 LANXIU",
                    onClick = { env.onDownloadLanxiu() }
                )
            }

            // 预装工具链
            SectionTitle("预装工具链（随 App 内置）")
            ToolRow("OpenSSH 10.2p1", "sshd 服务端 + ssh/scp/ssh-keygen/ssh-agent + sftp-server")
            ToolRow("Go", "go 工具链 → /usr/local/go")
            ToolRow("Rust 1.83.0", "rustc / cargo → /usr/local/rust")
            ToolRow("Node.js", "node / npm → /usr/local/")
            ToolRow("Python 3.13", "标准库 + pip → site-packages")
            ToolRow("Weston 14 桌面", "Wayland 合成器 + weston-terminal")
            ToolRow("qystore", "澜岫应用商店 / 桌面启动器")

            // 图形桌面卡片
            SectionTitle("图形桌面（原生支持）")
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = TerminalTheme.surfaceColor,
                shape = RoundedCornerShape(14.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.DesktopWindows,
                            contentDescription = null,
                            tint = TerminalTheme.accentColor,
                            modifier = Modifier.width(22.dp)
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            "LANXIU 图形桌面",
                            color = TerminalTheme.onSurfaceColor,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "在 ZorvAI 内原生支持 LANXIU 图形桌面，提供两种输出后端，可随时切换。",
                        color = TerminalTheme.onSurfaceVariant,
                        fontSize = 12.sp,
                        lineHeight = 17.sp
                    )
                    // 输出模式切换（VNC in-app / X11 → Termux:X11）
                    OutputModeToggle(
                        current = deskMode,
                        onSelect = { mode ->
                            deskMode = mode
                            LanxiuDesktop.setOutputMode(context, mode)
                        }
                    )
                    if (deskMode == LanxiuDesktop.OutputMode.X11) {
                        Text(
                            "X11 模式（推荐）：桌面渲染在 Termux:X11 App 窗口，零 rootfs 改动、最稳、立即可用。",
                            color = TerminalTheme.onSurfaceVariant,
                            fontSize = 11.sp,
                            lineHeight = 15.sp
                        )
                    } else {
                        Text(
                            "VNC 模式：App 内原生渲染（Xtigervnc 虚拟 X+VNC，Weston x11-backend 纯软渲），不依赖外部 App、不依赖 GPU，进桌面即可看见。\n· 构建 ${LanxiuDesktop.BUILD_STAMP} · 安装于 $installedAt（-w / getcwd 修复 + 僵尸锁清理）",
                            color = TerminalTheme.onSurfaceVariant,
                            fontSize = 11.sp,
                            lineHeight = 15.sp
                        )
                    }
                    if (deskMsg.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            deskMsg,
                            color = if (deskRunning) TerminalTheme.primaryColor
                            else TerminalTheme.errorColor,
                            fontSize = 12.sp
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Surface(
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .clickable(enabled = lanxiuReady && !deskBusy) {
                                    deskBusy = true
                                    CoroutineScope(Dispatchers.IO).launch {
                                        val r = if (deskRunning) LanxiuDesktop.stop(context)
                                        else LanxiuDesktop.start(context)
                                        deskRunning = r.first == 0
                                        // 失败原因常在末尾（tail 的日志），但「脚本到底有没有进 guest」的证据
                                        // （首行 [lanxiu-boot NAME]）在开头。只取末尾会把前者裁掉，只取开头
                                        // 又会丢掉真正的报错 —— 两头都要：首行标记 + 末尾 6 行。
                                        val lines = r.second.trim().lines().filter { it.isNotBlank() }
                                        val stamp = lines.firstOrNull { it.startsWith("[lanxiu-boot") }
                                        val tailLines = lines.takeLast(6).filter { it != stamp }
                                        val body = (listOfNotNull(stamp) + tailLines).joinToString("\n")
                                        deskMsg = if (r.first == 0) {
                                            body.ifEmpty { "已启动（${LanxiuDesktop.BUILD_STAMP}）" }
                                        } else if (body.isEmpty()) {
                                            "退出码 ${r.first}（无输出）· 构建 ${LanxiuDesktop.BUILD_STAMP}"
                                        } else {
                                            "退出码 ${r.first} · 构建 ${LanxiuDesktop.BUILD_STAMP}\n$body"
                                        }
                                        deskBusy = false
                                    }
                                },
                            color = TerminalTheme.elevated,
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text(
                                if (deskRunning) "停止桌面" else "启动桌面",
                                color = if (deskRunning) TerminalTheme.errorColor
                                else TerminalTheme.onSurfaceColor,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp)
                            )
                        }
                        Surface(
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .clickable(enabled = lanxiuReady) {
                                    if (deskMode == LanxiuDesktop.OutputMode.X11) {
                                        // X11 模式无 App 内 viewer：确保已启动并提示去 Termux:X11
                                        deskBusy = true
                                        CoroutineScope(Dispatchers.IO).launch {
                                            if (LanxiuDesktop.status(context).first != 0) {
                                                val r = LanxiuDesktop.start(context, LanxiuDesktop.OutputMode.X11)
                                                deskMsg = r.second.trim().lines().lastOrNull() ?: r.second.trim()
                                            } else {
                                                deskMsg = "已在 Termux:X11 运行，请切换到 Termux:X11 App 查看桌面"
                                            }
                                            deskBusy = false
                                        }
                                    } else {
                                        onOpenDesktop()
                                    }
                                },
                            color = TerminalTheme.primaryColor,
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text(
                                if (deskMode == LanxiuDesktop.OutputMode.X11) "打开桌面(X11)" else "进入桌面",
                                color = Color.White,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp)
                            )
                        }
                    }
                }
            }

            // 双系统切换
            SectionTitle("双系统")
            DistroSwitch(env = env)
        }
    }
}

@Composable
private fun InfoCard(
    title: String,
    icon: ImageVector,
    lines: List<String>
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = TerminalTheme.surfaceColor,
        shape = RoundedCornerShape(14.dp),
        shadowElevation = 2.dp
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = TerminalTheme.accentColor, modifier = Modifier.width(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(title, color = TerminalTheme.onSurfaceColor, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(8.dp))
            lines.forEach { line ->
                Text(line, color = TerminalTheme.onSurfaceVariant, fontSize = 12.sp, lineHeight = 17.sp)
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        color = TerminalTheme.onSurfaceColor,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 2.dp, bottom = 2.dp)
    )
}

@Composable
private fun ToolRow(name: String, desc: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Default.CheckCircle,
            contentDescription = null,
            tint = TerminalTheme.primaryColor,
            modifier = Modifier.width(16.dp)
        )
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(name, color = TerminalTheme.onSurfaceColor, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Text(desc, color = TerminalTheme.onSurfaceVariant, fontSize = 11.sp, lineHeight = 15.sp)
        }
    }
}

@Composable
private fun OutputModeToggle(
    current: LanxiuDesktop.OutputMode,
    onSelect: (LanxiuDesktop.OutputMode) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        LanxiuDesktop.OutputMode.entries.forEach { mode ->
            val selected = mode == current
            val label = when (mode) {
                LanxiuDesktop.OutputMode.VNC -> "VNC（App 内）"
                LanxiuDesktop.OutputMode.X11 -> "X11（Termux:X11）"
            }
            Surface(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { onSelect(mode) },
                color = if (selected) TerminalTheme.primaryColor else TerminalTheme.elevated,
                shape = RoundedCornerShape(10.dp)
            ) {
                Text(
                    label,
                    color = if (selected) Color.White else TerminalTheme.onSurfaceColor,
                    fontSize = 12.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 9.dp)
                )
            }
        }
    }
}

@Composable
private fun ActionRow(label: String, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable { onClick() },
        color = TerminalTheme.primaryColor,
        shape = RoundedCornerShape(10.dp)
    ) {
        Text(
            label,
            color = Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 11.dp)
        )
    }
}

@Composable
private fun DistroSwitch(env: TerminalEnv) {
    var expanded by remember { mutableStateOf(false) }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable { expanded = true },
        color = TerminalTheme.elevated,
        shape = RoundedCornerShape(10.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "当前发行版：${env.selectedDistro.displayName}",
                color = TerminalTheme.onSurfaceColor,
                fontSize = 13.sp,
                modifier = Modifier.weight(1f)
            )
            Icon(
                Icons.Default.KeyboardArrowDown,
                contentDescription = null,
                tint = TerminalTheme.onSurfaceVariant,
                modifier = Modifier.width(18.dp)
            )
        }
    }
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = { expanded = false },
        containerColor = TerminalTheme.surfaceColor
    ) {
        Distro.entries.forEach { d ->
            DropdownMenuItem(
                text = {
                    Text(
                        d.displayName,
                        color = if (d == env.selectedDistro) TerminalTheme.primaryColor
                        else TerminalTheme.onSurfaceColor,
                        fontSize = 14.sp
                    )
                },
                onClick = {
                    env.onSelectDistro(d)
                    expanded = false
                }
            )
        }
    }
}
