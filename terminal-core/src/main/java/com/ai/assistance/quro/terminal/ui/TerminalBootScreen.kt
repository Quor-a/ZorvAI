package com.ai.assistance.quro.terminal.ui

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.assistance.quro.terminal.TerminalManager
import com.ai.assistance.quro.terminal.runtime.GuestEnvStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 终端启动引导页（对齐 DeepSeek Harness 的「打开即自动启动」体验）。
 *
 * 历史痛点（用户 2026-11-20）：
 *  - 进入终端首次被直接丢到「环境配置」页，普通用户看不懂、以为坏了；
 *  - 加载界面被人为去掉（TerminalScreen 注释「隐藏加载界面」），rootfs 未就绪时
 *    只有黑屏 / 「未知发行版」，没有任何进度反馈；
 *  - 快照刷新偶发抹掉 rootfs 时（usr/var 被覆盖），用户完全无从判断在等什么。
 *
 * 本页做三件事：
 *  1. **首次进入自动启动**：检测 Ubuntu rootfs，NEEDS_INSTALL/INSTALLING 时自动
 *     调 [TerminalManager.ensureGuestRootfs] 完成零下载解压，无需用户操作；
 *  2. **相位可视**：检测 → 初始化 → 启动 → 就绪 四相位，逐条点亮，绝不黑屏干等；
 *  3. **失败可落地**：ASSET_MISSING（没有内置包）给明确的「去环境配置」按钮。
 *
 * 判定完全基于 [GuestEnvStatus]（纯 rootfs 可用性，无 UI 语义）；解压是阻塞式，
 * 必须在 [Dispatchers.IO] 执行，主线程只收集相位。
 */
