package com.ai.assistance.quro.ui
import androidx.compose.ui.res.stringResource
import com.ai.assistance.quro.R
import com.ai.assistance.quro.util.qstr

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.draw.clip
import com.ai.assistance.quro.core.adb.QuroAdbDebug
import com.ai.assistance.quro.core.approle.DefaultAppRole
import com.ai.assistance.quro.core.approle.QuroDefaultAppManager
import com.ai.assistance.quro.ui.theme.Accent
import com.ai.assistance.quro.ui.theme.Card
import com.ai.assistance.quro.ui.theme.Line
import com.ai.assistance.quro.ui.theme.Muted
import com.ai.assistance.quro.ui.theme.Sage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * USB / 无线调试（ADB）面板（路线图标 ①：设置新增「USB / 无线调试」入口）。
 *
 * 能力闭环（对应用户"完整 ADB"诉求）：
 *  - 控制代码：本应用终端 / 脚本执行（既有）。
 *  - 控制手机：本机以 ADB 客户端对系统发指令（root/Shizuku 静默；否则引导系统授权）。
 *  - 被电脑控制：root/Shizuku 下把 `adbd` 拉成 TCP 监听，展示 `adb connect <ip>:<port>`。
 *  - 被手机控制：本机也能作为 ADB 客户端连其它设备 / 自身（提供连接信息 + 终端入口）。
 *
 * 无提权通道（无 root / Shizuku）时：退回打开系统「无线调试 / 开发者选项」让用户手动配对。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuroUsbDebugScreen(onClose: () -> Unit, onOpenTerminal: () -> Unit = {}) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var probing by remember { mutableStateOf(true) }
    var hasPriv by remember { mutableStateOf(false) }
    var usbOn by remember { mutableStateOf<Boolean?>(null) }
    var usbCable by remember { mutableStateOf<String?>(null) }
    var tcpPort by remember { mutableStateOf(0) }
    var ip by remember { mutableStateOf<String?>(null) }
    var listening by remember { mutableStateOf(false) }
    // 正在控制本机的客户端（解析 /proc/net/tcp 的 ESTABLISHED 连接）与 adb devices 输出
    var clients by remember { mutableStateOf<List<String>>(emptyList()) }
    var adbOut by remember { mutableStateOf<String?>(null) }
    var portText by remember { mutableStateOf(QuroAdbDebug.DEFAULT_PORT.toString()) }
    var busy by remember { mutableStateOf(false) }
    var log by remember { mutableStateOf("") }

    // 本机 ADB Shell（控制代码 / 控制手机）：经特权通道以 root 执行命令
    var shellCmd by remember { mutableStateOf("") }
    var shellOut by remember { mutableStateOf("") }
    var shellBusy by remember { mutableStateOf(false) }

    // 本机作为 ADB 客户端：反向连接对方 ip:port（被手机控制 / 本机作为客户端）
    var clientTarget by remember { mutableStateOf("") }

    // 8 项默认应用角色持有状态（诊断用，随 refresh 刷新）
    var roleHeld by remember { mutableStateOf(mapOf<DefaultAppRole, Boolean>()) }

    // 常用设备控制命令（点按即填入输入框，避免手敲）
    val quickCmds = listOf(
        "getprop ro.build.version.release",
        "ip addr",
        "wm size",
        "settings list system",
        "pm list packages",
        "getenforce",
        "dumpsys battery",
        "svc wifi enable",
    )

    // 设备控制快捷动作（直接执行，服务"控制手机"）：label → 命令（__REBOOT__ 走二次确认）
    val extDir = ctx.getExternalFilesDir(null)?.absolutePath ?: ctx.filesDir.absolutePath
    val deviceActions = listOf(
        "截图" to "screencap -p $extDir/quro_screencap.png",
        "锁屏" to "input keyevent 26",
        "回桌面" to "input keyevent 3",
        "多任务" to "input keyevent 187",
        "音量+" to "input keyevent 24",
        "音量-" to "input keyevent 25",
        "重启" to "__REBOOT__",
    )

    var showRebootConfirm by remember { mutableStateOf(false) }

    fun runShell(cmd: String = shellCmd) {
        val c = cmd.trim()
        if (c.isBlank()) return
        if (!QuroAdbDebug.hasPrivilegedChannel()) {
            Toast.makeText(ctx, qstr(R.string.qk_02980), Toast.LENGTH_LONG).show()
            return
        }
        scope.launch {
            shellBusy = true
            val r = withContext(Dispatchers.IO) { QuroAdbDebug.shell(ctx, c) }
            shellBusy = false
            shellOut = buildString {
                append(shellOut)
                append("$ ")
                append(c)
                append("\n")
                append(r.render())
                append("\n")
            }.takeLast(8000)
        }
    }

    val tcpEnabled = tcpPort > 0

    fun refresh() {
        scope.launch {
            probing = true
            val (priv, usb, port, addr, live, cable, cl, adb) = withContext(Dispatchers.IO) {
                val p = QuroAdbDebug.hasPrivilegedChannel()
                val u = runCatching { QuroAdbDebug.usbDebugEnabled(ctx) }.getOrNull()
                val pt = runCatching { QuroAdbDebug.currentTcpPort(ctx) }.getOrDefault(0)
                val a = runCatching { QuroAdbDebug.wifiIp(ctx) }.getOrNull()
                val l = if (pt > 0) runCatching { QuroAdbDebug.isAdbdListening(ctx, pt) }.getOrDefault(false) else false
                // USB 线实际连接状态（区别于「USB 调试开关」）；谁正连着本机 ADB 端口；adb devices
                val cb = runCatching { QuroAdbDebug.usbCableState(ctx) }.getOrNull()
                val cs = if (pt > 0) runCatching { QuroAdbDebug.connectedClients(ctx, pt) }.getOrDefault(emptyList()) else emptyList()
                val dv = runCatching { QuroAdbDebug.adbDevices(ctx) }.getOrNull()
                Quin(p, u, pt, a, l, cb, cs, dv)
            }
            val roles = withContext(Dispatchers.IO) {
                enumValues<DefaultAppRole>().map { it to QuroDefaultAppManager.isHeld(ctx, it) }.toMap()
            }
            hasPriv = priv
            usbOn = usb
            tcpPort = port
            ip = addr
            listening = live
            usbCable = cable
            clients = cl
            adbOut = adb
            roleHeld = roles
            probing = false
        }
    }

    // 本机作为 ADB 客户端：反向连接对方 ip:port。定义在 refresh() 之后 —— Kotlin 局部函数需先声明后使用。
    fun connectRemote() {
        val t = clientTarget.trim()
        if (t.isBlank()) return
        if (!QuroAdbDebug.hasPrivilegedChannel()) {
            Toast.makeText(ctx, qstr(R.string.qk_02981), Toast.LENGTH_LONG).show()
            return
        }
        scope.launch {
            shellBusy = true
            val r = withContext(Dispatchers.IO) { QuroAdbDebug.shell(ctx, "adb connect $t") }
            shellBusy = false
            shellOut = buildString {
                append(shellOut)
                append("$ adb connect ")
                append(t)
                append("\n")
                append(r.render())
                append("\n")
            }.takeLast(8000)
            // 连接结果会改变 adb devices / 连接列表，执行后回查一次
            refresh()
        }
    }

    LaunchedEffect(Unit) { refresh() }

    fun copy(text: String) {
        runCatching {
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("adb", text))
            Toast.makeText(ctx, qstr(R.string.qk_02982, (text).toString()), Toast.LENGTH_SHORT).show()
        }
    }

    fun share(text: String) {
        runCatching {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ctx.startActivity(Intent.createChooser(intent, qstr(R.string.qk_02983)))
        }
    }

    fun shareDiagnostic() {
        val sb = StringBuilder()
        sb.appendLine(qstr(R.string.qk_02984))
        sb.appendLine("提权通道: ${if (hasPriv) "可用(root/Shizuku)" else "无"}")
        sb.appendLine("USB 调试: ${when (usbOn) { null -> "未知"; true -> "开"; false -> "关" }}")
        sb.appendLine("USB 数据线: ${usbCable ?: "未知"}")
        sb.appendLine("TCP ADB: ${if (tcpPort > 0) "监听 $tcpPort${if (listening) " (已监听)" else " (未监听)"}" else "未启用"}")
        sb.appendLine("WiFi IP: ${ip ?: "无"}")
        sb.appendLine("连接命令: ${if (tcpPort > 0 && ip != null) "adb connect $ip:$tcpPort" else "—"}")
        sb.appendLine("已连接客户端: ${clients.joinToString(", ").ifEmpty { "无" }}")
        sb.appendLine("adb devices: ${adbOut?.replace('\n', ';') ?: "宿主无 adb 客户端二进制"}")
        sb.appendLine(qstr(R.string.qk_02992))
        for ((r, h) in roleHeld) sb.appendLine("  ${r.label}: ${if (h) "已设为默认" else "未设"}")
        share(sb.toString())
    }

    fun onToggle(newVal: Boolean) {
        if (!QuroAdbDebug.hasPrivilegedChannel()) {
            Toast.makeText(ctx, qstr(R.string.qk_02993), Toast.LENGTH_LONG).show()
            QuroAdbDebug.openWirelessDebugging(ctx)
            return
        }
        scope.launch {
            busy = true
            val portNum = portText.toIntOrNull()?.coerceIn(1, 65535) ?: QuroAdbDebug.DEFAULT_PORT
            val r = withContext(Dispatchers.IO) { QuroAdbDebug.setTcpAdb(ctx, newVal, portNum) }
            busy = false
            log = if (r.success) {
                if (newVal) qstr(R.string.qk_02994, (portNum).toString(), (r.output).toString()) else qstr(R.string.qk_02995, (r.output).toString())
            } else qstr(R.string.qk_02996, (r.render()).toString())
            Toast.makeText(ctx, if (r.success) (if (newVal) qstr(R.string.qk_02997) else qstr(R.string.qk_02998)) else qstr(R.string.qk_01012), Toast.LENGTH_SHORT).show()
            // adbd 重启后要等一两秒再探监听状态
            delay(1500)
            refresh()
        }
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        // 顶部条
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) { Icon(Icons.Filled.ArrowBack, contentDescription = stringResource(R.string.qk_00143), tint = MaterialTheme.colorScheme.onSurface) }
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.qk_02999), fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.weight(1f))
            val cs = MaterialTheme.colorScheme
            Box(
                Modifier.clip(RoundedCornerShape(8.dp))
                    .background(if (hasPriv) Accent else Card)
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            ) {
                Text(
                    if (probing) stringResource(R.string.qk_01688) else if (hasPriv) stringResource(R.string.qk_03000) else stringResource(R.string.qk_03001),
                    fontSize = 12.sp, color = if (hasPriv) Color.White else cs.onSurfaceVariant,
                )
            }
            if (!probing) {
                Spacer(Modifier.width(8.dp))
                IconButton(onClick = { refresh() }) { Icon(Icons.Filled.Sync, contentDescription = stringResource(R.string.qk_00459), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
        HorizontalDivider(color = Line)

        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            GroupCaption(stringResource(R.string.qk_03002))
            SetGroup {
                StatusRow(Icons.Filled.Shield, stringResource(R.string.qk_03003), stringResource(R.string.qk_03004), hasPriv)
                HorizontalDivider(color = Line, thickness = 1.dp, modifier = Modifier.padding(horizontal = 12.dp))
                StatusRow(
                    Icons.Filled.Usb, stringResource(R.string.qk_03005),
                    stringResource(R.string.qk_03006),
                    usbOn ?: false,
                    unknown = usbOn == null,
                )
                HorizontalDivider(color = Line, thickness = 1.dp, modifier = Modifier.padding(horizontal = 12.dp))
                TextStateRow(
                    Icons.Filled.Cable, stringResource(R.string.qk_03007),
                    stringResource(R.string.qk_03008),
                    usbCable,
                )
                HorizontalDivider(color = Line, thickness = 1.dp, modifier = Modifier.padding(horizontal = 12.dp))
                StatusRow(
                    Icons.Filled.Router,
                    "TCP ADB",
                    if (tcpPort > 0) "监听端口 $tcpPort${if (listening) " · 已监听" else " · 未监听"}" else stringResource(R.string.qk_03010),
                    tcpEnabled,
                )
            }

            GroupCaption(stringResource(R.string.qk_03011))
            SetGroup {
                SetRow(
                    Icons.Filled.Wifi, stringResource(R.string.qk_03012),
                    if (hasPriv) stringResource(R.string.qk_03013) else stringResource(R.string.qk_03014),
                    tcpEnabled,
                    onToggle = { onToggle(!tcpEnabled) },
                    scaled = { it.sp },
                )
                if (hasPriv) {
                    HorizontalDivider(color = Line, thickness = 1.dp, modifier = Modifier.padding(horizontal = 12.dp))
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedTextField(
                            value = portText,
                            onValueChange = { portText = it.filter { c -> c.isDigit() }.take(5) },
                            label = { Text(stringResource(R.string.qk_00936), fontSize = 12.sp) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier.width(120.dp),
                            enabled = !busy,
                        )
                        Spacer(Modifier.width(12.dp))
                        if (busy) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.qk_00117), fontSize = 12.sp, color = Muted)
                        } else {
                            Text(stringResource(R.string.qk_03015), fontSize = 12.sp, color = Muted)
                        }
                    }
                }
            }

            // 连接信息：被电脑控制 / 被手机控制（同一 TCP adbd，控制端可是电脑也可是另一台手机）
            if (tcpEnabled && ip != null) {
                GroupCaption(stringResource(R.string.qk_03016))
                SetGroup {
                    val cmd = "adb connect $ip:$tcpPort"
                    ConnectInfoRow(
                        cmd,
                        stringResource(R.string.qk_03017),
                        onCopy = { copy(cmd) },
                        onShare = { share(cmd) },
                    )
                    HorizontalDivider(color = Line, thickness = 1.dp, modifier = Modifier.padding(horizontal = 12.dp))
                    val qrBitmap = remember(ip, tcpPort) {
                        try {
                            val hints = hashMapOf<com.google.zxing.EncodeHintType, Any>(
                                com.google.zxing.EncodeHintType.MARGIN to 1,
                                com.google.zxing.EncodeHintType.ERROR_CORRECTION to com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.M,
                            )
                            val matrix = com.google.zxing.qrcode.QRCodeWriter().encode(cmd, com.google.zxing.BarcodeFormat.QR_CODE, 400, 400, hints)
                            val w = matrix.width
                            val h = matrix.height
                            val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
                            for (x in 0 until w) {
                                for (y in 0 until h) {
                                    bmp.setPixel(x, y, if (matrix.get(x, y)) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
                                }
                            }
                            bmp
                        } catch (e: Exception) {
                            null
                        }
                    }
                    if (qrBitmap != null) {
                        Box(Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.Center) {
                            androidx.compose.foundation.Image(
                                bitmap = qrBitmap.asImageBitmap(),
                                contentDescription = stringResource(R.string.qk_03018),
                                modifier = Modifier.size(180.dp).clip(RoundedCornerShape(8.dp)).background(Color.White).padding(12.dp),
                            )
                        }
                        Text(stringResource(R.string.qk_03019), fontSize = 11.sp, color = Muted, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp))
                    }
                }
            } else if (tcpEnabled && ip == null) {
                GroupCaption(stringResource(R.string.qk_03020))
                SetGroup {
                    InfoLine(stringResource(R.string.qk_03021))
                }
            }

            // 谁正在控制本机：解析 /proc/net/tcp 中与 ADB 端口 ESTABLISHED 的连接（补齐「被控制」闭环）
            if (tcpEnabled) {
                GroupCaption(stringResource(R.string.qk_03022))
                SetGroup {
                    if (clients.isEmpty()) {
                        InfoLine(stringResource(R.string.qk_03023, (ip).toString(), (tcpPort).toString()))
                    } else {
                        clients.forEach { c -> InfoLine("● $c") }
                        HorizontalDivider(color = Line, thickness = 1.dp, modifier = Modifier.padding(horizontal = 12.dp))
                        InfoLine(stringResource(R.string.qk_03024, (tcpPort).toString()))
                    }
                    if (!probing) {
                        HorizontalDivider(color = Line, thickness = 1.dp, modifier = Modifier.padding(horizontal = 12.dp))
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(stringResource(R.string.qk_03025), fontSize = 12.sp, color = Muted, modifier = Modifier.weight(1f))
                            TextButton(onClick = { refresh() }) { Text(stringResource(R.string.qk_00459), fontSize = 13.sp, color = Accent) }
                        }
                    }
                }
            }

            // 本机作为 ADB 客户端：反向连接对方（被手机控制 / 本机作为客户端）
            GroupCaption(stringResource(R.string.qk_03026))
            SetGroup {
                InfoLine(stringResource(R.string.qk_03027, (tcpPort).toString()))
                HorizontalDivider(color = Line, thickness = 1.dp, modifier = Modifier.padding(horizontal = 12.dp))
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = clientTarget,
                        onValueChange = { clientTarget = it.filter { c -> c.isDigit() || c == '.' || c == ':' } },
                        label = { Text(stringResource(R.string.qk_03028), fontSize = 12.sp) },
                        placeholder = { Text(stringResource(R.string.qk_03029), fontSize = 12.sp, color = Muted) },
                        singleLine = true,
                        enabled = !shellBusy,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { connectRemote() }),
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = { connectRemote() },
                        enabled = !shellBusy && clientTarget.isNotBlank(),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                    ) {
                        if (shellBusy) Text(stringResource(R.string.qk_03030), fontSize = 13.sp) else Text(stringResource(R.string.qk_02111), fontSize = 13.sp)
                    }
                }
                HorizontalDivider(color = Line, thickness = 1.dp, modifier = Modifier.padding(horizontal = 12.dp))
                // 此前是弹 Toast 的占位行，改为真正跳到终端页
                SetRowClickable(
                    Icons.Filled.Terminal, stringResource(R.string.qk_03031), stringResource(R.string.qk_03032), "",
                    onClick = { onOpenTerminal() },
                    scaled = { it.sp },
                )
                if (adbOut != null) {
                    HorizontalDivider(color = Line, thickness = 1.dp, modifier = Modifier.padding(horizontal = 12.dp))
                    InfoLine("adb devices -l：\n$adbOut")
                } else {
                    HorizontalDivider(color = Line, thickness = 1.dp, modifier = Modifier.padding(horizontal = 12.dp))
                    InfoLine(stringResource(R.string.qk_03033))
                }
            }

            GroupCaption(stringResource(R.string.qk_03034))
            SetGroup {
                SetRowClickable(
                    Icons.Filled.DeveloperMode, stringResource(R.string.qk_03035), stringResource(R.string.qk_03036), "",
                    onClick = { QuroAdbDebug.openDeveloperOptions(ctx) }, scaled = { it.sp },
                )
                HorizontalDivider(color = Line, thickness = 1.dp, modifier = Modifier.padding(horizontal = 12.dp))
                SetRowClickable(
                    Icons.Filled.Wifi, stringResource(R.string.qk_03037), stringResource(R.string.qk_03038), "",
                    onClick = { QuroAdbDebug.openWirelessDebugging(ctx) }, scaled = { it.sp },
                )
            }

            // 本机 ADB Shell：经特权通道以 root 执行命令（控制代码 / 控制手机）
            GroupCaption(stringResource(R.string.qk_03039))
            SetGroup {
                // 常用命令快捷芯片：点按即填入输入框
                LazyRow(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(quickCmds) { cmd ->
                        AssistChip(
                            onClick = { shellCmd = cmd },
                            label = { Text(cmd, fontSize = 11.sp) },
                        )
                    }
                }
                HorizontalDivider(color = Line, thickness = 1.dp, modifier = Modifier.padding(horizontal = 12.dp))
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = shellCmd,
                        onValueChange = { shellCmd = it },
                        label = { Text(stringResource(R.string.qk_03040), fontSize = 12.sp) },
                        placeholder = { Text(stringResource(R.string.qk_03041), fontSize = 12.sp, color = Muted) },
                        singleLine = true,
                        enabled = !shellBusy,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { runShell() }),
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = { runShell() },
                        enabled = !shellBusy && shellCmd.isNotBlank(),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                    ) {
                        if (shellBusy) Text(stringResource(R.string.qk_00117), fontSize = 13.sp) else Text(stringResource(R.string.qk_01642), fontSize = 13.sp)
                    }
                }
                if (shellOut.isNotBlank()) {
                    HorizontalDivider(color = Line, thickness = 1.dp, modifier = Modifier.padding(horizontal = 12.dp))
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(stringResource(R.string.qk_02865), fontSize = 12.sp, color = Muted, modifier = Modifier.weight(1f))
                        TextButton(onClick = { copy(shellOut) }) { Text(stringResource(R.string.qk_00088), fontSize = 12.sp, color = Accent) }
                        TextButton(onClick = { shellOut = "" }) { Text(stringResource(R.string.qk_00764), fontSize = 12.sp, color = Accent) }
                    }
                    Box(
                        Modifier.fillMaxWidth().heightIn(max = 200.dp)
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        Text(shellOut, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary, fontFamily = FontFamily.Monospace)
                    }
                    Spacer(Modifier.height(6.dp))
                }
            }

            // 设备控制快捷动作：直接执行高频"控制手机"指令
            GroupCaption(stringResource(R.string.qk_03042))
            SetGroup {
                LazyRow(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(deviceActions) { (label, cmd) ->
                        AssistChip(
                            onClick = {
                                if (cmd == "__REBOOT__") showRebootConfirm = true
                                else runShell(cmd)
                            },
                            label = { Text(label, fontSize = 11.sp) },
                        )
                    }
                }
                HorizontalDivider(color = Line, thickness = 1.dp, modifier = Modifier.padding(horizontal = 12.dp))
                InfoLine(stringResource(R.string.qk_03043, (extDir).toString()))
            }

            if (log.isNotBlank()) {
                GroupCaption(stringResource(R.string.qk_03044))
                SetGroup {
                    Text(
                        log, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                }
            }

            // 诊断：无需 adb，在手机上即可确认 ADB 与默认应用状态
            GroupCaption(stringResource(R.string.qk_03045))
            SetGroup {
                InfoLine(stringResource(R.string.qk_03046))
                HorizontalDivider(color = Line, thickness = 1.dp, modifier = Modifier.padding(horizontal = 12.dp))
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Button(
                        onClick = { shareDiagnostic() },
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                    ) {
                        Text(stringResource(R.string.qk_03047), fontSize = 13.sp)
                    }
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = { refresh() }) { Text(stringResource(R.string.qk_00459), fontSize = 13.sp, color = Accent) }
                }
            }

            GroupCaption(stringResource(R.string.qk_01691))
            SetGroup {
                InfoLine(stringResource(R.string.qk_03048))
                InfoLine(stringResource(R.string.qk_03049))
                InfoLine(stringResource(R.string.qk_03050))
            }
        }
    }

    if (showRebootConfirm) {
        AlertDialog(
            onDismissRequest = { showRebootConfirm = false },
            title = { Text(stringResource(R.string.qk_03051)) },
            text = { Text(stringResource(R.string.qk_03052)) },
            confirmButton = {
                TextButton(onClick = { showRebootConfirm = false; runShell("reboot") }) {
                    Text(qstr(R.string.qk_02979), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showRebootConfirm = false }) { Text(stringResource(R.string.qk_00011)) }
            },
        )
    }
}

