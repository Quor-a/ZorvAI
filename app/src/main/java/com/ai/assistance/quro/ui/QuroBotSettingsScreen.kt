package com.ai.assistance.quro.ui
import androidx.compose.ui.res.stringResource
import com.ai.assistance.quro.R
import com.ai.assistance.quro.util.qstr

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.ImageBitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.assistance.quro.core.bot.QuroBotManager
import com.ai.assistance.quro.core.bot.QuroBotPlatform
import com.ai.assistance.quro.core.bot.adapters.QuroFeishuBotAdapter
import com.ai.assistance.quro.core.bot.adapters.QuroQqBotAdapter
import com.ai.assistance.quro.core.bot.adapters.QuroWechatIlinkBotAdapter
import com.ai.assistance.quro.core.bot.adapters.QrLoginStatus
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.ai.assistance.quro.ui.theme.Accent
import com.ai.assistance.quro.ui.theme.AccentSoft
import com.ai.assistance.quro.ui.theme.Line
import com.ai.assistance.quro.ui.theme.Muted
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import android.util.Base64

/**
 * 机器人接入页（v393 视觉精修：紧凑仪表盘 + 一体化平台卡）。
 *
 * v392 基础上优化：
 *  - 连接总览从三卡片改为单行状态条（更省空间、一目了然）
 *  - 本地测试台收窄：气泡区限高 + 输入行一体化
 *  - 平台卡头部内嵌状态指示（不再单独占一行），配置区默认折叠
 *  - 全局间距收紧 12→8dp，视觉密度提升
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuroBotSettingsScreen(onClose: () -> Unit) {
    val ctx = LocalContext.current.applicationContext
    val manager = remember { QuroBotManager.instance(ctx) }
    val prefs = remember { ctx.getSharedPreferences(QuroBotManager.PREFS, Context.MODE_PRIVATE) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.qk_00765)) },
                navigationIcon = {
                    IconButton(onClick = onClose) { Icon(Icons.Filled.ArrowBack, stringResource(R.string.qk_00143)) }
                },
            )
        }
    ) { pad ->
        LazyColumn(
            Modifier.fillMaxSize().padding(pad).padding(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // ── 说明条 ──
            item {
                Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    InfoBox(stringResource(R.string.qk_00766))
                }
            }

            // ── 连接状态条（单行紧凑）──
            item { ConnectionStatusBar(manager = manager) }

            // ── 平台卡 ──
            item { BotPlatformCard(QuroBotPlatform.QQ, prefs, manager = manager) }
            item { BotPlatformCard(QuroBotPlatform.FEISHU, prefs, manager = manager) }
            item { WechatBotPlatformCard(prefs, manager = manager) }
        }
    }
}

/** 连接状态条：单行双通道实时状态，紧凑型。 */
@Composable
private fun ConnectionStatusBar(manager: QuroBotManager) {
    val cs = MaterialTheme.colorScheme
    val items = listOf(
        Triple(QuroBotPlatform.QQ, "QQ", Icons.Filled.Chat),
        Triple(QuroBotPlatform.FEISHU, stringResource(R.string.qk_00767), Icons.Filled.Forum),
        Triple(QuroBotPlatform.WECHAT, stringResource(R.string.qk_00768), Icons.Filled.Chat),
    )
    val statuses = remember {
        mutableStateListOf<Triple<String, Color, Boolean>>().apply {
            items.forEach { add(Triple("…", Color.Gray, false)) }
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            items.forEachIndexed { i, (p, _, _) ->
                val adapter = manager.getAdapter(p)
                val text = when {
                    adapter == null -> qstr(R.string.qk_00769)
                    !adapter.isConnected -> qstr(R.string.qk_00770)
                    else -> when (adapter) {
                        is QuroQqBotAdapter -> if (adapter.wsConnected.get()) qstr(R.string.qk_00771) else qstr(R.string.qk_00772)
                        is QuroFeishuBotAdapter -> if (adapter.wsConnected.get()) qstr(R.string.qk_00771) else qstr(R.string.qk_00772)
                        is QuroWechatIlinkBotAdapter -> if (adapter.isConnected) qstr(R.string.qk_00771) else qstr(R.string.qk_00772)
                        else -> qstr(R.string.qk_00771)
                    }
                }
                val ok = text.contains("已连接")
                statuses[i] = Triple(text, if (ok) Color(0xFF4CAF50) else if (text.contains("断开") || text.contains("未连接")) Color(0xFFFF9800) else Color.Gray, ok)
            }
            delay(1500L)
        }
    }

    SetGroup {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items.forEachIndexed { i, (_, label, icon) ->
                val (text, color, ok) = statuses.getOrElse(i) { Triple("…", Color.Gray, false) }
                Row(
                    Modifier.weight(1f).clip(RoundedCornerShape(8.dp))
                        .background(cs.surfaceVariant.copy(alpha = 0.4f))
                        .padding(vertical = 6.dp, horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(8.dp).background(color, CircleShape))
                    Spacer(Modifier.width(5.dp))
                    Icon(icon, null, Modifier.size(14.dp), tint = color.copy(alpha = 0.7f))
                    Spacer(Modifier.width(4.dp))
                    Text(label, fontSize = 11.sp, color = cs.onSurface, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.width(4.dp))
                    Text(text, fontSize = 10.sp, color = color, maxLines = 1)
                }
            }
        }
    }
}

