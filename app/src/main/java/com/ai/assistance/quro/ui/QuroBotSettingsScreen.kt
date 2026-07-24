package com.ai.assistance.quro.ui

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.assistance.quro.core.bot.QuroBotManager
import com.ai.assistance.quro.core.bot.QuroBotPlatform
import com.ai.assistance.quro.core.bot.adapters.QuroLocalBotAdapter

/**
 * 机器人设置页（C2 重做版）：QQ / 飞书 / 微信 iLink 直连官方网关，无需公网后端。
 *
 * 每个平台：启用开关 + 凭据输入框（写入 SharedPreferences "quro_bots"）。
 *  - QQ：AppID + Secret（bot.q.qq.com 后台获取）
 *  - 飞书：App ID + App Secret（飞书开放平台自建应用）
 *  - 微信：bot_token（iLink 个人号，扫码登录后在设置页粘贴）
 * 本地平台提供「发送测试消息」验证端到端链路。
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
        QuroBotPlatform.WECHAT -> listOf("wechat_token" to "Bot Token (扫码后粘贴)")
        else -> emptyList()
    }
    val values = fields.associate { (k, _) -> k to remember { mutableStateOf(prefs.getString(k, "") ?: "") } }

    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(platform.label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Switch(checked = sw, onCheckedChange = {
                    sw = it
                    prefs.edit().putBoolean("enabled_${platform.name}", it).apply()
                    onToggle(it)
                })
            }
            if (isRelay) {
                Spacer(Modifier.height(6.dp))
                fields.forEach { (key, label) ->
                    OutlinedTextField(
                        value = values[key]?.value ?: "",
                        onValueChange = { v -> values[key]?.value = v; prefs.edit().putString(key, v).apply() },
                        label = { Text(label) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    )
                }
                Spacer(Modifier.height(4.dp))
                val hint = when (platform) {
                    QuroBotPlatform.QQ -> "QQ 开放平台建机器人拿 AppID/Secret；沙箱期需加自己为测试成员 + 配 IP 白名单。"
                    QuroBotPlatform.FEISHU -> "飞书开放平台建自建应用拿 App ID/Secret；事件订阅选「长连接接收」免填回调。"
                    QuroBotPlatform.WECHAT -> "微信 iLink 个人号：手机微信扫码登录后把 bot_token 粘贴此处（扫码登录端点待补）。"
                    else -> ""
                }
                Text(hint, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                // 启用时尝试直连；无凭据则适配器内部跳过
                androidx.compose.runtime.LaunchedEffect(sw) {
                    if (sw) runCatching { manager.getAdapter(platform)?.start() }
                }
            }
        }
    }
}
