package com.ai.assistance.quro.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.assistance.quro.R
import com.ai.assistance.quro.core.services.QuroService
import com.ai.assistance.quro.core.services.QuroServiceCatalog
import com.ai.assistance.quro.util.qstr
import com.ai.assistance.quro.ui.theme.Accent
import com.ai.assistance.quro.ui.theme.AccentSoft
import com.ai.assistance.quro.ui.theme.Card
import com.ai.assistance.quro.ui.theme.Line
import com.ai.assistance.quro.ui.theme.Muted

/**
 * 应用中心（与 MCP 并列的「应用」入口）。
 *
 * - 「已连接」区：Quro 本地工具（内置直连，无需配置）；
 * - 分类目录：多想应用 / 效率 / 内容 / 数据 / 设计 / 通信 / 自动化 / 支付；
 * - 每个服务一行：图标 + 名称 + 一句话说明，右侧连接开关；
 * - 底部「搜索可用服务」过滤框。
 *
 * 「连接」= 把服务写入 AI 的已连接服务目录（system prompt 注入），
 * AI 随后会用 web_search / read_url 查询其公开文档域。见 [QuroServiceCatalog]。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuroAppCenterScreen(
    onClose: () -> Unit,
    onLaunch: (String) -> Unit = {},
) {
    val cs = MaterialTheme.colorScheme
    val ctx = LocalContext.current
    var enabled by remember { mutableStateOf(QuroServiceCatalog.enabledIds(ctx)) }
    var query by remember { mutableStateOf("") }
    // 🔴 needsApiKey 服务连接时先弹 Key 输入框（2026-11-04）
    var pendingKeySvc by remember { mutableStateOf<com.ai.assistance.quro.core.services.QuroService?>(null) }
    // 🔴 OAuth 双态：phase1=发现端点+开浏览器；phase2=粘回调 URL 兑换 token
    var oauthPending by remember { mutableStateOf<com.ai.assistance.quro.core.services.QuroService?>(null) }
    var oauthUrls by remember { mutableStateOf<com.ai.assistance.quro.core.mcp.QuroMcpOAuth.OAuthUrls?>(null) }
    var oauthCallback by remember { mutableStateOf("") }
    var oauthBusy by remember { mutableStateOf(false) }
    val oauthScope = rememberCoroutineScope()

    Scaffold(
        containerColor = cs.background,
        topBar = {
            TopAppBar(
                title = { Text(qstr(R.string.qk_04549), fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    Icon(
                        Icons.Filled.ArrowBack, qstr(R.string.qk_00011),
                        Modifier.size(24.dp).clickable { onClose() }.padding(4.dp),
                        tint = cs.onSurface,
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = cs.background),
            )
        },
        bottomBar = {
            androidx.compose.foundation.layout.Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp)
                    .clip(RoundedCornerShape(28.dp))
                    .background(Card)
                    .border(1.dp, Line, RoundedCornerShape(28.dp))
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Search, null, Modifier.size(18.dp), tint = Muted)
                Spacer(Modifier.width(8.dp))
                androidx.compose.material3.OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text(qstr(R.string.qk_04712), fontSize = 13.sp, color = Muted) },
                    singleLine = true,
                    colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color.Transparent,
                        unfocusedBorderColor = Color.Transparent,
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
    ) { pad ->
        val q = query.trim().lowercase()
        val visibleCats = QuroServiceCatalog.categories.mapNotNull { (cat, list) ->
            val filtered = if (q.isBlank()) list
            else list.filter { it.name.lowercase().contains(q) || it.desc.lowercase().contains(q) }
            if (filtered.isEmpty()) null else cat to filtered
        }

        LazyColumn(
            Modifier.fillMaxSize().padding(pad).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // ── 已连接：Quro 本地工具（内置直连）──
            item {
                Text(
                    qstr(R.string.qk_04713),
                    fontSize = 12.sp, color = Muted,
                    modifier = Modifier.padding(top = 12.dp, bottom = 2.dp),
                )
            }
            item {
                androidx.compose.material3.Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = Card,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier.size(40.dp).background(AccentSoft, RoundedCornerShape(10.dp)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Icons.Filled.Terminal, null, Modifier.size(22.dp), tint = Accent)
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                qstr(R.string.qk_04714),
                                fontSize = 15.sp, fontWeight = FontWeight.Medium, color = cs.onSurface,
                            )
                            Text(
                                qstr(R.string.qk_04715),
                                fontSize = 11.sp, color = Muted, lineHeight = 15.sp,
                            )
                        }
                        Text(
                            qstr(R.string.qk_04716),
                            fontSize = 12.sp, color = Accent, fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }

            // ── 分类目录 ──
            visibleCats.forEach { (cat, list) ->
                item(key = "cap_$cat") {
                    Text(
                        cat,
                        fontSize = 12.sp, color = Muted,
                        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
                    )
                }
                items(list.size, key = { i -> list[i].id }) { i ->
                    val svc = list[i]
                    ServiceRow(
                        svc = svc,
                        connected = enabled.contains(svc.id),
                        onToggle = { on ->
                            // 🔴 一键连接第三方 MCP（2026-11-04）：needsApiKey 的服务先弹 Key 输入框再连接。
                            when {
                                on && svc.oauth -> {
                                    oauthBusy = true
                                    oauthScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                        runCatching {
                                            val urls = com.ai.assistance.quro.core.mcp.QuroMcpOAuth.startOAuth(ctx, svc.id, svc.mcpUrl)
                                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                                oauthUrls = urls; oauthPending = svc; oauthBusy = false
                                                ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(urls.authorizeUrl)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                                            }
                                        }.onFailure { e ->
                                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                                oauthBusy = false
                                                android.widget.Toast.makeText(ctx, "授权失败：" + e.message, android.widget.Toast.LENGTH_LONG).show()
                                            }
                                        }
                                    }
                                }
                                on && svc.needsApiKey -> pendingKeySvc = svc
                                else -> { connectCatalogService(ctx, svc, on); enabled = QuroServiceCatalog.enabledIds(ctx) }
                            }
                        },
                    )
                }
            }
            item { Spacer(Modifier.height(8.dp)) }
        }
    }

    // 🔴 needsApiKey 服务：连接前弹 Key 输入框（Key 以 Bearer 存入该 MCP 配置）
    pendingKeySvc?.let { svc ->
        McpApiKeyDialog(
            svc = svc,
            onConnect = { token ->
                connectCatalogService(ctx, svc, true, token)
                enabled = QuroServiceCatalog.enabledIds(ctx)
                pendingKeySvc = null
            },
            onDismiss = { pendingKeySvc = null },
        )
    }

    // 🔴 OAuth 第二阶段：浏览器授权后粘贴回调 URL → 兑换 token → 注册 MCP
    oauthPending?.let { svc ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { if (!oauthBusy) { oauthPending = null; oauthUrls = null } },
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text("授权 " + svc.name, fontSize = 17.sp, fontWeight = FontWeight.SemiBold) },
            text = {
                Column {
                    Text("浏览器登录成功后，即使页面报错也请复制地址栏完整 URL（以 localhost:41893 开头）粘贴到下面：", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = oauthCallback,
                        onValueChange = { oauthCallback = it },
                        placeholder = { Text("http://localhost:41893/callback?code=…", fontSize = 13.sp) },
                        singleLine = true,
                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !oauthBusy && oauthCallback.isNotBlank(),
                    onClick = {
                        oauthBusy = true
                        oauthScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                            runCatching {
                                val access = com.ai.assistance.quro.core.mcp.QuroMcpOAuth.completeOAuth(ctx, oauthCallback)
                                com.ai.assistance.quro.core.mcp.QuroMcpClientPrefs.add(ctx,
                                    com.ai.assistance.quro.core.mcp.QuroMcpClient.McpServerConfig(
                                        alias = svc.id, url = svc.mcpUrl, token = access,
                                        kind = "remote", handshake = true,
                                    ))
                                com.ai.assistance.quro.core.services.QuroServiceCatalog.setEnabled(ctx, svc.id, true)
                            }.fold({
                                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                    oauthBusy = false; oauthPending = null; oauthUrls = null; oauthCallback = ""
                                    enabled = QuroServiceCatalog.enabledIds(ctx)
                                    android.widget.Toast.makeText(ctx, svc.name + " 已授权连接", android.widget.Toast.LENGTH_SHORT).show()
                                }
                            }, { e ->
                                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                    oauthBusy = false
                                    android.widget.Toast.makeText(ctx, "兑换失败：" + e.message, android.widget.Toast.LENGTH_LONG).show()
                                }
                            })
                        }
                    },
                ) { Text(if (oauthBusy) "兑换中…" else "完成授权") }
            },
            dismissButton = { TextButton(onClick = { oauthPending = null; oauthUrls = null }) { Text("取消") } },
        )
    }
}

/** 单行服务：首字图标 + 名称 + 说明 + 连接开关。 */
@Composable
private fun ServiceRow(svc: QuroService, connected: Boolean, onToggle: (Boolean) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val ctx = LocalContext.current
    //    开关打开时实际 ping 一次 MCP 端点，真实显示「已连接/连接失败」。
    var pingState by remember(svc.id, connected) { mutableStateOf<Int?>(null) } // null=检测中
    if (connected && svc.mcpUrl.isNotBlank()) {
        LaunchedEffect(svc.id, connected) {
            pingState = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching {
                    val cfg = com.ai.assistance.quro.core.mcp.QuroMcpClientPrefs.load(ctx)
                        .firstOrNull { it.alias == svc.id }
                    when {
                        cfg == null -> -1
                        com.ai.assistance.quro.core.mcp.QuroMcpClient.ping(cfg) -> 1
                        else -> 0
                    }
                }.getOrDefault(0)
            }
        }
    }
    val statusLabel = when {
        svc.mcpUrl.isBlank() -> ""
        !connected -> "未连接"
        pingState == null -> "检测中…"
        pingState == 1 -> "已连接"
        else -> "连接失败"
    }
    val statusColor = when {
        svc.mcpUrl.isBlank() -> Muted
        !connected -> Muted
        pingState == null -> Muted
        pingState == 1 -> Color(0xFF18B89A)
        else -> Color(0xFFEF4444)
    }
    androidx.compose.material3.Surface(
        shape = RoundedCornerShape(14.dp),
        color = Card,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable { onToggle(!connected) },
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(40.dp).background(AccentSoft, RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    svc.name.take(1),
                    fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Accent,
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(svc.name, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = cs.onSurface)
                Text(
                    svc.desc,
                    fontSize = 11.sp, color = Muted, lineHeight = 15.sp,
                    maxLines = 2,
                )
                // 🔴 真实连接状态（ping 实测）
                if (statusLabel.isNotEmpty()) {
                    Text(
                        statusLabel,
                        fontSize = 10.sp, color = statusColor, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Switch(
                checked = connected,
                onCheckedChange = onToggle,
                colors = SwitchDefaults.colors(checkedTrackColor = Accent),
            )
        }
    }
}

/**
 * 应用目录（可嵌入版）：供「MCP 与应用」页在自身滚动列内直接复用。
 * 与独立页 [QuroAppCenterScreen] 同一数据源（[QuroServiceCatalog]），
 * 连接状态写入同一份 prefs —— 两处入口看到的状态永远一致。
 */
@Composable
fun QuroServiceCatalogSection() {
    val cs = MaterialTheme.colorScheme
    val ctx = LocalContext.current
    var enabled by remember { mutableStateOf(QuroServiceCatalog.enabledIds(ctx)) }
    // 🔴 needsApiKey 服务连接时先弹 Key 输入框（2026-11-04）
    var pendingKeySvc by remember { mutableStateOf<com.ai.assistance.quro.core.services.QuroService?>(null) }

    Text(
        qstr(R.string.qk_04549),
        fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurface,
        modifier = Modifier.padding(top = 8.dp),
    )
    Text(
        qstr(R.string.qk_04715),
        fontSize = 11.sp, color = Muted,
        modifier = Modifier.padding(top = 2.dp, bottom = 6.dp),
    )

    // 已连接：Quro 本地工具（内置直连）
    androidx.compose.material3.Surface(shape = RoundedCornerShape(14.dp), color = Card, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(38.dp).background(AccentSoft, RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Terminal, null, Modifier.size(20.dp), tint = Accent)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(qstr(R.string.qk_04714), fontSize = 14.sp, fontWeight = FontWeight.Medium, color = cs.onSurface)
                Text(qstr(R.string.qk_04715), fontSize = 11.sp, color = Muted, maxLines = 1)
            }
            Text(qstr(R.string.qk_04716), fontSize = 12.sp, color = Accent, fontWeight = FontWeight.SemiBold)
        }
    }

    QuroServiceCatalog.categories.forEach { (cat, list) ->
        Text(
            cat,
            fontSize = 12.sp, color = Muted,
            modifier = Modifier.padding(top = 10.dp, bottom = 2.dp),
        )
        list.forEach { svc ->
            val connected = enabled.contains(svc.id)
            androidx.compose.material3.Surface(shape = RoundedCornerShape(14.dp), color = Card, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
                Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(34.dp).background(AccentSoft, RoundedCornerShape(9.dp)), contentAlignment = Alignment.Center) {
                        Text(svc.name.take(1), fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Accent)
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(svc.name, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = cs.onSurface)
                        Text(svc.desc, fontSize = 10.sp, color = Muted, maxLines = 1)
                    }
                    Spacer(Modifier.width(6.dp))
                    Switch(
                        checked = connected,
                        onCheckedChange = { on ->
                            // 🔴 一键连接第三方 MCP：needsApiKey 先弹 Key 输入框
                            if (on && svc.needsApiKey) {
                                pendingKeySvc = svc
                            } else {
                                connectCatalogService(ctx, svc, on)
                                enabled = QuroServiceCatalog.enabledIds(ctx)
                            }
                        },
                        colors = SwitchDefaults.colors(checkedTrackColor = Accent),
                    )
                }
            }
        }
    }

    // 🔴 needsApiKey 服务：连接前弹 Key 输入框
    pendingKeySvc?.let { svc ->
        McpApiKeyDialog(
            svc = svc,
            onConnect = { token ->
                connectCatalogService(ctx, svc, true, token)
                enabled = QuroServiceCatalog.enabledIds(ctx)
                pendingKeySvc = null
            },
            onDismiss = { pendingKeySvc = null },
        )
    }
}

