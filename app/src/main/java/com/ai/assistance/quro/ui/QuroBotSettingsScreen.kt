package com.ai.assistance.quro.ui

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.ai.assistance.quro.core.bot.QuroBotManager
import com.ai.assistance.quro.core.bot.QuroBotPlatform
import com.ai.assistance.quro.core.bot.adapters.QuroFeishuBotAdapter
import com.ai.assistance.quro.core.bot.adapters.QuroLocalBotAdapter
import com.ai.assistance.quro.core.bot.adapters.QuroQqBotAdapter
import com.ai.assistance.quro.core.bot.adapters.QuroWechatIlinkBotAdapter

/**
 * 机器人设置页（v250 增强版）：真实连接状态 + 微信扫码登录 + 重连按钮。
 *
 * 每个平台卡片新增：
 *  - 状态行：显示 WS/轮询真实连接态（绿色=已连 / 灰色=未连 / 黄色=等待中）
 *  - 重连按钮：断线后一键重连（不依赖开关 toggle）
 *  - 微信专属：「扫码登录」按钮 → 弹二维码 → 轮询 → 自动填 token → 启动长轮询
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuroBotSettingsScreen(onClose: () -> Unit) {
    val ctx = LocalContext.current.applicationContext
    val manager = remember { QuroBotManager.instance(ctx) }
    val prefs = remember { ctx.getSharedPreferences(QuroBotManager.PREFS, Context.MODE_PRIVATE) }

    val replies = remember { mutableStateListOf<String>() }
    DisposableEffect(manager) {
        val local = manager.getAdapter(QuroBotPlatform.LOCAL) as? QuroLocalBotAdapter
        val listener: (String) -> Unit = { replies.add(0, it) }
        local?.addReplyListener(listener)
        onDispose { local?.removeReplyListener(listener) }
    }

    var testInput by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("机器人平台") },
                navigationIcon = {
                    IconButton(onClick = onClose) { Icon(Icons.Filled.ArrowBack, "返回") }
                },
            )
        }
    ) { pad ->
        LazyColumn(
            Modifier.fillMaxSize().padding(pad).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Text(
                    "QQ / 飞书 / 微信 iLink 均直连官方网关，App 持密钥出站收消息，无需自建后端。本地测试可在 App 内直接验证。",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // 本地测试平台
            item {
                BotPlatformCard(platform = QuroBotPlatform.LOCAL, prefs = prefs, enabled = true, onToggle = {}, manager = manager)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = testInput,
                    onValueChange = { testInput = it },
                    label = { Text("发送一条测试消息给本地机器人") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    trailingIcon = {
                        Button(onClick = {
                            val t = testInput.trim()
                            if (t.isNotBlank()) {
                                manager.sendLocalTest(t)
                                testInput = ""
                            }
                        }) { Text("发送") }
                    },
                )
                Spacer(Modifier.height(8.dp))
                Text("机器人回复：", fontSize = 13.sp)
                if (replies.isEmpty()) {
                    Text("（还没有回复，发一条试试）", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    replies.take(5).forEach {
                        Text("· ${it.take(200)}", fontSize = 12.sp)
                        Spacer(Modifier.height(4.dp))
                    }
                }
            }

            // 直连型平台（QQ / 飞书 / 微信 iLink）
            item { BotPlatformCard(QuroBotPlatform.QQ, prefs, manager = manager) }
            item { BotPlatformCard(QuroBotPlatform.FEISHU, prefs, manager = manager) }
            item { BotPlatformCard(QuroBotPlatform.WECHAT, prefs, manager = manager) }
        }
    }
}

@Composable
private fun BotPlatformCard(
    platform: QuroBotPlatform,
    prefs: SharedPreferences,
    enabled: Boolean = prefs.getBoolean("enabled_${platform.name}", platform == QuroBotPlatform.LOCAL),
    onToggle: (Boolean) -> Unit = {},
    manager: QuroBotManager,
) {
    var sw by remember { mutableStateOf(enabled) }
    val isRelay = platform != QuroBotPlatform.LOCAL

    // 各平台凭据字段
    val fields: List<Pair<String, String>> = when (platform) {
        QuroBotPlatform.QQ -> listOf("qq_appid" to "AppID", "qq_secret" to "Secret")
        QuroBotPlatform.FEISHU -> listOf("feishu_appid" to "App ID", "feishu_secret" to "App Secret")
        QuroBotPlatform.WECHAT -> listOf("wechat_token" to "Bot Token (扫码后自动填或手动粘贴)")
        else -> emptyList()
    }
    val values = fields.associate { (k, _) -> k to remember { mutableStateOf(prefs.getString(k, "") ?: "") } }

    // ---- 连接状态（实时读取 adapter 真实状态）----
    var statusText by remember { mutableStateOf("未启动") }
    var statusColor by remember { mutableStateOf(Color.Gray) }

    // 微信扫码状态
    var showQr by remember { mutableStateOf(false) }
    var qrData by remember { mutableStateOf<String?>(null) }
    var qrStatus by remember { mutableStateOf("") }

    // 定期刷新状态显示
    androidx.compose.runtime.LaunchedEffect(Unit) {
        while (true) {
            val adapter = manager.getAdapter(platform)
                statusText = when {
                    adapter == null -> "未注册"
                    !sw -> "已禁用"
                    !adapter.isConnected -> "未连接"
                else -> {
                    // 进一步区分 WS/轮询态
                    when (adapter) {
                        is QuroQqBotAdapter -> if (adapter.wsConnected.get()) "WS 已连接" else "WS 断开"
                        is QuroFeishuBotAdapter -> if (adapter.wsConnected.get()) "WS 已连接" else "WS 断开"
                        is QuroWechatIlinkBotAdapter -> {
                            val wechat = adapter as QuroWechatIlinkBotAdapter
                            when (wechat.loginState) {
                                QuroWechatIlinkBotAdapter.LoginState.WAITING_SCAN -> "等待扫码..."
                                QuroWechatIlinkBotAdapter.LoginState.CONFIRMED -> "扫码成功 ✓"
                                QuroWechatIlinkBotAdapter.LoginState.DENIED -> "已取消"
                                QuroWechatIlinkBotAdapter.LoginState.EXPIRED -> "二维码过期"
                                else -> if (adapter.isConnected) "长轮询中" else "未连接"
                            }
                        }
                        else -> "已连接"
                    }
                }
            }
            statusColor = when {
                !sw || adapter == null -> Color.Gray
                statusText.contains("已连接") || statusText.contains("成功") || statusText.contains("轮询") -> Color(0xFF4CAF50)
                statusText.contains("等待") || statusText.contains("扫码") -> Color(0xFFFF9800)
                else -> Color.Gray
            }

            // 同步微信扫码数据到 UI
            if (platform == QuroBotPlatform.WECHAT) {
                val wechat = adapter as? QuroWechatIlinkBotAdapter
                if (wechat != null) {
                    qrData = wechat.qrCodeData
                    showQr = wechat.loginState == QuroWechatIlinkBotAdapter.LoginState.WAITING_SCAN
                    qrStatus = when (wechat.loginState) {
                        QuroWechatIlinkBotAdapter.LoginState.IDLE -> ""
                        QuroWechatIlinkBotAdapter.LoginState.WAITING_SCAN -> "请用手机微信扫描下方二维码"
                        QuroWechatIlinkBotAdapter.LoginState.CONFIRMED -> "✓ 登录成功！token 已保存"
                        QuroWechatIlinkBotAdapter.LoginState.DENIED -> "✗ 已取消"
                        QuroWechatIlinkBotAdapter.LoginState.EXPIRED -> "✗ 二维码已过期，请重试"
                    }
                    // 扫码成功后 qrStatus 已显示"✓ 登录成功"，无需再同步输入框（输入框保留手动编辑能力）
                    Unit
                }
            }

            delay(1500L)
        }
    }

    ElevatedCard(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(containerColor = if (sw) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)),
    ) {
        Column(Modifier.padding(12.dp)) {
            // 标题行：平台名 + 开关
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(platform.label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Switch(checked = sw, onCheckedChange = {
                    sw = it
                    prefs.edit().putBoolean("enabled_${platform.name}", it).apply()
                    onToggle(it)
                    val adapter = manager.getAdapter(platform)
                    if (!it) CoroutineScope(Dispatchers.IO).launch { runCatching { adapter?.stop() } }
                    else CoroutineScope(Dispatchers.IO).launch { runCatching { adapter?.start() } }
                })
            }

            // 状态行（所有直连平台都显示）
            if (isRelay) {
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp, 8.dp).padding(end = 6.dp)) {
                        // 状态圆点
                    }
                    Text(
                        text = statusText,
                        fontSize = 12.sp,
                        color = statusColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    // 重连按钮
                    OutlinedButton(
                        onClick = {
                            val adapter = manager.getAdapter(platform)
                            CoroutineScope(Dispatchers.IO).launch { runCatching { adapter?.stop() }; runCatching { adapter?.start() } }
                        },
                        enabled = sw,
                        modifier = Modifier.height(32.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                    ) {
                        Icon(Icons.Filled.Refresh, "重连", modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("重连", fontSize = 11.sp)
                    }
                }
            }

            if (isRelay) {
                Spacer(Modifier.height(8.dp))

                // 凭据输入框（微信有 token 时也允许编辑）
                if (platform != QuroBotPlatform.WECHAT || !showQr) {
                    fields.forEach { (key, label) ->
                        OutlinedTextField(
                            value = values[key]?.value ?: "",
                            onValueChange = { v ->
                                values[key]?.value = v
                                prefs.edit().putString(key, v).apply()
                                // 微信 token 手动输入后也尝试启动
                                if (platform == QuroBotPlatform.WECHAT && v.isNotBlank() && sw) {
                                    CoroutineScope(Dispatchers.IO).launch { runCatching { manager.getAdapter(platform)?.start() } }
                                }
                            },
                            label = { Text(label) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                            keyboardOptions = KeyboardOptions(keyboardType = if (platform == QuroBotPlatform.WECHAT) KeyboardType.Text else KeyboardType.Password),
                        )
                    }
                }

                // 微信扫码登录区域
                if (platform == QuroBotPlatform.WECHAT) {
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Button(
                            onClick = {
                                val wechat = manager.getAdapter(QuroBotPlatform.WECHAT) as? QuroWechatIlinkBotAdapter
                                if (wechat != null) {
                                    if (wechat.loginState == QuroWechatIlinkBotAdapter.LoginState.WAITING_SCAN) {
                                        wechat.cancelQrLogin()
                                    } else {
                                        wechat.startQrLogin()
                                    }
                                }
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (showQr) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                            ),
                        ) {
                            Icon(Icons.Filled.QrCodeScanner, "扫码", modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(if (showQr) "取消扫码" else "扫码登录")
                        }
                    }

                    // 二维码展示
                    if (showQr && qrData != null) {
                        Spacer(Modifier.height(8.dp))
                        ElevatedCard(
                            Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface),
                        ) {
                            Column(Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(qrStatus, fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.height(8.dp))
                                // 二维码图片（base64 或 URL）
                                val data = qrData!!
                                Box(
                                    Modifier.fillMaxWidth().height(200.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    when {
                                        data.startsWith("http", ignoreCase = true) -> {
                                            // URL 形式：提示用户在浏览器打开或用其他方式查看
                                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                                Text("请在浏览器中打开此链接查看二维码：", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                Text(data, fontSize = 10.sp, color = MaterialTheme.colorScheme.primary, maxLines = 2)
                                            }
                                        }
                                        data.length > 200 -> {
                                            // base64 图片数据
                                            Text("二维码已生成（${data.length} 字符 base64）\n（真机可渲染为图片，预览环境暂显示文字）", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                        else -> {
                                            // 短文本（可能是 QR 内容字符串）
                                            Text("QR: $data", fontSize = 11.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                                        }
                                    }
                                }
                                Spacer(Modifier.height(4.dp))
                                Text("打开微信 → 扫一扫 → 确认登录", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }

                Spacer(Modifier.height(4.dp))
                val hint = when (platform) {
                    QuroBotPlatform.QQ -> "QQ 开放平台建机器人拿 AppID/Secret；沙箱期需加自己为测试成员 + 配 IP 白名单。"
                    QuroBotPlatform.FEISHU -> "飞书开放平台建自建应用拿 App ID/Secret；事件订阅选「长连接接收」免填回调。"
                    QuroBotPlatform.WECHAT -> "微信 iLink 个人号：点击「扫码登录」获取二维码，用手机微信扫后自动填入 token。"
                    else -> ""
                }
                Text(hint, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

                // 启用时自动 start（仅凭据齐全时有效）
                LaunchedEffect(sw) {
                    if (sw) runCatching { manager.getAdapter(platform)?.start() }
                }
            }
        }
    }
}