/** 本地测试台：紧凑气泡预览 + 一体化输入行。 */
@Composable
private fun LocalTestConsole(
    testInput: String,
    onInput: (String) -> Unit,
    onSend: () -> Unit,
    replies: List<String>,
    sent: List<String>,
) {
    val cs = MaterialTheme.colorScheme
    SetGroup {
        // 头部
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
            Box(
                Modifier.size(28.dp).clip(RoundedCornerShape(8.dp)).background(AccentSoft),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Android, null, Modifier.size(15.dp), tint = Accent)
            }
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.qk_00773), fontSize = 13.sp, color = cs.onSurface, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.qk_00774), fontSize = 10.sp, color = Muted)
        }

        // 气泡区（紧凑）
        Column(
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(cs.surfaceVariant.copy(alpha = 0.3f))
                .padding(8.dp),
        ) {
            if (sent.isEmpty() && replies.isEmpty()) {
                Text(stringResource(R.string.qk_00775), fontSize = 11.sp, color = Muted, modifier = Modifier.padding(vertical = 4.dp))
            } else {
                val max = if (sent.size > replies.size) sent.size else replies.size
                val turns = mutableListOf<Pair<Boolean, String>>()
                for (idx in 0 until minOf(max, 6)) {
                    sent.getOrNull(idx)?.let { turns.add(false to it) }
                    replies.getOrNull(idx)?.let { turns.add(true to it) }
                }
                turns.forEach { (isBot, msg) ->
                    if (isBot) {
                        Row(Modifier.fillMaxWidth()) { MiniBotBubble(msg) }
                    } else {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { MiniUserBubble(msg) }
                    }
                }
            }
        }

        // 输入行（一体化）
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
        ) {
            UnderlineField(
                label = "",
                value = testInput,
                onValueChange = onInput,
                placeholder = stringResource(R.string.qk_00776),
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(6.dp))
            IconButton(
                onClick = onSend,
                modifier = Modifier.size(34.dp).clip(CircleShape).background(Accent),
            ) {
                Icon(Icons.Filled.Send, stringResource(R.string.qk_00165), tint = Color.White, modifier = Modifier.size(16.dp))
            }
        }
    }
}

