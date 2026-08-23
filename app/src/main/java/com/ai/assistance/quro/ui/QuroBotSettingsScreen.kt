package com.ai.assistance.quro.ui

import android.content.Context
import android.content.SharedPreferences
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
                title = { Text("机器人接入") },
                navigationIcon = {
                    IconButton(onClick = onClose) { Icon(Icons.Filled.ArrowBack, "返回") }
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
                    InfoBox("QQ / 飞书 / 微信直连官方网关，App 持密钥出站。")
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
        Triple(QuroBotPlatform.FEISHU, "飞书", Icons.Filled.Forum),
        Triple(QuroBotPlatform.WECHAT, "微信", Icons.Filled.Chat),
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
                    adapter == null -> "未注册"
                    !adapter.isConnected -> "未连接"
                    else -> when (adapter) {
                        is QuroQqBotAdapter -> if (adapter.wsConnected.get()) "已连接" else "断开"
                        is QuroFeishuBotAdapter -> if (adapter.wsConnected.get()) "已连接" else "断开"
                        is QuroWechatIlinkBotAdapter -> if (adapter.isConnected) "已连接" else "断开"
                        else -> "已连接"
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
            Text("本地测试", fontSize = 13.sp, color = cs.onSurface, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(6.dp))
            Text("App 内验证", fontSize = 10.sp, color = Muted)
        }

        // 气泡区（紧凑）
        Column(
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(cs.surfaceVariant.copy(alpha = 0.3f))
                .padding(8.dp),
        ) {
            if (sent.isEmpty() && replies.isEmpty()) {
                Text("发一条消息试试，回复以气泡显示", fontSize = 11.sp, color = Muted, modifier = Modifier.padding(vertical = 4.dp))
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
                placeholder = "输入测试消息…",
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(6.dp))
            IconButton(
                onClick = onSend,
                modifier = Modifier.size(34.dp).clip(CircleShape).background(Accent),
            ) {
                Icon(Icons.Filled.Send, "发送", tint = Color.White, modifier = Modifier.size(16.dp))
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
                adapter == null -> "未注册"
                !sw -> "已禁用"
                !adapter.isConnected -> "未连接"
                else -> when (adapter) {
                    is QuroQqBotAdapter -> if (adapter.wsConnected.get()) "WS 已连接" else "WS 断开"
                    is QuroFeishuBotAdapter -> if (adapter.wsConnected.get()) "WS 已连接" else "WS 断开"
                    is QuroWechatIlinkBotAdapter -> if (adapter.isConnected) "轮询已连接" else "轮询断开"
                    else -> "已连接"
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
                        QuroBotPlatform.WECHAT -> "直连官方域名 (HTTP 长轮询)"
                        else -> if (isRelay) "直连官方网关" else "内置常开"
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
                        Icon(Icons.Filled.Refresh, "重连", modifier = Modifier.size(12.dp))
                        Spacer(Modifier.width(3.dp))
                        Text("重连", fontSize = 10.sp)
                    }
                    if (detailText.isNotBlank()) {
                        Text("⚠ $detailText", fontSize = 10.sp, color = Color(0xFFE53935), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }

            Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                // 凭据
                if (fields.isNotEmpty()) {
                    GroupCaption("网关凭据")
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
                    InfoBox(
                        "需开启 im:message / im:message.send_as_bot 权限 + im.message.receive_v1 事件订阅。"
                    )
                }

                // 会话绑定
                GroupCaption("会话绑定")
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("none" to "不绑定", "auto" to "自动创建", "fixed" to "固定").forEach { (mode, label) ->
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
                    val selectedTitle = convs.value.firstOrNull { it.id == bindConvId }?.title ?: "选择会话"
                    SetRowClickable(icon = Icons.Filled.ChevronRight, name = selectedTitle, sub = "消息写入此对话", onClick = { showConvPicker = true })
                    if (showConvPicker) {
                        val convsList = convs.value
                        AlertDialog(
                            onDismissRequest = { showConvPicker = false },
                            title = { Text("选择会话") },
                            text = {
                                Column {
                                    if (convsList.isEmpty()) Text("暂无会话，请先新建。", fontSize = 13.sp)
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
                            confirmButton = { OutlinedButton(onClick = { showConvPicker = false }) { Text("关闭") } },
                        )
                    }
                }

                // 平台提示
                val hint = when (platform) {
                    QuroBotPlatform.QQ -> "QQ 开放平台建机器人拿 AppID/Secret；沙箱期加自己为测试成员。IP 白名单不填即可。"
                    QuroBotPlatform.FEISHU -> "飞书开放平台建自建应用拿 App ID/Secret；事件订阅选「长连接」免填回调。"
                    QuroBotPlatform.WECHAT -> "微信 iLink 通过手机端 HTTP 长轮询直连官方域名，无需公网端点。"
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
    var sw by remember { mutableStateOf(prefs.getBoolean("enabled_WECHAT", false)) }
    var expanded by remember { mutableStateOf(false) }
    var manualToken by remember { mutableStateOf(prefs.getString("wechat_token", "") ?: "") }
    var showManualInput by remember { mutableStateOf(false) }

    val adapter = remember { manager.getAdapter(QuroBotPlatform.WECHAT) as? QuroWechatIlinkBotAdapter }
    val loginState = remember { mutableStateOf(adapter?.loginState ?: QuroWechatIlinkBotAdapter.LoginState.IDLE) }
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
                adapter == null -> "未注册"
                !sw -> "已禁用"
                !adapter.isConnected -> "未连接"
                else -> "已连接"
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
                    Text("微信 iLink 机器人", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
                    if (sw) {
                        Spacer(Modifier.width(6.dp))
                        StatusPill(statusText, statusColor)
                    }
                }
                Text("直连官方域名，零公网端点", fontSize = 10.sp, color = Muted)
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

        // ═══ 展开内容 ════
        if (expanded) {
            HorizontalDivider(color = Line.copy(alpha = 0.5f))

            // 操作栏（重连 + 错误提示）
            if (sw) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
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
                        Icon(Icons.Filled.Refresh, "重连", modifier = Modifier.size(12.dp))
                        Spacer(Modifier.width(3.dp))
                        Text("重连", fontSize = 10.sp)
                    }
                    if (adapter?.lastError?.isNotBlank() == true) {
                        Text("⚠ ${adapter?.lastError}", fontSize = 10.sp, color = Color(0xFFE53935), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }

            Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                // ===== 扫码登录区域 =====
                GroupCaption("扫码登录")
                when (loginState.value) {
                    QuroWechatIlinkBotAdapter.LoginState.IDLE -> {
                        OutlinedButton(
                            onClick = { adapter?.startQrLogin() },
                            Modifier.fillMaxWidth().height(36.dp),
                        ) {
                            Text("获取微信登录二维码", fontSize = 12.sp)
                        }
                    }
                    QuroWechatIlinkBotAdapter.LoginState.WAITING_SCAN -> {
                        // 显示二维码
                        val qrData = qrCodeData.value
                        if (qrData != null) {
                            if (qrData.startsWith("data:") || qrData.length > 100) {
                                // 可能是 base64 图片
                                val cleanBase64 = qrData.removePrefix("data:image/png;base64,").removePrefix("data:image/jpeg;base64,")
                                val decodedBytes = Base64.decode(cleanBase64, Base64.DEFAULT)
                                val bitmap = android.graphics.BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.size)
                                if (bitmap != null) {
                                    androidx.compose.foundation.Image(
                                        bitmap = bitmap.asImageBitmap(),
                                        contentDescription = "微信登录二维码",
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .aspectRatio(1f)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(Color.White)
                                            .padding(16.dp),
                                    )
                                } else {
                                    Text("二维码加载失败", fontSize = 12.sp, color = Color.Red)
                                }
                            } else {
                                Text("二维码 Token: $qrData", fontSize = 11.sp, color = Muted)
                            }
                            Spacer(Modifier.height(4.dp))
                            Text("请用微信扫描二维码完成登录", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            Text("正在获取二维码...", fontSize = 12.sp, color = Muted)
                        }
                        OutlinedButton(
                            onClick = { adapter?.cancelQrLogin() },
                            Modifier.fillMaxWidth().height(32.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFE53935)),
                        ) {
                            Text("取消", fontSize = 11.sp)
                        }
                    }
                    QuroWechatIlinkBotAdapter.LoginState.CONFIRMED -> {
                        Text("✓ 登录成功！", fontSize = 13.sp, color = Color(0xFF4CAF50), fontWeight = FontWeight.SemiBold)
                    }
                    QuroWechatIlinkBotAdapter.LoginState.DENIED -> {
                        Text("登录被拒绝或取消", fontSize = 12.sp, color = Color(0xFFE53935))
                        OutlinedButton(
                            onClick = { adapter?.startQrLogin() },
                            Modifier.fillMaxWidth().height(32.dp),
                        ) { Text("重新获取二维码", fontSize = 11.sp) }
                    }
                    QuroWechatIlinkBotAdapter.LoginState.EXPIRED -> {
                        Text("二维码已过期", fontSize = 12.sp, color = Color(0xFFFF9800))
                        OutlinedButton(
                            onClick = { adapter?.startQrLogin() },
                            Modifier.fillMaxWidth().height(32.dp),
                        ) { Text("重新获取二维码", fontSize = 11.sp) }
                    }
                }

                // 错误信息
                if (qrError.value != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(qrError.value!!, fontSize = 10.sp, color = Color(0xFFE53935), lineHeight = 14.sp)
                }

                // ===== 手动填 Token =====
                GroupCaption("手动填 Token（高级）")
                OutlinedButton(
                    onClick = { showManualInput = !showManualInput },
                    Modifier.fillMaxWidth().height(32.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                ) {
                    Text(if (showManualInput) "收起" else "手动填入 Bot Token", fontSize = 11.sp)
                }
                if (showManualInput) {
                    UnderlineField(
                        label = "Bot Token",
                        value = manualToken,
                        onValueChange = { v ->
                            manualToken = v
                            prefs.edit().putString("wechat_token", v).apply()
                        },
                        placeholder = "粘贴从 ilinkai.weixin.qq.com 获取的 token",
                        isSecret = true,
                    )
                    Text("可通过命令行获取: curl \"https://ilinkai.weixin.qq.com/ilink/bot/get_bot_qrcode?bot_type=3\"", fontSize = 9.sp, color = Muted)
                }

                // ===== 会话绑定 =====
                GroupCaption("会话绑定")
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("none" to "不绑定", "auto" to "自动创建", "fixed" to "固定").forEach { (mode, label) ->
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
                    val selectedTitle = convs.value.firstOrNull { it.id == bindConvId }?.title ?: "选择会话"
                    SetRowClickable(icon = Icons.Filled.ChevronRight, name = selectedTitle, sub = "消息写入此对话", onClick = { showConvPicker = true })
                    if (showConvPicker) {
                        val convsList = convs.value
                        AlertDialog(
                            onDismissRequest = { showConvPicker = false },
                            title = { Text("选择会话") },
                            text = {
                                Column {
                                    if (convsList.isEmpty()) Text("暂无会话，请先新建。", fontSize = 13.sp)
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
                            confirmButton = { OutlinedButton(onClick = { showConvPicker = false }) { Text("关闭") } },
                        )
                    }
                }

                // ===== 平台说明 =====
                Text(
                    "微信 iLink 通过手机端 HTTP 长轮询直连 ilinkai.weixin.qq.com，无需公网端点。" +
                    "登录后 Bot Token 有效期约 24 小时，过期需重新扫码。\n" +
                    "注：扫码需要手机能访问 ilinkai.weixin.qq.com 域名，如遇网络问题请切换网络或使用手动填 Token。",
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
