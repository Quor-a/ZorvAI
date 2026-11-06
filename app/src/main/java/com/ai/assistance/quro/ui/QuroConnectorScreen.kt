package com.ai.assistance.quro.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.assistance.quro.core.connector.QuroConnector
import com.ai.assistance.quro.core.connector.QuroConnectorCatalog
import com.ai.assistance.quro.core.connector.SignInType
import com.ai.assistance.quro.core.mcp.QuroMcpClient
import com.ai.assistance.quro.core.mcp.QuroMcpClientPrefs
import com.ai.assistance.quro.core.mcp.QuroMcpOAuth
import com.ai.assistance.quro.ui.theme.Accent
import com.ai.assistance.quro.ui.theme.AccentSoft
import com.ai.assistance.quro.ui.theme.Card
import com.ai.assistance.quro.ui.theme.Line
import com.ai.assistance.quro.ui.theme.Muted
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast

/**
 * 连接器页（2026-11-06 新增功能）：官方 MCP 服务商店。
 *
 * - 分类浏览：文档与知识 / 效率 / 代码 / 数据 / 设计 / 通信 / 自动化 / 支付 / 生活与出行；
 * - 每个连接器一行：首字图标 + 名称 + 说明 + 授权徽标（OAuth / API Key / 免登录）；
 * - 「连接」动作：OAuth → QuroMcpOAuth 浏览器授权；API Key → 弹 Key 输入框；
 *   免登录 → 直接注册。成功后写入 QuroMcpClientPrefs（真实 MCP 服务器），
 *   AI 经 mcp_servers / mcp_list_tools / mcp_call 即刻可用；
 * - 与「设置 → MCP 服务」分体共享：连接器连上的服务在那边可见、可停用、可删；
 * - 顶部搜索框按名称/说明过滤。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuroConnectorScreen(
    onClose: () -> Unit,
    onOpenMcpSettings: () -> Unit = {},
) {
    val cs = MaterialTheme.colorScheme
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var connected by remember { mutableStateOf(QuroConnectorCatalog.connectedIds(ctx)) }
    var query by remember { mutableStateOf("") }
    var busyId by remember { mutableStateOf<String?>(null) }

    // API Key 输入弹窗目标
    var pendingKeyConnector by remember { mutableStateOf<QuroConnector?>(null) }
    // OAuth 第二阶段弹窗
    var oauthPending by remember { mutableStateOf<QuroConnector?>(null) }
    var oauthCallback by remember { mutableStateOf("") }
    var oauthBusy by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = cs.background,
        topBar = {
            TopAppBar(
                title = { Text("连接器", fontSize = 18.sp, fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Filled.Close, "关闭", tint = cs.onSurface)
                    }
                },
                actions = {
                    // 已连接计数徽标 → 点进去可到 MCP 设置页管理
                    TextButton(onClick = onOpenMcpSettings) {
                        Text("管理（${connected.size}）", fontSize = 12.sp, color = Accent)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = cs.background),
            )
        },
    ) { pad ->
        val q = query.trim().lowercase()
        Column(Modifier.fillMaxSize().padding(pad)) {

            // 搜索框（与应用中心同款胶囊）
            Row(
                Modifier
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .clip(RoundedCornerShape(28.dp))
                    .background(Card)
                    .border(1.dp, Line, RoundedCornerShape(28.dp))
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Search, null, Modifier.size(18.dp), tint = Muted)
                Spacer(Modifier.width(8.dp))
                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    textStyle = TextStyle(fontSize = 14.sp, color = cs.onSurface),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(Accent),
                    decorationBox = { inner ->
                        Box {
                            if (query.isEmpty()) {
                                Text("搜索连接器…", fontSize = 14.sp, color = Muted)
                            }
                            inner()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // 顶部说明
                item {
                    Text(
                        "官方 MCP 服务目录：连接后 AI 可直接调用该服务的真实工具。" +
                            "已连接的服务可在「管理」里停用或删除。",
                        fontSize = 11.sp, color = Muted, lineHeight = 16.sp,
                    )
                }

                // 按分类渲染（搜索时只显示有命中的分类）
                QuroConnectorCatalog.categories.forEach { cat ->
                    val list = QuroConnectorCatalog.inCategory(cat.id)
                        .filter { c ->
                            q.isBlank() || c.name.lowercase().contains(q) ||
                                c.desc.lowercase().contains(q)
                        }
                    if (list.isEmpty()) return@forEach

                    item(key = "cat_${cat.id}") {
                        Text(
                            cat.label,
                            fontSize = 12.sp, color = Muted,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                    items(list.size, key = { i -> list[i].id }) { i ->
                        val c = list[i]
                        ConnectorRow(
                            connector = c,
                            connected = connected.contains(c.id),
                            busy = busyId == c.id,
                            onConnect = {
                                scope.launch {
                                    when (c.signIn) {
                                        SignInType.OAUTH -> {
                                            busyId = c.id
                                            runCatching {
                                                withContext(Dispatchers.IO) {
                                                    QuroMcpOAuth.startOAuth(ctx, c.id, c.mcpUrl)
                                                }
                                            }.onSuccess { urls ->
                                                oauthPending = c
                                                oauthBusy = false
                                                busyId = null
                                                runCatching {
                                                    ctx.startActivity(
                                                        Intent(Intent.ACTION_VIEW, Uri.parse(urls.authorizeUrl))
                                                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                                    )
                                                }.onFailure { e ->
                                                    Toast.makeText(ctx, "无法打开浏览器：" + e.message, Toast.LENGTH_LONG).show()
                                                }
                                            }.onFailure { e ->
                                                busyId = null
                                                Toast.makeText(ctx, "授权失败：" + e.message, Toast.LENGTH_LONG).show()
                                            }
                                        }
                                        SignInType.API_KEY -> pendingKeyConnector = c
                                        SignInType.NONE -> {
                                            busyId = c.id
                                            registerConnector(ctx, c, token = "")
                                            QuroConnectorCatalog.setConnected(ctx, c.id, true)
                                            connected = QuroConnectorCatalog.connectedIds(ctx)
                                            busyId = null
                                            Toast.makeText(ctx, c.name + " 已连接", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                }
                            },
                            onDisconnect = {
                                unregisterConnector(ctx, c.id)
                                QuroConnectorCatalog.setConnected(ctx, c.id, false)
                                connected = QuroConnectorCatalog.connectedIds(ctx)
                                Toast.makeText(ctx, c.name + " 已断开", Toast.LENGTH_SHORT).show()
                            },
                        )
                    }
                }

                item { Spacer(Modifier.height(20.dp)) }
            }
        }
    }

    // ── API Key 输入弹窗 ──
    pendingKeyConnector?.let { c ->
        var key by remember(c.id) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { pendingKeyConnector = null },
            containerColor = cs.surface,
            title = { Text("连接 ${c.name}", fontSize = 17.sp, fontWeight = FontWeight.SemiBold) },
            text = {
                Column {
                    Text(
                        if (c.docDomain.isNotBlank()) "该服务需要 API Key（Bearer）。到 ${c.docDomain} 创建 Key 后粘贴："
                        else "该服务需要 API Key（Bearer）。到其开放平台创建 Key 后粘贴：",
                        fontSize = 12.sp, color = cs.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = key,
                        onValueChange = { key = it },
                        placeholder = { Text("粘贴 API Key", fontSize = 13.sp) },
                        singleLine = true,
                        textStyle = TextStyle(fontSize = 13.sp),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = key.isNotBlank(),
                    onClick = {
                        scope.launch {
                            busyId = c.id
                            registerConnector(ctx, c, token = key.trim())
                            QuroConnectorCatalog.setConnected(ctx, c.id, true)
                            connected = QuroConnectorCatalog.connectedIds(ctx)
                            busyId = null
                            pendingKeyConnector = null
                            Toast.makeText(ctx, c.name + " 已连接", Toast.LENGTH_SHORT).show()
                        }
                    },
                ) { Text("连接") }
            },
            dismissButton = { TextButton(onClick = { pendingKeyConnector = null }) { Text("取消") } },
        )
    }

    // ── OAuth 第二阶段：浏览器授权后粘贴回调 URL → 兑换 token → 注册 ──
    oauthPending?.let { c ->
        AlertDialog(
            onDismissRequest = { if (!oauthBusy) { oauthPending = null; oauthCallback = "" } },
            containerColor = cs.surface,
            title = { Text("授权 " + c.name, fontSize = 17.sp, fontWeight = FontWeight.SemiBold) },
            text = {
                Column {
                    Text(
                        "浏览器登录成功后，即使页面报错也请复制地址栏完整 URL（以 localhost:41893 开头）粘贴到下面：",
                        fontSize = 12.sp, color = cs.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = oauthCallback,
                        onValueChange = { oauthCallback = it },
                        placeholder = { Text("http://localhost:41893/callback?code=…", fontSize = 13.sp) },
                        singleLine = true,
                        textStyle = TextStyle(fontSize = 13.sp),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !oauthBusy && oauthCallback.isNotBlank(),
                    onClick = {
                        oauthBusy = true
                        scope.launch(Dispatchers.IO) {
                            runCatching {
                                val access = QuroMcpOAuth.completeOAuth(ctx, oauthCallback)
                                registerConnector(ctx, c, token = access)
                                QuroConnectorCatalog.setConnected(ctx, c.id, true)
                            }.fold({
                                withContext(Dispatchers.Main) {
                                    oauthBusy = false; oauthPending = null; oauthCallback = ""
                                    connected = QuroConnectorCatalog.connectedIds(ctx)
                                    Toast.makeText(ctx, c.name + " 已授权连接", Toast.LENGTH_SHORT).show()
                                }
                            }, { e ->
                                withContext(Dispatchers.Main) {
                                    oauthBusy = false
                                    Toast.makeText(ctx, "兑换失败：" + e.message, Toast.LENGTH_LONG).show()
                                }
                            })
                        }
                    },
                ) { Text(if (oauthBusy) "兑换中…" else "完成授权") }
            },
            dismissButton = {
                TextButton(onClick = { oauthPending = null; oauthCallback = "" }) { Text("取消") }
            },
        )
    }
}

/** 单行连接器：首字图标 + 名称 + 说明 + 授权徽标 + 连接/断开按钮。 */
@Composable
private fun ConnectorRow(
    connector: QuroConnector,
    connected: Boolean,
    busy: Boolean,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = Card,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 🔴 官方品牌图标（2026-11-06）：已知品牌用官方底色+官方字形，未知回退首字
            BrandAvatar(
                name = connector.name,
                urlOrId = connector.id,
                size = 40.dp,
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        connector.name,
                        fontSize = 15.sp, fontWeight = FontWeight.Medium, color = cs.onSurface,
                    )
                    Spacer(Modifier.width(6.dp))
                    // 授权徽标
                    val badgeColor = when (connector.signIn) {
                        SignInType.OAUTH -> Color(0xFF8B5CF6)
                        SignInType.API_KEY -> Color(0xFFF59E0B)
                        SignInType.NONE -> Color(0xFF10B981)
                    }
                    Text(
                        connector.signInLabel,
                        fontSize = 9.sp,
                        color = badgeColor,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .border(1.dp, badgeColor.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
                            .padding(horizontal = 4.dp, vertical = 1.dp),
                    )
                }
                Text(
                    connector.desc,
                    fontSize = 11.sp, color = Muted, lineHeight = 15.sp,
                )
            }
            Spacer(Modifier.width(8.dp))
            if (busy) {
                Text("连接中…", fontSize = 12.sp, color = Accent)
            } else if (connected) {
                TextButton(onClick = onDisconnect) {
                    Text("断开", fontSize = 12.sp, color = cs.error)
                }
            } else {
                TextButton(onClick = onConnect) {
                    Text("连接", fontSize = 12.sp, color = Accent, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

/**
 * 注册连接器为真实 MCP 服务器（QuroMcpClientPrefs，与 MCP 设置页同一份存储）。
 * 已存在同 alias 时覆盖（断开重连 / 换 Key 场景）。
 */
internal fun registerConnector(ctx: Context, c: QuroConnector, token: String) {
    val next = QuroMcpClientPrefs.load(ctx)
        .filterNot { it.alias == c.id } + QuroMcpClient.McpServerConfig(
        alias = c.id,
        url = c.mcpUrl,
        token = token,
        kind = "remote",
        handshake = true,
    )
    QuroMcpClientPrefs.save(ctx, next)
}

/** 断开连接器：移除 MCP 注册（不动其他来源的手动配置）。 */
internal fun unregisterConnector(ctx: Context, connectorId: String) {
    val next = QuroMcpClientPrefs.load(ctx).filterNot { it.alias == connectorId }
    QuroMcpClientPrefs.save(ctx, next)
}