@Composable
private fun MiniBotBubble(text: String) {
    val cs = MaterialTheme.colorScheme
    Box(
        Modifier.fillMaxWidth(0.75f)
            .clip(RoundedCornerShape(10.dp, 10.dp, 10.dp, 2.dp))
            .background(cs.surface)
            .border(0.5.dp, Line, RoundedCornerShape(10.dp, 10.dp, 10.dp, 2.dp))
            .padding(horizontal = 8.dp, vertical = 5.dp),
    ) {
        Text(text, fontSize = 11.sp, color = cs.onSurface, lineHeight = 15.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun MiniUserBubble(text: String) {
    Box(
        Modifier.fillMaxWidth(0.7f)
            .clip(RoundedCornerShape(10.dp, 10.dp, 2.dp, 10.dp))
            .background(Accent)
            .padding(horizontal = 8.dp, vertical = 5.dp),
    ) {
        Text(text, fontSize = 11.sp, color = Color.White, lineHeight = 15.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
}

/** 状态圆点 */
@Composable
private fun StatusDot(color: Color, size: Int = 7) {
    Box(Modifier.size(size.dp).background(color, CircleShape))
}

/** 状态胶囊 */
@Composable
private fun StatusPill(text: String, color: Color) {
    Row(
        Modifier.clip(RoundedCornerShape(999.dp)).background(color.copy(alpha = 0.12f))
            .padding(horizontal = 7.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusDot(color, 6)
        Spacer(Modifier.width(4.dp))
        Text(text, fontSize = 10.sp, color = color, maxLines = 1)
    }
}

/**
 * 尝试把 base64 图片（含可选 data:image/...;base64, 前缀）解码为 ImageBitmap；
 * 不是图片（如纯 token/URL）则返回 null，交由 ZXing 生成二维码。
 */
private fun decodeQrImage(content: String): ImageBitmap? {
    return runCatching {
        val b64 = if (content.contains("base64,")) content.substringAfter("base64,") else content
        val bytes = android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        bmp.asImageBitmap()
    }.getOrNull()
}

/**
 * 平台卡：一体化设计——头部含图标/名称/副标/开关/状态，点击展开配置。
 * 配置区默认折叠，减少初始视觉噪音。
 */
@Composable
private fun BotPlatformCard(
    platform: QuroBotPlatform,
    prefs: SharedPreferences,
    enabled: Boolean = prefs.getBoolean("enabled_${platform.name}", false),
    onToggle: (Boolean) -> Unit = {},
    manager: QuroBotManager,
) {
    var sw by remember { mutableStateOf(enabled) }
    var expanded by remember { mutableStateOf(false) } // 默认折叠
    val isRelay = true
    val icon = when (platform) {
        QuroBotPlatform.QQ -> Icons.Filled.Chat
        QuroBotPlatform.FEISHU -> Icons.Filled.Forum
        QuroBotPlatform.WECHAT -> Icons.Filled.Chat
        else -> Icons.Filled.Chat
    }

    val fields: List<Pair<String, String>> = when (platform) {
        QuroBotPlatform.QQ -> listOf("qq_appid" to "AppID", "qq_secret" to "Secret")
        QuroBotPlatform.FEISHU -> listOf("feishu_appid" to "App ID", "feishu_secret" to "App Secret")
        QuroBotPlatform.WECHAT -> listOf("wechat_token" to "Bot Token")
        else -> emptyList()
    }
    val values = fields.associate { (k, _) -> k to remember { mutableStateOf(prefs.getString(k, "") ?: "") } }

    // 实时连接状态
    var statusText by remember { mutableStateOf("—") }
    var statusColor by remember { mutableStateOf(Color.Gray) }
    var detailText by remember { mutableStateOf("") }

    // 会话绑定
    var bindMode by remember { mutableStateOf(prefs.getString("bind_mode_${platform.name}", "auto") ?: "auto") }
    var bindConvId by remember { mutableStateOf(prefs.getString("bind_conv_${platform.name}", null) ?: "") }
    var showConvPicker by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        while (true) {
            val adapter = manager.getAdapter(platform)
            statusText = when {
                adapter == null -> qstr(R.string.qk_00769)
                !sw -> qstr(R.string.qk_00777)
                !adapter.isConnected -> qstr(R.string.qk_00770)
                else -> when (adapter) {
                    is QuroQqBotAdapter -> if (adapter.wsConnected.get()) qstr(R.string.qk_00778) else qstr(R.string.qk_00779)
                    is QuroFeishuBotAdapter -> if (adapter.wsConnected.get()) qstr(R.string.qk_00778) else qstr(R.string.qk_00779)
                    is QuroWechatIlinkBotAdapter -> if (adapter.isConnected) qstr(R.string.qk_00780) else qstr(R.string.qk_00781)
                    else -> qstr(R.string.qk_00771)
                }
            }
            statusColor = when {
                !sw || adapter == null -> Color.Gray
                statusText.contains("已连接") || statusText.contains("成功") -> Color(0xFF4CAF50)
                statusText.contains("断开") || statusText.contains("未连接") -> Color(0xFFFF9800)
                else -> Color.Gray
            }
            detailText = if (!sw || adapter == null) "" else (adapter.lastError ?: "")
            delay(1500L)
        }
    }

    SetGroup {
        // ═══ 头部（一行搞定）════
        Row(
            Modifier.fillMaxWidth().clickable { expanded = !expanded }
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 图标磁贴
            Box(Modifier.size(32.dp).clip(RoundedCornerShape(8.dp)).background(AccentSoft), contentAlignment = Alignment.Center) {
                Icon(icon, null, Modifier.size(17.dp), tint = Accent)
            }
            Spacer(Modifier.width(8.dp))
            // 名称 + 副标
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(platform.label, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
                    if (isRelay && sw) {
                        Spacer(Modifier.width(6.dp))
                        StatusPill(statusText, statusColor)
                    }
                }
                Text(
                    when (platform) {
                        QuroBotPlatform.WECHAT -> stringResource(R.string.qk_00782)
                        else -> if (isRelay) stringResource(R.string.qk_00783) else stringResource(R.string.qk_00784)
                    },
                    fontSize = 10.sp, color = Muted
                )
            }
            // 展开/收起图标
            Icon(if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null,
                tint = Muted, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(4.dp))
            // 开关
            Switch(
                checked = sw,
                onCheckedChange = {
                    val nv = !sw
                    sw = nv
                    expanded = false // 切换后自动折叠
                    prefs.edit().putBoolean("enabled_${platform.name}", nv).apply()
                    onToggle(nv)
                    val adapter = manager.getAdapter(platform)
                    if (!nv) CoroutineScope(Dispatchers.IO).launch { runCatching { adapter?.stop() } }
                    else CoroutineScope(Dispatchers.IO).launch { runCatching { adapter?.start() } }
                },
                colors = SwitchDefaults.colors(
                    checkedTrackColor = Accent,
                    checkedThumbColor = Color.White,
                    uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant,
                    uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            )
        }

        // ═══ 展开内容 ════
        if (expanded) {
            HorizontalDivider(color = Line.copy(alpha = 0.5f))

            // 操作栏（重连 + 错误提示）
            if (isRelay && sw) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = {
                            val adapter = manager.getAdapter(platform)
                            CoroutineScope(Dispatchers.IO).launch { runCatching { adapter?.stop() }; runCatching { adapter?.start() } }
                        },
                        modifier = Modifier.height(28.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                    ) {
                        Icon(Icons.Filled.Refresh, stringResource(R.string.qk_00785), modifier = Modifier.size(12.dp))
                        Spacer(Modifier.width(3.dp))
                        Text(stringResource(R.string.qk_00785), fontSize = 10.sp)
                    }
                    if (detailText.isNotBlank()) {
                        Text("⚠ $detailText", fontSize = 10.sp, color = Color(0xFFE53935), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }

            Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                // 凭据
                if (fields.isNotEmpty()) {
                    GroupCaption(stringResource(R.string.qk_00786))
                    fields.forEach { (key, label) ->
                        UnderlineField(
                            label = label,
                            value = values[key]?.value ?: "",
                            onValueChange = { v ->
                                values[key]?.value = v
                                prefs.edit().putString(key, v).apply()
                            },
                            placeholder = if (label.contains("Secret")) "••••••••" else "",
                            isSecret = label.contains("Secret"),
                        )
                    }
                }

                // 飞书权限说明
                if (platform == QuroBotPlatform.FEISHU) {
                    InfoBox(stringResource(R.string.qk_00787)
                    )
                }

                // 会话绑定
                GroupCaption(stringResource(R.string.qk_00788))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("none" to stringResource(R.string.qk_00789), "auto" to stringResource(R.string.qk_00790), "fixed" to stringResource(R.string.qk_00791)).forEach { (mode, label) ->
                        val selected = bindMode == mode
                        OutlinedButton(
                            onClick = {
                                bindMode = mode
                                prefs.edit().putString("bind_mode_${platform.name}", mode).apply()
                                if (mode != "fixed") { bindConvId = ""; prefs.edit().remove("bind_conv_${platform.name}").apply() }
                            },
                            Modifier.weight(1f).height(30.dp),
                            contentPadding = PaddingValues(horizontal = 4.dp),
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                            ),
                        ) { Text(label, fontSize = 10.sp, maxLines = 1) }
                    }
                }
                if (bindMode == "fixed") {
                    val convs = QuroChatViewModel.instance.conversations.collectAsState()
                    val selectedTitle = convs.value.firstOrNull { it.id == bindConvId }?.title ?: stringResource(R.string.qk_00792)
                    SetRowClickable(icon = Icons.Filled.ChevronRight, name = selectedTitle, sub = stringResource(R.string.qk_00793), onClick = { showConvPicker = true })
                    if (showConvPicker) {
                        val convsList = convs.value
                        AlertDialog(
                            onDismissRequest = { showConvPicker = false },
                            title = { Text(stringResource(R.string.qk_00792)) },
                            text = {
                                Column {
                                    if (convsList.isEmpty()) Text(stringResource(R.string.qk_00794), fontSize = 13.sp)
                                    else convsList.forEach { conv ->
                                        Row(
                                            Modifier.fillMaxWidth().clickable {
                                                bindConvId = conv.id
                                                prefs.edit().putString("bind_conv_${platform.name}", conv.id).apply()
                                                showConvPicker = false
                                            }.padding(vertical = 8.dp, horizontal = 4.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            RadioButton(selected = conv.id == bindConvId, onClick = {
                                                bindConvId = conv.id
                                                prefs.edit().putString("bind_conv_${platform.name}", conv.id).apply()
                                                showConvPicker = false
                                            })
                                            Spacer(Modifier.width(6.dp))
                                            Text(conv.title, fontSize = 13.sp, modifier = Modifier.weight(1f))
                                        }
                                    }
                                }
                            },
                            confirmButton = { OutlinedButton(onClick = { showConvPicker = false }) { Text(stringResource(R.string.qk_00065)) } },
                        )
                    }
                }

                // 平台提示
                val hint = when (platform) {
                    QuroBotPlatform.QQ -> stringResource(R.string.qk_00795)
                    QuroBotPlatform.FEISHU -> stringResource(R.string.qk_00796)
                    QuroBotPlatform.WECHAT -> stringResource(R.string.qk_00797)
                    else -> ""
                }
                if (hint.isNotBlank()) {
                    Text(hint, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 15.sp)
                }

                // 启动逻辑
                LaunchedEffect(sw) { if (sw) runCatching { manager.getAdapter(platform)?.start() } }
            }
        }
    }
}

/**
 * 微信 iLink 机器人平台卡（含扫码登录 + 手动 Token）。
 */
@Composable
private fun WechatBotPlatformCard(
    prefs: SharedPreferences,
    manager: QuroBotManager,
) {
    val ctx = LocalContext.current.applicationContext
    var sw by remember { mutableStateOf(prefs.getBoolean("enabled_WECHAT", false)) }
    var expanded by remember { mutableStateOf(false) }
    var manualToken by remember { mutableStateOf(prefs.getString("wechat_token", "") ?: "") }
    var showManualInput by remember { mutableStateOf(false) }

    val adapter = remember { manager.getAdapter(QuroBotPlatform.WECHAT) as? QuroWechatIlinkBotAdapter }
    Log.d("WechatBot", "adapter initialized: $adapter, platform=${QuroBotPlatform.WECHAT}")
    val loginState = remember { mutableStateOf(adapter?.loginState ?: QrLoginStatus.WAIT) }
    val qrCodeData = remember { mutableStateOf(adapter?.qrCodeData) }
    val qrError = remember { mutableStateOf(adapter?.qrError) }

    // 定时刷新登录状态
    LaunchedEffect(Unit) {
        while (true) {
            adapter?.let {
                loginState.value = it.loginState
                qrCodeData.value = it.qrCodeData
                qrError.value = it.qrError
            }
            delay(1000)
        }
    }

    val icon = Icons.Filled.Chat

    // 实时连接状态
    var statusText by remember { mutableStateOf("—") }
    var statusColor by remember { mutableStateOf(Color.Gray) }

    LaunchedEffect(Unit) {
        while (true) {
            statusText = when {
                adapter == null -> qstr(R.string.qk_00769)
                !sw -> qstr(R.string.qk_00777)
                !adapter.isConnected -> qstr(R.string.qk_00770)
                else -> qstr(R.string.qk_00771)
            }
            statusColor = when {
                !sw || adapter == null -> Color.Gray
                statusText.contains("已连接") -> Color(0xFF4CAF50)
                statusText.contains("断开") || statusText.contains("未连接") -> Color(0xFFFF9800)
                else -> Color.Gray
            }
            delay(1500L)
        }
    }

    // 会话绑定
    var bindMode by remember { mutableStateOf(prefs.getString("bind_mode_WECHAT", "auto") ?: "auto") }
    var bindConvId by remember { mutableStateOf(prefs.getString("bind_conv_WECHAT", null) ?: "") }
    var showConvPicker by remember { mutableStateOf(false) }

    SetGroup {
        // ═══ 头部（一行搞定）════
        Row(
            Modifier.fillMaxWidth().clickable { expanded = !expanded }
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 图标磁贴
            Box(Modifier.size(32.dp).clip(RoundedCornerShape(8.dp)).background(AccentSoft), contentAlignment = Alignment.Center) {
                Icon(icon, null, Modifier.size(17.dp), tint = Accent)
            }
            Spacer(Modifier.width(8.dp))
            // 名称 + 副标
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.qk_00798), fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
                    if (sw) {
                        Spacer(Modifier.width(6.dp))
                        StatusPill(statusText, statusColor)
                    }
                }
                Text(stringResource(R.string.qk_00799), fontSize = 10.sp, color = Muted)
            }
            // 展开/收起图标
            Icon(if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null,
                tint = Muted, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(4.dp))
            // 开关
            Switch(
                checked = sw,
                onCheckedChange = {
                    val nv = !sw
                    sw = nv
                    expanded = false
                    prefs.edit().putBoolean("enabled_WECHAT", nv).apply()
                    if (!nv) CoroutineScope(Dispatchers.IO).launch { runCatching { adapter?.stop() }; runCatching { adapter?.cancelQrLogin() } }
                    else CoroutineScope(Dispatchers.IO).launch { runCatching { adapter?.start() } }
                },
                colors = SwitchDefaults.colors(
                    checkedTrackColor = Accent,
                    checkedThumbColor = Color.White,
                    uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant,
                    uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            )
        }

        // ═══ 扫码登录区（始终可见，无需展开）═══
        HorizontalDivider(color = Line.copy(alpha = 0.5f))
        Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            // 操作栏（重连 + 错误提示）
            if (sw) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = {
                            CoroutineScope(Dispatchers.IO).launch { runCatching { adapter?.stop() }; runCatching { adapter?.start() } }
                        },
                        modifier = Modifier.height(28.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                    ) {
                        Icon(Icons.Filled.Refresh, stringResource(R.string.qk_00785), modifier = Modifier.size(12.dp))
                        Spacer(Modifier.width(3.dp))
                        Text(stringResource(R.string.qk_00785), fontSize = 10.sp)
                    }
                    if (adapter?.lastError?.isNotBlank() == true) {
                        val errorText = adapter?.lastError ?: ""
                        val isTokenExpired = errorText.contains("Token 过期", ignoreCase = true) ||
                                            errorText.contains("token过期", ignoreCase = true) ||
                                            errorText.contains("过期", ignoreCase = true) ||
                                            errorText.contains("expired", ignoreCase = true)
                        
                        if (isTokenExpired) {
                            // Token 过期特殊提示
                            Text(stringResource(R.string.qk_00803),
                                fontSize = 10.sp, 
                                color = Color(0xFFFF9800), // 橙色警告
                                maxLines = 2, 
                                overflow = TextOverflow.Ellipsis
                            )
                        } else {
                            Text("⚠ $errorText", fontSize = 10.sp, color = Color(0xFFE53935), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }

            GroupCaption(stringResource(R.string.qk_00804))
            when (loginState.value) {
                QrLoginStatus.WAIT -> {
                    OutlinedButton(
                        onClick = {
                            if (adapter == null) {
                                qrError.value = qstr(R.string.qk_00805)
                                return@OutlinedButton
                            }
                            try {
                                adapter!!.startQrLogin()
                            } catch (e: Exception) {
                                qrError.value = qstr(R.string.qk_00806, (e.message).toString())
                            }
                        },
                        Modifier.fillMaxWidth().height(36.dp),
                    ) {
                        Text(stringResource(R.string.qk_00807), fontSize = 12.sp)
                    }
                }
                QrLoginStatus.SCANNED -> {
                    val qrData = qrCodeData.value
                    if (qrData != null) {
                        val directImg = remember(qrData) { decodeQrImage(qrData) }
                        if (directImg != null) {
                            androidx.compose.foundation.Image(
                                bitmap = directImg,
                                contentDescription = stringResource(R.string.qk_00808),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(1f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color.White)
                                    .padding(8.dp),
                            )
                        } else {
                            val qrBitmap = remember(qrData) {
                                try {
                                    val hints = hashMapOf<com.google.zxing.EncodeHintType, Any>(
                                        com.google.zxing.EncodeHintType.MARGIN to 1,
                                        com.google.zxing.EncodeHintType.ERROR_CORRECTION to com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.M,
                                    )
                                    val matrix = com.google.zxing.qrcode.QRCodeWriter().encode(
                                        qrData,
                                        com.google.zxing.BarcodeFormat.QR_CODE,
                                        400, 400, hints
                                    )
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
                                androidx.compose.foundation.Image(
                                    bitmap = qrBitmap.asImageBitmap(),
                                    contentDescription = stringResource(R.string.qk_00808),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .aspectRatio(1f)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(Color.White)
                                        .padding(16.dp),
                                )
                            } else {
                                Text(stringResource(R.string.qk_00809, (qrData).toString()), fontSize = 11.sp, color = Color.Red)
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(stringResource(R.string.qk_00810), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        Text(stringResource(R.string.qk_00811), fontSize = 12.sp, color = Muted)
                    }
                    OutlinedButton(
                        onClick = { adapter?.cancelQrLogin() },
                        Modifier.fillMaxWidth().height(32.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFE53935)),
                    ) {
                        Text(stringResource(R.string.qk_00011), fontSize = 11.sp)
                    }
                }
                QrLoginStatus.CONFIRMED -> {
                    Text(stringResource(R.string.qk_00812), fontSize = 13.sp, color = Color(0xFF4CAF50), fontWeight = FontWeight.SemiBold)
                }
                QrLoginStatus.UNKNOWN -> {
                    Text(stringResource(R.string.qk_00813), fontSize = 12.sp, color = Color(0xFFE53935))
                    OutlinedButton(
                        onClick = { adapter?.startQrLogin() },
                        Modifier.fillMaxWidth().height(32.dp),
                    ) { Text(stringResource(R.string.qk_00814), fontSize = 11.sp) }
                }
                QrLoginStatus.DENIED -> {
                    Text(stringResource(R.string.qk_00815), fontSize = 12.sp, color = Color(0xFFE53935))
                    OutlinedButton(
                        onClick = { adapter?.startQrLogin() },
                        Modifier.fillMaxWidth().height(32.dp),
                    ) { Text(stringResource(R.string.qk_00814), fontSize = 11.sp) }
                }
                QrLoginStatus.EXPIRED -> {
                    Text(stringResource(R.string.qk_00816), fontSize = 12.sp, color = Color(0xFFFF9800))
                    OutlinedButton(
                        onClick = { adapter?.startQrLogin() },
                        Modifier.fillMaxWidth().height(32.dp),
                    ) { Text(stringResource(R.string.qk_00814), fontSize = 11.sp) }
                }
            }

            // 错误信息
            if (qrError.value != null) {
                Spacer(Modifier.height(4.dp))
                Text(qrError.value!!, fontSize = 10.sp, color = Color(0xFFE53935), lineHeight = 14.sp)
            }
        }

        // ═══ 展开内容（高级选项）═══
        if (expanded) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                // ===== 手动填 Token =====
                GroupCaption(stringResource(R.string.qk_00817))
                OutlinedButton(
                    onClick = { showManualInput = !showManualInput },
                    Modifier.fillMaxWidth().height(32.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                ) {
                    Text(if (showManualInput) stringResource(R.string.qk_00818) else stringResource(R.string.qk_00819), fontSize = 11.sp)
                }
                if (showManualInput) {
                    UnderlineField(
                        label = "Bot Token",
                        value = manualToken,
                        onValueChange = { v ->
                            manualToken = v
                            prefs.edit().putString("wechat_token", v).apply()
                            // 同步写入适配器自己的 SharedPreferences，否则轮询拿不到 token
                            val wechatPrefs = ctx.getSharedPreferences("quro_wechat_ilink", Context.MODE_PRIVATE)
                            wechatPrefs.edit().putString("bot_token", v).apply()
                            wechatPrefs.edit().putBoolean("logged_in", v.isNotBlank()).apply()
                        },
                        placeholder = qstr(R.string.qk_00820),
                        isSecret = true,
                    )
                    Text(stringResource(R.string.qk_00821), fontSize = 9.sp, color = Muted)
                }

                // ===== 会话绑定 =====
                GroupCaption(qstr(R.string.qk_00788))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("none" to stringResource(R.string.qk_00789), "auto" to stringResource(R.string.qk_00790), "fixed" to stringResource(R.string.qk_00791)).forEach { (mode, label) ->
                        val selected = bindMode == mode
                        OutlinedButton(
                            onClick = {
                                bindMode = mode
                                prefs.edit().putString("bind_mode_WECHAT", mode).apply()
                                if (mode != "fixed") { bindConvId = ""; prefs.edit().remove("bind_conv_WECHAT").apply() }
                            },
                            Modifier.weight(1f).height(30.dp),
                            contentPadding = PaddingValues(horizontal = 4.dp),
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                            ),
                        ) { Text(label, fontSize = 10.sp, maxLines = 1) }
                    }
                }
                if (bindMode == "fixed") {
                    val convs = QuroChatViewModel.instance.conversations.collectAsState()
                    val selectedTitle = convs.value.firstOrNull { it.id == bindConvId }?.title ?: qstr(R.string.qk_00792)
                    SetRowClickable(icon = Icons.Filled.ChevronRight, name = selectedTitle, sub = stringResource(R.string.qk_00793), onClick = { showConvPicker = true })
                    if (showConvPicker) {
                        val convsList = convs.value
                        AlertDialog(
                            onDismissRequest = { showConvPicker = false },
                            title = { Text(stringResource(R.string.qk_00792)) },
                            text = {
                                Column {
                                    if (convsList.isEmpty()) Text(stringResource(R.string.qk_00794), fontSize = 13.sp)
                                    else convsList.forEach { conv ->
                                        Row(
                                            Modifier.fillMaxWidth().clickable {
                                                bindConvId = conv.id
                                                prefs.edit().putString("bind_conv_WECHAT", conv.id).apply()
                                                showConvPicker = false
                                            }.padding(vertical = 8.dp, horizontal = 4.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            RadioButton(selected = conv.id == bindConvId, onClick = {
                                                bindConvId = conv.id
                                                prefs.edit().putString("bind_conv_WECHAT", conv.id).apply()
                                                showConvPicker = false
                                            })
                                            Spacer(Modifier.width(6.dp))
                                            Text(conv.title, fontSize = 13.sp, modifier = Modifier.weight(1f))
                                        }
                                    }
                                }
                            },
                            confirmButton = { OutlinedButton(onClick = { showConvPicker = false }) { Text(qstr(R.string.qk_00065)) } },
                        )
                    }
                }

                // ===== 平台说明 =====
                Text(
                    stringResource(R.string.qk_00822) +
                    stringResource(R.string.qk_00823) + stringResource(R.string.qk_00824),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 15.sp,
                )

                // 启动逻辑
                LaunchedEffect(sw) { if (sw) runCatching { adapter?.start() } }
            }
        }
    }
}