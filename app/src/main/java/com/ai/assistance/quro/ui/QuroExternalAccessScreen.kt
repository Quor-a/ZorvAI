package com.ai.assistance.quro.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Key
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.assistance.quro.R
import com.ai.assistance.quro.core.external.QuroExternalAccessPrefs
import com.ai.assistance.quro.core.external.QuroExternalServer
import com.ai.assistance.quro.service.QuroExternalAccessService
import com.ai.assistance.quro.ui.theme.Card
import com.ai.assistance.quro.ui.theme.Line
import com.ai.assistance.quro.ui.theme.Muted
import com.ai.assistance.quro.util.qstr
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 外部连接（模型服务组）：把本应用作为 OpenAI 兼容模型服务暴露给外部工具。
 *
 * 与 MCP 无关——MCP 暴露工具给本机客户端；这里暴露对话补全（/v1/models、/v1/chat/completions）：
 * - 独立前台服务（[QuroExternalAccessService]）承载 [QuroExternalServer]，绑 0.0.0.0，
 *   同一 Wi-Fi 下的电脑/设备可直连（页面同时给出局域网地址与本机回环地址）；
 * - /v1/models 返回**当前活跃配置 + 全部已保存预设**的模型，外部客户端下拉即见；
 * - /v1/chat/completions 的 model 参数按模型名/预设名路由到对应接入点；
 * - 生成 / 复制 / 吊销 API 密钥（sk- 前缀，Bearer 校验）；最近使用记录。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuroExternalAccessScreen(onBack: () -> Unit = {}) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    var enabled by remember { mutableStateOf(QuroExternalAccessPrefs.isEnabled(ctx)) }
    // 灵魂注入开关（默认关，保持 OpenAI 兼容纯透传语义）
    var injectPersona by remember { mutableStateOf(QuroExternalAccessPrefs.isInjectPersona(ctx)) }
    var keys by remember { mutableStateOf(QuroExternalAccessPrefs.keys(ctx)) }
    var recents by remember { mutableStateOf(QuroExternalAccessPrefs.recentCalls(ctx)) }
    var newKeyName by remember { mutableStateOf("") }
    var showNewKey by remember { mutableStateOf(false) }
    var freshlyCreated by remember { mutableStateOf<QuroExternalAccessPrefs.ApiKey?>(null) }
    // 🔴 端口**从一而终**（Base URL 永远固定）：
    //   端口一经分配（默认 8849）就永久保留，停止服务不再清零，
    //   所以外面保存的 Base URL 不会因为重启而失效。
    //   运行状态改由 [QuroExternalServer.runningFlow] 表达，不再拿「端口==0」猜。
    val port by QuroExternalAccessPrefs.portFlow().collectAsState()
    // 运行状态独立观察：端口保留后「port>0」不再等价于「服务在跑」。
    val serverRunning by QuroExternalServer.runningFlow().collectAsState()
    val lanIp = remember { QuroExternalServer.lanIpAddress() }
    // URL 文案：服务运行中显示实际可用的地址；未运行也显示**将来会用的地址**（端口固定），
    // 用户可以提前在外部客户端里配好——这正是「Base URL 永不变」的意义。
    val baseUrl = when {
        port > 0 && lanIp != null -> "http://$lanIp:$port/v1"
        port > 0 -> "http://127.0.0.1:$port/v1"
        else -> "—（服务未运行，打开下方「启用端点」开关）"
    }
    val baseUrlLan = if (port > 0 && lanIp != null) "http://$lanIp:$port/v1" else null
    val baseUrlLoopback = if (port > 0) "http://127.0.0.1:$port/v1" else null
    val timeFmt = remember { SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()) }
    // 已保存的模型（当前活跃配置 + 全部已保存预设）——「怎么拉已经保存的模型」的数据源。
    // 用 state 而非 remember{}：用户可能刚在「模型配置」保存了预设，页面要能**刷新**拉取。
    var modelOptions by remember { mutableStateOf(QuroExternalAccessPrefs.modelOptions(ctx)) }
    // 「只暴露勾选的模型」开关：关闭 = 全部暴露（默认）；打开 = 只暴露下面勾中的。
    val savedPicks = remember { QuroExternalAccessPrefs.exposedModels(ctx) }
    var restrictModels by remember { mutableStateOf(savedPicks.isNotEmpty()) }
    var pickedModels by remember {
        mutableStateOf(savedPicks.ifEmpty { QuroExternalAccessPrefs.modelOptions(ctx).map { it.id }.toSet() })
    }
    // /v1/models 实际会返回的模型（受勾选影响）
    val exposed = remember(modelOptions, pickedModels, restrictModels) {
        if (restrictModels) {
            modelOptions.filter { it.id in pickedModels }.map { it.id to "${it.label} · ${it.host}" }
        } else {
            modelOptions.map { it.id to "${it.label} · ${it.host}" }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("外部连接", fontSize = 18.sp, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, qstr(R.string.qk_00143)) }
                },
            )
        },
    ) { pad ->
        LazyColumn(
            Modifier.fillMaxSize().padding(pad).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    "外部工具（桌面端客户端 / 脚本 / 自动化）可把本应用当作 OpenAI 兼容模型服务调用：/v1/models 会列出当前配置与全部已保存预设的模型，/v1/chat/completions 按请求里的 model 名路由到对应接入点，支持流式（含思考过程 reasoning_content 透传）。生成密钥后，在对方配置里填入 Base URL 与 API Key 即可。",
                    fontSize = 12.sp, color = Muted,
                )
            }

            // ── Base URL 卡 ──
            item {
                Column(
                    Modifier.fillMaxWidth().background(Card, RoundedCornerShape(14.dp)).padding(14.dp),
                ) {
                    Text("Base URL", fontSize = 12.sp, color = Muted)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            baseUrl,
                            fontSize = 13.sp, color = cs.primary, fontWeight = FontWeight.Medium,
                            modifier = Modifier.weight(1f),
                        )
                        if (port > 0) {
                            IconButton(onClick = {
                                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                cm.setPrimaryClip(ClipData.newPlainText("Base URL", baseUrl))
                                Toast.makeText(ctx, qstr(R.string.qk_02065), Toast.LENGTH_SHORT).show()
                            }) { Icon(Icons.Filled.ContentCopy, qstr(R.string.qk_00088), tint = cs.primary, modifier = Modifier.size(18.dp)) }
                        }
                    }
                    if (baseUrlLan != null && baseUrlLoopback != null && baseUrlLan != baseUrl) {
                        Spacer(Modifier.height(4.dp))
                        Text("本机调用：$baseUrlLoopback", fontSize = 11.sp, color = Muted)
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("启用端点", fontSize = 14.sp, color = cs.onSurface)
                            Text(
                                if (enabled && serverRunning) "运行中 · 端口 $port · Bearer 密钥校验"
                                else if (enabled) "启动中…"
                                else "未启用 · 外部调用将被拒绝",
                                fontSize = 11.sp, color = Muted,
                            )
                        }
                        Switch(checked = enabled, onCheckedChange = {
                            enabled = it
                            QuroExternalAccessPrefs.setEnabled(ctx, it)
                            if (it) QuroExternalAccessService.start(ctx) else QuroExternalAccessService.stop(ctx)
                        })
                    }
                    HorizontalDivider(color = Line.copy(alpha = 0.5f), modifier = Modifier.padding(vertical = 10.dp))
                    // 灵魂注入（默认关）：外部请求未自带 system 时把当前人格注入，
                    // 让外部调用得到的不是裸模型。客户端自带 system 一律不覆盖。
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("注入当前人格（灵魂）", fontSize = 13.sp, color = cs.onSurface)
                            Text(
                                "调用方未带 system 时生效；自带 system 不覆盖。不注入记忆与设备能力。",
                                fontSize = 11.sp, color = Muted,
                            )
                        }
                        Switch(checked = injectPersona, onCheckedChange = { on ->
                            injectPersona = on
                            QuroExternalAccessPrefs.setInjectPersona(ctx, on)
                        })
                    }
                }
            }

            // ── 模型选择卡：从已保存的模型里选哪些暴露给外部客户端 ──
            item {
                Column(
                    Modifier.fillMaxWidth().background(Card, RoundedCornerShape(14.dp)).padding(14.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("模型选择", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurface)
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = {
                            modelOptions = QuroExternalAccessPrefs.modelOptions(ctx)
                            pickedModels = if (pickedModels.isEmpty()) modelOptions.map { it.id }.toSet() else pickedModels
                            Toast.makeText(ctx, "已刷新已保存模型", Toast.LENGTH_SHORT).show()
                        }) { Text("刷新", fontSize = 12.sp) }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "已保存的模型（当前配置 + 全部预设）在这里列出。外部客户端拉 /v1/models 时看到的就是这些。",
                        fontSize = 11.sp, color = Muted,
                    )
                    Spacer(Modifier.height(6.dp))
                    if (modelOptions.isEmpty()) {
                        Text("还没有保存任何模型。先到「模型配置」保存接入点，或添加已保存预设。", fontSize = 12.sp, color = Muted)
                    } else {
                        // 只暴露勾选的模型 —— 关闭时暴露全部（默认）
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("只暴露勾选的模型", fontSize = 13.sp, color = cs.onSurface)
                                Text(
                                    if (restrictModels) "外部只能看到已勾选的 ${pickedModels.count { id -> modelOptions.any { it.id == id } }} 个"
                                    else "外部可以看到全部 ${modelOptions.size} 个已保存模型",
                                    fontSize = 11.sp, color = Muted,
                                )
                            }
                            Switch(checked = restrictModels, onCheckedChange = { on ->
                                restrictModels = on
                                QuroExternalAccessPrefs.setExposedModels(
                                    ctx,
                                    if (on) pickedModels else emptySet(),
                                )
                            })
                        }
                        Spacer(Modifier.height(4.dp))
                        modelOptions.forEach { opt ->
                            val checked = !restrictModels || opt.id in pickedModels
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 1.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(
                                    checked = checked,
                                    enabled = restrictModels,
                                    onCheckedChange = { on ->
                                        val next = pickedModels.toMutableSet()
                                        if (on) next.add(opt.id) else next.remove(opt.id)
                                        pickedModels = next
                                        QuroExternalAccessPrefs.setExposedModels(ctx, next)
                                    },
                                )
                                Column(Modifier.weight(1f)) {
                                    Text(opt.id, fontSize = 12.sp, color = cs.onSurface)
                                    Text(
                                        "${opt.label} · ${opt.source} · ${opt.host}",
                                        fontSize = 10.sp, color = Muted,
                                    )
                                }
                            }
                        }
                        if (restrictModels) {
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                TextButton(onClick = {
                                    pickedModels = modelOptions.map { it.id }.toSet()
                                    QuroExternalAccessPrefs.setExposedModels(ctx, pickedModels)
                                }) { Text("全选", fontSize = 12.sp) }
                                TextButton(onClick = {
                                    pickedModels = emptySet()
                                    QuroExternalAccessPrefs.setExposedModels(ctx, emptySet())
                                }) { Text("清空", fontSize = 12.sp) }
                            }
                        }
                    }
                }
            }

            // ── 已暴露模型卡：/v1/models 实际返回的列表 ──
            item {
                Column(
                    Modifier.fillMaxWidth().background(Card, RoundedCornerShape(14.dp)).padding(14.dp),
                ) {
                    Text("已暴露模型（/v1/models）", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurface)
                    Spacer(Modifier.height(6.dp))
                    if (exposed.isEmpty()) {
                        Text("还没有可用模型。先到「模型配置」保存接入点，或添加已保存预设。", fontSize = 12.sp, color = Muted)
                    } else {
                        exposed.forEach { (id, source) ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(id, fontSize = 12.sp, color = cs.onSurface, modifier = Modifier.weight(1f))
                                Text(source, fontSize = 11.sp, color = Muted)
                            }
                            HorizontalDivider(color = Line.copy(alpha = 0.3f), thickness = 0.5.dp)
                        }
                    }
                }
            }

            // ── 接入指引卡：外部客户端到底怎么填 ──
            item {
                Column(
                    Modifier.fillMaxWidth().background(Card, RoundedCornerShape(14.dp)).padding(14.dp),
                ) {
                    Text("接入指引", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurface)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "在外部客户端（ChatBox / NextChat / Cherry Studio / 脚本）里按 OpenAI 兼容方式填：",
                        fontSize = 12.sp, color = Muted,
                    )
                    Spacer(Modifier.height(8.dp))
                    GuideRow("接口类型", "OpenAI 兼容（Chat Completions）")
                    GuideRow("Base URL", baseUrl)
                    GuideRow("API Key", if (keys.isEmpty()) "尚未生成密钥 —— 先在下方生成一个" else "已生成 ${keys.size} 个（下方可复制）")
                    GuideRow("模型名", exposed.firstOrNull()?.first ?: "（先在「模型配置」保存接入点）")
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "填好后点「测试」：能拉到模型列表即接通。若客户端报连接失败，先确认手机与电脑在同一 Wi-Fi、" +
                            "并用上面的局域网地址（不是 127.0.0.1）。",
                        fontSize = 11.sp, color = Muted,
                    )
                }
            }

            // ── 密钥管理 ──
            item {
                Column(
                    Modifier.fillMaxWidth().background(Card, RoundedCornerShape(14.dp)).padding(14.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Key, null, tint = cs.primary, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("API 密钥", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurface)
                        Spacer(Modifier.weight(1f))
                        Text("${keys.size} 个", fontSize = 12.sp, color = Muted)
                    }
                    Spacer(Modifier.height(8.dp))
                    if (showNewKey) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = newKeyName,
                                onValueChange = { newKeyName = it },
                                placeholder = { Text("密钥名称（如：桌面端）", fontSize = 12.sp) },
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                            )
                            Spacer(Modifier.width(8.dp))
                            Button(onClick = {
                                val list = QuroExternalAccessPrefs.createKey(ctx, newKeyName)
                                keys = list
                                freshlyCreated = list.last()
                                newKeyName = ""
                                showNewKey = false
                            }) { Text("生成", fontSize = 13.sp) }
                        }
                    } else {
                        OutlinedButton(onClick = { showNewKey = true }, modifier = Modifier.fillMaxWidth()) {
                            Text("+ 生成新密钥", fontSize = 13.sp)
                        }
                    }

                    // 新生成密钥一次性明文展示（此后只显示尾四位）
                    freshlyCreated?.let { fk ->
                        Spacer(Modifier.height(10.dp))
                        Column(
                            Modifier.fillMaxWidth().background(cs.primary.copy(alpha = 0.08f), RoundedCornerShape(10.dp)).padding(10.dp),
                        ) {
                            Text("「${fk.name}」已生成，请立即复制（仅此一次完整展示）：", fontSize = 11.sp, color = Muted)
                            Spacer(Modifier.height(4.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(fk.key, fontSize = 11.sp, color = cs.primary, modifier = Modifier.weight(1f))
                                IconButton(onClick = {
                                    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    cm.setPrimaryClip(ClipData.newPlainText("API Key", fk.key))
                                    Toast.makeText(ctx, qstr(R.string.qk_02065), Toast.LENGTH_SHORT).show()
                                }) { Icon(Icons.Filled.ContentCopy, null, tint = cs.primary, modifier = Modifier.size(16.dp)) }
                            }
                        }
                    }

                    Spacer(Modifier.height(8.dp))
                    keys.forEach { k ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(k.name, fontSize = 13.sp, color = cs.onSurface, fontWeight = FontWeight.Medium)
                                Text(
                                    "sk-…" + k.key.takeLast(4) + " · " +
                                        if (k.lastUsedAt > 0) "最近使用 ${timeFmt.format(Date(k.lastUsedAt))} · ${k.callCount} 次"
                                        else "从未使用",
                                    fontSize = 11.sp, color = Muted,
                                )
                            }
                            IconButton(onClick = {
                                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                cm.setPrimaryClip(ClipData.newPlainText("API Key", k.key))
                                Toast.makeText(ctx, qstr(R.string.qk_02065), Toast.LENGTH_SHORT).show()
                            }) { Icon(Icons.Filled.ContentCopy, null, tint = Muted, modifier = Modifier.size(16.dp)) }
                            IconButton(onClick = {
                                keys = QuroExternalAccessPrefs.revokeKey(ctx, k.id)
                                recents = QuroExternalAccessPrefs.recentCalls(ctx)
                            }) { Icon(Icons.Filled.Delete, "吊销", tint = Color(0xFFEF4444), modifier = Modifier.size(16.dp)) }
                        }
                        HorizontalDivider(color = Line.copy(alpha = 0.4f), thickness = 0.5.dp)
                    }
                    if (keys.isEmpty()) {
                        Text("还没有密钥。生成一个才能让外部工具接入。", fontSize = 12.sp, color = Muted)
                    }
                }
            }

            // ── 最近使用 ──
            item {
                Column(
                    Modifier.fillMaxWidth().background(Card, RoundedCornerShape(14.dp)).padding(14.dp),
                ) {
                    Text("最近使用", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurface)
                    Spacer(Modifier.height(6.dp))
                    val keyNameOf = { id: String -> keys.firstOrNull { it.id == id }?.name ?: "已吊销密钥" }
                    if (recents.isEmpty()) {
                        Text("暂无调用记录。", fontSize = 12.sp, color = Muted)
                    } else {
                        recents.take(20).forEach { r ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                                Column(Modifier.weight(1f)) {
                                    Text("${keyNameOf(r.keyId)} · ${r.model}", fontSize = 12.sp, color = cs.onSurface)
                                    Text(
                                        "${timeFmt.format(Date(r.at))} · 输入 ${r.promptTokens} tok · 输出 ${r.completionTokens} tok",
                                        fontSize = 11.sp, color = Muted,
                                    )
                                }
                            }
                            HorizontalDivider(color = Line.copy(alpha = 0.3f), thickness = 0.5.dp)
                        }
                    }
                }
            }

            item { Spacer(Modifier.height(20.dp)) }
        }
    }
}

/** 接入指引的一行「字段名 → 值」。 */
@Composable
private fun GuideRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.Top) {
        Text(label, fontSize = 11.sp, color = Muted, modifier = Modifier.width(72.dp))
        Text(value, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Medium)
    }
}
