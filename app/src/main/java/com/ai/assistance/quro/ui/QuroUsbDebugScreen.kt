package com.ai.assistance.quro.ui

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
fun QuroUsbDebugScreen(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var probing by remember { mutableStateOf(true) }
    var hasPriv by remember { mutableStateOf(false) }
    var usbOn by remember { mutableStateOf<Boolean?>(null) }
    var tcpPort by remember { mutableStateOf(0) }
    var ip by remember { mutableStateOf<String?>(null) }
    var listening by remember { mutableStateOf(false) }
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
            Toast.makeText(ctx, "需要 root / Shizuku 才能执行本机 ADB shell", Toast.LENGTH_LONG).show()
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

    fun connectRemote() {
        val t = clientTarget.trim()
        if (t.isBlank()) return
        if (!QuroAdbDebug.hasPrivilegedChannel()) {
            Toast.makeText(ctx, "需要 root / Shizuku 才能执行 adb connect", Toast.LENGTH_LONG).show()
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
        }
    }

    val tcpEnabled = tcpPort > 0

    fun refresh() {
        scope.launch {
            probing = true
            val (priv, usb, port, addr, live) = withContext(Dispatchers.IO) {
                val p = QuroAdbDebug.hasPrivilegedChannel()
                val u = runCatching { QuroAdbDebug.usbDebugEnabled(ctx) }.getOrNull()
                val pt = runCatching { QuroAdbDebug.currentTcpPort(ctx) }.getOrDefault(0)
                val a = runCatching { QuroAdbDebug.wifiIp(ctx) }.getOrNull()
                val l = if (pt > 0) runCatching { QuroAdbDebug.isAdbdListening(ctx, pt) }.getOrDefault(false) else false
                Quin(p, u, pt, a, l)
            }
            val roles = withContext(Dispatchers.IO) {
                enumValues<DefaultAppRole>().map { it to QuroDefaultAppManager.isHeld(ctx, it) }.toMap()
            }
            hasPriv = priv
            usbOn = usb
            tcpPort = port
            ip = addr
            listening = live
            roleHeld = roles
            probing = false
        }
    }

    LaunchedEffect(Unit) { refresh() }

    fun copy(text: String) {
        runCatching {
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("adb", text))
            Toast.makeText(ctx, "已复制：$text", Toast.LENGTH_SHORT).show()
        }
    }

    fun share(text: String) {
        runCatching {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ctx.startActivity(Intent.createChooser(intent, "分享连接命令"))
        }
    }

    fun shareDiagnostic() {
        val sb = StringBuilder()
        sb.appendLine("QuroAI ADB / 默认应用诊断")
        sb.appendLine("提权通道: ${if (hasPriv) "可用(root/Shizuku)" else "无"}")
        sb.appendLine("USB 调试: ${when (usbOn) { null -> "未知"; true -> "开"; false -> "关" }}")
        sb.appendLine("TCP ADB: ${if (tcpPort > 0) "监听 $tcpPort${if (listening) " (已监听)" else ""}" else "未启用"}")
        sb.appendLine("WiFi IP: ${ip ?: "无"}")
        sb.appendLine("连接命令: ${if (tcpPort > 0 && ip != null) "adb connect $ip:$tcpPort" else "—"}")
        sb.appendLine("默认应用角色:")
        for ((r, h) in roleHeld) sb.appendLine("  ${r.label}: ${if (h) "已设为默认" else "未设"}")
        share(sb.toString())
    }

    fun onToggle(newVal: Boolean) {
        if (!QuroAdbDebug.hasPrivilegedChannel()) {
            Toast.makeText(ctx, "无 root/Shizuku：改用系统无线调试配对", Toast.LENGTH_LONG).show()
            QuroAdbDebug.openWirelessDebugging(ctx)
            return
        }
        scope.launch {
            busy = true
            val portNum = portText.toIntOrNull()?.coerceIn(1, 65535) ?: QuroAdbDebug.DEFAULT_PORT
            val r = withContext(Dispatchers.IO) { QuroAdbDebug.setTcpAdb(ctx, newVal, portNum) }
            busy = false
            log = if (r.success) {
                if (newVal) "✅ 已启用 TCP ADB，监听端口 $portNum\n${r.output}" else "✅ 已关闭 TCP ADB\n${r.output}"
            } else "❌ 执行失败：${r.render()}"
            Toast.makeText(ctx, if (r.success) (if (newVal) "已启用无线 ADB" else "已关闭") else "执行失败", Toast.LENGTH_SHORT).show()
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
            IconButton(onClick = onClose) { Icon(Icons.Filled.ArrowBack, contentDescription = "返回", tint = MaterialTheme.colorScheme.onSurface) }
            Spacer(Modifier.width(8.dp))
            Text("USB / 无线调试 (ADB)", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.weight(1f))
            val cs = MaterialTheme.colorScheme
            Box(
                Modifier.clip(RoundedCornerShape(8.dp))
                    .background(if (hasPriv) Accent else Card)
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            ) {
                Text(
                    if (probing) "探测中…" else if (hasPriv) "可提权" else "无提权",
                    fontSize = 12.sp, color = if (hasPriv) Color.White else cs.onSurfaceVariant,
                )
            }
            if (!probing) {
                Spacer(Modifier.width(8.dp))
                IconButton(onClick = { refresh() }) { Icon(Icons.Filled.Sync, contentDescription = "刷新", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
        HorizontalDivider(color = Line)

        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            GroupCaption("通道状态")
            SetGroup {
                StatusRow(Icons.Filled.Shield, "提权通道", "root 或 Shizuku 可用于启动 TCP adbd", hasPriv)
                HorizontalDivider(color = Line, thickness = 1.dp, modifier = Modifier.padding(horizontal = 12.dp))
                StatusRow(
                    Icons.Filled.Usb, "USB 调试",
                    "系统开发者选项里的 USB 调试开关",
                    usbOn ?: false,
                    unknown = usbOn == null,
                )
                HorizontalDivider(color = Line, thickness = 1.dp, modifier = Modifier.padding(horizontal = 12.dp))
                StatusRow(
                    Icons.Filled.Router,
                    "TCP ADB",
                    if (tcpPort > 0) "监听端口 $tcpPort${if (listening) " · 已监听" else " · 未监听"}" else "未启用",
                    tcpEnabled,
                )
            }

            GroupCaption("无线 ADB（被电脑控制）")
            SetGroup {
                SetRow(
                    Icons.Filled.Wifi, "启用无线 ADB (TCP)",
                    if (hasPriv) "root/Shizuku 下启动 adbd 监听，电脑可连接" else "无提权：点此打开系统无线调试配对",
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
                            label = { Text("端口", fontSize = 12.sp) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier.width(120.dp),
                            enabled = !busy,
                        )
                        Spacer(Modifier.width(12.dp))
                        if (busy) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text("执行中…", fontSize = 12.sp, color = Muted)
                        } else {
                            Text("默认 5555，与 adb tcpip 对齐", fontSize = 12.sp, color = Muted)
                        }
                    }
                }
            }

            // 连接信息：被电脑控制 / 被手机控制（同一 TCP adbd，控制端可是电脑也可是另一台手机）
            if (tcpEnabled && ip != null) {
                GroupCaption("电脑/手机连接本机（被控制）")
                SetGroup {
                    val cmd = "adb connect $ip:$tcpPort"
                    ConnectInfoRow(
                        cmd,
                        "在电脑或另一台手机的终端执行该命令即可连接本机；连接后对方即可控制本机（安装/卸载/截屏/文件/Shell）",
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
                                contentDescription = "adb connect 二维码",
                                modifier = Modifier.size(180.dp).clip(RoundedCornerShape(8.dp)).background(Color.White).padding(12.dp),
                            )
                        }
                        Text("扫码即得连接命令（另一台手机/电脑相机扫码后可复制），实现被手机/电脑控制", fontSize = 11.sp, color = Muted, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp))
                    }
                }
            } else if (tcpEnabled && ip == null) {
                GroupCaption("电脑连接本机")
                SetGroup {
                    InfoLine("TCP ADB 已启用，但未检测到 WiFi 局域网 IP（请连接 WiFi）。连上后这里会显示 adb connect 命令。")
                }
            }

            // 本机作为 ADB 客户端：反向连接对方（被手机控制 / 本机作为客户端）
            GroupCaption("本机作为客户端（控制对方）")
            SetGroup {
                InfoLine("本机也可作为 ADB 客户端去连其它设备、或连自身（adb connect 127.0.0.1:$tcpPort）。输入对方 ip:port 即可反向连接并控制对方。")
                HorizontalDivider(color = Line, thickness = 1.dp, modifier = Modifier.padding(horizontal = 12.dp))
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = clientTarget,
                        onValueChange = { clientTarget = it.filter { c -> c.isDigit() || c == '.' || c == ':' } },
                        label = { Text("对方 ip:port", fontSize = 12.sp) },
                        placeholder = { Text("如 192.168.1.10:5555", fontSize = 12.sp, color = Muted) },
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
                        if (shellBusy) Text("连接中…", fontSize = 13.sp) else Text("连接", fontSize = 13.sp)
                    }
                }
                HorizontalDivider(color = Line, thickness = 1.dp, modifier = Modifier.padding(horizontal = 12.dp))
                SetRowClickable(
                    Icons.Filled.Terminal, "打开终端", "执行 adb / shell 命令控制本机或远端", "",
                    onClick = { Toast.makeText(ctx, "请在设置-终端打开终端，输入 adb 命令", Toast.LENGTH_SHORT).show() },
                    scaled = { it.sp },
                )
            }

            GroupCaption("系统入口（无提权时手动配对）")
            SetGroup {
                SetRowClickable(
                    Icons.Filled.DeveloperMode, "开发者选项", "打开系统开发者选项（USB 调试开关）", "",
                    onClick = { QuroAdbDebug.openDeveloperOptions(ctx) }, scaled = { it.sp },
                )
                HorizontalDivider(color = Line, thickness = 1.dp, modifier = Modifier.padding(horizontal = 12.dp))
                SetRowClickable(
                    Icons.Filled.Wifi, "无线调试", "Android 11+ 无线调试配对（配对码 + 端口）", "",
                    onClick = { QuroAdbDebug.openWirelessDebugging(ctx) }, scaled = { it.sp },
                )
            }

            // 本机 ADB Shell：经特权通道以 root 执行命令（控制代码 / 控制手机）
            GroupCaption("ADB Shell（控制代码 / 控制手机）")
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
                        label = { Text("命令", fontSize = 12.sp) },
                        placeholder = { Text("如 getprop ro.build.version.release", fontSize = 12.sp, color = Muted) },
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
                        if (shellBusy) Text("执行中…", fontSize = 13.sp) else Text("执行", fontSize = 13.sp)
                    }
                }
                if (shellOut.isNotBlank()) {
                    HorizontalDivider(color = Line, thickness = 1.dp, modifier = Modifier.padding(horizontal = 12.dp))
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("输出", fontSize = 12.sp, color = Muted, modifier = Modifier.weight(1f))
                        TextButton(onClick = { copy(shellOut) }) { Text("复制", fontSize = 12.sp, color = Accent) }
                        TextButton(onClick = { shellOut = "" }) { Text("清空", fontSize = 12.sp, color = Accent) }
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
            GroupCaption("设备控制（快捷动作 / 控制手机）")
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
                InfoLine("截图存到 App 私有存储（$extDir/quro_screencap.png）；锁屏/回桌面/多任务/音量经 input keyevent 注入；重启需二次确认。")
            }

            if (log.isNotBlank()) {
                GroupCaption("最近执行结果")
                SetGroup {
                    Text(
                        log, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                }
            }

            // 诊断：无需 adb，在手机上即可确认 ADB 与默认应用状态
            GroupCaption("诊断（无需 adb，手机上自证状态）")
            SetGroup {
                InfoLine("一键汇总当前 ADB 通道、TCP 监听、连接命令与 8 项默认应用角色状态，可分享给开发者定位；不写任何外部日志。")
                HorizontalDivider(color = Line, thickness = 1.dp, modifier = Modifier.padding(horizontal = 12.dp))
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Button(
                        onClick = { shareDiagnostic() },
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                    ) {
                        Text("分享诊断信息", fontSize = 13.sp)
                    }
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = { refresh() }) { Text("刷新", fontSize = 13.sp, color = Accent) }
                }
            }

            GroupCaption("说明")
            SetGroup {
                InfoLine("USB 调试 / 无线调试是系统能力；本应用只负责探测状态、在提权下启停 TCP adbd、展示连接命令，并引导你到系统设置手动配对。")
                InfoLine("启用无线 ADB 后，同一 WiFi 下的电脑可 adb connect 接管本机；在不可信网络请务必用完即关（关掉 TCP ADB）。")
                InfoLine("无 root / Shizuku 时无法静默启停 adbd，请使用系统「无线调试」配对（Android 11+ 支持配对码）。")
            }
        }
    }

    if (showRebootConfirm) {
        AlertDialog(
            onDismissRequest = { showRebootConfirm = false },
            title = { Text("确认重启？") },
            text = { Text("重启会立即关闭设备，未保存的数据可能丢失。") },
            confirmButton = {
                TextButton(onClick = { showRebootConfirm = false; runShell("reboot") }) {
                    Text("重启", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showRebootConfirm = false }) { Text("取消") }
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

/** 连接信息行：可复制 / 可分享的命令 + 说明。 */
@Composable
private fun ConnectInfoRow(cmd: String, desc: String, onCopy: () -> Unit, onShare: (() -> Unit)? = null) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("连接命令", fontSize = 13.sp, color = cs.onSurface, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            if (onShare != null) {
                TextButton(onClick = onShare) { Text("分享", fontSize = 13.sp, color = Accent, fontWeight = FontWeight.SemiBold) }
            }
            TextButton(onClick = onCopy) { Text("复制", fontSize = 13.sp, color = Accent, fontWeight = FontWeight.SemiBold) }
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
)
