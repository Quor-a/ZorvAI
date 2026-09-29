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
            clientServers.forEach { srv ->
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
                            Text(srv.url, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TextButton(onClick = {
                            scope.launch {
                                val n = withContext(Dispatchers.IO) {
                                    runCatching { QuroMcpClient.listTools(srv).size }.getOrNull()
                                }
                                Toast.makeText(ctx, if (n != null) qstr(R.string.qk_02076, (n).toString()) else qstr(R.string.qk_02077), Toast.LENGTH_SHORT).show()
                            }
                        }) { Text(qstr(R.string.qk_02078)) }
                        TextButton(onClick = {
                            QuroMcpClientPrefs.remove(ctx, srv.alias)
                            clientServers.remove(srv)
                        }) { Text(qstr(R.string.qk_00091), color = MaterialTheme.colorScheme.error) }
                    }
                }
            }

            Text(stringResource(R.string.qk_02079, (clientServers.size).toString()),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            var newAlias by remember { mutableStateOf("") }
            var newUrl by remember { mutableStateOf("") }
            var newToken by remember { mutableStateOf("") }
            OutlinedTextField(newAlias, { newAlias = it }, label = { Text(stringResource(R.string.qk_02080)) }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(newUrl, { newUrl = it }, label = { Text(stringResource(R.string.qk_02081)) }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(newToken, { newToken = it }, label = { Text(stringResource(R.string.qk_02082)) }, modifier = Modifier.fillMaxWidth())
            Button(onClick = {
                val a = newAlias.trim(); val u = newUrl.trim()
                if (a.isEmpty() || u.isEmpty()) {
                    Toast.makeText(ctx, qstr(R.string.qk_02083), Toast.LENGTH_SHORT).show()
                } else {
                    val cfg = QuroMcpClient.McpServerConfig(a, u, newToken.trim())
                    QuroMcpClientPrefs.add(ctx, cfg)
                    if (clientServers.none { it.alias == a }) clientServers.add(cfg)
                    newAlias = ""; newUrl = ""; newToken = ""
                    Toast.makeText(ctx, qstr(R.string.qk_02084, (a).toString()), Toast.LENGTH_SHORT).show()
                }
            }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.qk_02085)) }

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
    }
}