@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun TerminalBootScreen(
    manager: TerminalManager,
    onReady: () -> Unit,
    onOpenSetup: () -> Unit,
) {
    var phase by remember { mutableIntStateOf(0) } // 0 检测 1 初始化 2 启动 3 就绪
    var status by remember { mutableStateOf<GuestEnvStatus?>(null) }
    var message by remember { mutableStateOf("正在检测 Linux 运行环境…") }
    var fatal by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }

    // 首次进入即自动启动，无需点击。相位切换间留一点可视化停顿，避免相位闪过看不到。
    LaunchedEffect(Unit) {
        // 🔴 铁律（QuroLinuxEnv.ensureTerminalRootfs 注释早已写明，本页此前违反）：
        // prepareGuestRootfs → common.sh install_ubuntu 依赖 common.sh 与 filesDir 内的
        // Ubuntu tarball，两者都由 initializeEnvironment() 生成/补拷；它还做半截 rootfs
        // 自愈（ensureRootfsComplete）。首次装包或清数据后本页直接 guestEnvStatus() +
        // ensureGuestRootfs() 会绕过它 → tar 未就位 / common.sh 是旧的 / 空目录被
        // install_ubuntu 误判「已安装」→ 用户看到「解压了却找不到系统、要很久」。
        // 因此任何状态判定之前必须先初始化（后台线程，幂等）。
        val initOk = withContext(Dispatchers.IO) { manager.initializeEnvironment() }
        if (!initOk) {
            fatal = true
            message = "运行环境初始化失败，请进入环境配置重试或重装应用。"
            status = GuestEnvStatus.ASSET_MISSING
            return@LaunchedEffect
        }
        val st = withContext(Dispatchers.IO) { manager.guestEnvStatus() }
        status = st
        delay(180)
        when (st) {
            GuestEnvStatus.READY -> {
                phase = 2
                message = "环境已就绪，正在启动终端…"
                delay(180)
                phase = 3
                progress = 1f
                delay(160)
                onReady()
            }
            GuestEnvStatus.NEEDS_INSTALL, GuestEnvStatus.INSTALLING -> {
                phase = 1
                message = "首次启动，正在解压内置 Ubuntu 环境（约 1–3 分钟，请勿退出）…"
                // 确定性进度：解压不可精确计量，用缓慢推进的指示表示「仍在工作」，
                // 完成后直接置满；超时不放弃（install_ubuntu 内部 600s 上限）。
                val advance = launch {
                    while (progress < 0.9f) {
                        delay(700)
                        progress = (progress + 0.02f).coerceAtMost(0.9f)
                    }
                }
                val ok = withContext(Dispatchers.IO) { manager.ensureGuestRootfs() }
                advance.cancel()
                val after = withContext(Dispatchers.IO) { manager.guestEnvStatus() }
                if (ok && after == GuestEnvStatus.READY) {
                    progress = 1f
                    phase = 2
                    message = "初始化完成，正在启动终端…"
                    delay(200)
                    phase = 3
                    delay(180)
                    onReady()
                } else {
                    fatal = true
                    message = "环境初始化未完成，请进入环境配置重试。"
                }
            }
            GuestEnvStatus.ASSET_MISSING -> {
                fatal = true
                phase = 0
                message = "未找到内置 Ubuntu 安装包，请在环境配置中选择镜像源并安装。"
            }
        }
    }

    val bg = TerminalTheme.backgroundColor
    val surface = TerminalTheme.surfaceColor
    val onSurface = TerminalTheme.onSurfaceColor
    val muted = TerminalTheme.onSurfaceVariant
    val accent = TerminalTheme.accentColor
    val success = TerminalTheme.successColor
    val error = TerminalTheme.errorColor

    Box(
        Modifier
            .fillMaxSize()
            .background(bg),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            // 终端图标 + 标题（对齐 DSH 启动页的「品牌 + 名称 + 相位」结构）
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = accent.copy(alpha = 0.12f),
                modifier = Modifier.size(76.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Filled.Terminal,
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(40.dp),
                    )
                }
            }
            Spacer(Modifier.height(20.dp))
            Text(
                "ZorvAI 终端",
                color = onSurface,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "内置 Ubuntu 24.04 · 离线运行环境",
                color = muted,
                fontSize = 13.sp,
            )
            Spacer(Modifier.height(34.dp))

            // 相位卡
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = surface,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(20.dp)) {
                    val steps = listOf(
                        "检测运行环境",
                        "初始化 Ubuntu 环境",
                        "启动终端会话",
                        "就绪",
                    )
                    steps.forEachIndexed { idx, label ->
                        val done = idx < phase || (idx == 3 && phase >= 3)
                        val active = idx == phase && !fatal
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val iconTint = when {
                                fatal && idx == phase -> error
                                done -> success
                                active -> accent
                                else -> muted.copy(alpha = 0.5f)
                            }
                            if (done) {
                                Icon(
                                    Icons.Filled.CheckCircle,
                                    contentDescription = null,
                                    tint = iconTint,
                                    modifier = Modifier.size(22.dp),
                                )
                            } else {
                                Box(
                                    Modifier
                                        .size(22.dp)
                                        .background(iconTint.copy(alpha = 0.18f), RoundedCornerShape(50)),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Box(
                                        Modifier
                                            .size(8.dp)
                                            .background(iconTint, RoundedCornerShape(50)),
                                    )
                                }
                            }
                            Spacer(Modifier.width(12.dp))
                            Text(
                                label,
                                color = if (fatal && idx == phase) error else if (done || active) onSurface else muted,
                                fontSize = 14.sp,
                                fontWeight = if (active || (fatal && idx == phase)) FontWeight.SemiBold else FontWeight.Normal,
                                modifier = Modifier.alpha(if (done || active || (fatal && idx == phase)) 1f else 0.6f),
                            )
                        }
                        if (idx < steps.lastIndex) Spacer(Modifier.height(14.dp))
                    }
                }
            }

            Spacer(Modifier.height(22.dp))
            // 进度条（确定相位用真实值，解压阶段用缓慢推进表示仍在工作）
            val anim by animateFloatAsState(targetValue = progress, animationSpec = tween(400), label = "boot-progress")
            LinearProgressIndicator(
                progress = { anim },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp),
                color = if (fatal) error else accent,
                trackColor = TerminalTheme.divider,
            )
            Spacer(Modifier.height(14.dp))
            Text(
                message,
                color = if (fatal) error else muted,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 8.dp),
            )

            if (fatal) {
                Spacer(Modifier.height(22.dp))
                Button(
                    onClick = onOpenSetup,
                    colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = TerminalTheme.backgroundColor),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text("进入环境配置")
                }
            }
        }
    }
}
