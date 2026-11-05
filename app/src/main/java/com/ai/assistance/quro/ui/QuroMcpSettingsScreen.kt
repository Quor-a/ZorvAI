package com.ai.assistance.quro.ui
import androidx.compose.ui.res.stringResource
import com.ai.assistance.quro.R
import com.ai.assistance.quro.util.qstr

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.assistance.quro.core.mcp.QuroMcpClient
import com.ai.assistance.quro.core.mcp.QuroMcpClientPrefs
import com.ai.assistance.quro.core.mcp.QuroLocalMcpManager
import com.ai.assistance.quro.core.tools.buildQuroRegistry
import com.ai.assistance.quro.service.QuroMcpService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * MCP 服务设置页（v139 新增）：开关本地 MCP Server、展示本机连接地址与工具数。
 * 服务仅监听 127.0.0.1，外部网络不可达。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuroMcpSettingsScreen(onBack: () -> Unit = {}) {
    val ctx = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    var enabled by remember { mutableStateOf(QuroMcpService.isEnabled(ctx)) }
    var port by remember { mutableStateOf(QuroMcpService.getPort(ctx)) }
    val toolCount = remember { runCatching { buildQuroRegistry(ctx).all().size }.getOrDefault(0) }
    val endpoint = if (port > 0) "http://127.0.0.1:$port/mcp" else "—"

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.qk_02058)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = stringResource(R.string.qk_00143)) } },
            )
        }
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(R.string.qk_02059, (toolCount).toString()) + stringResource(R.string.qk_02060),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.qk_02061), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        if (enabled) stringResource(R.string.qk_02062, (port).toString()) else stringResource(R.string.qk_02063),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = enabled, onCheckedChange = {
                    enabled = it
                    if (it) {
                        QuroMcpService.start(ctx)
                        // 服务 onCreate 异步分配端口，稍后回读
                        scope.launch {
                            delay(500)
                            port = QuroMcpService.getPort(ctx)
                        }
                    } else {
                        QuroMcpService.stop(ctx)
                        port = 0
                    }
                })
            }

            HorizontalDivider()

            Text(stringResource(R.string.qk_02064), style = MaterialTheme.typography.titleSmall)
            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    endpoint,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = {
                    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("MCP Endpoint", endpoint))
                    Toast.makeText(ctx, qstr(R.string.qk_02065), Toast.LENGTH_SHORT).show()
                }) { Icon(Icons.Filled.ContentCopy, stringResource(R.string.qk_00088), tint = MaterialTheme.colorScheme.primary) }
            }

            OutlinedTextField(
                value = stringResource(R.string.qk_02066) +
                        stringResource(R.string.qk_02067) +
                        stringResource(R.string.qk_02068) + stringResource(R.string.qk_02069, (toolCount).toString()),
                onValueChange = {},
                readOnly = true,
                label = { Text(stringResource(R.string.qk_02070)) },
                modifier = Modifier.fillMaxWidth(),
                minLines = 5,
            )

            Text(
                stringResource(R.string.qk_02071) +
                        "{ \"mcpServers\": { \"quro\": { \"url\": \"$endpoint\" } } }",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.qk_02072) + stringResource(R.string.qk_02073),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            HorizontalDivider()

            Text(stringResource(R.string.qk_02074), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.qk_02075),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            val clientServers = remember {
                mutableStateListOf<QuroMcpClient.McpServerConfig>().apply {
                    addAll(QuroMcpClientPrefs.load(ctx))
                }
            }
            var editing by remember { mutableStateOf<QuroMcpClient.McpServerConfig?>(null) }
            clientServers.forEach { srv ->
                Card(
                    Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                ) {
                    Column(Modifier.fillMaxWidth().padding(12.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(srv.alias + if (srv.enabled) "" else " · 已停用", style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    srv.url + (if (srv.handshake) " · initialize" else "") +
                                        (if (srv.kind == "ws") " · WebSocket" else ""),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            // 单服务器启停：显示「已停用」状态，不再只能删了重加。
                            Switch(checked = srv.enabled, onCheckedChange = { on ->
                                val updated = srv.copy(enabled = on)
                                QuroMcpClientPrefs.add(ctx, updated)
                                val idx = clientServers.indexOfFirst { it.alias == srv.alias }
                                if (idx >= 0) clientServers[idx] = updated
                            })
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton(onClick = {
                                scope.launch {
                                    val info = withContext(Dispatchers.IO) {
                                        runCatching {
                                            val caps = QuroMcpClient.getServerCapabilities(srv)
                                            val names = caps.keys().asSequence().joinToString(",")
                                            val tools = QuroMcpClient.listTools(srv).size
                                            val res = QuroMcpClient.listResources(srv).size
                                            val prompts = QuroMcpClient.listPrompts(srv).size
                                            val pong = QuroMcpClient.ping(srv)
                                            "工具 $tools · 资源 $res · 提示词 $prompts · 能力[$names] · ping=${if (pong) "ok" else "无响应"}"
                                        }.getOrElse { "能力查询失败: ${it.message}" }
                                    }
                                    Toast.makeText(ctx, info, Toast.LENGTH_LONG).show()
                                }
                            }) { Text(qstr(R.string.qk_02078)) }
                            TextButton(onClick = { editing = srv }) { Text("编辑") }
                            TextButton(onClick = {
                                QuroMcpClientPrefs.remove(ctx, srv.alias)
                                clientServers.remove(srv)
                            }) { Text(qstr(R.string.qk_00091), color = MaterialTheme.colorScheme.error) }
                        }
                    }
                }
            }

            // 编辑已有服务器：URL / Token / 握手 / 传输（旧版只能删除后重加）。
            editing?.let { cur ->
                var eUrl by remember(cur.alias) { mutableStateOf(cur.url) }
                var eToken by remember(cur.alias) { mutableStateOf(cur.token) }
                var eHandshake by remember(cur.alias) { mutableStateOf(cur.handshake) }
                var eWs by remember(cur.alias) { mutableStateOf(cur.kind == "ws") }
                AlertDialog(
                    onDismissRequest = { editing = null },
                    title = { Text("编辑 ${cur.alias}") },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(eUrl, { eUrl = it }, label = { Text("端点 URL") }, singleLine = true)
                            OutlinedTextField(eToken, { eToken = it }, label = { Text("Token（可选）") }, singleLine = true)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Switch(checked = eHandshake, onCheckedChange = { eHandshake = it })
                                Spacer(Modifier.width(8.dp)); Text("启用 initialize 握手", fontSize = 13.sp)
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Switch(checked = eWs, onCheckedChange = { eWs = it })
                                Spacer(Modifier.width(8.dp)); Text("WebSocket 传输", fontSize = 13.sp)
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            val updated = cur.copy(
                                url = eUrl.trim(), token = eToken.trim(), handshake = eHandshake,
                                kind = if (eWs) "ws" else if (cur.kind == "local") "local" else "remote",
                            )
                            QuroMcpClientPrefs.add(ctx, updated)
                            val idx = clientServers.indexOfFirst { it.alias == cur.alias }
                            if (idx >= 0) clientServers[idx] = updated
                            editing = null
                        }) { Text("保存") }
                    },
                    dismissButton = { TextButton(onClick = { editing = null }) { Text("取消") } },
                )
            }

            Text(stringResource(R.string.qk_02079, (clientServers.size).toString()),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            var newAlias by remember { mutableStateOf("") }
            var newUrl by remember { mutableStateOf("") }
            var newToken by remember { mutableStateOf("") }
            var newHandshake by remember { mutableStateOf(false) }
            var newWs by remember { mutableStateOf(false) }
            OutlinedTextField(newAlias, { newAlias = it }, label = { Text(stringResource(R.string.qk_02080)) }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(newUrl, { newUrl = it }, label = { Text(stringResource(R.string.qk_02081)) }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(newToken, { newToken = it }, label = { Text(stringResource(R.string.qk_02082)) }, modifier = Modifier.fillMaxWidth())
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = newHandshake, onCheckedChange = { newHandshake = it })
                Spacer(Modifier.width(8.dp)); Text("启用 initialize 握手（严格型服务器）", fontSize = 13.sp)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = newWs, onCheckedChange = { newWs = it })
                Spacer(Modifier.width(8.dp)); Text("WebSocket 传输", fontSize = 13.sp)
            }
            Button(onClick = {
                val a = newAlias.trim(); val u = newUrl.trim()
                if (a.isEmpty() || u.isEmpty()) {
                    Toast.makeText(ctx, qstr(R.string.qk_02083), Toast.LENGTH_SHORT).show()
                } else {
                    val cfg = QuroMcpClient.McpServerConfig(
                        alias = a, url = u, token = newToken.trim(),
                        kind = if (newWs) "ws" else "remote", handshake = newHandshake,
                    )
                    QuroMcpClientPrefs.add(ctx, cfg)
                    if (clientServers.none { it.alias == a }) clientServers.add(cfg)
                    newAlias = ""; newUrl = ""; newToken = ""
                    Toast.makeText(ctx, qstr(R.string.qk_02084, (a).toString()), Toast.LENGTH_SHORT).show()
                }
            }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.qk_02085)) }

            // 从剪贴板导入标准 { "mcpServers": { ... } } 配置（Claude Desktop / Cursor / VS Code 通用）。
            TextButton(onClick = {
                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val raw = cm.primaryClip?.getItemAt(0)?.text?.toString().orEmpty()
                var added = 0
                runCatching {
                    val root = org.json.JSONObject(raw)
                    val servers = root.optJSONObject("mcpServers") ?: root
                    val keys = servers.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        val o = servers.optJSONObject(k) ?: continue
                        val url = o.optString("url", "").ifBlank { o.optString("serverUrl", "") }
                        if (url.isBlank()) continue
                        val token = o.optJSONObject("headers")?.optString("Authorization", "")
                            ?.removePrefix("Bearer ") ?: ""
                        val cfg = QuroMcpClient.McpServerConfig(
                            alias = k, url = url, token = token, kind = "remote", handshake = true,
                        )
                        QuroMcpClientPrefs.add(ctx, cfg)
                        if (clientServers.none { it.alias == k }) clientServers.add(cfg)
                        added++
                    }
                }
                Toast.makeText(
                    ctx,
                    if (added > 0) "已导入 $added 台 MCP 服务器" else "剪贴板里没有可识别的 mcpServers 配置",
                    Toast.LENGTH_SHORT,
                ).show()
            }, modifier = Modifier.fillMaxWidth()) { Text("从剪贴板导入 mcpServers 配置") }

            HorizontalDivider()

            // ════════════ 本地 MCP（AI 部署）═══════════
            Text(stringResource(R.string.qk_02086), style = MaterialTheme.typography.titleSmall)
            Text(
                stringResource(R.string.qk_02087) + stringResource(R.string.qk_02088),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val localServers = remember {
                mutableStateListOf<QuroMcpClient.McpServerConfig>().apply {
                    addAll(QuroMcpClientPrefs.loadLocal(ctx))
                }
            }
            if (localServers.isEmpty()) {
                Text(stringResource(R.string.qk_02089),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            localServers.forEach { srv ->
                val n = runCatching { org.json.JSONArray(srv.toolDefs).length() }.getOrDefault(0)
                Card(
                    Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(srv.alias, style = MaterialTheme.typography.bodyMedium)
                            Text(qstr(R.string.qk_02090, (srv.url).toString(), (n).toString()), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TextButton(onClick = {
                            QuroLocalMcpManager.undeploy(ctx, srv.alias)
                            localServers.remove(srv)
                            Toast.makeText(ctx, qstr(R.string.qk_02091, (srv.alias).toString()), Toast.LENGTH_SHORT).show()
                        }) { Text(qstr(R.string.qk_02092), color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
        }

        // ── 应用目录（应用与 MCP 同页，切换开关即把服务接入 AI 目录）──
        com.ai.assistance.quro.ui.QuroServiceCatalogSection()

        Spacer(Modifier.height(20.dp))
    }
}