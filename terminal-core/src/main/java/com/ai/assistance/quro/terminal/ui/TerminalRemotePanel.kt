package com.ai.assistance.quro.terminal.ui

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.ai.assistance.quro.terminal.R

/**
 * 终端「远程连接」面板（ZorvAI 作为 **客户端**）。
 *
 * 与前面的服务端版本彻底不同：这里不再是「在手机上开 SSH/VNC 服务端、暴露局域网」。
 * 方向是 **ZorvAI 去连远程终端**——填好那台机器（有桌面的那台）的地址 / 账号 / 密码，
 * SSH 进命令行、VNC 看那台机器的图形桌面。
 *
 * - 配置持久化在 SharedPreferences（remote_conn），下次打开自动回填；
 * - 「连接」直接拿配置实例化 [RemoteSshScreen] / [RemoteVncScreen]（底层是真正的
 *   [com.ai.assistance.quro.terminal.utils.SshTerminalClient] /
 *   [com.ai.assistance.quro.terminal.utils.VncClient] 客户端）；
 * - 不再有任何「安装 / 启动 / 探测本机服务」的误导，界面就是「填地址 → 连接」。
 */
@Composable
fun TerminalRemotePanel(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("remote_conn", Context.MODE_PRIVATE) }

    var sshHost by remember { mutableStateOf(prefs.getString("ssh_host", "") ?: "") }
    var sshPort by remember { mutableStateOf(prefs.getString("ssh_port", "22") ?: "22") }
    var sshUser by remember { mutableStateOf(prefs.getString("ssh_user", "root") ?: "root") }
    var sshPass by remember { mutableStateOf(prefs.getString("ssh_pass", "") ?: "") }

    var vncHost by remember { mutableStateOf(prefs.getString("vnc_host", "") ?: "") }
    var vncPort by remember { mutableStateOf(prefs.getString("vnc_port", "5900") ?: "5900") }
    var vncPass by remember { mutableStateOf(prefs.getString("vnc_pass", "") ?: "") }

    var activeConn by remember { mutableStateOf<RemoteConnection?>(null) }

    fun persist(block: android.content.SharedPreferences.Editor.() -> Unit) {
        val e = prefs.edit()
        e.block()
        e.apply()
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
                    stringResource(R.string.remote_client_hint),
                    color = TerminalTheme.onSurfaceVariant,
                    fontSize = 13.sp,
                    lineHeight = 18.sp
                )

                // SSH 连接配置（命令行）
                ConnectionCard(
                    title = stringResource(R.string.remote_ssh),
                    host = sshHost, onHost = { sshHost = it },
                    port = sshPort, onPort = { sshPort = it },
                    user = sshUser, onUser = { sshUser = it },
                    pass = sshPass, onPass = { sshPass = it },
                    showUser = true,
                    onSave = {
                        persist {
                            putString("ssh_host", sshHost.trim())
                            putString("ssh_port", sshPort.trim())
                            putString("ssh_user", sshUser.trim())
                            putString("ssh_pass", sshPass)
                        }
                        Toast.makeText(context, R.string.remote_saved, Toast.LENGTH_SHORT).show()
                    },
                    onConnect = {
                        val port = sshPort.toIntOrNull() ?: 22
                        if (sshHost.isBlank()) {
                            Toast.makeText(context, R.string.remote_invalid_host, Toast.LENGTH_SHORT).show()
                            return@ConnectionCard
                        }
                        activeConn = RemoteConnection(
                            RemoteConnection.Kind.SSH,
                            sshHost.trim(),
                            port,
                            sshUser.trim(),
                            sshPass
                        )
                    },
                )

                // VNC 连接配置（看那台机器的桌面）
                ConnectionCard(
                    title = stringResource(R.string.remote_vnc),
                    host = vncHost, onHost = { vncHost = it },
                    port = vncPort, onPort = { vncPort = it },
                    user = "", onUser = {},
                    pass = vncPass, onPass = { vncPass = it },
                    showUser = false,
                    onSave = {
                        persist {
                            putString("vnc_host", vncHost.trim())
                            putString("vnc_port", vncPort.trim())
                            putString("vnc_pass", vncPass)
                        }
                        Toast.makeText(context, R.string.remote_saved, Toast.LENGTH_SHORT).show()
                    },
                    onConnect = {
                        val port = vncPort.toIntOrNull() ?: 5900
                        if (vncHost.isBlank()) {
                            Toast.makeText(context, R.string.remote_invalid_host, Toast.LENGTH_SHORT).show()
                            return@ConnectionCard
                        }
                        activeConn = RemoteConnection(
                            RemoteConnection.Kind.VNC,
                            vncHost.trim(),
                            port,
                            "",
                            vncPass
                        )
                    },
                )
            }
        }
    }

    // 已连接的会话：全屏显示真实终端 / 桌面画面（不再是 banner）
    activeConn?.let { conn ->
        Dialog(
            onDismissRequest = { activeConn = null },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = TerminalTheme.backgroundColor,
            ) {
                when (conn.kind) {
                    RemoteConnection.Kind.SSH -> RemoteSshScreen(conn) { activeConn = null }
                    RemoteConnection.Kind.VNC -> RemoteVncScreen(conn) { activeConn = null }
                }
            }
        }
    }
}

@Composable
private fun ConnectionCard(
    title: String,
    host: String, onHost: (String) -> Unit,
    port: String, onPort: (String) -> Unit,
    user: String, onUser: (String) -> Unit,
    pass: String, onPass: (String) -> Unit,
    showUser: Boolean,
    onSave: () -> Unit,
    onConnect: () -> Unit,
) {
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

            ConfigField(stringResource(R.string.remote_host), host, onHost, KeyboardType.Text)

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(modifier = Modifier.weight(0.4f)) {
                    ConfigField(stringResource(R.string.remote_port), port, onPort, KeyboardType.Number)
                }
                if (showUser) {
                    Box(modifier = Modifier.weight(0.6f)) {
                        ConfigField(stringResource(R.string.remote_username), user, onUser, KeyboardType.Text)
                    }
                }
            }

            ConfigField(
                stringResource(R.string.remote_password),
                pass,
                onPass,
                KeyboardType.Password,
                isPassword = true
            )

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ActionButton(stringResource(R.string.remote_save_btn), TerminalTheme.elevated) { onSave() }
                ActionButton(stringResource(R.string.remote_connect_btn), TerminalTheme.primaryColor) { onConnect() }
            }
        }
    }
}

@Composable
private fun ConfigField(
    label: String,
    value: String,
    onValue: (String) -> Unit,
    keyboardType: KeyboardType,
    isPassword: Boolean = false,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, color = TerminalTheme.onSurfaceVariant, fontSize = 12.sp)
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp)),
            color = TerminalTheme.terminalBackground,
            shape = RoundedCornerShape(8.dp)
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValue,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 10.dp),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
                visualTransformation = if (isPassword) PasswordVisualTransformation() else VisualTransformation.None,
                textStyle = TextStyle(
                    color = TerminalTheme.onSurfaceColor,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 14.sp,
                ),
                cursorBrush = SolidColor(TerminalTheme.accentColor),
                decorationBox = { inner -> inner() },
            )
        }
    }
}

@Composable
private fun ActionButton(text: String, color: Color, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable { onClick() },
        color = color,
        shape = RoundedCornerShape(10.dp)
    ) {
        Text(
            text,
            color = if (color == TerminalTheme.primaryColor) Color.White else TerminalTheme.onSurfaceColor,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp)
        )
    }
}