/**
 * 🔴 第三方 MCP 连接统一动作（2026-11-04）：
 * 「连接」开关 → ① 写目录开关；② 带 mcpUrl 的条目同时注册为真实 MCP 服务器
 * （QuroMcpClientPrefs，mcp_list_tools / mcp_call 即刻可调）；③ needsApiKey 的
 * 服务由调用方先弹 Key 输入框再以 token 注册。断开 = 移除 MCP 注册。
 */
internal fun connectCatalogService(
    ctx: android.content.Context,
    svc: com.ai.assistance.quro.core.services.QuroService,
    on: Boolean,
    token: String = "",
) {
    com.ai.assistance.quro.core.services.QuroServiceCatalog.setEnabled(ctx, svc.id, on)
    if (svc.mcpUrl.isNotBlank()) {
        val next = com.ai.assistance.quro.core.mcp.QuroMcpClientPrefs.load(ctx)
            .filterNot { it.alias == svc.id }
            .let { base ->
                if (on) base + com.ai.assistance.quro.core.mcp.QuroMcpClient.McpServerConfig(
                    alias = svc.id,
                    url = svc.mcpUrl,
                    token = token,
                    kind = "remote",
                    handshake = true,
                ) else base
            }
        com.ai.assistance.quro.core.mcp.QuroMcpClientPrefs.save(ctx, next)
    }
}

/**
 * API Key 输入弹窗：needsApiKey 的服务在连接时弹出，Key 以 Bearer 存入该 MCP 配置。
 */
@Composable
private fun McpApiKeyDialog(
    svc: com.ai.assistance.quro.core.services.QuroService,
    onConnect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var key by remember { mutableStateOf("") }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text("连接 ${svc.name}", fontSize = 17.sp, fontWeight = FontWeight.SemiBold) },
        text = {
            Column {
                Text(
                    "该服务需要 API Key（Bearer）。到 ${svc.docDomain.ifBlank { "其官网" }} 创建 Key 后粘贴：",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it },
                    placeholder = { Text("粘贴 API Key", fontSize = 13.sp) },
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConnect(key.trim()) },
                enabled = key.isNotBlank(),
            ) { Text("连接") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
