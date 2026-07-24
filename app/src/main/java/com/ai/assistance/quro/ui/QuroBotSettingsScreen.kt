package com.ai.assistance.quro.ui

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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

/**
 * 机器人设置页（v257 精简版）：真实连接状态 + 重连按钮。
 *
 * 每个平台卡片新增：
 *  - 状态行：显示 WS 真实连接态（绿色=已连 / 灰色=未连 / 黄色=等待中）
 *  - 重连按钮：断线后一键重连（不依赖开关 toggle）
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
                    "QQ / 飞书均直连官方网关，App 持密钥出站收消息，无需自建后端。本地测试可在 App 内直接验证。",
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

            // 直连型平台（QQ / 飞书）
            item { BotPlatformCard(QuroBotPlatform.QQ, prefs, manager = manager) }
            item { BotPlatformCard(QuroBotPlatform.FEISHU, prefs, manager = manager) }
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
        else -> emptyList()
    }
    val values = fields.associate { (k, _) -> k to remember { mutableStateOf(prefs.getString(k, "") ?: "") } }

    // ---- 连接状态（实时读取 adapter 真实状态）----
    var statusText by remember { mutableStateOf("未启动") }
    var statusColor by remember { mutableStateOf(Color.Gray) }
    // 最近一次失败的可读原因（来自 adapter.lastError），无需翻 logcat 即可看到为什么连不上
    var detailText by remember { mutableStateOf("") }

    // 会话绑定模式：none=不写入 App 会话；auto=为每个平台用户自动创建新会话；fixed=绑定到指定会话
    var bindMode by remember { mutableStateOf(prefs.getString("bind_mode_${platform.name}", "auto") ?: "auto") }
    var bindConvId by remember { mutableStateOf(prefs.getString("bind_conv_${platform.name}", null) ?: "") }
    var showConvPicker by remember { mutableStateOf(false) }

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
                        else -> "已连接"
                    }
                }
            }
            statusColor = when {
                !sw || adapter == null -> Color.Gray
                statusText.contains("已连接") || statusText.contains("成功") || statusText.contains("轮询") -> Color(0xFF4CAF50)
                statusText.contains("等待") -> Color(0xFFFF9800)
                else -> Color.Gray
            }

            // 把 adapter 最近一次失败原因同步到 UI
            detailText = if (!sw || adapter == null) "" else (adapter.lastError ?: "")

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
                // 失败原因（红色小字，无需翻 logcat）
                if (detailText.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "⚠ $detailText",
                        fontSize = 11.sp,
                        color = Color(0xFFE53935),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            if (isRelay) {
                Spacer(Modifier.height(8.dp))

                // 凭据输入框
                fields.forEach { (key, label) ->
                        OutlinedTextField(
                            value = values[key]?.value ?: "",
                            onValueChange = { v ->
                                values[key]?.value = v
                                prefs.edit().putString(key, v).apply()
                            },
                            label = { Text(label) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        )
                    }

                // 会话绑定模式（仅直连平台）
                Spacer(Modifier.height(4.dp))
                Text("会话绑定", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(4.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("none" to "不绑定", "auto" to "自动创建", "fixed" to "绑定会话").forEach { (mode, label) ->
                        val selected = bindMode == mode
                        OutlinedButton(
                            onClick = {
                                bindMode = mode
                                prefs.edit().putString("bind_mode_${platform.name}", mode).apply()
                                if (mode != "fixed") {
                                    bindConvId = ""
                                    prefs.edit().remove("bind_conv_${platform.name}").apply()
                                }
                            },
                            modifier = Modifier.weight(1f).height(34.dp),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp),
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                            ),
                        ) {
                            Text(label, fontSize = 11.sp, maxLines = 1)
                        }
                    }
                }
                if (bindMode == "fixed") {
                    Spacer(Modifier.height(4.dp))
                    val convs = QuroChatViewModel.instance.conversations.collectAsState()
                    val selectedTitle = convs.value.firstOrNull { it.id == bindConvId }?.title ?: "选择要绑定的会话"
                    OutlinedButton(
                        onClick = { showConvPicker = true },
                        modifier = Modifier.fillMaxWidth().height(36.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp),
                    ) {
                        Text(selectedTitle, fontSize = 12.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Icon(Icons.Filled.ChevronRight, null, Modifier.size(16.dp))
                    }
                    // 会话选择对话框
                    if (showConvPicker) {
                        val convsList = convs.value
                        AlertDialog(
                            onDismissRequest = { showConvPicker = false },
                            title = { Text("选择会话") },
                            text = {
                                Column {
                                    if (convsList.isEmpty()) {
                                        Text("暂无可选会话，请先新建一个对话。", fontSize = 13.sp)
                                    } else {
                                        convsList.forEach { conv ->
                                            Row(
                                                Modifier.fillMaxWidth().clickable {
                                                    bindConvId = conv.id
                                                    prefs.edit().putString("bind_conv_${platform.name}", conv.id).apply()
                                                    showConvPicker = false
                                                }.padding(vertical = 10.dp, horizontal = 4.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                            ) {
                                                RadioButton(selected = conv.id == bindConvId, onClick = {
                                                    bindConvId = conv.id
                                                    prefs.edit().putString("bind_conv_${platform.name}", conv.id).apply()
                                                    showConvPicker = false
                                                })
                                                Spacer(Modifier.width(8.dp))
                                                Text(conv.title, fontSize = 14.sp, modifier = Modifier.weight(1f))
                                            }
                                        }
                                    }
                                }
                            },
                            confirmButton = {
                                OutlinedButton(onClick = { showConvPicker = false }) { Text("关闭") }
                            },
                        )
                    }
                }

                Spacer(Modifier.height(4.dp))
                val hint = when (platform) {
                    QuroBotPlatform.QQ -> "QQ 开放平台建机器人拿 AppID/Secret；沙箱期需加自己为测试成员。IP 白名单在 QQ 后台管理：不填 = 所有 IP 均可调用，本 App 不做额外限制。"
                    QuroBotPlatform.FEISHU -> "飞书开放平台建自建应用拿 App ID/Secret；事件订阅选「长连接接收」免填回调。"
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
