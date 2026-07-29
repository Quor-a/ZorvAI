package com.ai.assistance.quro.ui

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
                title = { Text("MCP 服务") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "返回") } },
            )
        }
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "把 Zorv AI 的 $toolCount 个内置工具以 MCP（Model Context Protocol）协议暴露给本机其它 AI 客户端" +
                        "（Claude Desktop / Cursor / MCP Inspector 等）。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("启用本地 MCP Server", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        if (enabled) "正在监听 127.0.0.1:$port" else "关闭（不监听任何端口）",
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

            Text("连接地址", style = MaterialTheme.typography.titleSmall)
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
                    Toast.makeText(ctx, "已复制连接地址", Toast.LENGTH_SHORT).show()
                }) { Icon(Icons.Filled.ContentCopy, "复制", tint = MaterialTheme.colorScheme.primary) }
            }

            OutlinedTextField(
                value = "传输：JSON-RPC 2.0 over HTTP（单 POST + 普通 JSON 响应）\n" +
                        "监听：127.0.0.1（仅本机，外部不可达）\n" +
                        "方法：initialize / tools/list / tools/call\n" +
                        "工具：与 AI 对话内工具 100% 同源（共 $toolCount 个）",
                onValueChange = {},
                readOnly = true,
                label = { Text("技术详情") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 5,
            )

            Text(
                "客户端配置示例（Claude Desktop / Cursor 的 mcp.json）：\n" +
                        "{ \"mcpServers\": { \"quro\": { \"url\": \"$endpoint\" } } }",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(8.dp))
            Text(
                "安全说明：服务只绑定环回地址，任何外部网络请求均无法抵达。工具调用复用应用内同一套" +
                        "权限与确认机制；涉及敏感操作（如安装/卸载应用、ROOT 命令）仍受系统权限与运行时确认约束。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            HorizontalDivider()

            Text("MCP 客户端（连接外部服务器）", style = MaterialTheme.typography.titleSmall)
            Text(
                "在此添加外部 MCP 服务器（如其它 AI 客户端、云端工具网关），添加后 AI 即可通过 mcp_call 调用其暴露的工具。",
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
                                Toast.makeText(ctx, if (n != null) "连接成功，发现 $n 个工具" else "连接失败", Toast.LENGTH_SHORT).show()
                            }
                        }) { Text("测试") }
                        TextButton(onClick = {
                            QuroMcpClientPrefs.remove(ctx, srv.alias)
                            clientServers.remove(srv)
                        }) { Text("删除", color = MaterialTheme.colorScheme.error) }
                    }
                }
            }

            Text(
                "已配置 ${clientServers.size} 个外部服务器（可继续添加，AI 通过 mcp_call 按别名调用）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            var newAlias by remember { mutableStateOf("") }
            var newUrl by remember { mutableStateOf("") }
            var newToken by remember { mutableStateOf("") }
            OutlinedTextField(newAlias, { newAlias = it }, label = { Text("别名") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(newUrl, { newUrl = it }, label = { Text("服务器地址 (http(s)://host/path)") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(newToken, { newToken = it }, label = { Text("Token（可选，Bearer）") }, modifier = Modifier.fillMaxWidth())
            Button(onClick = {
                val a = newAlias.trim(); val u = newUrl.trim()
                if (a.isEmpty() || u.isEmpty()) {
                    Toast.makeText(ctx, "别名与地址必填", Toast.LENGTH_SHORT).show()
                } else {
                    val cfg = QuroMcpClient.McpServerConfig(a, u, newToken.trim())
                    QuroMcpClientPrefs.add(ctx, cfg)
                    if (clientServers.none { it.alias == a }) clientServers.add(cfg)
                    newAlias = ""; newUrl = ""; newToken = ""
                    Toast.makeText(ctx, "已添加服务器：$a", Toast.LENGTH_SHORT).show()
                }
            }, modifier = Modifier.fillMaxWidth()) { Text("保存并继续添加") }

            HorizontalDivider()

            // ════════════ 本地 MCP（AI 部署）═══════════
            Text("本地 MCP（AI 部署）", style = MaterialTheme.typography.titleSmall)
            Text(
                "由 AI 通过 mcp_deploy 创作并部署到本应用内的 MCP 服务器。部署后自动在本机启动端点，" +
                        "可在对话中用 mcp_call 按别名调用，下方列出当前已部署的实例。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val localServers = remember {
                mutableStateListOf<QuroMcpClient.McpServerConfig>().apply {
                    addAll(QuroMcpClientPrefs.loadLocal(ctx))
                }
            }
            if (localServers.isEmpty()) {
                Text(
                    "暂无本地 MCP。在对话中让 AI 使用 mcp_deploy 提交工具定义即可部署。",
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
                            Text("${srv.url} · $n 个工具", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TextButton(onClick = {
                            QuroLocalMcpManager.undeploy(ctx, srv.alias)
                            localServers.remove(srv)
                            Toast.makeText(ctx, "已注销：${srv.alias}", Toast.LENGTH_SHORT).show()
                        }) { Text("注销", color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
        }
    }
}