/** 状态行：图标 + 名称/副标题 + 开关式状态徽标。 */
@Composable
private fun StatusRow(icon: androidx.compose.ui.graphics.vector.ImageVector, name: String, sub: String, on: Boolean, unknown: Boolean = false) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, Modifier.size(20.dp), tint = cs.onSurfaceVariant)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(name, fontSize = 14.sp, color = cs.onSurface)
            Text(sub, fontSize = 11.sp, color = Muted, modifier = Modifier.padding(top = 2.dp))
        }
        val (txt, col) = when {
            unknown -> "未知" to Muted
            on -> "已开启" to Sage
            else -> "未开启" to Muted
        }
        Box(Modifier.clip(RoundedCornerShape(20.dp)).background(col.copy(alpha = 0.15f)).padding(horizontal = 10.dp, vertical = 4.dp)) {
            Text(txt, color = col, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** 状态行：图标 + 名称/副标题 + 自定义值徽标（用于 USB 线连接等非「开/关」语义的状态）。 */
@Composable
private fun TextStateRow(icon: androidx.compose.ui.graphics.vector.ImageVector, name: String, sub: String, value: String?) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, Modifier.size(20.dp), tint = cs.onSurfaceVariant)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(name, fontSize = 14.sp, color = cs.onSurface)
            Text(sub, fontSize = 11.sp, color = Muted, modifier = Modifier.padding(top = 2.dp))
        }
        val connected = value?.startsWith(stringResource(R.string.qk_00771)) == true
        val col = if (connected) Sage else Muted
        Box(Modifier.clip(RoundedCornerShape(20.dp)).background(col.copy(alpha = 0.15f)).padding(horizontal = 10.dp, vertical = 4.dp)) {
            Text(value ?: stringResource(R.string.qk_00472), color = col, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** 连接信息行：可复制 / 可分享的命令 + 说明。 */
@Composable
private fun ConnectInfoRow(cmd: String, desc: String, onCopy: () -> Unit, onShare: (() -> Unit)? = null) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.qk_03054), fontSize = 13.sp, color = cs.onSurface, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            if (onShare != null) {
                TextButton(onClick = onShare) { Text(stringResource(R.string.qk_00090), fontSize = 13.sp, color = Accent, fontWeight = FontWeight.SemiBold) }
            }
            TextButton(onClick = onCopy) { Text(stringResource(R.string.qk_00088), fontSize = 13.sp, color = Accent, fontWeight = FontWeight.SemiBold) }
        }
        Spacer(Modifier.height(6.dp))
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                .background(cs.surfaceVariant.copy(alpha = 0.6f))
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Text(cmd, fontSize = 13.sp, color = cs.primary, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
        }
        Spacer(Modifier.height(6.dp))
        Text(desc, fontSize = 11.sp, color = Muted)
    }
}

/** 纯文本说明行。 */
@Composable
private fun InfoLine(text: String) {
    Text(
        text, fontSize = 12.sp, color = Muted, lineHeight = 18.sp,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
    )
}

/** 状态探针聚合（IO 线程一次性取回，避免多次重组）。 */
private data class Quin(
    val priv: Boolean,
    val usb: Boolean?,
    val port: Int,
    val ip: String?,
    val listening: Boolean,
    val cable: String?,
    val clients: List<String>,
    val adb: String?,
)