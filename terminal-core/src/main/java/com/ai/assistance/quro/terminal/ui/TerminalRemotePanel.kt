package com.ai.assistance.quro.terminal.ui

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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.ai.assistance.quro.terminal.TerminalManager
import com.ai.assistance.quro.terminal.utils.RemoteAccessManager
import kotlinx.coroutines.launch

/**
 * 终端「远程连接」面板：SSH / VNC 访问 Ubuntu 环境。
 *
 * 命令注入到终端 PTY 内执行（install 后 start），连接信息用本机局域网 IP 拼出。
 */
@Composable
fun TerminalRemotePanel(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val manager = remember { TerminalManager.getInstance(context) }
    val scope = rememberCoroutineScope()
    val ip = remember { RemoteAccessManager.localIpv4(context) }

    fun run(cmds: List<String>) {
        scope.launch {
            cmds.forEach { cmd -> manager.sendCommand(cmd) }
        }
    }

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
                    Column(Modifier.weight(1f)) {
                        Text(
                            context.getString(com.ai.assistance.quro.terminal.R.string.terminal_remote_title),
                            color = TerminalTheme.onSurfaceColor,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "关闭", tint = TerminalTheme.onSurfaceVariant)
                    }
                }

                Text(
                    context.getString(com.ai.assistance.quro.terminal.R.string.terminal_remote_hint),
                    color = TerminalTheme.onSurfaceVariant,
                    fontSize = 13.sp
                )

                // 本机 IP
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
                            context.getString(com.ai.assistance.quro.terminal.R.string.remote_local_ip),
                            color = TerminalTheme.onSurfaceVariant,
                            fontSize = 13.sp
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            ip ?: "—",
                            color = TerminalTheme.accentColor,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }

                // SSH
                RemoteSection(
                    title = context.getString(com.ai.assistance.quro.terminal.R.string.remote_ssh),
                    connectionInfo = RemoteAccessManager.sshConnectionInfo(ip),
                    onInstallStart = {
                        run(listOf(RemoteAccessManager.sshInstallCommand(), RemoteAccessManager.sshStartCommand()))
                    }
                )

                // VNC
                RemoteSection(
                    title = context.getString(com.ai.assistance.quro.terminal.R.string.remote_vnc),
                    connectionInfo = RemoteAccessManager.vncConnectionInfo(ip),
                    onInstallStart = {
                        run(listOf(RemoteAccessManager.vncInstallCommand(), RemoteAccessManager.vncStartCommand()))
                    }
                )
            }
        }
    }
}

@Composable
private fun RemoteSection(
    title: String,
    connectionInfo: String,
    onInstallStart: () -> Unit,
) {
    val context = LocalContext.current
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = TerminalTheme.surfaceVariant,
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                title,
                color = TerminalTheme.onSurfaceColor,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold
            )
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = TerminalTheme.terminalBackground,
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    connectionInfo,
                    color = TerminalTheme.accentColor,
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
                )
            }
            Surface(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { onInstallStart() },
                color = TerminalTheme.primaryColor,
                shape = RoundedCornerShape(10.dp)
            ) {
                Text(
                    context.getString(com.ai.assistance.quro.terminal.R.string.remote_install_start),
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp)
                )
            }
        }
    }
}
