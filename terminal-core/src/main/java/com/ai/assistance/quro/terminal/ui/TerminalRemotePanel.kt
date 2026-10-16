package com.ai.assistance.quro.terminal.ui

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.runtime.collectAsState
import com.ai.assistance.quro.terminal.R
import com.ai.assistance.quro.terminal.runtime.GuestEnvStatus
import com.ai.assistance.quro.terminal.utils.RemoteAccessCenter
import com.ai.assistance.quro.terminal.utils.RemoteAccessManager
import com.ai.assistance.quro.terminal.utils.RemoteServiceState

/**
 * 终端「远程连接」面板：SSH / VNC 访问 Ubuntu 环境。
 *
 * 与后端 [RemoteAccessCenter] 对齐的纯展示层：
 * - 打开时 [RemoteAccessCenter.refresh] 刷新环境、IP、并重新探测两个服务；
 * - 状态全部来自 [RemoteAccessCenter] 的 [androidx.compose.runtime.State]，不依赖任何
 *   终端会话生命周期 —— 弹窗关掉任务仍跑，重开即见进度（旧实现卡「安装中」的根因已根除）；
 * - 「是否在监听」以真实 TCP 探针为准，而不是看进程名或提示符。
 */
@Composable
fun TerminalRemotePanel(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val center = RemoteAccessCenter

    val envStatus by center.envStatus.collectAsState()
    val addresses by center.addresses.collectAsState()
    val sshState by center.sshState.collectAsState()
    val vncState by center.vncState.collectAsState()

    LaunchedEffect(Unit) { center.refresh(context) }

    val envMissing = envStatus == GuestEnvStatus.ASSET_MISSING

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = TerminalTheme.surfaceColor,
            shape = RoundedCornerShape(18.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.terminal_remote_title),
                        color = TerminalTheme.onSurfaceColor,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = onDismiss) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "关闭",
                            tint = TerminalTheme.onSurfaceVariant
                        )
                    }
                }

                Text(
                    stringResource(R.string.terminal_remote_hint),
                    color = TerminalTheme.onSurfaceVariant,
                    fontSize = 13.sp
                )

                // 环境可用性提示（缺 rootfs 时禁用安装按钮 + 引导去环境配置）
                when (envStatus) {
                    GuestEnvStatus.ASSET_MISSING ->
                        EnvBanner(stringResource(R.string.remote_env_missing), TerminalTheme.errorColor)

                    GuestEnvStatus.NEEDS_INSTALL ->
                        EnvBanner(stringResource(R.string.remote_env_needs_install), TerminalTheme.warningColor)

                    else -> { /* READY 或尚未探明：无需横幅 */ }
                }

                IpCard(addresses)

                RemoteSection(
                    kind = RemoteAccessManager.Kind.SSH,
                    title = stringResource(R.string.remote_ssh),
                    state = sshState,
                    envMissing = envMissing,
                    addresses = addresses,
                    onProvision = { center.provision(context, it) },
                    onStop = { center.stop(context, it) },
                )

                RemoteSection(
                    kind = RemoteAccessManager.Kind.VNC,
                    title = stringResource(R.string.remote_vnc),
                    state = vncState,
                    envMissing = envMissing,
                    addresses = addresses,
                    onProvision = { center.provision(context, it) },
                    onStop = { center.stop(context, it) },
                )
            }
        }
    }
}

@Composable
private fun EnvBanner(text: String, color: Color) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = color.copy(alpha = 0.12f),
        shape = RoundedCornerShape(12.dp)
    ) {
        Text(
            text,
            color = color,
            fontSize = 13.sp,
            lineHeight = 18.sp,
            modifier = Modifier.padding(12.dp)
        )
    }
}

@Composable
private fun IpCard(addresses: List<RemoteAccessManager.NetAddr>) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = TerminalTheme.elevated,
        shape = RoundedCornerShape(14.dp)
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                stringResource(R.string.remote_local_ip),
                color = TerminalTheme.onSurfaceVariant,
                fontSize = 13.sp
            )
            Spacer(Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.End) {
                if (addresses.isEmpty()) {
                    Text(
                        "—",
                        color = TerminalTheme.onSurfaceVariant,
                        fontSize = 15.sp,
                        fontFamily = FontFamily.Monospace
                    )
                } else {
                    addresses.forEach { addr ->
                        Text(
                            "${addr.ip}  (${addr.iface})",
                            color = TerminalTheme.accentColor,
                            fontSize = 14.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RemoteSection(
    kind: RemoteAccessManager.Kind,
    title: String,
    state: RemoteServiceState,
    envMissing: Boolean,
    addresses: List<RemoteAccessManager.NetAddr>,
    onProvision: (RemoteAccessManager.Kind) -> Unit,
    onStop: (RemoteAccessManager.Kind) -> Unit,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val copiedText = stringResource(R.string.remote_copied)

    val connectionLines = remember(addresses, kind) { buildConnectionLines(kind, addresses) }
    val busy = state is RemoteServiceState.Working

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = TerminalTheme.surfaceVariant,
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // 标题 + 实时状态胶囊
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title,
                    color = TerminalTheme.onSurfaceColor,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.weight(1f))
                StatusPill(state)
            }

            // 连接信息：点击整块复制到剪贴板
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        clipboard.setText(AnnotatedString(connectionLines.joinToString("\n")))
                        Toast
                            .makeText(context, copiedText, Toast.LENGTH_SHORT)
                            .show()
                    },
                color = TerminalTheme.terminalBackground,
                shape = RoundedCornerShape(8.dp)
            ) {
                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                    connectionLines.forEach { line ->
                        Text(
                            line,
                            color = TerminalTheme.accentColor,
                            fontSize = 13.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        stringResource(R.string.remote_copy_hint),
                        color = TerminalTheme.onSurfaceVariant,
                        fontSize = 11.sp
                    )
                }
            }

            // 运行中：展示服务端真实 banner（sshd / Xvnc 自报的标识）
            if (state is RemoteServiceState.Running) {
                Text(
                    stringResource(R.string.remote_banner_label) + state.banner,
                    color = TerminalTheme.onSurfaceVariant,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )
            }

            // 失败：一句话原因（日志在下方日志框）
            if (state is RemoteServiceState.Failed) {
                Text(
                    state.reason,
                    color = TerminalTheme.errorColor,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                )
            }

            // 操作按钮：运行中→停止；其它→安装并启动（失败态按钮文案变「重试」）
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                when {
                    busy -> {
                        Text(
                            stringResource(R.string.remote_status_working),
                            color = TerminalTheme.onSurfaceVariant,
                            fontSize = 13.sp,
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(TerminalTheme.elevated)
                                .padding(horizontal = 14.dp, vertical = 9.dp)
                        )
                    }

                    state is RemoteServiceState.Running -> {
                        ActionButton(
                            text = stringResource(R.string.remote_stop),
                            color = TerminalTheme.errorVariant
                        ) { onStop(kind) }
                    }

                    else -> {
                        val label =
                            if (state is RemoteServiceState.Failed) stringResource(R.string.remote_retry)
                            else stringResource(R.string.remote_install_start)
                        ActionButton(
                            text = label,
                            color = if (envMissing) TerminalTheme.divider else TerminalTheme.primaryColor,
                            enabled = !envMissing
                        ) { onProvision(kind) }
                    }
                }
            }

            // 进行中：当前步骤提示
            if (state is RemoteServiceState.Working) {
                Text(state.step, color = TerminalTheme.onSurfaceVariant, fontSize = 12.sp)
            }

            // 进行中 / 失败：脚本输出尾巴，便于排查（如密码未生效、端口未起）
            if (state is RemoteServiceState.Working || state is RemoteServiceState.Failed) {
                val log = when (state) {
                    is RemoteServiceState.Working -> state.log
                    is RemoteServiceState.Failed -> state.log
                }
                if (log.isNotEmpty()) LogBox(log)
            }
        }
    }
}

@Composable
private fun StatusPill(state: RemoteServiceState) {
    val text = when (state) {
        RemoteServiceState.Probing -> stringResource(R.string.remote_status_probing)
        is RemoteServiceState.Running -> "● " + stringResource(R.string.remote_status_connected)
        RemoteServiceState.Stopped -> "○ " + stringResource(R.string.remote_status_disconnected)
        is RemoteServiceState.Working -> "● " + stringResource(R.string.remote_status_working)
        is RemoteServiceState.Failed -> "● " + stringResource(R.string.remote_status_failed)
    }
    val color = when (state) {
        RemoteServiceState.Probing -> TerminalTheme.onSurfaceVariant
        is RemoteServiceState.Running -> TerminalTheme.successColor
        RemoteServiceState.Stopped -> TerminalTheme.onSurfaceVariant
        is RemoteServiceState.Working -> TerminalTheme.accentColor
        is RemoteServiceState.Failed -> TerminalTheme.errorColor
    }
    Surface(
        color = color.copy(alpha = 0.14f),
        shape = RoundedCornerShape(999.dp)
    ) {
        Text(
            text,
            color = color,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}

@Composable
private fun ActionButton(
    text: String,
    color: Color,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(enabled = enabled) { onClick() },
        color = if (enabled) color else TerminalTheme.divider,
        shape = RoundedCornerShape(10.dp)
    ) {
        Text(
            text,
            color = if (enabled) Color.White else TerminalTheme.onSurfaceVariant,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp)
        )
    }
}

@Composable
private fun LogBox(log: List<String>) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(120.dp),
        color = TerminalTheme.terminalBackground,
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 10.dp, vertical = 8.dp)
        ) {
            log.forEach { line ->
                Text(
                    line,
                    color = TerminalTheme.onSurfaceVariant,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    lineHeight = 15.sp
                )
            }
        }
    }
}

/**
 * 按网卡列表拼连接串：每个可用地址一行，方便电脑端直接复制。
 * 没有任何地址时退回占位符（仍给出正确端口/密码格式）。
 */
private fun buildConnectionLines(
    kind: RemoteAccessManager.Kind,
    addresses: List<RemoteAccessManager.NetAddr>,
): List<String> {
    val pw = RemoteAccessManager.LOGIN_PASSWORD
    return if (addresses.isEmpty()) {
        when (kind) {
            RemoteAccessManager.Kind.SSH ->
                listOf("ssh root@<本机IP> -p ${RemoteAccessManager.SSH_PORT}  （密码 $pw）")

            RemoteAccessManager.Kind.VNC ->
                listOf("<本机IP>:${RemoteAccessManager.VNC_PORT}  （密码 $pw）")
        }
    } else {
        addresses.map { addr ->
            when (kind) {
                RemoteAccessManager.Kind.SSH ->
                    "ssh root@${addr.ip} -p ${RemoteAccessManager.SSH_PORT}  （密码 $pw）"

                RemoteAccessManager.Kind.VNC ->
                    "${addr.ip}:${RemoteAccessManager.VNC_PORT}  （密码 $pw）"
            }
        }
    }
}
